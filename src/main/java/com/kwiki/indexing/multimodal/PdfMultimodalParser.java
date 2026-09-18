package com.kwiki.indexing.multimodal;

import com.kwiki.indexing.parse.UnsupportedInputException;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于 PDFBox 的 PDF 多模态解析器：逐页收集带位置的正文文本行与
 * 栅格图片绘制事件，输出确定性的阅读顺序（页码 → 顶部距离 →
 * 左边缘），并把同一文档内的重复图片字节按规范化 PNG 的
 * SHA-256 去重（同一字节只规范化一次，每个绘制位置仍保留）。
 * 装饰资源（蒙版/模板、过小、超限、损坏、全透明）被显式过滤并
 * 计入指标；去重后的图片数超出单文档上限时显式失败而非截断。
 * 本解析器不做 OCR：抽取不出正文的扫描版仍由上游规则拒绝。
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "kwiki.multimodal.enabled", havingValue = "true")
public class PdfMultimodalParser {

    /** 同一视觉行内文本与图片的纵向容差（PDF 点）。 */
    private static final float SAME_LINE_TOLERANCE = 3.0f;
    /** 相邻文本行合并为同一段落的最大行距（PDF 点）。 */
    private static final float PARAGRAPH_LINE_GAP = 14.0f;

    /** 一张通过过滤、完成规范化的唯一图片（PNG 字节）。 */
    public record PdfImage(byte[] pngBytes, String contentType, long pixelCount,
                           int width, int height, String sha256) {
    }

    /** 有序内容项：文本段落或对唯一图片的引用。 */
    public sealed interface ContentItem {
    }

    public record TextItem(String text) implements ContentItem {
    }

    /** occurrenceIndex 指向 occurrences；imageIndex 指向 uniqueImages。 */
    public record ImageRefItem(int occurrenceIndex, int imageIndex) implements ContentItem {
    }

    /**
     * 解析结果：occurrences 按阅读顺序给出每个保留的绘制位置；
     * uniqueImages 为去重后的图片字节（上传/摘要的唯一单位）；
     * items 是文本与图片交错后的确定性阅读序列。
     */
    public record PdfExtraction(List<ContentItem> items,
                                List<ImageRefItem> occurrences,
                                List<PdfImage> uniqueImages) {

        public boolean hasText() {
            return items.stream().anyMatch(item -> item instanceof TextItem text
                    && !text.text().isBlank());
        }
    }

    private final int minImagePixels;
    private final int maxImagePixels;
    private final int maxImageBytes;
    private final int maxImagesPerDocument;
    private final MultimodalMetrics metrics;

    @Autowired
    public PdfMultimodalParser(com.kwiki.indexing.config.MultimodalIndexingProperties config,
                               MultimodalMetrics metrics) {
        this(config.minImagePixels(), config.maxImagePixels(), config.maxImageBytes(),
                config.maxImagesPerDocument(), metrics);
    }

    public PdfMultimodalParser(int minImagePixels, int maxImagePixels, int maxImageBytes,
                               int maxImagesPerDocument, MultimodalMetrics metrics) {
        this.minImagePixels = minImagePixels;
        this.maxImagePixels = maxImagePixels;
        this.maxImageBytes = maxImageBytes;
        this.maxImagesPerDocument = maxImagesPerDocument;
        this.metrics = metrics;
    }

    public PdfExtraction parse(byte[] pdfBytes) {
        try (PDDocument document = PDDocument.load(pdfBytes)) {
            LayoutAwareStripper stripper = new LayoutAwareStripper();
            stripper.setSortByPosition(true);
            stripper.getText(document); // 驱动整份文档的内容流处理
            return assemble(stripper.texts, stripper.images);
        } catch (UnsupportedInputException e) {
            throw e;
        } catch (Exception e) {
            throw new UnsupportedInputException("pdf could not be parsed", e);
        }
    }

    /** 文本行/图片事件统一带位置收集；阅读顺序在 assemble 中统一裁决。 */
    static final class LayoutAwareStripper extends PDFTextStripper {

        final List<RawText> texts = new ArrayList<>();
        final List<RawImage> images = new ArrayList<>();

