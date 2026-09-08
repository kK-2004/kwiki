# Workspace API contract

All JSON APIs use the existing `TransDTO` envelope:

```json
{"code":200,"success":true,"message":"OK","data":{}}
```

Business failures use a stable `code`/`message` pair in the envelope. HTTP 401/403/404/409/422 remain meaningful at the transport boundary; clients never infer permissions from a JWT claim.

The authentication endpoints are:

| Method | Path | Request | Result |
| --- | --- | --- | --- |
| POST | `/api/v1/auth/login` | `username,password` | `token,tokenType,expiresInSeconds,user` |
| POST | `/api/v1/auth/register` | `username,displayName,email?,password` | login result; 409 `account_exists` |
| GET | `/api/v1/auth/me` | Bearer token | current user loaded by ID |
| POST | `/api/v1/auth/refresh` | Bearer token | `token,renewed,expiresAt,user` |

The chat stream accepts `query` plus optional `sessionId`, `clientMessageId`, and `agentId`. New-session streams start with a `session` event. Each SSE data object is flat and contains `seq`, `requestId`, and `type`; terminal types are `done` and `error`.

Workspace list APIs return arrays or `{items,nextCursor}` under `data`, with cursor pagination where a collection can grow. The client supports JSON, multipart upload, `AbortSignal`, and `X-Auth-Token` renewal headers. Upload forms must not set `Content-Type` manually so the browser can add the multipart boundary.

The remaining resource APIs use the same envelope and are grouped by resource: knowledge bases and pages, sessions, invitations/join requests, page audience, comments/anchors, notifications, interactions and statistics. Implementations must apply the document authorization service before returning a row, citation, notification or aggregate.

The implemented resource endpoints are:

| Area | Endpoints |
| --- | --- |
| Workspace | `GET/POST /knowledge-bases`, `GET /knowledge-bases/{kbId}/tree`, `GET /agents`, `GET /shared`, `GET /search?q=`, `GET /recent-visits`, `GET /summary` |
| Import | `POST multipart /knowledge-bases/{kbId}/imports` (`file`, `audienceMode`, optional `parentId`, `audienceMembers=sourceKbId:userId,...`, `Idempotency-Key`); `GET/POST /knowledge-bases/{kbId}/imports/{jobId}` and `/retry` |
| Sessions | `GET /chat/sessions`, cursor-paginated `GET /chat/sessions/page?limit=&cursor=`, `GET/PATCH/DELETE /chat/sessions/{sessionId}`, `POST /api/v1/chat/stream`, `POST /chat/sessions/{sessionId}/runs/{requestId}/cancel` |
| Audience | `GET/PUT /knowledge-bases/{kbId}/pages/{pageId}/audience`, `GET .../audience/candidates`, `GET /knowledge-bases/{kbId}/audience-candidates` |
| Collaboration | `POST/GET/DELETE /invitations`, `POST /invitations/{token}/accept`, ownership-transfer create/accept/revoke |
| Wiki interaction | statistics, page likes/favorites, comments/replies/comment likes, cursor-paginated `/comments/roots` and `/comments/roots/{rootId}/replies`, `POST .../visit` |
| Mentions | `GET .../mention-candidates`, notifications list/unread-count/read/read-all |
| Selection AI | `POST .../selection-question` as an independent SSE stream; `POST/GET .../anchors` for revision-aware selection links |

## DTO and error examples

The frontend treats every JSON response as `TransDTO<T>` and never reads a role or
knowledge-base scope from the JWT. Collection endpoints that support a cursor use
this shape:

```json
{"code":200,"success":true,"message":"OK","data":{"items":[{"id":"…"}],"nextCursor":"…"}}
```

Knowledge-base and published-page views include server-derived `canManage`/
`canUpload`/`canEdit` flags for hiding management controls; these flags are hints only and
the backend still enforces every resource action.

The stable request records are `LoginRequest(username,password)`,
`RegisterRequest(username,displayName,email,password)`, `ChatRequest(query,sessionId,clientMessageId,agentId)`,
`SaveDraftRequest(markdown,changeNote,expectedLockVersion)`,
`InvitationCreate(resourceType,resourceId,role,ttlSeconds)`,
`AudienceUpdate(mode,members)`, and `CommentCreate(body,anchorId,parentId,replyTo,mentions)`.
The server derives `parentId`/`replyTo` for replies and rechecks audience membership before
writing mention notifications. `Idempotency-Key` is accepted by import and comment writes.

Failures retain their transport status and use one of `unauthenticated`, `forbidden`,
`invalid_request`, `http_404`, `http_409`, or `http_422` as the business message. A
missing database is reported as an unavailable dependency rather than an empty list.

Chat frames are intentionally flat and safe to render without trusting event names:

```text
event: tool
data: {"seq":12,"requestId":"…","type":"tool","toolName":"hybrid_search","callId":"c1","status":"success","summary":"命中 3 个可读文档","scoreKind":"rerank","confidence":0.87,"round":1}

event: quality
data: {"seq":13,"requestId":"…","type":"quality","status":"rejected","passed":false,"reason":"缺少部署步骤","retryRound":2,"round":1}

event: done
data: {"seq":19,"requestId":"…","type":"done","outcome":"success"}
```

Only public summaries, scores and citation excerpts may be sent. Hidden chain of
thought, system prompts and raw tool arguments are never part of the wire contract.

Commentary and validation errors use `unauthenticated`, `forbidden`, `invalid_request`, `http_409` or `http_422` at the client boundary. The client must submit mention recipient IDs and the server rechecks each ID against the current page audience in the same transaction as the comment.

## Rollout notes

1. Apply Flyway V6–V16 after taking a backup; V1–V5 are immutable. V14 adds import jobs, V15 comment idempotency and V16 the cleanup cursor. Existing users receive a seven-day ID-only JWT policy and old tokens require a new login.
2. Deploy the backend before enabling the new frontend so resource checks cannot fall back to the legacy knowledge-base-only boundary. Enable Redis only after its connection and lock settings are supplied; `stats_repair` rebuilds counters from MySQL and never replays uncertain increments.
3. The default comment cleanup trigger runs at 01:00 Asia/Shanghai. It uses a MySQL advisory lock and calls `CommentCleanupStrategy`, so an XXL-JOB trigger can replace it without changing comment rules.
4. The offline checks intentionally do not run MySQL, Redis, Elasticsearch, content-center, or model connections. Run the real Flyway/MySQL and external SDK smoke checks in a deployment environment before enabling indexing, imports, or Agentic RAG.

The repeatable offline entry point is `scripts/test-offline.sh`. It runs the read-only
migration fixtures, boundary/audit tests and frontend checks. Tests annotated with
`KWIKI_IT_*` remain disabled until the operator explicitly supplies external service
credentials and selects the `external-it` Maven profile.
