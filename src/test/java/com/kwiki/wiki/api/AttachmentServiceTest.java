package com.kwiki.wiki.api;

import com.kk2004.common.exception.NotFoundException;

import com.kwiki.indexing.job.IndexingJobEnqueuer;
import com.kwiki.security.CurrentUser;
import com.kwiki.wiki.access.KnowledgeBaseAuthorizationService;
import com.kwiki.wiki.access.MembershipLookup;
import com.kwiki.wiki.attach.AttachmentStorage;
import com.kwiki.wiki.attach.AttachmentUpload;
import com.kwiki.wiki.attach.StoredAttachment;
import com.kwiki.wiki.domain.Attachment;
import com.kwiki.wiki.domain.KnowledgeBaseMember;
import com.kwiki.wiki.access.KnowledgeBaseRole;
import com.kwiki.wiki.persistence.AttachmentRepository;
import com.kwiki.wiki.persistence.KnowledgeBaseMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Upload/download/archive flows over the file-ID storage port without contacting
 * the content center: traversal rejection, type/size allowlists, role checks,
 * PENDING-until-validated sequencing, fail-closed missing-file-ID downloads, and
 * archive that never attempts physical content deletion.
 */
@ExtendWith(MockitoExtension.class)
class AttachmentServiceTest {

    private static final long KB = 1L;
    private static final CurrentUser EDITOR = new CurrentUser(5L, "editor", false);
    private static final CurrentUser VIEWER = new CurrentUser(6L, "viewer", false);

    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    @Mock
    AttachmentRepository attachments;

    @Mock
    AttachmentStorage storage;

    @Mock
    KnowledgeBaseMemberRepository members;

    @Mock
    IndexingJobEnqueuer indexingJobs;

    AttachmentService service;

    @BeforeEach
    void setUp() {
        lenient().when(attachments.save(any(Attachment.class)))
                .thenAnswer(inv -> {
                    Attachment attachment = inv.getArgument(0);
                    if (attachment.getId() == null) {
                        org.springframework.test.util.ReflectionTestUtils.setField(attachment, "id", 1L);
                    }
                    return attachment;
                });
        // Default successful store: echoes the upload's own size/type so the
        // service's verified-metadata check passes; individual tests override.
        lenient().when(storage.store(any())).thenAnswer(inv -> {
            AttachmentUpload upload = inv.getArgument(0);
            return new StoredAttachment(42L, upload.byteSize(), upload.contentType());
        });
        KnowledgeBaseAuthorizationService auth = new KnowledgeBaseAuthorizationService(
                providerOf(new JpaTestLookup()));
        service = new AttachmentService(attachments, storage, auth, indexingJobs,
                1024 * 1024L, Duration.ofSeconds(300), List.of());
    }

    private static ObjectProvider<MembershipLookup> providerOf(MembershipLookup lookup) {
        return new ObjectProvider<>() {
            @Override
            public MembershipLookup getIfAvailable() {
                return lookup;
            }
        };
    }

    /** Role resolution wired through the mocked member repository. */
    private class JpaTestLookup implements MembershipLookup {
        @Override
        public Optional<KnowledgeBaseRole> findRole(long kbId, long userId) {
            if (userId == VIEWER.id()) {
                return members.findByKbIdAndUserId(kbId, userId)
                        .map(KnowledgeBaseMember::getRole);
            }
            return Optional.of(KnowledgeBaseRole.EDITOR);
        }
    }

    private InputStream content(int size) {
        return new ByteArrayInputStream(new byte[size]);
    }

    private static StoredAttachment storedOk(long fileId, long size) {
        return new StoredAttachment(fileId, size, DOCX);
    }

