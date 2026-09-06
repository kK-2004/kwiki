## ADDED Requirements

### Requirement: 消费项目构建必须解析共享 SDK

消费方 Maven 构建必须（SHALL）使用 Spring Boot 3.5.6 或更高版本，并且必须（SHALL）从 `https://maven.pkg.github.com/kK-2004/kk-common` 解析准确坐标为 `com.kK-2004:kk-common:0.1.2` 的共享 SDK。构建必须（SHALL）保留现有 content-center 包的解析能力，所有包凭据必须保留在源码库之外。

#### Scenario: 使用已鉴权的 Maven settings 解析公共 SDK

- **WHEN** 开发者或 CI runner 为配置的 GitHub 仓库提供 Maven server 凭据并运行依赖解析构建
- **THEN** Maven 从 `kK-2004/kk-common` 包仓库解析 `com.kK-2004:kk-common:0.1.2`

#### Scenario: GitHub 仓库使用明确的鉴权映射

- **WHEN** POM 同时包含公共 SDK 仓库和现有 content-center 仓库
- **THEN** 每个仓库都有唯一 ID，文档化的 `settings.xml` 包含匹配的 server 条目，且 token 不出现在 POM 或源码库文件中

#### Scenario: 集成前拒绝不支持的框架基线

- **WHEN** 检查或评估消费方 POM 的 SDK 集成配置
- **THEN** Spring Boot parent 版本至少为 `3.5.6`

### Requirement: 功能开关必须控制 SDK 自动配置

消费方必须（SHALL）在 `kk.common` 下配置 SDK 自动装配。省略配置时，`web` 和 `exception` 必须（SHALL）默认启用；`redis` 和 `redisson` 必须（SHALL）默认关闭，只有显式设置 `enabled: true` 才能启用。

#### Scenario: 省略开关时使用安全默认值

- **WHEN** 应用省略全部四个 `kk.common.*.enabled` 属性
- **THEN** 共享 Web 和异常能力启用，Redis 和 Redisson 能力关闭

#### Scenario: 显式功能开关得到遵守

- **WHEN** 应用设置 `kk.common.web.enabled`、`kk.common.exception.enabled`、`kk.common.redis.enabled` 或 `kk.common.redisson.enabled`
- **THEN** 对应的自动配置遵循声明的布尔值，不因 classpath 中存在依赖而被强制覆盖

#### Scenario: Redisson 关闭时不连接 localhost

- **WHEN** `kk.common.redisson.enabled=false` 且未配置显式 Redisson 连接
- **THEN** 不创建 `RedissonClient`，应用启动也不向默认的 `localhost:6379` 端点发起连接

#### Scenario: 单独启用 Redis 时不会隐式启用 Redisson

- **WHEN** `kk.common.redis.enabled=true` 且 `kk.common.redisson.enabled=false`
- **THEN** Redis 工具可以被配置和测试，但不会创建 Redisson 客户端或分布式锁实现

### Requirement: HTTP 边界必须使用共享响应和异常契约

适用的消费方接口必须（SHALL）通过 SDK 的 `TransDTO.success(data)` 契约返回成功结果，并且必须（SHALL）对由共享异常自动配置处理的业务失败抛出 SDK 的 `BusinessException`。消费方不得（SHALL NOT）为同一共享异常路径注册重复处理器。

#### Scenario: 成功接口统一包装响应

- **WHEN** 适用接口带着一个数据对象成功完成
- **THEN** 响应使用 `kk-common` 定义的 `TransDTO.success(data)` 包装，并由契约测试验证序列化后的格式

#### Scenario: 业务失败使用共享异常路径

- **WHEN** 适用服务抛出 `BusinessException`
- **THEN** 共享异常自动配置生成文档化的安全错误响应，其中不包含堆栈信息或 provider 凭据

#### Scenario: 既有授权语义保持可区分

- **WHEN** 请求访问不存在/不可见资源、缺少权限或遇到乐观锁冲突
- **THEN** 消费方保留预期的 not-found、forbidden 和 conflict 语义，并在需要时应用共享响应包装

### Requirement: Redis 缓存穿透必须使用共享安全实现

消费方缓存穿透代码必须（SHALL）使用 SDK 的 `RedisUtil` 抽象，包括使用 `queryWithPassThrough`、类型化值、明确的 key 前缀和明确的过期时间。消费方不得（SHALL NOT）增加第二套序列化或缓存失效策略。

#### Scenario: 缓存未命中时加载并保存类型化值

