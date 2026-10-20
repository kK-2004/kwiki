package com.kwiki.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RefusalDetectorTest {

    final RefusalDetector detector = new RefusalDetector(List.of("无法回答", "没有相关信息"));

    @Test
    void insufficientOutcomeIsARefusal() {
        assertThat(detector.refused("insufficient", "")).isTrue();
    }

    @Test
    void refusalPhraseInAnswerIsARefusal() {
        assertThat(detector.refused("completed", "知识库中没有相关 信息，我无法进行回答")).isTrue();
    }

    @Test
    void ordinaryAnswerIsNotARefusal() {
        assertThat(detector.refused("completed", "子公司为绿源新能源")).isFalse();
        assertThat(detector.refused(null, null)).isFalse();
    }
}
