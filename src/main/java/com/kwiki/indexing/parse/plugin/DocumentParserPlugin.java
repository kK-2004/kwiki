package com.kwiki.indexing.parse.plugin;

import com.kwiki.indexing.parse.StructuredDocument;
import com.kwiki.indexing.plugin.IndexCapability;
import com.kwiki.indexing.plugin.IndexPlugin;

import java.util.Set;

/** 文档解析器插件：将页面、附件和导入内容转换为结构化文档。 */
public interface DocumentParserPlugin extends IndexPlugin {
    /** 解析器要求字段格式提供的能力。 */
    Set<IndexCapability> requiredCapabilities();

    /** 除图片外可索引的附件类型（小写 MIME）。 */
    Set<String> indexableAttachmentTypes();

    StructuredDocument parsePage(PageParseRequest request);

    StructuredDocument parseAttachment(AttachmentParseRequest request);

    StructuredDocument parseImport(ImportParseRequest request);
}
