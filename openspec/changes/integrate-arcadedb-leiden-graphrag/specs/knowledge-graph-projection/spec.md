## ADDED Requirements

### Requirement: ArcadeDB graph projection with traceable source identity
系统 SHALL 将已发布 PAGE 和可索引 ATTACHMENT 的实体、语义关系、来源 Chunk 和社区结构持久化到 ArcadeDB。每份图记录 MUST 属于明确的 kbId 和 graphVersion；来源身份 MUST 包含资源、修订、生命周期、Chunk 索引/分块流水线、chunkKey 和内容 hash。原文及授权的权威来源 SHALL 继续是现有业务存储。

#### Scenario: Same chunk ordinal occurs in different pipelines
- **WHEN** 两个索引流水线为同一页面产生相同 chunkKey 但分块内容不同
- **THEN** 系统生成不同 sourceChunkId，种子实体和引用不得互相复用

#### Scenario: Unpublished draft exists
- **WHEN** 页面有已发布修订和未发布草稿
- **THEN** 默认图构建仅使用已发布修订，不将草稿事实放入线上图快照

### Requirement: Validated extraction and conservative entity resolution
系统 SHALL 使用版本化的实体关系抽取契约，校验输出结构、受控实体/关系类型、端点身份和原文证据。每条持久化关系 MUST 有真实来源；有歧义的同名实体 MUST 保留区分，实体合并 MUST 限于同一知识库并可追溯。模型输出 MUST NOT 创建任意数据库类型、执行查询或扩大授权。

#### Scenario: Model invents a relation or source reference
- **WHEN** 抽取结果包含不在输入中的 sourceChunkId、无法定位的引文或不存在的关系端点
- **THEN** 该结果被拒绝并进入有上限的重试或失败状态，不进入可发布事实图

#### Scenario: Same name refers to different concepts
- **WHEN** 两个来源中的同名词在类型或上下文上有歧义
- **THEN** 系统保留不同 entityId，并记录消歧版本而非仅按名称合并

### Requirement: Durable asynchronous extraction independent of chunk indexing
图抽取 SHALL 使用由持久化内容事件驱动的独立任务，在所需 Chunk 产出成功后执行。幂等键 SHALL 包括来源和抽取/prompt/消歧版本，重试 MUST NOT 重复创建逻辑事实或放大支持数。图失败 MUST NOT 回滚内容发布或把已经成功的 Chunk 索引标记为失败。

#### Scenario: Process exits after successful chunk indexing
- **WHEN** Chunk 已写入 ES，但进程在发起抽取前退出
- **THEN** 持久化事件消费者恢复后仍能创建/恢复相同来源的抽取任务

#### Scenario: Same extraction job is delivered twice
- **WHEN** 相同幂等键的任务重复投递且两次结果均成功
- **THEN** 图中同一来源贡献只计一次，supportCount 不增加第二次

#### Scenario: ArcadeDB is temporarily unavailable
- **WHEN** 内容发布和 Chunk 索引已成功但图写入超时
- **THEN** 原有内容与 Chunk 检索继续可用，图任务独立记录失败并有界重试

### Requirement: Denormalized entity identifiers in Elasticsearch child chunks
系统 SHALL 在支持图增强的 ES CHILD 文档中持久化去重稳定排序的 keyword 多值 `entityIds`、`sourceChunkId`、`entityLinkingVersion` 和 `entityLinkingStatus`，与原文搜索命中一同返回。实体映射 MUST 仅包含该来源支持的实体，PARENT MUST NOT 聚合子块实体字段。实体映射代际 SHALL 固定抽取/prompt/消歧版本，相同来源及代际 MUST 复用同一持久化结果；单纯重新执行 Leiden MUST NOT 要求重写全部 Chunk 的 entityIds。

#### Scenario: Search returns an annotated child
- **WHEN** 授权范围内的 CHILD 命中且实体状态为 READY
- **THEN** BM25/vector 适配器、ChunkHit、ChildEvidence 和融合结果均保留 entityIds 与映射/来源身份，种子定位无需再次访问图数据库

#### Scenario: Extraction finds no entities
- **WHEN** 一个 Chunk 已成功完成抽取但没有实体
- **THEN** 保存 READY 与空 entityIds，区别于 PENDING、FAILED 和旧索引字段缺失

