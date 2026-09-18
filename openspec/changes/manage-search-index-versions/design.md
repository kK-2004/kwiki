## Context

KWiki 当前以 `kwiki-chunks` 作为统一读写别名，`ElasticsearchIndexManager` 已具备创建 `kwiki-chunks-v{n}`、校验 mapping 和通过单次 `_aliases` 请求切换/回滚的基础能力；启动引导在别名不存在时创建 v1。现有 `indexing_job` 提供 MySQL 持久化、租约、重试和生命周期 fencing，但任务没有物理索引目标，worker 固定写入别名，parser、chunker、embedding 模型和索引版本也由进程级常量决定。因此，当前能力不足以在在线内容持续变化时构建 v2，并保证 v1/v2 都可用于无数据缺口的热切换。

检索与回收站变更仍在进行中。新方案必须继承其原则：MySQL 是资源及生命周期的权威来源；只索引当前有效、已发布且允许索引的资源；所有 UPSERT、DELETE、ARCHIVE、RESTORE 事件都必须经过版本 fencing。物理索引版本管理不能成为绕过生命周期或权限校验的新路径。

现有业务前端是 Vue 3/Vite/Pinia/Naive UI，后端使用无状态 JWT 并已有 `ROLE_ADMIN`。管理端采用 vue-pure-admin 单独构建，但认证、管理 API 和生产静态资源仍由同一个 Spring Boot 服务提供。

项目已经依赖 kk-common 0.1.2，并已有 `RedisUtil`、`DistributedLockFactory` 及其 Redisson 自动配置，但 `application.yml` 当前把 Redis/Redisson 默认关闭。`ScopeCache` 已使用 `RedisUtil`，`WikiStatisticsCache` 仍直接依赖 `StringRedisTemplate` 并实现 SETNX 锁；`ResourceIndexMutex`、`MysqlAdvisoryLocks` 和 `CommentCleanupTrigger` 仍使用 MySQL `GET_LOCK`/`RELEASE_LOCK`。本变更把 common SDK 提升为全项目唯一 Redis 与应用级分布式锁入口。数据库事务行锁（例如 `SELECT ... FOR UPDATE`）仍用于数据一致性，不属于需要替换的分布式锁旁路。

## Goals / Non-Goals

**Goals:**

- 普通内容发布无需创建物理索引版本，继续异步更新当前写入目标。
- parser、chunker、embedding 模型、向量维度或 ES mapping 的实现可以先正常发版，再由管理员保存/编辑新逻辑版本、显式重建和选择切换时机，应用发版绝不自动切换。
- 发起切换准备后，所有未停用/删除的索引版本持续承接新写入；目标版本通过范围尾扫和事件重放追平后才原子切换。
- 所有长任务可恢复、可重试、可审计，并适用于多实例部署。
- 别名切换和回滚对查询原子可见；失败不改变当前在线索引。
- 提供管理员可操作、可观察且有危险操作保护的 vue-pure-admin 管理端。
- 默认启用 common Redis/Redisson，并让现有缓存、调度互斥、资源索引互斥和新索引切换互斥全部经过 SDK。
- 让默认离线测试与 SDK 集成测试各自具有明确、可重复且不访问外部 Redis 的配置。

**Non-Goals:**

- 不允许普通用户创建、切换或删除索引。
- 不在浏览器中直接连接 Elasticsearch，也不向管理端返回 ES 或模型凭据。
- 不自动删除旧物理索引；清理始终需要管理员显式操作。
- 不保证任意久远版本可热回滚；只有仍受当前程序支持且保持追平的版本可热回滚。
- 不以 v1/v2 chunk 数量相等作为正确性条件，因为 parser/chunker 变化会合法改变 chunk 数量。
- 本变更不重新定义 Wiki 归档保留策略，只要求其索引事件覆盖所有当前写入目标。
- 不自行封装第二套 Redis 客户端、序列化器或分布式锁抽象；应用只保留面向业务语义的薄适配器。
- 不把数据库事务行锁、JPA 乐观锁或 JVM 内部同步误判为 Redis 分布式锁并强制替换。

## Decisions

