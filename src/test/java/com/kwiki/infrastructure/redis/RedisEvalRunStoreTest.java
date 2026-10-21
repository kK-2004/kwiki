package com.kwiki.infrastructure.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwiki.eval.CaseResult;
import com.kwiki.eval.CaseScore;
import com.kwiki.eval.EvalCase;
import com.kwiki.eval.EvalLayer;
import com.kwiki.eval.EvalRunState;
import com.kwiki.eval.EvalRunStore;
import com.kwiki.eval.EvalSummary;
import com.kwiki.eval.LayerMetric;
import com.kwiki.eval.LossLayer;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 用内存 Map 模拟 hash / zset 操作，验证键布局、按字段更新与 JSON 往返（测试不依赖 Docker 中间件）。 */
class RedisEvalRunStoreTest {

    final Map<String, Map<Object, Object>> hashes = new ConcurrentHashMap<>();
    final Map<String, TreeMap<Double, String>> zsets = new ConcurrentHashMap<>();
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final RedisEvalRunStore store = new RedisEvalRunStore(fakeRedis(), mapper);

    @SuppressWarnings("unchecked")
    StringRedisTemplate fakeRedis() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hash = mock(HashOperations.class);
        ZSetOperations<String, String> zset = mock(ZSetOperations.class);
        when(redis.<Object, Object>opsForHash()).thenReturn(hash);
        when(redis.opsForZSet()).thenReturn(zset);
        when(redis.hasKey(anyString())).thenAnswer(inv -> hashes.containsKey(inv.<String>getArgument(0)));
        when(redis.delete(any(Collection.class))).thenAnswer(inv -> {
            long removed = 0;
            for (Object key : inv.<Collection<String>>getArgument(0)) if (hashes.remove(key) != null) removed++;
            return removed;
        });
        doAnswer(inv -> {
            hashes.computeIfAbsent(inv.getArgument(0), k -> new LinkedHashMap<>())
                    .putAll(inv.<Map<Object, Object>>getArgument(1));
            return null;
        }).when(hash).putAll(anyString(), anyMap());
        doAnswer(inv -> {
            hashes.computeIfAbsent(inv.getArgument(0), k -> new LinkedHashMap<>())
                    .put(inv.getArgument(1), inv.getArgument(2));
            return null;
        }).when(hash).put(anyString(), any(), any());
        when(hash.delete(anyString(), any(Object[].class))).thenAnswer(inv -> {
            Map<Object, Object> fields = hashes.get(inv.<String>getArgument(0));
            Object[] args = inv.getArguments();
            for (int i = 1; i < args.length; i++) if (fields != null) fields.remove(args[i]);
            return 1L;
        });
        when(hash.entries(anyString())).thenAnswer(inv ->
                new LinkedHashMap<>(hashes.getOrDefault(inv.<String>getArgument(0), Map.of())));
        when(hash.size(anyString())).thenAnswer(inv ->
                (long) hashes.getOrDefault(inv.<String>getArgument(0), Map.of()).size());
        when(zset.add(anyString(), anyString(), anyDouble())).thenAnswer(inv -> {
            zsets.computeIfAbsent(inv.getArgument(0), k -> new TreeMap<>())
                    .put(inv.getArgument(2), inv.getArgument(1));
            return true;
        });
        when(zset.remove(anyString(), any(Object[].class))).thenAnswer(inv -> {
            TreeMap<Double, String> set = zsets.get(inv.<String>getArgument(0));
            if (set != null) set.values().remove(inv.<Object>getArgument(1));
            return 1L;
        });
        when(zset.reverseRange(anyString(), anyLong(), anyLong())).thenAnswer(inv -> {
            TreeMap<Double, String> set = zsets.getOrDefault(inv.<String>getArgument(0), new TreeMap<>());
            return new LinkedHashSet<>(set.descendingMap().values().stream()
                    .limit(inv.<Long>getArgument(2) + 1).toList());
        });
        return redis;
    }

    static EvalRunStore.RunMeta meta(String runId, long createdAt) {
        return new EvalRunStore.RunMeta(runId, "run-" + runId, "50q", 7L, "基线", false, 3,
                Map.of("concurrency", 2, "notes", "基线"), Instant.ofEpochMilli(createdAt));
    }

    static CaseResult result(String id) {
        var evalCase = new EvalCase(id, "dragonball", 50, "事实性问题", "Finance", "排放量是多少",
                List.of(List.of("10000吨", "1万吨")), false, List.of(5), List.of("要点"));
        var score = new CaseScore(Map.of(EvalLayer.POOL_50, Set.of(0), EvalLayer.ANSWER, Set.of()), 1,
                Map.of(0, LossLayer.GENERATION_MISS), Set.of());
        return new CaseResult(evalCase, score, null, "completed", "排放量为1万吨", List.of("改写"),
                Map.of(EvalLayer.POOL_50, List.of("C1")), Map.of(EvalLayer.POOL_50, 2), null, 42);
    }

    @Test
    void createsRunWithFieldHashAndHistoryIndex() {
        store.create(meta("a", 1000));

        assertThat(hashes.get("kwiki:eval:run:a")).containsKeys("meta", "state", "heartbeatAt")
                .containsEntry("state", "RUNNING");
        assertThat(zsets.get("kwiki:eval:runs")).containsValue("a");
        var record = store.find("a").orElseThrow();
        assertThat(record.meta()).isEqualTo(meta("a", 1000));
        assertThat(record.state()).isEqualTo(EvalRunState.RUNNING);
        assertThat(record.cancelRequested()).isFalse();
    }

    @Test
    void caseResultsRoundTripThroughJson() {
        store.create(meta("a", 1000));
        store.saveCase("a", result("p-1"));
        store.saveCase("a", result("p-1"));

        assertThat(store.caseCount("a")).isEqualTo(1);
        CaseResult restored = store.cases("a").get("p-1");
        assertThat(restored).isEqualTo(result("p-1"));
        assertThat(restored.score().fullHit(EvalLayer.POOL_50)).isTrue();
    }

    @Test
    void finishCancelAndRunningUpdateIndividualFields() {
        store.create(meta("a", 1000));
        store.requestCancel("a");
        assertThat(store.find("a").orElseThrow().cancelRequested()).isTrue();

        var summary = new EvalSummary(1, 0, 1, Map.of("answer", new LayerMetric(1, 1, 1)), Map.of(), Map.of(),
                Map.of(), 0, 0, Map.of(), 0);
        store.finish("a", EvalRunState.CANCELLED, summary, true, "cancelled");
        var finished = store.find("a").orElseThrow();
        assertThat(finished.state()).isEqualTo(EvalRunState.CANCELLED);
        assertThat(finished.summary()).isEqualTo(summary);
        assertThat(finished.reportPartial()).isTrue();
        assertThat(finished.error()).isEqualTo("cancelled");
        assertThat(finished.cancelRequested()).isFalse();

        store.markRunning("a", Instant.ofEpochMilli(5000));
        var resumed = store.find("a").orElseThrow();
        assertThat(resumed.state()).isEqualTo(EvalRunState.RUNNING);
        assertThat(resumed.summary()).isNull();
        assertThat(resumed.error()).isNull();
        assertThat(resumed.reportPartial()).isFalse();
        assertThat(resumed.heartbeatAt()).isEqualTo(Instant.ofEpochMilli(5000));
    }

    @Test
    void listsNewestFirstAndDeleteRemovesEverything() {
        store.create(meta("old", 1000));
        store.create(meta("new", 2000));
        store.saveCase("old", result("p-1"));

        assertThat(store.list(10)).extracting(r -> r.meta().runId()).containsExactly("new", "old");

        store.delete("old");
        assertThat(store.find("old")).isEmpty();
        assertThat(hashes).doesNotContainKeys("kwiki:eval:run:old", "kwiki:eval:run:old:cases");
        assertThat(store.list(10)).extracting(r -> r.meta().runId()).containsExactly("new");
    }

    @Test
    void heartbeatOnDeletedRunDoesNotResurrectIt() {
        store.heartbeat("ghost", Instant.now());
        assertThat(store.find("ghost")).isEmpty();
        assertThat(hashes).doesNotContainKey("kwiki:eval:run:ghost");
    }
}
