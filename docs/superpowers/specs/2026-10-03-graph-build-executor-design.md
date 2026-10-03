# 设计：图构建执行器与环节插件化

日期：2026-10-03
前置：索引插件化三个子项目已合入（复用 `IndexPlugin` / `PluginRegistry` / `PluginAvailability`）。

## 0. 背景

提交图构建批次后子任务永远停在 QUEUED：项目中没有任何组件领取并执行图构建子任务。自图功能首次提交（`1fdd900`）起，执行链路只有骨架——`GraphBuildRunCoordinator`（租约 / 隔离令牌 / 断点）不是 Spring Bean、无调用方；`EntityRelationExtractionPort`、`CommunitySummaryPort` 无实现。

已存在、可直接复用：

| 能力 | 现有代码 |
|---|---|
| 状态与阶段、转换表 | `GraphBuildState`、`GraphBuildStage`、`GraphBuildStateMachine` |
| 批次 / 子任务持久化、租约、断点、取消、重排队 | `GraphBuildRepository` / `JdbcGraphBuildRepository`（`acquireLease`、`renewLease`、`checkpoint`、`cancelRun`、`requeueRun`） |
| 抽取提示与校验 | `GraphExtractionPrompt`、`GraphExtractionValidator` |
| 抽取结果缓存（按来源块 + 抽取器版本 + 提示词版本 + 实体链接版本唯一） | 表 `graph_extraction_result`（V28） |
| 实体登记与保守消解 | `JdbcGraphEntityRegistry`、`ConservativeGraphEntityResolver` |
| 来源清单与 epoch | `JdbcGraphSourceInventory`、`GraphSourceChildResolverPort` |
| 图投影 / 写入 ArcadeDB | `UnweightedGraphProjectionBuilder`、`ArcadeDbKnowledgeGraphStore` |
| 社区检测 | `ArcadeDbNativeCommunityDetection` |
| 社区代表选择、摘要校验 | `CommunityRepresentativeSelector`、`CommunitySummaryService`、`CommunitySummaryValidator` |
| COMMUNITY 索引写入、实体回写 CHUNK | `CommunityIndexWriter`、`EntityMappingBackfillService` |
| 快照校验 / 封存 / 自动发布 | `GraphSnapshotValidationService`、`GraphSnapshotSealingService`、`GraphSnapshotAutoPublishService` |
| 容量守卫、指标 | `GraphCapacityGuard`、`GraphBuildMetrics` |

执行链路还缺、需由本设计补齐的（经逐个核对代码确认）：

- 没有任何代码创建 `graph_snapshot` 行，也没有把来源清单写入 `graph_source_manifest` / `graph_source_manifest_entry`。
- 没有「按知识库列出 CHUNK 物理索引中全部子块」的方法：`GraphSourceInventoryPort` 只到资源级别，`GraphSourceChildResolverPort` 只能按单个 `sourceChunkId` 解析。
- `ConservativeGraphEntityResolver`、`GraphSnapshotValidationService`、`GraphSourceManifestBuilder`、`GraphExtractionJobPlanner` 不是 Spring Bean、也无人构造。
- 抽取缓存表只有入队（`GraphExtractionJobPlanner.plan`），没有读取、写回结果的方法。

已确认的决策：

- 执行方式：领取子任务后在后台线程一口气推进全部阶段，租约续期保活，每个阶段写断点；崩溃后由其他实例在租约过期时接手、从当前阶段继续；抽取按块缓存，重做抽取阶段不重复调用模型。
- 并发：多个知识库并行（全局上限可配），同一知识库同时只有一个活动子任务；模型限流全局共享。
- 执行器不插件化；抽取、社区检测、社区摘要三个环节做成插件，插件 id 记入批次、子任务、快照。
- 模型：独立配置 `kwiki.graph.models.extraction.*` / `kwiki.graph.models.summary.*`，未配置的连接项沿用问答模型。
- 不在范围：COMMUNITY 索引格式插件化、图构建独立灰度流程、第二种插件实现。

## 1. 执行器

### 1.1 领取与并发

- `GraphBuildExecutor`（`@Component`，仅 `kwiki.graph.enabled=true` 时启用）每 `kwiki.graph.build.poll-interval`（默认 10s）触发：
  1. 获取分布式锁 `graph-build-executor`（锁服务不可用时视为单实例，与 `GrayReleaseSyncDriver` 一致）。
  2. 本实例运行数 < `kwiki.graph.build.max-concurrent-runs`（默认 2）时，按「批次 id、子任务 id」顺序查找可领取子任务：`state = QUEUED`，或活动状态且租约已过期（崩溃接管）。
  3. 对每个候选调用 `GraphBuildRunCoordinator.recover`（经 `acquireLease`，隔离令牌 +1），成功后交给虚拟线程执行。
