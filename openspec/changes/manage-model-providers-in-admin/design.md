## Context

### 已检查的现状

| 位置 | 当前行为 | 本次处理 |
| --- | --- | --- |
| `KwikiAiClientConfiguration` | 读取 `kwiki.answer-llm`，注册唯一的 `kwikiChatModel`（temperature 0.0）和 `kwikiStreamingChatModel`（0.2，开启 thinking）；`AnswerModelCapabilities` 在启动时探测输出上限 | 删除这两个 Bean；输出上限探测下沉到每个模型，在 reload 时执行 |
| `AgentPlannerAdapter`、`RetrievalPlannerAdapter`、`RouterLlmAdapter`、`RewriteLlmAdapter`、`DirectAnswerLlmAdapter`、`QualityAnalyzerAdapter` | 注入同一个 `ChatModel` | 注入 `ModelRegistry`，每次调用时按用途取模型 |
| `StreamingAnswerLlmAdapter`、`SelectionQuestionLlmAdapter`、`QualityV2AnalyzerAdapter` | 注入同一个 `StreamingChatModel` | 同上 |
| `GraphModelClients` | 抽取/摘要读取 `kwiki.graph.models.*`，缺失项回退到问答模型；每个端点各有一个 `RateLimiter` | 从 registry 获取模型，限流器改为每个 provider 一个 |
| `MultimodalConfiguration.qwenVisionSummaryClient` | 读取 `kwiki.vision-model`，构建 WebClient | 改为由 registry 提供 `ImageSummaryPort` |
| `VersionedIndexingPipelineRegistry` | 按 `EditableIndexConfig.embeddingProvider`（profile 名）查 `kwiki.indexing.embeddings.*` 或 `kwiki.qwen-embedding` | 按 `embedding_model_id` 从 registry 取客户端 |
| `search_index_version`（V21） | `embedding_provider` 只存 profile 名，外加 `embedding_model` 和 `embedding_dimensions` | 新增可空列 `embedding_model_id`，原有三列保留作为快照 |
| `admin-frontend` | 已有索引管理、灰度、知识图谱三个视图，后端接口在 `/api/v1/admin/*` 下 | 新增「模型配置」视图 |

数据库为 MySQL，迁移工具为 Flyway（已有迁移只读，最新版本为 V39）。Redis 已接入（`KwikiRedisConfiguration`）。

## Goals / Non-Goals

**Goals**
- 在后台维护 provider 与模型，各用途可以独立选择模型并设置调用参数，保存后无需重启即可在所有实例生效。
- API key 只以密文形式落库，任何接口和日志都不出现明文。
- 上线不中断服务：首次启动时自动从 Nacos 导入现有配置。

**Non-Goals**
- 除 OpenAI-compatible 以外的其他协议（表中保留 `protocol` 列，便于以后扩展）。
- 主密钥轮换工具、调用费用统计、按知识库或按用户区分模型。
- embedding 不支持热切换，只能随索引版本切换。

## Decisions

### D1 数据模型（新增迁移 V40）

```
model_provider(id, name UNIQUE, protocol, base_url, api_key_cipher, api_key_hint,
               request_timeout_ms, max_retries, max_concurrency NULL, qps NULL,
               enabled, created_at, updated_at)
model_definition(id, provider_id FK, model_name, display_name, dimensions NULL,
                 max_output_tokens NULL, supports_tools, supports_thinking, enabled,
                 created_at, updated_at, UNIQUE(provider_id, model_name))
model_definition_tag(model_id FK, tag ENUM('chat','vision','embedding'), PK(model_id, tag))
model_assignment(purpose PK, model_id FK NULL, temperature NULL, max_tokens NULL,
                 thinking NULL, updated_at)
model_config_version(id=1, version BIGINT)
search_index_version.embedding_model_id BIGINT NULL FK
```

- 迁移中预置全部 `model_assignment` 行（`model_id` 为 NULL），以及 `model_config_version` 的唯一一行。
- 标签单独建表，不用 JSON 列，便于按标签过滤和做约束。
- 当模型带 `embedding` 标签时 `dimensions` 必填（取值 64–2048），由应用层校验。
- 调用参数为 NULL 时取 `ModelPurpose` 枚举里的代码默认值，即现在硬编码的取值。

### D2 用途枚举与约束

`ModelPurpose` 定义了每个用途的必需标签、调用方式（同步或流式）、默认参数和附加能力要求：

