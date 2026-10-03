# 图构建执行器与环节插件化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让提交的图构建批次真正被执行：新增执行器，按「抽取 → 投影 → 聚类 → 摘要 → 写索引 → 校验封存」推进子任务；抽取、社区检测、社区摘要做成插件，插件 id 记入批次、子任务、快照。

**Architecture:** 定时执行器在分布式锁下按全局并发上限领取子任务，经 `GraphBuildRunCoordinator` 取得租约与隔离令牌，在虚拟线程中逐阶段推进并写断点；各阶段组合已存在组件（本计划中的签名均已对照代码核实），三个可替换环节经插件注册表解析；图构建模型客户端独立构造，限流全局共享。

**Tech Stack:** Java 21、Spring Boot、JdbcTemplate、Flyway(MySQL)、LangChain4j `OpenAiChatModel`、ArcadeDB HTTP、Elasticsearch Java Client；admin-frontend Vue 3 + TS + Element Plus；JUnit 5 + Mockito + AssertJ、Vitest。

**Spec:** `docs/superpowers/specs/2026-10-03-graph-build-executor-design.md`

## Global Constraints

- 注释简体中文；历史迁移只读，只新增 `V38__graph_build_plugins_and_progress.sql`（新增后运行 `scripts/validate-migrations.sh`）；不引入 Docker / Testcontainers；不写真实密钥；提交信息中文 conventional，不加 `Co-Authored-By`。
- 插件 id：`kwiki-extractor-1`、`arcadedb-leiden-1`、`kwiki-summarizer-1`；复用 `com.kwiki.indexing.plugin.{IndexPlugin,PluginRegistry,PluginAvailability}`。
- 模型配置前缀固定为 `kwiki.graph.models.extraction.*` / `kwiki.graph.models.summary.*`。
- 执行器及其新增 Bean 仅在 `kwiki.graph.enabled=true` 时启用（`@ConditionalOnProperty(name="kwiki.graph.enabled", havingValue="true")`）。
- 所有权威状态写入必须带 `WHERE id=? AND lease_owner=? AND fencing_token=?`；令牌失效立即停止。
- 图构建模型客户端**不得**注册为 `ChatModel` / `StreamingChatModel` Bean。
- 不修改 COMMUNITY 索引结构版本（`community_index_version.mapping_schema_version`）。
- 已存在组件只做「新增方法 / 新增 Bean 定义」，不改变其已有方法行为。
- 单测 `./mvnw -q test -Dtest=<类名>`；全量 `./mvnw -q test`；前端 `cd admin-frontend && npm run build && npx vitest run`。外部依赖（模型、ArcadeDB、ES）在测试中一律用替身。

## 已核实的现有组件签名（实现时直接使用）

