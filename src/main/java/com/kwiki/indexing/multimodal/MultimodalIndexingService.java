package com.kwiki.indexing.multimodal;

import com.kwiki.indexing.config.MultimodalIndexingProperties;
import com.kwiki.indexing.job.IndexingWorker;
import com.kwiki.indexing.parse.MarkdownStructParser;
import com.kwiki.indexing.parse.StructBlock;
import com.kwiki.indexing.parse.StructuredDocument;
import com.kwiki.indexing.parse.StructuredTextAssembler;
import com.kwiki.indexing.parse.UnsupportedInputException;
import com.kwiki.wiki.attach.AttachmentStorage;
import com.kwiki.wiki.domain.Attachment;
import com.kwiki.wiki.persistence.AttachmentRepository;
import com.kwiki.wiki.render.MarkdownMediaScanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 多模态索引编排：解析/抓取 → 去重 → 条件性内容中心上传 →
 * CDN 查询 → 视觉摘要 → 受保护块组装。任一阶段失败都会让
 * 整个文档构建失败（不产生部分多模态索引版本）；已完成的
 * 派生中间结果由 {@link ImageResourceService} 持久化并在重试
 * 时复用。页面源 Markdown 永远不被修改——受保护块只存在于
 * 返回的索引投影中。
 */
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "kwiki.multimodal.enabled", havingValue = "true")
public class MultimodalIndexingService {

    private static final Logger log = LoggerFactory.getLogger(MultimodalIndexingService.class);

    /** 占位符使用不可见分隔符，保证 Markdown 行内解析不会吞掉它。 */
    private static final String PLACEHOLDER_FORMAT = "\u2063kwiki-image-%d\u2063";

    private static final Set<String> SUPPORTED_ATTACHMENT_IMAGE_TYPES = Set.of(
            "image/png", "image/jpeg", "image/gif", "image/webp");

    private final MarkdownStructParser markdownParser;
    private final SafeExternalImageDownloader downloader;
    private final ImageResourceService imageResources;
    private final AttachmentRepository attachments;
    private final AttachmentStorage storage;
    private final ProtectedBlockProtocol protocol;
    private final MultimodalIndexingProperties config;
    private final MultimodalMetrics metrics;

    public MultimodalIndexingService(MarkdownStructParser markdownParser,
                                     SafeExternalImageDownloader downloader,
                                     ImageResourceService imageResources,
                                     AttachmentRepository attachments,
                                     AttachmentStorage storage,
                                     ProtectedBlockProtocol protocol,
                                     MultimodalIndexingProperties config,
                                     MultimodalMetrics metrics) {
        this.markdownParser = markdownParser;
        this.downloader = downloader;
        this.imageResources = imageResources;
        this.attachments = attachments;
        this.storage = storage;
        this.protocol = protocol;
        this.config = config;
        this.metrics = metrics;
    }

    /** 一个已解析完成的图片引用：权威资产行 + 复用摘要。 */
    private record ResolvedImage(DerivedImageAsset asset, String summary) {

        long contentId() {
            return asset.getContentId();
        }
    }

