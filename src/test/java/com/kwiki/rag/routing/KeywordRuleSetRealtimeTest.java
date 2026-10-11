package com.kwiki.rag.routing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.util.List;

class KeywordRuleSetRealtimeTest {
    final KeywordRuleSet rules = KeywordRuleSet.defaults();

    /** 与路由器一致：规则只看归一化后的查询。 */
    private KeywordRuleSet.MatchResult match(String query) {
        return rules.match(QueryNormalizer.normalize(query));
    }

    @Test
    void realtimeQuestionsResolveToDirectAnswer() {
        for (String query : List.of("现在几点了", "现在几点了？", "几点了", "今天几号", "今天星期几？",
                "现在是什么时间", "今天天气怎么样", "当前什么日期", "What time is it?", "what day is it",
                "今天天气如何", "今天是什么日期", "今天是星期几", "今天是礼拜几", "what's the time",
                "what is today's date", "What Time Is It?", "what day is it today")) {
            var result = match(query);
            assertThat(result).as(query).isInstanceOf(KeywordRuleSet.MatchResult.Resolved.class);
            var resolved = (KeywordRuleSet.MatchResult.Resolved) result;
            assertThat(resolved.intent()).as(query).isEqualTo(Intent.DIRECT_ANSWER);
            assertThat(resolved.ruleIds()).as(query).anyMatch(id -> id.startsWith("realtime-"));
        }
    }

    @Test
    void knowledgeBaseQuestionsThatMentionTimeAreNotIntercepted() {
        for (String query : List.of("今天的会议时间", "2025营业收入", "发布时间是几点", "什么是时间序列数据库",
                "部署窗口的日期安排")) {
            var result = match(query);
            boolean realtime = result instanceof KeywordRuleSet.MatchResult.Resolved resolved
                    && resolved.ruleIds().stream().anyMatch(id -> id.startsWith("realtime-"));
            assertThat(realtime).as(query).isFalse();
        }
    }

    @Test
    void realtimeQuestionWithRepeatedPunctuationStillResolves() {
        for (String query : List.of("几点了？？", "现在几点了??")) {
            var result = match(query);
            assertThat(result).as(query).isInstanceOf(KeywordRuleSet.MatchResult.Resolved.class);
            var resolved = (KeywordRuleSet.MatchResult.Resolved) result;
            assertThat(resolved.intent()).as(query).isEqualTo(Intent.DIRECT_ANSWER);
            assertThat(resolved.ruleIds()).as(query).anyMatch(id -> id.startsWith("realtime-"));
        }
    }

    @Test
    void trulyCompoundRealtimeQueryIsNotResolvedByRealtimeRule() {
        assertThat(match("现在几点了？今天天气怎么样？")).isInstanceOf(KeywordRuleSet.MatchResult.NeedsLlm.class);
    }

    @Test
    void nonTerminalCompoundQueryStillGoesToTheLlm() {
        assertThat(match("什么是知识库？另外怎么部署？")).isInstanceOf(KeywordRuleSet.MatchResult.NeedsLlm.class);
    }

    @Test
    void existingRulesBehaviorIsUnchanged() {
        for (String query : List.of("会议是什么时间", "TCP 是什么时间复杂度")) {
            var result = match(query);
            assertThat(result).as(query).isInstanceOf(KeywordRuleSet.MatchResult.Resolved.class);
            var resolved = (KeywordRuleSet.MatchResult.Resolved) result;
            assertThat(resolved.intent()).as(query).isEqualTo(Intent.KNOWLEDGE_QA);
            assertThat(resolved.ruleIds()).as(query).contains("knowledge-zh");
        }
        for (String query : List.of("怎么部署", "怎么部署应用")) {
            var result = match(query);
            assertThat(result).as(query).isInstanceOf(KeywordRuleSet.MatchResult.Resolved.class);
            assertThat(((KeywordRuleSet.MatchResult.Resolved) result).intent()).as(query).isEqualTo(Intent.PROCEDURAL);
        }
    }

    @Test
    void ruleSetVersionIsBumped() {
        assertThat(KeywordRuleSet.VERSION).isEqualTo("rules-v2");
        assertThat(rules.version()).isEqualTo("rules-v2");
    }
}