### 1. 逻辑版本号自动维护，配置修订决定脏位

每个后台索引配置对应一条 `search_index_version`。创建时服务端通过数据库单调计数器和唯一约束分配下一个版本号，名称固定为 `kwiki-chunks-v{positive integer}`；已删除物理索引的版本元数据保留为 tombstone，因此版本号不回退、不复用。版本号和物理名称不可编辑，管理员可编辑 parserVersion、chunkerVersion、embeddingProvider、embeddingModel、dimensions、mappingSchemaVersion 等非敏感配置。密钥不进入数据库或 API。

版本保存 `configRevision` 与 `builtConfigRevision`。每次配置创建/编辑原子递增 `configRevision`；仅当一次完整构建基于同一配置修订成功时才更新 `builtConfigRevision`。`dirty = configRevision != builtConfigRevision` 为派生事实，不单独维护可能漂移的布尔列。数据是否追平由独立 `catchupStatus` 表示，避免把“配置待重建”和“构建后可能存在差量”混为一谈。编辑已构建但非在线、非写入、非运行中版本，会使现有物理内容失效并重新标记待重建；下一次重建可以删除并重新创建这个明确不在线的同名物理索引。活动别名目标、启用写入的版本或正在构建的版本禁止原地编辑，管理员应复制当前配置并创建下一个自动版本。

管理端主状态由后端按优先级派生，不能把多个内部状态直接拼成含混标签：`dirty` 且无活动 run 显示“待重建”，基线 run 活动时显示“重建中”，基线成功后显示“已重建”；管理员发起选择且目标存在未覆盖差量时显示“补齐中”；范围尾扫、事件重放、实时多写屏障及校验均通过并完成别名原子切换后，当前唯一别名目标显示“已发布”。“已重建”阶段即使内部知道构建水位之后可能产生了变化，也不展示“待补齐”；是否需要补齐只在切换准备建立新水位后计算。非当前索引即使保持同步也不显示“已发布”，其同步健康度作为独立指标展示。

若切换准备计算出的范围、事件和目标任务差量均为零，则目标可以不经过可见的“补齐中”，在校验及原子别名切换成功后直接从“已重建”变为“已发布”；该零差量判定及各水位仍需审计。

每次 build run 在开始时固化不可变 `buildManifestSnapshot` 和 configRevision，worker 只使用该快照。构建过程中再次编辑被禁止；构建结束发现版本修订不匹配时不得标记已重建。

Wiki 发布新修订只产生内容 UPSERT，不提升配置版本。parser、chunker、embedding 或 mapping 的代码/依赖随应用发版只表示“后台可选择的新配置已受支持”，既不自动创建 v2，也不自动修改、重建或切换任何版本。

替代方案是根据应用配置在启动时自动递增版本。该方式无法安排 embedding 成本、观察容量或选择切换时机，因此拒绝。

### 2. 读别名与写目标分离

所有 BM25、向量、父块和引用查询继续只读 `kwiki-chunks`。索引 worker 不再写该别名，而是根据任务中持久化的目标版本写显式物理索引。

新增概念表：

- `search_index_version`：自动版本号、可编辑配置、configRevision、builtConfigRevision、构建状态、writeEnabled、ES 物理名称、校验摘要和时间戳。
- `search_index_rebuild_run`：不可变构建配置快照、各资源类型范围上下界/游标、事件起始水位、计数、状态、租约、错误摘要和操作者。
- `search_index_change_event`：内容及生命周期变化的仅追加单调事件序列，供未参与早期双写的版本重放更新、删除、归档与恢复。
- `indexing_job_target`：把一个资源事件扇出为每个物理目标的独立执行状态；幂等键包含原事件、目标版本及操作。
- `search_index_audit`：创建、编辑、开始重建、切换准备、校验、选择、停用、重新启用和删除的操作者及结果。

目标集合由数据库持久化，典型阶段如下：

```text
初始稳定       read alias=v1    writable={v1}
v2 全量构建    read alias=v1    writable={v1};       v2=DIRTY/BUILDING
v2 构建完成    read alias=v1    writable={v1};       v2=BUILT
切换准备       read alias=v1    writable={全部启用版本}; v2=TAIL_CATCHUP
切换完成       read alias=v2    writable={全部启用版本}
管理员停用 v1 read alias=v2    writable={除 v1 外的启用版本}; v1=DISABLED
```

