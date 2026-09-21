package com.kwiki.indexing.multimodal;

import com.kwiki.indexing.chunk.ChildChunk;
import com.kwiki.indexing.chunk.ChildChunker;
import com.kwiki.indexing.chunk.ChunkingConfig;
import com.kwiki.indexing.chunk.ParentChunk;
import com.kwiki.indexing.chunk.ParentChunker;
import com.kwiki.indexing.config.MultimodalIndexingProperties;
import com.kwiki.indexing.multimodal.SafeExternalImageDownloader.DownloadedImage;
import com.kwiki.indexing.parse.MarkdownStructParser;
import com.kwiki.indexing.parse.StructuredDocument;
import com.kwiki.indexing.parse.UnsupportedInputException;
import com.kwiki.indexing.search.ChunkDocument;
import com.kwiki.infrastructure.config.ExternalServicesProperties;
import com.kwiki.rag.retrieval.ChunkHit;
import com.kwiki.wiki.attach.AttachmentStorage;
import com.kwiki.wiki.attach.AttachmentUpload;
import com.kwiki.wiki.attach.StoredAttachment;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 多模态索引的 JVM 内端到端串联（任务 9.1/9.4）：带图 PDF 与
 * 发布 Markdown（上传 + 外链图片）→ 内容中心（内存桩）→ 视觉
 * 摘要（桩）→ 结构化文档 → 原子分块 → ES 分块文档投影
 * （contentIds / 规范原文）→ embedding 无标记投影 → 引用资源
 * 回显；任一图片阶段失败时整份文档构建失败（不产生部分版本）。
 * 遵循仓库约定：不启动容器、不访问真实 ES/模型。
 */
class MultimodalEndToEndTest {

    private static final long KB = 42L;
    private static final long PAGE = 100L;
    private static final long REVISION = 200L;
    private static final String ATTACHMENT_UUID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";

    private MultimodalTestFixtures.FakeStorage storage;
    private MultimodalTestFixtures.FakeVision vision;
    private TestMultimodalRepos.AssetBacking assetBacking;
    private TestMultimodalRepos.SummaryBacking summaryBacking;
    private MultimodalTestFixtures.FakeAttachments attachments;
    private MultimodalIndexingService service;
    private com.kwiki.indexing.parse.DocumentParseService parseService;
    private MultimodalTestFixtures.StubDownloader downloader;

    @BeforeEach
    void setUp() throws Exception {
        storage = new MultimodalTestFixtures.FakeStorage();
        vision = new MultimodalTestFixtures.FakeVision();
        assetBacking = new TestMultimodalRepos.AssetBacking();
        summaryBacking = new TestMultimodalRepos.SummaryBacking();
        attachments = new MultimodalTestFixtures.FakeAttachments();
        downloader = new MultimodalTestFixtures.StubDownloader();

        ImageResourceService imageResources = new ImageResourceService(
                TestMultimodalRepos.assets(assetBacking),
                TestMultimodalRepos.summaries(summaryBacking),
                storage, vision, properties(), multimodal(), new MultimodalMetrics(null));
        service = new MultimodalIndexingService(new MarkdownStructParser(), downloader,
                imageResources, attachments.toRepository(), storage,
                new ProtectedBlockProtocol(512), multimodal(), new MultimodalMetrics(null));

        PdfMultimodalParser pdfParser = new PdfMultimodalParser(64, 1_000_000, 1_000_000, 20,
                new MultimodalMetrics(null));
        parseService = new com.kwiki.indexing.parse.DocumentParseService(
                java.util.Set.of(), new MarkdownStructParser(),
                new com.kwiki.indexing.parse.TikaStructParser(),
                com.kwiki.testutil.StandardTestProperties.providerOf(pdfParser));
    }

