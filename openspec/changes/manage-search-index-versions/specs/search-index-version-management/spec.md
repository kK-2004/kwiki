## ADDED Requirements

### Requirement: Content changes preserve the physical index generation
The system MUST treat a Wiki publication or other resource-content change as a resource indexing event and MUST NOT create a new physical index solely because content changed. Outside an index-upgrade session, the event MUST target the current writable physical index.

#### Scenario: Publish a new Wiki revision in stable operation
- **WHEN** an ACTIVE Wiki page publishes a new revision and no index upgrade session is active
- **THEN** the system enqueues an UPSERT for the current physical index and does not create or switch an index version

#### Scenario: Delete or archive content in stable operation
- **WHEN** an indexed resource becomes non-searchable and no index upgrade session is active
- **THEN** the system applies the lifecycle operation to the current physical index using the authoritative resource lifecycle version

#### Scenario: Structural support is deployed
- **WHEN** a release adds support for a new parser, chunker, embedding model, vector dimension, or mapping
- **THEN** startup preserves the current index configuration and read alias until an administrator explicitly saves, rebuilds, validates, and selects another version

### Requirement: Structural configuration uses auto-numbered editable versions
The backend MUST allocate the next positive version number and physical name when an administrator creates an index configuration. It MUST allow safe non-online versions to be edited, MUST increment `configRevision` on each change, and MUST derive “pending rebuild” from `configRevision != builtConfigRevision`. Version numbers and physical names MUST NOT be editable.

#### Scenario: Create v2 for a new embedding dimension
- **WHEN** v1 exists and an administrator saves a supported configuration whose vector dimension differs from v1
- **THEN** the backend atomically allocates v2, marks it pending rebuild, and leaves `kwiki-chunks` pointing to v1

#### Scenario: Edit v2 before rebuilding
- **WHEN** an administrator changes the embedding model of a non-active, non-writing, idle v2
- **THEN** the backend increments v2 `configRevision`, retains the v2 number, and reports pending rebuild

#### Scenario: Edit a previously built offline v2
- **WHEN** v2 has been built but is not the alias target, not write-enabled, and not running a build or catch-up
- **THEN** the edit is accepted, its prior physical content is treated as stale, and v2 becomes pending rebuild until rebuilt from the new configuration revision

#### Scenario: Attempt to edit an online version
- **WHEN** an administrator attempts to edit the alias target, a write-enabled version, or a version with an active build/catch-up run
- **THEN** the backend rejects in-place editing and offers creation of the next auto-numbered version

#### Scenario: Concurrent version creation
- **WHEN** two administrators create a version concurrently after v1
- **THEN** the database monotonic allocator and uniqueness constraints assign distinct increasing version numbers without overwriting or reusing a prior tombstoned version

### Requirement: Startup bootstraps or adopts v1 without automatic upgrades
The system MUST create and bind v1 only when no read alias exists. When an alias already exists, startup MUST preserve its target and reconcile it with persisted version metadata; startup MUST NOT automatically activate a higher configured version.

#### Scenario: First deployment has no alias
- **WHEN** the application starts and neither `kwiki-chunks` nor managed version metadata exists
- **THEN** it creates v1, validates its mapping, atomically adds `kwiki-chunks`, and records v1 as ACTIVE

#### Scenario: Restart after v2 activation
- **WHEN** the application starts while `kwiki-chunks` points to v2
- **THEN** it preserves v2 as the read target regardless of a bootstrap version default

#### Scenario: Existing alias cannot be safely adopted
- **WHEN** Elasticsearch has an alias target whose mapping cannot be matched to a supported manifest
- **THEN** normal reads remain on that alias but index-management mutations fail closed with NEEDS_ATTENTION

### Requirement: Searches use one stable read alias and writes use explicit targets
All production search paths MUST read only through `kwiki-chunks`. Index writes MUST name persisted physical targets explicitly and MUST NOT depend on the current alias target at worker execution time.

#### Scenario: Alias switches while an old job is waiting
- **WHEN** a resource event was enqueued for v1 and v2 before the read alias switched to v2
- **THEN** each target execution writes its recorded physical index and the switch does not retarget the queued work

