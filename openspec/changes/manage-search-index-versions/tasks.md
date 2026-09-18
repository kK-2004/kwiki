## 1. Resolve inputs and standardize common Redis/Redisson

- [x] 1.1 Record the currently deployed v1 parser, chunker, embedding model/dimensions and mapping hash, and reconcile this change with the pending v1 bootstrap edits without overwriting unrelated worktree changes
- [x] 1.2 Select and pin a vue-pure-admin thin-template release compatible with the repository's Node/Vite baseline, including its lockfile and license notices
- [x] 1.3 Define deployment properties for supported version manifests, old/new embedding credentials, rebuild batch/concurrency/QPS limits, capacity thresholds and management feature flags
- [x] 1.4 Document every index configuration generation the first deployment can execute concurrently and fail management checks when a selected or write-enabled version is unsupported
- [x] 1.5 Set the `application.yml` defaults for `kk.common.redis.enabled` and `kk.common.redisson.enabled` to true, preserve explicit environment overrides, and synchronize local profile, `.env.example`, deployment documentation and Redis readiness expectations
- [x] 1.6 Update `StandardTestProperties` and every shared offline context fixture to explicitly disable common Redis/Redisson, then revise switch tests so production-default and explicit-disable semantics are both covered without network access
- [x] 1.7 Refactor `WikiStatisticsCache` from `StringRedisTemplate`, Lua and SETNX to a versioned DTO through kk-common `RedisUtil` guarded by kk-common `DistributedLockFactory`, preserving database fallback and repair behavior
- [x] 1.8 Replace `ResourceIndexMutex`, `MysqlAdvisoryLocks` and `CommentCleanupTrigger` MySQL advisory locks with a shared thin adapter over `DistributedLockFactory`, including namespaced keys, bounded wait/lease, interruption and ownership-safe unlock
- [x] 1.9 Update cache, scheduler, archive/index race and lock contract tests to inject mock/fake SDK interfaces and verify unavailable, contention, timeout, lease and exceptional-exit behavior
- [x] 1.10 Strengthen architecture tests to reject direct production `RedisTemplate`/`StringRedisTemplate`, `RedissonClient`, custom Redis serialization, SETNX lock and MySQL `GET_LOCK`/`RELEASE_LOCK` usage outside approved SDK integration code

## 2. Persist index lifecycle state

- [x] 2.1 Use the project Flyway migration workflow to add `search_index_version` with auto version number, editable configuration, config/built revisions, build state, write-enabled flag, health summary and timestamps
- [x] 2.2 Add `search_index_rebuild_run` with immutable build snapshot, build generation, per-resource fixed ID bounds/cursors, build-start/change-event/catch-up watermarks, counters, pause/cancel state, lease and sanitized failure fields; enforce at most one active run per version with a database uniqueness constraint
- [x] 2.3 Add an append-only monotonic `search_index_change_event` outbox for content and lifecycle events plus target-specific persistence with independent physical-version state and target-aware idempotency
- [x] 2.4 Add `search_index_audit` and idempotency persistence for every administrator lifecycle mutation
- [x] 2.5 Implement and unit-test independent build, dirty, write-enabled, selected-alias and health state transitions, including editing restrictions and NEEDS_ATTENTION reconciliation
- [x] 2.6 Validate the new migration from a clean schema and from the current latest migration, including indexes, constraints and rollback compatibility notes

## 3. Manage version configurations and physical Elasticsearch indexes

- [x] 3.1 Implement non-reusable monotonic version allocation with retained deletion tombstones, editable configuration DTOs, atomic configRevision increments, derived dirty state, independent catchupStatus and immutable per-build snapshots without persisting credentials
- [x] 3.2 Extend mapping creation and validation to use each successful build snapshot's vector dimension, schema version and required lifecycle/provenance fields rather than process-wide constants
- [x] 3.3 Extend the Elasticsearch manager to inspect exact alias targets, create an explicit physical version idempotently and reject a same-name conflicting mapping
- [x] 3.4 Implement ES health and capacity preflight with fail-closed configurable thresholds and sanitized results
- [x] 3.5 Upgrade initial bootstrap to persist v1 with equal config/built revisions and selected/write-enabled metadata while never creating, rebuilding or activating later versions after an application release
- [x] 3.6 Implement safe adoption of an existing alias target and mark unmatched legacy state NEEDS_ATTENTION without interrupting existing reads
- [x] 3.7 Add integration tests for first bootstrap, existing-v1 adoption, restart on v2, conflicting manifests and unavailable Elasticsearch

## 4. Separate reads from versioned write targets

- [x] 4.1 Keep all BM25, vector, parent and citation reads on `kwiki-chunks` and add regression tests that no read path names a shadow index
- [x] 4.2 Change chunk UPSERT and DELETE APIs to require an explicit validated physical index target instead of writing through the read alias
- [x] 4.3 Implement transactional append-only change-event creation and fan-out that snapshots every write-enabled target at enqueue time and creates independent target records
- [x] 4.4 Include target version in idempotency semantics and migrate/reopen existing indexing jobs without duplicating already-completed v1 work
- [x] 4.5 Update publication, revision replacement, attachment, deletion, archive and restore producers to use the common target fan-out path
- [x] 4.6 Preserve per-target retry/lease/error state so shadow failure cannot hide behind active-target success or incorrectly fail the active target
- [x] 4.7 Add concurrency tests proving an alias switch cannot retarget queued jobs and a write-target transition cannot lose an in-flight resource event

