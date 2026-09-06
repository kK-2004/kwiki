## Context（背景）

`kwiki` 是一个当前基于 Spring Boot 3.4.6 的 Spring Boot Maven 消费项目。它的 `pom.xml` 已经从 GitHub Packages 解析私有 content-center SDK，但响应处理、异常转换、Redis 缓存行为以及未来的加锁约定仍由应用自行维护。共享的 `kk-common` 包面向 Spring Boot 3.5.6+ 提供这些横切能力。

本次变更必须兼容现有 content-center 仓库，不能将 GitHub 凭据提交到源码库，也不能让默认测试或本地启动尝试连接 `localhost:6379`。已发布 SDK 是 Java 包名和 API 签名的唯一依据；实施时必须检查已解析的 `0.1.2` 工件，不能凭猜测编写 import。

## Goals / Non-Goals（目标 / 非目标）

**Goals（目标）：**

- 将消费项目迁移到兼容 Spring Boot 3.5.6 的基线，并从 GitHub Packages 解析 `com.kK-2004:kk-common:0.1.2`。
- 让 SDK 的 `web` 和 `exception` 自动配置默认启用，同时要求 `redis` 和 `redisson` 显式 opt-in。
- 确保 Redis/Redisson 功能关闭时不会创建回退到 `localhost:6379` 的客户端。
- 在适用的应用边界使用共享的 `TransDTO`、`BusinessException`、`RedisUtil`、`DistributedLock` 和分布式锁工厂契约。
- 删除重复的本地横切实现，但保留时区序列化、授权范围语义等应用特有行为。
- 提供可复现的 Maven 鉴权指引，并为依赖解析、开关、API 契约以及 Redis/锁安全行为增加测试。

**Non-Goals（非目标）：**

- 修改 `kk-common` SDK 或发布新的 SDK 版本。
- 重设计业务领域 API、持久化结构、认证机制或 content-center 集成。
- 仅因为依赖存在就默认启用 Redis 或 Redisson。
- 将 GitHub token、Maven `settings.xml` 或环境相关的包凭据保存到本仓库。
- 在 `kwiki` 中重写 SDK 内部实现；消费项目只将应用特有调用点适配到共享契约。

## Decisions（技术决策）

### 1. 将公共包作为普通 Maven 依赖

声明值为 `0.1.2` 的可见 `kk-common` 版本属性，增加要求的 GitHub Packages URL，并在根目录 `pom.xml` 中依赖 `com.kK-2004:kk-common`。构建必须保留现有 kFile/content-center 仓库。由于当前 POM 已将 `github` 用作 kFile 的仓库 ID，公共 SDK 仓库继续按要求使用 `github`，现有 kFile 条目改为唯一 ID（例如 `github-kfile`）；Maven `settings.xml` 提供与之匹配的 server 条目，并可使用同一个 token。

这样可以通过 Maven 标准的 server ID 查找机制定位仓库凭据，避免重复仓库 ID 导致鉴权不明确。用户或 CI runner 的 `settings.xml` 可以通过 `${env.GITHUB_TOKEN}` 让两个 server 条目引用同一个 token。仓库文档需要说明：私有包至少需要 GitHub `read:packages` 权限。

### 2. 在使用自动配置前升级 Boot 基线

先将 Spring Boot parent 从 3.4.6 升级到 3.5.6（或实施时选择的更高 3.5.x 补丁版本），然后运行现有单元测试和上下文测试，再迁移调用点。这样可以将框架兼容性问题与 SDK 迁移问题分开，并确保消费项目满足 SDK 声明的基线。

该依赖保持为普通 compile 依赖。不增加 `bootstrap.yml`、自定义 classpath 扫描或手动导入自动配置。SDK 应通过 Spring Boot 3 的 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 机制激活受支持的配置。

### 3. 将四个功能开关作为集成边界

在 `kk.common` 下配置以下属性：

```yaml
kk:
  common:
    web:
      enabled: true
    exception:
      enabled: true
    redis:
      enabled: false
    redisson:
      enabled: false
```

省略配置时，`web` 和 `exception` 仍默认启用。`redis` 和 `redisson` 明确默认为 false；使用 scope cache 或分布式锁的环境必须显式 opt-in，并提供完整的连接/配置值。测试既要覆盖省略属性，也要覆盖显式 false。尤其是仅设置 `kk.common.redisson.enabled=false` 的上下文，不能包含 `RedissonClient`，也不能向默认 Redis 端点建立 socket 连接。

Redisson 被视为独立能力。启用它必须提供明确的 Redisson 配置并由部署环境主动决定；实现不能因为 classpath 中存在依赖就推断连接，也不能静默使用 localhost。如果 SDK 要求 Redis 开关作为 Redisson 前置条件，应在消费方校验和文档中明确表达，不能隐藏在应用代码里。

### 4. 将 API 边界迁移到共享响应和异常契约

属于本次公共集成范围的控制器成功结果统一返回 `TransDTO.success(data)`。应表示为业务错误的领域失败统一抛出 `BusinessException`，并使用 SDK 支持的安全 message/code。对应错误响应由共享异常自动配置单独负责。

