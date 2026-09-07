## Why

当前 kwiki 已具备 Wiki、附件解析与 Agentic RAG 后端基础，但前端仍有静态内容、未接通的菜单及不完整的账号、会话和协作流程。需要将工作台接入 MySQL 真实业务数据，并把文档权限贯穿上传、协作、检索、评论和通知，使用户能够完整使用知识管理与问答功能。

## What Changes

- 设计并实现登录、注册、当前用户加载和无感 JWT 续签。JWT 有效期 7 天；签发后 5 天内的已认证访问续签 7 天，超过 5 天不再续签，原令牌第 7 天过期。用户已确认此解释。
- **BREAKING**：JWT 仅携带用户 ID 及必要标准时间声明，删除用户名、管理员和权限声明；权限由后端按 ID 查询。旧令牌失效后重新登录。
- 删除生产前端的 mock 数据与静态业务占位，统一解包现有 TransDTO，接通知识库、文档、用户、最近访问、智能体、共享空间、会话、个人主页和消息中心。
- 新聊天首次发送后打开右下角聊天浮窗；支持最小化与最大化到会话页，同一会话、消息及流保持连续。补齐会话持久化、列表与历史续聊。
- 工具链默认展示状态和摘要，包含 query 改写、混合检索摘要/置信分、QA 结果/原因和重试；思考区域展示可公开的执行解释流，单行最新内容可展开，用户滚动时暂停自动跟随。
- 新建上传支持选择归属知识库、仅自己或指定知识库人员可见，多知识库树与用户搜索/勾选；DOCX、Markdown 解析后生成可阅读、编辑、发布和检索的 Wiki。
- 增加知识库/文档邀请链接、有效期、只读/编辑角色、审核开关、资源管理员和创作者权限转交；知识库管理员继承所属文档管理权限。
- 增加划词评论、独立划词 LLM client、权限范围内的 @ 候选搜索、键盘/鼠标多选，以及通知未读数和定位原文的消息中心。
- 增加文档点赞/取消、收藏/取消、评论统计、个人点赞列表及评论点赞；落实 parentId/replyTo 两层存储、单条逻辑删除、根评论隐藏整棵评论树，以及每日 01:00 的可替换调度策略清理。
- 统计以 MySQL 为准，业务提交后同步原子更新 Redis 并刷新 30 分钟 TTL；未命中加分布式锁回填，处理并发、幂等、缓存故障和根评论删除后的统计一致性。
- 通过离线单元、接口契约和前端交互测试验收；本变更不连接真实 MySQL 做测试，也不启动数据库服务。

## Capabilities

### New Capabilities

- `account-session-authentication`: 数据库账号、登录注册、ID-only JWT 与 5/7 天续签。
- `connected-workspace-navigation`: 全部菜单对应页面、真实数据与统一 API 状态。
- `persistent-agentic-conversations`: 持久会话、浮窗/全页切换、工具与执行解释流。
- `document-audience-authorization`: 文档受众、跨知识库指定人员和全链路权限隔离。
- `wiki-file-import`: DOCX/Markdown 上传、异步解析、Wiki 落库与索引。
- `resource-collaboration`: 邀请、审核、资源管理员、所有权转交。
- `wiki-selection-assistance`: 稳定划词锚点、评论入口及独立 LLM 上下文请求。
- `mentions-and-notifications`: 权限候选搜索、@ 通知、未读与原文定位。
- `wiki-social-interactions`: 文档/评论点赞、收藏、两层评论和清理策略。
- `wiki-statistics-cache`: 数据库优先写入、Redis 统计、锁回填和故障修复。

### Modified Capabilities

无。当前 `openspec/specs/` 尚无已归档基线规范，以上以新增规格定义本次增量；实施时与未归档的 Wiki、SDK、内容中心及 Agentic 编排变更对齐，避免重复归档同名能力。

## Impact

- 前端：Vue 3、Pinia、Vue Router、Naive UI；拆分全局布局与知识库布局，扩展 API/auth/conversation store、阅读器、上传、协作和消息组件。
- 后端：`security`、`wiki/api`、`wiki/access`、`wiki/persistence`、`rag/answer`、`rag/orchestration`、`indexing/parse`、Redis 和 LLM 适配器；沿用 kk-common 与内容中心 SDK。
- 数据：扩展现有账号、知识库、文档、会话表，新增文档授权、导入任务、邀请/申请、评论/锚点、互动、通知、最近访问和统计修复记录。沿用 `src/main/resources/db/migration/V<N>__*.sql`；实施前重新分配版本，不改已存在迁移。
- **BREAKING**：新增文档范围限制替代仅凭知识库成员身份的授权判断；前后端同步切换路由与 JWT 契约，旧 Wiki URL 保留重定向。
- 不引入多模态解析、自定义智能体编排平台、外部消息发送或 XXL-JOB 部署；预留调度策略接入点。
