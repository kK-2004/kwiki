package com.kwiki.wiki.render;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rendering contract backed by fixtures: supported structures (headings, lists,
 * tables, links, code blocks, inline formatting) survive rendering, unsafe HTML
 * is sanitized, unsupported constructs degrade to source-safe text instead of
 * disappearing, and plainText strips markup.
 */
class MarkdownRenderContractTest {

    private final MarkdownPort markdown = new CommonMarkMarkdownPort();

    private static String fixture(String name) {
        try (InputStream in = markdownFixtureStream(name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("fixture " + name + " missing", e);
        }
    }

    private static InputStream markdownFixtureStream(String name) {
        InputStream stream = MarkdownRenderContractTest.class
                .getResourceAsStream("/markdown/" + name);
        if (stream == null) {
            throw new IllegalStateException("fixture /markdown/" + name + " not found");
        }
        return stream;
    }

    @Test
    void headingsListsTablesAndQuotesArePreserved() {
        String html = markdown.renderToHtml(fixture("headings-lists-tables.md"));

        assertThat(html).contains("<h1>kwiki 工程手册</h1>");
        assertThat(html).contains("<h2>部署流程</h2>");
        assertThat(html).contains("<ol>");
        assertThat(html).contains("<ul>");
        assertThat(html).contains("<table>");
        assertThat(html).contains("dense_vector");
        assertThat(html).contains("<strong>加粗</strong>");
        assertThat(html).contains("<em>斜体</em>");
        assertThat(html).contains("<code>行内代码</code>");
        assertThat(html).contains("<blockquote>");
    }

    @Test
    void linksAndCodeBlocksArePreserved() {
        String html = markdown.renderToHtml(fixture("links-code-inline.md"));

        assertThat(html).contains("href=\"kwiki-page:11111111-1111-1111-1111-111111111111\"");
        assertThat(html).contains("href=\"https://spring.io\"");
        assertThat(html).contains("<pre><code");
        assertThat(html).contains("<del>删除线</del>");
    }

    @Test
    void unsafeConstructsAreSanitizedAway() {
        String html = markdown.renderToHtml(fixture("unsafe-constructs.md"));

        assertThat(html).doesNotContain("<script");
        assertThat(html).doesNotContain("onerror");
        assertThat(html).doesNotContain("onclick");
        assertThat(html).doesNotContain("javascript:");
        assertThat(html).as("safe content must survive sanitization")
                .contains("<strong>这段话应当保留</strong>");
    }

    @Test
    void kwikiMediaAttributesSurviveSanitization() {
        String html = markdown.renderToHtml(
                "<img src=\"https://cdn.example.com/a.png\" alt=\"截图\" data-align=\"center\" "
                        + "data-width-percent=\"25\" width=\"640\" style=\"position:fixed\" "
                        + "onload=\"alert(1)\" />");

        // layout/size attributes ride along for readers and standalone exports
        assertThat(html).contains("src=\"https://cdn.example.com/a.png\"");
        assertThat(html).contains("alt=\"截图\"");
        assertThat(html).contains("data-align=\"center\"");
        assertThat(html).contains("data-width-percent=\"25\"");
        assertThat(html).contains("width=\"640\"");
        // style and events never pass
        assertThat(html).doesNotContain("style=");
        assertThat(html).doesNotContain("onload");
    }

    @Test
    void unsupportedConstructsStaySourceSafe() {
        String html = markdown.renderToHtml(fixture("unsupported-constructs.md"));
        String source = fixture("unsupported-constructs.md");

        assertThat(html).contains("<em>这行斜体必须正常</em>");
        assertThat(source).contains("[^1]");
        assertThat(markdown.plainText(source))
                .as("unsupported syntax must not swallow its text")
                .contains("脚注内容")
                .contains("定义内容");
    }

    @ParameterizedTest
    @ValueSource(strings = {"headings-lists-tables.md", "links-code-inline.md",
            "unsupported-constructs.md"})
    void plainTextProjectionStripsMarkup(String fixtureName) {
        String source = fixture(fixtureName);
        String plain = markdown.plainText(source);

        assertThat(plain)
                .doesNotContain("**")
                .doesNotContain("```")
                .doesNotContain("<h1>")
                .doesNotContain("[kwiki-page:")
                .isNotBlank();
    }

    @Test
    void blankInputRendersEmptyOutput() {
        assertThat(markdown.renderToHtml(null)).isEmpty();
        assertThat(markdown.renderToHtml("   ")).isEmpty();
        assertThat(markdown.plainText(null)).isEmpty();
    }
}
