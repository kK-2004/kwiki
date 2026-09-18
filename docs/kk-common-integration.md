# kk-common SDK integration guide (kwiki consumer)

kwiki consumes the shared `kk-common` toolkit for cross-project concerns instead of
maintaining its own: the `TransDTO` response envelope, `BusinessException` +
shared exception handling, the `RedisUtil` cache-aside helper and the Redisson-backed
`DistributedLock` factory.

- **Baseline**: Spring Boot 3.5.6+ (kwiki parent = 3.5.6), Java 21.
- **Artifact**: `com.kK-2004:kk-common:0.1.2`, resolved from GitHub Packages.
- **Credentials**: never committed; see [kk-common-maven-settings.md](kk-common-maven-settings.md).

## 1. Maven setup

```xml
<properties>
  <kk-common.version>0.1.2</kk-common.version>
</properties>

<repositories>
  <repository>
    <id>github</id>
    <name>GitHub kK-2004/kk-common Packages</name>
    <url>https://maven.pkg.github.com/kK-2004/kk-common</url>
  </repository>
  <repository>
    <id>github-kfile</id>
    <name>GitHub kK-2004/kFile Packages</name>
    <url>https://maven.pkg.github.com/kK-2004/kFile</url>
  </repository>
</repositories>

<dependencies>
  <dependency>
    <groupId>com.kK-2004</groupId>
    <artifactId>kk-common</artifactId>
    <version>${kk-common.version}</version>
  </dependency>
</dependencies>
```

Both repository ids need a matching `<server>` entry in `~/.m2/settings.xml`; the
token needs at least **`read:packages`**. Template and troubleshooting:
[kk-common-maven-settings.md](kk-common-maven-settings.md).

## 2. Feature switches

The SDK ships four `kk.common.*.enabled` switches (kwiki maps them to
`KK_COMMON_*` environment variables in `application.yml`):

```yaml
kk:
  common:
    web:
      enabled: ${KK_COMMON_WEB_ENABLED:true}       # RequestContextFilter + TaskDecorator
    exception:
      enabled: ${KK_COMMON_EXCEPTION_ENABLED:true} # GlobalExceptionHandler
    redis:
      enabled: ${KK_COMMON_REDIS_ENABLED:true}     # RedisUtil + kkRedisTemplate
    redisson:
      enabled: ${KK_COMMON_REDISSON_ENABLED:true}  # RedissonClient + DistributedLockFactory
```

