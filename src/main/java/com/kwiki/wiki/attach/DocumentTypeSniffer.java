package com.kwiki.wiki.attach;

import java.util.Locale;
import java.util.Optional;

/**
 * PDF/DOCX 导入来源的魔数校验：页面来源预览只信任
 * 实际字节签名，而不是文件扩展名或上传时声明的 MIME。
 */
public final class DocumentTypeSniffer {

    public static final String PDF_TYPE = "application/pdf";
    public static final String DOCX_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    /** OOXML 将 [Content_Types].xml 作为最早的 zip 条目之一，其文件名以明文出现在本地头中。 */
    private static final byte[] OOXML_CONTENT_TYPES = "[Content_Types].xml".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final int ZIP_HEADER_SCAN_LIMIT = 8192;

    private DocumentTypeSniffer() {
    }

    /** 返回字节签名支持的可预览文档类型（PDF/DOCX），否则为空。 */
    public static Optional<String> sniffDocumentType(byte[] bytes) {
        if (bytes == null || bytes.length < 5) return Optional.empty();
        if (bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F' && bytes[4] == '-') {
            return Optional.of(PDF_TYPE);
        }
        if (bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K' && bytes[2] == 3 && bytes[3] == 4
                && contains(bytes, OOXML_CONTENT_TYPES, Math.min(bytes.length, ZIP_HEADER_SCAN_LIMIT))) {
            return Optional.of(DOCX_TYPE);
        }
        return Optional.empty();
    }

    /** 声明的 MIME 是否属于支持浏览器预览的文档类型。 */
    public static boolean isPreviewableDeclaredType(String contentType) {
        String normalized = contentType == null ? "" : contentType.trim().toLowerCase(Locale.ROOT);
        return PDF_TYPE.equals(normalized) || DOCX_TYPE.equals(normalized);
    }

    /** 面向前端页签的稳定格式标识；不可预览的类型返回 null。 */
    public static String previewFormat(String contentType) {
        String normalized = contentType == null ? "" : contentType.trim().toLowerCase(Locale.ROOT);
        if (PDF_TYPE.equals(normalized)) return "PDF";
        if (DOCX_TYPE.equals(normalized)) return "DOCX";
        return null;
    }

    private static boolean contains(byte[] haystack, byte[] needle, int limit) {
        outer:
        for (int at = 0; at + needle.length <= limit; at++) {
            for (int i = 0; i < needle.length; i++) {
                if (haystack[at + i] != needle[i]) continue outer;
            }
            return true;
        }
        return false;
    }
}
