package com.kwiki.wiki.persistence;

import com.kwiki.wiki.domain.WikiPageRevision;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WikiPageRevisionRepository extends JpaRepository<WikiPageRevision, Long> {

    Optional<WikiPageRevision> findByPageIdAndRevisionNo(Long pageId, int revisionNo);

    List<WikiPageRevision> findByPageIdOrderByRevisionNoDesc(Long pageId);

    List<WikiPageRevision> findByPageIdAndPublishedAtIsNotNullOrderByRevisionNoDesc(Long pageId);

    Optional<WikiPageRevision> findFirstByPageIdOrderByRevisionNoDesc(Long pageId);
}
