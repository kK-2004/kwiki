# QA 意图修正（reroute）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 QA 阶段在证据明显无关时能把意图改回直答（planner 的 `answer_directly`），固定阶梯在证据无关时跳过 `*_PARENT` 阶段，并用规则直接拦下常见实时信息问句。

**Architecture:** 规则层补 `realtime-*` 规则；planner 新增只在满足门槛时才暴露的 `answer_directly` 工具，由 `QaPlanningService` 返回 `Decision.Reroute`，工作流把 `s.route` 改为 `DIRECT_ANSWER`（来源 `PLANNER`）并跳到已有的 `direct` 节点；改道后的 direct 不能升级回检索。固定阶梯模式只加确定性短路，不自动改道。

**Tech Stack:** Java 21、Spring Boot、LangGraph4j、JUnit 5 + AssertJ、Maven（离线：`./mvnw -q -o -Dtest=... test`）。

## Global Constraints

- 设计依据：`docs/superpowers/specs/2026-10-12-qa-route-correction-design.md`。
- 默认开关不变：`kwiki.agentic.planner-enabled=false`、`kwiki.routing.llm-enabled=false`。
- **不往 `application.yml` 加任何配置**（用户已把非必要配置挪到 Nacos）；新配置 `kwiki.agentic.reroute-relevance-threshold` 的默认值 `0.2` 写在代码的 `@Value` 里。
- 仓库密钥审计（`RepositorySecretsAuditTest`、`scripts/check-no-docker-middleware.sh`）会拒绝匹配 `sk-[A-Za-z0-9_-]{12,}` 的源码字符串：错误码、标识符一律用下划线（如 `answer_directly_must_be_alone`），不要写出以 `ask-` / `task-` 开头的长连字符串。
- 创建测试文件前先 `ls` 目标目录确认不存在同名文件，**绝不覆盖**已有文件（已发生过一次覆盖事故）；本计划的新测试文件名均为新建。
- 提交信息不加 Co-Authored-By 行（用户偏好）。
- 测试里取 `Map` 的嵌套值时用 `@SuppressWarnings("unchecked")` 强转，不要对 `Map<?,?>` 直接 `containsEntry`。
- 回复与代码注释使用简体中文，代码标识符保持原样。

## 文件结构

| 文件 | 动作 | 职责 |
|---|---|---|
| `src/main/java/com/kwiki/rag/routing/KeywordRuleSet.java` | 改 | 增加 `realtime-zh` / `realtime-en`，`VERSION` → `rules-v2` |
| `src/main/java/com/kwiki/rag/routing/RouteSource.java` | 改 | 增加 `PLANNER` |
| `src/main/java/com/kwiki/rag/routing/RetrievalPlan.java` | 改 | 增加工厂 `RetrievalPlan.planner(Intent, boolean)` |
| `src/main/resources/agentic/tools/answer-directly-v1.schema.json` | 新建 | 工具入参 schema |
| `src/main/java/com/kwiki/rag/tool/AgentTools.java` | 改 | 增加 `ANSWER_DIRECTLY` |
| `src/main/java/com/kwiki/rag/tool/AgentToolArguments.java` | 改 | 增加 `AnswerDirectly` |
| `src/main/java/com/kwiki/rag/tool/ToolAvailability.java` | 改 | 增加 `canReroute` 第 5 个布尔 |
| `src/main/java/com/kwiki/rag/tool/ToolRegistry.java` | 改 | 注册、暴露、单独成批校验、参数解析 |
| `src/main/java/com/kwiki/rag/orchestration/QaPlanningService.java` | 改 | `Input.canReroute`、`Decision.Reroute`、观察摘要字段 |
| `src/main/java/com/kwiki/infrastructure/ai/AgentPlannerAdapter.java` | 改 | 系统提示加入 `answer_directly` 用法 |
| `src/main/java/com/kwiki/infrastructure/orchestration/LangGraphAgenticWorkflow.java` | 改 | reroute 节点逻辑、direct 禁止升级、阶梯短路、阈值配置 |
| `src/test/java/com/kwiki/testutil/AgenticTestSupport.java` | 改 | `ScriptQuality.irrelevant` |
| `src/test/java/com/kwiki/rag/routing/KeywordRuleSetRealtimeTest.java` | 新建 | 规则正反例 |
| `src/test/java/com/kwiki/rag/tool/AnswerDirectlyRegistryTest.java` | 新建 | 工具层测试 |
| `src/test/java/com/kwiki/rag/orchestration/QaPlanningServiceRerouteTest.java` | 新建 | 规划服务 reroute 测试 |
| `src/test/java/com/kwiki/rag/orchestration/AgenticRerouteWorkflowTest.java` | 新建 | 工作流 reroute 与阶梯短路测试 |
| `src/test/java/com/kwiki/rag/tool/AskUserRegistryTest.java` | 改 | `ALL` 断言放宽 |
| `docs/agentic-rag.md`、`docs/superpowers/specs/2026-10-12-qa-route-correction-design.md` | 改 | 文档同步 |

---

### Task 1: 实时信息规则

**Files:**
- Modify: `src/main/java/com/kwiki/rag/routing/KeywordRuleSet.java`
- Test (create): `src/test/java/com/kwiki/rag/routing/KeywordRuleSetRealtimeTest.java`

**Interfaces:**
- Produces: `KeywordRuleSet.defaults()` 对「现在几点了」「今天星期几」「今天天气怎么样」「what time is it」返回 `MatchResult.Resolved(Intent.DIRECT_ANSWER, ids)`；`KeywordRuleSet.VERSION == "rules-v2"`。

- [ ] **Step 1: 确认没有同名测试文件**

Run: `ls src/test/java/com/kwiki/rag/routing/`
Expected: 只有 `RetrievalPlanTest.java`、`RuleFirstRouterContextTest.java`、`RuleFirstRouterTest.java`（没有 `KeywordRuleSetRealtimeTest.java`）。

- [ ] **Step 2: 写失败的测试**

Create `src/test/java/com/kwiki/rag/routing/KeywordRuleSetRealtimeTest.java`:

```java
package com.kwiki.rag.routing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.util.List;

class KeywordRuleSetRealtimeTest {
    final KeywordRuleSet rules = KeywordRuleSet.defaults();

    /** 与路由器一致：规则只看归一化后的查询。 */
    private KeywordRuleSet.MatchResult match(String query) {
        return rules.match(QueryNormalizer.normalize(query));
    }

    @Test
    void realtimeQuestionsResolveToDirectAnswer() {
        for (String query : List.of("现在几点了", "现在几点了？", "几点了", "今天几号", "今天星期几？",
                "现在是什么时间", "今天天气怎么样", "当前什么日期", "What time is it?", "what day is it")) {
            var result = match(query);
            assertThat(result).as(query).isInstanceOf(KeywordRuleSet.MatchResult.Resolved.class);
            var resolved = (KeywordRuleSet.MatchResult.Resolved) result;
            assertThat(resolved.intent()).as(query).isEqualTo(Intent.DIRECT_ANSWER);
            assertThat(resolved.ruleIds()).as(query).anyMatch(id -> id.startsWith("realtime-"));
        }
    }

    @Test
    void knowledgeBaseQuestionsThatMentionTimeAreNotIntercepted() {
        for (String query : List.of("今天的会议时间", "2025营业收入", "发布时间是几点", "什么是时间序列数据库",
                "部署窗口的日期安排")) {
            var result = match(query);
            boolean realtime = result instanceof KeywordRuleSet.MatchResult.Resolved resolved
                    && resolved.ruleIds().stream().anyMatch(id -> id.startsWith("realtime-"));
            assertThat(realtime).as(query).isFalse();
        }
    }

    @Test
    void ruleSetVersionIsBumped() {
        assertThat(KeywordRuleSet.VERSION).isEqualTo("rules-v2");
        assertThat(rules.version()).isEqualTo("rules-v2");
    }
}
```

