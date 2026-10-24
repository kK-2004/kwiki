## Why

图快照发布后，文章的删除、编辑、恢复与权限变化都不会让图谱跟着变：`GraphSourceEpochService.advanceContentEpoch/advanceSecurityEpoch` 没有任何调用方，epoch 永远不前进；删除一篇文章会让该库快照清单里出现不可读来源，全来源覆盖门禁直接关闭整库图增强，直到有人重建并发布新快照；而每日 02:00 构建默认不自动发布。`integrate-arcadedb-leiden-graphrag` 中「内容变化推进 epoch 并安排投影刷新、在线证据逐条验证」的要求实际没有落地。

## What Changes

- 接通 epoch：页面发布/回滚修订/删除、归档/恢复、附件增删、知识库归档在同一事务内推进 content epoch；页面可见范围、页面成员、知识库成员、所有权转移等权限变化在推进 `scope_version` 的同一事务内推进 security epoch。图功能关闭时为空操作。
- 门禁从「全开/全关」改为三档：
  - `FULL`：epoch 一致、快照来源全部仍有效且可读、选择范围完整——维持现状。
  - `FACTS_ONLY`（仅图事实）：epoch 落后或快照清单含已归档/已删除来源，但当前有效来源全部可读且在选择范围内——停用社区召回与代表实体路径，仅允许从 Chunk 种子出发的 seed-only 扩展，每条关系逐条回查原文。
  - `OFF`：当前有效来源中有用户不可读的，或选择范围不完整——与现状一致，整条图路径关闭。
- 覆盖证明区分「已退役来源」（不再 ACTIVE/STORED，或已物理删除）与「无权读取的有效来源」：前者不再关闭门禁，只使门禁降为 `FACTS_ONLY`。
- 图证据回查 ES 后增加数据库侧二次校验：来源资源必须当前 ACTIVE/STORED、用户可读且属于本次选择范围，覆盖 ES 删除处于 PENDING 的窗口。
- 新增「待重建」记录与去抖调度：内容或权限变化标记该库待重建，静默期（默认 30 分钟）或最长延迟（默认 6 小时）到期后提交单库构建；未变化的 Chunk 命中抽取缓存，只对变化部分调用模型。该类构建默认自动发布（可配置），发布后旧快照按既有退役流程物理删除节点。
- 发布与重建相关配置支持 Nacos 热更新：新增 `kwiki.graph.rebuild.*`，并把现有的 `kwiki.graph.auto-publish` 从不可变的 `GraphProperties` 移入新的热更新配置组件。推送后整组重新读取并校验，通过则原子替换快照，不通过则保留原值并记 WARN（与 `QaRetrievalBudgets` 一致）。调度器、管理端提交与状态接口改为每次读取当前快照，下一次提交或调度即生效；已提交的批次沿用提交时写入的发布方式。
- 回收站彻底删除：立即触发重建（跳过静默期）；快照清单中引用已物理删除来源的快照不得作为回滚目标，也不计入「保留上一版本」的退役豁免。
- 检索轨迹新增降级项 `graph-facts-only`；管理端索引页展示每库图新鲜度（FULL / FACTS_ONLY、待重建起始时间、最近一次自动重建结果）。

## Capabilities

### New Capabilities

- `graph-source-lifecycle`：内容/权限变更推进 epoch、三档门禁与已退役来源处理、图证据数据库侧二次校验、去抖自动重建与发布、硬删除后的回滚与退役约束、新鲜度可观测性。

### Modified Capabilities

（无。`openspec/specs/` 下尚无已归档基线；本变更细化 `integrate-arcadedb-leiden-graphrag` 中 `community-summary-retrieval` 的全来源门禁与 `knowledge-graph-projection` 的内容生命周期要求，以新能力的 ADDED 要求表达，归档时与该变更一并合入。）

## Impact

- **代码**：`wiki/api/PageRevisionService`、`wiki/api/AttachmentService`、`wiki/archive/ResourceArchiveService`、`wiki/archive/RecycleBinCleanupService`、`wiki/access/ScopeVersionService` 及其调用方；`graph/GraphSourceCoverageService`、`GraphSourceCoverage`、`GraphKnowledgeBaseGateService`、`GraphAuthorizationGate`、`GraphEnhancementFallbackPolicy`、`GraphSourceInventoryPort`（`infrastructure/mysql/JdbcGraphSourceInventory`）；`rag/retrieval/GraphRetrievalEnhancer`；`graph/persistence/GraphBuildScheduler`、`GraphBuildBatchService`、`GraphSnapshotRollbackGuard`、`GraphSnapshotRetirementService`；管理端索引管理页。
- **数据库**：新增迁移，建 `graph_rebuild_request` 表（每库一行）。
- **配置**：新增 `kwiki.graph.rebuild.*`（是否启用、静默期、最长延迟、是否自动发布）；`kwiki.graph.auto-publish` 的键名与默认值（false）不变，但改由新组件 `GraphRuntimeSettings` 读取并支持热更新，`GraphProperties` 中移除 `autoPublish` 字段。默认值写在代码里，不写入 `application.yml`，可经 Nacos 覆盖并热更新。`CurrentChunkScheduledBatchFactory`、`GraphBuildAdminController` 改为读取当前快照。
- **行为**：删除/编辑文章不再整库关闭图增强，而是降级为仅图事实直到新快照发布；权限变化同样降级而非关闭。在途构建会因 epoch 前进被置 STALE，由去抖机制重新提交。
- **文档**：`docs/graph-arcadedb-operations.md` 更新 epoch、门禁三档与自动重建说明。
