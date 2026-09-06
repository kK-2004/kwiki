## Context

Attachment bytes currently flow through `AttachmentStorage` into `MinioAttachmentStorage`; the same adapter reads bytes for indexing, creates presigned browser URLs, and deletes objects during archive. MinIO credentials and bucket settings are bound as required application properties, and a bucket lookup contributes to readiness. The MySQL `attachment` table stores only an `object_key` created by `kwiki`.

The required replacement, `com.kk:content-center-sdk:0.1.3`, is a Java 17 client that is compatible with this Java 21 application. It authenticates content-center API calls with an app token, uploads streams through an init/presigned-PUT/complete flow, returns `fileId`, `storageKey`, and `source`, and creates short-lived download links. It does not expose object deletion or a health-check operation. The artifact is hosted in the private `kK-2004/kFile` GitHub Packages repository rather than Maven Central.

The database is MySQL managed by Spring Boot Flyway from `src/main/resources/db/migration`. The current complete migration set is `V1__...sql` through `V3__...sql`, migrations are forward-only, and real-loader contract tests are opt-in against operator-provided MySQL. No database-free migration manifest validator currently exists.

## Goals / Non-Goals

**Goals:**

- Make the content center the only attachment storage integration used by `kwiki` and remove every direct MinIO dependency, import, credential, configuration key, health check, and operational assumption.
- Preserve attachment API authorization, type/size validation, status transitions, indexing, and short-lived browser downloads.
- Persist only the canonical content-center `fileId`; storage keys and sources remain internal to the content center.
- Introduce an additive, forward-only migration without rewriting potentially applied migrations, with database-free structural validation plus existing MySQL contract coverage.
- Keep app tokens and signed object URLs out of logs, responses other than the authorized download endpoint, and source control.

**Non-Goals:**

- Adding browser-direct or multipart upload APIs; the existing multipart request continues to reach `kwiki`, which invokes the SDK's stream upload.
- Adding media CDN previews, even though SDK 0.1.3 supports them.
- Implementing content deletion or content-center administration APIs that SDK 0.1.3 does not expose.
- Provisioning content-center infrastructure, creating its application/token, or embedding GitHub Packages credentials in the repository.
- Migrating historical MinIO objects; the attachment table is confirmed to contain no legacy data.

## Decisions

### 1. Consume the official SDK through a dedicated infrastructure adapter

`pom.xml` will remove the MinIO version property and `io.minio:minio`, declare the private GitHub Packages repository, and add the exact dependency `com.kk:content-center-sdk:0.1.3`. Repository authentication remains in developer/CI Maven settings under a matching server id and is documented without credentials.

A singleton `ContentCenterClient` is built from validated `kwiki.content-center` properties: `base-url`, `app-token`, optional `source`, optional `path`, connect timeout, and request timeout. The client remains confined to a new `infrastructure/contentcenter` package behind the domain port. This keeps application and domain code independent of SDK types.

Alternative considered: call the content-center HTTP API directly. Rejected because it would duplicate the specified SDK's authentication, response parsing, and upload flow.

### 2. Make the storage port return and consume only the content-center file ID

The upload operation will return a small domain value containing the canonical `fileId`, verified size, and content type. `storageKey` and `source` returned by the SDK are transport details and are not persisted or exposed. Download-link generation and indexing reads consume `fileId`. The SDK adapter will:

1. call `ContentCenterClient.upload(InputStream, filename, size, UploadOptions)`;
2. reject a missing/invalid `fileId` or inconsistent verified metadata before an attachment becomes `STORED`;
3. call `getDownloadLink(ofFileId(fileId).filename(...).expiresIn(...))` for browser downloads;
4. obtain a separate short-lived link for indexing, fetch it with a bounded JDK HTTP client, enforce the configured byte limit, and never log the URL;
5. map `ContentCenterException` into sanitized application storage errors while preserving retry-relevant categories internally.

`delete` is removed from the port. Archive continues to authorize, mark metadata `ARCHIVED`, and enqueue index deletion, but does not pretend to delete content remotely.

Alternative considered: persist `storageKey + source` as a fallback. Rejected because SDK 0.1.3 supports every required download operation by `fileId`, making the extra provider details redundant and a source of data-model coupling.

### 3. Add a new migration for the content-center file ID

Immediately before implementation, the complete migration set (including untracked files) will be re-enumerated. If `V4` remains the next valid identifier, create `V4__content_center_attachment_metadata.sql`; if concurrent work has allocated it, use the next numeric Flyway version. Existing `V1`-`V3` files are not edited, renamed, or deleted.

