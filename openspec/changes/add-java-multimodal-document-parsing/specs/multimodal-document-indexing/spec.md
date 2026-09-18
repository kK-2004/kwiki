## ADDED Requirements

### Requirement: Java-native PDF multimodal parsing
The system SHALL parse supported PDFs, their text, and eligible raster image occurrences entirely inside the kwiki Java process, and MUST NOT call MarkItDown, Python, FastAPI, or another document parsing service.

#### Scenario: PDF contains text and images
- **WHEN** a stored PDF containing extractable text and eligible raster images is indexed with multimodal parsing enabled
- **THEN** the system produces one ordered structured document containing the extracted text and one protected metadata block at each image occurrence

#### Scenario: Plain text PDF
- **WHEN** a PDF contains extractable text but no eligible image
- **THEN** the system indexes the text without calling the vision model or creating a derived image resource

#### Scenario: Scanned PDF without extractable text
- **WHEN** a PDF has no extractable text even though page images are present
- **THEN** the system rejects it under the existing no-extractable-text rule and does not treat multimodal summarization as OCR

### Requirement: Published Markdown image summarization
The system SHALL process image references from a published Markdown revision in its asynchronous PAGE indexing job, SHALL replace every valid image occurrence with a protected summary block in the ES indexing projection, and MUST leave the stored revision Markdown and rendered page unchanged.

#### Scenario: Published page contains an uploaded image
- **WHEN** a page containing a valid `attachment://uuid` image is published
- **THEN** publication enqueues indexing, the indexer resolves the attachment's existing content-center contentId, generates or reuses its vision summary, and stores a protected block at that Markdown image position in ES

#### Scenario: Published page contains an external image
- **WHEN** a page containing an allowed absolute HTTPS image URL is published
- **THEN** the indexer securely fetches and validates the image, mirrors it to content center, generates or reuses its summary, and stores a protected block with the mirrored contentId at that image position in ES

#### Scenario: Source Markdown remains unchanged
- **WHEN** multimodal indexing successfully processes a published Markdown image
- **THEN** the published revision still contains its original Markdown or `<img>` syntax and the summary block exists only in the indexing projection

#### Scenario: Draft is saved but not published
- **WHEN** a draft containing an image is saved without publication
- **THEN** no vision summary is generated solely because of the draft save and no published ES page document is changed

### Requirement: Markdown media syntax and ordering
The system SHALL recognize standard Markdown image syntax and supported `<img src="...">` syntax outside escaped text and fenced code, and SHALL preserve each recognized image's source position relative to surrounding headings, paragraphs, lists, and other images.

#### Scenario: Image appears between Markdown paragraphs
- **WHEN** a published Markdown image occurs between two paragraphs
- **THEN** the ES text projection places the complete protected block between those paragraphs

#### Scenario: Image syntax appears in a code fence
- **WHEN** Markdown contains image-like syntax inside a fenced code block
- **THEN** the syntax remains code text and does not trigger content resolution, download, upload, or vision summarization

#### Scenario: Repeated uploaded image
- **WHEN** the same `attachment://uuid` occurs multiple times in one revision
- **THEN** the system reuses one contentId and accepted summary but emits a protected block at every occurrence

### Requirement: Uploaded Markdown image identity
The system SHALL resolve an `attachment://uuid` image only through the current revision's knowledge-base-scoped media reference, SHALL require a stored image attachment with positive contentCenterFileId and verified image bytes, and SHALL reuse that contentCenterFileId without re-uploading the image.

#### Scenario: Valid uploaded attachment image
- **WHEN** the referenced attachment belongs to the same knowledge base, is STORED, and its bytes match an allowed image type
- **THEN** its existing contentCenterFileId becomes the protected block contentId and its content-center CDN URL is used for vision input

#### Scenario: Invalid or cross-knowledge-base attachment reference
- **WHEN** an attachment UUID is unknown, not STORED, not an image, or belongs to another knowledge base
- **THEN** the page indexing attempt fails without disclosing or indexing that attachment

### Requirement: External Markdown image ingestion safety
The system SHALL mirror supported external Markdown images into content center before summarization and MUST fetch them through an SSRF-resistant downloader that validates the initial URL, every redirect, DNS resolution, response type, image signature, byte size, decoded pixel count, redirect count, and time limits without forwarding user credentials.

#### Scenario: Allowed public HTTPS image
- **WHEN** an external image URL and all redirects resolve only to allowed public HTTPS endpoints and return a valid image within configured limits
- **THEN** the system uploads the validated bytes to content center and uses the returned contentId rather than the external URL in the ES metadata block

#### Scenario: External URL targets a private address
- **WHEN** an external image URL or redirect resolves to loopback, link-local, private, reserved, cloud metadata, or otherwise denied network space
- **THEN** the fetch is rejected before content is read and the page indexing attempt fails with a sanitized permanent error

