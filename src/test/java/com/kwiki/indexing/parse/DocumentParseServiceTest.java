package com.kwiki.indexing.parse;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 文本输入边界：Markdown/DOCX/HTML 产出结构化块，图片/未知
 * 类型在允许列表之外被拒绝，无文本输入（扫描内容的
 * 替代物）在不经过 OCR、embedding 或索引写入的情况下被拒绝。
 */
class DocumentParseServiceTest {

    private final DocumentParseService service = DocumentParseService.forTests();

    private static List<String> headings(StructuredDocument document) {
        return document.blocks().stream()
                .filter(StructBlock::isHeading)
                .map(StructBlock::text)
                .toList();
    }

    @Test
    void markdownProducesHeadingAndParagraphBlocks() {
        StructuredDocument document = service.parse("notes.md", "text/markdown",
                DocumentParseService.utf8("# 标题一\n\n第一段。\n\n## 标题二\n\n- 列表项\n\n```java\ncode()\n```"));

        assertThat(headings(document)).containsExactly("标题一", "标题二");
        assertThat(document.blocks()).anySatisfy(block -> {
            assertThat(block.headingLevel()).isZero();
            assertThat(block.text()).isEqualTo("第一段。");
        });
        assertThat(document.plainText()).contains("code()");
        // 偏移量必须指向纯文本内部
        for (StructBlock block : document.blocks()) {
            assertThat(document.plainText()
                    .substring(block.charStart(), block.charEnd()))
                    .isEqualTo(block.text());
        }
    }

    @Test
    void docxHeadingsAndParagraphsAreExtracted() {
        StructuredDocument document = service.parse("spec.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                docxWith("部署章节", "部署说明正文。", "备份章节", "备份说明正文。"));

        assertThat(headings(document)).containsExactly("部署章节", "备份章节");
        assertThat(document.plainText()).contains("部署说明正文。").contains("备份说明正文。");
    }

    @Test
    void largeDocxWithAnImageKeepsTextAndSkipsEmbeddedMedia() throws Exception {
        var picture = new java.awt.image.BufferedImage(640, 640, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var random = new java.util.Random(42);
        for (int y = 0; y < 640; y++) for (int x = 0; x < 640; x++) picture.setRGB(x, y, random.nextInt());
        var png = new ByteArrayOutputStream(); javax.imageio.ImageIO.write(picture, "png", png);
        byte[] bytes;
        try (var doc = new XWPFDocument(); var out = new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("含图片文档的中文正文");
            doc.createParagraph().createRun().addPicture(new ByteArrayInputStream(png.toByteArray()),
                    org.apache.poi.xwpf.usermodel.Document.PICTURE_TYPE_PNG, "image.png", 1000, 1000);
            var table = doc.createTable(2, 2);
            table.getRow(0).getCell(0).setText("项目"); table.getRow(0).getCell(1).setText("说明");
            table.getRow(1).getCell(0).setText("导入"); table.getRow(1).getCell(1).setText("保留表格文字");
            doc.write(out); bytes = out.toByteArray();
        }
        assertThat(bytes.length).isGreaterThan(1024 * 1024);
        var result = service.parse("image.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", new ByteArrayInputStream(bytes));
        assertThat(result.plainText()).contains("中文正文", "项目", "保留表格文字");
        var fixture = java.nio.file.Path.of("target", "qa-fixtures", "含图片与表格的大文档.docx");
        java.nio.file.Files.createDirectories(fixture.getParent()); java.nio.file.Files.write(fixture, bytes);
    }

    @Test
    void htmlHeadingsAreExtracted() {
        String html = "<html><body><h1>架构</h1><p>分层说明。</p><h2>模块</h2><p>模块说明。</p></body></html>";
        StructuredDocument document = service.parse("page.html", "text/html",
                DocumentParseService.utf8(html));

        assertThat(headings(document)).contains("架构", "模块");
        assertThat(document.plainText()).contains("分层说明。");
    }

    @Test
    void imageTypeIsRejectedOutsideAllowlistWithoutOcr() {
        byte[] pngHeader = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};

        assertThatThrownBy(() ->
                service.parse("scan.png", "image/png", new ByteArrayInputStream(pngHeader)))
                .isInstanceOf(UnsupportedInputException.class)
                .hasMessageContaining("accepted text-extractable types")
                .hasMessageNotContaining("OCR service"); // 绝不提及或调用 OCR
    }

    @Test
    void executableExtensionIsRejected() {
        assertThatThrownBy(() ->
                service.parse("tool.exe", "application/x-msdownload",
                        DocumentParseService.utf8("MZ binary")))
                .isInstanceOf(UnsupportedInputException.class);
    }

    @Test
    void blankTextDocumentIsRejectedAsNoText() {
        assertThatThrownBy(() ->
                service.parse("empty.txt", "text/plain", DocumentParseService.utf8("   \n \t ")))
                .isInstanceOf(UnsupportedInputException.class)
                .hasMessageContaining("no extractable text");
    }

    @Test
    void markdownTableContentIsPreservedAsText() {
        StructuredDocument document = service.parse("matrix.md", "text/markdown",
                DocumentParseService.utf8("| A | B |\n| --- | --- |\n| 1 | 2 |\n"));

        assertThat(document.plainText()).contains("A").contains("2");
    }

    private static InputStream docxWith(String... headingThenParagraph) throws RuntimeException {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            org.apache.poi.xwpf.usermodel.XWPFStyles styles = doc.createStyles();
            for (int level = 1; level <= 6; level++) {
                org.openxmlformats.schemas.wordprocessingml.x2006.main.CTStyle ctStyle =
                        org.openxmlformats.schemas.wordprocessingml.x2006.main.CTStyle.Factory
                                .newInstance();
                ctStyle.setStyleId("Heading" + level);
                ctStyle.setType(org.openxmlformats.schemas.wordprocessingml.x2006.main.STStyleType.PARAGRAPH);
                ctStyle.addNewName().setVal("heading " + level);
                styles.addStyle(new org.apache.poi.xwpf.usermodel.XWPFStyle(ctStyle));
            }
            for (int i = 0; i < headingThenParagraph.length; i += 2) {
                XWPFParagraph heading = doc.createParagraph();
                heading.setStyle("Heading1");
                heading.createRun().setText(headingThenParagraph[i]);
                XWPFParagraph paragraph = doc.createParagraph();
                paragraph.createRun().setText(headingThenParagraph[i + 1]);
            }
            doc.write(out);
            return new ByteArrayInputStream(out.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