    private static ExternalServicesProperties properties() {
        return new ExternalServicesProperties(
                new ExternalServicesProperties.ContentCenter("http://cc", "k", null, null,
                        Duration.ofSeconds(5), Duration.ofSeconds(30)),
                new ExternalServicesProperties.Elasticsearch(null, null, null),
                new ExternalServicesProperties.AnswerLlm("http://l/v1", "k", "m",
                        Duration.ofSeconds(10)),
                new ExternalServicesProperties.QwenEmbedding("http://q/v1", "k", "m", 4,
                        Duration.ofSeconds(10)),
                new ExternalServicesProperties.VisionModel("http://v/v1", "vision-key",
                        "qwen3.7-flash", Duration.ofSeconds(5), Duration.ofSeconds(30), 1, 2));
    }

    private static MultimodalIndexingProperties multimodal() {
        return new MultimodalIndexingProperties(true, "v1", 20, 64, 1_000_000, 1_000_000, 512,
                1_000_000, Duration.ofSeconds(20), 3, java.util.Set.of(443));
    }

    // ---------- 索引侧：文档 → 分块 → ES 投影 ----------

    private record IndexedProjection(List<Map<String, Object>> documents, List<String> embeddingTexts) {
    }

    private IndexedProjection projectToIndex(StructuredDocument document) {
        ParentChunker parentChunker = new ParentChunker(ChunkingConfig.defaults());
        ChildChunker childChunker = new ChildChunker(ChunkingConfig.defaults());
        com.kwiki.indexing.pipeline.IndexedVersion identity =
                new com.kwiki.indexing.pipeline.IndexedVersion(
                        "PAGE", PAGE, REVISION, 0, KB, "kwiki-parse-2", "kwiki-chunk-1",
                        "text-embedding-v4", 2,
                        List.of(), List.of(), List.of());
        List<Map<String, Object>> documents = new ArrayList<>();
        List<String> embeddingTexts = new ArrayList<>();
        for (ParentChunk parent : parentChunker.chunk("PAGE:" + PAGE + ":" + REVISION, document)) {
            documents.add(ChunkDocument.parent(parent, identity));
            for (ChildChunk child : childChunker.chunk(parent, document)) {
                documents.add(ChunkDocument.child(child, new float[] {0.1f, 0.2f}, identity));
                embeddingTexts.add(com.kwiki.indexing.multimodal.ProtectedTextProjection
                        .strip(child.content()));
            }
        }
        return new IndexedProjection(documents, embeddingTexts);
    }

    // ---------- 检索侧：ES 源 → ChunkHit → 资源回显 ----------

    private static ChunkHit hitFrom(Map<String, Object> document) {
        Object contentIds = document.get("contentIds");
        return new ChunkHit(
                String.valueOf(document.get("chunkKey")),
                String.valueOf(document.get("parentChunkKey")),
                (Long) document.get("kbId"),
                String.valueOf(document.get("resourceType")),
                (Long) document.get("resourceId"),
                (Long) document.get("revisionId"),
                "",
                (Integer) document.get("charStart"),
                (Integer) document.get("charEnd"),
                String.valueOf(document.get("content")),
                contentIds instanceof List<?> list
                        ? list.stream().map(id -> (Long) id).toList()
                        : List.of());
    }

    // ---------- 场景 ----------

