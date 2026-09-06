## ADDED Requirements

### Requirement: Content center is the sole attachment storage integration
The system SHALL use `com.kk:content-center-sdk:0.1.3` for attachment content operations and MUST NOT include the MinIO client dependency, import `io.minio` classes, or require direct MinIO endpoint, credential, or bucket configuration.

#### Scenario: Application starts with content-center configuration
- **WHEN** a valid content-center base URL, app token, and timeout configuration are supplied
- **THEN** the application constructs one reusable SDK client without requiring any `KWIKI_MINIO_*` value or direct MinIO connectivity

#### Scenario: Repository is audited for MinIO coupling
- **WHEN** dependency, source, configuration, script, documentation, architecture-rule, and test audits run
- **THEN** no production or build path references the MinIO Maven artifact, `io.minio`, `KWIKI_MINIO_*`, or `kwiki.minio`

### Requirement: Successful uploads persist authoritative content metadata
The system SHALL upload each validated attachment stream through the content center, SHALL persist only the returned `fileId` as its content identity, and SHALL mark it `STORED` only after the SDK returns a valid file ID and consistent verified metadata.

#### Scenario: Content-center upload succeeds
- **WHEN** an authorized user uploads an allowed non-empty attachment within the configured size limit and the SDK completes the upload
- **THEN** the attachment becomes `STORED`, its authoritative content-center `fileId` is persisted, no `storageKey` or `source` is stored, and an attachment indexing job is enqueued

#### Scenario: Content-center upload fails or returns incomplete metadata
- **WHEN** the SDK fails, omits a valid `fileId`, or returns inconsistent verified metadata
- **THEN** the attachment MUST NOT become `STORED`, no indexing upsert is enqueued, and the caller receives a sanitized storage error

### Requirement: Downloads and indexing use fresh content-center links
The system SHALL address stored files only by their persisted content-center `fileId` and SHALL request short-lived download links through the SDK without persisting or reconstructing storage keys and sources.

#### Scenario: Authorized browser download
- **WHEN** an authorized knowledge-base member requests a download URL for a `STORED` attachment with a valid content-center file ID
- **THEN** the system returns a newly issued short-lived URL whose download filename matches the attachment metadata

#### Scenario: Indexing reads attachment content
- **WHEN** the indexing worker processes a stored attachment
- **THEN** it obtains a fresh content-center download link, fetches the bytes with bounded time and size, and feeds them to the existing parser without logging the URL

#### Scenario: Link or content fetch fails
- **WHEN** content-center link creation or the bounded content fetch fails
- **THEN** the system reports a sanitized retry-classified storage failure and does not index partial or oversized content

### Requirement: Attachment archive delegates retention to content center
The system SHALL archive attachment metadata and remove its search index entry without issuing a direct object-store delete, because content-center SDK 0.1.3 does not expose deletion and the content center owns retention.

#### Scenario: Authorized user archives an attachment
- **WHEN** a user with attachment-management permission archives an existing attachment
- **THEN** its local status becomes `ARCHIVED`, an indexing delete is enqueued, and no MinIO or content-center delete request is attempted

### Requirement: Content-center credentials and links remain secret
The system MUST load the app token from runtime configuration, MUST NOT commit package or application credentials, and MUST redact authorization values and signed download parameters from logs and diagnostics.

#### Scenario: SDK request fails
- **WHEN** the content center returns an authentication, rate-limit, validation, server, or transport error
- **THEN** logs and client-facing errors identify an actionable error category without containing the app token, authorization header, storage signature, or full signed URL

#### Scenario: Private SDK dependency is resolved
- **WHEN** Maven resolves SDK 0.1.3 locally or in CI
- **THEN** it uses externally supplied GitHub Packages credentials with `read:packages` permission and no credential is stored in repository files

### Requirement: Database migration replaces the MinIO locator with a file ID
The system SHALL replace `attachment.object_key` with a nullable, uniquely indexed content-center file ID in a new forward-only Flyway migration after the complete existing migration set and MUST NOT modify or renumber an existing migration.

#### Scenario: Migration is allocated
- **WHEN** implementation begins after enumerating tracked and untracked migration files
- **THEN** the new migration receives the next unique numeric Flyway version, follows the existing `V<version>__<description>.sql` convention, and leaves all earlier files unchanged

#### Scenario: New attachment file ID is stored
- **WHEN** a content-center upload is committed
- **THEN** `content_center_file_id` is non-null and duplicate non-null content-center file IDs are rejected

#### Scenario: Pending upload has no file ID yet
- **WHEN** attachment metadata is persisted before the SDK upload begins
- **THEN** the row may be `PENDING` with a null `content_center_file_id`, and it cannot be exposed as downloadable or indexable

#### Scenario: Obsolete MinIO column is removed
- **WHEN** the migration runs against the confirmed empty attachment table
- **THEN** `object_key` is dropped, `content_center_file_id` is added, and no storage key/source compatibility column is created

### Requirement: Migration validation is repeatable and non-destructive
The system SHALL provide a local, read-only validation command that checks the authoritative Flyway migration set without connecting to a database, and SHALL retain focused opt-in tests that load the migrations through the real Flyway MySQL path.

#### Scenario: Migration manifest is valid
- **WHEN** the validator scans the authoritative migration directory including untracked files
- **THEN** it exits successfully after confirming filename shape and unique numeric versions without requiring a live database

#### Scenario: Migration manifest contains an invalid file
- **WHEN** an isolated fixture contains a malformed filename or duplicate version
- **THEN** the validator exits non-zero and names every conflicting file

#### Scenario: MySQL migration contract is exercised
- **WHEN** the opt-in migration contract test runs against operator-provided disposable MySQL
- **THEN** Flyway applies the complete set and the test verifies `object_key` removal, nullable pending IDs, file-ID uniqueness, and stored-row integrity without touching a shared database

### Requirement: Content-center capability is verified outside readiness probes
The system SHALL validate content-center configuration at startup and SHALL verify end-to-end storage capability in an opt-in external acceptance flow rather than calling an undocumented or side-effecting endpoint from readiness.

#### Scenario: Required content-center configuration is missing
- **WHEN** the base URL or app token is blank or malformed
- **THEN** startup fails before accepting traffic with a configuration error that does not echo the token

#### Scenario: External acceptance runs
- **WHEN** an operator supplies content-center and other required integration credentials and invokes the external acceptance profile
- **THEN** the flow proves disposable upload, metadata persistence, link creation, bounded download, and indexing while the default build remains network-independent and Docker-free
