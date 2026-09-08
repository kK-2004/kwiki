package com.kwiki.wiki.api;

import com.kk2004.common.exception.NotFoundException;

import com.kwiki.security.CurrentUser;
import com.kwiki.wiki.access.KnowledgeBaseAuthorizationService;
import com.kwiki.wiki.access.ResourceAction;
import com.kwiki.wiki.access.ResourceAuthorizationService;
import com.kwiki.wiki.access.WikiAction;
import com.kwiki.wiki.domain.Attachment;
import com.kwiki.wiki.domain.WikiPage;
import com.kwiki.wiki.persistence.AttachmentRepository;
import com.kwiki.wiki.persistence.SourceDocumentRepository;
import com.kwiki.wiki.persistence.WikiPageRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Reader-facing provenance: which readable source documents a page was derived
 * from. Attachments outside the caller's knowledge base or archived attachments
 * are not disclosed — not even their metadata.
 */
@Service
public class PageProvenanceService {

    private final SourceDocumentRepository sources;
    private final AttachmentRepository attachments;
    private final WikiPageRepository pages;
    private final KnowledgeBaseAuthorizationService authorization;
    private final ResourceAuthorizationService resources;

    public PageProvenanceService(SourceDocumentRepository sources,
                                 AttachmentRepository attachments,
                                 WikiPageRepository pages,
                                 KnowledgeBaseAuthorizationService authorization) {
        this(sources, attachments, pages, authorization, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PageProvenanceService(SourceDocumentRepository sources,
                                 AttachmentRepository attachments,
                                 WikiPageRepository pages,
                                 KnowledgeBaseAuthorizationService authorization,
                                 ResourceAuthorizationService resources) {
        this.sources = sources;
        this.attachments = attachments;
        this.pages = pages;
        this.authorization = authorization;
        this.resources = resources;
    }

    public List<SourceView> readableSources(CurrentUser user, long kbId, long pageId) {
        requirePage(user, kbId, pageId, ResourceAction.READ, WikiAction.READ_PAGE);
        requireActivePageIn(kbId, pageId);
        return sources.findByPageId(pageId).stream()
                .map(source -> attachments.findById(source.getAttachmentId())
                        .filter(attachment -> attachment.getKbId() == kbId)
                        .filter(attachment -> !com.kwiki.wiki.domain.Attachment.STATUS_ARCHIVED
                                .equals(attachment.getStatus()))
                        .map(attachment -> new SourceView(
                                attachment.getUuid(), attachment.getFileName(),
                                attachment.getContentType(), attachment.getByteSize(),
                                source.getRelationship())))
                .flatMap(java.util.Optional::stream)
                .toList();
    }

    /** Attachments readable for the page (metadata only; downloads are presigned separately). */
    public List<SourceView> readableAttachments(CurrentUser user, long kbId, long pageId) {
        requirePage(user, kbId, pageId, ResourceAction.READ, WikiAction.READ_PAGE);
        requireActivePageIn(kbId, pageId);
        // A shared page must expose only attachments cited by that page, never the
        // entire owning knowledge base's attachment catalog.
        return readableSources(user, kbId, pageId);
    }

    private void requireActivePageIn(long kbId, long pageId) {
        WikiPage page = pages.findByIdAndStatus(pageId, WikiPage.STATUS_ACTIVE)
                .orElseThrow(() -> new NotFoundException("page not found"));
        if (page.getKbId() != kbId) {
            throw new NotFoundException("page not found");
        }
    }

    private void requirePage(CurrentUser user, long kbId, long pageId,
                             ResourceAction action, WikiAction fallback) {
        if (resources != null) resources.requireInKnowledgeBase(user, kbId, pageId, action);
        else authorization.require(user, kbId, fallback);
    }

    public record SourceView(String attachmentUuid, String fileName, String contentType,
                             long byteSize, String relationship) {
    }
}
