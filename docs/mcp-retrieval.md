# kwiki MCP 检索服务（kwiki-mcp）

把 kwiki 的混合检索能力（BM25 + 向量 + RRF 融合 + 父分块证据）封装为受 OAuth 2.0
保护的 MCP Server（Streamable HTTP）。Agent 首次接入时走标准 MCP 鉴权流程
（动态客户端注册 → 授权码 + PKCE → 登录 → 令牌），此后每次请求携带 Bearer 访问令牌；
检索范围是令牌对应用户在 kwiki 中有权限的范围。

## 1. 工具与权限映射

| 工具 | 输入 | 业务 Service | 授权链 |
|---|---|---|---|
| `list_knowledge_bases` | 无 | `AuthorizationScopeResolver.resolve(user)` + `KnowledgeBaseRepository` | 资源链要求 `SCOPE_mcp:search`；作用域由既有成员关系（OWNER/EDITOR/VIEWER/ADMIN）派生，管理员为全库 |
| `search` | `query`（必填）、`kb_ids`（可选）、`strategy`（hybrid/bm25/vector）、`top_k`、`parent_limit` | `AuthorizationScopeResolver.resolve(user[, kbIds])` + `HybridRetrievalOrchestrator.retrieve` | 同上；指定 `kb_ids` 时在查询层收窄，越权 id 整体拒绝并回显被拒 id |

权限语义（全部复用既有实现，未新造权限）：

- **有效范围 = 用户成员关系 ∩ 页面级受众 ∩ 归档状态**。检索过滤器
  （`EsScopeFilterBuilder`）按「知识库集合 ∪ 可见页面集合」的并集过滤，
  页面级受众（KB_MEMBERS / SELECTED_MEMBERS / 指定成员）继续生效。
- 作用域版本围栏照常生效：检索途中成员关系变更会使请求以
  `scope_changed` 失败，而不是泄漏旧范围的数据。
- 知道知识库 id 不等于有权限：`kb_ids` 含越权 id 时返回
  `knowledge_base_denied` 错误并列出被拒 id（不区分「不存在」与「无权限」）。
- 身份只来自令牌：`McpTokenIntrospector` 用授权记录
  （principalName / clientId / authorizedScopes）+ 实时用户状态构建
  不可变 `McpActor`；工具入参、transport 上下文之外的一切来源都不参与身份。

## 2. 实现与配置

### 2.1 架构

```text
MCP 客户端（Agent）
  │  1 GET /.well-known/oauth-protected-resource/mcp   （发现，匿名）
  │  2 POST /connect/register                          （RFC 7591 动态注册，签发机密客户端）
  │  3 GET  /oauth2/authorize → 浏览器 /login 登录      （授权码 + PKCE；复用 kwiki 账号）
  │  4 POST /oauth2/token                              （Basic 认证，换访问/刷新令牌）
  ▼  5 POST /mcp  （Authorization: Bearer <access token>，Streamable HTTP）
资源服务器链 @Order(2)：进程内内省（McpTokenIntrospector）
  → 每请求重新派生用户状态/角色 → tools/call
授权服务器（Spring Authorization Server 1.5.x，同进程）@Order(1)：
  /oauth2/**、/connect/register、/login（会话仅限此链）
```

关键实现（`com.kwiki.mcp`）：

- `McpResourceServerConfiguration`：`/mcp` 资源链——无会话、无 CSRF、无 Basic
  回退；401 携带 `resource_metadata` 的 Bearer challenge；scope 不足返回 403
  `insufficient_scope`。默认 RequestAttribute 上下文仓库保证异步分派可恢复认证。
- `McpTokenIntrospector`：进程内受信内省（等价于可信 introspection），用户禁用、
  令牌撤销/过期立即生效，无需等待 TTL。
- `McpTransportConfiguration`：自定义 `WebMvcStreamableServerTransportProvider`
  （自动装配退避），`contextExtractor` 在 HTTP 边界用同一内省器固化 `McpActor`
  进 transport 上下文——工具执行线程无需依赖 ThreadLocal；工具执行前独立复验
  actor 与 scope（tools/call 不因 tools/list 可见性而豁免鉴权）。