#### Scenario: Search during rebuild
- **WHEN** v2 is REBUILDING while the alias still points to v1
- **THEN** BM25, vector, parent fetch, and citation lookup continue reading v1 only

### Requirement: Switch preparation enables all non-disabled versions for live writes
Starting switch preparation MUST atomically enable writes for every supported version that an administrator has not disabled or deleted. From that watermark onward, publication, update, delete, archive, and restore events MUST fan out to every enabled version, including after the read alias changes.

#### Scenario: New Wiki is published during v2 baseline rebuild
- **WHEN** v1 is the only write-enabled version and v2 is rebuilding its fixed baseline range before switch preparation
- **THEN** the live event continues to target v1 and is captured by the append-only change-event sequence for later v2 catch-up

#### Scenario: Switch preparation begins
- **WHEN** an administrator starts preparing a built v2 for selection while v1 and v2 are not disabled
- **THEN** the system records a dual-write watermark and makes all subsequent resource events target both versions before catch-up proceeds

#### Scenario: Three versions remain enabled
- **WHEN** v1, v2, and v3 all remain enabled after a switch
- **THEN** every new content and lifecycle event is persisted independently for all three physical targets

#### Scenario: Existing Wiki publishes a new revision after activation
- **WHEN** v2 is the read alias target and v1 remains enabled
- **THEN** the new revision is indexed into both v2 and v1 using each version's built configuration

#### Scenario: Resource is archived during rebuild
- **WHEN** a resource lifecycle version changes to non-searchable while v1 and v2 are writable
- **THEN** both targets receive a fenced lifecycle operation and no stale UPSERT may make the resource searchable again

#### Scenario: Rollback target write fails
- **WHEN** the current read target succeeds but another enabled version exhausts retries for an event
- **THEN** the content event remains successful for the current target, the other version is marked behind, and switching to it is blocked until repaired

#### Scenario: Administrator disables an old version
- **WHEN** an administrator disables non-current v1
- **THEN** future events stop targeting v1, its existing ES data remains, and v1 cannot be selected until re-enabled, caught up, and validated

### Requirement: Each target uses its successfully built configuration revision
The system MUST parse, chunk, embed, and validate documents according to the immutable configuration snapshot captured by the target's successful build. A target MUST NOT receive vectors whose dimension or model identity belongs to another target or an unbuilt edited revision.

#### Scenario: Dual-write across different vector dimensions
- **WHEN** v1 requires 1024-dimensional vectors and v2 requires a different supported dimension
- **THEN** the worker invokes the corresponding pipeline for each target and writes only dimension-compatible vectors

#### Scenario: Old pipeline is unavailable
- **WHEN** a selected or write-enabled version requires a parser, chunker, model, or credential unavailable to the running application
- **THEN** the system marks the version unsupported, blocks selecting it, and requires restoration of support or disabling that version

### Requirement: Baseline rebuild uses fixed ranges and switch catch-up closes every gap
The system MUST record per-resource-type ID bounds and a starting change-event ID before a baseline rebuild, and MUST scan only that fixed resource range. Switch preparation MUST run range-tail scanning and append-only change-event replay in parallel with live all-version writes. Alias activation MUST wait until all three paths converge at a persisted barrier. Rebuild and catch-up coordination MUST use leases and resume safely after failure.

#### Scenario: Rebuild scans effective resources
- **WHEN** an administrator starts a v2 rebuild
- **THEN** the run represents the chunks workload as fixed per-source-resource ID bounds, captures each type's current maximum ID and change-event ID, then scans ACTIVE published pages and eligible stored resources only within those bounds

#### Scenario: New resource arrives beyond the captured range
- **WHEN** a page with an ID above the v2 baseline maximum is created before switch preparation
- **THEN** switch catch-up scans forward from the saved range endpoint and indexes the page before v2 can be selected

#### Scenario: Existing resource changes inside the captured range
- **WHEN** a page below the baseline maximum publishes, archives, restores, or deletes content after its baseline row is processed
- **THEN** ordered change-event replay applies that lifecycle/content event to v2 even though its resource ID is below the range-tail cursor