| 组件 | 签名 / 要点 |
|---|---|
| `GraphExtractionPrompt` | `VERSION = "graph-extraction-v1"`、`TEXT`（系统提示正文） |
| `GraphExtractionInput` | `(GraphSourceChunk sourceChunk, String content, String parentContext, String extractorVersion, String promptVersion, String entityLinkingVersion)` |
| `GraphExtractionResult` | `(GraphSourceChunk sourceChunk, List<GraphEntity> entities, List<GraphRelation> relations, String extractorVersion, String promptVersion, String entityLinkingVersion)` |
| `GraphExtractionValidator.validate(input, result)` | 来源与三项版本必须与输入一致；实体 `entityId` 不可重复；关系两端必须是本次实体集合中的 `entityId`；关系引文 `sourceChunkId` 必须等于输入块且 `endOffset ≤ content.length()`；失败抛 `GraphExtractionValidationException` |
| `GraphEntity` | `(String entityId, String canonicalName, GraphEntityType entityType, List<String> aliases)`；`entityId` 可为 null；`withEntityId(String)` |
| `GraphRelation` | `(String relationId, String sourceEntityId, GraphPredicate predicate, String targetEntityId, double confidence, List<GraphSourceRef> sourceRefs)`；`sourceRefs` 非空、`confidence ∈ [0,1]` |
| `GraphEntityType` | `PERSON, ORGANIZATION, PRODUCT, TECHNOLOGY, CONCEPT, LOCATION, EVENT, OTHER` |
| `GraphPredicate` | `USES, DEPENDS_ON, PART_OF, IMPLEMENTS, BASED_ON, RELATED_TO` |
| `GraphSourceRef` | `(String sourceChunkId, int startOffset, int endOffset)` |
| `GraphSourceChunk` | `(long kbId, String resourceType, long resourceId, Long revisionId, long lifecycleVersion, long indexVersion, String parserVersion, String chunkerVersion, String chunkKey, String contentHash)`；`sourceChunkId()` |
| `GraphEntityResolutionPort` / `ConservativeGraphEntityResolver(GraphEntityRegistryPort)` | `List<GraphEntity> resolve(long kbId, List<GraphEntity> candidates, String context, String resolverVersion)`——按顺序返回已登记实体（同序一一对应） |
| `JdbcGraphEntityRegistry` | `@Repository`，实现 `GraphEntityRegistryPort` |
| `UnweightedGraphProjectionBuilder.build` | `static UnweightedGraphProjection build(long kbId, long graphVersion, List<GraphEntity>, List<GraphRelation>)` |
| `UnweightedGraphProjection` | `(kbId, graphVersion, List<String> entityIds, List<Edge> edges, String projectionHash)` |
| `KnowledgeGraphStore.write` | `GraphWriteResult write(GraphWriteRequest(kbId, graphVersion, sources, entities, relations, runId, fencingToken))`；Bean 由 `ArcadeDbGraphConfiguration` 提供 |
| `CommunityDetectionInput` | `(long kbId, long graphVersion, GraphAlgorithmMode algorithmMode, int maxIterations, double resolution, String projectionHash, String runId, long fencingToken)` |
| `CommunityDetectionResult` | `(GraphAlgorithmMode algorithmMode, String engineVersion, List<CommunityMembership(kbId, graphVersion, entityId, communityId)> memberships)` |
| `ArcadeDbNativeCommunityDetection` | `@Component @ConditionalOnBean(ArcadeDbHttpAdapter.class)`，`detect(input)` |
| `ArcadeDbCapabilityProbe.probe()` | 返回 `ArcadeDbCapabilityReport(reachable, serverVersion, versionCompatible, leidenSupported, schemaSupported, databaseCreationAllowed, buildEnabled, failureCode)`；Bean 由 `ArcadeDbCapabilityConfiguration` 提供 |
| `CommunityRepresentativeSelector.select` | `static CommunitySummaryInput select(long kbId, long graphVersion, String communityId, List<GraphEntity>, List<GraphRelation>, List<GraphSourceChunk>, String content, String promptVersion)` |
| `CommunitySummary` | `(String title, String summary, List<String> keywords, List<String> coreConcepts, List<String> importantRelations, List<String> questionTypes, List<GraphSourceRef> sourceRefs)` |
| `CommunitySummaryValidator.requireValid(input, summary)` | 静态校验，失败抛异常 |
| `CommunityIndexWriter.write` | `WriteResult(written, verified) write(List<CommunityIndexWriteRequest>)`；请求字段：`physicalIndex, kbId, graphVersion, communityIndexVersion, communityId, sourceManifestId, sourceContentEpoch, sourceSecurityEpoch, sourceChunkIndexVersion, representativeEntityIds, entityCount, summaryModel, summaryPromptVersion, embeddingModel, embeddingDimensions, summary` |
| `EntityMappingBackfillService.backfill` | `Outcome backfill(Request(String physicalIndex, String childChunkKey, GraphSourceChunk sourceChunk, String entityLinkingVersion, List<String> entityIds))` |
| `GraphSourceInventoryPort` / `JdbcGraphSourceInventory` | `List<GraphResourceId> listCurrentSources(kbId)`、`long[] currentEpochs(kbId)`（`[content, security]`） |
| `GraphSourceManifestBuilder.build` | `GraphSourceManifest build(long runId, long kbId, int chunkIndexVersion, String entityLinkingVersion, long contentEpoch, long securityEpoch, long eventWatermark, boolean contiguousEventWatermark, List<GraphSourceChunk> sources, Map<String, GraphSourceReadiness> readiness)` |
| `GraphExtractionJobPlanner.plan` | `Optional<Long> plan(GraphExtractionJobSpec(sourceChunk, extractorVersion, promptVersion, entityLinkingVersion, contentEpoch, securityEpoch), boolean childIndexSucceeded, boolean publishedRevision)`——`INSERT IGNORE` PENDING 行并返回 id |
| `GraphSnapshotValidationService.validate` | `GraphSnapshotValidationReport validate(Input(kbId, graphVersion, GraphSourceManifest manifest, List<EntityMappingObservation> esObservations, Map<String,List<String>> extractedEntityIds, Map<String,List<String>> graphMentionEntityIds, List<String> inputEntityIds, List<CommunityMembership> memberships, Map<String,CommunitySummary> summaries, Map<String,Integer> observedEmbeddingDimensions, ConfigIdentity expected, ConfigIdentity observed, boolean refreshReadable))`；`ConfigIdentity(chunkIndexSchema, entityLinkingVersion, summaryModel, summaryPromptVersion, embeddingModel, embeddingDimensions)` |
| `GraphSnapshotSealingService.seal` | `boolean seal(long snapshotId, GraphSnapshotValidationReport report)` |
| `GraphSnapshotAutoPublishService.publishIfAutoPublishEnabled` | `(long runId, GraphSnapshot snapshot, GraphSnapshotState state, GraphValidationChecklist checklist, Long expectedSnapshotId)`；清单用 `report.toChecklist()` |
| `GraphBuildRunRecord` | `(id, batchId, kbId, chunkIndexVersion, chunkPhysicalIndex, communityIndexVersion, communityPhysicalIndex, graphVersion, chunkIndexSchema, entityLinkingVersion, contentEpoch, securityEpoch, state, stage, fencingToken, leaseOwner, leaseExpiresAt, eventWatermark)` |
| `GraphBuildRepository` | 已有 `acquireLease(runId, owner, leaseDuration, expectedFencingToken)`、`renewLease(runId, owner, fencingToken, leaseDuration)`、`checkpoint(runId, owner, fencingToken, state, stage, eventWatermark)`、`findActiveRunForUpdate(kbId)`、`cancelRun`、`requeueRun` |
| `ChunkHit` | `(chunkKey, parentChunkKey, kbId, resourceType, resourceId, revisionId, headingPath, charStart, charEnd, content, contentIds, sourceChunkId, entityIds, entityLinkingVersion, entityLinkingStatus)` |

**缺口（本计划补齐）**：无快照 / 清单落库方法；无「按知识库列出子块」方法；`ConservativeGraphEntityResolver`、`GraphSnapshotValidationService`、`GraphSourceManifestBuilder`、`GraphExtractionJobPlanner`、`GraphBuildRunCoordinator` 未注册为 Bean；抽取缓存只有入队、无读取与写回。

## File Structure

