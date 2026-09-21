package com.kwiki.indexing.multimodal;

import com.kwiki.indexing.multimodal.PdfMultimodalParser.ContentItem;
import com.kwiki.indexing.multimodal.PdfMultimodalParser.ImageRefItem;
import com.kwiki.indexing.multimodal.PdfMultimodalParser.PdfExtraction;
import com.kwiki.indexing.multimodal.PdfMultimodalParser.TextItem;
import com.kwiki.indexing.parse.UnsupportedInputException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PDF 多模态解析器契约：文本与图片的确定性阅读顺序（前/中/后、
 * 跨页）、同字节去重、装饰资源过滤（蒙版/过小）、损坏图片跳过、
 * 扫描版无文本、以及单文档图片上限的显式失败。
 * 测试 PDF 全部在内存中由 PDFBox 生成，不落盘。
 */
class PdfMultimodalParserTest {

    private static final int MIN_PIXELS = 64;     // 8x8 起
    private static final int MAX_PIXELS = 1_000_000;
    private static final int MAX_BYTES = 1_000_000;
    private static final int DOC_LIMIT = 5;

    private final PdfMultimodalParser parser = new PdfMultimodalParser(
            MIN_PIXELS, MAX_PIXELS, MAX_BYTES, DOC_LIMIT, new MultimodalMetrics(null));

    // ---------- 测试夹具 ----------

