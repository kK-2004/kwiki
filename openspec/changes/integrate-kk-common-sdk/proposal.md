## Why（为什么）

`kwiki` 当前自行维护响应/异常处理和 Redis 相关基础设施，而共享的 `kk-common` SDK 已经提供了这些跨项目能力。重复实现会造成行为分叉，也会让安全敏感的默认配置更容易产生漂移。消费项目需要在 Spring Boot 3.5.6+ 基线上接入已发布的 SDK，并通过明确的功能开关控制：Web 和异常能力默认启用，Redis 和 Redisson 仅在显式配置后启用。

## What Changes（变更内容）

- 将消费项目的 Spring Boot 基线升级到 3.5.6 或更高版本，并验证现有应用和测试配置的兼容性。
- 在 `pom.xml` 中增加 `kK-2004/kk-common` 的 GitHub Packages 仓库，并引入 `com.kK-2004:kk-common:0.1.2`，仓库中不保存凭据。
- 在避免 Maven 仓库 ID 重复的前提下，保留现有 content-center 包仓库的解析能力。
- 文档化私有 GitHub Packages 的 `settings.xml` 鉴权方式，说明 token 至少需要 `read:packages` 权限。
- 通过 `kk.common.web.enabled`、`kk.common.exception.enabled`、`kk.common.redis.enabled` 和 `kk.common.redisson.enabled` 配置 SDK；Web 和异常默认开启，Redis 和 Redisson 默认关闭。
- **BREAKING（破坏性变更）**：将适用的控制器响应和业务异常路径迁移到共享的 `TransDTO` 与 `BusinessException` 契约，并用测试记录新的响应格式。
- 将适用的本地缓存穿透和分布式锁调用点替换为 SDK 的 `RedisUtil` 与 `DistributedLock` 抽象；功能关闭时不创建 Redis/Redisson 客户端。
- 删除或收敛重复的本地公共基础设施，只保留应用特有的适配器和领域行为。
- 增加消费方使用文档和回归测试，覆盖自动装配开关、私有包配置、统一响应、缓存穿透保护和分布式锁。

## Capabilities（能力）

### New Capabilities（新增能力）

- `common-sdk-integration`：在 Spring Boot 消费项目中引入并配置共享 `kk-common` SDK，覆盖 Maven 解析、按功能开关控制的自动装配、统一响应/异常 API、安全 Redis 工具和分布式锁。

### Modified Capabilities（修改能力）

无。当前仓库没有已发布的 capability spec；本变更新增一项公共 SDK 集成能力。

## Impact（影响范围）

- `pom.xml` 中的 Maven 元数据和依赖，包括 Spring Boot parent 版本以及 GitHub Packages 仓库定义。
- `src/main/resources/application*.yml` 中的运行时配置，以及 `kk.common.*` 开关对应的测试属性。
- Web 控制器、异常处理、Redis 缓存服务，以及当前重复实现共享 SDK 行为的后续加锁业务操作。
- 现有本地 Redis/Jackson/公共工具类及其测试；这些内容可能被删除、收敛为适配器，或改为调用 SDK。
- 消费方文档和集成测试；私有 GitHub 凭据只保存在开发者或 CI 的 Maven `settings.xml` 中，绝不进入源码库。
- API 消费方可能需要适配共享 `TransDTO` 响应包装和标准化业务异常表示。
