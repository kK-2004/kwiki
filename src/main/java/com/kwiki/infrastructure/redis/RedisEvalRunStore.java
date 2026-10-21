package com.kwiki.infrastructure.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwiki.eval.CaseResult;
import com.kwiki.eval.EvalRunState;
import com.kwiki.eval.EvalRunStore;
import com.kwiki.eval.EvalSummary;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Redis 上的评测 run 存储，不设 TTL（由管理端删除）：
 * {@code kwiki:eval:runs} ZSET（runId 按创建时间）、{@code kwiki:eval:run:{runId}} HASH（meta 与各状态字段，
 * 按字段写入避免并发覆盖）、{@code kwiki:eval:run:{runId}:cases} HASH（caseId → CaseResult JSON）。
 */
public class RedisEvalRunStore implements EvalRunStore {

    static final String INDEX = "kwiki:eval:runs";
    private static final String RUN_PREFIX = "kwiki:eval:run:";
    private static final String META = "meta";
    private static final String STATE = "state";
    private static final String HEARTBEAT = "heartbeatAt";
    private static final String CANCEL = "cancelRequested";
    private static final String SUMMARY = "summary";
    private static final String ERROR = "error";
    private static final String PARTIAL = "reportPartial";

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public RedisEvalRunStore(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    private static String runKey(String runId) {
        return RUN_PREFIX + runId;
    }

    private static String casesKey(String runId) {
        return RUN_PREFIX + runId + ":cases";
    }

    private HashOperations<String, Object, Object> hash() {
        return redis.opsForHash();
    }

    @Override
    public void create(RunMeta meta) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(META, json(meta));
        fields.put(STATE, EvalRunState.RUNNING.name());
        fields.put(HEARTBEAT, Long.toString(meta.createdAt().toEpochMilli()));
        hash().putAll(runKey(meta.runId()), fields);
        redis.opsForZSet().add(INDEX, meta.runId(), meta.createdAt().toEpochMilli());
    }

    @Override
    public Optional<RunRecord> find(String runId) {
        Map<Object, Object> fields = hash().entries(runKey(runId));
        Object meta = fields.get(META);
        if (meta == null) return Optional.empty();
        Object summary = fields.get(SUMMARY);
        Object heartbeat = fields.get(HEARTBEAT);
        return Optional.of(new RunRecord(
                read(meta.toString(), RunMeta.class),
                EvalRunState.valueOf(String.valueOf(fields.getOrDefault(STATE, EvalRunState.FAILED.name()))),
                summary == null ? null : read(summary.toString(), EvalSummary.class),
                fields.get(ERROR) == null ? null : fields.get(ERROR).toString(),
                "true".equals(fields.get(PARTIAL)),
                heartbeat == null ? null : Instant.ofEpochMilli(Long.parseLong(heartbeat.toString())),
                "true".equals(fields.get(CANCEL))));
    }

    @Override
    public List<RunRecord> list(int limit) {
        Set<String> ids = redis.opsForZSet().reverseRange(INDEX, 0, limit - 1L);
        List<RunRecord> records = new ArrayList<>();
        if (ids == null) return records;
        for (String id : ids) find(id).ifPresent(records::add);
        return records;
    }

    @Override
    public void saveCase(String runId, CaseResult result) {
        hash().put(casesKey(runId), result.evalCase().id(), json(result));
    }

    @Override
    public Map<String, CaseResult> cases(String runId) {
        Map<String, CaseResult> results = new LinkedHashMap<>();
        hash().entries(casesKey(runId)).forEach((id, value) ->
                results.put(id.toString(), read(value.toString(), CaseResult.class)));
        return results;
    }

    @Override
    public int caseCount(String runId) {
        Long size = hash().size(casesKey(runId));
        return size == null ? 0 : size.intValue();
    }

    @Override
    public void markRunning(String runId, Instant now) {
        if (!Boolean.TRUE.equals(redis.hasKey(runKey(runId)))) return;
        hash().delete(runKey(runId), SUMMARY, ERROR, PARTIAL, CANCEL);
        hash().putAll(runKey(runId), Map.of(STATE, EvalRunState.RUNNING.name(),
                HEARTBEAT, Long.toString(now.toEpochMilli())));
    }

    @Override
    public void finish(String runId, EvalRunState state, EvalSummary summary, boolean reportPartial, String error) {
        if (!Boolean.TRUE.equals(redis.hasKey(runKey(runId)))) return;
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(STATE, state.name());
        fields.put(PARTIAL, Boolean.toString(reportPartial));
        if (summary != null) fields.put(SUMMARY, json(summary));
        if (error != null) fields.put(ERROR, error);
        hash().delete(runKey(runId), CANCEL);
        if (summary == null) hash().delete(runKey(runId), SUMMARY);
        if (error == null) hash().delete(runKey(runId), ERROR);
        hash().putAll(runKey(runId), fields);
    }

    @Override
    public void heartbeat(String runId, Instant now) {
        if (!Boolean.TRUE.equals(redis.hasKey(runKey(runId)))) return;
        hash().put(runKey(runId), HEARTBEAT, Long.toString(now.toEpochMilli()));
    }

    @Override
    public void requestCancel(String runId) {
        if (!Boolean.TRUE.equals(redis.hasKey(runKey(runId)))) return;
        hash().put(runKey(runId), CANCEL, "true");
    }

    @Override
    public void delete(String runId) {
        redis.delete(List.of(runKey(runId), casesKey(runId)));
        redis.opsForZSet().remove(INDEX, runId);
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize eval run data", e);
        }
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("corrupted eval run data: " + type.getSimpleName(), e);
        }
    }
}
