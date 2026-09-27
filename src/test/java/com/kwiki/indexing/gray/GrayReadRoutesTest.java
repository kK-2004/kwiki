package com.kwiki.indexing.gray;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class GrayReadRoutesTest {

    @Test
    void 只把已切换灰度的知识库路由到灰度物理索引() {
        InMemoryGrayReleaseStore store = new InMemoryGrayReleaseStore();
        long switched = store.insert("a", "kwiki-parse-2", 4, "admin");
        store.insertKbs(switched, List.of(7L, 9L));
        store.setStatus(switched, GrayReleaseStatus.SWITCHED, null);
        long synced = store.insert("b", "kwiki-parse-2", 5, "admin");
        store.insertKbs(synced, List.of(11L));
        store.setStatus(synced, GrayReleaseStatus.SYNCED, null);

        GrayReadRoutes routes = new GrayReadRoutes(store);
        ReadRouting routing = routes.current();

        assertThat(routing.kbIdsByGrayIndex()).isEqualTo(Map.of("kwiki-chunks-v4", Set.of(7L, 9L)));
        assertThat(routing.switchedKbIds()).containsExactlyInAnyOrder(7L, 9L);
        assertThat(routes.switchedParserFor(7L)).contains("kwiki-parse-2");
        assertThat(routes.switchedParserFor(11L)).isEmpty();
    }

    @Test
    void 无切换灰度时路由为空() {
        assertThat(new GrayReadRoutes(new InMemoryGrayReleaseStore()).current().isEmpty()).isTrue();
    }
}
