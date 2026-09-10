package com.kwiki.wiki.persistence;

import com.kwiki.wiki.domain.ArchiveBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

public interface ArchiveBatchRepository extends JpaRepository<ArchiveBatch, Long> {

    Optional<ArchiveBatch> findByBatchUuid(String batchUuid);

    /** Active (still restorable) batches, newest first, optionally narrowed to one kb. */
    List<ArchiveBatch> findByStateOrderByArchivedAtDesc(String state, Pageable pageable);

    List<ArchiveBatch> findByStateAndKbIdOrderByArchivedAtDesc(String state, long kbId, Pageable pageable);

    /** Batches whose retention window has fully elapsed, in expiry order. */
    List<ArchiveBatch> findByStateAndPurgeAfterBeforeOrderByPurgeAfterAsc(String state,
                                                                          java.time.Instant cutoff,
                                                                          Pageable pageable);

    /** Batches still waiting for a reliable ES deletion (retry task input). */
    List<ArchiveBatch> findByStateAndIndexSyncStatusOrderByArchivedAtAsc(
            String state, String indexSyncStatus, Pageable pageable);

    /** Idempotent re-archive: the most recent non-purged batch covering this root. */
    Optional<ArchiveBatch> findFirstByScopeTypeAndRootResourceIdAndStateNotOrderByArchivedAtDesc(
            String scopeType, long rootResourceId, String excludedState);
}
