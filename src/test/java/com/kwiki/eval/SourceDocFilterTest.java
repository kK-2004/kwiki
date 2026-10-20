package com.kwiki.eval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SourceDocFilterTest {

    static EvalCase withSources(String source, List<Integer> sourceDocIds) {
        return new EvalCase("c", source, 50, "事实性问题", "Finance", "排放量是多少",
                List.of(List.of("10000吨")), false, sourceDocIds, List.of());
    }

    @Test
    void admitsOnlyPagesMappedToTheCaseSourceDocs() {
        var filter = new SourceDocFilter(Map.of(1L, 5, 2L, 6), withSources("dragonball", List.of(5)));

        assertThat(filter.admits("PAGE", 1L)).isTrue();
        assertThat(filter.admits("PAGE", 2L)).as("同领域别家文档").isFalse();
        assertThat(filter.admits("PAGE", 3L)).as("不在语料清单中的页面").isFalse();
        assertThat(filter.admits("FILE", 1L)).as("非页面资源").isFalse();
    }

    @Test
    void customCasesWithoutSourceDocsAreNotFiltered() {
        var filter = new SourceDocFilter(Map.of(), withSources("custom", List.of()));

        assertThat(filter.admits("PAGE", 2L)).isTrue();
        assertThat(filter.admits("FILE", 9L)).isTrue();
    }
}
