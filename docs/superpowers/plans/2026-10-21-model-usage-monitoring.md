# 模型 token 用量监控 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 采集每次 LLM / embedding 调用的 token 用量（来源 × 步骤 × provider × 模型 × 用户），落 MySQL 分钟聚合表与 7 天明细表，并在管理端用 ECharts 按时间、来源、用户、步骤/模型展示趋势。

**Architecture:** 新包 `com.kwiki.usage` 承载用量领域（来源判定上下文、事件、内存队列、JDBC 仓储、定时 flush 与清理、查询服务与管理接口）；对话模型在 `OpenAiModelClientFactory` 挂 langchain4j `ChatModelListener` 采集，embedding 在 `QwenEmbeddingClient` 解析 `usage` 采集；来源经 `ModelUsageContext`（显式作用域）与 `RunContext.usageSource()`（请求上下文）判定。前端只调两个接口（`/users`、`/series`），维度树、折线分组、汇总与明细在前端由同一份序列数据计算。设计文档：`docs/superpowers/specs/2026-10-21-model-usage-monitoring-design.md`。

**Tech Stack:** Java 21、Spring Boot 3.5.6、langchain4j 1.20.0、Spring JDBC（`JdbcOperations`）、Flyway、MySQL 8；Vue 3.5 + element-plus 2.11 + echarts 5 + vue-echarts 7；JUnit 5 / AssertJ / Mockito / MockWebServer；Vitest + @testing-library/vue。

## Global Constraints

- 全部注释、Javadoc、界面文案、提交信息使用简体中文；代码标识符英文。提交信息格式：`feat(usage): …` / `test(usage): …` / `docs(usage): …`。
- **提交信息绝不添加 `Co-Authored-By` 之类的 Claude 署名尾行。**
- `src/main/resources/application.yml` 不新增配置；新配置用 `@Value("${key:默认值}")`，实际值放 Nacos。新增配置键：`kwiki.model-usage.retention-days`（默认 `180`）、`kwiki.model-usage.call-retention-days`（默认 `7`）、`kwiki.model-usage.flush-interval-ms`（默认 `10000`）。
- 来源取值固定为 `ONLINE`、`EVAL`、`BACKGROUND`；embedding 步骤固定为 `QUERY_EMBEDDING`（查询向量）、`INDEX_EMBEDDING`（索引向量）；其余步骤为 `ModelPurpose` 枚举名，中文名取 `ModelPurpose.label()`。
- 后台任务（无用户）的 `user_id` 记为 `0`，界面显示「系统」。
- 采集侧任何异常都不得影响模型调用结果；入队绝不阻塞。
- 数据库中时间一律为 UTC：写入用 `LocalDateTime.ofInstant(instant, ZoneOffset.UTC)` 绑定参数；分桶用 `TIMESTAMPDIFF(SECOND, '1970-01-01 00:00:00', bucket_start)`，不得使用 `UNIX_TIMESTAMP`。
- 管理接口前缀 `/api/v1/admin/model-usage`，类级 `@PreAuthorize("hasRole('ADMIN')")`，返回 `com.kk2004.common.response.TransDTO`；业务错误抛 `com.kk2004.common.exception.BusinessException(int code, String message)`。
- 新 Bean 依赖 `JdbcOperations` 时一律经 `ObjectProvider<JdbcOperations>` 注入并在缺失时退化为 no-op（离线测试上下文不装配数据源）。
- 后端运行单测：`./mvnw -q -Dtest=<类名> test`（`JAVA_HOME` 未设置时参考 `scripts/test-offline.sh` 中的 JDK 21 路径）；前端：`cd admin-frontend && npm run typecheck && npx vitest run && npm run build`。
- 管理端依赖版本固定写死（与现有 `package.json` 风格一致，不用 `^`）。

## File Structure

| 文件 | 职责 |
|---|---|
| `src/main/java/com/kwiki/usage/UsageSource.java` | 来源枚举 |
| `src/main/java/com/kwiki/usage/UsageSteps.java` | embedding 步骤常量与步骤中文名 |
| `src/main/java/com/kwiki/usage/UsageEvent.java` | 一次调用的用量事件 |
| `src/main/java/com/kwiki/usage/ModelUsageContext.java` | 线程级来源/用户/步骤作用域与判定 |
| `src/main/java/com/kwiki/rag/orchestration/RunContext.java`（改） | 增加 `usageSource` |
| `src/main/java/com/kwiki/rag/orchestration/RunObserver.java`（改） | 默认方法 `usageSource()` |
| `src/main/java/com/kwiki/infrastructure/orchestration/LangGraphAgenticWorkflow.java`（改） | 按观测者设置 `RunContext` 来源 |
| `src/main/java/com/kwiki/eval/WorkflowProbe.java`、`EvalRunService.java`（改） | 评测来源 |
| `src/main/resources/db/migration/V43__model_usage.sql` | 两张表 |
| `src/main/java/com/kwiki/usage/ModelUsageRecorder.java` | 有界内存队列 |
| `src/main/java/com/kwiki/usage/MinuteAggregate.java` | 分钟聚合行 |
| `src/main/java/com/kwiki/usage/ModelUsageRepository.java` | JDBC 写入、查询、清理 |
| `src/main/java/com/kwiki/usage/ModelUsageFlusher.java` | 定时预聚合与落库 |
| `src/main/java/com/kwiki/usage/ModelUsageRetentionJob.java` | 每日清理 |
| `src/main/java/com/kwiki/infrastructure/ai/registry/ClientSpec.java`（改） | 增加 `providerName`、`step` |
| `src/main/java/com/kwiki/infrastructure/ai/registry/DefaultModelRegistry.java`（改） | 传入 provider 名与步骤；embedding 走新工厂方法 |
| `src/main/java/com/kwiki/infrastructure/ai/registry/ModelUsageListener.java` | 对话模型用量监听器 |
| `src/main/java/com/kwiki/infrastructure/ai/registry/ModelClientFactory.java`、`OpenAiModelClientFactory.java`（改） | 挂监听器；embedding 新重载 |
| `src/main/java/com/kwiki/infrastructure/ai/QwenEmbeddingClient.java`（改） | 解析 usage 并记录 |
| `src/main/java/com/kwiki/indexing/job/IndexingWorker.java`、`infrastructure/elasticsearch/CommunityIndexWriter.java`（改） | 索引向量作用域 |
| `src/main/java/com/kwiki/usage/ModelUsageQueryService.java` | 分桶、补 0、升粒度、校验 |
| `src/main/java/com/kwiki/usage/ModelUsageAdminController.java` | 管理接口 |
| `admin-frontend/src/usageFormat.ts` | 单位自适应格式化 |
| `admin-frontend/src/usageSeries.ts` | 维度树、折线分组、汇总/明细计算 |
| `admin-frontend/src/views/ModelUsageView.vue` | 页面 |
| `admin-frontend/src/api.ts`、`router.ts`、`components/AdminLayout.vue`（改） | 接口、路由、导航 |
| `docs/model-usage.md` | 运行说明 |

---

### Task 1: 用量来源判定与评测标记

**Files:**
- Create: `src/main/java/com/kwiki/usage/UsageSource.java`、`UsageSteps.java`、`UsageEvent.java`、`ModelUsageContext.java`
- Modify: `src/main/java/com/kwiki/rag/orchestration/RunContext.java`、`RunObserver.java`
- Modify: `src/main/java/com/kwiki/infrastructure/orchestration/LangGraphAgenticWorkflow.java`（`run.observer(observer);` 所在行）
- Modify: `src/main/java/com/kwiki/eval/WorkflowProbe.java`、`src/main/java/com/kwiki/eval/EvalRunService.java`（`evaluate(...)` 方法）
- Test: `src/test/java/com/kwiki/usage/ModelUsageContextTest.java`、`UsageStepsTest.java`
- Test: `src/test/java/com/kwiki/rag/orchestration/WorkflowRunObserverTest.java`（追加用例）

**Interfaces:**
- Produces:
  - `enum UsageSource { ONLINE, EVAL, BACKGROUND }`
  - `UsageSteps.QUERY_EMBEDDING`、`UsageSteps.INDEX_EMBEDDING`、`static String UsageSteps.label(String step)`
  - `record UsageEvent(Instant occurredAt, UsageSource source, String step, String provider, String model, long userId, String requestId, int inputTokens, int outputTokens, long latencyMs, boolean success, String errorType)`
  - `ModelUsageContext.open(UsageSource, Long userId)`、`open(UsageSource, Long userId, String step)` 返回 `ModelUsageContext.Handle`（`AutoCloseable`，`close()` 不抛受检异常）；`ModelUsageContext.resolve()` 返回 `ModelUsageContext.Resolved(UsageSource source, long userId, String requestId, String step)`（`step` 可为 null）
  - `RunContext.usageSource()` / `RunContext.usageSource(UsageSource)`
  - `RunObserver.usageSource()` 默认返回 `UsageSource.ONLINE`

- [ ] **Step 1: 写失败测试**

`src/test/java/com/kwiki/usage/ModelUsageContextTest.java`：

```java
package com.kwiki.usage;

import com.kwiki.rag.orchestration.AgenticLimits;
import com.kwiki.rag.orchestration.RunContext;
import com.kwiki.wiki.access.AuthorizationScope;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ModelUsageContextTest {

    static RunContext run(long userId) {
        return new RunContext("req-9", new AuthorizationScope(userId, false, Set.of(1L), Map.of()),
                kb -> 0L, AgenticLimits.defaults(), Map.of());
    }

    @Test
    void withoutScopeOrRunItIsBackgroundSystem() {
        assertThat(ModelUsageContext.resolve())
                .isEqualTo(new ModelUsageContext.Resolved(UsageSource.BACKGROUND, 0L, null, null));
    }

    @Test
    void boundRunIsOnlineWithItsUserAndRequestId() throws Exception {
        var run = run(42L);
        try (var bound = run.bind()) {
            assertThat(ModelUsageContext.resolve())
                    .isEqualTo(new ModelUsageContext.Resolved(UsageSource.ONLINE, 42L, "req-9", null));
        }
    }

    @Test
    void runMarkedAsEvalReportsEval() throws Exception {
        var run = run(7L);
        run.usageSource(UsageSource.EVAL);
        try (var bound = run.bind()) {
            assertThat(ModelUsageContext.resolve().source()).isEqualTo(UsageSource.EVAL);
        }
    }

    @Test
    void explicitScopeWinsAndFallsBackToRunUser() throws Exception {
        var run = run(42L);
        try (var bound = run.bind();
             var scope = ModelUsageContext.open(UsageSource.BACKGROUND, null, UsageSteps.INDEX_EMBEDDING)) {
            assertThat(ModelUsageContext.resolve()).isEqualTo(new ModelUsageContext.Resolved(
                    UsageSource.BACKGROUND, 42L, "req-9", UsageSteps.INDEX_EMBEDDING));
        }
    }

    @Test
    void nestedScopesRestoreThePreviousOne() {
        try (var outer = ModelUsageContext.open(UsageSource.EVAL, 5L)) {
            try (var inner = ModelUsageContext.open(UsageSource.BACKGROUND, 0L, UsageSteps.INDEX_EMBEDDING)) {
                assertThat(ModelUsageContext.resolve().source()).isEqualTo(UsageSource.BACKGROUND);
            }
            assertThat(ModelUsageContext.resolve())
                    .isEqualTo(new ModelUsageContext.Resolved(UsageSource.EVAL, 5L, null, null));
        }
        assertThat(ModelUsageContext.resolve().source()).isEqualTo(UsageSource.BACKGROUND);
    }
}
```

`src/test/java/com/kwiki/usage/UsageStepsTest.java`：

```java
package com.kwiki.usage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UsageStepsTest {

    @Test
    void labelsCoverPurposesEmbeddingStepsAndUnknownValues() {
        assertThat(UsageSteps.label("REWRITE")).isEqualTo("查询改写");
        assertThat(UsageSteps.label(UsageSteps.QUERY_EMBEDDING)).isEqualTo("查询向量");
        assertThat(UsageSteps.label(UsageSteps.INDEX_EMBEDDING)).isEqualTo("索引向量");
        assertThat(UsageSteps.label("SOMETHING_ELSE")).isEqualTo("SOMETHING_ELSE");
        assertThat(UsageSteps.label(null)).isEqualTo("unknown");
    }

    @Test
    void eventNormalisesMissingValues() {
        var event = new UsageEvent(null, null, null, null, null, -1, null, -5, -1, -3, true, null);
        assertThat(event.occurredAt()).isNotNull();
        assertThat(event.source()).isEqualTo(UsageSource.BACKGROUND);
        assertThat(event.step()).isEqualTo("unknown");
        assertThat(event.provider()).isEqualTo("unknown");
        assertThat(event.model()).isEqualTo("unknown");
        assertThat(event.userId()).isZero();
        assertThat(event.inputTokens()).isZero();
        assertThat(event.outputTokens()).isZero();
        assertThat(event.latencyMs()).isZero();
    }
}
```

在 `src/test/java/com/kwiki/rag/orchestration/WorkflowRunObserverTest.java` 末尾（类的最后一个 `}` 之前）追加：

```java
    @Test
    void observerUsageSourceIsAppliedToTheRunContext() {
        var harness = harness();
        harness.bm25.hits = List.of(hit("C1", "候选依据 C1"));
        harness.vector.hits = harness.bm25.hits;
        harness.answer.scripted.add("基于证据的回答 [P0]");
        List<com.kwiki.usage.UsageSource> seen = new CopyOnWriteArrayList<>();
        RunObserver evalObserver = new RunObserver() {
            @Override
            public void onEvent(Event event) {
                RunContext current = RunContext.current();
                if (current != null) seen.add(current.usageSource());
            }

            @Override
            public com.kwiki.usage.UsageSource usageSource() {
                return com.kwiki.usage.UsageSource.EVAL;
            }
        };

        harness.workflow.answerInSession(user, "什么是部署方式", List.of(), Set.of(), Set.of(),
                RouteInput.NONE, evalObserver).collectList().block(Duration.ofSeconds(10));

        assertThat(seen).isNotEmpty().containsOnly(com.kwiki.usage.UsageSource.EVAL);
    }
```

- [ ] **Step 2: 运行确认失败**

Run: `./mvnw -q -Dtest='ModelUsageContextTest,UsageStepsTest,WorkflowRunObserverTest' test`
Expected: 编译失败，`com.kwiki.usage` 不存在。

- [ ] **Step 3: 实现用量领域类型**

`src/main/java/com/kwiki/usage/UsageSource.java`：

```java
package com.kwiki.usage;

/** 模型调用来源：用户请求、RAG 评测、索引与图谱构建等后台任务。 */
public enum UsageSource {
    ONLINE, EVAL, BACKGROUND
}
```

`src/main/java/com/kwiki/usage/UsageSteps.java`：

```java
package com.kwiki.usage;

import com.kwiki.infrastructure.ai.registry.ModelPurpose;

/** 用量步骤：对话模型用 {@link ModelPurpose} 枚举名，embedding 用本类两个常量。 */
public final class UsageSteps {

    public static final String QUERY_EMBEDDING = "QUERY_EMBEDDING";
    public static final String INDEX_EMBEDDING = "INDEX_EMBEDDING";

    private UsageSteps() {
    }

    /** 步骤中文名；未知步骤原样返回，null 返回 unknown。 */
    public static String label(String step) {
        if (step == null) return "unknown";
        if (QUERY_EMBEDDING.equals(step)) return "查询向量";
        if (INDEX_EMBEDDING.equals(step)) return "索引向量";
        try {
            return ModelPurpose.valueOf(step).label();
        } catch (IllegalArgumentException e) {
            return step;
        }
    }
}
```

`src/main/java/com/kwiki/usage/UsageEvent.java`：

```java
package com.kwiki.usage;

import java.time.Instant;

/** 一次模型调用的用量事件；缺失值归一化，负数归零。 */
public record UsageEvent(
        Instant occurredAt,
        UsageSource source,
        String step,
        String provider,
        String model,
        long userId,
        String requestId,
        int inputTokens,
        int outputTokens,
        long latencyMs,
        boolean success,
        String errorType) {

    public UsageEvent {
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
        source = source == null ? UsageSource.BACKGROUND : source;
        step = blankToUnknown(step);
        provider = blankToUnknown(provider);
        model = blankToUnknown(model);
        userId = Math.max(0, userId);
        inputTokens = Math.max(0, inputTokens);
        outputTokens = Math.max(0, outputTokens);
        latencyMs = Math.max(0, latencyMs);
    }

    private static String blankToUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
```