        LayoutAwareStripper() throws IOException {
            super();
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) {
            if (text == null || text.isBlank() || positions.isEmpty()) {
                return;
            }
            float top = Float.MAX_VALUE;
            float left = Float.MAX_VALUE;
            for (TextPosition position : positions) {
                top = Math.min(top, position.getYDirAdj());
                left = Math.min(left, position.getXDirAdj());
            }
            String stripped = text.strip();
            if (!stripped.isEmpty()) {
                texts.add(new RawText(getCurrentPageNo(), top, left, stripped));
            }
        }

        @Override
        protected void processOperator(Operator operator, List<COSBase> operands)
                throws IOException {
            if ("Do".equals(operator.getName()) && !operands.isEmpty()
                    && operands.get(0) instanceof COSName name) {
                PDXObject xobject = getResources().getXObject(name);
                if (xobject instanceof PDImageXObject image) {
                    recordImage(image);
                }
            }
            super.processOperator(operator, operands);
        }

        private void recordImage(PDImageXObject image) {
            float pageHeight = getCurrentPage() != null && getCurrentPage().getMediaBox() != null
                    ? getCurrentPage().getMediaBox().getHeight() : 0f;
            var ctm = getGraphicsState().getCurrentTransformationMatrix();
            // CTM 平移是 PDF 底朝上坐标中图片底边的 y；顶部距离取上边缘
            float imageTop = pageHeight - (ctm.getTranslateY() + Math.abs(ctm.getScalingFactorY()));
            images.add(new RawImage(getCurrentPageNo(), imageTop,
                    Math.abs(ctm.getTranslateX()), image));
        }

        record RawText(int page, float top, float left, String text) {
        }

        record RawImage(int page, float top, float left, PDImageXObject image) {
        }
    }

    private PdfExtraction assemble(List<LayoutAwareStripper.RawText> rawTexts,
                                   List<LayoutAwareStripper.RawImage> rawImages) {
        List<Positioned> positioned = new ArrayList<>();
        for (int i = 0; i < rawTexts.size(); i++) {
            var text = rawTexts.get(i);
            positioned.add(new Positioned(text.page(), text.top(), text.left(), true, i));
        }
        for (int i = 0; i < rawImages.size(); i++) {
            var image = rawImages.get(i);
            positioned.add(new Positioned(image.page(), image.top(), image.left(), false, i));
        }
        positioned.sort(Comparator
                .comparingInt(Positioned::page)
                .thenComparing(p -> roundBand(p.top))
                .thenComparing(p -> roundBand(p.left))
                .thenComparing(p -> p.isText ? 0 : 1));

        Map<String, Integer> uniqueByHash = new LinkedHashMap<>();
        List<PdfImage> uniqueImages = new ArrayList<>();
        List<ImageRefItem> occurrences = new ArrayList<>();
        List<ContentItem> items = new ArrayList<>();
        StringBuilder paragraph = new StringBuilder();
        Integer currentPage = null;
        Float lastTextTop = null;

        for (Positioned entry : positioned) {
            if (entry.isText) {
                String text = rawTexts.get(entry.index).text();
                boolean samePage = currentPage != null && currentPage == entry.page;
                boolean adjacentLine = samePage && lastTextTop != null
                        && entry.top - lastTextTop <= PARAGRAPH_LINE_GAP
                        && entry.top >= lastTextTop - SAME_LINE_TOLERANCE;
                if (!adjacentLine && !paragraph.isEmpty()) {
                    items.add(new TextItem(paragraph.toString()));
                    paragraph = new StringBuilder();
                }
                if (!paragraph.isEmpty()) {
                    paragraph.append('\n');
                }
                paragraph.append(text);
                currentPage = entry.page;
                lastTextTop = entry.top;
            } else {
                if (!paragraph.isEmpty()) {
                    items.add(new TextItem(paragraph.toString()));
                    paragraph = new StringBuilder();
                }
                lastTextTop = null;
                currentPage = entry.page;
                int imageIndex = deduplicate(rawImages.get(entry.index).image(),
                        uniqueByHash, uniqueImages);
                if (imageIndex < 0) {
                    continue; // 装饰/损坏资源：位置不进入阅读序列
                }
                ImageRefItem ref = new ImageRefItem(occurrences.size(), imageIndex);
                occurrences.add(ref);
                items.add(ref);
            }
        }
        if (!paragraph.isEmpty()) {
            items.add(new TextItem(paragraph.toString()));
        }
        if (uniqueImages.size() > maxImagesPerDocument) {
            throw new UnsupportedInputException(
                    "pdf contains more than the configured limit of unique images ("
                            + uniqueImages.size() + " > " + maxImagesPerDocument + ")");
        }
        return new PdfExtraction(List.copyOf(items), List.copyOf(occurrences),
                List.copyOf(uniqueImages));
    }

