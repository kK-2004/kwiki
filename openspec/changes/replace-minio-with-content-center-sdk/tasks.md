## 1. Dependency and configuration foundation

- [x] 1.1 Update `pom.xml` to declare the authenticated `github-kfile` package repository, remove the MinIO version/property and `io.minio:minio`, add exact dependency `com.kk:content-center-sdk:0.1.3`, and verify Maven resolves it using external settings without committing credentials.
- [x] 1.2 Replace `ExternalServicesProperties.Minio` with validated content-center base URL, app token, optional source/path, connect timeout, and request timeout properties; update property-binding tests for missing, malformed, and valid values without echoing the token.
- [x] 1.3 Add a singleton `ContentCenterClient` configuration in `infrastructure/contentcenter` and cover builder mapping and startup failure behavior with unit tests.

## 2. Flyway migration and persistence model

- [x] 2.1 Re-enumerate every tracked and untracked file in `src/main/resources/db/migration`, confirm the numeric `V<version>__<lower_snake_description>.sql` Flyway convention and that gaps are allowed, then record the next unallocated version without changing `V1`-`V3`.
- [x] 2.2 Add a read-only migration-manifest validator under `scripts/` that scans the authoritative directory, rejects malformed filenames and duplicate numeric versions with actionable file names, and document one non-interactive command for local/CI use.
- [x] 2.3 Verify the validator against the current migration set and isolated temporary fixtures containing one malformed filename and one duplicate version; confirm each invalid fixture exits non-zero without connecting to a database.
- [ ] 2.4 Confirm the attachment table has no data, then create the newly allocated forward migration (expected `V4__content_center_attachment_metadata.sql` if still free) to drop `attachment.object_key`, add nullable `content_center_file_id`, and add uniqueness for non-null file IDs.
- [x] 2.5 Update `Attachment` mapping and lifecycle methods so `PENDING` rows may have no file ID and only a validated content-center `fileId` can transition to `STORED`; add domain tests for missing/invalid IDs.
- [x] 2.6 Extend the focused MySQL/Flyway contract test to prove `object_key` is removed, pending null file IDs are accepted, non-null file-ID uniqueness is enforced, and new stored rows round-trip; keep it opt-in and never point it at a shared database.

## 3. Content-center storage adapter

- [x] 3.1 Refactor `AttachmentStorage` around a file-ID-based upload result and lookup methods, remove its delete operation, and keep SDK types out of `wiki`, `rag`, `security`, and indexing-domain packages.
- [x] 3.2 Implement stream upload with `ContentCenterClient.upload`, configured `UploadOptions`, and strict validation of returned file ID, storage key, source, size, and content type before returning success.
- [x] 3.3 Implement short-lived browser download links by persisted file ID and original filename, honoring the existing configured TTL rather than current content-center source/path defaults.
- [x] 3.4 Implement indexing reads by obtaining a fresh short-lived SDK link and fetching it with a bounded JDK HTTP client; enforce successful status, request/connect timeouts, and maximum response bytes before returning content.
- [x] 3.5 Map SDK HTTP/transport failures into sanitized application exceptions with retry-relevant categories, ensuring app tokens, authorization headers, and complete signed URLs never enter exception messages or logs.
- [x] 3.6 Test the real SDK adapter against local `MockWebServer` endpoints for init/PUT/complete, download-link generation, bounded byte fetch, expiry/error handling, incomplete metadata, and secret redaction without contacting content center or MinIO.

## 4. Attachment and indexing workflows

- [x] 4.1 Refactor `AttachmentService.upload` to persist a file-ID-free `PENDING` record, invoke the storage port, persist the authoritative returned file ID and verified metadata, transition to `STORED`, and enqueue indexing only after success; test SDK and database failure boundaries and reconciliation diagnostics.
- [x] 4.2 Update authorized attachment downloads to reject non-stored or missing-file-ID records and request a fresh content-center URL by file ID while preserving the existing REST response shape and TTL behavior.
- [x] 4.3 Update `IndexingWorker` to read by content-center file ID and preserve existing retry/dead-letter behavior for transient, permanent, oversized, and missing-ID failures.
- [x] 4.4 Change archive handling to mark metadata `ARCHIVED` and enqueue index deletion without invoking physical content deletion; update service tests to make the content-center retention boundary explicit.
- [x] 4.5 Add workflow tests proving knowledge-base authorization, file type/size/path validation, status transitions, no partial indexing, and fail-closed missing-file-ID behavior remain intact.

## 5. Remove direct MinIO surface area

- [x] 5.1 Delete `infrastructure/minio`, remove its bucket readiness contributor, and replace every `kwiki.minio`/`KWIKI_MINIO_*` entry in `application*.yml` and `.env.example` with credential-free content-center configuration examples.
- [x] 5.2 Update `scripts/verify-external-services.sh`, the `external-it` Maven profile text, no-Docker audits, and acceptance fixtures to require and exercise content center instead of probing MinIO directly; keep the default build Docker-free and network-independent.
- [x] 5.3 Update architecture rules to forbid `com.kk.sdk` outside the content-center infrastructure adapter, remove `io.minio` rules/imports, and add a repository audit that fails on the MinIO artifact, package imports, configuration keys, or environment-variable names.
- [x] 5.4 Replace MinIO-specific comments, fake URLs, secret-redaction examples, test data, and operational/migration documentation while retaining generic signed-query and bearer-token redaction coverage.
- [x] 5.5 Run a repository-wide case-insensitive MinIO search and classify any intentional historical OpenSpec references separately; confirm no current production, build, runtime configuration, script, or test path still depends on MinIO.

## 6. Verification and cutover

- [x] 6.1 Run the migration validator, backend unit/architecture tests, and default `./mvnw verify`; confirm the build succeeds with SDK 0.1.3 and does not contact external middleware.
- [ ] 6.2 With operator-provided disposable MySQL and content-center credentials, run focused Flyway contracts and an end-to-end disposable attachment upload, metadata persistence, download, parsing/indexing, archive, and log-redaction acceptance flow.
- [x] 6.3 Verify rollback expectations before cutover: because the migration drops `object_key`, rollback to the MinIO-backed application is unsupported after schema migration; use application/database backup restoration if rollback is required.
- [x] 6.4 Run strict OpenSpec validation for `replace-minio-with-content-center-sdk` and review the final diff for unintended generated files, credentials, modified historical migrations, storage-key/source persistence, or unresolved MinIO coupling.
