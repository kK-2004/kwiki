package com.kwiki.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RefusalDetectorTest {

    final RefusalDetector detector = RefusalDetector.defaults();

    @Test
    void insufficientOutcomeIsARefusal() {
        assertThat(detector.refused("insufficient", "")).isTrue();
        assertThat(detector.reason("insufficient", "")).contains("outcome=insufficient");
    }

    /** 2026-10-21 50q 评测中被误判为「未拒答」的真实回答。 */
    @ParameterizedTest
    @ValueSource(strings = {
            "提供的证据未明确说明“环境管理政策”的制定时间；其中提到公司于2021年2月采用了环境管理系统 [P0]。",
            "所给证据未提供媒体之星有限公司2017年的净资产收益率，因此无法计算或比较。",
            "现有判决书材料中，钱某仅被记载为“苹果市正义律师事务所律师” [P0]，没有提供其家庭住址。",
            "翠湖市人民医院住院病历中未记载杜某某的血型，无法根据该病历确定 [P0]。",
            "知识库中没有相关信息，我无法进行回答"
    })
    void naturalRefusalWordingIsRecognised(String answer) {
        assertThat(detector.refused("completed", answer)).isTrue();
    }

    @Test
    void reasonQuotesTheMatchedWording() {
        assertThat(detector.reason("completed", "病历中未记载杜某某的血型"))
                .hasValueSatisfying(reason -> assertThat(reason).contains("未记载"));
    }

    @Test
    void ordinaryAnswerIsNotARefusal() {
        assertThat(detector.refused("completed", "子公司为绿源新能源，成立于2017年12月")).isFalse();
        assertThat(detector.refused(null, null)).isFalse();
        assertThat(detector.reason("completed", "子公司为绿源新能源")).isEmpty();
    }

    @Test
    void customPatternsAreSupported() {
        var custom = new RefusalDetector(List.of("查无此项"));
        assertThat(custom.refused("completed", "查无此项")).isTrue();
        assertThat(custom.refused("completed", "无法回答")).isFalse();
    }
}
