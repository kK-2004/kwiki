## ADDED Requirements

### Requirement: Optional graph integration and verified deployment capability
ArcadeDB SHALL 由用户独立部署，本项目 SHALL 提供 endpoint、数据库、读/构建身份、连接/查询/批写/算法超时、TLS、并发和临时库前缀配置，以及健康/能力探测；MUST NOT 自动部署或启动数据库服务。图功能 SHALL 默认关闭，关闭时不建立 ArcadeDB 连接且现有内容/检索链路可用。开启时 MUST 显式提供服务连接、受控数据库目标与凭据，并验证已锁定引擎版本、Leiden 契约及图 schema；MUST NOT 静默连接默认 localhost、采用默认密码或替代算法。后台 MUST NOT 回显密码。

#### Scenario: Graph feature is disabled
- **WHEN** 应用在未配置 ArcadeDB 的既有环境启动且 graph.enabled=false
- **THEN** 原有功能保持可用，不尝试探测或连接 ArcadeDB

#### Scenario: Enabled graph has incomplete configuration
- **WHEN** 已开启图功能但 endpoint、凭据或算法能力不完整
- **THEN** 图服务显示不可用/配置错误，不执行图任务或发布不完整快照

### Requirement: Durable observable build runs with bounded concurrency
系统 SHALL 提供每日 Asia/Shanghai 02:00 的应用内全量构建调度，以及现有管理后台发起的 ALL/KNOWLEDGE_BASE 范围手动构建，默认不开启变化阈值触发。系统 SHALL 持久化父批次及按库子任务的阶段、检查点、来源水位、计数、耗时、成本和脱敏错误。每库 SHALL 至多有一个活动构建，全局默认 SHALL 只运行一个知识库构建和一个临时投影库；超限 MUST 明确失败而非发布截断图。

#### Scenario: Two application instances reach the daily trigger
- **WHEN** 两实例同时在 Asia/Shanghai 02:00 调度同一天任务
- **THEN** 持久化日期唯一键和分布式锁只创建一个全量批次，批次固定提交时所有未归档且启用图构建的知识库集合

#### Scenario: Administrator chooses one knowledge base
- **WHEN** 管理员选择 KNOWLEDGE_BASE 并提交库 A
- **THEN** 只为 A 生成完整图/社区构建子任务，复用合法抽取缓存，不清空其他库或所有历史 Chunk 索引

#### Scenario: One child of a full build fails
- **WHEN** 全量批次中 A 成功、B 失败
- **THEN** 其他库继续执行，父批次显示 PARTIAL_FAILED 及各子任务结果，不能显示全局成功

#### Scenario: Scheduler resumes after missing the daily time
- **WHEN** 应用在当日 02:00 之后恢复且该日期尚无已处理调度记录
- **THEN** 当日最多补触发一次，不补跑所有历史日期；有未完成批次时先恢复并记录 SKIPPED_ACTIVE 及关联任务

#### Scenario: Manual and scheduled build arrive simultaneously
- **WHEN** 同一知识库的手动构建与定时任务同时触发
- **THEN** 仅一个活动 run 获得执行资格，另一请求返回已有 run 或 BUSY，不创建并发重建

#### Scenario: Input exceeds configured capacity
- **WHEN** 完整实体/边投影超过容量预算
- **THEN** run 以 CAPACITY_EXCEEDED 结束或等待运维处理，不裁剪输入后声明全量构建成功

### Requirement: Explicit configurable graph capacity limits
系统 SHALL 默认限制每库每快照 50,000 Entity、250,000 去重有向语义关系、1,000,000 关系来源记录和 5,000 社区摘要，并在 20,000 Entity 或 100,000 语义关系时预警。后台 SHALL 展示这些可配置阈值和实际计数。系统 MUST 在超限时阻止该库构建/发布，MUST NOT 丢弃孤点、关系或来源来伪造完整结果。数值 SHALL 作为初始保护阈值而非未经验证的吞吐保证。

#### Scenario: Relation evidence grows faster than logical edges
- **WHEN** 实体/逻辑关系未超限，但关系来源记录超过 1,000,000
- **THEN** 系统仍拒绝继续构建并显示来源记录容量原因，不只检查实体和边数

#### Scenario: Community count exceeds summary budget
- **WHEN** Leiden 输出超过 5,000 个社区且配置未提高该上限
- **THEN** run 明确报告成本/容量超限，不能只生成前 5,000 个摘要后发布

### Requirement: Immutable dual Elasticsearch version targets on every graph build
每个图构建批次及其子任务 MUST 持久化并展示 chunkIndexVersion 和 communityIndexVersion 两个独立版本标记；按库子任务 MUST 额外记录两个物理索引名及 graphVersion。默认 Chunk 目标 SHALL 在提交时从当前读别名解析，手动任务 SHALL 允许选择已构建、配置未脏且支持实体 schema 的候选；COMMUNITY 版本 SHALL 为本批次新分配。运行、重试、回填和审计 MUST 继承原版本对，MUST NOT 在执行时跟随别名漂移。

#### Scenario: Chunk alias switches during a graph build
- **WHEN** 任务固定 CHUNK v3 / COMMUNITY v7 后，管理员把 Chunk 读别名切为 v4
- **THEN** 该任务及重试继续使用 v3 和 v7，后台保持原双标记，不把 entityIds 或摘要写入 v4 对应目标

#### Scenario: All-scope batch has multiple child indexes
- **WHEN** 一个批次包含知识库 A 和 B
- **THEN** 父子任务都显示相同版本对，子任务分别展示自己的社区物理索引和图版本，父任务不显示虚构的单一社区物理目标

