## Context

图谱以「每库不可变快照」形式存放在 ArcadeDB，并通过 MySQL 发布指针与 `(kbId, chunkIndexVersion)` 配对。查询时的现状：

- **ES**：页面归档时 `ArchiveIndexSyncService` 同步删除该页在所有写入目标中的 CHILD Chunk，失败则 PENDING 重试；回收站彻底删除时再删一次。
- **图证据回查**：`GraphRetrievalEnhancer.resolveSources` 按 `sourceChunkId` 精确回查 ES。`sourceChunkId` 的哈希包含 revisionId、lifecycleVersion、contentHash，所以编辑或删除后旧关系回查不到原文，会被丢弃。已删除或修改前的原文不会进入回答。
- **社区**：社区摘要存在独立 ES 索引，只在 agent 调用 `graph_expand` 时召回，只用来限定扩展范围（`COMMUNITY_SEEDED`）或挑选代表实体（`REPRESENTATIVE_ENTITY`）。摘要文本不交给模型。
- **门禁**：`GraphSourceCoverageService` 以「当前有效来源 ∪ 快照清单」为期望集合，要求逐个可读。被归档的页面仍在快照清单里，但 `canRead` 只认 ACTIVE，于是覆盖不完整，整库图增强关闭。
- **epoch**：`graph_source_epoch` 表、`GraphSourceEpochService`、构建时捕获 epoch、执行器按阶段比对后置 STALE、发布事务 `FOR UPDATE` 校验，这些都已具备。唯独没有任何业务代码推进 epoch。
- **构建**：每日 02:00 全量构建，`autoPublish` 默认跟随 `kwiki.graph.auto-publish`（默认手动）。抽取结果按来源 Chunk 身份缓存，未变化的 Chunk 不再调用模型。
- **权限**：权限变化已统一调用 `ScopeVersionService.bump(kbId)`（调用方有 `ResourceAudienceService`、`PageCollaborationService`、`KnowledgeBaseService`、`InvitationService`、`OwnershipTransferService`、`ResourceArchiveService`）。

## Goals / Non-Goals

**Goals:**
- 内容或权限变化后，图增强从「整库关闭」改为降级到可逐条验证的「仅图事实」，并在可控延迟内自动重建、发布，恢复完整能力。
- 已删除内容在任何阶段都不进入模型输入；物理删除后最终从 ArcadeDB 中清除，且不能被回滚带回。
- 复用现有快照、epoch、STALE、抽取缓存、退役机制，不引入新的存储形态。

**Non-Goals:**
- 不做在线逐篇删除或修改 ArcadeDB 节点和边（快照仍不可变）。
- 不做真正的图增量构建（只重建变化部分的社区等）。重建仍是单库全量流程，只是依赖抽取缓存降低模型成本。
- 不改变 ES Chunk 删除流程，不改变每日 02:00 全量调度。

## Decisions

### D1：快照保持不可变，不在线删除节点
实体在多篇文章之间合并，社区与摘要基于整张图计算，快照又被请求固定、受读取租约保护。在线删除某篇文章的节点会让社区成员、摘要与图不一致，还会破坏正在读取的请求。节点的物理删除统一在「新快照发布 → 旧快照退役 → 宽限期与租约结束 → 按资源清单删除」这一步完成。
- 备选：维护每篇文章到实体、关系的引用计数，归档时在线删除零引用的节点。否决理由：需要改造写入模型与社区一致性，收益只是「早几十分钟物理删除」，而查询期校验已经保证内容不泄露。

