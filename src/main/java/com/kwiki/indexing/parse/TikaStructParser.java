package com.kwiki.indexing.parse;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.regex.Pattern;

/**
 * 基于 Tika 的 DOCX/HTML/TXT/PDF 结构解析器：从 XHTML 流中
 * 捕获 h1-h6 标题元素与段落。抽取不出文本的文档
 * （扫描版 PDF）会表现为空白，并在上游被拒绝 ——
 * 不会配置、也不会调用 OCR。
 */
@Component
public class TikaStructParser {

    private static final Pattern HEADING = Pattern.compile("h([1-6])");

    public StructuredDocument parse(InputStream content) {
        try {
            StructHandler handler = new StructHandler();
            AutoDetectParser parser = new AutoDetectParser();
            ParseContext context = new ParseContext();
            // 只导入主文档文本。内嵌媒体绝不得触发
            // 图片解析器/OCR，或让一份本应有效的 Word 文档失败。
            context.set(org.apache.tika.extractor.EmbeddedDocumentExtractor.class,
                    new org.apache.tika.extractor.EmbeddedDocumentExtractor() {
                        @Override public boolean shouldParseEmbedded(Metadata metadata) { return false; }
                        @Override public void parseEmbedded(InputStream stream, org.xml.sax.ContentHandler target,
                                                            Metadata metadata, boolean outputHtml) { }
                    });
            parser.parse(content, handler, new Metadata(), context);
            return handler.assembler.build();
        } catch (Exception e) {
            throw new UnsupportedInputException("document could not be parsed as text", e);
        }
    }

    private static final class StructHandler extends DefaultHandler {

        private final StructuredTextAssembler assembler = new StructuredTextAssembler();
        private final StringBuilder current = new StringBuilder();
        private int currentHeadingLevel = -1;

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) {
            String name = localName == null || localName.isEmpty() ? qName : localName;
            if (name != null) {
                var matcher = HEADING.matcher(name.toLowerCase(java.util.Locale.ROOT));
                if (matcher.matches()) {
                    flush();
                    currentHeadingLevel = Integer.parseInt(matcher.group(1));
                    return;
                }
                if ("p".equalsIgnoreCase(name)) {
                    flush();
                    currentHeadingLevel = 0;
                }
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            String name = localName == null || localName.isEmpty() ? qName : localName;
            if (name != null) {
                var matcher = HEADING.matcher(name.toLowerCase(java.util.Locale.ROOT));
                if (matcher.matches() || "p".equalsIgnoreCase(name)) {
                    flush();
                }
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) throws SAXException {
            if (currentHeadingLevel >= 0) {
                current.append(ch, start, length);
            }
        }

        private void flush() {
            if (currentHeadingLevel >= 0) {
                assembler.append(currentHeadingLevel, current.toString());
            }
            current.setLength(0);
            currentHeadingLevel = -1;
        }
    }
}
