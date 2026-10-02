package com.kwiki.wiki.api;

import com.kwiki.indexing.parse.plugin.TikaParserV1;
import com.kwiki.indexing.parse.plugin.ImportParseRequest;
import com.kwiki.indexing.parse.plugin.DocumentParserRegistry;
import com.kwiki.indexing.gray.GrayReadRoutes;
import com.kwiki.indexing.parse.StructuredDocument;
import com.kwiki.indexing.version.SearchIndexVersionRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** 按知识库所在已切换灰度或全局选中的解析代选择导入文本解析策略；图片语义由导入页的索引任务处理。 */
@Component
public class WikiImportDocumentParser {
    private final DocumentParserRegistry parserRegistry;
    private final ObjectProvider<SearchIndexVersionRepository> versions;
    private final GrayReadRoutes routes;

    public WikiImportDocumentParser(DocumentParserRegistry parserRegistry,
                                    ObjectProvider<SearchIndexVersionRepository> versions) {
        this(parserRegistry, versions, null);
    }

    @Autowired
    public WikiImportDocumentParser(DocumentParserRegistry parserRegistry,
                                    ObjectProvider<SearchIndexVersionRepository> versions,
                                    @Nullable GrayReadRoutes routes) {
        this.parserRegistry = parserRegistry;
        this.versions = versions;
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
        return parserRegistry.require(parserVersionFor(kbId)).parseImport(
                new ImportParseRequest(kbId == null ? 0 : kbId,
                        fileName, contentType, content));
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
        return repository == null ? TikaParserV1.ID
                : repository.findBySelectedTrue().map(version -> version.editableConfig().parserVersion())
                        .orElse(TikaParserV1.ID);
    }
}