- `McpSearchTools`：薄适配既有 `AuthorizationScopeResolver` 与
  `HybridRetrievalOrchestrator`，预算（topK、父分块数、字符预算）沿用
  `RetrievalBudgets`，越界请求被拒绝而非放宽。
- `McpAuthorizationServerConfiguration`：授权码 + PKCE、刷新轮换
  （`reuseRefreshTokens=false`，旧刷新令牌重放被拒）、RFC 7009 撤销端点、
  授权服务器元数据（含 `registration_endpoint`）。令牌为随机不透明值。
- `McpClientRegistrationController`：RFC 7591 动态注册子集——只接受
  `client_secret_basic/post`，签发一次性 `client_secret`；scope 固定
  `mcp:search`；redirect URI 逐条校验（HTTPS 或环回地址、无 fragment、≤5 条）。
- `McpLoginPageController`：服务端渲染登录页（CSRF 令牌内嵌），复用
  `DatabaseUserDetailsService`；首方只读 scope 自动同意（用户登录即授权）。

### 2.2 开关与配置项

总开关 `kwiki.mcp.enabled`（默认 **false**）：同时控制 `spring.ai.mcp.server.enabled`
与全部安全链/工具/授权服务器装配；关闭时应用与现状完全一致。

```yaml
kwiki:
  mcp:
    enabled: true
    public-base-url: https://kwiki.example.com   # 必填（启用时）：issuer/资源标识来源，禁止从 Host 推断
    access-token-ttl: 30m                         # 默认 30m
    refresh-token-ttl: 7d                         # 默认 7d
    auth-store: jdbc                              # jdbc（默认，需数据源）| memory（单机/演示）
    dynamic-client-registration: true             # 默认 true
```

Flyway 迁移 `V32__mcp_authorization_server.sql` 建 SAS 标准三表
（`oauth2_registered_client` / `oauth2_authorization` / `oauth2_authorization_consent`）。

MCP Server 端点属性在 `application.yml`：`spring.ai.mcp.server.*`
（name=kwiki-mcp、protocol=STREAMABLE、endpoint=/mcp、仅 tool 能力、关掉注解扫描）。

### 2.3 设计决策与已知偏离

- **自建授权服务器**：项目没有外部 IdP，按 SOP 采用成熟实现
  （Spring Authorization Server）而非手写协议端点；授权服务器与资源服务器同进程，
  服务单一资源 `/mcp`。
- **机密客户端而非公共客户端**：SAS 对「公共客户端 + 刷新令牌」要求在刷新请求中
  重验原始 PKCE verifier（偏离 RFC 6749），会导致标准 MCP 客户端刷新失败；
  故 DCR 签发带一次性 `client_secret` 的机密客户端，Basic 认证 + 刷新轮换均为标准语义。
- **令牌值不落哈希**：SAS 的刷新轮换/撤销契约依赖「呈上值 == 存储值」比较，
  应用层哈希装饰器会静默破坏该契约。缓解：随机 48 字节不透明令牌、轮换、短 TTL、
  撤销即时生效（内省）。
- **自动同意**：首方只读 scope（mcp:search）不设 consent 页；用户在 /login 登录即授权。
  如需收紧，可在 DCR 客户端设置中开启 `requireAuthorizationConsent` 并补 consent 页。
- **图检索（GraphRAG）暂未暴露**：`search` 覆盖混合检索主链路；ArcadeDB 图增强
  目前只在 QA 编排链路中，可按需追加为独立工具。

## 3. 验证

集成测试 `McpOAuthIntegrationTest`（`@SpringBootTest` + MockMvc，内存存储，
覆盖以下路径，全部通过 8/8）：

1. 发现：匿名读受保护资源元数据（resource / authorization_servers / scopes）。
2. 未认证 `/mcp` → 401 + `WWW-Authenticate: Bearer ... resource_metadata=...`。
3. 伪造令牌 → 401。
4. 端到端：DCR → 授权（登录 alice）→ 授权码+PKCE → 令牌 → initialize →
   notifications/initialized (202) → tools/list → `list_knowledge_bases`
   （仅见成员库 101/102，不见机密库）→ `search`（默认全量作用域与 `kb_ids:[101]` 均正确）。
