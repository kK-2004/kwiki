## 1. 兼容性与实施基线

- [ ] 1.1 重新读取当前源码及三个进行中的 OpenSpec change，记录本 change 的能力重叠与现有测试基线；只调整本次范围内文件，不覆盖其他未提交成果。
- [ ] 1.2 根据 design D1/D2 将客户端范围、流式官方证据、保留 RAG/embedding 的结论提交用户检查并记录反馈；LangGraph4j core / Java 单体已获确认，不重复征询。
- [ ] 1.3 以 design D1 的候选版本验证 LangChain4j core/open-ai、LangGraph4j core 和 networknt JSON Schema 库的制品解析、Java 21 / Boot 3.5.6 / Jackson 2 依赖收敛，固定版本并形成兼容性报告。
- [ ] 1.4 用 MockWebServer 验证候选 SDK 的同步聊天、原生 tool request、stream completion、首帧前后 transport 取消及 trace header；选定可取消 HTTP transport，不能仅凭屏蔽回调判定通过。

## 2. 领域端口与运行上下文

- [ ] 2.1 定义 kwiki-owned RetrievalPlannerPort、QualityAnalyzerPort、ToolCall/ToolResult、RetrievalDecision、QualityDecision 和 AgenticWorkflowPort；确保 LangChain4j DTO 只出现在基础设施适配器中。
- [ ] 2.2 定义请求级 AgenticState、不可变状态更新和有界列表/证据 reducers；在独立 RunContext 中持有身份、scopeVersion、trace、deadline、取消令牌及预算，禁止模型覆盖。
- [ ] 2.3 实现全 run / 节点超时和模型、工具、轮次、图步数计数，按 design D7 设置默认值及启动交叉校验；提供可控时钟和执行器用于确定性验证。
- [ ] 2.4 建立受限 worker、并发许可和活跃外部调用取消登记机制，测试任务取消/超时后释放资源，以及回调线程不执行阻塞检索或持久化。

## 3. 低层模型适配器

- [ ] 3.1 改造 KwikiAiClientConfiguration，显式创建 ChatModel/StreamingChatModel 并映射现有聊天配置及角色参数；保留独立 Qwen embedding WebClient，验证启动不调用供应商。
- [ ] 3.2 迁移 RouterLlmAdapter 到低层模型客户端，保留规则优先、闭合路由校验、10s/剩余 deadline 与安全 fallback；补齐 RewriteLlmPort 实现并验证四种 rewrite 模式及异常回退。
- [ ] 3.3 实现 retrieval planner 与 QA 的结构化适配、闭合输入/输出协议和语义校验；区分 native tool mode 与显式 JSON-envelope mode，禁止自然语言响应直接成为检索证据。
- [ ] 3.4 将 StreamingAnswerLlmAdapter 改为 SDK 回调驱动的冷 Flux，覆盖完成无 null、有限缓冲、首 token 前后取消、迟到回调及部分 token 后失败不重放。
- [ ] 3.5 为模型 transport 和异步回调接入现有 Observation/trace context，关闭隐式重试或纳入统一预算；验证 router/QA/answer 的 traceparent、脱敏错误及超时语义。

## 4. Schema 工具目录与手动执行

- [ ] 4.1 定义版本化 es_search 输入/结果 schema、ToolDefinition、ToolRegistry.get 和显式 handler 注册；从同一 schema 派生 ToolSpecification，测试供应商 schema 投影不改变服务器约束。
- [ ] 4.2 实现全批调用的严格 JSON 和完整 JSON Schema 验证，再进行 query 去空白/去重、整批三条子查询上限、topK 及预算语义校验；验证缺字段、额外字段、重复键、无效枚举和越界均在执行前拒绝。
- [ ] 4.3 实现 ManualToolDispatcher 显式读取 schema 并调用 handler，服务端注入身份/scope/deadline；验证模型提供 userId、scope、kbIds、index、DSL 被拒绝，任何无 scope 路径不执行检索。
- [ ] 4.4 实现一次 schema 纠正、稳定字段错误码及一次经相同校验器执行的 HYBRID fallback；验证 fallback 自身失败直接终止，且未发生半批执行。
- [ ] 4.5 实现 callId、modelTurnId、schemaVersion 与 ToolResult 关联和输出校验；手动组装模型 continuation 消息，覆盖多调用成功/超时、同 ID 去重、不同参数冲突、完整回调重复和 arguments 碎片不执行。
- [ ] 4.6 扩展 ArchUnit/装配约束，验证框架 SDK 不侵入领域包、无 AiServices/自动 ToolNode/预制 agent 执行路径，工具只由应用 dispatcher 调用。

## 5. 受控 ES 策略与多轮证据

