package com.kwiki.eval;

/** 漏召关键词组的首次丢失位置。 */
public enum LossLayer {
    /** 在上下文中，但回答没写出来。 */
    GENERATION_MISS,
    /** 两路 Top-50 都没搜到（分词/嵌入/切块问题）。 */
    RECALL_MISS,
    /** 在候选池中，但没进扩大阶段 Top-20。 */
    RANK_MISS_20,
    /** 在 Top-20 中，但没进基础阶段 Top-8。 */
    RANK_MISS_8,
    /** 在基础 Top-8 中，却没进最终上下文（阶梯、父块裁剪或预算）。 */
    CONTEXT_MISS
}