#### Scenario: Target chunk version is being deleted or rebuilt in place
- **WHEN** 管理员尝试删除、原地重建或编辑仍被图任务引用的 Chunk 版本
- **THEN** 索引管理后端拒绝并显示依赖任务；普通内容更新与合法别名切换仍按原流程执行

#### Scenario: Source index is missing required chunks
- **WHEN** 目标 Chunk 版本未追平完整来源清单或不支持 entityIds schema
- **THEN** 任务显示 WAITING_FOR_CHUNKS 或 UNSUPPORTED，不能忽略缺失资源后开始聚类并宣称完整

### Requirement: Fenced recovery and owned temporary-resource cleanup
构建与发布锁 MUST 使用现有 common SDK 分布式锁边界，配合数据库租约及 fencing。失锁旧 worker MUST 不得提交检查点或发布；进程恢复 SHALL 接管合法过期 run。临时数据库 SHALL 按 run 所有权清理，取消或失败后的清理 SHALL 可重试。

#### Scenario: Worker continues after its lease is superseded
- **WHEN** 新 worker 已接管且 fencing token 推进，旧 worker 才完成外部写入
- **THEN** 旧 worker 无法推进权威状态或发布，候选脏输出被隔离并可清理

#### Scenario: Cancelled build leaves a temporary database
- **WHEN** 管理员取消运行而临时库首次清理失败
- **THEN** 记录该 run 的待清理资源，后续仅重试清理其拥有的数据库，不删除其他运行或在线图

#### Scenario: Algorithm HTTP request times out but server status is unknown
- **WHEN** Leiden 调用超过默认 15 分钟或被取消，尚未确认远端算法结束
- **THEN** 保留算法槽位和临时库，探测或取消远端执行；状态无法确认时标记 NEEDS_ATTENTION，不能立即删除运行中的库或启动下一个算法

### Requirement: Administrative control and reviewable status
系统 SHALL 在现有 `admin-frontend`、`/admin/` 认证/路由中提供图状态、全量/单库构建、重试/取消、校验、发布、回滚和删除功能，并提供 `ROLE_ADMIN` 保护的对应 API。索引管理 SHALL 并列呈现 CHUNK/COMMUNITY 类型、版本/物理索引/配置/存储/校验/发布状态；图任务列表及详情 SHALL 显示两个 ES 版本徽标和可定位的父子任务。写操作 MUST 使用幂等键并审计，长任务 SHALL 返回 batchId/runId；默认 SHALL 由管理员显式发布，只有 autoPublish 配置开启时才自动发布合格快照。MUST NOT 新建独立后台应用或把社区索引直接交给 Chunk parser 重建入口。

#### Scenario: Administrator opens existing index management
- **WHEN** 用户在现有后台查看 COMMUNITY 索引类型
- **THEN** 可见社区版本下各知识库物理索引、配对 Chunk/图版本、数量/存储量、构建任务和发布结果，且原 CHUNK 管理功能保留

#### Scenario: An original chunk-only task has no community target
- **WHEN** 后台展示纯 Chunk 索引任务
- **THEN** CHUNK 显示真实版本，COMMUNITY 显示“不涉及”，不复制 Chunk 版本伪装成社区版本

#### Scenario: Ordinary knowledge-base member invokes a build endpoint
- **WHEN** 非平台管理员直接请求图管理 API
- **THEN** 后端拒绝操作，不能仅依赖管理按钮隐藏

#### Scenario: Browser refreshes during a long build
- **WHEN** 管理员刷新页面或应用实例重启
- **THEN** 页面从持久化 run 恢复阶段/统计，不把浏览器本地计数作为构建事实

#### Scenario: Duplicate publish request is delivered
- **WHEN** 相同幂等键的发布请求再次到达
- **THEN** 返回原操作结果，不产生额外版本切换或重复审计动作

### Requirement: Snapshot deletion protects readers and existing index management
系统 MUST 拒绝删除当前发布、活动构建或仍被读取的快照；删除 SHALL 仅操作其清单拥有的图记录和社区索引。图管理 MUST NOT 移动/删除 `kwiki-chunks` 别名或其物理索引，Chunk 版本兼容性不足时 SHALL 降级图增强。

#### Scenario: Administrator requests deletion of the active graph snapshot
- **WHEN** 删除目标仍是 GraphPublication 指向的快照
- **THEN** 系统拒绝并返回不可删除原因，保持在线读取不变

#### Scenario: Community index deletion succeeds but graph cleanup fails
- **WHEN** 退役清理在两个存储之间部分成功
- **THEN** 快照保持 DELETING 并记录剩余资源供幂等重试，不重新变为可发布或可读

### Requirement: Safe operational metrics and retrieval traces
系统 SHALL 记录图构建/检索阶段、版本、允许披露的计数、耗时、预算截断、降级原因与模型使用量，并在后台提供 ES 实体映射回填覆盖率、积压和失败数。在线种子阶段 SHALL 记录为 ES 元数据提取，不将其伪装为图查询。普通日志 MUST 脱敏且不包含文档正文、隐私实体名或凭据；用户可见轨迹 MUST 遵守原文授权和所选范围，不能披露被社区门禁排除的内容与数量。

#### Scenario: Community gate rejects a private source snapshot
- **WHEN** 用户此次请求不能使用某库社区
- **THEN** 面向用户的轨迹仅说明增强不可用或已降级，不返回隐藏社区标题、成员或规模

#### Scenario: Graph expansion reaches its edge budget
- **WHEN** 图遍历在检查边上限处截断
- **THEN** 轨迹记录预算原因、实际允许披露的结果数与耗时，便于区分截断、零命中和服务失败
