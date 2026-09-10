package com.kwiki.indexing.parse;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * kwiki 能够从中抽取可用文本的输入的显式白名单。其他任何输入
 * 都会在解析前被拒绝 —— 包括图片类型，因为 kwiki 从不运行 OCR。
 */
public final class TextInputAllowlist {

    public static final Set<String> DEFAULT_EXTENSIONS = Set.of(
            "md", "markdown", "txt", "html", "htm", "docx", "pdf");

    private static final Map<String, Set<String>> EXTENSION_TO_MIMES = Map.of(
            "md", Set.of("text/markdown", "text/plain"),
            "markdown", Set.of("text/markdown", "text/plain"),
            "txt", Set.of("text/plain"),
            "html", Set.of("text/html"),
            "htm", Set.of("text/html"),
            "docx", Set.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            "pdf", Set.of("application/pdf"));

    private final Set<String> allowedExtensions;

    public TextInputAllowlist() {
        this(DEFAULT_EXTENSIONS);
    }

    public TextInputAllowlist(Set<String> allowedExtensions) {
        this.allowedExtensions = Set.copyOf(allowedExtensions);
    }

    public String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        String name = fileName.toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1);
    }

    /** 当扩展名已启用且声明的 MIME 合理时允许通过。 */
    public boolean allows(String fileName, String contentType) {
        String extension = extensionOf(fileName);
        if (!allowedExtensions.contains(extension)) {
            return false;
        }
        if (contentType == null || contentType.isBlank() || "application/octet-stream".equals(contentType)) {
            return true; // 回退到扩展名；Tika 检测在解析时再次复核
        }
        Set<String> expected = EXTENSION_TO_MIMES.getOrDefault(extension, Set.of());
        String normalized = contentType.toLowerCase(Locale.ROOT);
        if (expected.contains(normalized)) {
            return true;
        }
        // 浏览器声明的 application/octet-stream 等类型不得拒绝合法文件
        return expected.isEmpty() || normalized.equals("application/octet-stream");
    }

    public String describeAccepted() {
        return String.join(", ", allowedExtensions.stream().sorted().toList());
    }
}