- [ ] **Step 3: 运行并确认失败**

Run: `./mvnw -q -o -Dtest=KeywordRuleSetRealtimeTest test 2>&1 | grep -E "Tests run|FAIL|ERROR\]" | head`
Expected: 失败（实时规则未命中，且版本还是 `rules-v1`）。

- [ ] **Step 4: 实现规则与版本**

In `src/main/java/com/kwiki/rag/routing/KeywordRuleSet.java`, change `public static final String VERSION = "rules-v1";` to:

```java
    public static final String VERSION = "rules-v2";
```

and insert the two rules right after the `knowledge-en` rule line (before `return new KeywordRuleSet(rules);`):

```java
        // 实时信息：整句锚定的短问句，知识库里没有当前时间/天气；「今天的会议时间」这类带「的」的不会命中
        rules.add(new KeywordRule("realtime-zh", Pattern.compile("^(现在|当前|今天)?(是)?(几点|几号|星期几|礼拜几|什么时间|什么日期|天气(怎么样|如何)?)(了|啊|呢)?[吗?？!！。]*$"), Intent.DIRECT_ANSWER));
        rules.add(new KeywordRule("realtime-en", Pattern.compile("^(what time is it|what's the time|what is today's date|what day is it)( now| today)?[?!.]*$", Pattern.CASE_INSENSITIVE), Intent.DIRECT_ANSWER));
```

- [ ] **Step 5: 运行测试**

Run: `./mvnw -q -o -Dtest='KeywordRuleSetRealtimeTest,RuleFirstRouterTest,RuleFirstRouterContextTest,RetrievalPlanTest' test 2>&1 | grep -E "Tests run|FAIL|ERROR\]" | head`
Expected: 无失败输出（`-q` 下成功不打印）；再确认 `target/surefire-reports` 里这些类 `failures="0" errors="0"`。

如果 `现在是什么时间` 或 `今天天气怎么样` 之外的正例未命中，只调整正则，不要改测试意图；反例必须保持不命中。

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/kwiki/rag/routing/KeywordRuleSet.java src/test/java/com/kwiki/rag/routing/KeywordRuleSetRealtimeTest.java
git commit -m "feat(routing): 实时信息问句（时间、日期、天气）由规则直接判直答，规则集升至 rules-v2"
```

---

### Task 2: `answer_directly` 工具定义与暴露门槛

**Files:**
- Create: `src/main/resources/agentic/tools/answer-directly-v1.schema.json`
- Modify: `src/main/java/com/kwiki/rag/tool/AgentTools.java`, `AgentToolArguments.java`, `ToolAvailability.java`, `ToolRegistry.java`
- Modify (test): `src/test/java/com/kwiki/rag/tool/AskUserRegistryTest.java:33`
- Test (create): `src/test/java/com/kwiki/rag/tool/AnswerDirectlyRegistryTest.java`

**Interfaces:**
- Produces:
  - `AgentTools.ANSWER_DIRECTLY = "answer_directly"`，`AgentTools.ALL` 末尾追加；
  - `AgentToolArguments.AnswerDirectly(String reason)`；
  - `new ToolAvailability(boolean graphEnabled, boolean hasChildren, boolean canRewrite, boolean canAsk, boolean canReroute)`（4 参、3 参构造保留，`canReroute=false`）；
  - `ToolRegistry.available(...)` 在 `canReroute` 为 true 时把 `answer_directly` 排在**最后**；
  - `ToolRegistry.validateAgentBatch` 对「与其他工具同批」抛 `RunFailure("answer_directly_must_be_alone")`；对未暴露时抛 `tool-unavailable`。

- [ ] **Step 1: 确认无同名测试文件**

Run: `ls src/test/java/com/kwiki/rag/tool/ | grep -i AnswerDirectly || echo none`
Expected: `none`

- [ ] **Step 2: 写失败的测试**

Create `src/test/java/com/kwiki/rag/tool/AnswerDirectlyRegistryTest.java`:

```java
package com.kwiki.rag.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import java.util.List;

class AnswerDirectlyRegistryTest {
    final ToolRegistry registry = new ToolRegistry();
    final ToolAvailability withReroute = new ToolAvailability(true, true, true, true, true);
    static final String VALID = "{\"reason\":\"问题与知识库无关，是实时时间\"}";

    static ToolCall call(String id, String name, String json) {
        return new ToolCall(id, name, json, "turn");
    }

    private List<String> names(ToolAvailability availability) {
        return registry.available(availability).stream().map(ToolRegistry.ToolDefinition::name).toList();
    }

    @Test
    void isExposedLastAndOnlyWhenRerouteIsAllowed() {
        assertThat(names(withReroute)).endsWith("answer_directly");
        assertThat(names(new ToolAvailability(true, true, true, true, false))).doesNotContain("answer_directly");
        assertThat(names(new ToolAvailability(true, true, true, true))).as("4 参构造 = canReroute=false")
                .doesNotContain("answer_directly");
        assertThat(names(new ToolAvailability(true, true, true))).as("3 参构造 = canReroute=false")
                .doesNotContain("answer_directly");
        assertThat(AgentTools.ALL).contains("answer_directly");
        assertThat(registry.get("answer_directly").inputSchema()).contains("additionalProperties");
        assertThat(registry.get("answer_directly").outputSchema()).contains("evidenceRefs");
    }

    @Test
    void validCallProducesTypedArguments() {
        var validated = registry.validateAgentBatch(List.of(call("a", "answer_directly", VALID)),
                withReroute, 3, 3);

        assertThat(validated).hasSize(1);
        var arguments = (AgentToolArguments.AnswerDirectly) validated.getFirst().arguments();
        assertThat(arguments.reason()).isEqualTo("问题与知识库无关，是实时时间");
        assertThat(arguments.signature()).isEqualTo(arguments.reason());
    }

    @Test
    void mustBeTheOnlyCallOfATurn() {
        assertThatThrownBy(() -> registry.validateAgentBatch(List.of(
                call("a", "answer_directly", VALID),
                call("b", "rewrite_query", "{\"mode\":\"FEEDBACK\"}")), withReroute, 3, 3))
                .hasMessage("answer_directly_must_be_alone");
    }

    @Test
    void unavailableWhenRerouteIsNotAllowed() {
        assertThatThrownBy(() -> registry.validateAgentBatch(List.of(call("a", "answer_directly", VALID)),
                new ToolAvailability(true, true, true, true, false), 3, 3)).hasMessage("tool-unavailable");
    }

    @Test
    void rejectsMissingEmptyOrOversizedReasonAndExtraProperties() {
        for (String arguments : List.of("{}", "{\"reason\":\"\"}", "{\"reason\":\"" + "x".repeat(201) + "\"}",
                "{\"reason\":\"ok\",\"extra\":1}")) {
            assertThatThrownBy(() -> registry.validateAgentBatch(List.of(call("a", "answer_directly", arguments)),
                    withReroute, 3, 3)).as(arguments).hasMessage("invalid-answer-directly-v1");
        }
    }
}
```

- [ ] **Step 3: 运行并确认编译失败**

Run: `./mvnw -q -o -Dtest=AnswerDirectlyRegistryTest test 2>&1 | grep -E "ERROR|cannot find" | head -5`
Expected: 编译错误（`ToolAvailability` 没有 5 参构造、`AnswerDirectly` 不存在）。

- [ ] **Step 4: 新建 schema**

Create `src/main/resources/agentic/tools/answer-directly-v1.schema.json`:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "additionalProperties": false,
  "required": ["reason"],
  "properties": {
    "reason": {"type": "string", "minLength": 1, "maxLength": 200}
  }
}
```

- [ ] **Step 5: 修改 `AgentTools.java`**

