## Context

`kwiki` 当前只有 OpenSpec 配置，没有应用代码或既有 capability spec。迁移来源 `/Users/kk/code/k-Rag` 是 Java 21、Spring Boot 3.4.2 与 Vue 3/TypeScript 应用，其中 Embedding、双路召回和标准 RRF 已有可借鉴实现，但检索存在新旧链路并行，规则路由与独立 LLM Router 尚未完全接线，并且部分流程与 Neo4j、Kafka 和历史权限模型耦合。

本次变更面向企业内部知识工作者和系统管理员。运行环境已经提供 MySQL、Redis、MinIO 与 Elasticsearch，应用只能通过配置连接这些服务，不能负责创建中间件容器。Qwen `text-embedding-v4` 继续作为 Embedding 模型，但 `kwiki` 必须使用全新凭据。Wiki 内容以 Markdown 为持久化格式，前端提供所见即所得编辑体验。

## Goals / Non-Goals

**Goals:**

- 建立可独立运行、模块边界清晰的 `kwiki` 单体应用，而不是复制 `k-Rag` 的整个历史代码树。
- 提供知识库、目录、页面、版本、标签、链接、附件、来源和成员权限组成的 Wiki MVP。
- 将页面发布和 Apache Tika 可直接提取文本的文档可靠地转换为带来源信息的父子 Chunk。
- 提供规则优先、LLM 兜底的意图识别，以及可审计的会话补全、扩展和问题分解。
- 提供带相同授权过滤的子块 BM25/Vector 独立召回、标准 RRF 融合、父块去重回取和分支降级。
- 通过 SSE 输出可观察的 Agent 步骤、回答 token 和可定位引用。
- 提供接近参考图的三栏 Wiki 工作区和无构建依赖的 HTML 原型。
- 默认开发与测试流程不依赖 Docker、Docker Compose 或 Testcontainers。

**Non-Goals:**

- Neo4j、知识图谱构建、图检索、Community 投影和 Cypher 工具。
- Kafka 或其他消息中间件。
- 图像 OCR、扫描件识别，以及依赖 OCR 才能获得正文的 PDF/图片输入。
- Qwen Rerank 或其他额外重排服务；MVP 的最终顺序由 RRF 决定。
- 实时多人协同编辑、评论审批、复杂工作流和移动端原生应用。
- 从 `k-Rag` 复制生产数据、密钥、索引或历史数据库结构。

## Decisions

### 1. 采用单仓库、模块化单体结构

后端保持根目录 Maven 工程，前端位于 `frontend/`，与迁移来源的开发方式一致。后端包根为 `com.kwiki`，按 `wiki`、`security`、`indexing`、`rag.routing`、`rag.rewrite`、`rag.retrieval`、`rag.answer` 和 `infrastructure` 分区。跨模块依赖通过应用服务或端口接口发生，外部 SDK 只出现在 `infrastructure` 适配器中。

选择该方案是为了保留 Spring Boot/Vue 的团队经验，同时避免在 MVP 阶段承担分布式事务和多服务部署成本。备选方案“整体复制 `k-Rag` 后删除无关代码”会保留重复链路和未完成占位；“立即拆微服务”会过早增加运维面，均不采用。

### 2. 外部服务全部使用显式配置和启动校验

MySQL、Redis、MinIO、Elasticsearch、生成模型与 Embedding 模型都从环境变量注入。仓库仅提供无秘密的 `.env.example` 和 Spring 配置映射，不提供中间件 Docker 文件。`KWIKI_QWEN_EMBEDDING_API_KEY` 必须单独设置，模型名固定为 `text-embedding-v4`；向量维度由一个配置项同时驱动 Embedding 请求校验和 ES mapping，避免两处硬编码漂移。

连接配置使用 Bean Validation 做格式和必填校验。服务连通性进入 readiness，而不是 liveness；某一依赖暂时故障不会让进程反复重启，但会阻止流量进入依赖该服务的功能。

### 3. Wiki 使用不可变版本承载内容，页面保存当前位置

MySQL 核心表包括 `knowledge_base`、`knowledge_base_member`、`wiki_page`、`wiki_page_revision`、`wiki_link`、`attachment`、`indexing_job`、`chat_session` 和 `chat_message`。`wiki_page` 保存父节点、同级排序、状态、当前草稿版本和当前发布版本；`wiki_page_revision` 保存不可变 Markdown、纯文本投影、作者、时间和变更说明。

发布会原子地更新当前发布版本并创建索引任务。恢复旧版本会创建一个新的修订版本，不修改历史记录。页面删除采用归档语义，避免引用和历史立即丢失。Wiki 链接由 Markdown 链接解析为稳定 page ID，反向链接通过 `wiki_link` 查询。

