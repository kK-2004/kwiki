package com.kwiki.eval;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 内存存储的排队、CAS 提交、认领令牌与令牌校验写入语义（与 Redis 实现同约定）。 */
class InMemoryEvalRunStoreTest {

    final InMemoryEvalRunStore store = new InMemoryEvalRunStore();
    final Instant t0 = Instant.parse("2026-10-22T08:00:00Z");

    static EvalRunStore.RunMeta meta(String runId, Instant createdAt) {
        return new EvalRunStore.RunMeta(runId, "run-" + runId, "50q", 7L, "", false, 3,
                Map.of(), createdAt, "1");
    }

    static CaseResult result(String caseId) {
        return new CaseResult(new EvalCase(caseId, "dragonball", 50, "事实性问题", "Finance", "排放量是多少",
                List.of(List.of("10000吨")), false, List.of(5), List.of()), null, null, "completed", "", List.of(),
                Map.of(), Map.of(), null, 1);
    }

    @Test
    void createPutsRunIntoQueueWithSubmittedBy() {
        store.create(meta("a", t0));

        var record = store.find("a").orElseThrow();
        assertThat(record.state()).isEqualTo(EvalRunState.QUEUED);
        assertThat(record.meta().submittedBy()).isEqualTo("1");
        assertThat(record.queuedAt()).isEqualTo(t0);
        assertThat(store.queuePosition("a")).isZero();
    }

    @Test
    void queuePositionCountsOnlyRunsAheadOfMine() {
        store.create(meta("a", t0));
        store.create(meta("b", t0.plusSeconds(1)));
        store.create(meta("c", t0.plusSeconds(2)));

        assertThat(store.queuePosition("a")).isZero();
        assertThat(store.queuePosition("b")).isEqualTo(1);
        assertThat(store.queuePosition("c")).isEqualTo(2);
        assertThat(store.queuePosition("ghost")).isEqualTo(-1);

        store.cancelQueued("a");
        assertThat(store.queuePosition("b")).isZero();
    }

    @Test
    void markQueuedIsCasOverTerminalOrStaleStates() {
        store.create(meta("a", t0));
        // QUEUED 重复提交失败
        assertThat(store.markQueued("a", "2", t0, Duration.ofMinutes(2).toMillis())).isFalse();

        // 排队取消后可重新提交
        assertThat(store.cancelQueued("a")).isTrue();
        assertThat(store.find("a").orElseThrow().state()).isEqualTo(EvalRunState.CANCELLED);
        assertThat(store.queuePosition("a")).isEqualTo(-1);
        assertThat(store.markQueued("a", "2", t0.plusSeconds(5), Duration.ofMinutes(2).toMillis())).isTrue();
        assertThat(store.find("a").orElseThrow().meta().submittedBy()).isEqualTo("2");

        // 运行中（心跳新鲜）不可提交
        long token = store.claimOwnership("a", "instance-1", t0.plusSeconds(6));
        assertThat(token).isEqualTo(1);
        assertThat(store.find("a").orElseThrow().state()).isEqualTo(EvalRunState.RUNNING);
        assertThat(store.find("a").orElseThrow().ownerInstanceId()).isEqualTo("instance-1");
        assertThat(store.queuePosition("a")).isEqualTo(-1);
        assertThat(store.markQueued("a", "2", t0.plusSeconds(7), Duration.ofMinutes(2).toMillis())).isFalse();

        // 心跳过期后视为已中断，可重新提交（过期判定为 beat + staleAfter < now）
        assertThat(store.markQueued("a", "2", t0.plusSeconds(6).plusSeconds(121),
                Duration.ofMinutes(2).toMillis())).isTrue();

        // 终态 COMPLETED 不可提交
        store.claimOwnership("a", "instance-1", t0.plusSeconds(10));
        store.finish("a", 2, EvalRunState.COMPLETED, null, false, null);
        assertThat(store.markQueued("a", "2", t0.plusSeconds(11), Duration.ofMinutes(2).toMillis())).isFalse();

        // 记录不存在
        assertThat(store.markQueued("ghost", "2", t0, 1)).isFalse();
    }

