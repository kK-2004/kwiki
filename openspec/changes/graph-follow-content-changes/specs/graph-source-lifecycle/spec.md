## ADDED Requirements

### Requirement: Content and security changes advance graph source epochs transactionally
系统 SHALL 在页面发布、回滚修订、删除，附件上传、替换、删除，页面、附件、知识库归档与恢复，以及回收站彻底删除时，在同一业务事务内推进该知识库的 content epoch。系统 SHALL 在权限作用域版本（`scope_version`）推进的同一事务内推进该知识库的 security epoch。业务事务回滚时 epoch MUST NOT 前进。图功能关闭时推进 MUST 为无副作用的空操作，且 MUST NOT 使业务写入失败。

#### Scenario: Publishing a revision advances the content epoch
- **WHEN** 用户发布页面新修订，事务提交
- **THEN** 该知识库的 content epoch 加一，并与页面索引任务在同一事务中持久化

#### Scenario: Business write rolls back
- **WHEN** 归档事务因冲突回滚
- **THEN** content epoch 保持原值，不产生待重建记录

#### Scenario: Page audience changes
- **WHEN** 页面可见范围从全库成员改为指定成员
- **THEN** 该知识库的 scope_version 与 security epoch 在同一事务中各加一

#### Scenario: Graph feature is disabled
- **WHEN** `kwiki.graph.enabled=false` 时用户编辑页面
- **THEN** 页面发布照常成功，不访问图相关表

### Requirement: Three-level graph gate distinguishes retired sources from unreadable sources
系统 SHALL 把快照来源清单中不再处于有效状态（PAGE 非 ACTIVE、ATTACHMENT 非 STORED，或业务记录已不存在）的资源计为「已退役来源」，不参与可读性与选择范围校验。每个知识库的门禁 SHALL 按以下顺序得出档位：
- 当前有效来源（当前有效可索引资源 ∪ 快照清单中仍有效的资源）中有用户不可读或不在本次选择范围内的 → `OFF`；
- 否则，若快照的 content 或 security epoch 与当前值不一致，或存在已退役来源 → `FACTS_ONLY`；
- 否则 → `FULL`。

`OFF` MUST 保持现有语义：不查社区、不做任何图扩展。

#### Scenario: One page in the snapshot is archived
- **WHEN** 快照发布后，清单中的一个页面被归档，用户可读其余全部有效来源
- **THEN** 该库门禁为 `FACTS_ONLY`，不是 `OFF`

#### Scenario: Snapshot contains a private page the user cannot read
- **WHEN** 清单中一个仍为 ACTIVE 的页面对该用户不可读
- **THEN** 该库门禁为 `OFF`，与是否存在已退役来源无关

#### Scenario: Only the content epoch is behind
- **WHEN** 快照内全部来源仍有效且可读，但其后有页面发布了新修订
- **THEN** 该库门禁为 `FACTS_ONLY`

#### Scenario: A member is added to the knowledge base
- **WHEN** security epoch 因新增成员而前进，用户仍可读全部有效来源
- **THEN** 该库门禁为 `FACTS_ONLY`，不是 `OFF`

#### Scenario: Snapshot is current
- **WHEN** epoch 一致、无已退役来源、用户可读全部有效来源且选择范围完整
- **THEN** 该库门禁为 `FULL`，行为与变更前一致

### Requirement: Facts-only mode uses no community data
门禁为 `FACTS_ONLY` 的知识库 MUST NOT 执行社区召回，MUST NOT 以社区限定扩展范围，MUST NOT 走代表实体路径。存在 Chunk 种子时 SHALL 执行与 `SEED_ONLY` 相同预算的有界扩展；没有种子时 SHALL 跳过图增强。检索轨迹 SHALL 为该库记录降级项 `graph-facts-only`。

