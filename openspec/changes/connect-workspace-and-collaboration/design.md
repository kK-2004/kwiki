## Context

当前前端使用 Vue 3、Pinia、Vue Router 和 Naive UI，只有 `/:kbId?/:pageId?` 工作台路由。`WikiWorkspaceLayout.vue` 中新对话、智能体、共享空间及最近访问未接通；账号、面包屑和 `PageReader.vue` 有静态业务数据。`api.ts` 把 JSON 直接当业务对象，实际后端返回 kk-common `TransDTO {code,success,message,data}`，须先统一契约。

后端为 Java 21 / Spring Boot，已存在数据库用户名密码登录、BCrypt、JWT、Wiki 树/修订/发布、内容中心附件、文本解析和多轮 Agentic RAG SSE。当前 JWT 包含 username/uid/adm，过滤器使用令牌管理员标记；知识库角色为 OWNER/EDITOR/VIEWER，文档无独立 ACL。聊天请求只有 query，现有落库偏向结束时审计，不能直接支持可靠的历史续聊。

Flyway 位于 `src/main/resources/db/migration`，当前完整版本为 V1–V5；生产配置使用 MySQL 与 JPA validate。现有部分迁移测试会连接数据库并执行 clean，本次及后续默认离线验收不得运行这些测试。工作区已有未提交的 Agentic 编排改动，本提案与其 SSE、取消和权限复核契约对齐，不覆盖这些改动。

## Goals / Non-Goals

**Goals:**

- 完成用户提出的账号、全菜单、持久会话、上传成 Wiki、邀请协作、划词 AI/@、社交互动与 Redis 统计闭环。
- 生产界面只展示后端真实数据，空库、无权限、依赖不可用均有真实状态；测试 fixture 限于测试代码。
- 以统一资源授权服务约束所有数据出口；角色变更即时反映到后续请求，并中止持有旧范围的长任务。
- 将可验证的接口、数据结构、状态机、并发和离线验收要求交付给实施阶段。

**Non-Goals:**

- 本轮不实现业务代码、不执行数据库迁移，不连接或启动真实 MySQL。
- 不做图片/OCR/扫描 PDF/多模态导入、自定义智能体编排平台、邮件短信邀请发送、XXL-JOB 服务部署。
- “思考过程”定义为服务端显式生成、允许展示的执行解释和进度摘要，不直接公开模型隐藏推理、系统提示、原始工具参数或未授权证据。

## Decisions

### 1. 账号、JWT 与已确认的 5/7 天规则

沿用现有认证边界，补 `POST /auth/register`、`GET /auth/me` 和 `POST /auth/refresh`。注册只接受 username/displayName/email/password 等白名单字段；用户名标准化与现有 MySQL 唯一约束一致，冲突返回稳定错误，BCrypt 存储，禁止提交管理员字段提权。登录错误不区分账号不存在/禁用/密码错误。

JWT 的唯一业务身份是 `sub=String(userId)`，标准声明只有签名验证需要的 `iat/exp`，不得包含用户名、邮箱、角色、资源列表或 admin。后端验签和时间检查后按 ID 查有效 `app_user` 构造 CurrentUser，平台权限从数据库读取，资源角色由授权服务另查；数据库不可用时拒绝授权且返回依赖不可用，不能回退信任 JWT 权限。

设签发时刻 t0，有效期 7×24h；在 `t0 <= now <= t0+5d` 的合法前台已认证访问可续签为 `iat=now, exp=now+7d`；`t0+5d < now < t0+7d` 保持认证但不续签；`now >= exp` 为 401。所有边界用后端可注入 Clock，JWT 秒精度统一。显式密码登录始终建立新 7 天令牌。例：第 4 天访问续签到第 11 天；第 6 天首次返回不续签，第 7 天需要登录。

