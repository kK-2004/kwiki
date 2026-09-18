package com.kwiki.wiki.api;

import com.kwiki.security.CurrentUser;
import com.kwiki.wiki.access.KnowledgeBaseAuthorizationService;
import com.kwiki.wiki.access.WikiAction;
import com.kwiki.wiki.attach.AttachmentStorage;
import com.kwiki.wiki.domain.Attachment;
import com.kwiki.wiki.persistence.AttachmentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DirectImportJobTest {
    private final CurrentUser user = new CurrentUser(7L, "u", false);
    private final AttachmentRepository attachments = mock(AttachmentRepository.class);
    private final AttachmentStorage storage = mock(AttachmentStorage.class);
    private final WikiImportService importer = mock(WikiImportService.class);
    private final JdbcOperations jdbc = mock(JdbcOperations.class);
    private final KnowledgeBaseAuthorizationService authorization = mock(KnowledgeBaseAuthorizationService.class);

    private WikiImportJobService service() {
        ObjectProvider<JdbcOperations> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(jdbc);
        return new WikiImportJobService(provider, attachments, storage, authorization, importer);
    }

    @Test
    @SuppressWarnings("unchecked")
    void completedSourceQueuesDurablyWithoutDownloadingOrParsingInRequest() throws Exception {
        Attachment attachment = source(7, 1, true);
        when(attachments.findByUuidForUpdate("upload")).thenReturn(Optional.of(attachment));
        when(jdbc.queryForObject(eq("SELECT id FROM wiki_import_job WHERE uuid = ?"), eq(Long.class), anyString())).thenReturn(31L);
        ResultSet row = mock(ResultSet.class);
        when(row.getLong(1)).thenReturn(31L);
        when(row.getLong(3)).thenReturn(7L);
        when(row.getString(11)).thenReturn("STORED");
        when(row.getTimestamp(17)).thenReturn(Timestamp.from(Instant.now()));
        when(row.getTimestamp(18)).thenReturn(Timestamp.from(Instant.now()));
        doAnswer(inv -> ((RowMapper<?>) inv.getArgument(1)).mapRow(row, 0))
                .when(jdbc).queryForObject(startsWith("SELECT id, uuid,"), any(RowMapper.class), eq(31L));
        var result = service().submitStored(user, 1, null, "upload", "PRIVATE", List.of(), "key");
        assertThat(result.jobId()).isEqualTo(31);
        assertThat(result.state()).isEqualTo("STORED");
        verify(jdbc).update(startsWith("INSERT INTO wiki_import_job"), anyString(), eq(7L), eq(1L), isNull(),
                eq(11L), eq("key"), eq("book.pdf"), eq("application/pdf"), eq("PRIVATE"), eq("[]"), eq("[]"));
        verifyNoInteractions(storage, importer);
    }

    @Test
    void foreignPendingAndGeneralAttachmentsCannotBeSubmittedForImport() {
        var service = service();
        Attachment general = source(7, 1, true);
        ReflectionTestUtils.setField(general, "purpose", Attachment.PURPOSE_GENERAL);
        for (Attachment attachment : List.of(source(8, 1, true), source(7, 2, true), source(7, 1, false), general)) {
            when(attachments.findByUuidForUpdate("upload")).thenReturn(Optional.of(attachment));
            assertThatThrownBy(() -> service.submitStored(user, 1, null, "upload", "PRIVATE", List.of(), "key"))
                    .hasMessageContaining("completed import attachment not found");
        }
        verifyNoInteractions(jdbc, storage, importer);
    }

    @Test
    @SuppressWarnings("unchecked")
    void listIsScopedToTheCurrentUserAndKnowledgeBase() {
        when(jdbc.query(contains("created_by = ? AND kb_id = ?"), any(RowMapper.class), eq(7L), eq(1L)))
                .thenReturn(List.of());

        assertThat(service().list(user, 1, 500)).isEmpty();

        verify(authorization).require(user, 1, WikiAction.READ_PAGE);
        verify(jdbc).query(contains("created_by = ? AND kb_id = ?"), any(RowMapper.class), eq(7L), eq(1L));
    }

    private Attachment source(long owner, long kb, boolean stored) {
        var a = new Attachment("upload", kb, owner, "book.pdf", "application/pdf", 100);
        ReflectionTestUtils.setField(a, "id", 11L);
        a.markWikiImportSource();
        if (stored) a.markStored(42);
        return a;
    }
}