    /** 过滤 + 规范化 + 去重；返回唯一图片索引，装饰资源返回 -1。 */
    private int deduplicate(PDImageXObject image, Map<String, Integer> uniqueByHash,
                            List<PdfImage> uniqueImages) {
        metrics.imageStage(MultimodalMetrics.STAGE_EXTRACTED);
        if (image.getCOSObject().getBoolean(org.apache.pdfbox.cos.COSName.IMAGE_MASK, false)) {
            metrics.imageStage(MultimodalMetrics.STAGE_FILTERED_MASK);
            return -1;
        }
        int width = image.getWidth();
        int height = image.getHeight();
        long pixels = (long) width * height;
        if (pixels < minImagePixels) {
            metrics.imageStage(MultimodalMetrics.STAGE_FILTERED_SMALL);
            return -1;
        }
        if (pixels > maxImagePixels) {
            metrics.imageStage(MultimodalMetrics.STAGE_FILTERED_LARGE);
            return -1;
        }
        BufferedImage decoded;
        try {
            decoded = image.getImage();
        } catch (Exception corrupt) {
            metrics.imageStage(MultimodalMetrics.STAGE_FILTERED_CORRUPT);
            return -1;
        }
        if (decoded == null) {
            metrics.imageStage(MultimodalMetrics.STAGE_FILTERED_CORRUPT);
            return -1;
        }
        PdfImage normalized = normalizeImage(decoded, maxImageBytes, metrics);
        if (normalized == null) {
            return -1;
        }
        Integer existing = uniqueByHash.get(normalized.sha256());
        if (existing != null) {
            metrics.imageStage(MultimodalMetrics.STAGE_DEDUPLICATED);
            return existing;
        }
        uniqueByHash.put(normalized.sha256(), uniqueImages.size());
        uniqueImages.add(normalized);
        return uniqueImages.size() - 1;
    }

    /** 哈希工具：规范化图片字节的 SHA-256。 */
    public static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** 图片字节规范化：重编码 PNG 并做透明/大小过滤。 */
    static PdfImage normalizeImage(BufferedImage image, int maxBytes, MultimodalMetrics metrics) {
        if (isFullyTransparent(image)) {
            metrics.imageStage(MultimodalMetrics.STAGE_FILTERED_MASK);
            return null;
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            byte[] png = out.toByteArray();
            if (png.length > maxBytes) {
                metrics.imageStage(MultimodalMetrics.STAGE_FILTERED_LARGE);
                return null;
            }
            return new PdfImage(png, "image/png", (long) image.getWidth() * image.getHeight(),
                    image.getWidth(), image.getHeight(), sha256Hex(png));
        } catch (IOException e) {
            metrics.imageStage(MultimodalMetrics.STAGE_FILTERED_CORRUPT);
            return null;
        }
    }

    private static boolean isFullyTransparent(BufferedImage image) {
        if (!image.getColorModel().hasAlpha()) {
            return false;
        }
        int width = image.getWidth();
        int height = image.getHeight();
        int step = Math.max(1, (width * height) / 4096);
        int index = 0;
        int sampled = 0;
        boolean anyVisible = false;
        for (int y = 0; y < height && !anyVisible; y++) {
            for (int x = 0; x < width && !anyVisible; x++) {
                if (index++ % step != 0) {
                    continue;
                }
                sampled++;
                if ((image.getRGB(x, y) >>> 24) != 0) {
                    anyVisible = true;
                }
            }
        }
        return sampled > 0 && !anyVisible;
    }

    private static int roundBand(float value) {
        return Math.round(value / SAME_LINE_TOLERANCE);
    }

    private record Positioned(int page, float top, float left, boolean isText, int index) {
    }

    /** 供诊断使用的短哈希前缀；避免日志中出现完整内容身份。 */
    static String shortHash(String sha256Hex) {
        return sha256Hex == null ? "" : sha256Hex.substring(0, Math.min(12, sha256Hex.length()));
    }
}
