package com.kwiki.indexing.parse.plugin;

import com.kwiki.indexing.config.MultimodalSwitchReadiness;
import com.kwiki.indexing.multimodal.MultimodalIndexingService;
import com.kwiki.indexing.multimodal.PdfMultimodalParser;
import com.kwiki.indexing.parse.DocumentParseService;
import com.kwiki.indexing.parse.StructuredDocument;
import com.kwiki.indexing.parse.StructuredTextAssembler;
import com.kwiki.indexing.plugin.IndexCapability;
import com.kwiki.indexing.plugin.PluginAvailability;
import com.kwiki.wiki.attach.AttachmentStorage;
import com.kwiki.wiki.attach.AttachmentStorageException;
import com.kwiki.wiki.domain.Attachment;
import com.kwiki.wiki.domain.SourceDocument;
import com.kwiki.wiki.persistence.AttachmentRepository;
import com.kwiki.wiki.persistence.SourceDocumentRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.Objects;
import java.util.Set;

/** PDFBox 文本解析与多模态页面、PDF 索引投影。 */
@Component
public class PdfboxMultimodalParserV2 implements DocumentParserPlugin {
    public static final String ID = "kwiki-parse-2";
    private final DocumentParseService parser;
    private final MultimodalSwitchReadiness readiness;
    private final ObjectProvider<MultimodalIndexingService> multimodal;
    private final ObjectProvider<SourceDocumentRepository> sources;
    private final AttachmentRepository attachments;
    private final AttachmentStorage storage;

    public PdfboxMultimodalParserV2(DocumentParseService parser, MultimodalSwitchReadiness readiness,
                                  ObjectProvider<MultimodalIndexingService> multimodal,
                                  ObjectProvider<SourceDocumentRepository> sources,
                                  AttachmentRepository attachments, AttachmentStorage storage) {
        this.parser = parser;
        this.readiness = readiness;
        this.multimodal = multimodal;
        this.sources = sources;
        this.attachments = attachments;
        this.storage = storage;
    }

    @Override
    public String id() { return ID; }

    @Override
    public String label() { return "pdfbox-v2"; }

    @Override
    public PluginAvailability availability() {
        if (multimodal.getIfAvailable() == null) {
            return PluginAvailability.unavailable("多模态服务未启用");
        }
        return readiness.ready() ? PluginAvailability.ok()
                : PluginAvailability.unavailable("缺少配置：" + String.join("、", readiness.missingConfiguration()));
    }

    @Override
    public Set<IndexCapability> requiredCapabilities() { return Set.of(IndexCapability.RESOURCE_IDS); }

    @Override
    public Set<String> indexableAttachmentTypes() { return Set.of("application/pdf"); }

    @Override
    public StructuredDocument parsePage(PageParseRequest request) {
        MultimodalIndexingService service = requireMultimodal();
        StructuredDocument imported = importedPdfDocument(request, service);
        if (imported != null) {
            return imported;
        }
        return service.buildPageDocument(request.page().getKbId(), request.page().getId(),
                request.revision().getId(), request.revision().getMarkdown());
    }

    /** 导入首修订使用同库已存储的 PDF 原件；后续编辑按页面 Markdown 解析。 */
    private StructuredDocument importedPdfDocument(PageParseRequest request, MultimodalIndexingService service) {
        SourceDocumentRepository repository = sources.getIfAvailable();
        if (repository == null || request.revision().getRevisionNo() != 1
                || request.revision().getChangeNote() == null
                || !request.revision().getChangeNote().startsWith("导入 ")) {
            return null;
        }
        for (SourceDocument source : repository.findByPageId(request.page().getId())) {
            if (!SourceDocument.REL_DERIVED_FROM.equals(source.getRelationship())) {
                continue;
            }
            Attachment attachment = attachments.findById(source.getAttachmentId()).orElse(null);
            if (attachment == null || !attachment.isStored()
                    || !Objects.equals(attachment.getKbId(), request.page().getKbId())
                    || !"application/pdf".equalsIgnoreCase(attachment.getContentType())) {
                continue;
            }
            Long fileId = attachment.getContentCenterFileId();
            if (fileId == null || fileId <= 0) {
                throw new AttachmentStorageException(AttachmentStorageException.Category.PERMANENT,
                        "import source has no content-center file id for indexing");
            }
            return service.buildPdfDocument(parser, request.page().getKbId(), attachment.getId(),
                    attachment.getFileName(), attachment.getContentType(), storage.readContent(fileId));
        }
        return null;
    }

    @Override
    public StructuredDocument parseAttachment(AttachmentParseRequest request) {
        MultimodalIndexingService service = requireMultimodal();
        Attachment attachment = request.attachment();
        return service.buildPdfDocument(parser, attachment.getKbId(), attachment.getId(),
                attachment.getFileName(), attachment.getContentType(), request.content().get());
    }

    @Override
    public StructuredDocument parseImport(ImportParseRequest request) {
        if (!"application/pdf".equalsIgnoreCase(request.contentType())) {
            return parser.parse(request.fileName(), request.contentType(), new ByteArrayInputStream(request.content()));
        }
        readiness.requireReadyFor(ID);
        requireMultimodal();
        PdfMultimodalParser.PdfExtraction extraction = parser.parsePdfMultimodal(
                request.fileName(), request.contentType(), request.content());
        StructuredTextAssembler assembler = new StructuredTextAssembler();
        for (PdfMultimodalParser.ContentItem item : extraction.items()) {
            if (item instanceof PdfMultimodalParser.TextItem text) {
                assembler.append(0, text.text());
            }
        }
        return assembler.build();
    }

    private MultimodalIndexingService requireMultimodal() {
        MultimodalIndexingService service = multimodal.getIfAvailable();
        if (service == null) {
            throw new IllegalStateException("multimodal parser version requested but multimodal indexing is disabled");
        }
        return service;
    }
}
