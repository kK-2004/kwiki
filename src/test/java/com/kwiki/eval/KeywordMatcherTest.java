package com.kwiki.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KeywordMatcherTest {

    @Test
    void normalizeFoldsWidthCaseWhitespaceAndThousandsSeparators() {
        assertThat(KeywordMatcher.normalize("１０，０００ 吨")).isEqualTo("10000吨");
        assertThat(KeywordMatcher.normalize("ABC  Corp\n")).isEqualTo("abccorp");
        assertThat(KeywordMatcher.normalize("1,2 和 3")).isEqualTo("1,2和3");
        assertThat(KeywordMatcher.normalize(null)).isEmpty();
    }

    @Test
    void anyAliasInAGroupCountsAsHit() {
        var groups = List.of(List.of("五亿元", "5亿元"), List.of("2017年8月"));
        assertThat(KeywordMatcher.matchedGroups(List.of("公司减少了5亿元负债"), groups))
                .containsExactly(0);
    }

    @Test
    void matchingIsPerSegmentAndNeverAcrossSegmentBoundaries() {
        var groups = List.of(List.of("2017年8月"));
        assertThat(KeywordMatcher.matchedGroups(List.of("2017年", "8月"), groups)).isEmpty();
        assertThat(KeywordMatcher.matchedGroups(List.of("无关", "2017 年 8 月发生"), groups))
                .containsExactly(0);
    }

    @Test
    void numbersWithSeparatorsMatchPlainNumbers() {
        var groups = List.of(List.of("10000吨"));
        assertThat(KeywordMatcher.matchedGroups(List.of("排放量为10,000吨"), groups)).containsExactly(0);
    }
}