| purpose | 标签 | 调用方式 | 默认参数 | 附加要求 |
| --- | --- | --- | --- | --- |
| AGENT_PLANNER | chat | 同步 | temperature 0.0 | `tool-mode=native` 时要求 supports_tools |
| RETRIEVAL_PLANNER | chat | 同步 | temperature 0.0 | 同上 |
| ROUTER / REWRITE / DIRECT_ANSWER / QUALITY_ANALYZER | chat | 同步 | temperature 0.0 | — |
| ANSWER / SELECTION_QUESTION | chat | 流式 | temperature 0.2，thinking 开启 | 开启 thinking 时要求 supports_thinking |
| GRAPH_EXTRACTION / GRAPH_SUMMARY | chat | 同步 | 沿用 `GraphModelClients` 的现有参数 | — |
| VISION_SUMMARY | vision | HTTP | 沿用 `kwiki.vision-model` 的现有默认值 | — |

`QualityV2AnalyzerAdapter` 目前使用流式模型，与 v1 共用 `QUALITY_ANALYZER` 分配：registry 对同一个分配可以同时提供同步客户端和流式客户端。

### D3 ModelRegistry 与快照

- 包路径为 `com.kwiki.infrastructure.ai.registry`，对外接口如下：`chat(purpose)`、`streaming(purpose)`、`vision()`、`embedding(modelId)`、`describe(purpose)`、`status()`。
- `ModelSnapshot` 是不可变对象，通过 `AtomicReference` 发布。适配器在每次调用开始时取一次客户端，调用过程中不再重新获取，这样 reload 不会影响进行中的请求。
- 客户端缓存的 key 是指纹 SHA-256(protocol, baseUrl, apiKey, timeout, retries, modelName, temperature, maxTokens, thinking)。重建快照时只新建指纹发生变化的客户端。
- 每个 provider 一个 `RateLimiter`（复用 `graph.model.RateLimiter`），按 provider id 与限流参数缓存；同一 provider 下的各用途共享。
- 用途未分配、模型或 provider 已停用时，抛出 `ModelNotConfiguredException(purpose)`。问答链路把它映射为现有 `RunFailure` 中的「模型未配置」，图谱构建和图片摘要把任务标为失败并记录原因。

### D4 刷新与多实例

1. 后台的写操作都在一个事务内完成：写业务表，并执行 `UPDATE model_config_version SET version = version + 1`。
2. 事务提交后（`@TransactionalEventListener(AFTER_COMMIT)`），本机执行 `reload()`，然后 `PUBLISH kwiki:model-config <version>`。
3. 其他实例订阅该频道，收到的版本号大于本地版本时执行 reload；同时用 `@Scheduled(fixedDelay = 30s)` 比对数据库中的版本号作为兜底。
4. `reload()` 依次读库、解密、校验、构建，最后原子替换快照；任何一步失败都保留旧快照，并把错误记录到 `status().lastError` 和日志。没有快照时（首次加载失败）启动失败。

### D5 API key 加密

- `ModelSecretCipher` 使用 AES-256-GCM，每次加密生成随机 12 字节 nonce，存储格式为 `v1:` + Base64(nonce ‖ ciphertext ‖ tag)。
- 主密钥来自 `kwiki.model-config.master-key`（Base64 编码的 32 字节），通过 `@Value` 读取，不写入 `application.yml`。只有存在 provider 数据或需要导入时才强制要求该配置。
- `api_key_hint` 取明文的前 3 位和后 4 位，中间用 `****` 连接；明文少于 10 位时只保留 `****` 和后 2 位。
- 管理接口只接收明文，从不返回明文；PUT 请求中 `apiKey` 为空或缺省表示保持原值。

### D6 Embedding 与索引版本

- `EditableIndexConfig` 新增 `embeddingModelId`。新建版本时必须提供，服务端根据模型目录填好 `embeddingProvider`（取 provider 名）、`embeddingModel` 和 `embeddingDimensions` 这几个快照字段，客户端不能自行指定这些值。
- `VersionedIndexingPipelineRegistry` 改为：版本带有 `embedding_model_id` 时，从 `registry.embedding(id)` 获取客户端，并校验模型的当前维度与版本固化的维度一致；不一致或模型已停用时，该版本标记为 `pipeline_supported = 0`，原因写入 `needs_attention_reason`。
- 修改 provider 的 key 或 baseUrl 会热更新到 embedding 客户端；修改 embedding 模型的维度会被拒绝（返回 409），只要该模型仍被任何未删除的索引版本引用。
- 隐式 manifest（`implicitManifest`）改为从带 `embedding` 标签的模型目录中生成。

