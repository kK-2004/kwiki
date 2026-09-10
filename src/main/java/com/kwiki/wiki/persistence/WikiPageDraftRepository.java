package com.kwiki.wiki.persistence;

import com.kwiki.wiki.domain.WikiPageDraft;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WikiPageDraftRepository extends JpaRepository<WikiPageDraft, Long> {
}