#### Scenario: Facts-only knowledge base with chunk seeds
- **WHEN** agent 调用 `graph_expand`，证据池中的 Chunk 带有该库的实体种子
- **THEN** 系统不查询社区索引，从种子做无社区限定的有界扩展，轨迹包含 `graph-facts-only`

#### Scenario: Facts-only knowledge base without seeds
- **WHEN** 证据池中没有该库的实体种子
- **THEN** 该库跳过图增强，不访问社区索引，也不使用代表实体

### Requirement: Graph evidence is re-verified against the business store before entering the evidence pool
图关系回查 ES 得到的每个 CHILD，在进入证据池前 MUST 校验以下三项：其来源资源当前有效（PAGE 为 ACTIVE，ATTACHMENT 为 STORED）、用户当前可读、属于本次选择范围。未通过的 CHILD MUST 被丢弃；一条关系的全部 CHILD 都被丢弃时，该关系 MUST 被丢弃。该校验 SHALL 在 `FULL` 与 `FACTS_ONLY` 下都执行，且每次图增强对业务库的批量查询次数有上限并计入图时间预算。

#### Scenario: ES deletion is still pending after archive
- **WHEN** 页面已归档，但其 CHILD 因 ES 暂时不可用仍在索引中，图扩展命中引用该 CHILD 的关系
- **THEN** 该 CHILD 不进入证据池，该页面内容不出现在模型输入中

#### Scenario: Relation supported by an archived and a live source
- **WHEN** 一条关系由两个来源支持，其中一个已归档，另一个仍有效且可读
- **THEN** 只有仍有效来源的 CHILD 进入证据池，该关系保留

### Requirement: Changed knowledge bases are rebuilt automatically after a quiet period
系统 SHALL 为发生 content 或 security 变化的知识库持久化待重建记录（首次变化时间、最近变化时间、对应 epoch、是否硬删除）。调度器 SHALL 在跨实例互斥下周期检查。一个知识库同时满足以下条件时，调度器 SHALL 提交单库图构建：
- 图功能开启，且该库在当前选中的 Chunk 版本下已有发布快照；
- 静默期已到，或最长延迟已到，或记录为硬删除；
- 该库没有活动中的图构建；
- 该库没有未完成的索引任务。

提交 MUST 以 epoch 组成幂等键。自动发布 SHALL 由独立开关控制，默认开启，且 MUST 走与手动发布相同的校验、epoch 与 CAS 门禁。发布的快照 epoch 不低于记录中的 epoch 时，系统 SHALL 清除该记录；构建 STALE 或失败时 SHALL 保留记录并按退避重新提交。静默期、最长延迟、开关 SHALL 有代码内默认值，并按「Graph publishing and rebuild settings are hot-reloadable」热更新。

#### Scenario: Several edits within the quiet period
- **WHEN** 同一知识库在 20 分钟内发布了 5 次修订，静默期为 30 分钟
- **THEN** 最后一次发布后 30 分钟只提交一次单库构建，未变化的 Chunk 命中抽取缓存

#### Scenario: Continuous editing exceeds the maximum delay
- **WHEN** 某库持续编辑，从未出现 30 分钟静默，首次变化已超过 6 小时
- **THEN** 调度器仍提交构建；若构建因 epoch 再次前进而 STALE，记录保留并在下一次满足条件时重提

#### Scenario: Indexing job still running
- **WHEN** 静默期已到，但该库仍有排队中的页面索引任务
- **THEN** 本轮不提交构建，待索引任务完成后再提交

#### Scenario: Knowledge base never had a graph
- **WHEN** 一个从未发布过图快照的知识库发生内容变化
- **THEN** 不自动提交图构建

#### Scenario: Rebuild publishes and old nodes are removed
- **WHEN** 自动重建的快照通过校验并发布
- **THEN** 待重建记录被清除，门禁恢复为 `FULL`；旧快照进入退役流程，宽限期与读取租约结束后其节点和边被物理删除

#### Scenario: Two instances run the rebuild scheduler
- **WHEN** 两个应用实例同时到达调度时刻
- **THEN** 只有一个实例提交构建，同一 epoch 不产生重复批次