#### Scenario: Unsupported external source
- **WHEN** an image uses HTTP when HTTPS is required, a relative URL, data URI, unsupported scheme, forbidden port, invalid image bytes, or exceeds a configured byte/pixel limit
- **THEN** the system does not fetch or summarize it and does not publish a partial new ES page version

#### Scenario: External image changes at a later revision
- **WHEN** a later published revision references the same URL but the fetched image content hash has changed
- **THEN** the later revision receives a derived content-center identity and summary for the new bytes while the earlier indexed revision remains reproducible from its mirrored contentId

### Requirement: Deterministic image placement and deduplication
The system SHALL determine image occurrence order from PDF page and drawing position, SHALL preserve that order relative to page text, and SHALL reuse the same derived resource and summary for identical image bytes within one source document while retaining every occurrence position.

#### Scenario: Multiple positioned images
- **WHEN** a PDF page contains text before, between, and after two eligible image occurrences
- **THEN** the assembled document places the two protected blocks in deterministic reading order among the corresponding text blocks

#### Scenario: Repeated image bytes
- **WHEN** identical image bytes are drawn more than once in the same source document
- **THEN** the system uploads and summarizes the image once but emits a protected block at every occurrence using the same contentId

### Requirement: Content center persistence for derived images
The system SHALL upload each unique PDF-extracted or external-Markdown image to the configured kFile content center before vision summarization, SHALL reuse the existing contentId for valid manually uploaded Markdown images, and SHALL treat the resulting positive contentId as the only persistent external resource identity exposed by the metadata block.

#### Scenario: Derived image upload succeeds
- **WHEN** content center accepts an extracted image and returns a consistent positive file id
- **THEN** the system records that id as the derived image contentId and uses it for subsequent CDN lookup

#### Scenario: Upload response is incomplete
- **WHEN** content center returns no positive id or returns inconsistent size or content type metadata
- **THEN** the system rejects the derived resource and does not emit a metadata block for it

#### Scenario: Indexing retry after upload
- **WHEN** indexing retries after an image was uploaded and durably recorded for the same source identity, parser version, and image hash
- **THEN** the system reuses the recorded contentId rather than uploading a duplicate object

#### Scenario: Existing Markdown attachment is summarized
- **WHEN** a published page references a valid manually uploaded image already stored in content center
- **THEN** the system uses that existing contentId and does not create a second content-center object

### Requirement: MIME handling remains outside the marker
The system MUST determine and validate an image MIME type for upload and model delivery, but SHALL NOT include MIME in the persisted `KWIKI_META_DATA` JSON because contentId identifies the authoritative content-center object.

#### Scenario: JPEG image is uploaded
- **WHEN** an extracted JPEG is persisted to content center
- **THEN** the upload uses a validated JPEG content type while the emitted marker contains only type and contentId

### Requirement: Qwen image summarization request
The system SHALL obtain a content-center CDN URL for every PDF, uploaded-Markdown, or mirrored external-Markdown image that lacks a reusable summary and SHALL call an OpenAI-compatible chat completions endpoint using model `qwen3.7-flash`, a user message whose first content part is `image_url`, and a second text content part containing the configured summary prompt.

#### Scenario: Vision request shape
- **WHEN** an uploaded image requires a summary
- **THEN** the request contains `{"type":"image_url","image_url":{"url":"<cdn-url>"}}` followed by `{"type":"text","text":"<prompt>"}` in one user message and identifies model `qwen3.7-flash`

#### Scenario: Valid vision response
- **WHEN** the provider returns a non-blank `choices[0].message.content`
- **THEN** the system stores the normalized response as the image summary associated with the derived contentId and prompt/model version

#### Scenario: Invalid vision response
- **WHEN** the provider response is missing the expected choice or contains a blank summary
- **THEN** the system treats the summarization as failed and does not publish a partial index update

### Requirement: Search-oriented summary prompt
The configured prompt SHALL require a concise, factual, independently searchable Chinese description covering visible subject, relevant text, entities, numbers, trends, and relationships, and SHALL prohibit fabricated details and metadata-marker syntax in the response.

#### Scenario: Summary is normalized
- **WHEN** the model returns a summary with surrounding whitespace or forbidden marker text
- **THEN** the system normalizes harmless whitespace and rejects output containing nested `KWIKI_META_DATA` markers

### Requirement: Canonical protected metadata block
The system SHALL encode every derived image reference exactly as a paired protected block whose START and END metadata are valid JSON objects containing only `type` and `contentId`, with `type` equal to `image`, `contentId` a positive integer, and the image summary between the markers.

#### Scenario: Canonical block is emitted
- **WHEN** image contentId 12345 has a successful summary
- **THEN** the document contains `<<KWIKI_META_DATA_START {"type":"image","contentId":12345}>>`, then the summary, then `<<KWIKI_META_DATA_END {"type":"image","contentId":12345}>>`

#### Scenario: Marker metadata does not match
- **WHEN** a START and END marker have different type or contentId values
- **THEN** the parser rejects the block and does not expose it as a resource reference

