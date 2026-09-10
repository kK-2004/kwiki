package com.kwiki.wiki.attach;

import java.io.InputStream;
import java.time.Duration;

/**
 * 基于内容中心（k-File）的附件存储端口。具体实现持有提供方协议；调用方
 * 仅通过 {@link #store} 返回的持久化内容中心文件 id 来寻址内容。
 * 此处刻意不提供删除操作：内容生命周期由内容中心掌管，因此归档只移除
 * 本地元数据与搜索索引条目。
 */
public interface AttachmentStorage {

    /**
     * 上传数据流并返回带有已校验元数据的权威内容中心标识。实现必须在
     * 上报成功前严格校验返回结果，且绝不可在错误中泄露令牌或签名 URL。
     */
    StoredAttachment store(AttachmentUpload upload);

    /**
     * 以内容中心文件 id 寻址的短期浏览器下载 URL；下载文件名与附件元数据一致。
     * 绝非永久链接。
     */
    String downloadLink(long contentCenterFileId, String downloadFileName, Duration ttl);

    /**
     * 通过新签发的短期链接读取已存储字节（供索引构建 worker 使用），
     * 限定时间与大小。实现在响应被截断或超大型时默认拒绝。
     */
    byte[] readContent(long contentCenterFileId);

    /**
     * 以内容中心文件 id 寻址的持久 CDN URL，用于会话结束后仍需存在的引用（导出）。
     * 不同于 {@link #downloadLink}，此处无预签名 TTL：是否永久由部署的 CDN 策略决定；
     * 与其它所有操作一样，失败以脱敏的 {@link AttachmentStorageException} 形式暴露。
     */
    String cdnLink(long contentCenterFileId);
}
