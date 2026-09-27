package com.kwiki.infrastructure.elasticsearch;

import com.kwiki.indexing.gray.ReadRouting;
import org.junit.jupiter.api.Test;

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
}
