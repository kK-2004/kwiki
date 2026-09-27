package com.kwiki.indexing.gray;

import com.kwiki.indexing.config.MultimodalSwitchReadiness;
import com.kwiki.wiki.api.ConflictException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ParserCatalogTest {

    @Test
    void 列出两个解析器并给出显示名与可用性() {
        MultimodalSwitchReadiness readiness = mock(MultimodalSwitchReadiness.class);
        when(readiness.ready()).thenReturn(false);
        when(readiness.missingConfiguration()).thenReturn(List.of("kwiki.indexing.multimodal.vision.base-url"));
        ParserCatalog catalog = new ParserCatalog(readiness);

        assertThat(catalog.options()).extracting(ParserCatalog.ParserOption::id)
                .containsExactly("kwiki-parse-1", "kwiki-parse-2");
        assertThat(catalog.options().get(0).label()).isEqualTo("tika-v1");
        assertThat(catalog.options().get(0).available()).isTrue();
        assertThat(catalog.options().get(1).label()).isEqualTo("pdfbox-v2");
        assertThat(catalog.options().get(1).available()).isFalse();
        assertThat(catalog.options().get(1).unavailableReason()).contains("base-url");
    }

    @Test
    void 不可用或未知解析器被拒绝() {
        MultimodalSwitchReadiness readiness = mock(MultimodalSwitchReadiness.class);
        when(readiness.ready()).thenReturn(false);
        when(readiness.missingConfiguration()).thenReturn(List.of("x"));
        ParserCatalog catalog = new ParserCatalog(readiness);
        assertThatThrownBy(() -> catalog.requireAvailable("kwiki-parse-2")).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> catalog.requireAvailable("nope")).isInstanceOf(ConflictException.class);
        catalog.requireAvailable("kwiki-parse-1");
        assertThat(ParserCatalog.label("kwiki-parse-2")).isEqualTo("pdfbox-v2");
        assertThat(ParserCatalog.label("custom")).isEqualTo("custom");
    }
}
