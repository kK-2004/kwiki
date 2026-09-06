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
 * Tika-based structure parser for DOCX/HTML/TXT/PDF: captures h1-h6 heading
 * elements and paragraphs from the XHTML stream. Documents whose extraction
 * yields no text (scanned PDFs) surface as blank and are rejected upstream —
 * no OCR is configured or invoked.
 */
@Component
public class TikaStructParser {

    private static final Pattern HEADING = Pattern.compile("h([1-6])");

    public StructuredDocument parse(InputStream content) {
        try {
            StructHandler handler = new StructHandler();
            AutoDetectParser parser = new AutoDetectParser();
            parser.parse(content, handler, new Metadata(), new ParseContext());
            return handler.assembler.build();
        } catch (Exception e) {
            throw new UnsupportedInputException("document could not be parsed as text");
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