## 5. Execute built-configuration-specific indexing pipelines

- [x] 5.1 Implement `VersionedIndexingPipelineRegistry` that resolves supported parser, chunker, embedding client/model and dimension from a target's built configuration revision
- [x] 5.2 Refactor `IndexingWorker` to build and write an `IndexedVersion` with the immutable build snapshot and explicit physical name
- [x] 5.3 Share parsed/chunked/embedded intermediates only when built configuration snapshots are equivalent, and record embedding calls separately when they differ
- [x] 5.4 Enforce vector length, model identity, parser/chunker identity, revision and lifecycle fencing before and after each target write
- [x] 5.5 Mark selected or write-enabled versions unsupported/degraded when their pipeline or credential is unavailable and block switching to them
- [x] 5.6 Add tests for same-built-configuration reuse, different-dimension multi-write, partial target failure, stale UPSERT, archive/restore races and target retry

## 6. Build fixed ranges and catch up at switch time

- [x] 6.1 Implement admin create/edit services that validate supported configurations, auto-allocate the next version, enforce editing restrictions, update configRevision/dirty state and leave the read alias unchanged
- [x] 6.2 Implement a shared `VersionRebuildCoordinator` for both initial and manual rebuilds; acquire `kwiki:lock:index-rebuild:v{version}` through `DistributedLockFactory` before any run/index mutation, hold the watchdog lock on one coordinator thread for the run lifetime, and combine it with the per-version active-run uniqueness, lease and fencing guard
- [x] 6.3 At rebuild start, freeze configRevision/build snapshot, capture buildStartEventId and per-resource min/max ID bounds without adding the new version to normal live-write targets
- [x] 6.4 Implement stable ID-cursor scans only within the captured bounds for ACTIVE published pages and eligible STORED attachments while excluding drafts, archived content and superseded revisions
- [x] 6.5 Persist range progress and counters per batch, enqueue fenced target-specific baseline writes, mark builtConfigRevision only when the same config revision completes successfully, and keep catchupStatus separate from dirty/build status
- [x] 6.6 Implement switch preparation that atomically enables every supported non-disabled/non-deleted version for future events and captures dualWriteStartEventId plus current range upper bounds
- [x] 6.7 Run periodic per-resource range-tail scans from the prior last ID and ordered change-event replay from buildStartEventId to dualWriteStartEventId in parallel with live all-version writes
- [x] 6.8 Capture a final barrier and keep the switch PREPARING until range tails, event replay and every target operation at or below the barrier are successful or authoritatively obsolete
- [x] 6.9 Add tests for new IDs and existing-ID revisions/deletes/archives during baseline scan, cursor restart, expired leases, pauses, cancellation, idempotent overlap and unresolved gap failures
- [x] 6.10 Add the manual per-version full-rebuild service and eligibility guards for non-selected, write-disabled, idle versions, including unchanged-config rebuild generations and same-name physical-index recreation without alias mutation
- [x] 6.11 Add concurrency and recovery tests proving initial/manual requests for the same version share one lock and return BUSY with the active runId, different versions obey global limits, watchdog renewal preserves exclusivity, and crash recovery resumes the fenced original run

## 7. Validate switch and rollback readiness

- [x] 7.1 Implement persisted validation reports for configRevision/builtConfigRevision equality, build-snapshot/mapping identity, vector dimensions and required fields
- [x] 7.2 Compare target coverage against the current effective MySQL resource/revision/lifecycle set without requiring equal v1/v2 chunk counts
- [x] 7.3 Detect searchable archived/deleted resources, superseded revisions, missing parent references, malformed child vectors and mixed manifest documents
- [x] 7.4 Validate range-tail cursors, change-event replay, live target-job barrier and bounded BM25/vector smoke queries without exposing document content in the report
- [x] 7.5 Invalidate a prior READY report when configuration becomes dirty, cursor/barrier state changes, relevant backlog appears, target health/support changes or alias facts change
- [x] 7.6 Add validation tests for legal parser-driven count differences and every hard-failure gate that must leave the alias unchanged

## 8. Activate, reconcile and roll back atomically

- [x] 8.1 Implement alias-mutation coordination with kk-common `DistributedLockFactory`, bounded wait/lease and fail-closed Redis handling, then verify the exact expected source alias immediately before submission
- [x] 8.2 Persist a PENDING audit operation, execute one `_aliases` remove/add request, require acknowledgement and verify the unique target afterward
- [x] 8.3 Permit alias activation only from a completed switch-preparation run, mark the destination as selected, and retain every non-disabled version as a write target
- [x] 8.4 Implement selecting a prior enabled version with the same catch-up gates and atomic operation without pausing resource-event enqueueing or disabling the former read target
- [x] 8.5 Implement startup and scheduled reconciliation that treats the Elasticsearch alias as read-target truth and repairs interrupted database state with an audit entry
- [x] 8.6 Add fault-injection tests for rejection, timeout before commit, process failure after ES commit, concurrent admin requests and external alias mismatch