    private static BufferedImage solidImage(int width, int height, Color color) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(color);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        return image;
    }

    private static PDImageXObject embedded(PDDocument document, BufferedImage image)
            throws Exception {
        return LosslessFactory.createFromImage(document, image);
    }

    /** 一个文本行：y 取 PDF 底朝上坐标（页面高 792）。 */
    private static void text(PDPageContentStream stream, String content, float x, float y)
            throws Exception {
        stream.beginText();
        stream.setFont(org.apache.pdfbox.pdmodel.font.PDType1Font.HELVETICA, 11);
        stream.newLineAtOffset(x, y);
        stream.showText(content);
        stream.endText();
    }

    private static byte[] build(ThrowingConsumer<PdfBuilder> writer) throws Exception {
        try (PDDocument document = new PDDocument()) {
            PdfBuilder builder = new PdfBuilder(document);
            writer.accept(builder);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    @FunctionalInterface
    private interface ThrowingConsumer<T> {
        void accept(T value) throws Exception;
    }

    private static final class PdfBuilder {
        final PDDocument document;

        PdfBuilder(PDDocument document) {
            this.document = document;
        }

        PDPage addPage() {
            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            return page;
        }

        PDPageContentStream content(PDPage page) throws Exception {
            return new PDPageContentStream(document, page,
                    PDPageContentStream.AppendMode.APPEND, true);
        }
    }

    // ---------- 测试用例 ----------

    @Test
    void textOnlyPdfProducesNoImagesAndNoVisionWork() throws Exception {
        byte[] pdf = build(builder -> {
            PDPage page = builder.addPage();
            try (PDPageContentStream stream = builder.content(page)) {
                text(stream, "plain paragraph one", 50, 700);
                text(stream, "plain paragraph two", 50, 680);
            }
        });
        PdfExtraction extraction = parser.parse(pdf);
        assertThat(extraction.hasText()).isTrue();
        assertThat(extraction.uniqueImages()).isEmpty();
        assertThat(extraction.items()).hasSizeGreaterThanOrEqualTo(1);
        assertThat(extraction.items()).allSatisfy(item ->
                assertThat(item).isInstanceOf(TextItem.class));
    }

    @Test
    void imageBeforeMiddleAndAfterTextKeepsDeterministicOrder() throws Exception {
        BufferedImage red = solidImage(40, 40, Color.RED);
        BufferedImage blue = solidImage(40, 40, Color.BLUE);
        byte[] pdf = build(builder -> {
            PDPage page = builder.addPage();
            PDImageXObject redImage = embedded(builder.document, red);
            PDImageXObject blueImage = embedded(builder.document, blue);
            try (PDPageContentStream stream = builder.content(page)) {
                // y 越大越靠页面顶部
                stream.drawImage(redImage, 50, 730, 40, 40);
                text(stream, "first paragraph", 50, 690);
                stream.drawImage(blueImage, 50, 640, 40, 40);
                text(stream, "second paragraph", 50, 600);
                text(stream, "third paragraph", 50, 580);
            }
        });
        PdfExtraction extraction = parser.parse(pdf);
        List<String> shape = shapeOf(extraction);
        // 确定性阅读顺序：图前、文、图中、文、文
        assertThat(shape).containsExactly("IMG", "TXT", "IMG", "TXT", "TXT");
        assertThat(uniqueTexts(extraction)).isNotEmpty();
    }

    @Test
    void multiPageDocumentsOrderByPageThenPosition() throws Exception {
        BufferedImage green = solidImage(40, 40, Color.GREEN);
        byte[] pdf = build(builder -> {
            PDImageXObject image = embedded(builder.document, green);
            PDPage page1 = builder.addPage();
            try (PDPageContentStream stream = builder.content(page1)) {
                text(stream, "page one text", 50, 700);
                stream.drawImage(image, 50, 650, 40, 40);
            }
            PDPage page2 = builder.addPage();
            try (PDPageContentStream stream = builder.content(page2)) {
                text(stream, "page two text", 50, 700);
            }
        });
        PdfExtraction extraction = parser.parse(pdf);
        List<String> texts = uniqueTexts(extraction);
        assertThat(texts).anySatisfy(t -> assertThat(t).contains("page one text"));
        assertThat(texts).anySatisfy(t -> assertThat(t).contains("page two text"));
        // 第一页的图必须在第一页文本之后、第二页文本之前
        int pageOne = indexOfTextContaining(extraction, "page one");
        int image = indexOfFirstImage(extraction);
        int pageTwo = indexOfTextContaining(extraction, "page two");
        assertThat(pageOne).isLessThan(image);
        assertThat(image).isLessThan(pageTwo);
    }

    @Test
    void repeatedImageBytesAreDeduplicatedButEveryOccurrenceKept() throws Exception {
        BufferedImage same = solidImage(40, 40, Color.MAGENTA);
        byte[] pdf = build(builder -> {
            PDImageXObject image = embedded(builder.document, same);
            PDPage page = builder.addPage();
            try (PDPageContentStream stream = builder.content(page)) {
                stream.drawImage(image, 50, 730, 40, 40);
                text(stream, "between", 50, 690);
                stream.drawImage(image, 300, 640, 60, 60);
                stream.drawImage(image, 50, 600, 30, 30);
            }
        });
        PdfExtraction extraction = parser.parse(pdf);
        assertThat(extraction.uniqueImages()).hasSize(1);
        assertThat(extraction.occurrences()).hasSize(3);
        assertThat(extraction.occurrences())
                .allSatisfy(ref -> assertThat(ref.imageIndex()).isZero());
    }

    @Test
    void decorativeImagesAreFiltered() throws Exception {
        BufferedImage tiny = solidImage(4, 4, Color.GRAY); // 16 px < MIN_PIXELS
        BufferedImage normal = solidImage(40, 40, Color.CYAN);
        byte[] pdf = build(builder -> {
            PDImageXObject tinyImage = embedded(builder.document, tiny);
            PDImageXObject normalImage = embedded(builder.document, normal);
            PDPage page = builder.addPage();
            try (PDPageContentStream stream = builder.content(page)) {
                stream.drawImage(tinyImage, 50, 730, 8, 8);
                stream.drawImage(normalImage, 50, 700, 40, 40);
                text(stream, "caption text", 50, 660);
            }
        });
        PdfExtraction extraction = parser.parse(pdf);
        assertThat(extraction.uniqueImages()).hasSize(1);
        assertThat(extraction.occurrences()).hasSize(1);
        assertThat(extraction.hasText()).isTrue();
    }

    @Test
    void corruptImageBytesAreSkippedWithoutFailingTheDocument() throws Exception {
        byte[] pdf = build(builder -> {
            PDPage page = builder.addPage();
            try (PDPageContentStream stream = builder.content(page)) {
                text(stream, "body text survives", 50, 700);
            }
            // 直接注入一个损坏的图片对象流（声明 DCTDecode 但内容是垃圾）
            org.apache.pdfbox.cos.COSStream junk =
                    new org.apache.pdfbox.cos.COSStream();
            junk.setItem(org.apache.pdfbox.cos.COSName.FILTER,
                    org.apache.pdfbox.cos.COSName.DCT_DECODE);
            junk.setItem(org.apache.pdfbox.cos.COSName.TYPE,
                    org.apache.pdfbox.cos.COSName.XOBJECT);
            junk.setItem(org.apache.pdfbox.cos.COSName.SUBTYPE,
                    org.apache.pdfbox.cos.COSName.IMAGE);
            junk.setInt(org.apache.pdfbox.cos.COSName.WIDTH, 40);
            junk.setInt(org.apache.pdfbox.cos.COSName.HEIGHT, 40);
            junk.setInt(org.apache.pdfbox.cos.COSName.BITS_PER_COMPONENT, 8);
            junk.setItem(org.apache.pdfbox.cos.COSName.COLORSPACE,
                    org.apache.pdfbox.cos.COSName.DEVICERGB);
            try (java.io.OutputStream raw = junk.createRawOutputStream()) {
                raw.write("this is definitely not a jpeg".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
            }
            org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject corrupt =
                    new org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject(
                            new org.apache.pdfbox.pdmodel.common.PDStream(junk), null);
            // 追加一个引用损坏资源的绘制事件
            try (PDPageContentStream stream = builder.content(page)) {
                stream.drawImage(corrupt, 50, 660, 40, 40);
            }
        });
        PdfExtraction extraction = parser.parse(pdf);
        assertThat(extraction.uniqueImages()).isEmpty();
        assertThat(extraction.hasText()).isTrue();
    }

    @Test
    void scannedOnlyPdfHasNoTextAndIsRejectedUpstream() throws Exception {
        BufferedImage scan = solidImage(200, 200, Color.DARK_GRAY);
        byte[] pdf = build(builder -> {
            PDImageXObject image = embedded(builder.document, scan);
            PDPage page = builder.addPage();
            try (PDPageContentStream stream = builder.content(page)) {
                stream.drawImage(image, 50, 400, 300, 300);
            }
        });
        PdfExtraction extraction = parser.parse(pdf);
        assertThat(extraction.hasText()).isFalse();
        assertThat(extraction.uniqueImages()).hasSize(1);
        // 「无文本即拒绝」规则：多模态摘要不是 OCR
        com.kwiki.indexing.parse.DocumentParseService service =
                new com.kwiki.indexing.parse.DocumentParseService(java.util.Set.of(),
                        new com.kwiki.indexing.parse.MarkdownStructParser(),
                        new com.kwiki.indexing.parse.TikaStructParser(),
                        com.kwiki.testutil.StandardTestProperties.providerOf(parser));
        assertThatThrownBy(() -> service.parsePdfMultimodal("scan.pdf", "application/pdf", pdf))
                .isInstanceOf(UnsupportedInputException.class)
                .hasMessageContaining("no extractable text");
    }

    @Test
    void overLimitUniqueImagesFailExplicitly() throws Exception {
        PdfMultimodalParser limited = new PdfMultimodalParser(
                MIN_PIXELS, MAX_PIXELS, MAX_BYTES, 1, new MultimodalMetrics(null));
        byte[] pdf = build(builder -> {
            PDPage page = builder.addPage();
            try (PDPageContentStream stream = builder.content(page)) {
                text(stream, "body", 50, 700);
            }
            PDImageXObject first = embedded(builder.document, solidImage(40, 40, Color.RED));
            PDImageXObject second = embedded(builder.document, solidImage(40, 40, Color.GREEN));
            try (PDPageContentStream stream = builder.content(page)) {
                stream.drawImage(first, 50, 660, 40, 40);
                stream.drawImage(second, 120, 660, 40, 40);
            }
        });
        assertThatThrownBy(() -> limited.parse(pdf))
                .isInstanceOf(UnsupportedInputException.class)
                .hasMessageContaining("limit");
    }

    // ---------- 辅助方法 ----------

    private static List<String> shapeOf(PdfExtraction extraction) {
        return extraction.items().stream()
                .map(item -> item instanceof TextItem ? "TXT" : "IMG")
                .toList();
    }

    private static List<String> uniqueTexts(PdfExtraction extraction) {
        return extraction.items().stream()
                .filter(item -> item instanceof TextItem)
                .map(item -> ((TextItem) item).text())
                .toList();
    }

    private static int indexOfTextContaining(PdfExtraction extraction, String needle) {
        List<ContentItem> items = extraction.items();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i) instanceof TextItem t && t.text().contains(needle)) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfFirstImage(PdfExtraction extraction) {
        List<ContentItem> items = extraction.items();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i) instanceof ImageRefItem) {
                return i;
            }
        }
        return -1;
    }
}
