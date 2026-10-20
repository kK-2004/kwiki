# Langfuse RAG 召回评测 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 kwiki 内实现基于关键词的分层 RAG 召回评测（DragonBall 中文语料，50/100/200 嵌套测试集），结果写入 Langfuse Cloud 并落本地 JSON 报告，同时提供首次丢失层归因。

**Architecture:** 新包 `com.kwiki.eval` 承载评测核心（数据集加载、关键词匹配、分层计分、归因、汇总、探针、编排、管理接口）；`com.kwiki.infrastructure.langfuse` 用 Spring `RestClient` 调 Langfuse 公共 API 实现 `EvalReportPort`；工作流通过新增的 `RunObserver` 扩展点把最终送入生成的上下文暴露给评测。设计文档：`docs/superpowers/specs/2026-10-20-langfuse-rag-eval-design.md`。

**Tech Stack:** Java 21、Spring Boot 3.5.6、Jackson、Spring `RestClient`、JUnit 5 + AssertJ + Mockito、OkHttp MockWebServer 4.12、Node.js（`tools/eval/*.mjs` 数据脚本）。

## Global Constraints

- 全部注释、Javadoc、提交信息使用简体中文；代码标识符英文。提交信息格式沿用仓库习惯：`feat(eval): …` / `test(eval): …` / `docs(eval): …`。
- **提交信息绝不添加 `Co-Authored-By` 之类的 Claude 署名尾行。**
- `src/main/resources/application.yml` 不新增任何配置；新配置一律用 `@Value("${key:默认值}")`，实际值放 Nacos。新增配置键：`kwiki.langfuse.host`（默认 `https://cloud.langfuse.com`）、`kwiki.langfuse.public-key`（默认空）、`kwiki.langfuse.secret-key`（默认空）、`kwiki.eval.concurrency`（默认 `2`）、`kwiki.eval.report-dir`（默认 `./eval-reports`）、`kwiki.eval.refusal-phrases`（默认 `无法回答,信息不足,未找到相关,没有相关信息`）。
- 管理接口统一前缀 `/api/v1/admin/eval`，类级 `@PreAuthorize("hasRole('ADMIN')")`，返回 `com.kk2004.common.response.TransDTO`。
- 业务错误用 `com.kwiki.eval.EvalException`（继承 `com.kk2004.common.exception.BusinessException`，构造 `(int code, String message)`），由现有 `ApiControllerAdvice` 统一作答。
- 检索范围始终为 `AuthorizationScopeResolver.resolve(user, Set.of(kbId), Set.of())`（管理员指定 kbId 时会收窄为非超级用户作用域），不新增权限路径。
- 不新增数据库表、不改 ES mapping、不改现有检索/生成行为；`RunObserver` 为 NOOP 时主链路行为与现在完全一致。
- DragonBall 数据许可 CC BY-NC-SA 4.0：仓库内衍生文件必须带 `NOTICE.md` 署名，仅用于内部评测。
- 运行单测：`./mvnw -q -Dtest=<类名> test`（若 `JAVA_HOME` 未设置，参考 `scripts/test-offline.sh` 中的 JDK 21 路径）。

## File Structure

| 文件 | 职责 |
|---|---|
| `src/main/java/com/kwiki/rag/orchestration/RunObserver.java` | 运行观测扩展点与事件 record |
| `src/main/java/com/kwiki/rag/orchestration/RunContext.java`（改） | 挂载 observer，`observe(Supplier)` 安全分发 |
| `src/main/java/com/kwiki/rag/orchestration/AgenticWorkflowPort.java`（改） | 新增带 `RunObserver` 的 `answerInSession` 默认方法 |
| `src/main/java/com/kwiki/infrastructure/orchestration/LangGraphAgenticWorkflow.java`（改） | 透传 observer，在子块召回/图增强/改写/生成处发事件 |
| `src/main/java/com/kwiki/eval/EvalCase.java` | 评测题 record 与校验 |
| `src/main/java/com/kwiki/eval/EvalDatasetSize.java` | 50q/100q/200q 枚举与 Langfuse 数据集名 |
| `src/main/java/com/kwiki/eval/EvalDatasetLoader.java` | 读取 jsonl、去重、按 tier 选择 |
| `src/main/java/com/kwiki/eval/KeywordMatcher.java` | 归一化与关键词组匹配 |
| `src/main/java/com/kwiki/eval/EvalLayer.java` / `LossLayer.java` | 评测层与丢失层枚举 |
| `src/main/java/com/kwiki/eval/CaseScore.java` / `LayerScorer.java` | 单题分层计分与归因 |
| `src/main/java/com/kwiki/eval/RefusalDetector.java` | 负样本拒答判定 |
| `src/main/java/com/kwiki/eval/CaseResult.java` / `LayerMetric.java` / `EvalSummary.java` / `EvalSummaries.java` | 单题结果与汇总 |
| `src/main/java/com/kwiki/eval/BaselineRetrievalProbe.java` | 原始 query 的单路/融合基线检索 |
| `src/main/java/com/kwiki/eval/WorkflowProbe.java` | 调完整 QA 工作流，收集回答/终态/上下文 |
| `src/main/java/com/kwiki/eval/EvalReportPort.java` / `EvalReportException.java` | 上报抽象 |
| `src/main/java/com/kwiki/infrastructure/langfuse/LangfuseRestClient.java` | Langfuse REST 实现 |
| `src/main/java/com/kwiki/eval/EvalException.java` | 业务错误 |
| `src/main/java/com/kwiki/eval/EvalCorpusService.java` | 语料导入与索引就绪检查 |
| `src/main/java/com/kwiki/eval/EvalDatasetSyncService.java` | jsonl → 三个 Langfuse dataset |
| `src/main/java/com/kwiki/eval/EvalReportWriter.java` | 本地 JSON 报告 |
| `src/main/java/com/kwiki/eval/EvalRunService.java` | 一次评测 run 的编排 |
| `src/main/java/com/kwiki/eval/EvalAdminController.java` | 管理接口 |
| `tools/eval/convert-dragonball.mjs` / `tools/eval/sample-dragonball.mjs` | 语料转换与分层抽样脚本 |
| `src/main/resources/eval/corpus/dragonball-zh/` | 108 篇 Markdown + `manifest.json` + `NOTICE.md` |
| `src/main/resources/eval/datasets/` | `dragonball-sample.json`（抽样中间件）、`kwiki-rag.jsonl`（测试集）、`README.md` |
| `docs/rag-eval.md` | 运维手册 |

---

### Task 1: RunObserver 扩展点与工作流埋点

**Files:**
- Create: `src/main/java/com/kwiki/rag/orchestration/RunObserver.java`
- Modify: `src/main/java/com/kwiki/rag/orchestration/RunContext.java`
- Modify: `src/main/java/com/kwiki/rag/orchestration/AgenticWorkflowPort.java`
- Modify: `src/main/java/com/kwiki/infrastructure/orchestration/LangGraphAgenticWorkflow.java`
- Test: `src/test/java/com/kwiki/rag/orchestration/RunContextObserverTest.java`
- Test: `src/test/java/com/kwiki/rag/orchestration/WorkflowRunObserverTest.java`

**Interfaces:**
- Produces:
  - `interface RunObserver { RunObserver NOOP; void onEvent(Event event); interface Event {} record RewriteObserved(int queryRound, String newQuery) implements Event; record ChildrenObserved(int queryRound, String attemptStage, List<String> chunkKeys, boolean graphEnhanced) implements Event; record ContextObserved(int queryRound, String attemptStage, String query, String context) implements Event; }`
  - `RunContext.observer(RunObserver)`、`RunContext.observe(Supplier<RunObserver.Event>)`
  - `AgenticWorkflowPort.answerInSession(CurrentUser, String, List<ChatTurn>, Set<Long>, Set<Long>, RouteInput, RunObserver)` 返回 `Flux<ChatStreamEvent>`

- [ ] **Step 1: 写 RunContext 观测失败测试**

`src/test/java/com/kwiki/rag/orchestration/RunContextObserverTest.java`：

```java
package com.kwiki.rag.orchestration;

import com.kwiki.wiki.access.AuthorizationScope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class RunContextObserverTest {

    RunContext run() {
        return new RunContext("req-1", new AuthorizationScope(1L, true, Set.of(), Map.of()),
                kb -> 0L, AgenticLimits.defaults(), Map.of());
    }

    @Test
    void noopObserverNeverBuildsEventPayload() {
        var built = new AtomicInteger();
        run().observe(() -> {
            built.incrementAndGet();
            return new RunObserver.RewriteObserved(1, "q");
        });
        assertThat(built).hasValue(0);
    }

    @Test
    void installedObserverReceivesEvents() {
        var run = run();
        List<RunObserver.Event> events = new CopyOnWriteArrayList<>();
        run.observer(events::add);
        run.observe(() -> new RunObserver.ContextObserved(0, "BASE_CHILD", "q", "上下文"));
        assertThat(events).containsExactly(new RunObserver.ContextObserved(0, "BASE_CHILD", "q", "上下文"));
    }

    @Test
    void failingObserverDoesNotPropagate() {
        var run = run();
        run.observer(event -> { throw new IllegalStateException("boom"); });
        assertThatCode(() -> run.observe(() -> new RunObserver.RewriteObserved(1, "q")))
                .doesNotThrowAnyException();
    }

    @Test
    void nullObserverFallsBackToNoop() {
        var run = run();
        run.observer(null);
        var built = new AtomicInteger();
        run.observe(() -> { built.incrementAndGet(); return new RunObserver.RewriteObserved(1, "q"); });
        assertThat(built).hasValue(0);
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./mvnw -q -Dtest=RunContextObserverTest test`
Expected: 编译失败，`RunObserver` 与 `RunContext.observer` 不存在。

- [ ] **Step 3: 实现 RunObserver 与 RunContext 挂载**

`src/main/java/com/kwiki/rag/orchestration/RunObserver.java`：

```java
package com.kwiki.rag.orchestration;

import java.util.List;

/**
 * 运行观测扩展点：工作流在关键节点发出只读事件，供评测（以及将来的线上追踪 /
 * agent 评测）采集。观测者不得修改运行状态；未安装时为 {@link #NOOP}，
 * 事件 payload 不会被构造。
 */
@FunctionalInterface
public interface RunObserver {

    RunObserver NOOP = event -> { };

    void onEvent(Event event);

    /** 观测事件的标记接口。 */
    interface Event { }

    /** 反馈改写完成：新一轮的检索问题。 */
    record RewriteObserved(int queryRound, String newQuery) implements Event { }

    /** 子块召回完成（graphEnhanced=true 表示图增强合并后的结果）。 */
    record ChildrenObserved(int queryRound, String attemptStage, List<String> chunkKeys,
                            boolean graphEnhanced) implements Event {
        public ChildrenObserved {
            chunkKeys = List.copyOf(chunkKeys);
        }
    }

    /** 即将送入生成模型的上下文全文。 */
    record ContextObserved(int queryRound, String attemptStage, String query, String context)
            implements Event { }
}
```

在 `RunContext.java` 中，紧接 `thinkingListener`/`emitThinking` 两个方法之后加入（文件已 `import java.util.*;`，另需在 import 区加 `import java.util.function.Supplier;` 与 slf4j）：

```java
    private static final org.slf4j.Logger OBSERVER_LOG =
            org.slf4j.LoggerFactory.getLogger(RunContext.class);

    private volatile RunObserver observer = RunObserver.NOOP;

    /** 安装运行观测者；null 恢复为 NOOP。 */
    public void observer(RunObserver value) {
        observer = value == null ? RunObserver.NOOP : value;
    }

    /** 观测者存在时才构造事件；观测者异常只记日志，绝不影响主链路。 */
    public void observe(Supplier<RunObserver.Event> event) {
        RunObserver current = observer;
        if (current == RunObserver.NOOP) return;
        try {
            current.onEvent(event.get());
        } catch (RuntimeException e) {
            OBSERVER_LOG.warn("run observer failed requestId={} reason={}", requestId, e.toString());
        }
    }
```

- [ ] **Step 4: 运行确认 RunContext 测试通过**

Run: `./mvnw -q -Dtest=RunContextObserverTest test`
Expected: PASS（4 个测试）。

- [ ] **Step 5: 写工作流观测失败测试**

`src/test/java/com/kwiki/rag/orchestration/WorkflowRunObserverTest.java`：

```java
package com.kwiki.rag.orchestration;

import com.kwiki.rag.answer.ChatPersistenceService;
import com.kwiki.rag.retrieval.ChunkHit;
import com.kwiki.rag.routing.KeywordRuleSet;
import com.kwiki.rag.routing.RuleFirstRouter;
import com.kwiki.security.CurrentUser;
import com.kwiki.testutil.AgenticTestSupport;
import com.kwiki.testutil.AgenticTestSupport.Harness;
import com.kwiki.testutil.StandardTestProperties;
import com.kwiki.wiki.access.AuthorizationScopeResolver;
import com.kwiki.wiki.access.ScopeVersionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** 工作流在子块召回、改写与生成处发出观测事件；观测者故障不影响回答。 */
class WorkflowRunObserverTest {
    final CurrentUser user = new CurrentUser(1L, "admin", true);
    final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    final ScopeVersionService versions = new ScopeVersionService(StandardTestProperties.nullProvider());

    Harness harness() {
        var router = new RuleFirstRouter(KeywordRuleSet.defaults(),
                StandardTestProperties.nullProvider(), false, metrics);
        var scopes = new AuthorizationScopeResolver(
                org.mockito.Mockito.mock(com.kwiki.wiki.persistence.KnowledgeBaseMemberRepository.class),
                versions,
                new com.kwiki.infrastructure.redis.ScopeCache(
                        StandardTestProperties.nullProvider(), Duration.ofSeconds(1)));
        return AgenticTestSupport.harness(router, scopes, versions,
                new ChatPersistenceService(StandardTestProperties.nullProvider()), metrics);
    }

    static ChunkHit hit(String key, String content) {
        return new ChunkHit(key, "P-" + key, 1, "PAGE", 1, 1L, "heading", 0, content.length(), content);
    }

    @Test
    void successfulRunEmitsChildrenAndContext() {
        var harness = harness();
        harness.bm25.hits = List.of(hit("C1", "候选依据 C1 部署使用容器"));
        harness.vector.hits = harness.bm25.hits;
        harness.answer.scripted.add("基于证据的回答 [P0]");
        List<RunObserver.Event> events = new CopyOnWriteArrayList<>();

        var stream = harness.workflow.answerInSession(user, "什么是部署方式", List.of(), Set.of(), Set.of(),
                RouteInput.NONE, events::add).collectList().block(Duration.ofSeconds(10));

        assertThat(stream.getLast().payloadMap()).containsEntry("outcome", "completed");
        assertThat(events).anySatisfy(event -> assertThat(event)
                .isInstanceOfSatisfying(RunObserver.ChildrenObserved.class,
                        children -> assertThat(children.chunkKeys()).contains("C1")));
        var contexts = events.stream().filter(RunObserver.ContextObserved.class::isInstance)
                .map(RunObserver.ContextObserved.class::cast).toList();
        assertThat(contexts).isNotEmpty();
        assertThat(contexts.getLast().context()).contains("候选依据 C1");
        assertThat(contexts.getLast().query()).isEqualTo("什么是部署方式");
    }

    @Test
    void feedbackRewriteEmitsRewriteEvents() {
        var harness = harness();
        harness.bm25.hits = List.of(hit("C1", "候选依据 C1"));
        harness.vector.hits = harness.bm25.hits;
        harness.quality.passes = input -> false;
        List<RunObserver.Event> events = new CopyOnWriteArrayList<>();

        harness.workflow.answerInSession(user, "什么是部署方式", List.of(), Set.of(), Set.of(),
                RouteInput.NONE, events::add).collectList().block(Duration.ofSeconds(10));

        long rewrites = events.stream().filter(RunObserver.RewriteObserved.class::isInstance).count();
        assertThat(harness.rewriter.inputs).isNotEmpty();
        // 每次成功改写恰好发一次事件；改写器可能因重试被多调用，故只断言上界
        assertThat(rewrites).isPositive().isLessThanOrEqualTo(harness.rewriter.inputs.size());
    }

    @Test
    void failingObserverDoesNotChangeTheAnswer() {
        var harness = harness();
        harness.bm25.hits = List.of(hit("C1", "候选依据 C1"));
        harness.vector.hits = harness.bm25.hits;
        harness.answer.scripted.add("基于证据的回答 [P0]");

        var stream = harness.workflow.answerInSession(user, "什么是部署方式", List.of(), Set.of(), Set.of(),
                RouteInput.NONE, event -> { throw new IllegalStateException("boom"); })
                .collectList().block(Duration.ofSeconds(10));

        assertThat(stream.getLast().payloadMap()).containsEntry("outcome", "completed");
    }
}
```

- [ ] **Step 6: 运行确认失败**

Run: `./mvnw -q -Dtest=WorkflowRunObserverTest test`
Expected: 编译失败，`answerInSession(..., RunObserver)` 不存在。

- [ ] **Step 7: 端口新增默认方法**

在 `AgenticWorkflowPort.java` 末尾（最后一个 `answerInSession` 默认方法之后）加入：

```java
    /** 带运行观测者的会话路径（评测使用）；旧适配器忽略观测者。 */
    default Flux<ChatStreamEvent> answerInSession(CurrentUser user, String query, java.util.List<ChatTurn> history,
                                                   java.util.Set<Long> kbIds, java.util.Set<Long> pageIds,
                                                   RouteInput routeInput, RunObserver observer) {
        return answerInSession(user, query, history, kbIds, pageIds, routeInput);
    }
```

- [ ] **Step 8: 工作流透传 observer 并发事件**

在 `LangGraphAgenticWorkflow.java`：

1. `streamAnswer` 签名末尾追加参数 `RunObserver observer`：
   `private Flux<ChatStreamEvent> streamAnswer(CurrentUser user, String query, List<ChatTurn> conversationHistory, boolean persistConversation, Set<Long> kbIds, Set<Long> pageIds, RouteInput routeInput, RunObserver observer)`。
2. 第 245、250、256、262 行四处现有调用在最后追加实参 `RunObserver.NOOP`。
3. 新增覆盖方法（放在第 260 行那个 `answerInSession` 之后）：

```java
    @Override
    public Flux<ChatStreamEvent> answerInSession(CurrentUser user, String query, List<ChatTurn> conversationHistory,
                                                  Set<Long> kbIds, Set<Long> pageIds, RouteInput routeInput,
                                                  RunObserver observer) {
        return streamAnswer(user, query, conversationHistory, false, kbIds, pageIds,
                routeInput == null ? RouteInput.NONE : routeInput, observer);
    }
```

4. 在 `streamAnswer` 内 `var run = new RunContext(requestId, scopes.resolve(user, kbIds, pageIds), versions::current, limits, headers);` 语句之后紧跟：

```java
                                                                run.observer(observer);
```

5. `retrieveChildren(Session s)` 中 `s.children = trimToBudget(outcome.children(), budget.childCharBudget());` 之后加入：

```java
        s.run.observe(() -> new RunObserver.ChildrenObserved(s.queryRound, s.stage.name(),
                s.children.stream().map(com.kwiki.rag.retrieval.ChildEvidence::chunkKey).toList(), false));
```

6. `applyGraphEnhancement` 中 `if (enhancement.enhanced()) { s.children = trimToBudget(...); }` 块内、赋值之后加入：

