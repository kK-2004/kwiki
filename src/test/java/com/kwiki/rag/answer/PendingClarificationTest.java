package com.kwiki.rag.answer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

class PendingClarificationTest {

    private static PendingClarification pending() {
        return PendingClarification.from(Map.of(
                "clarificationId", "c-1",
                "question", "你想了解哪一个？",
                "options", List.of(
                        Map.of("id", "a", "label", "部署文档"),
                        Map.of("id", "b", "label", "运维手册", "kbId", 7, "pageId", 9)),
                "allowFreeText", true,
                "source", "router"), "介绍一下它");
    }

    @Test
    void roundTripsThroughJson() {
        PendingClarification restored = PendingClarification.parse(pending().toJson());

        assertThat(restored).isEqualTo(pending());
        assertThat(PendingClarification.parse("not json")).isNull();
        assertThat(PendingClarification.parse(null)).isNull();
    }

    @Test
    void optionAnswerUsesServerSideLabelAndScope() {
        var resolved = pending().resolve(new ClarificationReply("c-1", "b", "客户端伪造的文案")).orElseThrow();

        assertThat(resolved.effectiveQuery()).contains("介绍一下它").contains("运维手册")
                .doesNotContain("客户端伪造的文案");
        assertThat(resolved.kbIds()).containsExactly(7L);
        assertThat(resolved.pageIds()).containsExactly(9L);
    }

    @Test
    void freeTextAnswerIsBoundedAndComposedIntoTheQuery() {
        String longText = "补".repeat(900);

        var resolved = pending().resolve(new ClarificationReply("c-1", null, longText)).orElseThrow();

        assertThat(resolved.effectiveQuery().length()).isLessThanOrEqualTo(1000);
        assertThat(resolved.effectiveQuery()).startsWith("介绍一下它").contains("用户补充");
        assertThat(resolved.kbIds()).isEmpty();
    }

    @Test
    void mismatchedIdUnknownOptionAndEmptyAnswerAreRejected() {
        assertThat(pending().resolve(new ClarificationReply("other", "a", null))).isEmpty();
        assertThat(pending().resolve(new ClarificationReply("c-1", "zzz", null))).isEmpty();
        assertThat(pending().resolve(new ClarificationReply("c-1", null, "  "))).isEmpty();
        assertThat(pending().resolve(null)).isEmpty();
    }

    @Test
    void freeTextIsRejectedWhenNotAllowed() {
        var restricted = PendingClarification.from(Map.of(
                "clarificationId", "c-2", "question", "二选一",
                "options", List.of(Map.of("id", "a", "label", "甲")),
                "allowFreeText", false), "问题");

        assertThat(restricted.resolve(new ClarificationReply("c-2", null, "随便"))).isEmpty();
        assertThat(restricted.resolve(new ClarificationReply("c-2", "a", null))).isPresent();
    }

    @Test
    void chosenScopeNarrowsTheRequestedScope() {
        var resolved = pending().resolve(new ClarificationReply("c-1", "b", null)).orElseThrow();

        assertThat(resolved.kbScope(Set.of())).containsExactly(7L);
        assertThat(resolved.kbScope(Set.of(7L, 8L))).containsExactly(7L);
        assertThat(resolved.kbScope(Set.of(8L))).containsExactly(7L);

        var plain = pending().resolve(new ClarificationReply("c-1", "a", null)).orElseThrow();
        assertThat(plain.kbScope(Set.of(8L))).containsExactly(8L);
        assertThat(plain.pageScope(Set.of())).isEmpty();
    }
}