### D2：epoch 推进放在业务写入的同一事务里，经端口解耦
wiki 侧新增端口 `KnowledgeSourceChangePort`，提供 `contentChanged(kbId)` 与 `securityChanged(kbId)` 两个方法。graph 侧实现：推进 epoch，并 upsert `graph_rebuild_request`。图功能关闭时注入空实现。
- **content**：在调用 `indexingJobs.enqueue*` 的同一事务中调用，覆盖页面发布、回滚修订、删除，附件上传、替换、删除，页面、附件、知识库的归档与恢复。
- **security**：在 `ScopeVersionService.bump` 内部调用，一处覆盖全部权限变化入口。
- 事务一致性：业务写入回滚，epoch 也跟着回滚，不会出现「epoch 前进但内容没变」导致的无谓降级。`graph_source_epoch` 每库一行、行锁很短；发布事务对同一行 `FOR UPDATE`，与内容写入天然串行，这正是现有 CAS 设计所期望的。
- 备选：监听 `indexing_job` 发件箱异步推进。否决理由：在发件箱处理之前，门禁仍按旧 epoch 判为 FULL，存在窗口；而且 graph 要反向读取 wiki 的作业表。

### D3：门禁三档，「已退役来源」与「无权来源」分开计
`GraphSourceInventoryPort` 新增批量方法 `lifecycleStates(List<GraphResourceId>)`，返回每个资源 `LIVE`（PAGE 为 ACTIVE、ATTACHMENT 为 STORED）或 `RETIRED`（其他状态或行已不存在）。`GraphSourceCoverage` 增加 `retiredSourceCount`：

| 条件（按顺序判定） | 档位 |
|---|---|
| 某个 LIVE 来源用户不可读，或不在选择范围内 | `OFF` |
| 快照与当前的 content/security epoch 不一致，或 `retiredSourceCount > 0` | `FACTS_ONLY` |
| 其他 | `FULL` |

期望集合 = 当前 LIVE 来源 ∪ 快照清单中的 LIVE 来源；RETIRED 来源只计数，不参与可读与选择范围的校验。
- **security epoch 落后不直接判 OFF**：门禁每次都按当前权限逐个核验全部 LIVE 来源，权限收紧会落入第一行的 OFF；权限放宽或成员变化不影响安全性。摘要文本不进入模型输入，且 `FACTS_ONLY` 不使用社区。若坚持对 security epoch 判 OFF，任何一次加成员都会让整库图增强停用到重建完成，代价过高。
- `GraphAuthorizationGate.Decision` 增加 `mode` 字段；`GraphKnowledgeBaseGateService.eligible` 返回每库的档位，而不是只给通过与否。

### D4：FACTS_ONLY 下的检索行为
- 不调用 `CommunityRecallCoordinator`（不查社区 ES 索引）。
- `GraphEnhancementFallbackPolicy.resolve` 增加 `factsOnly` 入参：有种子则走 `SEED_ONLY`，无种子则 `SKIP`；`REPRESENTATIVE_ENTITY` 不可达。
- 检索轨迹新增降级项 `graph-facts-only`（每库一条）。

### D5：图证据回查后的数据库侧二次校验
`resolveSources` 得到 `ChunkHit` 后，按 `(resourceType, resourceId)` 去重，批量做三项校验：LIVE、`canRead`、选择范围覆盖。不通过的 child 丢弃；某条关系若全部 child 都被丢弃，整条关系丢弃。FULL 与 FACTS_ONLY 都执行。这样补上了 ES 删除处于 PENDING 时「页面已归档但 chunk 仍可检索」的窗口，也落实了已有 spec 中「在线图证据验证当前生命周期与授权」的要求。批量查询每次调用最多一次 SQL，开销计入现有 1.5 秒图预算。

### D6：去抖自动重建
新表 `graph_rebuild_request`（迁移脚本命名遵循现有 `V<n>__*.sql` 规则，编号在实现时取当前最大编号加 1）：

```
kb_id PK, first_dirty_at, last_dirty_at, dirty_content_epoch, dirty_security_epoch,
hard_delete BOOLEAN, last_submitted_batch_id NULL, last_outcome NULL, updated_at
```