```java
            s.run.observe(() -> new RunObserver.ChildrenObserved(s.queryRound, s.stage.name(),
                    s.children.stream().map(com.kwiki.rag.retrieval.ChildEvidence::chunkKey).toList(), true));
```

7. `rewrite(Session s)` 中 `s.queryRound++;` 之后加入：

```java
        s.run.observe(() -> new RunObserver.RewriteObserved(s.queryRound, newQuery));
```

8. `generate(Session s)` 中 `String context = s.stage.isParentStage() ? parentContext(s.parentEvidence) : childContext(s.children);` 之后加入：

```java
        s.run.observe(() -> new RunObserver.ContextObserved(s.queryRound, s.stage.name(), s.currentQuery, context));
```

- [ ] **Step 9: 运行新测试与既有工作流测试**

Run: `./mvnw -q -Dtest='WorkflowRunObserverTest,RunContextObserverTest,AgenticWorkflowTest,AgenticPlannerWorkflowTest,AgenticRoutingWorkflowTest,AgenticRerouteWorkflowTest' test`
Expected: 全部 PASS。

- [ ] **Step 10: 提交**

```bash
git add src/main/java/com/kwiki/rag/orchestration/RunObserver.java src/main/java/com/kwiki/rag/orchestration/RunContext.java src/main/java/com/kwiki/rag/orchestration/AgenticWorkflowPort.java src/main/java/com/kwiki/infrastructure/orchestration/LangGraphAgenticWorkflow.java src/test/java/com/kwiki/rag/orchestration/RunContextObserverTest.java src/test/java/com/kwiki/rag/orchestration/WorkflowRunObserverTest.java
git commit -m "feat(rag): 新增运行观测扩展点 RunObserver——工作流在子块召回、图增强、改写与生成前发出只读事件，未安装时零开销"
```

---

### Task 2: 测试集模型、加载与关键词匹配

**Files:**
- Create: `src/main/java/com/kwiki/eval/EvalCase.java`
- Create: `src/main/java/com/kwiki/eval/EvalDatasetSize.java`
- Create: `src/main/java/com/kwiki/eval/EvalDatasetLoader.java`
- Create: `src/main/java/com/kwiki/eval/KeywordMatcher.java`
- Test: `src/test/java/com/kwiki/eval/KeywordMatcherTest.java`
- Test: `src/test/java/com/kwiki/eval/EvalDatasetLoaderTest.java`
- Test resource: `src/test/resources/eval/loader/valid.jsonl`、`duplicate.jsonl`、`invalid.jsonl`

**Interfaces:**
- Produces:
  - `record EvalCase(String id, String source, int tier, String category, String domain, String query, List<List<String>> keywords, boolean negative, List<Integer> sourceDocIds, List<String> keypoints)`，方法 `boolean inDataset(EvalDatasetSize size)`
  - `enum EvalDatasetSize { Q50, Q100, Q200; int size(); String label(); String datasetName(); static EvalDatasetSize parse(String label) }`，`label()` 为 `"50q"` 等，`datasetName()` 为 `"kwiki-rag-50q"` 等
  - `EvalDatasetLoader(ObjectMapper)`：`List<EvalCase> load()`（类路径 `eval/datasets/kwiki-rag.jsonl`）、`List<EvalCase> load(Resource)`、`static List<EvalCase> select(List<EvalCase>, EvalDatasetSize)`
  - `KeywordMatcher.normalize(String)`、`KeywordMatcher.matchedGroups(List<String> segments, List<List<String>> groups)` 返回 `Set<Integer>`（有序）

- [ ] **Step 1: 写 KeywordMatcher 失败测试**

```java
package com.kwiki.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KeywordMatcherTest {

    @Test
    void normalizeFoldsWidthCaseWhitespaceAndThousandsSeparators() {
        assertThat(KeywordMatcher.normalize("１０，０００ 吨")).isEqualTo("10000吨");
        assertThat(KeywordMatcher.normalize("ABC  Corp\n")).isEqualTo("abccorp");
        assertThat(KeywordMatcher.normalize("1,2 和 3")).isEqualTo("1,2和3");
        assertThat(KeywordMatcher.normalize(null)).isEmpty();
    }

    @Test
    void anyAliasInAGroupCountsAsHit() {
        var groups = List.of(List.of("五亿元", "5亿元"), List.of("2017年8月"));
        assertThat(KeywordMatcher.matchedGroups(List.of("公司减少了5亿元负债"), groups))
                .containsExactly(0);
    }

    @Test
    void matchingIsPerSegmentAndNeverAcrossSegmentBoundaries() {
        var groups = List.of(List.of("2017年8月"));
        assertThat(KeywordMatcher.matchedGroups(List.of("2017年", "8月"), groups)).isEmpty();
        assertThat(KeywordMatcher.matchedGroups(List.of("无关", "2017 年 8 月发生"), groups))
                .containsExactly(0);
    }

    @Test
    void numbersWithSeparatorsMatchPlainNumbers() {
        var groups = List.of(List.of("10000吨"));
        assertThat(KeywordMatcher.matchedGroups(List.of("排放量为10,000吨"), groups)).containsExactly(0);
    }
}
```

- [ ] **Step 2: 写 EvalDatasetLoader 失败测试与测试资源**

`src/test/resources/eval/loader/valid.jsonl`：

```json
{"id":"t-1","source":"dragonball","tier":50,"category":"事实性问题","domain":"Finance","query":"绿源环保2017年的二氧化碳排放量是多少？","keywords":[["10,000吨","1万吨"]],"negative":false,"sourceDocIds":[5],"keypoints":["排放量为10,000吨"]}

{"id":"t-2","source":"dragonball","tier":100,"category":"无关无解问","domain":"Finance","query":"绿源环保在2017年12月设立了什么子公司？","keywords":[],"negative":true,"sourceDocIds":[5],"keypoints":["无法回答"]}
{"id":"t-3","source":"dragonball","tier":200,"category":"多跳推理问题","domain":"Law","query":"甲案判决依据是什么？","keywords":[["第一条"],["罚款"]],"negative":false,"sourceDocIds":[50],"keypoints":[]}
```

`duplicate.jsonl`：

```json
{"id":"t-1","tier":50,"query":"问题一","keywords":[["a"]],"negative":false}
{"id":"t-1","tier":50,"query":"问题二","keywords":[["b"]],"negative":false}
```

`invalid.jsonl`：

```json
{"id":"t-9","tier":50,"query":"负样本却带关键词","keywords":[["a"]],"negative":true}
```

`src/test/java/com/kwiki/eval/EvalDatasetLoaderTest.java`：

```java
package com.kwiki.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvalDatasetLoaderTest {
    final EvalDatasetLoader loader = new EvalDatasetLoader(new ObjectMapper());

    @Test
    void loadsCasesSkippingBlankLinesAndSelectsNestedTiers() {
        var cases = loader.load(new ClassPathResource("eval/loader/valid.jsonl"));
        assertThat(cases).extracting(EvalCase::id).containsExactly("t-1", "t-2", "t-3");
        assertThat(EvalDatasetLoader.select(cases, EvalDatasetSize.Q50)).extracting(EvalCase::id)
                .containsExactly("t-1");
        assertThat(EvalDatasetLoader.select(cases, EvalDatasetSize.Q100)).extracting(EvalCase::id)
                .containsExactly("t-1", "t-2");
        assertThat(EvalDatasetLoader.select(cases, EvalDatasetSize.Q200)).hasSize(3);
        assertThat(cases.get(0).keywords()).containsExactly(java.util.List.of("10,000吨", "1万吨"));
    }

    @Test
    void duplicateIdsAreRejected() {
        assertThatThrownBy(() -> loader.load(new ClassPathResource("eval/loader/duplicate.jsonl")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate eval case id: t-1");
    }

    @Test
    void invalidCaseReportsLineNumber() {
        assertThatThrownBy(() -> loader.load(new ClassPathResource("eval/loader/invalid.jsonl")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("line 1");
    }

    @Test
    void datasetSizeLabelsAndNames() {
        assertThat(EvalDatasetSize.parse("100q")).isEqualTo(EvalDatasetSize.Q100);
        assertThat(EvalDatasetSize.Q200.datasetName()).isEqualTo("kwiki-rag-200q");
        assertThatThrownBy(() -> EvalDatasetSize.parse("300q")).isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 3: 运行确认失败**

Run: `./mvnw -q -Dtest='KeywordMatcherTest,EvalDatasetLoaderTest' test`
Expected: 编译失败，`com.kwiki.eval` 类不存在。

- [ ] **Step 4: 实现**

`EvalDatasetSize.java`：

```java
package com.kwiki.eval;

/** 三个嵌套测试集：tier ≤ size 的题都属于该集合。 */
public enum EvalDatasetSize {
    Q50(50), Q100(100), Q200(200);

    private final int size;

    EvalDatasetSize(int size) {
        this.size = size;
    }

    public int size() {
        return size;
    }

    public String label() {
        return size + "q";
    }

    /** Langfuse 中的数据集名。 */
    public String datasetName() {
        return "kwiki-rag-" + label();
    }

    public static EvalDatasetSize parse(String label) {
        for (EvalDatasetSize value : values()) {
            if (value.label().equalsIgnoreCase(label)) return value;
        }
        throw new IllegalArgumentException("unknown eval dataset: " + label);
    }
}
```

`EvalCase.java`：

```java
package com.kwiki.eval;

import java.util.List;
import java.util.Set;

/**
 * 一道评测题。keywords 是关键词组列表，组内为同义别名，命中任一别名即该组命中；
 * negative=true 表示无关无解问，必须没有关键词。tier 为该题首次纳入的层级。
 */
public record EvalCase(
        String id,
        String source,
        int tier,
        String category,
        String domain,
        String query,
        List<List<String>> keywords,
        boolean negative,
        List<Integer> sourceDocIds,
        List<String> keypoints) {

    private static final Set<Integer> TIERS = Set.of(50, 100, 200);

    public EvalCase {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("eval case id is blank");
        if (query == null || query.isBlank()) throw new IllegalArgumentException("eval case " + id + " query is blank");
        if (!TIERS.contains(tier)) throw new IllegalArgumentException("eval case " + id + " tier must be 50/100/200");
        keywords = keywords == null ? List.of() : keywords.stream().map(List::copyOf).toList();
        if (negative && !keywords.isEmpty()) {
            throw new IllegalArgumentException("negative eval case " + id + " must not have keywords");
        }
        if (!negative && (keywords.isEmpty() || keywords.size() > 6)) {
            throw new IllegalArgumentException("eval case " + id + " needs 1-6 keyword groups");
        }
        for (List<String> group : keywords) {
            if (group.isEmpty() || group.stream().anyMatch(alias -> alias == null || alias.isBlank())) {
                throw new IllegalArgumentException("eval case " + id + " has a blank keyword alias");
            }
        }
        source = source == null || source.isBlank() ? "dragonball" : source;
        category = category == null ? "" : category;
        domain = domain == null ? "" : domain;
        sourceDocIds = sourceDocIds == null ? List.of() : List.copyOf(sourceDocIds);
        keypoints = keypoints == null ? List.of() : List.copyOf(keypoints);
    }

    public boolean inDataset(EvalDatasetSize size) {
        return tier <= size.size();
    }
}
```

`EvalDatasetLoader.java`：

```java
package com.kwiki.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 读取仓库内唯一事实来源的 jsonl 测试集；id 必须唯一。 */
@Component
public class EvalDatasetLoader {

    public static final String DEFAULT_RESOURCE = "eval/datasets/kwiki-rag.jsonl";

    private final ObjectMapper mapper;

    public EvalDatasetLoader(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public List<EvalCase> load() {
        return load(new ClassPathResource(DEFAULT_RESOURCE));
    }

    public List<EvalCase> load(Resource resource) {
        List<EvalCase> cases = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        try (var reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            int lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (line.isBlank()) continue;
                EvalCase evalCase;
                try {
                    evalCase = mapper.readValue(line, EvalCase.class);
                } catch (IOException | IllegalArgumentException e) {
                    throw new IllegalArgumentException(
                            "invalid eval case at line " + lineNo + ": " + e.getMessage(), e);
                }
                if (!ids.add(evalCase.id())) {
                    throw new IllegalArgumentException("duplicate eval case id: " + evalCase.id());
                }
                cases.add(evalCase);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read eval dataset " + resource, e);
        }
        return List.copyOf(cases);
    }

    public static List<EvalCase> select(List<EvalCase> all, EvalDatasetSize size) {
        return all.stream().filter(evalCase -> evalCase.inDataset(size)).toList();
    }
}
```

`KeywordMatcher.java`：

```java
package com.kwiki.eval;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * 关键词匹配：文本与关键词统一做 NFKC、小写、去空白、去千分位逗号后做子串匹配。
 * 每个片段（chunk/上下文/回答）单独匹配，绝不跨片段拼接，避免边界处的假命中。
 */
public final class KeywordMatcher {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern THOUSANDS = Pattern.compile("(?<=\\d),(?=\\d{3})");

    private KeywordMatcher() {
    }

    public static String normalize(String text) {
        if (text == null || text.isEmpty()) return "";
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        normalized = WHITESPACE.matcher(normalized).replaceAll("");
        return THOUSANDS.matcher(normalized).replaceAll("");
    }

