package com.kwiki.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class QaRetrievalBudgetsTest {

    private static QaRetrievalBudgets defaults() {
        return new QaRetrievalBudgets(20, 8, 50, 20, 8, 16, 12000, 24000, 24000);
    }

    @Test
    void invalidStartupConfigFailsFast() {
        assertThatThrownBy(() -> new QaRetrievalBudgets(20, 30, 50, 20, 8, 16, 12000, 24000, 24000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void environmentChangeReplacesWholeSnapshot() {
        var budgets = defaults();
        var env = new MockEnvironment()
                .withProperty("kwiki.retrieval.qa.base-final-topk", "10")
                .withProperty("kwiki.retrieval.qa.expanded-branch-topk", "80")
                .withProperty("kwiki.retrieval.qa.expanded-final-topk", "30");
        budgets.setEnvironment(env);

        budgets.onEnvironmentChange();

        assertThat(budgets.base()).isEqualTo(new QaRetrievalBudgets.StageBudget(20, 10, 8, 12000));
        assertThat(budgets.expanded()).isEqualTo(new QaRetrievalBudgets.StageBudget(80, 30, 16, 24000));
        assertThat(budgets.parentCharBudget()).isEqualTo(24000);
    }

    @Test
    void invalidRefreshKeepsPreviousSnapshot() {
        var budgets = defaults();
        var before = budgets.snapshot();

        // finalTopK 超过 branchTopK
        assertThat(budgets.reload(new MockEnvironment()
                .withProperty("kwiki.retrieval.qa.base-final-topk", "30"))).isFalse();
        // 无法解析为数字
        assertThat(budgets.reload(new MockEnvironment()
                .withProperty("kwiki.retrieval.qa.base-branch-topk", "abc"))).isFalse();

        assertThat(budgets.snapshot()).isSameAs(before);
    }

    @Test
    void unchangedValuesKeepSameSnapshot() {
        var budgets = defaults();
        var before = budgets.snapshot();

        assertThat(budgets.reload(new MockEnvironment())).isFalse();
        assertThat(budgets.snapshot()).isSameAs(before);
    }

    @Test
    void removedKeyFallsBackToDefault() {
        var budgets = new QaRetrievalBudgets(20, 12, 50, 20, 8, 16, 12000, 24000, 24000);

        assertThat(budgets.reload(new MockEnvironment())).isTrue();
        assertThat(budgets.base().finalTopK()).isEqualTo(8);
    }
}
