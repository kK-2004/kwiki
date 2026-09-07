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