#### Scenario: Marker contains an extra field
- **WHEN** marker JSON contains mime, URL, document id, hash, or any field other than type and contentId
- **THEN** canonical validation fails rather than silently accepting or persisting the extra field

### Requirement: Protected blocks are atomic during chunking
The parent and child chunkers SHALL NOT place a chunk boundary inside a valid protected metadata block and SHALL retain the complete block wherever that image occurrence is included.

#### Scenario: Normal boundary intersects an image block
- **WHEN** the configured character boundary would split a START marker, summary, or END marker
- **THEN** the chunker moves the boundary so the complete block remains in one chunk

#### Scenario: Protected block exceeds a chunk limit
- **WHEN** one valid protected block alone exceeds the normal maximum chunk size
- **THEN** the system emits it as one intact exceptional chunk and records an oversize-block metric instead of truncating it

### Requirement: Embedding text retains image semantics without marker noise
The system SHALL retain the image summary in text sent to embedding and retrieval-context generation while removing the START and END wrappers from that projection.

#### Scenario: Child chunk contains an image block
- **WHEN** a child chunk is prepared for embedding
- **THEN** the embedding input contains the complete image summary but not `KWIKI_META_DATA_START` or `KWIKI_META_DATA_END`

### Requirement: Indexed chunks expose resource identities
The system SHALL persist the intact source text and a deduplicated list of referenced image contentIds in each applicable chunk document, with the protected block remaining the rebuildable source of truth.

#### Scenario: Chunk references multiple image occurrences
- **WHEN** a chunk contains two occurrences of contentId 12345 and one occurrence of contentId 67890
- **THEN** its structured resource field contains 12345 and 67890 exactly once each

### Requirement: Authorized resource playback
The system SHALL resolve a stored contentId to a CDN or preview URL only after verifying the current principal can read the knowledge base and source document that produced the indexed reference, and SHALL NOT require a persisted URL for replay.

#### Scenario: Authorized retrieval result
- **WHEN** an authorized user receives a retrieval result containing an image reference
- **THEN** the result exposes type and contentId and can provide a freshly resolved preview URL for image display

#### Scenario: Unauthorized contentId lookup
- **WHEN** a user without source-document access attempts to resolve a known contentId
- **THEN** the system denies the request without returning a content-center URL

### Requirement: Bounded retries and failure atomicity
The system SHALL apply bounded retries with backoff to transient content-center, external-image download, and vision-provider failures, SHALL classify non-retryable failures as permanent, and SHALL not commit a new document or page index version containing only a subset of eligible image summaries.

#### Scenario: Vision provider is temporarily unavailable
- **WHEN** the provider returns HTTP 429 or 5xx, times out, or has a transport failure
- **THEN** the operation is retried up to the configured bound and the indexing job remains retryable if the bound is exhausted

#### Scenario: Vision provider rejects the request
- **WHEN** the provider returns a non-retryable 4xx response
- **THEN** the indexing attempt fails permanently with a sanitized error classification

#### Scenario: One of several images fails
- **WHEN** a PDF or published Markdown page has several eligible images and one cannot be resolved, downloaded, uploaded, or summarized
- **THEN** the system publishes none of the newly assembled multimodal index version and retains completed durable intermediates for retry

### Requirement: Concurrent indexing is idempotent
The system SHALL coordinate concurrent attempts for the same source document, parser version, and image hash using durable uniqueness and state transitions, and SHALL perform external upload/model calls outside long-running database transactions.

#### Scenario: Two workers process the same image
- **WHEN** two indexing workers concurrently encounter the same derived-image identity
- **THEN** at most one durable content-center resource and one accepted summary become authoritative, and both workers converge on the same contentId

### Requirement: Multimodal configuration and secret safety
The system SHALL provide independently configurable vision base URL, API key, model, timeouts, concurrency, retry limits, image limits, and prompt version, and MUST redact credentials, complete CDN URLs, and request bodies from logs, traces, metrics, and user-visible errors.

#### Scenario: Required vision configuration is invalid
- **WHEN** multimodal parsing is enabled with a blank API key, invalid base URL, or blank model
- **THEN** application configuration validation fails before indexing work is accepted

#### Scenario: Provider call fails
- **WHEN** a vision HTTP request fails
- **THEN** diagnostics include safe operation, status, timing, and classification fields without the API key, full CDN URL, or image request body

### Requirement: Versioned rollout and rebuild
The multimodal parser and prompt identities SHALL participate in index pipeline versioning so enabling the feature or changing extraction/summary behavior requires an explicit rebuild and does not silently mix incompatible chunk semantics.

#### Scenario: Multimodal parser is enabled for existing documents
- **WHEN** an operator enables the new parser version
- **THEN** existing indexed documents remain on the readable old index until the new version is rebuilt, validated, and selected through the index-version workflow

#### Scenario: Existing published Markdown pages are rebuilt
- **WHEN** a new multimodal parser version is rebuilt against previously published pages
- **THEN** their uploaded and allowed external images are summarized into the new physical index without rewriting the stored page revisions
