package com.kwiki.wiki.api;

import com.kwiki.indexing.config.MultimodalSwitchReadiness;
import com.kwiki.indexing.gray.GrayReadRoutes;
import com.kwiki.indexing.job.IndexingWorker;
import com.kwiki.indexing.multimodal.PdfMultimodalParser;
import com.kwiki.indexing.parse.DocumentParseService;
import com.kwiki.indexing.parse.StructuredDocument;
import com.kwiki.indexing.parse.StructuredTextAssembler;
import com.kwiki.indexing.version.SearchIndexVersionRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.Optional;

/** 按知识库所在已切换灰度或全局选中的解析代选择导入文本解析策略；图片语义由导入页的索引任务处理。 */
@Component
public class WikiImportDocumentParser {
    private final DocumentParseService parser;
    private final ObjectProvider<SearchIndexVersionRepository> versions;
    private final MultimodalSwitchReadiness readiness;
    private final GrayReadRoutes routes;

    public WikiImportDocumentParser(DocumentParseService parser,
                                    ObjectProvider<SearchIndexVersionRepository> versions,
                                    MultimodalSwitchReadiness readiness) {
        this(parser, versions, readiness, null);
    }

    @Autowired
    public WikiImportDocumentParser(DocumentParseService parser,
                                    ObjectProvider<SearchIndexVersionRepository> versions,
                                    MultimodalSwitchReadiness readiness,
                                    @Nullable GrayReadRoutes routes) {
        this.parser = parser;
        this.versions = versions;
        this.readiness = readiness;
        this.routes = routes;
    }

    /** 不区分知识库：始终使用全局已发布版本的解析器。 */
    public StructuredDocument parse(String fileName, String contentType, byte[] content) {
        return parseWith(null, fileName, contentType, content);
    }

    /** 按知识库选择解析器：属于已切换灰度时使用灰度解析器。 */
    public StructuredDocument parse(long kbId, String fileName, String contentType, byte[] content) {
        return parseWith(kbId, fileName, contentType, content);
    }

    private StructuredDocument parseWith(Long kbId, String fileName, String contentType, byte[] content) {
        if (!"application/pdf".equalsIgnoreCase(contentType)) {
            return parser.parse(fileName, contentType, new ByteArrayInputStream(content));
        }
        String selectedParser = parserVersionFor(kbId);
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

    /** 知识库属于已切换灰度 → 灰度解析器；否则 → 全局已发布版本的解析器。 */
    private String parserVersionFor(Long kbId) {
        if (kbId != null && routes != null) {
            Optional<String> gray = routes.switchedParserFor(kbId);
            if (gray.isPresent()) {
                return gray.get();
            }
        }
        SearchIndexVersionRepository repository = versions.getIfAvailable();
        return repository == null ? IndexingWorker.PARSER_VERSION
                : repository.findBySelectedTrue().map(version -> version.editableConfig().parserVersion())
                        .orElse(IndexingWorker.PARSER_VERSION);
    }
}
