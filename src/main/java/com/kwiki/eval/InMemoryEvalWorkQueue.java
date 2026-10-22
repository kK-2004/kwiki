package com.kwiki.eval;

import java.time.Duration;
import java.util.concurrent.ConcurrentLinkedQueue;

/** 未启用 Redis 时的进程内任务队列：语义与 Redis Stream 实现一致，应用重启即丢失。 */
public class InMemoryEvalWorkQueue implements EvalWorkQueue {

    private final ConcurrentLinkedQueue<ClaimedTask> queue = new ConcurrentLinkedQueue<>();

    @Override
    public void enqueue(String runId) {
        queue.add(new ClaimedTask(runId, runId));
    }

    @Override
    public ClaimedTask claim(Duration block) {
        long deadline = System.nanoTime() + block.toNanos();
        ClaimedTask task = queue.poll();
        while (task == null && System.nanoTime() < deadline) {
            try {
                Thread.sleep(Math.min(50, Math.max(1, block.toMillis())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            task = queue.poll();
        }
        return task;
    }

    @Override
    public void ack(ClaimedTask task) {
        // 进程内队列读取即出队，无需额外确认
    }
}
