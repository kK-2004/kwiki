package com.kwiki.usage;

import com.kwiki.infrastructure.redis.KwikiDistributedLocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** 每日 03:30（Asia/Shanghai）在分布式锁下清理过期用量：聚合行保留 retention-days，明细保留 call-retention-days。 */
@Component
@EnableScheduling
public class ModelUsageRetentionJob {

    static final String LOCK_PURPOSE = "model-usage-retention";
    private static final Logger log = LoggerFactory.getLogger(ModelUsageRetentionJob.class);

    private final ModelUsageRepository repository;
    private final KwikiDistributedLocks locks;
    private final int retentionDays;
    private final int callRetentionDays;
    private final Clock clock;

    @Autowired
    public ModelUsageRetentionJob(ModelUsageRepository repository, KwikiDistributedLocks locks,
                                  @Value("${kwiki.model-usage.retention-days:180}") int retentionDays,
                                  @Value("${kwiki.model-usage.call-retention-days:7}") int callRetentionDays) {
        this(repository, locks, retentionDays, callRetentionDays, Clock.systemUTC());
    }

    ModelUsageRetentionJob(ModelUsageRepository repository, KwikiDistributedLocks locks,
                           int retentionDays, int callRetentionDays, Clock clock) {
        this.repository = repository;
        this.locks = locks;
        this.retentionDays = Math.max(1, retentionDays);
        this.callRetentionDays = Math.max(1, callRetentionDays);
        this.clock = clock;
    }

    @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Shanghai")
    public void daily() {
        boolean ran = locks.tryRun(LOCK_PURPOSE, Duration.ZERO, () -> {
            try {
                runOnce();
            } catch (RuntimeException e) {
                log.error("model usage retention aborted: {}", e.getMessage(), e);
            }
        });
        if (!ran) log.info("model usage retention skipped: lock unavailable or held by another instance");
    }

    void runOnce() {
        Instant now = clock.instant();
        int minutes = repository.deleteMinutesBefore(now.minus(Duration.ofDays(retentionDays)));
        int calls = repository.deleteCallsBefore(now.minus(Duration.ofDays(callRetentionDays)));
        log.info("model usage retention deleted minuteRows={} callRows={}", minutes, calls);
    }
}