- **标记**：`KnowledgeSourceChangePort` 在 epoch 推进后 upsert 这一行。新建时写入 `first_dirty_at`；之后每次只刷新 `last_dirty_at` 和 epoch；硬删除时置 `hard_delete=true`。
- **调度**：`GraphRebuildScheduler` 每分钟执行一次，持有分布式锁 `graph:rebuild`。一个库同时满足以下条件才提交构建：
  1. 图功能开启，且该库在当前选中的 Chunk 版本下有已发布快照（从未建过图的库不自动建）；
  2. `now - last_dirty_at ≥ quiet-period`，或 `now - first_dirty_at ≥ max-delay`，或 `hard_delete=true`；
  3. 该库没有活动中的图构建子任务；
  4. 该库没有 QUEUED 或运行中的 `indexing_job`，保证 ES 已反映最新内容，避免基于旧 Chunk 构建后又立即 STALE。
- **提交**：`scopeKind=KNOWLEDGE_BASE`，幂等键 `GRAPH_REBUILD:{kbId}:{contentEpoch}:{securityEpoch}`，`autoPublish` 取当前配置快照中的 `rebuild.auto-publish`（默认 true），`requestedBy=graph-rebuild`。
- **清除**：快照发布成功、且其 epoch 不低于记录中的 dirty epoch 时，删除这一行。构建 STALE 或 FAILED 时保留这一行并记录 `last_outcome`，等下一次满足条件后重新提交。FAILED 采用指数退避，最长间隔取 `max-delay`，避免反复失败时空转。
- **配置**：`kwiki.graph.rebuild.enabled=true`、`quiet-period=30m`、`max-delay=6h`、`auto-publish=true`。读取方式与热更新见 D9。
- 备选：只依赖每日 02:00 构建并把它改为自动发布。否决理由：降级可能持续接近 24 小时，而且全量构建的范围远大于实际变化。

### D7：硬删除的回滚与退役约束
- 回收站彻底删除页面、附件或知识库时调用 `contentChanged`，并标记 `hard_delete`，调度器跳过静默期，下一轮就提交。
- `GraphSnapshotRollbackGuard` 增加检查：目标快照的来源清单中，若有资源在业务库中已不存在（物理删除），则拒绝回滚。仅归档、仍可恢复的来源不受限制，因为查询期会被判为 RETIRED。
- `GraphSnapshotRetirementService` 计算「至少保留当前版本与上一版本」时，跳过清单引用了已物理删除来源的快照，使其在宽限期后即可清理。

### D8：可观测性
管理端索引管理页的每库图状态增加以下字段：
- 当前档位（以当前 epoch 与快照 epoch、RETIRED 计数为准，按管理员视角计算，不代表任意用户的结果）；
- 待重建起始时间、下次可提交时间、最近一次自动重建的批次与结果。

指标方面，新增 `graph.gate.mode{mode}` 计数与 `graph.rebuild.submitted/stale/failed` 计数。

### D9：发布与重建配置支持 Nacos 热更新
现状：Nacos 刷新已开启，但 `kwiki.graph.auto-publish` 绑定在不可变 record `GraphProperties` 上，刷新时不会重新绑定；`CurrentChunkScheduledBatchFactory` 还在构造时把它存成了字段，所以改了必须重启。`@Value` 注入同样只在 bean 创建时读一次。

做法：新增 `GraphRuntimeSettings`（`@Component`，实现 `EnvironmentAware`），照搬 `QaRetrievalBudgets` 的模式：
- 管理 5 个键：
  - `kwiki.graph.auto-publish`：默认 false，键名不变；
  - `kwiki.graph.rebuild.enabled`：默认 true；
  - `kwiki.graph.rebuild.quiet-period`：默认 30m；
  - `kwiki.graph.rebuild.max-delay`：默认 6h；
  - `kwiki.graph.rebuild.auto-publish`：默认 true。
- 默认值写在代码常量里，不写入 `application.yml`。
- 时长用 Spring Boot 的 `DurationStyle.detectAndParse` 解析，同时支持 `30m` 和 ISO-8601 两种写法，不依赖 Environment 上挂了哪种转换服务。
- 校验：`quiet-period > 0`，`max-delay ≥ quiet-period`。
- 监听 `EnvironmentChangeEvent`，整组重新读取并校验：通过则原子替换不可变快照 `Snapshot`，记 INFO `graph runtime settings refreshed: <旧> -> <新>`；不通过则保留原值，记 WARN。启动时非法直接失败。删除的键回落到默认值。
- 使用方每次操作取一次 `snapshot()`，不缓存字段：
  - `CurrentChunkScheduledBatchFactory.create`：取 `autoPublish`；
  - `GraphBuildAdminController` 的提交接口取 `autoPublish`，`/service-status` 显示当前值，并新增 rebuild 的 4 项；
  - `GraphRebuildScheduler` 每轮开始取一次，用同一个快照判断这一轮里所有库。
