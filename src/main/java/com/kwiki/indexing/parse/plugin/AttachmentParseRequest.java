package com.kwiki.indexing.parse.plugin;

import com.kwiki.wiki.domain.Attachment;

import java.util.function.Supplier;

/** 附件解析输入，按需读取附件内容。 */
public record AttachmentParseRequest(Attachment attachment, Supplier<byte[]> content) {
}