### Requirement: Graph publishing and rebuild settings are hot-reloadable
`kwiki.graph.auto-publish`（默认 false）、`kwiki.graph.rebuild.enabled`（默认 true）、`kwiki.graph.rebuild.quiet-period`（默认 30m）、`kwiki.graph.rebuild.max-delay`（默认 6h）、`kwiki.graph.rebuild.auto-publish`（默认 true）的默认值 SHALL 定义在代码中，不依赖 `application.yml`。配置中心推送变更后，系统 SHALL 把这一组配置整体重新读取并校验（`quiet-period` 大于 0，`max-delay` 不小于 `quiet-period`）：
- 校验通过：原子替换整组值，并记录变更前后的值；
- 校验不通过：保留原值，记录 WARN；
- 被删除的键：回落到默认值；
- 启动时配置非法：应用启动失败。

每次调度、每次批次提交 SHALL 读取当时的一份完整配置；同一次操作 MUST NOT 混用两个版本的值。配置变更 MUST 只影响之后提交的批次，已提交的批次 MUST 沿用提交时记录的发布方式。管理端服务状态 SHALL 显示当前生效的值。

#### Scenario: Administrator enables auto publish in Nacos
- **WHEN** 应用运行中，Nacos 将 `kwiki.graph.auto-publish` 由 false 改为 true
- **THEN** 不重启应用，之后提交的手动和每日构建批次均为自动发布，管理端服务状态显示 true；改动前已提交的批次仍需手动发布

#### Scenario: Rebuild is disabled at runtime
- **WHEN** Nacos 将 `kwiki.graph.rebuild.enabled` 改为 false
- **THEN** 从下一轮调度起不再提交自动重建，已提交的构建照常完成，待重建记录保留

#### Scenario: Invalid durations are pushed
- **WHEN** Nacos 推送 `quiet-period=2h`、`max-delay=1h`
- **THEN** 整组变更被拒绝，系统继续使用原有的全部值并记录 WARN

#### Scenario: A key is removed from Nacos
- **WHEN** 运行中从 Nacos 删除 `kwiki.graph.rebuild.quiet-period`
- **THEN** 静默期回落为 30 分钟，其他值保持当前配置

### Requirement: Hard deletion forces rebuild and blocks rollback to snapshots referencing purged sources
回收站彻底删除资源时，系统 SHALL 将该库标记为硬删除待重建，不等待静默期。回滚目标快照的来源清单中引用了业务库中已物理删除的资源时，回滚 MUST 被拒绝。快照退役在计算「保留当前与上一版本」时，MUST 不为这类快照保留豁免。

#### Scenario: Page is purged from the recycle bin
- **WHEN** 管理员彻底删除回收站中的页面
- **THEN** 该库在下一轮调度中即可提交重建，无需等待静默期

#### Scenario: Administrator rolls back to a snapshot containing a purged page
- **WHEN** 目标快照清单包含一个已物理删除的页面
- **THEN** 回滚被拒绝并给出原因，在线指针不变

#### Scenario: Snapshot only references an archived page
- **WHEN** 目标快照清单包含一个已归档但仍在回收站中的页面
- **THEN** 回滚允许执行；查询时该页面计为已退役来源，门禁为 `FACTS_ONLY`

### Requirement: Graph freshness is observable to administrators
管理端 SHALL 为每个知识库展示以下信息：图门禁档位（按管理员视角计算）、待重建起始时间、下次可提交时间、最近一次自动重建的批次与结果。系统 SHALL 输出门禁档位计数与自动重建提交、STALE、失败计数指标，且不包含正文、标题或用户标识。

#### Scenario: Administrator checks a recently edited knowledge base
- **WHEN** 管理员在页面编辑 10 分钟后打开索引管理页
- **THEN** 该库显示 `FACTS_ONLY`、待重建起始时间和预计提交时间
