package com.kwiki.wiki.api;

import com.kwiki.security.CurrentUser;
import com.kwiki.wiki.access.ResourceAction;
import com.kwiki.wiki.access.ResourceAuthorizationService;
import com.kwiki.wiki.domain.WikiPageRevision;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Persists Java/String (UTF-16 code-unit) offsets against a concrete published revision. */
@Service
public class SelectionAnchorService {
    private final JdbcOperations jdbc;
    private final ResourceAuthorizationService authorization;
    private final PageRevisionService revisions;

    public SelectionAnchorService(ObjectProvider<JdbcOperations> jdbc, ResourceAuthorizationService authorization, PageRevisionService revisions) { this.jdbc = jdbc.getIfAvailable(); this.authorization = authorization; this.revisions = revisions; }

    @Transactional
    public AnchorView create(CurrentUser user, long kbId, long pageId, String selectedText, Integer startOffset, Integer endOffset) {
        if (jdbc == null) throw new IllegalStateException("database is unavailable");
        authorization.requireInKnowledgeBase(user, kbId, pageId, ResourceAction.READ);
        WikiPageRevision revision = revisions.publishedContent(user, kbId, pageId);
        String quote = selectedText == null ? "" : selectedText.trim(); if (quote.isEmpty()) throw new IllegalArgumentException("selection is required");
        if (quote.contains("\n\n") || quote.contains("\r\n\r\n")) throw new IllegalArgumentException("selection must stay within one paragraph");
        String plain = revision.getPlainText(); int start = startOffset == null ? plain.indexOf(quote) : startOffset; int end = endOffset == null ? start + quote.length() : endOffset;
        if (start < 0 || end <= start || end > plain.length() || !plain.substring(start, end).equals(quote)) throw new IllegalArgumentException("selection does not match revision");
        String paragraph = paragraph(plain, start, end); String hash = digest(paragraph);
        jdbc.update("INSERT INTO selection_anchor (page_id, revision_id, block_id, paragraph_hash, start_offset, end_offset, quote, prefix_text, suffix_text, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", pageId, revision.getId(), "paragraph-" + hash, hash, start, end, quote, prefix(plain, start), suffix(plain, end), user.id());
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return new AnchorView(id == null ? 0 : id, pageId, revision.getRevisionNo(), start, end, quote, hash);
    }

