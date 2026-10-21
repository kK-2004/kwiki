package com.kwiki.usage;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 定时把内存队列中的用量事件按「分钟 × 来源 × 步骤 × provider × 模型 × 用户」预聚合后写库，
 * 同时写入逐次调用明细。写库失败只记日志并丢弃本批（不重试，避免重复累加）；应用关闭时再 flush 一次。
 */
@Component
@EnableScheduling
public class ModelUsageFlusher {

    private static final Logger log = LoggerFactory.getLogger(ModelUsageFlusher.class);

    private final ModelUsageRecorder recorder;
    private final ModelUsageRepository repository;

    public ModelUsageFlusher(ModelUsageRecorder recorder, ModelUsageRepository repository) {
        this.recorder = recorder;
        this.repository = repository;
    }

    @Scheduled(fixedDelayString = "${kwiki.model-usage.flush-interval-ms:10000}")
    public synchronized void flush() {
        List<UsageEvent> events = recorder.drain();
        if (events.isEmpty() || !repository.available()) return;
        try {
            repository.upsertMinutes(aggregate(events));
            repository.insertCalls(events);
        } catch (RuntimeException e) {
            log.warn("model usage flush failed events={} reason={}", events.size(), e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        flush();
    }

    private record Key(Instant bucket, UsageSource source, String step, String provider, String model, long userId) {
    }

    static List<MinuteAggregate> aggregate(List<UsageEvent> events) {
        Map<Key, long[]> sums = new LinkedHashMap<>();
        for (UsageEvent e : events) {
            Key key = new Key(e.occurredAt().truncatedTo(ChronoUnit.MINUTES), e.source(), e.step(),
                    e.provider(), e.model(), e.userId());
            long[] sum = sums.computeIfAbsent(key, ignored -> new long[5]);
            sum[0] += 1;
            sum[1] += e.success() ? 0 : 1;
            sum[2] += e.inputTokens();
            sum[3] += e.outputTokens();
            sum[4] += e.latencyMs();
        }
        List<MinuteAggregate> aggregates = new ArrayList<>(sums.size());
        sums.forEach((k, s) -> aggregates.add(new MinuteAggregate(k.bucket(), k.source(), k.step(), k.provider(),
                k.model(), k.userId(), s[0], s[1], s[2], s[3], s[4])));
        return aggregates;
    }
}