前端业务请求响应可携带 `X-Auth-Token` 与签发/到期时间，SSE 在响应头提交前处理；`/auth/refresh` 返回同一契约，超出续签窗口返回 `renewed=false` 和原到期时间而非 401。所有 API 由单一 auth store 接收新 token；并发响应只接受不早于当前 iat 的有效令牌，logout/login 改变本地 auth generation 后丢弃旧请求响应。不得在已经返回 401 后无限刷新/重放写操作。

token 存 Pinia 内存和 sessionStorage 以保留页面刷新登录状态，退出时清除账号相关状态与流连接；不引入 refresh token。消息轮询、SSE 心跳等纯后台维护请求不触发续签，避免无人使用时自动延长登录。跨源部署时显式暴露续签响应头。认证路由保留 returnTo 和邀请上下文，returnTo 限本站路径。

选择延续 Bearer 方案以兼容现有 SSE/API；Cookie 会引入另一套 CSRF 与部署契约，双令牌模式也不符合此次单 JWT 规则。旧 JWT 格式直接拒绝，切换时要求重新登录。

### 2. 真实数据与路由清单

拆出全局 AppShell、WikiLayout 和 ConversationSurface；auth store、conversation store 放在路由之上。静态样式/标签可保留，用户名、文档、统计、最近访问、摘要和列表内容必须来自 API；无数据不伪造示例。

| 入口 | 路由/行为 | 数据和完整操作 |
| --- | --- | --- |
| 登录、注册 | `/login`, `/register` | 校验、提交、防重复、错误与返回原位置 |
| 知识库 | `/knowledge-bases` | 列表、新建、切换、管理、空状态 |
| Wiki | `/knowledge-bases/:kbId/pages/:pageId` | 树/搜索、阅读/编辑/发布、历史/恢复、摘要、互动/协作 |
| 新聊天 | 全局按钮 → 输入 → 右下角浮窗 | 首次发送建会话；最小化回按钮；再次打开继续 |
| 会话 | `/conversations`, `/conversations/:sessionId` | 分页列表、历史、续聊、重命名、删除、停止生成 |
| 智能体 | `/agents`, `/agents/:agentId` | 从 MySQL agent_definition 读取已配置 Agentic RAG 能力、工具描述及启用状态，点击开始会话；不做编排器 |
| 共享空间 | `/shared` | 获邀知识库/文档、待处理申请、我创建的链接；按当前角色管理 |
| 消息中心 | `/notifications` | 右侧主内容替换为消息列表，未读/已读、跳转原文 |
| 账号/用户主页 | `/users/:userId`，`/me` 重定向 | 当前用户信息及真实点赞/收藏列表，逐项做文档可读过滤；他人主页仅展示对访问者可见信息 |
| 最近访问 | 导航真实条目 | MySQL 记录最近访问页面/会话，去重排序，删除/失权后移除 |
| 全局搜索 | `/search?q=...` | 后端按当前权限搜索标题/文档；可点击结果 |
| 邀请落地 | `/invite/:token` | 登录后预览/接受/提交申请；不在 GET 时自动写入 |

旧 `/:kbId/:pageId` 识别为数字 ID 时重定向 Wiki 新路由；`/` 指向知识库，无匹配路由显示 404。导航使用 RouterLink 和 active 状态。树的目录/页面创建、索引入口、摘要切换、历史和归档等现有可点操作纳入点击清单，不能留下空 handler；普通读者不展示管理操作。摘要读取持久化手写/生成内容，尚未生成显示“暂无摘要”。

API client 解包 TransDTO，兼容 HTTP 错误与 HTTP 200 内业务错误，支持 PATCH/DELETE、FormData 不强设 JSON Content-Type、AbortSignal、分页和错误码。SSE 单独按现有扁平 wire JSON 解码。401 统一回登录，403/404/依赖故障展示真实页面状态；加载切换取消陈旧请求，不用静态内容兜底。

### 3. 持久会话与浮窗生命周期