- `GraphProperties` 移除 `autoPublish` 字段。record 不会因为配置里多出一个它不认识的键而报错，所以键名保持不变，已有的 Nacos 配置不用迁移。

生效语义：
- 配置变更只影响之后提交的批次。批次的发布方式在提交时写进 `graph_build_batch.auto_publish`，`publishIfAutoPublishEnabled` 读的是这一列，在途批次不受影响。
- `rebuild.enabled` 改为 false 后，下一轮调度起不再提交，已提交的构建照常跑完。

其余 `GraphProperties` 字段不在本次范围内：
- `schedule-cron` / `schedule-zone` 用在 `@Scheduled` 注解上，无法热更新；
- `enabled` 决定 bean 是否装配，无法热更新；
- 容量、租约等字段不在本次范围内，仍需重启。

备选方案：
- 给相关 bean 加 `@RefreshScope`。否决理由：调度器和控制器是单例；`@RefreshScope` 刷新时会重建代理目标，和 `@Scheduled`、分布式锁的生命周期冲突；而且和项目已有的热更新模式不一致。
- 把 `GraphProperties` 改为可变 JavaBean，交给 `ConfigurationPropertiesRebinder` 重新绑定。否决理由：会有部分字段已更新、部分未更新的中间状态，也没有整组校验后再替换的语义。

## Risks / Trade-offs

- **[持续编辑的库长期停留在 FACTS_ONLY]** 每次编辑都会推进 epoch，让在途构建 STALE。→ `max-delay` 强制提交；FACTS_ONLY 本身安全，只是少了社区路由。管理端展示新鲜度，管理员可在低峰期手动发布。
- **[epoch 行锁热点]** 高频编辑的库会频繁更新同一行。→ 单行短事务，量级与页面发布频率相同；发布事务持有 `FOR UPDATE` 的时间很短。如有需要，后续可改为在同一事务内合并多次推进。
- **[自动发布改变了原有「默认手动发布」的运维假设]** → 只对去抖重建批次生效，开关独立；仍走同一套校验、epoch、CAS 门禁，失败不影响在线指针。
- **[二次校验增加查询延迟]** → 每次图增强最多增加一次批量 SQL，计入现有图时间预算；超时则按 `graph-expansion-failed` 降级，不影响 Chunk 主路径。
- **[权限收紧到重建前的窗口]** 门禁每次请求都实时核验，被收紧的用户立刻判为 OFF，不依赖 epoch；epoch 只用来驱动重建和退出 FULL。

## Migration Plan

1. 部署迁移（新表）。`graph_source_epoch` 已存在，无需改动。
2. 上线后首次内容变化会让对应库进入 FACTS_ONLY，并在静默期后自动重建。既有快照的 epoch 均为 0，而推进后的 epoch ≥ 1，可以正确识别为落后。
3. `kwiki.graph.auto-publish` 的键名与默认值不变，已有的 Nacos 配置无需迁移，上线后支持热更新。
4. 回滚：在 Nacos 中将 `kwiki.graph.rebuild.enabled=false`，下一轮调度起即停止自动重建，无需重启。门禁三档只在「存在已退役来源」时比现状放宽（从 OFF 变为 FACTS_ONLY），同时二次校验让每条证据的检查比现状更严格。需要完全回退时，回退应用版本即可，新表可以保留。

## Open Questions

- 「从未建过图的库不自动建」是否符合预期，还是应在首次满足条件时也自动构建？当前设计选择不自动建，避免默默放大模型成本。
- `quiet-period=30m`、`max-delay=6h` 的默认值需结合实际编辑频率与模型成本确认。