    /** 返回命中的关键词组下标（升序）。 */
    public static Set<Integer> matchedGroups(List<String> segments, List<List<String>> groups) {
        List<String> normalized = segments.stream().map(KeywordMatcher::normalize).toList();
        Set<Integer> matched = new TreeSet<>();
        for (int i = 0; i < groups.size(); i++) {
            for (String alias : groups.get(i)) {
                String target = normalize(alias);
                if (!target.isEmpty() && normalized.stream().anyMatch(text -> text.contains(target))) {
                    matched.add(i);
                    break;
                }
            }
        }
        return matched;
    }
}
```

- [ ] **Step 5: 运行确认通过**

Run: `./mvnw -q -Dtest='KeywordMatcherTest,EvalDatasetLoaderTest' test`
Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add src/main/java/com/kwiki/eval src/test/java/com/kwiki/eval src/test/resources/eval/loader
git commit -m "feat(eval): 评测题模型、jsonl 测试集加载与按 tier 嵌套选择，关键词组按片段归一化匹配"
```

---

### Task 3: 分层计分、首次丢失层归因与汇总

**Files:**
- Create: `src/main/java/com/kwiki/eval/EvalLayer.java`、`LossLayer.java`、`CaseScore.java`、`LayerScorer.java`、`RefusalDetector.java`、`CaseResult.java`、`LayerMetric.java`、`EvalSummary.java`、`EvalSummaries.java`
- Test: `src/test/java/com/kwiki/eval/LayerScorerTest.java`、`RefusalDetectorTest.java`、`EvalSummariesTest.java`

**Interfaces:**
- Consumes: `KeywordMatcher.matchedGroups`、`EvalCase`（Task 2）
- Produces:
  - `enum EvalLayer { BM25_50, VECTOR_50, POOL_50, FUSED_8, FUSED_20, CONTEXT, ANSWER; String metric(); }`，metric 依次为 `bm25@50`、`vector@50`、`pool@50`、`fused@8`、`fused@20`、`context`、`answer`
  - `enum LossLayer { GENERATION_MISS, RECALL_MISS, RANK_MISS_20, RANK_MISS_8, CONTEXT_MISS }`
  - `record CaseScore(Map<EvalLayer, Set<Integer>> matched, int groupCount, Map<Integer, LossLayer> losses, Set<Integer> contextGains)`，方法 `Set<Integer> matched(EvalLayer)`、`double coverage(EvalLayer)`、`boolean fullHit(EvalLayer)`
  - `LayerScorer.score(List<List<String>> keywords, Map<EvalLayer, List<String>> segments)` → `CaseScore`（调用方不传 `POOL_50`，由 BM25 与向量片段合并得到）
  - `RefusalDetector(List<String> phrases)`：`boolean refused(String outcome, String answer)`
  - `record CaseResult(EvalCase evalCase, CaseScore score, Boolean refusedCorrectly, String outcome, String answer, List<String> rewrites, Map<EvalLayer, List<String>> chunkKeys, String errorCode, long elapsedMs)`，方法 `boolean error()`
  - `record LayerMetric(double coverage, double fullHit, int cases)`
  - `record EvalSummary(int total, int errors, int scored, Map<String, LayerMetric> overall, Map<String, Map<String, LayerMetric>> byCategory, Map<String, Map<String, LayerMetric>> byDomain, Map<String, Map<String, LayerMetric>> bySource, int negatives, double refusalAccuracy, Map<String, Map<String, Integer>> lossMatrix, int contextGains)`
  - `EvalSummaries.summarize(List<CaseResult>)` → `EvalSummary`

- [ ] **Step 1: 写失败测试**

`LayerScorerTest.java`：

```java
package com.kwiki.eval;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LayerScorerTest {

    // 五个关键词组，分别设计成落在不同丢失层
    static final List<List<String>> KEYWORDS = List.of(
            List.of("甲"),   // 0：答案命中
            List.of("乙"),   // 1：上下文有、答案无 → GENERATION_MISS
            List.of("丙"),   // 2：哪里都没有 → RECALL_MISS
            List.of("丁"),   // 3：只在 BM25 池 → RANK_MISS_20
            List.of("戊"));  // 4：在 fused@20 不在 fused@8 → RANK_MISS_8

    static Map<EvalLayer, List<String>> segments() {
        Map<EvalLayer, List<String>> segments = new EnumMap<>(EvalLayer.class);
        segments.put(EvalLayer.BM25_50, List.of("甲", "丁"));
        segments.put(EvalLayer.VECTOR_50, List.of("戊"));
        segments.put(EvalLayer.FUSED_8, List.of("甲"));
        segments.put(EvalLayer.FUSED_20, List.of("甲", "戊"));
        segments.put(EvalLayer.CONTEXT, List.of("甲 乙"));
        segments.put(EvalLayer.ANSWER, List.of("答案提到甲"));
        return segments;
    }

    @Test
    void poolIsTheUnionOfBothBranches() {
        var score = LayerScorer.score(KEYWORDS, segments());
        assertThat(score.matched(EvalLayer.POOL_50)).containsExactly(0, 3, 4);
    }

    @Test
    void lossLayersFollowTheSpecOrder() {
        var score = LayerScorer.score(KEYWORDS, segments());
        assertThat(score.losses()).containsExactlyInAnyOrderEntriesOf(Map.of(
                1, LossLayer.GENERATION_MISS,
                2, LossLayer.RECALL_MISS,
                3, LossLayer.RANK_MISS_20,
                4, LossLayer.RANK_MISS_8));
    }

    @Test
    void inFused8ButNotInContextIsContextMiss() {
        Map<EvalLayer, List<String>> segments = new EnumMap<>(EvalLayer.class);
        segments.put(EvalLayer.BM25_50, List.of("甲"));
        segments.put(EvalLayer.FUSED_8, List.of("甲"));
        segments.put(EvalLayer.FUSED_20, List.of("甲"));
        var score = LayerScorer.score(List.of(List.of("甲")), segments);
        assertThat(score.losses()).containsEntry(0, LossLayer.CONTEXT_MISS);
    }

    @Test
    void contextGainIsCountedWhenContextHasWhatThePoolMissed() {
        var score = LayerScorer.score(KEYWORDS, segments());
        assertThat(score.contextGains()).containsExactly(1);
    }

    @Test
    void coverageAndFullHit() {
        var score = LayerScorer.score(KEYWORDS, segments());
        assertThat(score.coverage(EvalLayer.POOL_50)).isEqualTo(0.6);
        assertThat(score.fullHit(EvalLayer.POOL_50)).isFalse();
        var full = LayerScorer.score(List.of(List.of("甲")), segments());
        assertThat(full.fullHit(EvalLayer.ANSWER)).isTrue();
    }
}
```

`RefusalDetectorTest.java`：

```java
package com.kwiki.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RefusalDetectorTest {
    final RefusalDetector detector = new RefusalDetector(List.of("无法回答", "没有相关信息"));

    @Test
    void insufficientOutcomeIsARefusal() {
        assertThat(detector.refused("insufficient", "")).isTrue();
    }

    @Test
    void refusalPhraseInAnswerIsARefusal() {
        assertThat(detector.refused("completed", "知识库中没有相关 信息，我无法进行回答")).isTrue();
    }

    @Test
    void ordinaryAnswerIsNotARefusal() {
        assertThat(detector.refused("completed", "子公司为绿源新能源")).isFalse();
        assertThat(detector.refused(null, null)).isFalse();
    }
}
```

`EvalSummariesTest.java`：

```java
package com.kwiki.eval;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvalSummariesTest {

    static EvalCase positive(String id, String category, String domain) {
        return new EvalCase(id, "dragonball", 50, category, domain, "问题" + id,
                List.of(List.of("甲"), List.of("乙")), false, List.of(1), List.of());
    }

    static EvalCase negative(String id) {
        return new EvalCase(id, "dragonball", 50, "无关无解问", "Finance", "无解" + id,
                List.of(), true, List.of(1), List.of());
    }

    static CaseResult scored(EvalCase evalCase, List<String> answer) {
        Map<EvalLayer, List<String>> segments = new EnumMap<>(EvalLayer.class);
        segments.put(EvalLayer.BM25_50, List.of("甲乙"));
        segments.put(EvalLayer.CONTEXT, List.of("甲乙"));
        segments.put(EvalLayer.ANSWER, answer);
        return new CaseResult(evalCase, LayerScorer.score(evalCase.keywords(), segments), null,
                "completed", String.join("", answer), List.of(), Map.of(), null, 10);
    }

    @Test
    void summarizesLayersRefusalsLossesAndErrors() {
        var results = List.of(
                scored(positive("a", "事实性问题", "Finance"), List.of("甲乙")),
                scored(positive("b", "多跳推理问题", "Law"), List.of("甲")),
                new CaseResult(negative("n"), null, true, "insufficient", "", List.of(), Map.of(), null, 5),
                new CaseResult(positive("e", "事实性问题", "Finance"), null, null, null, "", List.of(),
                        Map.of(), "timeout", 7));

        EvalSummary summary = EvalSummaries.summarize(results);

        assertThat(summary.total()).isEqualTo(4);
        assertThat(summary.errors()).isEqualTo(1);
        assertThat(summary.scored()).isEqualTo(2);
        assertThat(summary.overall().get("answer")).isEqualTo(new LayerMetric(0.75, 0.5, 2));
        assertThat(summary.overall().get("pool@50")).isEqualTo(new LayerMetric(1.0, 1.0, 2));
        assertThat(summary.byCategory().get("多跳推理问题").get("answer"))
                .isEqualTo(new LayerMetric(0.5, 0.0, 1));
        assertThat(summary.byDomain()).containsKeys("Finance", "Law");
        assertThat(summary.bySource()).containsKey("dragonball");
        assertThat(summary.negatives()).isEqualTo(1);
        assertThat(summary.refusalAccuracy()).isEqualTo(1.0);
        assertThat(summary.lossMatrix().get("多跳推理问题")).containsEntry("GENERATION_MISS", 1);
        assertThat(summary.contextGains()).isZero();
    }

    @Test
    void emptyResultsProduceZeroMetrics() {
        var summary = EvalSummaries.summarize(List.of());
        assertThat(summary.overall().get("answer")).isEqualTo(new LayerMetric(0, 0, 0));
        assertThat(summary.refusalAccuracy()).isZero();
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./mvnw -q -Dtest='LayerScorerTest,RefusalDetectorTest,EvalSummariesTest' test`
Expected: 编译失败。

- [ ] **Step 3: 实现**

`EvalLayer.java`：

```java
package com.kwiki.eval;

/** 评测分层：从单路候选池到最终回答。 */
public enum EvalLayer {
    BM25_50("bm25@50"),
    VECTOR_50("vector@50"),
    POOL_50("pool@50"),
    FUSED_8("fused@8"),
    FUSED_20("fused@20"),
    CONTEXT("context"),
    ANSWER("answer");

    private final String metric;

    EvalLayer(String metric) {
        this.metric = metric;
    }

    public String metric() {
        return metric;
    }
}
```

`LossLayer.java`：

```java
package com.kwiki.eval;

/** 漏召关键词组的首次丢失位置。 */
public enum LossLayer {
    /** 在上下文中，但回答没写出来。 */
    GENERATION_MISS,
    /** 两路 Top-50 都没搜到（分词/嵌入/切块问题）。 */
    RECALL_MISS,
    /** 在候选池中，但没进扩大阶段 Top-20。 */
    RANK_MISS_20,
    /** 在 Top-20 中，但没进基础阶段 Top-8。 */
    RANK_MISS_8,
    /** 在基础 Top-8 中，却没进最终上下文（阶梯、父块裁剪或预算）。 */
    CONTEXT_MISS
}
```

`CaseScore.java`：

```java
package com.kwiki.eval;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 单题各层命中的关键词组、丢失归因与上下文补回。 */
public record CaseScore(
        Map<EvalLayer, Set<Integer>> matched,
        int groupCount,
        Map<Integer, LossLayer> losses,
        Set<Integer> contextGains) {

    public CaseScore {
        matched = matched.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> Set.copyOf(entry.getValue())));
        losses = Map.copyOf(losses);
        contextGains = Set.copyOf(contextGains);
    }

    public Set<Integer> matched(EvalLayer layer) {
        return matched.getOrDefault(layer, Set.of());
    }

    public double coverage(EvalLayer layer) {
        return groupCount == 0 ? 0.0 : matched(layer).size() / (double) groupCount;
    }

    public boolean fullHit(EvalLayer layer) {
        return groupCount > 0 && matched(layer).size() == groupCount;
    }
}
```

`LayerScorer.java`：

```java
package com.kwiki.eval;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 单题分层计分。POOL_50 由 BM25 与向量片段合并得到；未在回答命中的关键词组按
 * GENERATION_MISS → RECALL_MISS → RANK_MISS_20 → RANK_MISS_8 → CONTEXT_MISS 归因。
 */
public final class LayerScorer {

    private LayerScorer() {
    }

    public static CaseScore score(List<List<String>> keywords, Map<EvalLayer, List<String>> segments) {
        Map<EvalLayer, Set<Integer>> matched = new EnumMap<>(EvalLayer.class);
        for (EvalLayer layer : EvalLayer.values()) {
            List<String> texts = layer == EvalLayer.POOL_50
                    ? union(segments.get(EvalLayer.BM25_50), segments.get(EvalLayer.VECTOR_50))
                    : segments.getOrDefault(layer, List.of());
            matched.put(layer, KeywordMatcher.matchedGroups(texts, keywords));
        }
        Map<Integer, LossLayer> losses = new TreeMap<>();
        Set<Integer> gains = new TreeSet<>();
        for (int group = 0; group < keywords.size(); group++) {
            if (matched.get(EvalLayer.CONTEXT).contains(group)
                    && !matched.get(EvalLayer.POOL_50).contains(group)) {
                gains.add(group);
            }
            if (!matched.get(EvalLayer.ANSWER).contains(group)) {
                losses.put(group, attribute(group, matched));
            }
        }
        return new CaseScore(matched, keywords.size(), losses, gains);
    }

    static LossLayer attribute(int group, Map<EvalLayer, Set<Integer>> matched) {
        if (matched.get(EvalLayer.CONTEXT).contains(group)) return LossLayer.GENERATION_MISS;
        if (!matched.get(EvalLayer.POOL_50).contains(group)) return LossLayer.RECALL_MISS;
        if (!matched.get(EvalLayer.FUSED_20).contains(group)) return LossLayer.RANK_MISS_20;
        if (!matched.get(EvalLayer.FUSED_8).contains(group)) return LossLayer.RANK_MISS_8;
        return LossLayer.CONTEXT_MISS;
    }

    private static List<String> union(List<String> first, List<String> second) {
        List<String> all = new ArrayList<>(first == null ? List.of() : first);
        all.addAll(second == null ? List.of() : second);
        return all;
    }
}
```

`RefusalDetector.java`：

```java
package com.kwiki.eval;

import com.kwiki.rag.orchestration.AgenticErrorCodes;

import java.util.List;

/** 负样本拒答判定：终态为证据不足，或回答含任一拒答短语（归一化后匹配）。 */
public final class RefusalDetector {

    private final List<String> phrases;

    public RefusalDetector(List<String> phrases) {
        this.phrases = phrases.stream().map(KeywordMatcher::normalize)
                .filter(phrase -> !phrase.isEmpty()).toList();
    }

    public boolean refused(String outcome, String answer) {
        if (AgenticErrorCodes.INSUFFICIENT.equals(outcome)) return true;
        String normalized = KeywordMatcher.normalize(answer);
        return phrases.stream().anyMatch(normalized::contains);
    }
}
```

`CaseResult.java`：

```java
package com.kwiki.eval;

import java.util.List;
import java.util.Map;

/**
 * 单题评测结果。score 仅对非负样本存在；refusedCorrectly 仅对负样本存在；
 * errorCode 非空表示该题执行失败，不计入召回分母。
 */
public record CaseResult(
        EvalCase evalCase,
        CaseScore score,
        Boolean refusedCorrectly,
        String outcome,
        String answer,
        List<String> rewrites,
        Map<EvalLayer, List<String>> chunkKeys,
        String errorCode,
        long elapsedMs) {

    public CaseResult {
        rewrites = rewrites == null ? List.of() : List.copyOf(rewrites);
        chunkKeys = chunkKeys == null ? Map.of() : Map.copyOf(chunkKeys);
        answer = answer == null ? "" : answer;
    }

    public boolean error() {
        return errorCode != null;
    }
}
```

`LayerMetric.java`：

```java
package com.kwiki.eval;

/** 某一层的关键词覆盖率均值、完全命中率与参与题数。 */
public record LayerMetric(double coverage, double fullHit, int cases) {
}
```

`EvalSummary.java`：

```java
package com.kwiki.eval;

import java.util.Map;

/** 一次评测 run 的汇总；指标键为 {@link EvalLayer#metric()}。 */
public record EvalSummary(
        int total,
        int errors,
        int scored,
        Map<String, LayerMetric> overall,
        Map<String, Map<String, LayerMetric>> byCategory,
        Map<String, Map<String, LayerMetric>> byDomain,
        Map<String, Map<String, LayerMetric>> bySource,
        int negatives,
        double refusalAccuracy,
        Map<String, Map<String, Integer>> lossMatrix,
        int contextGains) {
}
```

`EvalSummaries.java`：

```java
package com.kwiki.eval;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 把单题结果聚合为分层、分组指标与丢失层矩阵。 */
public final class EvalSummaries {

    private EvalSummaries() {
    }

    public static EvalSummary summarize(List<CaseResult> results) {
        List<CaseResult> scored = results.stream()
                .filter(result -> !result.error() && result.score() != null).toList();
        List<CaseResult> negatives = results.stream()
                .filter(result -> !result.error() && result.evalCase().negative()).toList();
        int errors = (int) results.stream().filter(CaseResult::error).count();
        double refusal = negatives.isEmpty() ? 0.0
                : negatives.stream().filter(result -> Boolean.TRUE.equals(result.refusedCorrectly())).count()
                        / (double) negatives.size();
        Map<String, Map<String, Integer>> lossMatrix = new TreeMap<>();
        int gains = 0;
        for (CaseResult result : scored) {
            Map<String, Integer> row = lossMatrix.computeIfAbsent(
                    result.evalCase().category(), ignored -> new TreeMap<>());
            for (LossLayer loss : result.score().losses().values()) {
                row.merge(loss.name(), 1, Integer::sum);
            }
            gains += result.score().contextGains().size();
        }
        return new EvalSummary(results.size(), errors, scored.size(), metrics(scored),
                grouped(scored, result -> result.evalCase().category()),
                grouped(scored, result -> result.evalCase().domain()),
                grouped(scored, result -> result.evalCase().source()),
                negatives.size(), refusal, lossMatrix, gains);
    }

    private static Map<String, Map<String, LayerMetric>> grouped(
            List<CaseResult> scored, Function<CaseResult, String> key) {
        Map<String, List<CaseResult>> groups = scored.stream()
                .collect(Collectors.groupingBy(key, TreeMap::new, Collectors.toList()));
        Map<String, Map<String, LayerMetric>> result = new TreeMap<>();
        groups.forEach((name, members) -> result.put(name, metrics(members)));
        return result;
    }

    private static Map<String, LayerMetric> metrics(List<CaseResult> scored) {
        Map<String, LayerMetric> metrics = new LinkedHashMap<>();
        for (EvalLayer layer : EvalLayer.values()) {
            if (scored.isEmpty()) {
                metrics.put(layer.metric(), new LayerMetric(0, 0, 0));
                continue;
            }
            double coverage = scored.stream().mapToDouble(result -> result.score().coverage(layer))
                    .average().orElse(0);
            double fullHit = scored.stream().filter(result -> result.score().fullHit(layer)).count()
                    / (double) scored.size();
            metrics.put(layer.metric(), new LayerMetric(coverage, fullHit, scored.size()));
        }
        return metrics;
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `./mvnw -q -Dtest='LayerScorerTest,RefusalDetectorTest,EvalSummariesTest' test`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/kwiki/eval src/test/java/com/kwiki/eval
git commit -m "feat(eval): 分层关键词计分、首次丢失层归因、负样本拒答判定与按题型/领域/来源汇总"
```

---

### Task 4: 基线检索探针与工作流探针

**Files:**
- Create: `src/main/java/com/kwiki/eval/BaselineRetrievalProbe.java`
- Create: `src/main/java/com/kwiki/eval/WorkflowProbe.java`
- Test: `src/test/java/com/kwiki/eval/BaselineRetrievalProbeTest.java`
- Test: `src/test/java/com/kwiki/eval/WorkflowProbeTest.java`

**Interfaces:**
- Consumes: `RunObserver`、`AgenticWorkflowPort.answerInSession(..., RunObserver)`（Task 1）；`EvalLayer`（Task 3）；现有 `ConcurrentRecallService.recall(String, float[], ScopeFilter, int, long, RetrievalStrategy)`、`StandardRrfFusion.fuse(List<RankedList>, int)`、`QaRetrievalBudgets`（公开字段 `baseBranchTopK`、`baseFinalTopK`、`expandedBranchTopK`、`expandedFinalTopK`）、`ChunkEmbeddingPort.embed(List<String>)`
- Produces:
  - `BaselineRetrievalProbe(ChunkEmbeddingPort, ConcurrentRecallService, QaRetrievalBudgets)`：`Result probe(String query, ScopeFilter filter)`；`record Result(List<ChunkHit> bm25, List<ChunkHit> vector, List<ChunkHit> fused8, List<ChunkHit> fused20, List<String> degradations)`，方法 `Map<EvalLayer, List<ChunkHit>> byLayer()`（键为 BM25_50、VECTOR_50、FUSED_8、FUSED_20）
  - `WorkflowProbe(AgenticWorkflowPort)`：`Result run(CurrentUser user, long kbId, String query, Duration timeout)`；`record Result(String outcome, String errorCode, String answer, String context, List<String> rewrites)`

- [ ] **Step 1: 写失败测试**

`BaselineRetrievalProbeTest.java`：

```java
package com.kwiki.eval;

import com.kwiki.indexing.pipeline.ChunkEmbeddingPort;
import com.kwiki.rag.retrieval.ChildRecallPort;
import com.kwiki.rag.retrieval.ChunkHit;
import com.kwiki.rag.retrieval.ConcurrentRecallService;
import com.kwiki.rag.retrieval.QaRetrievalBudgets;
import com.kwiki.rag.retrieval.ScopeFilter;
import com.kwiki.testutil.AgenticTestSupport.ScriptRecall;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class BaselineRetrievalProbeTest {

    final QaRetrievalBudgets budgets = new QaRetrievalBudgets(20, 8, 50, 20, 8, 16, 12000, 24000, 24000);

    static List<ChunkHit> hits(String prefix, int count) {
        return IntStream.range(0, count).mapToObj(i -> new ChunkHit(prefix + i, "P" + prefix + i, 7, "PAGE",
                i, 1L, "heading", 0, 4, "内容" + prefix + i)).toList();
    }

    static ChunkEmbeddingPort embeddings(boolean fail) {
        return new ChunkEmbeddingPort() {
            @Override
            public List<float[]> embed(List<String> texts) {
                if (fail) throw new IllegalStateException("embedding down");
                return texts.stream().map(text -> new float[4]).toList();
            }

            @Override
            public String cacheIdentity() {
                return "test";
            }
        };
    }

    @Test
    void probesBaseAndExpandedBudgetsLikeTheQaLadder() {
        var bm25 = new ScriptRecall(ChildRecallPort.Branch.BM25);
        var vector = new ScriptRecall(ChildRecallPort.Branch.VECTOR);
        bm25.hits = hits("B", 60);
        vector.hits = hits("V", 60);
        var probe = new BaselineRetrievalProbe(embeddings(false), new ConcurrentRecallService(bm25, vector), budgets);

        var result = probe.probe("部署方式", new ScopeFilter(false, List.of(7L)));

        assertThat(bm25.topKs).containsExactlyInAnyOrder(20, 50);
        assertThat(result.bm25()).hasSize(50);
        assertThat(result.vector()).hasSize(50);
        assertThat(result.fused8()).hasSize(8);
        assertThat(result.fused20()).hasSize(20);
        assertThat(result.fused8().getFirst().chunkKey()).isIn("B0", "V0");
        assertThat(result.byLayer()).containsOnlyKeys(EvalLayer.BM25_50, EvalLayer.VECTOR_50,
                EvalLayer.FUSED_8, EvalLayer.FUSED_20);
        assertThat(result.degradations()).isEmpty();
    }

    @Test
    void embeddingFailureDegradesToBm25Only() {
        var bm25 = new ScriptRecall(ChildRecallPort.Branch.BM25);
        var vector = new ScriptRecall(ChildRecallPort.Branch.VECTOR);
        bm25.hits = hits("B", 30);
        vector.hits = hits("V", 30);
        var probe = new BaselineRetrievalProbe(embeddings(true), new ConcurrentRecallService(bm25, vector), budgets);

        var result = probe.probe("部署方式", new ScopeFilter(false, List.of(7L)));

        assertThat(result.bm25()).hasSize(30);
        assertThat(result.vector()).isEmpty();
        assertThat(vector.queries).isEmpty();
        assertThat(result.degradations()).contains("vector-unavailable");
    }
}
```

`WorkflowProbeTest.java`：

```java
package com.kwiki.eval;

import com.kwiki.rag.answer.ChatPersistenceService;
import com.kwiki.rag.retrieval.ChunkHit;
import com.kwiki.rag.routing.KeywordRuleSet;
import com.kwiki.rag.routing.RuleFirstRouter;
import com.kwiki.security.CurrentUser;
import com.kwiki.testutil.AgenticTestSupport;
import com.kwiki.testutil.StandardTestProperties;
import com.kwiki.wiki.access.AuthorizationScopeResolver;
import com.kwiki.wiki.access.ScopeVersionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowProbeTest {
    final CurrentUser user = new CurrentUser(1L, "admin", true);
    final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    final ScopeVersionService versions = new ScopeVersionService(StandardTestProperties.nullProvider());

    AgenticTestSupport.Harness harness() {
        var router = new RuleFirstRouter(KeywordRuleSet.defaults(), StandardTestProperties.nullProvider(), false, metrics);
        var scopes = new AuthorizationScopeResolver(
                org.mockito.Mockito.mock(com.kwiki.wiki.persistence.KnowledgeBaseMemberRepository.class), versions,
                new com.kwiki.infrastructure.redis.ScopeCache(StandardTestProperties.nullProvider(), Duration.ofSeconds(1)));
        return AgenticTestSupport.harness(router, scopes, versions,
                new ChatPersistenceService(StandardTestProperties.nullProvider()), metrics);
    }

    @Test
    void collectsAnswerOutcomeAndFinalContext() {
        var harness = harness();
        harness.bm25.hits = List.of(new ChunkHit("C1", "P1", 7, "PAGE", 1, 1L, "heading", 0, 12, "候选依据 C1 使用容器部署"));
        harness.vector.hits = harness.bm25.hits;
        harness.answer.scripted.add("基于证据的回答 [P0]");

        var result = new WorkflowProbe(harness.workflow).run(user, 7L, "什么是部署方式", Duration.ofSeconds(10));

        assertThat(result.outcome()).isEqualTo("completed");
        assertThat(result.errorCode()).isNull();
        assertThat(result.answer()).isEqualTo("基于证据的回答 [P0]");
        assertThat(result.context()).contains("候选依据 C1");
        assertThat(result.rewrites()).isEmpty();
    }

    @Test
    void recordsRewritesWhenQualityKeepsFailing() {
        var harness = harness();
        harness.bm25.hits = List.of(new ChunkHit("C1", "P1", 7, "PAGE", 1, 1L, "heading", 0, 6, "候选依据 C1"));
        harness.vector.hits = harness.bm25.hits;
        harness.quality.passes = input -> false;

        var result = new WorkflowProbe(harness.workflow).run(user, 7L, "什么是部署方式", Duration.ofSeconds(10));

        assertThat(result.rewrites()).isNotEmpty();
        assertThat(result.outcome()).isEqualTo("insufficient");
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./mvnw -q -Dtest='BaselineRetrievalProbeTest,WorkflowProbeTest' test`
Expected: 编译失败。

- [ ] **Step 3: 实现**

`BaselineRetrievalProbe.java`：

```java
package com.kwiki.eval;

import com.kwiki.indexing.pipeline.ChunkEmbeddingPort;
import com.kwiki.rag.retrieval.ChildRecallPort;
import com.kwiki.rag.retrieval.ChunkHit;
import com.kwiki.rag.retrieval.ConcurrentRecallService;
import com.kwiki.rag.retrieval.QaRetrievalBudgets;
import com.kwiki.rag.retrieval.RetrievalStrategy;
import com.kwiki.rag.retrieval.ScopeFilter;
import com.kwiki.rag.retrieval.StandardRrfFusion;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 原始 query 的检索基线（不改写、不走图）：与 QA 阶梯同口径——基础阶段两路各
 * baseBranchTopK 融合取 baseFinalTopK，扩大阶段两路各 expandedBranchTopK 融合取
 * expandedFinalTopK；扩大阶段的两路原始列表即单路候选池。查询向量只计算一次。
 */
@Component
public class BaselineRetrievalProbe {

    static final long BRANCH_TIMEOUT_MS = 10_000;

    public record Result(List<ChunkHit> bm25, List<ChunkHit> vector, List<ChunkHit> fused8,
                         List<ChunkHit> fused20, List<String> degradations) {

        public Map<EvalLayer, List<ChunkHit>> byLayer() {
            Map<EvalLayer, List<ChunkHit>> layers = new EnumMap<>(EvalLayer.class);
            layers.put(EvalLayer.BM25_50, bm25);
            layers.put(EvalLayer.VECTOR_50, vector);
            layers.put(EvalLayer.FUSED_8, fused8);
            layers.put(EvalLayer.FUSED_20, fused20);
            return layers;
        }
    }

    private final ChunkEmbeddingPort embeddings;
    private final ConcurrentRecallService recall;
    private final QaRetrievalBudgets budgets;

    public BaselineRetrievalProbe(ChunkEmbeddingPort embeddings, ConcurrentRecallService recall,
                                  QaRetrievalBudgets budgets) {
        this.embeddings = embeddings;
        this.recall = recall;
        this.budgets = budgets;
    }

    public Result probe(String query, ScopeFilter filter) {
        LinkedHashSet<String> degradations = new LinkedHashSet<>();
        float[] vector = embed(query, degradations);
        var base = recall.recall(query, vector, filter, budgets.baseBranchTopK,
                BRANCH_TIMEOUT_MS, RetrievalStrategy.HYBRID);
        var expanded = recall.recall(query, vector, filter, budgets.expandedBranchTopK,
                BRANCH_TIMEOUT_MS, RetrievalStrategy.HYBRID);
        Map<String, ChunkHit> hits = new LinkedHashMap<>();
        for (var outcome : base) remember(outcome, hits, degradations);
        List<ChunkHit> bm25 = List.of();
        List<ChunkHit> vectorHits = List.of();
        for (var outcome : expanded) {
            remember(outcome, hits, degradations);
            if (outcome.degradation() != null) continue;
            if (outcome.branch() == ChildRecallPort.Branch.BM25) bm25 = List.copyOf(outcome.hits());
            else vectorHits = List.copyOf(outcome.hits());
        }
        return new Result(bm25, vectorHits,
                fuse(base, hits, budgets.baseFinalTopK),
                fuse(expanded, hits, budgets.expandedFinalTopK),
                List.copyOf(degradations));
    }

    private float[] embed(String query, LinkedHashSet<String> degradations) {
        try {
            List<float[]> vectors = embeddings.embed(List.of(query));
            if (!vectors.isEmpty()) return vectors.get(0);
        } catch (RuntimeException ignored) {
            // 向量不可用时与线上一致：降级为 BM25 单路
        }
        degradations.add("vector-unavailable");
        return null;
    }

    private static void remember(ConcurrentRecallService.BranchOutcome outcome, Map<String, ChunkHit> hits,
                                 LinkedHashSet<String> degradations) {
        if (outcome.degradation() != null) {
            degradations.add(outcome.degradation());
            return;
        }
        outcome.hits().forEach(hit -> hits.putIfAbsent(hit.chunkKey(), hit));
    }

    private static List<ChunkHit> fuse(List<ConcurrentRecallService.BranchOutcome> outcomes,
                                       Map<String, ChunkHit> hits, int limit) {
        List<StandardRrfFusion.RankedList> rankings = new ArrayList<>();
        for (var outcome : outcomes) {
            if (outcome.degradation() != null) continue;
            rankings.add(new StandardRrfFusion.RankedList(outcome.branch().name(),
                    outcome.hits().stream().map(ChunkHit::chunkKey).distinct().toList()));
        }
        return StandardRrfFusion.fuse(rankings, 60).stream()
                .limit(limit)
                .map(fused -> hits.get(fused.chunkKey()))
                .filter(Objects::nonNull)
                .toList();
    }
}
```

`WorkflowProbe.java`：

```java
package com.kwiki.eval;

import com.kwiki.rag.answer.ChatStreamEvent;
import com.kwiki.rag.orchestration.AgenticWorkflowPort;
import com.kwiki.rag.orchestration.RouteInput;
import com.kwiki.rag.orchestration.RunObserver;
import com.kwiki.security.CurrentUser;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 以不落会话的方式跑一次完整 QA 工作流（限定评测知识库），从 SSE 事件取回答与终态，
 * 从运行观测取最后一次送入生成的上下文与改写历史。
 */
@Component
public class WorkflowProbe {

    public record Result(String outcome, String errorCode, String answer, String context,
                         List<String> rewrites) {
    }

    private final AgenticWorkflowPort workflow;

    public WorkflowProbe(AgenticWorkflowPort workflow) {
        this.workflow = workflow;
    }

    public Result run(CurrentUser user, long kbId, String query, Duration timeout) {
        List<RunObserver.Event> observed = new CopyOnWriteArrayList<>();
        List<ChatStreamEvent> events = workflow.answerInSession(user, query, List.of(), Set.of(kbId), Set.of(),
                RouteInput.NONE, observed::add).collectList().block(timeout);
        StringBuilder answer = new StringBuilder();
        String outcome = null;
        String error = null;
        for (ChatStreamEvent event : events == null ? List.<ChatStreamEvent>of() : events) {
            switch (event.type()) {
                case "token" -> answer.append(String.valueOf(event.payloadMap().get("text")));
                case "done" -> outcome = String.valueOf(event.payloadMap().get("outcome"));
                case "error" -> error = String.valueOf(event.payloadMap().get("error"));
                default -> { }
            }
        }
        String context = "";
        List<String> rewrites = new ArrayList<>();
        for (RunObserver.Event event : observed) {
            if (event instanceof RunObserver.ContextObserved contextEvent) context = contextEvent.context();
            else if (event instanceof RunObserver.RewriteObserved rewrite) rewrites.add(rewrite.newQuery());
        }
        return new Result(outcome, error, answer.toString(), context, List.copyOf(rewrites));
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `./mvnw -q -Dtest='BaselineRetrievalProbeTest,WorkflowProbeTest' test`
Expected: PASS。若 `recordsRewritesWhenQualityKeepsFailing` 的终态不是 `insufficient`，先查看 `AgenticWorkflowTest.exhaustedRoundsOutputExactRefusalWithEmptyCitations` 的断言并以该测试为准修正期望值（不要改工作流）。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/kwiki/eval/BaselineRetrievalProbe.java src/main/java/com/kwiki/eval/WorkflowProbe.java src/test/java/com/kwiki/eval/BaselineRetrievalProbeTest.java src/test/java/com/kwiki/eval/WorkflowProbeTest.java
git commit -m "feat(eval): 基线检索探针（与 QA 阶梯同口径的单路池与 RRF Top-8/20）与完整工作流探针"
```

---

### Task 5: Langfuse 上报端口与 REST 客户端

**Files:**
- Create: `src/main/java/com/kwiki/eval/EvalReportPort.java`
- Create: `src/main/java/com/kwiki/eval/EvalReportException.java`
- Create: `src/main/java/com/kwiki/infrastructure/langfuse/LangfuseRestClient.java`
- Test: `src/test/java/com/kwiki/infrastructure/langfuse/LangfuseRestClientTest.java`

**Interfaces:**
- Produces:
  - `interface EvalReportPort { boolean available(); void upsertDataset(String name, String description); void upsertItem(String datasetName, String itemId, Map<String,Object> input, Map<String,Object> expectedOutput, Map<String,Object> metadata); void recordTrace(TraceRecord trace); void flush(); }`
  - `EvalReportPort.TraceRecord(String traceId, String name, Map<String,Object> input, Map<String,Object> output, Map<String,Object> metadata, List<String> tags, List<SpanRecord> spans, List<ScoreRecord> scores, String datasetItemId, String runName, Map<String,Object> runMetadata)`
  - `EvalReportPort.SpanRecord(String id, String name, Instant start, Instant end, Map<String,Object> input, Map<String,Object> output)`
  - `EvalReportPort.ScoreRecord(String name, double value, String comment)`
  - `EvalReportException extends RuntimeException`
  - `LangfuseRestClient implements EvalReportPort`（Spring Bean）

- [ ] **Step 1: 写失败测试**

```java
package com.kwiki.infrastructure.langfuse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwiki.eval.EvalReportException;
import com.kwiki.eval.EvalReportPort;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LangfuseRestClientTest {
    final ObjectMapper mapper = new ObjectMapper();
    final List<Long> sleeps = new CopyOnWriteArrayList<>();
    MockWebServer server;
    LangfuseRestClient client;

    @BeforeEach
    void start() throws Exception {
        server = new MockWebServer();
        server.start();
        client = new LangfuseRestClient(server.url("/").toString(), "pk-lf-test", "sk-lf-test", mapper, sleeps::add);
    }

    @AfterEach
    void stop() throws Exception {
        server.shutdown();
    }

    JsonNode body(RecordedRequest request) throws Exception {
        return mapper.readTree(request.getBody().readString(StandardCharsets.UTF_8));
    }

    static EvalReportPort.TraceRecord trace() {
        Instant now = Instant.parse("2026-10-20T00:00:00Z");
        return new EvalReportPort.TraceRecord("trace-1", "eval:db-1", Map.of("query", "问题"),
                Map.of("answer", "回答"), Map.of("category", "事实性问题"), List.of("eval"),
                List.of(new EvalReportPort.SpanRecord("span-1", "bm25@50", now, now,
                        Map.of("query", "问题"), Map.of("matched", List.of(0)))),
                List.of(new EvalReportPort.ScoreRecord("coverage@answer", 0.5, null)),
                "kwiki-rag-50q:db-1", "50q-run", Map.of("notes", "baseline"));
    }

    @Test
    void unconfiguredClientIsUnavailable() {
        assertThat(new LangfuseRestClient("https://cloud.langfuse.com", "", "", mapper, sleeps::add).available())
                .isFalse();
        assertThat(client.available()).isTrue();
    }

    @Test
    void datasetConflictIsTreatedAsExisting() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(409).setBody("{\"message\":\"exists\"}"));

        client.upsertDataset("kwiki-rag-50q", "desc");

        RecordedRequest request = server.takeRequest();
        assertThat(request.getPath()).isEqualTo("/api/public/datasets");
        assertThat(request.getHeader("Authorization")).isEqualTo("Basic "
                + Base64.getEncoder().encodeToString("pk-lf-test:sk-lf-test".getBytes(StandardCharsets.UTF_8)));
        assertThat(body(request).get("name").asText()).isEqualTo("kwiki-rag-50q");
    }

    @Test
    void itemUpsertRetriesServerErrorsWithBackoff() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));

        client.upsertItem("kwiki-rag-50q", "kwiki-rag-50q:db-1", Map.of("query", "问题"),
                Map.of("keywords", List.of(List.of("甲"))), Map.of("tier", 50));

        assertThat(server.getRequestCount()).isEqualTo(3);
        assertThat(sleeps).containsExactly(500L, 1000L);
        JsonNode item = body(server.takeRequest());
        assertThat(item.get("id").asText()).isEqualTo("kwiki-rag-50q:db-1");
        assertThat(item.get("datasetName").asText()).isEqualTo("kwiki-rag-50q");
        assertThat(item.get("expectedOutput").get("keywords").get(0).get(0).asText()).isEqualTo("甲");
    }

    @Test
    void flushSendsIngestionBatchThenRunItems() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(207).setBody("{\"successes\":[],\"errors\":[]}"));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));

        client.recordTrace(trace());
        client.flush();

        RecordedRequest ingestion = server.takeRequest();
        assertThat(ingestion.getPath()).isEqualTo("/api/public/ingestion");
        JsonNode batch = body(ingestion).get("batch");
        assertThat(batch).extracting(node -> node.get("type").asText())
                .containsExactly("trace-create", "span-create", "score-create");
        assertThat(batch.get(1).get("body").get("traceId").asText()).isEqualTo("trace-1");
        assertThat(batch.get(2).get("body").get("value").asDouble()).isEqualTo(0.5);
        RecordedRequest runItem = server.takeRequest();
        assertThat(runItem.getPath()).isEqualTo("/api/public/dataset-run-items");
        JsonNode link = body(runItem);
        assertThat(link.get("runName").asText()).isEqualTo("50q-run");
        assertThat(link.get("datasetItemId").asText()).isEqualTo("kwiki-rag-50q:db-1");
        assertThat(link.get("traceId").asText()).isEqualTo("trace-1");
    }

    @Test
    void flushReportsFailureAfterRetriesAreExhausted() {
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(503));

        client.recordTrace(trace());

        assertThatThrownBy(client::flush).isInstanceOf(EvalReportException.class)
                .hasMessageContaining("1 traces");
    }

    @Test
    void ingestionPartialErrorsFailTheBatch() {
        server.enqueue(new MockResponse().setResponseCode(207)
                .setBody("{\"successes\":[],\"errors\":[{\"id\":\"x\",\"status\":400}]}"));

        client.recordTrace(trace());

        assertThatThrownBy(client::flush).isInstanceOf(EvalReportException.class);
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./mvnw -q -Dtest=LangfuseRestClientTest test`
Expected: 编译失败。

- [ ] **Step 3: 实现**

`EvalReportException.java`：

```java
package com.kwiki.eval;

/** 评测结果上报失败。 */
public class EvalReportException extends RuntimeException {

    public EvalReportException(String message) {
        super(message);
    }

    public EvalReportException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

`EvalReportPort.java`：

```java
package com.kwiki.eval;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 评测结果上报抽象；当前实现为 Langfuse，测试可替换为内存实现。 */
public interface EvalReportPort {

