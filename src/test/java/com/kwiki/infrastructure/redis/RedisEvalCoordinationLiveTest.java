package com.kwiki.infrastructure.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwiki.eval.CaseResult;
import com.kwiki.eval.EvalCase;
import com.kwiki.eval.EvalRunState;
import com.kwiki.eval.EvalRunStore;
import com.kwiki.eval.EvalInstanceId;
import com.kwiki.eval.EvalWorkQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 只在提供 Redis 地址时运行（{@code KWIKI_IT_REDIS_URI=redis://127.0.0.1:6379}）：
 * 验证 Redis 实现的 Lua 脚本与 Stream 消费组语义——集群名额（占位/上限/过期/续期/释放）、
 * 状态 CAS、认领令牌与令牌校验写入、任务队列（单次投递 / 挂起回收）。无该环境变量时跳过。
 */
@EnabledIfEnvironmentVariable(named = "KWIKI_IT_REDIS_URI", matches = ".+")
class RedisEvalCoordinationLiveTest {

    final StringRedisTemplate redis = template();
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @AfterEach
    void cleanup() {
        redis.delete(List.of(RedisEvalClusterSlots.KEY, RedisEvalWorkQueue.KEY));
    }

    static StringRedisTemplate template() {
        URI uri = URI.create(System.getenv("KWIKI_IT_REDIS_URI"));
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(uri.getHost(),
                uri.getPort() > 0 ? uri.getPort() : 6379);
        String userInfo = uri.getUserInfo();
        if (userInfo != null && userInfo.contains(":")) {
            config.setPassword(userInfo.substring(userInfo.indexOf(':') + 1));
        }
        if (uri.getPath() != null && uri.getPath().length() > 1) {
            config.setDatabase(Integer.parseInt(uri.getPath().substring(1)));
        }
        LettuceConnectionFactory factory = new LettuceConnectionFactory(config);
        factory.afterPropertiesSet();
        return EvalCoordinationConfiguration.template(factory);
    }

    static EvalRunStore.RunMeta meta(String runId, Instant createdAt) {
        return new EvalRunStore.RunMeta(runId, "run-" + runId, "50q", 7L, "", false, 1, Map.of(),
                createdAt, "1");
    }

    static CaseResult result(String caseId) {
        return new CaseResult(new EvalCase(caseId, "dragonball", 50, "事实性问题", "Finance", "排放量是多少",
                List.of(List.of("10000吨")), false, List.of(5), List.of()), null, null, "completed", "", List.of(),
                Map.of(), Map.of(), null, 1);
    }

    @Test
    void clusterSlotsAcquireLimitExpiryRenewAndRelease() throws Exception {
        var slots = new RedisEvalClusterSlots(redis);
        redis.delete(RedisEvalClusterSlots.KEY);
        Duration ttl = Duration.ofMillis(150);

        assertThat(slots.tryAcquire("i-1", 1, ttl)).isTrue();
        assertThat(slots.tryAcquire("i-2", 1, ttl)).as("达到上限").isFalse();
        assertThat(slots.renew("i-1", ttl)).isTrue();
        assertThat(slots.renew("i-2", ttl)).as("未持有不能续期").isFalse();
        assertThat(slots.tryAcquire("i-2", 2, ttl)).isTrue();

        slots.release("i-1");
        assertThat(slots.tryAcquire("i-1", 2, ttl)).isTrue();

        // 过期后自动回收：续期失败、其他实例可占位
        Thread.sleep(ttl.toMillis() + 40);
        assertThat(slots.renew("i-2", ttl)).as("过期后续期失败").isFalse();
        assertThat(slots.tryAcquire("i-3", 1, ttl)).isTrue();
        assertThat(slots.renew("i-1", ttl)).as("过期后（未被清零前）续期失败").isFalse();
    }

    @Test
    void markQueuedCasOwnershipTokenWritesAndQueueIndex() throws Exception {
        var store = new RedisEvalRunStore(redis, mapper);
        String runId = "it-" + UUID.randomUUID().toString().substring(0, 8);
        Instant t0 = Instant.now();
        try {
            store.create(meta(runId, t0));
            var record = store.find(runId).orElseThrow();
            assertThat(record.state()).isEqualTo(EvalRunState.QUEUED);
            assertThat(store.queuePosition(runId)).isZero();

            // QUEUED 重复提交失败；排队取消后可重新提交
            assertThat(store.markQueued(runId, "2", t0.plusSeconds(1), Duration.ofMinutes(2).toMillis())).isFalse();
            assertThat(store.cancelQueued(runId)).isTrue();
            assertThat(store.find(runId).orElseThrow().state()).isEqualTo(EvalRunState.CANCELLED);
            assertThat(store.queuePosition(runId)).isEqualTo(-1);
            assertThat(store.markQueued(runId, "2", t0.plusSeconds(2), Duration.ofMinutes(2).toMillis())).isTrue();
            assertThat(store.find(runId).orElseThrow().meta().submittedBy()).isEqualTo("2");

            // 认领发放递增令牌；旧令牌的检查点 / 心跳 / 已上报 / 终态写入被拒
            long first = store.claimOwnership(runId, "instance-a", t0.plusSeconds(3), Duration.ofMinutes(2).toMillis());
            // 心跳新鲜时不可抢占；过期后可接管
            assertThat(store.claimOwnership(runId, "instance-b", t0.plusSeconds(4), Duration.ofMinutes(2).toMillis())).isEqualTo(-1);
            // 过期判定用 Redis 服务器时间：阈值 1ms 并稍等，使 instance-a 的心跳过期
            Thread.sleep(20);
            long second = store.claimOwnership(runId, "instance-b", Instant.now(), 1);
            assertThat(first).isEqualTo(1);
            assertThat(second).isEqualTo(2);
            var claimed = store.find(runId).orElseThrow();
            assertThat(claimed.state()).isEqualTo(EvalRunState.RUNNING);
            assertThat(claimed.ownerInstanceId()).isEqualTo("instance-b");
            assertThat(store.queuePosition(runId)).isEqualTo(-1);

            assertThat(store.saveCase(runId, first, result("c-1"))).isFalse();
            assertThat(store.caseCount(runId)).isZero();
            assertThat(store.saveCase(runId, second, result("c-1"))).isTrue();
            assertThat(store.markReported(runId, first, "c-1")).isFalse();
            assertThat(store.markReported(runId, second, "c-1")).isTrue();
            assertThat(store.reportedCaseIds(runId)).containsExactly("c-1");
            assertThat(store.heartbeat(runId, first, t0.plusSeconds(5))).isFalse();
            assertThat(store.heartbeat(runId, second, t0.plusSeconds(5))).isTrue();
            assertThat(store.finish(runId, first, EvalRunState.COMPLETED, null, false, null)).isFalse();
            assertThat(store.find(runId).orElseThrow().state()).isEqualTo(EvalRunState.RUNNING);
            assertThat(store.finish(runId, second, EvalRunState.CANCELLED, null, false, "cancelled")).isTrue();

            // 终态 CANCELLED 可重新提交回 QUEUED；认领后心跳新鲜的 RUNNING 不可提交，过期后可重新提交（CAS）
            assertThat(store.markQueued(runId, "2", t0.plusSeconds(6), Duration.ofMinutes(2).toMillis())).isTrue();
            store.claimOwnership(runId, "instance-a", t0.plusSeconds(7), Duration.ofMinutes(2).toMillis());
            assertThat(store.markQueued(runId, "2", t0.plusSeconds(8), Duration.ofMinutes(2).toMillis())).isFalse();
            Thread.sleep(20);
            assertThat(store.markQueued(runId, "2", Instant.now(), 1)).isTrue();

            // 取消请求存在时认领失败且不清除标志；排队取消后认领失败
            store.requestCancel(runId);
            Thread.sleep(20);
            assertThat(store.claimOwnership(runId, "instance-c", Instant.now(), 1))
                    .as("即使心跳已过期，存在取消请求时也不可认领").isEqualTo(-1);
            assertThat(store.find(runId).orElseThrow().cancelRequested()).isTrue();

            // 记录删除后令牌写入全部失败
            store.delete(runId);
            assertThat(store.find(runId)).isEmpty();
            assertThat(store.saveCase(runId, second, result("c-1"))).isFalse();
            assertThat(store.finish(runId, second, EvalRunState.FAILED, null, false, "x")).isFalse();
        } finally {
            store.delete(runId);
        }
    }

    @Test
    void systemLevelFailAndCancelWrites() {
        var store = new RedisEvalRunStore(redis, mapper);
        String failed = "it-" + UUID.randomUUID().toString().substring(0, 8);
        String cancelled = "it-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            store.create(meta(failed, Instant.now()));
            store.markFailed(failed, "submitter-not-authorized");
            assertThat(store.find(failed).orElseThrow().state()).isEqualTo(EvalRunState.FAILED);
            assertThat(store.find(failed).orElseThrow().error()).isEqualTo("submitter-not-authorized");
            assertThat(store.queuePosition(failed)).isEqualTo(-1);

            store.create(meta(cancelled, Instant.now()));
            assertThat(store.claimOwnership(cancelled, "instance-a", Instant.now(),
                    Duration.ofMinutes(2).toMillis())).isEqualTo(1);
            store.requestCancel(cancelled);
            store.markCancelled(cancelled);
            var record = store.find(cancelled).orElseThrow();
            assertThat(record.state()).isEqualTo(EvalRunState.CANCELLED);
            assertThat(record.cancelRequested()).isFalse();
        } finally {
            store.delete(failed);
            store.delete(cancelled);
        }
    }

    @Test
    void queueDeliversEachTaskOnceAndReclaimsPending() throws Exception {
        redis.delete(RedisEvalWorkQueue.KEY);
        var consumerA = new RedisEvalWorkQueue(redis, new EvalInstanceId("it-a-" + UUID.randomUUID().toString()
                .substring(0, 6)), Duration.ofMillis(200));
        var consumerB = new RedisEvalWorkQueue(redis, new EvalInstanceId("it-b-" + UUID.randomUUID().toString()
                .substring(0, 6)), Duration.ofMillis(200));

        consumerA.enqueue("it-task-1");
        consumerA.enqueue("it-task-2");

        var first = consumerA.claim(Duration.ofSeconds(2));
        var second = consumerB.claim(Duration.ofSeconds(2));
        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(List.of(first.runId(), second.runId())).containsExactlyInAnyOrder("it-task-1", "it-task-2");
        consumerA.ack(first);
        consumerB.ack(second);
        assertThat(redis.opsForStream().size(RedisEvalWorkQueue.KEY)).as("确认后的消息被删除，stream 不增长").isZero();
        assertThat(consumerA.claim(Duration.ofMillis(150))).as("队列已空").isNull();

        // 读取但未确认（实例在认领过程中宕机）：空闲超过阈值后被另一实例接管
        consumerA.enqueue("it-task-3");
        var stuck = consumerA.claim(Duration.ofSeconds(2));
        assertThat(stuck.runId()).isEqualTo("it-task-3"); // 不 ack，模拟宕机
        Thread.sleep(400);
        var reclaimed = consumerB.claim(Duration.ofSeconds(2));
        assertThat(reclaimed).as("挂起任务被接管").isNotNull();
        assertThat(reclaimed.runId()).isEqualTo("it-task-3");
        consumerB.ack(reclaimed);
        assertThat(consumerB.claim(Duration.ofMillis(150))).isNull();
        assertThat(redis.opsForStream().size(RedisEvalWorkQueue.KEY)).isZero();
    }
}
