package com.kwiki.wiki.render;

/**
 * Markdown 渲染边界：一个实现产出经过净化的读者端 HTML，
 * 另一个产出随每个修订版本存储、用于索引构建的纯文本投影。
 * 将其保留为端口，可使领域层与具体渲染器解耦，并让测试针对契约而非某个库。
 */
public interface MarkdownPort {

    /** 面向读者端的净化 HTML（可防范脚本与事件处理器）。 */
    String renderToHtml(String markdown);

    /** 仅含文本的投影（无标记），用于分块与搜索。 */
    String plainText(String markdown);
}
