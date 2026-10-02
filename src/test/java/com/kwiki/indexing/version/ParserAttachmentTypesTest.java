package com.kwiki.indexing.version;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwiki.indexing.parse.plugin.*;
import com.kwiki.indexing.search.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ParserAttachmentTypesTest {
    @Test
    void 第三方类型与图片去重_非法类型拒绝且错误包含解析器身份() {
        var plugin = plugin(Set.of("text/plain", "image/png", "application/vnd.test+zip"));
        assertThat(ParserAttachmentTypes.forParser(plugin)).containsExactly("application/vnd.test+zip", "image/gif", "image/jpeg", "image/png", "image/webp", "text/plain");
        for (String invalid : List.of("Text/plain", "text/plain'", "text/plain; charset=utf-8", "", "text/ plain")) {
            assertThatThrownBy(() -> ParserAttachmentTypes.forParser(plugin(Set.of(invalid))))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("third-parser");
        }
    }
    @Test
    @SuppressWarnings("unchecked")
    void 扫描与校验SQL均按第三方插件类型生成占位符_保留截止及知识库参数顺序() {
        var registry = new DocumentParserRegistry(List.of(plugin(Set.of("text/plain"))));
        var types = ParserAttachmentTypes.forParser(registry.require("third-parser"));
        var jdbc = mock(JdbcTemplate.class);
        List<Object[]> calls = new ArrayList<>();
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(invocation -> {
            calls.add(invocation.getRawArguments()); return List.of();
        });
        var scanner = new FixedRangeRebuildScanner(mock(SearchIndexRebuildRunRepository.class),
                mock(SearchIndexRebuildRangeRepository.class), mock(SearchIndexVersionRepository.class), jdbc,
                mock(RebuildTargetEnqueuer.class), mock(PlatformTransactionManager.class),
                IndexVersionWriteServiceTest.properties(true), registry);
        var range = new SearchIndexRebuildRange(7L, "ATTACHMENT", 1, 100);
        ReflectionTestUtils.invokeMethod(scanner, "attachmentRows", range, types, 2, 42L);
        assertThat((String)calls.get(0)[0]).contains("IN (?,?,?,?,?)").contains("e.id>?").doesNotContain("application/pdf").doesNotContain("text/plain");
        var expected = new ArrayList<Object>();
        expected.add(range.getLastSeenId()); expected.add(range.getMaxId()); expected.addAll(types);
        expected.add(42L); expected.add(2); expected.add(2); expected.add(IndexVersionWriteServiceTest.properties(true).rebuild().batchSize());
        assertThat((Object[])calls.get(0)[2]).containsExactlyElementsOf(expected);
        var service = new SearchIndexValidationService(mock(SearchIndexVersionRepository.class),
                mock(SearchIndexRebuildRunRepository.class), mock(SearchIndexRebuildRangeRepository.class),
                mock(SearchIndexValidationReportRepository.class), mock(ElasticsearchIndexManager.class),
                mock(ChunkMappingBuilder.class), new ObjectMapper(), jdbc, registry);
        var version = SearchIndexVersion.bootstrapped(2, "chunks-v2", new EditableIndexConfig("third-parser",
                "kwiki-chunk-1", "default", "model", 1024, "kwiki-schema-1"), "hash");
        ReflectionTestUtils.invokeMethod(service, "effectiveResources", version);
        assertThat((String)calls.get(2)[0]).contains("IN (?,?,?,?,?)").doesNotContain("application/pdf").doesNotContain("text/plain");
        var validationExpected = new ArrayList<Object>(types); validationExpected.add(2); validationExpected.add(2);
        assertThat((Object[])calls.get(2)[2]).containsExactlyElementsOf(validationExpected);
    }
    @Test
    void 未注册解析器校验显式失败() {
        var service = new SearchIndexValidationService(mock(SearchIndexVersionRepository.class),
                mock(SearchIndexRebuildRunRepository.class), mock(SearchIndexRebuildRangeRepository.class),
                mock(SearchIndexValidationReportRepository.class), mock(ElasticsearchIndexManager.class),
                mock(ChunkMappingBuilder.class), new ObjectMapper(), mock(JdbcTemplate.class), new DocumentParserRegistry(List.of()));
        var version = SearchIndexVersion.bootstrapped(2, "chunks-v2", new EditableIndexConfig("missing-parser",
                "kwiki-chunk-1", "default", "model", 1024, "kwiki-schema-1"), "hash");
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "effectiveResources", version)).hasMessageContaining("missing-parser");
    }
    private static ParserTestFixtures.FakeParser plugin(Set<String> types) {
        return new ParserTestFixtures.FakeParser("third-parser", types, Set.of(), true);
    }
}
