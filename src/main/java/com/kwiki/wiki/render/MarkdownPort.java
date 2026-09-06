package com.kwiki.wiki.render;

/**
 * Markdown rendering boundary: one implementation produces sanitized reader HTML,
 * one produces the plain-text projection stored with each revision for indexing.
 * Keeping it a port lets the domain stay renderer-agnostic and the fixtures test
 * the contract instead of a library.
 */
public interface MarkdownPort {

    /** Sanitized HTML for reader surfaces (safe against scripts and event handlers). */
    String renderToHtml(String markdown);

    /** Text-only projection (no markup) used for chunking and search. */
    String plainText(String markdown);
}
