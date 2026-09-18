package com.kwiki.indexing.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.HealthStatus;
import com.kwiki.indexing.config.IndexingProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * ES 健康与容量预检（任务 3.4）：创建/重建物理索引之前执行，
 * 全部失败关闭——无法确认健康或剩余容量时拒绝操作并返回脱敏原因。
 * 结果只包含布尔判定、百分比与异常类名，绝不包含端点、凭据或堆栈。
 */
@Component
public class ElasticsearchCapacityGuard {

    /** 脱敏的预检结论；reason 面向管理端展示。 */
    public record PreflightResult(boolean ok, String reason, Integer minHeadroomPercent) {

        static PreflightResult pass(Integer minHeadroomPercent) {
            return new PreflightResult(true, null, minHeadroomPercent);
        }

        static PreflightResult fail(String reason) {
            return new PreflightResult(false, reason, null);
        }
    }

    private final ObjectProvider<ElasticsearchClient> clientProvider;
    private final IndexingProperties properties;

    public ElasticsearchCapacityGuard(ObjectProvider<ElasticsearchClient> clientProvider,
                                      IndexingProperties properties) {
        this.clientProvider = clientProvider;
        this.properties = properties;
    }

    /**
     * @param managedIndexCount 当前未删除的受管物理索引数量
     *                          （预检评估"再创建一个"是否安全）
     */
    public PreflightResult preflight(int managedIndexCount) {
        ElasticsearchClient client = clientProvider.getIfAvailable();
        if (client == null) {
            return PreflightResult.fail("elasticsearch unavailable");
        }
        IndexingProperties.Capacity capacity = properties.capacity();
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return inspect(client, managedIndexCount, capacity);
                } catch (Exception failure) {
                    return PreflightResult.fail(sanitized(failure));
                }
            }).get(capacity.healthTimeout().toMillis() + 1, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            return PreflightResult.fail("health check timeout");
        } catch (Exception failure) {
            return PreflightResult.fail(sanitized(failure));
        }
    }

    private PreflightResult inspect(ElasticsearchClient client, int managedIndexCount,
                                    IndexingProperties.Capacity capacity) throws Exception {
        var health = client.cluster().health(request -> request);
        if (health.timedOut()) {
            return PreflightResult.fail("cluster health request timed out");
        }
        HealthStatus status = health.status();
        if (status == HealthStatus.Red) {
            return PreflightResult.fail("cluster health: red");
        }

        var stats = client.nodes().stats(request -> request.metric("fs"));
        Map<String, co.elastic.clients.elasticsearch.nodes.Stats> nodes = stats.nodes();
        if (nodes.isEmpty()) {
            return PreflightResult.fail("no nodes reported filesystem stats");
        }
        long minAvailablePercent = Long.MAX_VALUE;
        for (co.elastic.clients.elasticsearch.nodes.Stats node : nodes.values()) {
            Long total = node.fs() == null || node.fs().total() == null ? null
                    : node.fs().total().totalInBytes();
            Long available = node.fs() == null || node.fs().total() == null ? null
                    : node.fs().total().availableInBytes();
            if (total == null || available == null || total <= 0) {
                return PreflightResult.fail("node filesystem stats unavailable");
            }
            minAvailablePercent = Math.min(minAvailablePercent,
                    Math.floorDiv(available * 100, total));
        }
        if (minAvailablePercent < capacity.minStorageHeadroomPercent()) {
            return PreflightResult.fail("storage headroom below "
                    + capacity.minStorageHeadroomPercent() + "% (min observed "
                    + minAvailablePercent + "%)");
        }
        if (managedIndexCount + 1 > capacity.maxManagedIndices()) {
            return PreflightResult.fail("managed index budget exceeded (max "
                    + capacity.maxManagedIndices() + ")");
        }
        return PreflightResult.pass((int) minAvailablePercent);
    }

    private static String sanitized(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause.getCause() == null) {
                return cause.getClass().getSimpleName();
            }
        }
        return failure.getClass().getSimpleName();
    }
}
