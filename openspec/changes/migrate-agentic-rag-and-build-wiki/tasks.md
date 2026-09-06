## 1. Application foundation and external configuration

- [x] 1.1 Create the Java 21/Spring Boot 3.4.2 Maven application in `pom.xml`, `src/main/java/com/kwiki/KwikiApplication.java`, Maven Wrapper files, and `src/test/java/com/kwiki/KwikiApplicationTest.java`; verify `./mvnw test` starts the minimal context without external connections.
- [x] 1.2 Add validated configuration records in `src/main/java/com/kwiki/infrastructure/config/` for MySQL, Redis, MinIO, Elasticsearch, answer LLM, and Qwen Embedding; test missing/blank required values in `ExternalServicePropertiesTest` before wiring clients.
- [x] 1.3 Create `src/main/resources/application.yml`, `application-local.yml`, and a secret-free `.env.example` using `KWIKI_*` variables, including the required new `KWIKI_QWEN_EMBEDDING_API_KEY` and fixed `text-embedding-v4` model name; add a repository test that rejects committed credential-like defaults.
- [x] 1.4 Implement external client adapters under `src/main/java/com/kwiki/infrastructure/{mysql,redis,minio,elasticsearch,ai}/` and register dependency-specific readiness contributors; verify liveness remains UP while a simulated dependency makes readiness OUT_OF_SERVICE.
- [x] 1.5 Add `SecretRedactionFilter` and correlation-ID logging under `src/main/java/com/kwiki/infrastructure/observability/`; test that authorization headers, API keys, passwords, and signed MinIO query parameters never appear in captured logs.
- [x] 1.6 Add `scripts/check-no-docker-middleware.sh` and a CI test that fails if Docker Compose, Testcontainers, copied `k-Rag` secrets, or middleware container startup becomes required by the default build.

## 2. Database schema and security baseline

- [x] 2.1 Create Flyway migration `src/main/resources/db/migration/V1__identity_and_knowledge_base.sql` for users, knowledge bases, members, roles, optimistic versions, and audit columns; verify schema constraints with `V1MigrationContractTest`.
- [x] 2.2 Create Flyway migration `V2__wiki_pages_revisions_links.sql` for ordered pages, immutable revisions, tags, links, attachments, draft/published pointers, archive state, and cycle-safe parent constraints; verify unique and foreign-key behavior with repository contract tests.
- [x] 2.3 Create Flyway migration `V3__indexing_and_chat.sql` for indexing jobs, leases, unique idempotency keys, chat sessions, messages, request traces, and scope versions; test duplicate job rejection and lease fields.
- [x] 2.4 Implement JWT authentication and `CurrentUser` resolution in `src/main/java/com/kwiki/security/`, with method-security defaults that deny anonymous Wiki access; verify authentication and sanitized 401/403 responses in `SecurityIntegrationTest`.
- [x] 2.5 Implement `OWNER`, `EDITOR`, and `VIEWER` membership policy in `src/main/java/com/kwiki/wiki/access/KnowledgeBaseAuthorizationService.java`; write table-driven tests covering every role/action combination.
- [x] 2.6 Implement immutable `AuthorizationScope`, Redis-backed `ScopeCache`, and monotonic `ScopeVersionService`; test membership change invalidation and in-memory-safe failure behavior without widening scope.

## 3. Wiki content domain and APIs