复用 chat_session/chat_message，增加 chat_run 表保存 run/request/clientMessageId、状态、开始/结束、最终 seq、可公开进度与引用。`POST /chat/sessions` 在第一次发送时创建会话；客户端生成 clientMessageId，`POST /chat/stream` 增加 sessionId、clientMessageId、agentId（可选）、query；旧 query-only 请求仍可建新会话并通过首个 session 事件返回 ID。

运行前事务检查会话归属，写 USER 消息和 RUNNING run，唯一 `(session_id,client_message_id)` 防重复，同会话只允许一个运行中的 turn，冲突 409。结束时事务更新状态和助手消息/引用；错误和取消也持久化部分答案及明确状态。审计失败与业务消息持久化失败分别处理：最终业务落库失败不能展示为历史已保存的成功结果，应结束为可恢复错误；服务重启扫描过期 RUNNING 标为 INTERRUPTED，不伪造完成。

会话历史按预算送入编排器；客户端不能指定他人的消息或会话。历史中的引用和工具证据每次展示/重送模型都校验当前文档权限；失权的证据内容须遮蔽，相关旧助手消息若不能安全剥离则整体隐藏为“引用内容已不可访问”，不通过 chat_message 绕过 ACL。

状态机：`launcher → composing → floating → minimized/floating/fullpage`。最大化只导航并挂接同一个 store；最小化只隐藏 UI，流继续；恢复不重复请求。路由组件卸载不直接 abort 全局流，显式停止、退出登录、删除当前会话才取消。浏览器刷新/断网会取消传输，重进通过历史/run 状态展示中断，可明确重试为新 turn；本期不承诺 SSE 断点重放。桌面浮窗固定右下，标题/输入固定，消息区内部滚动；窄屏保持可操作的全屏对话。

选择共享 store 单连接优于浮窗和页面各发一条请求，避免重复生成与多次写入。服务端真实历史优于仅 localStorage 保存。

### 4. 可公开的工具/解释事件和滚动

保留现有 route/rewrite/retrieve/tool/quality/retry/token/citations/done/error、requestId 和 run-wide seq，增加 session 事件与 reasoning-summary 事件。进度标准字段为 sessionId/runId、node、round、callId、toolName、status、summary；检索完成可附 evidenceSummary、confidence、scoreKind（区分检索相关分和归一化置信分），QA 附 passed/reason/retryRound。缺失置信分显示“未提供”，禁止前端编造分数。

默认每个工具调用一行状态和摘要，状态机 queued/running/succeeded/failed/cancelled；按 callId+round 更新同一项，保留重试历史。文案如“调用 query 改写中”“混合检索工具调用成功—摘要…，置信分…”“QA 评审不通过—原因…”。解释流来自编排器节点说明或独立的可公开 explanation 文本通道，不能直接透传供应商隐藏 reasoning 字段。折叠显示最新一行，展开查看完整可公开解释；历史也只持久化这些允许展示的内容。

外层消息区和展开解释区各有 followTail 状态；初始在底部才自动跟随（距底部 48px 内），wheel/touch/键盘上滚离开底部暂停跟随，生成内容不更改其 scrollTop，显示“有新内容/回到底部”；用户主动回底或点击按钮才恢复。DOM 更新用 nextTick 后按当前 followTail 判断，切换展开状态保留各自位置，不能每个 token 无条件 scrollIntoView。

### 5. 统一知识库/文档权限与可见名单

新增 ResourceAuthorizationService 和文档范围版本，公共操作以 READ/EDIT/MANAGE/TRANSFER 等 action 判断。`created_by` 保留历史作者，新增 `owner_id` 表示当前创作者权利；不能转交时改写历史修订作者。

| 角色 | 读/互动 | 编辑 | 邀请/审核/可见范围 | 任免管理员 | 转交创作者 |
| --- | --- | --- | --- | --- | --- |
| VIEWER | 是 | 否 | 否 | 否 | 否 |
| EDITOR | 是 | 是 | 否 | 否 | 否 |
| 资源 ADMIN | 是 | 是 | 是 | 否 | 否 |
| 资源 OWNER | 是 | 是 | 是 | 是 | 是 |
| 平台超管 | 是 | 是 | 是 | 是 | 仅显式审计管理接口，不经普通邀请提升 |

