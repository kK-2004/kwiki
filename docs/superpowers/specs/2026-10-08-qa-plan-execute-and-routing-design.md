# QA plan-execute 与路由分流设计

## 背景与目标

现状（`LangGraphAgenticWorkflow`）是“带反馈回环的确定性 workflow”：

- 节点 `route → retrieve → generate → quality → rewrite → respond`，下一步由 Java 代码写死。
- `route()` 末尾无条件 `s.next = "retrieve"`，`RetrievalPlan` 的 `needsRetrieval` / `rewriteMode` 不影响流程；只有问候正则和“指代”澄清正则会分流。
- `kwiki.routing.llm-enabled` 默认 `false` 且 `application.yml` 无显式配置，LLM 路由实际没有运行。
- 路由无跨 turn 状态；`conversationHistory` 只被 `FeedbackRewriteAgent` 使用，“它怎么用？”类追问在有历史时仍被直接打回澄清。
- `RetrievalPlannerAdapter` / `ManualToolDispatcher` / `ToolRegistry`（仅一个 `es_search`）已存在，但不在主链路上。

目标：**把 QA 节点做成 plan-execute 的 agentic，其余保持 workflow**，避免整条链路大幅摆动。

1. 路由按真实结果分流，并带上一轮路由状态；冲突时升级到 LLM router。
2. QA 节点由 planner 规划工具调用（child 检索、parent 扩展、图检索、query 重写、反问），**代码**校验并执行、拼装上下文，框架不执行工具。
3. 不确定时反问，让用户选择或回答。
4. 全程 debug 级日志；`llm-enabled` 补显式配置，保持默认关闭。

非目标：不引入 `@Tool` / `AiServices` 自动执行；不改质量门禁、授权、预算守卫、引用校验；不改检索/索引本身。

## 已确认的关键决策

| 决策点 | 结论 |
|---|---|
| planner 与固定阶梯/门禁 | planner 取代阶梯；质量门禁保留；阶梯保留为降级路径 |
| 一轮 plan 的形态 | 每轮一批互相独立的调用；结果在下一轮 plan 前被观察（plan→execute→observe 回环） |
| 路由状态来源 | 落库到 `chat_run`（需 migration） |
| `needsRetrieval=false` | 新增 `direct` 节点，轻量 LLM 直答 |
| 谁能反问 | Router 与 Planner 都可以 |
| 反问机制 | 结束当前 run，下一次请求携带回答续跑；不挂起 run |
| planner 开关 | `kwiki.agentic.planner-enabled`，默认关闭 |

## 第 1 部分：图结构与路由分流

### 节点拓扑

新增节点只有 `direct`、`plan`、`execute`，其余保留。

```
route ─┬─ 问候（规则命中）──────────────→ respond（固定文案）
       ├─ needsRetrieval=false ─→ direct ─┬─ 直答 ──→ respond
       │                                  └─ 升级 ──→ plan（planner 关闭时 retrieve）
       ├─ 需反问 ─────────────────────────→ clarify 终止（见反问一节）
       └─ 需检索 ─→ plan ─→ execute ─→ generate ─→ quality ─┬─ 通过 → 发布
                    ▲                                        └─ 不过 ─┐
                    └──────────────── 带评审反馈重新规划 ◄───────────┘
         planner 关闭或降级 → 原 retrieve / advanceRecovery / rewrite 阶梯
```

### 路由真正分流

- `route` 末尾按 `RetrievalPlan` 分流：`needsRetrieval=false` 走 `direct`，否则走 `plan`（planner 关闭或降级时走 `retrieve`）。
- `rewriteMode`、`subqueries` 降为给 planner 的**提示**；阶梯模式下仍不使用，行为与现状一致。
- `route()` 内硬编码的问候正则并入 `KeywordRuleSet`（`greet-zh` / `greet-en` 已存在），纯问候仍走固定文案、不调 LLM。

### direct 节点

- 无检索、无引用；独立 system prompt，直答闲聊、翻译、改写类请求，禁止声称信息来自知识库。
- 与 `CandidateGenerator` 一致：服务端完整收集后再发布，未审核 token 不外泄。
- **误路由补救**：prompt 允许模型整段只输出哨兵 `[[NEEDS_KNOWLEDGE]]`；代码检测到后把路由改为 `KNOWLEDGE_QA` 并转入 `plan`（planner 关闭时 `retrieve`）。每个请求只升级一次，防止循环。

