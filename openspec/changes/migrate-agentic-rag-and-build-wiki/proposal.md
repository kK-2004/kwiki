## Why

`kwiki` 目前只有 OpenSpec 骨架，缺少可用的 Wiki 与智能检索能力；与此同时，`k-Rag` 已积累了可复用的 Agentic RAG 思路，但存在新旧链路并存、图检索耦合和部分 LLM 路由尚未接线等问题。现在需要在保持既有 Spring Boot/Vue 技术栈的前提下，提取可靠能力并建立一个边界清晰、可持续演进的企业 Wiki 基座。

## What Changes

- 建立 Java 21、Spring Boot 3.4.2 与 Vue 3/TypeScript 的 `kwiki` 应用骨架，并按 Wiki、检索、智能编排和外部适配器划分模块。
- 通过环境变量直接连接现有 MySQL、Redis、MinIO、Elasticsearch 服务；仓库不提供或依赖 Docker Compose，不复制 `k-Rag` 的凭据。
- 为 Qwen `text-embedding-v4` 建立独立配置和新的 API Key 注入方式，禁止在代码或配置默认值中保存密钥。
- 增加知识库、目录、Markdown 页面、草稿/发布、版本历史、恢复、标签、双向链接、附件和来源文档等 Wiki 内容能力。
- 增加成员角色、知识库级读写权限和检索范围约束，使 BM25 与向量召回使用相同的授权过滤条件。
- 增加持久化索引任务：页面或文档变化后使用 Apache Tika 提取 Markdown、DOCX 等可直接解析的文本，生成父子 Chunk、向量化子块并更新 Elasticsearch；使用 MySQL 任务表与应用内后台执行器，不引入 Kafka 或 OCR。
- 迁移并重构规则优先、LLM 兜底的意图识别，以及会话补全、扩展和分解等 Query Rewrite 能力。
- 迁移独立的 Elasticsearch BM25 与 Vector 双路并行召回，只检索 128～512 字符的子块，按稳定 Chunk Key 去重，并以标准 RRF 合并排名。
- 在融合后按 `parentChunkKey` 去重回取 1024～4096 字符的标题章节父块，将父块作为大模型上下文，同时保留命中子块作为引用依据。
- 增加基于父块上下文的流式回答与页面、版本、父子块、段落级引用。
- 增加参考腾讯 iWiki 风格的三栏 Wiki 工作区，覆盖搜索、树形导航、阅读、编辑、摘要和历史版本入口。
- 明确排除 Neo4j、知识图谱、图检索、Kafka、图像 OCR、扫描 PDF 处理、实时多人协作、评论审批和额外重排模型。

## Capabilities

### New Capabilities

- `external-service-connectivity`: 现有 MySQL、Redis、MinIO、Elasticsearch 与独立 Qwen Embedding 凭据的外部连接、校验和健康状态。
- `wiki-content-management`: 知识库、目录、Markdown 页面、草稿发布、版本恢复、标签、附件、来源和双向链接的内容生命周期。
- `wiki-access-control`: Wiki 成员角色、知识库读写授权以及贯穿导航、内容读取和检索的统一权限范围。
- `wiki-document-indexing`: 页面与 Apache Tika 可直接解析文档的持久化任务、父子分块、子块向量化、幂等索引和删除流程。
- `agentic-query-routing`: 规则优先与 LLM 兜底的意图识别、结构化路由结果、失败回退和 Query Rewrite。
- `hybrid-chunk-retrieval`: Elasticsearch 子块 BM25 与 Vector 双路召回、分支降级、标准 RRF 融合，以及按父块去重的上下文扩展。
- `cited-answer-streaming`: 使用去重后的父块上下文生成流式回答，并返回可定位到 Wiki 页面版本和命中子块的引用。
- `wiki-workspace-ui`: 三栏 Wiki 工作区中的搜索、知识树、摘要切换、阅读、编辑和版本历史交互。

### Modified Capabilities

无。当前 `kwiki` 尚无已发布 capability spec。

## Impact

- 新增后端 Maven 工程、前端 Vue 工程、Flyway 数据库迁移、Elasticsearch 索引模板以及本地环境变量示例。
- 参考并迁移 `/Users/kk/code/k-Rag` 的 Embedding、意图路由、Query Rewrite、双路召回和 RRF 相关实现，但重新命名包、移除图检索依赖并补齐未完成的 LLM 路由接线。
- 新增 Wiki 与 RAG REST/SSE API；数据写入涉及 MySQL、MinIO、Redis 和 Elasticsearch 四个既有外部服务。
- 运维方式改为显式外部服务地址与凭据注入；任何环境均不由本仓库创建中间件容器。
- 新增单元、契约和可选外部服务集成测试，默认测试不要求 Docker 或 Testcontainers。
