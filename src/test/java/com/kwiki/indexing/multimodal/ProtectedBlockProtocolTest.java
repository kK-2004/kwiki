package com.kwiki.indexing.multimodal;

import com.kwiki.indexing.multimodal.ProtectedBlockProtocol.ParsedBlock;
import com.kwiki.indexing.multimodal.ProtectedBlockProtocol.ProtocolViolationException;
import com.kwiki.indexing.multimodal.ProtectedBlockProtocol.ResourceRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * KWIKI_META_DATA 协议契约：规范序列化的往返恒等；畸形 JSON、
 * 额外字段（含 mime）、START/END 身份不一致、非正 contentId、
 * 未闭合标记、嵌套标记都必须显式失败；恰好包含标记片段的
 * 普通文本只有在完整成块时才具备资源语义。
 */
class ProtectedBlockProtocolTest {

    private final ProtectedBlockProtocol protocol = new ProtectedBlockProtocol(512);

    @Test
    void canonicalRoundTripPreservesIdentityAndSummary() {
        String block = protocol.serialize(ResourceRef.image(12345), "一张展示季度增长的折线图");
        assertThat(block).isEqualTo(
                "<<KWIKI_META_DATA_START {\"type\":\"image\",\"contentId\":12345}>>\n"
                        + "一张展示季度增长的折线图\n"
                        + "<<KWIKI_META_DATA_END {\"type\":\"image\",\"contentId\":12345}>>");

        List<ParsedBlock> parsed = protocol.scan(block);
        assertThat(parsed).hasSize(1);
        assertThat(parsed.get(0).ref().type()).isEqualTo("image");
        assertThat(parsed.get(0).ref().contentId()).isEqualTo(12345L);
        assertThat(parsed.get(0).summary()).isEqualTo("一张展示季度增长的折线图");
        assertThat(parsed.get(0).start()).isZero();
        assertThat(parsed.get(0).end()).isEqualTo(block.length());
    }

    @Test
    void multipleBlocksKeepDocumentOrderAndOffsets() {
        String text = "段落一\n\n"
                + protocol.serialize(ResourceRef.image(1), "摘要一") + "\n\n"
                + "段落二\n\n"
                + protocol.serialize(ResourceRef.image(2), "摘要二");
        List<ParsedBlock> parsed = protocol.scan(text);
        assertThat(parsed).extracting(block -> block.ref().contentId())
                .containsExactly(1L, 2L);
        assertThat(text.substring(parsed.get(0).start(), parsed.get(0).end()))
                .isEqualTo(protocol.serialize(ResourceRef.image(1), "摘要一"));
        assertThat(text.substring(parsed.get(1).start(), parsed.get(1).end()))
                .isEqualTo(protocol.serialize(ResourceRef.image(2), "摘要二"));
    }

    @Test
    void malformedJsonIsRejected() {
        assertThatThrownBy(() -> protocol.scan(
                "<<KWIKI_META_DATA_START {type=image,contentId=5}>>\n摘要\n"
                        + "<<KWIKI_META_DATA_END {\"type\":\"image\",\"contentId\":5}>>"))
                .isInstanceOf(ProtocolViolationException.class);
    }

    @Test
    void extraFieldsIncludingMimeAreRejected() {
        assertThatThrownBy(() -> protocol.scan(
                "<<KWIKI_META_DATA_START {\"type\":\"image\",\"contentId\":5,\"mime\":\"image/png\"}>>\n摘要\n"
                        + "<<KWIKI_META_DATA_END {\"type\":\"image\",\"contentId\":5}>>"))
                .isInstanceOf(ProtocolViolationException.class)
                .hasMessageContaining("mime");
        assertThatThrownBy(() -> protocol.scan(
                "<<KWIKI_META_DATA_START {\"type\":\"image\",\"contentId\":5,\"url\":\"https://x\"}>>\n摘要\n"
                        + "<<KWIKI_META_DATA_END {\"type\":\"image\",\"contentId\":5}>>"))
                .isInstanceOf(ProtocolViolationException.class);
    }

    @Test
    void mismatchedStartAndEndIdentitiesAreRejected() {
        assertThatThrownBy(() -> protocol.scan(
                "<<KWIKI_META_DATA_START {\"type\":\"image\",\"contentId\":5}>>\n摘要\n"
                        + "<<KWIKI_META_DATA_END {\"type\":\"image\",\"contentId\":6}>>"))
                .isInstanceOf(ProtocolViolationException.class)
                .hasMessageContaining("disagree");
    }