    /** 是否已配置凭据。 */
    boolean available();

    /** 创建数据集；已存在视为成功。 */
    void upsertDataset(String name, String description);

    /** 以 itemId 幂等写入数据集条目。 */
    void upsertItem(String datasetName, String itemId, Map<String, Object> input,
                    Map<String, Object> expectedOutput, Map<String, Object> metadata);

    /** 缓冲一条 trace（达到批量阈值时自动发送）。 */
    void recordTrace(TraceRecord trace);

    /** 发送剩余缓冲；本 run 内有任何 trace 上传失败则抛 {@link EvalReportException}。 */
    void flush();

    record TraceRecord(
            String traceId,
            String name,
            Map<String, Object> input,
            Map<String, Object> output,
            Map<String, Object> metadata,
            List<String> tags,
            List<SpanRecord> spans,
            List<ScoreRecord> scores,
            String datasetItemId,
            String runName,
            Map<String, Object> runMetadata) {
    }

    record SpanRecord(String id, String name, Instant start, Instant end,
                      Map<String, Object> input, Map<String, Object> output) {
    }

    record ScoreRecord(String name, double value, String comment) {
    }
}
```

`LangfuseRestClient.java`：

```java
package com.kwiki.infrastructure.langfuse;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwiki.eval.EvalReportException;
import com.kwiki.eval.EvalReportPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongConsumer;

/**
 * Langfuse 公共 API 客户端（datasets / dataset-items / ingestion / dataset-run-items）。
 * Basic 认证（publicKey:secretKey）；5xx、429 与网络错误按 500ms 起指数退避重试 3 次；
 * 未配置凭据时 {@link #available()} 为 false，调用方不应再发请求。密钥绝不写日志。
 */
@Component
public class LangfuseRestClient implements EvalReportPort {

    private static final Logger log = LoggerFactory.getLogger(LangfuseRestClient.class);
    static final int MAX_ATTEMPTS = 3;
    static final int FLUSH_THRESHOLD = 20;

    private final RestClient http;
    private final ObjectMapper mapper;
    private final LongConsumer sleeper;
    private final boolean configured;
    private final List<TraceRecord> buffer = new ArrayList<>();
    private int failedTraces;

    @Autowired
    public LangfuseRestClient(@Value("${kwiki.langfuse.host:https://cloud.langfuse.com}") String host,
                              @Value("${kwiki.langfuse.public-key:}") String publicKey,
                              @Value("${kwiki.langfuse.secret-key:}") String secretKey,
                              ObjectMapper mapper) {
        this(host, publicKey, secretKey, mapper, LangfuseRestClient::sleep);
    }