文档 OWNER、文档 ADMIN、所属知识库 OWNER/ADMIN 和超管具有管理例外；“仅自己”表示除这些管理者外不向普通成员开放，界面明确提示这一管理继承。知识库 ADMIN/OWNER 默认管理全部所属文档，但不因此获得文档所有权或任免文档管理员/转交的普通创作者操作。

文档 audience 模式为 PRIVATE、SELECTED_MEMBERS、KB_MEMBERS（兼容已有文档）。上传界面只提供用户要求的 PRIVATE 与 SELECTED_MEMBERS；历史文档回填 KB_MEMBERS 保留旧访问行为。

SELECTED_MEMBERS 保存 `(pageId,sourceKbId,userId,READ)`，可选多个知识库的不同成员；不选人就不授予该库全员权限。左树加载当前用户有权浏览成员的知识库；右侧按用户名分页搜索并勾选/移除，切换节点保留全部选择、跨库同用户去重展示，提交时复核成员仍存在。此处“增删人员”仅编辑文档受众，不修改 knowledge_base_member。若需要调整知识库成员，在知识库管理页执行。

可见名单是所选成员快照，后加入该库的人不自动加入；名单关联来源知识库，退出来源库后该条授权失效，多来源中任一仍有效则保留。资源独立邀请获得的文档 VIEWER/EDITOR 授权不依赖来源库；变为 PRIVATE 时须同事务撤销普通文档受众与普通直接授权，并撤销未消费文档邀请/申请，明确确认影响人数后执行，保留管理例外。

同资源多来源取最高授权等级；授予单个文档不授予所属知识库或兄弟文档访问。共享文档从 `/shared` 直接打开，仅返回当前文档必要元信息，不能读取不可见父目录标题。

列表/树/全局搜索、正文/草稿/修订、附件/下载、反向链接/来源/摘要、候选用户、消息、统计和 RAG 全部复用当前权限。扩展 AuthorizationScope 与 ES 过滤为资源范围，不能只按 kbId 放行；文档 ID 过滤批处理，后续上下文组装和引用解析再校验数据库。知识库成员、受众、文档授权、所有权变更推进持久化范围版本并失效缓存；长流出站前复核，失效中止。成员搜索必须先限制有权展示的候选集合，再相关排序。

选择显式文档授权优于给跨库协作者补知识库成员，避免共享一页扩大权限；选择管理例外以满足知识库管理员继承文档管理的要求。

### 6. 上传到 Wiki 的可恢复导入

在知识库“新建”中选择“上传文档”，模态表单包含目标知识库、可选目录、标题（默认文件名）、DOCX/MD 文件和可见范围，提交前呈现选中的人员列表。导入目标必须有 CREATE_PAGE/UPLOAD_ATTACHMENT 权限，后端复核扩展名、内容签名/MIME、大小和非空文本，拒绝伪装 ZIP/超限 DOCX 解包和多模态输入；Markdown 的通用 MIME 可在内容校验后识别，不能仅依赖浏览器 MIME。

新增 wiki_import_job，状态 PENDING_UPLOAD → STORED → PARSING → SUCCEEDED/FAILED；客户端幂等键和 source attachment 唯一关联防重复。沿用 AttachmentStorage 内容中心及已有 DOCX/Tika、Markdown parser，新增结构化文本到规范 Markdown 的转换，保留标题/段落/列表/表格/链接；不执行嵌入脚本，渲染做净化，图片不入多模态管道并返回提示。

