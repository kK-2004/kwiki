package com.kwiki.wiki.render;

import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.commonmark.renderer.text.TextContentRenderer;
import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * CommonMark-based renderer with GFM tables/strikethrough and an explicit OWASP
 * policy: headings, lists, tables, links (including the kwiki-page: scheme),
 * code blocks and inline formatting survive; scripts, styles, event handlers,
 * and javascript: URLs never do.
 */
@Component
public class CommonMarkMarkdownPort implements MarkdownPort {

    /** Internal wiki links use this scheme: [title](kwiki-page:{page-uuid}). */
    public static final String INTERNAL_LINK_SCHEME = "kwiki-page:";

    private static final List<org.commonmark.Extension> EXTENSIONS =
            List.of(TablesExtension.create(), StrikethroughExtension.create());

    private final Parser parser = Parser.builder().extensions(EXTENSIONS).build();

    private final HtmlRenderer htmlRenderer = HtmlRenderer.builder()
            .extensions(EXTENSIONS)
            .build();

    private final TextContentRenderer textRenderer = TextContentRenderer.builder()
            .extensions(EXTENSIONS)
            .build();

    private final PolicyFactory sanitizer = new HtmlPolicyBuilder()
            .allowUrlProtocols("http", "https", "mailto", "kwiki-page", "attachment")
            .allowElements("p", "div", "span", "br", "hr",
                    "h1", "h2", "h3", "h4", "h5", "h6",
                    "ul", "ol", "li", "blockquote",
                    "table", "thead", "tbody", "tr", "th", "td",
                    "pre", "code", "em", "strong", "del", "a",
                    "img", "audio", "video")
            .allowAttributes("href", "class", "data-kwiki-attachment", "data-file-name",
                    "data-byte-size").onElements("a")
            // Restricted media attributes only: layout (data-align) and size
            // (width px / data-width-percent); events, style and iframes never pass.
            .allowAttributes("src", "alt", "title", "width", "data-align",
                    "data-width-percent").onElements("img")
            .allowAttributes("src", "controls", "preload", "width", "data-align",
                    "data-width-percent").onElements("audio", "video")
            .allowAttributes("class").onElements("code", "td", "th", "span")
            .allowAttributes("align").onElements("td", "th")
            .requireRelNofollowOnLinks()
            .toFactory();

    @Override
    public String renderToHtml(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        Node document = parser.parse(markdown);
        return sanitizer.sanitize(htmlRenderer.render(document));
    }

    @Override
    public String plainText(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        Node document = parser.parse(markdown);
        return textRenderer.render(document).trim();
    }
}
