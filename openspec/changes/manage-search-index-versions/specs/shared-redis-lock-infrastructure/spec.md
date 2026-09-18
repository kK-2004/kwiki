## ADDED Requirements

### Requirement: Common Redis and Redisson are enabled by default
The production application configuration MUST default both `kk.common.redis.enabled` and `kk.common.redisson.enabled` to `true`, while retaining explicit environment-variable overrides. Runtime profiles and environment examples MUST require an explicit Redis host and MUST NOT silently fall back to `localhost:6379`.

#### Scenario: Start with production defaults and valid Redis configuration
- **WHEN** the application starts without either common feature-flag environment variable and with valid `KWIKI_REDIS_*` settings
- **THEN** kk-common provides `RedisUtil`, `RedissonClient`, and `DistributedLockFactory`, and Redis participates in readiness reporting

#### Scenario: Redis host is omitted
- **WHEN** Redis/Redisson remain enabled but no Redis host is provided
- **THEN** configuration or readiness fails explicitly without attempting an implicit localhost endpoint

#### Scenario: Operator explicitly disables the SDK integrations
- **WHEN** both feature flags are explicitly set to `false` for an approved isolated environment
- **THEN** the application does not create Redis or Redisson clients and features requiring a distributed lock remain disabled or fail closed

### Requirement: All Redis data access uses kk-common
Production business code MUST use kk-common `RedisUtil` and its SDK-managed template/serialization for Redis data access. It MUST NOT directly use Spring `RedisTemplate`/`StringRedisTemplate`, a Redisson data-structure API, custom Redis serialization, raw Redis commands, or a project-local Redis client abstraction.

#### Scenario: Authorization scope is cached
- **WHEN** an authorization scope is read, stored, or invalidated in Redis
- **THEN** the infrastructure adapter performs the operation through `RedisUtil` and safely falls back to the authoritative database on cache failure

#### Scenario: Wiki statistics cache is updated
- **WHEN** committed like, favorite, or comment statistics need a cache update
- **THEN** the service updates an SDK-serialized versioned statistics value inside an SDK-provided distributed lock without directly invoking hash, Lua, SETNX, or template operations

#### Scenario: Required atomic Redis operation is absent from the SDK
- **WHEN** a feature requires a Redis primitive not exposed by the current kk-common API
- **THEN** the primitive is first added to and versioned in kk-common or the feature uses existing SDK operations with database authority, rather than bypassing the SDK in KWiki

### Requirement: All application-level distributed locks use kk-common
Every cross-instance application lock MUST be acquired from kk-common `DistributedLockFactory`. Production business code MUST NOT use `RedissonClient` directly, implement SETNX locks, or execute MySQL `GET_LOCK`/`RELEASE_LOCK` as a distributed-lock substitute.

#### Scenario: Scheduled cleanup is triggered on multiple instances
- **WHEN** multiple instances trigger recycle-bin or comment cleanup concurrently
- **THEN** only the instance that obtains the namespaced SDK distributed lock executes that run and all others skip it safely

#### Scenario: Archive and indexing race on one resource
- **WHEN** archive/index lifecycle operations for the same resource contend across instances
- **THEN** they use the same deterministic SDK lock name and lifecycle fencing so at most one critical transition executes at a time

#### Scenario: Administrator switches the alias
- **WHEN** an administrator requests activation or rollback
- **THEN** the service obtains the SDK alias-management lock before final validation and never submits `_aliases` without that lock

#### Scenario: Database row consistency is required
- **WHEN** a transaction needs `SELECT ... FOR UPDATE`, a unique constraint, or optimistic lifecycle fencing for database correctness
- **THEN** it retains that database mechanism because transactional row locking is not treated as an application-level distributed-lock bypass

### Requirement: Distributed-lock handling is bounded and ownership-safe
SDK lock consumers MUST use names under `kwiki:lock:<purpose>[:resource]`, bounded acquisition waits, an explicit critical-section lease or a documented watchdog strategy, interruption propagation, and `finally` cleanup that unlocks only when the current thread owns the lock.