解析成功事务创建 wiki_page、首个草稿和发布修订、来源关系、受众授权及索引任务，返回 pageId/revisionId。本次上传默认自动发布，以满足上传完成即 Wiki 可阅读；如用户只有草稿权限则入口不允许自动发布导入，避免半授权成功。原附件索引入口增加 import-purpose 标记，Wiki 导入的附件只作为来源，索引规范 Wiki 一份，避免附件和页面重复召回。

页面创建前失败不遗留可见空白 Wiki；解析成功但 ES 失败时 Wiki 保持可读，UI 单独显示索引失败/重试。用户可查任务进度与可读错误、重试失败任务；任务重启恢复与过期认领复用现有 job 模式。重试提交及 worker 提交前重新验证创建者和受众权限。

### 7. 邀请、审核、管理员与转交

邀请是站内可复制链接，服务端生成高熵 token，只存 hash，绑定 resourceType/resourceId、VIEWER/EDITOR、expiresAt、creatorId、revokedAt。默认有效期 7 天、可在 1 小时到 30 天选择；资源 `join_approval_required` 默认开启，现有资源也回填开启。链接可多人使用，显式撤销即时生效；不通过聊天/邮件工具替用户发送链接。

未登录只保留 token 并登录；已登录预览时复核有效期、资源存活和发行者仍有分享权限。前端用户激活有效链接后自动发幂等 accept POST；GET 仅预览不写库。开关关闭直接入成员，开启写 PENDING 申请。审核者为当前资源 OWNER/ADMIN、文档所属知识库管理者或超管；审批时再校验链接未过期/撤销、申请仍待审及当前审核权限。开关从开变关不批量批准已有申请，用户可再次接受有效链接，原申请完成为直接加入；从关变开对新 accept 生效。

知识库邀请只增加知识库成员，不放开 PRIVATE/SELECTED 文档；文档邀请仅创建该页授权。重复 accept、重复审批和已具备更高角色不能降级或产生重复成员。链接不可授予 ADMIN/OWNER。

创作者通过资源管理员管理接口添加/移除 ADMIN；继承管理员在文档页面标明来源，只能回知识库撤销，文档不能局部移除继承关系。普通管理员不能任命其他管理员或转交所有权。

转交仅当前 owner 选择当前有效的资源协作者，接收者确认后事务 CAS 更新 owner_id、OWNER 成员映射和范围版本，原 owner 默认 EDITOR。转交请求到期默认 7 天，可撤销；并发最多一笔成功，始终唯一 owner，不允许移除/降级当前 owner 使资源无主。知识库转交不自动转交其每篇文档，但新知识库 OWNER 继承管理权；所有管理变更记录审计。

### 8. 划词锚点与独立 AI client

阅读渲染为稳定段落 blockId/paragraphHash。SelectionAnchor 保存 pageId、revisionId、blockId、paragraphHash、start/end（统一 UTF-16 offset）、quote、prefix/suffix；后端读取该授权修订验证完整段落和所选文本，不信任客户端上传的整段。支持同一段内非空划词；跨段选择提示重新选择，不静默截断。

划词后浮动工具栏提供“评论”和“问 AI”。评论复用统一 wiki_comment，并关联 anchor；问 AI 打开带引用的输入浮层，经 `POST /pages/:pageId/selection-questions/stream` 交由 SelectionQuestionLlmClient。client 独立配置模型/超时/预算，沿用已有可取消底层 HTTP，独立于检索规划器；服务端构造上下文含 Wiki id/标题/版本/授权范围内必要元信息、完整段落、划词文本、用户 query，将文档作为不可信数据区分系统指令，长度超限返回明确错误而不静默丢弃完整段。支持流式答案、取消、错误，出站前复核权限。

通知跳转优先精确原 revision/block/offset；新修订中按 block+quote+上下文唯一匹配才高亮。无法唯一重定位显示“原文已变更”并允许查看仍可读的原修订和评论，不盲目高亮同名词。

### 9. @ 候选与站内消息

