# 控制器响应与异常处理器盘点（任务 3.1）

## 控制器与当前成功响应格式

| 控制器 | 路径 | 成功响应（迁移前） | 备注 |
| --- | --- | --- | --- |
| `security/AuthController` | `/api/v1/auth/login` | `ResponseEntity<LoginResponse>`（裸 JSON）；认证失败时手工返回 401 `{"error":"invalid_credentials"}` | 登录边界；失败格式由 Spring Security 语义决定 |
| `wiki/api/KnowledgeBaseController` | `/api/v1/knowledge-bases` | 裸 `KnowledgeBaseView` / `List<MemberView>` 等 | CRUD + 成员管理 |
| `wiki/api/PageController` | `/api/v1/knowledge-bases/{kbId}/pages/**` | 裸 `PublishedView`（带 ETag header）/ `RevisionView` / `List` | read 带 `ETag` header；restore/publish/draft |
| `wiki/api/WikiTreeController` | `/api/v1/knowledge-bases/{kbId}/tree|nodes` | 裸 `List<TreeNodeView>` / `NodeView` | 树操作 |
| `wiki/api/AttachmentController` | `/api/v1/knowledge-bases/{kbId}/attachments` | 裸 `AttachmentView` / `Map.of("url",…)` | multipart 上传 |
| `wiki/api/AdminIndexingJobController` | `/api/v1/admin/indexing-jobs` | 裸 `List<Map>` / `ResponseEntity<Map>`（404/409 手工构造） | 管理员端点 |
| `rag/answer/ChatController` | `/api/v1/chat/stream`、`/api/v1/citations/{key}` | `Flux<ServerSentEvent<String>>`（SSE）、裸 `Map` | SSE 流**不适合** `TransDTO` 包装；citation 是 Map |

## 异常处理器（迁移前）

`wiki/api/ApiControllerAdvice`（唯一 `@RestControllerAdvice`）：

- `NotFoundException` → 404 `{"error":"not_found"}`
- `AccessDeniedException`（Spring Security）→ 403 `{"error":"forbidden"}`
- `ConflictException` → 409 `{"error":"conflict"}`
- `MethodArgumentNotValidException`/`ConstraintViolationException`/`IllegalArgumentException` → 400 `{"error":"invalid_request"}`

另有非 advice 的错误通道：`RestAccessDeniedHandler`/`RestAuthenticationEntryPoint`（filter 链级 403/401，非控制器路径）、`AdminIndexingJobController` 手工 404/409、`AuthController` 手工 401。

## 应用自有异常

- `wiki/api/NotFoundException`（RuntimeException，语义：不存在**或**无权知晓 → 404）
- `wiki/api/ConflictException`（RuntimeException，乐观锁冲突 → 409，调用方可重载重试）
- 抛出点：约 25 处 `new NotFoundException(...)`（7 个服务类 + PageController 内联），1 处 `new ConflictException(...)`（PageRevisionService.saveDraft 乐观锁）。

## TransDTO 包装影响范围（有意破坏性契约）

本次迁移将**控制器成功响应**统一为 `TransDTO.success(data)`；客户端需要从 `$.<field>` 改读 `$.data.<field>`。影响上述全部 JSON 接口。**排除**：

- `ChatController` SSE 流（text/event-stream，非 JSON 资源）
- `AttachmentController` 的文件下载 URL 仍是 `Map`→包装进 TransDTO？——download-url 接口是 JSON，纳入包装。
- actuator 端点、Spring Security filter 链错误（401/403 entry point）不属于 MVC advice 范围，格式不变。

## 错误语义映射（迁移后）

| 迁移前 | 迁移后 | HTTP |
| --- | --- | --- |
| `NotFoundException` → `{"error":"not_found"}` | SDK `NotFoundException`（=404 code）→ `GlobalExceptionHandler` TransDTO 包裹 | 404 |
| `AccessDeniedException` → `{"error":"forbidden"}` | 保留本地 advice 处理（SDK 不处理 Spring Security 的 AccessDeniedException；Security filter 链级别由 RestAccessDeniedHandler 负责） | 403 |
| `ConflictException` → `{"error":"conflict"}` | 应用自有 `BusinessException(409,"conflict")` 子类语义 → SDK advice TransDTO 包裹 | 409 |
| `MethodArgumentNotValidException`/`ConstraintViolationException` → `{"error":"invalid_request"}` | SDK `GlobalExceptionHandler` 处理（SDK advice 原生支持这两种异常） | 400 |
| `IllegalArgumentException` → `{"error":"invalid_request"}` | 保留在收窄后的本地 advice（SDK 兜底 handler 会将其归为 500 system error，语义需保留为 400） | 400 |

**收窄后的 `ApiControllerAdvice` 只保留**：`AccessDeniedException`（403 forbidden）与 `IllegalArgumentException`（400 invalid_request）。`NotFoundException`/`ConflictException`/Bean Validation 异常全部交给 SDK，避免重复处理器。

`CommonErrorCode` 的实际值（SDK enum）：SUCCESS=200?（未解压验证值），BAD_REQUEST/UNAUTHORIZED/FORBIDDEN/NOT_FOUND/VALIDATION_FAILED/SYSTEM_ERROR。SDK `NotFoundException` 使用 NOT_FOUND code；HTTP 状态由 SDK advice 决定（TransDTO<String> + 对应状态码）。