一旦任意切换准备开启，所有未被管理员停用或删除且流水线仍受支持的版本都设为 writeEnabled；入队事务必须快照当时的写目标并生成每目标记录，不能等 worker 消费时再读取目标集合。主目标和其他版本独立成功、重试；非当前读目标失败不伪装成当前目标失败，但会把对应版本标记为数据落后并阻止切换到它。管理员可以显式停用旧版本来终止后续多写，停用不改变 config/built revision，而是将 catchupStatus 标记为 BEHIND；重新选择前必须补齐和校验。

### 3. 双写使用版本化流水线，而不是把新向量写进旧 mapping

`VersionedIndexingPipelineRegistry` 按已成功构建的配置修订解析 parser、chunker 和 embedding 实现。向 v1 写入时使用 v1 的模型与维度，向 v2 写入时使用 v2 的模型与维度。当前部署必须保留所有 writeEnabled 版本所需的流水线实现与模型配置。

这项约束对于向量维度变化是必需的：新维度向量无法写入旧 mapping。若旧配置已不受当前程序或模型配置支持，系统必须禁止继续启用该版本并将其标记为不可切换，而不能声称已有安全热切换能力。

为了避免同一资源事件重复读取与解析，可在 built configuration snapshot 完全相同的目标间共享中间结果；快照不同时必须分别生成。缓存只限单个任务运行，不成为跨任务事实来源。

### 4. 全量构建固定范围，切换准备同时开启全版本双写与间隔补齐

管理员对 dirty v2 发起重建后，系统验证配置与模型可用性，固化 configRevision/buildManifestSnapshot，记录起始 changeEventId，并为 PAGE、ATTACHMENT 等资源类型分别记录本次 MySQL 最小/最大 ID 上界。MySQL 当前没有独立 chunks 表，因此后台所称“chunks 重建范围”实际持久化为每类源资源的 ID 范围，worker 再用目标配置确定性地产生 parent/child chunks。全量构建只扫描这个固定闭区间内的当前有效资源：ACTIVE 知识库下的已发布页面，以及现行规则允许索引的 STORED 附件。此阶段 v2 不加入普通内容多写，在线写入仍按原写目标执行；构建完成且配置修订未变化时 v2 标记 BUILT，管理端只显示“已重建”。此时不启动也不展示补齐状态，直到管理员选择该版本并进入切换准备。

扫描任务携带所读 revision/lifecycle version。写入前后继续校验数据库当前状态；过期扫描不得覆盖更晚的内容事件或复活归档数据。实时内容事件与扫描写入依赖稳定 chunk key、版本 fencing 和目标级幂等键收敛。

管理员选择从 v1 切换到已重建 v2 时，先进入异步 SWITCH_PREPARING，而不是立即移动别名。一个事务完成两件事：把所有未停用/删除的受支持版本设为 writeEnabled，使事务之后产生的 UPSERT/DELETE/ARCHIVE/RESTORE 扇出到所有启用版本；同时记录 `dualWriteStartEventId` 和当前各资源 ID 上界。随后并行执行两类间隔补齐：

1. **范围尾扫**：从全量构建记录的每类 `lastRangeId + 1` 开始，分批扫描到新的稳定上界，持久化 lastSeenId，并按配置间隔继续捕获新插入资源。
2. **变更事件重放**：按单调 `search_index_change_event.id` 从 buildStartEventId 重放到 dualWriteStartEventId，覆盖 ID 已在旧范围内但构建期间发生的新修订、删除、归档和恢复。只按资源 ID 向后扫不能发现这些旧 ID 更新，因此事件游标是必需条件。

当尾扫到达稳定上界、事件重放到达双写水位、此后实时双写目标任务没有缺口时，记录最终 catch-up barrier。只有 v2 上所有不晚于屏障的目标任务都已成功或被权威状态判定为无操作，且没有未处理删除，才能进入 VERIFYING/READY。验证通过后同一个切换请求才提交原子别名变更。进程退出后由租约恢复相同范围和游标；多实例只允许一个活动构建或切换准备协调者。