- [x] 3.1 Implement focused JPA entities and repositories under `src/main/java/com/kwiki/wiki/domain/` and `wiki/persistence/` for knowledge bases, members, pages, revisions, links, tags, attachments, and sources; verify repository queries never return archived or out-of-scope rows by default.
- [x] 3.2 Implement `KnowledgeBaseService` and `/api/v1/knowledge-bases` create/read/update/archive/member endpoints; add MockMvc tests proving role enforcement and scope-version changes.
- [x] 3.3 Implement `WikiTreeService` and `/api/v1/knowledge-bases/{kbId}/tree` for ordered hierarchy creation, move, and archive; test stable IDs, sibling ordering, cross-knowledge-base move rejection, and descendant-cycle rejection.
- [x] 3.4 Implement `PageRevisionService` for Markdown drafts, immutable revisions, publishing, comparison metadata, and restore-as-new-revision; test that draft saves do not change reader-visible published content.
- [x] 3.5 Implement page REST endpoints under `src/main/java/com/kwiki/wiki/api/PageController.java` for read, draft save, publish, revision list, compare, restore, and archive; verify ETag/optimistic-lock conflicts return a recoverable 409 response.
- [x] 3.6 Implement Markdown rendering and sanitization through a `MarkdownPort`, preserving headings, lists, tables, links, code blocks, and unsupported source-safe constructs; add round-trip fixtures in `src/test/resources/markdown/`.
- [x] 3.7 Implement internal-link parsing, stable page-ID links, backlinks, tags, attachments, and source-document endpoints; test that rename/move preserves backlinks and inaccessible attachments are not disclosed.
- [x] 3.8 Implement MinIO attachment upload/download adapters with validated object keys and short-lived presigned URLs; test file type/size validation, path traversal rejection, and permission checks without contacting real MinIO.

## 4. Durable document indexing

- [x] 4.1 Implement `IndexingJob` state machine and repository in `src/main/java/com/kwiki/indexing/job/` with PENDING, LEASED, RETRY_WAIT, COMPLETED, and FAILED transitions; write transition tests before implementing worker behavior.
- [x] 4.2 Update page publish/archive and attachment-ingest transactions to insert one idempotent UPSERT/DELETE job atomically; verify commit and rollback scenarios in service transaction tests.
- [x] 4.3 Implement `IndexingJobClaimer` using bounded batches, lease owner/expiry, retry eligibility, and deterministic reclaim; test concurrent claimers cannot own the same live lease.
- [x] 4.4 Implement an explicit Tika text-input allowlist and parser adapters under `src/main/java/com/kwiki/indexing/parse/` for Markdown, DOCX, and other enabled directly extractable formats; test scanned PDF/image/no-text inputs are rejected without OCR, embeddings, or index writes.
- [x] 4.5 Implement deterministic `ParentChunker` under `src/main/java/com/kwiki/indexing/chunk/` using major-heading sections and 1024-to-4096-character bounds; test short-section merge, subordinate-heading/paragraph split, Unicode, tables, stable parent keys, and repeated runs.
- [x] 4.6 Implement deterministic `ChildChunker` that accumulates intact paragraphs into 128-to-512-character children within one parent, splitting oversized paragraphs by sentence before hard boundaries; test min/max, paragraph integrity, stable child keys, and exact parent references.
- [x] 4.7 Implement `IndexingWorker` with bounded exponential backoff, maximum attempts, sanitized errors, cancellation, and admin retry; test crash-after-write replay and terminal-failure behavior.
- [x] 4.8 Add `/api/v1/admin/indexing-jobs` list/detail/retry endpoints and Micrometer metrics for queue depth, age, attempts, duration, and terminal failures; verify only administrators can view sanitized failures.

## 5. Qwen Embedding and Elasticsearch chunk index

