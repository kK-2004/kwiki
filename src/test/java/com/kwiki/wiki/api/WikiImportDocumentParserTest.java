package com.kwiki.wiki.api;

import com.kwiki.indexing.config.MultimodalSwitchReadiness;
import com.kwiki.indexing.multimodal.PdfMultimodalParser;
import com.kwiki.indexing.parse.DocumentParseService;
import com.kwiki.indexing.parse.StructuredDocument;
import com.kwiki.indexing.version.EditableIndexConfig;
import com.kwiki.indexing.version.SearchIndexVersion;
import com.kwiki.indexing.version.SearchIndexVersionRepository;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WikiImportDocumentParserTest {
    @Test
    void selectedV1UsesTextParser() {
        DocumentParseService parser = mock(DocumentParseService.class);
        SearchIndexVersionRepository versions = mock(SearchIndexVersionRepository.class);
        StructuredDocument expected = new StructuredDocument(List.of(), "正文");
        when(versions.findBySelectedTrue()).thenReturn(Optional.of(version("kwiki-parse-1", 1)));
        when(parser.parse(org.mockito.ArgumentMatchers.eq("source.pdf"),
                org.mockito.ArgumentMatchers.eq("application/pdf"), any(InputStream.class)))
                .thenReturn(expected);
        WikiImportDocumentParser strategy = new WikiImportDocumentParser(parser,
                com.kwiki.testutil.StandardTestProperties.providerOf(versions),
                mock(MultimodalSwitchReadiness.class));

        assertThat(strategy.parse("source.pdf", "application/pdf", new byte[]{1}))
                .isSameAs(expected);
    }

    @Test
    void selectedV2UsesPdfBoxTextWhileLeavingImagesForSourceIndexing() {
        DocumentParseService parser = mock(DocumentParseService.class);
        SearchIndexVersionRepository versions = mock(SearchIndexVersionRepository.class);
        MultimodalSwitchReadiness readiness = mock(MultimodalSwitchReadiness.class);
        when(versions.findBySelectedTrue()).thenReturn(Optional.of(version("kwiki-parse-2", 2)));
        byte[] pdf = new byte[]{1};
        when(parser.parsePdfMultimodal("source.pdf", "application/pdf", pdf)).thenReturn(
                new PdfMultimodalParser.PdfExtraction(List.of(
                        new PdfMultimodalParser.TextItem("图片前"),
                        new PdfMultimodalParser.ImageRefItem(0, 0),
                        new PdfMultimodalParser.TextItem("图片后")), List.of(), List.of()));
        WikiImportDocumentParser strategy = new WikiImportDocumentParser(parser,
                com.kwiki.testutil.StandardTestProperties.providerOf(versions), readiness);

        assertThat(strategy.parse("source.pdf", "application/pdf", pdf).plainText())
                .isEqualTo("图片前\n\n图片后");
        verify(readiness).requireReadyFor("kwiki-parse-2");
        verify(parser).parsePdfMultimodal("source.pdf", "application/pdf", pdf);
    }

    private static SearchIndexVersion version(String parser, int mapping) {
        return SearchIndexVersion.bootstrapped(1, "kwiki-chunks-v1",
                new EditableIndexConfig(parser, "kwiki-chunk-1", "default", "model", 1024,
                        mapping), "hash");
    }
}
