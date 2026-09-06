package com.kwiki.wiki.persistence;

import com.kwiki.wiki.domain.WikiTag;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WikiTagRepository extends JpaRepository<WikiTag, Long> {

    Optional<WikiTag> findByKbIdAndNameIgnoreCase(Long kbId, String name);
}
