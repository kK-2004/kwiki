## ADDED Requirements

### Requirement: Durable indexing jobs
The system SHALL record an indexing job in MySQL in the same transaction that publishes, archives, or replaces an indexable Wiki resource.

#### Scenario: Page publication commits
- **WHEN** a page publication transaction commits
- **THEN** one pending UPSERT job exists for the published revision even if the application stops immediately afterward

#### Scenario: Page publication rolls back
- **WHEN** a page publication transaction rolls back
- **THEN** neither the new published pointer nor its indexing job is committed

### Requirement: At-least-once idempotent processing
The system SHALL process indexing jobs at least once using leases and SHALL make repeated processing safe through a unique resource-version-operation key.

#### Scenario: Worker stops after writing Elasticsearch
- **WHEN** a worker writes a chunk set but stops before marking the job complete
- **THEN** the retried job replaces the same versioned chunk documents without creating duplicates

#### Scenario: Worker lease expires
- **WHEN** a claimed job exceeds its lease without completion
- **THEN** another worker can reclaim it after the lease and continue within the retry policy

### Requirement: Tika text-only input boundary
The system SHALL accept an allowlist of Apache Tika inputs that yield usable text, including Markdown and DOCX, and SHALL NOT invoke or require image OCR.

#### Scenario: Supported Markdown or DOCX is uploaded
- **WHEN** Apache Tika and the format-specific structure adapter extract nonblank text and paragraph structure from an allowed file
- **THEN** the system records the document and schedules parent-child chunk indexing

#### Scenario: Input requires OCR
- **WHEN** a scanned PDF, image, or other input yields no usable text without OCR
- **THEN** the system rejects it with an unsupported-or-no-text result and creates no embedding or index document

#### Scenario: File type is outside the allowlist
- **WHEN** a user uploads a type that has not been enabled even if Tika can identify it
- **THEN** the system rejects the file before indexing and reports the accepted text-extractable types

### Requirement: Hierarchical parent-child chunks
The system SHALL create parent chunks from major heading sections with a target size of 1024 to 4096 characters and SHALL create paragraph-aware child chunks of 128 to 512 characters within exactly one parent.

#### Scenario: Published page is chunked
- **WHEN** the indexer processes a published page revision
- **THEN** every parent follows the heading hierarchy and every child references exactly one parent with resolvable page, revision, heading path, and character range

#### Scenario: Heading section is larger than 4096 characters
- **WHEN** one major-heading section exceeds the configured parent maximum
- **THEN** the system splits it first on subordinate headings and paragraphs while preserving the original heading path

#### Scenario: Paragraph exceeds 512 characters
- **WHEN** one paragraph cannot fit within the configured child maximum
- **THEN** the system splits it on sentence boundaries when possible and uses a hard boundary only when no natural boundary exists

#### Scenario: Adjacent paragraphs fit one child
- **WHEN** consecutive paragraphs remain within the configured 128-to-512-character child range
- **THEN** the system keeps each paragraph intact and accumulates them into one child

#### Scenario: Same revision is reprocessed
- **WHEN** the same revision and chunking configuration are processed again
- **THEN** parent keys, child keys, parent references, and logical content boundaries remain deterministic

### Requirement: Parent-child indexing provenance
The system SHALL store both parent and child documents with level, stable keys, ordinals, parser/chunker versions, heading paths, and character ranges, and SHALL generate embeddings only for child documents.

#### Scenario: Parent and children are indexed
- **WHEN** a parsed section produces one parent and multiple children
- **THEN** Elasticsearch stores one `PARENT` document plus `CHILD` documents that share its `parentChunkKey`, while only children contain retrieval vectors

### Requirement: Versioned Elasticsearch index lifecycle
The system SHALL write chunks to a versioned index behind an alias and SHALL validate mapping compatibility before activating a new index version.

#### Scenario: New mapping passes validation
- **WHEN** a replacement index is built with compatible vector dimensions and required fields
- **THEN** the alias can switch atomically after verification

#### Scenario: New mapping fails validation
- **WHEN** a replacement index has incompatible dimensions or missing provenance fields
- **THEN** alias activation is rejected and the current readable index remains active

### Requirement: Retry visibility and terminal failure
The system SHALL expose job state, attempt count, next attempt time, and sanitized last error, and SHALL stop automatic retries after the configured maximum.

#### Scenario: Transient provider error occurs
- **WHEN** parsing, embedding, storage, or indexing fails with a retryable error
- **THEN** the job returns to retryable state with bounded exponential backoff

#### Scenario: Maximum attempts are exhausted
- **WHEN** a job reaches the configured maximum attempts
- **THEN** it enters terminal failure, remains inspectable, and can be explicitly retried by an administrator

### Requirement: Published-content deletion
The system SHALL remove or hide all chunks for archived pages and superseded resource versions according to the active-index policy.

#### Scenario: Published page is archived
- **WHEN** the archive DELETE job completes
- **THEN** no chunk from that page can be returned by search or RAG retrieval
