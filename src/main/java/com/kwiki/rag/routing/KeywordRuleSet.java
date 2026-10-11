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
 * 例外：整句锚定的 terminal 规则（如实时问句）先于且独立于复合句判断，命中后优先于其他规则，直接判定；
 * 它们是整句锚定的，不可能是复合句（重复标点如「现在几点了??」同样直接命中）。
 */
public final class KeywordRuleSet {

    public static final String VERSION = "rules-v2";

    /** terminal 规则（整句锚定）命中时优先于其他规则，不参与意图冲突判定。 */
    public record KeywordRule(String id, Pattern pattern, Intent intent, boolean terminal) {
        public KeywordRule(String id, Pattern pattern, Intent intent) {
            this(id, pattern, intent, false);
        }
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
        rules.add(new KeywordRule("greet-zh", Pattern.compile("^(你好|您好|嗨|在吗|早上好|下午好|帮助)[!！。?？]*$"), Intent.DIRECT_ANSWER));
        rules.add(new KeywordRule("greet-en", Pattern.compile("^(hi|hello|hey|good morning|good afternoon|help)[!！。?？.]*$"), Intent.DIRECT_ANSWER));
        rules.add(new KeywordRule("procedural-zh", Pattern.compile("(怎么|如何|怎样|步骤|操作指南|教程)"), Intent.PROCEDURAL));
        rules.add(new KeywordRule("procedural-en", Pattern.compile("\\b(how to|how do|steps to|guide to)\\b"), Intent.PROCEDURAL));
        rules.add(new KeywordRule("analytical-zh", Pattern.compile("(对比|区别|差异|分析|优缺点|总结)"), Intent.ANALYTICAL));
        rules.add(new KeywordRule("analytical-en", Pattern.compile("\\b(compare|difference|analyze|pros and cons|why)\\b"), Intent.ANALYTICAL));
        rules.add(new KeywordRule("knowledge-zh", Pattern.compile("(什么是|是什么|介绍一下|解释|定义)"), Intent.KNOWLEDGE_QA));
        rules.add(new KeywordRule("knowledge-en", Pattern.compile("\\b(what is|who is|explain|definition of)\\b"), Intent.KNOWLEDGE_QA));
        // 实时信息：整句锚定的短问句，知识库里没有当前时间/天气；「今天的会议时间」这类带「的」的不会命中。
        // 裸「天气」也按设计命中。terminal=true：命中即优先于 knowledge-*/procedural-* 等泛化规则
        rules.add(new KeywordRule("realtime-zh", Pattern.compile("^(现在|当前|今天)?(是)?(几点|几号|星期几|礼拜几|什么时间|什么日期|天气(怎么样|如何)?)(了|啊|呢)?[吗?？!！。]*$"), Intent.DIRECT_ANSWER, true));
        rules.add(new KeywordRule("realtime-en", Pattern.compile("^(what time is it|what's the time|what is today's date|what day is it)( now| today)?[?!.]*$", Pattern.CASE_INSENSITIVE), Intent.DIRECT_ANSWER, true));
        return new KeywordRuleSet(rules);
    }

    public String version() {
        return VERSION;
    }

    public MatchResult match(String normalizedQuery) {
        // terminal 规则整句锚定，不可能是复合句：先于 isCompound 判断，重复标点也能命中
        for (KeywordRule rule : rules) {
            if (rule.terminal() && rule.pattern().matcher(normalizedQuery).find()) {
                return new MatchResult.Resolved(rule.intent(), List.of(rule.id()));
            }
        }
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
