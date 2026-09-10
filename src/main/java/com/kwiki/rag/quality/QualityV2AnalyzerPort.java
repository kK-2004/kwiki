package com.kwiki.rag.quality;

/**
 * quality-v2 分析器：评估将被发布的具象候选答案，
 * 而非仅评估 evidence。实现需返回结构已校验的
 * {@link QualityAssessment}；格式失败应作为
 * {@code qa-unavailable} 暴露，而非内容质量判定。
 */
public interface QualityV2AnalyzerPort {

    QualityAssessment assess(QualityV2Input input);
}
