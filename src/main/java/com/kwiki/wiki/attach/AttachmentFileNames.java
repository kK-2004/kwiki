package com.kwiki.wiki.attach;

/**
 * Attachment file-name validation. Names come from untrusted multipart requests:
 * traversal segments, absolute paths, and separator smuggling are rejected so a
 * hostile upload can never reference anything outside its own identity.
 */
public final class AttachmentFileNames {

    private AttachmentFileNames() {
    }

    /** Strips any path components; rejects traversal and separator smuggling. */
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