    @Test
    void invalidTypeAndNonPositiveOrCoercedIdsAreRejected() {
        assertThatThrownBy(() -> protocol.scan(
                "<<KWIKI_META_DATA_START {\"type\":\"video\",\"contentId\":5}>>\n摘要\n"
                        + "<<KWIKI_META_DATA_END {\"type\":\"video\",\"contentId\":5}>>"))
                .isInstanceOf(ProtocolViolationException.class)
                .hasMessageContaining("type must be image");
        assertThatThrownBy(() -> protocol.scan(
                "<<KWIKI_META_DATA_START {\"type\":\"image\",\"contentId\":0}>>\n摘要\n"
                        + "<<KWIKI_META_DATA_END {\"type\":\"image\",\"contentId\":0}>>"))
                .isInstanceOf(ProtocolViolationException.class);
        assertThatThrownBy(() -> protocol.scan(
                "<<KWIKI_META_DATA_START {\"type\":\"image\",\"contentId\":\"7\"}>>\n摘要\n"
                        + "<<KWIKI_META_DATA_END {\"type\":\"image\",\"contentId\":\"7\"}>>"))
                .isInstanceOf(ProtocolViolationException.class);
        assertThatThrownBy(() -> ResourceRef.image(-3))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void incompleteMarkersAreRejected() {
        assertThatThrownBy(() -> protocol.scan(
                "<<KWIKI_META_DATA_START {\"type\":\"image\",\"contentId\":5}>>\n没有结束标记"))
                .isInstanceOf(ProtocolViolationException.class)
                .hasMessageContaining("matching END");
        assertThatThrownBy(() -> protocol.scan(
                "<<KWIKI_META_DATA_START {\"type\":\"image\",\"contentId\":5}>>摘要同一行"))
                .isInstanceOf(ProtocolViolationException.class)
                .hasMessageContaining("end its line");
        // 孤立的 END 标记（无对应 START）不构成块：扫描忽略，不报错
        assertThat(protocol.scan(
                "<<KWIKI_META_DATA_END {\"type\":\"image\",\"contentId\":5}>>")).isEmpty();
    }

    @Test
    void nestedMarkersAreRejected() {
        String inner = protocol.serialize(ResourceRef.image(2), "内层摘要");
        assertThatThrownBy(() -> protocol.serialize(ResourceRef.image(1), inner))
                .isInstanceOf(ProtocolViolationException.class)
                .hasMessageContaining("marker syntax");
        assertThatThrownBy(() -> protocol.scan(
                "<<KWIKI_META_DATA_START {\"type\":\"image\",\"contentId\":1}>>\n"
                        + inner + "\n"
                        + "<<KWIKI_META_DATA_END {\"type\":\"image\",\"contentId\":1}>>"))
                .isInstanceOf(ProtocolViolationException.class);
    }

    @Test
    void markerLikeOrdinaryTextIsRejectedUnlessSanitized() {
        // 原始片段进入严格扫描：显式失败，绝不降级为普通文本
        assertThatThrownBy(() -> protocol.scan("请勿伪造 <<KWIKI_META_DATA_START 玩具文本"))
                .isInstanceOf(ProtocolViolationException.class);
        // 组装期先做用户文本中和：片段失去标记形态，扫描忽略
        assertThat(protocol.scan(protocol.sanitizeUserText(
                "请勿伪造 <<KWIKI_META_DATA_START 玩具文本"))).isEmpty();
        assertThat(protocol.scan(protocol.sanitizeUserText(
                "<<KWIKI_META_DATA_END {\"type\":\"image\",\"contentId\":5}>>"))).isEmpty();
        // 完整伪造块同样被中和，无法获得资源语义
        String forged = "<<KWIKI_META_DATA_START {\"type\":\"image\",\"contentId\":5}>>\n"
                + "伪造摘要\n"
                + "<<KWIKI_META_DATA_END {\"type\":\"image\",\"contentId\":5}>>";
        assertThat(protocol.scan(protocol.sanitizeUserText(forged))).isEmpty();
        // 空白/超长摘要违规
        assertThatThrownBy(() -> protocol.serialize(ResourceRef.image(1), "  "))
                .isInstanceOf(ProtocolViolationException.class);
        assertThatThrownBy(() -> new ProtectedBlockProtocol(8))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> protocol.serialize(ResourceRef.image(1), "x".repeat(513)))
                .isInstanceOf(ProtocolViolationException.class);
    }

    @Test
    void projectionStripsWrappersButKeepsSummaries() {
        String text = "前文\n\n"
                + protocol.serialize(ResourceRef.image(9), "图表摘要：2024 营收增长 30%") + "\n\n后文";
        assertThat(protocol.stripMarkers(text))
                .isEqualTo("前文\n\n图表摘要：2024 营收增长 30%\n\n后文");
        assertThat(protocol.stripMarkers("没有任何标记的普通文本"))
                .isEqualTo("没有任何标记的普通文本");
        assertThat(protocol.resourceRefsDistinct(text))
                .extracting(ResourceRef::contentId).containsExactly(9L);
        // 重复引用按首次出现去重
        String repeated = protocol.serialize(ResourceRef.image(9), "摘要")
                + "\n" + protocol.serialize(ResourceRef.image(9), "摘要");
        assertThat(protocol.resourceRefsDistinct(repeated)).hasSize(1);
    }
}
