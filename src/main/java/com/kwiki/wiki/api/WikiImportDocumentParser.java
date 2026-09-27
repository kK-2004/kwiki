package com.kwiki.wiki.api;

import com.kwiki.indexing.config.MultimodalSwitchReadiness;
import com.kwiki.indexing.job.IndexingWorker;
import com.kwiki.indexing.multimodal.PdfMultimodalParser;
import com.kwiki.indexing.parse.DocumentParseService;
import com.kwiki.indexing.parse.StructuredDocument;
import com.kwiki.indexing.parse.StructuredTextAssembler;
import com.kwiki.indexing.version.SearchIndexVersionRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;

/** 按当前选中的解析代选择导入文本解析策略；图片语义由导入页的索引任务处理。 */
@Component
public class WikiImportDocumentParser {
    private final DocumentParseService parser;
    private final ObjectProvider<SearchIndexVersionRepository> versions;
    private final MultimodalSwitchReadiness readiness;

    public WikiImportDocumentParser(DocumentParseService parser,
                                    ObjectProvider<SearchIndexVersionRepository> versions,
                                    MultimodalSwitchReadiness readiness) {
        this.parser = parser;
        this.versions = versions;
        this.readiness = readiness;
    }

    public StructuredDocument parse(String fileName, String contentType, byte[] content) {
        if (!"application/pdf".equalsIgnoreCase(contentType)) {
            return parser.parse(fileName, contentType, new ByteArrayInputStream(content));
        }
        SearchIndexVersionRepository repository = versions.getIfAvailable();
        String selectedParser = repository == null ? IndexingWorker.PARSER_VERSION
                : repository.findBySelectedTrue().map(version -> version.editableConfig().parserVersion())
                        .orElse(IndexingWorker.PARSER_VERSION);
        if (!IndexingWorker.PARSER_VERSION_MULTIMODAL.equals(selectedParser)) {
            return parser.parse(fileName, contentType, new ByteArrayInputStream(content));
        }
        readiness.requireReadyFor(selectedParser);
        PdfMultimodalParser.PdfExtraction extraction = parser.parsePdfMultimodal(
                fileName, contentType, content);
        StructuredTextAssembler assembler = new StructuredTextAssembler();
        for (PdfMultimodalParser.ContentItem item : extraction.items()) {
            if (item instanceof PdfMultimodalParser.TextItem text) {
                assembler.append(0, text.text());
            }
        }
        return assembler.build();
    }
}
