package com.kwiki.indexing.parse.plugin;

import com.kwiki.indexing.parse.DocumentParseService;
import com.kwiki.indexing.parse.StructuredDocument;
import com.kwiki.indexing.plugin.IndexCapability;
import com.kwiki.indexing.plugin.PluginAvailability;
import java.util.List;
import java.util.Set;

/** 调用方测试使用真实注册表及可记录输入的第三方解析器。 */
public final class ParserTestFixtures {
    private ParserTestFixtures() { }
    public static DocumentParserRegistry tika(DocumentParseService parser) {
        return new DocumentParserRegistry(List.of(new TikaParserV1(parser)));
    }
    public static DocumentParserRegistry availableBoth() {
        return new DocumentParserRegistry(List.of(new TikaParserV1(DocumentParseService.forTests()),
                new FakeParser(PdfboxMultimodalParserV2.ID, Set.of("application/pdf"), Set.of(IndexCapability.RESOURCE_IDS), true)));
    }
    public static class FakeParser implements DocumentParserPlugin {
        private final String id;
        private final Set<String> types;
        private final Set<IndexCapability> capabilities;
        private final boolean available;
        public PageParseRequest page;
        public AttachmentParseRequest attachment;
        public ImportParseRequest imported;
        public StructuredDocument document = new StructuredDocument(List.of(), "插件正文");
        public FakeParser(String id, Set<String> types, Set<IndexCapability> capabilities, boolean available) {
            this.id=id; this.types=types; this.capabilities=capabilities; this.available=available;
        }
        public String id() { return id; }
        public String label() { return id; }
        public PluginAvailability availability() { return available ? PluginAvailability.ok() : PluginAvailability.unavailable("测试不可用"); }
        public Set<String> indexableAttachmentTypes() { return types; }
        public Set<IndexCapability> requiredCapabilities() { return capabilities; }
        public StructuredDocument parsePage(PageParseRequest request) { page=request; return document; }
        public StructuredDocument parseAttachment(AttachmentParseRequest request) { attachment=request; request.content().get(); request.content().get(); return document; }
        public StructuredDocument parseImport(ImportParseRequest request) { imported=request; return document; }
    }
}