#### Scenario: Leiden changes community labels only
- **WHEN** 抽取/消歧代际和来源未改变，仅重建社区得到不同 communityId
- **THEN** 原有 entityIds 保持有效，Chunk 不冗余随该次聚类变化的 communityId 或 graphVersion

### Requirement: Fenced asynchronous entity mapping backfill
ES 实体字段 SHALL 由持久化独立目标任务异步回填到指定物理索引。更新 MUST 同时匹配来源、修订/生命周期、流水线与预期实体映射代际，仅变更实体字段，MUST NOT upsert 已删除 Chunk、覆盖正文/向量或把旧映射附到新内容。ArcadeDB 投影及 ES 回填 SHALL 分别记录完成状态并从同一抽取结果幂等恢复。

#### Scenario: Resource changes before entity backfill completes
- **WHEN** 回填任务的 sourceChunkId 或 revision/lifecycle 已不同于目标文档
- **THEN** 更新被拒绝或作废，不把旧 entityIds 写入当前文档

#### Scenario: Target child has been deleted
- **WHEN** 延迟回填发现目标 CHILD 不存在
- **THEN** 不使用 upsert 重建该文档，后台按当前来源状态决定跳过或重新安排合法索引任务

#### Scenario: One derived store succeeds before a crash
- **WHEN** ArcadeDB 写入或 ES 实体回填已成功，另一目标尚未完成时进程退出
- **THEN** 恢复后复用持久化抽取结果只补齐未完成目标，重复投递不会新增实体或再次累加来源

#### Scenario: Full chunk indexing overwrites an annotated document
- **WHEN** 普通 Chunk 重建覆盖已有 CHILD
- **THEN** 复用相同来源/代际的实体字段，或设为 PENDING 并持久化入队回填，不永久丢失映射

### Requirement: Version-managed entity mapping schema
系统 MUST 通过既有 Chunk 索引版本机制引入实体字段，并将 entityLinkingVersion 固定到该物理索引构建配置和配套图清单。旧严格 mapping MUST NOT 收到未知实体字段。需要改变抽取/消歧映射代际时 SHALL 创建新物理索引，旧索引和配对图快照保持可读。

#### Scenario: Existing strict index lacks entity fields
- **WHEN** 部署发现在线旧索引没有实体字段 mapping
- **THEN** 旧索引继续普通检索；新能力在受支持的新版本索引构建并按现有流程切换，不直接回填未知字段

#### Scenario: Entity resolver generation changes
- **WHEN** 新消歧规则使相同来源对应不同实体集合
- **THEN** 新实体映射代际写入新的 Chunk 物理索引并配对新图快照，不覆盖旧索引中的实体语义

### Requirement: Source-level lifecycle invalidation
系统 MUST 在原文修订更新、归档、删除、恢复及权限变化时推进对应持久化 epoch 并安排投影刷新；在线图证据 MUST 验证当前修订、生命周期、授权和所选范围，不等待下一轮 Leiden。撤销一个来源 MUST NOT 删除其他有效来源支持的同一逻辑关系。

#### Scenario: One of two supporting documents is archived
- **WHEN** 同一关系有两个来源，其中一个来源归档
- **THEN** 归档来源立即失去在线资格，仍可读且当前有效的另一个来源可继续支持该关系

#### Scenario: An old job completes after resource deletion
- **WHEN** 携带旧 revision/lifecycle 的任务在删除或归档之后完成
- **THEN** 版本 fencing 阻止其恢复资源的在线证据资格

#### Scenario: Restored resource has a new lifecycle identity
- **WHEN** 页面从回收站恢复
- **THEN** 新投影使用当前生命周期重新验证/构建，不能仅清除旧快照上的失效标记就视为有效

### Requirement: Indexed bounded graph lookups
ArcadeDB SHALL 建立 `(kbId, graphVersion, entityId)` 唯一索引、`(kbId, graphVersion, communityId)` 成员索引及来源 Chunk 唯一定位索引。在线查询 MUST 从有界种子或有界成员定位出发，实际访问路径 MUST 通过目标版本的查询计划及高扇出场景验证。

#### Scenario: Community has thousands of members
- **WHEN** 请求只需查询该社区中的两个种子实体
- **THEN** 系统按种子键定位并有界读取邻接关系，不先加载该社区所有实体和关系