#### Scenario: Content changes after its scan row was read
- **WHEN** a scanned resource changes before its baseline write completes
- **THEN** revision and lifecycle fencing prevent the stale baseline from replacing the newer target state

#### Scenario: Worker stops midway
- **WHEN** the rebuild lease expires after the application stops at a persisted cursor
- **THEN** another worker resumes from a safe cursor without treating duplicate target writes as new content

#### Scenario: Catch-up runs beside live all-version writes
- **WHEN** switch preparation enables v2 writes and starts tail/event catch-up
- **THEN** new events after the dual-write watermark target v2 directly while the catch-up workers replay only the earlier uncovered interval idempotently

#### Scenario: Catch-up has a pending interval
- **WHEN** baseline scanning is complete but range-tail, event replay, or target jobs at or below the barrier remain pending
- **THEN** the switch remains PREPARING and the read alias does not move to v2

### Requirement: Initial and manual rebuilds share one per-version distributed lock
The system MUST expose a manual full-rebuild action for eligible versions. Initial full builds and later manual rebuilds MUST enter the same coordinator and MUST acquire the same kk-common `DistributedLockFactory` key `kwiki:lock:index-rebuild:v{version}` before creating or resuming work. A version MUST have at most one active rebuild run, enforced by the distributed lock plus a persistent uniqueness/lease/fencing guard.

#### Scenario: Manually rebuild an unchanged offline version
- **WHEN** an administrator requests a rebuild for a built, non-selected, write-disabled, idle and supported v2 without changing its configuration
- **THEN** the system acquires the v2 rebuild lock, creates a new build generation with fixed ranges and immutable snapshot, and performs a complete rebuild without changing the read alias

#### Scenario: Initial build and manual rebuild race
- **WHEN** one request is already rebuilding v2 and another initial or manual rebuild request targets v2
- **THEN** the second request receives BUSY with the active runId and no second active run or Elasticsearch rebuild starts

#### Scenario: Different versions rebuild concurrently
- **WHEN** v2 and v3 each acquire their distinct version lock and global resource limits allow both
- **THEN** their rebuilds may proceed concurrently without sharing cursors or physical targets

#### Scenario: Rebuild process crashes
- **WHEN** the coordinator process exits while holding the v2 rebuild lock
- **THEN** another instance waits for lock and database lease expiry, acquires the same v2 lock, and resumes the fenced original run instead of creating a second active run

#### Scenario: Attempt to rebuild an online version in place
- **WHEN** an administrator requests a manual rebuild of the read-alias target, a write-enabled version, or a version participating in catch-up/switch preparation
- **THEN** the backend rejects the request before mutating Elasticsearch and explains the required safe transition

### Requirement: Validation gates activation and rollback
The system MUST persist a validation report and MUST block selection unless the version is not dirty and mapping, vector dimensions, built-configuration identity, current-resource coverage, lifecycle/revision currency, range-tail position, change-event watermark, live target-job barrier, parent-child integrity, and bounded query checks pass. Raw chunk-count equality between different configurations MUST NOT be required.

#### Scenario: Version configuration is dirty
- **WHEN** v2 `configRevision` differs from `builtConfigRevision`
- **THEN** validation reports pending rebuild and selection of v2 remains unavailable

#### Scenario: Parser change alters chunk counts
- **WHEN** v2 has complete current-resource coverage and valid structure but a different chunk count from v1 because of its parser/chunker
- **THEN** count differences are reported diagnostically and do not alone fail validation

#### Scenario: Current resource is missing
- **WHEN** an ACTIVE published resource has no current representation in v2
- **THEN** validation fails, identifies the missing resource without exposing its content, and activation remains unavailable

#### Scenario: Vector dimension is inconsistent
- **WHEN** any sampled or counted child vector conflicts with the v2 manifest or mapping dimension
- **THEN** validation fails and the read alias remains unchanged

#### Scenario: Failed target jobs exist before the barrier
- **WHEN** v2 has an unresolved FAILED target job at or below the activation barrier
- **THEN** validation cannot mark v2 READY

