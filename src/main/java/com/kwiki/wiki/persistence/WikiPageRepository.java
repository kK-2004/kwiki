package com.kwiki.wiki.persistence;

import com.kwiki.wiki.domain.WikiPage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Default queries never return archived pages: navigation scopes are derived from
 * the caller's authorization scope and combined with ACTIVE status filtering.
 */
public interface WikiPageRepository extends JpaRepository<WikiPage, Long> {

    Optional<WikiPage> findByUuid(String uuid);

    Optional<WikiPage> findByIdAndStatus(Long id, String status);

    List<WikiPage> findByKbIdAndStatusOrderByParentIdAscSiblingOrderAsc(Long kbId, String status);

    List<WikiPage> findByKbIdInAndStatusOrderByKbIdAscParentIdAscSiblingOrderAsc(
            Collection<Long> kbIds, String status);

    List<WikiPage> findByKbIdAndParentIdAndStatusOrderBySiblingOrderAsc(
            Long kbId, Long parentId, String status);

    List<WikiPage> findByParentIdAndStatus(Long parentId, String status);

    long countByKbIdAndStatusAndTitleContainingIgnoreCase(
            Long kbId, String status, String titleFragment);
}
