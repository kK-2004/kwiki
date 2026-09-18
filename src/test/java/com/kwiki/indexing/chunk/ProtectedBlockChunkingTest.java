package com.kwiki.indexing.chunk;

import com.kwiki.indexing.multimodal.MultimodalMetrics;
import com.kwiki.indexing.multimodal.ProtectedBlockProtocol;
import com.kwiki.indexing.parse.StructBlock;
import com.kwiki.indexing.parse.StructuredDocument;
import com.kwiki.indexing.parse.StructuredTextAssembler;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 受保护块在父子分块中的原子性契约（任务 7.3/7.5）：
 * 常规字符边界不会切开 START/摘要/END；单独超过上限的块完整
 * 发出并计入超限指标；相邻块、边界碰撞、重复 contentId、
 * 超长摘要与混合标题/文本/图片结构保持确定性。
 */
class ProtectedBlockChunkingTest {

    private final ProtectedBlockProtocol protocol = new ProtectedBlockProtocol(4096);

    private StructuredDocument document(String... pieces) {
        StructuredTextAssembler assembler = new StructuredTextAssembler();
        for (String piece : pieces) {
            if (piece.startsWith("#")) {
                int level = 0;
                while (level < piece.length() && piece.charAt(level) == '#') {
                    level++;
                }
                assembler.append(level, piece.substring(level + 1));
            } else if (piece.startsWith("IMG:")) {
                String summary = piece.substring("IMG:".length());
                long contentId = Long.parseLong(summary.substring(0, summary.indexOf('|')));
                String text = summary.substring(summary.indexOf('|') + 1);
                assembler.appendProtectedResource(
                        protocol.serialize(ProtectedBlockProtocol.ResourceRef.image(contentId),
                                text),
                        contentId);
            } else {
                assembler.append(0, piece);
            }
        }
        return assembler.build();
    }

    /** 全部子分块都必须包含完整的成对标记或完全不含标记。 */
    private static void assertNoPartialMarkers(List<ChildChunk> children) {
        for (ChildChunk child : children) {
            boolean hasStart = child.content()
                    .contains(ProtectedBlockProtocol.START_PREFIX);
            boolean hasEnd = child.content().contains(ProtectedBlockProtocol.END_PREFIX);
            assertThat(hasStart).as("partial markers in %s", child.childKey()).isEqualTo(hasEnd);
            if (hasStart) {
                // 块内标记必须可被严格解析（数量与配对完整）
                new ProtectedBlockProtocol(8192).scan(child.content());
            }
        }
    }

    @Test
    void boundariesNeverSplitProtectedBlocks() {
        String filler = "这是一段用于把分块边界推向受保护块内部的普通中文文本。".repeat(4);
        StructuredDocument document = document(
                filler, filler,
                "IMG:12345|一张关键架构图，展示了服务分层与数据流。",
                filler, filler);
        ParentChunker parentChunker = new ParentChunker(new ChunkingConfig(64, 256, 32, 128, 96));
        ChildChunker childChunker = new ChildChunker(new ChunkingConfig(64, 256, 32, 128, 96));
        List<ParentChunk> parents = parentChunker.chunk("T", document);
        List<ChildChunk> children = new java.util.ArrayList<>();
        for (ParentChunk parent : parents) {
            children.addAll(childChunker.chunk(parent, document));
        }
        assertThat(children).isNotEmpty();
        assertNoPartialMarkers(children);
        // 至少一个子分块承载完整受保护块
        assertThat(children.stream().anyMatch(child ->
                child.content().contains(ProtectedBlockProtocol.START_PREFIX))).isTrue();
    }

