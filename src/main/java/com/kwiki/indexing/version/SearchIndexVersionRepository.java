package com.kwiki.indexing.version;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

public interface SearchIndexVersionRepository extends JpaRepository<SearchIndexVersion, Long> {

    Optional<SearchIndexVersion> findByVersionNumber(int versionNumber);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from SearchIndexVersion v where v.versionNumber = :version")
    Optional<SearchIndexVersion> findByVersionNumberForUpdate(@Param("version") int version);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from SearchIndexVersion v where v.deletedAt is null order by v.versionNumber")
    List<SearchIndexVersion> findAllActiveForUpdate();

    /** tombstone 行保留，因此 max 覆盖包括已删除在内的全部历史版本号。 */
    @Query("select max(v.versionNumber) from SearchIndexVersion v")
    Integer findMaxVersionNumber();

    List<SearchIndexVersion> findByDeletedAtIsNullOrderByVersionNumberAsc();

    List<SearchIndexVersion> findByWriteEnabledTrueAndDeletedAtIsNull();

    Optional<SearchIndexVersion> findBySelectedTrue();

    Optional<SearchIndexVersion> findByPhysicalNameAndDeletedAtIsNull(String physicalName);

    boolean existsByDeletedAtIsNull();
}