| 文件 | 动作 | 职责 |
|---|---|---|
| `src/main/resources/db/migration/V38__graph_build_plugins_and_progress.sql` | 新建 | 插件列与进度列 |
| `src/main/java/com/kwiki/graph/config/{GraphBuildProperties,GraphClusteringProperties,GraphPluginDefaults,GraphModelProperties}.java` | 新建 | 配置 |
| `.../graph/config/GraphBuildComponentsConfiguration.java` | 新建 | 为未注册组件提供 Bean |
| `.../graph/model/{GraphModelClients,ModelEndpoint,RateLimiter}.java` | 新建 | 模型客户端与限流 |
| `.../graph/plugin/*.java` | 新建 | 三类插件接口、注册表、首个实现、默认值校验 |
| `.../graph/persistence/{GraphExtractionCache,JdbcGraphExtractionCache}.java` | 新建 | 抽取缓存读取与写回 |
| `.../graph/GraphSourceChunkListingPort.java` + `.../infrastructure/elasticsearch/EsGraphSourceChunkListing.java` | 新建 | 按知识库列出子块 |
| `GraphBuildRepository` / `JdbcGraphBuildRepository` / `GraphBuildRunRecord` / `GraphBuildRunCommand` / `GraphBuildBatchCommand` | 改 | 领取、计数、进度、失败、过期、收尾、快照与清单落库、插件列 |
| `.../graph/build/{GraphBuildExecutor,GraphBuildRunner,RunContext,RunProgress,GraphBuildFailure,RetryPolicy,RunArtifacts}.java` | 新建 | 执行器与阶段上下文 |
| `.../graph/build/stage/{ExtractionStage,ProjectionStage,ClusteringStage,SummarizationStage,IndexingStage,ValidationStage}.java` | 新建 | 各阶段 |
| `GraphBuildBatchRequest` / `GraphBuildBatchService` / `GraphAdminQueryService` / `GraphBuildAdminController` | 改 | 插件选择、进度查询 |
| `.../wiki/api/GraphPluginController.java` | 新建 | 插件列表接口 |
| `admin-frontend/src/{api.ts,labels.ts,views/KnowledgeGraphView.vue}` | 改 | 前端 |

（Java 前缀 `src/main/java/com/kwiki/`，测试对应 `src/test/java/com/kwiki/`。）

---

### Task 1: 配置与迁移

**Files:** Create 四个配置记录、V38；Test `GraphBuildConfigurationPropertiesTest`

```java
@ConfigurationProperties("kwiki.graph.build")
public record GraphBuildProperties(
        @DefaultValue("10s") Duration pollInterval,
        @DefaultValue("2") int maxConcurrentRuns,
        @DefaultValue("2m") Duration leaseDuration,
        @DefaultValue("5") int maxAttempts,
        @DefaultValue("1s") Duration retryBaseDelay,
        @DefaultValue("20000") int maxChunksPerRun,
        @DefaultValue("5000") int maxSummariesPerRun) { }

@ConfigurationProperties("kwiki.graph.clustering")
public record GraphClusteringProperties(@DefaultValue("10") int maxIterations,
                                        @DefaultValue("1.0") double resolution) { }

@ConfigurationProperties("kwiki.graph.defaults")
public record GraphPluginDefaults(@DefaultValue("kwiki-extractor-1") String extractor,
                                  @DefaultValue("arcadedb-leiden-1") String communityDetector,
                                  @DefaultValue("kwiki-summarizer-1") String summarizer) { }

@ConfigurationProperties("kwiki.graph.models")
public record GraphModelProperties(@DefaultValue Model extraction, @DefaultValue Model summary) {
    /** 连接项（baseUrl / apiKey / model / requestTimeout）为空时沿用问答模型。 */
    public record Model(String baseUrl, String apiKey, String model, Duration requestTimeout,
                        Integer maxConcurrency, Double qps, Integer maxRetries) { }
}
```

V38（完整写出，不用注释代替）：

```sql
-- 图构建环节插件与进度：插件 id 记入批次、子任务、快照，便于追溯产出来源。
ALTER TABLE graph_build_batch ADD COLUMN extractor VARCHAR(64) NULL,
    ADD COLUMN community_detector VARCHAR(64) NULL, ADD COLUMN summarizer VARCHAR(64) NULL;
UPDATE graph_build_batch SET extractor = 'kwiki-extractor-1',
    community_detector = 'arcadedb-leiden-1', summarizer = 'kwiki-summarizer-1';
ALTER TABLE graph_build_batch MODIFY COLUMN extractor VARCHAR(64) NOT NULL,
    MODIFY COLUMN community_detector VARCHAR(64) NOT NULL, MODIFY COLUMN summarizer VARCHAR(64) NOT NULL;

ALTER TABLE graph_build_run ADD COLUMN extractor VARCHAR(64) NULL,
    ADD COLUMN community_detector VARCHAR(64) NULL, ADD COLUMN summarizer VARCHAR(64) NULL;
UPDATE graph_build_run SET extractor = 'kwiki-extractor-1',
    community_detector = 'arcadedb-leiden-1', summarizer = 'kwiki-summarizer-1';
ALTER TABLE graph_build_run MODIFY COLUMN extractor VARCHAR(64) NOT NULL,
    MODIFY COLUMN community_detector VARCHAR(64) NOT NULL, MODIFY COLUMN summarizer VARCHAR(64) NOT NULL;

ALTER TABLE graph_snapshot ADD COLUMN extractor VARCHAR(64) NULL,
    ADD COLUMN community_detector VARCHAR(64) NULL, ADD COLUMN summarizer VARCHAR(64) NULL;
UPDATE graph_snapshot SET extractor = 'kwiki-extractor-1',
    community_detector = 'arcadedb-leiden-1', summarizer = 'kwiki-summarizer-1';
ALTER TABLE graph_snapshot MODIFY COLUMN extractor VARCHAR(64) NOT NULL,
    MODIFY COLUMN community_detector VARCHAR(64) NOT NULL, MODIFY COLUMN summarizer VARCHAR(64) NOT NULL;

-- 子任务进度（执行器带隔离令牌更新）
ALTER TABLE graph_build_run
    ADD COLUMN sources_total BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN sources_extracted BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN extraction_cache_hits BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN communities_total BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN communities_summarized BIGINT NOT NULL DEFAULT 0;
```

