package com.kwiki.indexing.job;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface IndexingJobRepository extends JpaRepository<IndexingJob, Long> {

    Optional<IndexingJob> findByIdempotencyKey(String idempotencyKey);

    List<IndexingJob> findByStateInOrderByIdAsc(Collection<IndexingJobState> states);

    long countByStateIn(Collection<IndexingJobState> states);
}
