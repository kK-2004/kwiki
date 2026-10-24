## 0. 配置热更新

- [x] 0.1 新增 `GraphRuntimeSettings`（参照 `QaRetrievalBudgets`）：管理 `kwiki.graph.auto-publish` 和 `kwiki.graph.rebuild.{enabled,quiet-period,max-delay,auto-publish}`，默认值写在代码常量里；时长用 `DurationStyle.detectAndParse` 解析；校验 `quiet-period > 0`、`max-delay ≥ quiet-period`；监听 `EnvironmentChangeEvent` 整组重载并原子替换，失败保留原值记 WARN，启动时非法直接失败
- [x] 0.2 `GraphProperties` 移除 `autoPublish`；`CurrentChunkScheduledBatchFactory.create` 改为每次读取 `snapshot().autoPublish()`，删除构造期缓存的字段
- [x] 0.3 `GraphBuildAdminController` 的提交接口与 `/service-status` 改为读取当前快照，状态里加上 rebuild 的 4 项
- [x] 0.4 单元测试：热更新生效、非法组合整组拒绝、删除键回落默认值、启动时非法直接失败、已提交批次的 `auto_publish` 不受影响
- [ ] 0.5 确认 `application.yml` 和其他环境配置文件中没有 `kwiki.graph.auto-publish`，或其值与 Nacos 一致；按 spec 场景在本地 Nacos 实测一次热更新

## 1. Epoch 接通

- [x] 1.1 在 wiki 侧定义 `KnowledgeSourceChangePort`（`contentChanged(kbId)`、`securityChanged(kbId)`、`hardDeleted(kbId)`），graph 侧实现推进 `GraphSourceEpochService` 并 upsert 待重建记录；图功能关闭时注入空实现
- [x] 1.2 `PageRevisionService` 的发布、回滚修订、删除三处在 `enqueuePage*` 的同一事务内调用 `contentChanged`
- [x] 1.3 `AttachmentService` 的上传、替换、删除三处调用 `contentChanged`
- [x] 1.4 `ResourceArchiveService` 的页面、附件、知识库归档与恢复调用 `contentChanged`
- [x] 1.5 `ScopeVersionService.bump` 内调用 `securityChanged`，确认全部权限变化入口都经过 `bump`
- [x] 1.6 `RecycleBinCleanupService` 物理删除页面、附件、知识库时调用 `hardDeleted`
- [x] 1.7 单元测试：业务事务回滚时 epoch 不变；图功能关闭时业务写入不访问图表

## 2. 门禁三档

- [x] 2.1 `GraphSourceInventoryPort` 增加批量 `lifecycleStates`，`JdbcGraphSourceInventory` 实现 LIVE/RETIRED 判定（行不存在计为 RETIRED）
- [x] 2.2 `GraphSourceCoverage` 增加 `retiredSourceCount`；`GraphSourceCoverageService` 将 RETIRED 来源排除出可读与选择范围校验
- [x] 2.3 `GraphAuthorizationGate.Decision` 增加档位 `FULL / FACTS_ONLY / OFF`，按 spec 顺序判定；`GraphKnowledgeBaseGateService.eligible` 返回每库档位
- [x] 2.4 单元测试覆盖 spec 中门禁的五个场景（归档页、私有页、仅 content epoch 落后、加成员、完全一致）

## 3. 检索行为

- [x] 3.1 `GraphEnhancementFallbackPolicy.resolve` 增加 `factsOnly` 入参：有种子则 `SEED_ONLY`，否则 `SKIP`
- [x] 3.2 `GraphRetrievalEnhancer` 只对 FULL 库调用社区召回；FACTS_ONLY 库记录降级项 `graph-facts-only`
- [x] 3.3 `resolveSources` 之后增加业务库批量二次校验（LIVE、canRead、选择范围），丢弃不通过的 CHILD 及无剩余 CHILD 的关系，耗时计入图预算
- [x] 3.4 测试：ES 删除 PENDING 时已归档页面的 CHILD 不进入证据池；双来源关系保留仍有效来源

## 4. 去抖自动重建

- [x] 4.1 新增数据库迁移：创建 `graph_rebuild_request` 表（编号取当前最大编号加 1）
- [x] 4.2 实现待重建记录仓储：upsert（保留 `first_dirty_at`）、按条件查询可提交的库、发布后按 epoch 清除、记录结果与退避
- [x] 4.3 新增 `GraphRebuildScheduler`：每分钟在 `graph:rebuild` 锁下执行；检查已有发布快照、静默期/最长延迟/硬删除、无活动构建、无未完成索引任务；以 `GRAPH_REBUILD:{kbId}:{ce}:{se}` 幂等提交单库批次
- [x] 4.4 `GraphRebuildScheduler` 每轮开始时取一次 `GraphRuntimeSettings.snapshot()`，用同一份快照判断开关、静默期、最长延迟和 `rebuild.auto-publish`（依赖第 0 组）
- [x] 4.5 快照发布成功后清除 epoch 已覆盖的待重建记录；STALE 或 FAILED 时记录结果并按指数退避
- [x] 4.6 测试：多次编辑只提交一次；超过最长延迟强制提交；有索引任务时不提交；从未建图的库不提交；两个实例并发不重复提交

## 5. 硬删除约束

- [x] 5.1 `GraphSnapshotRollbackGuard` 拒绝来源清单引用已物理删除资源的快照
- [x] 5.2 `GraphSnapshotRetirementService` 的「保留上一版本」豁免跳过引用已物理删除来源的快照
- [x] 5.3 测试：引用已物理删除页面的快照拒绝回滚；仅引用已归档页面的快照允许回滚

## 6. 可观测性与文档

- [x] 6.1 管理端查询接口返回每库门禁档位（管理员视角）、待重建起止时间、最近一次自动重建的批次与结果
- [x] 6.2 管理端索引管理页展示上述字段
- [x] 6.3 增加指标 `graph.gate.mode{mode}`、`graph.rebuild.submitted/stale/failed`
- [x] 6.4 更新 `docs/graph-arcadedb-operations.md`：epoch 推进点、门禁三档、自动重建配置与故障处理；注明 `auto-publish` 与 `rebuild.*` 支持 Nacos 热更新、只影响之后提交的批次，以及 cron、`enabled` 等其余项仍需重启
- [x] 6.5 运行全量后端测试与管理端构建，确认通过（1592 tests, 0 failures; admin-frontend vue-tsc + vite build 通过；0.5 的 Nacos 实测需部署环境）
