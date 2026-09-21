## ADDED Requirements

### Requirement: Graph enhancement integrates with the active QA retrieval workflow
图增强 SHALL 接入 `LangGraphAgenticWorkflow` 当前 CHILD 检索阶段，保持生成、QA、PARENT 补充和有界恢复的现有顺序。图预算 MUST 由应用管理，所有轮次累计受限；DIRECT 路径和原有 es_search 的 ES 契约 SHALL 保持兼容，模型 MUST NOT 指定任意图 DSL、授权范围或活动版本。

#### Scenario: Graph evidence is available on the first child retrieval
- **WHEN** 初次知识检索取得有效 Chunk 和图补充证据
- **THEN** 合并后的有界证据进入既有生成/QA，不能因图存在而跳过 QA 或提前展开所有 PARENT

#### Scenario: Query rewrite starts another retrieval round
- **WHEN** QA 恢复流程改写查询并再次执行图增强
- **THEN** 新轮次共享该 run 剩余的访问、时间和上下文预算，不从零重置额度

### Requirement: Seeds and community constraints have explicit fallback behavior
系统 SHALL 仅对通过全来源权限与选择范围门禁的知识库，从当前有效且授权的 ES CHILD 命中读取 READY 的 entityIds，校验来源和实体映射代际后按 Chunk 排名去重取得 seed entity，MUST NOT 为定位种子执行 Chunk → MENTIONS → Entity 图查询。后续有界图扩展 SHALL 批量验证实体和支持来源属于固定快照，并在允许使用的命中社区内扩展。已通过权限/范围门禁但社区零命中或服务故障时 SHALL 支持同预算 seed-only 路径；权限/范围不完整 MUST NOT 使用该回退。无 Chunk 种子但有允许的社区时 SHALL 仅从少量代表实体且有有效来源的种子启动。没有安全种子时 MUST 跳过图扩展。

#### Scenario: Partial-access user has annotated chunk hits
- **WHEN** 用户对该库存在不可读来源，但其可读 CHILD 已有 READY entityIds
- **THEN** 系统仍不使用这些 ID 访问图数据库，按现有 Chunk、生成、QA 和 PARENT 恢复流程执行

#### Scenario: Redisson question has matching chunks and community
- **WHEN** Chunk 命中 Redisson、Watchdog、RLock，社区命中 Redis 分布式锁主题
- **THEN** 系统从这些种子在对应社区内有界检索关系，返回关联原文而非整社区实体

#### Scenario: Several child hits already contain entity identifiers
- **WHEN** 多个有效 CHILD 命中提供版本匹配的 READY entityIds
- **THEN** 系统直接取去重种子，将实体及支持 sourceChunkId 合并交给图扩展，不逐 Chunk 发起图查找

#### Scenario: Entity identifiers are unavailable or empty
- **WHEN** 命中 CHILD 的实体字段缺失、PENDING/FAILED，或 READY 但 entityIds 为空
- **THEN** 该 Chunk 不提供种子，不回查 MENTIONS 或在线调用模型抽实体；保留 Chunk 证据，允许使用安全社区代表实体路径，可修复缺失交由后台补齐

#### Scenario: Entity mapping generation does not match the pinned graph
- **WHEN** ES 命中的 entityLinkingVersion 不同于固定图快照要求
- **THEN** 忽略该命中的种子元数据并记录映射不兼容，不把两个代际的实体集合混合

#### Scenario: A new source mentions an entity already in the snapshot
- **WHEN** 新 Chunk 的 entityId 恰好存在于旧快照，但其 sourceChunkId 不属于该快照的来源
- **THEN** 批量图扩展校验拒绝该来源对应的种子，不仅凭实体 ID 已存在就视为快照兼容

#### Scenario: Seeds do not intersect retrieved communities
- **WHEN** Chunk 种子均不属于 Top 社区
- **THEN** 系统执行受原有范围与预算限制的 seed-only 回退，不改写成员关系或扩大到全图扫描