    LangfuseRestClient(String host, String publicKey, String secretKey, ObjectMapper mapper,
                       LongConsumer sleeper) {
        this.configured = publicKey != null && !publicKey.isBlank() && secretKey != null && !secretKey.isBlank();
        String token = Base64.getEncoder().encodeToString(
                ((publicKey == null ? "" : publicKey) + ":" + (secretKey == null ? "" : secretKey))
                        .getBytes(StandardCharsets.UTF_8));
        this.http = RestClient.builder()
                .baseUrl(host.endsWith("/") ? host.substring(0, host.length() - 1) : host)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + token)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.mapper = mapper;
        this.sleeper = sleeper;
    }

    @Override
    public boolean available() {
        return configured;
    }

    @Override
    public void upsertDataset(String name, String description) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("description", description);
        post("/api/public/datasets", body, true);
    }

    @Override
    public void upsertItem(String datasetName, String itemId, Map<String, Object> input,
                           Map<String, Object> expectedOutput, Map<String, Object> metadata) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("datasetName", datasetName);
        body.put("id", itemId);
        body.put("input", input);
        body.put("expectedOutput", expectedOutput);
        body.put("metadata", metadata);
        post("/api/public/dataset-items", body, false);
    }

    @Override
    public synchronized void recordTrace(TraceRecord trace) {
        buffer.add(trace);
        if (buffer.size() >= FLUSH_THRESHOLD) sendBuffered();
    }

    @Override
    public synchronized void flush() {
        sendBuffered();
        if (failedTraces > 0) {
            int failed = failedTraces;
            failedTraces = 0;
            throw new EvalReportException(failed + " traces failed to upload to langfuse");
        }
    }

    private void sendBuffered() {
        if (buffer.isEmpty()) return;
        List<TraceRecord> batch = List.copyOf(buffer);
        buffer.clear();
        try {
            JsonNode response = post("/api/public/ingestion", Map.of("batch", ingestionEvents(batch)), false);
            JsonNode errors = response.path("errors");
            if (errors.isArray() && !errors.isEmpty()) {
                throw new EvalReportException("langfuse ingestion rejected " + errors.size() + " events");
            }
            for (TraceRecord trace : batch) {
                if (trace.datasetItemId() == null) continue;
                Map<String, Object> link = new LinkedHashMap<>();
                link.put("runName", trace.runName());
                link.put("datasetItemId", trace.datasetItemId());
                link.put("traceId", trace.traceId());
                link.put("metadata", trace.runMetadata());
                post("/api/public/dataset-run-items", link, false);
            }
        } catch (RuntimeException e) {
            failedTraces += batch.size();
            log.warn("langfuse upload failed traces={} reason={}", batch.size(), e.getMessage());
        }
    }

    private List<Map<String, Object>> ingestionEvents(List<TraceRecord> batch) {
        String now = Instant.now().toString();
        List<Map<String, Object>> events = new ArrayList<>();
        for (TraceRecord trace : batch) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("id", trace.traceId());
            body.put("name", trace.name());
            body.put("input", trace.input());
            body.put("output", trace.output());
            body.put("metadata", trace.metadata());
            body.put("tags", trace.tags());
            events.add(event("trace-create", now, body));
            for (SpanRecord span : trace.spans()) {
                Map<String, Object> spanBody = new LinkedHashMap<>();
                spanBody.put("id", span.id());
                spanBody.put("traceId", trace.traceId());
                spanBody.put("name", span.name());
                spanBody.put("startTime", span.start().toString());
                spanBody.put("endTime", span.end().toString());
                spanBody.put("input", span.input());
                spanBody.put("output", span.output());
                events.add(event("span-create", now, spanBody));
            }
            for (ScoreRecord score : trace.scores()) {
                Map<String, Object> scoreBody = new LinkedHashMap<>();
                scoreBody.put("id", UUID.randomUUID().toString());
                scoreBody.put("traceId", trace.traceId());
                scoreBody.put("name", score.name());
                scoreBody.put("value", score.value());
                scoreBody.put("dataType", "NUMERIC");
                if (score.comment() != null) scoreBody.put("comment", score.comment());
                events.add(event("score-create", now, scoreBody));
            }
        }
        return events;
    }

    private static Map<String, Object> event(String type, String timestamp, Map<String, Object> body) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("id", UUID.randomUUID().toString());
        event.put("timestamp", timestamp);
        event.put("type", type);
        event.put("body", body);
        return event;
    }

    private JsonNode post(String path, Object body, boolean conflictIsSuccess) {
        String payload;
        try {
            payload = mapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new EvalReportException("langfuse payload serialization failed", e);
        }
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                String response = http.post().uri(path).body(payload).retrieve().body(String.class);
                return response == null || response.isBlank() ? mapper.createObjectNode() : mapper.readTree(response);
            } catch (HttpClientErrorException e) {
                int status = e.getStatusCode().value();
                if (conflictIsSuccess && status == 409) return mapper.createObjectNode();
                if (status != 429) throw new EvalReportException("langfuse " + path + " rejected with " + status, e);
                last = e;
            } catch (HttpServerErrorException | ResourceAccessException e) {
                last = e;
            } catch (JsonProcessingException e) {
                throw new EvalReportException("langfuse " + path + " returned invalid json", e);
            }
            if (attempt < MAX_ATTEMPTS) sleeper.accept(500L << (attempt - 1));
        }
        throw new EvalReportException("langfuse " + path + " failed after " + MAX_ATTEMPTS + " attempts: "
                + last.getMessage(), last);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EvalReportException("interrupted while retrying langfuse", e);
        }
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `./mvnw -q -Dtest=LangfuseRestClientTest test`
Expected: PASS（6 个测试）。

- [ ] **Step 5: 确认应用上下文与密钥审计未受影响**

Run: `./mvnw -q -Dtest='KwikiApplicationTest,RepositorySecretsAuditTest' test`
Expected: PASS（未配置 key 时 Bean 正常创建）。

- [ ] **Step 6: 提交**

```bash
git add src/main/java/com/kwiki/eval/EvalReportPort.java src/main/java/com/kwiki/eval/EvalReportException.java src/main/java/com/kwiki/infrastructure/langfuse src/test/java/com/kwiki/infrastructure/langfuse
git commit -m "feat(eval): Langfuse REST 上报客户端——数据集与条目幂等写入、trace/span/score 批量 ingestion 与 dataset run 关联，带退避重试"
```

---

### Task 6: 评测语料转换、导入与就绪检查

**Files:**
- Create: `tools/eval/convert-dragonball.mjs`
- Create（生成）: `src/main/resources/eval/corpus/dragonball-zh/*.md`、`manifest.json`
- Create: `src/main/resources/eval/corpus/dragonball-zh/NOTICE.md`
- Create: `src/main/java/com/kwiki/eval/EvalException.java`
- Create: `src/main/java/com/kwiki/eval/EvalCorpusService.java`
- Test: `src/test/java/com/kwiki/eval/EvalCorpusServiceTest.java`
- Test resource: `src/test/resources/eval/corpus/test-mini/manifest.json`、`a.md`、`b.md`

**Interfaces:**
- Consumes: 现有 `WikiTreeService.createNode(CurrentUser, long kbId, Long parentId, String title, String nodeType, Integer position)` → `WikiPage`；`WikiTreeService.tree(CurrentUser, long)` → `List<WikiTreeService.TreeNodeView>`（`TreeNodeView(Long id, String uuid, String title, String nodeType, List<TreeNodeView> children)`）；`PageRevisionService.saveDraft(CurrentUser, long, long, String markdown, String changeNote, Integer expectedLockVersion)`；`PageRevisionService.publish(CurrentUser, long, long)`；`PageIndexStatusService.status(CurrentUser, long, long)` → `PageIndexStatus(IndexStatus status)`，`IndexStatus { INDEXING, READY, FAILED }`；`WikiPage.TYPE_PAGE`。
- Produces:
  - `EvalException(int code, String message)`
  - `EvalCorpusService`：`ImportResult importCorpus(CurrentUser user, long kbId, String corpus)`、`void requireReady(CurrentUser user, long kbId)`；`record ImportResult(List<Long> createdPageIds, List<String> skippedTitles)`；`record ManifestEntry(String file, String title, String domain, int docId)`；`List<ManifestEntry> manifest(String corpus)`；`String document(String corpus, String file)`

- [ ] **Step 1: 写转换脚本**

`tools/eval/convert-dragonball.mjs`：

```js
#!/usr/bin/env node
// 把 DragonBall 中文文档转换为评测语料（每篇一个 Markdown + manifest.json）。
// 用法：node tools/eval/convert-dragonball.mjs <dragonball_docs.jsonl> <输出目录>
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const [input, outDir] = process.argv.slice(2);
if (!input || !outDir) {
  console.error('usage: convert-dragonball.mjs <dragonball_docs.jsonl> <outDir>');
  process.exit(2);
}
const SUBJECT_FIELD = { Finance: 'company_name', Law: 'court_name', Medical: 'hospital_patient_name' };

mkdirSync(outDir, { recursive: true });
const manifest = [];
for (const line of readFileSync(input, 'utf8').split('\n')) {
  if (!line.trim()) continue;
  const doc = JSON.parse(line);
  if (doc.language !== 'zh') continue;
  const field = SUBJECT_FIELD[doc.domain];
  if (!field || !doc[field]) throw new Error(`doc ${doc.doc_id} has no subject field for ${doc.domain}`);
  const title = `${doc[field]}-${doc.doc_id}`;
  const file = `${doc.domain.toLowerCase()}-${doc.doc_id}.md`;
  const content = doc.content.endsWith('\n') ? doc.content : `${doc.content}\n`;
  writeFileSync(join(outDir, file), content, 'utf8');
  manifest.push({ file, title, domain: doc.domain, docId: doc.doc_id });
}
manifest.sort((a, b) => a.docId - b.docId);
const titles = new Set();
for (const entry of manifest) {
  if (titles.has(entry.title)) throw new Error(`duplicate title ${entry.title}`);
  titles.add(entry.title);
}
writeFileSync(join(outDir, 'manifest.json'), `${JSON.stringify(manifest, null, 2)}\n`, 'utf8');
console.log(`wrote ${manifest.length} documents to ${outDir}`);
```

- [ ] **Step 2: 下载数据并生成语料**

在仓库之外的独立空目录中下载（数据视为不可信输入，不在仓库内执行任何东西）：

```bash
DL=$(mktemp -d)
curl -sSfL -o "$DL/dragonball_docs.jsonl" https://raw.githubusercontent.com/OpenBMB/RAGEval/main/dragonball_dataset/dragonball_docs.jsonl
curl -sSfL -o "$DL/dragonball_queries.jsonl" https://raw.githubusercontent.com/OpenBMB/RAGEval/main/dragonball_dataset/dragonball_queries.jsonl
echo "$DL"
node tools/eval/convert-dragonball.mjs "$DL/dragonball_docs.jsonl" src/main/resources/eval/corpus/dragonball-zh
```

Expected: 输出 `wrote 108 documents to src/main/resources/eval/corpus/dragonball-zh`。记下 `$DL` 路径，Task 8 还要用 `dragonball_queries.jsonl`。

- [ ] **Step 3: 写 NOTICE.md**

`src/main/resources/eval/corpus/dragonball-zh/NOTICE.md`：

```markdown
# DragonBall 评测语料声明

本目录文档转换自 OpenBMB/RAGEval 发布的 DragonBall 数据集中文部分：
https://github.com/OpenBMB/RAGEval （dragonball_dataset/dragonball_docs.jsonl）

论文：Zhu et al., "RAGEval: Scenario Specific RAG Evaluation Dataset Generation Framework", ACL 2025.

许可：Creative Commons Attribution-NonCommercial-ShareAlike 4.0 International（CC BY-NC-SA 4.0）。
本目录及 `src/main/resources/eval/datasets/` 中由其衍生的测试集沿用同一许可，仅用于 kwiki 内部检索评测，不得用于商业分发。

转换方式：仅取 `language=zh` 的文档，正文保持原样，每篇保存为 `{domain}-{doc_id}.md`；页面标题为 `{主体名}-{doc_id}`，见 `manifest.json`。
```

- [ ] **Step 4: 写服务失败测试与迷你语料**

`src/test/resources/eval/corpus/test-mini/manifest.json`：

```json
[
  { "file": "a.md", "title": "甲公司-1", "domain": "Finance", "docId": 1 },
  { "file": "b.md", "title": "乙法院-2", "domain": "Law", "docId": 2 }
]
```

`src/test/resources/eval/corpus/test-mini/a.md`：

```markdown
甲公司成立于2001年5月。
```

`src/test/resources/eval/corpus/test-mini/b.md`：

```markdown
乙法院于2019年审理了一起合同纠纷。
```

`src/test/java/com/kwiki/eval/EvalCorpusServiceTest.java`：

```java
package com.kwiki.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwiki.security.CurrentUser;
import com.kwiki.testutil.StandardTestProperties;
import com.kwiki.wiki.api.PageIndexStatusService;
import com.kwiki.wiki.api.PageRevisionService;
import com.kwiki.wiki.api.WikiTreeService;
import com.kwiki.wiki.domain.WikiPage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EvalCorpusServiceTest {
    final CurrentUser admin = new CurrentUser(1L, "admin", true);
    final WikiTreeService tree = mock(WikiTreeService.class);
    final PageRevisionService revisions = mock(PageRevisionService.class);
    final PageIndexStatusService statuses = mock(PageIndexStatusService.class);

    @SuppressWarnings("unchecked")
    EvalCorpusService service() {
        ObjectProvider<PageIndexStatusService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(statuses);
        return new EvalCorpusService(tree, revisions, provider, new ObjectMapper());
    }

    static WikiTreeService.TreeNodeView node(long id, String title) {
        return new WikiTreeService.TreeNodeView(id, "u" + id, title, WikiPage.TYPE_PAGE, List.of());
    }

    static WikiPage page(long id) {
        WikiPage page = mock(WikiPage.class);
        when(page.getId()).thenReturn(id);
        return page;
    }

    @Test
    void importsMissingDocumentsThroughTheNormalPublishPath() {
        when(tree.tree(admin, 9L)).thenReturn(List.of(node(100L, "甲公司-1")));
        WikiPage created = page(101L);
        when(tree.createNode(eq(admin), eq(9L), eq(null), eq("乙法院-2"), eq(WikiPage.TYPE_PAGE), eq(null)))
                .thenReturn(created);

        var result = service().importCorpus(admin, 9L, "test-mini");

        assertThat(result.createdPageIds()).containsExactly(101L);
        assertThat(result.skippedTitles()).containsExactly("甲公司-1");
        verify(revisions).saveDraft(eq(admin), eq(9L), eq(101L), eq("乙法院于2019年审理了一起合同纠纷。\n"),
                eq("评测语料导入 test-mini"), eq(null));
        verify(revisions).publish(admin, 9L, 101L);
        verify(revisions, never()).publish(admin, 9L, 100L);
    }

    @Test
    void rejectsCorpusNamesThatCouldEscapeTheResourceRoot() {
        assertThatThrownBy(() -> service().importCorpus(admin, 9L, "../secrets"))
                .isInstanceOf(EvalException.class);
    }

    @Test
    void readinessRequiresEveryPageIndexed() {
        when(tree.tree(admin, 9L)).thenReturn(List.of(node(100L, "甲公司-1"), node(101L, "乙法院-2")));
        when(statuses.status(admin, 9L, 100L)).thenReturn(
                new PageIndexStatusService.PageIndexStatus(PageIndexStatusService.IndexStatus.READY));
        when(statuses.status(admin, 9L, 101L)).thenReturn(
                new PageIndexStatusService.PageIndexStatus(PageIndexStatusService.IndexStatus.INDEXING));

        assertThatThrownBy(() -> service().requireReady(admin, 9L))
                .isInstanceOf(EvalException.class).hasMessageContaining("corpus-not-ready");
    }

    @Test
    void readinessRejectsEmptyKnowledgeBase() {
        when(tree.tree(admin, 9L)).thenReturn(List.of());
        assertThatThrownBy(() -> service().requireReady(admin, 9L))
                .isInstanceOf(EvalException.class).hasMessageContaining("corpus-empty");
    }

    @Test
    void readinessPassesWhenAllPagesAreReady() {
        when(tree.tree(admin, 9L)).thenReturn(List.of(node(100L, "甲公司-1")));
        when(statuses.status(any(), anyLong(), anyLong())).thenReturn(
                new PageIndexStatusService.PageIndexStatus(PageIndexStatusService.IndexStatus.READY));
        service().requireReady(admin, 9L);
    }
}
```

- [ ] **Step 5: 运行确认失败**

Run: `./mvnw -q -Dtest=EvalCorpusServiceTest test`
Expected: 编译失败。

- [ ] **Step 6: 实现**

`EvalException.java`：

```java
package com.kwiki.eval;

import com.kk2004.common.exception.BusinessException;

/** 评测相关业务错误；code 为 HTTP 语义码，message 为稳定错误码。 */
public class EvalException extends BusinessException {

    public EvalException(int code, String message) {
        super(code, message);
    }
}
```

`EvalCorpusService.java`：

```java
package com.kwiki.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwiki.security.CurrentUser;
import com.kwiki.wiki.api.PageIndexStatusService;
import com.kwiki.wiki.api.PageRevisionService;
import com.kwiki.wiki.api.WikiTreeService;
import com.kwiki.wiki.domain.WikiPage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 评测语料导入与就绪检查。导入与用户手动建页走同一条链路：
 * createNode → saveDraft → publish（publish 同事务入队索引任务）。按标题幂等。
 */
@Service
public class EvalCorpusService {

    static final String CORPUS_ROOT = "eval/corpus/";
    private static final Pattern CORPUS_NAME = Pattern.compile("[a-z0-9-]+");

    public record ManifestEntry(String file, String title, String domain, int docId) {
    }

    public record ImportResult(List<Long> createdPageIds, List<String> skippedTitles) {
    }

    private final WikiTreeService tree;
    private final PageRevisionService revisions;
    private final ObjectProvider<PageIndexStatusService> indexStatuses;
    private final ObjectMapper mapper;

    public EvalCorpusService(WikiTreeService tree, PageRevisionService revisions,
                             ObjectProvider<PageIndexStatusService> indexStatuses, ObjectMapper mapper) {
        this.tree = tree;
        this.revisions = revisions;
        this.indexStatuses = indexStatuses;
        this.mapper = mapper;
    }

    public ImportResult importCorpus(CurrentUser user, long kbId, String corpus) {
        List<ManifestEntry> manifest = manifest(corpus);
        Set<String> existing = new HashSet<>();
        pages(tree.tree(user, kbId)).forEach(node -> existing.add(node.title()));
        List<Long> created = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (ManifestEntry entry : manifest) {
            if (existing.contains(entry.title())) {
                skipped.add(entry.title());
                continue;
            }
            String markdown = document(corpus, entry.file());
            WikiPage page = tree.createNode(user, kbId, null, entry.title(), WikiPage.TYPE_PAGE, null);
            revisions.saveDraft(user, kbId, page.getId(), markdown, "评测语料导入 " + corpus, null);
            revisions.publish(user, kbId, page.getId());
            created.add(page.getId());
        }
        return new ImportResult(List.copyOf(created), List.copyOf(skipped));
    }

    /** 评测 KB 至少有一个页面，且全部页面索引就绪。 */
    public void requireReady(CurrentUser user, long kbId) {
        List<WikiTreeService.TreeNodeView> pages = pages(tree.tree(user, kbId));
        if (pages.isEmpty()) throw new EvalException(409, "corpus-empty");
        PageIndexStatusService statuses = indexStatuses.getIfAvailable();
        if (statuses == null) return;
        long notReady = pages.stream()
                .filter(page -> statuses.status(user, kbId, page.id()).status()
                        != PageIndexStatusService.IndexStatus.READY)
                .count();
        if (notReady > 0) {
            throw new EvalException(409, "corpus-not-ready: " + notReady + " pages not indexed");
        }
    }

    public List<ManifestEntry> manifest(String corpus) {
        requireCorpusName(corpus);
        try (InputStream in = new ClassPathResource(CORPUS_ROOT + corpus + "/manifest.json").getInputStream()) {
            return mapper.readValue(in, new TypeReference<List<ManifestEntry>>() { });
        } catch (IOException e) {
            throw new EvalException(404, "corpus-not-found: " + corpus);
        }
    }

    public String document(String corpus, String file) {
        requireCorpusName(corpus);
        if (!file.matches("[A-Za-z0-9._-]+\\.md")) throw new EvalException(400, "invalid-corpus-file");
        try (InputStream in = new ClassPathResource(CORPUS_ROOT + corpus + "/" + file).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new EvalException(404, "corpus-file-not-found: " + file);
        }
    }

    private static void requireCorpusName(String corpus) {
        if (corpus == null || !CORPUS_NAME.matcher(corpus).matches()) {
            throw new EvalException(400, "invalid-corpus-name");
        }
    }

    private static List<WikiTreeService.TreeNodeView> pages(List<WikiTreeService.TreeNodeView> roots) {
        List<WikiTreeService.TreeNodeView> pages = new ArrayList<>();
        collect(roots, pages);
        return pages;
    }

    private static void collect(List<WikiTreeService.TreeNodeView> nodes, List<WikiTreeService.TreeNodeView> out) {
        if (nodes == null) return;
        for (WikiTreeService.TreeNodeView node : nodes) {
            if (WikiPage.TYPE_PAGE.equals(node.nodeType())) out.add(node);
            collect(node.children(), out);
        }
    }
}
```

