package com.kwiki.rag.retrieval;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 以虚拟线程并发执行 BM25 与向量分支，并为每个分支设置截止时间。某个
 * 分支失败或超时则降级到另一分支；两分支都失败则抛出结构化检索
 * 错误。完成顺序无关紧要。
 */
@Component
public class ConcurrentRecallService {

    public record BranchOutcome(
            ChildRecallPort.Branch branch, List<ChunkHit> hits, String degradation) {}

    public static final class RetrievalBranchException extends RuntimeException {
        public RetrievalBranchException(String message) {
            super(message);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(ConcurrentRecallService.class);
    private static final int MAX_REASON_CHARS = 300;

    private final ChildRecallPort bm25;
    private final ChildRecallPort vector;
    private final ExecutorService executor =
            Executors.newThreadPerTaskExecutor(
                    Thread.ofVirtual().name("retrieval-worker-", 0).factory());

    public ConcurrentRecallService(
            @Qualifier("bm25RecallAdapter") ChildRecallPort bm25Adapter,
            @Qualifier("vectorRecallAdapter") ChildRecallPort vectorAdapter) {
        this.bm25 = bm25Adapter;
        this.vector = vectorAdapter;
    }

    public List<BranchOutcome> recall(
            String query, float[] vector, ScopeFilter scope, int topK, long timeout) {
        return recall(query, vector, scope, topK, timeout, RetrievalStrategy.HYBRID);
    }

    public List<BranchOutcome> recall(
            String query,
            float[] embedding,
            ScopeFilter scope,
            int topK,
            long timeout,
            RetrievalStrategy strategy) {
        var run = com.kwiki.rag.orchestration.RunContext.current();
        if (run != null) run.authorize();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
        var futures = new java.util.LinkedHashMap<ChildRecallPort.Branch, Future<List<ChunkHit>>>();
        if (strategy != RetrievalStrategy.VECTOR)
            futures.put(
                    ChildRecallPort.Branch.BM25,
                    executor.submit(() -> bm25.search(query, null, scope, topK)));
        if (strategy != RetrievalStrategy.BM25 && embedding != null)
            futures.put(
                    ChildRecallPort.Branch.VECTOR,
                    executor.submit(() -> vector.search(query, embedding, scope, topK)));
        Runnable cancel = () -> futures.values().forEach(f -> f.cancel(true));
        AutoCloseable registration = run == null ? () -> {} : run.onCancel(cancel);
        try (registration) {
            List<BranchOutcome> results = new ArrayList<>();
            List<String> reasons = new ArrayList<>();
            for (var entry : futures.entrySet()) {
                if (run != null) run.check();
                try {
                    results.add(
                            new BranchOutcome(
                                    entry.getKey(),
                                    entry.getValue()
                                            .get(
                                                    Math.max(1, deadline - System.nanoTime()),
                                                    TimeUnit.NANOSECONDS),
                                    null));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new com.kwiki.rag.orchestration.RunFailure("cancelled");
                } catch (Exception e) {
                    // 降级标记保持稳定（调用方按前缀判断），具体原因写日志并带进双路失败的异常信息
                    String reason = describe(e, timeout);
                    reasons.add(entry.getKey().name().toLowerCase() + "=" + reason);
                    log.warn("retrieval branch failed branch={} topK={} reason={}", entry.getKey(), topK, reason);
                    results.add(
                            new BranchOutcome(
                                    entry.getKey(),
                                    List.of(),
                                    entry.getKey().name().toLowerCase() + "-degraded"));
                }
            }
            if (strategy != RetrievalStrategy.BM25 && embedding == null) {
                reasons.add("vector=no-query-embedding");
                results.add(
                        new BranchOutcome(
                                ChildRecallPort.Branch.VECTOR,
                                List.of(),
                                "vector-degraded:no-query-embedding"));
            }
            if (results.stream().noneMatch(o -> o.degradation() == null))
                throw new RetrievalBranchException("both branches failed: " + String.join(", ", reasons));
            return results;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RetrievalBranchException("retrieval-failed");
        } finally {
            cancel.run();
        }
    }

    /** 失败类型与根因：超时写明时限，异常取最内层原因（脱敏、截断）。 */
    private static String describe(Exception e, long timeoutMs) {
        if (e instanceof TimeoutException) return "timeout(" + timeoutMs + "ms)";
        Throwable root = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String message = root.getMessage() == null ? "" : root.getMessage().replaceAll("\\s+", " ").strip();
        message = com.kwiki.infrastructure.observability.SecretRedaction.redact(message);
        if (message.length() > MAX_REASON_CHARS) message = message.substring(0, MAX_REASON_CHARS) + "…";
        return "error(" + root.getClass().getSimpleName() + (message.isEmpty() ? "" : ": " + message) + ")";
    }

    @jakarta.annotation.PreDestroy
    public void close() {
        executor.shutdownNow();
    }
}
