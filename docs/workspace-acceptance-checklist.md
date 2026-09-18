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
| 引用聚焦 | citation deep link scrolls and highlights the verified range (`citation-focus.spec.ts`, `e2e/reader-and-chat.spec.ts`) | ambiguous/missing excerpt or revoked citation shows status without false highlight | stale citation tasks are invalidated on page/teardown; query consumed only after final result; no-Custom-Highlight browsers use a temporary `<mark>` fallback |
| 来源文件预览 | PDF/DOCX 导入页的“源文件/解析文本”页签、逐页懒渲染与水印 (`source-preview.spec.ts`, `PageSourcePreviewServiceTest`) | Markdown/无来源页无页签；超限/损坏/失权文件给出明确失败原因与重试 | 每次预览重新校验页面授权、`source_document` 关联、STORED 与字节签名；响应不含永久公开地址且 `no-store` |
| 浮窗历史导航 | 新建/历史会话、就地选择续聊、展开携带相同 sessionId (`floating-history.spec.ts`) | 空列表、加载与错误状态可重试 | 生成中禁止切换并解释原因，不取消/复制/重订阅 run；Escape 先关面板再最小化 |
| 流式跟随 | 消息区与思考区独立 follow-tail（48px 阈值、ResizeObserver、帧级合并）与思考展开折叠过渡 (`follow-tail.spec.ts`) | 用户上滚后流式更新不改写 scrollTop；恢复跟随后自动贴底 | 嵌套滚动互不影响；`prefers-reduced-motion` 仅缩短视觉过渡 |

The frontend suite also covers old numeric Wiki URLs, envelope decoding,
multipart boundaries, empty search results, keyboard tree operations and the
three-column shell. Browser-level checks run against the built bundle with
route-level API fixtures via `npm run e2e` (Playwright, Chromium): reference
highlight navigation (with and without the Custom Highlight API), PDF/DOCX/Markdown
page variants, watermarked preview, floating history continuation, and
user-controlled streaming scroll restoration.

## OpenSpec coverage record

| Spec | Implemented evidence |
| --- | --- |
| account-session-authentication | `AuthController`, `JwtTokenService`, auth store and `api-auth.spec.ts` |
| document-audience-authorization | `ResourceAuthorizationService`, page audience APIs, scope version invalidation |
| connected-workspace-navigation | `WikiWorkspaceLayout`, `KnowledgeBasePage`, `FeaturePage`, `offline-workspace.spec.ts` |
| persistent-agentic-conversations | `ChatSessionService`, global `conversationStore`, SSE reducer and agent panel |
| wiki-file-import | `WikiImportJobService`, PDF/DOCX/Markdown parser, upload retry modal |
| resource-collaboration | invitation, join request, page member and ownership transfer services |
| wiki-selection-assistance | `SelectionAnchorService`, selection-question stream and anchor deep-link resolution |
| mentions-and-notifications | mention candidate API, notification center and permission-filtered deep links |
| wiki-social-interactions | threaded comments, soft deletion, likes/favorites and cleanup trigger |
| wiki-statistics-cache | versioned Redis hash, distributed miss lock, DB fallback and repair scheduler |
| wiki-reference-focus | `locateChunk` (verified range + mark fallback), `PageReader` citation ticket lifecycle, `citation-focus.spec.ts` |
| wiki-source-document-viewing | `PageSourcePreviewService` + `/source-preview` endpoint, `SourcePreview.vue`, lazy `sourceAdapters`, watermark layer |
| floating-conversation-navigation | `ConversationLauncher` history panel over the global conversation store, `floating-history.spec.ts` |
| streaming-chat-follow-tail | `useFollowTail` controller, message/thinking attachment, `ThinkingStream` transitions, `follow-tail.spec.ts` |
