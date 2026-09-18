package com.kwiki.indexing.version;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.List;

public interface SearchIndexRebuildRangeRepository
        extends JpaRepository<SearchIndexRebuildRange, Long> {
    List<SearchIndexRebuildRange> findByRunIdOrderByResourceType(long runId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from SearchIndexRebuildRange r where r.id = :id")
    java.util.Optional<SearchIndexRebuildRange> findByIdForUpdate(@Param("id") long id);
}
