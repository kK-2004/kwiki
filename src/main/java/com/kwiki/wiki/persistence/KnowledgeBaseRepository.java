package com.kwiki.wiki.persistence;

import com.kwiki.wiki.domain.KnowledgeBase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBase, Long> {

    Optional<KnowledgeBase> findByUuid(String uuid);

    Optional<KnowledgeBase> findByIdAndStatus(Long id, String status);

    List<KnowledgeBase> findByIdInAndStatusOrderByNameAsc(Collection<Long> ids, String status);

    List<KnowledgeBase> findByStatusOrderByNameAsc(String status);
}
