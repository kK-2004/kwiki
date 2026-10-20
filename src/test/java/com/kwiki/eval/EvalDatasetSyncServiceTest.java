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

        int items = new EvalDatasetSyncService(loader, report).sync();

        assertThat(items).isEqualTo(6); // 50q:1 + 100q:2 + 200q:3
        assertThat(report.datasets).containsExactly("kwiki-rag-50q", "kwiki-rag-100q", "kwiki-rag-200q");
        assertThat(report.itemIds).contains("kwiki-rag-50q:a", "kwiki-rag-100q:b", "kwiki-rag-200q:c");
    }

    @Test
    void refusesWithoutLangfuseCredentials() {
        var report = new InMemoryReport();
        report.available = false;
        assertThatThrownBy(() -> new EvalDatasetSyncService(mock(EvalDatasetLoader.class), report).sync())
                .isInstanceOf(EvalException.class).hasMessageContaining("langfuse-not-configured");
    }
}