    /**
     * 发布后 Markdown 的多模态投影：识别标准图片语法与受支持
     * {@code <img>}（跳过围栏/转义——由 MarkdownMediaScanner 保证），
     * attachment://uuid 复用既有内容中心对象，绝对 HTTPS 外链安全
     * 抓取后镜像；图片语法位置替换为受保护块，其余结构（标题/
     * 段落/列表/代码）保持原相对顺序。不支持的 scheme、相对地址、
     * data URI 或任何校验失败都让本次索引显式失败。
     */
    public StructuredDocument buildPageDocument(long kbId, long pageId, long revisionId,
                                                String markdown) {
        List<MarkdownMediaScanner.MediaReference> imageRefs = MarkdownMediaScanner
                .scan(markdown).stream()
                .filter(ref -> ref.kind() == MarkdownMediaScanner.MediaKind.IMAGE)
                .toList();
        if (imageRefs.isEmpty()) {
            return plainMarkdownDocument(markdown);
        }

        // 同一来源（uuid / URL）只解析一次；重复语法位置复用同一资产与摘要
        Map<String, ResolvedImage> resolvedBySrc = new HashMap<>();
        ResolvedImage[] resolvedPerOccurrence = new ResolvedImage[imageRefs.size()];
        String[] placeholders = new String[imageRefs.size()];
        StringBuilder substituted = new StringBuilder(markdown.length());
        int cursor = 0;
        for (int i = 0; i < imageRefs.size(); i++) {
            MarkdownMediaScanner.MediaReference ref = imageRefs.get(i);
            ResolvedImage resolved = resolvedBySrc.computeIfAbsent(ref.src(),
                    src -> resolveReference(src, kbId, pageId, revisionId));
            resolvedPerOccurrence[i] = resolved;
            placeholders[i] = String.format(PLACEHOLDER_FORMAT, i);
            substituted.append(markdown, cursor, ref.start());
            substituted.append(placeholders[i]);
            cursor = ref.end();
        }
        substituted.append(markdown.substring(cursor));
        if (resolvedBySrc.size() > config.maxImagesPerDocument()) {
            throw new UnsupportedInputException(
                    "page references more than the configured limit of unique images ("
                            + resolvedBySrc.size() + " > " + config.maxImagesPerDocument() + ")");
        }

        // 完整 Markdown 结构解析（占位符保证围栏/结构不被切断），
        // 然后按占位符把块拆分并插入受保护块。
        StructuredDocument parsed = markdownParser.parse(substituted.toString());
        StructuredTextAssembler assembler = new StructuredTextAssembler();
        for (StructBlock block : parsed.blocks()) {
            String text = block.text();
            int placeholderAt = indexOfPlaceholder(text, placeholders, 0);
            if (placeholderAt < 0) {
                assembler.append(block.headingLevel(), protocol.sanitizeUserText(text));
                continue;
            }
            int from = 0;
            while (placeholderAt >= 0) {
                int index = placeholderIndex(text, placeholderAt, placeholders);
                String before = text.substring(from, placeholderAt);
                if (!before.isBlank()) {
                    assembler.append(block.headingLevel(),
                            protocol.sanitizeUserText(before.strip()));
                }
                appendProtectedBlock(assembler, resolvedPerOccurrence[index]);
                from = placeholderAt + placeholders[index].length();
                placeholderAt = indexOfPlaceholder(text, placeholders, from);
            }
            String after = text.substring(from);
            if (!after.isBlank()) {
                assembler.append(block.headingLevel(), protocol.sanitizeUserText(after.strip()));
            }
        }
        return assembler.build();
    }

    /**
     * 已存储 PDF 附件的多模态投影：PDFBox 位置化抽取（无文本即
     * 拒绝的规则由 DocumentParseService 执行），唯一图片经内容
     * 中心持久化并摘要，按确定性阅读顺序交错组装。
     */
    public StructuredDocument buildPdfDocument(
            com.kwiki.indexing.parse.DocumentParseService parseService, long kbId,
            long attachmentId, String fileName, String contentType, byte[] pdfBytes) {
        com.kwiki.indexing.multimodal.PdfMultimodalParser.PdfExtraction extraction =
                parseService.parsePdfMultimodal(fileName, contentType, pdfBytes);
        List<ResolvedImage> uniqueImages = new ArrayList<>();
        for (com.kwiki.indexing.multimodal.PdfMultimodalParser.PdfImage image
                : extraction.uniqueImages()) {
            DerivedImageAsset asset = imageResources.resolveDerivedAsset(
                    new ImageResourceService.DerivedUpload(
                            DerivedImageAsset.KIND_ATTACHMENT_PDF,
                            "attachment:" + attachmentId, kbId,
                            IndexingWorker.PARSER_VERSION_MULTIMODAL, null),
                    image.pngBytes(), image.contentType(),
                    "attachment-" + attachmentId + "-image.png");
            uniqueImages.add(withSummary(asset));
        }
        StructuredTextAssembler assembler = new StructuredTextAssembler();
        for (com.kwiki.indexing.multimodal.PdfMultimodalParser.ContentItem item : extraction.items()) {
            if (item instanceof com.kwiki.indexing.multimodal.PdfMultimodalParser.TextItem text) {
                assembler.append(0, protocol.sanitizeUserText(text.text()));
            } else {
                com.kwiki.indexing.multimodal.PdfMultimodalParser.ImageRefItem ref =
                        (com.kwiki.indexing.multimodal.PdfMultimodalParser.ImageRefItem) item;
                appendProtectedBlock(assembler, uniqueImages.get(ref.imageIndex()));
            }
        }
        return assembler.build();
    }

    // ---------- 内部 ----------

    private ResolvedImage resolveReference(String src, long kbId, long pageId, long revisionId) {
        String uuid = MarkdownMediaScanner.attachmentUuid(src);
        if (uuid != null) {
            return resolveUploaded(uuid, kbId);
        }
        return resolveExternal(src, kbId, pageId, revisionId);
    }

