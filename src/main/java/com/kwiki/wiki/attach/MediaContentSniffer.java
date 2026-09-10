package com.kwiki.wiki.attach;

import java.util.Locale;
import java.util.Optional;

/**
 * Magic-byte sniffing for the media types the platform accepts. Declared MIME
 * types are never trusted alone: the actual content must match. This is the
 * "actual content/MIME validation" gate for both uploads and image indexing —
 * a mismatched body is rejected instead of indexed or previewed.
 */
public final class MediaContentSniffer {

    private MediaContentSniffer() {
    }

    /** Returns the sniffed image type, or empty when the bytes are not a known image. */
    public static Optional<String> sniffImageType(byte[] bytes) {
        if (bytes == null || bytes.length < 12) {
            return Optional.empty();
        }
        if ((bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N'
                && bytes[3] == 'G') {
            return Optional.of("image/png");
        }
        if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8
                && (bytes[2] & 0xFF) == 0xFF) {
            return Optional.of("image/jpg");
        }
        if (bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == '8') {
            return Optional.of("image/gif");
        }
        if (bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return Optional.of("image/webp");
        }
        return Optional.empty();
    }

    /** Returns the sniffed audio type, or empty when unrecognized. */
    public static Optional<String> sniffAudioType(byte[] bytes) {
        if (bytes == null || bytes.length < 12) {
            return Optional.empty();
        }
        // MP3: ID3 header or MPEG frame sync
        if (bytes[0] == 'I' && bytes[1] == 'D' && bytes[2] == '3') {
            return Optional.of("audio/mpeg");
        }
        if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xE0) == 0xE0) {
            return Optional.of("audio/mpeg");
        }
        // WAV: RIFF....WAVE
        if (bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'A' && bytes[10] == 'V' && bytes[11] == 'E') {
            return Optional.of("audio/wav");
        }
        // OGG
        if (bytes[0] == 'O' && bytes[1] == 'g' && bytes[2] == 'g' && bytes[3] == 'S') {
            return Optional.of("audio/ogg");
        }
        return Optional.empty();
    }

    /** Returns the sniffed video type, or empty when unrecognized. */
    public static Optional<String> sniffVideoType(byte[] bytes) {
        if (bytes == null || bytes.length < 12) {
            return Optional.empty();
        }
        // MP4: ftyp box at offset 4
        if (bytes[4] == 'f' && bytes[5] == 't' && bytes[6] == 'y' && bytes[7] == 'p') {
            return Optional.of("video/mp4");
        }
        // WebM: EBML header 0x1A45DFA3
        if ((bytes[0] & 0xFF) == 0x1A && (bytes[1] & 0xFF) == 0x45
                && (bytes[2] & 0xFF) == 0xDF && (bytes[3] & 0xFF) == 0xA3) {
            return Optional.of("video/webm");
        }
        return Optional.empty();
    }

    /**
     * Validates that the bytes match the declared content type. Treats the
     * legacy "image/jpg" alias as equivalent to image/jpeg.
     */
    public static boolean matchesDeclaredType(byte[] bytes, String declaredContentType) {
        String declared = declaredContentType == null
                ? "" : declaredContentType.toLowerCase(Locale.ROOT);
        return switch (declared) {
            case "image/png", "image/jpeg" -> sniffImageType(bytes)
                    .map(sniffed -> sniffed.equals(declared)
                            || ("image/jpg".equals(sniffed) && "image/jpeg".equals(declared)))
                    .orElse(false);
            case "image/gif", "image/webp" -> sniffImageType(bytes)
                    .map(sniffed -> sniffed.equals(declared))
                    .orElse(false);
            case "audio/mpeg", "audio/mp3" -> sniffAudioType(bytes)
                    .map(sniffed -> "audio/mpeg".equals(sniffed))
                    .orElse(false);
            case "audio/wav" -> sniffAudioType(bytes)
                    .map(sniffed -> "audio/wav".equals(sniffed))
                    .orElse(false);
            case "audio/ogg" -> sniffAudioType(bytes)
                    .map(sniffed -> "audio/ogg".equals(sniffed))
                    .orElse(false);
            case "video/mp4" -> sniffVideoType(bytes)
                    .map(sniffed -> "video/mp4".equals(sniffed))
                    .orElse(false);
            case "video/webm" -> sniffVideoType(bytes)
                    .map(sniffed -> "video/webm".equals(sniffed))
                    .orElse(false);
            default -> false;
        };
    }
}
