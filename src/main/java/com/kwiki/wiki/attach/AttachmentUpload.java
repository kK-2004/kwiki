package com.kwiki.wiki.attach;

import java.io.InputStream;

/**
 * Validated upload specification handed to the storage port: the caller has already
 * sanitized the file name and enforced the type/size allowlists.
 */
public record AttachmentUpload(String fileName, String contentType, InputStream content,
                               long byteSize) {
}
