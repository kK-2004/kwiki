package com.kwiki.graph.persistence;

import com.kwiki.infrastructure.redis.KwikiDistributedLocks;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 持久化的每日 02:00（Asia/Shanghai 可配）全量构建入口。日期主键保证多实例
 * 当天最多触发一次；当日漏跑在应用恢复后最多补跑一次；仍有未完成批次时
 * 先恢复该批次并记录 SKIPPED_ACTIVE 及关联任务，不创建重复批次。默认不
 * 开启变化阈值触发。
 */
@ConditionalOnProperty(name = "kwiki.graph.enabled", havingValue = "true")
@Component
public class GraphBuildScheduler {

    public enum Outcome {TRIGGERED, CATCH_UP, SKIPPED_ACTIVE, ALREADY_DONE, LOCK_NOT_ACQUIRED}

    private static final Pattern DAILY_CRON = Pattern.compile(
            "^0\\s+(\\d{1,2})\\s+(\\d{1,2})\\s+\\*\\s+\\*\\s+\\*$");

    private final GraphBuildBatchService batches;
    private final GraphBuildRepository repository;
    private final GraphScheduledBatchFactory factory;
    private final KwikiDistributedLocks locks;
    private final String scheduleCron;
    private final ZoneId scheduleZone;
    private final Clock clock;

    @Autowired
    public GraphBuildScheduler(GraphBuildBatchService batches,
                               GraphBuildRepository repository,
                               GraphScheduledBatchFactory factory,
                               KwikiDistributedLocks locks,
                               @org.springframework.beans.factory.annotation.Value(
                                       "${kwiki.graph.schedule-cron:0 0 2 * * *}")
                               String scheduleCron,
                               @org.springframework.beans.factory.annotation.Value(
                                       "${kwiki.graph.schedule-zone:Asia/Shanghai}")
                               String scheduleZone) {
        this(batches, repository, factory, locks, scheduleCron, scheduleZone,
                Clock.systemDefaultZone());
    }

    GraphBuildScheduler(GraphBuildBatchService batches,
                        GraphBuildRepository repository,
                        GraphScheduledBatchFactory factory,
                        KwikiDistributedLocks locks,
                        String scheduleCron,
                        String scheduleZone,
                        Clock clock) {
        this.batches = batches;
        this.repository = repository;
        this.factory = factory;
        this.locks = locks;
        this.scheduleCron = scheduleCron;
        this.scheduleZone = ZoneId.of(scheduleZone);
        this.clock = clock;
    }

    @Scheduled(cron = "${kwiki.graph.schedule-cron:0 0 2 * * *}",
            zone = "${kwiki.graph.schedule-zone:Asia/Shanghai}")
    public void trigger() {
        triggerOnce(false);
    }

    /** 应用恢复后的当日漏跑补偿：触发时刻已过且当天无记录时最多补一次。 */
    public Outcome catchUpIfMissed() {
        LocalDate today = LocalDate.now(clock);
        if (repository.findScheduleTrigger(today).isPresent()) {
            return Outcome.ALREADY_DONE;
        }
        LocalTime triggerTime = dailyTriggerTime();
        if (triggerTime == null
                || LocalTime.now(clock.withZone(scheduleZone)).isBefore(triggerTime)) {
            return Outcome.ALREADY_DONE;
        }
        return triggerOnce(true);
    }

    Outcome triggerOnce(boolean catchUp) {
        LocalDate today = LocalDate.now(clock);
        if (!locks.isAvailable()) {
            // 锁服务不可用：按失败关闭处理，不无锁执行；下一轮/下次恢复再试。
            return Outcome.LOCK_NOT_ACQUIRED;
        }
        Boolean created = locks.tryRunReturning("graph:schedule",
                java.time.Duration.ofSeconds(10), () -> {
                    // 日期主键幂等：并发实例只允许一个插入成功。
                    if (repository.findScheduleTrigger(today).isPresent()) {
                        return Boolean.FALSE;
                    }
                    Optional<Long> active = repository.findActiveBatchId();
                    if (active.isPresent()) {
                        repository.insertScheduleTrigger(today, "SKIPPED_ACTIVE", active.get());
                        return Boolean.FALSE;
                    }
                    String status = catchUp ? "CATCH_UP" : "TRIGGERED";
                    repository.insertScheduleTrigger(today, status, null);
                    Optional<GraphBuildBatchRequest> request = factory.create(today);
                    if (request.isPresent()) {
                        batches.submit(request.get());
                    }
                    return Boolean.TRUE;
                });
        if (created == null) {
            return Outcome.LOCK_NOT_ACQUIRED;
        }
        if (!created) {
            return repository.findScheduleTrigger(today)
                    .filter(record -> "SKIPPED_ACTIVE".equals(record.status()))
                    .isPresent() ? Outcome.SKIPPED_ACTIVE : Outcome.ALREADY_DONE;
        }
        return catchUp ? Outcome.CATCH_UP : Outcome.TRIGGERED;
    }

    /** 解析 `0 m h * * *` 形式的每日 cron；其他形式不做漏跑补偿。 */
    private LocalTime dailyTriggerTime() {
        if (scheduleCron == null) {
            return null;
        }
        Matcher matcher = DAILY_CRON.matcher(scheduleCron.trim());
        if (!matcher.matches()) {
            return null;
        }
        return LocalTime.of(Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(1)));
    }
}