`GET /pages/:pageId/mention-candidates?q=&cursor=&limit=` 先验证发起者可读，再查询该页当前可读/编辑的活跃用户（含管理者）候选。排序为 username 完全匹配 → 前缀匹配 → 包含匹配 → 规范化 username/userId 稳定次序，限制分页大小，参数化并转义 LIKE 通配符。前端 200ms debounce、AbortController，旧结果不得覆盖新前缀；输入 @ 打开候选，方向键/回车/Escape 和鼠标操作，将选定用户存为 `{userId,label}` token，可多个 @，展示文本不能作为收件人身份依据。

评论事务写正文、mention 关系与 notification，发送前再校验候选权限；伪造或刚失权 userId 返回可纠正错误，不给未授权人发通知。每条评论每个用户只产生一条 @ 通知，重试不重复，默认不通知本人。

notification 包含 recipientId/type/pageId/commentId/anchorId/readAt。消息中心默认分页，点击未读消息标已读后路由到 Wiki 并展开评论/锚点；支持单条和全部已读，幂等更新，badge 为本人未读数。会话/通知不混用统计 key。初期用可见窗口定时轮询（30s）+窗口聚焦刷新+写操作后刷新，不通过后台轮询续签 JWT。

通知摘要读取时复核页面和评论可见性，已删除/失权显示不可访问，不泄露旧标题/划词；badge 只计当前可访问的未读消息，与列表过滤保持一致，权限恢复后按未读状态重新纳入。

### 10. 评论、点赞与收藏

文档 page_like/page_favorite、评论 comment_like 使用 `(resource_id,user_id)` 唯一关系，PUT 为设为已点赞/收藏，DELETE 为取消，幂等结果带当前状态和统计。禁止使用 toggle API 使重试翻转状态。个人主页点赞列表以真实关联分页，加入时排序，读取时过滤失权或归档页面。

wiki_comment 字段包含 id/pageId/authorId/body/parentId/replyTo/anchorId/deletedAt。一级 parentId/replyTo 均空；所有回复 parentId=一级评论 ID，replyTo=直接被回复评论 ID。后端从目标推导 root，拒绝跨页、已删除或根已删目标；锁定 root 协调回复与删除。前端按根分页、回复独立分页，展示被回复者，不生成递归 parentId 链。

删除权限为本人或资源管理者。同步删除仅更新被删评论 deletedAt 一行：根评论删除后 API 查询立即排除根和全部 parentId 指向根的回复，前端也删除整组，防止分页/缓存/深链重现；非根删除仅隐藏自己，其余同根回复保留，replyTo 指向已删评论显示“原回复已删除”。

评论总数定义为当前可见未删除评论数（含根和回复）：根删除时从数据库计数可见子树，同事务序列化写后使用 `-(1+可见回复数)` 更新统计；不逐条修改回复。对已隐藏回复的后续每日清理不再扣减统计。隐藏/已删评论不能继续获赞、回复或发送通知。

清理通过 CommentCleanupStrategy 业务接口，默认 RootDeletedReplyCleanupStrategy 按游标批量逻辑删除根已删的回复；CommentCleanupTrigger 接口的 SpringScheduled 实现每日 01:00（Asia/Shanghai，可配置）触发，集群锁防多实例重复任务，短事务/批次/幂等/失败指标，服务重启可补跑未完成批次。XXL-JOB 将来实现 trigger 调用同一策略，不改评论业务逻辑。

### 11. Redis 统计与并发协议

文档 key `kwiki:stats:page:{pageId}` 保存 likes/favorites/comments，评论 key `kwiki:stats:comment:{commentId}` 保存 likes；每组还带 dbVersion。使用 kk-common 的 Redis/分布式锁能力，在适配器中确认整数/hash 和 Lua 原子脚本序列化；不能把 SDK JSON 值直接当整数 INCR。所有写操作先做权限校验和业务数据库事务，提交后同步 Lua HINCRBY + EXPIRE 1800，查询命中直接读且不续 TTL，业务操作（含幂等成功操作的 touch）刷新 TTL。

