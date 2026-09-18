# Contracts Inventory (Task 1.1)

Recorded before implementation. Exact contracts this change touches.

## Backend

### Page read endpoint
- `GET /api/v1/knowledge-bases/{kbId}/pages/{pageId}` → `PageController.read` (`src/main/java/com/kwiki/wiki/api/PageController.java:98-111`), `ETag: "rev-{n}"`, `TransDTO.success(PublishedView)`.
- `PublishedView` record fields: `revisionNo, markdown, html, title, createdBy, createdAt, canEdit, canManage` (lines 78–81; constructed once at 103–108).
- Authorization: `PageRevisionService.publishedContent` → `requirePage(user, kbId, pageId, ResourceAction.READ, WikiAction.READ_PAGE)` + `requireActivePage` (kbId match + `status='ACTIVE'`).

### Source document / attachments
- `source_document` (`SourceDocument.java`, table `source_document`): `id, pageId, attachmentId, relationship (DERIVED_FROM|UPLOADED_SOURCE), createdAt`. Repo: `findByPageId`, `findByAttachmentId`, `findByPageIdIn`.
- `attachment` (`Attachment.java`): `id, uuid, kbId, uploadedBy, fileName, contentType, byteSize, contentCenterFileId, status (PENDING|STORED|ARCHIVED), purpose (GENERAL|WIKI_IMPORT_SOURCE), lockVersion`.
- Content bytes: `AttachmentStorage` port — `downloadLink(fileId, name, ttl)` (short-lived presigned URL), `readContent(fileId)` (full bytes, `kwiki.attachments.max-bytes` default 50MB cap), `cdnLink(fileId)` (permanent CDN URL — must NOT leak into page DTO for source preview).
- Existing readable gate: `AttachmentService.requireReadableAttachment` (KB `READ_PAGE` + kbId match + `STORED` + valid fileId; for `WIKI_IMPORT_SOURCE` also requires ≥1 readable ACTIVE derived page via `jdbcVisibleImportPages` joining `source_document`→`wiki_page`).
- Existing byte route: `GET /api/v1/knowledge-bases/{kbId}/attachments/{uuid}/content` (inline bytes) and `/download-url`, `/media-preview-url`.
- MIME truth on `attachment.content_type`; `MediaContentSniffer` covers images/audio/video only — PDF (`%PDF-`)/DOCX (`PK\x03\x04` + `[Content_Types].xml`) sniffing must be added; import accepts `.md/.markdown/.docx/.pdf` (≤20MB, `WikiImportService.validateUploadChecked`).
- `source_document` row created in `WikiImportService.importDocument` (page + attachment in one transaction).

### Citation resolution
- `GET /api/v1/citations/{childChunkKey}` → `ChatController` (`rag/answer/ChatController.java:67-71`) → `CitationService.resolve`: keys `childChunkKey, parentChunkKey, resourceType, resourceId (numeric page id), revisionId, headingPath, charStart, charEnd, excerpt (≤160 chars), resources[{type:'image',contentId,previewUrl}]`. Denial = `NotFoundException` (no title/leak). Auth: `AuthorizationScopeResolver.resolve(user).includes(kbId)` + `resources.can(user, pageId, READ)`.

### Security
- `/api/**` authenticated; new endpoints need `@PreAuthorize("isAuthenticated()")` + service-level `requireInKnowledgeBase(..., ResourceAction.READ)`; deny via `NotFoundException`.

### Backend tests
- Pattern A: plain JUnit5+Mockito (`PageLinkAndProvenanceTest`, `AttachmentServiceTest`, `CitationServiceTest`).
- Pattern B: `@SpringBootTest @AutoConfigureMockMvc @Import(WikiMockBeans.class)` + `StandardTestProperties` (excludes DB/JPA/ES/Redis; real JWT via `JwtTokenService`).

## Frontend

