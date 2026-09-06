package com.kwiki.indexing.chunk;

import com.kwiki.indexing.parse.DocumentParseService;
import com.kwiki.indexing.parse.StructuredDocument;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic chunking contract: short sections merge without crossing superior
 * headings, oversized sections split on subordinate headings/paragraphs, children
 * keep paragraph integrity with sentence-before-hard splitting, and repeated runs
 * produce identical keys, ordinals, and boundaries.
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
        // two h2 sections under one h1, each far below the 1024 minimum
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
        // one h1 section with > 4096 characters split across paragraphs
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
        // ordinals and keys are consecutive and deterministic
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
        // two ~150-char paragraphs accumulate into a single child
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
        // every sentence boundary cut ends with the terminator, no mid-sentence splits
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
