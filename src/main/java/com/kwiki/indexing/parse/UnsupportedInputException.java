package com.kwiki.indexing.parse;

/**
 * The input is not indexable: outside the allowlist or carrying no extractable
 * text (OCR is never invoked — such files are rejected before any embedding
 * or index write).
 */
public class UnsupportedInputException extends RuntimeException {

    public UnsupportedInputException(String message) {
        super(message);
    }
}