### Citation route flow
- Deep link = query param `?chunk=<childChunkKey>` on route `wiki-shell`/`workspace` (`/knowledge-bases/:kbId/:pageId?`), hash history.
- `PageReader.vue` watch (L83–99): fetch `/citations/{key}` → bail if `resourceId !== props.pageId` → `nextTick` → **global** `document.querySelector('[data-testid="page-content"]')` → `locateChunk(container, excerpt, charStart)` → notice on failure → strips `chunk` query via `router.replace` regardless of success/failure. Cancellation only via watch `cleanup` stale flag; no unmount highlight cleanup.
- `locateChunk.ts`: TreeWalker text collection (non-whitespace chars), picks match nearest `charStart`, CSS Custom Highlight API `kwiki-citation` registry + 2600ms module timer; **silent no-op if API missing**; no ambiguity rejection, no mark fallback.
- Reference click sources: `ConversationMessages.goToCitation` (`router.push` with `chunk` query), `ChunkPreview` footer link.
- `?anchor=` flow is separate legacy selection-anchor logic (`locateAnchor`).

### Page DTO / store
- `PageDto` (`wiki/api.ts:11-20`) has no source metadata. `wiki/store.ts loadPage` owns module-level AbortController; `selectPage` clears state. Reader content container: `<MediaMountRegion data-testid="page-content">` inside PageReader; async media mounts; no component ref today.

### Conversation store (`workspace/conversationStore.ts`)
- Module-level `disposeStream` (single SSE), `generation` ticket, `selection` ticket; run cache in localStorage (`kwiki:conversation-run:v1:*`, cap 50).
- `select(id)`: blocked only for same-id-while-running; different id **cancels the active run** (`POST .../runs/{rid}/cancel`) then loads. `newConversation()`: cancel + reset + `minimized=false, floatingOpen=false`. `send()`: single stream ownership. `loadSessions()`: `GET /chat/sessions`, no pagination.
- Evidence hydration: `hydrateRunActivity` replays `GET /chat/sessions/{sid}/runs/{rid}/events`; no client-side redaction (non-navigable citations filtered at render).
- Floating flags: `minimized`, `floatingOpen`; `ConversationLauncher` hidden on conversations route; Escape currently minimizes directly (no panel ordering).

### Scrolling / thinking
- `ConversationMessages`: `stick` ref, threshold 100px, watches only `[answer, history.length, running]` — misses retrieval steps/citations/media height changes; no ResizeObserver/sentinel.
- `ThinkingStream`: `v-show`, fixed `height:200px`, `following` threshold 24px, no transition, no reduced-motion handling.
- `AgenticAnswerPanel` (unmounted) has the only dual follow-tail implementation (48px/32px) to reuse as reference.

### Tests
- vitest 2 + jsdom + **@testing-library/vue** (`render/fireEvent/rerender`), `tests/test-pinia.ts` (`createTestingPinia` real stores), `tests/setup.ts` only `afterEach(cleanup)`. No ResizeObserver/matchMedia/scrollIntoView mocks yet (specs monkey-patch per test). No Playwright. `api.json` spied with `vi.spyOn(api, 'json')`; fetch stubbed with `{success,code,data}` envelopes.
- `retrieval-activity.spec.ts` tests `locateChunk` under jsdom without Custom Highlight API (relies on silent fallback).

### Build
- `vite.config.ts`: vue + UnoCSS, no `build` section (no manualChunks/worker config yet). Lazy imports only at router level. Heavy static: highlight.js in `render.ts`, NModal in ChunkPreview.

## Affected contracts (summary)
1. `PublishedView` gains optional `sourceDocument` (format, fileName, byteSize, previewable) — no CDN URL.
2. New route `GET /api/v1/knowledge-bases/{kbId}/pages/{pageId}/source-preview` returning protected inline bytes after page-auth + association + STORED + PDF/DOCX signature re-check.
3. `PageDto` frontend type gains optional `sourceDocument`; store hydration backward-compatible.
4. Citation focus: PageReader-owned container ref + cancellable target keyed by (page, revision, chunk); `locateChunk` gains normalized matching/ambiguity rejection/mark fallback/cleanup; `?chunk` consumed only on final result.
5. ConversationLauncher gains 新建会话/历史会话 panel reusing store (`newConversation`, `loadSessions`, `select`); switching while running must be blocked in UI (store currently cancels).
6. New shared follow-tail controller (48px threshold, FOLLOWING/PAUSED) for messages + thinking; ThinkingStream height/opacity transition + reduced-motion.
