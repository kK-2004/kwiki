package com.kwiki.rag.tool;

import static com.kwiki.rag.tool.AgentToolTestSupport.child;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kwiki.rag.retrieval.ChildEvidence;
import com.kwiki.rag.retrieval.GraphRetrievalEnhancer;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

class GraphExpandToolTest {
    final AgentToolTestSupport support = new AgentToolTestSupport();
    final GraphRetrievalEnhancer enhancer = mock(GraphRetrievalEnhancer.class);

    static ObjectProvider<GraphRetrievalEnhancer> provider(GraphRetrievalEnhancer value) {
        return new ObjectProvider<>() {
            @Override
            public GraphRetrievalEnhancer getIfAvailable() {
                return value;
            }
        };
    }

    GraphExpandTool tool() {
        return new GraphExpandTool(provider(enhancer));
    }

    EvidencePool poolWithOneChild() {
        EvidencePool pool = new EvidencePool("当前检索问题");
        pool.mergeChildren(List.of(child("C1", "P1", "片段", 0.5)), 12000);
        return pool;
    }

    void stubEnhance(List<ChildEvidence> merged, List<String> degradations, boolean enhanced) {
        when(enhancer.enhance(anyLong(), anyBoolean(), any(), any(), any(), any(), any(), eq(false)))
                .thenReturn(new GraphRetrievalEnhancer.Enhancement(merged, List.of(), degradations, enhanced));
    }

    @Test
    void enhancedResultReplacesThePoolAndReportsAddedChunks() {
        EvidencePool pool = poolWithOneChild();
        stubEnhance(List.of(child("C1", "P1", "片段", 0.5), child("G1", "P9", "图补充", 0.2)),
                List.of("graph-community-degraded"), true);

        var outcome = tool().execute(new AgentToolArguments.GraphExpand(null),
                support.context(support.run(), pool));

        assertThat(outcome.evidenceRefs()).containsExactly("G1");
        assertThat(outcome.degradations()).containsExactly("graph-community-degraded");
        assertThat(outcome.stats()).containsEntry("addedCount", 1).containsEntry("childCount", 2);
        assertThat(pool.childKeys()).containsExactlyInAnyOrder("C1", "G1");
    }

    @Test
    void notEnhancedLeavesThePoolUntouchedButKeepsTheDegradations() {
        EvidencePool pool = poolWithOneChild();
        stubEnhance(List.of(child("C1", "P1", "片段", 0.5)), List.of("graph-budget-exhausted"), false);

        var outcome = tool().execute(new AgentToolArguments.GraphExpand(null),
                support.context(support.run(), pool));

        assertThat(outcome.evidenceRefs()).isEmpty();
        assertThat(outcome.degradations()).containsExactly("graph-budget-exhausted");
        assertThat(outcome.stats()).containsEntry("addedCount", 0).containsEntry("childCount", 1);
    }

    @Test
    void usesTheCurrentQueryByDefaultAndAnExplicitQueryWhenGiven() {
        EvidencePool pool = poolWithOneChild();
        stubEnhance(List.of(child("C1", "P1", "片段", 0.5)), List.of(), false);
        var context = support.context(support.run(), pool);

        tool().execute(new AgentToolArguments.GraphExpand(null), context);
        tool().execute(new AgentToolArguments.GraphExpand("指定的图检索问题"), context);

        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        verify(enhancer, org.mockito.Mockito.times(2)).enhance(anyLong(), anyBoolean(), any(),
                query.capture(), any(), any(), any(), eq(false));
        assertThat(query.getAllValues()).containsExactly("当前检索问题", "指定的图检索问题");
    }

    @Test
    void passesTheRunIdentityAndSharedGraphBudget() {
        EvidencePool pool = poolWithOneChild();
        stubEnhance(List.of(child("C1", "P1", "片段", 0.5)), List.of(), false);

        tool().execute(new AgentToolArguments.GraphExpand(null), support.context(support.run(), pool));

        verify(enhancer).enhance(eq(1L), eq(true), any(), any(), any(), eq(pool.embeddingCache()),
                eq(pool.graphBudget()), eq(false));
    }

    @Test
    void failsWhenTheGraphIsDisabledOrThereIsNoEvidence() {
        GraphExpandTool disabled = new GraphExpandTool(provider(null));
        assertThatThrownBy(() -> disabled.execute(new AgentToolArguments.GraphExpand(null),
                support.context(support.run(), poolWithOneChild())))
                .isInstanceOf(AgentToolExecutor.ToolFailure.class).hasMessage("tool-unavailable");

        assertThatThrownBy(() -> tool().execute(new AgentToolArguments.GraphExpand(null),
                support.context(support.run(), new EvidencePool("q"))))
                .isInstanceOf(AgentToolExecutor.ToolFailure.class).hasMessage("no-evidence");
        verify(enhancer, never()).enhance(anyLong(), anyBoolean(), any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void foreignArgumentsAreRejected() {
        assertThatThrownBy(() -> tool().execute(new AgentToolArguments.RewriteQuery(
                AgentToolArguments.RewriteKind.FEEDBACK), support.context(support.run(), poolWithOneChild())))
                .isInstanceOf(AgentToolExecutor.ToolFailure.class).hasMessage("invalid-arguments");
        assertThat(tool().name()).isEqualTo("graph_expand");
    }
}