Replace

```java
    public static final String ASK_USER = "ask_user";

    public static final List<String> ALL =
            List.of(SEARCH_CHUNKS, EXPAND_PARENTS, GRAPH_EXPAND, REWRITE_QUERY, ASK_USER);
```

with

```java
    public static final String ASK_USER = "ask_user";
    public static final String ANSWER_DIRECTLY = "answer_directly";

    public static final List<String> ALL =
            List.of(SEARCH_CHUNKS, EXPAND_PARENTS, GRAPH_EXPAND, REWRITE_QUERY, ASK_USER, ANSWER_DIRECTLY);
```

- [ ] **Step 6: 修改 `AgentToolArguments.java`**

Append before the final closing `}` of the interface:

```java

    /** 放弃检索、改走直接回答；只携带 planner 给出的理由（用于日志与审计，不进入回答）。 */
    record AnswerDirectly(String reason) implements AgentToolArguments {
        @Override
        public String signature() {
            return reason;
        }
    }
```

- [ ] **Step 7: 修改 `ToolAvailability.java`**

Replace the whole file with:

```java
package com.kwiki.rag.tool;

/**
 * 当前轮次可向 planner 暴露哪些工具：expand_parents / graph_expand / ask_user 需要证据池非空，
 * graph_expand 还需要图功能启用，rewrite_query 需要仍有改写与模型预算，
 * ask_user 还需要允许反问（非反问续跑、本次 run 尚未反问），
 * answer_directly 只在 canReroute 时暴露（已检索过且上一轮评审判定证据无关，由工作流计算）。
 */
public record ToolAvailability(boolean graphEnabled, boolean hasChildren, boolean canRewrite, boolean canAsk,
                               boolean canReroute) {

    /** 不允许改道的 4 参构造，保持 P3 的调用方不变。 */
    public ToolAvailability(boolean graphEnabled, boolean hasChildren, boolean canRewrite, boolean canAsk) {
        this(graphEnabled, hasChildren, canRewrite, canAsk, false);
    }

    /** 不允许反问与改道的 3 参构造，保持 P2 的调用方不变。 */
    public ToolAvailability(boolean graphEnabled, boolean hasChildren, boolean canRewrite) {
        this(graphEnabled, hasChildren, canRewrite, false, false);
    }
}
```

- [ ] **Step 8: 修改 `ToolRegistry.java`**

(a) After the `definitions.put(AgentTools.ASK_USER, ...)` statement (it ends with `+ " the user starts a new run."));`), add:

```java
        definitions.put(AgentTools.ANSWER_DIRECTLY, agentTool(AgentTools.ANSWER_DIRECTLY, "answer-directly-v1",
                "Give up retrieval and answer the user directly. Use it only when the question is plainly not"
                        + " about the knowledge base (for example the current time, the weather or small talk)"
                        + " and the last review shows the evidence is irrelevant. Never use it for a question the"
                        + " knowledge base could answer. It must be the only tool call of the turn and ends the"
                        + " evidence search. Give a short reason."));
```

(b) In the schema name list, replace `"ask-user-v1",` with:

```java
                        "ask-user-v1",
                        "answer-directly-v1",
```

(c) In `available(...)`, after the `canAsk` block add:

```java
        if (availability.canReroute()) tools.add(definitions.get(AgentTools.ANSWER_DIRECTLY));
```

(d) In `validateAgentBatch(...)`, after the `ask_user_must_be_alone` check add:

```java
        if (calls.size() > 1 && calls.stream().anyMatch(c -> AgentTools.ANSWER_DIRECTLY.equals(c.name())))
            throw new RunFailure("answer_directly_must_be_alone");
```

(e) In `parseAgentArguments(...)`, before `default -> throw new RunFailure("unknown-tool");` add:

```java
            case AgentTools.ANSWER_DIRECTLY -> new AgentToolArguments.AnswerDirectly(
                    node.get("reason").asText().strip());
```

- [ ] **Step 9: 放宽既有断言**

In `src/test/java/com/kwiki/rag/tool/AskUserRegistryTest.java`, replace

```java
        assertThat(AgentTools.ALL).endsWith("ask_user");
```

with

```java
        assertThat(AgentTools.ALL).contains("ask_user");
```

- [ ] **Step 10: 运行测试**

Run: `./mvnw -q -o -Dtest='AnswerDirectlyRegistryTest,AskUserRegistryTest,AgentToolRegistryTest,AgentToolDispatcherTest,AgentPlannerAdapterTest' test 2>&1 | grep -E "Tests run|FAIL|ERROR\]" | head`
Expected: 无失败输出；surefire 报告里这些类 `failures="0" errors="0"`。

- [ ] **Step 11: Commit**

```bash
git add src/main/resources/agentic/tools/answer-directly-v1.schema.json src/main/java/com/kwiki/rag/tool/AgentTools.java src/main/java/com/kwiki/rag/tool/AgentToolArguments.java src/main/java/com/kwiki/rag/tool/ToolAvailability.java src/main/java/com/kwiki/rag/tool/ToolRegistry.java src/test/java/com/kwiki/rag/tool/AskUserRegistryTest.java src/test/java/com/kwiki/rag/tool/AnswerDirectlyRegistryTest.java
git commit -m "feat(planner): 新增 answer_directly 工具定义与暴露门槛（canReroute）"
```

---

### Task 3: 规划服务返回 `Reroute`，planner 提示词

**Files:**
- Modify: `src/main/java/com/kwiki/rag/orchestration/QaPlanningService.java`
- Modify: `src/main/java/com/kwiki/infrastructure/ai/AgentPlannerAdapter.java`
- Test (create): `src/test/java/com/kwiki/rag/orchestration/QaPlanningServiceRerouteTest.java`

**Interfaces:**
- Consumes (Task 2): `new ToolAvailability(g, hasChildren, canRewrite, canAsk, canReroute)`；`AgentTools.ANSWER_DIRECTLY`；`AgentToolArguments.AnswerDirectly`。
- Produces:
  - `QaPlanningService.Input` 新增最后一个组件 `boolean canReroute`；保留 12 参构造（`canReroute=false`）；
  - `QaPlanningService.Decision.Reroute(String reason)`；
  - 观察摘要新增 `canAnswerDirectly`（布尔）；
  - `REJECTIONS` 增加 `answer_directly_must_be_alone`。

- [ ] **Step 1: 确认无同名测试文件**

Run: `ls src/test/java/com/kwiki/rag/orchestration/ | grep -i RerouteTest || echo none`
Expected: `none`

- [ ] **Step 2: 写失败的测试**

Create `src/test/java/com/kwiki/rag/orchestration/QaPlanningServiceRerouteTest.java`:

```java
package com.kwiki.rag.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.kwiki.rag.activity.AgenticDebugLogger;
import com.kwiki.rag.quality.QualityAssessment;
import com.kwiki.rag.retrieval.ChildEvidence;
import com.kwiki.rag.retrieval.RetrievalBudgets;
import com.kwiki.rag.routing.RetrievalPlan;
import com.kwiki.rag.tool.AgentPlannerPort;
import com.kwiki.rag.tool.EvidencePool;
import com.kwiki.rag.tool.ToolCall;
import com.kwiki.rag.tool.ToolRegistry;
import com.kwiki.wiki.access.AuthorizationScope;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

class QaPlanningServiceRerouteTest {

    static final class ScriptPlanner implements AgentPlannerPort {
        final List<Request> requests = new ArrayList<>();
        final ArrayDeque<List<ToolCall>> scripted = new ArrayDeque<>();

        @Override
        public List<ToolCall> plan(Request request) {
            requests.add(request);
            return scripted.isEmpty() ? List.of() : scripted.pop();
        }
    }

    static ToolCall call(String id, String name, String json) {
        return new ToolCall(id, name, json, "turn");
    }

    static final String DIRECT = "{\"reason\":\"与知识库无关\"}";
    final ScriptPlanner planner = new ScriptPlanner();
    final QaPlanningService service = new QaPlanningService(planner, new ToolRegistry(), AgenticLimits.defaults(),
            new RetrievalBudgets(50, 40, 8, 3, 24000, Duration.ofSeconds(5)), new AgenticDebugLogger());
    final RunContext run = new RunContext("r", new AuthorizationScope(1, false, Set.of(1L), Map.of(1L, 1L)),
            id -> 1, AgenticLimits.defaults(), Map.of());

    static EvidencePool pool() {
        EvidencePool pool = new EvidencePool("现在几点了");
        pool.mergeChildren(List.of(new ChildEvidence("C1", "P1", 1, "PAGE", 1, 1L, "财报", 0, 4, "营业收入",
                1, 1, 0.5)), 12000);
        pool.useHybridRetrieval();
        return pool;
    }

    static QualityAssessment irrelevantReview() {
        return new QualityAssessment(0.0, 0.0, 1.0, false, List.of(), List.of(), List.of("当前时间"),
                "off-topic", "证据与问题无关");
    }

    QaPlanningService.Input input(boolean canReroute) {
        return new QaPlanningService.Input("现在几点了", pool(), null, List.of(), irrelevantReview(), 2, 3, false,
                true, false, List.of(), run, canReroute);
    }

    @Test
    void answerDirectlyBecomesARerouteDecisionWhenAllowed() {
        planner.scripted.add(List.of(call("a", "answer_directly", DIRECT)));

        var decision = service.plan(input(true));

        assertThat(decision).isInstanceOf(QaPlanningService.Decision.Reroute.class);
        assertThat(((QaPlanningService.Decision.Reroute) decision).reason()).isEqualTo("与知识库无关");
        var request = planner.requests.getFirst();
        assertThat(request.tools()).extracting(ToolRegistry.ToolDefinition::name).contains("answer_directly");
        assertThat(request.observation()).containsEntry("canAnswerDirectly", true);
    }

    @Test
    void theToolIsInvisibleAndRejectedWhenNotAllowedThenPlanningDegrades() {
        planner.scripted.add(List.of(call("a", "answer_directly", DIRECT)));
        planner.scripted.add(List.of(call("b", "answer_directly", DIRECT)));

        var decision = service.plan(input(false));

        assertThat(planner.requests.getFirst().tools()).extracting(ToolRegistry.ToolDefinition::name)
                .doesNotContain("answer_directly");
        assertThat(planner.requests.getFirst().observation()).containsEntry("canAnswerDirectly", false);
        assertThat(planner.requests).as("被拒后带原因重试一次").hasSize(2);
        assertThat(planner.requests.get(1).validationError()).isEqualTo("tool-unavailable");
        assertThat(decision).isInstanceOf(QaPlanningService.Decision.Degrade.class);
    }

    @Test
    void answerDirectlyBatchedWithAnotherToolIsRetriedWithTheReason() {
        planner.scripted.add(List.of(call("a", "answer_directly", DIRECT),
                call("b", "rewrite_query", "{\"mode\":\"FEEDBACK\"}")));
        planner.scripted.add(List.of(call("c", "answer_directly", DIRECT)));

        var decision = service.plan(input(true));

        assertThat(planner.requests.get(1).validationError()).isEqualTo("answer_directly_must_be_alone");
        assertThat(decision).isInstanceOf(QaPlanningService.Decision.Reroute.class);
    }
}
```

- [ ] **Step 3: 运行并确认失败**

Run: `./mvnw -q -o -Dtest=QaPlanningServiceRerouteTest test 2>&1 | grep -E "ERROR|cannot find" | head -5`
Expected: 编译错误（`Input` 没有 13 参构造、`Decision.Reroute` 不存在）。

- [ ] **Step 4: 修改 `QaPlanningService.java`**

(a) In `Decision`, after the `Degrade` record add:

```java

        /** planner 判定问题与知识库无关：放弃检索，改走直接回答。 */
        record Reroute(String reason) implements Decision {
        }
```

(b) Replace the `Input` record header through its compact constructor. Replace

```java
            boolean evidenceUnexamined,
            List<RetrievalPlannerPort.ToolExchange> toolHistory,
            RunContext run) {
        public Input {
            history = history == null ? List.of() : List.copyOf(history);
            toolHistory = toolHistory == null ? List.of() : List.copyOf(toolHistory);
        }
    }
```

with

```java
            boolean evidenceUnexamined,
            List<RetrievalPlannerPort.ToolExchange> toolHistory,
            RunContext run,
            boolean canReroute) {
        public Input {
            history = history == null ? List.of() : List.copyOf(history);
            toolHistory = toolHistory == null ? List.of() : List.copyOf(toolHistory);
        }

        /** 不允许改道的 12 参构造，保持既有调用方不变。 */
        public Input(String originalQuery, EvidencePool pool, RetrievalPlan route, List<ChatTurn> history,
                     QualityAssessment lastReview, int round, int maxRounds, boolean graphEnabled,
                     boolean canAsk, boolean evidenceUnexamined,
                     List<RetrievalPlannerPort.ToolExchange> toolHistory, RunContext run) {
            this(originalQuery, pool, route, history, lastReview, round, maxRounds, graphEnabled, canAsk,
                    evidenceUnexamined, toolHistory, run, false);
        }
    }
```

(c) In `REJECTIONS`, replace

```java
            "ask_user_must_be_alone", "tool-budget-exceeded");
```

with

```java
            "ask_user_must_be_alone", "answer_directly_must_be_alone", "tool-budget-exceeded");
```

(d) In `plan(...)`, replace

```java
                input.canAsk());
        var tools = registry.available(availability);
```

with

```java
                input.canAsk(),
                input.canReroute());
        var tools = registry.available(availability);
```

(e) In `plan(...)`, right after `var validated = registry.validateAgentBatch(...)` statement (the one ending `budgets.maxSubqueries);`) and **before** `if (!run.canTools(validated.size()))`, add:

```java
                if (validated.size() == 1
                        && validated.getFirst().arguments() instanceof AgentToolArguments.AnswerDirectly direct) {
                    debug.stage(run, "plan-" + input.round(), input.round(), null, "plan", "reroute", elapsed(started),
                            () -> Map.of("attempt", 1, "reason", AgenticDebugLogger.boundedQuery(direct.reason())));
                    return new Decision.Reroute(direct.reason());
                }
```

and add the import `import com.kwiki.rag.tool.AgentToolArguments;` next to the other `com.kwiki.rag.tool` imports.

(f) In `observation(...)`, after `view.put("canAskUser", availability.canAsk());` add:

```java
        view.put("canAnswerDirectly", availability.canReroute());
```

- [ ] **Step 5: 修改 planner 系统提示**

In `src/main/java/com/kwiki/infrastructure/ai/AgentPlannerAdapter.java`, after the line

```java
                .append("plausible interpretations that only the user can resolve. ")
```

add:

```java
                .append("Use answer_directly, as the only call of the turn, only when the question is plainly ")
                .append("not about the knowledge base (for example the current time, the weather or small talk) ")
                .append("and the last review shows the evidence is irrelevant; never for a question the ")
                .append("knowledge base could answer. ")
```

- [ ] **Step 6: 运行测试**