The migration drops the obsolete `object_key` column, adds nullable `content_center_file_id BIGINT`, and adds a unique index for non-null content-center file IDs. Null is required while a pre-upload row is `PENDING`; application invariants require a valid file ID before transition to `STORED`. The user has confirmed that there is no existing attachment data, so the change intentionally provides no MinIO locator compatibility or backfill path.

New successful uploads MUST persist the returned file ID. A `PENDING` row with no file ID is not downloadable or indexable and remains available for reconciliation after a failed upload.

A read-only script in `scripts/` will validate the discovered Flyway filename pattern, numeric version uniqueness, and SQL-file scope without a database. Focused migration contract tests continue to load the real Flyway migrations against opt-in MySQL and assert removal of `object_key`, nullable pending IDs, unique non-null file IDs, and stored-row application invariants. Gaps are allowed because Flyway allows them; no down-file pairing is invented.

Alternative considered: edit `V2` because the repository files are currently untracked. Rejected because an external database may already have applied that migration, and applied/shared migration immutability is safer than relying on Git status.

### 4. Remove MinIO readiness rather than inventing a content-center health call

SDK 0.1.3 has no health API. Startup validates the content-center URI, token, and timeout configuration, while readiness no longer performs a synthetic file lookup or undocumented endpoint call. The opt-in external acceptance path verifies the real capability with an upload, link creation, download, and indexing flow using disposable test content. This distinguishes configuration validity from end-to-end acceptance without creating orphaned files on each health probe.

Alternative considered: request a download link for a fake file as a readiness probe. Rejected because a `404` is ambiguous and relies on an application operation as a health protocol.

### 5. Remove provider-specific surface area while retaining generic URL secrecy

`KWIKI_MINIO_*` keys, `kwiki.minio`, MinIO classes/packages, scripts, profile text, tests, comments, and architecture import patterns are removed or renamed. Secret redaction keeps generic bearer-token and signed-query handling; tests use content-center/app-token terminology and prove that download URLs and credentials are not logged. A repository-wide audit fails if production/build sources still reference `io.minio`, the MinIO Maven artifact, `KWIKI_MINIO_`, or `kwiki.minio`.

## Risks / Trade-offs

- **[SDK artifact is private and Maven Central cannot resolve 0.1.3]** → Declare the documented GitHub Packages repository, document the `read:packages` Maven server setup, and verify dependency resolution in developer and CI environments without committing a PAT.
- **[Remote upload succeeds but the local transaction fails]** → Persist `PENDING` metadata before upload, update `fileId` and `STORED` state only after validated SDK completion, make retries explicit, and include reconciliation diagnostics without exposing signed URLs.
- **[Indexing follows an expired link or reads an unbounded response]** → Generate a fresh link for each indexing attempt, apply connect/request timeouts, enforce successful HTTP status and maximum bytes, and classify transient failures for the existing job retry path.
- **[Archiving no longer physically removes bytes]** → Document retention as content-center ownership, remove the misleading delete contract, and keep authorization plus de-indexing behavior unchanged.
- **[The destructive column replacement is applied to an unexpected non-empty table]** → Reconfirm the no-data assumption immediately before migration and abort deployment if any attachment row exists.
- **[Content-center storage source/path defaults change]** → Address existing content exclusively by stable `fileId`; the content center resolves its own storage details.
- **[Readiness has less active dependency coverage]** → Keep strict startup validation and move capability verification to the opt-in external acceptance test instead of fabricating an unsupported health request.

## Migration Plan

1. Re-enumerate all Flyway files and allocate the next version; add and run the database-free migration validator, including isolated duplicate and malformed-name fixtures.
2. Add the forward migration and update the real MySQL contract test. Verify the confirmed empty attachment table before applying the migration that drops `object_key`, then deploy code that persists content-center file IDs.
3. Provision a content-center application and app token, configure Maven/CI package access, and supply the new runtime variables without removing old application instances yet.
4. Deploy the SDK adapter and configuration changes, run the disposable upload/download/index external acceptance flow, and verify logs and readiness expose no secret or signed URL.
5. Remove `KWIKI_MINIO_*` secrets and direct MinIO network access only after successful acceptance.

Rollback uses the previous application version while leaving the additive columns in place; the migration is not reversed. Attachments created exclusively through content center after cutover are not automatically readable by the old MinIO adapter, so rollback requires pausing attachment writes or accepting that those new attachments remain unavailable until the content-center version is restored.

## Open Questions

None blocking the proposal. Deployment-specific content-center base URL, app token, optional upload source/path, and private package credentials are required before implementation acceptance and production cutover.
