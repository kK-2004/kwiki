package com.kwiki.eval;

import com.kwiki.rag.orchestration.AgenticErrorCodes;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 负样本拒答判定：终态为证据不足，或回答（归一化后）匹配任一拒答表达。
 * 检索召回到相关段落时工作流会正常生成，模型在回答里说明「未提及/无法确定」，
 * 措辞多样，因此用一组正则覆盖常见说法，而不是固定短语。
 */
public final class RefusalDetector {

    /** 默认拒答表达；匹配对象是 {@link KeywordMatcher#normalize} 后的回答（无空白、小写、NFKC）。 */
    public static final List<String> DEFAULT_PATTERNS = List.of(
            "无法(进行)?(回答|确定|判断|得知|获知|获取|计算|比较|给出|提供|查到|找到|回复)",
            "不能(确定|回答|判断)",
            "(未|没有|并未|尚未|均未|并没有)(明确|直接|具体)?"
                    + "(说明|提及|提到|提供|记载|记录|涉及|给出|找到|披露|包含|显示|写明|载明)",
            "(信息|证据|资料)不足",
            "未找到相关|没有相关(信息|内容|记录)|不包含(相关|该)");

    private final List<Pattern> patterns;

    public RefusalDetector(List<String> patterns) {
        this.patterns = patterns.stream().filter(pattern -> pattern != null && !pattern.isBlank())
                .map(Pattern::compile).toList();
    }

    public static RefusalDetector defaults() {
        return new RefusalDetector(DEFAULT_PATTERNS);
    }

    public boolean refused(String outcome, String answer) {
        return reason(outcome, answer).isPresent();
    }

    /** 判为拒答的依据（终态或命中的原文片段），供报告与 Langfuse 分数备注核对。 */
    public Optional<String> reason(String outcome, String answer) {
        if (AgenticErrorCodes.INSUFFICIENT.equals(outcome)) return Optional.of("outcome=insufficient");
        String normalized = KeywordMatcher.normalize(answer);
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(normalized);
            if (matcher.find()) return Optional.of("matched=" + matcher.group());
        }
        return Optional.empty();
    }
}
