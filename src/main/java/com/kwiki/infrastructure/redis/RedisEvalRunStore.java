package com.kwiki.infrastructure.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwiki.eval.CaseResult;
import com.kwiki.eval.EvalRunState;
import com.kwiki.eval.EvalRunStore;
import com.kwiki.eval.EvalSummary;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Redis 上的评测 run 存储与执行协调，不设 TTL（由管理端删除）：
 * {@code kwiki:eval:runs} ZSET（runId 按创建时间）、{@code kwiki:eval:queued} ZSET（排队中 runId 按 queuedAt）、
 * {@code kwiki:eval:run:{runId}} HASH（meta 与各状态字段，按字段写入避免并发覆盖）、
 * {@code kwiki:eval:run:{runId}:cases} HASH（caseId → CaseResult JSON）、
 * {@code kwiki:eval:run:{runId}:cases:reported} HASH（已上报 caseId）。
 * 状态 CAS、认领归属与令牌校验写入用 Lua 脚本原子执行，防止并发提交与被接管后的僵尸实例写入。
 */
public class RedisEvalRunStore implements EvalRunStore {

    static final String INDEX = "kwiki:eval:runs";
    static final String QUEUED_INDEX = "kwiki:eval:queued";
    private static final String RUN_PREFIX = "kwiki:eval:run:";
    private static final String META = "meta";
    private static final String STATE = "state";
    private static final String HEARTBEAT = "heartbeatAt";
    private static final String CREATED = "created";
    private static final String CANCEL = "cancelRequested";
    private static final String SUMMARY = "summary";
    private static final String ERROR = "error";
    private static final String PARTIAL = "reportPartial";
    private static final String OWNER_INSTANCE = "ownerInstanceId";
    private static final String OWNER_TOKEN = "ownerToken";
    private static final String QUEUED_AT = "queuedAt";
    private static final String SUBMITTED_BY = "submittedBy";

    /** CANCELLED / FAILED / 心跳过期 RUNNING → QUEUED，记提交人与 queuedAt，入排队索引。 */
    private static final RedisScript<Long> MARK_QUEUED = new DefaultRedisScript<>("""
            local state = redis.call('HGET', KEYS[1], 'state')
            if not state then return 0 end
            if state ~= 'CANCELLED' and state ~= 'FAILED' then
              if state ~= 'RUNNING' then return 0 end
              local beat = redis.call('HGET', KEYS[1], 'heartbeatAt') or redis.call('HGET', KEYS[1], 'created')
              if not beat or tonumber(beat) + tonumber(ARGV[3]) >= tonumber(ARGV[2]) then return 0 end
            end
            redis.call('HSET', KEYS[1], 'state', 'QUEUED', 'submittedBy', ARGV[1], 'queuedAt', ARGV[2])
            redis.call('HDEL', KEYS[1], 'summary', 'error', 'reportPartial', 'cancelRequested')
            redis.call('ZADD', KEYS[2], ARGV[2], ARGV[4])
            return 1
            """, Long.class);

    /** QUEUED → CANCELLED 并移出排队索引；其余状态返回 0。 */
    private static final RedisScript<Long> CANCEL_QUEUED = new DefaultRedisScript<>("""
            local state = redis.call('HGET', KEYS[1], 'state')
            if not state or state ~= 'QUEUED' then return 0 end
            redis.call('HSET', KEYS[1], 'state', 'CANCELLED', 'error', 'cancelled')
            redis.call('HDEL', KEYS[1], 'cancelRequested')
            redis.call('ZREM', KEYS[2], ARGV[1])
            return 1
            """, Long.class);

    /** 排队位次：ZRANK（0 起），不在排队返回 -1。 */
    private static final RedisScript<Long> QUEUE_POSITION = new DefaultRedisScript<>("""
            local rank = redis.call('ZRANK', KEYS[1], ARGV[1])
            if not rank then return -1 end
            return rank
            """, Long.class);

