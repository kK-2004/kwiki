package com.kwiki.indexing.multimodal;

/**
 * embedding/检索上下文文本投影：去掉受保护块的 START/END 包装行，
 * 仅保留完整图片摘要。投影只应用于发送给 embedding 模型与
 * 回答上下文的文本；存储与资源抽取永远使用含标记的规范原文
 * （标记块是可重建资源字段的事实来源）。投影实例使用宽裕的
 * 摘要上限——持久化摘要已在上游受配置上限约束，此处只做
 * 剥离，不做二次校验拒绝。
 */
public final class ProtectedTextProjection {

    private static final ProtectedBlockProtocol PROJECTION_PROTOCOL =
            new ProtectedBlockProtocol(8192);

    private ProtectedTextProjection() {
    }

    /** 去除标记包装；不含受保护块的文本原样返回。 */
    public static String strip(String text) {
        return PROJECTION_PROTOCOL.stripMarkers(text);
    }
}
