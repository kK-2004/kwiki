package com.kwiki.indexing.parse;

import java.util.Locale;
import java.util.Set;

/**
 * Single source of truth for which attachments may enter the document index:
 * only images whose actual content was validated at upload. Audio, video,
 * PDF, Office and every other attachment stays page-display-only — no chunks,
 * no embedding, no ES upsert — across upload, retry, worker and restore
 * rebuild paths. Wiki page bodies remain indexed by the publish rules and are
 * unaffected by this classification.
 */
public final class AttachmentIndexEligibility {

    /** Image content types eligible for document indexing. */
    public static final Set<String> INDEXABLE_IMAGE_TYPES = Set.of(
            "image/png", "image/jpeg", "image/gif", "image/webp");

    /** All media content types accepted for upload (display + preview only). */
    public static final Set<String> MEDIA_AUDIO_TYPES = Set.of(
            "audio/mpeg", "audio/mp3", "audio/wav", "audio/ogg");
    public static final Set<String> MEDIA_VIDEO_TYPES = Set.of(
            "video/mp4", "video/webm");

    private AttachmentIndexEligibility() {
    }

    public static boolean isIndexableImage(String contentType) {
        return contentType != null
                && INDEXABLE_IMAGE_TYPES.contains(contentType.toLowerCase(Locale.ROOT));
    }

    public static boolean isAudio(String contentType) {
        return contentType != null
                && MEDIA_AUDIO_TYPES.contains(contentType.toLowerCase(Locale.ROOT));
    }

    public static boolean isVideo(String contentType) {
        return contentType != null
                && MEDIA_VIDEO_TYPES.contains(contentType.toLowerCase(Locale.ROOT));
    }

    public static boolean isMedia(String contentType) {
        return isAudio(contentType) || isVideo(contentType)
                || (contentType != null
                        && contentType.toLowerCase(Locale.ROOT).startsWith("image/"));
    }
}
