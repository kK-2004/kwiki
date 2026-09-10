package com.kwiki.wiki.attach;

/**
 * 已完成上传的权威内容中心标识：kwiki 持久化的规范文件 id，
 * 以及内容中心上报的已校验大小/content type。
 * 存储密钥与来源刻意不出现于此——它们保留在内容中心内部。
 */
public record StoredAttachment(long contentCenterFileId, long verifiedByteSize,
                               String verifiedContentType) {
}
