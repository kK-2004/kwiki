package com.kwiki.wiki.attach;

/**
 * 附件文件名校验。文件名来自不可信的 multipart 请求：
 * 目录穿越片段、绝对路径以及分隔符走私均会被拒绝，从而让
 * 恶意上传永远无法引用其自身标识之外的内容。
 */
public final class AttachmentFileNames {

    private AttachmentFileNames() {
    }

    /** 剥离所有路径成分；拒绝目录穿越与分隔符走私。 */
    public static String sanitizeFileName(String rawFileName) {
        if (rawFileName == null) {
            throw new IllegalArgumentException("file name is required");
        }
        String name = rawFileName.replace('\\', '/');
        if (name.contains("..") || name.contains("./") || name.contains("/.") || name.startsWith("/")) {
            throw new IllegalArgumentException("file name must not contain path segments");
        }
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        if (name.isBlank() || name.equals(".") || name.equals("..")) {
            throw new IllegalArgumentException("file name is required");
        }
        return name;
    }
}