5. 跨库越权：alice 检索 `kb_ids:[103]` → `isError` + `knowledge_base_denied`（回显 103）。
6. 零权限用户 bob：目录为空、检索任何指定库被拒。
7. 撤销：`/oauth2/revoke` 后原访问令牌立即 401（内省即时生效）。
8. 刷新轮换：refresh → 新访问/刷新令牌 ≠ 旧值，新令牌可用，旧刷新令牌重放 → 400。

未验证项：

- 未用真实 MCP 客户端（如 Claude Code / Cursor）实测；协议侧由 MCP Java SDK
  0.18.x + Spring AI 1.1.8 保证，测试内以原始 JSON-RPC 覆盖了
  initialize → tools/list → tools/call 全链路。
- 真实 MySQL 上的 Flyway V32 迁移与 JDBC 存储（`auth-store=jdbc`）未在本机执行，
  部署时首启会自动迁移。
- 未配置 CORS（浏览器直连 `/mcp` 默认被拒；MCP 客户端为非浏览器场景不受影响）。
- DCR 未加速率限制；如公开暴露建议在网关层限制 `/connect/register`。

## 4. 客户端接入与撤销

### 4.1 Agent / MCP 客户端接入

支持 MCP 鉴权自动协商的客户端（如 Claude Code）只需给出服务器地址，其余自动：

```bash
claude mcp add --transport http kwiki https://kwiki.example.com/mcp
# 客户端会自动：发现受保护资源元数据 → 动态注册 → 打开浏览器登录 kwiki → 拿到令牌
```

手工流程（任何 OAuth2 + PKCE 客户端）：

1. `GET /.well-known/oauth-protected-resource/mcp` → 取 `authorization_servers`。
2. `POST /connect/register`，body `{"redirect_uris":["https://app.example/cb"]}` →
   得 `client_id` + `client_secret`（请保存，后续请求用 Basic 认证）。
3. 浏览器打开
   `GET /oauth2/authorize?response_type=code&client_id=..&redirect_uri=..&scope=mcp:search&state=..&code_challenge=..&code_challenge_method=S256`
   → 用户登录 kwiki → 302 回 `redirect_uri?code=..`。
4. `POST /oauth2/token`（Basic `client_id:client_secret`）
   `grant_type=authorization_code&code=..&redirect_uri=..&code_verifier=..` →
   `{access_token, refresh_token, expires_in}`。
5. MCP 连接地址 `https://kwiki.example.com/mcp`，每次请求
   `Authorization: Bearer <access_token>`。
6. 访问令牌过期：`POST /oauth2/token`（Basic）
   `grant_type=refresh_token&refresh_token=..` → 新令牌对（旧刷新令牌立即作废）。

### 4.2 撤销与回收

- **单个令牌**：`POST /oauth2/revoke`（Basic 认证）`token=<access 或 refresh token>`
  → 内省即时失效，无需等 TTL。
- **某个客户端的全部授权**：删除 `oauth2_authorization` 中
  `registered_client_id = (SELECT id FROM oauth2_registered_client WHERE client_id = '..')`
  的记录，并按需删除 `oauth2_registered_client` 行（DCR 客户端无更新接口，重连即重注册）。
- **用户侧**：在 kwiki 停用/禁用账号（`app_user.is_active=false`）后，
  该用户所有 MCP 令牌在下一次请求即被内省拒绝。

## 5. 变更文件清单

- `pom.xml`：spring-ai-bom 1.1.8、`spring-ai-starter-mcp-server-webmvc`、
  `spring-boot-starter-oauth2-resource-server`、`spring-boot-starter-oauth2-authorization-server`
- `src/main/resources/application.yml`：`spring.ai.mcp.server.*`（跟随 kwiki.mcp.enabled）
- `src/main/resources/db/migration/V32__mcp_authorization_server.sql`：SAS 三表
- `src/main/java/com/kwiki/mcp/*`：上述 13 个类
- `src/test/java/com/kwiki/mcp/McpOAuthIntegrationTest.java`：8 个端到端用例
- `docs/mcp-retrieval.md`：本文档