    @Test
    void publishedMarkdownWithUploadedAndExternalImagesFlowsEndToEnd() {
        registerUploadedImageAttachment(ATTACHMENT_UUID, 8080L);
        String markdown = "## 架构说明\n\n"
                + "服务分层如 ![架构图](attachment://" + ATTACHMENT_UUID + ") 所示。\n\n"
                + "外部参考 ![趋势图](https://images.example.org/trend.png)。\n\n"
                + "重复引用同一上传图 ![复用](attachment://" + ATTACHMENT_UUID + ")。";

        StructuredDocument document = service.buildPageDocument(KB, PAGE, REVISION, markdown);
        IndexedProjection indexed = projectToIndex(document);

        // 上传图复用既有 contentId（不上传），外链镜像为唯一新对象
        assertThat(storage.uploads.get()).isEqualTo(1);
        assertThat(vision.calls.get()).isEqualTo(2);

        // ES 投影：规范原文保留完整标记；contentIds 去重
        List<Map<String, Object>> withResources = indexed.documents().stream()
                .filter(doc -> doc.containsKey("contentIds"))
                .toList();
        assertThat(withResources).isNotEmpty();
        for (Map<String, Object> doc : indexed.documents()) {
            Object content = doc.get("content");
            if (content != null && String.valueOf(content)
                    .contains(ProtectedBlockProtocol.START_PREFIX)) {
                // 规范原文可被严格扫描重建（资源事实来源）
                new ProtectedBlockProtocol(8192).scan(String.valueOf(content));
            }
            // 绝不持久化任何 URL
            assertThat(String.valueOf(content)).doesNotContain("images.example.org");
            assertThat(String.valueOf(content)).doesNotContain("cdn.example.internal");
        }
        boolean sawUploadedAndExternal = withResources.stream()
                .anyMatch(doc -> ((List<?>) doc.get("contentIds")).contains(8080L))
                && withResources.stream().anyMatch(doc -> ((List<?>) doc.get("contentIds"))
                        .contains(5001L));
        assertThat(sawUploadedAndExternal).isTrue();

        // embedding 投影：摘要保留、标记消除
        assertThat(indexed.embeddingTexts()).isNotEmpty();
        for (String embeddingText : indexed.embeddingTexts()) {
            assertThat(embeddingText).doesNotContain(ProtectedBlockProtocol.START_PREFIX);
            assertThat(embeddingText).doesNotContain(ProtectedBlockProtocol.END_PREFIX);
        }
        assertThat(String.join("\n", indexed.embeddingTexts())).contains("图片摘要");

        // 回显：授权后按 contentId 即时换取 CDN 链接
        Map<String, Object> docWithResource = withResources.get(0);
        ChunkHit hit = hitFrom(docWithResource);
        assertThat(hit.contentIds().stream().distinct().count())
                .isEqualTo(hit.contentIds().size());
        for (Long contentId : hit.contentIds()) {
            assertThat(storage.cdnLink(contentId))
                    .isEqualTo("https://cdn.example.internal/f/" + contentId);
        }
    }

    @Test
    void illustratedPdfFlowsEndToEndInReadingOrder() throws Exception {
        byte[] pdf = illustratedPdf();
        StructuredDocument document = service.buildPdfDocument(parseService, KB, 9001L,
                "设计文档.pdf", "application/pdf", pdf);
        assertThat(storage.uploads.get()).isEqualTo(2); // 两张唯一图片全部镜像
        assertThat(vision.calls.get()).isEqualTo(2);

        IndexedProjection indexed = projectToIndex(document);
        List<Map<String, Object>> withResources = indexed.documents().stream()
                .filter(doc -> doc.containsKey("contentIds"))
                .toList();
        assertThat(withResources).isNotEmpty();
        // 检索文本包含正文与图片摘要语义
        String allEmbeddings = String.join("\n", indexed.embeddingTexts());
        assertThat(allEmbeddings).contains("quarterly");
        assertThat(allEmbeddings).contains("图片摘要");
    }

    @Test
    void anyImageStageFailureFailsTheWholeDocumentAtomically() {
        registerUploadedImageAttachment(ATTACHMENT_UUID, 8080L);
        // 视觉摘要阶段瞬时故障：整份页面文档构建失败
        vision.fails = new VisionSummaryException(VisionSummaryException.Category.TRANSIENT,
                "vision summary transport failure");
        assertThatThrownBy(() -> service.buildPageDocument(KB, PAGE, REVISION,
                "前文\n\n![图](attachment://" + ATTACHMENT_UUID + ")\n\n后文"))
                .isInstanceOf(VisionSummaryException.class)
                .hasFieldOrPropertyWithValue("category", VisionSummaryException.Category.TRANSIENT);

        // 恢复后重试：复用已持久化的中间结果（无重复上传）
        vision.fails = null;
        StructuredDocument recovered = service.buildPageDocument(KB, PAGE, REVISION,
                "前文\n\n![图](attachment://" + ATTACHMENT_UUID + ")\n\n后文");
        assertThat(recovered.blocks().stream()
                .filter(com.kwiki.indexing.parse.StructBlock::isProtectedResource)).hasSize(1);
    }

