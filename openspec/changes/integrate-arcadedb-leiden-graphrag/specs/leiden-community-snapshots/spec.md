## ADDED Requirements

### Requirement: Offline Leiden on an isolated entity-only graph
系统 SHALL 按知识库及冻结来源清单离线执行 ArcadeDB 内置无权 Leiden。系统 SHALL 在用户独立部署的同一个 ArcadeDB 服务中创建仅含该清单 Entity 与去重 CONNECTED 边的临时数据库，不要求另部署数据库实例；Document、Chunk、Community、MENTIONS、CONTAINS 和 IN_COMMUNITY MUST NOT 参与聚类。线上查询 MUST NOT 触发 Leiden。外部加权 worker 和权重算法不在本期实现契约内。

#### Scenario: Main graph has multiple knowledge bases and snapshots
- **WHEN** 为知识库 A 的新快照运行社区发现，主库同时有 B 和 A 的旧快照
- **THEN** 算法只处理 A 新快照清单的实体投影，其他库、旧快照及结构顶点均不进入算法输入

#### Scenario: One entity appears in many chunks
- **WHEN** 某实体被大量 Chunk 提及但其语义关系未改变
- **THEN** MENTIONS 数量不会通过结构边或重复 CONNECTED 边直接改变无权投影

#### Scenario: Online question arrives during a build
- **WHEN** 用户发起知识问答而后台正在计算 Leiden
- **THEN** 请求只使用此前已发布的兼容快照或降级到已有检索，不等待或启动聚类

### Requirement: Explicit unweighted projection and algorithm configuration
首版 SHALL 使用原生无权 Leiden，将允许的 Entity–Entity 关系按无序端点去重、移除自环并保留孤立实体。系统 MUST 持久化引擎版本、算法模式、maxIterations、resolution 和投影 hash；MUST NOT 宣称 confidence 或 supportCount 作为原生 Leiden 权重参与优化，也不得静默替换算法。

#### Scenario: Two nodes have repeated and reciprocal relations
- **WHEN** 两实体之间存在多个来源、双向关系及不同 predicate
- **THEN** 原生聚类投影只有一个 CONNECTED 连接，正式图仍保留有向 predicate 和各自证据

#### Scenario: Input contains isolated entities or is empty
- **WHEN** 投影含孤立实体或完全无实体
- **THEN** 孤立实体分别得到有效成员结果；空图构建为合法空快照，不调用模型编造摘要

#### Scenario: Deployment lacks the required Leiden capability
- **WHEN** 固定部署版本的能力探测不支持已约定调用或输出字段
- **THEN** 图构建明确失败，既有检索继续可用，不自动换用其他算法

### Requirement: Immutable versioned membership
社区身份 MUST 为 `(kbId, graphVersion, communityId)`，本期 communityVersion SHALL 与 graphVersion 同义。每个快照实体 SHALL 恰有一个社区归属，成员属性和成员边 MUST 一致；READY 及已发布快照 MUST 不可变，新构建 MUST NOT 覆盖旧实体的 communityId。

#### Scenario: Algorithm reuses a numeric community label
- **WHEN** 版本 41 的 communityId=17 与版本 42 的 communityId=17 包含不同实体
- **THEN** 两者是不同社区，ES 命中及图遍历必须匹配完整版本身份

#### Scenario: Previous request still reads the old version
- **WHEN** 新版本已写入或已发布，旧请求固定的是版本 41
- **THEN** 旧请求读取版本 41 的成员和关系，不被新版本写回覆盖

### Requirement: Complete source manifest and publish validation
构建 SHALL 固定完整资源身份清单、Chunk 目标流水线、entityLinkingVersion、内容/安全 epoch 和事件处理水位。系统 MUST 在所有必需抽取、成员、摘要、embedding 及索引校验完成后才标记 READY；其中来源清单内 CHILD 的 ES 实体字段 MUST 已 READY、刷新可读，并与同代际持久化抽取结果及图 MENTIONS 一致，成功空实体集合为合法结果。事件游标 MUST 不跨过未处理缺口，来源变化 MUST 使候选失效而不是混合清单。

#### Scenario: Graph is ready but Elasticsearch entity backfill is incomplete
- **WHEN** 图和社区摘要均已完成，但来源清单中仍有 CHILD 的实体映射待回填或与图抽取结果不一致
- **THEN** 候选不能标记 READY 或发布，恢复回填并重新校验后才可继续，普通 Chunk 检索保持可用

#### Scenario: One community summary fails
- **WHEN** 一个非空社区的摘要或 embedding 最终失败
- **THEN** 候选不能以“完整快照”发布，已有在线快照保持不变

#### Scenario: A lower event identifier is still pending
- **WHEN** 高序号事件已处理而同一消费范围中的低序号事件尚未完成
- **THEN** 系统不把高序号当作完整追平水位，验证报告显示缺口

#### Scenario: Source changes before publication
- **WHEN** 构建捕获的 content/security epoch 与发布事务中的当前值不同
- **THEN** 候选标记 STALE，重新捕获/构建后才能发布，在线指针不变

### Requirement: Single publication pointer and pinned read targets
系统 SHALL 为每个 `(kbId, chunkIndexVersion)` 配对使用一个 MySQL 发布指针关联完整 GraphSnapshot 清单，该清单 MUST 绑定图版本、两个明确 ES 版本及物理索引名。发布 SHALL 使用带预期旧值和 epoch 校验的事务更新；查询 MUST 按固定的 Chunk 版本选取配套清单并用物理索引名查询。ES alias 与单独 activeGraphVersion 的两次更新 MUST NOT 被视为跨系统原子发布。

#### Scenario: Candidate chunk version gets a graph before chunk alias selection
- **WHEN** 当前 Chunk v3 配套图已发布，后台为候选 Chunk v4 发布了配套图
- **THEN** v3 的请求继续使用 v3 配对，v4 配对不会覆盖 v3 指针或自动切换 Chunk 别名

#### Scenario: Graph and community index are ready but process exits before pointer commit
- **WHEN** 外部数据已完成而 MySQL 发布事务尚未提交时进程退出
- **THEN** 新请求仍使用旧指针，新候选可恢复发布或清理，不产生混版读

#### Scenario: Pointer commits while a request is in flight
- **WHEN** 已固定旧快照的请求执行期间，新发布事务提交
- **THEN** 该请求继续读旧图与旧社区物理索引，后续新请求读取新配对

#### Scenario: Chunk index selection changes to an incompatible pipeline
- **WHEN** 当前固定的 Chunk 索引没有兼容的图快照
- **THEN** 系统跳过该图增强并记录 version-incompatible，不使用旧 chunkKey 强行查种子

### Requirement: Safe rollback and retention
系统 SHALL 支持切回完整且 Chunk 流水线兼容的历史快照，但 MUST 继续执行当前来源及 epoch 检查。删除 SHALL 排除活动发布目标、构建目标及仍被请求固定的快照，并遵守覆盖最大请求时长的退役宽限期和读取租约。

#### Scenario: Rollback snapshot predates a source edit
- **WHEN** 管理员切回较早快照，其中摘要 epoch 已过期
- **THEN** 旧摘要不可用于回答，仍有效的图事实须逐条验证来源后才能使用

#### Scenario: Cleanup races with snapshot pinning
- **WHEN** 清理进程准备删除退役快照
- **THEN** 它先使快照进入不可接受新 pin 的状态，再确认宽限期及既有读取租约已结束后清理
