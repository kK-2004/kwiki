package com.kwiki.infrastructure.redis;

import com.kwiki.eval.EvalInstanceId;
import com.kwiki.eval.EvalWorkQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisStreamCommands;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Redis Stream 上的评测执行任务队列：stream {@code kwiki:eval:queue}、消费组
 * {@code kwiki-eval-workers}、消费者名 = 实例标识。XREADGROUP 阻塞读取保证一条消息
 * 只被一个实例消费，XACK 在认领归属后确认；读取后未确认的消息（实例在认领过程中宕机）
 * 空闲超过 {@link #PENDING_MIN_IDLE} 后由认领线程经 XPENDING + XCLAIM 接管重新处理
 * （Spring Data Redis 3.5 未封装 XAUTOCLAIM）。
 */
public class RedisEvalWorkQueue implements EvalWorkQueue {

    private static final Logger log = LoggerFactory.getLogger(RedisEvalWorkQueue.class);
    static final String KEY = "kwiki:eval:queue";
    static final String GROUP = "kwiki-eval-workers";
    static final Duration PENDING_MIN_IDLE = Duration.ofSeconds(60);
    private static final int PENDING_SCAN = 10;

    private final StringRedisTemplate redis;
    private final String consumer;
    private final Duration pendingMinIdle;
    private volatile boolean groupChecked;

    public RedisEvalWorkQueue(StringRedisTemplate redis, EvalInstanceId instanceId) {
        this(redis, instanceId, PENDING_MIN_IDLE);
    }

    /** 测试用：可缩短挂起回收阈值。 */
    RedisEvalWorkQueue(StringRedisTemplate redis, EvalInstanceId instanceId, Duration pendingMinIdle) {
        this.redis = redis;
        this.consumer = instanceId.get();
        this.pendingMinIdle = pendingMinIdle;
    }

    private StreamOperations<String, Object, Object> stream() {
        return redis.opsForStream();
    }

    /** 消费组不存在时创建（从 0 开始，保证历史消息可投递）；stream 尚不存在时本轮跳过。 */
    private void ensureGroup() {
        if (groupChecked) return;
        try {
            stream().createGroup(KEY, ReadOffset.from("0"), GROUP);
            groupChecked = true;
        } catch (RuntimeException e) {
            // BUSYGROUP = 组已存在；stream 不存在（首次部署尚无任务）时下轮再试
            if (String.valueOf(e.getMessage()).contains("BUSYGROUP")) groupChecked = true;
            else log.debug("eval queue group not created yet reason={}", e.getMessage());
        }
    }

    @Override
    public void enqueue(String runId) {
        stream().add(KEY, Map.of("runId", runId));
    }

    @Override
    public ClaimedTask claim(Duration block) {
        ensureGroup();
        ClaimedTask reclaimed = reclaimPending();
        if (reclaimed != null) return reclaimed;
        try {
            List<MapRecord<String, Object, Object>> records = stream().read(
                    Consumer.from(GROUP, consumer),
                    StreamReadOptions.empty().block(block).count(1),
                    StreamOffset.create(KEY, ReadOffset.lastConsumed()));
            if (records == null || records.isEmpty()) return null;
            return toTask(records.getFirst());
        } catch (RuntimeException e) {
            // 消费组或 stream 尚未建立（NOGROUP）：重置组标记，下轮 ensureGroup 后再试
            if (String.valueOf(e.getMessage()).contains("NOGROUP")) {
                groupChecked = false;
                return null;
            }
            throw e;
        }
    }

    /** 回收空闲超过阈值的挂起消息（原读取实例已失联）；一次最多接管一条。 */
    private ClaimedTask reclaimPending() {
        PendingMessages pending = stream().pending(KEY, GROUP, Range.unbounded(), PENDING_SCAN);
        if (pending == null) return null;
        for (PendingMessage message : pending) {
            if (message.getElapsedTimeSinceLastDelivery().compareTo(pendingMinIdle) < 0) continue;
            List<MapRecord<String, Object, Object>> claimed = typed().claim(KEY, GROUP, consumer,
                    RedisStreamCommands.XClaimOptions.minIdle(pendingMinIdle)
                            .ids(RecordId.of(message.getIdAsString())));
            if (!claimed.isEmpty()) return toTask(claimed.getFirst());
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private StreamOperations<String, Object, Object> typed() {
        return (StreamOperations<String, Object, Object>) (StreamOperations<?, ?, ?>) stream();
    }

    private ClaimedTask toTask(MapRecord<String, Object, Object> record) {
        Object runId = record.getValue().get("runId");
        return new ClaimedTask(record.getId().getValue(), String.valueOf(runId));
    }

    /** 确认并删除：已确认的消息不再需要保留，避免 stream 无限增长；两步原子执行。 */
    private static final RedisScript<Long> ACK_AND_DELETE = new DefaultRedisScript<>("""
            redis.call('XACK', KEYS[1], ARGV[1], ARGV[2])
            return redis.call('XDEL', KEYS[1], ARGV[2])
            """, Long.class);

    @Override
    public void ack(ClaimedTask task) {
        redis.execute(ACK_AND_DELETE, List.of(KEY), GROUP, task.id());
    }
}
