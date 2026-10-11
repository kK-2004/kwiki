# QA 意图修正（reroute）设计

日期：2026-10-12
前置：`2026-10-08-qa-plan-execute-and-routing-design.md`（P1 路由、P2 工具层、P3 plan-execute 已合入 main）

## 1. 问题

实测「现在几点了」：规则未命中，`kwiki.routing.llm-enabled=false`，兜底成 `KNOWLEDGE_QA` 进入检索。
固定阶梯（`planner-enabled=false`）随后在无关证据上空转：19 次模型调用、约 84 秒，
评审连续给出 `off-topic` / `irrelevant-query`（relevance 0）。

开启 planner 后仍有同一个缺口：
- planner 决策只有 `Calls` / `Sufficient` / `Degrade`，没有「这题不该检索」的出口；
- `direct → retrieval` 的 `[[NEEDS_KNOWLEDGE]]` 升级是单向的。

结果是路由一旦判成检索，QA 阶段无法修正意图。

## 2. 目标与非目标

目标：
1. planner 模式下，QA 阶段在有证据依据时可以把意图改回直答（reroute），并写回路由状态。
2. 固定阶梯在证据明显无关时不再浪费 `*_PARENT` 阶段。
3. 常见的实时信息问句（时间、日期、星期、天气）由规则直接判直答。

非目标：
- 不改质量评审的 `json-schema` 400 重试问题（单独处理）。
- 不让代码在固定阶梯里自动改道（见 §4 风险）。
- 不改默认开关（`planner-enabled`、`llm-enabled` 仍默认关闭）。

## 3. 方案总览

| 层 | 改动 | 谁决定 |
|---|---|---|
| 规则 | `KeywordRuleSet` 增加实时信息规则 → `DIRECT_ANSWER` | 代码（确定性） |
| planner | 新工具 `answer_directly`，通过门槛校验后改走 `direct` | planner 选，代码设门槛 |
| 固定阶梯 | 评审 relevance ≤ 阈值时跳过 `*_PARENT` 阶段 | 代码（确定性） |

## 4. 关键风险与取舍

评审的 relevance≈0 区分不开两种情况：
- 用户问的根本不是知识库的事（「现在几点了」）；
- 确实是知识库问题，只是这次没检索到。

后一种若被自动改成直答，direct 会凭模型常识回答，产生幻觉。因此：
- 「改道」的判断交给 planner（它能看到问题本身、证据摘要、评审结果）；
- 代码只设上限：门槛不满足时工具不可见，输出了也被拒绝；
- 固定阶梯（降级兜底）没有 LLM 做判断，**不自动改道**，只做无风险的短路。

残余风险：planner 在门槛满足时误选 `answer_directly`。缓解：工具说明里明确「仅当问题与知识库无关」，
且 reroute 后的 direct 不能凭空升级回检索，失败走 `finishInsufficient`，不会编造引用。

## 5. 设计

### 5.1 `answer_directly` 工具

- 名称 `answer_directly`，schema `answer-directly-v1.schema.json`：`{"reason": string(1..200)}`，`additionalProperties:false`。
- `AgentTools.ALL` 增加；`ToolRegistry.agentTool` 注册；`AgentToolArguments` 增加 `AnswerDirectly(reason)`。
- 与 `ask_user` 相同：必须单独出现（和其他工具同批被拒，错误码 `answer_directly_must_be_alone`，
  下划线形式，避免 `-` 前缀被密钥审计的 `sk-` 规则误判）。
- 可用性（`ToolAvailability` 新增第 5 个布尔 `canReroute`，保留 3 参 / 4 参构造）：
  `canReroute = 本 run 尚未 reroute ∧ 非反问续跑 ∧ 证据池已检索过（hybridRetrievals>0）∧ 存在上一轮评审且 relevance ≤ 阈值`。
- 阈值：`kwiki.agentic.reroute-relevance-threshold`，默认 `0.2`，与 `planner-enabled` 一样走 `@Value`。
- 校验不通过的拒绝码沿用 `REJECTIONS` 机制（带原因重试一次，仍非法则降级），新增 `reroute-not-allowed`。

### 5.2 决策与流转

