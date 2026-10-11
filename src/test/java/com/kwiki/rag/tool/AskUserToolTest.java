package com.kwiki.rag.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kwiki.rag.retrieval.ChildEvidence;

import org.junit.jupiter.api.Test;

import java.util.List;

class AskUserToolTest {
    final AgentToolTestSupport support = new AgentToolTestSupport();
    final AskUserTool tool = new AskUserTool();

    static ChildEvidence child(String key, long kb, String type, long resource) {
        return new ChildEvidence(key, "P-" + key, kb, type, resource, 1L, "h", 0, 3, "内容", 1, 1, 0.3);
    }

    EvidencePool pool() {
        EvidencePool pool = new EvidencePool("q");
        pool.mergeChildren(List.of(child("C1", 1, "PAGE", 11), child("C2", 2, "PAGE", 22),
                child("F1", 3, "FILE", 33)), 12000);
        return pool;
    }

    static AgentToolArguments.AskUser ask(String question, boolean freeText, String... refAndLabel) {
        var options = new java.util.ArrayList<AgentToolArguments.AskUser.Option>();
        for (int i = 0; i < refAndLabel.length; i += 2) {
            options.add(new AgentToolArguments.AskUser.Option(refAndLabel[i], refAndLabel[i + 1]));
        }
        return new AgentToolArguments.AskUser(question, options, freeText);
    }

    @Test
    void optionsAreDerivedFromTheEvidenceNotFromTheModel() {
        EvidencePool pool = pool();

        var outcome = tool.execute(ask("你想了解哪个知识库？", true, "C1", "部署手册", "C2", "运维手册"),
                support.context(support.run(), pool));

        var request = pool.clarification();
        assertThat(request.source()).isEqualTo("planner");
        assertThat(request.question()).isEqualTo("你想了解哪个知识库？");
        assertThat(request.allowFreeText()).isTrue();
        assertThat(request.options()).extracting(o -> o.id()).containsExactly("o1", "o2");
        assertThat(request.options()).extracting(o -> o.label()).containsExactly("部署手册", "运维手册");
        assertThat(request.options()).extracting(o -> o.kbId()).containsExactly(1L, 2L);
        assertThat(request.options()).extracting(o -> o.pageId()).containsExactly(11L, 22L);
        assertThat(outcome.stats()).containsEntry("optionCount", 2);
        assertThat(outcome.evidenceRefs()).isEmpty();
    }

    @Test
    void nonPageResourcesOnlyNarrowTheKnowledgeBase() {
        EvidencePool pool = pool();

        tool.execute(ask("哪一个？", true, "F1", "附件"), support.context(support.run(), pool));

        var option = pool.clarification().options().getFirst();
        assertThat(option.kbId()).isEqualTo(3L);
        assertThat(option.pageId()).isNull();
    }

    @Test
    void referencesMustExistInThePoolAndTargetsMustBeDistinct() {
        EvidencePool pool = pool();

        assertThatThrownBy(() -> tool.execute(ask("哪一个？", true, "C1", "甲", "NOPE", "乙"),
                support.context(support.run(), pool)))
                .isInstanceOf(AgentToolExecutor.ToolFailure.class).hasMessage("unknown-child-key");
        assertThatThrownBy(() -> tool.execute(ask("哪一个？", true, "C1", "甲", "C1", "乙"),
                support.context(support.run(), pool)))
                .isInstanceOf(AgentToolExecutor.ToolFailure.class).hasMessage("duplicate-option-target");
        assertThat(pool.clarification()).as("失败时不登记反问").isNull();
    }

    @Test
    void freeTextOnlyQuestionsAreAllowedButAnEmptyDeadEndIsNot() {
        EvidencePool pool = pool();
        tool.execute(ask("请补充限定条件", true), support.context(support.run(), pool));
        assertThat(pool.clarification().options()).isEmpty();

        assertThatThrownBy(() -> tool.execute(ask("请补充限定条件", false), support.context(support.run(), pool())))
                .isInstanceOf(AgentToolExecutor.ToolFailure.class).hasMessage("invalid-clarification");
        assertThatThrownBy(() -> tool.execute(ask("哪一个？", true, "C1", "  "), support.context(support.run(), pool())))
                .isInstanceOf(AgentToolExecutor.ToolFailure.class).hasMessage("invalid-clarification");
        assertThatThrownBy(() -> tool.execute(ask("  ", true), support.context(support.run(), pool())))
                .isInstanceOf(AgentToolExecutor.ToolFailure.class).hasMessage("invalid-clarification");
    }

    @Test
    void aRunCanOnlyAskOnce() {
        EvidencePool pool = pool();
        var context = support.context(support.run(), pool);
        tool.execute(ask("第一次？", true), context);

        assertThatThrownBy(() -> tool.execute(ask("第二次？", true), context))
                .isInstanceOf(AgentToolExecutor.ToolFailure.class).hasMessage("already-asked");
        assertThat(pool.clarification().question()).isEqualTo("第一次？");
    }

    @Test
    void foreignArgumentsAreRejected() {
        assertThatThrownBy(() -> tool.execute(new AgentToolArguments.GraphExpand(null),
                support.context(support.run(), pool())))
                .isInstanceOf(AgentToolExecutor.ToolFailure.class).hasMessage("invalid-arguments");
        assertThat(tool.name()).isEqualTo("ask_user");
    }
}