- [ ] 写测试：用 `ApplicationContextRunner` + `@EnableConfigurationProperties` 断言四个记录的默认值与 `kwiki.graph.models.extraction.model=x` 的绑定，且与 `GraphProperties`（`kwiki.graph`）共存不冲突。
- [ ] 实现 → `scripts/validate-migrations.sh` → `./mvnw -q test -Dtest=GraphBuildConfigurationPropertiesTest` → 提交 `feat(graph): 新增图构建执行配置、模型配置与插件进度迁移`。

---

### Task 2: 模型客户端与全局限流

**Files:** Create `graph/model/{RateLimiter,ModelEndpoint,GraphModelClients}.java`；Test `RateLimiterTest`、`GraphModelClientsTest`

**Interfaces（Produces）:**

```java
/** 并发许可 + 令牌桶；睡眠函数可注入以便测试。 */
public final class RateLimiter {
    public RateLimiter(int maxConcurrency, double qps) { ... }
    RateLimiter(int maxConcurrency, double qps, java.util.function.LongSupplier nanoTime,
                java.util.function.LongConsumer sleeperNanos) { ... }
    public <T> T call(java.util.function.Supplier<T> body) { ... } // 中断时恢复标志并抛 IllegalStateException
}

public record ModelEndpoint(dev.langchain4j.model.chat.ChatModel model, RateLimiter limiter,
                            int maxRetries, String modelName) { }

@Component
@ConditionalOnProperty(name = "kwiki.graph.enabled", havingValue = "true")
public class GraphModelClients {
    public GraphModelClients(GraphModelProperties models, ExternalServicesProperties external) { ... }
    public Optional<ModelEndpoint> extraction();
    public Optional<ModelEndpoint> summary();
    /** 合并问答模型后仍缺的配置键（如 kwiki.graph.models.extraction.api-key），用于可用性原因。 */
    public List<String> missing(String purpose); // "extraction" | "summary"
}
```

- 合并规则：`baseUrl` / `apiKey` / `model` / `requestTimeout` 为空取 `external.answerLlm()` 对应值；并发 / QPS / 重试缺省：抽取 4 / 5.0 / 2，摘要 2 / 2.0 / 2。
- 构造 `dev.langchain4j.model.openai.OpenAiChatModel.builder().baseUrl(...).apiKey(...).modelName(...).timeout(...)`，HTTP 客户端构造方式与 `KwikiAiClientConfiguration#kwikiChatModel` 一致（复用 `CancellableModelHttpClient.Builder`）。两个端点在构造器中各建一次，**不声明 `@Bean`**。

- [ ] 写测试：只配问答模型时两端点可用、模型名等于问答模型；单独配置抽取模型名时覆盖；问答模型缺密钥且未单独配置时 `extraction()` 为空、`missing("extraction")` 含 `kwiki.graph.models.extraction.api-key`；`RateLimiter` 第三个调用在两个许可占满时阻塞（用 `CountDownLatch`）、QPS=2 时两次调用间隔 ≥ 500ms（注入时钟与睡眠函数断言）。
- [ ] 实现 → 运行 → 提交 `feat(graph): 新增图构建模型客户端与全局限流`。

---

### Task 3: 插件接口、注册表与首个实现

**Files:** Create `graph/plugin/{GraphExtractorPlugin,CommunityDetectorPlugin,CommunitySummarizerPlugin,GraphExtractorRegistry,CommunityDetectorRegistry,CommunitySummarizerRegistry,LlmGraphExtractorV1,ArcadeDbLeidenV1,LlmCommunitySummarizerV1,GraphPluginDefaultsValidator}.java`；Tests 每个实现一个 + 注册表

**Interfaces（Produces）:**

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
    /** 返回的模型名写入快照与社区索引（summaryModel）。 */
    String modelName();
    CommunitySummary summarize(CommunitySummaryInput input);
}
@Component public class GraphExtractorRegistry extends PluginRegistry<GraphExtractorPlugin> {
    public GraphExtractorRegistry(List<GraphExtractorPlugin> p) { super("抽取器", p); } }
// CommunityDetectorRegistry("社区检测")、CommunitySummarizerRegistry("摘要器") 同理
```

**`LlmGraphExtractorV1`**（id `kwiki-extractor-1`，label「大模型抽取 v1」，`promptVersion()` = `GraphExtractionPrompt.VERSION`）：
- 系统消息 = `GraphExtractionPrompt.TEXT` + 输出格式说明；用户消息含 `content` 与 `parentContext`。要求模型只输出 JSON：

```json
{"entities":[{"id":"e1","name":"…","type":"TECHNOLOGY","aliases":["…"]}],
 "relations":[{"source":"e1","target":"e2","predicate":"USES","confidence":0.8,"start":12,"end":40}]}
