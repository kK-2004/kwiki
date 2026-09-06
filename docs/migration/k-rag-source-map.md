# k-Rag → kwiki 源码映射

本文档记录 `k-Rag` 中每一类实现与 `kwiki` 对应物（或明确的排除项）的映射关系。

## 总体策略

kwiki 不是 k-Rag 的复制：模块边界按 `wiki`、`security`、`indexing`、`rag.*`、`infrastructure`
重新划分，外部 SDK 只出现在 `infrastructure` 适配器中。迁移的代码一律重写包名、补齐测试，
不携带生产数据、密钥或历史数据库结构。

## 已迁移并重写的部分

| k-Rag 能力 | kwiki 对应物 | 说明 |
| --- | --- | --- |
| Embedding 调用 | `infrastructure/ai/QwenEmbeddingClient` | 保留 `text-embedding-v4`；新增批量、退避重试、维度校验与无凭据契约测试；必须使用全新 kwiki 凭据 |
| 意图规则路由 | `rag/routing/KeywordRuleSet` + `RuleFirstRouter` | 规则版本化（`rules-v1`）；单一终止意图直接跳过 LLM；无命中/冲突/复合问题进入 LLM 兜底 |
| LLM Router 接线 | `infrastructure/ai/RouterLlmAdapter` | 替换 k-Rag 中未完成的占位实现；受 JSON Schema 约束 + `RouterDecisionValidator` 闭合校验 |
| Query Rewrite | `rag/rewrite/*` | NONE/CONVERSATIONAL/EXPANSION/DECOMPOSITION 四模式；原始 query 永远保留；失败回退安全检索 |
| 双路 BM25/Vector 召回 | `rag/retrieval/{ChildRecallPort,Bm25RecallAdapter,VectorRecallAdapter,ConcurrentRecallService}` | 仅 CHILD 文档参与召回；两分支共用同一授权过滤器（`ScopeFilter`）；分支失败降级不扩权 |
| RRF 融合 | `rag/retrieval/StandardRrfFusion` | 无依赖纯函数；rank 从 1 开始、`Σ 1/(60+rank)`；不用 ES 原始 `_score` |
| 父块回取 | `rag/retrieval/ParentEvidenceResolver` + `rag/answer/EvidenceAssembler` | 按父块去重、首命中顺序、总字符预算截断但保留子块引用 |
| SSE 回答流 | `rag/answer/{ChatStreamEvent,AgenticAnswerService,ChatController}` | route/rewrite/retrieve/token/citations/done/error 事件 + 单调序列号；无证据不调模型；断连级联取消 |

## 明确排除的部分

| k-Rag 内容 | 排除原因 |
| --- | --- |
| Neo4j / 知识图谱 / Cypher 工具 / Community 投影 | 设计 Non-Goal：kwiki 不含图存储与图检索 |
| Kafka 及消息中间件依赖 | 用 MySQL `indexing_job`（租约 + 幂等键）替代（设计决策 4） |
| HyDE（假设文档扩展） | 增加额外模型调用且召回偏移难解释；rewrite 语料库中记录为有意排除 |
| Qwen Rerank / 其他重排服务 | MVP 顺序完全由标准 RRF 决定；重排留作后续独立变更 |
| 旧版混合检索链路（新旧并行实现） | kwiki 只保留一条链路：子块双路召回 → RRF → 父块扩展 |
| 图像 OCR / 扫描件处理 | `DocumentParseService` 对无文本输入直接拒绝，不进入向量化 |
| 历史权限模型 | 重写为 `OWNER/EDITOR/VIEWER` + `AuthorizationScope`/scope version 出站防护 |
| 生产数据、密钥、索引与历史表结构 | 全部不迁移；kwiki 使用全新凭据与 Flyway V1–V3 |
