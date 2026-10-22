package com.kwiki.infrastructure.elasticsearch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * 检索慢请求诊断：对比客户端实际等待时间与 ES 自报的 took。两者接近说明 ES 本身慢；
 * wall 远大于 took 说明耗在网络链路、代理排队或客户端连接池等待上。超过阈值才记录。
 */
final class EsSearchTiming {

    private static final Logger log = LoggerFactory.getLogger(EsSearchTiming.class);
    static final long SLOW_MILLIS = 2_000;

    private EsSearchTiming() {
    }

    /** prepareMs：发请求前的准备（作用域过滤、生命周期排除等）；searchMs：ES 调用的客户端等待时间。 */
    static void logIfSlow(String branch, int topK, long startNanos, long requestNanos, Long tookMillis,
                          int hits, Throwable failure) {
        long end = System.nanoTime();
        long totalMs = TimeUnit.NANOSECONDS.toMillis(end - startNanos);
        if (totalMs < SLOW_MILLIS) return;
        long prepareMs = TimeUnit.NANOSECONDS.toMillis(Math.max(0, requestNanos - startNanos));
        long searchMs = TimeUnit.NANOSECONDS.toMillis(end - Math.max(startNanos, requestNanos));
        log.warn("es search slow branch={} topK={} totalMs={} prepareMs={} searchMs={} esTookMs={} hits={} "
                        + "interrupted={} failure={}",
                branch, topK, totalMs, prepareMs, searchMs, tookMillis == null ? "-" : tookMillis, hits,
                Thread.currentThread().isInterrupted(),
                failure == null ? "-" : failure.getClass().getSimpleName());
    }
}
