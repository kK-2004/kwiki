## Why

当前 kwiki 自行维护 LLM HTTP/SSE 协议，问答主流程仅串行执行一次路由、改写、检索和生成，没有独立质量分析与有界重检索，也没有受 schema 约束的工具调用边界。需要在保留 Wiki 权限、父子分块、RRF 和引用契约的基础上，以轻量模型客户端和显式状态图补齐完整 agentic 流程。

## What Changes

- 使用 LangChain4j 低层 `ChatModel` / `StreamingChatModel` 替换聊天协议适配，统一配置、超时、错误、取消与链路追踪；补齐 `RewriteLlmPort` 实现，并支持规划和 QA 的结构化模型响应。官方流式能力已确认，实际配置的供应商兼容性留给实现期契约验证。
- 编排采用 **LangGraph4j core**，按 Java 21 / Spring Boot 单进程设计；它是受 LangGraph 启发的独立 Java 项目，不是官方 Python/JavaScript 包。用户已明确确认此选择。
- 实现 `请求 → 意图/路由 → 改写决策与检索策略 → 检索工具选择 → schema 校验与手动执行 → 证据汇总 → QA → 有界重检索 / 生成 / 直接返回结果`，包括无需检索、澄清、无证据、失败和取消路径。
- 定义 kwiki 自有工具目录、JSON Schema、调用与结果 DTO；将 schema 转为 LangChain4j 低层 `ToolSpecification`，由应用取得完整调用参数后自行校验、鉴权、分发和关联结果。禁止框架自动执行工具。
- 保留领域 RAG 接口、ES 客户端、标准 RRF、父子块和授权范围；提供 BM25 / VECTOR / HYBRID 受限检索策略，修正每个子查询与其 embedding 的对应关系。
- **Embedding 评估结论：本次保持 `ChunkEmbeddingPort` 与 Qwen 适配器**，不切换向量模型、维度或索引。LangChain4j `EmbeddingModel` 只作为后续可选内部适配方案，不作为本次实施依赖。
- 保留 `/api/v1/chat/stream` 请求及现有 SSE 事件字段，增加工具、质量评估和重试进度；强化整个图的预算、终态、断连取消、出站证据授权检查与现有 trace 持久化。

## Capabilities

### New Capabilities

- `llm-client-integration`: 低层聊天客户端、结构化响应、真实改写适配器与可取消的流式响应。
- `bounded-agentic-orchestration`: 显式状态图、独立 QA、条件分支、有限重试和确定性终止。
- `manual-schema-tool-execution`: schema 单一来源、模型工具选择、应用校验和手动执行、调用关联及错误反馈。
- `agentic-retrieval-strategies`: 受限 ES 策略、子查询向量对应、授权、融合和多轮证据管理。
- `agentic-stream-lifecycle`: 多轮进度、兼容 SSE、并发隔离、全阶段取消与审计。

### Modified Capabilities

无已归档主规格可修改：当前 `openspec/specs/` 为空。上述规格作为新能力编写；明确承接进行中 change `migrate-agentic-rag-and-build-wiki` 的 `agentic-query-routing`、`hybrid-chunk-retrieval`、`cited-answer-streaming` 契约。归档时需协调重叠条款，不修改该 change 或其他进行中 change 的文件。

## Impact

- 后端：`infrastructure/ai`、`rag/{routing,rewrite,retrieval,answer}`；新增 `rag/{orchestration,quality,tool}` 和相应基础设施适配器。
- 依赖：LangChain4j core/OpenAI-compatible 客户端、LangGraph4j core、JSON Schema 校验库；版本在兼容性验证后固定，不引入 AiServices、预制 ReAct Agent、自动 ToolNode 或 LangChain4j RAG pipeline。
- 前端：`frontend/src/features/wiki/sse.ts` 及问答进度展示；旧事件保持兼容，新事件可被旧客户端忽略。
- 运维：复用 `KWIKI_ANSWER_LLM_*` 与独立 `KWIKI_QWEN_EMBEDDING_*`；增加有上界的 agentic 预算配置。无新中间件、数据库结构或 ES mapping 变更，无需重建索引。
- 本 change 为提案；不包含业务实现。模型客户端/流式范围及 LangGraph4j 选型集中在 design 的检查项中供用户 review。
