package com.kwiki.usage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UsageStepsTest {

    @Test
    void labelsCoverPurposesEmbeddingStepsAndUnknownValues() {
        assertThat(UsageSteps.label("REWRITE")).isEqualTo("查询改写");
        assertThat(UsageSteps.label(UsageSteps.QUERY_EMBEDDING)).isEqualTo("查询向量");
        assertThat(UsageSteps.label(UsageSteps.INDEX_EMBEDDING)).isEqualTo("索引向量");
        assertThat(UsageSteps.label("SOMETHING_ELSE")).isEqualTo("SOMETHING_ELSE");
        assertThat(UsageSteps.label(null)).isEqualTo("unknown");
    }

    @Test
    void eventNormalisesMissingValues() {
        var event = new UsageEvent(null, null, null, null, null, -1, null, -5, -1, -3, true, null);
        assertThat(event.occurredAt()).isNotNull();
        assertThat(event.source()).isEqualTo(UsageSource.BACKGROUND);
        assertThat(event.step()).isEqualTo("unknown");
        assertThat(event.provider()).isEqualTo("unknown");
        assertThat(event.model()).isEqualTo("unknown");
        assertThat(event.userId()).isZero();
        assertThat(event.inputTokens()).isZero();
        assertThat(event.outputTokens()).isZero();
        assertThat(event.latencyMs()).isZero();
    }
}
