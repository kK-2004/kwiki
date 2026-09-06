## Why

`kwiki` currently owns MinIO credentials, client construction, bucket health checks, and object operations, which couples attachment handling to one storage implementation. Attachments should instead be managed through the content center's authenticated application boundary by using `com.kk:content-center-sdk:0.1.3`, so storage topology and object-store credentials remain outside `kwiki`.

## What Changes

- Replace the direct `io.minio:minio` dependency and all MinIO client/configuration code with `com.kk:content-center-sdk:0.1.3` and a content-center-backed attachment adapter.
- Upload attachment streams through `ContentCenterClient`, persist only the returned content-center `fileId`, issue short-lived SDK download links by `fileId`, and let the indexing worker read attachment bytes through those links.
- Remove MinIO endpoint, access key, secret key, and bucket settings from application configuration, environment examples, readiness wiring, external-service checks, architecture rules, documentation, and tests; add content-center base URL, app token, source/path, and timeout settings where required.
- **BREAKING**: deployment configuration changes from `KWIKI_MINIO_*` credentials to content-center application configuration, and attachment archival no longer requests direct physical object deletion because SDK 0.1.3 exposes no delete operation; content lifecycle is owned by the content center.
- Add a forward-only Flyway migration after the existing `V1`-`V3` set that drops the obsolete `object_key` column and adds the canonical content-center `fileId`; do not edit or renumber existing migrations. No legacy-data compatibility or backfill is required because the attachment table has no existing data.
- Preserve the current attachment REST surface, authorization, validation, status transitions, indexing behavior, and short-lived download-link contract while adapting provider-specific failures into application-level storage errors without leaking tokens or signed URLs.

## Capabilities

### New Capabilities

- `content-center-attachment-storage`: Content-center-backed attachment upload, persistence, download/indexing access, archival semantics, configuration, observability, and migration compatibility.

### Modified Capabilities

None. This repository has no promoted specs under `openspec/specs/`; the earlier external-service and attachment requirements exist only in an in-progress change.

## Impact

- Build and dependency resolution: `pom.xml`, private GitHub Packages repository/authentication requirements, and removal of the MinIO library/version property.
- Backend: attachment storage port and service, attachment entity/repository mapping, indexing download path, content-center infrastructure configuration/adapter, readiness behavior, error handling, and secret redaction.
- Database: MySQL `attachment` metadata via a new immutable Flyway migration and updated migration contract tests.
- Operations and tests: `application*.yml`, `.env.example`, external acceptance scripts/profile text, architecture/audit rules, fixtures, and documentation that currently name or assume MinIO.
- External systems: the content center and its app token become required; `kwiki` no longer requires direct MinIO network access or credentials.
