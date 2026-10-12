## ADDED Requirements

### Requirement: 首次启动导入现有配置
当 `model_provider` 为空且存在旧配置时，系统 SHALL 在 registry 首次加载前、在锁住 `model_config_version` 行（`SELECT ... FOR UPDATE`）的同一事务内，把 `kwiki.answer-llm`、`kwiki.graph.models.*`、`kwiki.vision-model`、`kwiki.qwen-embedding`、`kwiki.indexing.embeddings.*` 导入为 provider、模型与用途分配，按 `(baseUrl, apiKey)` 去重 provider，并保持与现有行为一致的模型与参数。导入 SHALL 是幂等的。

#### Scenario: 两个实例同时首次启动
- **WHEN** 两个实例同时以空目录启动
- **THEN** 只有一个实例执行导入，另一个等到前者提交后发现目录非空并跳过

#### Scenario: 图谱沿用问答模型
- **WHEN** 旧配置中只有 `kwiki.answer-llm`，没有 `kwiki.graph.models.*`
- **THEN** `GRAPH_EXTRACTION` 与 `GRAPH_SUMMARY` 分配给从 answer-llm 导入的模型

### Requirement: 已有索引版本无需回填
导入 embedding 配置时，系统 SHALL 以 profile 名作为 provider 名（`kwiki.qwen-embedding` 对应 `default`），使已有 `search_index_version` 的 `(embedding_provider, embedding_model)` 直接对应到目录中的模型。

#### Scenario: 默认 profile 的索引版本
- **WHEN** 已有版本的 `embedding_provider=default`、`embedding_model=text-embedding-v4`、维度为 1024，且 `kwiki.qwen-embedding` 与之一致
- **THEN** 导入后该版本解析到 provider `default` 下的 `text-embedding-v4`，并继续正常服务