Run: `./mvnw -q -o -Dtest='QaPlanningServiceRerouteTest,QaPlanningServiceTest,AgentPlannerAdapterTest' test 2>&1 | grep -E "Tests run|FAIL|ERROR\]" | head`
Expected: 无失败输出；surefire 报告 `failures="0" errors="0"`。

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/kwiki/rag/orchestration/QaPlanningService.java src/main/java/com/kwiki/infrastructure/ai/AgentPlannerAdapter.java src/test/java/com/kwiki/rag/orchestration/QaPlanningServiceRerouteTest.java
git commit -m "feat(planner): 规划服务支持 Reroute 决策，观察摘要与系统提示加入 answer_directly"
```

---

### Task 4: 工作流 reroute（改走 direct，且不能升级回检索）

**Files:**
- Modify: `src/main/java/com/kwiki/rag/routing/RouteSource.java`, `RetrievalPlan.java`
- Modify: `src/main/java/com/kwiki/infrastructure/orchestration/LangGraphAgenticWorkflow.java`
- Modify (test support): `src/test/java/com/kwiki/testutil/AgenticTestSupport.java`
- Test (create): `src/test/java/com/kwiki/rag/orchestration/AgenticRerouteWorkflowTest.java`

**Interfaces:**
- Consumes (Task 3): `QaPlanningService.Input(..., RunContext run, boolean canReroute)`、`Decision.Reroute(String reason)`。
- Produces:
  - `RouteSource.PLANNER`；`RetrievalPlan.planner(Intent intent, boolean needsRetrieval)`；
  - 配置 `kwiki.agentic.reroute-relevance-threshold`（`@Value`，默认 0.2）；
  - `AgenticTestSupport.ScriptQuality.irrelevant`（`Predicate<QualityV2Input>`，命中时评审 relevance=0.0、reasonCode=`off-topic` 且不通过）。

- [ ] **Step 1: 确认无同名测试文件**

Run: `ls src/test/java/com/kwiki/rag/orchestration/ | grep -i AgenticRerouteWorkflowTest || echo none`
Expected: `none`

- [ ] **Step 2: 扩展测试支撑 `ScriptQuality`**

In `src/test/java/com/kwiki/testutil/AgenticTestSupport.java`, replace

```java
        public Predicate<QualityV2Input> passes = input -> true;
        public boolean unavailable;

        @Override
        public QualityAssessment assess(QualityV2Input input) {
            inputs.add(input);
            if (unavailable) {
                throw new com.kwiki.rag.orchestration.RunFailure("qa-unavailable");
            }
            boolean pass = passes.test(input);
            return new QualityAssessment(0.95, 0.95, 0.95, pass,
                    pass ? List.of(firstEvidenceId(input)) : List.of(),
                    pass ? List.of() : List.of("证据不足"),
                    pass ? List.of() : List.of("关键方面"),
                    pass ? "sufficient" : "coverage-missing",
                    pass ? "全部达标" : "覆盖不足");
        }
```

with

```java
        public Predicate<QualityV2Input> passes = input -> true;
        /** 命中时模拟「证据与问题无关」：relevance=0.0、reasonCode=off-topic，且一定不通过。 */
        public Predicate<QualityV2Input> irrelevant = input -> false;
        public boolean unavailable;

        @Override
        public QualityAssessment assess(QualityV2Input input) {
            inputs.add(input);
            if (unavailable) {
                throw new com.kwiki.rag.orchestration.RunFailure("qa-unavailable");
            }
            if (irrelevant.test(input)) {
                return new QualityAssessment(0.0, 0.0, 1.0, false, List.of(), List.of(), List.of("当前时间"),
                        "off-topic", "证据与问题无关");
            }
            boolean pass = passes.test(input);
            return new QualityAssessment(0.95, 0.95, 0.95, pass,
                    pass ? List.of(firstEvidenceId(input)) : List.of(),
                    pass ? List.of() : List.of("证据不足"),
                    pass ? List.of() : List.of("关键方面"),
                    pass ? "sufficient" : "coverage-missing",
                    pass ? "全部达标" : "覆盖不足");
        }
```

- [ ] **Step 3: 写失败的测试**

Create `src/test/java/com/kwiki/rag/orchestration/AgenticRerouteWorkflowTest.java`:

```java
package com.kwiki.rag.orchestration;

import static com.kwiki.testutil.AgenticTestSupport.ScriptPlanner.call;
import static org.assertj.core.api.Assertions.assertThat;

import com.kwiki.rag.answer.ChatPersistenceService;
import com.kwiki.rag.answer.ChatStreamEvent;
import com.kwiki.rag.retrieval.ChunkHit;
import com.kwiki.rag.routing.KeywordRuleSet;
import com.kwiki.rag.routing.RuleFirstRouter;
import com.kwiki.rag.tool.ToolRegistry;
import com.kwiki.security.CurrentUser;
import com.kwiki.testutil.AgenticTestSupport;
import com.kwiki.testutil.AgenticTestSupport.Harness;
import com.kwiki.testutil.AgenticTestSupport.ScriptPlanner;
import com.kwiki.testutil.StandardTestProperties;
import com.kwiki.wiki.access.AuthorizationScopeResolver;
import com.kwiki.wiki.access.ScopeVersionService;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 意图修正：planner 的 answer_directly 改道，以及固定阶梯在证据无关时跳过 *_PARENT。 */
class AgenticRerouteWorkflowTest {
    static final String SEARCH = "{\"queries\":[\"云朵\"],\"strategy\":\"HYBRID\",\"range\":\"BASE\"}";
    static final String DIRECT = "{\"reason\":\"与知识库无关\"}";
    /** 规则未命中，路由兜底成检索。 */
    static final String QUERY = "窗外云朵的颜色";
    final CurrentUser user = new CurrentUser(1L, "admin", true);
    final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    final ScopeVersionService versions = new ScopeVersionService(StandardTestProperties.nullProvider());
    Harness harness;
    ScriptPlanner planner;

    void setUp(boolean withPlanner) {
        var router = new RuleFirstRouter(KeywordRuleSet.defaults(), StandardTestProperties.nullProvider(), false, metrics);
        var scopes = new AuthorizationScopeResolver(
                org.mockito.Mockito.mock(com.kwiki.wiki.persistence.KnowledgeBaseMemberRepository.class), versions,
                new com.kwiki.infrastructure.redis.ScopeCache(StandardTestProperties.nullProvider(), Duration.ofSeconds(1)));
        harness = AgenticTestSupport.harness(router, scopes, versions,
                new ChatPersistenceService(StandardTestProperties.nullProvider()), metrics);
        planner = withPlanner ? harness.enablePlanner() : null;
    }

    List<ChatStreamEvent> run(String query, RouteInput input) {
        return harness.workflow.answerInSession(user, query, List.of(), Set.of(), Set.of(), input)
                .collectList().block(Duration.ofSeconds(10));
    }

    static ChunkHit hit(String key, String parent) {
        return new ChunkHit(key, parent, 1, "PAGE", 11, 1L, "heading", 0, 6, "候选依据 " + key);
    }

    void corpus(ChunkHit... hits) {
        harness.bm25.hits = List.of(hits);
        harness.vector.hits = List.of(hits);
    }