#### Scenario: Community hit is the only recall result
- **WHEN** Chunk 搜索成功但零命中，社区路径找到代表实体
- **THEN** 只有代表实体及扩展关系可回查到当前可读原文时才能补充证据，否则保持无证据状态

### Requirement: Every traversal step enforces scope and current evidence
每个种子、中间节点、关系及目标节点 MUST 属于固定 kbId/graphVersion，并有当前可读且属于用户所选范围的来源；适用时还 MUST 满足允许社区集合。部分来源可见用户 MUST 不进入该库图路径；即使先前全来源门禁通过，遍历及出站也 MUST 继续验证当前来源，实体名称和别名 MUST 由当前可见 mention 支持。

#### Scenario: An unreadable relation connects two readable entities
- **WHEN** 先前门禁通过，但运行中连接关系来源变为不可读
- **THEN** 该边不可返回也不可作为到第二跳的桥梁，并触发既有权限变化处理

#### Scenario: Canonical description contains private terminology
- **WHEN** 实体的全库聚合描述或别名包含私有文档内容
- **THEN** 权限不完整的请求不进入该库图路径，已在途请求复核失败时停止输出这些字段

### Requirement: Traversal bounds apply to actual work and returned context
图遍历 SHALL 受服务端 hop、种子数、返回实体/关系数、每节点邻边读取数、全请求检查边数、deadline 和上下文预算约束。首版 hop MUST 不超过 2；被过滤边仍计入检查量。系统 MUST NOT 通过先加载全图/整社区、再在客户端裁剪来实现限制。

#### Scenario: Hub node has thousands of incident edges
- **WHEN** 访问高扇出节点达到邻边检查上限
- **THEN** 系统停止该节点进一步读取，按全请求预算返回已有结果并标记 truncated

#### Scenario: Most inspected edges are unauthorized
- **WHEN** 可见结果不足 TopK，但实际已检查边数达到上限
- **THEN** 系统返回较少结果，不继续无限翻页寻找更多可见边

#### Scenario: Graph query times out
- **WHEN** 图查询超过增强 deadline 或 run 剩余时间
- **THEN** 停止/取消后续图工作并保留成功的 Chunk 证据，不等待图完成后才返回

### Requirement: Original evidence merging and citation integrity
图返回的关系 SHALL 转换为携带来源身份的当前 CHILD 证据，与原有结果去重、排序并接受同一阶段 finalTopK 和上下文总预算。社区摘要、关系说明也 MUST 计入总预算；最终引用 MUST 指向实际保留的页面/附件原文，不指向虚构的 Entity 或 Community 文档。

#### Scenario: Graph and chunk search find the same child
- **WHEN** 两条路径找到相同 sourceChunkId
- **THEN** 上下文只保留一份正文及合并后的来源说明，同一列表重试不重复增加排名贡献

#### Scenario: Context truncation removes supporting evidence
- **WHEN** 预算裁剪移除某关系断言唯一的来源正文
- **THEN** 对应图断言及引用一并移除，不能保留脱离证据的结论

### Requirement: Graph failures do not weaken retrieval or authorization semantics
ArcadeDB/社区索引不可用、无兼容快照或图超时 SHALL 导致可观察的增强降级，保留成功的原有检索。全部 Chunk 基础设施分支失败 MUST 保持 retrieval-failed；权限/生命周期无法验证 MUST 失败关闭，不能当作普通增强失败继续输出。

#### Scenario: ArcadeDB fails but chunk search succeeds
- **WHEN** 图扩展失败而 Chunk 搜索取得有效证据
- **THEN** 回答可继续使用 Chunk，并记录 graph-unavailable 类原因

#### Scenario: All chunk retrieval branches fail
- **WHEN** Chunk 检索因基础设施错误全部失败但社区摘要仍可查
- **THEN** 请求仍标记 retrieval-failed，不能只靠摘要调用回答模型伪装成功

#### Scenario: Authorization changes before model input
- **WHEN** 图召回之后、入模之前检测到权限版本改变
- **THEN** 停止使用已失效图证据并按现有授权变化流程结束/重新检索
