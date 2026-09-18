package com.kwiki.indexing.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.cluster.ElasticsearchClusterClient;
import co.elastic.clients.elasticsearch.cluster.HealthResponse;
import co.elastic.clients.elasticsearch._types.HealthStatus;
import co.elastic.clients.elasticsearch.nodes.ElasticsearchNodesClient;
import co.elastic.clients.elasticsearch.nodes.FileSystem;
import co.elastic.clients.elasticsearch.nodes.FileSystemTotal;
import co.elastic.clients.elasticsearch.nodes.NodesStatsResponse;
import co.elastic.clients.elasticsearch.nodes.Stats;
import com.kwiki.indexing.config.IndexingProperties;
import com.kwiki.testutil.StandardTestProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 容量预检契约：一切无法确认的情形（客户端缺失、集群 RED、节点统计
 * 缺失、水位不足、预算超限、异常、超时）都失败关闭；结论脱敏，只含
 * 布尔判定、百分比与异常类名。
 */
class ElasticsearchCapacityGuardTest {

    private ElasticsearchClient client;
    private ElasticsearchClusterClient cluster;
    private ElasticsearchNodesClient nodes;
    private HealthResponse health;
    private NodesStatsResponse stats;
    private ElasticsearchCapacityGuard guard;

    @BeforeEach
    void setUp() throws Exception {
        client = mock(ElasticsearchClient.class);
        cluster = mock(ElasticsearchClusterClient.class);
        nodes = mock(ElasticsearchNodesClient.class);
        health = mock(HealthResponse.class);
        stats = mock(NodesStatsResponse.class);
        when(client.cluster()).thenReturn(cluster);
        when(client.nodes()).thenReturn(nodes);
        doReturn(health).when(cluster).health(anyFn());
        when(nodes.stats(any(java.util.function.Function.class))).thenReturn(stats);
        when(health.timedOut()).thenReturn(false);
        when(health.status()).thenReturn(HealthStatus.Green);

        guard = new ElasticsearchCapacityGuard(StandardTestProperties.providerOf(client),
                indexingProperties());
    }

    private static IndexingProperties indexingProperties() {
        return new IndexingProperties(null, null, rebuildDefaults(), catchupDefaults(),
                new IndexingProperties.Capacity(20, 8, Duration.ofSeconds(5)),
                new IndexingProperties.Management(false));
    }

    private static IndexingProperties.Rebuild rebuildDefaults() {
        return new IndexingProperties.Rebuild(50, 2, 20);
    }

    @SuppressWarnings("unchecked")
    private static <B, T> java.util.function.Function<B, co.elastic.clients.util.ObjectBuilder<T>> anyFn() {
        return any(java.util.function.Function.class);
    }

    private static IndexingProperties.Catchup catchupDefaults() {
        return new IndexingProperties.Catchup(100, Duration.ofSeconds(30));
    }

    private void nodeWith(long totalBytes, long availableBytes) {
        Stats node = mock(Stats.class);
        FileSystem fs = mock(FileSystem.class);
        FileSystemTotal fsTotal = mock(FileSystemTotal.class);
        when(node.fs()).thenReturn(fs);
        when(fs.total()).thenReturn(fsTotal);
        when(fsTotal.totalInBytes()).thenReturn(totalBytes);
        when(fsTotal.availableInBytes()).thenReturn(availableBytes);
        when(stats.nodes()).thenReturn(Map.of("node-1", node));
    }

    @Test
    void missingClientFailsClosed() {
        ElasticsearchCapacityGuard offline = new ElasticsearchCapacityGuard(
                StandardTestProperties.nullProvider(), indexingProperties());

        var result = offline.preflight(1);

        assertThat(result.ok()).isFalse();
        assertThat(result.reason()).isEqualTo("elasticsearch unavailable");
    }

    @Test
    void redClusterFailsClosed() throws Exception {
        when(health.status()).thenReturn(HealthStatus.Red);

        var result = guard.preflight(1);

        assertThat(result.ok()).isFalse();
        assertThat(result.reason()).isEqualTo("cluster health: red");
    }

    @Test
    void emptyNodeStatsFailClosed() {
        when(stats.nodes()).thenReturn(Map.of());

        assertThat(guard.preflight(1).reason()).isEqualTo("no nodes reported filesystem stats");
    }

    @Test
    void lowHeadroomFailsClosed() throws Exception {
        nodeWith(1_000_000_000_000L, 100_000_000_000L); // 10% < 20%

        var result = guard.preflight(1);

        assertThat(result.ok()).isFalse();
        assertThat(result.reason()).contains("storage headroom below 20%").contains("10%");
    }

    @Test
    void managedIndexBudgetIsEnforced() throws Exception {
        nodeWith(1_000_000_000_000L, 500_000_000_000L); // 50%

        var result = guard.preflight(8); // 8 + 1 > max 8

        assertThat(result.ok()).isFalse();
        assertThat(result.reason()).contains("managed index budget exceeded");
    }

    @Test
    void healthyClusterWithHeadroomPasses() {
        nodeWith(1_000_000_000_000L, 500_000_000_000L); // 50%

        var result = guard.preflight(1);

        assertThat(result.ok()).isTrue();
        assertThat(result.minHeadroomPercent()).isEqualTo(50);
        assertThat(result.reason()).isNull();
    }

    @Test
    void elasticsearchFailureIsSanitizedToExceptionClassName() throws Exception {
        doThrow(new RuntimeException("https://es-louis.ksite.xin:9207 conn refused",
                new java.io.IOException("socket closed"))).when(cluster).health(anyFn());

        var result = guard.preflight(1);

        assertThat(result.ok()).isFalse();
        assertThat(result.reason()).isEqualTo("IOException");
        assertThat(result.reason()).doesNotContain("es-louis").doesNotContain("socket");
    }

    @Test
    void slowClusterFailsClosedOnTimeout() throws Exception {
        doAnswer(invocation -> {
            // 必须明确超过 5s 预检期限，避免调度抖动让调用刚好在边界返回。
            Thread.sleep(6000);
            return health;
        }).when(cluster).health(anyFn());

        var result = guard.preflight(1);

        assertThat(result.ok()).isFalse();
        assertThat(result.reason()).isEqualTo("health check timeout");
    }
}