    static ChatStreamEvent done(List<ChatStreamEvent> events) {
        return events.getLast();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> route(ChatStreamEvent done) {
        return (Map<String, Object>) done.payloadMap().get("route");
    }

    static String tokens(List<ChatStreamEvent> events) {
        return events.stream().filter(e -> e.type().equals("token"))
                .map(e -> String.valueOf(e.payloadMap().get("text"))).collect(Collectors.joining());
    }

    static List<String> toolNames(com.kwiki.rag.tool.AgentPlannerPort.Request request) {
        return request.tools().stream().map(ToolRegistry.ToolDefinition::name).toList();
    }

    @Test
    void plannerReroutesToDirectAfterAnIrrelevantReview() {
        setUp(true);
        corpus(hit("C1", "P1"));
        harness.quality.irrelevant = input -> true;
        harness.answer.scripted.add("无关的候选 [P0]");
        harness.direct.scripted.add("我没有实时时钟，无法告知窗外云朵的颜色。");
        planner.then(call("a", "search_chunks", SEARCH)).then(call("b", "answer_directly", DIRECT));

        var events = run(QUERY, RouteInput.NONE);

        assertThat(done(events).payloadMap()).containsEntry("outcome", "direct");
        assertThat(tokens(events)).isEqualTo("我没有实时时钟，无法告知窗外云朵的颜色。");
        assertThat(planner.requests).hasSize(2);
        assertThat(toolNames(planner.requests.getFirst())).as("第一轮还没有评审，不暴露").doesNotContain("answer_directly");
        assertThat(toolNames(planner.requests.get(1))).endsWith("answer_directly");
        assertThat(harness.direct.questions).containsExactly(QUERY);
        assertThat(harness.bm25.topKs).as("改道后不再检索").hasSize(1);
        assertThat(route(done(events))).containsEntry("intent", "DIRECT_ANSWER").containsEntry("source", "PLANNER")
                .containsEntry("needsRetrieval", false);
    }

    @Test
    void rerouteIsNotOfferedWhenTheReviewShowsRelevantEvidence() {
        setUp(true);
        corpus(hit("C1", "P1"));
        harness.quality.passes = input -> false; // 覆盖不足但相关（relevance 0.95）
        harness.answer.scripted.add("候选 [P0]");
        planner.then(call("a", "search_chunks", SEARCH));

        run(QUERY, RouteInput.NONE);

        assertThat(planner.requests.size()).isGreaterThanOrEqualTo(2);
        for (var request : planner.requests) {
            assertThat(toolNames(request)).doesNotContain("answer_directly");
        }
    }

    @Test
    void rerouteIsNotOfferedToAClarificationResume() {
        setUp(true);
        corpus(hit("C1", "P1"));
        harness.quality.irrelevant = input -> true;
        harness.answer.scripted.add("无关的候选 [P0]");
        planner.then(call("a", "search_chunks", SEARCH));

        run(QUERY, new RouteInput(null, true));

        assertThat(planner.requests.size()).isGreaterThanOrEqualTo(2);
        for (var request : planner.requests) {
            assertThat(toolNames(request)).doesNotContain("answer_directly");
        }
    }

    @Test
    void directAfterRerouteNeverEscalatesBackToRetrieval() {
        setUp(true);
        corpus(hit("C1", "P1"));
        harness.quality.irrelevant = input -> true;
        harness.answer.scripted.add("无关的候选 [P0]");
        harness.direct.scripted.add("[[NEEDS_KNOWLEDGE]]");
        planner.then(call("a", "search_chunks", SEARCH)).then(call("b", "answer_directly", DIRECT));

        var events = run(QUERY, RouteInput.NONE);

        assertThat(done(events).payloadMap()).containsEntry("outcome", AgenticErrorCodes.INSUFFICIENT);
        assertThat(harness.bm25.topKs).as("没有再次检索").hasSize(1);
        assertThat(planner.requests).hasSize(2);
    }

    @Test
    void directFailureAfterRerouteEndsInsufficientInsteadOfRetrieving() {
        setUp(true);
        corpus(hit("C1", "P1"));
        harness.quality.irrelevant = input -> true;
        harness.answer.scripted.add("无关的候选 [P0]");
        harness.direct.fail = true;
        planner.then(call("a", "search_chunks", SEARCH)).then(call("b", "answer_directly", DIRECT));

        var events = run(QUERY, RouteInput.NONE);

        assertThat(done(events).payloadMap()).containsEntry("outcome", AgenticErrorCodes.INSUFFICIENT);
        assertThat(harness.bm25.topKs).hasSize(1);
    }

    @Test
    void fixedLadderSkipsParentStagesWhenTheEvidenceIsIrrelevant() {
        setUp(false);
        corpus(hit("C1", "P1"));
        // 只有扩大后的子块阶段通过；基础子块阶段评审为「证据无关」
        harness.quality.irrelevant = input -> input.candidate().attemptStage() == AttemptStage.BASE_CHILD;
        harness.quality.passes = input -> input.candidate().attemptStage() == AttemptStage.EXPANDED_CHILD;
        harness.answer.scripted.add("基础子候选");
        harness.answer.scripted.add("扩大检索候选 [P0]");

        var events = run(QUERY, RouteInput.NONE);

        assertThat(done(events).payloadMap()).containsEntry("outcome", "completed")
                .containsEntry("attemptStage", "EXPANDED_CHILD");
        assertThat(harness.bm25.topKs).containsExactly(20, 50);
        assertThat(harness.parents.requestedKeys).as("跳过了 BASE_PARENT").isEmpty();
        assertThat(harness.answer.prompts).hasSize(2);
    }

    @Test
    void fixedLadderStillTriesParentsWhenTheEvidenceIsRelevantButIncomplete() {
        setUp(false);
        var child = hit("C1", "P1");
        corpus(child);
        harness.parents.byKey.put("P1", new com.kwiki.rag.retrieval.ParentEvidenceChunk("P1", child.kbId(),
                child.resourceType(), child.resourceId(), child.revisionId(), "heading", "完整父段落 P1", 0,
                List.of(child)));
        harness.quality.passes = input -> input.candidate().attemptStage() == AttemptStage.BASE_PARENT;
        harness.answer.scripted.add("基础子候选");
        harness.answer.scripted.add("父候选 [P0]");

        var events = run(QUERY, RouteInput.NONE);

        assertThat(done(events).payloadMap()).containsEntry("attemptStage", "BASE_PARENT");
        assertThat(harness.parents.requestedKeys).isNotEmpty();
    }
}
```

- [ ] **Step 4: 运行并确认失败**

Run: `./mvnw -q -o -Dtest=AgenticRerouteWorkflowTest test 2>&1 | grep -E "ERROR|cannot find|Tests run" | head -8`
Expected: 编译通过（Task 2/3 已就绪），测试失败：`PLANNER` 枚举、reroute 流转与阶梯短路均未实现。若 `RouteInput` 构造签名与上面不符，参照 `AgenticPlannerWorkflowTest.askUserIsNotOfferedToAClarificationResume` 中的 `new RouteInput(null, true)` 用法（二者一致）。

- [ ] **Step 5: `RouteSource` 与 `RetrievalPlan`**

`src/main/java/com/kwiki/rag/routing/RouteSource.java` — replace the enum body:

```java
public enum RouteSource {
    RULE,
    LLM,
    FALLBACK,
    /** QA planner 在检索过程中修正的路由（answer_directly）。 */
    PLANNER
}
```

`src/main/java/com/kwiki/rag/routing/RetrievalPlan.java` — add after `fallback(...)`:

```java

    /** QA planner 在检索后修正出的路由；confidence 取 1.0（由代码门槛背书，不是模型自评）。 */
    public static RetrievalPlan planner(Intent intent, boolean needsRetrieval) {
        return new RetrievalPlan(intent, needsRetrieval, RewriteMode.NONE, List.of(),
                RouteSource.PLANNER, null, List.of(), "planner-reroute", 1.0);
    }
```

- [ ] **Step 6: 工作流——阈值配置与会话状态**

In `LangGraphAgenticWorkflow.java`:

(a) After the line `private volatile boolean plannerEnabled;` add:

```java
    /** 评审 relevance 不高于该值，才开放 answer_directly / 触发固定阶梯的 *_PARENT 跳过；默认值写在代码里，由 Nacos 覆盖。 */
    private volatile double rerouteRelevanceThreshold = 0.2;
```

(b) Immediately after the `usePlanner(...)` method (which ends with `this.plannerEnabled = enabled;\n    }`), add:

```java

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void configureReroute(@Value("${kwiki.agentic.reroute-relevance-threshold:0.2}") double threshold) {
        this.rerouteRelevanceThreshold = threshold;
    }

    /** 测试与手工装配用的注入入口。 */
    public void useRerouteThreshold(double threshold) {
        this.rerouteRelevanceThreshold = threshold;
    }
