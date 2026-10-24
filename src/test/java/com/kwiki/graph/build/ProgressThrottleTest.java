package com.kwiki.graph.build;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** 进度节流：首次立即放行，同一间隔内只放行一次。 */
class ProgressThrottleTest {

    @Test
    void firstReportIsImmediateThenAtMostOncePerInterval() {
        AtomicLong now = new AtomicLong(5_000);
        ProgressThrottle throttle = new ProgressThrottle(1_000, now::get);

        assertThat(throttle.due()).isTrue();
        assertThat(throttle.due()).isFalse();
        now.addAndGet(999);
        assertThat(throttle.due()).isFalse();
        now.addAndGet(1);
        assertThat(throttle.due()).isTrue();
        assertThat(throttle.due()).isFalse();
    }

    @Test
    void concurrentCallersGetASinglePermissionPerInterval() throws Exception {
        AtomicLong now = new AtomicLong(0);
        ProgressThrottle throttle = new ProgressThrottle(1_000, now::get);
        AtomicLong granted = new AtomicLong();
        Thread[] threads = new Thread[16];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = Thread.ofVirtual().start(() -> {
                if (throttle.due()) {
                    granted.incrementAndGet();
                }
            });
        }
        for (Thread thread : threads) {
            thread.join();
        }
        assertThat(granted.get()).isEqualTo(1);
    }
}