`src/main/java/com/kwiki/usage/ModelUsageContext.java`：

```java
package com.kwiki.usage;

import com.kwiki.rag.orchestration.RunContext;

/**
 * 用量归属判定：显式作用域（评测、索引任务）优先；其次是当前线程绑定的请求上下文；
 * 都没有时归为后台任务（用户 0）。作用域可嵌套，关闭时恢复外层作用域。
 */
public final class ModelUsageContext {

    /** 判定结果；step 为 null 表示由调用方使用默认步骤。 */
    public record Resolved(UsageSource source, long userId, String requestId, String step) {
    }

    /** 作用域句柄，配合 try-with-resources 使用。 */
    public interface Handle extends AutoCloseable {
        @Override
        void close();
    }

    private record Scope(UsageSource source, Long userId, String step) {
    }

    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private ModelUsageContext() {
    }

    public static Handle open(UsageSource source, Long userId) {
        return open(source, userId, null);
    }

    public static Handle open(UsageSource source, Long userId, String step) {
        Scope previous = CURRENT.get();
        CURRENT.set(new Scope(source, userId, step));
        return () -> {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        };
    }

    public static Resolved resolve() {
        Scope scope = CURRENT.get();
        RunContext run = RunContext.current();
        String requestId = run == null ? null : run.requestId;
        long runUser = run == null ? 0L : run.scope.userId();
        if (scope != null) {
            return new Resolved(scope.source(), scope.userId() != null ? scope.userId() : runUser,
                    requestId, scope.step());
        }
        if (run != null) return new Resolved(run.usageSource(), runUser, requestId, null);
        return new Resolved(UsageSource.BACKGROUND, 0L, null, null);
    }
}
```

- [ ] **Step 4: RunContext、RunObserver、工作流与评测接入**

`RunContext.java`：在 import 区加入 `import com.kwiki.usage.UsageSource;`；在 `private volatile java.util.function.Consumer<String> thinking = text -> {};` 之前加入：

```java
    private volatile UsageSource usageSource = UsageSource.ONLINE;

    /** 本次运行的用量来源（评测运行为 EVAL）。 */
    public UsageSource usageSource() {
        return usageSource;
    }

    public void usageSource(UsageSource value) {
        usageSource = value == null ? UsageSource.ONLINE : value;
    }
```

`RunObserver.java`：在 `void onEvent(Event event);` 之后加入：

```java
    /** 观测者所代表的用量来源；评测的观测者返回 EVAL，工作流据此标记本次运行。 */
    default com.kwiki.usage.UsageSource usageSource() {
        return com.kwiki.usage.UsageSource.ONLINE;
    }
```

`LangGraphAgenticWorkflow.java`：在 `run.observer(observer);` 这一行之后紧跟一行：

```java
                                                                run.usageSource(observer.usageSource());
```

`WorkflowProbe.java`：把 `RouteInput.NONE, observed::add).collectList().block(timeout);` 中的 `observed::add` 换成局部变量 `evalObserver`，并在该语句之前定义：

```java
        RunObserver evalObserver = new RunObserver() {
            @Override
            public void onEvent(Event event) {
                observed.add(event);
            }

            @Override
            public com.kwiki.usage.UsageSource usageSource() {
                return com.kwiki.usage.UsageSource.EVAL;
            }
        };
```

`EvalRunService.java` 的 `evaluate(...)` 中，把

```java
            BaselineRetrievalProbe.Result base = baseline.probe(evalCase.query(), filter);
            WorkflowProbe.Result run = workflow.run(user, kbId, evalCase.query(), CASE_TIMEOUT);
```

替换为（评测线程上的基线检索查询向量也计入评测，用户为触发评测的管理员）：

```java
            BaselineRetrievalProbe.Result base;
            WorkflowProbe.Result run;
            try (var usage = com.kwiki.usage.ModelUsageContext.open(
                    com.kwiki.usage.UsageSource.EVAL, user.id())) {
                base = baseline.probe(evalCase.query(), filter);
                run = workflow.run(user, kbId, evalCase.query(), CASE_TIMEOUT);
            }
```

- [ ] **Step 5: 运行确认通过**

Run: `./mvnw -q -Dtest='ModelUsageContextTest,UsageStepsTest,WorkflowRunObserverTest,WorkflowProbeTest,EvalRunServiceTest,RunContextObserverTest' test`
Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add src/main/java/com/kwiki/usage src/main/java/com/kwiki/rag/orchestration/RunContext.java src/main/java/com/kwiki/rag/orchestration/RunObserver.java src/main/java/com/kwiki/infrastructure/orchestration/LangGraphAgenticWorkflow.java src/main/java/com/kwiki/eval/WorkflowProbe.java src/main/java/com/kwiki/eval/EvalRunService.java src/test/java/com/kwiki/usage src/test/java/com/kwiki/rag/orchestration/WorkflowRunObserverTest.java
git commit -m "feat(usage): 模型用量来源判定——显式作用域优先、其次请求上下文、否则后台；评测运行与基线检索标记为评测来源"
```

---

### Task 2: 用量存储（迁移、队列、落库、清理）

**Files:**
- Create: `src/main/resources/db/migration/V43__model_usage.sql`
- Create: `src/main/java/com/kwiki/usage/ModelUsageRecorder.java`、`MinuteAggregate.java`、`ModelUsageRepository.java`、`ModelUsageFlusher.java`、`ModelUsageRetentionJob.java`
- Test: `src/test/java/com/kwiki/usage/ModelUsageRecorderTest.java`、`ModelUsageFlusherTest.java`、`ModelUsageRepositoryTest.java`、`ModelUsageRetentionJobTest.java`、`ModelUsageRepositoryContractTest.java`

**Interfaces:**
- Consumes: `UsageEvent`、`UsageSource`（Task 1）；`com.kwiki.infrastructure.redis.KwikiDistributedLocks.tryRun(String, Duration, Runnable)`
- Produces:
  - `ModelUsageRecorder(ObjectProvider<MeterRegistry>)`：`void record(UsageEvent)`、`List<UsageEvent> drain()`、`int CAPACITY = 50_000`
  - `record MinuteAggregate(Instant bucketStart, UsageSource source, String step, String provider, String model, long userId, long calls, long failures, long inputTokens, long outputTokens, long latencyMs)`
  - `ModelUsageRepository(ObjectProvider<JdbcOperations>)`：`boolean available()`、`void upsertMinutes(List<MinuteAggregate>)`、`void insertCalls(List<UsageEvent>)`、`int deleteMinutesBefore(Instant)`、`int deleteCallsBefore(Instant)`（各自分批删除并返回总行数）；Task 5 追加查询方法
  - `ModelUsageFlusher(ModelUsageRecorder, ModelUsageRepository)`：`void flush()`、`static List<MinuteAggregate> aggregate(List<UsageEvent>)`
  - `ModelUsageRetentionJob(ModelUsageRepository, KwikiDistributedLocks, int retentionDays, int callRetentionDays, Clock)`：`void daily()`、`void runOnce()`

- [ ] **Step 1: 核对迁移版本**

先用 `db-migration` 技能确认迁移机制与下一个版本号（当前最大为 `V42__model_extra_parameters.sql`）。若期间他人新增了迁移，把下文的 `V43` 改为实际的下一个版本，文件名与提交信息同步修改。

- [ ] **Step 2: 写迁移**

`src/main/resources/db/migration/V43__model_usage.sql`：

```sql
-- 模型 token 用量：分钟级聚合（长期保留，供趋势图）与逐次调用明细（短期保留，供排查）。
-- 时间均为 UTC；user_id = 0 表示后台任务（系统）。
CREATE TABLE model_usage_minute (
    bucket_start   DATETIME     NOT NULL,
    source         VARCHAR(16)  NOT NULL,
    step           VARCHAR(32)  NOT NULL,
    provider       VARCHAR(128) NOT NULL,
    model          VARCHAR(128) NOT NULL,
    user_id        BIGINT       NOT NULL DEFAULT 0,
    calls          BIGINT       NOT NULL DEFAULT 0,
    failures       BIGINT       NOT NULL DEFAULT 0,
    input_tokens   BIGINT       NOT NULL DEFAULT 0,
    output_tokens  BIGINT       NOT NULL DEFAULT 0,
    latency_ms     BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (bucket_start, source, step, provider, model, user_id),
    KEY idx_usage_minute_step (step, bucket_start),
    KEY idx_usage_minute_model (provider, model, bucket_start),
    KEY idx_usage_minute_user (user_id, bucket_start)
);

CREATE TABLE model_usage_call (
    id             BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    occurred_at    DATETIME(3)  NOT NULL,
    source         VARCHAR(16)  NOT NULL,
    step           VARCHAR(32)  NOT NULL,
    provider       VARCHAR(128) NOT NULL,
    model          VARCHAR(128) NOT NULL,
    user_id        BIGINT       NOT NULL DEFAULT 0,
    request_id     VARCHAR(64)  NULL,
    input_tokens   INT          NOT NULL DEFAULT 0,
    output_tokens  INT          NOT NULL DEFAULT 0,
    latency_ms     INT          NOT NULL DEFAULT 0,
    status         VARCHAR(16)  NOT NULL,
    error_type     VARCHAR(128) NULL,
    KEY idx_usage_call_time (occurred_at),
    KEY idx_usage_call_request (request_id)
);
```

Run: `node tools/validate-migrations.mjs`
Expected: `validated 43 migrations in …`（数字随实际版本变化）。

- [ ] **Step 3: 写失败测试**

`src/test/java/com/kwiki/usage/ModelUsageRecorderTest.java`：

```java
package com.kwiki.usage;

import com.kwiki.testutil.StandardTestProperties;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ModelUsageRecorderTest {

    static UsageEvent event(int input) {
        return new UsageEvent(Instant.parse("2026-10-21T01:02:03Z"), UsageSource.ONLINE, "ANSWER", "p", "m",
                1, "r", input, 1, 10, true, null);
    }

    @Test
    void recordsAndDrainsInOrder() {
        var recorder = new ModelUsageRecorder(StandardTestProperties.nullProvider());
        recorder.record(event(1));
        recorder.record(event(2));
        assertThat(recorder.drain()).extracting(UsageEvent::inputTokens).containsExactly(1, 2);
        assertThat(recorder.drain()).isEmpty();
    }

    @Test
    void fullQueueDropsInsteadOfBlocking() {
        var recorder = new ModelUsageRecorder(StandardTestProperties.nullProvider());
        for (int i = 0; i < ModelUsageRecorder.CAPACITY + 10; i++) recorder.record(event(1));
        assertThat(recorder.drain()).hasSize(ModelUsageRecorder.CAPACITY);
        assertThat(recorder.dropped()).isEqualTo(10);
    }

    @Test
    void nullEventsAreIgnored() {
        var recorder = new ModelUsageRecorder(StandardTestProperties.nullProvider());
        recorder.record(null);
        assertThat(recorder.drain()).isEmpty();
    }
}
```

`src/test/java/com/kwiki/usage/ModelUsageFlusherTest.java`：

```java
package com.kwiki.usage;