- [x] 5.1 Implement `QwenEmbeddingClient` under `src/main/java/com/kwiki/infrastructure/ai/` with `text-embedding-v4`, request batching, deadline, retry classification, and response dimension validation; use an HTTP stub contract test with no real credential.- [x] 5.2 Define `ChunkDocument` and mapping builder in `src/main/java/com/kwiki/indexing/search/` for PARENT/CHILD level, chunk/parent keys, ordinals, heading/range provenance, content, tags, scope fields, parser/chunker versions, and optional child dense vectors; test the emitted mapping JSON against hand-authored fixtures.
- [x] 5.3 Implement versioned `kwiki-chunks-v1` creation, mapping verification, alias activation, and rollback in `ElasticsearchIndexManager`; test incompatible dimensions and missing provenance fields prevent alias switching.
- [x] 5.4 Implement idempotent bulk UPSERT and resource-version DELETE operations for parent and child documents in `ChunkIndexRepository`; test deterministic IDs, parent linkage, partial bulk failure classification, and no accidental cross-resource deletion.
- [x] 5.5 Connect `IndexingWorker` Tika parse → parent chunk → child chunk → child-only embed → parent/child index flow and persist parser, chunker, embedding-model, and index-version provenance; verify a repeated revision produces the same hierarchy.
- [x] 5.6 Add an explicit `external-it` Maven profile and `scripts/verify-external-services.sh` for operator-provided MySQL, Redis, MinIO, Elasticsearch, and Qwen endpoints; keep the default `./mvnw verify` fully Docker-free.

## 6. Rule and LLM intent routing

- [x] 6.1 Port and simplify the useful `k-Rag` routing enums into `src/main/java/com/kwiki/rag/routing/Intent.java`, `RewriteMode.java`, and `RetrievalPlan.java`, excluding every graph-specific intent and tool; add enum/schema compatibility tests.
- [x] 6.2 Implement `QueryNormalizer` and a versioned `KeywordRuleSet` for DIRECT_ANSWER, KNOWLEDGE_QA, PROCEDURAL, and ANALYTICAL intents; build a fixed Chinese/English regression corpus and fail tests on ambiguous terminal-rule changes.
- [x] 6.3 Implement `RuleFirstRouter` so a single terminal rule bypasses the LLM while no-hit, conflict, and compound cases call `RouterLlmPort`; verify call counts and route provenance using a deterministic fake port.
- [x] 6.4 Define a closed JSON Schema for LLM route output and implement `RouterDecisionValidator` that rejects unknown fields, ES DSL, scope fields, credentials, graph routes, blank subqueries, and more than three subqueries; cover every rejection branch with literal fixtures.
- [x] 6.5 Implement the answer-provider-backed `RouterLlmAdapter` with deadline and sanitized trace metadata, replacing the unfinished `k-Rag` placeholder rather than copying it; verify timeout, invalid JSON, and provider failure return an explicit fallback result.
- [x] 6.6 Add route feature flags, latency/fallback metrics, and structured trace storage; test disabled LLM routing always follows deterministic rule or scoped KNOWLEDGE_QA fallback paths.

## 7. Query rewriting

- [x] 7.1 Implement `RewriteDecisionService` to select NONE, CONVERSATIONAL, EXPANSION, or DECOMPOSITION from route intent, query shape, and bounded chat history; add table-driven strategy-selection tests.
- [x] 7.2 Implement `ConversationalRewriter` that produces a self-contained query from explicit recent history while retaining the original query; test pronouns, missing history, oversized history, and unrelated turns.
- [x] 7.3 Implement bounded `ExpansionRewriter` for short/low-information queries; test that expansion adds useful domain terms without changing named entities or injecting permissions.
- [x] 7.4 Implement schema-constrained `DecompositionRewriter` returning one to three distinct nonblank subqueries; test compound, duplicate, blank, over-limit, and unsafe outputs.
- [x] 7.5 Implement `QueryRewriteOrchestrator` with deadlines, original/effective query trace, deduplication, and fallback to normalized input; verify blank, invalid, and failed rewrites cannot abort safe retrieval.
- [x] 7.6 Port a curated rewrite regression corpus from `k-Rag` while documenting intentionally excluded HyDE behavior; run `./mvnw -Dtest='*Rewrite*Test' test` and record the baseline metrics fixture.

## 8. Scoped BM25/vector recall and RRF