```

- 解析：去除 ```json 围栏 → Jackson → 实体 `new GraphEntity(id, name, GraphEntityType.valueOf(type), aliases)`（未知类型 → `OTHER`）；关系 `new GraphRelation(relationId, source, predicate, target, confidence, List.of(new GraphSourceRef(input.sourceChunk().sourceChunkId(), start, end)))`，`relationId = sha256(sourceChunkId + "|" + source + "|" + predicate + "|" + target)`，未知谓词 → `RELATED_TO`；单条关系构造失败（越界、置信度非法）丢弃该条，不影响其余。
- 返回 `new GraphExtractionResult(input.sourceChunk(), entities, relations, id(), promptVersion(), input.entityLinkingVersion())`。
- 调用：`endpoint.limiter().call(() -> endpoint.model().chat(List.of(SystemMessage.from(...), UserMessage.from(...))).aiMessage().text())`。
- 错误分类：JSON 不可解析 → `GraphBuildFailure.permanent("EXTRACTION_INVALID_JSON", "抽取结果不是合法 JSON")`；超时 / 429 / 5xx（LangChain4j 抛出的 HTTP 异常，按状态码或异常类型判断）→ `GraphBuildFailure.retryable("MODEL_UNAVAILABLE", "抽取模型暂不可用")`。
- `availability()`：`clients.extraction()` 为空 → `unavailable("缺少配置：" + String.join("、", clients.missing("extraction")))`。

**`LlmCommunitySummarizerV1`**（id `kwiki-summarizer-1`，label「大模型摘要 v1」，`promptVersion()` = `"community-summary-v1"`，`modelName()` = 摘要端点模型名）：
- 系统提示：只依据输入实体、关系、来源内容生成中文社区摘要；输出 JSON `{title, summary, keywords[], coreConcepts[], importantRelations[], questionTypes[], sourceRefs:[{sourceChunkId,start,end}]}` → `CommunitySummary`。
- 错误分类同抽取器（code 前缀 `SUMMARY_`）；可用性取 `clients.summary()`。

**`ArcadeDbLeidenV1`**（id `arcadedb-leiden-1`，label「ArcadeDB Leiden v1」）：注入 `ObjectProvider<ArcadeDbNativeCommunityDetection>`、`ObjectProvider<ArcadeDbCapabilityProbe>`；`detect` 委托；`availability()`：检测 Bean 缺失 → 「ArcadeDB 未配置」；`probe()` 结果 `!reachable` / `!leidenSupported` / `!buildEnabled` → 对应中文原因（附 `failureCode`）。可用性结果缓存 1 分钟，避免每次列插件都探测。

**`GraphPluginDefaultsValidator`**：启动时 `GraphPluginDefaults` 三项必须已注册，否则 `IllegalStateException`（参照 `IndexingDefaultsValidator`）。

所有插件与注册表类加 `@ConditionalOnProperty(name = "kwiki.graph.enabled", havingValue = "true")`。

- [ ] 写测试：抽取器——合法 JSON、带围栏 JSON、未知类型 / 谓词回退、越界关系被丢弃、非法 JSON 永久失败、模型异常可重试、结果经 `GraphExtractionValidator.validate` 通过；摘要器——合法 JSON、非法 JSON；检测器——不可用原因与委托；注册表与默认值校验。
- [ ] 实现 → 运行 → 提交 `feat(graph): 新增抽取、社区检测、社区摘要插件与首个实现`。

---

### Task 4: 补齐 Bean、子块列举、抽取缓存

**Files:** Create `graph/config/GraphBuildComponentsConfiguration.java`、`graph/GraphSourceChunkListingPort.java`、`infrastructure/elasticsearch/EsGraphSourceChunkListing.java`、`graph/persistence/{GraphExtractionCache,JdbcGraphExtractionCache}.java`；Tests `EsGraphSourceChunkListingTest`、`JdbcGraphExtractionCacheTest`

**Interfaces（Produces）:**

```java
@Configuration
@ConditionalOnProperty(name = "kwiki.graph.enabled", havingValue = "true")
public class GraphBuildComponentsConfiguration {
    @Bean GraphEntityResolutionPort graphEntityResolver(GraphEntityRegistryPort registry) {
        return new ConservativeGraphEntityResolver(registry); }
    @Bean GraphSnapshotValidationService graphSnapshotValidationService() {
        return new GraphSnapshotValidationService(); }      // 仅有默认构造器（已核实）
    @Bean GraphSourceManifestBuilder graphSourceManifestBuilder() { return new GraphSourceManifestBuilder(); }
    @Bean GraphExtractionJobPlanner graphExtractionJobPlanner(JdbcTemplate jdbc) {
        return new GraphExtractionJobPlanner(jdbc); }
    @Bean GraphBuildRunCoordinator graphBuildRunCoordinator(GraphBuildRepository repository) {
        return new GraphBuildRunCoordinator(repository); }
}

/** 列出 CHUNK 物理索引中某知识库全部有效 CHILD（按 chunkKey 排序）。 */
public interface GraphSourceChunkListingPort {
    List<ChunkHit> listChildren(String chunkPhysicalIndex, long kbId, int maxResults);
}

public interface GraphExtractionCache {
    /** 身份一致且 READY 的缓存结果；不存在或非 READY 返回 empty。 */
    Optional<GraphExtractionResult> findReady(GraphSourceChunk source, String extractorVersion,
                                              String promptVersion, String entityLinkingVersion);
    /** 写回结果并置 READY（行由 GraphExtractionJobPlanner.plan 预先创建）。 */
    void markReady(long extractionId, GraphExtractionResult result);
    void markFailed(long extractionId, String errorClass, String errorSummary);
}
```