    /** attachment://uuid —— 仅限当前知识库、STORED、字节校验为受支持图片。 */
    private ResolvedImage resolveUploaded(String uuid, long kbId) {
        Attachment attachment = attachments.findByUuid(uuid)
                .filter(candidate -> candidate.getKbId() != null && candidate.getKbId() == kbId)
                .orElseThrow(() -> new UnsupportedInputException(
                        "page references an attachment outside this knowledge base"));
        if (!attachment.isStored() || attachment.getContentCenterFileId() == null
                || attachment.getContentCenterFileId() <= 0) {
            throw new UnsupportedInputException(
                    "page references an attachment that is not stored");
        }
        byte[] bytes = storage.readContent(attachment.getContentCenterFileId());
        String sniffed = com.kwiki.wiki.attach.MediaContentSniffer.sniffImageType(bytes)
                .map(type -> "image/jpg".equals(type) ? "image/jpeg" : type)
                .orElse("");
        String declared = attachment.getContentType() == null
                ? "" : attachment.getContentType().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_ATTACHMENT_IMAGE_TYPES.contains(sniffed) || !sniffed.equals(declared)) {
            throw new UnsupportedInputException(
                    "page references an attachment whose bytes are not a supported image");
        }
        DerivedImageAsset asset = imageResources.resolveAttachmentAsset(
                attachment.getId(), attachment.getUuid(), kbId,
                IndexingWorker.PARSER_VERSION_MULTIMODAL, bytes,
                attachment.getContentCenterFileId());
        return withSummary(asset);
    }

    /** 绝对 HTTPS 外链：安全下载 → 镜像上传 → 以新 contentId 为准。 */
    private ResolvedImage resolveExternal(String src, long kbId, long pageId, long revisionId) {
        URI url;
        try {
            url = URI.create(src);
        } catch (IllegalArgumentException invalid) {
            throw new UnsupportedInputException("page references an invalid image URL");
        }
        if (!url.isAbsolute()) {
            throw new UnsupportedInputException(
                    "page references a relative image URL; only absolute https is supported");
        }
        String scheme = url.getScheme() == null ? "" : url.getScheme().toLowerCase(Locale.ROOT);
        if (!"https".equals(scheme)) {
            // http、data URI 及其他 scheme 一律显式失败，不做 alt 文本降级
            throw new UnsupportedInputException(
                    "page references an image URL with an unsupported scheme");
        }
        SafeExternalImageDownloader.DownloadedImage downloaded;
        try {
            downloaded = downloader.download(url);
        } catch (ExternalImageFetchException e) {
            if (e.getCategory() == ExternalImageFetchException.Category.PERMANENT) {
                metrics.stageFailure("external-download", "permanent");
                throw new UnsupportedInputException(e.getMessage());
            }
            metrics.stageFailure("external-download", "transient");
            throw e;
        }
        String urlHash = PdfMultimodalParser.sha256Hex(
                src.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        DerivedImageAsset asset = imageResources.resolveDerivedAsset(
                new ImageResourceService.DerivedUpload(
                        DerivedImageAsset.KIND_EXTERNAL_URL,
                        "page:" + pageId + ":rev:" + revisionId, kbId,
                        IndexingWorker.PARSER_VERSION_MULTIMODAL, urlHash),
                downloaded.bytes(), downloaded.contentType(),
                "page-" + pageId + "-image." + extensionOf(downloaded.contentType()));
        return withSummary(asset);
    }

    private ResolvedImage withSummary(DerivedImageAsset asset) {
        return new ResolvedImage(asset, imageResources.resolveSummary(asset));
    }

    private void appendProtectedBlock(StructuredTextAssembler assembler, ResolvedImage resolved) {
        String block = protocol.serialize(
                ProtectedBlockProtocol.ResourceRef.image(resolved.contentId()),
                resolved.summary());
        assembler.appendProtectedResource(block, resolved.contentId());
    }

    private StructuredDocument plainMarkdownDocument(String markdown) {
        return markdownParser.parse(protocol.sanitizeUserText(markdown));
    }

    private static int indexOfPlaceholder(String text, String[] placeholders, int from) {
        int best = -1;
        for (String placeholder : placeholders) {
            int at = text.indexOf(placeholder, from);
            if (at >= 0 && (best < 0 || at < best)) {
                best = at;
            }
        }
        return best;
    }

    private static int placeholderIndex(String text, int at, String[] placeholders) {
        for (int i = 0; i < placeholders.length; i++) {
            if (text.startsWith(placeholders[i], at)) {
                return i;
            }
        }
        return -1;
    }

    private static String extensionOf(String contentType) {
        return switch (contentType) {
            case "image/png" -> "png";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            default -> "jpg";
        };
    }
}