- [x] 8.1 Implement one `EsScopeFilterBuilder` from immutable `AuthorizationScope`; test superuser, member, selected-knowledge-base, empty-scope, and stale-scope cases with exact Elasticsearch query fixtures.
- [x] 8.2 Implement `Bm25RecallAdapter` and `VectorRecallAdapter` as separate CHILD-only TopK searches that both apply the scope filter before ranking; test query construction excludes PARENT documents and prove raw `_score` is not exposed to fusion.
- [x] 8.3 Implement `ConcurrentRecallService` with per-branch deadlines, virtual-thread concurrency, partial degradation, and a both-failed error; use controllable fake adapters to verify cancellation and completion ordering.
- [x] 8.4 Port `StandardRrfFusion` as a dependency-free pure function over child ranked lists; first add exact formula, rank-origin, duplicate-child, missing-list, and deterministic-tie tests using literal expected scores.
- [x] 8.5 Implement `ParentEvidenceResolver` that walks fused children, distincts by `parentChunkKey`, aggregates matched-child provenance, batch-fetches authorized parents, and preserves first-child order; test duplicate, missing, stale-scope, and multi-parent cases.
- [x] 8.6 Implement validated retrieval budgets for child branch Top50, fused child Top40, distinct parent Top8 defaults, maximum three subqueries, total parent-context size, and deadlines; test out-of-range requests fail instead of silently widening work.
- [x] 8.7 Implement `HybridRetrievalOrchestrator` for embed → parallel child branches → multi-list RRF → child trim → distinct parent fetch → scope-version guard, with no rerank or graph path; verify single-branch degradation and parent expansion never widen authorization scope.
- [x] 8.8 Build an offline retrieval evaluation fixture in `src/test/resources/retrieval/` covering exact terms, paraphrases, short queries, multi-part questions, children sharing a parent, ties, and restricted parents; record child recall@K, parent recall@K, and citation precision thresholds.

## 9. Evidence assembly and cited SSE answers

- [x] 9.1 Implement `ParentEvidence` and `EvidenceAssembler` in `src/main/java/com/kwiki/rag/answer/` with one bounded parent body, matched-child provenance, deterministic first-hit order, total-context budget, and scope-version guard; test distinct and truncation never remove child citation identity.
- [x] 9.2 Define versioned SSE DTOs and encoder for route, rewrite, retrieve, token, citations, done, and error events with request ID and monotonic sequence; add golden-stream contract tests.
- [x] 9.3 Implement `AnswerLlmPort` and streaming provider adapter with evidence-only prompt contract, deadline, cancellation, and sanitized errors; verify a disconnected subscriber cancels the provider stream.
- [x] 9.4 Implement `AgenticAnswerService` orchestration and `/api/v1/chat/stream`; test event order, safe route fallback, branch degradation, no-evidence short circuit, provider failure, and single terminal event.
- [x] 9.5 Implement authorized citation-resolution endpoint `/api/v1/citations/{childChunkKey}` that returns page, published revision, parent key, child key, heading path, character range, and child excerpt; test revoked access returns no restricted metadata.
- [x] 9.6 Persist chat turns and non-sensitive agent trace metadata after completion or failure; test raw provider credentials and unrestricted evidence are never stored in trace fields.

## 10. Vue workspace foundation and Wiki navigation

- [x] 10.1 Scaffold `frontend/` with Vue 3, TypeScript, Vite, Pinia, Vue Router, Naive UI, UnoCSS, Vitest, and Testing Library while preserving pnpm; verify `pnpm --dir frontend test`, `typecheck`, and `build` scripts.
- [x] 10.2 Create typed API clients and Pinia stores under `frontend/src/features/wiki/` for knowledge bases, tree, pages, revisions, attachments, search, and authorization; test stale request cancellation and normalized error mapping.
- [x] 10.3 Implement `WikiWorkspaceLayout.vue` with global navigation, tree column, and content column using shared design tokens from `frontend/src/styles/tokens.css`; add layout tests for desktop and drawer state below 1024px.
- [x] 10.4 Implement `WikiSearch.vue`, knowledge/summary tabs, result counts, and ancestor-preserving tree filtering; test Chinese/English matching, clear action, empty state, and keyboard submission.
- [x] 10.5 Implement accessible `WikiTree.vue` with expand/collapse, selection, lazy state, create, rename, move, and archive affordances gated by role; test ARIA tree semantics, focus movement, and selected-page persistence.
- [x] 10.6 Implement `PageReader.vue` with breadcrumb, title, type, version, update time, sanitized rendered Markdown, links, backlinks, source documents, and role-aware actions; test inaccessible provenance never renders.