不允许在补齐未完成时先把别名切到 v2：那会让新建资源或旧资源的新修订暂时检索不到。对用户而言“发起切换”是一个可观察的异步准备过程，“别名切换”是准备完成后的最终原子提交点。

### 5. 首次构建与手动重建共用版本级互斥入口

每个版本都提供手动“重建”动作。新版本的首次全量构建和已构建离线版本的再次全量重建必须统一进入 `VersionRebuildCoordinator`，并且在创建 run、删除/重建物理索引或扫描任何数据前，先通过 kk-common `DistributedLockFactory` 申请 `kwiki:lock:index-rebuild:v{version}`。同一版本的两种入口使用完全相同的锁名、状态机和构建代码，不允许存在绕过锁的“首次创建”特殊路径。锁竞争失败返回 `409 CONFLICT/BUSY` 及当前活动 runId；不同版本可在全局并发和 embedding QPS 限额内并行重建。

协调器在一个专用执行线程中持有带 watchdog 自动续期的 SDK 锁直至该次重建结束，并在 `finally` 中确认由当前线程持有后释放。数据库同时使用“每版本最多一个活动 rebuild run”的唯一约束、租约、generation/fencing token 作为第二道防线。进程崩溃后 Redisson 锁释放，接管实例只有在原 run 租约过期并取得同一锁后才能恢复原 run；不得另建第二个活动 run。暂停会停止新批次但仍保留该 run 的活动身份和版本级互斥；取消或终态后才释放活动资格。

手动重建可以在配置没有变化时执行，用于修复数据或重新生成全部内容，并产生新的 build generation、固定范围、统计和审计记录。为避免清空当前查询或写入目标，只有非别名目标、`writeEnabled=false`、无活动补齐/切换且流水线受支持的版本允许原地手动重建；否则后端拒绝并说明需要先切走、停用，或创建下一版本。符合条件的旧物理索引可在锁内按同名重建，成功前不会改变读别名。

### 6. 校验以资源覆盖与当前性为准

切换门禁生成持久化报告，至少验证：

1. 物理索引存在，名称、mappingHash、字段类型和 dense_vector 维度与 built configuration snapshot 一致。
2. `configRevision == builtConfigRevision`，物理索引构建元数据与该 built config revision 完全一致。
3. MySQL 当前有效资源均在目标索引中存在其当前 revision/lifecycle version；已归档、已删除和旧修订不得处于可检索状态。
4. 范围尾扫、变更事件重放和实时双写均已到达同一个稳定屏障，之前没有 PENDING、RUNNING、RETRY_WAIT 或 FAILED 的目标任务。
5. ES 文档只声明该物理版本允许的 parser、chunker、embedding 和 indexVersion。
6. parent/child 引用完整、child 向量数量与维度有效，并执行有界抽样查询。
7. 资源覆盖数、范围/事件游标、parent/child 文档数、失败数及抽样结果可供管理员审阅。

parser/chunker 改变时 v1/v2 chunk 总数可以不同，因此总数只作诊断；切换依据是当前有效资源覆盖、版本一致性和结构完整性。校验有任一硬失败时版本保持 FAILED/VERIFYING，别名不变。

### 7. 单次 `_aliases` 请求是切换和回滚的原子提交点

管理服务通过 kk-common `DistributedLockFactory` 获取有界等待和租期的 Redisson 分布式锁来串行化切换操作，并在调用前确认别名恰好指向预期源索引、目标 READY 且追平。随后发送一个 `_aliases` 请求，在同一操作中移除 `kwiki-chunks` 的旧目标并添加新目标；得到 acknowledged 后再次读取别名验证唯一目标。未获取锁、Redis 不可用或线程中断时必须失败关闭，不得无锁执行别名变更；只有实际持锁线程才能在 `finally` 中解锁。

