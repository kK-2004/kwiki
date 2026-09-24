package com.kwiki.indexing.version;

import com.kwiki.infrastructure.redis.KwikiDistributedLocks;
import com.kwiki.indexing.config.IndexingProperties;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.Semaphore;

/** 同一版本的首次构建与手动重建共用的长任务互斥入口。 */
@Service
public class VersionRebuildCoordinator {

    private static final Logger log = LoggerFactory.getLogger(VersionRebuildCoordinator.class);
    private static final Duration LOCK_WAIT = Duration.ofMillis(500);
    private static final Duration RUN_LEASE = Duration.ofMinutes(2);
    private static final Duration ADMISSION_TIMEOUT = Duration.ofSeconds(5);

    private final KwikiDistributedLocks locks;
    private final RebuildRunRegistry runs;
    private final ExecutorService executor;
    private final Semaphore globalSlots;

    @Autowired
    public VersionRebuildCoordinator(KwikiDistributedLocks locks, RebuildRunRegistry runs,
                                     IndexingProperties properties) {
        this(locks, runs, Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("index-rebuild-coordinator-", 0).factory()),
                new Semaphore(properties.rebuild().maxConcurrentRuns()));
    }

    VersionRebuildCoordinator(KwikiDistributedLocks locks, RebuildRunRegistry runs,
                              ExecutorService executor) {
        this(locks, runs, executor, new Semaphore(Integer.MAX_VALUE));
    }

    VersionRebuildCoordinator(KwikiDistributedLocks locks, RebuildRunRegistry runs,
                              ExecutorService executor, Semaphore globalSlots) {
        this.locks = locks;
        this.runs = runs;
        this.executor = executor;
        this.globalSlots = globalSlots;
    }

    public StartResult startInitial(Request request, RebuildWork work) {
        return start(requireKind(request, RebuildRunKind.INITIAL), work);
    }

    public StartResult startManual(Request request, RebuildWork work) {
        return start(requireKind(request, RebuildRunKind.MANUAL), work);
    }

    private StartResult start(Request request, RebuildWork work) {
        CompletableFuture<StartResult> admission = new CompletableFuture<>();
        executor.execute(() -> coordinate(request, work, admission));
        try {
            return admission.get(ADMISSION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("rebuild admission interrupted", interrupted);
        } catch (ExecutionException failure) {
            throw new IllegalStateException("rebuild admission failed", failure.getCause());
        } catch (TimeoutException timeout) {
            throw new IllegalStateException("rebuild admission timed out", timeout);
        }
    }

    private void coordinate(Request request, RebuildWork work,
                            CompletableFuture<StartResult> admission) {
        if (!globalSlots.tryAcquire()) {
            log.info("index rebuild admission contended version={} scope=global", request.versionNumber());
            admission.complete(StartResult.busy(runs.activeRunId(request.versionNumber())
                    .orElse(null)));
            return;
        }
        try {
            coordinateWithVersionLock(request, work, admission);
        } finally {
            globalSlots.release();
        }
    }

    private void coordinateWithVersionLock(Request request, RebuildWork work,
                                           CompletableFuture<StartResult> admission) {
        String lockResource = "index-rebuild:v" + request.versionNumber();
        AutoCloseable held = locks.acquire(lockResource, LOCK_WAIT);
        if (held == null) {
            log.info("index rebuild lock contended version={} lock={}",request.versionNumber(),lockResource);
            admission.complete(StartResult.busy(
                    runs.activeRunId(request.versionNumber()).orElse(null)));
            return;
        }

        SearchIndexRebuildRun run = null;
        String owner = UUID.randomUUID().toString();
        try (held) {
            log.info("index rebuild lock acquired version={} lock={}",request.versionNumber(),lockResource);
            RebuildRunRegistry.Claim claim = runs.claimOrCreate(request, owner, RUN_LEASE);
            run = claim.run();
            if (!claim.accepted()) {
                admission.complete(StartResult.busy(run.getId()));
                return;
            }
            admission.complete(StartResult.accepted(run.getId(), claim.resumed()));
            log.info("index rebuild started version={} runId={} generation={} configRevision={} resumed={}",
                    request.versionNumber(),run.getId(),run.getBuildGeneration(),run.getConfigRevision(),claim.resumed());
            work.execute(run);
            runs.complete(run.getId(), owner);
            log.info("index rebuild completed version={} runId={} scanned={} succeeded={} skipped={} failed={}",
                    request.versionNumber(),run.getId(),run.getResourcesScanned(),run.getResourcesSucceeded(),
                    run.getResourcesSkipped(),run.getResourcesFailed());
        } catch (RebuildCancelledException cancelled) {
            if (run != null) runs.cancel(run.getId(), owner);
            admission.completeExceptionally(cancelled);
        } catch (Throwable failure) {
            if (run != null) {
                runs.fail(run.getId(), owner, failure);
            }
            admission.completeExceptionally(failure);
            log.error("index rebuild failed (version={}, runId={})",
                    request.versionNumber(), run == null ? null : run.getId(), failure);
        }
    }

    private static Request requireKind(Request request, RebuildRunKind expected) {
        Objects.requireNonNull(request, "request");
        if (request.kind() != expected) {
            throw new IllegalArgumentException("expected rebuild kind " + expected);
        }
        return request;
    }

    @PreDestroy
    void shutdown() {
        executor.close();
    }

    public record Request(int versionNumber, RebuildRunKind kind, String requestedBy) {
        public Request {
            if (versionNumber < 1) {
                throw new IllegalArgumentException("version must be positive");
            }
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(requestedBy, "requestedBy");
        }
    }

    public record StartResult(boolean accepted, Long runId, boolean resumed, String code) {
        static StartResult accepted(long runId, boolean resumed) {
            return new StartResult(true, runId, resumed, "ACCEPTED");
        }

        static StartResult busy(Long runId) {
            return new StartResult(false, runId, false, "BUSY");
        }
    }

    @FunctionalInterface
    public interface RebuildWork {
        void execute(SearchIndexRebuildRun run) throws Exception;
    }
}