## 9. Control version enablement, retention and manual cleanup

- [x] 9.1 Implement explicit disable for a non-selected version that removes it from future target fan-out without deleting ES data and marks its switch readiness behind
- [x] 9.2 Implement re-enable as future-write enrollment followed by mandatory range/event catch-up and validation before the version can be selected
- [x] 9.3 Compute the default recent-two-version retention recommendation and expose older disabled versions as cleanup candidates without scheduling automatic deletion
- [x] 9.4 Implement physical-index deletion guards for alias targets, write-enabled versions, build/catch-up targets, non-exact names and versions with active jobs
- [x] 9.5 Require exact-name confirmation and an idempotency key for deletion, then record ES acknowledgement and final audit state
- [x] 9.6 Add tests proving selected, write-enabled, rebuilding/catching-up and malformed-name targets cannot be deleted and eligible deletion cannot affect the alias

## 10. Expose administrator APIs and observability

- [x] 10.1 Add `ROLE_ADMIN`-protected version, alias-truth, writable-target, run, validation and audit query endpoints under `/api/v1/admin/search-indexes/**`
- [x] 10.2 Add idempotent create/edit, per-version manual rebuild/rebuild-control, switch-prepare/select, validate, disable/re-enable and delete command endpoints with server-side state revalidation and `409 BUSY` responses carrying the active rebuild runId
- [x] 10.3 Return allowed actions and actionable sanitized errors while excluding credentials, source content and stack traces from all DTOs
- [x] 10.4 Emit structured logs and metrics for version/run IDs, config/built revisions, derived display status, rebuild-lock acquisition/contention/renewal, fixed ranges, tail/event cursors, barriers, resource/document counts, per-version submitted/succeeded/pending/retrying/failed multi-write targets, lag, embedding calls, latency and alias target
- [x] 10.5 Add controller/security tests for anonymous, non-admin and admin access plus stale confirmation and repeated idempotency requests

## 11. Build the vue-pure-admin console

- [x] 11.1 Create `admin-frontend/` from the pinned vue-pure-admin thin template and configure `/admin/` base routing, TypeScript checks and production output
- [x] 11.2 Reuse the existing JWT login/me APIs, implement admin-route guards and handle expired/insufficient-role sessions without relying on hidden buttons for security
- [x] 11.3 Build the index overview showing actual alias target, editable configuration, config/built revisions, exact `待重建 → 重建中 → 已重建 → 补齐中 → 已发布` label semantics, write-enabled/disabled state, health, ranges/cursors, backlog, validation age and backend-provided allowed actions
- [x] 11.4 Build auto-numbered version creation and eligible-version editing with compatibility/capacity preflight, dirty-state feedback and idempotent submission
- [x] 11.5 Build the per-version manual “重建” action plus durable fixed-range rebuild and switch-preparation monitoring with backoff polling, range/event progress, pause/resume/retry/cancel actions, BUSY/current-run handling and reload recovery
- [x] 11.6 Build catch-up statistics by range/event cursor and multi-write statistics by target version, including totals, successes, pending/retries/failures, lag, throughput, embedding calls, timestamps, elapsed time and ETA
- [x] 11.7 Build validation details that separate hard failures from diagnostics and require fresh source→destination confirmation before final alias activation
- [x] 11.8 Build version selection, disable/re-enable and exact-index-name deletion dialogs with consequences and refreshed backend truth after completion
- [x] 11.9 Build audit history with operator, action, target, prior/result states, timestamps and sanitized outcome
- [x] 11.10 Add component and route tests for role guards, exact status transitions, manual-rebuild availability, BUSY/current-run handling, statistics, polling recovery, confirmation flows and backend-rejected operations

## 12. Serve, verify and roll out the integrated administration surface

- [x] 12.1 Integrate the admin frontend production build into Spring Boot static resources under `/admin/` and add an SPA fallback that excludes `/api/**` and actuator paths
- [x] 12.2 Update Spring Security so admin static/login-shell assets load as intended while every management API remains protected by `ROLE_ADMIN`
- [x] 12.3 Add end-to-end tests for direct nested-route loading, login, create/edit→待重建→manual rebuild/重建中→已重建→switch prepare/补齐中→validate→activate/已发布, per-version catch-up and multi-write statistics, all-enabled-version writes, disable/re-enable and safe cleanup
- [x] 12.4 Run backend compile/tests, frontend/admin typecheck/tests/build, migration validation and Elasticsearch integration tests; record any external-service prerequisites
- [ ] 12.5 Execute a staging interruption drill covering worker restart, range/event cursor recovery, alias/database reconciliation, partial all-version write failure and switching back before enabling production mutations
- [x] 12.6 Document operator runbooks for application release versus admin configuration, dirty/rebuilt interpretation, fixed-range rebuild, switch preparation, hot selection, disabling/re-enabling, manual cleanup and incident recovery