Defaults (the SDK's own conditions enforce the same values when keys are omitted):

| Switch | kwiki default | Effect when off |
| --- | --- | --- |
| `kk.common.web.enabled` | `true` | no SDK request-context filter/decorator beans |
| `kk.common.exception.enabled` | `true` | no shared `GlobalExceptionHandler` |
| `kk.common.redis.enabled` | `true` | no `RedisUtil`; the whole Redis stack (Spring connection factory included) stays unconfigured and disconnected |
| `kk.common.redisson.enabled` | `true` | no `RedissonClient`, no lock factory — and still **no connection attempt to `localhost:6379`** |

Redis and Redisson are **formal runtime dependencies**: kwiki defaults both switches to
`true`, so a deployment must provide the connection values (`KWIKI_REDIS_HOST` is
required with no fallback; Redisson derives its endpoint from
`spring.data.redis.host/port`). Consequences of the defaults:

- `kk.common.redisson.enabled=true` builds the `RedissonClient` eagerly at startup —
  an unreachable Redis fails the boot (fail fast, by design).
- `kk.common.redis.enabled=true` re-imports the Spring Redis auto-configurations
  through `KwikiRedisConfiguration`; the kwiki Redis health indicator and Boot's own
  `redis` indicator both participate in the readiness group, so a Redis outage drains
  the instance while caches keep degrading to the database.
- Explicitly setting either flag to `false` remains supported for an approved isolated
  environment; features that require a distributed lock then fail closed or skip their
  scheduled run.

The two Redis switches stay independent: Redisson does not require
`kk.common.redis.enabled=true` (it builds its own client from `RedisProperties`).

## 3. HTTP contract

### Success envelope

Applicable JSON endpoints wrap payloads in the shared envelope:

```java
import com.kk2004.common.response.TransDTO;

@GetMapping("/{kbId}")
TransDTO<KnowledgeBaseView> get(...) {
    return TransDTO.success(toView(knowledgeBases.requireAccessible(user, kbId)));
}
```

Wire format (golden-tested in `CommonResponseContractTest`):

```json
{"code":200, "success":true, "message":"OK", "data":{"name":"Engineering Wiki"}}
```

**Intentional breaking change** (see `openspec/changes/integrate-kk-common-sdk/notes/api-inventory.md`):
clients must read `$.data.<field>` instead of `$.<field>`. The SSE chat stream is
deliberately not wrapped.

### Business failures

Throw the SDK exception types; the SDK's auto-configured `GlobalExceptionHandler`
renders them — HTTP stays `200` and the code lives in the body:

```java
import com.kk2004.common.exception.BusinessException;
import com.kk2004.common.exception.NotFoundException;

throw new NotFoundException("knowledge base not found");   // {"code":404,"success":false,...}
throw new BusinessException(409, "not_retryable");          // explicit code
```

kwiki's `ConflictException extends BusinessException(409, …)` keeps the optimistic
-conflict semantics; not-found / forbidden / conflict remain distinguishable through
the body `code`. `BusinessException` skips stack-trace capture (`fillInStackTrace`
returns itself), and messages never echo secrets or provider details.

Handlers are not duplicated: the shared handler owns not-found, conflict, validation
(body code `422`), unreadable bodies and the unknown-exception fallback. The app-owned
`ApiControllerAdvice` keeps only what the SDK does not cover — Spring Security
`AccessDeniedException` (HTTP 403) and `IllegalArgumentException` (HTTP 400) — both
rendered in the same envelope. Filter-level 401/403 (`RestAuthenticationEntryPoint`,
`RestAccessDeniedHandler`) keep their own sanitized bodies and are never wrapped.

## 4. Redis caching (`RedisUtil`)

Only the SDK's serialization and cache-aside mechanics are used — no second JSON
strategy. kwiki keeps exactly one thin adapter, `ScopeCache`:

```java
AuthorizationScope scope = scopeCache.getOrLoad(userId, this::loadFromDatabase);
// → RedisUtil.queryWithPassThrough("kwiki:scope:v1:user:", userId,
//        CachedScope.class, loader, ttlSeconds)
scopeCache.invalidate(userId);   // exact-key delete on membership changes
```

Safety invariants (enforced by `ArchitectureRulesTest`):

- values are typed and stored with an explicit TTL through the SDK's
  `kkRedisTemplate` (`GenericJackson2JsonRedisSerializer`, String keys);
- every failure — feature off, connection refused, corrupted payload — degrades to
  the database loader; correctness never depends on Redis;
- prefix invalidation uses `RedisUtil.deleteByPrefix` (SCAN-based);
  Redis `KEYS *` and Jackson default-typing activation are forbidden by test;
- List-shaped values use `RedisUtil.setListAsJson` / `getListFromJson`
  (String + JSON), never sentinel entries inside a Redis list.

## 5. Distributed locks (`DistributedLock`)

Production code has no critical section today, so the contract lives in
`DistributedLockContractTest` (the `runWithLock` adapter doubles as the reference
example). Any future caller must follow this shape:

```java
DistributedLock lock = locks.getDistributedLock("kwiki:lock:example"); // locks = DistributedLockFactory
boolean acquired = false;
try {
    acquired = lock.tryLock(250, TimeUnit.MILLISECONDS); // bounded wait
    if (!acquired) {
        return Optional.empty();        // documented busy path; no unlock
    }
    return doWork();                    // critical section
} catch (InterruptedException e) {
    Thread.currentThread().interrupt(); // restore the cancel signal
    return Optional.empty();
} finally {
    if (acquired) {
        lock.unlock();                  // only unlock what was acquired
    }
}
```

Hand-written SETNX locks or other legacy lock implementations are prohibited.

## 6. Auto-configuration conventions (Spring Boot 3)

The SDK registers through the standard Boot 3 mechanism
(`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`):

```
com.kk2004.common.autoconfigure.CommonBoot3AutoConfiguration
com.kk2004.common.autoconfigure.RedisToolkitAutoConfiguration
com.kk2004.common.autoconfigure.RedissonLockAutoConfiguration
```

Consumers must not add legacy `spring.factories` entries, custom `@Import`s or
`@ComponentScan` overrides for the SDK — no kwiki code or resource does
(enforced by review and the audit in this change).

**Boot 3.5 note**: `WebMvcAutoConfigurationAdapter` registers spring-web's
`RequestContextFilter` under the same bean name the SDK uses, and its type-based
condition cannot see the SDK's filter type. `CommonSdkWebCompatibilityConfiguration`
declares spring-web's filter first (identical to Boot's own definition) so Boot's
conditional bean backs off and the SDK filter claims the name. Remove this shim once
the SDK resolves the name collision natively.