    /** 认领归属：令牌自增、置 RUNNING（清错误/取消标志）、记执行实例与心跳、移出排队索引；记录不存在返回 -1。 */
    private static final RedisScript<Long> CLAIM_OWNERSHIP = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 0 then return -1 end
            local token = redis.call('HINCRBY', KEYS[1], 'ownerToken', 1)
            redis.call('HSET', KEYS[1], 'state', 'RUNNING', 'ownerInstanceId', ARGV[1], 'heartbeatAt', ARGV[2])
            redis.call('HDEL', KEYS[1], 'summary', 'error', 'reportPartial', 'cancelRequested')
            redis.call('ZREM', KEYS[2], ARGV[3])
            return token
            """, Long.class);

    /** 令牌校验通过后写逐题检查点；记录不存在或令牌不符返回 0。 */
    private static final RedisScript<Long> SAVE_CASE = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 0 then return 0 end
            local token = redis.call('HGET', KEYS[1], 'ownerToken')
            if not token or token ~= ARGV[1] then return 0 end
            redis.call('HSET', KEYS[2], ARGV[2], ARGV[3])
            return 1
            """, Long.class);

    /** 令牌校验通过后刷新心跳。 */
    private static final RedisScript<Long> HEARTBEAT_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 0 then return 0 end
            local token = redis.call('HGET', KEYS[1], 'ownerToken')
            if not token or token ~= ARGV[1] then return 0 end
            redis.call('HSET', KEYS[1], 'heartbeatAt', ARGV[2])
            return 1
            """, Long.class);

    /** 令牌校验通过后标记逐题已上报。 */
    private static final RedisScript<Long> MARK_REPORTED = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 0 then return 0 end
            local token = redis.call('HGET', KEYS[1], 'ownerToken')
            if not token or token ~= ARGV[1] then return 0 end
            redis.call('HSET', KEYS[2], ARGV[2], '1')
            return 1
            """, Long.class);

    /** 令牌校验通过后写终态：置状态与 reportPartial，summary / error 为空串时删除对应字段，清取消标志并移出排队索引。 */
    private static final RedisScript<Long> FINISH = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 0 then return 0 end
            local token = redis.call('HGET', KEYS[1], 'ownerToken')
            if not token or token ~= ARGV[1] then return 0 end
            redis.call('HSET', KEYS[1], 'state', ARGV[2], 'reportPartial', ARGV[3])
            if ARGV[4] == '' then redis.call('HDEL', KEYS[1], 'summary') else redis.call('HSET', KEYS[1], 'summary', ARGV[4]) end
            if ARGV[5] == '' then redis.call('HDEL', KEYS[1], 'error') else redis.call('HSET', KEYS[1], 'error', ARGV[5]) end
            redis.call('HDEL', KEYS[1], 'cancelRequested')
            redis.call('ZREM', KEYS[2], ARGV[6])
            return 1
            """, Long.class);

    /** 系统级失败（无令牌）：认领期校验失败时置 FAILED 并移出排队索引。 */
    private static final RedisScript<Long> MARK_FAILED = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 0 then return 0 end
            redis.call('HSET', KEYS[1], 'state', 'FAILED', 'error', ARGV[1])
            redis.call('ZREM', KEYS[2], ARGV[2])
            return 1
            """, Long.class);

    /** 系统级中断收尾（无令牌）：仅 RUNNING 置 CANCELLED（已请求取消但执行实例失联）。 */
    private static final RedisScript<Long> MARK_CANCELLED = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 0 then return 0 end
            if redis.call('HGET', KEYS[1], 'state') ~= 'RUNNING' then return 0 end
            redis.call('HSET', KEYS[1], 'state', 'CANCELLED', 'error', 'cancelled')
            redis.call('HDEL', KEYS[1], 'cancelRequested')
            redis.call('ZREM', KEYS[2], ARGV[1])
            return 1
            """, Long.class);

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

    private static String reportedKey(String runId) {
        return RUN_PREFIX + runId + ":cases:reported";
    }

    private HashOperations<String, Object, Object> hash() {
        return redis.opsForHash();
    }

    @Override
    public void create(RunMeta meta) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(META, json(meta));
        fields.put(STATE, EvalRunState.QUEUED.name());
        fields.put(SUBMITTED_BY, meta.submittedBy());
        fields.put(CREATED, Long.toString(meta.createdAt().toEpochMilli()));
        fields.put(HEARTBEAT, Long.toString(meta.createdAt().toEpochMilli()));
        fields.put(QUEUED_AT, Long.toString(meta.createdAt().toEpochMilli()));
        hash().putAll(runKey(meta.runId()), fields);
        redis.opsForZSet().add(INDEX, meta.runId(), meta.createdAt().toEpochMilli());
        redis.opsForZSet().add(QUEUED_INDEX, meta.runId(), meta.createdAt().toEpochMilli());
    }

    @Override
    public Optional<RunRecord> find(String runId) {
        Map<Object, Object> fields = hash().entries(runKey(runId));
        Object meta = fields.get(META);
        if (meta == null) return Optional.empty();
        Object summary = fields.get(SUMMARY);
        Object heartbeat = fields.get(HEARTBEAT);
        Object queuedAt = fields.get(QUEUED_AT);
        RunMeta parsed = read(meta.toString(), RunMeta.class);
        Object submitted = fields.get(SUBMITTED_BY);
        if (submitted != null) {
            parsed = new RunMeta(parsed.runId(), parsed.runName(), parsed.dataset(), parsed.kbId(), parsed.notes(),
                    parsed.localOnly(), parsed.total(), parsed.config(), parsed.createdAt(), submitted.toString());
        }
        return Optional.of(new RunRecord(
                parsed,
                EvalRunState.valueOf(String.valueOf(fields.getOrDefault(STATE, EvalRunState.FAILED.name()))),
                summary == null ? null : read(summary.toString(), EvalSummary.class),
                fields.get(ERROR) == null ? null : fields.get(ERROR).toString(),
                "true".equals(fields.get(PARTIAL)),
                heartbeat == null ? null : Instant.ofEpochMilli(Long.parseLong(heartbeat.toString())),
                "true".equals(fields.get(CANCEL)),
                fields.get(OWNER_INSTANCE) == null ? null : fields.get(OWNER_INSTANCE).toString(),
                queuedAt == null ? null : Instant.ofEpochMilli(Long.parseLong(queuedAt.toString()))));
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
    public boolean markQueued(String runId, String submittedBy, Instant now, long staleAfterMillis) {
        Long result = redis.execute(MARK_QUEUED, List.of(runKey(runId), QUEUED_INDEX),
                submittedBy, Long.toString(now.toEpochMilli()), Long.toString(staleAfterMillis), runId);
        return Long.valueOf(1L).equals(result);
    }

    @Override
    public boolean cancelQueued(String runId) {
        Long result = redis.execute(CANCEL_QUEUED, List.of(runKey(runId), QUEUED_INDEX), runId);
        return Long.valueOf(1L).equals(result);
    }

    @Override
    public long queuePosition(String runId) {
        Long rank = redis.execute(QUEUE_POSITION, List.of(QUEUED_INDEX), runId);
        return rank == null ? -1 : rank;
    }

    @Override
    public long claimOwnership(String runId, String instanceId, Instant now) {
        Long token = redis.execute(CLAIM_OWNERSHIP, List.of(runKey(runId), QUEUED_INDEX),
                instanceId, Long.toString(now.toEpochMilli()), runId);
        return token == null ? -1 : token;
    }

    @Override
    public boolean saveCase(String runId, long ownerToken, CaseResult result) {
        Long done = redis.execute(SAVE_CASE, List.of(runKey(runId), casesKey(runId)),
                Long.toString(ownerToken), result.evalCase().id(), json(result));
        return Long.valueOf(1L).equals(done);
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
    public Set<String> reportedCaseIds(String runId) {
        Set<Object> ids = hash().keys(reportedKey(runId));
        Set<String> result = new java.util.LinkedHashSet<>();
        ids.forEach(id -> result.add(id.toString()));
        return result;
    }

    @Override
    public boolean markReported(String runId, long ownerToken, String caseId) {
        Long done = redis.execute(MARK_REPORTED, List.of(runKey(runId), reportedKey(runId)),
                Long.toString(ownerToken), caseId);
        return Long.valueOf(1L).equals(done);
    }

    @Override
    public boolean finish(String runId, long ownerToken, EvalRunState state, EvalSummary summary,
                          boolean reportPartial, String error) {
        Long done = redis.execute(FINISH, List.of(runKey(runId), QUEUED_INDEX), Long.toString(ownerToken),
                state.name(), Boolean.toString(reportPartial), summary == null ? "" : json(summary),
                error == null ? "" : error, runId);
        return Long.valueOf(1L).equals(done);
    }

    @Override
    public boolean heartbeat(String runId, long ownerToken, Instant now) {
        Long done = redis.execute(HEARTBEAT_SCRIPT, List.of(runKey(runId)), Long.toString(ownerToken),
                Long.toString(now.toEpochMilli()));
        return Long.valueOf(1L).equals(done);
    }

    @Override
    public void markFailed(String runId, String error) {
        redis.execute(MARK_FAILED, List.of(runKey(runId), QUEUED_INDEX), error, runId);
    }

    @Override
    public void markCancelled(String runId) {
        redis.execute(MARK_CANCELLED, List.of(runKey(runId), QUEUED_INDEX), runId);
    }

    @Override
    public void requestCancel(String runId) {
        if (!Boolean.TRUE.equals(redis.hasKey(runKey(runId)))) return;
        hash().put(runKey(runId), CANCEL, "true");
    }

    @Override
    public void delete(String runId) {
        redis.delete(List.of(runKey(runId), casesKey(runId), reportedKey(runId)));
        redis.opsForZSet().remove(INDEX, runId);
        redis.opsForZSet().remove(QUEUED_INDEX, runId);
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
