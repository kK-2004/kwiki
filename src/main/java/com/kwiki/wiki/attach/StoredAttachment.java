package com.kwiki.wiki.attach;

/**
 * Authoritative content-center identity for a completed upload: the canonical file
 * id kwiki persists, plus the verified size/content type the content center reports.
 * Storage keys and sources deliberately do not appear here — they stay internal to
 * the content center.
 */
public record StoredAttachment(long contentCenterFileId, long verifiedByteSize,
                               String verifiedContentType) {
}
