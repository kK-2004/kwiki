## ADDED Requirements

### Requirement: Index management is restricted to administrators
The backend MUST require authenticated `ROLE_ADMIN` authorization for every index-management read and write API. The management UI MUST NOT connect directly to Elasticsearch or receive Elasticsearch/model credentials.

#### Scenario: Non-admin calls a management API
- **WHEN** an authenticated non-admin requests `/api/v1/admin/search-indexes/**`
- **THEN** the backend returns an authorization failure without disclosing index metadata

#### Scenario: Admin opens the console
- **WHEN** an authenticated administrator opens the index-management route
- **THEN** the console loads version and run data through the same Spring Boot backend using the existing JWT identity

### Requirement: The admin console is served by the same backend
The system MUST build the vue-pure-admin console as a dedicated frontend entry and MUST serve its production assets and route fallback below `/admin/` from the existing Spring Boot service. The fallback MUST NOT intercept API or actuator paths.

#### Scenario: Load a nested admin route directly
- **WHEN** a browser requests `/admin/search-indexes/runs/123`
- **THEN** Spring Boot serves the admin SPA entry and the client router restores that route

#### Scenario: Anonymous user loads admin assets
- **WHEN** an anonymous browser loads `/admin/`
- **THEN** it can load the login shell but cannot obtain any management data until authenticated as an administrator

### Requirement: Administrators can inspect index truth and safe actions
The console MUST show the actual read-alias target, every managed physical version, editable configuration and config revision, built revision, backend-derived lifecycle label, write-enabled/disabled state, switch readiness, fixed build ranges, tail/event catch-up cursors, document/resource counts, last validation, job backlog, failures, and actions currently allowed by backend policy. Its primary labels MUST be “待重建” after creation/edit, “重建中” during baseline work, “已重建” after baseline completion, “补齐中” only after a switch request finds outstanding differences, and “已发布” only for the version successfully activated by the read alias.

#### Scenario: Database and alias state disagree
- **WHEN** reconciliation detects that the persisted selected version differs from the Elasticsearch alias target
- **THEN** the console prominently shows NEEDS_ATTENTION, identifies the non-sensitive mismatch, and disables unsafe mutations

#### Scenario: Rollback target is behind
- **WHEN** v1 has failed or pending target jobs after v2 activation
- **THEN** the console marks v1 behind and disables selecting it until catch-up validation passes

#### Scenario: Baseline rebuild completes before selection
- **WHEN** v2 finishes its fixed-range baseline rebuild while v1 remains the read alias target
- **THEN** v2 displays “已重建” and does not display “待补齐” or “补齐中”

#### Scenario: Selection requires catch-up
- **WHEN** an administrator selects built v2 and the backend detects range, event, or live-target differences
- **THEN** v2 displays “补齐中” until those differences are closed, validated, and the alias activation succeeds, after which v2 displays “已发布”

#### Scenario: Selection has no catch-up difference
- **WHEN** an administrator selects built v2 and all range, event, and live-target differences are already zero
- **THEN** the console may keep “已重建” during the short validation step and changes to “已发布” only after atomic alias activation succeeds

### Requirement: Version configuration is auto-numbered and safely editable
The console MUST let an administrator create the next automatically numbered configuration and edit parser version, chunker version, embedding provider/model, vector dimensions, and mapping schema for an eligible offline version. The backend MUST validate values independently, increment config revision, and derive whether rebuilding is required.

#### Scenario: Create a supported version
- **WHEN** v1 exists and an administrator submits a valid new configuration with an idempotency key
- **THEN** the backend allocates or returns v2, displays it as pending rebuild, and does not change the read alias

#### Scenario: Edit an eligible version
- **WHEN** an administrator edits non-current, write-disabled, idle v2 after an earlier build
- **THEN** the console preserves the v2 number, shows the incremented config revision, and changes its label from built to pending rebuild

#### Scenario: Edit an online version
- **WHEN** an administrator attempts to edit the current read target, a write-enabled version, or an active build/catch-up target
- **THEN** the backend rejects the edit and the console offers copying the configuration into the next version

#### Scenario: Submit an unsupported pipeline
- **WHEN** the requested parser, chunker, embedding configuration, or dimension is not supported by the deployed backend
- **THEN** creation is rejected with an actionable non-secret error and no partial active version

### Requirement: Rebuild controls expose durable progress
The console MUST provide a manual “重建” action for each eligible version and allow an administrator to start, pause, resume, and retry a fixed-range rebuild through asynchronous APIs. The backend MUST acquire the shared per-version rebuild lock before accepting work and MUST return BUSY with the current run when another rebuild for that version is active. Each operation MUST return durable identifiers, and reloads MUST preserve configuration revision, build generation, per-resource bounds/cursors, starting change-event ID, progress, and history.