```

(c) In `Session`, after `QualityAssessment lastReview;` add:

```java
        /** planner 已把本次 run 改成直答：direct 不得再升级回检索。 */
        boolean rerouted;
        /** 固定阶梯：最近一次评审判定证据无关，跳过 *_PARENT 阶段。 */
        boolean skipParentStages;
```

- [ ] **Step 7: 工作流——plan 节点处理 `Reroute`，传入 `canReroute`**

(a) In `plan(...)`, replace

```java
                List.copyOf(s.toolHistory), s.run));
```

with

```java
                List.copyOf(s.toolHistory), s.run, canReroute(s)));
```

(b) In the `switch (decision)` of `plan(...)`, add a case before `Decision.Degrade`:

```java
            case QaPlanningService.Decision.Reroute reroute -> reroute(s, step, startedAt, reroute.reason());
```

(c) Add these methods right before the `degrade(...)` method (the one with javadoc `/** 规划不可用：降级回固定阶梯…`):

```java
    /** 允许 planner 改道：已检索过、上一轮评审判定证据无关，且不是反问续跑。 */
    private boolean canReroute(Session s) {
        return !s.routeInput.clarificationResumed()
                && s.pool != null && s.pool.hybridRetrievals() > 0
                && s.lastReview != null && s.lastReview.relevance() <= rerouteRelevanceThreshold;
    }

    /** planner 判定问题与知识库无关：路由改为直答并交给 direct 节点；之后 direct 不能再升级回检索。 */
    private void reroute(Session s, String step, long startedAt, String reason) {
        String from = s.route == null ? "none" : s.route.intent().name();
        double relevance = s.lastReview == null ? 0.0 : s.lastReview.relevance();
        s.route = RetrievalPlan.planner(Intent.DIRECT_ANSWER, false);
        s.rerouted = true;
        s.debugStage("plan", "reroute", s.sinceMs(startedAt), () -> Map.of(
                "reason", AgenticDebugLogger.boundedQuery(reason),
                "relevance", relevance,
                "fromIntent", from,
                "toIntent", Intent.DIRECT_ANSWER.name(),
                "planRound", s.planRound));
        s.emitActivity(ActivityEvent.finished(step, null, s.queryRound, null,
                ActivityEvent.Phase.PLAN, ActivityEvent.Status.COMPLETED, startedAt,
                s.sinceMs(startedAt), "与知识库无关，改为直接回答", Map.of(), null));
        s.next = "direct";
    }
```

- [ ] **Step 8: 工作流——改道后的 direct 不再升级**

Replace the existing `escalate(...)` method:

```java
    private void escalate(Session s, String step, long startedAt, String reason) {
        s.route = RetrievalPlan.fallback(reason);
        s.debugStage("direct", "escalated", s.sinceMs(startedAt), () -> Map.of("reason", reason));
        s.emitActivity(ActivityEvent.finished(step, null, s.queryRound, null,
                ActivityEvent.Phase.GENERATION, ActivityEvent.Status.SKIPPED, startedAt,
                s.sinceMs(startedAt), "需要查询知识库，转入检索", Map.of(), reason));
        s.next = retrievalEntry(s);
    }
```

with

```java
    private void escalate(Session s, String step, long startedAt, String reason) {
        if (s.rerouted) {
            // planner 刚判定「与知识库无关」：不能再回检索，避免来回跳；以信息不足结束
            String code = "direct-after-reroute-failed";
            s.debugStage("direct", "reroute-failed", s.sinceMs(startedAt), () -> Map.of("reason", reason));
            s.emitActivity(ActivityEvent.finished(step, null, s.queryRound, null,
                    ActivityEvent.Phase.GENERATION, ActivityEvent.Status.SKIPPED, startedAt,
                    s.sinceMs(startedAt), "无法直接回答", Map.of(), code));
            s.finishInsufficient(code);
            return;
        }
        s.route = RetrievalPlan.fallback(reason);
        s.debugStage("direct", "escalated", s.sinceMs(startedAt), () -> Map.of("reason", reason));
        s.emitActivity(ActivityEvent.finished(step, null, s.queryRound, null,
                ActivityEvent.Phase.GENERATION, ActivityEvent.Status.SKIPPED, startedAt,
                s.sinceMs(startedAt), "需要查询知识库，转入检索", Map.of(), reason));
        s.next = retrievalEntry(s);
    }
```

- [ ] **Step 9: 工作流——固定阶梯跳过 `*_PARENT`**

(a) In `quality(...)`, replace

```java
        } else {
            advanceRecovery(s);
        }
    }

    /** 只发布被评审过的候选文本；不做二次生成。 */
```

with

```java
        } else {
            // 证据与问题无关时，无关块的父段落同样无关：跳过 *_PARENT 阶段，直接扩大检索或改写
            s.skipParentStages = assessment.relevance() <= rerouteRelevanceThreshold;
            advanceRecovery(s);
        }
    }

    /** 只发布被评审过的候选文本；不做二次生成。 */
```

(b) In `advanceRecovery(...)`, replace

```java
        AttemptStage next = s.stage.next();
        if (next == null) {
```

with

```java
        AttemptStage next = s.stage.next();
        while (next != null && s.skipParentStages && next.isParentStage()) {
            next = next.next();
        }
        if (next == null) {
```

- [ ] **Step 10: 运行测试**

Run: `./mvnw -q -o -Dtest='AgenticRerouteWorkflowTest,AgenticPlannerWorkflowTest,AgenticWorkflowTest,AgenticRoutingWorkflowTest,QaPlanningServiceTest,QaPlanningServiceRerouteTest' test 2>&1 | grep -E "FAIL|ERROR\]|cannot find" | head`
Expected: 无失败输出（`RunFailure: ...` 这类 `ERROR org.bsc.langgraph4j` 日志行来自预期失败的既有用例，可忽略）。再用 surefire 报告确认：

Run: `for f in target/surefire-reports/TEST-*Reroute*.xml target/surefire-reports/TEST-*AgenticWorkflowTest.xml target/surefire-reports/TEST-*AgenticPlannerWorkflowTest.xml; do grep -o 'name="[^"]*" time="[^"]*" tests="[0-9]*" errors="[0-9]*" skipped="[0-9]*" failures="[0-9]*"' $f | head -1; done`
Expected: 全部 `errors="0" failures="0"`。

如果 `fixedLadderSkipsParentStages...` 里 `harness.parents.requestedKeys` 不是空，说明 `BASE_PARENT` 没被跳过——检查 Step 9(b) 的 while 是否在 `next == EXPANDED_CHILD` 判断之前。如果 `directFailure...` 的 outcome 不是 `insufficient`，检查 `finishInsufficient` 是否走到 `respond`。

- [ ] **Step 11: Commit**

```bash
git add src/main/java/com/kwiki/rag/routing/RouteSource.java src/main/java/com/kwiki/rag/routing/RetrievalPlan.java src/main/java/com/kwiki/infrastructure/orchestration/LangGraphAgenticWorkflow.java src/test/java/com/kwiki/testutil/AgenticTestSupport.java src/test/java/com/kwiki/rag/orchestration/AgenticRerouteWorkflowTest.java
git commit -m "feat(planner): planner 可在证据无关时改道直答（不可升级回检索），固定阶梯在证据无关时跳过父段落阶段"
```

---

### Task 5: 全量验证、日志与文档

**Files:**
- Modify: `docs/agentic-rag.md`
- Modify: `docs/superpowers/specs/2026-10-12-qa-route-correction-design.md`
- Modify (test): `src/test/java/com/kwiki/rag/orchestration/AgenticRerouteWorkflowTest.java`（增加 debug 日志断言）

- [ ] **Step 1: 增加 reroute debug 日志断言**

Append this test to `AgenticRerouteWorkflowTest` (before the final `}`):

```java

    @Test
    void rerouteIsLoggedAtDebugLevelWithItsEvidence() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("kwiki.agentic.debug");
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        var previousLevel = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        try {
            setUp(true);
            corpus(hit("C1", "P1"));
            harness.quality.irrelevant = input -> true;
            harness.answer.scripted.add("无关的候选 [P0]");
            planner.then(call("a", "search_chunks", SEARCH)).then(call("b", "answer_directly", DIRECT));

            run(QUERY, RouteInput.NONE);

            String logs = appender.list.stream()
                    .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .collect(Collectors.joining("\n"));
            assertThat(logs).contains("\"status\":\"reroute\"", "\"fromIntent\":\"KNOWLEDGE_QA\"",
                    "\"toIntent\":\"DIRECT_ANSWER\"", "\"relevance\":0.0", "与知识库无关");
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
        }
    }
