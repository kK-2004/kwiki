package com.kwiki.graph.build;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 进度上报节流：距上次上报满一个间隔才放行，避免逐块写库（缓存命中时一秒可完成上千块）。
 * 慢速调用时每完成一块都能及时反映到界面上。并发调用安全：同一间隔内只有一个调用方拿到放行。
 */
public final class ProgressThrottle {

    /** 默认间隔：界面轮询为秒级，1 秒足够及时且写库量有界。 */
    public static final long DEFAULT_INTERVAL_NANOS = 1_000_000_000L;

    private final long intervalNanos;
    private final LongSupplier clock;
    private final AtomicLong lastReport;

    public ProgressThrottle() {
        this(DEFAULT_INTERVAL_NANOS, System::nanoTime);
    }

    ProgressThrottle(long intervalNanos, LongSupplier clock) {
        this.intervalNanos = intervalNanos;
        this.clock = clock;
        // 第一次完成立即上报
        this.lastReport = new AtomicLong(clock.getAsLong() - intervalNanos);
    }

    /** 是否该上报；返回 true 时同时记下本次上报时间。 */
    public boolean due() {
        long now = clock.getAsLong();
        long last = lastReport.get();
        return now - last >= intervalNanos && lastReport.compareAndSet(last, now);
    }
}
