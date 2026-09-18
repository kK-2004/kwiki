package com.kwiki.indexing.parse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 可索引输入的解析边界：白名单准入（扩展名 + 声明的 MIME）、
 * 基于 Tika 的检测、结构感知抽取（.md 用 Markdown 解析器，其他用 Tika
 * XHTML 结构），以及一道「无文本即拒绝」的硬性检查，阻止扫描版
 * PDF/图片进入向量嵌入或索引。
 *
 * <p>多模态解析代（kwiki-parse-2）的 PDF 走 {@link #parsePdfMultimodal}：
 * 同样的白名单准入与「无文本即拒绝」规则，但由 PDFBox 按位置
 * 抽取正文与图片绘制事件；非 PDF 与纯文本行为保持不变。</p>
 */
@Service
public class DocumentParseService {

    private final TextInputAllowlist allowlist;
    private final MarkdownStructParser markdownParser;
    private final TikaStructParser tikaParser;
    private final org.springframework.beans.factory.ObjectProvider<
            com.kwiki.indexing.multimodal.PdfMultimodalParser> multimodalPdfParser;

    public DocumentParseService(
            @Value("${kwiki.indexing.allowed-extensions:}") Set<String> configuredExtensions,
            MarkdownStructParser markdownParser,
            TikaStructParser tikaParser) {
        this(configuredExtensions, markdownParser, tikaParser, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DocumentParseService(
            @Value("${kwiki.indexing.allowed-extensions:}") Set<String> configuredExtensions,
            MarkdownStructParser markdownParser,
            TikaStructParser tikaParser,
            org.springframework.beans.factory.ObjectProvider<
                    com.kwiki.indexing.multimodal.PdfMultimodalParser> multimodalPdfParser) {
        this.allowlist = configuredExtensions == null || configuredExtensions.isEmpty()
                ? new TextInputAllowlist()
                : new TextInputAllowlist(configuredExtensions.stream()
                        .map(ext -> ext.toLowerCase(Locale.ROOT))
                        .collect(Collectors.toSet()));
        this.markdownParser = markdownParser;
        this.tikaParser = tikaParser;
        this.multimodalPdfParser = multimodalPdfParser;
    }

    public StructuredDocument parse(String fileName, String contentType, InputStream content) {
        if (!allowlist.allows(fileName, contentType)) {
            throw new UnsupportedInputException(
                    "unsupported file type; accepted text-extractable types: "
                            + allowlist.describeAccepted());
        }
        StructuredDocument document = extract(fileName, content);
        if (document.plainText() == null || document.plainText().isBlank()) {
            throw new UnsupportedInputException(
                    "no extractable text (OCR is not supported); accepted types: "
                            + allowlist.describeAccepted());
        }
        return document;
    }

    private StructuredDocument extract(String fileName, InputStream content) {
        String extension = allowlist.extensionOf(fileName);
        if ("md".equals(extension) || "markdown".equals(extension)) {
            String markdown = new String(readAll(content), StandardCharsets.UTF_8);
            return markdownParser.parse(markdown);
        }
        return tikaParser.parse(content);
    }

    private static byte[] readAll(InputStream stream) {
        try (stream) {
            return stream.readAllBytes();
        } catch (Exception e) {
            throw new UnsupportedInputException("input could not be read");
        }
    }

    public TextInputAllowlist allowlist() {
        return allowlist;
    }

    public static DocumentParseService forTests() {
        return new DocumentParseService(Set.of(), new MarkdownStructParser(), new TikaStructParser());
    }

    /**
     * 多模态 PDF 路径：与 {@link #parse} 相同的白名单准入与
     * 「无文本即拒绝」规则（扫描版即使携带页面图片也拒绝——
     * 摘要不是 OCR），抽取由 PDFBox 按位置完成。仅在多模态
     * 解析代可用时存在，否则显式失败。
     */
    public com.kwiki.indexing.multimodal.PdfMultimodalParser.PdfExtraction parsePdfMultimodal(
            String fileName, String contentType, byte[] pdfBytes) {
        if (!allowlist.allows(fileName, contentType)) {
            throw new UnsupportedInputException(
                    "unsupported file type; accepted text-extractable types: "
                            + allowlist.describeAccepted());
        }
        com.kwiki.indexing.multimodal.PdfMultimodalParser parser = multimodalPdfParser == null
                ? null : multimodalPdfParser.getIfAvailable();
        if (parser == null) {
            throw new IllegalStateException(
                    "multimodal pdf parsing requested but the parser is unavailable");
        }
        com.kwiki.indexing.multimodal.PdfMultimodalParser.PdfExtraction extraction =
                parser.parse(pdfBytes);
        if (!extraction.hasText()) {
            throw new UnsupportedInputException(
                    "no extractable text (OCR is not supported); accepted types: "
                            + allowlist.describeAccepted());
        }
        return extraction;
    }

    public static InputStream utf8(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }
}