MySQL 与 ES 无法组成分布式事务。操作记录先进入 PENDING，ES 别名是“当前实际读目标”的事实来源；成功后数据库更新 selectedVersion/lastObservedAlias，原读版本是否继续写入只由其 enabled 状态决定。若进程在 ES 成功后、数据库提交前退出，启动和定时 reconciliation 会读取别名并完成数据库状态收敛，同时留下恢复审计。任何校验失败或 ES 未确认都不修改现有别名。

切换 v1→v2 后，所有未被管理员停用/删除的版本继续双写。切回 v1 之前必须确认 v1 仍启用、流水线可用且目标任务追平；切回复用相同原子操作。这样，新 Wiki 和已有 Wiki 的新修订都能在任意已启用、已追平版本上立即检索。

### 8. 版本停用、重新启用、保留和删除都是显式动作

默认建议保留最近两个物理版本（含当前读版本）。切换准备开启后，所有未停用/删除版本持续双写，不隐含只有一个候选版本。管理员可以显式“停用写入”某个非当前读版本，使其退出写目标但保留 ES 数据；此后它不再保证可直接热切换。重新启用时必须先将其加入写目标、执行范围/事件补齐并重新校验，不能直接移动别名。

删除是另一个显式动作，只允许已停用/失败且非别名目标、非构建/补齐目标的版本。管理端必须展示将删除的精确物理索引名并要求二次确认。默认保留策略用于提示，不触发后台自动删除；需要释放容量时由管理员决策。

### 9. 初始 v1 引导与已有部署采用发现/收敛

首次部署仍可在别名不存在时自动创建 v1 并绑定别名，但同时写入 v1 配置、相等的 config/built revision、selected 和 writeEnabled 元数据。若升级部署发现 ES 已有别名而数据库没有版本记录，则读取别名和 mapping，只有能与当前配置安全匹配时才收养为 selected；无法证明匹配时应用检索可继续，但管理端进入 NEEDS_ATTENTION，禁止创建升级会话或切换，直到管理员修复元数据。

应用重启绝不因配置中的 `indexVersion` 自动把别名切向更高版本。

### 10. 管理 API 和 vue-pure-admin 共用现有后端安全边界

后端新增 `/api/v1/admin/search-indexes/**`，控制器与服务均要求 `ROLE_ADMIN`，所有写操作要求幂等请求键并记录审计。API 支持自动创建下一版本、编辑非在线配置、按版本手动重建、发起切换准备、选择检索版本、停用/重新启用写入和删除；返回后端派生的“待重建/重建中/已重建/补齐中/已发布”主状态、配置修订、别名事实、范围及事件补齐进度、全版本多写健康度、校验报告和允许动作，不返回凭据、原始文档正文或完整异常堆栈。

补齐统计至少按资源类型展示区间起止、当前 lastSeenId、总量/已扫描/成功/跳过/失败/剩余、事件起止水位/当前游标/lag、吞吐、耗时和 ETA。多写统计至少展示当前写目标集合，并按目标版本展示已提交、成功、等待、重试、失败、当前 lag、最近成功/失败时间、吞吐和 embedding 调用量。统计从持久化游标和目标任务聚合，不以浏览器本地计数为事实来源，页面刷新后必须可恢复。

新增 `admin-frontend/`，以锁定版本的 vue-pure-admin 精简模板为基础，复用现有 JWT 登录接口。生产构建输出复制到 Spring Boot 静态资源 `/admin/`；匿名用户可以加载登录页静态文件，但管理 API 始终由后端角色校验。SPA history fallback 仅限 `/admin/**`，不能覆盖 `/api/**` 或 actuator。

长操作均为异步：管理端提交后获得 runId，通过有退避的轮询读取状态；页面刷新后能从 MySQL 恢复。UI 不以按钮是否隐藏代替鉴权。

### 11. 可观察性与运行限额属于正确性边界

每个版本和 run 输出结构化 INFO 日志与指标：runId、目标版本、config/built revision、dirty 状态、阶段、每类范围上下界/lastSeenId、changeEventId 水位、扫描资源数、成功/跳过/失败数、待追平任务、文档数、embedding 调用量、耗时和别名目标。错误摘要必须脱敏。