#### Scenario: Start a rebuild
- **WHEN** an administrator starts rebuilding a created version
- **THEN** the UI receives a runId and shows the immutable build config revision, fixed ID ranges, resource cursors, scanned/succeeded/skipped/failed counts, embedding usage, and elapsed time

#### Scenario: Retry failed resources
- **WHEN** a rebuild has isolated retryable target failures
- **THEN** the administrator can retry those failures without rescanning already-current resources or changing the alias

#### Scenario: Click manual rebuild while the version is busy
- **WHEN** an administrator clicks “重建” for v2 while its initial or manual rebuild already holds the v2 lock
- **THEN** the console shows the existing run and a non-destructive busy message instead of creating another run

#### Scenario: Manual rebuild is unsafe for the online version
- **WHEN** v2 is selected, write-enabled, catching up, or switching
- **THEN** the backend omits the rebuild action from allowed actions and rejects any stale direct request

### Requirement: Catch-up and multi-write statistics are visible and recoverable
The console MUST present catch-up and multi-write statistics sourced from backend persisted state. Catch-up statistics MUST include per-resource interval bounds and current cursor, scanned/succeeded/skipped/failed/remaining counts, event watermark/current cursor/lag, throughput, elapsed time, and ETA. Multi-write statistics MUST include the enabled target set and per-version submitted/succeeded/pending/retrying/failed counts, lag, throughput, embedding calls, and latest success/failure timestamps.

#### Scenario: Administrator monitors switch catch-up
- **WHEN** v2 is “补齐中”
- **THEN** the page updates range-tail and event-replay progress independently and preserves the same values after reload

#### Scenario: One multi-write target falls behind
- **WHEN** v1 succeeds but v2 accumulates retrying or failed target jobs
- **THEN** the page shows the divergence per version, marks v2 not ready, and does not hide it behind aggregate success

### Requirement: Selecting a retrieval version prepares dual-write and catch-up before activation
The console MUST treat selecting another retrieval version as an asynchronous switch operation. It MUST first show that all non-disabled versions will become write targets, display parallel range-tail and change-event catch-up, require successful validation, and only then request atomic alias activation. The backend MUST revalidate all gates regardless of UI state.

#### Scenario: Prepare and activate v2
- **WHEN** v2 is built and the administrator confirms `kwiki-chunks: v1 → v2`
- **THEN** the backend enables all non-disabled versions for new writes, catches v2 up from its saved range/event cursors, validates it, atomically switches the alias, and the UI displays each durable phase

#### Scenario: Catch-up is still running
- **WHEN** range-tail or change-event replay has not reached the switch barrier
- **THEN** the UI shows switch preparation progress and the actual read alias remains v1

#### Scenario: State changes after the confirmation dialog opens
- **WHEN** new backlog or an alias mismatch invalidates the displayed validation before submission
- **THEN** the backend rejects activation as stale and the UI requires a fresh validation and confirmation

### Requirement: Hot rollback clearly reports readiness and consequences
The console MUST offer hot switching only to a supported, enabled, caught-up, non-dirty version and MUST require explicit confirmation of the alias transition. It MUST show that every non-disabled version remains writable after switching.

#### Scenario: Roll back from v2 to v1
- **WHEN** enabled v1 is caught up and validated and the administrator confirms `kwiki-chunks: v2 → v1`
- **THEN** the alias changes atomically, v2 remains write-enabled, and an audit record is displayed

#### Scenario: Attempt to select a disabled version
- **WHEN** an administrator selects a version whose writes were disabled
- **THEN** the console requires re-enable, range/event catch-up, and validation instead of presenting immediate switching as available

### Requirement: Disable and cleanup use two-stage destructive confirmation
The console MUST separate disabling a physical index from deleting it. Deletion MUST display and require confirmation of the exact physical index name, and the backend MUST recheck all safety guards at execution time.

#### Scenario: Disable writes but retain data
- **WHEN** an administrator disables non-current v1
- **THEN** v1 stops receiving writes but remains present in Elasticsearch until a separate delete request

#### Scenario: Confirm old-index deletion
- **WHEN** an eligible v1 is selected and the administrator confirms its exact name
- **THEN** only v1 is deleted, the active alias remains unchanged, and the operation is audited

### Requirement: Every management mutation is idempotent and audited
Create, edit, rebuild control, switch preparation, validation, activation, disabling, re-enabling, and deletion APIs MUST accept idempotency protection and MUST record administrator identity, request time, target, configuration revision, prior state, resulting state, outcome, and sanitized failure summary.

#### Scenario: Activation request is retried after a client timeout
- **WHEN** the same administrator repeats an activation request with the same idempotency key
- **THEN** the backend returns the original or reconciled outcome without performing a second conflicting alias transition

#### Scenario: Administrator reviews history
- **WHEN** an administrator opens the audit view
- **THEN** the console lists who performed each lifecycle action and its non-sensitive result without exposing document content or credentials
