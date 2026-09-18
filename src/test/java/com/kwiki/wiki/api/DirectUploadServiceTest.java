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