    @Test
    void claimOwnershipIssuesIncrementingTokensAndClearsErrorFields() {
        store.create(meta("a", t0));
        store.markFailed("a", "submitter-not-authorized");

        long first = store.claimOwnership("a", "i-1", t0.plusSeconds(1));
        long second = store.claimOwnership("a", "i-2", t0.plusSeconds(2));

        assertThat(first).isEqualTo(1);
        assertThat(second).isEqualTo(2);
        var record = store.find("a").orElseThrow();
        assertThat(record.state()).isEqualTo(EvalRunState.RUNNING);
        assertThat(record.ownerInstanceId()).isEqualTo("i-2");
        assertThat(record.error()).isNull();
        store.claimOwnership("ghost", "i-1", t0);
        assertThat(store.find("ghost")).isEmpty();
    }

    @Test
    void tokenCheckedWritesAreRejectedAfterTakeover() {
        store.create(meta("a", t0));
        long oldToken = store.claimOwnership("a", "i-1", t0.plusSeconds(1));
        long newToken = store.claimOwnership("a", "i-2", t0.plusSeconds(2));

        assertThat(store.saveCase("a", oldToken, result("p-1"))).isFalse();
        assertThat(store.caseCount("a")).isZero();
        assertThat(store.saveCase("a", newToken, result("p-1"))).isTrue();
        assertThat(store.caseCount("a")).isEqualTo(1);

        assertThat(store.markReported("a", oldToken, "p-1")).isFalse();
        assertThat(store.markReported("a", newToken, "p-1")).isTrue();
        assertThat(store.reportedCaseIds("a")).containsExactly("p-1");

        assertThat(store.heartbeat("a", oldToken, t0.plusSeconds(3))).isFalse();
        assertThat(store.heartbeat("a", newToken, t0.plusSeconds(3))).isTrue();
        assertThat(store.find("a").orElseThrow().heartbeatAt()).isEqualTo(t0.plusSeconds(3));

        assertThat(store.finish("a", oldToken, EvalRunState.COMPLETED, null, false, null)).isFalse();
        assertThat(store.find("a").orElseThrow().state()).isEqualTo(EvalRunState.RUNNING);
        assertThat(store.finish("a", newToken, EvalRunState.CANCELLED, null, false, "cancelled")).isTrue();
        assertThat(store.find("a").orElseThrow().state()).isEqualTo(EvalRunState.CANCELLED);

        // 记录删除后所有写入被拒
        store.delete("a");
        assertThat(store.saveCase("a", newToken, result("p-1"))).isFalse();
        assertThat(store.heartbeat("a", newToken, t0)).isFalse();
        assertThat(store.finish("a", newToken, EvalRunState.FAILED, null, false, "x")).isFalse();
    }

    @Test
    void requestCancelKeepsStateUntilOwnerActs() {
        store.create(meta("a", t0));
        long token = store.claimOwnership("a", "i-1", t0.plusSeconds(1));
        store.saveCase("a", token, result("p-1"));

        store.requestCancel("a");

        assertThat(store.find("a").orElseThrow().cancelRequested()).isTrue();
        assertThat(store.reportedCaseIds("a")).isEmpty();
        assertThat(Set.copyOf(store.cases("a").keySet())).containsExactly("p-1");
    }

    @Test
    void markFailedWritesTerminalStateWithoutToken() {
        store.create(meta("a", t0));

        store.markFailed("a", "submitter-not-authorized");

        var record = store.find("a").orElseThrow();
        assertThat(record.state()).isEqualTo(EvalRunState.FAILED);
        assertThat(record.error()).isEqualTo("submitter-not-authorized");
        assertThat(store.queuePosition("a")).isEqualTo(-1);
        store.markFailed("ghost", "x");
        assertThat(store.find("ghost")).isEmpty();
    }
}
