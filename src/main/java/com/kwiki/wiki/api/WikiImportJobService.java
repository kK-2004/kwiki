package com.kwiki.wiki.api;

import com.kwiki.security.CurrentUser;
import com.kwiki.wiki.access.KnowledgeBaseAuthorizationService;
import com.kwiki.wiki.access.WikiAction;
import com.kwiki.wiki.attach.AttachmentStorage;
import com.kwiki.wiki.attach.AttachmentUpload;
import com.kwiki.wiki.attach.StoredAttachment;
import com.kwiki.wiki.domain.Attachment;
import com.kwiki.wiki.persistence.AttachmentRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;

/** Durable import lifecycle. Uploads are stored as provenance and parsed once; the
 * generated Wiki revision is the only content sent to the indexing pipeline. */
@Service
public class WikiImportJobService {
    private final JdbcOperations jdbc;
    private final AttachmentRepository attachments;
    private final AttachmentStorage storage;
    private final KnowledgeBaseAuthorizationService authorization;
    private final WikiImportService importer;

    public WikiImportJobService(ObjectProvider<JdbcOperations> jdbc,
                                AttachmentRepository attachments,
                                AttachmentStorage storage,
                                KnowledgeBaseAuthorizationService authorization,
                                WikiImportService importer) {
        this.jdbc = jdbc.getIfAvailable();
        this.attachments = attachments;
        this.storage = storage;
        this.authorization = authorization;
        this.importer = importer;
    }

    public ImportJobView submit(CurrentUser user, long kbId, Long parentId, String fileName,
                                String contentType, byte[] content, String audienceMode,
                                List<ResourceAudienceService.Member> audienceMembers,
                                String requestedIdempotencyKey) {
        requireDb();
        authorization.require(user, kbId, WikiAction.UPLOAD_ATTACHMENT);
        importer.validateUpload(fileName, contentType, content);
        String key = normalizeKey(requestedIdempotencyKey);
        ImportJobView existing = findOwned(user, key);
        if (existing != null) return existing;
        String safeName = fileName == null || fileName.isBlank() ? "导入文档" : fileName;
        String mode = audienceMode == null ? "KB_MEMBERS" : audienceMode.trim().toUpperCase(Locale.ROOT);
        if ("SELECTED_MEMBERS".equals(mode) && (audienceMembers == null || audienceMembers.isEmpty())) {
            throw new IllegalArgumentException("selected audience requires members");
        }
        if (!List.of("PRIVATE", "SELECTED_MEMBERS", "KB_MEMBERS").contains(mode)) {
            throw new IllegalArgumentException("invalid audience mode");
        }
        String memberJson = membersJson(audienceMembers);
        String jobUuid = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO wiki_import_job (uuid, created_by, kb_id, parent_id, idempotency_key, file_name, content_type, audience_mode, audience_members_json, warnings_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                jobUuid, user.id(), kbId, parentId, key, safeName,
                contentType == null ? "application/octet-stream" : contentType, mode, memberJson, "[]");
        long jobId = jdbc.queryForObject("SELECT id FROM wiki_import_job WHERE uuid = ?", Long.class, jobUuid);
        try {
            Attachment attachment = attachments.save(new Attachment(UUID.randomUUID().toString(), kbId, user.id(), safeName,
                    contentType == null ? "application/octet-stream" : contentType, content.length));
            attachment.markWikiImportSource();
            attachment = attachments.save(attachment);
            StoredAttachment stored = storage.store(new AttachmentUpload(safeName,
                    contentType == null ? "application/octet-stream" : contentType,
                    new java.io.ByteArrayInputStream(content), content.length));
            if (stored.verifiedByteSize() != content.length) throw new IllegalStateException("attachment storage returned inconsistent size");
            attachment.markStored(stored.contentCenterFileId());
            attachments.save(attachment);
            jdbc.update("UPDATE wiki_import_job SET source_attachment_id = ?, state = 'STORED', updated_at = CURRENT_TIMESTAMP(6) WHERE id = ?", attachment.getId(), jobId);
            return process(user, jobId);
        } catch (RuntimeException failure) {
            fail(jobId, failure);
            return detail(user, jobId);
        }
    }

