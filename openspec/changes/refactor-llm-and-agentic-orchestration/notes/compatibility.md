# 实施兼容性与验收记录

日期：2026-09-07。用户已确认 LangGraph4j core / Java 单体，并通过 openspec-apply-change 授权实施提案范围。工作区基线为 7ac6e3f；变更未提交、未归档。

## 依赖与客户端边界

Java 21.0.10、Spring Boot 3.5.6 下，LangChain4j open-ai/core 1.20.0、LangGraph4j core 1.8.20、networknt json-schema-validator 2.0.0 已成功解析、编译、启动并执行回归。使用 Jackson 2 API 的 SchemaRegistry；无 AiServices、框架 RAG pipeline、EmbeddingStore 或自动工具节点。ArchUnit 验证领域包不依赖两套框架 SDK。

LangGraph4j 1.8.20 已将运行时可变的迭代上限接口标记为移除；图现在在 `compile(CompileConfig.builder().recursionLimit(...).build())` 时设置递归上限，应用层 RunContext 仍负责业务步数预算。

LangChain4j 拥有聊天 JSON 和 SSE 解码。自定义 HTTP SPI 只承担 JDK transport、超时、取消与 trace header。MockWebServer 验证同步聊天、native tool request、流式完成无 null；本地 ServerSocket 验证首帧前与首 token 后取消真实关闭连接，不能只靠忽略回调通过。既有 trace 集成测试覆盖聊天和 embedding 的 traceparent。

Canonical JSON Schema 移除根 `$schema` 后投影为 ToolSpecification；OpenAI 非 strict 请求省略 additionalProperties，但服务器仍执行完整闭合 schema。JSON envelope 模式采用普通消息携带 continuation，native 模式逐 callId 配对 assistant/tool 消息。两种模式都不自动执行工具。

保留自有 ES 检索接口和 Qwen embedding：现有 CHILD/PARENT、权限过滤、RRF、引用及模型维度契约比引入 LangChain RAG 接口更贴合 kwiki。本次修改逐 query 向量对应、请求内缓存和 deadline 传播，不迁移模型或索引。

## 实施调整

图使用不可变 next/round 投影和请求独立 Session（RunnableConfig metadata），未引入 checkpoint；Session 保存有界工具历史、证据累计和 QA 数据，RunContext 保存可信 scope/deadline/取消登记。design D3 已同步，不对敏感上下文执行通用序列化。

真实 HTTP SSE 测试发现 Spring MVC ASYNC 再派发缺失 JWT 鉴权上下文；JwtAuthenticationFilter 现对异步派发恢复认证，匿名请求仍返回 401。SSE data 已修复为扁平 JSON，不再嵌套 event/data 文本。

## 本地验证

- `./mvnw clean verify` 完成干净构建，之后对新增协议/生命周期测试再次执行 `./mvnw verify`。最终统计见下方最终检查。
- frontend：typecheck、16 项 Vitest 测试、生产 build 通过。
- 工具：闭合 schema、重复键/ID、整批 query 上限、缓存去重和撤权、native/JSON continuation。
- 图：QA 缺口驱动第二次真实 fixture 检索、首轮空结果恢复、重复尝试终止、非法 QA 不生成、第三轮不足停止、引用错误、冷 publisher、取消、全局 timeout、缓冲溢出与单一终态。
- RunContext：可控时钟验证节点/全局时限，独立请求预算、撤权、取消登记和 ThreadLocal 恢复。
- 检索：BM25 不 embedding、VECTOR 显式失败、逐 query 向量、缓存/RRF 列表去重；既有权限、引用、rewrite 和 trace 测试通过。

首次受限 sandbox 测试因 Mockito attach 权限失败，不能作为业务失败或干净基线；最终测试在获准的本机 Maven 执行环境完成。

## 评估限制与外部验收

7 类标注 fixtures 和结果位于 src/test/resources/agentic/evaluation-cases.json 与构建生成的 target/agentic-evaluation.json。全部预期流程通过。旧基线为单轮有证据即生成的策略模拟，QA 与检索均脚本化；缺失指代、矛盾和文档指令案例显示该旧策略会放行，本图按脚本 QA 拒绝/澄清。这不是完整旧系统实测，也不是实际模型质量或延迟结论。

KWIKI_IT_MYSQL_URL、KWIKI_ANSWER_LLM_API_KEY、KWIKI_QWEN_EMBEDDING_API_KEY、KWIKI_ELASTICSEARCH_URIS 在本会话环境均未设置；未读取或输出凭据。8.4 仍未完成，不能声明生产供应商支持 native tools、JSON QA、取消或 ES/Qwen 全链路。操作步骤见 docs/agentic-rag.md。

回滚使用前一应用制品、对应前端和配置；无数据库/索引迁移。其他未归档 change 的 routing/retrieval/streaming 契约由既有回归验证，本次未自动归档或合并 specs。

## 最终检查

后端 353 项测试，0 failure、0 error、26 skipped（外部集成条件未满足）；前端 16 项通过。OpenSpec strict validate 和 git diff --check 通过。任务为 41/42，唯一未完成项 8.4：真实外部服务验收。归档应在该验收完成后另行执行。
