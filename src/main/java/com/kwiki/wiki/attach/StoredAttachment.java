package com.kwiki.wiki.attach;

/**
 * 已完成上传的权威内容中心标识：kwiki 持久化的规范文件 id，
 * 以及内容中心上报的已校验大小/content type。storageKey 与 storageSource 是
 * 文件实际所在的对象键与数据源，用于在内容中心切换默认数据源后仍按真实位置读取；
 * 内容中心未返回时为 null。
 */
public record StoredAttachment(long contentCenterFileId, long verifiedByteSize,
                               String verifiedContentType, String storageKey, String storageSource) {

    public StoredAttachment(long contentCenterFileId, long verifiedByteSize, String verifiedContentType) {
        this(contentCenterFileId, verifiedByteSize, verifiedContentType, null, null);
    }
}
