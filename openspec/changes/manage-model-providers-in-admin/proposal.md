## Why

目前所有模型的连接信息与参数都放在 Nacos：问答链路（planner、意图路由、改写、回答等 9 个用途）共用一组 `kwiki.answer-llm`，图谱、vision、embedding 各有一组独立配置。想给 planner 换一个更便宜或更快的模型，或者调整某个用途的 temperature，都得改 Nacos 配置并重新构建 Bean，后台也看不到、选不了 embedding 模型。需要把 provider、模型与用途分配落库，由后台管理端维护，并且改完能热更新。

## What Changes

- 新增**模型目录**：provider 记录 baseUrl、AES-GCM 加密的 API key 和连接参数，一个 provider 下可以挂多个模型；模型带能力标签 `chat` / `vision` / `embedding`（可多选），embedding 模型登记维度。
- 新增**用途分配**：`AGENT_PLANNER`、`RETRIEVAL_PLANNER`、`ROUTER`、`REWRITE`、`DIRECT_ANSWER`、`ANSWER`、`SELECTION_QUESTION`、`QUALITY_ANALYZER`、`GRAPH_EXTRACTION`、`GRAPH_SUMMARY`、`VISION_SUMMARY` 各自独立指定模型，并可设置 temperature、maxTokens、thinking；服务端校验所选模型的标签和能力是否满足该用途。
- 新增运行时 **`ModelRegistry`**：内存中持有不可变快照，各适配器在每次调用时按用途取客户端；后台保存后本机立即生效，其他实例通过 Redis pub/sub 广播获知，另有 30 秒一次的版本号轮询兜底。reload 失败时保留旧快照。
- **embedding** 不参与全局用途分配，而是在新建索引版本时从带 `embedding` 标签的模型中选择，索引版本固化 `embedding_model_id` 与维度，切换仍走现有的重建 → 灰度 → 切换流程。
- 首次启动且模型目录为空时，把 Nacos 中现有的 `kwiki.answer-llm`、`kwiki.graph.models.*`、`kwiki.vision-model`、`kwiki.qwen-embedding`、`kwiki.indexing.embeddings.*` 导入为目录和分配，并回填已有索引版本的 `embedding_model_id`；导入后上述配置项改为可选。
- 后台管理端新增「模型配置」页（「Provider 与模型」「用途分配」两个 tab），并把索引版本对话框里的 embedding 选择改为从模型目录中选。
- **BREAKING（运维）**：新增必需配置 `kwiki.model-config.master-key`（放在 Nacos 或环境变量中）；删除 `kwikiChatModel` / `kwikiStreamingChatModel` 两个 Bean。

## Capabilities

### New Capabilities

- `model-provider-catalog`: provider 与模型目录、能力标签、API key 加密存储、连通性测试与模型发现。
- `model-purpose-assignment`: 按用途独立分配模型与调用参数，标签/能力校验，未配置时显式失败。
- `model-config-hot-reload`: 运行时快照、客户端复用、多实例刷新、reload 失败保护与状态查询。
- `embedding-model-selection`: 索引版本引用模型目录中的 embedding 模型，查询侧使用活跃版本固化的模型。
- `model-config-bootstrap-import`: 首次启动时从现有 Nacos 配置导入，并回填索引版本。

### Modified Capabilities

无：`openspec/specs/` 为空。本 change 修改了进行中的 `refactor-llm-and-agentic-orchestration` 中「复用 KWIKI_ANSWER_LLM 配置」的条款，归档时需协调。

## Impact

- 后端：`infrastructure/ai`（新增 `registry/`，10 个适配器改为注入 `ModelRegistry`，删除问答模型 Bean）、`graph/model/GraphModelClients`、`indexing/multimodal/MultimodalConfiguration`、`indexing/pipeline/VersionedIndexingPipelineRegistry`、`indexing/version/EditableIndexConfig`、`infrastructure/config/ExternalServicesProperties`；新增 `/api/v1/admin/models/*` 控制器。
- 数据库：新增一个 Flyway 版本（`model_provider`、`model_definition`、`model_definition_tag`、`model_assignment`、`model_config_version`，以及 `search_index_version.embedding_model_id`）。
- 前端：`admin-frontend` 新增「模型配置」视图与路由，修改索引版本创建对话框。
- 中间件：复用现有 MySQL 与 Redis，不新增依赖。
