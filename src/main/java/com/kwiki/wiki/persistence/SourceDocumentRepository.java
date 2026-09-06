package com.kwiki.wiki.persistence;

import com.kwiki.wiki.domain.SourceDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SourceDocumentRepository extends JpaRepository<SourceDocument, Long> {

    List<SourceDocument> findByPageId(Long pageId);
}