    @Test
    void permanentExternalFailureNeverProducesAPartialVersion() {
        registerUploadedImageAttachment(ATTACHMENT_UUID, 8080L);
        downloader.behavior = uri -> {
            throw new ExternalImageFetchException(ExternalImageFetchException.Category.PERMANENT,
                    "external image resolves to a denied network range");
        };
        String markdown = "前文\n\n![好图](attachment://" + ATTACHMENT_UUID + ")\n\n"
                + "![坏图](https://metadata.example.internal/secret.png)";
        // 下载阶段即失败：既无文档，也无任何镜像上传；
        // 已完成的上传图摘要是可复用的持久中间结果（供重试）。
        assertThatThrownBy(() -> service.buildPageDocument(KB, PAGE, REVISION, markdown))
                .isInstanceOf(UnsupportedInputException.class);
        assertThat(storage.uploads.get()).isZero();
        assertThat(vision.calls.get()).isEqualTo(1);
    }

    @Test
    void scannedOnlyPdfIsRejectedUnderTheNoTextRule() throws Exception {
        byte[] scanned = scannedPdf();
        assertThatThrownBy(() -> service.buildPdfDocument(parseService, KB, 9002L,
                "扫描件.pdf", "application/pdf", scanned))
                .isInstanceOf(UnsupportedInputException.class)
                .hasMessageContaining("no extractable text");
        assertThat(vision.calls.get()).isZero(); // 摘要不是 OCR
    }

    // ---------- 测试夹具 ----------

    private void registerUploadedImageAttachment(String uuid, long contentCenterFileId) {
        com.kwiki.wiki.domain.Attachment attachment =
                new com.kwiki.wiki.domain.Attachment(uuid, KB, 1L, "chart.png", "image/png",
                        MultimodalTestFixtures.pngBytes().length);
        MultimodalTestFixtures.assignId(attachment, 1L);
        attachment.markStored(contentCenterFileId);
        storage.content.put(contentCenterFileId, MultimodalTestFixtures.pngBytes());
        attachments.byUuid.put(uuid, attachment);
    }

    private static BufferedImage solidImage(int width, int height, Color color) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(color);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        return image;
    }

    /** 两页 PDF：正文与两张不同图片交错（第二页含第二张图）。 */
    private static byte[] illustratedPdf() throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDImageXObject first =
                    LosslessFactory.createFromImage(document, solidImage(40, 40, Color.RED));
            PDImageXObject second =
                    LosslessFactory.createFromImage(document, solidImage(40, 40, Color.BLUE));
            PDPage pageOne = new PDPage(PDRectangle.LETTER);
            document.addPage(pageOne);
            try (PDPageContentStream stream = new PDPageContentStream(document, pageOne)) {
                stream.drawImage(first, 50, 730, 40, 40);
                text(stream, "quarterly revenue report introduction", 50, 690);
            }
            PDPage pageTwo = new PDPage(PDRectangle.LETTER);
            document.addPage(pageTwo);
            try (PDPageContentStream stream = new PDPageContentStream(document, pageTwo)) {
                text(stream, "quarterly outlook and risks", 50, 700);
                stream.drawImage(second, 50, 650, 40, 40);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    /** 仅图片、无正文的扫描版。 */
    private static byte[] scannedPdf() throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDImageXObject scan = LosslessFactory.createFromImage(document,
                    solidImage(120, 120, Color.DARK_GRAY));
            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.drawImage(scan, 50, 400, 300, 300);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private static void text(PDPageContentStream stream, String content, float x, float y)
            throws Exception {
        stream.beginText();
        stream.setFont(PDType1Font.HELVETICA, 11);
        stream.newLineAtOffset(x, y);
        stream.showText(content);
        stream.endText();
    }
}
