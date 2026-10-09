package com.kwiki.rag.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kwiki.rag.orchestration.AgenticLimits;
import com.kwiki.rag.rewrite.ChatTurn;
import com.kwiki.rag.rewrite.ConversationalRewriter;
import com.kwiki.rag.rewrite.DecompositionRewriter;
import com.kwiki.rag.rewrite.ExpansionRewriter;
import com.kwiki.rag.rewrite.RewriteLlmPort;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

class RewriteQueryToolTest {
    final AgentToolTestSupport support = new AgentToolTestSupport();

    RewriteQueryTool tool(RewriteLlmPort llm) {
        return new RewriteQueryTool(
                new ConversationalRewriter(Optional.ofNullable(llm)),
                new ExpansionRewriter(Optional.ofNullable(llm)),
                new DecompositionRewriter(Optional.ofNullable(llm)),
                support.harness.rewriter,
                support.harness.budgets,
                AgenticLimits.defaults());
    }

    static AgentToolArguments.RewriteQuery mode(AgentToolArguments.RewriteKind kind) {
        return new AgentToolArguments.RewriteQuery(kind);
    }

    @Test
    void expansionUpdatesTheCurrentQueryAndRecordsTheRewrite() {
        EvidencePool pool = new EvidencePool("kwiki");

        var outcome = tool((system, user) -> Optional.of("kwiki 部署 配置"))
                .execute(mode(AgentToolArguments.RewriteKind.EXPANSION), support.context(support.run(), pool));

        assertThat(pool.currentQuery()).isEqualTo("kwiki 部署 配置");
        assertThat(pool.rewriteHistory()).containsExactly("kwiki 部署 配置");
        assertThat(pool.rewriteSuggestions()).containsExactly("kwiki 部署 配置");
        assertThat(pool.rewritesUsed()).isEqualTo(1);
        assertThat(outcome.evidenceRefs()).isEmpty();
        assertThat(outcome.stats()).containsEntry("suggestionCount", 1);
    }

    @Test
    void conversationalRewriteWithHistoryFallsBackDeterministicallyAndReportsIt() {
        EvidencePool pool = new EvidencePool("它怎么用");
        var context = support.context(support.run(), pool, List.of(new ChatTurn("USER", "介绍一下知识库")));

        var outcome = tool(null).execute(mode(AgentToolArguments.RewriteKind.CONVERSATIONAL), context);

        assertThat(pool.currentQuery()).contains("它怎么用").contains("介绍一下知识库");
        assertThat(outcome.degradations()).containsExactly("llm-unavailable");
    }

    @Test
    void conversationalRewriteWithoutHistoryChangesNothing() {
        EvidencePool pool = new EvidencePool("它怎么用");

        assertThatThrownBy(() -> tool(null).execute(mode(AgentToolArguments.RewriteKind.CONVERSATIONAL),
                support.context(support.run(), pool)))
                .isInstanceOf(AgentToolExecutor.ToolFailure.class).hasMessage("rewrite-no-change");
        assertThat(pool.currentQuery()).isEqualTo("它怎么用");
        assertThat(pool.rewriteHistory()).isEmpty();
    }

    @Test
    void decompositionOnlyProducesSuggestionsAndKeepsTheCurrentQuery() {
        EvidencePool pool = new EvidencePool("什么是部署；另外怎么备份");

        var outcome = tool(null).execute(mode(AgentToolArguments.RewriteKind.DECOMPOSITION),
                support.context(support.run(), pool));

        assertThat(pool.rewriteSuggestions()).containsExactly("什么是部署", "怎么备份");
        assertThat(pool.currentQuery()).isEqualTo("什么是部署；另外怎么备份");
        assertThat(pool.rewriteHistory()).isEmpty();
        assertThat(outcome.stats()).containsEntry("suggestionCount", 2);
        assertThat(outcome.degradations()).containsExactly("deterministic-split");
    }

    @Test
    void feedbackRewriteGetsTheStructuredFailureInput() {
        EvidencePool pool = new EvidencePool("原始问题");
        pool.recordFailure("coverage-missing");
        support.harness.rewriter.scripted.add(Optional.of("基于失败原因的新问题"));

        var outcome = tool(null).execute(mode(AgentToolArguments.RewriteKind.FEEDBACK),
                support.context(support.run(), pool, List.of(new ChatTurn("USER", "上一条"))));

        var input = support.harness.rewriter.inputs.getFirst();
        assertThat(input.originalQuery()).isEqualTo("原始问题");
        assertThat(input.lastQuery()).isEqualTo("原始问题");
        assertThat(input.stageFailures()).containsExactly("coverage-missing");
        assertThat(input.latestReason()).isEqualTo("coverage-missing");
        assertThat(input.topKBefore()).isEqualTo(8);
        assertThat(input.topKAfter()).isEqualTo(20);
        assertThat(input.conversationContext()).hasSize(1);
        assertThat(pool.currentQuery()).isEqualTo("基于失败原因的新问题");
        assertThat(outcome.stats()).containsEntry("suggestionCount", 1);
    }

    @Test
    void feedbackRewriteThatYieldsNothingOrEchoesIsNoChange() {
        EvidencePool pool = new EvidencePool("原始问题");
        support.harness.rewriter.scripted.add(Optional.empty());
        support.harness.rewriter.scripted.add(Optional.of("原始问题"));
        var tool = tool(null);
        var context = support.context(support.run(), pool);

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> tool.execute(mode(AgentToolArguments.RewriteKind.FEEDBACK), context))
                    .isInstanceOf(AgentToolExecutor.ToolFailure.class).hasMessage("rewrite-no-change");
        }
        assertThat(pool.rewritesUsed()).as("失败的改写仍消耗预算").isEqualTo(2);
        assertThat(pool.currentQuery()).isEqualTo("原始问题");
    }

    @Test
    void rewriteBudgetIsCheckedBeforeAnythingIsConsumed() {
        EvidencePool pool = new EvidencePool("q");
        pool.useRewrite();
        pool.useRewrite();

        assertThatThrownBy(() -> tool(null).execute(mode(AgentToolArguments.RewriteKind.FEEDBACK),
                support.context(support.run(), pool)))
                .isInstanceOf(AgentToolExecutor.ToolFailure.class).hasMessage("rewrite-budget-exhausted");
        assertThat(pool.rewritesUsed()).isEqualTo(2);
        assertThat(support.harness.rewriter.inputs).isEmpty();
    }

    @Test
    void modelBudgetExhaustionIsAToolFailure() {
        var limits = new AgenticLimits(3, 2, 1, 12, 3, 128, Duration.ofSeconds(300), 256, 16, 6, 6, 12, 12,
                Duration.ofSeconds(30));
        var run = support.run(limits);
        run.modelCall();
        EvidencePool pool = new EvidencePool("q");

        assertThatThrownBy(() -> tool(null).execute(mode(AgentToolArguments.RewriteKind.FEEDBACK),
                support.context(run, pool)))
                .isInstanceOf(AgentToolExecutor.ToolFailure.class).hasMessage("model-budget-exhausted");
        assertThat(pool.rewritesUsed()).isZero();
    }

    @Test
    void foreignArgumentsAreRejected() {
        assertThatThrownBy(() -> tool(null).execute(new AgentToolArguments.GraphExpand(null),
                support.context(support.run(), new EvidencePool("q"))))
                .isInstanceOf(AgentToolExecutor.ToolFailure.class).hasMessage("invalid-arguments");
        assertThat(tool(null).name()).isEqualTo("rewrite_query");
    }
}