- [ ] **Step 7: 运行确认通过**

Run: `./mvnw -q -Dtest=EvalCorpusServiceTest test`
Expected: PASS。若 `WikiPage` 在 `com.kwiki.wiki.domain` 之外，按 `grep -rn "class WikiPage " src/main/java` 修正 import。

- [ ] **Step 8: 提交**

```bash
git add tools/eval/convert-dragonball.mjs src/main/resources/eval/corpus src/main/java/com/kwiki/eval/EvalException.java src/main/java/com/kwiki/eval/EvalCorpusService.java src/test/java/com/kwiki/eval/EvalCorpusServiceTest.java src/test/resources/eval/corpus
git commit -m "feat(eval): DragonBall 中文语料转换脚本与 108 篇评测语料，导入走正常建页发布链路并按标题幂等，评测前检查索引就绪"
```

---

### Task 7: 评测编排、本地报告、数据集同步与管理接口

**Files:**
- Create: `src/main/java/com/kwiki/eval/EvalReportWriter.java`
- Create: `src/main/java/com/kwiki/eval/EvalDatasetSyncService.java`
- Create: `src/main/java/com/kwiki/eval/EvalRunService.java`
- Create: `src/main/java/com/kwiki/eval/EvalAdminController.java`
- Modify: `.gitignore`（追加 `eval-reports/`）
- Test: `src/test/java/com/kwiki/eval/EvalRunServiceTest.java`
- Test: `src/test/java/com/kwiki/eval/EvalDatasetSyncServiceTest.java`

**Interfaces:**
- Consumes: Task 2–6 全部产出；现有 `AuthorizationScopeResolver.resolve(CurrentUser, Set<Long>, Set<Long>)`、`ScopeFilter.from(AuthorizationScope)`、`KwikiDistributedLocks.isAvailable()` / `acquire(String, Duration)`（返回 `AutoCloseable`，null 表示未获取；必须在同一线程关闭）、`QaRetrievalBudgets` 公开字段。
- Produces:
  - `EvalReportWriter(ObjectMapper, @Value report-dir)`：`Path write(String runName, Map<String,Object> config, EvalSummary summary, List<CaseResult> results)`
  - `EvalDatasetSyncService(EvalDatasetLoader, EvalReportPort)`：`int sync()`（返回写入条目数）
  - `EvalRunService`：`RunStatus start(CurrentUser, StartRequest)`、`RunStatus status(String runId)`、包内 `RunStatus runNow(CurrentUser, StartRequest)`（同步执行，测试用）；`record StartRequest(String dataset, Long kbId, String runName, String notes, boolean localOnly)`；`enum State { RUNNING, COMPLETED, REPORT_PARTIAL, FAILED }`；`record RunStatus(String runId, String runName, String dataset, State state, int total, int completed, EvalSummary summary, String reportPath, String error)`
  - 接口：`POST /api/v1/admin/eval/corpus/import?kbId=&corpus=dragonball-zh`、`POST /api/v1/admin/eval/datasets/sync`、`POST /api/v1/admin/eval/runs`、`GET /api/v1/admin/eval/runs/{runId}`

- [ ] **Step 1: 写失败测试**

`EvalDatasetSyncServiceTest.java`：

```java
package com.kwiki.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EvalDatasetSyncServiceTest {

    static EvalCase evalCase(String id, int tier) {
        return new EvalCase(id, "dragonball", tier, "事实性问题", "Finance", "问题" + id,
                List.of(List.of("甲")), false, List.of(1), List.of("要点"));
    }

    @Test
    void writesNestedItemsIntoThreeDatasets() {
        var loader = mock(EvalDatasetLoader.class);
        when(loader.load()).thenReturn(List.of(evalCase("a", 50), evalCase("b", 100), evalCase("c", 200)));
        var report = new InMemoryReport();

        int items = new EvalDatasetSyncService(loader, report).sync();

        assertThat(items).isEqualTo(6); // 50q:1 + 100q:2 + 200q:3
        assertThat(report.datasets).containsExactly("kwiki-rag-50q", "kwiki-rag-100q", "kwiki-rag-200q");
        assertThat(report.itemIds).contains("kwiki-rag-50q:a", "kwiki-rag-100q:b", "kwiki-rag-200q:c");
    }

    @Test
    void refusesWithoutLangfuseCredentials() {
        var report = new InMemoryReport();
        report.available = false;
        assertThatThrownBy(() -> new EvalDatasetSyncService(mock(EvalDatasetLoader.class), report).sync())
                .isInstanceOf(EvalException.class).hasMessageContaining("langfuse-not-configured");
    }
}
```

同目录新建测试辅助类 `InMemoryReport.java`：

```java
package com.kwiki.eval;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** 测试用内存上报端口。 */
class InMemoryReport implements EvalReportPort {
    boolean available = true;
    boolean failFlush;
    int flushes;
    final List<String> datasets = new CopyOnWriteArrayList<>();
    final List<String> itemIds = new CopyOnWriteArrayList<>();
    final List<TraceRecord> traces = new CopyOnWriteArrayList<>();

    @Override
    public boolean available() {
        return available;
    }

    @Override
    public void upsertDataset(String name, String description) {
        datasets.add(name);
    }

    @Override
    public void upsertItem(String datasetName, String itemId, Map<String, Object> input,
                           Map<String, Object> expectedOutput, Map<String, Object> metadata) {
        itemIds.add(itemId);
    }

    @Override
    public void recordTrace(TraceRecord trace) {
        traces.add(trace);
    }

    @Override
    public void flush() {
        flushes++;
        if (failFlush) throw new EvalReportException("boom");
    }
}
```

`EvalRunServiceTest.java`：

```java
package com.kwiki.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwiki.infrastructure.redis.KwikiDistributedLocks;
import com.kwiki.rag.retrieval.ChunkHit;
import com.kwiki.rag.retrieval.QaRetrievalBudgets;
import com.kwiki.security.CurrentUser;
import com.kwiki.testutil.StandardTestProperties;
import com.kwiki.wiki.access.AuthorizationScope;
import com.kwiki.wiki.access.AuthorizationScopeResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EvalRunServiceTest {
    @TempDir
    Path reports;
    final CurrentUser admin = new CurrentUser(1L, "admin", true);
    final EvalDatasetLoader loader = mock(EvalDatasetLoader.class);
    final BaselineRetrievalProbe baseline = mock(BaselineRetrievalProbe.class);
    final WorkflowProbe workflow = mock(WorkflowProbe.class);
    final AuthorizationScopeResolver scopes = mock(AuthorizationScopeResolver.class);
    final EvalCorpusService corpus = mock(EvalCorpusService.class);
    final InMemoryReport report = new InMemoryReport();

    static ChunkHit hit(String key, String content) {
        return new ChunkHit(key, "P" + key, 7, "PAGE", 1, 1L, "heading", 0, content.length(), content);
    }

    static EvalCase positive(String id, String query) {
        return new EvalCase(id, "dragonball", 50, "事实性问题", "Finance", query,
                List.of(List.of("10000吨")), false, List.of(5), List.of());
    }

    EvalRunService service() {
        when(scopes.resolve(eq(admin), eq(Set.of(7L)), eq(Set.of())))
                .thenReturn(new AuthorizationScope(1L, false, Set.of(7L), Map.of()));
        return new EvalRunService(loader, baseline, workflow, scopes, corpus, report,
                new EvalReportWriter(new ObjectMapper(), reports.toString()),
                new KwikiDistributedLocks(StandardTestProperties.nullProvider()),
                new QaRetrievalBudgets(20, 8, 50, 20, 8, 16, 12000, 24000, 24000),
                2, List.of("无法回答", "没有相关信息"));
    }

    void stubCases() {
        var hit = hit("C1", "排放量为10,000吨");
        when(loader.load()).thenReturn(List.of(
                positive("p-1", "排放量是多少"),
                positive("p-err", "会失败的问题"),
                new EvalCase("n-1", "dragonball", 50, "无关无解问", "Finance", "设立了什么子公司",
                        List.of(), true, List.of(5), List.of())));
        when(baseline.probe(eq("排放量是多少"), any())).thenReturn(new BaselineRetrievalProbe.Result(
                List.of(hit), List.of(), List.of(hit), List.of(hit), List.of()));
        when(baseline.probe(eq("会失败的问题"), any())).thenThrow(new IllegalStateException("es down"));
        when(baseline.probe(eq("设立了什么子公司"), any())).thenReturn(new BaselineRetrievalProbe.Result(
                List.of(), List.of(), List.of(), List.of(), List.of()));
        when(workflow.run(eq(admin), eq(7L), eq("排放量是多少"), any(Duration.class))).thenReturn(
                new WorkflowProbe.Result("completed", null, "排放量为1万吨", "排放量为10,000吨", List.of()));
        when(workflow.run(eq(admin), eq(7L), eq("设立了什么子公司"), any(Duration.class))).thenReturn(
                new WorkflowProbe.Result("insufficient", null, "知识库中没有相关信息，我无法进行回答", "", List.of()));
    }

    @Test
    void runScoresReportsAndWritesLocalReport() throws Exception {
        stubCases();

        var status = service().runNow(admin, new EvalRunService.StartRequest("50q", 7L, "run-a", "基线", false));

        assertThat(status.state()).isEqualTo(EvalRunService.State.COMPLETED);
        assertThat(status.total()).isEqualTo(3);
        assertThat(status.completed()).isEqualTo(3);
        EvalSummary summary = status.summary();
        assertThat(summary.errors()).isEqualTo(1);
        assertThat(summary.scored()).isEqualTo(1);
        assertThat(summary.overall().get("pool@50").fullHit()).isEqualTo(1.0);
        assertThat(summary.overall().get("answer").fullHit()).isEqualTo(0.0); // 回答写成「1万吨」，未命中别名
        assertThat(summary.lossMatrix().get("事实性问题")).containsEntry("GENERATION_MISS", 1);
        assertThat(summary.refusalAccuracy()).isEqualTo(1.0);
        assertThat(report.traces).hasSize(3);
        assertThat(report.traces).extracting(EvalReportPort.TraceRecord::datasetItemId)
                .contains("kwiki-rag-50q:p-1");
        assertThat(report.traces).extracting(EvalReportPort.TraceRecord::runName).containsOnly("run-a");
        assertThat(report.flushes).isEqualTo(1);
        Path file = Path.of(status.reportPath());
        assertThat(file).exists();
        assertThat(Files.readString(file)).contains("\"runName\" : \"run-a\"").contains("p-err");
    }

    @Test
    void localOnlyRunNeverTouchesLangfuse() {
        stubCases();
        report.available = false;

        var status = service().runNow(admin, new EvalRunService.StartRequest("50q", 7L, null, null, true));

        assertThat(status.state()).isEqualTo(EvalRunService.State.COMPLETED);
        assertThat(status.runName()).startsWith("50q-");
        assertThat(report.traces).isEmpty();
        assertThat(report.flushes).isZero();
    }

    @Test
    void langfuseFailureMarksReportPartialButKeepsLocalReport() {
        stubCases();
        report.failFlush = true;

        var status = service().runNow(admin, new EvalRunService.StartRequest("50q", 7L, "run-b", null, false));

        assertThat(status.state()).isEqualTo(EvalRunService.State.REPORT_PARTIAL);
        assertThat(Path.of(status.reportPath())).exists();
    }

    @Test
    void refusesWithoutCredentialsUnlessLocalOnly() {
        report.available = false;
        assertThatThrownBy(() -> service().start(admin, new EvalRunService.StartRequest("50q", 7L, null, null, false)))
                .isInstanceOf(EvalException.class).hasMessageContaining("langfuse-not-configured");
    }

    @Test
    void rejectsUnknownDatasetAndMissingKb() {
        assertThatThrownBy(() -> service().start(admin, new EvalRunService.StartRequest("300q", 7L, null, null, true)))
                .isInstanceOf(EvalException.class);
        assertThatThrownBy(() -> service().start(admin, new EvalRunService.StartRequest("50q", null, null, null, true)))
                .isInstanceOf(EvalException.class).hasMessageContaining("kbId-required");
    }

    @Test
    void secondRunIsRejectedWhileOneIsInProgress() throws Exception {
        stubCases();
        var gate = new CountDownLatch(1);
        when(workflow.run(eq(admin), eq(7L), eq("排放量是多少"), any(Duration.class))).thenAnswer(invocation -> {
            gate.await(5, TimeUnit.SECONDS);
            return new WorkflowProbe.Result("completed", null, "", "", List.of());
        });
        var service = service();

        var first = service.start(admin, new EvalRunService.StartRequest("50q", 7L, "run-c", null, true));
        assertThat(first.state()).isEqualTo(EvalRunService.State.RUNNING);
        assertThatThrownBy(() -> service.start(admin, new EvalRunService.StartRequest("50q", 7L, "run-d", null, true)))
                .isInstanceOf(EvalException.class).hasMessageContaining("eval-busy");
        gate.countDown();
        for (int i = 0; i < 50 && service.status(first.runId()).state() == EvalRunService.State.RUNNING; i++) {
            Thread.sleep(100);
        }
        assertThat(service.status(first.runId()).state()).isEqualTo(EvalRunService.State.COMPLETED);
    }

    @Test
    void corpusNotReadyBlocksTheRun() {
        when(loader.load()).thenReturn(List.of(positive("p-1", "排放量是多少")));
        org.mockito.Mockito.doThrow(new EvalException(409, "corpus-not-ready: 1 pages not indexed"))
                .when(corpus).requireReady(admin, 7L);
        assertThatThrownBy(() -> service().start(admin, new EvalRunService.StartRequest("50q", 7L, null, null, true)))
                .hasMessageContaining("corpus-not-ready");
    }

    @Test
    void unknownRunIdIsNotFound() {
        assertThatThrownBy(() -> service().status("nope")).isInstanceOf(EvalException.class)
                .hasMessageContaining("eval-run-not-found");
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./mvnw -q -Dtest='EvalRunServiceTest,EvalDatasetSyncServiceTest' test`
Expected: 编译失败。

- [ ] **Step 3: 实现 EvalReportWriter 与 EvalDatasetSyncService**

`EvalReportWriter.java`：

```java
package com.kwiki.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 每个 run 写一份本地 JSON 报告：配置快照、汇总与逐题明细。 */
@Component
public class EvalReportWriter {

    private final ObjectMapper mapper;
    private final Path directory;

    public EvalReportWriter(ObjectMapper mapper,
                            @Value("${kwiki.eval.report-dir:./eval-reports}") String directory) {
        this.mapper = mapper;
        this.directory = Path.of(directory);
    }

    public Path write(String runName, Map<String, Object> config, EvalSummary summary, List<CaseResult> results) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("runName", runName);
        report.put("config", config);
        report.put("summary", summary);
        report.put("cases", results);
        try {
            Files.createDirectories(directory);
            Path file = directory.resolve(runName.replaceAll("[^A-Za-z0-9._-]", "_") + ".json");
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), report);
            return file.toAbsolutePath();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write eval report", e);
        }
    }
}
```

`EvalDatasetSyncService.java`：

```java
package com.kwiki.eval;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 把仓库 jsonl 按 tier 嵌套同步为三个 Langfuse 数据集；条目 id 为 {datasetName}:{caseId}，幂等。 */
@Service
public class EvalDatasetSyncService {

    private final EvalDatasetLoader loader;
    private final EvalReportPort report;

    public EvalDatasetSyncService(EvalDatasetLoader loader, EvalReportPort report) {
        this.loader = loader;
        this.report = report;
    }

    public int sync() {
        if (!report.available()) throw new EvalException(503, "langfuse-not-configured");
        List<EvalCase> all = loader.load();
        int items = 0;
        for (EvalDatasetSize size : EvalDatasetSize.values()) {
            report.upsertDataset(size.datasetName(), "kwiki RAG 召回评测 " + size.label());
            for (EvalCase evalCase : EvalDatasetLoader.select(all, size)) {
                report.upsertItem(size.datasetName(), itemId(size, evalCase), input(evalCase),
                        expected(evalCase), metadata(evalCase));
                items++;
            }
        }
        return items;
    }

    static String itemId(EvalDatasetSize size, EvalCase evalCase) {
        return size.datasetName() + ":" + evalCase.id();
    }

    private static Map<String, Object> input(EvalCase evalCase) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("query", evalCase.query());
        input.put("category", evalCase.category());
        input.put("domain", evalCase.domain());
        return input;
    }

    private static Map<String, Object> expected(EvalCase evalCase) {
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("keywords", evalCase.keywords());
        expected.put("negative", evalCase.negative());
        return expected;
    }

    private static Map<String, Object> metadata(EvalCase evalCase) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", evalCase.source());
        metadata.put("tier", evalCase.tier());
        metadata.put("sourceDocIds", evalCase.sourceDocIds());
        metadata.put("keypoints", evalCase.keypoints());
        return metadata;
    }
}
```

- [ ] **Step 4: 实现 EvalRunService**