备选的“只在页面表覆盖正文”无法可靠支持历史、恢复和版本引用；“用富文本 JSON 作为唯一真相”会加大索引、导入导出和迁移难度，因此均不采用。

### 4. 使用 MySQL 持久化任务代替 Kafka

页面发布、附件上传或归档时，在同一数据库事务中写入 `indexing_job`。后台 worker 通过带租约的批量领取处理 `PENDING`/可重试任务，并使用指数退避、最大尝试次数和最后错误摘要。唯一幂等键由资源类型、资源 ID、版本 ID 和操作组成。

Redis 用于短期权限范围缓存、限流和跨实例互斥优化，但任务正确性不依赖 Redis。这样即使进程重启也不会丢失索引工作，同时无需引入用户未要求的消息中间件。

### 5. Apache Tika 解析后生成父子 Chunk

上传入口使用文件扩展名、MIME 与 Apache Tika 探测结果的 allowlist，只接收能直接提取有效文本的格式，MVP 至少覆盖 Markdown、纯文本、HTML 和 DOCX。系统不配置 Tesseract 或其他 OCR；扫描 PDF、图片和任何提取后没有有效文本的文件会在入库前或解析阶段被明确拒绝，不进入向量化。

结构化文本先生成父块，再生成子块。Markdown 以大标题章节为首选父块边界；DOCX 等格式使用 Tika 产出的标题/段落结构。父块目标范围为 1024～4096 字符：短章节可在不跨越上级标题的前提下与相邻内容合并，超长章节优先按下级标题和段落拆分。每个父块再按段落累积为 128～512 字符的子块，默认目标 384 字符；单个超长段落优先按句子边界拆分，最后才在 512 字符处硬切。

父块键为 `resourceType:resourceId:revisionId:parentOrdinal`，子块键追加 `childOrdinal`。Elasticsearch 同一版本化索引同时保存 `PARENT` 与 `CHILD` 文档；只有 `CHILD` 带向量并参与 BM25/Vector 召回，父块通过 `parentChunkKey` 批量回取。该方案兼顾细粒度命中与大模型上下文完整性，避免在每个子块重复保存整段父文本。

### 6. 只迁移清晰可验证的 Agentic 路径

请求先标准化，再由版本化关键词规则判断 `DIRECT_ANSWER`、`KNOWLEDGE_QA`、`PROCEDURAL` 或 `ANALYTICAL`。恰好命中一个终止意图时跳过 LLM；无命中、冲突或疑似复合问题时调用 Router LLM。LLM 只能返回受 JSON Schema 约束的意图、是否检索、rewrite mode、子问题和置信度，不能返回 ES DSL、权限字段或工具名。超时、解析失败或 schema 非法时回退到 `KNOWLEDGE_QA`。

Query Rewrite 支持 `NONE`、`CONVERSATIONAL`、`EXPANSION` 和 `DECOMPOSITION`：有指代且存在会话历史时补全上下文；过短或低信息查询做受限扩展；复合问题拆成有限子问题。原始 query 始终保留用于审计和展示。HyDE 不进入 MVP，以减少额外模型调用和难以解释的召回偏移。

### 7. 检索子块、RRF 融合，再按父块去重扩展上下文

Elasticsearch 使用版本化索引 `kwiki-chunks-v1`。Chunk 文档包含 `chunkLevel`、`chunkKey`、`parentChunkKey`、knowledge base、page、revision、parent/child ordinal、heading path、character range、content、tags、visibility/membership scope、embedding model 和 index version。父子稳定键使版本引用、批量回取和幂等覆盖明确。

每个有效 query 并行执行 `chunkLevel=CHILD` 的 BM25 TopK 与 Vector TopK；两条分支在 TopK 计算前应用同一个授权过滤器。对问题分解结果，每个“子问题 × 分支”都是一个有序列表。RRF 分数为 `Σ 1 / (rankConstant + rank_i)`，默认 `rankConstant=60`；不读取、归一化或加权 ES 原始 `_score`。子块候选按 `chunkKey` 去重，同分时依次比较最佳分支名次、BM25 名次、Vector 名次和 chunkKey。

融合后按子块顺序首次出现的 `parentChunkKey` 做稳定 distinct，将同一父块的全部命中子块和最佳 RRF 分数聚合，再批量回取仍在授权范围内的父块。默认每分支子块 Top50、融合子块 Top40、最终父块 Top8，父块文本还受生成上下文总字符/token 预算约束。发送给大模型的是去重后的父块全文；引用记录保留命中子块的 heading path 和字符范围。

某一分支失败时使用另一路结果并记录降级原因；两路都失败时返回结构化检索错误，而不是向生成模型发送空的伪证据。备选的 ES 单查询加权混合会把 BM25 和向量分数的尺度耦合，故不采用。