    @Test
    void oversizedProtectedBlockIsEmittedIntactWithMetric() {
        CountingMetrics metrics = new CountingMetrics();
        String hugeSummary = "超".repeat(400);
        StructuredDocument document = document(
                "前置短文本",
                "IMG:999|" + hugeSummary,
                "后置短文本");
        ChunkingConfig config = new ChunkingConfig(64, 256, 32, 128, 96);
        ParentChunker parentChunker = new ParentChunker(config, metrics);
        ChildChunker childChunker = new ChildChunker(config, metrics);
        List<ParentChunk> parents = parentChunker.chunk("T", document);
        List<ChildChunk> children = new java.util.ArrayList<>();
        for (ParentChunk parent : parents) {
            children.addAll(childChunker.chunk(parent, document));
        }
        assertNoPartialMarkers(children);
        List<ChildChunk> withMarkers = children.stream()
                .filter(child -> child.content().contains(ProtectedBlockProtocol.START_PREFIX))
                .toList();
        assertThat(withMarkers).isNotEmpty();
        assertThat(withMarkers).allSatisfy(child -> {
            assertThat(child.content()).startsWith(ProtectedBlockProtocol.START_PREFIX);
            assertThat(child.content()).endsWith(ProtectedBlockProtocol.END_SUFFIX);
        });
        assertThat(metrics.oversizedCount.get()).isGreaterThanOrEqualTo(2); // 父 + 子各计一次
    }

    @Test
    void adjacentAndRepeatedProtectedBlocksStayWholeAndOrdered() {
        StructuredDocument document = document(
                "段落一",
                "IMG:1|第一张图。",
                "IMG:1|第一张图。", // 重复 contentId 的相邻出现
                "IMG:2|第二张图。",
                "段落二");
        ChunkingConfig config = new ChunkingConfig(16, 512, 8, 256, 128);
        ParentChunker parentChunker = new ParentChunker(config);
        ChildChunker childChunker = new ChildChunker(config);
        List<ParentChunk> parents = parentChunker.chunk("T", document);
        List<ChildChunk> children = new java.util.ArrayList<>();
        for (ParentChunk parent : parents) {
            children.addAll(childChunker.chunk(parent, document));
        }
        assertNoPartialMarkers(children);
        // 出现顺序保持：段落一 → 图1 → 图1 → 图2 → 段落二
        String joined = String.join("\n", children.stream().map(ChildChunk::content).toList());
        int p1 = joined.indexOf("段落一");
        int firstImg = joined.indexOf("\"contentId\":1");
        int secondImg = joined.indexOf("\"contentId\":1", firstImg + 1);
        int img2 = joined.indexOf("\"contentId\":2");
        int p2 = joined.indexOf("段落二");
        assertThat(p1).isLessThan(firstImg);
        assertThat(firstImg).isLessThan(secondImg);
        assertThat(secondImg).isLessThan(img2);
        assertThat(img2).isLessThan(p2);
    }

    @Test
    void mixedHeadingsTextAndImagesKeepStructure() {
        StructuredDocument document = document(
                "#1|标题一",
                "标题一下的正文。",
                "IMG:5|配图说明。",
                "##2|子标题",
                "子标题正文。",
                "IMG:6|另一张图。",
                "结尾段落。");
        ChunkingConfig config = new ChunkingConfig(16, 512, 8, 256, 128);
        List<ParentChunk> parents = new ParentChunker(config).chunk("T", document);
        assertThat(parents).isNotEmpty();
        for (ParentChunk parent : parents) {
            // 标题路径在含图分块中仍然完整
            assertThat(parent.headingPath()).isNotNull();
        }
        List<ChildChunk> children = new java.util.ArrayList<>();
        ChildChunker childChunker = new ChildChunker(config);
        for (ParentChunk parent : parents) {
            children.addAll(childChunker.chunk(parent, document));
        }
        assertNoPartialMarkers(children);
        // 所有块都可被组装器偏移对齐
        for (ChildChunk child : children) {
            assertThat(document.plainText().substring(child.charStart(), child.charEnd()))
                    .isEqualTo(child.content());
        }
    }

    @Test
    void plainDocumentsWithoutMarkersAreUnchanged() {
        StructuredDocument document = document("普通段落一", "普通段落二", "#1|标题");
        List<ParentChunk> parents = new ParentChunker().chunk("T", document);
        assertThat(parents).hasSize(1);
        assertThat(parents.get(0).content()).doesNotContain("KWIKI_META_DATA");
    }

    /** 可编程计数的指标桩。 */
    static class CountingMetrics extends MultimodalMetrics {
        final java.util.concurrent.atomic.AtomicInteger oversizedCount =
                new java.util.concurrent.atomic.AtomicInteger();

        CountingMetrics() {
            super(null);
        }

        @Override
        public void oversizedProtectedBlock() {
            oversizedCount.incrementAndGet();
        }
    }
}
