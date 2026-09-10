package com.kwiki.wiki.attach;

import java.io.InputStream;

/**
 * 交由存储端口的、已校验的上传规格：调用方已完成文件名的净化，
 * 并强制执行了类型/大小允许列表。
 */
public record AttachmentUpload(String fileName, String contentType, InputStream content,
                               long byteSize) {
}
