package com.kwiki.wiki.persistence;

import com.kwiki.wiki.domain.KnowledgeBaseMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface KnowledgeBaseMemberRepository extends JpaRepository<KnowledgeBaseMember, Long> {

    Optional<KnowledgeBaseMember> findByKbIdAndUserId(Long kbId, Long userId);

    List<KnowledgeBaseMember> findByKbIdOrderByIdAsc(Long kbId);

    List<KnowledgeBaseMember> findByUserId(Long userId);

    List<KnowledgeBaseMember> findByUserIdAndKbIdIn(Long userId, List<Long> kbIds);

    void deleteByKbIdAndUserId(Long kbId, Long userId);
}