### D7 首次导入

- `ModelConfigBootstrapImporter` 在 `ApplicationReadyEvent` 之前、registry 首次加载之前运行，用 `GET_LOCK('kwiki_model_config_import', 30)` 实现多实例互斥，拿到锁后再检查一次 `model_provider` 是否为空。
- provider 去重的 key 是规范化后的 `(baseUrl, apiKey)`，导入的 provider 名形如「answer-llm」「graph-extraction」，可在导入后重命名。
- 用途映射：9 个问答用途分配给 answer-llm 模型；`GRAPH_*` 有独立配置时用独立配置，没有时用 answer-llm 模型；`VISION_SUMMARY` 只在 vision 已配置时分配。
- 索引版本回填：按 `(embedding_provider 对应的 profile → baseUrl/key, embedding_model, embedding_dimensions)` 匹配导入后的模型；匹配不上的版本保持 `embedding_model_id` 为 NULL，并标记为不受支持。
- 导入后 `ExternalServicesProperties` 中的 `answerLlm`、`qwenEmbedding`、`visionModel` 以及 `GraphModelProperties` 都改为可选；不再有其他读取它们的路径。

### D8 管理 API（`/api/v1/admin/models`）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET/POST | `/providers` | 列表 / 新建 |
| PUT/DELETE | `/providers/{id}` | 修改 / 删除（仍被引用时返回 409，并列出引用方） |
| POST | `/providers/test` | 用未保存的表单值测试；`providerId` 加空 key 表示使用已保存的 key |
| POST | `/providers/{id}/discover` | 调用 provider 的 `/models`，返回尚未登记的模型名 |
| GET/POST | `/providers/{id}/models` | 列表 / 新建（支持批量） |
| PUT/DELETE | `/models/{id}` | 修改 / 删除（仍被引用时返回 409） |
| POST | `/models/{id}/test` | 按标签做最小调用；embedding 会校验返回维度 |
| GET/PUT | `/assignments` | 读取 / 批量保存，校验标签与能力 |
| GET | `/status` | 快照版本、加载时间、最近一次错误 |

停用 provider 或模型的效果等同于删除时的引用检查：仍被用途分配或活跃索引版本引用时返回 409。

### D9 管理端页面

- 路由为 `/models`，在 `AdminLayout` 的菜单中新增「模型配置」。
- 「Provider 与模型」tab：左侧是 provider 列表，右侧是选中 provider 下的模型表格（标签 chips、维度、tools/thinking、启停、测试），提供「从 provider 拉取模型」的勾选对话框。key 输入框显示 hint，留空表示不修改。
- 「用途分配」tab：每个用途一行，包括说明、按 provider 分组且按标签过滤的模型下拉框、参数输入框（placeholder 显示代码默认值）。保存时整体提交，成功后提示「已生效（版本 N）」。
- `GrayReleaseCreateDialog` 等新建索引版本的入口：embedding 下拉框只显示带 `embedding` 标签的模型，维度只读显示。

## Risks / Trade-offs

- **主密钥丢失后所有 key 都无法解密** → 启动和 reload 时给出明确报错；运维文档要求主密钥与数据库备份分开保存。
- **广播丢失导致实例间短暂不一致** → 30 秒的版本号轮询兜底，最坏情况下有 30 秒窗口。
- **参数配错导致问答整体失败** → 保存前做标签和能力校验，并提供「测试」按钮；reload 失败时保留旧快照。但「参数合法、模型效果差」这类问题需要靠人工回滚。
- **删除了两个 `ChatModel` Bean** → 现有测试中按类型注入的地方需要全部改为注入 fake registry。

## Migration Plan

1. 部署前在 Nacos 中添加 `kwiki.model-config.master-key`。
2. 发布新版本：Flyway V40 建表，首次启动时自动导入并回填。
3. 在后台检查导入结果，按需调整各用途的模型。
4. 观察稳定后，从 Nacos 中删除旧的 `kwiki.answer-llm` / `graph.models` / `vision-model` / `qwen-embedding` / `indexing.embeddings`。
5. 回滚：旧版本不读取新表，回滚到旧版本即可，前提是 Nacos 中的旧配置还没有删除。
