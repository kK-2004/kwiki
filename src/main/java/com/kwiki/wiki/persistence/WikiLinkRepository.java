package com.kwiki.wiki.persistence;

import com.kwiki.wiki.domain.WikiLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface WikiLinkRepository extends JpaRepository<WikiLink, Long> {

    List<WikiLink> findBySourcePageId(Long sourcePageId);

    List<WikiLink> findByTargetPageId(Long targetPageId);

    boolean existsBySourcePageIdAndTargetPageId(Long sourcePageId, Long targetPageId);

    @Modifying
    @Query("delete from WikiLink l where l.sourcePageId = :pageId")
    void deleteAllBySourcePageId(@Param("pageId") Long pageId);
}