- 全局并发：领取前统计全库「活动状态且租约未过期」的子任务数，达到上限不再领取（多实例共享上限）。
- 同一知识库唯一活动子任务沿用现有约束。
- 租约：`kwiki.graph.build.lease-duration`（默认 2m），执行线程每 `lease-duration / 3` 续期；续期失败（令牌被接管）立即停止，不再写任何权威状态。
- `GraphBuildRunCoordinator` 改为 `@Component`。

### 1.2 阶段推进

每个阶段开始前 `checkpoint(nextState, nextStage)`；隔离令牌失效抛出并终止。

| 阶段 | 行为 |
|---|---|
| EXTRACTING | 用 `GraphSourceInventoryPort` 枚举知识库当前有效资源 → `GraphSourceChildResolverPort` 取子任务 CHUNK 目标版本中的子块 → 按块：命中 `graph_extraction_result`（READY 且身份一致）则复用，否则经抽取插件调用模型 → `GraphExtractionValidator` 校验 → 写缓存 → 实体登记与消解。块级并发 = 抽取模型并发配置。 |
| PROJECTING | `UnweightedGraphProjectionBuilder` 生成投影 → 容量守卫 → `KnowledgeGraphStore.write`（按本次 graphVersion 隔离） |
| CLUSTERING | 社区检测插件（参数来自 `kwiki.graph.clustering.*`） |
| SUMMARIZING | `CommunityRepresentativeSelector` 挑代表 → 摘要插件 → `CommunitySummaryService` 校验 |
| INDEXING | `CommunityIndexWriter` 写 COMMUNITY 物理索引；`EntityMappingBackfillService` 回写 CHUNK 子块实体字段 |
| VALIDATING | `GraphSnapshotValidationService` 生成报告 → `GraphSnapshotSealingService` 封存为 READY；批次 autoPublish 时交给 `GraphSnapshotAutoPublishService` |

### 1.3 失败、取消、收尾

- 可重试错误（模型超时 / 429 / 5xx、ArcadeDB 或 ES 暂不可用）：阶段内指数退避，次数 `kwiki.graph.build.max-attempts`（默认 5）；耗尽或不可重试 → `FAILED`，`error_code` / `error_summary` 脱敏记录；管理端重试后从失败阶段继续（缓存保证抽取不重复）。
- 取消：每块、每阶段检查子任务状态，为 `CANCELLED` 时停止。
- 来源变化：沿用 epoch 守卫，`GraphSnapshotEpochGuard` 判定过期 → `STALE`。
- 批次收尾（任一子任务进入终态时计算）：全部 READY / PUBLISHED → 批次 `READY`；部分失败 → `PARTIAL_FAILED`；全部失败 → `FAILED`。

## 2. 环节插件

### 2.1 接口（包 `com.kwiki.graph.plugin`）

```java
public interface GraphExtractorPlugin extends IndexPlugin {
    String promptVersion();
    GraphExtractionResult extract(GraphExtractionInput input);
}
public interface CommunityDetectorPlugin extends IndexPlugin {
    CommunityDetectionResult detect(CommunityDetectionInput input);
}
public interface CommunitySummarizerPlugin extends IndexPlugin {
    String promptVersion();
    CommunitySummary summarize(CommunitySummaryInput input);
}
```

注册表：`GraphExtractorRegistry`、`CommunityDetectorRegistry`、`CommunitySummarizerRegistry`（均 `extends PluginRegistry<…>`）。

### 2.2 首个实现

| 类 | id | 行为 | 可用性 |
|---|---|---|---|
| `LlmGraphExtractorV1` | `kwiki-extractor-1` | `GraphExtractionPrompt` 组装 → 抽取模型 → 去除代码块围栏后解析 JSON → `GraphExtractionResult`（校验由执行器统一调用 `GraphExtractionValidator`）；`promptVersion()` 取提示词类的版本常量 | 抽取模型地址 / 密钥 / 模型名齐全 |
| `ArcadeDbLeidenV1` | `arcadedb-leiden-1` | 包装 `ArcadeDbNativeCommunityDetection` | ArcadeDB 已配置且能力报告支持原生 Leiden |
| `LlmCommunitySummarizerV1` | `kwiki-summarizer-1` | 摘要模型生成社区摘要 | 摘要模型配置齐全 |