- 已核实：上述五个类目前均未在任何地方声明为 Bean，且 `GraphSnapshotValidationService`、`GraphSourceManifestBuilder` 只有默认构造器。
- `EsGraphSourceChunkListing`：`search` 过滤 `kbId`、`chunkLevel=CHILD`，`search_after` 按 `chunkKey` 分页（每页 500），总数超过 `maxResults` 时抛 `GraphBuildFailure.permanent("CAPACITY_EXCEEDED_SOURCES", "来源块数超过上限 " + maxResults)`；`_source` 映射为 `ChunkHit`（字段与 `EsChunkLookup` 一致）。
- `JdbcGraphExtractionCache`：`entities_json` / `relations_json` 用 Jackson 序列化 `List<GraphEntity>` / `List<GraphRelation>`。

- [ ] 写测试：列举分页拼接、超限永久失败；缓存命中 / 未命中 / 非 READY、写回后可读、失败记录截断到列宽。
- [ ] 实现 → 运行 → 提交 `feat(graph): 补齐图构建组件 Bean、子块列举与抽取缓存`。

---

### Task 5: 仓储扩展

**Files:** Modify `GraphBuildRepository`、`JdbcGraphBuildRepository`、`GraphBuildRunRecord`、`GraphBuildRunCommand`、`GraphBuildBatchCommand`；Test `JdbcGraphBuildRepositoryExecutorTest`（Mockito `JdbcOperations`，断言 SQL 片段与参数）

**Interfaces（Produces）:**

```java
List<GraphBuildRunRecord> findClaimableRuns(Instant now, int limit);   // QUEUED，或活动状态且 lease_expires_at < now；按 batch_id, id
int countLiveRuns(Instant now);                                      // 活动状态且 lease_expires_at >= now
boolean updateProgress(long runId, String owner, long fencingToken, RunProgress progress);
boolean failRun(long runId, String owner, long fencingToken, String errorCode, String errorSummary);
boolean markStale(long runId, String owner, long fencingToken);
void settleBatch(long batchId);                                      // 子任务均终态时：全 READY/PUBLISHED→READY；部分失败→PARTIAL_FAILED；全失败→FAILED
long persistManifest(GraphSourceManifest manifest);                  // 写 graph_source_manifest（state=COMPLETE）+ entries，返回 manifest id
long createSnapshot(GraphBuildRunRecord run, long manifestId, String manifestHash,
                    GraphSnapshotIdentity identity);                 // state=BUILDING
boolean updateSnapshotCounts(long snapshotId, long entities, long relations, long communities);
```

- `record RunProgress(long sourcesTotal, long sourcesExtracted, long extractionCacheHits, long communitiesTotal, long communitiesSummarized)`（放 `graph/build`，仓储引用）。
- `record GraphSnapshotIdentity(String extractor, String communityDetector, String summarizer, String algorithmMode, String engineVersion, int graphSchemaVersion, String summaryModel, String summaryPromptVersion, String embeddingModel, Integer embeddingDimensions)`；`createSnapshot` 写入 `graph_snapshot` 全部 NOT NULL 列（`chunk_index_schema` 取自 run，`content_epoch` / `security_epoch` 取自 run）。`graphSchemaVersion` 取 `ArcadeDbSchemaDefinition.SCHEMA_VERSION`（现为包内可见的 `static final int SCHEMA_VERSION = 1`，本任务将其改为 `public`）。
- `GraphBuildRunRecord` 末尾新增 `extractor, communityDetector, summarizer`；`GraphBuildBatchCommand` / `GraphBuildRunCommand` 增同名字段，`createBatch` / `createRun` 写入。
- 所有带 owner / fencingToken 的更新以 `WHERE id=? AND lease_owner=? AND fencing_token=?` 保护；`failRun` 同时写 `completed_at`。

- [ ] 写测试 → 实现 → 运行 → 提交 `feat(graph): 图构建仓储支持领取、进度、失败、批次收尾与快照落库`。

---

### Task 6: 阶段实现

**Files:** Create `graph/build/{RunContext,RunArtifacts,GraphBuildFailure,RetryPolicy}.java`、`graph/build/stage/*.java`；Tests 每阶段一个

**Interfaces（Produces）:**

```java
public final class GraphBuildFailure extends RuntimeException {
    public static GraphBuildFailure retryable(String code, String summary);
    public static GraphBuildFailure permanent(String code, String summary);
    public boolean retryable(); public String code();
}
public record RetryPolicy(int maxAttempts, Duration baseDelay) {
    public <T> T run(Supplier<T> body); // 可重试：baseDelay×2^n（上限 30s）；耗尽抛最后一次；永久直接抛
}
public record RunContext(GraphBuildRunRecord run, String owner, long fencingToken,
                         BooleanSupplier cancelled, Consumer<RunProgress> progress) {
    public void throwIfCancelled(); // 取消 → 抛 CancellationException
}
/** 阶段间传递的中间产物；崩溃恢复时由 GraphBuildRunner 从持久层重建（见 Task 7）。 */
public final class RunArtifacts {
    List<GraphSourceChunk> sources; Map<String, ChunkHit> childrenBySourceChunkId;
    List<GraphEntity> entities; List<GraphRelation> relations;
    Map<String, List<String>> extractedEntityIds;   // sourceChunkId → 正式 entityId
    UnweightedGraphProjection projection; CommunityDetectionResult detection;
    Map<String, CommunitySummary> summaries; long manifestId; String manifestHash; long snapshotId;
}
public interface GraphBuildStage { void execute(RunContext ctx, RunArtifacts artifacts); }
```

