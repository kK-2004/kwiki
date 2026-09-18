## 1. Configuration and persistence foundation

- [x] 1.1 Add validated multimodal vision configuration for enablement, OpenAI-compatible base URL/API key, `qwen3.7-flash`, timeouts, bounded retries, concurrency, image limits, filtering thresholds, and prompt version; document corresponding environment variables without secret defaults.
- [x] 1.2 Add a Flyway migration and JPA model/repository for durable derived-image identity, content-center contentId, image hash, source/parser/prompt identity, processing state, summary, timestamps, uniqueness, and cleanup/audit status.
- [x] 1.3 Add repository concurrency tests proving duplicate workers converge on one authoritative derived-image record and can resume incomplete states.

## 2. Metadata block protocol

- [x] 2.1 Implement immutable metadata/block types and a canonical serializer for paired `KWIKI_META_DATA_START/END` markers containing only `type=image` and a positive `contentId`.
- [x] 2.2 Implement strict paired-block parsing and validation, including metadata size, summary size, exact allowed fields, matching START/END identities, and nested-marker rejection.
- [x] 2.3 Add protocol tests for canonical round trips, malformed JSON, extra fields including mime, mismatched pairs, invalid ids, incomplete markers, nested markers, and marker-like ordinary text.

## 3. Java PDF image extraction

- [x] 3.1 Implement a PDFBox-based page parser that collects positioned text blocks and raster image drawing events and emits a deterministic page/coordinate reading order.
- [x] 3.2 Add image-byte normalization, MIME sniffing, SHA-256 hashing, duplicate reuse, transparent/mask/empty filtering, pixel/area bounds, and per-document image limits.
- [x] 3.3 Integrate the PDF multimodal path into `DocumentParseService` while retaining the current Tika path for non-PDF and plain-text behavior and preserving the no-extractable-text rejection rule.
- [x] 3.4 Add PDF fixtures and parser tests for text-only, image-before/middle/after-text, multi-page, repeated images, decorative images, corrupt images, complex placement, scanned-only, and over-limit documents.

## 4. Published Markdown image resolution

- [x] 4.1 Extend the Markdown indexing parser to use media source spans for standard image syntax and supported `<img>` tags while skipping fenced code and escaped examples.
- [x] 4.2 Resolve `attachment://uuid` through the current knowledge base and published revision media references, verify stored image bytes, and reuse the existing contentCenterFileId without re-upload.
- [x] 4.3 Implement an SSRF-resistant HTTPS image downloader with URL/redirect/DNS/port checks, public-address enforcement, no credential forwarding, content sniffing, and byte/pixel/time limits.
- [x] 4.4 Mirror validated external image bytes to content center and persist URL/revision/content-hash identity so retries reuse the authoritative contentId and changed content produces a new result.
- [x] 4.5 Assemble protected blocks at the original Markdown positions only in the structured ES projection, leaving published revision Markdown and page rendering unchanged.
- [x] 4.6 Add Markdown tests for uploaded and external images, repeated references, `<img>`, surrounding text order, drafts, fenced/escaped syntax, invalid attachment scope/state/type, URL changes, redirects, private/metadata targets, unsupported schemes, and size limits.

## 5. Content-center image lifecycle

- [x] 5.1 Implement an image-resource service that uploads normalized PDF/external-image bytes through `AttachmentStorage.store`, reuses existing uploaded-Markdown contentIds, validates MIME/size/contentId, and retrieves CDN links by contentId.
- [x] 5.2 Implement idempotent state transitions that reuse completed upload/summary intermediates across worker retries and index-version fan-out without holding database transactions across external calls.
- [x] 5.3 Add auditable orphan/cleanup candidate handling that respects attachment/page revision and active index references and degrades safely when the content-center SDK has no delete capability.
- [x] 5.4 Add content-center contract tests for new derived uploads, existing attachment reuse, inconsistent metadata, transient/permanent failures, retry reuse, concurrent creation, CDN lookup, and sanitized diagnostics.

## 6. Qwen vision summarization

- [x] 6.1 Implement a dedicated OpenAI-compatible vision client using one user message with ordered `image_url` and text parts and model `qwen3.7-flash`.
- [x] 6.2 Add the versioned Chinese search-summary prompt, deterministic generation settings, response normalization, empty/invalid response rejection, and forbidden-marker rejection.
- [x] 6.3 Add bounded retry/backoff, concurrency limiting, timeout/cancellation propagation, transient/permanent error classification, tracing, metrics, and secret/CDN/request-body redaction.
- [x] 6.4 Add mock-server tests asserting exact request JSON, response extraction, 429/5xx/timeout retries, non-retryable 4xx handling, malformed responses, cancellation, and log redaction.

## 7. Multimodal document assembly and chunking

- [x] 7.1 Extend structured document/block representation to carry protected resource blocks while maintaining correct plain-text offsets and original PDF/Markdown ordering.
- [x] 7.2 Orchestrate resolve/extract → deduplicate → conditional content-center upload → CDN lookup → vision summary → protected-block assembly, with failure-atomic output and durable intermediate reuse.
- [x] 7.3 Update parent and child chunkers so no boundary splits a protected block and an oversized block is emitted intact with an explicit metric.
- [x] 7.4 Add an embedding/context text projection that removes marker wrappers while retaining full image summaries.
- [x] 7.5 Add assembly and chunking tests for adjacent blocks, boundary collisions, repeated contentIds, oversized summaries, mixed headings/text/images, and partial external-stage failure.

## 8. Index storage and retrieval projection

- [x] 8.1 Extend Elasticsearch chunk mapping and write models with deduplicated multimodal resource references/contentIds while retaining intact canonical source text.
- [x] 8.2 Populate resource fields from validated protected blocks during indexing and ensure embeddings use the marker-free summary projection.
- [x] 8.3 Extend retrieval/reference DTO assembly to expose `type` and `contentId` and resolve a fresh preview/CDN URL only after source knowledge-base/document authorization.
- [x] 8.4 Add index and retrieval tests for resource deduplication, source-of-truth reconstruction, PDF/Markdown BM25 and embedding summary recall, authorized preview, unauthorized denial, and absence of persisted URLs.

## 9. Worker integration, observability, and rollout

- [x] 9.1 Integrate multimodal processing into ATTACHMENT and post-publication PAGE indexing with retryable/permanent failure mapping and guarantee that no partial multimodal index version is committed.
- [x] 9.2 Add metrics and structured logs for extracted/resolved/downloaded/filtered/deduplicated/uploaded/summarized images, latency, retries, SSRF denials, oversized blocks, failures, and cleanup candidates without sensitive values.
- [x] 9.3 Bump/register the parser and prompt identities in index pipeline versioning so enablement and behavior changes require an explicit rebuild and validation.
- [x] 9.4 Add end-to-end integration coverage for a stored illustrated PDF and published Markdown pages with uploaded/external images through content-center handling, Qwen mock summarization, chunk indexing, hybrid retrieval, resource response, and image replay.
- [x] 9.5 Update operator documentation for configuration, feature-flag rollout, external-image network policy, model/CDN connectivity checks, page publication semantics, index rebuild/select, monitoring, rollback, and derived-resource cleanup limitations.
- [x] 9.6 Run the complete Maven test suite and OpenSpec validation, recording any unrelated pre-existing failures separately.