- 抽取缓存身份：`extractor_version` = 插件 id，`prompt_version` = 插件 `promptVersion()`。
- `EntityRelationExtractionPort` / `CommunitySummaryPort` / `CommunityDetectionPort` 保留为插件内部或适配层使用；执行器只面向插件注册表。

### 2.3 记录插件 id（迁移 `V38__graph_build_plugins_and_progress.sql`）

- `graph_build_batch`、`graph_build_run`、`graph_snapshot` 新增 `extractor VARCHAR(64)`、`community_detector VARCHAR(64)`、`summarizer VARCHAR(64)`；历史行回填为三个首个实现 id 后改 NOT NULL。
- `graph_build_run` 新增进度列：`sources_total`、`sources_extracted`、`extraction_cache_hits`、`communities_total`、`communities_summarized`（BIGINT NOT NULL DEFAULT 0）。
- 提交批次时确定三项插件，子任务与快照照抄。

### 2.4 默认与选择

- `kwiki.graph.defaults.{extractor,community-detector,summarizer}`，默认三个首个实现；启动时校验已注册。
- `GET /api/v1/admin/knowledge-graphs/plugins`（ROLE_ADMIN）返回三类插件 `{id,label,available,unavailableReason}` 与默认值。
- 提交批次请求体新增可选 `extractor`、`communityDetector`、`summarizer`，缺省取默认；任一未注册或不可用 → 拒绝并说明原因。

### 2.5 模型配置

- `kwiki.graph.models.extraction.*` 与 `kwiki.graph.models.summary.*`（`kwiki.graph` 前缀已由 `GraphProperties` 绑定，故放在 `models` 子前缀下）：`base-url`、`api-key`、`model`、`request-timeout`、`max-concurrency`、`qps`、`max-retries`。
- 连接项（地址、密钥、模型名、超时）未配置时沿用问答模型（`ExternalServicesProperties.answerLlm()`）。默认：抽取并发 4 / QPS 5，摘要并发 2 / QPS 2，重试 2。
- 模型客户端由 `GraphModelClients` 组件内部构造（LangChain4j `OpenAiChatModel`，沿用 `KwikiAiClientConfiguration` 的 HTTP 客户端与超时方式），**不注册为 `ChatModel` Bean**，避免与问答模型按类型注入冲突。
- 限流器（信号量 + 令牌桶）按模型配置全局单例，所有子任务共享。

## 3. 容量、前端、测试

### 3.1 容量与成本

- 结构上限：抽取结束、写 ArcadeDB 前用 `GraphCapacityGuard`（`kwiki.graph.capacity.*`）；超限 `FAILED`，原因「实体数超过上限 {n}」等，不裁剪数据。
- 新增 `kwiki.graph.build.max-chunks-per-run`（默认 20000）：EXTRACTING 前来源块数超限直接 `FAILED`；`kwiki.graph.build.max-summaries-per-run`（默认等于社区上限）。
- 指标：阶段耗时 / 成败、模型调用次数（含重试）、抽取缓存命中率。

### 3.2 前端（图构建任务页）

- 提交表单：三项插件下拉（默认配置值，不可用置灰并显示原因）。
- 批次与子任务列表显示插件组合；子任务显示进度「已抽取 x / 总块数 y（缓存命中 z）」「已摘要 a / 社区数 b」。
- 快照列表显示插件组合。
- 状态 / 阶段中文映射：排队中、抽取中、投影中、聚类中、摘要中、写索引中、校验中、就绪、已发布、部分失败、失败、已取消、已过期、等待分块、不受支持、需要处理、清理中。

### 3.3 测试（不依赖 Docker，外部依赖用替身）

- 执行器：并发上限与单知识库唯一；阶段顺序与断点；隔离令牌失效即停止；租约过期接管从当前阶段继续且抽取命中缓存；块间取消；可重试错误退避、耗尽 FAILED；批次收尾 READY / PARTIAL_FAILED / FAILED。
- 插件：抽取 JSON 解析（合法 / 非法 / 越权内容）与提示词版本进入缓存身份；摘要校验失败不写入；检测包装传参与可用性。
- 配置：模型配置继承问答模型；默认插件未注册启动失败；V38 迁移校验。
- 容量：来源块超限抽取前拒绝；结构超限 FAILED 带原因。
- 接口与前端：插件列表；提交选择与不可用拒绝；进度与中文状态。