重建并发、批大小、embedding QPS、重试和暂停开关可配置。管理员可以暂停/继续或重试失败 run，但取消只停止后续扫描，不删除目标索引，也不改变读别名。容量预检必须在创建前给出预计双索引磁盘占用风险；无法确认 ES 健康或剩余容量时失败关闭。

### 12. common SDK 是唯一 Redis 与分布式锁边界

`application.yml` 将以下默认值调整为开启，同时保留环境变量作为显式覆盖：

```yaml
kk:
  common:
    redis:
      enabled: ${KK_COMMON_REDIS_ENABLED:true}
    redisson:
      enabled: ${KK_COMMON_REDISSON_ENABLED:true}
```

正式运行必须提供 `KWIKI_REDIS_HOST` 等连接参数；不提供时不得静默连接 `localhost:6379`。`.env.example`、本地 profile、部署文档和 readiness 必须同步说明 Redis 已是默认依赖。

所有业务 Redis 访问经由 kk-common `RedisUtil`，沿用 SDK 的 `kkRedisTemplate` 和序列化策略。`ScopeCache` 保持 SDK 用法；`WikiStatisticsCache` 不再使用 `StringRedisTemplate`、Lua 脚本或手写 SETNX 锁。由于当前 SDK 只提供对象/列表缓存能力，统计缓存改为在 SDK 分布式锁内读取/写回一个带 dbVersion 的整体 DTO；MySQL 继续是权威数据源，锁或 Redis 失败时请求降级到数据库并排队修复，不允许丢失已经提交的统计变化。若将来需要原子 hash/Lua 能力，应先在 kk-common 增加公共 API，再由 KWiki 升级 SDK，不能在项目内直接取底层 template。

所有跨实例应用锁经由 `DistributedLockFactory`：回收站清理、评论清理、资源索引与归档竞态、索引重建协调以及别名切换。锁名统一使用 `kwiki:lock:<purpose>[:resource]`，设置有界等待和明确租期，获取失败时跳过或返回 BUSY，处理中断时恢复中断标志，并且只在当前线程确实持有锁时于 `finally` 解锁。业务代码不得直接依赖 `RedissonClient`、自行 SETNX/解锁或执行 MySQL `GET_LOCK`/`RELEASE_LOCK`。

缓存与锁采用不同失败策略：缓存是可降级加速层，Redis 故障时回源数据库；互斥保护的管理写操作和单实例调度任务必须失败关闭或跳过本轮，绝不在没有分布式锁时继续执行。数据库 `FOR UPDATE`、唯一约束和版本 fencing 继续作为数据正确性的第二道防线。

默认测试上下文通过 `StandardTestProperties` 显式设置两个 common 开关为 `false`，保证无需 Docker/Redis。SDK 契约和需要锁的单元测试注入 mock `RedisUtil`/`DistributedLockFactory`；专门的上下文测试提供假的连接工厂/RedissonClient，验证开启后的 bean 装配而不进行外拨。架构测试禁止生产业务代码导入 `StringRedisTemplate`、`RedisTemplate`、`RedissonClient`，禁止 Redis SETNX 锁和 MySQL advisory lock 字符串，并允许 common SDK 类型只出现在批准的基础设施适配边界。

## Risks / Trade-offs

