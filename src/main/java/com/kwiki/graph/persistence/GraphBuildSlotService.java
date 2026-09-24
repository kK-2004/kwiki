package com.kwiki.graph.persistence;

import com.kwiki.infrastructure.redis.KwikiDistributedLocks;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 全局唯一的图构建/算法槽位：手动与定时任务共用同一入口。槽位由
 * common SDK 分布式锁保护，数据库活动 run 唯一键与租约/fencing 在其内
 * 再按库互斥；未获取槽位按 BUSY 处理，不并发执行第二个构建。
 */
@ConditionalOnProperty(name = "kwiki.graph.enabled", havingValue = "true")
@Service
public class GraphBuildSlotService {

    public static final String SLOT_LOCK = "graph:build-slot";

    /** 供测试替换的执行结果。 */
    public static final class BusyException extends RuntimeException {
        public BusyException() {
            super("图构建槽位忙：已有构建在执行");
        }
    }

    private final KwikiDistributedLocks locks;

    public GraphBuildSlotService(KwikiDistributedLocks locks) {
        this.locks = locks;
    }

    /**
     * 在全局槽位内执行；返回 empty 表示槽位被占用（BUSY），body 不执行。
     * 失去租约的旧 worker 由数据库 fencing 拦截，无法推进权威状态。
     */
    public <T> Optional<T> withBuildSlot(Supplier<T> body) {
        if (!locks.isAvailable()) {
            return Optional.empty();
        }
        T result = locks.tryRunReturning(SLOT_LOCK, Duration.ofSeconds(5), body::get);
        return Optional.ofNullable(result);
    }
}
