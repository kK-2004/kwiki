package com.kwiki.wiki.attach;

/** Provider locator is internal; only the PUT URL and expiry may be sent to the browser. */
public record DirectUpload(String storageKey, String source, String putUrl, long expiresIn, Long fileId) {}