现有 `ApiControllerAdvice` 应删除，或收敛为真正属于应用自身且不会与 SDK 冲突的处理器。既有的 not-found、forbidden 和 optimistic-conflict 语义必须继续可区分；如果共享包装导致响应格式变化，控制器契约测试要记录这一有意的破坏性变更。任何处理器都不得泄露安全敏感详情和堆栈信息。

### 5. 将缓存和锁行为委托给 SDK

需要缓存穿透保护的应用服务使用 SDK 的 `RedisUtil.queryWithPassThrough`，传入应用 key 前缀、类型化的值、加载器和明确 TTL。现有 `ScopeCache` 包含应用特有行为，因此如果 SDK API 无法表达授权 scope 失效逻辑，可以在 SDK 工具之上保留一个薄适配器；但不能再引入第二套 JSON 策略。

需要分布式锁的业务操作从 SDK 的分布式锁工厂获取 `DistributedLock`，以有界等待调用 `tryLock`，只有成功获取后才执行临界区，并在 `finally` 中解锁。捕获 `InterruptedException` 时要恢复线程中断标记。不得新增手写 SETNX 锁或旧版锁实现。

集成必须保持 SDK 的安全不变量：使用 `GenericJackson2JsonRedisSerializer`，不使用不安全的 default typing；按 SCAN 删除前缀，不使用 `KEYS *`；List 缓存使用 String + JSON；只保留一套共享 `JsonUtils` 行为；分布式锁只使用 Redisson 抽象。

### 6. 在构建、上下文和契约三个层面验证集成

测试分层如下：

1. Maven/POM 检查确认准确的仓库 URL、依赖坐标/版本、Boot 基线以及没有凭据。
2. 轻量 Spring 上下文测试验证默认和显式功能开关、Bean 的有无，以及 Redis/Redisson 关闭时不产生外部连接尝试。
3. Web 契约测试验证代表性接口的共享成功/错误包装，并确保本地重复异常处理器未启用。
4. Redis/锁测试使用 fake 或 mock 验证加载器、TTL、失效、锁获取、异常时解锁和中断行为，不要求真实 Redis。
5. 文档化的 opt-in 包解析检查使用开发者/CI 的 `settings.xml`；默认测试套件不依赖真实 GitHub 和 Redis 凭据。

## Risks / Trade-offs（风险 / 权衡）

- [现有 kFile 包和新的 common 包可能需要不同的 Maven server ID] → 保持仓库 ID 唯一，文档化对应的 server 条目，并在适用时使用同一个环境变量 token。
- [Spring Boot 3.5.6 可能暴露无关的兼容性变更] → 先完成升级并运行迁移前测试，将仅属于框架升级的修复单独记录在实施任务中。
- [共享 SDK 的精确包名或 DTO 字段可能与示例不同] → 检查已解析的 JAR，并在修改应用调用点前编译最小契约测试。
- [修改响应包装可能破坏现有客户端] → 将受影响接口标记为破坏性契约，增加 golden JSON 测试，并在发布前记录迁移格式。
- [Redis 默认关闭可能使 scope cache 有意变成 no-op] → 在需要缓存的环境显式设置 opt-in，并验证缓存未命中时正确性仍来自数据库。
- [共享异常自动配置可能与本地处理器冲突] → 删除重复处理器，并在上下文和 HTTP 测试中断言每个异常类型只有一个负责人。
- [贡献者环境可能无法解析私有包] → 凭据保持在仓库外，文档提供 settings 模板/示例，并让失败提示指向 `read:packages` 和正确的 server ID。

## Migration Plan（迁移计划）

1. 检查已发布的 `kk-common:0.1.2` JAR，确认自动配置和 API 类型；更新 POM、唯一仓库 ID、Boot parent 和依赖。
2. 增加四个 `kk.common.*.enabled` 属性，并在接入应用代码前先用 Redis/Redisson 关闭的默认配置测试它们。
3. 迁移代表性响应和业务异常路径，删除冲突的本地异常处理，并记录新的 HTTP 契约。
4. 将缓存穿透和需要加锁的业务操作适配到 SDK 抽象，同时保留应用特有的 scope 失效和授权行为。
5. 删除未使用的重复工具/配置，更新测试夹具和文档，然后运行完整默认验证套件以及一次带鉴权的包解析检查。
6. 按环境明确设置 Redis/Redisson，保持 `web`/`exception` 开启。如果需要回滚，以协调发布的方式回退依赖/配置并恢复原有本地适配器；回滚过程中不得提交凭据。

## Open Questions（待确认事项）

- `kk-common:0.1.2` 为 `TransDTO`、`BusinessException`、`RedisUtil`、`DistributedLock` 和工厂暴露的准确包名及方法签名是什么？这是针对已发布工件的非阻塞实施检查。
- 当前哪些 HTTP 接口有外部客户端，可能需要为新的 `TransDTO` 包装增加兼容桥接？实施时可盘点调用方，并在任务验证记录中列出最终迁移范围。
- 已发布 SDK 是否要求 `kk.common.redis.enabled=true` 作为 Redisson 前置条件，还是允许两个开关独立启用？最终验证应以 SDK 的条件注解为准，并在文档中记录结论。
