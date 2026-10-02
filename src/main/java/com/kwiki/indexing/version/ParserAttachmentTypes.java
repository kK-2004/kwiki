package com.kwiki.indexing.version;

import com.kwiki.indexing.parse.plugin.DocumentParserPlugin;
import java.util.List;
import java.util.TreeSet;
import java.util.Collections;

/** 图片公共路径与解析器声明类型共同决定有效附件范围。 */
final class ParserAttachmentTypes {
    private ParserAttachmentTypes() { }
    static List<String> forParser(DocumentParserPlugin parser) {
        TreeSet<String> types = new TreeSet<>(List.of("image/png", "image/jpeg", "image/gif", "image/webp"));
        for (String type : parser.indexableAttachmentTypes()) {
            if (type == null || !type.matches("^[a-z0-9.+/-]+$")) {
                throw new IllegalArgumentException("解析器 " + parser.id() + " 声明非法附件类型：" + type);
            }
            types.add(type);
        }
        return List.copyOf(types);
    }
    static String placeholders(List<String> types) {
        return "(" + String.join(",", Collections.nCopies(types.size(), "?")) + ")";
    }
}
