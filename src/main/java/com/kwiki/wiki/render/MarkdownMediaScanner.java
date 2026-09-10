package com.kwiki.wiki.render;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scans markdown for inline media references with source ranges. Recognizes
 * standard images {@code ![alt](url)} and restricted
 * {@code <img>/<audio>/<video>} tags; fenced code blocks and escaped markers
 * are skipped so code samples never masquerade as media. Only
 * {@code attachment://uuid} references are reported as ATTACHMENT references —
 * external http(s) links stay display-only and are never fetched server-side.
 */
public final class MarkdownMediaScanner {

    public enum MediaKind {
        IMAGE, AUDIO, VIDEO, DOCUMENT
    }

    public record MediaReference(int start, int end, MediaKind kind, String src) {}

    private static final Pattern FENCE = Pattern.compile("^\\s*(```|~~~)");
    private static final Pattern IMAGE = Pattern.compile(
            "!\\[([^\\]]*)]\\(([^\\s)]+)(?:\\s+\"[^\"]*\")?\\)");
    private static final Pattern MEDIA_TAG = Pattern.compile(
            "<(img|audio|video)\\b[^>]*?src\\s*=\\s*\"([^\"]+)\"[^>]*?(/?)>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTACHMENT_TAG = Pattern.compile(
            "<a\\b[^>]*?href\\s*=\\s*\"([^\"]+)\"[^>]*?data-kwiki-attachment\\s*=\\s*\"true\"[^>]*?>[^<]*</a>",
            Pattern.CASE_INSENSITIVE);

    private MarkdownMediaScanner() {
    }

    public static List<MediaReference> scan(String markdown) {
        List<MediaReference> references = new ArrayList<>();
        if (markdown == null || markdown.isEmpty()) {
            return references;
        }
        String[] lines = markdown.split("\n", -1);
        boolean inFence = false;
        int offset = 0;
        List<int[]> plainSpans = new ArrayList<>();
        for (String line : lines) {
            int lineEnd = offset + line.length();
            Matcher fence = FENCE.matcher(line);
            if (fence.find()) {
                inFence = !inFence;
            } else if (!inFence) {
                plainSpans.add(new int[] { offset, lineEnd });
            }
            offset = lineEnd + 1;
        }
        for (int[] span : plainSpans) {
            collect(markdown, span[0], span[1], references);
        }
        references.sort((a, b) -> Integer.compare(a.start(), b.start()));
        return references;
    }

    private static void collect(String markdown, int from, int to,
                                List<MediaReference> references) {
        Matcher image = IMAGE.matcher(markdown);
        while (image.find()) {
            if (image.start() < from || image.end() > to + 1) continue;
            if (isEscaped(markdown, image.start())) continue;
            references.add(new MediaReference(image.start(), image.end(),
                    MediaKind.IMAGE, image.group(2)));
        }
        Matcher tag = MEDIA_TAG.matcher(markdown);
        while (tag.find()) {
            if (tag.start() < from || tag.end() > to + 1) continue;
            if (isEscaped(markdown, tag.start())) continue;
            String element = tag.group(1).toLowerCase();
            MediaKind kind = switch (element) {
                case "img" -> MediaKind.IMAGE;
                case "audio" -> MediaKind.AUDIO;
                default -> MediaKind.VIDEO;
            };
            references.add(new MediaReference(tag.start(), tag.end(), kind, tag.group(2)));
        }
        Matcher attachment = ATTACHMENT_TAG.matcher(markdown);
        while (attachment.find()) {
            if (attachment.start() < from || attachment.end() > to + 1) continue;
            if (isEscaped(markdown, attachment.start())) continue;
            references.add(new MediaReference(attachment.start(), attachment.end(),
                    MediaKind.DOCUMENT, attachment.group(1)));
        }
    }

    private static boolean isEscaped(String markdown, int start) {
        int backslashes = 0;
        for (int i = start - 1; i >= 0 && markdown.charAt(i) == '\\'; i--) {
            backslashes++;
        }
        return backslashes % 2 == 1;
    }

    /** Extracts the attachment uuid of an attachment:// reference, or null. */
    public static String attachmentUuid(String src) {
        if (src == null || !src.startsWith("attachment://")) {
            return null;
        }
        String uuid = src.substring("attachment://".length());
        return uuid.isBlank() ? null : uuid;
    }
}
