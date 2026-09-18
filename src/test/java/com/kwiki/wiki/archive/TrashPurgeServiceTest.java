package com.kwiki.wiki.archive;

import com.kwiki.security.CurrentUser;
import com.kwiki.wiki.access.KnowledgeBaseAuthorizationService;
import com.kwiki.wiki.domain.ArchiveBatch;
import com.kwiki.wiki.persistence.ArchiveBatchRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrashPurgeServiceTest {

    @Test
    void checksTheArchiveScopeBeforeDelegatingPermanentPurge() {
        ArchiveBatchRepository batches = mock(ArchiveBatchRepository.class);
        KnowledgeBaseAuthorizationService authorization = mock(KnowledgeBaseAuthorizationService.class);
        RecycleBinCleanupService cleanup = mock(RecycleBinCleanupService.class);
        ArchiveBatch batch = new ArchiveBatch("batch-42", ArchiveBatch.SCOPE_PAGE, 7L, 5L,
                9L, Instant.parse("2026-09-14T00:00:00Z"),
                Instant.parse("2026-09-21T00:00:00Z"), 1, ArchiveBatch.ORIGIN_NORMAL);
        org.springframework.test.util.ReflectionTestUtils.setField(batch, "id", 42L);
        when(batches.findById(42L)).thenReturn(Optional.of(batch));
        when(cleanup.purgeNow(42L)).thenReturn(new RecycleBinCleanupService.PurgeResult(42L, 1));

        TrashPurgeService service = new TrashPurgeService(batches, authorization, cleanup);
        var result = service.purge(new CurrentUser(9L, "owner", false), 42L);

        verify(authorization).require(eq(new CurrentUser(9L, "owner", false)), eq(5L),
                eq(com.kwiki.wiki.access.WikiAction.ARCHIVE_PAGE));
        verify(cleanup).purgeNow(42L);
        assertThat(result.batchId()).isEqualTo(42L);
        assertThat(result.purgedItems()).isEqualTo(1);
        assertThat(result.message()).contains("不可恢复");
    }
}
