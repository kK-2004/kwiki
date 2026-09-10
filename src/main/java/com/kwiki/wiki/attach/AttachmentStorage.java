package com.kwiki.wiki.attach;

import java.io.InputStream;
import java.time.Duration;

/**
 * Attachment-storage port over the content center (k-File). Implementations own the
 * provider protocol; callers address content only by the persisted content-center
 * file id returned from {@link #store}. There is deliberately no delete operation:
 * content lifecycle is owned by the content center, so archive removes only local
 * metadata and the search index entry.
 */
public interface AttachmentStorage {

    /**
     * Uploads the stream and returns the authoritative content-center identity with
     * verified metadata. Implementations must validate the returned result strictly
     * before reporting success and must never leak tokens or signed URLs in errors.
     */
    StoredAttachment store(AttachmentUpload upload);

    /**
     * Short-lived browser download URL addressed by content-center file id; the
     * download filename matches the attachment metadata. Never a permanent link.
     */
    String downloadLink(long contentCenterFileId, String downloadFileName, Duration ttl);

    /**
     * Reads the stored bytes (used by the indexing worker) through a freshly issued
     * short-lived link, with bounded time and size. Implementations fail closed on
     * truncated or oversized responses.
     */
    byte[] readContent(long contentCenterFileId);

    /**
     * Durable CDN URL addressed by content-center file id, meant for references that
     * outlive a session (exports). Unlike {@link #downloadLink} there is no presign
     * TTL: the deployment's CDN policy decides permanence; failures surface as
     * sanitized {@link AttachmentStorageException}s like every other operation.
     */
    String cdnLink(long contentCenterFileId);
}