```java
package com.kwiki.eval;

import com.kwiki.infrastructure.redis.KwikiDistributedLocks;
import com.kwiki.rag.retrieval.ChunkHit;
import com.kwiki.rag.retrieval.QaRetrievalBudgets;
import com.kwiki.rag.retrieval.ScopeFilter;
import com.kwiki.security.CurrentUser;
import com.kwiki.wiki.access.AuthorizationScopeResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一次评测 run 的编排：校验 → 语料就绪检查 → 单实例互斥 → 逐题（有界并发）跑基线检索与完整工作流 →
 * 分层计分 → 上报 Langfuse → 写本地报告。单题失败只记错误继续；上报失败标记 REPORT_PARTIAL。
 * run 状态只在内存中，应用重启后进行中的 run 视为中断。
 */
@Service
public class EvalRunService {

    private static final Logger log = LoggerFactory.getLogger(EvalRunService.class);
    static final Duration CASE_TIMEOUT = Duration.ofSeconds(200);
    private static final DateTimeFormatter RUN_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");

    public record StartRequest(String dataset, Long kbId, String runName, String notes, boolean localOnly) {
    }

    public enum State { RUNNING, COMPLETED, REPORT_PARTIAL, FAILED }

    public record RunStatus(String runId, String runName, String dataset, State state, int total, int completed,
                            EvalSummary summary, String reportPath, String error) {
    }

    private static final class RunState {
        final String runId = UUID.randomUUID().toString();
        final String runName;
        final EvalDatasetSize size;
        final int total;
        final AtomicInteger completed = new AtomicInteger();
        volatile State state = State.RUNNING;
        volatile EvalSummary summary;
        volatile String reportPath;
        volatile String error;

        RunState(String runName, EvalDatasetSize size, int total) {
            this.runName = runName;
            this.size = size;
            this.total = total;
        }

        RunStatus status() {
            return new RunStatus(runId, runName, size.label(), state, total, completed.get(), summary,
                    reportPath, error);
        }
    }

    private final EvalDatasetLoader loader;
    private final BaselineRetrievalProbe baseline;
    private final WorkflowProbe workflow;
    private final AuthorizationScopeResolver scopes;
    private final EvalCorpusService corpus;
    private final EvalReportPort report;
    private final EvalReportWriter writer;
    private final KwikiDistributedLocks locks;
    private final QaRetrievalBudgets budgets;
    private final int concurrency;
    private final RefusalDetector refusal;
    private final ExecutorService runner = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("eval-run-", 0).daemon(true).factory());
    private final AtomicBoolean busy = new AtomicBoolean();
    private final Map<String, RunState> runs = new ConcurrentHashMap<>();

    @Autowired
    public EvalRunService(EvalDatasetLoader loader, BaselineRetrievalProbe baseline, WorkflowProbe workflow,
                          AuthorizationScopeResolver scopes, EvalCorpusService corpus, EvalReportPort report,
                          EvalReportWriter writer, KwikiDistributedLocks locks, QaRetrievalBudgets budgets,
                          @Value("${kwiki.eval.concurrency:2}") int concurrency,
                          @Value("${kwiki.eval.refusal-phrases:无法回答,信息不足,未找到相关,没有相关信息}")
                          String refusalPhrases) {
        this(loader, baseline, workflow, scopes, corpus, report, writer, locks, budgets, concurrency,
                Arrays.stream(refusalPhrases.split(",")).map(String::trim).toList());
    }

    EvalRunService(EvalDatasetLoader loader, BaselineRetrievalProbe baseline, WorkflowProbe workflow,
                   AuthorizationScopeResolver scopes, EvalCorpusService corpus, EvalReportPort report,
                   EvalReportWriter writer, KwikiDistributedLocks locks, QaRetrievalBudgets budgets,
                   int concurrency, List<String> refusalPhrases) {
        this.loader = loader;
        this.baseline = baseline;
        this.workflow = workflow;
        this.scopes = scopes;
        this.corpus = corpus;
        this.report = report;
        this.writer = writer;
        this.locks = locks;
        this.budgets = budgets;
        this.concurrency = Math.max(1, Math.min(concurrency, 8));
        this.refusal = new RefusalDetector(refusalPhrases);
    }

    /** 校验后异步启动；同一时刻只允许一个 run。 */
    public RunStatus start(CurrentUser user, StartRequest request) {
        Prepared prepared = prepare(user, request);
        if (!busy.compareAndSet(false, true)) throw new EvalException(409, "eval-busy");
        runs.put(prepared.state.runId, prepared.state);
        try {
            runner.submit(() -> {
                try {
                    execute(user, request, prepared);
                } finally {
                    busy.set(false);
                }
            });
        } catch (RuntimeException e) {
            busy.set(false);
            throw e;
        }
        return prepared.state.status();
    }

    public RunStatus status(String runId) {
        RunState state = runs.get(runId);
        if (state == null) throw new EvalException(404, "eval-run-not-found");
        return state.status();
    }

    /** 同步执行（测试用），不经过互斥标志。 */
    RunStatus runNow(CurrentUser user, StartRequest request) {
        Prepared prepared = prepare(user, request);
        runs.put(prepared.state.runId, prepared.state);
        execute(user, request, prepared);
        return prepared.state.status();
    }

    private record Prepared(RunState state, List<EvalCase> cases) {
    }

    private Prepared prepare(CurrentUser user, StartRequest request) {
        EvalDatasetSize size;
        try {
            size = EvalDatasetSize.parse(request.dataset());
        } catch (IllegalArgumentException e) {
            throw new EvalException(400, "unknown-dataset");
        }
        if (request.kbId() == null) throw new EvalException(400, "kbId-required");
        if (!request.localOnly() && !report.available()) throw new EvalException(503, "langfuse-not-configured");
        List<EvalCase> cases = EvalDatasetLoader.select(loader.load(), size);
        if (cases.isEmpty()) throw new EvalException(409, "eval-dataset-empty");
        corpus.requireReady(user, request.kbId());
        String runName = request.runName() == null || request.runName().isBlank()
                ? size.label() + "-" + RUN_TIME.format(LocalDateTime.now())
                : request.runName();
        return new Prepared(new RunState(runName, size, cases.size()), cases);
    }

    private void execute(CurrentUser user, StartRequest request, Prepared prepared) {
        RunState state = prepared.state;
        AutoCloseable lock = null;
        try {
            if (locks.isAvailable()) {
                lock = locks.acquire("eval-run", Duration.ZERO);
                if (lock == null) {
                    state.error = "eval-busy";
                    state.state = State.FAILED;
                    return;
                }
            }
            ScopeFilter filter = ScopeFilter.from(scopes.resolve(user, Set.of(request.kbId()), Set.of()));
            Map<String, Object> config = configSnapshot(request, state);
            List<CaseResult> results = evaluateAll(user, request, prepared, filter, config);
            EvalSummary summary = EvalSummaries.summarize(results);
            boolean partial = false;
            if (!request.localOnly()) {
                try {
                    report.flush();
                } catch (RuntimeException e) {
                    partial = true;
                    log.warn("eval report upload incomplete run={} reason={}", state.runName, e.getMessage());
                }
            }
            Path file = writer.write(state.runName, config, summary, results);
            state.summary = summary;
            state.reportPath = file.toString();
            state.state = partial ? State.REPORT_PARTIAL : State.COMPLETED;
        } catch (Exception e) {
            state.error = e.getClass().getSimpleName() + ": " + e.getMessage();
            state.state = State.FAILED;
            log.warn("eval run failed run={} reason={}", state.runName, state.error);
        } finally {
            if (lock != null) {
                try {
                    lock.close();
                } catch (Exception ignored) {
                    // 锁租期到期后 ownership 检查会阻止误解锁
                }
            }
        }
    }

    private List<CaseResult> evaluateAll(CurrentUser user, StartRequest request, Prepared prepared,
                                         ScopeFilter filter, Map<String, Object> config) throws Exception {
        List<EvalCase> cases = prepared.cases;
        CaseResult[] results = new CaseResult[cases.size()];
        ExecutorService pool = Executors.newFixedThreadPool(concurrency,
                Thread.ofPlatform().name("eval-case-", 0).daemon(true).factory());
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < cases.size(); i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    CaseResult result = evaluate(user, request.kbId(), filter, cases.get(index));
                    results[index] = result;
                    if (!request.localOnly()) {
                        report.recordTrace(trace(prepared.state, cases.get(index), result, config));
                    }
                    prepared.state.completed.incrementAndGet();
                }));
            }
            for (Future<?> future : futures) future.get();
        } finally {
            pool.shutdownNow();
        }
        return List.of(results);
    }

    CaseResult evaluate(CurrentUser user, long kbId, ScopeFilter filter, EvalCase evalCase) {
        long started = System.nanoTime();
        try {
            BaselineRetrievalProbe.Result base = baseline.probe(evalCase.query(), filter);
            WorkflowProbe.Result run = workflow.run(user, kbId, evalCase.query(), CASE_TIMEOUT);
            Map<EvalLayer, List<String>> segments = new EnumMap<>(EvalLayer.class);
            Map<EvalLayer, List<String>> keys = new EnumMap<>(EvalLayer.class);
            base.byLayer().forEach((layer, hits) -> {
                segments.put(layer, hits.stream().map(ChunkHit::content).toList());
                keys.put(layer, hits.stream().map(ChunkHit::chunkKey).toList());
            });
            segments.put(EvalLayer.CONTEXT, List.of(run.context() == null ? "" : run.context()));
            segments.put(EvalLayer.ANSWER, List.of(run.answer() == null ? "" : run.answer()));
            CaseScore score = evalCase.negative() ? null : LayerScorer.score(evalCase.keywords(), segments);
            Boolean refused = evalCase.negative() ? refusal.refused(run.outcome(), run.answer()) : null;
            return new CaseResult(evalCase, score, refused, run.outcome(), run.answer(), run.rewrites(), keys,
                    run.errorCode(), elapsedMs(started));
        } catch (RuntimeException e) {
            String reason = e.getClass().getSimpleName() + ": " + e.getMessage();
            return new CaseResult(evalCase, null, null, null, "", List.of(), Map.of(),
                    reason.length() > 300 ? reason.substring(0, 300) : reason, elapsedMs(started));
        }
    }

    private EvalReportPort.TraceRecord trace(RunState state, EvalCase evalCase, CaseResult result,
                                             Map<String, Object> config) {
        String traceId = UUID.nameUUIDFromBytes((state.runName + ":" + evalCase.id())
                .getBytes(StandardCharsets.UTF_8)).toString();
        Instant now = Instant.now();
        List<EvalReportPort.SpanRecord> spans = new ArrayList<>();
        List<EvalReportPort.ScoreRecord> scores = new ArrayList<>();
        if (result.error()) {
            scores.add(new EvalReportPort.ScoreRecord("error", 1, result.errorCode()));
        } else if (evalCase.negative()) {
            scores.add(new EvalReportPort.ScoreRecord("refusal",
                    Boolean.TRUE.equals(result.refusedCorrectly()) ? 1 : 0, result.outcome()));
        } else {
            for (EvalLayer layer : EvalLayer.values()) {
                Map<String, Object> output = new LinkedHashMap<>();
                output.put("matched", aliases(evalCase, result.score().matched(layer)));
                output.put("chunkKeys", result.chunkKeys().getOrDefault(layer, List.of()));
                spans.add(new EvalReportPort.SpanRecord(
                        UUID.nameUUIDFromBytes((traceId + ":" + layer.metric()).getBytes(StandardCharsets.UTF_8))
                                .toString(),
                        layer.metric(), now, now, Map.of("query", evalCase.query()), output));
                scores.add(new EvalReportPort.ScoreRecord("coverage@" + layer.metric(),
                        result.score().coverage(layer), null));
                scores.add(new EvalReportPort.ScoreRecord("fullHit@" + layer.metric(),
                        result.score().fullHit(layer) ? 1 : 0, null));
            }
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("category", evalCase.category());
        metadata.put("domain", evalCase.domain());
        metadata.put("source", evalCase.source());
        metadata.put("tier", evalCase.tier());
        metadata.put("rewrites", result.rewrites());
        if (result.score() != null) {
            Map<String, String> losses = new LinkedHashMap<>();
            result.score().losses().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                    losses.put(evalCase.keywords().get(entry.getKey()).get(0), entry.getValue().name()));
            metadata.put("losses", losses);
            metadata.put("contextGains", result.score().contextGains().size());
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("outcome", result.outcome());
        output.put("answer", result.answer());
        return new EvalReportPort.TraceRecord(traceId, "eval:" + evalCase.id(),
                Map.of("query", evalCase.query()), output, metadata, List.of("rag-eval", state.size.label()),
                spans, scores, EvalDatasetSyncService.itemId(state.size, evalCase), state.runName, config);
    }

    private static List<String> aliases(EvalCase evalCase, Set<Integer> groups) {
        return groups.stream().sorted(Comparator.naturalOrder())
                .map(group -> evalCase.keywords().get(group).get(0)).toList();
    }

    private Map<String, Object> configSnapshot(StartRequest request, RunState state) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("dataset", state.size.label());
        config.put("kbId", request.kbId());
        config.put("notes", request.notes() == null ? "" : request.notes());
        config.put("baseBranchTopK", budgets.baseBranchTopK);
        config.put("baseFinalTopK", budgets.baseFinalTopK);
        config.put("expandedBranchTopK", budgets.expandedBranchTopK);
        config.put("expandedFinalTopK", budgets.expandedFinalTopK);
        config.put("baseParentLimit", budgets.baseParentLimit);
        config.put("expandedParentLimit", budgets.expandedParentLimit);
        config.put("concurrency", concurrency);
        config.put("localOnly", request.localOnly());
        return config;
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
```

- [ ] **Step 5: 实现管理接口**

`EvalAdminController.java`：

```java
package com.kwiki.eval;

import com.kk2004.common.response.TransDTO;
import com.kwiki.security.CurrentUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** RAG 召回评测管理接口；类级角色校验是全部命令的服务端边界。 */
@RestController
@RequestMapping("/api/v1/admin/eval")
@PreAuthorize("hasRole('ADMIN')")
public class EvalAdminController {

    private final EvalCorpusService corpus;
    private final EvalDatasetSyncService datasets;
    private final EvalRunService runs;

    public EvalAdminController(EvalCorpusService corpus, EvalDatasetSyncService datasets, EvalRunService runs) {
        this.corpus = corpus;
        this.datasets = datasets;
        this.runs = runs;
    }

    @PostMapping("/corpus/import")
    TransDTO<EvalCorpusService.ImportResult> importCorpus(@AuthenticationPrincipal CurrentUser user,
                                                          @RequestParam long kbId,
                                                          @RequestParam(defaultValue = "dragonball-zh") String corpus) {
        return TransDTO.success(this.corpus.importCorpus(user, kbId, corpus));
    }

    @PostMapping("/datasets/sync")
    TransDTO<Map<String, Integer>> syncDatasets() {
        return TransDTO.success(Map.of("items", datasets.sync()));
    }

    @PostMapping("/runs")
    TransDTO<EvalRunService.RunStatus> start(@AuthenticationPrincipal CurrentUser user,
                                             @RequestBody EvalRunService.StartRequest request) {
        return TransDTO.success(runs.start(user, request));
    }

    @GetMapping("/runs/{runId}")
    TransDTO<EvalRunService.RunStatus> status(@PathVariable String runId) {
        return TransDTO.success(runs.status(runId));
    }
}
```

在 `.gitignore` 末尾追加一行：

```
eval-reports/
```

- [ ] **Step 6: 运行确认通过**

Run: `./mvnw -q -Dtest='EvalRunServiceTest,EvalDatasetSyncServiceTest,KwikiApplicationTest' test`
Expected: PASS。若 `KwikiApplicationTest` 因测试上下文缺少 `WikiTreeService`/`PageRevisionService` Bean 失败，查看 `src/test/java/com/kwiki/testutil/WikiMockBeans.java` 的现有做法，为缺失 Bean 增加同样的 `@MockitoBean` 声明，不要改生产代码的依赖。

- [ ] **Step 7: 提交**

```bash
git add src/main/java/com/kwiki/eval src/test/java/com/kwiki/eval .gitignore
git commit -m "feat(eval): 评测 run 编排（有界并发、单题容错、单实例互斥）、本地 JSON 报告、Langfuse 数据集同步与管理接口"
```

---

### Task 8: 分层抽样与 200 题测试集

**Files:**
- Create: `tools/eval/sample-dragonball.mjs`
- Create（生成）: `src/main/resources/eval/datasets/dragonball-sample.json`
- Create（编写）: `src/main/resources/eval/datasets/kwiki-rag.jsonl`
- Create: `src/main/resources/eval/datasets/README.md`
- Test: `src/test/java/com/kwiki/eval/EvalDatasetFileTest.java`

**Interfaces:**
- Consumes: `EvalDatasetLoader`、`EvalDatasetSize`、`KeywordMatcher`（Task 2）；`src/main/resources/eval/corpus/dragonball-zh/manifest.json` 与 `*.md`（Task 6）；Task 6 Step 2 下载的 `dragonball_queries.jsonl`。
- Produces: `kwiki-rag.jsonl`（200 行，字段见 Task 2 `EvalCase`），供 Task 7 的 run 与同步使用。

- [ ] **Step 1: 写抽样脚本**

`tools/eval/sample-dragonball.mjs`：

```js
#!/usr/bin/env node
// DragonBall 中文 query 分层抽样（固定种子，可复现）：按题型配额抽 200 题，并按 50 ⊂ 100 ⊂ 200 分配 tier。
// 用法：node tools/eval/sample-dragonball.mjs <dragonball_queries.jsonl> <输出 json>
import { readFileSync, writeFileSync } from 'node:fs';

const [input, output] = process.argv.slice(2);
if (!input || !output) {
  console.error('usage: sample-dragonball.mjs <dragonball_queries.jsonl> <out.json>');
  process.exit(2);
}
const SEED = 20261020;
// 同名主体文档（doc 4 与 doc 24 均为「创新科技有限公司」），引用它们的题答案有歧义，排除
const EXCLUDED_DOC_IDS = new Set([4, 24]);
// 每个题型在 tier 50 / 100 / 200 的累计配额
const QUOTAS = {
  '无关无解问': [4, 8, 16],
  '事实性问题': [10, 21, 42],
  '多跳推理问题': [8, 15, 30],
  '总结性问题': [9, 17, 35],
  '多文档信息整合问题': [10, 21, 42],
  '多文档对比问题': [6, 11, 22],
  '多文档时间序列问题': [3, 7, 13],
};
const DOMAIN_SHARE = { Finance: 40, Law: 30, Medical: 38 };
const DOMAIN_CODE = { Finance: 'fin', Law: 'law', Medical: 'med' };

function mulberry32(seed) {
  let a = seed;
  return () => {
    a |= 0; a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
const rand = mulberry32(SEED);
function shuffle(list) {
  const items = [...list];
  for (let i = items.length - 1; i > 0; i--) {
    const j = Math.floor(rand() * (i + 1));
    [items[i], items[j]] = [items[j], items[i]];
  }
  return items;
}

const pools = {};
for (const type of Object.keys(QUOTAS)) pools[type] = { Finance: [], Law: [], Medical: [] };
for (const line of readFileSync(input, 'utf8').split('\n')) {
  if (!line.trim()) continue;
  const row = JSON.parse(line);
  if (row.language !== 'zh') continue;
  const type = row.query.query_type;
  if (!(type in QUOTAS)) continue;
  const docIds = row.ground_truth.doc_ids;
  if (docIds.some((id) => EXCLUDED_DOC_IDS.has(id))) continue;
  const negative = type === '无关无解问';
  if (!negative && row.ground_truth.references.length === 0) continue;
  pools[type][row.domain].push(row);
}
for (const type of Object.keys(pools)) {
  for (const domain of Object.keys(DOMAIN_SHARE)) {
    pools[type][domain].sort((a, b) => a.query.query_id - b.query.query_id);
    pools[type][domain] = shuffle(pools[type][domain]);
  }
}

const cases = [];
for (const [type, [q50, q100, q200]] of Object.entries(QUOTAS)) {
  const picked = { Finance: 0, Law: 0, Medical: 0 };
  for (let index = 0; index < q200; index++) {
    // 每一步选「已选数 / 占比」最小且仍有余量的领域，保证任意前缀的领域分布都接近语料比例
    const domain = Object.keys(DOMAIN_SHARE)
      .filter((d) => pools[type][d].length > 0)
      .sort((a, b) => (picked[a] + 1) / DOMAIN_SHARE[a] - (picked[b] + 1) / DOMAIN_SHARE[b])[0];
    if (!domain) throw new Error(`not enough queries for ${type}`);
    const row = pools[type][domain].shift();
    picked[domain]++;
    const tier = index < q50 ? 50 : index < q100 ? 100 : 200;
    cases.push({
      id: `db-zh-${DOMAIN_CODE[domain]}-${String(row.query.query_id).padStart(4, '0')}`,
      tier,
      category: type,
      domain,
      query: row.query.content,
      negative: type === '无关无解问',
      sourceDocIds: row.ground_truth.doc_ids,
      answer: row.ground_truth.content,
      references: row.ground_truth.references,
      keypoints: row.ground_truth.keypoints,
    });
  }
}
const typeOrder = Object.keys(QUOTAS);
cases.sort((a, b) => a.tier - b.tier || typeOrder.indexOf(a.category) - typeOrder.indexOf(b.category)
  || a.id.localeCompare(b.id));
writeFileSync(output, `${JSON.stringify({ seed: SEED, quotas: QUOTAS, cases }, null, 2)}\n`, 'utf8');
console.log(`sampled ${cases.length} cases`);
```

