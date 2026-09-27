package com.kwiki.infrastructure.elasticsearch;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.kwiki.indexing.gray.ReadRouting;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EsReadRoutingTest {

    @Test
    void 无路由时只查别名且不加过滤() {
        assertThat(EsReadRouting.indices(ReadRouting.none())).containsExactly("kwiki-chunks");
        assertThat(EsReadRouting.filter(ReadRouting.none())).isEmpty();
    }

    @Test
    void 有路由时同时查别名与灰度索引_并按索引限定知识库() {
        ReadRouting routing = new ReadRouting(Map.of("kwiki-chunks-v4", Set.of(7L, 9L)));
        assertThat(EsReadRouting.indices(routing)).containsExactly("kwiki-chunks", "kwiki-chunks-v4");
        String query = EsReadRouting.filter(routing).orElseThrow().toString();
        // 灰度索引分支：_index = v4 且 kbId ∈ {7,9}
        assertThat(query).contains("kwiki-chunks-v4").contains("_index").contains("kbId");
        // 别名分支：排除灰度索引，并排除已切换的知识库
        assertThat(query).contains("must_not");
    }

    @Test
    void 过滤条件结构_一个别名分支加每个灰度索引一个分支() {
        ReadRouting routing = new ReadRouting(Map.of(
                "kwiki-chunks-v4", Set.of(7L, 9L),
                "kwiki-chunks-v5", Set.of(11L)));
        assertThat(EsReadRouting.indices(routing))
                .containsExactly("kwiki-chunks", "kwiki-chunks-v4", "kwiki-chunks-v5");

        BoolQuery root = EsReadRouting.filter(routing).orElseThrow().bool();
        assertThat(root.minimumShouldMatch()).isEqualTo("1");
        assertThat(root.should()).hasSize(1 + 2);

        // 别名分支：两个 must_not——排除灰度索引、排除已切换知识库
        BoolQuery alias = root.should().get(0).bool();
        assertThat(alias.mustNot()).hasSize(2);
        Query notIndex = alias.mustNot().get(0);
        assertThat(notIndex.terms().field()).isEqualTo("_index");
        assertThat(strings(notIndex.terms().terms().value()))
                .containsExactlyInAnyOrder("kwiki-chunks-v4", "kwiki-chunks-v5");
        Query notKb = alias.mustNot().get(1);
        assertThat(notKb.terms().field()).isEqualTo("kbId");
        assertThat(longs(notKb.terms().terms().value())).containsExactlyInAnyOrder(7L, 9L, 11L);

        // 灰度分支：_index term + kbId terms
        Set<String> seenIndices = new HashSet<>();
        for (Query branch : root.should().subList(1, root.should().size())) {
            List<Query> filters = branch.bool().filter();
            assertThat(filters).hasSize(2);
            assertThat(filters.get(0).term().field()).isEqualTo("_index");
            String index = filters.get(0).term().value().stringValue();
            seenIndices.add(index);
            assertThat(filters.get(1).terms().field()).isEqualTo("kbId");
            assertThat(longs(filters.get(1).terms().terms().value()))
                    .containsExactlyInAnyOrderElementsOf(routing.kbIdsByGrayIndex().get(index));
        }
        assertThat(seenIndices).containsExactlyInAnyOrder("kwiki-chunks-v4", "kwiki-chunks-v5");
    }

    @Test
    void 路由快照深拷贝_调用方修改原集合不影响快照() {
        Set<Long> kbs = new HashSet<>(Set.of(7L));
        ReadRouting routing = new ReadRouting(Map.of("kwiki-chunks-v4", kbs));
        kbs.add(99L);
        assertThat(routing.switchedKbIds()).containsExactly(7L);
    }

    private static List<String> strings(List<FieldValue> values) {
        return values.stream().map(FieldValue::stringValue).toList();
    }

    private static List<Long> longs(List<FieldValue> values) {
        return values.stream().map(FieldValue::longValue).toList();
    }
}
