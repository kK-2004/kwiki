package com.kwiki.wiki.attach;

/**
 * Sanitized storage failure raised by attachment-storage adapters. Messages never
 * contain app tokens, authorization headers, or signed URLs; the category keeps the
 * retry decision (worker backoff vs terminal dead-letter) expressible without
 * exposing provider details.
 */
public class AttachmentStorageException extends RuntimeException {

    public enum Category {
        /** Worth retrying: transport errors, timeouts, remote 429/5xx. */
        TRANSIENT,
        /** Not worth retrying: rejected request, invalid content, unusable result. */
        PERMANENT
    }

    private final Category category;

    public AttachmentStorageException(Category category, String message) {
        super(message);
        this.category = category;
    }

    public AttachmentStorageException(Category category, String message, Throwable cause) {
        super(message, cause);
        this.category = category;
    }

    public Category getCategory() {
        return category;
    }
}
