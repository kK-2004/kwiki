package com.kwiki.wiki.persistence;

import com.kwiki.wiki.domain.WikiPageTag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface WikiPageTagRepository extends JpaRepository<WikiPageTag, WikiPageTag.PageTagKey> {

    List<WikiPageTag> findByPageId(Long pageId);

    @Modifying
    @Query("delete from WikiPageTag t where t.pageId = :pageId")
    void deleteAllByPageId(@Param("pageId") Long pageId);
}
