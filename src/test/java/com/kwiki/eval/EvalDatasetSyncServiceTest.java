package com.kwiki.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EvalDatasetSyncServiceTest {

    static EvalCase evalCase(String id, int tier) {
        return new EvalCase(id, "dragonball", tier, "事实性问题", "Finance", "问题" + id,
                List.of(List.of("甲")), false, List.of(1), List.of("要点"));
    }

    @Test
    void writesNestedItemsIntoThreeDatasets() {
        var loader = mock(EvalDatasetLoader.class);
        when(loader.load()).thenReturn(List.of(evalCase("a", 50), evalCase("b", 100), evalCase("c", 200)));
        var report = new InMemoryReport();

        var result = new EvalDatasetSyncService(loader, report).sync();

        assertThat(result.items()).isEqualTo(6); // 50q:1 + 100q:2 + 200q:3
        assertThat(result.written()).isEqualTo(6);
        assertThat(result.skipped()).isZero();
        assertThat(report.datasets).containsExactly("kwiki-rag-50q", "kwiki-rag-100q", "kwiki-rag-200q");
        assertThat(report.itemIds).contains("kwiki-rag-50q:a", "kwiki-rag-100q:b", "kwiki-rag-200q:c");
    }

    @Test
    void unchangedExistingItemsAreSkippedAndChangedOnesRewritten() {
        var loader = mock(EvalDatasetLoader.class);
        when(loader.load()).thenReturn(List.of(evalCase("a", 50)));
        var report = new InMemoryReport();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var a = evalCase("a", 50);
        // 50q 中已有完全相同的条目；100q 中已有但关键词不同；200q 中不存在
        report.existing.put("kwiki-rag-50q", java.util.Map.of("kwiki-rag-50q:a", stored(mapper, a, a.keywords())));
        report.existing.put("kwiki-rag-100q", java.util.Map.of("kwiki-rag-100q:a",
                stored(mapper, a, List.of(List.of("旧关键词")))));

        var result = new EvalDatasetSyncService(loader, report).sync();

        assertThat(result.items()).isEqualTo(3);
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.written()).isEqualTo(2);
        assertThat(report.itemIds).containsExactly("kwiki-rag-100q:a", "kwiki-rag-200q:a");
    }

    static EvalReportPort.StoredItem stored(com.fasterxml.jackson.databind.ObjectMapper mapper, EvalCase c,
                                            List<List<String>> keywords) {
        var input = new java.util.LinkedHashMap<String, Object>();
        input.put("query", c.query());
        input.put("category", c.category());
        input.put("domain", c.domain());
        var expected = new java.util.LinkedHashMap<String, Object>();
        expected.put("keywords", keywords);
        expected.put("negative", c.negative());
        var metadata = new java.util.LinkedHashMap<String, Object>();
        metadata.put("source", c.source());
        metadata.put("tier", c.tier());
        metadata.put("sourceDocIds", c.sourceDocIds());
        metadata.put("keypoints", c.keypoints());
        return new EvalReportPort.StoredItem(mapper.valueToTree(input), mapper.valueToTree(expected),
                mapper.valueToTree(metadata));
    }

    @Test
    void refusesWithoutLangfuseCredentials() {
        var report = new InMemoryReport();
        report.available = false;
        assertThatThrownBy(() -> new EvalDatasetSyncService(mock(EvalDatasetLoader.class), report).sync())
                .isInstanceOf(EvalException.class).hasMessageContaining("langfuse-not-configured");
    }
}
