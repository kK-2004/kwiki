## ADDED Requirements

### Requirement: 索引版本引用目录中的 embedding 模型
索引版本的 `(embedding_provider, embedding_model)` SHALL 引用目录中「provider 名 + 模型名」对应的、带 `embedding` 标签且已启用的模型；新建或编辑版本时找不到对应模型 SHALL 返回 409，`embedding_dimensions` SHALL 由服务端按目录登记的维度改写。

#### Scenario: 用新 embedding 模型建立索引版本
- **WHEN** 管理员新建索引版本并选择 `text-embedding-v4`（1024 维）
- **THEN** 新版本记录该模型的 provider 名、模型名与 1024 维，通过现有的重建、灰度、切换流程生效，活跃版本在切换前保持不变

### Requirement: embedding 客户端随 provider 热更新且维度不可变
索引写入 SHALL 通过版本记录的 provider 名与模型名从 registry 获取客户端，查询 SHALL 使用已发布版本对应的客户端；provider 的 key 或 baseUrl 变更 SHALL 热生效；当模型仍被未删除的索引版本引用时，修改其维度或模型名 SHALL 被拒绝，修改 provider 名 SHALL 在同一事务中同步更新索引版本。模型被停用、或其维度与版本不一致时，该版本 SHALL 被标记为不受支持。

#### Scenario: 轮换 embedding provider 的 key
- **WHEN** 管理员更新活跃索引版本所用 provider 的 API key
- **THEN** 下一次查询向量化使用新 key，无需重建索引
