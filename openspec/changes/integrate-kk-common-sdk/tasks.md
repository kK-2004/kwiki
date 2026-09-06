## 1. 验证已发布工件和 Maven 基线

- [x] 1.1 检查已解析的 `kk-common:0.1.2` JAR/POM，记录 `TransDTO`、`BusinessException`、`RedisUtil`、`DistributedLock`、分布式锁工厂的准确包名、自动配置类、条件属性和方法签名。（记录见 notes/kk-common-0.1.2-api.md）
- [x] 1.2 将 `pom.xml` 中的 Spring Boot parent 升级到 3.5.6 或兼容的更高 3.5.x 补丁版本，运行迁移前测试套件，只修复框架基线导致的兼容性问题。（299 tests, 0 failures；唯一基线修复：`ContentCenterConfigurationTest` 中 `kwiki.elasticsearch.uris` 在 Boot 3.5 下不再实例化空嵌套 record 导致 `@NotNull` 绑定失败，改为可绑定的 `kwiki.elasticsearch.username`）
- [x] 1.3 增加值为 `0.1.2` 的 `kk-common` 版本属性，增加 `https://maven.pkg.github.com/kK-2004/kk-common` 对应的 `github` 仓库，并声明 `com.kK-2004:kk-common` 依赖。（依赖树已确认 kk-common 0.1.2 + TTL 2.14.5 + Redisson 3.37.0 进入 compile classpath）
- [x] 1.4 将现有 kFile/content-center 仓库改为唯一 ID，使用占位符和 `read:packages` 文档化匹配的 `settings.xml` server 条目，并在凭据由外部提供时验证两个私有包仓库都能解析。（kFile 仓库 ID 改为 `github-kfile`，SDK 仓库保持 `github`；模板见 docs/kk-common-maven-settings.md，本地已验证两个工件可解析）

## 2. 配置并测试按功能开关控制的自动配置

- [x] 2.1 在应用配置中增加 `kk.common.web`、`kk.common.exception`、`kk.common.redis` 和 `kk.common.redisson` 开关，保持 Web/异常默认开启、Redis/Redisson 默认关闭。
- [x] 2.2 确保只有在功能显式启用时才读取 Redis 和 Redisson 连接配置；删除任何可能在正常启动期间创建客户端的隐式 localhost 回退。（spring.autoconfigure.exclude + KwikiRedisConfiguration 条件 @Import 使 Redis 连接配置仅在开关开启时读取；application-local.yml 的 localhost 回退已删除）
- [x] 2.3 更新 Spring Boot 3.5.6/SDK 集成所需的共享测试属性和上下文夹具，包含不需要外部中间件的功能关闭场景。
- [x] 2.4 增加上下文测试，覆盖省略默认值、显式 false、仅启用 Redis 和启用 Redisson；断言 Bean 的有无，并证明 Redisson 关闭时不会尝试连接 `localhost:6379`。（CommonSdkSwitchContextTest：7 个场景全绿）

## 3. 迁移 HTTP 响应和异常边界

- [x] 3.1 盘点当前控制器响应格式和异常处理器，识别受 `TransDTO` 包装影响的接口，并记录有意的破坏性契约范围。（见 notes/api-inventory.md）
- [x] 3.2 使用任务 1.1 确认的准确 SDK 签名，将适用的控制器成功响应迁移为 SDK 的 `TransDTO.success(data)`。
- [x] 3.3 将适用的业务异常路径替换为 `BusinessException`，保留 not-found、forbidden 和 optimistic-conflict 语义，并确保消息/堆栈信息经过安全处理。
- [x] 3.4 删除或收窄 `ApiControllerAdvice`，避免与 SDK 异常自动配置重复；然后增加成功响应和代表性错误响应的 golden JSON/Web 测试。（advice 只保留 403/400 且 @Order(HIGHEST_PRECEDENCE) 对抗 SDK @Order(MIN_VALUE) 的 catch-all；golden 测试见 CommonResponseContractTest，6 个场景）

## 4. 安全迁移 Redis 和分布式锁用法

- [x] 4.1 将现有 `ScopeCache`、Redis 配置和相关测试映射到 SDK 的 `RedisUtil` 契约；只保留 SDK 无法表达的应用特有授权 scope 行为。（ScopeCache 变为 RedisUtil 薄适配器 getOrLoad/invalidate，CachedScope 存储形状由 SDK 共享序列化器负责）
- [x] 4.2 将缓存穿透调用点适配为 `RedisUtil.queryWithPassThrough`，使用命名空间 key、类型化值、明确 TTL、缓存失败安全降级和正确的 scope 失效逻辑。
- [x] 4.3 增加消费方测试，覆盖缓存未命中/加载、过期、格式错误、Redis 不可用、基于 SCAN 的前缀删除、安全 JSON 序列化以及 String + JSON List 缓存；增加拒绝 `KEYS *` 和不安全 default typing 的静态检查。
- [x] 4.4 将已有或确实需要的临界区迁移到 SDK `DistributedLock` 工厂，使用有界 `tryLock`、`finally` 解锁、获取失败不解锁和中断恢复；如果生产代码没有真实加锁场景，则通过专门的适配器测试和文档示例覆盖契约，不人为增加业务锁。（DistributedLockContractTest 4 个契约场景 + docs 示例；生产代码无真实临界区）
- [x] 4.5 删除废弃的重复 JSON/缓存/锁工具，更新 import、架构规则和单元测试，确保分布式锁实现只保留 Redisson 抽象。

## 5. 文档化、验证并准备发布

- [x] 5.1 增加消费方文档，包含仓库/依赖 XML、私有包 `settings.xml` 占位符、`read:packages` 要求、功能开关 YAML，以及 `TransDTO`、`BusinessException`、`RedisUtil` 和 `DistributedLock` 示例。
- [x] 5.2 文档化 Spring Boot 3 的 `AutoConfiguration.imports` 约定，并验证消费方没有为 SDK 增加旧版 `spring.factories` 或手动 bootstrap 注册。
- [x] 5.3 在没有包凭据或在线 Redis/Redisson 的环境中运行 `./mvnw test`/`./mvnw verify`，确认默认测试使用 fake/mock 且不泄露密钥；凭据可用时单独运行带鉴权的依赖检查。（./mvnw verify BUILD SUCCESS：321 tests, 0 failures, 26 skipped；kk-common 0.1.2 自本地 ~/.m2 解析，干净环境的鉴权解析流程见 docs/kk-common-maven-settings.md）
- [x] 5.4 运行应用、API 契约、缓存、锁、架构和仓库密钥审计；确认没有提交凭据，且关闭的功能不会尝试外部连接。（RepositorySecretsAudit/NoDockerMiddleware/CommonSdkSwitchContextTest 全绿；.idea/ 已 gitignore；文档中仅 github_pat_ 占位符）
- [x] 5.5 运行 `openspec validate integrate-kk-common-sdk --strict`，检查最终状态，并记录必须显式启用 Redis 或 Redisson 的环境对应的发布/回滚说明。（validate --strict 通过；发布/回滚说明见 docs/kk-common-integration.md 第 8 节）