- **WHEN** `RedisUtil.queryWithPassThrough` 收到未命中的 key，且加载器返回一个值
- **THEN** 方法返回类型化值，并使用配置的过期时间和消费方命名空间 key 保存该值

#### Scenario: 缓存失败时安全降级

- **WHEN** Redis 不可用、缓存值格式错误或缓存写入失败
- **THEN** 消费方将缓存操作视为未命中或尽力写入失败，既不扩大授权范围，也不让请求正确性依赖 Redis

#### Scenario: 前缀删除避免危险的 key 扫描

- **WHEN** 消费方按前缀失效一组由 SDK 管理的缓存 key
- **THEN** 操作使用基于 SCAN 的迭代，绝不执行 Redis `KEYS *` 命令

#### Scenario: 序列化保持安全且一致

- **WHEN** SDK 序列化对象或 List 缓存值
- **THEN** 使用共享的安全 JSON 行为（在 SDK 提供时包括 `GenericJackson2JsonRedisSerializer`），不启用 `enableDefaultTyping(NON_FINAL)`，并将 List 缓存表示为 String + JSON，而不是在 Redis List 中写入空值哨兵

### Requirement: 分布式锁只能使用 Redisson 抽象

任何需要分布式锁的消费方临界区必须（SHALL）从 SDK 的分布式锁工厂获取 `DistributedLock`，使用有界 `tryLock`，成功获取后在 `finally` 中解锁，并在中断时恢复中断标记。消费方不得（SHALL NOT）增加手写 SETNX 锁或其他旧版锁实现。

#### Scenario: 获取锁后保护并释放临界区

- **WHEN** `tryLock` 成功
- **THEN** 业务操作在受保护区域内执行一次，并且即使业务操作抛异常也会在 `finally` 中调用 `unlock`

#### Scenario: 获取锁失败时跳过临界区

- **WHEN** `tryLock` 返回 false
- **THEN** 不执行业务操作，调用方按照文档化的忙碌/降级路径处理，并且不解锁未获取的锁

#### Scenario: 获取锁被中断时保留取消信号

- **WHEN** `tryLock` 被中断
- **THEN** 调用方恢复线程中断状态，并通过文档化的取消路径退出

#### Scenario: 开关关闭时不存在锁能力

- **WHEN** `kk.common.redisson.enabled=false`
- **THEN** 不创建 `RedissonClient`、分布式锁工厂，也不建立隐式 localhost 连接

### Requirement: 集成必须遵循 Spring Boot 3 自动配置约定

消费方必须（SHALL）依赖 SDK 通过 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 注册的标准 Spring Boot 3 自动配置，并且不得（SHALL NOT）为 SDK 增加旧版 `spring.factories` 桥接或手动 bootstrap 导入。

#### Scenario: Spring Boot 3 上下文发现自动配置

- **WHEN** 消费方将 `kk-common:0.1.2` 放入 classpath，并启用相关功能开关后启动
- **THEN** 通过标准 `AutoConfiguration.imports` 机制发现受支持的 SDK 自动配置

#### Scenario: 不需要手动或旧版注册

- **WHEN** 检查消费方 POM 和资源文件
- **THEN** 消费方没有重复或绕过 SDK 标准自动配置入口的旧版注册代码

### Requirement: 消费方文档和验证必须覆盖私有包配置

本变更必须（SHALL）文档化 Maven 仓库/依赖片段、`kk.common` 开关示例、私有包 `settings.xml` 要求，以及 `TransDTO`、`BusinessException`、`RedisUtil` 和 `DistributedLock` 的代表性用法。自动化验证必须（SHALL）覆盖默认功能开关，并且不得依赖真实包凭据或在线 Redis/Redisson 服务。

#### Scenario: 文档说明私有包访问方式

- **WHEN** 开发者按照消费方文档配置私有包
- **THEN** 文档说明 Maven token 至少需要 `read:packages` 权限，列出匹配的 server ID，并且不要求开发者提交 token

#### Scenario: 文档说明 opt-in 行为

- **WHEN** 开发者只使用公共 SDK 默认值，或设置 `kk.common.redisson.enabled=false`
- **THEN** 文档说明 Web/异常保持启用，Redis/Redisson 只有显式开启才启用，且 Redisson 关闭时不会连接 localhost

#### Scenario: 默认验证不依赖外部服务

- **WHEN** 在没有 GitHub 凭据和 Redis/Redisson 的干净环境中运行普通 Maven 测试/验证命令
- **THEN** 测试使用 fake/mock 验证消费方契约，不要求外部中间件，也不泄露凭据
