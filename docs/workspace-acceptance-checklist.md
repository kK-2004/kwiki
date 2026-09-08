# Workspace offline acceptance checklist

This checklist is intentionally executable without MySQL, Redis, Elasticsearch,
content-center or model services. The API fixtures live in Vitest and the Java
boundary checks are invoked by `scripts/test-offline.sh`.

| Surface | Success path | Empty/error path | Permission and stale request check |
| --- | --- | --- | --- |
| 登录/注册 | envelope is unwrapped and renewed token is stored | business error is surfaced | logout generation rejects late token renewal |
| 知识库/Wiki | list, create, tree keyboard navigation, page read/edit/publish/history | empty tree and retry state | resource authorization filters unreadable pages; latest page request owns state |
| 会话/浮窗 | compose, minimize, restore, maximize, stop | stream error and partial answer | global store owns one stream and aborts only explicitly |
| 导入/协作 | upload, audience selection, invitation and transfer | parse failure and retry | server rechecks member scope and idempotency key |
| 划词/@/消息 | same-paragraph anchor, mention selection, deep link | changed or ambiguous anchor gives history fallback | candidate and notification queries are permission filtered |
| 互动/统计 | like, favorite, threaded comment and reply | unavailable cache falls back to DB truth | writes are idempotent and Redis failures do not fabricate counts |

The frontend suite also covers old numeric Wiki URLs, envelope decoding,
multipart boundaries, empty search results, keyboard tree operations and the
three-column shell. Browser-only visual checks remain deployment smoke tests
because this offline entry point must not start external services.

## OpenSpec coverage record

| Spec | Implemented evidence |
| --- | --- |
| account-session-authentication | `AuthController`, `JwtTokenService`, auth store and `api-auth.spec.ts` |
| document-audience-authorization | `ResourceAuthorizationService`, page audience APIs, scope version invalidation |
| connected-workspace-navigation | `WikiWorkspaceLayout`, `KnowledgeBasePage`, `FeaturePage`, `offline-workspace.spec.ts` |
| persistent-agentic-conversations | `ChatSessionService`, global `conversationStore`, SSE reducer and agent panel |
| wiki-file-import | `WikiImportJobService`, DOCX/Markdown parser, upload retry modal |
| resource-collaboration | invitation, join request, page member and ownership transfer services |
| wiki-selection-assistance | `SelectionAnchorService`, selection-question stream and anchor deep-link resolution |
| mentions-and-notifications | mention candidate API, notification center and permission-filtered deep links |
| wiki-social-interactions | threaded comments, soft deletion, likes/favorites and cleanup trigger |
| wiki-statistics-cache | versioned Redis hash, distributed miss lock, DB fallback and repair scheduler |
