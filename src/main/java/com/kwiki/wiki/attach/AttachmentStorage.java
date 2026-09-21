package com.kwiki.wiki.attach;

import java.io.InputStream;
import java.time.Duration;

/**
 * 基于内容中心（k-File）的附件存储端口。具体实现持有提供方协议；调用方
 * 仅通过 {@link #store} 返回的持久化内容中心文件 id 来寻址内容。
 */
public interface AttachmentStorage {

    /*底层对象存储返回的有界字节区间。 */
    record ContentRange(byte[] bytes, long start, long end, long total) {
        public ContentRange {
            if (bytes == null || start < 0 || end < start || total <= end) {
                throw new IllegalArgumentException("invalid content range");
            }
        }
    }

    /**
     * 上传数据流并返回带有已校验元数据的权威内容中心标识。实现必须在
     * 上报成功前严格校验返回结果，且绝不可在错误中泄露令牌或签名 URL。
     */
    StoredAttachment store(AttachmentUpload upload);

    /**
     * 删除一个已不再被任何本地附件引用的内容中心文件。实现必须把已不存在的
     * 对象视为删除成功，其他失败则抛出脱敏的 {@link AttachmentStorageException}，
     * 以便回收站在下次任务中重试。
     */
    default void delete(long contentCenterFileId) {
        throw new UnsupportedOperationException("file deletion is unavailable");
    }

    default DirectUpload initiateUpload(String fileName, String contentType, long byteSize) {
        throw new UnsupportedOperationException("direct upload is unavailable");
    }

    default StoredAttachment completeUpload(String storageKey, String source, String contentType, long byteSize) {
        throw new UnsupportedOperationException("direct upload is unavailable");
    }

    default StoredAttachment completeUpload(String storageKey, String source, String contentType, long byteSize, Long fileId) {
        return completeUpload(storageKey, source, contentType, byteSize);
    }

    /*为识别媒体签名而做的有界区间读取，绝不整文件中转。 */
    default byte[] readPrefix(long contentCenterFileId, int length) {
        throw new UnsupportedOperationException("range reads are unavailable");
    }

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
     * 只读取所请求的闭区间字节范围。对于不暴露
     * 区间读取能力的服务商保留安全的兼容兜底；内容中心适配器
     * 会用真正的 HTTP Range 请求覆盖该行为。
     */
    default ContentRange readContentRange(long contentCenterFileId, long start, long end) {
        if (start < 0 || end < start) throw new IllegalArgumentException("invalid content range");
        byte[] body = readContent(contentCenterFileId);
        if (start >= body.length) throw new IllegalArgumentException("content range starts beyond object");
        long actualEnd = Math.min(end, body.length - 1L);
        return new ContentRange(java.util.Arrays.copyOfRange(body, (int) start, (int) actualEnd + 1),
                start, actualEnd, body.length);
    }

    /**
     * 以内容中心文件 id 寻址的持久 CDN URL，用于会话结束后仍需存在的引用（导出）。
     * 不同于 {@link #downloadLink}，此处无预签名 TTL：是否永久由部署的 CDN 策略决定；
     * 与其它所有操作一样，失败以脱敏的 {@link AttachmentStorageException} 形式暴露。
     */
    String cdnLink(long contentCenterFileId);
}