并发算法：每个统计资源使用同一分布式锁协调缓存回填和业务写入；锁在业务事务前申请，持有至提交后的缓存处理结束，有所有权校验、续租与有界等待。真实业务状态变化的事务递增 stats_revision，唯一互动关系确保重复请求 delta=0。缓存脚本仅接受连续 dbVersion 增量；旧/重复版本 no-op，版本跳跃或字段不完整则失效并回填。root 评论删除及回复创建统一先锁 page，再锁 comment（如需要），避免反向锁序。

1. 读命中：授权后直接返回完整 hash。
2. 读未命中：申请该资源锁，二次检查，数据库同一一致性快照读聚合+版本，原子写完整 hash+30min TTL；未获锁者有界重读，仍未命中直接查 DB，不无锁写缓存。
3. 写提交后 key 存在且版本相邻：原子应用 delta 和 TTL；任何业务回滚均不发增量。
4. 写提交后 key 缺失/过期：在已持锁情况下读取包含此次提交的真实聚合和版本，完整回填；不能从 0 直接 INCR，也不能回填后再叠加同一 delta。
5. Redis 写超时/断连：数据库成功仍返回业务成功和 DB 统计，尽力删 key；同事务记录待处理 stats_repair（目标资源+版本，可合并），恢复后加同锁按数据库重建，绝不盲目重放不确定 delta。正常同步更新成功将修复标记完成；锁续租失败或 Redis 整体不可用进入同一修复路径，绝不无保护回填。key TTL 为最坏陈旧时间上界，修复触发默认每分钟缩短窗口。

锁仅串行同一资源的互动与回填，不锁所有 Wiki；命中读允许看到并发写提交前的统计，本设计不是跨 MySQL/Redis 强一致事务。高热文档写串行是保守取舍，可测量后采用更复杂的版本日志流水线；单纯 afterCommit INCR + 只锁 miss 不能避免回填含新值后再次增量，故不采用。

### 12. 数据增量与接口边界

| 数据对象 | 主要增量与约束 |
| --- | --- |
| app_user | 沿用 username/password_hash/is_active/is_admin；补注册校验，不增加权限 JWT 列 |
| knowledge_base / wiki_page | owner_id、join_approval_required；page 增 audience_mode 与持久化范围版本；created_by 不变 |
| knowledge_base_member | 扩展 ADMIN 检查约束，owner 映射与 owner_id 事务一致 |
| page_member / page_audience_member | `(page,user)` 直接角色唯一；`(page,sourceKb,user)` 受众唯一及候选查询索引 |
| resource_invitation / join_request / ownership_transfer | token_hash 唯一、资源/期限/状态、申请去重、受让人/状态/CAS 版本 |
| wiki_import_job | 幂等键唯一、attachment/page 关联、状态、lease、attempt/error |
| chat_session / chat_message / chat_run | 会话拥有者、更新时间、逻辑删除；run/message 唯一键、状态与安全历史元信息 |
| agent_definition / recent_visit / page_summary | 真实系统能力配置、用户最近访问唯一键、带修订来源的摘要；仅配置数据允许迁移初始化 |
| selection_anchor / wiki_comment / comment_mention | 精确版本锚点、根/回复约束、软删除；`(page,parent,created,id)`、`(parent,deleted)` 和 mention 唯一 |
| page_like / page_favorite / comment_like | 资源+用户唯一，个人互动时间分页索引 |
| notification | recipient/readAt/createdAt/id 查询索引，comment+recipient+type 去重 |
| stats_revision / stats_repair / management_audit | 单资源版本、可合并修复状态、管理审计；有界批量查询索引 |

多态资源外键由服务验证资源存在及类型，能用具体 FK 的字段仍使用 FK；parent/root/replyTo 同页一致性在服务层锁定校验，MySQL CHECK 不承担跨表查询。