    public ImportJobView detail(CurrentUser user, long jobId) {
        requireDb();
        ImportRow row = row(jobId);
        if (row.createdBy() != user.id() && !user.admin()) throw new AccessDeniedException("import job is unavailable");
        return view(row);
    }

    public List<ImportJobView> list(CurrentUser user, int limit) {
        requireDb();
        int bounded = Math.max(1, Math.min(limit, 100));
        return jdbc.query("SELECT id, uuid, created_by, kb_id, parent_id, source_attachment_id, idempotency_key, file_name, content_type, audience_mode, state, page_id, revision_id, attempt_count, last_error, warnings_json, created_at, updated_at FROM wiki_import_job WHERE created_by = ? ORDER BY id DESC LIMIT " + bounded,
                (rs, n) -> view(new ImportRow(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getLong(4), nullableLong(rs.getObject(5)), nullableLong(rs.getObject(6)), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11), nullableLong(rs.getObject(12)), nullableLong(rs.getObject(13)), rs.getInt(14), rs.getString(15), rs.getString(16), rs.getTimestamp(17).toInstant(), rs.getTimestamp(18).toInstant())), user.id());
    }

    public ImportJobView retry(CurrentUser user, long jobId) {
        requireDb();
        ImportRow row = row(jobId);
        if (row.createdBy() != user.id() && !user.admin()) throw new AccessDeniedException("import job is unavailable");
        if (!"FAILED".equals(row.state())) throw new IllegalArgumentException("only failed imports can be retried");
        if (row.attachmentId() == null) throw new IllegalArgumentException("import has no stored attachment");
        jdbc.update("UPDATE wiki_import_job SET state = 'STORED', last_error = NULL, lease_owner = NULL, lease_expires_at = NULL, updated_at = CURRENT_TIMESTAMP(6) WHERE id = ? AND state = 'FAILED'", jobId);
        return process(loadCreator(row), jobId);
    }

    /** Recovers uploads left in STORED/PARSING after a process restart. The lease
     * predicate makes concurrent schedulers harmless and the source relation makes
     * a retry converge on the already-created page. */
    @Scheduled(fixedDelayString = "${kwiki.wiki-import.worker.poll-interval:10000}")
    public void recoverPending() {
        if (jdbc == null) return;
        List<Long> ids;
        try {
            ids = jdbc.query("SELECT id FROM wiki_import_job WHERE state = 'STORED' OR (state = 'PARSING' AND (lease_expires_at IS NULL OR lease_expires_at < CURRENT_TIMESTAMP(6))) ORDER BY id LIMIT 5",
                    (rs, row) -> rs.getLong(1));
        } catch (RuntimeException unavailable) {
            return;
        }
        for (Long id : ids) {
            try { process(loadCreator(row(id)), id); }
            catch (RuntimeException ignored) { /* fail() records the reason for the next retry */ }
        }
    }

    private ImportJobView process(CurrentUser user, long jobId) {
        ImportRow row = row(jobId);
        if (row.pageId() != null && "SUCCEEDED".equals(row.state())) return view(row);
        String leaseOwner = "import-" + UUID.randomUUID();
        int claimed = jdbc.update("UPDATE wiki_import_job SET state = 'PARSING', attempt_count = attempt_count + 1, lease_owner = ?, lease_expires_at = DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 10 MINUTE), updated_at = CURRENT_TIMESTAMP(6) WHERE id = ? AND (state = 'STORED' OR (state = 'PARSING' AND (lease_expires_at IS NULL OR lease_expires_at < CURRENT_TIMESTAMP(6))))", leaseOwner, jobId);
        if (claimed != 1) return detail(user, jobId);
        try {
            Attachment attachment = attachments.findById(row.attachmentId()).orElseThrow(() -> new IllegalArgumentException("source attachment not found"));
            Long existingPage = jdbc.query("SELECT page_id FROM source_document WHERE attachment_id = ? ORDER BY id LIMIT 1", rs -> rs.next() ? rs.getLong(1) : null, attachment.getId());
            if (existingPage != null) {
                Long existingRevision = jdbc.queryForObject("SELECT current_published_revision_id FROM wiki_page WHERE id = ?", Long.class, existingPage);
                jdbc.update("UPDATE wiki_import_job SET state = 'SUCCEEDED', page_id = ?, revision_id = ?, lease_owner = NULL, lease_expires_at = NULL, last_error = NULL, updated_at = CURRENT_TIMESTAMP(6) WHERE id = ?", existingPage, existingRevision, jobId);
                return detail(user, jobId);
            }
            byte[] content = storage.readContent(attachment.getContentCenterFileId());
            String audienceMembersJson = jdbc.queryForObject("SELECT audience_members_json FROM wiki_import_job WHERE id = ?", String.class, jobId);
            List<ResourceAudienceService.Member> members = parseMembers(audienceMembersJson);
            WikiImportService.ImportedPage page = importer.importDocument(user, row.kbId(), row.parentId(), row.fileName(), row.contentType(), content, row.audienceMode(), members, attachment.getId());
            Long revisionId = jdbc.queryForObject("SELECT current_published_revision_id FROM wiki_page WHERE id = ?", Long.class, page.id());
            jdbc.update("UPDATE wiki_import_job SET state = 'SUCCEEDED', page_id = ?, revision_id = ?, warnings_json = ?, lease_owner = NULL, lease_expires_at = NULL, last_error = NULL, updated_at = CURRENT_TIMESTAMP(6) WHERE id = ?", page.id(), revisionId, json(page.warnings()), jobId);
        } catch (RuntimeException failure) {
            fail(jobId, failure);
        }
        return detail(user, jobId);
    }

    private void fail(long jobId, RuntimeException failure) {
        String raw = failure.getMessage() == null ? "" : failure.getMessage();
        String message;
        if (failure instanceof org.springframework.security.access.AccessDeniedException) message = "没有导入或发布到此知识库的权限";
        else if (raw.contains("no extractable text")) message = "文档中没有可提取的文字，暂不支持扫描件或纯图片文档";
        else if (failure instanceof com.kwiki.indexing.parse.UnsupportedInputException) message = "文档无法解析，请确认文件未损坏、未加密，并重新另存为 DOCX 后导入";
        else if (failure instanceof IllegalArgumentException && raw.startsWith("DOCX")) message = "DOCX 文件结构无效，或包含不支持的嵌入文件";
        else message = row(jobId).attachmentId() == null ? "文件存储失败，请重新上传" : "导入处理失败，请重试；若仍失败，请提供任务编号以便排查";
        org.slf4j.LoggerFactory.getLogger(WikiImportJobService.class)
                .warn("Wiki import job {} failed ({})", jobId, failure.getClass().getSimpleName());
        jdbc.update("UPDATE wiki_import_job SET state = 'FAILED', last_error = ?, lease_owner = NULL, lease_expires_at = NULL, updated_at = CURRENT_TIMESTAMP(6) WHERE id = ?", message.substring(0, Math.min(500, message.length())), jobId);
    }

    private ImportRow row(long jobId) {
        try {
            return jdbc.queryForObject("SELECT id, uuid, created_by, kb_id, parent_id, source_attachment_id, idempotency_key, file_name, content_type, audience_mode, state, page_id, revision_id, attempt_count, last_error, warnings_json, created_at, updated_at FROM wiki_import_job WHERE id = ?", (rs, n) -> new ImportRow(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getLong(4), nullableLong(rs.getObject(5)), nullableLong(rs.getObject(6)), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11), nullableLong(rs.getObject(12)), nullableLong(rs.getObject(13)), rs.getInt(14), rs.getString(15), rs.getString(16), rs.getTimestamp(17).toInstant(), rs.getTimestamp(18).toInstant()), jobId);
        } catch (EmptyResultDataAccessException ex) { throw new IllegalArgumentException("import job not found"); }
    }

    private ImportJobView findOwned(CurrentUser user, String key) {
        List<ImportRow> rows = jdbc.query("SELECT id, uuid, created_by, kb_id, parent_id, source_attachment_id, idempotency_key, file_name, content_type, audience_mode, state, page_id, revision_id, attempt_count, last_error, warnings_json, created_at, updated_at FROM wiki_import_job WHERE created_by = ? AND idempotency_key = ?", (rs, n) -> new ImportRow(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getLong(4), nullableLong(rs.getObject(5)), nullableLong(rs.getObject(6)), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11), nullableLong(rs.getObject(12)), nullableLong(rs.getObject(13)), rs.getInt(14), rs.getString(15), rs.getString(16), rs.getTimestamp(17).toInstant(), rs.getTimestamp(18).toInstant()), user.id(), key);
        return rows.isEmpty() ? null : view(rows.get(0));
    }

    private CurrentUser loadCreator(ImportRow row) {
        var value = jdbc.queryForMap("SELECT username, is_admin FROM app_user WHERE id = ?", row.createdBy());
        return new CurrentUser(row.createdBy(), String.valueOf(value.get("username")), Boolean.TRUE.equals(value.get("is_admin")));
    }

    private ImportJobView view(ImportRow row) {
        return new ImportJobView(row.id(), row.uuid(), row.state(), row.pageId(), row.revisionId(), row.fileName(), row.audienceMode(), parseWarnings(row.warningsJson()), row.attempts(), row.lastError(), row.createdAt(), row.updatedAt());
    }

    private void requireDb() { if (jdbc == null) throw new IllegalStateException("database is unavailable"); }
    private static String normalizeKey(String key) { String value = key == null ? "" : key.trim(); if (value.isEmpty()) return UUID.randomUUID().toString(); if (value.length() > 120) throw new IllegalArgumentException("idempotency key is too long"); return value; }
    private static String membersJson(List<ResourceAudienceService.Member> members) { if (members == null || members.isEmpty()) return "[]"; return "[" + members.stream().map(member -> "\"" + member.sourceKbId() + ":" + member.userId() + "\"").reduce((a, b) -> a + "," + b).orElse("") + "]"; }
    private static List<ResourceAudienceService.Member> parseMembers(String json) { if (json == null || json.equals("[]")) return List.of(); List<ResourceAudienceService.Member> result = new ArrayList<>(); for (String token : json.replace("[", "").replace("]", "").replace("\"", "").split(",")) { String[] pair = token.trim().split(":"); if (pair.length == 2) result.add(new ResourceAudienceService.Member(Long.parseLong(pair[0]), Long.parseLong(pair[1]))); } return result; }
    private static String json(List<String> values) { if (values == null || values.isEmpty()) return "[]"; return "[" + values.stream().map(value -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"").reduce((a, b) -> a + "," + b).orElse("") + "]"; }
    private static List<String> parseWarnings(String value) { if (value == null || value.equals("[]")) return List.of(); List<String> result = new ArrayList<>(); for (String item : value.replace("[", "").replace("]", "").split(",")) { String clean = item.trim().replace("\\\"", "\"").replace("\"", ""); if (!clean.isBlank()) result.add(clean); } return result; }
    private static Long nullableLong(Object value) { return value == null ? null : ((Number) value).longValue(); }

    private record ImportRow(long id, String uuid, long createdBy, long kbId, Long parentId, Long attachmentId, String idempotencyKey, String fileName, String contentType, String audienceMode, String state, Long pageId, Long revisionId, int attempts, String lastError, String warningsJson, Instant createdAt, Instant updatedAt) {}
    public record ImportJobView(long jobId, String uuid, String state, Long pageId, Long revisionId, String fileName, String audienceMode, List<String> warnings, int attempts, String error, Instant createdAt, Instant updatedAt) {}
}