import com.kwiki.testutil.StandardTestProperties;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelUsageFlusherTest {

    static UsageEvent event(String at, String step, long user, int in, int out, long latency, boolean ok) {
        return new UsageEvent(Instant.parse(at), UsageSource.ONLINE, step, "dashscope", "qwen-plus", user, "r",
                in, out, latency, ok, ok ? null : "HttpException");
    }

    @Test
    void sameMinuteAndCombinationAreSummed() {
        var aggregates = ModelUsageFlusher.aggregate(List.of(
                event("2026-10-21T01:02:03Z", "ANSWER", 1, 100, 20, 300, true),
                event("2026-10-21T01:02:59.900Z", "ANSWER", 1, 50, 5, 100, false),
                event("2026-10-21T01:03:00Z", "ANSWER", 1, 7, 0, 1, true),
                event("2026-10-21T01:02:10Z", "REWRITE", 1, 9, 9, 9, true),
                event("2026-10-21T01:02:10Z", "ANSWER", 2, 1, 1, 1, true)));

        assertThat(aggregates).hasSize(4);
        MinuteAggregate first = aggregates.stream()
                .filter(a -> a.step().equals("ANSWER") && a.userId() == 1
                        && a.bucketStart().equals(Instant.parse("2026-10-21T01:02:00Z")))
                .findFirst().orElseThrow();
        assertThat(first.calls()).isEqualTo(2);
        assertThat(first.failures()).isEqualTo(1);
        assertThat(first.inputTokens()).isEqualTo(150);
        assertThat(first.outputTokens()).isEqualTo(25);
        assertThat(first.latencyMs()).isEqualTo(400);
    }

    @Test
    void flushWritesAggregatesAndCalls() {
        var recorder = new ModelUsageRecorder(StandardTestProperties.nullProvider());
        var repository = mock(ModelUsageRepository.class);
        when(repository.available()).thenReturn(true);
        recorder.record(event("2026-10-21T01:02:03Z", "ANSWER", 1, 100, 20, 300, true));

        new ModelUsageFlusher(recorder, repository).flush();

        verify(repository).upsertMinutes(anyList());
        verify(repository).insertCalls(anyList());
        assertThat(recorder.drain()).isEmpty();
    }

    @Test
    void emptyQueueOrMissingDatabaseWritesNothing() {
        var recorder = new ModelUsageRecorder(StandardTestProperties.nullProvider());
        var repository = mock(ModelUsageRepository.class);
        when(repository.available()).thenReturn(false);
        recorder.record(event("2026-10-21T01:02:03Z", "ANSWER", 1, 1, 1, 1, true));

        new ModelUsageFlusher(recorder, repository).flush();

        verify(repository, never()).upsertMinutes(any());
        assertThat(recorder.drain()).isEmpty(); // 无数据库时丢弃，避免无限堆积
    }

    @Test
    void databaseFailureIsSwallowed() {
        var recorder = new ModelUsageRecorder(StandardTestProperties.nullProvider());
        var repository = mock(ModelUsageRepository.class);
        when(repository.available()).thenReturn(true);
        doThrow(new IllegalStateException("db down")).when(repository).upsertMinutes(anyList());
        recorder.record(event("2026-10-21T01:02:03Z", "ANSWER", 1, 1, 1, 1, true));

        new ModelUsageFlusher(recorder, repository).flush();

        verify(repository).upsertMinutes(anyList());
    }
}
```

`src/test/java/com/kwiki/usage/ModelUsageRepositoryTest.java`：

```java
package com.kwiki.usage;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcOperations;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelUsageRepositoryTest {
    final JdbcOperations jdbc = mock(JdbcOperations.class);

    @SuppressWarnings("unchecked")
    ModelUsageRepository repository() {
        ObjectProvider<JdbcOperations> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(jdbc);
        return new ModelUsageRepository(provider);
    }

    @Test
    @SuppressWarnings("unchecked")
    void upsertAccumulatesOnDuplicateKeyWithUtcBucket() {
        var aggregate = new MinuteAggregate(Instant.parse("2026-10-21T01:02:00Z"), UsageSource.EVAL, "ANSWER",
                "dashscope", "qwen-plus", 3, 2, 1, 150, 25, 400);

        repository().upsertMinutes(List.of(aggregate));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List<Object[]>> args = ArgumentCaptor.forClass(List.class);
        verify(jdbc).batchUpdate(sql.capture(), args.capture());
        assertThat(sql.getValue()).contains("INSERT INTO model_usage_minute")
                .contains("ON DUPLICATE KEY UPDATE").contains("calls = calls + VALUES(calls)")
                .contains("input_tokens = input_tokens + VALUES(input_tokens)");
        Object[] row = args.getValue().getFirst();
        assertThat(row[0]).isEqualTo(LocalDateTime.parse("2026-10-21T01:02:00"));
        assertThat(row).containsSequence("EVAL", "ANSWER", "dashscope", "qwen-plus", 3L, 2L, 1L, 150L, 25L, 400L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void callsAreInsertedWithStatus() {
        var event = new UsageEvent(Instant.parse("2026-10-21T01:02:03.456Z"), UsageSource.ONLINE, "REWRITE",
                "p", "m", 9, "req-1", 10, 2, 33, false, "AuthenticationException");

        repository().insertCalls(List.of(event));

        ArgumentCaptor<List<Object[]>> args = ArgumentCaptor.forClass(List.class);
        verify(jdbc).batchUpdate(anyString(), args.capture());
        Object[] row = args.getValue().getFirst();
        assertThat(row[0]).isEqualTo(LocalDateTime.parse("2026-10-21T01:02:03.456"));
        assertThat(row).contains("FAILED", "AuthenticationException", "req-1");
    }

    @Test
    void deletesRunInBatchesUntilNothingIsLeft() {
        when(jdbc.update(anyString(), eq(LocalDateTime.parse("2026-04-24T00:00:00"))))
                .thenReturn(ModelUsageRepository.DELETE_BATCH, 123, 0);

        int deleted = repository().deleteMinutesBefore(Instant.parse("2026-04-24T00:00:00Z"));

        assertThat(deleted).isEqualTo(ModelUsageRepository.DELETE_BATCH + 123);
    }

    @Test
    @SuppressWarnings("unchecked")
    void missingDatabaseIsANoOp() {
        ObjectProvider<JdbcOperations> provider = mock(ObjectProvider.class);
        var repository = new ModelUsageRepository(provider);
        assertThat(repository.available()).isFalse();
        repository.upsertMinutes(List.of());
        assertThat(repository.deleteCallsBefore(Instant.now())).isZero();
    }
}
```

`src/test/java/com/kwiki/usage/ModelUsageRetentionJobTest.java`：

```java
package com.kwiki.usage;

import com.kwiki.infrastructure.redis.KwikiDistributedLocks;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ModelUsageRetentionJobTest {

    @Test
    void deletesOlderThanConfiguredRetention() {
        var repository = mock(ModelUsageRepository.class);
        var clock = Clock.fixed(Instant.parse("2026-10-21T00:00:00Z"), ZoneOffset.UTC);
        var job = new ModelUsageRetentionJob(repository, mock(KwikiDistributedLocks.class), 180, 7, clock);

        job.runOnce();

        verify(repository).deleteMinutesBefore(Instant.parse("2026-04-24T00:00:00Z"));
        verify(repository).deleteCallsBefore(Instant.parse("2026-10-14T00:00:00Z"));
    }
}
```

`src/test/java/com/kwiki/usage/ModelUsageRepositoryContractTest.java`（仅在提供真实 MySQL 时运行）：

```java
package com.kwiki.usage;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 针对运维方提供的 MySQL（KWIKI_IT_MYSQL_URL/USERNAME/PASSWORD）验证 upsert 累加与清理。 */
@EnabledIfEnvironmentVariable(named = "KWIKI_IT_MYSQL_URL", matches = ".+")
class ModelUsageRepositoryContractTest {

    static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        String url = System.getenv("KWIKI_IT_MYSQL_URL");
        String user = System.getenv().getOrDefault("KWIKI_IT_MYSQL_USERNAME", "");
        String password = System.getenv().getOrDefault("KWIKI_IT_MYSQL_PASSWORD", "");
        Flyway.configure().dataSource(url, user, password).locations("classpath:db/migration")
                .cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(url, user, password).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
    }

    @SuppressWarnings("unchecked")
    static ModelUsageRepository repository() {
        ObjectProvider<JdbcOperations> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(jdbc);
        return new ModelUsageRepository(provider);
    }

    @Test
    void upsertAccumulatesAndRetentionDeletes() {
        var bucket = Instant.parse("2026-10-21T01:02:00Z");
        var a = new MinuteAggregate(bucket, UsageSource.ONLINE, "ANSWER", "p", "m", 1, 1, 0, 10, 2, 5);
        repository().upsertMinutes(List.of(a));
        repository().upsertMinutes(List.of(a));

        assertThat(jdbc.queryForObject("SELECT input_tokens FROM model_usage_minute", Long.class)).isEqualTo(20L);
        assertThat(repository().deleteMinutesBefore(Instant.parse("2026-10-22T00:00:00Z"))).isEqualTo(1);
    }
}
```

- [ ] **Step 4: 运行确认失败**

Run: `./mvnw -q -Dtest='ModelUsageRecorderTest,ModelUsageFlusherTest,ModelUsageRepositoryTest,ModelUsageRetentionJobTest' test`
Expected: 编译失败。

- [ ] **Step 5: 实现**

`src/main/java/com/kwiki/usage/ModelUsageRecorder.java`：

```java
package com.kwiki.usage;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/** 用量事件的有界内存队列：入队绝不阻塞模型调用，满时丢弃并计数。 */
@Component
public class ModelUsageRecorder {

    public static final int CAPACITY = 50_000;

    private final ArrayBlockingQueue<UsageEvent> queue = new ArrayBlockingQueue<>(CAPACITY);
    private final AtomicLong dropped = new AtomicLong();

    public ModelUsageRecorder(ObjectProvider<MeterRegistry> metrics) {
        MeterRegistry registry = metrics.getIfAvailable();
        if (registry != null) {
            registry.gauge("kwiki_model_usage_dropped_total", dropped);
            registry.gauge("kwiki_model_usage_queue_size", queue, ArrayBlockingQueue::size);
        }
    }

    public void record(UsageEvent event) {
        if (event == null) return;
        if (!queue.offer(event)) dropped.incrementAndGet();
    }

    public List<UsageEvent> drain() {
        List<UsageEvent> events = new ArrayList<>(queue.size());
        queue.drainTo(events);
        return events;
    }

    long dropped() {
        return dropped.get();
    }
}
```

`src/main/java/com/kwiki/usage/MinuteAggregate.java`：

```java
package com.kwiki.usage;

import java.time.Instant;

/** 分钟级聚合行（bucketStart 为截断到分钟的 UTC 时刻）。 */
public record MinuteAggregate(
        Instant bucketStart,
        UsageSource source,
        String step,
        String provider,
        String model,
        long userId,
        long calls,
        long failures,
        long inputTokens,
        long outputTokens,
        long latencyMs) {
}
```

`src/main/java/com/kwiki/usage/ModelUsageRepository.java`：

```java
package com.kwiki.usage;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/** 用量表的 JDBC 访问；未装配数据源时所有操作为 no-op。时间一律按 UTC 绑定。 */
@Repository
public class ModelUsageRepository {

    static final int DELETE_BATCH = 10_000;

    private static final String UPSERT_MINUTE = """
            INSERT INTO model_usage_minute
              (bucket_start, source, step, provider, model, user_id,
               calls, failures, input_tokens, output_tokens, latency_ms)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
              calls = calls + VALUES(calls),
              failures = failures + VALUES(failures),
              input_tokens = input_tokens + VALUES(input_tokens),
              output_tokens = output_tokens + VALUES(output_tokens),
              latency_ms = latency_ms + VALUES(latency_ms)
            """;

    private static final String INSERT_CALL = """
            INSERT INTO model_usage_call
              (occurred_at, source, step, provider, model, user_id, request_id,
               input_tokens, output_tokens, latency_ms, status, error_type)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    protected final JdbcOperations jdbc;

    public ModelUsageRepository(ObjectProvider<JdbcOperations> jdbc) {
        this.jdbc = jdbc.getIfAvailable();
    }

    public boolean available() {
        return jdbc != null;
    }

    public void upsertMinutes(List<MinuteAggregate> aggregates) {
        if (jdbc == null || aggregates.isEmpty()) return;
        jdbc.batchUpdate(UPSERT_MINUTE, aggregates.stream().map(a -> new Object[]{
                utc(a.bucketStart()), a.source().name(), a.step(), a.provider(), a.model(), a.userId(),
                a.calls(), a.failures(), a.inputTokens(), a.outputTokens(), a.latencyMs()}).toList());
    }

    public void insertCalls(List<UsageEvent> events) {
        if (jdbc == null || events.isEmpty()) return;
        jdbc.batchUpdate(INSERT_CALL, events.stream().map(e -> new Object[]{
                utc(e.occurredAt()), e.source().name(), e.step(), e.provider(), e.model(), e.userId(),
                e.requestId(), e.inputTokens(), e.outputTokens(), (int) Math.min(e.latencyMs(), Integer.MAX_VALUE),
                e.success() ? "SUCCESS" : "FAILED", truncate(e.errorType(), 128)}).toList());
    }

    public int deleteMinutesBefore(Instant cutoff) {
        return deleteInBatches("DELETE FROM model_usage_minute WHERE bucket_start < ? LIMIT " + DELETE_BATCH, cutoff);
    }

    public int deleteCallsBefore(Instant cutoff) {
        return deleteInBatches("DELETE FROM model_usage_call WHERE occurred_at < ? LIMIT " + DELETE_BATCH, cutoff);
    }

    private int deleteInBatches(String sql, Instant cutoff) {
        if (jdbc == null) return 0;
        int total = 0;
        int deleted;
        do {
            deleted = jdbc.update(sql, utc(cutoff));
            total += deleted;
        } while (deleted >= DELETE_BATCH);
        return total;
    }

    static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
```

`src/main/java/com/kwiki/usage/ModelUsageFlusher.java`：

```java
package com.kwiki.usage;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 定时把内存队列中的用量事件按「分钟 × 来源 × 步骤 × provider × 模型 × 用户」预聚合后写库，
 * 同时写入逐次调用明细。写库失败只记日志并丢弃本批（不重试，避免重复累加）；应用关闭时再 flush 一次。
 */
@Component
@EnableScheduling
public class ModelUsageFlusher {

    private static final Logger log = LoggerFactory.getLogger(ModelUsageFlusher.class);

    private final ModelUsageRecorder recorder;
    private final ModelUsageRepository repository;

    public ModelUsageFlusher(ModelUsageRecorder recorder, ModelUsageRepository repository) {
        this.recorder = recorder;
        this.repository = repository;
    }

    @Scheduled(fixedDelayString = "${kwiki.model-usage.flush-interval-ms:10000}")
    public synchronized void flush() {
        List<UsageEvent> events = recorder.drain();
        if (events.isEmpty() || !repository.available()) return;
        try {
            repository.upsertMinutes(aggregate(events));
            repository.insertCalls(events);
        } catch (RuntimeException e) {
            log.warn("model usage flush failed events={} reason={}", events.size(), e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        flush();
    }

    private record Key(Instant bucket, UsageSource source, String step, String provider, String model, long userId) {
    }

    static List<MinuteAggregate> aggregate(List<UsageEvent> events) {
        Map<Key, long[]> sums = new LinkedHashMap<>();
        for (UsageEvent e : events) {
            Key key = new Key(e.occurredAt().truncatedTo(ChronoUnit.MINUTES), e.source(), e.step(),
                    e.provider(), e.model(), e.userId());
            long[] sum = sums.computeIfAbsent(key, ignored -> new long[5]);
            sum[0] += 1;
            sum[1] += e.success() ? 0 : 1;
            sum[2] += e.inputTokens();
            sum[3] += e.outputTokens();
            sum[4] += e.latencyMs();
        }
        List<MinuteAggregate> aggregates = new ArrayList<>(sums.size());
        sums.forEach((k, s) -> aggregates.add(new MinuteAggregate(k.bucket(), k.source(), k.step(), k.provider(),
                k.model(), k.userId(), s[0], s[1], s[2], s[3], s[4])));
        return aggregates;
    }
}
```

`src/main/java/com/kwiki/usage/ModelUsageRetentionJob.java`：

```java
package com.kwiki.usage;

import com.kwiki.infrastructure.redis.KwikiDistributedLocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** 每日 03:30（Asia/Shanghai）在分布式锁下清理过期用量：聚合行保留 retention-days，明细保留 call-retention-days。 */
@Component
@EnableScheduling
public class ModelUsageRetentionJob {

    static final String LOCK_PURPOSE = "model-usage-retention";
    private static final Logger log = LoggerFactory.getLogger(ModelUsageRetentionJob.class);

    private final ModelUsageRepository repository;
    private final KwikiDistributedLocks locks;
    private final int retentionDays;
    private final int callRetentionDays;
    private final Clock clock;

    @Autowired
    public ModelUsageRetentionJob(ModelUsageRepository repository, KwikiDistributedLocks locks,
                                  @Value("${kwiki.model-usage.retention-days:180}") int retentionDays,
                                  @Value("${kwiki.model-usage.call-retention-days:7}") int callRetentionDays) {
        this(repository, locks, retentionDays, callRetentionDays, Clock.systemUTC());
    }

    ModelUsageRetentionJob(ModelUsageRepository repository, KwikiDistributedLocks locks,
                           int retentionDays, int callRetentionDays, Clock clock) {
        this.repository = repository;
        this.locks = locks;
        this.retentionDays = Math.max(1, retentionDays);
        this.callRetentionDays = Math.max(1, callRetentionDays);
        this.clock = clock;
    }

    @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Shanghai")
    public void daily() {
        boolean ran = locks.tryRun(LOCK_PURPOSE, Duration.ZERO, () -> {
            try {
                runOnce();
            } catch (RuntimeException e) {
                log.error("model usage retention aborted: {}", e.getMessage(), e);
            }
        });
        if (!ran) log.info("model usage retention skipped: lock unavailable or held by another instance");
    }

    void runOnce() {
        Instant now = clock.instant();
        int minutes = repository.deleteMinutesBefore(now.minus(Duration.ofDays(retentionDays)));
        int calls = repository.deleteCallsBefore(now.minus(Duration.ofDays(callRetentionDays)));
        log.info("model usage retention deleted minuteRows={} callRows={}", minutes, calls);
    }
}
```

- [ ] **Step 6: 运行确认通过**

Run: `./mvnw -q -Dtest='ModelUsageRecorderTest,ModelUsageFlusherTest,ModelUsageRepositoryTest,ModelUsageRetentionJobTest,ModelUsageRepositoryContractTest,KwikiApplicationTest' test`
Expected: PASS（Contract 测试在无 `KWIKI_IT_MYSQL_URL` 时跳过）。

- [ ] **Step 7: 提交**

```bash
git add src/main/resources/db/migration/V43__model_usage.sql src/main/java/com/kwiki/usage src/test/java/com/kwiki/usage
git commit -m "feat(usage): 用量存储——分钟聚合表与 7 天明细表、有界内存队列、每 10 秒预聚合落库、每日分批清理过期数据"
```

---

### Task 3: 对话模型用量采集

**Files:**
- Modify: `src/main/java/com/kwiki/infrastructure/ai/registry/ClientSpec.java`
- Modify: `src/main/java/com/kwiki/infrastructure/ai/registry/DefaultModelRegistry.java`（构造 `ClientSpec spec = new ClientSpec(...)` 处）
- Create: `src/main/java/com/kwiki/infrastructure/ai/registry/ModelUsageListener.java`
- Modify: `src/main/java/com/kwiki/infrastructure/ai/registry/OpenAiModelClientFactory.java`
- Test: `src/test/java/com/kwiki/infrastructure/ai/registry/ModelUsageListenerTest.java`、`ClientSpecTest.java`

**Interfaces:**
- Consumes: `ModelUsageContext.resolve()`、`UsageEvent`（Task 1）；`ModelUsageRecorder.record`（Task 2）
- Produces:
  - `ClientSpec` 新增组件 `String providerName, String step`（位于末尾），保留原 7 参、8 参构造器（两者取 `""`）；`fingerprint()` 纳入二者
  - `ModelUsageListener(ModelUsageRecorder recorder, String step, String providerName, String modelName)` 实现 `ChatModelListener`
  - `OpenAiModelClientFactory` 新增 `@Autowired` 4 参构造器（最后一个参数 `ObjectProvider<ModelUsageRecorder>`），原 3 参构造器保留（不挂用量监听器）

- [ ] **Step 1: 写失败测试**

`src/test/java/com/kwiki/infrastructure/ai/registry/ClientSpecTest.java`：

```java
package com.kwiki.infrastructure.ai.registry;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ClientSpecTest {

    @Test
    void stepAndProviderSeparateClientsOfTheSameModel() {
        var rewrite = new ClientSpec("https://x/v1", "k", Duration.ofSeconds(5), "m", 0.0, null, null, Map.of(),
                "dashscope", "REWRITE");
        var router = new ClientSpec("https://x/v1", "k", Duration.ofSeconds(5), "m", 0.0, null, null, Map.of(),
                "dashscope", "ROUTER");
        assertThat(rewrite.fingerprint()).isNotEqualTo(router.fingerprint());
    }

    @Test
    void legacyConstructorsDefaultToEmptyStepAndProvider() {
        var legacy = new ClientSpec("https://x/v1", "k", Duration.ofSeconds(5), "m", 0.0, null, null);
        assertThat(legacy.step()).isEmpty();
        assertThat(legacy.providerName()).isEmpty();
    }
}
```

`src/test/java/com/kwiki/infrastructure/ai/registry/ModelUsageListenerTest.java`：

```java
package com.kwiki.infrastructure.ai.registry;

import com.kwiki.rag.orchestration.AgenticLimits;
import com.kwiki.rag.orchestration.RunContext;
import com.kwiki.testutil.StandardTestProperties;
import com.kwiki.usage.ModelUsageContext;
import com.kwiki.usage.ModelUsageRecorder;
import com.kwiki.usage.UsageEvent;
import com.kwiki.usage.UsageSource;
import com.kwiki.wiki.access.AuthorizationScope;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ModelUsageListenerTest {
    final ModelUsageRecorder recorder = new ModelUsageRecorder(StandardTestProperties.nullProvider());
    final ModelUsageListener listener = new ModelUsageListener(recorder, "REWRITE", "dashscope", "qwen-plus");
    final ChatRequest request = ChatRequest.builder().messages(UserMessage.from("问题")).build();

    static RunContext run(long user) {
        return new RunContext("req-1", new AuthorizationScope(user, false, Set.of(1L), Map.of()),
                kb -> 0L, AgenticLimits.defaults(), Map.of());
    }

    @Test
    void successfulCallRecordsTokensSourceUserAndRequest() throws Exception {
        Map<Object, Object> attributes = new HashMap<>();
        try (var bound = run(42L).bind()) {
            listener.onRequest(new ChatModelRequestContext(request, ModelProvider.OPEN_AI, attributes));
        }
        ChatResponse response = ChatResponse.builder().aiMessage(AiMessage.from("答"))
                .tokenUsage(new TokenUsage(120, 30)).build();
        listener.onResponse(new ChatModelResponseContext(response, request, ModelProvider.OPEN_AI, attributes));

        UsageEvent event = recorder.drain().getFirst();
        assertThat(event.source()).isEqualTo(UsageSource.ONLINE);
        assertThat(event.step()).isEqualTo("REWRITE");
        assertThat(event.provider()).isEqualTo("dashscope");
        assertThat(event.model()).isEqualTo("qwen-plus");
        assertThat(event.userId()).isEqualTo(42L);
        assertThat(event.requestId()).isEqualTo("req-1");
        assertThat(event.inputTokens()).isEqualTo(120);
        assertThat(event.outputTokens()).isEqualTo(30);
        assertThat(event.success()).isTrue();
    }

    @Test
    void missingTokenUsageStillCountsTheCall() {
        Map<Object, Object> attributes = new HashMap<>();
        listener.onRequest(new ChatModelRequestContext(request, ModelProvider.OPEN_AI, attributes));
        ChatResponse response = ChatResponse.builder().aiMessage(AiMessage.from("答")).build();
        listener.onResponse(new ChatModelResponseContext(response, request, ModelProvider.OPEN_AI, attributes));

        UsageEvent event = recorder.drain().getFirst();
        assertThat(event.source()).isEqualTo(UsageSource.BACKGROUND);
        assertThat(event.inputTokens()).isZero();
        assertThat(event.success()).isTrue();
    }

    @Test
    void failureIsRecordedWithErrorType() {
        Map<Object, Object> attributes = new HashMap<>();
        try (var scope = ModelUsageContext.open(UsageSource.EVAL, 7L)) {
            listener.onRequest(new ChatModelRequestContext(request, ModelProvider.OPEN_AI, attributes));
        }
        listener.onError(new ChatModelErrorContext(new HttpException(400, "Arrearage"), request,
                ModelProvider.OPEN_AI, attributes));

        UsageEvent event = recorder.drain().getFirst();
        assertThat(event.source()).isEqualTo(UsageSource.EVAL);
        assertThat(event.userId()).isEqualTo(7L);
        assertThat(event.success()).isFalse();
        assertThat(event.errorType()).isEqualTo("HttpException");
    }

    @Test
    void responseWithoutRequestContextFallsBackToCurrentResolution() {
        ChatResponse response = ChatResponse.builder().aiMessage(AiMessage.from("答"))
                .tokenUsage(new TokenUsage(1, 1)).build();
        listener.onResponse(new ChatModelResponseContext(response, request, ModelProvider.OPEN_AI, new HashMap<>()));
        assertThat(recorder.drain()).hasSize(1);
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./mvnw -q -Dtest='ClientSpecTest,ModelUsageListenerTest' test`
Expected: 编译失败。

- [ ] **Step 3: 修改 ClientSpec**

把 `ClientSpec.java` 的 record 声明与前两个构造器替换为：

```java
public record ClientSpec(String baseUrl, String apiKey, Duration timeout, String modelName,
                         double temperature, Integer maxTokens, Boolean thinking, Map<String, Object> parameters,
                         String providerName, String step) {

    public ClientSpec {
        parameters = parameters == null ? Map.of() : parameters;
        providerName = providerName == null ? "" : providerName;
        step = step == null ? "" : step;
    }

    public ClientSpec(String baseUrl, String apiKey, Duration timeout, String modelName,
                      double temperature, Integer maxTokens, Boolean thinking, Map<String, Object> parameters) {
        this(baseUrl, apiKey, timeout, modelName, temperature, maxTokens, thinking, parameters, "", "");
    }

    public ClientSpec(String baseUrl, String apiKey, Duration timeout, String modelName,
                      double temperature, Integer maxTokens, Boolean thinking) {
        this(baseUrl, apiKey, timeout, modelName, temperature, maxTokens, thinking, Map.of(), "", "");
    }
```

并把 `fingerprint()` 中的 `String.valueOf(new java.util.TreeMap<>(parameters)));` 改为：

```java
                    String.valueOf(new java.util.TreeMap<>(parameters)), providerName, step);
```

类注释改为：`/** 构建一个对话客户端所需的全部取值；指纹相同的客户端在快照之间复用。providerName 与 step 用于用量归属，不同步骤各有独立客户端。 */`

- [ ] **Step 4: DefaultModelRegistry 传入 provider 名与步骤**

把

```java
            ClientSpec spec = new ClientSpec(provider.baseUrl(), apiKey, timeout, model.modelName(),
                    temperature, maxTokens, assignment.thinking(), ModelExtra.passthrough(extra));
```

改为：

```java
            ClientSpec spec = new ClientSpec(provider.baseUrl(), apiKey, timeout, model.modelName(),
                    temperature, maxTokens, assignment.thinking(), ModelExtra.passthrough(extra),
                    provider.name(), assignment.purpose().name());
```

- [ ] **Step 5: 实现 ModelUsageListener**

```java
package com.kwiki.infrastructure.ai.registry;

import com.kwiki.usage.ModelUsageContext;
import com.kwiki.usage.ModelUsageRecorder;
import com.kwiki.usage.UsageEvent;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.output.TokenUsage;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * 对话模型用量采集：请求时在调用线程上判定来源/用户/requestId 并记下开始时间，
 * 响应或失败时（可能在 HTTP 回调线程）提交用量事件。任何异常都被吞掉，不影响调用。
 */
public final class ModelUsageListener implements ChatModelListener {

    private static final String STARTED = ModelUsageListener.class.getName() + ".started";

    private record Started(ModelUsageContext.Resolved owner, long startNanos) {
    }

    private final ModelUsageRecorder recorder;
    private final String step;
    private final String providerName;
    private final String modelName;

    public ModelUsageListener(ModelUsageRecorder recorder, String step, String providerName, String modelName) {
        this.recorder = recorder;
        this.step = step;
        this.providerName = providerName;
        this.modelName = modelName;
    }

    @Override
    public void onRequest(ChatModelRequestContext context) {
        try {
            context.attributes().put(STARTED, new Started(ModelUsageContext.resolve(), System.nanoTime()));
        } catch (RuntimeException ignored) {
            // 采集失败不影响模型调用
        }
    }

    @Override
    public void onResponse(ChatModelResponseContext context) {
        try {
            TokenUsage usage = context.chatResponse() == null ? null : context.chatResponse().tokenUsage();
            record(started(context.attributes().get(STARTED)), usage, true, null);
        } catch (RuntimeException ignored) {
            // 采集失败不影响模型调用
        }
    }

    @Override
    public void onError(ChatModelErrorContext context) {
        try {
            String errorType = context.error() == null ? null : context.error().getClass().getSimpleName();
            record(started(context.attributes().get(STARTED)), null, false, errorType);
        } catch (RuntimeException ignored) {
            // 采集失败不影响模型调用
        }
    }

    private static Started started(Object value) {
        return value instanceof Started started ? started : new Started(ModelUsageContext.resolve(), -1);
    }

    private void record(Started started, TokenUsage usage, boolean success, String errorType) {
        long latency = started.startNanos() < 0 ? 0
                : TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started.startNanos());
        ModelUsageContext.Resolved owner = started.owner();
        recorder.record(new UsageEvent(Instant.now(), owner.source(), step, providerName, modelName,
                owner.userId(), owner.requestId(),
                usage == null || usage.inputTokenCount() == null ? 0 : usage.inputTokenCount(),
                usage == null || usage.outputTokenCount() == null ? 0 : usage.outputTokenCount(),
                latency, success, errorType));
    }
}
```

- [ ] **Step 6: 工厂挂载监听器**

`OpenAiModelClientFactory.java`：

1. import 区加入 `import com.kwiki.usage.ModelUsageRecorder;`、`import dev.langchain4j.model.chat.listener.ChatModelListener;`、`import org.springframework.beans.factory.ObjectProvider;`、`import org.springframework.beans.factory.annotation.Autowired;`。
2. 字段与构造器改为：

```java
    private final WebClient.Builder webClientBuilder;
    private final Optional<io.micrometer.tracing.Tracer> tracer;
    private final Optional<io.micrometer.tracing.propagation.Propagator> propagator;
    private final ModelUsageRecorder usageRecorder;

    public OpenAiModelClientFactory(WebClient.Builder webClientBuilder,
                                    Optional<io.micrometer.tracing.Tracer> tracer,
                                    Optional<io.micrometer.tracing.propagation.Propagator> propagator) {
        this(webClientBuilder, tracer, propagator, null);
    }

    @Autowired
    public OpenAiModelClientFactory(WebClient.Builder webClientBuilder,
                                    Optional<io.micrometer.tracing.Tracer> tracer,
                                    Optional<io.micrometer.tracing.propagation.Propagator> propagator,
                                    ObjectProvider<ModelUsageRecorder> usageRecorder) {
        this.webClientBuilder = webClientBuilder;
        this.tracer = tracer;
        this.propagator = propagator;
        this.usageRecorder = usageRecorder == null ? null : usageRecorder.getIfAvailable();
    }

    /** 错误日志始终挂载；用量监听器在装配了记录器时挂载。 */
    private List<ChatModelListener> listeners(ClientSpec spec) {
        List<ChatModelListener> listeners = new ArrayList<>();
        listeners.add(new ModelCallErrorLogger(spec.baseUrl(), spec.modelName()));
        if (usageRecorder != null) {
            listeners.add(new ModelUsageListener(usageRecorder, spec.step(), spec.providerName(), spec.modelName()));
        }
        return listeners;
    }
```

3. `chat(...)` 与 `streaming(...)` 中把 `.listeners(List.of(new ModelCallErrorLogger(spec.baseUrl(), spec.modelName())))` 都替换为 `.listeners(listeners(spec))`。

- [ ] **Step 7: 运行确认通过**

Run: `./mvnw -q -Dtest='ClientSpecTest,ModelUsageListenerTest,OpenAiModelClientFactoryTest,DefaultModelRegistryTest,ModelCallErrorLoggerTest,KwikiApplicationTest' test`
Expected: PASS。若 `DefaultModelRegistryTest` 断言「同一模型多个用途复用同一客户端」，按新语义（不同步骤各自一个客户端）修改该断言并在测试名中说明原因。

- [ ] **Step 8: 提交**

```bash
git add src/main/java/com/kwiki/infrastructure/ai/registry src/test/java/com/kwiki/infrastructure/ai/registry
git commit -m "feat(usage): 对话模型用量采集——每个步骤独立客户端并挂载用量监听器，记录来源、用户、requestId、输入/输出 token、耗时与失败"
```

---

### Task 4: embedding 用量采集

**Files:**
- Modify: `src/main/java/com/kwiki/infrastructure/ai/QwenEmbeddingClient.java`
- Modify: `src/main/java/com/kwiki/infrastructure/ai/registry/ModelClientFactory.java`、`OpenAiModelClientFactory.java`、`DefaultModelRegistry.java`（embedding 构建处）
- Modify: `src/main/java/com/kwiki/indexing/job/IndexingWorker.java`（`pipeline.embeddings().embed(childTexts)` 处）
- Modify: `src/main/java/com/kwiki/infrastructure/elasticsearch/CommunityIndexWriter.java`（`embeddings.embed(texts)` 处）
- Test: `src/test/java/com/kwiki/infrastructure/ai/QwenEmbeddingUsageTest.java`

**Interfaces:**
- Consumes: Task 1、2 的类型
- Produces:
  - `QwenEmbeddingClient(WebClient, String model, int dimensions, int batchSize, int maxRetries, Map<String,Object> parameters, String providerName, ModelUsageRecorder recorder)`（recorder 可为 null）
  - `ModelClientFactory` 新默认方法 `ChunkEmbeddingPort embedding(String providerName, String baseUrl, String apiKey, Duration timeout, String modelName, int dimensions, int maxRetries, int batchSize, Map<String,Object> parameters)`，默认委托旧方法

- [ ] **Step 1: 写失败测试**

先读 `src/test/java/com/kwiki/infrastructure/ai/QwenEmbeddingClientTest.java` 了解它构造 `WebClient` 与 MockWebServer 的方式，新测试沿用同样写法：

```java
package com.kwiki.infrastructure.ai;

import com.kwiki.testutil.StandardTestProperties;
import com.kwiki.usage.ModelUsageContext;
import com.kwiki.usage.ModelUsageRecorder;
import com.kwiki.usage.UsageEvent;
import com.kwiki.usage.UsageSource;
import com.kwiki.usage.UsageSteps;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QwenEmbeddingUsageTest {
    MockWebServer server;
    final ModelUsageRecorder recorder = new ModelUsageRecorder(StandardTestProperties.nullProvider());

    @BeforeEach
    void start() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stop() throws Exception {
        server.shutdown();
    }

    QwenEmbeddingClient client() {
        WebClient web = WebClient.builder().baseUrl(server.url("/v1").toString())
                .defaultHeader("Content-Type", "application/json").build();
        return new QwenEmbeddingClient(web, "text-embedding-v4", 2, 10, 0, Map.of(), "dashscope", recorder);
    }

    static MockResponse ok(String usage) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(
                "{\"data\":[{\"index\":0,\"embedding\":[0.1,0.2]}]" + usage + "}");
    }

    @Test
    void queryEmbeddingRecordsPromptTokens() {
        server.enqueue(ok(",\"usage\":{\"prompt_tokens\":12,\"total_tokens\":12}"));

        client().embed(List.of("问题"));

        UsageEvent event = recorder.drain().getFirst();
        assertThat(event.step()).isEqualTo(UsageSteps.QUERY_EMBEDDING);
        assertThat(event.provider()).isEqualTo("dashscope");
        assertThat(event.model()).isEqualTo("text-embedding-v4");
        assertThat(event.inputTokens()).isEqualTo(12);
        assertThat(event.outputTokens()).isZero();
        assertThat(event.source()).isEqualTo(UsageSource.BACKGROUND);
        assertThat(event.success()).isTrue();
    }

    @Test
    void indexScopeMarksIndexEmbeddingAndMissingUsageCountsZero() {
        server.enqueue(ok(""));

        try (var scope = ModelUsageContext.open(UsageSource.BACKGROUND, 0L, UsageSteps.INDEX_EMBEDDING)) {
            client().embed(List.of("段落"));
        }

        UsageEvent event = recorder.drain().getFirst();
        assertThat(event.step()).isEqualTo(UsageSteps.INDEX_EMBEDDING);
        assertThat(event.inputTokens()).isZero();
    }

    @Test
    void rejectedRequestIsRecordedAsFailure() {
        server.enqueue(new MockResponse().setResponseCode(400).setBody("{\"error\":\"Arrearage\"}"));

        assertThatThrownBy(() -> client().embed(List.of("问题"))).isInstanceOf(IllegalStateException.class);

        UsageEvent event = recorder.drain().getFirst();
        assertThat(event.success()).isFalse();
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./mvnw -q -Dtest=QwenEmbeddingUsageTest test`
Expected: 编译失败（8 参构造器不存在）。

- [ ] **Step 3: 实现 QwenEmbeddingClient 采集**

1. import 加入 `com.kwiki.usage.ModelUsageContext`、`ModelUsageRecorder`、`UsageEvent`、`UsageSteps`、`com.fasterxml.jackson.annotation.JsonProperty`。
2. 字段加入 `private final String providerName;`、`private final ModelUsageRecorder recorder;`；原 6 参构造器改为委托 `this(webClient, model, dimensions, batchSize, maxRetries, parameters, null, null);`，新增 8 参构造器（赋值同原构造器，外加 `this.providerName = providerName; this.recorder = recorder;`）。
3. 把 `embedBatch` 改为：

```java
    private List<float[]> embedBatch(List<String> batch) {
        long started = System.nanoTime();
        try {
            BatchResult result = embedWithRetry(batch);
            recordUsage(started, result.promptTokens(), true, null);
            return result.vectors();
        } catch (RuntimeException e) {
            recordUsage(started, 0, false, e.getClass().getSimpleName());
            throw e;
        }
    }
```

   原 `embedBatch` 方法体整体改名为 `private BatchResult embedWithRetry(List<String> batch)`，其中 `return callOnce(batch);` 不变；`callOnce` 返回类型改为 `BatchResult`，末尾 `return vectors;` 改为：

```java
        int promptTokens = response.usage() == null || response.usage().promptTokens() == null
                ? 0 : response.usage().promptTokens();
        return new BatchResult(vectors, promptTokens);
```

4. 新增：

```java
    private record BatchResult(List<float[]> vectors, int promptTokens) {
    }

    /** 记录一次批量请求的用量；步骤默认查询向量，索引任务通过 ModelUsageContext 覆盖为索引向量。 */
    private void recordUsage(long startedNanos, int promptTokens, boolean success, String errorType) {
        if (recorder == null) return;
        try {
            var owner = ModelUsageContext.resolve();
            String step = owner.step() == null ? UsageSteps.QUERY_EMBEDDING : owner.step();
            recorder.record(new UsageEvent(java.time.Instant.now(), owner.source(), step, providerName, model,
                    owner.userId(), owner.requestId(), promptTokens, 0,
                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos),
                    success, errorType));
        } catch (RuntimeException ignored) {
            // 采集失败不影响 embedding
        }
    }
```

5. 文件末尾的 `EmbeddingResponse` 改为（保留类上的 `@JsonIgnoreProperties(ignoreUnknown = true)`）：

```java
    public record EmbeddingResponse(List<EmbeddingItem> data, EmbeddingUsage usage) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EmbeddingUsage(@JsonProperty("prompt_tokens") Integer promptTokens,
                                 @JsonProperty("total_tokens") Integer totalTokens) {}
```

- [ ] **Step 4: 工厂与注册表传入 provider 名**

`ModelClientFactory.java` 在旧 `embedding(...)` 之后加入：

```java
    /** 带 provider 名的构建（用于用量归属）；默认忽略 provider 名，委托旧方法。 */
    default ChunkEmbeddingPort embedding(String providerName, String baseUrl, String apiKey, Duration timeout,
                                         String modelName, int dimensions, int maxRetries, int batchSize,
                                         Map<String, Object> parameters) {
        return embedding(baseUrl, apiKey, timeout, modelName, dimensions, maxRetries, batchSize, parameters);
    }
```

`OpenAiModelClientFactory.java` 覆盖该方法：

```java
    @Override
    public ChunkEmbeddingPort embedding(String providerName, String baseUrl, String apiKey, Duration timeout,
                                        String modelName, int dimensions, int maxRetries, int batchSize,
                                        Map<String, Object> parameters) {
        return new QwenEmbeddingClient(webClient(baseUrl, apiKey, timeout), modelName, dimensions,
                batchSize, maxRetries, parameters, providerName, usageRecorder);
    }
```

`DefaultModelRegistry.java` 中把 `factory.embedding(provider.baseUrl(), apiKey, timeout, model.modelName(),` 改为 `factory.embedding(provider.name(), provider.baseUrl(), apiKey, timeout, model.modelName(),`（其余参数不变）。

- [ ] **Step 5: 索引向量作用域**

`IndexingWorker.java`：把 `return pipeline.embeddings().embed(childTexts);` 改为：

```java
            try (var usage = com.kwiki.usage.ModelUsageContext.open(com.kwiki.usage.UsageSource.BACKGROUND, 0L,
                    com.kwiki.usage.UsageSteps.INDEX_EMBEDDING)) {
                return pipeline.embeddings().embed(childTexts);
            }
```

`CommunityIndexWriter.java`：把

```java
        List<float[]> vectors = limiter == null ? embeddings.embed(texts)
                : limiter.execute(() -> embeddings.embed(texts));
```

改为：

```java
        List<float[]> vectors;
        try (var usage = com.kwiki.usage.ModelUsageContext.open(com.kwiki.usage.UsageSource.BACKGROUND, 0L,
                com.kwiki.usage.UsageSteps.INDEX_EMBEDDING)) {
            vectors = limiter == null ? embeddings.embed(texts) : limiter.execute(() -> embeddings.embed(texts));
        }
```

（若 `limiter.execute` 在另一个线程执行回调，作用域不会传播；先读 `limiter` 的实现确认，若跨线程则把作用域放进 lambda 内部：`limiter.execute(() -> { try (var u = ModelUsageContext.open(...)) { return embeddings.embed(texts); } })`。）

- [ ] **Step 6: 运行确认通过**

Run: `./mvnw -q -Dtest='QwenEmbeddingUsageTest,QwenEmbeddingClientTest,OpenAiModelClientFactoryTest,DefaultModelRegistryTest,IndexingWorker*,CommunityIndex*,KwikiApplicationTest' -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: PASS。

- [ ] **Step 7: 提交**

```bash
git add src/main/java/com/kwiki/infrastructure/ai src/main/java/com/kwiki/indexing/job/IndexingWorker.java src/main/java/com/kwiki/infrastructure/elasticsearch/CommunityIndexWriter.java src/test/java/com/kwiki/infrastructure/ai/QwenEmbeddingUsageTest.java
git commit -m "feat(usage): embedding 用量采集——解析 usage.prompt_tokens，默认计为查询向量，建索引与社区索引写入计为索引向量"
```

---

### Task 5: 用量查询服务与管理接口

**Files:**
- Modify: `src/main/java/com/kwiki/usage/ModelUsageRepository.java`（追加查询方法）
- Create: `src/main/java/com/kwiki/usage/ModelUsageQueryService.java`、`ModelUsageAdminController.java`
- Test: `src/test/java/com/kwiki/usage/ModelUsageQueryServiceTest.java`、`ModelUsageRepositoryQueryTest.java`

**Interfaces:**
- Consumes: `ModelUsageRepository`（Task 2）、`UsageSteps.label`、`UsageSource`（Task 1）
- Produces:
  - 仓储：`record SeriesRow(long bucketEpochSeconds, String step, String provider, String model, long calls, long failures, long inputTokens, long outputTokens, long latencyMs)`；`List<SeriesRow> querySeries(Instant from, Instant to, Set<UsageSource> sources, Set<Long> users, long bucketSeconds, long offsetSeconds)`；`record UserRow(long userId, String displayName, long totalTokens)`；`List<UserRow> queryUsers(Instant from, Instant to, Set<UsageSource> sources)`
  - 服务：`SeriesResponse series(Instant from, Instant to, Set<UsageSource> sources, Set<Long> users, String bucket, int tzOffsetMinutes)`；`List<UserView> users(Instant from, Instant to, Set<UsageSource> sources)`；`record SeriesResponse(long bucketSeconds, List<Long> buckets, List<Series> series)`；`record Series(String step, String stepLabel, String provider, String model, long[] calls, long[] failures, long[] inputTokens, long[] outputTokens, long[] latencyMs)`；`record UserView(long userId, String displayName, long totalTokens)`
  - 接口：`GET /api/v1/admin/model-usage/users`、`GET /api/v1/admin/model-usage/series`

- [ ] **Step 1: 写失败测试**

`src/test/java/com/kwiki/usage/ModelUsageQueryServiceTest.java`：

```java
package com.kwiki.usage;

import com.kk2004.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelUsageQueryServiceTest {
    final ModelUsageRepository repository = mock(ModelUsageRepository.class);
    final ModelUsageQueryService service = new ModelUsageQueryService(repository);

    // 东八区 2026-10-21 当天：UTC 2026-10-20T16:00Z ~ 2026-10-21T16:00Z
    final Instant from = Instant.parse("2026-10-20T16:00:00Z");
    final Instant to = Instant.parse("2026-10-21T16:00:00Z");

    @Test
    void autoBucketForOneDayIsFiveMinutesAlignedAndZeroFilled() {
        when(repository.available()).thenReturn(true);
        long first = from.getEpochSecond();
        when(repository.querySeries(eq(from), eq(to), any(), any(), eq(300L), eq(28_800L))).thenReturn(List.of(
                new ModelUsageRepository.SeriesRow(first, "ANSWER", "dashscope", "qwen-plus", 2, 0, 100, 20, 50),
                new ModelUsageRepository.SeriesRow(first + 600, "ANSWER", "dashscope", "qwen-plus", 1, 1, 10, 0, 5),
                new ModelUsageRepository.SeriesRow(first, "QUERY_EMBEDDING", "dashscope", "v4", 3, 0, 30, 0, 9)));

        var response = service.series(from, to, Set.of(), Set.of(), "auto", 480);

        assertThat(response.bucketSeconds()).isEqualTo(300);
        assertThat(response.buckets()).hasSize(288);
        assertThat(response.buckets().getFirst()).isEqualTo(from.toEpochMilli());
        var answer = response.series().stream().filter(s -> s.step().equals("ANSWER")).findFirst().orElseThrow();
        assertThat(answer.stepLabel()).isEqualTo("回答生成");
        assertThat(answer.inputTokens()[0]).isEqualTo(100);
        assertThat(answer.inputTokens()[1]).isZero();
        assertThat(answer.inputTokens()[2]).isEqualTo(10);
        assertThat(answer.failures()[2]).isEqualTo(1);
        assertThat(response.series()).extracting(ModelUsageQueryService.Series::stepLabel).contains("查询向量");
    }

    @Test
    void longRangesEscalateBucketSize() {
        when(repository.available()).thenReturn(true);
        when(repository.querySeries(any(), any(), any(), any(), anyLong(), anyLong())).thenReturn(List.of());

        assertThat(service.series(from, from.plusSeconds(3 * 86_400), Set.of(), Set.of(), "auto", 480)
                .bucketSeconds()).isEqualTo(3600);
        assertThat(service.series(from, from.plusSeconds(30 * 86_400), Set.of(), Set.of(), "auto", 480)
                .bucketSeconds()).isEqualTo(86_400);
        // 手动 5m 查 30 天会超过 2000 个桶，自动升到 1h
        assertThat(service.series(from, from.plusSeconds(30 * 86_400), Set.of(), Set.of(), "5m", 480)
                .bucketSeconds()).isEqualTo(3600);
    }

    @Test
    void dailyBucketsAlignToLocalMidnight() {
        when(repository.available()).thenReturn(true);
        when(repository.querySeries(any(), any(), any(), any(), anyLong(), anyLong())).thenReturn(List.of());

        var response = service.series(Instant.parse("2026-10-01T03:00:00Z"), Instant.parse("2026-10-21T03:00:00Z"),
                Set.of(), Set.of(), "1d", 480);

        // 东八区零点 = UTC 前一日 16:00
        assertThat(Instant.ofEpochMilli(response.buckets().getFirst()))
                .isEqualTo(Instant.parse("2026-09-30T16:00:00Z"));
    }

    @Test
    void sourcesAndUsersArePassedThrough() {
        when(repository.available()).thenReturn(true);
        when(repository.querySeries(any(), any(), any(), any(), anyLong(), anyLong())).thenReturn(List.of());

        service.series(from, to, Set.of(UsageSource.EVAL), Set.of(0L, 7L), "1h", 480);

        verify(repository).querySeries(from, to, Set.of(UsageSource.EVAL), Set.of(0L, 7L), 3600L, 28_800L);
    }

    @Test
    void invalidRangesAreRejected() {
        assertThatThrownBy(() -> service.series(to, from, Set.of(), Set.of(), "auto", 480))
                .isInstanceOf(BusinessException.class).hasMessageContaining("invalid-range");
        assertThatThrownBy(() -> service.series(from, from.plusSeconds(367L * 86_400), Set.of(), Set.of(), "auto", 480))
                .isInstanceOf(BusinessException.class).hasMessageContaining("range-too-long");
        assertThatThrownBy(() -> service.series(from, to, Set.of(), Set.of(), "2m", 480))
                .isInstanceOf(BusinessException.class).hasMessageContaining("invalid-bucket");
    }

    @Test
    void usersShowSystemForZero() {
        when(repository.available()).thenReturn(true);
        when(repository.queryUsers(from, to, Set.of())).thenReturn(List.of(
                new ModelUsageRepository.UserRow(7, "张三", 900), new ModelUsageRepository.UserRow(0, null, 100)));

        assertThat(service.users(from, to, Set.of())).containsExactly(
                new ModelUsageQueryService.UserView(7, "张三", 900),
                new ModelUsageQueryService.UserView(0, "系统", 100));
    }

    @Test
    void missingDatabaseIsServiceUnavailable() {
        when(repository.available()).thenReturn(false);
        assertThatThrownBy(() -> service.series(from, to, Set.of(), Set.of(), "auto", 480))
                .isInstanceOf(BusinessException.class).hasMessageContaining("usage-store-unavailable");
    }
}
```

`src/test/java/com/kwiki/usage/ModelUsageRepositoryQueryTest.java`：

```java
package com.kwiki.usage;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelUsageRepositoryQueryTest {
    final JdbcOperations jdbc = mock(JdbcOperations.class);

    @SuppressWarnings("unchecked")
    ModelUsageRepository repository() {
        ObjectProvider<JdbcOperations> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(jdbc);
        return new ModelUsageRepository(provider);
    }

    @Test
    @SuppressWarnings("unchecked")
    void seriesQueryBucketsWithTimestampDiffAndBindsFilters() {
        var sources = new LinkedHashSet<>(List.of(UsageSource.ONLINE, UsageSource.EVAL));
        var users = new LinkedHashSet<>(List.of(0L, 7L));

        repository().querySeries(Instant.parse("2026-10-20T16:00:00Z"), Instant.parse("2026-10-21T16:00:00Z"),
                sources, users, 300, 28_800);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), args.capture());
        assertThat(sql.getValue()).contains("TIMESTAMPDIFF(SECOND, '1970-01-01 00:00:00', bucket_start)")
                .doesNotContain("UNIX_TIMESTAMP").contains("source IN (?, ?)").contains("user_id IN (?, ?)")
                .contains("GROUP BY bucket_epoch, step, provider, model");
        assertThat(args.getValue()).containsExactly(28_800L, 300L, 300L, 28_800L,
                LocalDateTime.parse("2026-10-20T16:00:00"), LocalDateTime.parse("2026-10-21T16:00:00"),
                "ONLINE", "EVAL", 0L, 7L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void emptyFiltersMeanAll() {
        repository().querySeries(Instant.EPOCH, Instant.EPOCH.plusSeconds(60), Set.of(), Set.of(), 300, 0);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sql.getValue()).doesNotContain("source IN").doesNotContain("user_id IN");
    }

    @Test
    @SuppressWarnings("unchecked")
    void usersQueryJoinsDisplayNames() {
        repository().queryUsers(Instant.EPOCH, Instant.EPOCH.plusSeconds(60), Set.of(UsageSource.ONLINE));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sql.getValue()).contains("LEFT JOIN app_user").contains("display_name")
                .contains("ORDER BY total_tokens DESC");
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./mvnw -q -Dtest='ModelUsageQueryServiceTest,ModelUsageRepositoryQueryTest' test`
Expected: 编译失败。

- [ ] **Step 3: 仓储查询方法**

在 `ModelUsageRepository` 中加入 import `java.util.ArrayList`、`java.util.Collections`、`java.util.Set`，并追加：

```java
    public record SeriesRow(long bucketEpochSeconds, String step, String provider, String model,
                            long calls, long failures, long inputTokens, long outputTokens, long latencyMs) {
    }

    public record UserRow(long userId, String displayName, long totalTokens) {
    }

    /** 按（桶、步骤、provider、模型）汇总；桶起点 = floor((秒数 + offset) / size) * size - offset。 */
    public List<SeriesRow> querySeries(Instant from, Instant to, Set<UsageSource> sources, Set<Long> users,
                                       long bucketSeconds, long offsetSeconds) {
        if (jdbc == null) return List.of();
        List<Object> args = new ArrayList<>(List.of(offsetSeconds, bucketSeconds, bucketSeconds, offsetSeconds,
                utc(from), utc(to)));
        StringBuilder sql = new StringBuilder("""
                SELECT FLOOR((TIMESTAMPDIFF(SECOND, '1970-01-01 00:00:00', bucket_start) + ?) / ?) * ? - ?
                         AS bucket_epoch,
                       step, provider, model,
                       SUM(calls) AS calls, SUM(failures) AS failures,
                       SUM(input_tokens) AS input_tokens, SUM(output_tokens) AS output_tokens,
                       SUM(latency_ms) AS latency_ms
                FROM model_usage_minute
                WHERE bucket_start >= ? AND bucket_start < ?
                """);
        appendFilters(sql, args, sources, users);
        sql.append(" GROUP BY bucket_epoch, step, provider, model");
        return jdbc.query(sql.toString(), (rs, i) -> new SeriesRow(rs.getLong("bucket_epoch"),
                rs.getString("step"), rs.getString("provider"), rs.getString("model"), rs.getLong("calls"),
                rs.getLong("failures"), rs.getLong("input_tokens"), rs.getLong("output_tokens"),
                rs.getLong("latency_ms")), args.toArray());
    }

    public List<UserRow> queryUsers(Instant from, Instant to, Set<UsageSource> sources) {
        if (jdbc == null) return List.of();
        List<Object> args = new ArrayList<>(List.of(utc(from), utc(to)));
        StringBuilder sql = new StringBuilder("""
                SELECT u.user_id, a.display_name, SUM(u.input_tokens + u.output_tokens) AS total_tokens
                FROM model_usage_minute u
                LEFT JOIN app_user a ON a.id = u.user_id
                WHERE u.bucket_start >= ? AND u.bucket_start < ?
                """);
        appendFilters(sql, args, sources, Set.of());
        sql.append(" GROUP BY u.user_id, a.display_name ORDER BY total_tokens DESC");
        return jdbc.query(sql.toString(), (rs, i) -> new UserRow(rs.getLong("user_id"),
                rs.getString("display_name"), rs.getLong("total_tokens")), args.toArray());
    }

    private static void appendFilters(StringBuilder sql, List<Object> args, Set<UsageSource> sources,
                                      Set<Long> users) {
        if (!sources.isEmpty()) {
            sql.append(" AND source IN (").append(String.join(", ", Collections.nCopies(sources.size(), "?")))
                    .append(")");
            sources.forEach(source -> args.add(source.name()));
        }
        if (!users.isEmpty()) {
            sql.append(" AND user_id IN (").append(String.join(", ", Collections.nCopies(users.size(), "?")))
                    .append(")");
            args.addAll(users);
        }
    }
```

注意 `queryUsers` 的 `source IN` 条件作用在 `u` 表上：列名 `source` 在两表 join 中不冲突（`app_user` 无该列），可直接使用。

- [ ] **Step 4: 查询服务**

`src/main/java/com/kwiki/usage/ModelUsageQueryService.java`：

```java
package com.kwiki.usage;

import com.kk2004.common.exception.BusinessException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 用量查询：校验范围、选择并按需升级分桶粒度、按本地时区对齐、补 0。 */
@Service
public class ModelUsageQueryService {

    static final long MAX_BUCKETS = 2_000;
    static final Duration MAX_RANGE = Duration.ofDays(366);
    private static final List<Long> LADDER = List.of(300L, 3_600L, 86_400L);

    public record Series(String step, String stepLabel, String provider, String model, long[] calls,
                         long[] failures, long[] inputTokens, long[] outputTokens, long[] latencyMs) {
    }

    public record SeriesResponse(long bucketSeconds, List<Long> buckets, List<Series> series) {
    }

    public record UserView(long userId, String displayName, long totalTokens) {
    }

    private final ModelUsageRepository repository;

    public ModelUsageQueryService(ModelUsageRepository repository) {
        this.repository = repository;
    }

    public SeriesResponse series(Instant from, Instant to, Set<UsageSource> sources, Set<Long> users,
                                 String bucket, int tzOffsetMinutes) {
        validateRange(from, to);
        long size = bucketSeconds(from, to, bucket);
        requireStore();
        long offset = tzOffsetMinutes * 60L;
        long start = Math.floorDiv(from.getEpochSecond() + offset, size) * size - offset;
        List<Long> buckets = new ArrayList<>();
        for (long t = start; t < to.getEpochSecond(); t += size) buckets.add(t * 1000);
        Map<String, Series> series = new LinkedHashMap<>();
        for (ModelUsageRepository.SeriesRow row : repository.querySeries(from, to, sources, users, size, offset)) {
            int index = (int) ((row.bucketEpochSeconds() - start) / size);
            if (index < 0 || index >= buckets.size()) continue;
            Series target = series.computeIfAbsent(row.step() + "\u0000" + row.provider() + "\u0000" + row.model(),
                    key -> new Series(row.step(), UsageSteps.label(row.step()), row.provider(), row.model(),
                            new long[buckets.size()], new long[buckets.size()], new long[buckets.size()],
                            new long[buckets.size()], new long[buckets.size()]));
            target.calls()[index] += row.calls();
            target.failures()[index] += row.failures();
            target.inputTokens()[index] += row.inputTokens();
            target.outputTokens()[index] += row.outputTokens();
            target.latencyMs()[index] += row.latencyMs();
        }
        return new SeriesResponse(size, buckets, List.copyOf(series.values()));
    }

    public List<UserView> users(Instant from, Instant to, Set<UsageSource> sources) {
        validateRange(from, to);
        requireStore();
        return repository.queryUsers(from, to, sources).stream()
                .map(row -> new UserView(row.userId(), row.userId() == 0 ? "系统"
                        : row.displayName() == null ? "用户 " + row.userId() : row.displayName(), row.totalTokens()))
                .toList();
    }

    /** 参数校验先于存储可用性检查：非法请求总是得到 400。 */
    private static void validateRange(Instant from, Instant to) {
        if (from == null || to == null || !to.isAfter(from)) throw new BusinessException(400, "invalid-range");
        if (Duration.between(from, to).compareTo(MAX_RANGE) > 0) throw new BusinessException(400, "range-too-long");
    }

    private void requireStore() {
        if (!repository.available()) throw new BusinessException(503, "usage-store-unavailable");
    }

    private static long bucketSeconds(Instant from, Instant to, String bucket) {
        long range = Duration.between(from, to).getSeconds();
        long size = switch (bucket == null ? "auto" : bucket) {
            case "auto" -> range <= 86_400 ? 300 : range <= 7 * 86_400 ? 3_600 : 86_400;
            case "5m" -> 300;
            case "1h" -> 3_600;
            case "1d" -> 86_400;
            default -> throw new BusinessException(400, "invalid-bucket");
        };
        for (long candidate : LADDER) {
            if (candidate >= size && Math.ceilDiv(range, candidate) <= MAX_BUCKETS) return candidate;
        }
        return 86_400;
    }
}
```

- [ ] **Step 5: 管理接口**

`src/main/java/com/kwiki/usage/ModelUsageAdminController.java`：

```java
package com.kwiki.usage;

import com.kk2004.common.exception.BusinessException;
import com.kk2004.common.response.TransDTO;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 模型用量监控管理接口；类级角色校验是全部查询的服务端边界。 */
@RestController
@RequestMapping("/api/v1/admin/model-usage")
@PreAuthorize("hasRole('ADMIN')")
public class ModelUsageAdminController {

    private final ModelUsageQueryService queries;

    public ModelUsageAdminController(ModelUsageQueryService queries) {
        this.queries = queries;
    }

    @GetMapping("/users")
    TransDTO<List<ModelUsageQueryService.UserView>> users(@RequestParam String from, @RequestParam String to,
                                                          @RequestParam(required = false) String sources) {
        return TransDTO.success(queries.users(instant(from), instant(to), sources(sources)));
    }

    @GetMapping("/series")
    TransDTO<ModelUsageQueryService.SeriesResponse> series(@RequestParam String from, @RequestParam String to,
                                                          @RequestParam(required = false) String sources,
                                                          @RequestParam(required = false) String users,
                                                          @RequestParam(defaultValue = "auto") String bucket,
                                                          @RequestParam(defaultValue = "480") int tzOffsetMinutes) {
        return TransDTO.success(queries.series(instant(from), instant(to), sources(sources), users(users), bucket,
                tzOffsetMinutes));
    }

    private static Instant instant(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new BusinessException(400, "invalid-time");
        }
    }

    private static Set<UsageSource> sources(String value) {
        Set<UsageSource> sources = new LinkedHashSet<>();
        if (value == null || value.isBlank()) return sources;
        for (String token : value.split(",")) {
            try {
                sources.add(UsageSource.valueOf(token.trim()));
            } catch (IllegalArgumentException e) {
                throw new BusinessException(400, "invalid-source");
            }
        }
        return sources;
    }

    private static Set<Long> users(String value) {
        Set<Long> users = new LinkedHashSet<>();
        if (value == null || value.isBlank()) return users;
        try {
            Arrays.stream(value.split(",")).map(String::trim).map(Long::parseLong).forEach(users::add);
        } catch (NumberFormatException e) {
            throw new BusinessException(400, "invalid-user");
        }
        return users;
    }
}
```

- [ ] **Step 6: 运行确认通过**

Run: `./mvnw -q -Dtest='ModelUsageQueryServiceTest,ModelUsageRepositoryQueryTest,ModelUsageRepositoryTest,KwikiApplicationTest' test`
Expected: PASS。

- [ ] **Step 7: 补充真实 MySQL 契约**

在 Task 2 的 `ModelUsageRepositoryContractTest` 中追加：

```java
    @Test
    void seriesBucketsAreAlignedToLocalDaysAndUsersJoinDisplayNames() {
        jdbc.update("DELETE FROM model_usage_minute");
        var repository = repository();
        repository.upsertMinutes(List.of(
                new MinuteAggregate(Instant.parse("2026-10-20T16:00:00Z"), UsageSource.ONLINE, "ANSWER", "p", "m",
                        0, 1, 0, 10, 1, 1),
                new MinuteAggregate(Instant.parse("2026-10-21T15:59:00Z"), UsageSource.ONLINE, "ANSWER", "p", "m",
                        0, 1, 0, 20, 1, 1)));

        var rows = repository.querySeries(Instant.parse("2026-10-20T16:00:00Z"),
                Instant.parse("2026-10-21T16:00:00Z"), java.util.Set.of(), java.util.Set.of(), 86_400, 28_800);

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().bucketEpochSeconds()).isEqualTo(Instant.parse("2026-10-20T16:00:00Z").getEpochSecond());
        assertThat(rows.getFirst().inputTokens()).isEqualTo(30);
        assertThat(repository.queryUsers(Instant.parse("2026-10-20T00:00:00Z"), Instant.parse("2026-10-22T00:00:00Z"),
                java.util.Set.of())).extracting(ModelUsageRepository.UserRow::userId).containsExactly(0L);
    }
```

Run: `./mvnw -q -Dtest=ModelUsageRepositoryContractTest test`
Expected: 无 `KWIKI_IT_MYSQL_URL` 时跳过；有则 PASS。

- [ ] **Step 8: 提交**

```bash
git add src/main/java/com/kwiki/usage src/test/java/com/kwiki/usage
git commit -m "feat(usage): 用量查询接口——按步骤×provider×模型返回分桶序列（本地时区对齐、补 0、自动升粒度）与用户列表"
```

---

### Task 6: 管理端用量数据工具函数

**Files:**
- Modify: `admin-frontend/src/api.ts`
- Create: `admin-frontend/src/usageFormat.ts`、`admin-frontend/src/usageSeries.ts`
- Test: `admin-frontend/tests/usage-format.spec.ts`、`admin-frontend/tests/usage-series.spec.ts`

**Interfaces:**
- Consumes: Task 5 接口
- Produces:
  - `api.ts`：类型 `UsageSourceName`、`UsageSeries`、`UsageSeriesResponse`、`UsageUser`；`api.modelUsageSeries(params)`、`api.modelUsageUsers(params)`
  - `usageFormat.ts`：`formatTokens(n: number): string`
  - `usageSeries.ts`：`type Metric`、`type GroupBy`、`interface TreeNode`、`interface Line`、`interface Totals`、`leafKey(series)`、`buildTree(series, groupBy)`、`allLeafKeys(tree)`、`buildLines(series, tree, checked, groupBy, metric)`、`totals(series, checked)`、`tableRows(lines)`、`metricValues(series, metric)`、`rangeFor(preset, now)`

- [ ] **Step 1: 写失败测试**

`admin-frontend/tests/usage-format.spec.ts`：

```ts
import { describe, expect, it } from "vitest";
import { formatTokens } from "../src/usageFormat";

describe("formatTokens", () => {
  it("小于 1000 原样显示", () => {
    expect(formatTokens(0)).toBe("0");
    expect(formatTokens(999)).toBe("999");
  });
  it("按 k / M / B 自适应并保留 3 位有效数字", () => {
    expect(formatTokens(1000)).toBe("1.00k");
    expect(formatTokens(12_345)).toBe("12.3k");
    expect(formatTokens(999_499)).toBe("999k");
    expect(formatTokens(4_560_000)).toBe("4.56M");
    expect(formatTokens(1_200_000_000)).toBe("1.20B");
  });
  it("进位到下一单位", () => {
    expect(formatTokens(999_999)).toBe("1.00M");
  });
  it("非有限数显示为 0", () => {
    expect(formatTokens(Number.NaN)).toBe("0");
  });
});
```

`admin-frontend/tests/usage-series.spec.ts`：

```ts
import { describe, expect, it } from "vitest";
import type { UsageSeries } from "../src/api";
import { allLeafKeys, buildLines, buildTree, leafKey, rangeFor, tableRows, totals } from "../src/usageSeries";

const s = (step: string, stepLabel: string, model: string, input: number[], output: number[] = input.map(() => 0)): UsageSeries => ({
  step, stepLabel, provider: "dashscope", model,
  calls: input.map(() => 1), failures: input.map(() => 0), inputTokens: input, outputTokens: output, latencyMs: input.map(() => 10),
});
const data = [
  s("ANSWER", "回答生成", "qwen-plus", [10, 20], [1, 2]),
  s("ANSWER", "回答生成", "qwen-max", [5, 5]),
  s("REWRITE", "查询改写", "qwen-plus", [1, 1]),
];

describe("维度树", () => {
  it("按步骤：步骤 → 模型，按总 token 降序", () => {
    const tree = buildTree(data, "step");
    expect(tree.map(n => n.label)).toEqual(["回答生成", "查询改写"]);
    expect(tree[0].children.map(c => c.label)).toEqual(["dashscope / qwen-plus", "dashscope / qwen-max"]);
  });
  it("按模型：provider/模型 → 步骤，按总 token 降序", () => {
    const tree = buildTree(data, "model");
    expect(tree.map(n => n.label)).toEqual(["dashscope / qwen-plus", "dashscope / qwen-max"]);
    expect(tree[0].children.map(c => c.label)).toEqual(["回答生成", "查询改写"]);
    expect(allLeafKeys(tree)).toHaveLength(3);
  });
});

describe("折线分组", () => {
  it("一级节点全选时合并为一条线", () => {
    const tree = buildTree(data, "step");
    const lines = buildLines(data, tree, new Set(allLeafKeys(tree)), "step", "total");
    expect(lines.map(l => l.name)).toEqual(["回答生成", "查询改写"]);
    expect(lines[0].values).toEqual([16, 27]);
  });
  it("部分勾选时按叶子拆线", () => {
    const tree = buildTree(data, "step");
    const lines = buildLines(data, tree, new Set([leafKey(data[0]), leafKey(data[2])]), "step", "input");
    expect(lines.map(l => l.name)).toEqual(["回答生成 / dashscope / qwen-plus", "查询改写"]);
  });
  it("按模型分组时同一模型跨步骤合并", () => {
    const tree = buildTree(data, "model");
    const lines = buildLines(data, tree, new Set(allLeafKeys(tree)), "model", "input");
    expect(lines.find(l => l.name === "dashscope / qwen-plus")?.values).toEqual([11, 21]);
  });
});

describe("汇总与明细", () => {
  it("只统计勾选的叶子", () => {
    const t = totals(data, new Set([leafKey(data[0])]));
    expect(t).toMatchObject({ input: 30, output: 3, total: 33, calls: 2, failures: 0, failureRate: 0, avgLatencyMs: 10 });
  });
  it("明细行按总量降序", () => {
    const tree = buildTree(data, "step");
    const rows = tableRows(buildLines(data, tree, new Set(allLeafKeys(tree)), "step", "total"));
    expect(rows[0]).toMatchObject({ name: "回答生成", total: 43 });
  });
});

describe("时间范围", () => {
  it("今天为本地零点到明天零点", () => {
    const now = new Date(2026, 9, 21, 15, 30);
    const { from, to } = rangeFor("today", now);
    expect(from.getHours()).toBe(0);
    expect(to.getTime() - from.getTime()).toBe(24 * 3600 * 1000);
    expect(from.getDate()).toBe(21);
  });
  it("近 7 天以今天结束", () => {
    const { from, to } = rangeFor("7d", new Date(2026, 9, 21, 15, 30));
    expect((to.getTime() - from.getTime()) / 86_400_000).toBe(7);
  });
});
```

- [ ] **Step 2: 运行确认失败**

Run: `cd admin-frontend && npx vitest run tests/usage-format.spec.ts tests/usage-series.spec.ts`
Expected: FAIL，模块不存在。

- [ ] **Step 3: api.ts 增加类型与接口**

在 `admin-frontend/src/api.ts` 的类型区（`export const api={` 之前）加入：

```ts
export type UsageSourceName="ONLINE"|"EVAL"|"BACKGROUND";
export interface UsageSeries{step:string;stepLabel:string;provider:string;model:string;calls:number[];failures:number[];inputTokens:number[];outputTokens:number[];latencyMs:number[]}
export interface UsageSeriesResponse{bucketSeconds:number;buckets:number[];series:UsageSeries[]}
export interface UsageUser{userId:number;displayName:string;totalTokens:number}
export interface UsageQuery{from:string;to:string;sources?:UsageSourceName[];users?:number[];bucket?:string;tzOffsetMinutes?:number}
const usageParams=(q:UsageQuery)=>{const p=new URLSearchParams({from:q.from,to:q.to});if(q.sources?.length)p.set("sources",q.sources.join(","));if(q.users?.length)p.set("users",q.users.join(","));if(q.bucket)p.set("bucket",q.bucket);if(q.tzOffsetMinutes!==undefined)p.set("tzOffsetMinutes",String(q.tzOffsetMinutes));return p.toString()};
```

并在 `export const api={` 对象中加入两项：

```ts
  modelUsageSeries:(q:UsageQuery)=>request<UsageSeriesResponse>(`/admin/model-usage/series?${usageParams(q)}`),
  modelUsageUsers:(q:UsageQuery)=>request<UsageUser[]>(`/admin/model-usage/users?${usageParams({from:q.from,to:q.to,sources:q.sources})}`),
```

- [ ] **Step 4: 实现 usageFormat.ts**

```ts
/** token 数量单位自适应：<1000 原样；之后 k / M / B，保留 3 位有效数字。 */
const UNITS: Array<[number, string]> = [[1e9, "B"], [1e6, "M"], [1e3, "k"]];

export function formatTokens(n: number): string {
  if (!Number.isFinite(n)) return "0";
  const abs = Math.abs(n);
  if (abs < 1000) return String(Math.round(n));
  for (let i = 0; i < UNITS.length; i++) {
    const [base, unit] = UNITS[i];
    if (abs >= base) {
      const scaled = Number((n / base).toPrecision(3));
      // 四舍五入后达到 1000 时进位到更大单位（如 999,999 → 1.00M）
      if (Math.abs(scaled) >= 1000 && i > 0) return `${(n / UNITS[i - 1][0]).toPrecision(3)}${UNITS[i - 1][1]}`;
      return `${(n / base).toPrecision(3)}${unit}`;
    }
  }
  return String(n);
}
```

（`999_499 / 1000 = 999.499` → `toPrecision(3)` 为 `"999"`；`999_999 / 1000` → `"1.00e+3"` 时 `scaled=1000` 触发进位为 `"1.00M"`。）

- [ ] **Step 5: 实现 usageSeries.ts**

```ts
import type { UsageSeries } from "./api";

export type Metric = "total" | "input" | "output" | "calls" | "failures";
export type GroupBy = "step" | "model";
export type RangePreset = "today" | "yesterday" | "7d" | "30d";

export interface TreeNode { id: string; label: string; children: TreeNode[] }
export interface Line { name: string; values: number[]; leaves: UsageSeries[] }
export interface Totals { total: number; input: number; output: number; calls: number; failures: number; failureRate: number; avgLatencyMs: number }
export interface TableRow extends Totals { name: string }

const modelLabel = (s: UsageSeries) => `${s.provider} / ${s.model}`;
export const leafKey = (s: UsageSeries) => `${s.step}|${s.provider}|${s.model}`;

export function metricValues(s: UsageSeries, metric: Metric): number[] {
  switch (metric) {
    case "input": return s.inputTokens;
    case "output": return s.outputTokens;
    case "calls": return s.calls;
    case "failures": return s.failures;
    default: return s.inputTokens.map((v, i) => v + s.outputTokens[i]);
  }
}

const tokenTotal = (s: UsageSeries) =>
  s.inputTokens.reduce((a, b) => a + b, 0) + s.outputTokens.reduce((a, b) => a + b, 0);

/**
 * 由序列构建维度树：按步骤为 步骤 → 模型；按模型为 provider/模型 → 步骤。叶子 id 为 leafKey。
 * 一级节点与叶子都按总 token 降序（用量大的在前），同量时按名称排序保证稳定。
 */
export function buildTree(series: UsageSeries[], groupBy: GroupBy): TreeNode[] {
  const groups = new Map<string, { node: TreeNode; total: number; leaves: Array<{ node: TreeNode; total: number }> }>();
  for (const s of series) {
    const groupId = groupBy === "step" ? `g:${s.step}` : `g:${s.provider}|${s.model}`;
    const groupLabel = groupBy === "step" ? s.stepLabel : modelLabel(s);
    const entry = groups.get(groupId) ?? { node: { id: groupId, label: groupLabel, children: [] }, total: 0, leaves: [] };
    const total = tokenTotal(s);
    entry.total += total;
    entry.leaves.push({ node: { id: leafKey(s), label: groupBy === "step" ? modelLabel(s) : s.stepLabel, children: [] }, total });
    groups.set(groupId, entry);
  }
  const order = (a: { node: TreeNode; total: number }, b: { node: TreeNode; total: number }) =>
    b.total - a.total || a.node.label.localeCompare(b.node.label, "zh-Hans-CN");
  return [...groups.values()].sort(order)
    .map(g => ({ ...g.node, children: [...g.leaves].sort(order).map(l => l.node) }));
}

export const allLeafKeys = (tree: TreeNode[]) => tree.flatMap(n => n.children.map(c => c.id));

const sum = (arrays: number[][], length: number) =>
  Array.from({ length }, (_, i) => arrays.reduce((acc, a) => acc + (a[i] ?? 0), 0));

/** 一级节点下全部勾选时合并为一条线，部分勾选时每个勾选叶子一条线。 */
export function buildLines(series: UsageSeries[], tree: TreeNode[], checked: Set<string>, groupBy: GroupBy, metric: Metric): Line[] {
  const byKey = new Map(series.map(s => [leafKey(s), s]));
  const length = series[0] ? metricValues(series[0], metric).length : 0;
  const lines: Line[] = [];
  for (const group of tree) {
    const selected = group.children.filter(c => checked.has(c.id));
    if (selected.length === 0) continue;
    if (selected.length === group.children.length) {
      const leaves = selected.map(c => byKey.get(c.id)!).filter(Boolean);
      lines.push({ name: group.label, values: sum(leaves.map(l => metricValues(l, metric)), length), leaves });
    } else {
      for (const child of selected) {
        const leaf = byKey.get(child.id);
        if (leaf) lines.push({ name: `${group.label} / ${child.label}`, values: [...metricValues(leaf, metric)], leaves: [leaf] });
      }
    }
  }
  return lines;
}

function totalsOf(leaves: UsageSeries[]): Totals {
  const add = (pick: (s: UsageSeries) => number[]) => leaves.reduce((acc, s) => acc + pick(s).reduce((a, b) => a + b, 0), 0);
  const input = add(s => s.inputTokens);
  const output = add(s => s.outputTokens);
  const calls = add(s => s.calls);
  const failures = add(s => s.failures);
  const latency = add(s => s.latencyMs);
  return { total: input + output, input, output, calls, failures,
    failureRate: calls ? failures / calls : 0, avgLatencyMs: calls ? Math.round(latency / calls) : 0 };
}

export const totals = (series: UsageSeries[], checked: Set<string>) => totalsOf(series.filter(s => checked.has(leafKey(s))));

export const tableRows = (lines: Line[]): TableRow[] =>
  lines.map(l => ({ name: l.name, ...totalsOf(l.leaves) })).sort((a, b) => b.total - a.total);

/** 预设时间范围（浏览器本地时区）：右开区间。 */
export function rangeFor(preset: RangePreset, now = new Date()): { from: Date; to: Date } {
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  const day = 86_400_000;
  const tomorrow = new Date(today.getTime() + day);
  switch (preset) {
    case "yesterday": return { from: new Date(today.getTime() - day), to: today };
    case "7d": return { from: new Date(tomorrow.getTime() - 7 * day), to: tomorrow };
    case "30d": return { from: new Date(tomorrow.getTime() - 30 * day), to: tomorrow };
    default: return { from: today, to: tomorrow };
  }
}
```

注意：`rangeFor` 用毫秒加减天数，夏令时地区会差 1 小时；本项目面向东八区（无夏令时），可接受。

- [ ] **Step 6: 运行确认通过**

Run: `cd admin-frontend && npx vitest run tests/usage-format.spec.ts tests/usage-series.spec.ts && npm run typecheck`
Expected: PASS。

- [ ] **Step 7: 提交**

```bash
git add admin-frontend/src/api.ts admin-frontend/src/usageFormat.ts admin-frontend/src/usageSeries.ts admin-frontend/tests/usage-format.spec.ts admin-frontend/tests/usage-series.spec.ts
git commit -m "feat(usage): 管理端用量数据工具——单位自适应 k/M/B、步骤/模型两棵维度树、按勾选合并或拆分折线、汇总与明细计算"
```

---

### Task 7: 管理端「模型用量」页面

**Files:**
- Modify: `admin-frontend/package.json`（新增 `echarts`、`vue-echarts`）
- Create: `admin-frontend/src/views/ModelUsageView.vue`
- Modify: `admin-frontend/src/router.ts`、`admin-frontend/src/components/AdminLayout.vue`
- Test: `admin-frontend/tests/model-usage.spec.ts`；Modify: `admin-frontend/tests/admin-layout.spec.ts`

**Interfaces:**
- Consumes: Task 6 全部导出；`api.modelUsageSeries`、`api.modelUsageUsers`

- [ ] **Step 1: 安装图表依赖**

```bash
cd admin-frontend && npm install --save-exact echarts@5 vue-echarts@7
```

Expected: `package.json` 的 `dependencies` 出现两项精确版本（如 `"echarts": "5.6.0"`、`"vue-echarts": "7.0.3"`），`package-lock.json` 同步更新。

- [ ] **Step 2: 写失败测试**

`admin-frontend/tests/model-usage.spec.ts`：

```ts
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/vue";
import { createPinia } from "pinia";
import ElementPlus from "element-plus";

const mocks = vi.hoisted(() => ({ modelUsageSeries: vi.fn(), modelUsageUsers: vi.fn() }));
vi.mock("../src/api", async original => {
  const actual = await original<typeof import("../src/api")>();
  return { ...actual, api: { ...actual.api, ...mocks } };
});
vi.mock("vue-echarts", () => ({ default: { name: "VChart", props: ["option"], template: "<div data-testid='chart'>{{ JSON.stringify(option.series.map(s => s.name)) }}</div>" } }));
vi.mock("../src/auth", () => ({ useAuth: () => ({ logout: vi.fn() }) }));
import ModelUsageView from "../src/views/ModelUsageView.vue";

const series = (step: string, stepLabel: string, model: string, input: number[]) => ({
  step, stepLabel, provider: "dashscope", model, calls: input.map(() => 1), failures: input.map(() => 0),
  inputTokens: input, outputTokens: input.map(() => 0), latencyMs: input.map(() => 10),
});
const plugins = [createPinia(), ElementPlus];

beforeEach(() => {
  mocks.modelUsageUsers.mockResolvedValue([{ userId: 7, displayName: "张三", totalTokens: 12_300 }, { userId: 0, displayName: "系统", totalTokens: 100 }]);
  mocks.modelUsageSeries.mockResolvedValue({
    bucketSeconds: 300, buckets: [Date.parse("2026-10-20T16:00:00Z"), Date.parse("2026-10-20T16:05:00Z")],
    series: [series("ANSWER", "回答生成", "qwen-plus", [12_000, 300]), series("REWRITE", "查询改写", "qwen-plus", [5, 5])],
  });
});
afterEach(() => { vi.restoreAllMocks(); localStorage.clear(); });

describe("模型用量页", () => {
  it("默认查询今天并显示自适应单位的汇总", async () => {
    render(ModelUsageView, { global: { plugins } });
    await waitFor(() => expect(mocks.modelUsageSeries).toHaveBeenCalled());
    const query = mocks.modelUsageSeries.mock.calls[0][0];
    expect(new Date(query.to).getTime() - new Date(query.from).getTime()).toBe(86_400_000);
    expect(new Date(query.from).getHours()).toBe(0);
    expect(query.tzOffsetMinutes).toBe(-new Date().getTimezoneOffset());
    await waitFor(() => expect(screen.getByTestId("total-tokens").textContent).toBe("12.3k"));
  });

  it("按步骤默认每个步骤一条线，切换按模型后按模型分线", async () => {
    render(ModelUsageView, { global: { plugins } });
    await waitFor(() => expect(screen.getByTestId("chart").textContent).toContain("回答生成"));
    expect(screen.getByTestId("chart").textContent).toContain("查询改写");
    await fireEvent.click(screen.getByRole("radio", { name: "按模型" }));
    await waitFor(() => expect(screen.getByTestId("chart").textContent).toContain("dashscope / qwen-plus"));
  });

  it("切换来源会带着来源重新查询", async () => {
    render(ModelUsageView, { global: { plugins } });
    await waitFor(() => expect(mocks.modelUsageSeries).toHaveBeenCalledTimes(1));
    await fireEvent.click(screen.getByRole("checkbox", { name: "评测" }));
    await waitFor(() => expect(mocks.modelUsageSeries).toHaveBeenCalledTimes(2));
    expect(mocks.modelUsageSeries.mock.calls[1][0].sources).toEqual(["ONLINE", "BACKGROUND"]);
  });
});
```

在 `admin-frontend/tests/admin-layout.spec.ts` 的路由数组中加入 `{ path: "/model-usage", component: { template: "<div/>" } }`，并追加断言：

```ts
    expect(screen.getByRole("link", { name: /模型用量/ }).getAttribute("href")).toContain("/model-usage");
```

（放在已有 `RAG 评测` 链接断言之后。）

- [ ] **Step 3: 运行确认失败**

Run: `cd admin-frontend && npx vitest run tests/model-usage.spec.ts tests/admin-layout.spec.ts`
Expected: FAIL，`ModelUsageView.vue` 不存在、导航无「模型用量」。

- [ ] **Step 4: 实现页面**

`admin-frontend/src/views/ModelUsageView.vue`：

```vue
<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { ElMessage, type ElTree } from "element-plus";
import VChart from "vue-echarts";
import { use } from "echarts/core";
import { CanvasRenderer } from "echarts/renderers";
import { LineChart } from "echarts/charts";
import { DataZoomComponent, GridComponent, LegendComponent, TooltipComponent } from "echarts/components";
import { api, ApiError, type UsageSeriesResponse, type UsageSourceName, type UsageUser } from "../api";
import { useAuth } from "../auth";
import { formatTokens } from "../usageFormat";
import { allLeafKeys, buildLines, buildTree, rangeFor, tableRows, totals, type GroupBy, type Metric, type RangePreset } from "../usageSeries";

use([CanvasRenderer, LineChart, GridComponent, TooltipComponent, LegendComponent, DataZoomComponent]);

const STORE_KEY = "kwiki_admin_model_usage";
const SOURCES: Array<{ value: UsageSourceName; label: string }> = [
  { value: "ONLINE", label: "线上" }, { value: "EVAL", label: "评测" }, { value: "BACKGROUND", label: "后台" }];
const METRICS: Array<{ value: Metric; label: string }> = [
  { value: "total", label: "总 token" }, { value: "input", label: "输入" }, { value: "output", label: "输出" },
  { value: "calls", label: "调用数" }, { value: "failures", label: "失败数" }];

interface Saved { preset: RangePreset | "custom"; sources: UsageSourceName[]; users: number[]; metric: Metric; groupBy: GroupBy; stacked: boolean }
const saved: Partial<Saved> = JSON.parse(localStorage.getItem(STORE_KEY) || "{}");

const preset = ref<RangePreset | "custom">(saved.preset ?? "today");
const custom = ref<[Date, Date] | null>(null);
const sources = ref<UsageSourceName[]>(saved.sources ?? SOURCES.map(s => s.value));
const users = ref<number[]>(saved.users ?? []);
const metric = ref<Metric>(saved.metric ?? "total");
const groupBy = ref<GroupBy>(saved.groupBy ?? "step");
const stacked = ref<boolean>(saved.stacked ?? false);
const loading = ref(false);
const data = ref<UsageSeriesResponse | null>(null);
const userOptions = ref<UsageUser[]>([]);
const checked = ref<Set<string>>(new Set());
const treeRef = ref<InstanceType<typeof ElTree>>();
const auth = useAuth();
let timer: ReturnType<typeof setInterval> | undefined;

const range = computed(() => preset.value === "custom" && custom.value
  ? { from: custom.value[0], to: custom.value[1] }
  : rangeFor(preset.value === "custom" ? "today" : preset.value));
const tree = computed(() => buildTree(data.value?.series ?? [], groupBy.value));
const lines = computed(() => buildLines(data.value?.series ?? [], tree.value, checked.value, groupBy.value, metric.value));
const summary = computed(() => totals(data.value?.series ?? [], checked.value));
const rows = computed(() => tableRows(buildLines(data.value?.series ?? [], tree.value, checked.value, groupBy.value, "total")));
const isTokenMetric = computed(() => metric.value === "total" || metric.value === "input" || metric.value === "output");

const option = computed(() => ({
  tooltip: { trigger: "axis", valueFormatter: (v: number) => isTokenMetric.value ? formatTokens(v) : String(v) },
  legend: { type: "scroll", top: 0 },
  grid: { left: 56, right: 24, top: 40, bottom: 56 },
  dataZoom: [{ type: "inside" }, { type: "slider", height: 18, bottom: 8 }],
  xAxis: { type: "time" },
  yAxis: { type: "value", axisLabel: { formatter: (v: number) => isTokenMetric.value ? formatTokens(v) : String(v) } },
  series: lines.value.map(line => ({
    name: line.name, type: "line", showSymbol: false, smooth: false,
    stack: stacked.value ? "total" : undefined, areaStyle: stacked.value ? {} : undefined,
    data: (data.value?.buckets ?? []).map((t, i) => [t, line.values[i] ?? 0]),
  })),
}));

function persist() {
  const value: Saved = { preset: preset.value, sources: sources.value, users: users.value, metric: metric.value, groupBy: groupBy.value, stacked: stacked.value };
  localStorage.setItem(STORE_KEY, JSON.stringify(value));
}

function handleError(error: unknown, fallback: string) {
  if (error instanceof ApiError && (error.status === 401 || error.status === 403)) { auth.logout(); return; }
  const code = error instanceof ApiError ? error.code : "";
  const messages: Record<string, string> = {
    "usage-store-unavailable": "用量存储不可用（数据库未连接）", "range-too-long": "时间范围最长 366 天", "invalid-range": "结束时间必须晚于开始时间",
  };
  ElMessage.error(messages[code] ?? fallback);
}

async function load() {
  const { from, to } = range.value;
  const query = { from: from.toISOString(), to: to.toISOString(), sources: sources.value, users: users.value, tzOffsetMinutes: -new Date().getTimezoneOffset() };
  loading.value = true;
  try {
    const [series, people] = await Promise.all([api.modelUsageSeries(query), api.modelUsageUsers(query)]);
    data.value = series;
    userOptions.value = people;
    checked.value = new Set(allLeafKeys(buildTree(series.series, groupBy.value)));
    await nextTick();
    treeRef.value?.setCheckedKeys([...checked.value]);
  } catch (error) {
    handleError(error, "加载模型用量失败");
  } finally {
    loading.value = false;
  }
}

function onCheck() {
  const keys = (treeRef.value?.getCheckedKeys(true) ?? []) as string[];
  checked.value = new Set(keys);
}

function resetTimer() {
  if (timer) clearInterval(timer);
  timer = preset.value === "today" ? setInterval(load, 60_000) : undefined;
}

watch([preset, custom, sources, users], () => { persist(); resetTimer(); void load(); }, { deep: true });
watch([metric, stacked], persist);
watch(groupBy, async () => {
  persist();
  checked.value = new Set(allLeafKeys(tree.value));
  await nextTick();
  treeRef.value?.setCheckedKeys([...checked.value]);
});

onMounted(() => { resetTimer(); void load(); });
onBeforeUnmount(() => { if (timer) clearInterval(timer); });
</script>

<template>
  <div class="usage-page">
    <header class="page-header">
      <h1>模型用量</h1>
      <p class="hint">统计对话模型与 embedding 的 token 消耗；数据每 10 秒落库一次，当天视图每分钟自动刷新。</p>
    </header>

    <section class="controls" v-loading="loading">
      <el-radio-group v-model="preset" aria-label="时间范围">
        <el-radio-button value="today">今天</el-radio-button>
        <el-radio-button value="yesterday">昨天</el-radio-button>
        <el-radio-button value="7d">近 7 天</el-radio-button>
        <el-radio-button value="30d">近 30 天</el-radio-button>
        <el-radio-button value="custom">自定义</el-radio-button>
      </el-radio-group>
      <el-date-picker v-if="preset === 'custom'" v-model="custom" type="datetimerange" range-separator="至"
                      start-placeholder="开始" end-placeholder="结束" aria-label="自定义时间" />
      <el-checkbox-group v-model="sources" aria-label="来源">
        <el-checkbox v-for="s in SOURCES" :key="s.value" :value="s.value">{{ s.label }}</el-checkbox>
      </el-checkbox-group>
      <el-select v-model="users" multiple filterable collapse-tags clearable placeholder="全部用户" style="width: 220px" aria-label="用户">
        <el-option v-for="u in userOptions" :key="u.userId" :value="u.userId" :label="`${u.displayName}（${formatTokens(u.totalTokens)}）`" />
      </el-select>
      <el-select v-model="metric" style="width: 120px" aria-label="指标">
        <el-option v-for="m in METRICS" :key="m.value" :value="m.value" :label="m.label" />
      </el-select>
      <el-switch v-model="stacked" active-text="堆叠" />
    </section>

    <section class="cards">
      <div class="card"><span>总 token</span><strong data-testid="total-tokens">{{ formatTokens(summary.total) }}</strong></div>
      <div class="card"><span>输入</span><strong>{{ formatTokens(summary.input) }}</strong></div>
      <div class="card"><span>输出</span><strong>{{ formatTokens(summary.output) }}</strong></div>
      <div class="card"><span>调用数</span><strong>{{ formatTokens(summary.calls) }}</strong></div>
      <div class="card"><span>失败率</span><strong>{{ (summary.failureRate * 100).toFixed(1) }}%</strong></div>
      <div class="card"><span>平均耗时</span><strong>{{ summary.avgLatencyMs }} ms</strong></div>
    </section>

    <section class="body">
      <aside class="dimension">
        <el-radio-group v-model="groupBy" size="small" aria-label="维度">
          <el-radio-button value="step">按步骤</el-radio-button>
          <el-radio-button value="model">按模型</el-radio-button>
        </el-radio-group>
        <el-tree ref="treeRef" :data="tree" node-key="id" show-checkbox default-expand-all
                 :props="{ label: 'label', children: 'children' }" @check="onCheck" />
        <p v-if="!tree.length" class="hint">所选范围内没有调用</p>
      </aside>
      <div class="chart-area">
        <v-chart class="chart" :option="option" autoresize />
        <el-table :data="rows" size="small" :default-sort="{ prop: 'total', order: 'descending' }">
          <el-table-column prop="name" label="维度" min-width="220" />
          <el-table-column prop="total" label="总 token" sortable><template #default="{ row }">{{ formatTokens(row.total) }}</template></el-table-column>
          <el-table-column prop="input" label="输入" sortable><template #default="{ row }">{{ formatTokens(row.input) }}</template></el-table-column>
          <el-table-column prop="output" label="输出" sortable><template #default="{ row }">{{ formatTokens(row.output) }}</template></el-table-column>
          <el-table-column prop="calls" label="调用数" sortable />
          <el-table-column prop="failures" label="失败数" sortable />
          <el-table-column prop="avgLatencyMs" label="平均耗时(ms)" sortable />
        </el-table>
      </div>
    </section>
  </div>
</template>

<style scoped>
.usage-page { display: flex; flex-direction: column; gap: 16px; }
.page-header h1 { margin: 0 0 4px; font-size: 20px; }
.hint { color: var(--el-text-color-secondary); font-size: 13px; margin: 0; }
.controls { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; }
.cards { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); gap: 12px; }
.card { border: 1px solid var(--el-border-color-light); border-radius: 8px; padding: 12px; display: flex; flex-direction: column; gap: 4px; }
.card span { color: var(--el-text-color-secondary); font-size: 12px; }
.card strong { font-size: 20px; }
.body { display: grid; grid-template-columns: 280px minmax(0, 1fr); gap: 16px; }
.dimension { border: 1px solid var(--el-border-color-light); border-radius: 8px; padding: 12px; max-height: 640px; overflow: auto; display: flex; flex-direction: column; gap: 12px; }
.chart-area { display: flex; flex-direction: column; gap: 16px; min-width: 0; }
.chart { height: 380px; }
</style>
```

先打开 `admin-frontend/src/views/RagEvalView.vue` 对照现有页面的标题、间距与样式变量写法，若与上面的 class/变量不一致，以现有页面风格为准调整样式部分。

- [ ] **Step 5: 路由与导航**

`admin-frontend/src/router.ts`：import `ModelUsageView from "./views/ModelUsageView.vue"`，并在 `{path:"/rag-eval",component:RagEvalView},` 之后加入 `{path:"/model-usage",component:ModelUsageView},`。

`admin-frontend/src/components/AdminLayout.vue`：在 RAG 评测的 `RouterLink` 之后加入：

```vue
        <RouterLink to="/model-usage" class="nav-item" active-class="active">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 19h16M6 16l4-5 3 3 5-7" /></svg>模型用量
        </RouterLink>
```

- [ ] **Step 6: 运行确认通过**

Run: `cd admin-frontend && npm run typecheck && npx vitest run && npm run build`
Expected: 全部 PASS，构建成功。若 element-plus 的 `el-radio-button`/`el-checkbox` 在当前版本使用 `label` 而非 `value` 作为取值属性，按 `RagEvalView.vue` 中的现有用法统一。

- [ ] **Step 7: 提交**

```bash
git add admin-frontend/package.json admin-frontend/package-lock.json admin-frontend/src/views/ModelUsageView.vue admin-frontend/src/router.ts admin-frontend/src/components/AdminLayout.vue admin-frontend/tests/model-usage.spec.ts admin-frontend/tests/admin-layout.spec.ts
git commit -m "feat(usage): 管理端模型用量页——时间/来源/用户/指标控制栏，按步骤或按模型的可勾选维度树，ECharts 趋势图、汇总卡片与明细表"
```

---

### Task 8: 运行说明与全量验证

**Files:**
- Create: `docs/model-usage.md`

- [ ] **Step 1: 写运行说明**

`docs/model-usage.md`：

```markdown
# 模型 token 用量监控

管理端「模型用量」页统计对话模型与 embedding 的 token 消耗。设计见
`docs/superpowers/specs/2026-10-21-model-usage-monitoring-design.md`。

## 维度

- 来源：线上（用户请求）、评测（RAG 评测，含基线检索的查询向量）、后台（索引、图谱构建等）。
- 步骤：意图路由、查询改写、Agent 规划、检索规划、直接回答、质量分析、回答生成、划词问答、图谱抽取、社区摘要、图片摘要，以及查询向量、索引向量。
- provider / 模型、用户（后台任务为「系统」）。

## 数据

- `model_usage_minute`：分钟聚合，保留 `kwiki.model-usage.retention-days`（默认 180）天。
- `model_usage_call`：逐次调用明细（含 requestId、失败类型），保留 `kwiki.model-usage.call-retention-days`（默认 7）天，便于按 requestId 排查。
- 采集先进内存队列（上限 50,000 条，满时丢弃并计 `kwiki_model_usage_dropped_total`），每 `kwiki.model-usage.flush-interval-ms`（默认 10000）毫秒落库；进程崩溃最多丢最近一个周期的数据。
- 每日 03:30（Asia/Shanghai）在分布式锁下清理过期数据。
- provider 未返回用量时 token 记 0，调用数照常统计。

## 页面

- 时间：今天（默认，每分钟自动刷新）/ 昨天 / 近 7 天 / 近 30 天 / 自定义（最长 366 天）；分桶自动选择 5 分钟、1 小时或 1 天，并按浏览器时区对齐。
- 维度树：按步骤（步骤 → 模型）或按模型（provider/模型 → 步骤）。一级节点全选时合并为一条线，部分勾选时按勾选的叶子拆线。
- 单位自适应：k / M / B。

## 排查示例

    SELECT occurred_at, step, model, input_tokens, output_tokens, status, error_type
    FROM model_usage_call WHERE request_id = '<requestId>' ORDER BY occurred_at;
```

- [ ] **Step 2: 全量测试**

Run: `./mvnw -q test`
Expected: PASS（与改动前相比无新增失败）。

Run: `cd admin-frontend && npm run typecheck && npx vitest run && npm run build`
Expected: PASS。

Run: `node tools/validate-migrations.mjs`
Expected: `validated … migrations`。

- [ ] **Step 3: 提交**

```bash
git add docs/model-usage.md
git commit -m "docs(usage): 模型 token 用量监控运行说明"
```
