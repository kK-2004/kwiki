# Agentic RAG 运行说明

聊天客户端使用 LangChain4j open-ai 1.20.0，编排使用 LangGraph4j core 1.8.20。两者只在基础设施层使用。保留现有 ES CHILD 召回、RRF、PARENT 上下文、权限过滤和 Qwen embedding 接口；不需要数据库或索引迁移，也不改变 embedding 模型、维度及文档分块。

请求订阅后执行 `route → rewrite → plan → validate → retrieve → quality`。QA 选择生成、带缺口重新改写检索或返回澄清/证据不足；问候与帮助只允许规则白名单回复。生成引用只接受本轮保留上下文中的 P 标签，输出引用前再次校验权限版本。断开 SSE 会取消模型 transport 和已登记任务。

工具协议由 `src/main/resources/agentic/tools/es-search-v1.schema.json` 定义。模型只提出调用；ToolRegistry 严格解析整批参数，ManualToolDispatcher 读取目录并显式执行 handler。用户身份、知识库权限、deadline 不来自模型参数。最多一次参数纠正，然后一次同样接受校验的 HYBRID fallback；无自动工具执行器。

## 配置

继续使用 `kwiki.answer-llm` 及 application.yml 中既有环境变量映射，无需新增聊天凭据。同步角色 temperature=0/maxTokens=2048，生成 temperature=0.2/maxTokens=4096；同步 SDK 自动重试关闭。embedding 独立保留既有配置。

以下 Spring properties 可在部署配置中覆盖：

| 属性 | 默认值 |
| --- | --- |
| kwiki.agentic.tool-mode | native；供应商不支持 tools 时显式设 json |
| kwiki.agentic.max-rounds | 3 |
| kwiki.agentic.max-model-calls | 16 |
| kwiki.agentic.max-tool-calls | 9 |
| kwiki.agentic.calls-per-round | 3 |
| kwiki.agentic.max-steps | 64 |
| kwiki.agentic.timeout | 180s |
| kwiki.agentic.buffer-size | 256 |
| kwiki.agentic.concurrent-runs | 16 |

节点时限：route 10s，generate 120s，其余 15s，外部模型调用取节点/请求剩余时间与客户端配置的最小值。检索继续使用既有 TopK、上下文及召回时限。可降低预算；轮次、模型数、工具数和步数不得高于表中上限。

原生模式使用 SDK ToolSpecification；兼容投影移除根 `$schema`，非 strict OpenAI 请求可能省略 additionalProperties。服务器始终使用完整 Draft 2020-12 schema 校验，因此供应商支持程度不能放宽执行边界。JSON 模式同样校验，不根据一次错误自动切换协议。

## 验证与诊断

`./mvnw clean verify` 不需要真实模型。`cd frontend && npm run typecheck && npm test && npm run build` 验证界面。MockWebServer/真实本地 socket 覆盖原生 tool call、流式完成、首帧前后实际断开和 traceparent；真实 HTTP controller 测试检查扁平 SSE JSON 以及异步 JWT 鉴权。

`target/agentic-evaluation.json` 是 7 类标注 fixture 的脚本化 QA/检索流程比较：精确、语义、复合、空结果、缺失指代、矛盾、文档内指令。旧基线只是原单轮“有证据即生成”策略，耗时不包含真实模型/ES；不能据此宣称模型质量或生产性能改善。

SSE 保留 seq/requestId/type，新增 tool/quality/retry；done 可含 outcome/message。错误后已发送文本在 UI 标记为不完整。审计复用 chat/request_trace，记录 traceId、节点、轮次、预算、schema 版本与 outcome；审计写入失败增加 `kwiki_chat_audit_failures_total`。

常见错误包括 agent-busy、model-busy、timeout、authorization-changed、retrieval-failed、vector-unavailable、invalid-citation。零命中是证据不足；外部故障不能等价为知识库为空。

## 外部验收与回滚

真实环境仍需操作者配置既有 external-it 所需 MySQL/ES/Qwen/聊天端点及凭据，先运行 `scripts/verify-external-services.sh`，再运行 `./mvnw -Pexternal-it verify`。该 profile 的既有测试不能替代新 agent 的供应商验收：还需通过已登录 chat HTTP 接口验证 native 和显式 JSON 两种模式、QA 重检索、引用、首 token 前后断开及取消后的外部请求停止，保留脱敏 trace 和调用次数。

本次未执行真实供应商验收。部署前应将相同标注问题交给真实模型复测误判与调用成本。回滚部署前一应用制品及对应前端和配置；无需回滚数据库或重建 ES 索引。

## 路由分流、直答与反问

