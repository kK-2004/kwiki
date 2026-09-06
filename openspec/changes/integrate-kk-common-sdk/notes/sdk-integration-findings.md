# SDK 集成过程中的关键发现（实施记录）

实施 `integrate-kk-common-sdk` 时针对已发布工件 `kk-common:0.1.2` + Spring Boot 3.5.6 实测发现的三个 SDK 行为，以及 kwiki 侧的对应处置。**升级 SDK 版本时应复核此处全部三条**。

## 1. Boot 3.5 的 `requestContextFilter` Bean 名冲突（启动失败级）

Boot 3.5 起 `WebMvcAutoConfigurationAdapter` 自带名为 `requestContextFilter` 的 bean（返回 spring-web `RequestContextFilter`/`OrderedRequestContextFilter`），条件为 `@ConditionalOnMissingBean({RequestContextListener, org.springframework.web.filter.RequestContextFilter})`。SDK `CommonBoot3AutoConfiguration.requestContextFilter()` 的过滤器类型是 `com.kk2004.common.web.RequestContextFilter extends OncePerRequestFilter`（**不是** spring-web 类型），所以 Boot 的条件看不到它；两个同名 bean 定义直接 `BeanDefinitionOverrideException`，应用无法启动。

**处置**：`CommonSdkWebCompatibilityConfiguration` 自行声明 spring-web 的 `RequestContextFilter`（与 Boot 自己要建的完全一致），Boot 的条件 bean 退位，SDK 过滤器拿到该名字。

## 2. SDK 过滤器覆写 `X-Trace-Id`（观测性回退级）

SDK `RequestContextFilter` 会：读请求头 `X-Trace-Id`（缺失则生成 **UUID**）→ 写入 TTL `RequestContext` → **设置响应头 `X-Trace-Id`**。它以普通 Filter bean 注册（LOWEST_PRECEDENCE），晚于 kwiki 的 `TraceIdResponseHeaderFilter`（@Order(-200)，回写 Brave W3C trace id），导致响应里的 trace id 变成无关联的 UUID，破坏日志/链路关联。

**处置**：同一兼容配置里用 `FilterRegistrationBean` 把 SDK 过滤器固定在 order `-210`（早于 -200），Brave trace id 最终覆盖响应头；SDK 的 TTL 上下文功能不受影响。

## 3. `GlobalExceptionHandler` 声明 `@Order(Integer.MIN_VALUE)`（错误语义级）

SDK 的 `@RestControllerAdvice GlobalExceptionHandler` 带 `@Order(-2147483648)`，且含 `handleException(Exception)` 兜底处理器。Spring 的 advice 解析按顺序取**第一个**有匹配处理器的 advice，因此它会吞掉一切异常 —— 包括 kwiki 必须保留的 403（Spring Security `AccessDeniedException`）和 400（`IllegalArgumentException`）语义，把它们压成 HTTP 200 + `{"code":-100,"message":"网络异常，请稍后重试"}`。消费方 advice 无论设什么 order 都排在它后面，唯一可行的是同样声明 `HIGHEST_PRECEDENCE` 并依赖稳定排序（用户扫描的 bean 先于自动配置 bean 注册）。

**处置**：`ApiControllerAdvice` 加 `@Order(HIGHEST_PRECEDENCE)`，只保留 `AccessDeniedException`（HTTP 403 + 403 envelope）与 `IllegalArgumentException`（HTTP 400 + 400 envelope）两个处理器，其余全部由 SDK 独占。`CommonResponseContractTest` 以 golden 断言固化该语义边界。

## 错误响应契约实测（golden 固化）

- 成功：`TransDTO.success(data)` → `{"code":200,"success":true,"message":"OK","data":…}`。
- SDK 处理的业务错误：**HTTP 仍为 200**，状态藏在 body `code`（404/409/422…）。`CommonErrorCode`：SUCCESS=200、BAD_REQUEST=400、UNAUTHORIZED=401、FORBIDDEN=403、VALIDATION_FAILED=**422**、NOT_FOUND=404、SYSTEM_ERROR=**-100**。
- filter 级 401/403（`RestAuthenticationEntryPoint`/`RestAccessDeniedHandler`）与登录失败（HTTP 401 + `TransDTO.failure(401,"invalid_credentials")`）不受 SDK advice 影响，格式保持可区分。
- 应用自有 `ConflictException extends BusinessException(409, …)`；本地 `NotFoundException` 已删除、直接使用 SDK 的同名类型（25 处抛出点已迁移）。

## 默认测试夹具的既有事实

- `StandardTestProperties` 一直排除 Redis 自动配置；kwiki 侧的 Redis 总开关由 `KwikiRedisConfiguration`（`kk.common.redis.enabled=true` 时 `@Import` Spring Redis 自动配置）实现，因此 `spring.data.redis.*` 在开关关闭时完全不会被读取。
- 26 个 skipped 测试是 `KWIKI_IT_*` 环境变量门控的外部契约测试，默认构建不依赖任何中间件或凭据。
