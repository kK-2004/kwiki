package com.kwiki.indexing.multimodal;

/**
 * 图片视觉摘要端口：输入内容中心 CDN URL，输出可直接进入
 * 受保护块的中文检索摘要。实现负责协议细节、重试、并发与
 * 脱敏；失败以 {@link VisionSummaryException} 分类暴露。
 */
public interface ImageSummaryPort {

    String summarize(String cdnUrl);
}
