package com.kwiki.wiki.api;

import com.kwiki.indexing.job.IndexingJobEnqueuer;
import com.kwiki.security.CurrentUser;
import com.kwiki.wiki.access.KnowledgeBaseAuthorizationService;
import com.kwiki.wiki.access.WikiAction;
import com.kwiki.wiki.attach.AttachmentStorage;
import com.kwiki.wiki.attach.DirectUpload;
import com.kwiki.wiki.attach.StoredAttachment;
import com.kwiki.wiki.domain.Attachment;
import com.kwiki.wiki.persistence.AttachmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DirectUploadServiceTest {
    private final CurrentUser user = new CurrentUser(7L, "u", false);
    private final AttachmentRepository attachments = mock(AttachmentRepository.class);
    private final AttachmentStorage storage = mock(AttachmentStorage.class);
    private final KnowledgeBaseAuthorizationService authorization = mock(KnowledgeBaseAuthorizationService.class);
    private final IndexingJobEnqueuer jobs = mock(IndexingJobEnqueuer.class);
    private final JdbcOperations jdbc = mock(JdbcOperations.class);
    private DirectUploadService service;

    @BeforeEach
    void setup() {
        ObjectProvider<JdbcOperations> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(jdbc);
        var metadata = new AttachmentService(attachments, storage, authorization, jobs,
                50 * 1024 * 1024L, Duration.ofMinutes(5), List.of());
        service = new DirectUploadService(attachments, storage, metadata, authorization, provider);
    }

    @Test
    void initializationSavesOwnerAndProviderLocatorButOnlyReturnsBrowserTicket() {
        when(attachments.saveAndFlush(any())).thenAnswer(inv -> {
            Attachment attachment = inv.getArgument(0);
            ReflectionTestUtils.setField(attachment, "id", 11L);
            return attachment;
        });
        when(storage.initiateUpload("book.pdf", "application/pdf", 100L))
                .thenReturn(new DirectUpload("private/key", "cloud", "https://objects.test/put", 300, 42L));
        var ticket = service.initiate(user, 1, new DirectUploadService.Request("book.pdf", "", 100, "WIKI_IMPORT_SOURCE"));
        assertThat(ticket.headers()).containsEntry("Content-Type", "application/pdf");
        assertThat(ticket.attachmentUuid()).isNotBlank();
        verify(attachments).saveAndFlush(argThat(a -> a.getUploadedBy() == 7 && a.getKbId() == 1
                && a.getContentCenterFileId() == null && !a.isStored() && a.getPurpose().equals("WIKI_IMPORT_SOURCE")));
        verify(jdbc).update(startsWith("INSERT INTO attachment_upload_session"), eq(11L), eq("private/key"), eq("cloud"), any(Timestamp.class), eq(42L));
        verify(storage, never()).store(any());
        verifyNoInteractions(jobs);
    }

    @Test
    void initializationRejectsInvalidMetadataBeforeContactingStorage() {
        assertThatThrownBy(() -> service.initiate(user, 1,
                new DirectUploadService.Request("book.pdf", "application/pdf", 21 * 1024 * 1024, "WIKI_IMPORT_SOURCE")))
                .isInstanceOf(WikiImportValidationException.class);
        assertThatThrownBy(() -> service.initiate(user, 1,
                new DirectUploadService.Request("../x.png", "image/png", 12, "GENERAL")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.initiate(user, 1,
                new DirectUploadService.Request("x.exe", "application/x-msdownload", 12, "GENERAL")))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(storage, attachments);
    }

    @Test
    void unauthorizedInitializationNeverCreatesAnUpload() {
        doThrow(new org.springframework.security.access.AccessDeniedException("denied"))
                .when(authorization).require(user, 1, WikiAction.UPLOAD_ATTACHMENT);
        assertThatThrownBy(() -> service.initiate(user, 1,
                new DirectUploadService.Request("x.png", "image/png", 12, "GENERAL")))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verifyNoInteractions(storage, attachments, jdbc);
    }

    @Test
    void completionRejectsAnotherOwnerOrKnowledgeBaseBeforeProviderCalls() {
        Attachment attachment = attachment();
        when(attachments.findByUuidForUpdate("uuid")).thenReturn(Optional.of(attachment));
        assertThatThrownBy(() -> service.complete(new CurrentUser(8L, "other", true), 1, "uuid"))
                .hasMessageContaining("not found");
        assertThatThrownBy(() -> service.complete(user, 2, "uuid")).hasMessageContaining("not found");
        verifyNoInteractions(storage, jdbc, jobs);
    }

    @Test
    void completionChecksActualMediaAndIsIdempotent() throws Exception {
        Attachment attachment = attachment();
        prepareCompletion(attachment, Instant.now().plusSeconds(300));
        when(storage.completeUpload("key", "cloud", "image/png", 12, null)).thenReturn(new StoredAttachment(42, 12, "image/png"));
        when(storage.readPrefix(42, 12)).thenReturn(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0, 0, 0, 0, 0, 0, 0, 0});
        assertThat(service.complete(user, 1, "uuid").isStored()).isTrue();
        service.complete(user, 1, "uuid");
        verify(storage, times(1)).completeUpload("key", "cloud", "image/png", 12, null);
        verify(storage, never()).readContent(anyLong());
        verify(jobs, times(1)).enqueueAttachmentUpsert(11L);
        verify(jdbc).update("DELETE FROM attachment_upload_session WHERE attachment_id = ?", 11L);
    }

    @Test
    void invalidMediaAndMetadataNeverBecomeStored() throws Exception {
        Attachment attachment = attachment();
        prepareCompletion(attachment, Instant.now().plusSeconds(300));
        when(storage.completeUpload("key", "cloud", "image/png", 12, null)).thenReturn(new StoredAttachment(42, 13, "image/png"));
        assertThatThrownBy(() -> service.complete(user, 1, "uuid")).hasMessageContaining("inconsistent metadata");
        when(storage.completeUpload("key", "cloud", "image/png", 12, null)).thenReturn(new StoredAttachment(42, 12, "image/png"));
        when(storage.readPrefix(42, 12)).thenReturn(new byte[12]);
        assertThatThrownBy(() -> service.complete(user, 1, "uuid")).hasMessageContaining("媒体类型不符");
        assertThat(attachment.isStored()).isFalse();
        verifyNoInteractions(jobs);
        verify(attachments, never()).saveAndFlush(any());
    }

    @Test
    void expiredSessionNeverContactsProvider() throws Exception {
        prepareCompletion(attachment(), Instant.now().minusSeconds(1));
        assertThatThrownBy(() -> service.complete(user, 1, "uuid")).hasMessageContaining("已过期");
        verifyNoInteractions(storage, jobs);
    }

    @Test
    void importSourceIsConfirmedWithoutDownloadingOrIndexingTheDocument() throws Exception {
        Attachment attachment = new Attachment("uuid", 1L, 7L, "x.pdf", "application/pdf", 12);
        attachment.markWikiImportSource();
        ReflectionTestUtils.setField(attachment, "id", 11L);
        prepareCompletion(attachment, Instant.now().plusSeconds(300));
        when(storage.completeUpload("key", "cloud", "application/pdf", 12, null)).thenReturn(new StoredAttachment(42, 12, "application/pdf"));
        service.complete(user, 1, "uuid");
        assertThat(attachment.isStored()).isTrue();
        verify(storage, never()).readContent(anyLong());
        verify(storage, never()).readPrefix(anyLong(), anyInt());
        verifyNoInteractions(jobs);
    }

    private Attachment attachment() {
        var a = new Attachment("uuid", 1L, 7L, "x.png", "image/png", 12);
        ReflectionTestUtils.setField(a, "id", 11L);
        return a;
    }

    private static final String HASH = "a".repeat(64);

    private DirectUploadService.Request fingerprinted() {
        return new DirectUploadService.Request("book.pdf", "application/pdf", 100, "GENERAL", "sha256-prefix-1m-v1", HASH, HASH);
    }

    @SuppressWarnings("unchecked")
    private void stubBlob(String status, Long fileId, String key, String source) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong(1)).thenReturn(5L);
        when(rs.getString(2)).thenReturn(status);
        when(rs.getObject(3, Long.class)).thenReturn(fileId);
        when(rs.getString(4)).thenReturn(key);
        when(rs.getString(5)).thenReturn(source);
        doAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0)))
                .when(jdbc).query(startsWith("SELECT id, status, content_center_file_id"), any(RowMapper.class), any(), any(), anyLong());
    }

    private void stubSave() {
        when(attachments.saveAndFlush(any())).thenAnswer(inv -> {
            Attachment attachment = inv.getArgument(0);
            ReflectionTestUtils.setField(attachment, "id", 11L);
            return attachment;
        });
        when(storage.initiateUpload("book.pdf", "application/pdf", 100L))
                .thenReturn(new DirectUpload("new/key", "minio", "https://objects.test/put", 300, 42L));
    }

    @Test
    void 已存储的去重记录直接复用并带上存储位置() throws Exception {
        stubSave();
        stubBlob("STORED", 99L, "old/key", "oss");
        var ticket = service.initiate(user, 1, fingerprinted());
        assertThat(ticket.state()).isEqualTo("REUSED");
        verify(attachments).saveAndFlush(argThat(a -> a.getContentCenterFileId() == 99L
                && "old/key".equals(a.getStorageKey()) && "oss".equals(a.getStorageSource())));
        verify(storage, never()).initiateUpload(any(), any(), anyLong());
    }

    @Test
    void 已丢失的去重记录被接管并重新上传() throws Exception {
        stubSave();
        stubBlob("MISSING", 99L, "old/key", "oss");
        when(jdbc.update(startsWith("UPDATE attachment_blob SET status = 'PENDING'"), eq(5L))).thenReturn(1);
        var ticket = service.initiate(user, 1, fingerprinted());
        assertThat(ticket.state()).isEqualTo("UPLOAD");
        verify(jdbc).update("UPDATE attachment SET blob_id = ? WHERE id = ?", 5L, 11L);
        verify(storage).initiateUpload("book.pdf", "application/pdf", 100L);
    }

    @Test
    void 没有未过期会话的PENDING记录被接管() throws Exception {
        stubSave();
        stubBlob("PENDING", null, null, null);
        when(jdbc.queryForObject(startsWith("SELECT COUNT(*) FROM attachment_upload_session"), eq(Integer.class), eq(5L))).thenReturn(0);
        assertThat(service.initiate(user, 1, fingerprinted()).state()).isEqualTo("UPLOAD");
    }

    @Test
    void 仍在上传中的PENDING记录保持等待() throws Exception {
        stubSave();
        stubBlob("PENDING", null, null, null);
        when(jdbc.queryForObject(startsWith("SELECT COUNT(*) FROM attachment_upload_session"), eq(Integer.class), eq(5L))).thenReturn(1);
        assertThat(service.initiate(user, 1, fingerprinted()).state()).isEqualTo("PENDING");
        verify(storage, never()).initiateUpload(any(), any(), anyLong());
    }

    @Test
    void 完成直传时记录会话中的真实存储位置() throws Exception {
        Attachment attachment = new Attachment("uuid", 1L, 7L, "book.pdf", "application/pdf", 100);
        ReflectionTestUtils.setField(attachment, "id", 11L);
        prepareCompletion(attachment, Instant.now().plusSeconds(60));
        when(storage.completeUpload("key", "cloud", "application/pdf", 100L, null))
                .thenReturn(new StoredAttachment(55L, 100, "application/pdf"));
        service.complete(user, 1, "uuid");
        assertThat(attachment.getStorageKey()).isEqualTo("key");
        assertThat(attachment.getStorageSource()).isEqualTo("cloud");
    }

    @SuppressWarnings("unchecked")
    private void prepareCompletion(Attachment attachment, Instant expiry) throws Exception {
        when(attachments.findByUuidForUpdate("uuid")).thenReturn(Optional.of(attachment));
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(1)).thenReturn("key");
        when(rs.getString(2)).thenReturn("cloud");
        when(rs.getTimestamp(3)).thenReturn(Timestamp.from(expiry));
        doAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0)))
                .when(jdbc).query(startsWith("SELECT storage_key"), any(RowMapper.class), eq(11L));
    }
}