- [ ] 5.1 将 es_search handler 接入现有检索领域服务，支持 BM25/VECTOR/HYBRID；BM25 不调用 embedding，HYBRID 内独立双分支并发，所有分支继续在 CHILD TopK 前过滤权限。
- [ ] 5.2 修正每条有效 query 与对应 embedding 的关联，引入请求内 query+model+dimensions 缓存；验证 A/VA、B/VB 对应及单 query embedding 失败降级，保留既有 ChunkEmbeddingPort 与模型/维度。
- [ ] 5.3 将 run deadline/cancellation 传入或桥接现有 embedding、ES 调用生命周期；避免原有 block/retry 时间超过总预算，验证取消后不启动下一个子查询或重试。
- [ ] 5.4 为检索结果保留 query/branch/round 的排名 provenance，实现跨轮列表去重、标准 RRF 与 child/parent/context 重新截断；验证重复列表不二次加分和稳定排序。
- [ ] 5.5 完善跨轮父块/revision 有效性和 scopeVersion 检查，过滤失效证据并保持引用绑定；覆盖召回前、中、后权限变更及父块缺失。
- [ ] 5.6 区分成功零命中、单分支降级、双分支故障及 VECTOR embedding 不可用；验证错误不会被包装成“知识为空”，后续策略变更明确计入预算。

## 6. QA 与 LangGraph4j 闭环

- [ ] 6.1 实现独立 QA 的确定性证据检查和模型判定，验证支持 ID、覆盖缺口、矛盾、action/sufficient/returnKind 的组合；非法/超时 QA 不能放行生成，检索分数不作为正确概率。
- [ ] 6.2 在 infrastructure 编译 LangGraph4j core 状态图，连接 route、策略/改写、planner、schema/dispatcher、evidence、QA、generate/return 节点与显式条件边，通过 AgenticWorkflowPort 接入 facade。
- [ ] 6.3 实现规则白名单直接回复、知识请求检索、无可信历史的改写回退/澄清和原文请求直接返回；验证模型 needsRetrieval=false 无法绕过知识证据约束。
- [ ] 6.4 实现 QA 缺口驱动的 queries/strategy/topK 有界重试，保存有效证据，检测重复 attempt signature 与第二轮后的无进展；验证首轮零命中仍可恢复及第三轮不足不强行生成。
- [ ] 6.5 在所有图边接入全局预算、deadline、scope 和取消守卫；覆盖 schema repair 消耗模型预算、不增加检索轮次、图步数兜底、预算耗尽与同时执行请求的隔离。
- [ ] 6.6 用脚本化模型和检索 fixtures 覆盖首轮成功、缺口重检索成功、无证据、QA 失败、澄清、直接摘录、工具失败和撤权路径；断言实际节点顺序及外部调用次数，不只验证最终文案。

## 7. SSE、引用、审计和前端

- [ ] 7.1 将 AgenticAnswerService 改为延迟订阅的 run facade，统一取消和终态守卫，去除提前启动且脱离请求的 subscribe；验证 completion/error/cancel 竞争只终结一次。
- [ ] 7.2 保留 wire 的 seq/requestId/type 与扁平业务字段，扩展 tool/quality/retry 元数据；修复 ChatStreamEvent 与 ServerSentEvent 的双重封装，使用真实 HTTP controller 响应验证 data 是 JSON 而非嵌套 SSE 文本。
- [ ] 7.3 将最终回答/直接摘录绑定 retained evidence ID，生成前及发引用前重验 scope；验证真实引用定位、未知引用 error、撤权不发 stale citations，保留成功和无证据响应契约。
- [ ] 7.4 复用现有 chat/request_trace 存储，显式传递捕获的 traceId，记录节点、轮次、schema 版本、预算和 outcome；覆盖取消审计、异步 MDC 缺失和持久化故障不改变终态。
- [ ] 7.5 更新 frontend sse parser/reducer 和问答进度 UI，展示多轮检索、tool/QA/retry 与澄清/无证据结果；处理未知事件、终态后事件和错误后的部分答案状态。
- [ ] 7.6 验证完整事件顺序、seq 单调、取消发生在 planner/ES/QA/answer 各阶段、缓冲溢出和前端卸载；确保没有后续工具调用、重复终态或资源泄漏。

## 8. 回归、外部验收与交付

- [ ] 8.1 在变更范围完成后运行 ./mvnw verify，覆盖既有 routing/rewrite/RRF/scope/citations、QwenEmbeddingClient 和 TracePropagationIntegrationTest 回归；默认测试不得依赖真实模型或新增中间件。
- [ ] 8.2 在 frontend 运行 npm run typecheck、npm test、npm run build，验证原有问答和引用定位及新多轮进度均通过。
- [ ] 8.3 准备小型标注问答集，包含精确查找、语义、复合问题、无证据、缺失指代、矛盾资料和文档内指令；以相同数据比较旧/新流程的证据充分性判定、支持引用、重试次数和耗时，记录误判样例而非仅声称质量提升。
- [ ] 8.4 在配置齐全的显式 external-it 环境验证实际聊天 endpoint/model 的 streaming、native tools/JSON mode、QA 和取消，以及 ES/Qwen 全链路；记录脱敏结果，缺少条件时明确标记未完成外部验收，不伪造通过。
- [ ] 8.5 更新 AI 配置、预算默认值、运行流程及回滚文档；确认无数据库/索引迁移，回滚使用前一应用制品和对应配置；核对重叠 OpenSpec 契约后运行本 change 的严格校验，按真实进度更新任务状态。