#### Scenario: Resource tail is caught up but an old-ID event is not
- **WHEN** the range cursor reaches its current upper bound but change-event replay has not reached the dual-write watermark
- **THEN** validation fails the catch-up gate and the alias remains on v1

### Requirement: Alias activation and rollback are atomic and reconciled
The system MUST serialize application-managed alias mutations and use one Elasticsearch `_aliases` request to remove the old `kwiki-chunks` target and add the new target. Elasticsearch alias state MUST be reconciled with database state after success, timeout, or process interruption.

#### Scenario: Activate a ready v2
- **WHEN** v1 is the expected alias target and switch preparation has produced a current validation report for caught-up v2
- **THEN** one atomic alias operation changes `kwiki-chunks` from v1 to v2 while every non-disabled version remains write-enabled

#### Scenario: Alias operation fails
- **WHEN** Elasticsearch rejects or does not acknowledge activation
- **THEN** the system reports failure, does not claim v2 is selected, and verifies that queries remain on the prior alias target

#### Scenario: Process exits after Elasticsearch commits
- **WHEN** the alias points to v2 but the database operation remains PENDING after restart
- **THEN** reconciliation observes the actual alias, completes consistent version states, and records a recovery audit event

#### Scenario: Hot rollback is requested
- **WHEN** v1 remains enabled, supported, caught up, and validated while v2 is selected
- **THEN** the same atomic operation switches the alias to v1 without pausing content publication or disabling v2 writes

### Requirement: Version disabling and deletion are explicit and safe
The system MUST keep every non-disabled version write-enabled after switch preparation and MUST recommend retaining the current and most recent alternative versions. It MUST NOT automatically disable or delete physical indexes. Disabling writes and deleting an index MUST be separate administrator actions.

#### Scenario: Disable writes to v1
- **WHEN** an administrator confirms v2 is stable and disables non-current v1
- **THEN** v1 leaves the writable target set, keeps its ES data, and the UI states that immediate switching to v1 is no longer available

#### Scenario: Re-enable v1
- **WHEN** an administrator re-enables previously disabled v1
- **THEN** v1 joins future writes but remains unselectable until its missed range/events are caught up and a new validation passes

#### Scenario: Delete an eligible old index
- **WHEN** an administrator explicitly confirms the exact name of a disabled index that is not aliased, building, catching up, or write-enabled
- **THEN** the system deletes only that physical index and records the outcome

#### Scenario: Attempt to delete the active index
- **WHEN** an administrator requests deletion of the current alias target
- **THEN** the system rejects the request without issuing an Elasticsearch delete

#### Scenario: More than two versions exist
- **WHEN** v3 is selected, v2 remains enabled, and v1 is disabled
- **THEN** the system recommends v1 for manual cleanup but does not delete it automatically

### Requirement: Index operations are observable and bounded
The system MUST expose config/built revisions, derived dirty state, fixed rebuild ranges, range-tail and change-event cursors, live-write barriers, and sanitized failures for rebuild, catch-up, validation, activation, rollback, disabling, re-enabling, and deletion, and MUST apply configurable batch, interval, concurrency, retry, model-rate, and capacity limits.

#### Scenario: Administrator reloads during a rebuild
- **WHEN** the management page reloads while a run is active
- **THEN** the API reconstructs its current phase, cursor, counts, backlog, timings, and safe actions from persisted state

#### Scenario: Elasticsearch capacity is unsafe
- **WHEN** the configured capacity guard cannot confirm sufficient health or headroom for another index
- **THEN** creation fails closed before starting the rebuild and records a sanitized reason

#### Scenario: Rebuild is paused
- **WHEN** an administrator pauses a running rebuild
- **THEN** no new baseline batches start, already committed target work remains intact, the existing write-target set is unchanged, and the read alias is unchanged

#### Scenario: Switch catch-up is paused
- **WHEN** an administrator pauses range/event catch-up after switch preparation enabled all-version writes
- **THEN** no new catch-up batches start, live writes to every enabled version continue, and the read alias remains on its current version
