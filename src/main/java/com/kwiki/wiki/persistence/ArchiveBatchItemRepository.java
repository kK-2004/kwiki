package com.kwiki.wiki.persistence;

import com.kwiki.wiki.domain.ArchiveBatchItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ArchiveBatchItemRepository extends JpaRepository<ArchiveBatchItem, Long> {

    List<ArchiveBatchItem> findByBatchIdOrderByIdAsc(long batchId);

    Optional<ArchiveBatchItem> findByBatchIdAndResourceTypeAndResourceId(
            long batchId, String resourceType, long resourceId);

    /** 对资源的任一有效引用（未清除批次），用于幂等。 */
    List<ArchiveBatchItem> findByResourceTypeAndResourceId(String resourceType, long resourceId);

    long countByBatchIdAndPurgedFalse(long batchId);
}
