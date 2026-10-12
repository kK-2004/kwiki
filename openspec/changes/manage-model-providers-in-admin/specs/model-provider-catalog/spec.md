## ADDED Requirements

### Requirement: Provider 与模型目录
系统 SHALL 在数据库中维护 provider 与模型目录：一个 provider 记录 baseUrl、加密的 API key 与连接参数（timeout、重试、并发、QPS），其下可登记多个模型；系统 SHALL 支持多个 provider，同一 provider 下的模型无需重复配置连接信息。

#### Scenario: 一个 provider 下登记多个模型
- **WHEN** 管理员为 provider「阿里百炼」登记 `qwen-max` 与 `qwen-turbo`
- **THEN** 两个模型共享该 provider 的 baseUrl、key 与连接参数，且 `(provider, model_name)` 不可重复

#### Scenario: 删除或停用仍被引用的模型
- **WHEN** 管理员删除或停用一个仍被用途分配或未删除索引版本引用的模型或 provider
- **THEN** 接口返回 409，并列出全部引用方，数据不变

### Requirement: 模型能力标签
每个模型 SHALL 带有一个或多个能力标签 `chat`、`vision`、`embedding`；带 `embedding` 标签的模型 SHALL 登记 64–2048 之间的维度。

#### Scenario: embedding 模型缺少维度
- **WHEN** 管理员保存一个带 `embedding` 标签但未填维度的模型
- **THEN** 接口返回 400，模型不被保存

### Requirement: API key 加密存储与掩码
系统 SHALL 用 AES-256-GCM 与 `kwiki.model-config.master-key` 加密 API key 后落库，SHALL 只在接口中返回掩码 hint，任何接口响应与日志 MUST NOT 包含明文 key。更新 provider 时 key 留空 SHALL 保持原值。

#### Scenario: 编辑 provider 不改 key
- **WHEN** 管理员修改 provider 的 timeout，`apiKey` 字段为空
- **THEN** 原密文保持不变，响应只包含 `apiKeyHint`

#### Scenario: 密文被篡改
- **WHEN** 数据库中的 `api_key_cipher` 被改动导致 GCM 校验失败
- **THEN** reload 失败并保留旧快照，错误信息不包含任何 key 内容

### Requirement: 连通性测试与模型发现
系统 SHALL 提供 provider 连通性测试（可使用未保存的表单值）、从 provider `/models` 拉取未登记模型的发现接口，以及按标签执行最小调用的模型测试；embedding 测试 SHALL 校验返回维度与登记维度一致。

#### Scenario: embedding 维度不符
- **WHEN** 测试一个登记维度为 1024、实际返回 1536 维向量的模型
- **THEN** 测试结果为失败，并给出两个维度值