```

Run: `./mvnw -q -o -Dtest=AgenticRerouteWorkflowTest test 2>&1 | grep -E "FAIL|ERROR\]" | head`
Expected: 无失败输出。（`QaPlanningService` 与 `reroute()` 各打一条 `reroute` 日志，断言都满足。）

- [ ] **Step 2: 文档**

In `docs/agentic-rag.md`, append a new section at the end of the file:

```markdown

## 意图修正（reroute）

路由一旦判成检索，QA 阶段可以在证据明显无关时把意图改回直答。

- **规则兜底**：`KeywordRuleSet`（`rules-v2`）对整句的时间、日期、星期、天气类短问句（`realtime-zh` / `realtime-en`）直接判 `DIRECT_ANSWER`；「今天的会议时间」这类带「的」的知识库问题不会命中。
- **planner 改道**：`answer_directly` 工具必须单独成批，只在这些条件同时满足时才暴露：已检索过、上一轮评审 `relevance ≤ kwiki.agentic.reroute-relevance-threshold`（默认 `0.2`）、不是反问续跑。通过校验后 `s.route` 改为 `DIRECT_ANSWER`（`source=PLANNER`，写回 `chat_run` 路由状态），进入 `direct` 节点；改道后 direct 输出 `[[NEEDS_KNOWLEDGE]]`、为空或失败时不再升级回检索，以「信息不足」结束。
- **固定阶梯**（planner 关闭或降级）：不自动改道（评审的 relevance≈0 分不清「与知识库无关」和「知识库里没检索到」，自动改道会让 direct 凭常识作答）；只在评审 `relevance ≤ 阈值` 时跳过 `BASE_PARENT` / `EXPANDED_PARENT`，直接扩大检索或改写。
- **配置**：`kwiki.agentic.reroute-relevance-threshold`（默认 `0.2`）。`application.yml` 只保留 Nacos 引导项，需要调整时在 Nacos 的应用配置里设置。
- **日志**（`kwiki.agentic.debug`，DEBUG）：`phase=plan` 的 `reroute`（`reason`、`relevance`、`fromIntent`、`toIntent`、`planRound`）；direct 在改道后失败时 `phase=direct` 的 `reroute-failed`。
```

In `docs/superpowers/specs/2026-10-12-qa-route-correction-design.md`:

(a) Replace the line

```
- 校验不通过的拒绝码沿用 `REJECTIONS` 机制（带原因重试一次，仍非法则降级），新增 `reroute-not-allowed`。
```

with

```
- 工具未暴露时 planner 仍然输出它，沿用既有的 `tool-unavailable` 拒绝码（带原因重试一次，仍非法则降级），不新增拒绝码；与其他工具同批时拒绝码为 `answer_directly_must_be_alone`。
```

(b) Replace the line

```
  `canReroute = 本 run 尚未 reroute ∧ 非反问续跑 ∧ 证据池已检索过（hybridRetrievals>0）∧ 存在上一轮评审且 relevance ≤ 阈值`。
```

with

```
  `canReroute = 非反问续跑 ∧ 证据池已检索过（hybridRetrievals>0）∧ 存在上一轮评审且 relevance ≤ 阈值`。「本 run 只允许一次」天然成立：改道后 direct 要么回答要么以信息不足结束，不会再回到 plan。
```

(c) In section 8 (配置), replace the sentence `其余开关不变。\`.env.example\` 与 \`docs/agentic-rag.md\` 同步补充。` with `其余开关不变。默认值写在代码的 \`@Value\` 中，不写入 \`application.yml\`（该文件只保留 Nacos 引导项），在 Nacos 配置覆盖；\`docs/agentic-rag.md\` 同步补充。`

- [ ] **Step 3: 全量相关测试与仓库审计**

Run: `./mvnw -q -o test 2>&1 | grep -E "Tests run:|FAIL|BUILD|ERROR\] (Failures|Errors|Tests)" | tail -15`
Expected: 无 `FAIL`；若有失败，先对照基线（`git stash` 后同一用例是否同样失败）再判断是否由本次改动引入。重点关注 `RepositorySecretsAuditTest`（不得出现 `sk-` 长串）。

Run: `bash scripts/check-no-docker-middleware.sh 2>&1 | tail -3`
Expected: 通过（退出码 0，无违规输出）。

- [ ] **Step 4: Commit**

```bash
git add docs/agentic-rag.md docs/superpowers/specs/2026-10-12-qa-route-correction-design.md src/test/java/com/kwiki/rag/orchestration/AgenticRerouteWorkflowTest.java
git commit -m "docs(planner): 意图修正说明与 reroute debug 日志断言"
```

---

## Self-Review（对照 spec）

- §5.1 工具与门槛 → Task 2（定义、暴露、单独成批）、Task 3（`canReroute` 进入可用性）、Task 4 `canReroute(s)`（已检索、评审 relevance ≤ 阈值、非续跑）。
- §5.2 决策与流转 → Task 3 `Decision.Reroute`；Task 4 `reroute()`、`RouteSource.PLANNER`、`escalate` 禁止升级、失败走 `finishInsufficient`。`PriorRoute` 由 `s.routeState()` 取自 `s.route`，无需另改；`chat_run.route_source` 为 `VARCHAR(20)`，`PLANNER` 可存。
- §5.3 固定阶梯短路 → Task 4 Step 9。
- §5.4 规则 → Task 1。
- §5.5 日志 → Task 3（`QaPlanningService` 的 `reroute`）、Task 4（`reroute()` 的 `reroute` / `reroute-failed`）、Task 5 Step 1 断言。固定阶梯 `quality` 失败日志增加 `skipParentStages`：**未单独实现**——`quality` 的 fail 日志在 `s.skipParentStages` 赋值之前已打出，且短路结果可由 `attemptStage` 序列观察；若需要字段，在 Task 4 Step 9(a) 的赋值后补一条 `s.debugStage("quality", "skip-parent-stages", 0, () -> Map.of("relevance", assessment.relevance(), "threshold", rerouteRelevanceThreshold))`。
- §5.6 前端 → 无改动；`reroute` 复用 `PLAN` 阶段完成事件。
- §7 测试 → 规则正反例（Task 1）、注册表/规划服务门槛与单独成批（Task 2、3）、工作流改道/不可升级/续跑不暴露/阶梯短路与对照（Task 4）、日志（Task 5）。
- §8 配置 → `@Value("${kwiki.agentic.reroute-relevance-threshold:0.2}")`，不动 `application.yml`。
- 类型一致性：`AgentToolArguments.AnswerDirectly(reason)`、`ToolAvailability(5 参)`、`QaPlanningService.Input(…, run, canReroute)`、`Decision.Reroute(reason)`、`RetrievalPlan.planner(Intent, boolean)`、`ScriptQuality.irrelevant` 在各任务间名称一致。
