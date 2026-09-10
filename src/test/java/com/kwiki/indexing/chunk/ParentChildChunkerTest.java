package com.kwiki.indexing.chunk;

import com.kwiki.indexing.parse.DocumentParseService;
import com.kwiki.indexing.parse.StructuredDocument;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 确定性分块契约：短小节在不跨越上级标题的前提下合并，
 * 超大节按下属标题/段落切分，子块在硬切之前按句切分以
 * 保持段落完整性，重复执行会产生一致的 key、序号与边界。
 */
class ParentChildChunkerTest {

    private final ChunkingConfig config = ChunkingConfig.defaults();
    private final ParentChunker parents = new ParentChunker(config);
    private final ChildChunker children = new ChildChunker(config);

    private static final String KEY_PREFIX = "PAGE:7:103";

    private StructuredDocument parse(String markdown) {
        return DocumentParseService.forTests().parse("doc.md", "text/markdown",
                DocumentParseService.utf8(markdown));
    }

    private static String paragraph(String filler, int repeats) {
        return filler.repeat(repeats);
    }

    @Test
    void shortAdjacentSectionsMergeWithoutCrossingSuperiorHeading() {
        // 一个 h1 下的两个 h2 小节，各自都远低于 1024 的下限
        String markdown = "# 总纲\n\n" + paragraph("甲", 100) + "\n\n"
                + "## 小节一\n\n" + paragraph("乙", 100) + "\n\n"
                + "## 小节二\n\n" + paragraph("丙", 100);
        StructuredDocument document = parse(markdown);

        List<ParentChunk> chunks = parents.chunk(KEY_PREFIX, document);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).headingPath()).containsExactly("总纲");
        assertThat(chunks.get(0).boundaryType()).isEqualTo(ParentChunk.BoundaryType.HEADING);
        assertThat(chunks.get(0).parentKey()).isEqualTo(KEY_PREFIX + ":P0");
    }

    @Test
    void superiorHeadingBlocksMerging() {
        String markdown = "# 甲篇\n\n" + paragraph("甲", 100) + "\n\n"
                + "# 乙篇\n\n" + paragraph("乙", 100);
        StructuredDocument document = parse(markdown);

        List<ParentChunk> chunks = parents.chunk(KEY_PREFIX, document);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).headingPath()).containsExactly("甲篇");
        assertThat(chunks.get(1).headingPath()).containsExactly("乙篇");
    }

    @Test
    void oversizedSectionSplitsPreservingHeadingPath() {
        // 一个 h1 小节，字符数超过 4096，按段落切分
        StringBuilder markdown = new StringBuilder("# 大章\n\n");
        for (int i = 0; i < 30; i++) {
            markdown.append(paragraph("段落" + i + "内容", 200)).append("\n\n");
        }
        StructuredDocument document = parse(markdown.toString());

        List<ParentChunk> chunks = parents.chunk(KEY_PREFIX, document);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.headingPath()).containsExactly("大章");
            assertThat(chunk.content().length()).isLessThanOrEqualTo(config.parentMaxChars());
        });
        // 序号与 key 是连续且确定性的
        for (int i = 0; i < chunks.size(); i++) {
            assertThat(chunks.get(i).parentOrdinal()).isEqualTo(i);
            assertThat(chunks.get(i).parentKey()).isEqualTo(KEY_PREFIX + ":P" + i);
        }
    }

    @Test
    void singleGiantParagraphHardCutsAtMaximum() {
        String markdown = "# 巨章\n\n" + paragraph("字", config.parentMaxChars() * 2 + 10);
        StructuredDocument document = parse(markdown);

        List<ParentChunk> chunks = parents.chunk(KEY_PREFIX, document);

        assertThat(chunks).hasSize(3);
        assertThat(chunks).allMatch(chunk -> chunk.boundaryType() == ParentChunk.BoundaryType.HARD);
        assertThat(chunks.get(0).content().length()).isEqualTo(config.parentMaxChars());
    }

    @Test
    void childrenAccumulateParagraphsAndStayWithinBounds() {
        String markdown = "# 章\n\n" + "段落甲".repeat(50) + "\n\n" + "段落乙".repeat(50);
        StructuredDocument document = parse(markdown);
        ParentChunk parent = parents.chunk(KEY_PREFIX, document).get(0);

        List<ChildChunk> childChunks = children.chunk(parent, document);

        assertThat(childChunks).isNotEmpty();
        // 两个约 150 字符的段落累积成一个子块
        assertThat(childChunks).anyMatch(chunk ->
                chunk.content().contains("段落甲") && chunk.content().contains("段落乙"));
        assertThat(childChunks).allMatch(chunk ->
                chunk.content().length() <= config.childMaxChars());
        assertThat(childChunks).allMatch(chunk ->
                chunk.parentKey().equals(parent.parentKey()));
    }

    @Test
    void oversizedParagraphSplitsOnSentencesBeforeHardCut() {
        String sentence = "这是一句完整的句子，用来测试分句。";
        int sentenceLength = sentence.length();
        int count = (config.childMaxChars() / sentenceLength) + 4;
        String markdown = "# 章\n\n" + sentence.repeat(count);
        StructuredDocument document = parse(markdown);
        ParentChunk parent = parents.chunk(KEY_PREFIX, document).get(0);

        List<ChildChunk> childChunks = children.chunk(parent, document);

        assertThat(childChunks.size()).isGreaterThan(1);
        assertThat(childChunks).allMatch(chunk ->
                chunk.content().length() <= config.childMaxChars());
        long sentenceBoundaries = childChunks.stream()
                .filter(chunk -> chunk.boundaryType() == ChildChunk.BoundaryType.SENTENCE)
                .count();
        assertThat(sentenceBoundaries).isGreaterThan(0);
        // 每个句边界切分都以终止符结尾，不存在句中切断
        childChunks.stream()
                .filter(chunk -> chunk.boundaryType() == ChildChunk.BoundaryType.SENTENCE)
                .forEach(chunk -> assertThat(chunk.content()).endsWith("。"));
    }

    @Test
    void repeatedRunsAreDeterministic() {
        String markdown = "# 章\n\n" + paragraph("内", 300) + "\n\n## 节\n\n"
                + paragraph("文", 300) + "\n\n这是短句。这是另一句。";
        StructuredDocument document = parse(markdown);

        List<ParentChunk> firstParents = parents.chunk(KEY_PREFIX, document);
        List<ParentChunk> secondParents = parents.chunk(KEY_PREFIX, document);
        assertThat(secondParents).isEqualTo(firstParents);

        List<ChildChunk> firstChildren = firstParents.stream()
                .flatMap(parent -> children.chunk(parent, document).stream()).toList();
        List<ChildChunk> secondChildren = secondParents.stream()
                .flatMap(parent -> children.chunk(parent, document).stream()).toList();
        assertThat(secondChildren).isEqualTo(firstChildren);
    }

    @Test
    void childCharRangesPointIntoParentContent() {
        String markdown = "# 章\n\n" + paragraph("甲", 400) + "\n\n" + paragraph("乙", 400);
        StructuredDocument document = parse(markdown);
        ParentChunk parent = parents.chunk(KEY_PREFIX, document).get(0);

        for (ChildChunk chunk : children.chunk(parent, document)) {
            assertThat(chunk.charStart()).isGreaterThanOrEqualTo(parent.charStart());
            assertThat(chunk.charEnd()).isLessThanOrEqualTo(parent.charEnd());
            assertThat(document.plainText().substring(chunk.charStart(), chunk.charEnd()))
                    .isEqualTo(chunk.content());
        }
    }
}