## 11. Visual editor, revisions, and AI interaction

- [x] 11.1 Select and wrap a Markdown round-trip visual editor behind `MarkdownEditorAdapter.vue`; verify the agreed fixture set survives source → visual edit → Markdown without losing supported structures.
- [x] 11.2 Implement `PageEditor.vue` edit/preview, dirty-state navigation guard, draft save, publish, optimistic conflict resolution, and accessible toolbar; test unsaved navigation and 409 conflict flows.
- [x] 11.3 Implement `RevisionHistoryDrawer.vue` with revision list, compare, and permission-gated restore; test restore creates a new displayed revision and does not mutate history entries.
- [x] 11.4 Implement summary mode and provenance in `WikiSummaryPanel.vue`, preserving active tree selection when switching knowledge/summary tabs; test mode restoration after navigation.
- [x] 11.5 Implement `AgenticAnswerPanel.vue` and an SSE client that renders route/rewrite/retrieve progress, answer tokens, terminal errors, and citations; test fragmented events, reconnection prohibition after a terminal event, and cancellation on unmount.
- [x] 11.6 Implement citation navigation that opens the referenced page revision and focuses the cited heading/chunk; test revoked or missing citations produce a safe unavailable state.
- [x] 11.7 Add frontend accessibility and responsive tests for focus visibility, labeled controls, tree keyboard navigation, drawers, editor warnings, and SSE status announcements.

## 12. Prototype, migration audit, and release verification

- [x] 12.1 Preserve `prototype/kwiki-wiki.html` as the approved information-architecture reference and keep `prototype/kwiki-wiki.test.mjs` passing for three-column structure, search, tree selection, summary, edit/preview, history, and responsive controls.
- [x] 12.2 Create `docs/migration/k-rag-source-map.md` mapping each migrated `k-Rag` class to its `kwiki` replacement or explicit exclusion, including Neo4j, Kafka, HyDE, rerank, legacy hybrid search, and unfinished route placeholders.
- [x] 12.3 Add architecture rules that prevent `wiki` and `rag` domain packages from importing Elasticsearch, MinIO, Redis, or provider SDK classes directly; verify with ArchUnit tests.
- [x] 12.4 Add end-to-end API tests using in-process fakes for Markdown/DOCX → Tika text → parent/child chunks → child-only dual recall → RRF → distinct parent context → SSE child citation, including OCR-required rejection and access revocation during generation; keep default verification Docker-free.
- [x] 12.5 Run `./mvnw clean verify`, `pnpm --dir frontend test`, `pnpm --dir frontend typecheck`, `pnpm --dir frontend build`, `node --test prototype/kwiki-wiki.test.mjs`, `openspec validate migrate-agentic-rag-and-build-wiki --strict`, and the no-secret/no-Docker audit; record zero failures before external integration.
- [ ] 12.6 Run the opt-in external-service acceptance profile against operator-supplied MySQL, Redis, MinIO, Elasticsearch, and fresh Qwen credentials; verify readiness, upload/index/search, vector dimensions, alias rollback, and secret redaction without provisioning containers.
- [ ] 12.7 Deploy with Wiki and indexing enabled first, observe readiness, job backlog, failure rate, index counts, and permission audit, then enable Agentic routing and answering by feature flag.
- [ ] 12.8 Exercise rollback by disabling Agentic/index workers, restoring the previous ES alias, and running the previous application version against forward-compatible schema additions without deleting Wiki content.