## 7. Package/type confinement

`com.kk2004.common.*` types may only appear in:

- `com.kwiki.infrastructure.redis..` — `RedisUtil` adapter (`ScopeCache`)
- `com.kwiki.wiki.api..` — `TransDTO`/`BusinessException` at the HTTP boundary
- `com.kwiki.security..` — login boundary (`TransDTO`)
- `com.kwiki.rag.answer..` — citation endpoint (`TransDTO`)

Domain, retrieval, indexing and persistence packages stay SDK-free
(`ArchitectureRulesTest.kkCommonSdkIsConfinedToApprovedPackages`).

## 8. Deployment & rollback notes (Redis / Redisson as default dependencies)

**Release.** Redis and Redisson are enabled by default; every environment must set,
before rollout:

- the full connection set: `KWIKI_REDIS_HOST` (required, no fallback), plus
  `KWIKI_REDIS_PORT` / `KWIKI_REDIS_PASSWORD` / `KWIKI_REDIS_DATABASE` as needed;
  Redisson derives its endpoint from the same `spring.data.redis.host/port`.
- nothing else — omitting `KK_COMMON_REDIS_ENABLED` / `KK_COMMON_REDISSON_ENABLED`
  keeps both integrations on.

Readiness expectations: the kwiki Redis health indicator and Boot's built-in `redis`
indicator both participate in readiness, so a Redis outage drains the instance while
caches keep degrading to their database loaders. Redisson connects eagerly: a Redis
that is unreachable at boot fails startup explicitly (there is no localhost fallback
and no lazy retry). Operations that require a distributed lock (scheduled cleanups,
index rebuild coordination, alias switching) fail closed or skip the run while Redis
is unavailable.

**Rollback.** To decouple a rollback from middleware availability, disable the
integrations first (`KK_COMMON_REDIS_ENABLED=false`, `KK_COMMON_REDISSON_ENABLED=false`):
the context boots with no Redis/Redisson beans and reads no `spring.data.redis.*`
value, so no middleware outage can block a rollback. Correctness during rollback
comes from the database (scope resolution is cache-or-DB by contract); lock-protected
features must stay out of the rollback path while disabled. To roll the SDK
integration back entirely, revert the dependency and config changes together with the
pre-migration local adapters in a coordinated release; never commit package
credentials as part of any rollback artifact.

**Upgrade checks for newer kk-common builds** (see
`openspec/changes/integrate-kk-common-sdk/notes/sdk-integration-findings.md`):
re-verify the Boot 3.5 `requestContextFilter` name collision shim, the
`X-Trace-Id` filter ordering shim, and the `@Order(MIN_VALUE)` advice precedence.
