# kk-common 0.1.2 已发布工件检查记录（任务 1.1）

来源：`~/.m2/repository/com/kK-2004/kk-common/0.1.2/kk-common-0.1.2.jar`（SHA1 与本地 POM 一致；POM 声明 Spring Boot 3.5.6、JDK 17、Redisson 3.37.0、TTL 2.14.5）。

## 自动配置（`META-INF/spring/...AutoConfiguration.imports`）

| 类 | 条件 | Beans |
| --- | --- | --- |
| `com.kk2004.common.autoconfigure.CommonBoot3AutoConfiguration` | `@ConditionalOnWebApplication(SERVLET)` | `GlobalExceptionHandler`（`kk.common.exception.enabled`，matchIfMissing=true）、`RequestContextFilter` + `RequestContextTaskDecorator`（`kk.common.web.enabled`，matchIfMissing=true）；均为 `@ConditionalOnMissingBean` |
| `com.kk2004.common.autoconfigure.RedisToolkitAutoConfiguration` | `@ConditionalOnClass(RedisTemplate, RedisConnectionFactory)` + `@ConditionalOnProperty(prefix="kk.common.redis", name="enabled", havingValue="true")`（默认关闭） | `kkRedisTemplate`（String key + `GenericJackson2JsonRedisSerializer` value，`@ConditionalOnBean(RedisConnectionFactory)`）、`redisUtil`（`@ConditionalOnBean(name="kkRedisTemplate")`，`@ConditionalOnMissingBean`） |
| `com.kk2004.common.autoconfigure.RedissonLockAutoConfiguration` | `@ConditionalOnClass(RedissonClient)` + `@ConditionalOnProperty(prefix="kk.common.redisson", name="enabled", havingValue="true")`（默认关闭）+ `@EnableConfigurationProperties(RedisProperties)` | `redissonClient`（由 Spring Boot `RedisProperties` 构建 `redis://host:port` 单机配置，`@ConditionalOnMissingBean`）、`DistributedLockFactory`（`RedissonDistributedLockFactory`，`@ConditionalOnBean(RedissonClient)`） |

结论（对应 Open Question 3）：Redis 与 Redisson 开关互相独立；Redisson 不要求 `kk.common.redis.enabled=true`，但复用 `spring.data.redis.*` 的 host/port 配置。

## 响应契约

- `com.kk2004.common.response.TransDTO<T>`：字段 `code:int`、`success:boolean`、`message:String`、`data:T`；静态工厂 `success(T)`、`success()`、`failure(int,String)`；链式 `withCode/withSuccess/withMessage/withData`。
- `com.kk2004.common.response.PageResponse<T>.of(long pageNum, long pageSize, long total, List<T> records)`。

## 异常契约

- `BusinessException extends BaseBusinessException`：构造 `(ErrorCode)`、`(int code, String message)`、`(String message)`。`BaseBusinessException` 携带 `code`/`userMessage`，覆写 `fillInStackTrace()` 返回自身（无堆栈填充开销）。
- 预置异常：`NotFoundException()`/`NotFoundException(String)`、`UnauthorizedException()`、`SystemException(String)`/`(String, Throwable)`（`SystemException` 不是 `BaseBusinessException` 子类）。
- `ErrorCode` 接口：`getCode()`、`getMessage()`；`CommonErrorCode` 枚举：`SUCCESS`、`BAD_REQUEST`、`UNAUTHORIZED`、`FORBIDDEN`、`NOT_FOUND`、`VALIDATION_FAILED`、`SYSTEM_ERROR`。
- `GlobalExceptionHandler`（@RestControllerAdvice 风格，由 SDK 自动注册）：处理 `BaseBusinessException`、`SystemException`、`MethodArgumentNotValidException`、`ConstraintViolationException`、`HttpMessageNotReadableException`、兜底 `Exception`，统一返回 `TransDTO<String>`；`isNonProd()` 决定是否暴露细节，生产环境不泄露堆栈。

## Redis 契约

`com.kk2004.common.redis.RedisUtil`（常量 `EMPTY_VALUE`、`EMPTY_LIST_VALUE`、`CACHE_NULL_TTL_SECONDS`）：

- `boolean set(String key, Object value, long seconds)` / `set(String, Object, Duration)`
- `Object get(String key)`、`boolean expire(String, long)`、`boolean hasKey(String)`、`void del(String...)`
- `Long deleteByPrefix(String prefix)` — SCAN 游标分批删除（`scanKeys`/`deleteBatch` 私有实现），不用 `KEYS`
- `List<Object> multiSet(Map<String,Object>, long ttl)`
- `<R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID,R> loader, Long ttlSeconds)` — 缓存穿透保护，空值写 `EMPTY_VALUE` 短 TTL 防穿透
- `<R,ID> List<R> queryWithPassThroughList(String keyPrefix, ID id, Class<R> elementType, Function<ID,List<R>> loader, Long ttlSeconds)` — List 用 String+JSON 表示（`setListAsJson`/`getListFromJson`/`getListJson`），不写空值哨兵进 Redis List
- `String getKey(String prefix, String suffix)`

## 锁契约

- `com.kk2004.common.lock.DistributedLock`：`tryLock(long wait, long lease, TimeUnit)`、`tryLock(long, TimeUnit)`、`tryLock()`、`lock(long, TimeUnit)`、`unlock()`、`isLocked()`、`isHeldByThread(long)`、`isHeldByCurrentThread()`。
- `com.kk2004.common.lock.DistributedLockFactory`：`DistributedLock getDistributedLock(String name)`。
- 实现 `RedissonDistributedLockFactory(RedissonClient)`。

## 工具与缓存定义

- `JsonUtils`：`objectMapper()`、`toJson`、`fromJson(String,Class)`、`fromJson(String,TypeReference)`、`fromJsonList(String,Class)`。
- `StringUtils`：`isBlank`、`isNotBlank`、`defaultIfBlank`、`joinKey(String,Object)`。
- `RedisKeyDefinition` 接口：`getPrefix()`、`getDesc()`、`getTimeout()`、`getTimeUnit()`；`CacheExpireEnum`：`FIVE_SECONDS…ONE_MONTH`，`getSeconds()`。

## 消费方注意

- Redisson 关闭（默认）时 `RedissonLockAutoConfiguration` 完全不生效，不会创建客户端、不会连 `localhost:6379`；开启时使用 `spring.data.redis.host/port`（默认 host=localhost、port=6379），部署环境必须显式提供连接配置。
- Redis 工具开启的前提是容器里有 `RedisConnectionFactory`（kwiki 已有 `spring-boot-starter-data-redis`，其自动配置按需创建 lazy 连接工厂；无真实连接时工厂创建本身不发起 socket 连接，首次命令才连接）。
