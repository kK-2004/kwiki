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
 * 基于 CommonMark 的渲染器，支持 GFM 表格/删除线，并采用显式 OWASP 策略：
 * 标题、列表、表格、链接（含 kwiki-page: 方案）、代码块与行内格式均保留；
 * 脚本、样式、事件处理器以及 javascript: URL 则一律被剔除。
 */
@Component
public class CommonMarkMarkdownPort implements MarkdownPort {

    /** 内部 wiki 链接使用此方案：[title](kwiki-page:{page-uuid})。 */
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
            // 仅开放的受限媒体属性：布局（data-align）与尺寸
            // （width px / data-width-percent）；事件、样式与 iframe 一律不通过。
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