### 路由状态跨 turn

- migration 为 `chat_run` 新增可空列：`route_intent`、`route_source`、`route_needs_retrieval`、`route_confidence`；每轮结束写入最终路由（含直答升级后的结果）。版本号以 `db-migration` skill 核对为准（当前最新为 V38），并通过 `scripts/validate-migrations.sh`。
- `AgenticAnswerService` 像加载 `historyBefore` 一样加载上一轮的 `PriorRoute`，经 `AgenticWorkflowPort` 传入工作流。

### 冲突 → 升级到 LLM router

规则命中后，满足以下任一条件即判定冲突，改交 LLM router：

1. 存在上一轮路由，且本轮是依赖上文的指代或简短追问（“它怎么用？”）。无历史时才走“无历史指代”反问（原澄清正则的替代）。
2. 规则得出的“是否需要检索”与上一轮不一致。

LLM router 的 prompt 带上 `PriorRoute` 与最近若干条用户消息。LLM 关闭或失败时沿用现有回退：`KNOWLEDGE_QA`（偏向检索）。

### 配置

`application.yml` 显式补 `kwiki.routing.llm-enabled: ${KWIKI_ROUTING_LLM_ENABLED:false}`，`.env.example` 同步；新增 `kwiki.routing.clarify-confidence-threshold`（默认 0.5）。

## 第 1b 部分：反问（Clarification）

### 协议

- 新增 SSE 事件 `clarification`：`clarificationId`、`question`、`options[≤4]`（`id`、`label`，可选 `kbId` / `pageId`）、`allowFreeText`、`source`（`router` | `planner`）。
- 事件后以 `done(outcome=clarification)` 收尾。事件经 `ChatRunEventStore` 持久化，可回放。

### 状态与续跑

- `chat_run.pending_clarification`（可空 JSON）：id、原问题、来源、选项、当时的路由快照。与路由列放在同一个 migration。
- `ChatRequest` 增加可选 `clarification:{id, optionId | text}`。服务端校验 `optionId` 属于该待答记录，**标签以服务端存储为准，不信任客户端文案**。
- 代码把“原问题 + 回答”合成有效问题。选项带 `kbId` / `pageId` 时收窄检索范围，范围仍经 `AuthorizationScopeResolver`，越权 id 照常被拒。
- 续跑的这次 run 禁止再反问。用户不理会反问直接发新问题，则待答记录作废。

### 触发点

- **Router**：LLM router 输出 schema 增加可选 `clarify`（问题 + 选项），`RouterDecisionValidator` 增加对应校验（仍为闭集，拒绝 scope / 凭据 / DSL 字段）；置信度低于阈值时反问。LLM 关闭时，“无历史指代”仍反问，从纯文本 token 改为正式 `clarification` 事件（仅自由输入）。
- **Planner**：`ask_user` 工具，选项必须引用当前证据池里真实存在的 kb / 页面，代码校验引用有效，不接受 LLM 凭空造选项。`ask_user` 必须是该轮唯一调用，执行后直接结束 run。

### 前端

`ConversationMessages.vue` 渲染选项按钮与输入框；`conversationStore.ts` 发起带 `clarification` 字段的请求。

## 第 2 部分：QA 节点 plan-execute

### 工具集

`ToolRegistry` 扩为多工具，每个工具一份版本化 JSON Schema（`resources/agentic/tools/`），按运行时能力动态暴露。

| 工具 | 作用 | 执行方 |
|---|---|---|
| `search_chunks` | child 检索：`queries≤3`、`strategy`、`range: BASE/EXPANDED`（映射现有两档预算） | `QaChildRetrievalService` |
| `expand_parents` | child → parent：`childChunkKeys` 必须是证据池子集 | `QaParentFetchService` |
| `graph_expand` | 图检索；仅图功能开启且证据池非空时暴露 | `GraphRetrievalEnhancer`，共享 run 级图预算 |
| `rewrite_query` | `mode: CONVERSATIONAL / EXPANSION / DECOMPOSITION / FEEDBACK` | 现有 rewriter 与 `FeedbackRewriteAgent` |
| `ask_user` | 见第 1b 部分 | 结束 run |

planner 不调用任何工具，表示“证据足够”，进入 generate。