各阶段（依赖均为已核实签名）：

1. **ExtractionStage**：
   - `GraphSourceChunkListingPort.listChildren(run.chunkPhysicalIndex(), run.kbId(), maxChunksPerRun)`；对每个 `ChunkHit` 构造 `GraphSourceChunk`（`indexVersion` = run.chunkIndexVersion，`parserVersion` / `chunkerVersion` 取 CHUNK 版本配置，`contentHash` = sha256(content)）。
   - 进度 `sourcesTotal`。块级并发 = 抽取端点 `maxConcurrency`（虚拟线程 + 信号量）。
   - 每块：`ctx.throwIfCancelled()` → `extractionCache.findReady(...)` 命中则计入 `extractionCacheHits`；否则 `planner.plan(spec, true, true)` 取 id → `retry.run(() -> extractor.extract(new GraphExtractionInput(source, hit.content(), "", extractor.id(), extractor.promptVersion(), run.entityLinkingVersion())))` → `GraphExtractionValidator.validate(input, result)`（`GraphExtractionValidationException` → 永久失败 `EXTRACTION_INVALID`）→ `cache.markReady(id, result)`。
   - 结果按块：`resolver.resolve(run.kbId(), result.entities(), hit.content(), extractor.id())` 得同序正式实体 → 建「临时 id → 正式 id」映射 → 关系端点改写为正式 id（`relationId` 重算）→ 汇入 `artifacts.entities`（按 entityId 去重）/ `relations`；`extractedEntityIds[sourceChunkId]` = 该块正式 id。
   - 每 50 块与结束时 `ctx.progress()`。
2. **ProjectionStage**：`GraphCapacityGuard.check(new Counts(entities, relations, relationSources, 0), graphProperties.capacity())` 不允许 → 永久失败（code = reason，summary 中文，如「实体数超过上限 50000」）；`UnweightedGraphProjectionBuilder.build(...)`；`retry.run(() -> store.write(new GraphWriteRequest(kbId, graphVersion, sources, entities, relations, String.valueOf(run.id()), fencingToken)))`。
3. **ClusteringStage**：`detector.detect(new CommunityDetectionInput(kbId, graphVersion, graphProperties.algorithmMode(), clustering.maxIterations(), clustering.resolution(), projection.projectionHash(), String.valueOf(run.id()), fencingToken))`；社区数超 `maxSummariesPerRun` 或容量守卫 communities 超限 → 永久失败。
4. **SummarizationStage**：按 `communityId` 分组成员；每社区 `CommunityRepresentativeSelector.select(kbId, graphVersion, communityId, 社区实体, 两端都在社区内的关系, 相关来源, 拼接来源内容, summarizer.promptVersion())` → `retry.run(() -> summarizer.summarize(input))` → `CommunitySummaryValidator.requireValid(input, summary)`（失败 → 永久失败 `SUMMARY_INVALID`）；进度 `communitiesTotal` / `communitiesSummarized`。
5. **IndexingStage**：先 `manifestBuilder.build(run.id(), kbId, chunkIndexVersion, entityLinkingVersion, contentEpoch, securityEpoch, run.eventWatermark(), true, sources, 全部 READY 的 readiness)` → `repository.persistManifest` → `repository.createSnapshot(run, manifestId, manifestHash, identity)`；`communityIndexWriter.write(每社区一条 CommunityIndexWriteRequest(run.communityPhysicalIndex(), …, String.valueOf(manifestId), …, summary))`（`verified=false` → 可重试失败）；对每个子块 `entityMappingBackfill.backfill(new Request(run.chunkPhysicalIndex(), hit.chunkKey(), source, run.entityLinkingVersion(), extractedEntityIds.get(sourceChunkId)))`；`updateSnapshotCounts`。
6. **ValidationStage**：组装 `GraphSnapshotValidationService.Input`（`esObservations` 由回填后读取子块实体字段得到；`graphMentionEntityIds` = `extractedEntityIds`；`inputEntityIds` = 投影 entityIds；`expected` / `observed` 的 `ConfigIdentity` 分别取 run 与实际产物）→ `validate` → `sealing.seal(snapshotId, report)` → 读回 `GraphSnapshot` → `autoPublish.publishIfAutoPublishEnabled(run.id(), snapshot, GraphSnapshotState.READY, report.toChecklist(), null)`。

- [ ] 写测试（每阶段：正常路径、失败分类、取消、进度；抽取阶段重点：缓存命中不调用插件、临时 id 改写为正式 id、单块校验失败为永久失败）→ 实现 → 运行 → 提交 `feat(graph): 实现图构建各阶段`。

---

### Task 7: 执行器与单任务推进

**Files:** Create `graph/build/{GraphBuildRunner,GraphBuildExecutor}.java`；Tests `GraphBuildRunnerTest`、`GraphBuildExecutorTest`

