package com.kwiki.wiki.access;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ScopeVersionServiceTest {

    private static ScopeVersionService inMemoryService() {
        ObjectProvider<org.springframework.jdbc.core.JdbcOperations> provider =
                new ObjectProvider<>() {
                    @Override
                    public org.springframework.jdbc.core.JdbcOperations getIfAvailable() {
                        return null;
                    }
                };
        return new ScopeVersionService(provider);
    }

    @Test
    void versionStartsAtOneAndBumpsMonotonically() {
        ScopeVersionService service = inMemoryService();
        assertThat(service.current(1L)).isEqualTo(1L);
        assertThat(service.bump(1L)).isEqualTo(2L);
        assertThat(service.bump(1L)).isEqualTo(3L);
        assertThat(service.current(1L)).isEqualTo(3L);
    }

    @Test
    void versionsAreTrackedPerKnowledgeBase() {
        ScopeVersionService service = inMemoryService();
        service.bump(1L);
        assertThat(service.current(2L)).isEqualTo(1L);
        assertThat(service.current(1L)).isEqualTo(2L);
    }

    @Test
    void concurrentBumpsStayStrictlyMonotonicWithoutGaps() throws Exception {
        ScopeVersionService service = inMemoryService();
        int threads = 8;
        int bumpsPerThread = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<List<Long>>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    List<Long> observed = new ArrayList<>();
                    for (int j = 0; j < bumpsPerThread; j++) {
                        observed.add(service.bump(5L));
                    }
                    return observed;
                }));
            }
            start.countDown();

            var allValues = new java.util.HashSet<Long>();
            var duplicates = new AtomicInteger();
            for (Future<List<Long>> future : futures) {
                for (Long value : future.get()) {
                    if (!allValues.add(value)) {
                        duplicates.incrementAndGet();
                    }
                }
            }
            assertThat(duplicates.get()).as("bump values must be unique").isZero();
            assertThat(service.current(5L))
                    .as("final version = 1 + total bumps")
                    .isEqualTo(1L + (long) threads * bumpsPerThread);
        } finally {
            pool.shutdownNow();
        }
    }
}
