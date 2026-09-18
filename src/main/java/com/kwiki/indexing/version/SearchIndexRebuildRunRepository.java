package com.kwiki.indexing.version;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SearchIndexRebuildRunRepository
        extends JpaRepository<SearchIndexRebuildRun, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<SearchIndexRebuildRun> findFirstByVersionNumberAndStateInOrderByIdDesc(
            int versionNumber, Collection<String> states);

    @Query("select coalesce(max(r.buildGeneration), 0) from SearchIndexRebuildRun r"
            + " where r.versionNumber = :version")
    long findMaxBuildGeneration(@Param("version") int version);

    Optional<SearchIndexRebuildRun> findByIdAndLeaseOwner(long id, String leaseOwner);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from SearchIndexRebuildRun r where r.id = :id")
    Optional<SearchIndexRebuildRun> findByIdForUpdate(@Param("id") long id);

    List<SearchIndexRebuildRun>
    findByStateAndSwitchStateOrderByIdAsc(String state, String switchState);

    boolean existsByVersionNumberAndStateIn(int versionNumber, Collection<String> states);

    boolean existsByVersionNumberAndSwitchState(int versionNumber, String switchState);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<SearchIndexRebuildRun>
    findFirstByVersionNumberAndConfigRevisionAndStateOrderByIdDesc(
            int versionNumber, long configRevision, String state);
}
