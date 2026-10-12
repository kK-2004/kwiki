## ADDED Requirements

### Requirement: 索引版本引用目录中的 embedding 模型
新建索引版本时 SHALL 从带 `embedding` 标签且已启用的模型中选择 `embeddingModelId`；服务端 SHALL 根据目录填写 `embedding_provider`、`embedding_model`、`embedding_dimensions` 这几个快照字段，客户端提交的这几个值 SHALL 被忽略。

#### Scenario: 用新 embedding 模型建立索引版本
- **WHEN** 管理员新建索引版本并选择 `text-embedding-v4`（1024 维）
- **THEN** 新版本固化该模型 id 与 1024 维，通过现有的重建、灰度、切换流程生效，活跃版本在切换前保持不变

### Requirement: embedding 客户端随 provider 热更新且维度不可变
索引与查询 SHALL 通过版本固化的 `embedding_model_id` 从 registry 获取客户端；provider 的 key 或 baseUrl 变更 SHALL 热生效；当模型仍被未删除的索引版本引用时，修改其维度 SHALL 被拒绝。模型被停用、或其维度与版本不一致时，该版本 SHALL 被标记为不受支持。

#### Scenario: 轮换 embedding provider 的 key
- **WHEN** 管理员更新活跃索引版本所用 provider 的 API key
- **THEN** 下一次查询向量化使用新 key，无需重建索引
