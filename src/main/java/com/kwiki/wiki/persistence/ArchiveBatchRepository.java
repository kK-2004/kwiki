package com.kwiki.wiki.persistence;

import com.kwiki.wiki.domain.ArchiveBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

public interface ArchiveBatchRepository extends JpaRepository<ArchiveBatch, Long> {

    Optional<ArchiveBatch> findByBatchUuid(String batchUuid);

    /** 有效（仍可恢复）的批次，按最新在前排序，可选择性收窄到单个 kb。 */
    List<ArchiveBatch> findByStateOrderByArchivedAtDesc(String state, Pageable pageable);

    List<ArchiveBatch> findByStateAndKbIdOrderByArchivedAtDesc(String state, long kbId, Pageable pageable);

    /** 保留窗口已完全届满的批次，按到期顺序排列。 */
    List<ArchiveBatch> findByStateAndPurgeAfterBeforeOrderByPurgeAfterAsc(String state,
                                                                          java.time.Instant cutoff,
                                                                          Pageable pageable);

    /** 仍在等待可靠 ES 删除的批次（重试任务的输入）。 */
    List<ArchiveBatch> findByStateAndIndexSyncStatusOrderByArchivedAtAsc(
            String state, String indexSyncStatus, Pageable pageable);

    /** 幂等重归档：覆盖该根的最新、未清除的批次。 */
    Optional<ArchiveBatch> findFirstByScopeTypeAndRootResourceIdAndStateNotOrderByArchivedAtDesc(
            String scopeType, long rootResourceId, String excludedState);
}
