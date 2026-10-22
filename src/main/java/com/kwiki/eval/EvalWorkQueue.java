package com.kwiki.eval;

import java.time.Duration;

/**
 * 评测执行任务队列：开始 / 继续提交后入队，各实例的认领线程以消费组语义领取。
 * 一条消息任何时刻只会被一个实例读取；读取后未确认（实例在认领过程中宕机）的消息
 * 在空闲超过挂起阈值后被其他实例回收重新处理。
 */
public interface EvalWorkQueue {

    /** 入队一个待执行的 run。 */
    void enqueue(String runId);

    /**
     * 读取一条待执行任务（本实例消费者），阻塞至多 {@code block}；没有任务返回 null。
     * 顺带回收挂起超过阈值的任务（原读取实例已失联）。
     */
    ClaimedTask claim(Duration block);

    /** 确认任务已处理（跳过或已认领归属开始执行）。 */
    void ack(ClaimedTask task);

    /** 一条已读取的任务；id 为队列内的消息标识，runId 为待执行 run。 */
    record ClaimedTask(String id, String runId) {
    }
}
