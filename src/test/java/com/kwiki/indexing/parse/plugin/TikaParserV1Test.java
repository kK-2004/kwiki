package com.kwiki.indexing.parse.plugin;

import com.kwiki.indexing.parse.DocumentParseService;
import com.kwiki.indexing.parse.StructuredDocument;
import com.kwiki.wiki.domain.WikiPage;
import com.kwiki.wiki.domain.WikiPageRevision;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TikaParserV1Test {
    @Test
    void pageAndImportPreserveNamesMimeBytesAndDocument() {
        DocumentParseService service = mock(DocumentParseService.class);
        StructuredDocument expected = new StructuredDocument(List.of(), "正文");
        when(service.parse(anyString(), anyString(), any(InputStream.class))).thenAnswer(call -> {
            assertThat(((InputStream) call.getArgument(2)).readAllBytes())
                    .isEqualTo("正文".getBytes(StandardCharsets.UTF_8));
            return expected;
        });
        TikaParserV1 plugin = new TikaParserV1(service);
        WikiPage page = new WikiPage("page", 3L, null, "报告", WikiPage.TYPE_PAGE, 0, 1L);
        WikiPageRevision revision = new WikiPageRevision(7L, 1, "正文", "正文", "导入 报告.pdf", 1L);

        assertThat(plugin.parsePage(new PageParseRequest(page, revision))).isSameAs(expected);
        verify(service).parse(eq("报告.md"), eq("text/markdown"), any(InputStream.class));
        assertThat(plugin.parseImport(new ImportParseRequest(3L, "source.pdf", "application/pdf",
                "正文".getBytes(StandardCharsets.UTF_8)))).isSameAs(expected);
        verify(service).parse(eq("source.pdf"), eq("application/pdf"), any(InputStream.class));
        assertThat(plugin.id()).isEqualTo("kwiki-parse-1");
        assertThat(plugin.label()).isEqualTo("tika-v1");
        assertThat(plugin.availability().available()).isTrue();
        assertThat(plugin.requiredCapabilities()).isEmpty();
        assertThat(plugin.indexableAttachmentTypes()).isEmpty();
        assertThatThrownBy(() -> plugin.parseAttachment(new AttachmentParseRequest(null,
                () -> { throw new AssertionError("不应读取附件"); })))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