    /**
     * Resolves a notification/comment anchor against the current published revision.
     * The original revision is kept as a safe fallback whenever a new revision has
     * no unique match; callers must never highlight an arbitrary duplicate quote.
     */
    @Transactional(readOnly = true)
    public AnchorResolution resolve(CurrentUser user, long kbId, long pageId, long anchorId) {
        if (jdbc == null) throw new IllegalStateException("database is unavailable");
        authorization.requireInKnowledgeBase(user, kbId, pageId, ResourceAction.READ);
        AnchorRow anchor = jdbc.query(
                "SELECT id, page_id, revision_id, start_offset, end_offset, quote, prefix_text, suffix_text, paragraph_hash "
                        + "FROM selection_anchor WHERE id = ? AND page_id = ?",
                ps -> { ps.setLong(1, anchorId); ps.setLong(2, pageId); },
                rs -> rs.next() ? new AnchorRow(rs.getLong("id"), rs.getLong("page_id"), rs.getLong("revision_id"),
                        rs.getInt("start_offset"), rs.getInt("end_offset"), rs.getString("quote"),
                        rs.getString("prefix_text"), rs.getString("suffix_text"), rs.getString("paragraph_hash")) : null);
        if (anchor == null) throw new com.kk2004.common.exception.NotFoundException("selection anchor not found");

        long originalRevisionId = anchor.revisionId();
        int originalStart = anchor.start();
        int originalEnd = anchor.end();
        String quote = Objects.toString(anchor.quote(), "");
        String paragraphHash = Objects.toString(anchor.paragraphHash(), "");
        String prefix = Objects.toString(anchor.prefix(), "");
        String suffix = Objects.toString(anchor.suffix(), "");
        var original = revisions.revisionHistory(user, kbId, pageId).stream()
                .filter(item -> Objects.equals(item.getId(), originalRevisionId)).findFirst().orElse(null);
        var current = revisions.publishedContent(user, kbId, pageId);
        int originalRevisionNo = original == null ? 0 : original.getRevisionNo();
        if (original != null && Objects.equals(original.getId(), current.getId())
                && validRange(original.getPlainText(), originalStart, originalEnd, quote)) {
            return new AnchorResolution(anchorId, pageId, "CURRENT", current.getRevisionNo(), originalRevisionNo,
                    originalStart, originalEnd, quote, "原文位置仍然有效", false);
        }

        String plain = current.getPlainText() == null ? "" : current.getPlainText();
        List<Integer> all = occurrences(plain, quote);
        List<Integer> paragraphMatches = all.stream()
                .filter(start -> Objects.equals(paragraphHash, digest(paragraph(plain, start, start + quote.length()))))
                .toList();
        List<Integer> contextualMatches = (paragraphMatches.isEmpty() ? all : paragraphMatches).stream()
                .filter(start -> contextMatches(plain, start, quote.length(), prefix, suffix))
                .toList();
        if (contextualMatches.size() == 1) {
            int start = contextualMatches.getFirst();
            return new AnchorResolution(anchorId, pageId, "RELOCATED", current.getRevisionNo(), originalRevisionNo,
                    start, start + quote.length(), quote, "原文已更新，已按上下文唯一定位", true);
        }
        String message = contextualMatches.size() > 1 || (contextualMatches.isEmpty() && all.size() > 1)
                ? "原文出现多个可能位置，已回退到历史版本"
                : "原文已变化或被删除，已回退到历史版本";
        return new AnchorResolution(anchorId, pageId, contextualMatches.size() > 1 || all.size() > 1 ? "AMBIGUOUS" : "HISTORICAL",
                originalRevisionNo, originalRevisionNo, originalStart, originalEnd, quote, message, false);
    }

    private List<Integer> occurrences(String text, String quote) {
        if (quote.isEmpty()) return List.of();
        java.util.ArrayList<Integer> matches = new java.util.ArrayList<>();
        int from = 0;
        while (from <= text.length() - quote.length()) {
            int found = text.indexOf(quote, from);
            if (found < 0) break;
            matches.add(found);
            from = found + Math.max(1, quote.length());
        }
        return matches;
    }

    private boolean contextMatches(String plain, int start, int length, String prefix, String suffix) {
        String before = plain.substring(Math.max(0, start - prefix.length()), start);
        String after = plain.substring(start + length, Math.min(plain.length(), start + length + suffix.length()));
        boolean prefixOk = prefix.isEmpty() || before.endsWith(prefix);
        boolean suffixOk = suffix.isEmpty() || after.startsWith(suffix);
        return prefixOk && suffixOk;
    }

    private boolean validRange(String plain, int start, int end, String quote) {
        return start >= 0 && end > start && end <= plain.length() && end - start == quote.length()
                && plain.substring(start, end).equals(quote);
    }

    private record AnchorRow(long id, long pageId, long revisionId, int start, int end, String quote,
                             String prefix, String suffix, String paragraphHash) {}

    private String paragraph(String plain, int start, int end) { int left = plain.lastIndexOf("\n\n", start); int right = plain.indexOf("\n\n", end); return plain.substring(left < 0 ? 0 : left + 2, right < 0 ? plain.length() : right); }
    private String prefix(String value, int start) { return value.substring(Math.max(0, start - 300), start); }
    private String suffix(String value, int end) { return value.substring(end, Math.min(value.length(), end + 300)); }
    private String digest(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception ex) { throw new IllegalStateException(ex); } }
    public record AnchorView(long id, long pageId, int revisionNo, int startOffset, int endOffset, String quote, String paragraphHash) {}
    public record AnchorResolution(long anchorId, long pageId, String status, int revisionNo, int originalRevisionNo,
                                   int startOffset, int endOffset, String quote, String message, boolean currentRevision) {}
}