- **分流**：`route` 节点按 `RetrievalPlan` 分流。纯问候走固定文案；`needsRetrieval=false` 走 `direct` 节点（LLM 直答，无检索、无引用）；其余进入检索。
- **直答升级**：`direct` 模型若整段只输出 `[[NEEDS_KNOWLEDGE]]`（或不可用），工作流转入检索；每个请求最多升级一次。
- **路由状态**：每轮最终路由写入 `chat_run.route_*`；下一轮把它作为 `PriorRoute` 传给路由器。依赖上文的追问在有历史时升级到 LLM router；规则得出的“是否需要检索”与上一轮不一致时同样升级，LLM 不可用则保留规则结果。
- **反问**：无历史的指代（“它怎么用？”）、LLM router 给出 `clarify` 或置信度低于阈值时，发出 `clarification` SSE 事件（问题、至多 4 个选项、是否允许自由输入）并以 `done(outcome=clarification)` 结束本次 run。下次请求在 `clarification:{id, optionId | text}` 里带回回答，服务端按存储的选项校验、合成有效问题后续跑，且续跑不再反问。待答记录存于 `chat_run.pending_clarification`，只认紧邻的上一个 run。
- **配置**：`kwiki.routing.llm-enabled`（默认 `false`，`KWIKI_ROUTING_LLM_ENABLED`）、`kwiki.routing.clarify-confidence-threshold`（默认 `0.5`）。

## QA agent 工具层（P2，尚未接入图）

为 QA 节点的 plan-execute 预备的工具层。**LLM 只产出 `tool_calls`（策略），校验与执行全部在代码里**，不使用 `AiServices` / `@Tool` 自动执行。

- **工具**（`rag/tool`，schema 在 `resources/agentic/tools/*-v1.schema.json`，全部 `additionalProperties:false`，不含 scope / kbId / DSL）：
  - `search_chunks`：`queries≤3`、`strategy`（BM25 / VECTOR / HYBRID）、`range`（BASE / EXPANDED，映射 `QaRetrievalBudgets`）；每个 query 一次独立召回，命中合并进证据池。
  - `expand_parents`：child → parent，可指定证据池里的 `childChunkKeys`。
  - `graph_expand`：图增强；图未启用或证据池为空时不暴露。
  - `rewrite_query`：`CONVERSATIONAL` / `EXPANSION` / `DECOMPOSITION` / `FEEDBACK`；结果写入证据池状态（当前 query、改写历史、候选子查询）。
- **校验**：`ToolRegistry.validateAgentBatch` 检查数量（≤ `callsPerRound`）、callId、工具是否当前可用、参数 schema，以及批级规则（去重后查询总数、每批至多 1 次改写）。
- **执行**：`AgentToolDispatcher` 逐个执行——重新鉴权、`callId` 幂等、`toolCalls` 预算、错误映射（预期失败 → `ERROR` 结果；取消 / 超时 / 授权变化 → 中止 run）、结果按 `tool-result-v2` 校验、DEBUG 日志（`kwiki.agentic.debug`，`phase=tool`，不含证据全文）。
- **Planner**：`AgentPlannerAdapter` 支持 `native` 与 `json` 两种模式（`kwiki.agentic.tool-mode`），只把模型输出转成 `ToolCall`。
- **旧路径**：`es_search`、`ManualToolDispatcher`、`RetrievalPlannerAdapter` 保持不变。
- **P3 才会做**：`plan` / `execute` 节点、`ask_user`、降级到固定阶梯、`kwiki.agentic.planner-enabled`、观察摘要。

## QA plan-execute（P3）

`kwiki.agentic.planner-enabled=true`（默认关闭）时，QA 节点由 planner 规划工具、**代码**执行并拼装上下文；关闭时保持固定的检索—评审—改写阶梯。

- **流程**：`route →（需要检索）plan → execute → generate → quality`；质量门禁不过则带评审结果回到 `plan`，最多 `max-query-rounds`（3）轮，耗尽后回固定的“信息不足”文案。证据没有更新就不会重新生成，而是回到规划。
- **plan**：`QaPlanningService` 构造有界观察摘要（问题、当前 query、证据摘录、上一轮评审、剩余预算；证据按不可信数据处理并脱敏限长），调用 planner，校验输出；非法则带原因重试 1 次。
- **execute**：`AgentToolDispatcher` 逐个执行 `search_chunks` / `expand_parents` / `graph_expand` / `rewrite_query` / `ask_user`，结果写入 per-run 的 `EvidencePool`。生成时已扩展 parent 则用 parent 上下文（`[P#]` 按池内顺序重新编号），否则用 child。
- **ask_user**：必须单独成批；选项只能引用证据池里的子块，kbId / pageId 由服务端从证据派生；反问续跑的 run 不暴露该工具。以 `clarification` 事件（`source=planner`）结束本次 run，回答协议与路由反问一致。
- **降级**：planner 输出两次非法或供应商故障 → 回固定阶梯（`retrieve → … → rewrite`），预算计数从证据池带过去，降级只发生一次，指标 `kwiki_planner_degraded_total{reason}`。取消 / 超时 / 授权变化 / 模型预算耗尽照常中止，不降级。
- **日志**（`kwiki.agentic.debug`，DEBUG）：`phase=plan`（observation / rejected / calls / sufficient / degraded）、`phase=tool`（start / complete）、`phase=observe`（证据池增量）。
