package com.kwiki.wiki.api;

/** Safe messages produced only by the import boundary, never by external services. */
public class WikiImportValidationException extends RuntimeException {
    public WikiImportValidationException(String message) { super(message); }
}