- [ ] **Step 2: 生成抽样文件**

```bash
node tools/eval/sample-dragonball.mjs "$DL/dragonball_queries.jsonl" src/main/resources/eval/datasets/dragonball-sample.json
```

（`$DL` 为 Task 6 Step 2 的下载目录；若已丢失，按 Task 6 Step 2 的 curl 命令重新下载到新的空目录。）
Expected: `sampled 200 cases`。连续运行两次，`git diff --stat` 应无变化（可复现）。

- [ ] **Step 3: 写数据集校验失败测试**

`src/test/java/com/kwiki/eval/EvalDatasetFileTest.java`：

```java
package com.kwiki.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** 仓库测试集的数据质量门禁：规模、嵌套、题型配额、关键词可在原文找到且不出现在题干中。 */
class EvalDatasetFileTest {

    static final Map<String, int[]> QUOTAS = new LinkedHashMap<>();
    static {
        QUOTAS.put("无关无解问", new int[]{4, 8, 16});
        QUOTAS.put("事实性问题", new int[]{10, 21, 42});
        QUOTAS.put("多跳推理问题", new int[]{8, 15, 30});
        QUOTAS.put("总结性问题", new int[]{9, 17, 35});
        QUOTAS.put("多文档信息整合问题", new int[]{10, 21, 42});
        QUOTAS.put("多文档对比问题", new int[]{6, 11, 22});
        QUOTAS.put("多文档时间序列问题", new int[]{3, 7, 13});
    }

    static List<EvalCase> cases;
    static Map<Integer, String> documents = new HashMap<>();

    record Entry(String file, String title, String domain, int docId) { }

    @BeforeAll
    static void load() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        cases = new EvalDatasetLoader(mapper).load();
        String root = "eval/corpus/dragonball-zh/";
        List<Entry> manifest;
        try (InputStream in = new ClassPathResource(root + "manifest.json").getInputStream()) {
            manifest = mapper.readValue(in, new TypeReference<List<Entry>>() { });
        }
        for (Entry entry : manifest) {
            try (InputStream in = new ClassPathResource(root + entry.file()).getInputStream()) {
                documents.put(entry.docId(), KeywordMatcher.normalize(
                        new String(in.readAllBytes(), StandardCharsets.UTF_8)));
            }
        }
    }

    @Test
    void datasetsAreNestedWithExactSizes() {
        assertThat(EvalDatasetLoader.select(cases, EvalDatasetSize.Q50)).hasSize(50);
        assertThat(EvalDatasetLoader.select(cases, EvalDatasetSize.Q100)).hasSize(100);
        assertThat(EvalDatasetLoader.select(cases, EvalDatasetSize.Q200)).hasSize(200);
    }

    @Test
    void categoryQuotasMatchTheSamplingPlan() {
        EvalDatasetSize[] sizes = EvalDatasetSize.values();
        for (int i = 0; i < sizes.length; i++) {
            Map<String, Long> counts = EvalDatasetLoader.select(cases, sizes[i]).stream()
                    .collect(Collectors.groupingBy(EvalCase::category, Collectors.counting()));
            for (var quota : QUOTAS.entrySet()) {
                assertThat(counts.getOrDefault(quota.getKey(), 0L))
                        .as(sizes[i].label() + " " + quota.getKey())
                        .isEqualTo(quota.getValue()[i]);
            }
        }
    }

    @Test
    void negativesAreExactlyTheUnanswerableCategory() {
        assertThat(cases).allSatisfy(evalCase ->
                assertThat(evalCase.negative()).isEqualTo("无关无解问".equals(evalCase.category())));
    }

    @Test
    void everyKeywordGroupAppearsVerbatimInItsSourceDocuments() {
        for (EvalCase evalCase : cases) {
            if (evalCase.negative()) continue;
            String corpus = evalCase.sourceDocIds().stream()
                    .map(id -> documents.getOrDefault(id, "")).collect(Collectors.joining("\u0000"));
            for (List<String> group : evalCase.keywords()) {
                assertThat(group.stream().map(KeywordMatcher::normalize).anyMatch(corpus::contains))
                        .as(evalCase.id() + " keyword group " + group + " must appear in source documents")
                        .isTrue();
            }
        }
    }

    @Test
    void noKeywordAliasAppearsInTheQuery() {
        for (EvalCase evalCase : cases) {
            String query = KeywordMatcher.normalize(evalCase.query());
            for (List<String> group : evalCase.keywords()) {
                for (String alias : group) {
                    assertThat(query.contains(KeywordMatcher.normalize(alias)))
                            .as(evalCase.id() + " alias '" + alias + "' must not appear in the query")
                            .isFalse();
                }
            }
        }
    }

    @Test
    void multiDocumentCasesCoverEverySourceDocument() {
        for (EvalCase evalCase : cases) {
            if (evalCase.negative() || evalCase.sourceDocIds().size() < 2) continue;
            for (Integer docId : evalCase.sourceDocIds()) {
                String document = documents.getOrDefault(docId, "");
                boolean covered = evalCase.keywords().stream().anyMatch(group ->
                        group.stream().map(KeywordMatcher::normalize).anyMatch(document::contains));
                assertThat(covered).as(evalCase.id() + " must have a keyword from doc " + docId).isTrue();
            }
        }
    }
}
```

- [ ] **Step 4: 运行确认失败**

Run: `./mvnw -q -Dtest=EvalDatasetFileTest test`
Expected: FAIL，`cannot read eval dataset`（`kwiki-rag.jsonl` 尚不存在）。

- [ ] **Step 5: 编写 kwiki-rag.jsonl（分 4 批，每批 50 题）**

读取 `dragonball-sample.json` 的 `cases`，按顺序为每题写一行 JSON 到 `src/main/resources/eval/datasets/kwiki-rag.jsonl`，字段：

```json
{"id":"<同 sample>","source":"dragonball","tier":<同 sample>,"category":"<同 sample>","domain":"<同 sample>","query":"<同 sample>","keywords":[[...]],"negative":<同 sample>,"sourceDocIds":[<同 sample>],"keypoints":[<同 sample>]}
```

关键词提炼规则（逐题对照 `references`、`answer`、`keypoints` 与对应语料 md 原文）：

1. 只取回答该题**必需**的信息单元：数字（含单位）、日期、主体名、事件/措施短语；每题 2~6 组，单一事实题可为 1 组。
2. 题干中已出现的词一律不收录（例如题干问「二氧化碳排放量是多少」，不收「二氧化碳排放量」）。
3. 每组至少一个别名必须在来源文档原文中逐字出现（校验会做 NFKC/空白/千分位归一化）；数字与日期补充常见写法别名，如 `["10,000吨","10000吨","1万吨"]`、`["2017年4月","2017年04月"]`。
4. 事件短语取原文中最短的、能区分答案的片段（如「废水泄漏」而不是整句），避免过长导致回答改写后无法命中。
5. 多文档题（`sourceDocIds` 多于 1 个）必须从每篇来源文档至少取 1 组。
6. 负样本：`"keywords":[]`、`"negative":true`。

示例（来自 DragonBall 原始数据）：

```json
{"id":"db-zh-fin-0002","source":"dragonball","tier":50,"category":"事实性问题","domain":"Finance","query":"绿源环保有限公司2017年的二氧化碳排放量是多少？","keywords":[["10,000吨","10000吨","1万吨"]],"negative":false,"sourceDocIds":[5],"keypoints":["1. 绿源环保有限公司2017年的二氧化碳排放量为10,000吨。"]}
{"id":"db-zh-fin-0000","source":"dragonball","tier":50,"category":"无关无解问","domain":"Finance","query":"绿源环保有限公司在2017年12月设立了什么子公司？","keywords":[],"negative":true,"sourceDocIds":[5],"keypoints":["无法回答"]}
```

（上面两行只演示格式；实际 id、tier 以 `dragonball-sample.json` 为准。）

每完成 50 题运行一次 Step 6 的测试，按失败信息修正（找不到原文 → 换成原文写法；出现在题干 → 换成其他必需信息单元）。

- [ ] **Step 6: 运行确认通过**

Run: `./mvnw -q -Dtest='EvalDatasetFileTest,EvalDatasetLoaderTest' test`
Expected: PASS（6 个数据门禁全部通过）。

- [ ] **Step 7: 写数据集 README**

`src/main/resources/eval/datasets/README.md`：

```markdown
# kwiki RAG 召回评测测试集

- `kwiki-rag.jsonl`：唯一事实来源，200 题；`tier` 为首次纳入层级，50q ⊂ 100q ⊂ 200q。
- `dragonball-sample.json`：抽样中间产物（含原始 references/answer），用于审阅关键词。
- 来源与许可：见 `../corpus/dragonball-zh/NOTICE.md`（CC BY-NC-SA 4.0，仅内部评测）。

## 复现抽样

    node tools/eval/sample-dragonball.mjs <dragonball_queries.jsonl> src/main/resources/eval/datasets/dragonball-sample.json

固定种子 20261020；排除引用 doc 4 / doc 24（同名公司）的题与非负样本中 references 为空的题。
题型配额（tier 50/100/200 累计）：无关无解问 4/8/16，事实性问题 10/21/42，多跳推理问题 8/15/30，
总结性问题 9/17/35，多文档信息整合问题 10/21/42，多文档对比问题 6/11/22，多文档时间序列问题 3/7/13。

## 关键词规则

每题 1~6 个关键词组，组内为别名；必须能在来源文档原文找到，且不得出现在题干中；多文档题每篇来源至少一组。
`EvalDatasetFileTest` 会校验以上全部规则。

## 追加自建题

自建文档（PDF/多模态）上传到同一评测知识库后，按相同格式追加行，`source` 设为 `custom`、`sourceDocIds` 置空，
并确保关键词能在文档解析后的文本中找到。自建题不参与 DragonBall 配额校验时，需同步调整 `EvalDatasetFileTest`。
```

- [ ] **Step 8: 提交**

```bash
git add tools/eval/sample-dragonball.mjs src/main/resources/eval/datasets src/test/java/com/kwiki/eval/EvalDatasetFileTest.java
git commit -m "feat(eval): DragonBall 中文分层抽样脚本与 200 题嵌套测试集，数据门禁校验关键词出自原文且不在题干"
```

---

### Task 9: 运维手册与真实 Langfuse 冒烟测试

**Files:**
- Create: `docs/rag-eval.md`
- Create: `src/test/java/com/kwiki/infrastructure/langfuse/LangfuseRestClientLiveTest.java`

**Interfaces:**
- Consumes: Task 5 `LangfuseRestClient`；Task 7 接口。

- [ ] **Step 1: 写真实环境冒烟测试（无凭据时跳过）**

```java
package com.kwiki.infrastructure.langfuse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwiki.eval.EvalReportPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 只在提供 Langfuse 凭据时运行：创建冒烟数据集、条目并上报一条 trace。 */
@EnabledIfEnvironmentVariable(named = "KWIKI_IT_LANGFUSE_PUBLIC_KEY", matches = ".+")
class LangfuseRestClientLiveTest {

    @Test
    void uploadsDatasetItemAndTrace() {
        var client = new LangfuseRestClient(
                System.getenv().getOrDefault("KWIKI_IT_LANGFUSE_HOST", "https://cloud.langfuse.com"),
                System.getenv("KWIKI_IT_LANGFUSE_PUBLIC_KEY"),
                System.getenv("KWIKI_IT_LANGFUSE_SECRET_KEY"),
                new ObjectMapper());
        String dataset = "kwiki-rag-smoke";
        String itemId = dataset + ":smoke-1";
        client.upsertDataset(dataset, "kwiki 冒烟测试");
        client.upsertItem(dataset, itemId, Map.of("query", "冒烟问题"),
                Map.of("keywords", List.of(List.of("冒烟"))), Map.of("tier", 50));
        Instant now = Instant.now();
        client.recordTrace(new EvalReportPort.TraceRecord(UUID.randomUUID().toString(), "eval:smoke-1",
                Map.of("query", "冒烟问题"), Map.of("answer", "冒烟回答"), Map.of(), List.of("rag-eval", "smoke"),
                List.of(new EvalReportPort.SpanRecord(UUID.randomUUID().toString(), "answer", now, now,
                        Map.of("query", "冒烟问题"), Map.of("matched", List.of("冒烟")))),
                List.of(new EvalReportPort.ScoreRecord("coverage@answer", 1.0, null)),
                itemId, "smoke-" + now.toEpochMilli(), Map.of("notes", "smoke")));
        client.flush();
    }
}
```

Run（无凭据）：`./mvnw -q -Dtest=LangfuseRestClientLiveTest test`
Expected: 测试被跳过（0 failures）。

- [ ] **Step 2: 写运维手册**

`docs/rag-eval.md`：

```markdown
# RAG 召回评测运行说明

基于关键词的分层召回评测：DragonBall 中文语料（108 篇）+ 200 题嵌套测试集（50q ⊂ 100q ⊂ 200q），
结果写入 Langfuse Cloud 并在本地落 JSON 报告。设计见
`docs/superpowers/specs/2026-10-20-langfuse-rag-eval-design.md`。

## 1. 配置（Nacos 应用配置）

| 键 | 默认 | 说明 |
|---|---|---|
| `kwiki.langfuse.host` | `https://cloud.langfuse.com` | 美区为 `https://us.cloud.langfuse.com` |
| `kwiki.langfuse.public-key` | 空 | Langfuse 项目 Public Key |
| `kwiki.langfuse.secret-key` | 空 | Langfuse 项目 Secret Key（勿入仓库） |
| `kwiki.eval.concurrency` | `2` | 同时评测的题数（1~8），受模型限流约束 |
| `kwiki.eval.report-dir` | `./eval-reports` | 本地报告目录 |
| `kwiki.eval.refusal-phrases` | `无法回答,信息不足,未找到相关,没有相关信息` | 负样本拒答判定短语 |

未配置 key 时应用正常启动；评测只能以 `localOnly=true` 运行。上报的 trace 含文档片段与回答，
评测知识库只放公开数据集与明确同意上传的文档。

## 2. 步骤（均需管理员令牌）

1. 新建一个专用知识库（例如「RAG 评测」），记下 `kbId`。
2. 导入语料：`POST /api/v1/admin/eval/corpus/import?kbId={kbId}&corpus=dragonball-zh`（幂等，可重复调用）。
3. 等待索引完成：评测启动时会检查，未就绪返回 `corpus-not-ready`。
4. 同步测试集到 Langfuse：`POST /api/v1/admin/eval/datasets/sync`，生成 `kwiki-rag-50q/100q/200q`。
5. 启动评测：`POST /api/v1/admin/eval/runs`，body：
   `{"dataset":"50q","kbId":123,"runName":"baseline-50q","notes":"现状基线","localOnly":false}`
6. 查看进度：`GET /api/v1/admin/eval/runs/{runId}`；完成后在 Langfuse 的 Datasets → Runs 中对比。

## 3. 指标

- 每层两个指标：`coverage@{层}`（关键词覆盖率均值）、`fullHit@{层}`（全部关键词命中的题占比）。
- 层：`bm25@50`、`vector@50`、`pool@50`、`fused@8`、`fused@20`（原始 query 基线），`context`、`answer`（完整工作流）。
- 首次丢失层（trace metadata `losses` 与报告 `lossMatrix`）：
  `GENERATION_MISS`（上下文有、回答无）、`RECALL_MISS`（两路 Top-50 都没有）、`RANK_MISS_20`、`RANK_MISS_8`、`CONTEXT_MISS`（在 Top-8 却没进最终上下文）。
- `contextGains`：基线候选池没有、但改写/图增强后进入上下文的关键词组数。
- 负样本 `refusal`：终态 insufficient 或回答含拒答短语记 1。
- 回答层受生成温度（0.2）影响有少量波动，结论性对比建议同配置跑两次。

## 4. 对比改进

每次检索改动后用相同 dataset 跑一次，`notes` 写清改动（如「ik 分词」「contextual chunk」「rerank」），
在 Langfuse 中按 run 对比各层分数，用本地报告的 `lossMatrix` 判断丢失集中在哪一层。

## 5. 维护测试集

见 `src/main/resources/eval/datasets/README.md`。修改后运行 `./mvnw -q -Dtest=EvalDatasetFileTest test`，
再调用一次数据集同步。
```

- [ ] **Step 3: 运行全部评测相关测试**

Run: `./mvnw -q -Dtest='RunContextObserverTest,WorkflowRunObserverTest,KeywordMatcherTest,EvalDatasetLoaderTest,LayerScorerTest,RefusalDetectorTest,EvalSummariesTest,BaselineRetrievalProbeTest,WorkflowProbeTest,LangfuseRestClientTest,LangfuseRestClientLiveTest,EvalCorpusServiceTest,EvalDatasetSyncServiceTest,EvalRunServiceTest,EvalDatasetFileTest,KwikiApplicationTest' test`
Expected: 全部 PASS（Live 测试跳过）。

- [ ] **Step 4: 运行完整离线测试**

Run: `./mvnw -q test`
Expected: PASS；与改动前相比无新增失败。

- [ ] **Step 5: 提交**

```bash
git add docs/rag-eval.md src/test/java/com/kwiki/infrastructure/langfuse/LangfuseRestClientLiveTest.java
git commit -m "docs(eval): RAG 召回评测运行说明与 Langfuse 真实环境冒烟测试（无凭据时跳过）"
```

---

## 交付后（由操作者执行，不属于实现任务）

1. 在 Nacos 配置 Langfuse key，按 `docs/rag-eval.md` 第 2 节导入语料、同步数据集。
2. 跑 `200q` 基线（`notes`：现状基线），把本地报告路径交给 Claude 做召回分析：按 `lossMatrix` 与各层分数定位剩余缺口并给出改进优先级。
