package com.kwiki.rag.routing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 带版本号的确定性关键词规则。只有当恰好命中一个终止性意图
 * 且查询不是复合句时，查询才会不经 LLM 直接判定；
 * 无命中、冲突以及复合情形都会交给路由器 LLM。
 */
public final class KeywordRuleSet {

    public static final String VERSION = "rules-v1";

    public record KeywordRule(String id, Pattern pattern, Intent intent) {
    }

    public sealed interface MatchResult {
        record Resolved(Intent intent, List<String> ruleIds) implements MatchResult {
        }

        record NeedsLlm(String reason, List<String> matchedRuleIds) implements MatchResult {
        }
    }

    private final List<KeywordRule> rules;

    public KeywordRuleSet(List<KeywordRule> rules) {
        this.rules = List.copyOf(rules);
    }

    public static KeywordRuleSet defaults() {
        List<KeywordRule> rules = new ArrayList<>();
        rules.add(new KeywordRule("greet-zh", Pattern.compile("^(你好|您好|嗨|在吗|早上好|下午好)$"), Intent.DIRECT_ANSWER));
        rules.add(new KeywordRule("greet-en", Pattern.compile("^(hi|hello|hey|good morning|good afternoon)$"), Intent.DIRECT_ANSWER));
        rules.add(new KeywordRule("procedural-zh", Pattern.compile("(怎么|如何|怎样|步骤|操作指南|教程)"), Intent.PROCEDURAL));
        rules.add(new KeywordRule("procedural-en", Pattern.compile("\\b(how to|how do|steps to|guide to)\\b"), Intent.PROCEDURAL));
        rules.add(new KeywordRule("analytical-zh", Pattern.compile("(对比|区别|差异|分析|优缺点|总结)"), Intent.ANALYTICAL));
        rules.add(new KeywordRule("analytical-en", Pattern.compile("\\b(compare|difference|analyze|pros and cons|why)\\b"), Intent.ANALYTICAL));
        rules.add(new KeywordRule("knowledge-zh", Pattern.compile("(什么是|是什么|介绍一下|解释|定义)"), Intent.KNOWLEDGE_QA));
        rules.add(new KeywordRule("knowledge-en", Pattern.compile("\\b(what is|who is|explain|definition of)\\b"), Intent.KNOWLEDGE_QA));
        return new KeywordRuleSet(rules);
    }

    public String version() {
        return VERSION;
    }

    public MatchResult match(String normalizedQuery) {
        Map<Intent, List<String>> matched = new LinkedHashMap<>();
        for (KeywordRule rule : rules) {
            if (rule.pattern().matcher(normalizedQuery).find()) {
                matched.computeIfAbsent(rule.intent(), intent -> new ArrayList<>()).add(rule.id());
            }
        }
        List<String> allIds = matched.values().stream().flatMap(List::stream).toList();
        if (matched.isEmpty()) {
            return new MatchResult.NeedsLlm("no-rule-match", allIds);
        }
        if (matched.size() > 1) {
            return new MatchResult.NeedsLlm("conflicting-intents:" + matched.keySet(), allIds);
        }
        if (isCompound(normalizedQuery)) {
            return new MatchResult.NeedsLlm("compound-query", allIds);
        }
        Map.Entry<Intent, List<String>> only = matched.entrySet().iterator().next();
        return new MatchResult.Resolved(only.getKey(), only.getValue());
    }

    /** 多个问号或显式连接词暗示该问题可被拆解。 */
    static boolean isCompound(String normalizedQuery) {
        if (normalizedQuery.chars().filter(ch -> ch == '?' || ch == '？').count() > 1) {
            return true;
        }
        return Pattern.compile("(另外|同时|以及呢|和呢|; then| and also )").matcher(normalizedQuery).find();
    }
}
