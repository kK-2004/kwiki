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
 * Text-input boundary: Markdown/DOCX/HTML yield structured blocks, image/unknown
 * types are rejected outside the allowlist, and no-text inputs (scanned-content
 * stand-ins) are rejected without OCR, embeddings, or index writes.
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
        // offsets must point into the plain text
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
                .hasMessageNotContaining("OCR service"); // never mentions or invokes OCR
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
