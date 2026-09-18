package com.kwiki.rag.answer;

import com.kwiki.wiki.persistence.AttachmentRepository;
import com.kwiki.wiki.persistence.KnowledgeBaseRepository;
import com.kwiki.wiki.persistence.WikiPageRepository;
import org.springframework.stereotype.Component;

/** Keeps mutable resource names out of model output and resolves them at response time. */
@Component
public class CitationDisplayNameResolver {
    private final WikiPageRepository pages;
    private final KnowledgeBaseRepository knowledgeBases;
    private final AttachmentRepository attachments;

    public CitationDisplayNameResolver(WikiPageRepository pages,
                                       KnowledgeBaseRepository knowledgeBases,
                                       AttachmentRepository attachments) {
        this.pages = pages;
        this.knowledgeBases = knowledgeBases;
        this.attachments = attachments;
    }

    public String resolve(String resourceType, long resourceId, String fallback) {
        String name = switch (resourceType == null ? "" : resourceType) {
            case "PAGE" -> pages.findById(resourceId).map(page -> page.getTitle()).orElse(null);
            case "KNOWLEDGE_BASE" -> knowledgeBases.findById(resourceId).map(kb -> kb.getName()).orElse(null);
            case "ATTACHMENT" -> attachments.findById(resourceId).map(file -> file.getFileName()).orElse(null);
            default -> null;
        };
        return name == null || name.isBlank() ? fallback : name;
    }
}
