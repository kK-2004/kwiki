package com.kwiki.indexing.chunk.plugin;

import com.kwiki.indexing.chunk.*;
import com.kwiki.indexing.multimodal.MultimodalMetrics;
import com.kwiki.indexing.multimodal.ProtectedBlockProtocol;
import com.kwiki.indexing.parse.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KwikiChunkV1Test {
    private final ChunkingConfig config = ChunkingConfig.defaults();
    private final KwikiChunkV1 plugin = new KwikiChunkV1(
            new StaticListableBeanFactory().getBeanProvider(MultimodalMetrics.class));

    @Test
    void parentChildFixturesMatchLegacyChunkersExactly() {
        StringBuilder oversized = new StringBuilder("# 大章\n\n");
        for (int i = 0; i < 30; i++) {
            oversized.append(("段落" + i + "内容").repeat(200)).append("\n\n");
        }
        String sentence = "这是一句完整的句子，用来测试分句。";
        List<String> inputs = List.of(
                "# 总纲\n\n" + "甲".repeat(100) + "\n\n## 小节一\n\n" + "乙".repeat(100)
                        + "\n\n## 小节二\n\n" + "丙".repeat(100),
                "# 甲篇\n\n" + "甲".repeat(100) + "\n\n# 乙篇\n\n" + "乙".repeat(100),
                oversized.toString(),
                "# 巨章\n\n" + "字".repeat(config.parentMaxChars() * 2 + 10),
                "# 章\n\n" + "段落甲".repeat(50) + "\n\n" + "段落乙".repeat(50),
                "# 章\n\n" + sentence.repeat(config.childMaxChars() / sentence.length() + 4),
                "# 章\n\n" + "内".repeat(300) + "\n\n## 节\n\n" + "文".repeat(300)
                        + "\n\n这是短句。这是另一句。",
                "# 章\n\n" + "甲".repeat(400) + "\n\n" + "乙".repeat(400));
        for (String input : inputs) {
            assertLegacyEquivalent("PAGE:7:103", DocumentParseService.forTests().parse(
                    "doc.md", "text/markdown", DocumentParseService.utf8(input)));
        }
    }

    @Test
    void protectedBlockFixturesMatchLegacyChunkersAndRemainAtomic() {
        String filler = "这是一段用于把分块边界推向受保护块内部的普通中文文本。".repeat(4);
        List<StructuredDocument> inputs = List.of(
                document(filler, filler, "IMG:12345|一张关键架构图，展示了服务分层与数据流。", filler, filler),
                document("前置短文本", "IMG:999|" + "超".repeat(400), "后置短文本"),
                document("段落一", "IMG:1|第一张图。", "IMG:1|第一张图。", "IMG:2|第二张图。", "段落二"),
                document("#1|标题一", "标题一下的正文。", "IMG:5|配图说明。", "##2|子标题",
                        "子标题正文。", "IMG:6|另一张图。", "结尾段落。"),
                document("普通段落一", "普通段落二", "#1|标题"),
                document("前置短文本", "IMG:999|" + "超".repeat(5000), "后置短文本"));
        inputs.forEach(input -> assertLegacyEquivalent("T", input));
    }

    @Test
    void identityAndRegistryResolvePlugin() {
        assertThat(plugin.id()).isEqualTo("kwiki-chunk-1");
        assertThat(plugin.label()).isEqualTo("父子分块 v1");
        assertThat(new ChunkerRegistry(List.of(plugin)).require(plugin.id())).isSameAs(plugin);
    }

    @Test
    void optionalMetricsBeanReceivesOversizedBlockEvents() {
        class CountingMetrics extends MultimodalMetrics {
            int count;
            CountingMetrics() { super(null); }
            @Override
            public void oversizedProtectedBlock() { count++; }
        }
        CountingMetrics metrics = new CountingMetrics();
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("metrics", metrics);
        KwikiChunkV1 measured = new KwikiChunkV1(beans.getBeanProvider(MultimodalMetrics.class));
        StructuredDocument input = document("前置短文本", "IMG:999|" + "超".repeat(5000), "后置短文本");
        measured.parents("T", input).forEach(parent -> measured.children(parent, input));
        assertThat(metrics.count).isGreaterThanOrEqualTo(2);
    }

    private void assertLegacyEquivalent(String prefix, StructuredDocument document) {
        List<ParentChunk> expected = new ParentChunker(config, null).chunk(prefix, document);
        List<ParentChunk> actual = plugin.parents(prefix, document);
        // record 相等同时覆盖 key、序号、字符边界、正文、标题路径和边界类型。
        assertThat(actual).containsExactlyElementsOf(expected);
        List<ChildChunk> expectedChildren = new ArrayList<>();
        List<ChildChunk> actualChildren = new ArrayList<>();
        for (int i = 0; i < expected.size(); i++) {
            expectedChildren.addAll(new ChildChunker(config, null).chunk(expected.get(i), document));
            actualChildren.addAll(plugin.children(actual.get(i), document));
        }
        assertThat(actualChildren).containsExactlyElementsOf(expectedChildren);
        assertThat(plugin.parents(prefix, document)).isEqualTo(actual);
        for (ChildChunk child : actualChildren) {
            assertThat(document.plainText().substring(child.charStart(), child.charEnd()))
                    .isEqualTo(child.content());
        }
        for (var block : new ProtectedBlockProtocol(8192).scan(document.plainText())) {
            String protectedContent = document.plainText().substring(block.start(), block.end());
            assertThat(actual).anySatisfy(parent -> assertThat(parent.content()).contains(protectedContent));
            assertThat(actualChildren).anySatisfy(child -> assertThat(child.content()).contains(protectedContent));
        }
    }

    private StructuredDocument document(String... pieces) {
        StructuredTextAssembler assembler = new StructuredTextAssembler();
        ProtectedBlockProtocol protocol = new ProtectedBlockProtocol(8192);
        for (String piece : pieces) {
            if (piece.startsWith("#")) {
                int level = 0;
                while (level < piece.length() && piece.charAt(level) == '#') level++;
                assembler.append(level, piece.substring(level + 1));
            } else if (piece.startsWith("IMG:")) {
                String summary = piece.substring(4);
                long id = Long.parseLong(summary.substring(0, summary.indexOf('|')));
                assembler.appendProtectedResource(protocol.serialize(ProtectedBlockProtocol.ResourceRef.image(id),
                        summary.substring(summary.indexOf('|') + 1)), id);
            } else {
                assembler.append(0, piece);
            }
        }
        return assembler.build();
    }
}
