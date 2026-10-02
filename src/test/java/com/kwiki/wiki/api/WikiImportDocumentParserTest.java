package com.kwiki.wiki.api;

import com.kwiki.indexing.gray.GrayReadRoutes;
import com.kwiki.indexing.parse.plugin.*;
import com.kwiki.indexing.version.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.kwiki.testutil.StandardTestProperties.*;

class WikiImportDocumentParserTest {
    @Test
    void 全局选中第三方解析器_传入零知识库及原始导入输入() {
        var plugin = new ParserTestFixtures.FakeParser("third-parser", Set.of("text/plain"), Set.of(), true);
        var versions = mock(SearchIndexVersionRepository.class);
        when(versions.findBySelectedTrue()).thenReturn(Optional.of(version(plugin.id())));
        var strategy = new WikiImportDocumentParser(new DocumentParserRegistry(List.of(plugin)), providerOf(versions));
        byte[] bytes = {1, 2};
        assertThat(strategy.parse("report.txt", "text/plain", bytes)).isSameAs(plugin.document);
        assertThat(plugin.imported).isEqualTo(new ImportParseRequest(0, "report.txt", "text/plain", bytes));
    }
    @Test
    void 已切换灰度选择插件_其余知识库和全局选择已发布插件() {
        var global = new ParserTestFixtures.FakeParser(TikaParserV1.ID, Set.of(), Set.of(), true);
        var gray = new ParserTestFixtures.FakeParser("third-parser", Set.of(), Set.of(), true);
        var versions = mock(SearchIndexVersionRepository.class);
        when(versions.findBySelectedTrue()).thenReturn(Optional.of(version(global.id())));
        var routes = mock(GrayReadRoutes.class);
        when(routes.switchedParserFor(7)).thenReturn(Optional.of(gray.id()));
        when(routes.switchedParserFor(8)).thenReturn(Optional.empty());
        var strategy = new WikiImportDocumentParser(new DocumentParserRegistry(List.of(global, gray)), providerOf(versions), routes);
        byte[] bytes = {1};
        assertThat(strategy.parse(7, "a.txt", "text/plain", bytes)).isSameAs(gray.document);
        assertThat(gray.imported.kbId()).isEqualTo(7);
        assertThat(strategy.parse(8, "b.pdf", "application/pdf", bytes)).isSameAs(global.document);
        assertThat(global.imported.kbId()).isEqualTo(8);
        assertThat(strategy.parse("c.pdf", "application/pdf", bytes)).isSameAs(global.document);
        assertThat(global.imported.kbId()).isZero();
    }
    @Test
    void 缺省使用Tika_未知解析器显式失败() {
        var tika = new TikaParserV1(com.kwiki.indexing.parse.DocumentParseService.forTests());
        var strategy = new WikiImportDocumentParser(new DocumentParserRegistry(List.of(tika)), nullProvider());
        assertThat(strategy.parse("a.txt", "text/plain", "正文".getBytes(java.nio.charset.StandardCharsets.UTF_8)).plainText()).contains("正文");
        var versions = mock(SearchIndexVersionRepository.class);
        when(versions.findBySelectedTrue()).thenReturn(Optional.of(version("missing-parser")));
        var missing = new WikiImportDocumentParser(new DocumentParserRegistry(List.of(tika)), providerOf(versions));
        assertThatThrownBy(() -> missing.parse("a.txt", "text/plain", new byte[0])).hasMessageContaining("missing-parser");
    }
    private static SearchIndexVersion version(String parser) {
        return SearchIndexVersion.bootstrapped(1, "chunks-v1", new EditableIndexConfig(parser,
                "kwiki-chunk-1", "default", "model", 1024, "kwiki-schema-1"), "hash");
    }
}
