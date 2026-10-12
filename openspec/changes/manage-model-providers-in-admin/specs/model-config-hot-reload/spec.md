## ADDED Requirements

### Requirement: 运行时快照
系统 SHALL 通过 `ModelRegistry` 持有不可变的模型快照，适配器 SHALL 在每次调用开始时从 registry 获取客户端；快照替换 MUST NOT 中断进行中的调用。指纹相同的客户端 SHALL 在多次快照之间复用。

#### Scenario: 回答流式输出期间切换模型
- **WHEN** `ANSWER` 的流式调用进行中，管理员把 `ANSWER` 改为另一个模型
- **THEN** 进行中的流使用原模型完成，之后的新请求使用新模型

### Requirement: 多实例刷新
任何配置写入 SHALL 在同一事务中递增 `model_config_version`；提交后本机 SHALL 立即 reload 并通过 Redis 广播新版本号；其他实例收到更高版本号时 SHALL reload，并且 SHALL 至少每 30 秒比对一次数据库中的版本号作为兜底。

#### Scenario: 广播消息丢失
- **WHEN** 实例 B 没有收到版本 7 的广播
- **THEN** 实例 B 在 30 秒内通过轮询发现版本 7 并完成 reload

### Requirement: reload 失败保护与状态查询
reload 中解密、校验或客户端构建任一步失败时，系统 SHALL 保留旧快照并记录错误；`GET /api/v1/admin/models/status` SHALL 返回当前快照版本、加载时间与最近一次错误。

#### Scenario: 首次加载失败
- **WHEN** 启动时目录无法加载（如数据库中的密文无法解密）
- **THEN** 应用照常启动，各用途表现为未配置，`status` 中显示加载错误

#### Scenario: 主密钥被改错
- **WHEN** 运行中把主密钥改成错误值，并触发 reload
- **THEN** 服务继续使用旧快照，`status` 中显示解密失败