新增接口统一 `/api/v1`，既有知识库 Wiki CRUD 保留并升级文档授权；新接口分组为 auth、chat/sessions、agents、users、recent-visits、search、knowledge-bases/:id/imports、resources/:type/:id/{members,invitations,join-requests,ownership-transfers}、pages/:id/{audience,selection-questions,mention-candidates,comments,likes,favorites,statistics}、comments/:id/{likes}、notifications。分页统一 cursor/limit/nextCursor，资源 ID 必须验证归属；流协议与 JSON envelope 分开。实施时先将各 DTO/错误码及请求示例固化为接口契约再并行推进前后端任务。

## Risks / Trade-offs

- [“仅自己”和管理员继承管理存在表面冲突] → 明确定义管理例外并显示文案；普通成员绝不因属于知识库获得 PRIVATE 文档。
- [新文档权限容易遗漏旧接口/ES/聊天历史] → 建立出口清单和同一 ACL 服务；检索前过滤、发模型/引用前复核，测试跨库与权限撤销。
- [已有未归档规范与本变更重叠] → 引用当前实现作为依赖，不回滚已有进度；归档前统一 Wiki/RAG 基线要求，避免保留相互矛盾的知识库唯一授权规则。
- [活跃续签导致响应并发和后台无限延长] → iat 单调接收、auth generation、维护请求不续签、可注入时钟边界测试。
- [热文档分布式锁增加写延迟] → 按资源隔离、超时有界、故障读 DB 与修复，保留压测任务；正常缓存命中读不争锁。
- [Redis 与 MySQL 无原子跨库事务] → 同步更新+持久化修复；明确故障期最终一致，不能把缓存失败伪装业务失败让用户重复点赞。
- [原文修订导致划词漂移] → 存版本/段落/引用上下文，只在唯一匹配时重定位，否则提示并回原修订。
- [真实 MySQL 未启用] → 离线验证覆盖逻辑与契约，但不声称完成 MySQL 方言、Flyway 执行和实际 SDK 连通验证；这些留作环境就绪后的单独验收。

## Migration Plan

1. 实施开始重新检查全部迁移（包含未跟踪文件）、Flyway 配置和已有分支变化；当前 V5 仅为观察结果，新版本运行时按实际 max+1 分配，不提前锁死 V6。补只读离线命名/重复版本校验，保留原迁移不可变，不运行现有 Flyway clean 测试。
2. 分阶段追加身份/资源授权、会话/导入、互动/通知/统计迁移；按现有 created_by 回填 owner_id，历史页回填 KB_MEMBERS，审核默认开，现有 OWNER 映射校验后补唯一所有者不变量。新表空表起步，不注入假用户/文档/会话；agent_definition 只写真实系统能力配置。
3. 先交付统一身份和文档 ACL，再开放协作/私有导入，随后接会话、划词、通知和统计，最后替换全部静态 UI。按模块开关控制未上线功能，禁止新 ACL 数据与旧仅 KB 鉴权代码混合服务。
4. 同步发布后端 JWT 契约与前端登录态，旧 token 重新登录；保留旧 URL 重定向与 query-only SSE 入口。
5. 执行离线 Java/前端/规范检查，确认每个入口的数据与错误路径。真实 MySQL 方言与迁移演练待服务就绪另行安排，本任务不启动它。
6. 回退优先关闭新增入口并前滚修复；新增列/表保留，不删除用户数据。已启用 PRIVATE/SELECTED ACL 后不能回滚到旧知识库级授权程序；必须保留新授权层或进入维护状态，避免暴露私有文档。JWT 格式回退也不能重新信任旧 adm。

## Open Questions

无阻塞问题。JWT 规则已由用户确认。其余采用上述明确默认值：仅自己保留管理员例外、上传人员增删只改受众、邀请默认审核/7 天有效、转交需接收确认、上传成功自动发布、清理时区 Asia/Shanghai、解释区域展示可公开执行说明。实施前可调整产品默认值，但不得静默改变规格中的权限和时间边界。