### 8. 权限范围在导航、召回和出站阶段一致执行

知识库角色定义为 `OWNER`、`EDITOR`、`VIEWER`。所有页面 API 先解析不可变授权范围；ES 两个召回分支使用由该范围生成的同一 filter。在外发生成模型和最终返回引用前重新检查权限范围版本，成员变更会使 Redis 缓存和进行中的旧范围失效。

前端隐藏无权限入口只用于体验，后端仍是唯一授权边界。任何检索降级都不能扩大范围或补入未授权候选。

### 9. SSE 事件同时服务回答和可观察性

聊天接口输出 `route`、`rewrite`、`retrieve`、`token`、`citations`、`done` 和 `error` 事件。证据上下文包含去重父块全文、page/revision/parentChunk 定位和命中子块列表；生成提示明确要求只基于父块证据回答。引用在 token 完成后作为结构化列表发送，前端可跳转至对应页面版本和命中子块段落。

检索没有证据时不调用生成模型，返回“未在当前可访问知识中找到依据”的完成事件。客户端断开时取消下游模型流并保留已完成的审计步骤。

### 10. 原型与生产前端共享信息架构，不共享实现依赖

原型保存为 `prototype/kwiki-wiki.html`，使用原生 HTML/CSS/JavaScript 实现三栏布局、搜索过滤、树折叠、页面切换、编辑预览和历史面板，确保双击即可查看。正式 Vue 前端沿用同一信息架构，但拆分为布局、树、阅读器、编辑器、版本历史和 API store 等组件。

## Risks / Trade-offs

- [应用内 worker 的吞吐量低于专用消息队列] → 使用批量领取、租约、幂等键和可观测积压指标；只有经数据证明需要时再引入消息中间件。
- [Embedding 维度配置与 ES mapping 不一致] → 启动时读取 mapping 并校验首个探针向量，失败时 readiness 不通过且禁止写入。
- [Tika 对同一格式的结构信息质量不同] → 对 Markdown 使用标题解析器补强，对 DOCX 使用 Tika 标题/段落事件并保存解析器版本；用固定 fixture 锁定父子边界。
- [标题章节超出父块范围或单段超出子块范围] → 先按标题、段落和句子逐级拆分，只有无法找到自然边界时才执行硬切并记录边界类型。
- [规则和 LLM 对同一 query 的判断漂移] → 版本化规则、保存 route source/confidence、建立固定回归语料并监控 fallback 比例。
- [多子问题会放大 ES 与模型请求] → 限制最多 3 个子问题、设置全局时间预算，并对等价 query 去重。
- [RRF 无重排时对细粒度语义区分有限] → 先用离线查询集调优分块和召回预算；重排作为后续独立变更，不在本期偷偷加入。
- [权限在长请求期间变化导致内容外泄] → 使用 scope version，在外部模型调用和引用出站前二次校验，变化时中止请求。
- [所见即所得编辑器与 Markdown 往返可能损失格式] → 选用支持 Markdown round-trip 的编辑器适配层，并以 Markdown fixture 做导入、编辑、导出契约测试。
- [现有外部服务版本和地址尚未写入仓库] → 通过环境变量与 readiness 暴露兼容问题；部署前运行显式的外部服务验收 profile。

## Migration Plan

1. 建立后端、前端、Flyway 和无秘密环境配置骨架，先验证四类外部中间件连接与 Qwen Embedding 新凭据。
2. 建立 Wiki 数据模型、内容 API、权限范围和三栏前端基础，使用样例页面完成端到端读写。
3. 建立持久化 indexing job、Tika 文本 allowlist、父子分块和 `kwiki-chunks-v1` 版本化索引；用 Markdown/DOCX fixture 与影子索引验证后切换 alias。
4. 从 `k-Rag` 提取并重写意图规则、LLM schema、Query Rewrite、双路召回和纯函数 RRF，逐项用回归测试证明等价或明确差异。
5. 接入 SSE 回答与引用，在测试知识库上对授权、降级、超时和引用跳转做验收。
6. 上线时先启用 Wiki 与索引，观察任务积压和索引质量；再按功能开关逐步启用 Agentic 路由和回答。

回滚时关闭 Agentic 与索引 worker 功能开关，保留 Wiki 读写；ES alias 切回上一版本索引。数据库迁移只做前向兼容新增，应用回滚版本忽略新表/新列，不删除用户内容。

## Open Questions

没有阻塞设计或实施的产品问题。部署操作者需要在实施验收前提供外部 MySQL、Redis、MinIO、Elasticsearch 连接参数，以及全新的 Qwen Embedding 和回答模型凭据；这些值不进入版本库。
