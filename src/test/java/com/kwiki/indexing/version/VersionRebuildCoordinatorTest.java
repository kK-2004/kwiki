package com.kwiki.indexing.version;

import com.kk2004.common.lock.DistributedLock;
import com.kk2004.common.lock.DistributedLockFactory;
import com.kwiki.infrastructure.redis.KwikiDistributedLocks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Semaphore;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class VersionRebuildCoordinatorTest {
    private VersionRebuildCoordinator coordinator;

    @AfterEach
    void closeExecutor() {
        if (coordinator != null) coordinator.shutdown();
    }

    @Test
    void initialAndManualBuildsUseTheSameVersionLockAndOnlyOneRuns() throws Exception {
        FakeLock lock = new FakeLock();
        RecordingFactory factory = new RecordingFactory(lock);
        FakeRegistry registry = new FakeRegistry(
                RebuildRunRegistry.Claim.accepted(persistedRun(41), false));
        coordinator = new VersionRebuildCoordinator(
                new KwikiDistributedLocks(
                        com.kwiki.testutil.StandardTestProperties.providerOf(factory)),
                registry, Executors.newFixedThreadPool(2));

        CountDownLatch workStarted = new CountDownLatch(1);
        CountDownLatch releaseWork = new CountDownLatch(1);
        var first = coordinator.startInitial(request(RebuildRunKind.INITIAL), ignored -> {
            workStarted.countDown();
            releaseWork.await(5, TimeUnit.SECONDS);
        });
        assertThat(workStarted.await(2, TimeUnit.SECONDS)).isTrue();
        var second = coordinator.startManual(request(RebuildRunKind.MANUAL), ignored -> { });

        assertThat(first.accepted()).isTrue();
        assertThat(first.runId()).isEqualTo(41L);
        assertThat(second.accepted()).isFalse();
        assertThat(second.runId()).isEqualTo(41L);
        assertThat(factory.names).containsExactly(
                "kwiki:lock:index-rebuild:v2", "kwiki:lock:index-rebuild:v2");

        releaseWork.countDown();
        coordinator.shutdown();
        coordinator = null;
        assertThat(registry.completedRunId).isEqualTo(41L);
        assertThat(registry.completedOwner).isNotBlank();
        assertThat(lock.unlocks.get()).isEqualTo(1);
        assertThat(lock.watchdogAcquisitions.get()).isEqualTo(2);
    }

    @Test
    void databaseBusyRunIsReturnedWithoutExecutingWork() {
        FakeLock lock = new FakeLock();
        FakeRegistry registry = new FakeRegistry(
                RebuildRunRegistry.Claim.busy(persistedRun(77)));
        coordinator = new VersionRebuildCoordinator(
                new KwikiDistributedLocks(
                        com.kwiki.testutil.StandardTestProperties.providerOf(
                                new RecordingFactory(lock))),
                registry, Executors.newSingleThreadExecutor());
        AtomicBoolean executed = new AtomicBoolean();

        var result = coordinator.startManual(request(RebuildRunKind.MANUAL),
                ignored -> executed.set(true));

        assertThat(result).isEqualTo(new VersionRebuildCoordinator.StartResult(
                false, 77L, false, "BUSY"));
        assertThat(executed).isFalse();
        assertThat(lock.unlocks.get()).isEqualTo(1);
    }

    @Test
    void differentVersionsRespectTheConfiguredGlobalConcurrencyLimit() throws Exception {
        FakeLock lock = new FakeLock();
        FakeRegistry registry = new FakeRegistry(
                RebuildRunRegistry.Claim.accepted(persistedRun(51), false));
        coordinator = new VersionRebuildCoordinator(
                new KwikiDistributedLocks(
                        com.kwiki.testutil.StandardTestProperties.providerOf(
                                new RecordingFactory(lock))),
                registry, Executors.newFixedThreadPool(2), new Semaphore(1));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        var first = coordinator.startInitial(request(2, RebuildRunKind.INITIAL), ignored -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
        });
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        var second = coordinator.startInitial(request(3, RebuildRunKind.INITIAL), ignored -> { });

        assertThat(first.accepted()).isTrue();
        assertThat(second.accepted()).isFalse();
        release.countDown();
    }

    @Test
    void expiredRunRecoveryResumesTheOriginalFencedRunInsteadOfCreatingAnother() {
        FakeLock lock = new FakeLock();
        FakeRegistry registry = new FakeRegistry(
                RebuildRunRegistry.Claim.accepted(persistedRun(88), true));
        coordinator = new VersionRebuildCoordinator(
                new KwikiDistributedLocks(
                        com.kwiki.testutil.StandardTestProperties.providerOf(
                                new RecordingFactory(lock))),
                registry, Executors.newSingleThreadExecutor());

        var result = coordinator.startInitial(request(RebuildRunKind.INITIAL), ignored -> { });

        assertThat(result.accepted()).isTrue();
        assertThat(result.resumed()).isTrue();
        assertThat(result.runId()).isEqualTo(88L);
    }

    private static SearchIndexRebuildRun persistedRun(long id) {
        SearchIndexRebuildRun run = SearchIndexRebuildRun.create(2, 1,
                RebuildRunKind.INITIAL, 3, "{}", "admin", "owner",
                Duration.ofMinutes(2), 0, java.time.Instant.EPOCH);
        ReflectionTestUtils.setField(run, "id", id);
        return run;
    }

    private static VersionRebuildCoordinator.Request request(RebuildRunKind kind) {
        return request(2, kind);
    }

    private static VersionRebuildCoordinator.Request request(int version, RebuildRunKind kind) {
        return new VersionRebuildCoordinator.Request(version, kind, "admin");
    }

    private static final class FakeRegistry implements RebuildRunRegistry {
        private final Claim claim;
        private Long completedRunId;
        private String completedOwner;

        private FakeRegistry(Claim claim) { this.claim = claim; }
        @Override public Claim claimOrCreate(VersionRebuildCoordinator.Request request,
                                             String owner, Duration leaseDuration) {
            return claim;
        }
        @Override public void complete(long runId, String owner) {
            completedRunId = runId;
            completedOwner = owner;
        }
        @Override public void fail(long runId, String owner, Throwable failure) { }
        @Override public void cancel(long runId, String owner) { }
        @Override public Optional<Long> activeRunId(int versionNumber) {
            return claim.run() == null ? Optional.empty() : Optional.ofNullable(claim.run().getId());
        }
    }

    private static final class RecordingFactory implements DistributedLockFactory {
        private final FakeLock lock;
        private final java.util.List<String> names =
                new java.util.concurrent.CopyOnWriteArrayList<>();

        private RecordingFactory(FakeLock lock) { this.lock = lock; }
        @Override public DistributedLock getDistributedLock(String name) {
            names.add(name);
            return lock;
        }
    }

    private static final class FakeLock implements DistributedLock {
        private final AtomicBoolean held = new AtomicBoolean();
        private final AtomicInteger unlocks = new AtomicInteger();
        private final AtomicInteger watchdogAcquisitions = new AtomicInteger();
        private volatile Thread owner;

        @Override public boolean tryLock(long waitTime, TimeUnit unit) {
            watchdogAcquisitions.incrementAndGet();
            boolean acquired = held.compareAndSet(false, true);
            if (acquired) owner = Thread.currentThread();
            return acquired;
        }
        @Override public boolean tryLock(long waitTime, long leaseTime, TimeUnit unit) {
            return tryLock(waitTime, unit);
        }
        @Override public boolean tryLock() { return tryLock(0, TimeUnit.MILLISECONDS); }
        @Override public void lock(long leaseTime, TimeUnit unit) {
            if (!tryLock(0, unit)) throw new IllegalStateException("busy");
        }
        @Override public void unlock() {
            if (isHeldByCurrentThread()) {
                owner = null;
                held.set(false);
                unlocks.incrementAndGet();
            }
        }
        @Override public boolean isLocked() { return held.get(); }
        @Override public boolean isHeldByThread(long threadId) {
            return owner != null && owner.threadId() == threadId;
        }
        @Override public boolean isHeldByCurrentThread() {
            return owner == Thread.currentThread();
        }
    }
}