### 每轮循环（全部由代码控制）

1. `plan`：planner 看到观察摘要，输出 `tool_calls`。LLM 只给策略。
2. 校验：schema、数量（每轮 ≤3）、去重、预算、工具可用性、每轮至多 1 次 `rewrite_query`。
3. `execute`：代码按序调用 dispatcher；每次前后重新鉴权，`callId` 幂等，结果经 `tool-result` schema 校验后写入证据池。
4. `generate` → `quality`。通过则发布；不过则把评审结果作为下一轮观察，回到 `plan`。

### 观察摘要

有长度上限，证据文本一律按不可信数据处理：问题、路由提示、当前 query 与改写历史、用户历史、证据池概览（chunkKey、kbId、headingPath、120 字摘录、是否已扩展 parent）、此前工具结果、上轮评审（分数、`reasonCode`、`missingAspects`、`unsupportedClaims`）、剩余预算。

### 降级

planner 未启用、模型报错、或输出非法（带 `validationError` 重试 1 次后仍非法）时，标记 `plannerDegraded`，从当前状态切回原固定阶梯继续。降级只发生一次，不来回切换。

### 其他

- 轮次上限复用 `max-query-rounds`（3）；其余预算沿用 `AgenticLimits`。
- 传输沿用 langchain4j 低层 `ChatModel`，`tool_calls` 由我们的代码解析与执行；`native` 与 `json` 信封两种模式都保留。
- 证据池按 `chunkKey` 去重；generate 的上下文在已扩展 parent 时使用 parent，否则使用 child（沿用 `CandidateAnswer.EvidenceLevel`）。

### 开关

`kwiki.agentic.planner-enabled`，默认 `false`。

### debug 日志

经 `AgenticDebugLogger`，DEBUG 级别，字段有界、不记录证据全文：

- `plan`：输入摘要、原始 tool_calls、校验结果或拒绝原因。
- `tool`：开始 / 结束，含名称、参数签名、耗时、命中数、降级项。
- `observe`：证据池增量。
- `fallback`：降级原因。
- `clarify`：反问来源与选项数。

## 第 3 部分：交付拆分、测试与风险

### 交付拆分

三个可单独合入、单独回滚的阶段，每阶段合入后默认行为不变：

1. **P1 路由分流与反问基础**：分流与 `direct` 节点、升级哨兵、`PriorRoute` 落库（含 migration）、冲突升级、`llm-enabled` 配置、`clarification` 事件与前端（仅 router 触发）。
2. **P2 工具层**：`ToolRegistry` 多工具化，四个工具的 schema 与执行器，`RetrievalPlannerAdapter` 泛化为多工具规格。不接入图，仅单元测试。
3. **P3 plan-execute 接入**：`plan` / `execute` 节点、`ask_user`、降级、`planner-enabled`、debug 日志。

### 测试

- **路由**：冲突判定（指代加历史、检索需求不一致）、LLM 关闭或失败回退、`PriorRoute` 读写、`clarify` 校验。
- **direct**：直答、哨兵升级仅一次、未审核 token 不外泄。
- **工具层**：schema 正反例、预算越界、`callId` 幂等与冲突、执行前后越权被拒、图关闭时 `graph_expand` 不暴露。
- **工作流集成**：脚本化 fake planner 跑完整图——一轮通过、评审不过后重规划、输出非法降级到阶梯、`ask_user` 终止且续跑禁止再反问、取消与超时守卫。
- **回归**：`planner-enabled=false` 时现有 orchestration 测试保持不变。
- **前端**：vitest 覆盖反问渲染、选项点击、自由输入、放弃后新问题。

### 风险与对策

| 风险 | 对策 |
|---|---|
| 每轮多一次 planner LLM 调用，延迟与成本上升 | 默认关闭；轮次上限 3；观察摘要有界 |
| 证据中的 prompt injection 影响 planner | 证据按数据处理；工具参数 schema 校验；`ask_user` 选项必须引用真实证据；执行前后重新鉴权 |
| `direct` 编造知识库内容 | 独立 prompt 禁止声称来源；哨兵可升级到检索 |
| 反问过度打扰用户 | LLM router 阈值可配；续跑禁止再反问 |
| migration 影响现网 | 新增列均可空；用 `db-migration` skill 核对版本并跑 `scripts/validate-migrations.sh` |