#### Scenario: Lock acquisition times out
- **WHEN** a consumer cannot acquire a lock within its configured wait time
- **THEN** it returns BUSY, skips the scheduled run, or retries according to the operation contract and does not enter the critical section

#### Scenario: Waiting thread is interrupted
- **WHEN** `tryLock` is interrupted
- **THEN** the consumer restores the interrupt flag, does not enter the critical section, and does not unlock a lock it never acquired

#### Scenario: Critical section fails
- **WHEN** an exception leaves a critical section while the current thread still owns the lock
- **THEN** the consumer unlocks in `finally` and preserves the original sanitized business failure

#### Scenario: Lease expires during long processing
- **WHEN** work can exceed the short distributed-lock lease
- **THEN** the lock protects only a bounded coordinator/state transition and persisted leases plus fencing prevent duplicate long-running work

### Requirement: Redis and lock failures follow explicit safety semantics
Redis caches MUST degrade to their authoritative database behavior without losing committed writes. Operations whose correctness depends on mutual exclusion MUST fail closed or skip the current run when `DistributedLockFactory` or Redis is unavailable; they MUST NOT continue without a lock.

#### Scenario: Cache Redis is unavailable
- **WHEN** a cache read, write, or invalidation receives a Redis connection failure
- **THEN** the request uses database truth, records a sanitized metric/log, and schedules repair where the cache contract requires it

#### Scenario: Alias lock service is unavailable
- **WHEN** Redis or Redisson is unavailable during an activation request
- **THEN** activation fails before any Elasticsearch alias mutation and the current read alias remains unchanged

#### Scenario: Cleanup lock service is unavailable
- **WHEN** a scheduled cleanup cannot create or acquire its SDK lock
- **THEN** that scheduled run is skipped and retryable observability is emitted without deleting data

### Requirement: Test configuration mirrors production intent without external Redis
Shared test fixtures MUST explicitly disable common Redis and Redisson for offline application contexts. Tests exercising caches or locks MUST inject mock/fake kk-common SDK interfaces, and dedicated context tests MUST validate enabled auto-configuration without making external network connections.

#### Scenario: Standard application test starts offline
- **WHEN** a test uses `StandardTestProperties`
- **THEN** it sets `kk.common.redis.enabled=false` and `kk.common.redisson.enabled=false` explicitly and starts without Redis/Redisson clients

#### Scenario: SDK-enabled context is tested
- **WHEN** a context test verifies the enabled Redis/Redisson configuration
- **THEN** it supplies fake connection/client beans, asserts `RedisUtil` and `DistributedLockFactory` presence, and performs no external connection

#### Scenario: Cache or lock unit test runs
- **WHEN** a unit test exercises Redis caching or a distributed critical section
- **THEN** it mocks the kk-common interface and verifies fallback, bounded acquisition, ownership-safe unlock, interruption, and failure behavior

### Requirement: Architecture tests prevent Redis and lock bypasses
The build MUST enforce an architecture rule or equivalent source audit that rejects direct production use of Spring Redis data templates, `RedissonClient`, custom Redis serializers, Redis SETNX lock patterns, and MySQL advisory-lock statements outside approved SDK auto-configuration integration code.

#### Scenario: Direct StringRedisTemplate usage is introduced
- **WHEN** production business code imports or calls `StringRedisTemplate`
- **THEN** the architecture test fails with guidance to use kk-common `RedisUtil`

#### Scenario: MySQL advisory lock is introduced
- **WHEN** production code contains `GET_LOCK` or `RELEASE_LOCK` for cross-instance coordination
- **THEN** the architecture test fails with guidance to use kk-common `DistributedLockFactory`

#### Scenario: Direct Redisson client is introduced
- **WHEN** a production consumer depends directly on `RedissonClient`
- **THEN** the architecture test fails while still permitting the kk-common auto-configuration to own the Redisson client

