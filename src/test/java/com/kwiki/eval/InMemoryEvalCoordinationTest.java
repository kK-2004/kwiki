package com.kwiki.eval;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** 内存协调实现的语义：队列单消费、集群名额上限与过期、控制广播、run 锁互斥。 */
class InMemoryEvalCoordinationTest {

    @Test
    void queueDeliversEachMessageToExactlyOneConsumer() {
        var queue = new InMemoryEvalWorkQueue();
        queue.enqueue("r-1");
        queue.enqueue("r-2");

        var first = queue.claim(Duration.ofMillis(10));
        var second = queue.claim(Duration.ofMillis(10));
        assertThat(List.of(first.runId(), second.runId())).containsExactlyInAnyOrder("r-1", "r-2");
        assertThat(queue.claim(Duration.ofMillis(60))).isNull();
    }

    @Test
    void slotsEnforceClusterLimitAndExpire() throws Exception {
        var slots = new InMemoryEvalClusterSlots();
        Duration ttl = Duration.ofMillis(120);

        assertThat(slots.tryAcquire("i-1", 1, ttl)).isTrue();
        assertThat(slots.tryAcquire("i-2", 1, ttl)).as("达到上限").isFalse();
        assertThat(slots.renew("i-1", ttl)).isTrue();
        assertThat(slots.tryAcquire("i-2", 2, ttl)).isTrue();
        assertThat(slots.held()).isEqualTo(2);

        slots.release("i-1");
        assertThat(slots.tryAcquire("i-1", 2, ttl)).isTrue();

        // 过期后名额自动释放，其他实例可占用
        Thread.sleep(ttl.toMillis() + 30);
        assertThat(slots.renew("i-2", ttl)).as("过期后续期失败").isFalse();
        assertThat(slots.tryAcquire("i-3", 1, ttl)).isTrue();
        assertThat(slots.held()).isEqualTo(1);
    }

    @Test
    void busBroadcastsToAllSubscribersAndUnsubscribeStopsDelivery() {
        var bus = new InMemoryEvalControlBus();
        List<String> a = new CopyOnWriteArrayList<>();
        List<String> b = new CopyOnWriteArrayList<>();
        var unsubscribe = bus.subscribe((runId, action) -> a.add(runId + ":" + action));
        bus.subscribe((runId, action) -> { throw new IllegalStateException("boom"); });

        bus.publish("r-1", "cancel");

        assertThat(a).containsExactly("r-1:cancel");
        assertThat(b).isEmpty();
        unsubscribe.run();
        bus.publish("r-2", "cancel");
        assertThat(a).hasSize(1);
    }

    @Test
    void runLocksAreMutuallyExclusivePerRun() throws Exception {
        var locks = new InMemoryEvalRunLocks();

        try (var first = locks.tryAcquire("r-1")) {
            assertThat(first).isNotNull();
            assertThat(locks.tryAcquire("r-1")).as("同 run 互斥").isNull();
            try (var other = locks.tryAcquire("r-2")) {
                assertThat(other).isNotNull();
            }
        }
        assertThat(locks.tryAcquire("r-1")).as("关闭后释放").isNotNull();
    }
}
