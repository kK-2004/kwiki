package com.kwiki.indexing.parse;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Explicit allowlist of inputs kwiki can extract usable text from. Anything else
 * is rejected before parsing — including image types, because kwiki never runs OCR.
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

    /** Allowed when the extension is enabled and the declared MIME is plausible. */
    public boolean allows(String fileName, String contentType) {
        String extension = extensionOf(fileName);
        if (!allowedExtensions.contains(extension)) {
            return false;
        }
        if (contentType == null || contentType.isBlank() || "application/octet-stream".equals(contentType)) {
            return true; // fall back to extension; Tika detection double-checks at parse
        }
        Set<String> expected = EXTENSION_TO_MIMES.getOrDefault(extension, Set.of());
        String normalized = contentType.toLowerCase(Locale.ROOT);
        if (expected.contains(normalized)) {
            return true;
        }
        // declared types like application/octet-stream from browsers must not reject valid files
        return expected.isEmpty() || normalized.equals("application/octet-stream");
    }

    public String describeAccepted() {
        return String.join(", ", allowedExtensions.stream().sorted().toList());
    }
}