- **[所有未停用版本双写会随版本数线性放大 embedding 成本和写入压力]** → 按构建配置复用可共享结果，提供 QPS/批量/并发限制，在管理端显示每版本成本/积压并提示停用旧版本。
- **[旧模型或旧 parser 无法继续运行]** → 版本配置记录支持状态；不支持时禁止继续 writeEnabled 或声明可热切换，要求先恢复依赖或停用该版本。
- **[扫描与实时写入乱序]** → 使用 revision/lifecycle fencing、稳定文档键、目标级幂等任务以及扫描后的事件屏障校验。
- **[ES 切换成功但 MySQL 状态未提交]** → 以别名事实为准，通过 PENDING 审计和 reconciliation 自动收敛。
- **[影子索引失败被主索引成功掩盖]** → 每目标独立状态和指标；任何积压/失败阻止切换，回滚目标积压则撤销其“可热回滚”标志。
- **[重建占满 ES 磁盘或模型配额]** → 创建前容量检查、可暂停限流、默认只保留最近两个版本，旧版本仍需手动清理。
- **[管理端扩大攻击面]** → API 双层 `ROLE_ADMIN` 校验、参数白名单、幂等键、审计、无 ES 凭据下发和危险操作精确确认。
- **[单次校验后仍有新事件到达]** → 切换前建立最终屏障并短暂冻结目标集合变更；内容事件继续入队并同时面向两目标，别名切换不依赖暂停业务写入。
- **[Redis 默认启用后成为新的启动和运行依赖]** → 配置不提供 localhost 兜底，readiness 暴露真实状态，部署前验证连接；普通缓存可回源，所有依赖互斥的写操作在锁服务异常时失败关闭。
- **[整体统计 DTO 的锁内读改写吞吐低于 Lua 原子增量]** → 以每页细粒度锁限制竞争、保持短临界区并监控等待；达到瓶颈后优先扩展 kk-common 原子操作 API，而不是重新引入项目私有 template。
- **[版本级重建锁在线程切换、长任务或进程崩溃时失效]** → 首次/手动重建共用单一协调器，专用线程全程持有 watchdog 锁；数据库每版本活动 run 唯一约束、租约和 fencing 防止双执行，崩溃后只恢复原 run，并覆盖锁竞争、续期、线程所有权与接管测试。
- **[只按资源 ID 尾扫会漏掉构建期间对旧 ID 的修改]** → 同时维护固定资源范围和单调 change-event 游标；尾扫处理新增 ID，事件重放处理既有资源更新及生命周期变化。
- **[管理员编辑已构建配置会让物理内容与配置不一致]** → configRevision/builtConfigRevision 派生 dirty，在线/写入/构建中版本禁止编辑，任何 dirty 版本禁止切换。

## Migration Plan

1. 核对 kk-common 版本已提供 `RedisUtil` 与 `DistributedLockFactory`，先迁移 `WikiStatisticsCache` 及现有 MySQL/手写分布式锁，并用架构测试阻断旁路；同步让默认测试显式关闭两项 SDK 开关。
2. 将 `application.yml` 的 common Redis/Redisson 默认值改为 true，补齐环境变量和 readiness，在具备 Redis 配置的环境完成启动、故障降级与锁失败关闭演练。
3. 通过 Flyway 增加版本、run、目标任务和审计表；先部署为只记录/只观察模式，不改变现有别名。
4. 将已有 v1 与 `kwiki-chunks` 别名收养为当前检索版本，保存 configRevision=builtConfigRevision 的已构建配置；无法安全识别时标记 NEEDS_ATTENTION。
5. 改造入队与 worker 为显式目标写入，初始唯一写目标仍为 v1；验证内容发布、删除、归档和恢复行为不变。
6. 部署管理 API、reconciliation、指标及管理端，但先通过配置关闭创建/切换写操作。
7. 在测试环境通过后台自动创建/编辑 v2，执行固定范围全量重建、切换准备时开启全版本双写、范围尾扫与事件重放、校验、切换、停用/重新启用、Redis 锁竞争和进程中断恢复演练。
8. 生产开启管理写操作；结构能力随应用发版后由管理员显式配置、重建和切换，确认稳定后按成本需要停用旧版本。

若新版本切换后出现问题且 v1 仍为 enabled、已追平和校验通过，则直接选择 v1 执行原子热切换；若 v1 已停用，先异步补齐并重新校验。若服务端代码本身需要回滚，数据库新增结构保持向后兼容，旧应用仍读取 `kwiki-chunks`。在显式目标写入已启用后回滚到不认识目标表的旧应用前，必须先把写目标兼容模式恢复为单一当前版本。

## Open Questions

- 实施时锁定哪个 vue-pure-admin 精简模板版本，以及是否需要从其上游同步安全修复；版本一经选定必须固定 lockfile。
- 当前部署能够同时提供多少个 enabled 版本需要的 embedding 模型及凭据；不受支持的旧版本必须先停用，不能进入全版本双写或声明可热切换。
- 生产 ES 的容量水位和 embedding QPS 上限需要由部署配置给出；在没有明确值前使用保守默认值并禁止自动放大并发。
