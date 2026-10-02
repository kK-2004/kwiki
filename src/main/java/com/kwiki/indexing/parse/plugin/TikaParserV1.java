package com.kwiki.indexing.parse.plugin;

import com.kwiki.indexing.parse.DocumentParseService;
import com.kwiki.indexing.parse.StructuredDocument;
import com.kwiki.indexing.plugin.IndexCapability;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** 保持原有 Tika 页面与导入文本解析行为。 */
@Component
public class TikaParserV1 implements DocumentParserPlugin {
    public static final String ID = "kwiki-parse-1";
    private final DocumentParseService parser;

    public TikaParserV1(DocumentParseService parser) {
        this.parser = parser;
    }

    @Override
    public String id() { return ID; }

    @Override
    public String label() { return "tika-v1"; }

    @Override
    public Set<IndexCapability> requiredCapabilities() { return Set.of(); }

    @Override
    public Set<String> indexableAttachmentTypes() { return Set.of(); }

    @Override
    public StructuredDocument parsePage(PageParseRequest request) {
        return parser.parse(request.page().getTitle() + ".md", "text/markdown",
                new ByteArrayInputStream(request.revision().getMarkdown().getBytes(StandardCharsets.UTF_8)));
    }

    @Override
    public StructuredDocument parseAttachment(AttachmentParseRequest request) {
        throw new UnsupportedOperationException("tika-v1 does not index attachments");
    }

    @Override
    public StructuredDocument parseImport(ImportParseRequest request) {
        return parser.parse(request.fileName(), request.contentType(), new ByteArrayInputStream(request.content()));
    }
}