- `QaPlanningService.Decision` 新增 `record Reroute(String reason)`。
- 校验通过的 `answer_directly` 不进入 `execute`，由 `plan()` 直接处理：
  1. `s.route` 改为 `DIRECT_ANSWER`（`needsRetrieval=false`，source=`PLANNER`）；
  2. `s.rerouted = true`；
  3. `s.next = "direct"`。
- `RouteSource` 新增 `PLANNER`。`PriorRoute` 以名称持久化，`fromMap` 对非法枚举返回 null，
  旧数据不受影响；`chat_run` 不需要新迁移。
- `direct()`：若 `s.rerouted`，不允许 `escalate`——输出哨兵、为空或调用失败时走
  `finishInsufficient("direct-after-reroute-failed")`，避免来回跳。
- 已生成的候选被丢弃，直答是新的单次生成，预算计入 `modelCalls`。
- `plan` 活动事件摘要：「改为直接回答」。

### 5.3 固定阶梯短路

在 `quality()` 非 planner 分支：评审未通过且 `relevance ≤ 阈值` 时，调用
`advanceRecovery(s)` 前标记 `s.skipParentStages = true`，使 `advanceRecovery` 跳过
`BASE_PARENT` / `EXPANDED_PARENT`（无关块的父段落同样无关）。其余阶梯（扩大检索、改写）不变，
仍然有界。

### 5.4 规则

`KeywordRuleSet.defaults()` 增加两条（锚定整句、短问句，避免误伤「今天的会议时间」这类知识库问题）：
- `realtime-zh`：`^(现在|当前|今天)?(是)?(几点|几号|星期几|礼拜几|什么时间|什么日期|天气(怎么样|如何)?)(了|啊|呢)?[吗?？!！。]*$`
- `realtime-en`：`^(what time is it|what's the time|what is today's date|what day is it)[?!.]*$`

均解析为 `Intent.DIRECT_ANSWER`；具体正则以通过 §7 的正反例测试为准。`VERSION` 升为 `rules-v2`。

### 5.5 日志

debug 级别沿用 `kwiki.agentic.debug`：
- `plan` 阶段新增状态 `reroute`，字段 `reason`、`relevance`、`fromIntent`、`toIntent`、`planRound`；
- 固定阶梯 `quality` 失败日志增加 `skipParentStages`；
- 规则命中沿用 `matchedRuleIds`。

### 5.6 前端

无需改动协议。`RetrievalActivity.vue` 对 `PLAN` 阶段已有标签；reroute 复用 `PLAN` 的完成事件，
摘要文案区分即可。`direct` 的活动事件已存在。

## 6. 数据流（planner 模式，「现在几点了」且规则未命中）

```
route(FALLBACK 检索) → plan(search_chunks) → execute → generate → quality(fail, relevance 0)
  → plan(answer_directly 可见) → Reroute → direct → respond
```

开启 `llm-enabled` 时多数这类问句在 route 阶段就被判成直答，reroute 是第二道防线。

## 7. 测试

- 规则：时间/日期/天气正例命中；「今天的会议时间」「2025营业收入」不命中。
- `ToolRegistry` / `QaPlanningService`：门槛各条件分别不满足时工具不可见、输出被拒；单独出现约束；
  通过时返回 `Reroute`。
- 工作流：
  - 脚本化 planner 先检索、评审失败（relevance 0）、再 `answer_directly`，最终 `outcome=direct`；
  - reroute 后 direct 输出哨兵 → `finishInsufficient`，不再检索；
  - reroute 只允许一次；反问续跑中不可见；
  - `done.route` 与持久化的 `PriorRoute` 均为 `DIRECT_ANSWER`/`PLANNER`。
- 固定阶梯：relevance 低时不出现 `*_PARENT` 阶段；relevance 高但覆盖不足时行为不变。
- 既有 P1–P3 测试保持通过。

## 8. 配置

| 配置 | 默认 | 说明 |
|---|---|---|
| `kwiki.agentic.reroute-relevance-threshold` | `0.2` | 评审 relevance 不高于此值才开放 `answer_directly` / 触发阶梯短路 |

其余开关不变。`.env.example` 与 `docs/agentic-rag.md` 同步补充。

## 9. 未决 / 后续

- 评审 `json-schema` 400 后降级重试：每个候选多一次请求，另开事项。
- 社区 ES 索引创建缺口（`CommunityMappingBuilder` 未使用）：与本设计无关，另开事项。
