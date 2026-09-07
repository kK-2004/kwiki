## ADDED Requirements

### Requirement: Database-backed registration and login
系统 SHALL 提供登录、注册页面及对应 API，使用 MySQL 的真实用户和 BCrypt 密码，验证唯一用户名/邮箱，禁止注册者自行授予管理权限，错误 SHALL 以稳定契约展示。

#### Scenario: New user registers and signs in
- **WHEN** 用户提交有效且未占用的账号信息并登录
- **THEN** 数据库保存密码哈希，返回真实用户和 JWT，前端进入登录前目标或知识库页

#### Scenario: Duplicate registration or invalid credentials
- **WHEN** 注册唯一字段冲突或登录凭证不合法
- **THEN** 页面保留可修正输入并显示对应错误，登录失败不泄露账号存在性或禁用状态

### Requirement: Identity-only JWT with current server permissions
JWT SHALL 仅以 sub 携带用户 ID 及标准 iat/exp，MUST NOT 携带用户名、管理员或权限。每次认证 SHALL 按 ID 查询有效用户和数据库权限，资源授权 SHALL 在后端完成。

#### Scenario: Administrator loses privileges with a valid JWT
- **WHEN** 用户的数据库管理员权限被撤销而 JWT 尚有效
- **THEN** 下一次请求不再拥有该管理员权限，无须等待令牌过期

#### Scenario: User is disabled or database cannot be queried
- **WHEN** 用户被禁用/删除或身份数据源不可用
- **THEN** 系统拒绝授予身份权限，并区分未认证与依赖不可用，不能信任旧令牌权限兜底

#### Scenario: Legacy token is presented
- **WHEN** 客户端提交旧 username/uid/adm 格式 JWT
- **THEN** 系统要求重新登录，不使用 adm 构造 CurrentUser

### Requirement: Confirmed five-day renewal within seven-day validity
JWT SHALL 有效 7×24 小时。在签发后第 5 天边界以内的有效前台认证访问 SHALL 获得新的 7 天 JWT；超过第 5 天 SHALL 不再续签，但原 JWT 在第 7 天之前继续有效。到期或无效 JWT MUST NOT 通过续签复活。

#### Scenario: Access occurs four days after issue
- **WHEN** 用户在 t0+4d 使用有效 JWT 访问
- **THEN** 获得 iat=t0+4d、exp=t0+11d 的新 JWT，后续窗口以新 iat 计算

#### Scenario: Exact renewal and expiry boundaries
- **WHEN** 注入时钟分别位于 t0+5d、t0+5d+1s、t0+7d
- **THEN** 分别为可续签、不续签但可认证、401 需要重新登录

#### Scenario: Background maintenance is running
- **WHEN** 用户未主动访问而消息轮询或 SSE 心跳持续运行
- **THEN** 后台维护请求不延长 JWT 到期时间

### Requirement: Transparent client token coordination
前端 SHALL 统一管理 token、当前用户和认证路由，接收普通响应与 SSE 响应头的续签结果，持久化于 sessionStorage，拒绝旧响应覆盖新令牌，退出清除状态；401 SHALL 回登录且不自动重放业务写请求。

#### Scenario: Renewed responses arrive out of order
- **WHEN** 多个请求返回不同签发时间的 token，或旧账号请求在退出后返回
- **THEN** store 只接受当前 auth generation 中不早于现有 iat 的令牌，不恢复已退出账号

#### Scenario: User returns through an invite after login
- **WHEN** 未登录用户访问站内邀请链接并成功登录
- **THEN** 返回该邀请流程，returnTo 不能跳转站外 URL