**Interfaces（Produces）:**
- `GraphBuildRunner.run(GraphBuildRunRecord run, String owner, long fencingToken, BooleanSupplier leaseLost)`：
  - 起始阶段：`QUEUED` → EXTRACTING；其余从 `run.state()` 对应阶段开始。
  - 恢复时重建 `RunArtifacts`：从抽取缓存按清单重读实体关系（恢复点 ≥ PROJECTING 时），从 ArcadeDB / 快照行读回已有产物所需字段；无法安全重建时从 EXTRACTING 重做（抽取命中缓存，成本低）——**实现选择：恢复点在 SUMMARIZING 及之前一律从 EXTRACTING 重做；INDEXING / VALIDATING 从 EXTRACTING 重做并跳过已存在快照的重复创建（按 run_id 查找已有 BUILDING 快照复用）**。
  - 每阶段：`coordinator.checkpoint(run, owner, token, state, stage, run.eventWatermark())` → `stage.execute` → `GraphBuildMetrics.recordStage`。
  - 异常：`GraphBuildFailure` → `repository.failRun`；`CancellationException` → 退出；epoch 过期（`JdbcGraphSourceInventory.currentEpochs` 与 run 不一致）→ `markStale`；`leaseLost` 为真 → 立即退出、不写任何状态。
  - 结束（任一终态）→ `repository.settleBatch(run.batchId())`。
- `GraphBuildExecutor`（`@Component`，`@ConditionalOnProperty(kwiki.graph.enabled=true)`）：
  - `@Scheduled(fixedDelayString = "${kwiki.graph.build.poll-interval:10s}")`；`KwikiDistributedLocks.acquire("graph-build-executor", 200ms, 60s)`（不可用视为单实例）。
  - `repository.countLiveRuns(now)` ≥ `maxConcurrentRuns` 则返回；否则 `findClaimableRuns(now, 余量)` → 逐个 `coordinator.recover(run.kbId(), instanceOwner, leaseDuration)`（失败跳过）→ 虚拟线程执行 `runner.run`。
  - 每个运行中的子任务：续租定时任务每 `leaseDuration/3` 调 `renewLease`，返回 false 则置 `leaseLost`。
  - `@PreDestroy` 停止续租与执行线程（不改状态，交由租约过期后接管）。

- [ ] 写测试：已有 2 个存活子任务时不领取；同库不重复；阶段顺序与断点调用；续租失败后不再写状态；永久失败置 FAILED 且批次收尾；取消后退出；恢复时抽取全部命中缓存、插件零调用。
- [ ] 实现 → 运行 → 提交 `feat(graph): 新增图构建执行器，提交的批次开始真正执行`。

---

### Task 8: 提交批次的插件选择、插件列表与进度查询

**Files:** Modify `GraphBuildBatchRequest`、`GraphBuildBatchService`、`GraphBuildAdminController`、`GraphAdminQueryService`；Create `wiki/api/GraphPluginController.java`；Tests 对应

- 提交请求体新增可选 `extractor`、`communityDetector`、`summarizer`，缺省取 `GraphPluginDefaults`；未注册或不可用 → `IllegalArgumentException("{类别} {id} 不可用：{原因}")`（经现有映射 400）；写入 `GraphBuildBatchCommand` 与各 `GraphBuildRunCommand`。
- `GET /api/v1/admin/knowledge-graphs/plugins`（类级 `@PreAuthorize("hasRole('ADMIN')")`）→ `{extractors, communityDetectors, summarizers, defaults}`，项 `{id,label,available,unavailableReason}`。
- `GraphAdminQueryService.batches()` / `runsOfBatch()` / `snapshots()` 的 SELECT 增加插件三列与进度五列（经现有 `camel` 转换输出）。

- [ ] 写测试 → 实现 → 运行 → 提交 `feat(graph-api): 提交批次可选择插件，新增插件列表与进度查询`。

---

### Task 9: 管理端前端

**Files:** Modify `admin-frontend/src/{api.ts,labels.ts,views/KnowledgeGraphView.vue}`；Test `admin-frontend/tests/knowledge-graph-executor.spec.ts`

- `labels.ts` 新增 `graphStateLabel`：QUEUED 排队中、EXTRACTING 抽取中、PROJECTING 投影中、CLUSTERING 聚类中、SUMMARIZING 摘要中、INDEXING 写索引中、VALIDATING 校验中、READY 就绪、PUBLISHED 已发布、PARTIAL_FAILED 部分失败、FAILED 失败、CANCELLED 已取消、STALE 已过期、WAITING_FOR_CHUNKS 等待分块、UNSUPPORTED 不受支持、NEEDS_ATTENTION 需要处理、CLEANING 清理中。
- `api.ts`：`GraphBatch` / `GraphRun` / `GraphSnapshotRow` 增插件与进度字段；新增 `graphPlugins()`；`graphSubmit` 载荷可含三项插件。
- 页面：提交表单三项插件下拉（默认 `defaults`，不可用置灰 + 原因）；批次 / 子任务 / 快照显示插件组合；子任务进度「已抽取 x / y（缓存命中 z）」「已摘要 a / b」；状态与阶段中文（相同时只显示一次）。

- [ ] 写测试（下拉默认与不可用置灰、进度文本、中文状态）→ 实现 → `npm run build && npx vitest run` → 提交 `feat(admin): 图构建页支持插件选择、执行进度与中文状态`。

---

### Task 10: 收尾

- [ ] 核对：`EntityRelationExtractionPort` / `CommunitySummaryPort` 若仍无实现且无引用，删除并改由插件接口承担（同步更新引用处）；`GraphBuildRunCoordinator` 被执行器使用。
- [ ] 文档 `docs/graph-arcadedb-operations.md` 补：执行器配置（`kwiki.graph.build.*`）、模型配置与继承规则（`kwiki.graph.models.*`）、插件与默认值（`kwiki.graph.defaults.*`）、调用量上限、故障恢复行为。
- [ ] `./mvnw -q test`、`scripts/validate-migrations.sh`、`cd admin-frontend && npm run build && npx vitest run` 全部通过；提交 `docs(graph): 图构建执行器与插件运维说明`。