    private static final byte[] PNG_HEADER = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D};

    private InputStream pngContent() {
        return new ByteArrayInputStream(PNG_HEADER);
    }

    @Test
    void validImageUploadPersistsFileIdMarksStoredAndEnqueuesIndexing() {
        Attachment stored = service.upload(EDITOR, KB, "arch.png", "image/png",
                PNG_HEADER.length, pngContent());

        ArgumentCaptor<AttachmentUpload> upload = ArgumentCaptor.forClass(AttachmentUpload.class);
        verify(storage).store(upload.capture());
        assertThat(upload.getValue().fileName()).isEqualTo("arch.png");
        assertThat(upload.getValue().contentType()).isEqualTo("image/png");
        assertThat(upload.getValue().byteSize()).isEqualTo((long) PNG_HEADER.length);

        assertThat(stored.getStatus()).isEqualTo(Attachment.STATUS_STORED);
        assertThat(stored.getContentCenterFileId()).isEqualTo(42L);
        // image indexing strictly after validated success
        verify(indexingJobs).enqueueAttachmentUpsert(stored.getId());
    }

    @Test
    void nonImageUploadStaysDisplayOnlyWithoutIndexing() {
        Attachment stored = service.upload(EDITOR, KB, "spec.docx", DOCX, 100, content(100));

        assertThat(stored.getStatus()).isEqualTo(Attachment.STATUS_STORED);
        verify(indexingJobs, never()).enqueueAttachmentUpsert(anyLong());
    }

    @Test
    void mediaUploadWithMismatchedMagicBytesIsRejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.upload(EDITOR, KB, "fake.png", "image/png", 100, content(100)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("媒体类型不符");
        verify(storage, never()).store(any());
    }

    @Test
    void uploadPersistsPendingWithoutFileIdThenStoresWithReturnedFileId() {
        // The entity is mutated between saves, so snapshot status/file id at save time.
        java.util.List<String> statusesAtSave = new java.util.ArrayList<>();
        java.util.List<Long> fileIdsAtSave = new java.util.ArrayList<>();
        org.mockito.Mockito.doAnswer(inv -> {
            Attachment attachment = inv.getArgument(0);
            statusesAtSave.add(attachment.getStatus());
            fileIdsAtSave.add(attachment.getContentCenterFileId());
            if (attachment.getId() == null) {
                org.springframework.test.util.ReflectionTestUtils.setField(attachment, "id", 1L);
            }
            return attachment;
        }).when(attachments).save(any(Attachment.class));

        Attachment stored = service.upload(EDITOR, KB, "spec.docx", DOCX, 10, content(10));

        assertThat(statusesAtSave).containsExactly(Attachment.STATUS_PENDING, Attachment.STATUS_STORED);
        assertThat(fileIdsAtSave).containsExactly((Long) null, 42L);
        assertThat(stored.getContentCenterFileId()).isEqualTo(42L);
    }

    @Test
    void pathTraversalFileNamesAreRejected() {
        assertThatThrownBy(() ->
                service.upload(EDITOR, KB, "../../etc/passwd", DOCX, 100, content(100)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                service.upload(EDITOR, KB, "a/../../b.docx", DOCX, 100, content(100)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(storage, never()).store(any());
    }

    @Test
    void oversizedFilesAreRejectedBeforeStorage() {
        assertThatThrownBy(() ->
                service.upload(EDITOR, KB, "big.docx", DOCX, 1024 * 1024 + 1, content(10)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(storage, never()).store(any());
    }

    @Test
    void disallowedContentTypesAreRejected() {
        assertThatThrownBy(() ->
                service.upload(EDITOR, KB, "malware.exe", "application/x-msdownload", 10, content(10)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(storage, never()).store(any());
    }

    @Test
    void viewerCannotUpload() {
        when(members.findByKbIdAndUserId(KB, VIEWER.id())).thenReturn(Optional.of(
                new KnowledgeBaseMember(KB, VIEWER.id(), KnowledgeBaseRole.VIEWER, EDITOR.id())));

        assertThatThrownBy(() ->
                service.upload(VIEWER, KB, "doc.docx", DOCX, 10, content(10)))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verify(storage, never()).store(any());
    }

    @Test
    void storageFailureLeavesPendingRowWithoutFileIdAndNeverEnqueuesIndexing() {
        org.mockito.Mockito.doThrow(
                        new com.kwiki.wiki.attach.AttachmentStorageException(
                                com.kwiki.wiki.attach.AttachmentStorageException.Category.TRANSIENT,
                                "content-center upload failed (HTTP 500)"))
                .when(storage).store(any());

        assertThatThrownBy(() ->
                service.upload(EDITOR, KB, "spec.docx", DOCX, 10, content(10)))
                .isInstanceOf(IllegalStateException.class);

        ArgumentCaptor<Attachment> saved = ArgumentCaptor.forClass(Attachment.class);
        verify(attachments, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        assertThat(saved.getAllValues())
                .allSatisfy(attachment ->
                        assertThat(attachment.getStatus())
                                .as("no STORED status when the upload failed")
                                .isNotEqualTo(Attachment.STATUS_STORED));
        // reconciliation trail: the committed PENDING row has no file id
        assertThat(saved.getAllValues())
                .anySatisfy(attachment -> {
                    assertThat(attachment.getStatus()).isEqualTo(Attachment.STATUS_PENDING);
                    assertThat(attachment.getContentCenterFileId()).isNull();
                });
        verify(indexingJobs, never()).enqueueAttachmentUpsert(anyLong());
    }

    @Test
    void inconsistentVerifiedSizeIsRejectedAndNeverStored() {
        org.mockito.Mockito.doReturn(storedOk(42L, 999)).when(storage).store(any());

        assertThatThrownBy(() ->
                service.upload(EDITOR, KB, "spec.docx", DOCX, 10, content(10)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inconsistent");

        ArgumentCaptor<Attachment> saved = ArgumentCaptor.forClass(Attachment.class);
        verify(attachments, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        assertThat(saved.getAllValues()).allSatisfy(attachment ->
                assertThat(attachment.getStatus()).isNotEqualTo(Attachment.STATUS_STORED));
        verify(indexingJobs, never()).enqueueAttachmentUpsert(anyLong());
    }

    @Test
    void databaseFailureAfterUploadNeverEnqueuesIndexing() {
        org.mockito.Mockito.doReturn(storedOk(42L, 10)).when(storage).store(any());
        // PENDING persist succeeds; the STORED transition write fails.
        java.util.concurrent.atomic.AtomicInteger saves = new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.doAnswer(inv -> {
            if (saves.incrementAndGet() >= 2) {
                throw new RuntimeException("db write failed");
            }
            Attachment attachment = inv.getArgument(0);
            org.springframework.test.util.ReflectionTestUtils.setField(attachment, "id", 1L);
            return attachment;
        }).when(attachments).save(any(Attachment.class));

        assertThatThrownBy(() ->
                service.upload(EDITOR, KB, "spec.docx", DOCX, 10, content(10)))
                .isInstanceOf(RuntimeException.class);
        verify(indexingJobs, never()).enqueueAttachmentUpsert(anyLong());
        verify(attachments, org.mockito.Mockito.times(2)).save(any(Attachment.class));
    }

    @Test
    void downloadIssuesShortLivedLinkByFileIdOnlyForStoredAttachments() {
        Attachment stored = new Attachment("att-uuid", KB, EDITOR.id(), "spec.docx", DOCX, 10);
        stored.markStored(777L);
        when(attachments.findByUuid("att-uuid")).thenReturn(Optional.of(stored));
        when(storage.downloadLink(eq(777L), eq("spec.docx"), any(Duration.class)))
                .thenReturn("https://content-center.internal/signed?sig=abc");

        String url = service.downloadUrl(EDITOR, KB, "att-uuid");

        assertThat(url).contains("sig=");
        verify(storage).downloadLink(eq(777L), eq("spec.docx"), eq(Duration.ofSeconds(300)));
    }

    @Test
    void storedAttachmentWithoutFileIdFailsClosedOnDownload() {
        Attachment stored = new Attachment("att-noid", KB, EDITOR.id(), "spec.docx", DOCX, 10);
        stored.markStored(1L);
        org.springframework.test.util.ReflectionTestUtils.setField(stored, "contentCenterFileId", null);
        when(attachments.findByUuid("att-noid")).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> service.downloadUrl(EDITOR, KB, "att-noid"))
                .isInstanceOf(NotFoundException.class);
        verify(storage, never()).downloadLink(anyLong(), anyString(), any());
    }

    @Test
    void pendingAttachmentHasNoDownloadUrl() {
        Attachment pending = new Attachment("att-pending", KB, EDITOR.id(), "spec.docx", DOCX, 10);
        when(attachments.findByUuid("att-pending")).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> service.downloadUrl(EDITOR, KB, "att-pending"))
                .isInstanceOf(NotFoundException.class);
        verify(storage, never()).downloadLink(anyLong(), anyString(), any());
    }

    @Test
    void attachmentOfAnotherKnowledgeBaseIsNotFound() {
        Attachment foreign = new Attachment("att-foreign", 999L, EDITOR.id(), "x.docx", DOCX, 10);
        foreign.markStored(888L);
        when(attachments.findByUuid("att-foreign")).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.downloadUrl(EDITOR, KB, "att-foreign"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void archiveMarksMetadataAndEnqueuesDeIndexWithoutPhysicalDeletion() {
        Attachment stored = new Attachment("att-arch", KB, EDITOR.id(), "spec.docx", DOCX, 10);
        stored.markStored(42L);
        when(attachments.findByUuid("att-arch")).thenReturn(Optional.of(stored));

        service.archive(EDITOR, KB, "att-arch");

        ArgumentCaptor<Attachment> saved = ArgumentCaptor.forClass(Attachment.class);
        verify(attachments, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        assertThat(saved.getAllValues()).anySatisfy(attachment -> {
            assertThat(attachment.getStatus()).isEqualTo(Attachment.STATUS_ARCHIVED);
            assertThat(attachment.getContentCenterFileId()).isEqualTo(42L);
        });
        verify(indexingJobs).enqueueAttachmentDelete(1L);
        // The port has no delete operation; nothing beyond archive/de-index may be attempted.
        verify(storage, never()).store(any());
        verify(storage, never()).downloadLink(anyLong(), anyString(), any());
    }

    @Test
    void readContentReturnsStoredBytesForKbMembers() {
        Attachment stored = new Attachment("att-content", KB, EDITOR.id(), "arch.png", "image/png", 12);
        stored.markStored(777L);
        when(attachments.findByUuid("att-content")).thenReturn(Optional.of(stored));
        when(storage.readContent(777L)).thenReturn(PNG_HEADER);

        AttachmentService.AttachmentContent content = service.readContent(EDITOR, KB, "att-content");

        assertThat(content.bytes()).isEqualTo(PNG_HEADER);
        assertThat(content.fileName()).isEqualTo("arch.png");
        assertThat(content.contentType()).isEqualTo("image/png");
    }

    @Test
    void readContentFailsClosedWithoutFileId() {
        Attachment stored = new Attachment("att-noid-content", KB, EDITOR.id(), "x.png", "image/png", 10);
        stored.markStored(1L);
        org.springframework.test.util.ReflectionTestUtils.setField(stored, "contentCenterFileId", null);
        when(attachments.findByUuid("att-noid-content")).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> service.readContent(EDITOR, KB, "att-noid-content"))
                .isInstanceOf(NotFoundException.class);
        verify(storage, never()).readContent(anyLong());
    }
}
