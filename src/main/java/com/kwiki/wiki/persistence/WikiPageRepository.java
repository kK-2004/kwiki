package com.kwiki.wiki.persistence;

import com.kwiki.wiki.domain.WikiPage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 默认查询永不返回已归档页面：导航作用域由调用方的授权作用域派生，
 * 并与 ACTIVE 状态过滤相结合。
 */
public interface WikiPageRepository extends JpaRepository<WikiPage, Long> {

    Optional<WikiPage> findByUuid(String uuid);

    /** 不论生命周期状态的所有节点；用于子树快照与恢复。 */
    List<WikiPage> findByKbId(Long kbId);

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
