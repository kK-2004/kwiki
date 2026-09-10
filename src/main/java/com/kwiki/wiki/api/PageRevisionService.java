package com.kwiki.wiki.api;

import com.kk2004.common.exception.NotFoundException;

import com.kwiki.indexing.job.IndexingJobEnqueuer;
import com.kwiki.security.CurrentUser;
import com.kwiki.wiki.access.KnowledgeBaseAuthorizationService;
import com.kwiki.wiki.access.ResourceAction;
import com.kwiki.wiki.access.ResourceAuthorizationService;
import com.kwiki.wiki.access.WikiAction;
import com.kwiki.wiki.domain.WikiPage;
import com.kwiki.wiki.domain.WikiPageDraft;
import com.kwiki.wiki.domain.WikiPageRevision;
import com.kwiki.wiki.persistence.WikiPageDraftRepository;
import com.kwiki.wiki.persistence.WikiPageRepository;
import com.kwiki.wiki.persistence.WikiPageRevisionRepository;
import com.kwiki.wiki.render.MarkdownPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Draft/publish lifecycle with a mutable working copy outside immutable revisions.
 * Saving never changes what readers see or creates a version; publishing creates
 * one numbered revision and records its indexing job. Restoring an old revision
 * creates a new current revision instead of mutating history.
 */
@Service
public class PageRevisionService {

    private final WikiPageRepository pages;
    private final WikiPageRevisionRepository revisions;
    private final KnowledgeBaseAuthorizationService authorization;
    private final MarkdownPort markdown;
    private final IndexingJobEnqueuer indexingJobs;
    private final PageLinkService pageLinks;
    private final ResourceAuthorizationService resources;
    private final com.kwiki.wiki.archive.ResourceArchiveService archiveService;
    private final PageMediaReferenceService mediaReferences;
    private final WikiPageDraftRepository drafts;

    public PageRevisionService(WikiPageRepository pages,
                               WikiPageRevisionRepository revisions,
                               KnowledgeBaseAuthorizationService authorization,
                               MarkdownPort markdown,
                               IndexingJobEnqueuer indexingJobs,
                               PageLinkService pageLinks) {
        this(pages, revisions, authorization, markdown, indexingJobs, pageLinks, null, null, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PageRevisionService(WikiPageRepository pages,
                               WikiPageRevisionRepository revisions,
                               KnowledgeBaseAuthorizationService authorization,
                               MarkdownPort markdown,
                               IndexingJobEnqueuer indexingJobs,
                               PageLinkService pageLinks,
                               ResourceAuthorizationService resources,
                               com.kwiki.wiki.archive.ResourceArchiveService archiveService,
                               PageMediaReferenceService mediaReferences,
                               WikiPageDraftRepository drafts) {
        this.pages = pages;
        this.revisions = revisions;
        this.authorization = authorization;
        this.markdown = markdown;
        this.indexingJobs = indexingJobs;
        this.pageLinks = pageLinks;
        this.resources = resources;
        this.archiveService = archiveService;
        this.mediaReferences = mediaReferences;
        this.drafts = drafts;
    }

    @Transactional
    public WikiPageDraft saveDraft(CurrentUser user, long kbId, long pageId, String markdownText,
                                   String changeNote, Integer expectedLockVersion) {
        requirePage(user, kbId, pageId, ResourceAction.EDIT, WikiAction.EDIT_PAGE);
        WikiPage page = requireActivePage(kbId, pageId);
        if (expectedLockVersion != null && page.getLockVersion() != expectedLockVersion) {
            throw new ConflictException("page was modified concurrently");
        }
        WikiPageDraft draft = drafts.findById(pageId)
                .orElseGet(() -> new WikiPageDraft(pageId, markdownText,
                        markdown.plainText(markdownText), changeNote, user.id()));
        draft.replace(markdownText, markdown.plainText(markdownText), changeNote, user.id());
        return drafts.save(draft);
    }

    @Transactional
    public WikiPageRevision publish(CurrentUser user, long kbId, long pageId) {
        requirePage(user, kbId, pageId, ResourceAction.EDIT, WikiAction.EDIT_PAGE);
        WikiPage page = requireActivePage(kbId, pageId);
        WikiPageDraft draft = drafts.findById(pageId)
                .orElseThrow(() -> new IllegalArgumentException("no draft to publish"));
        WikiPageRevision published = new WikiPageRevision(
                pageId, nextRevisionNo(pageId), draft.getMarkdown(), draft.getPlainText(),
                draft.getChangeNote(), user.id());
        published.markPublished();
        published = revisions.save(published);
        page.setCurrentPublishedRevisionId(published.getId());
        page.setCurrentDraftRevisionId(published.getId());
        pages.save(page);
        // same-transaction contract: job exists iff publish commits (task 4.2)
        indexingJobs.enqueuePageUpsert(pageId, published.getId());
        pageLinks.refreshLinks(pageId, kbId, published.getMarkdown());
        persistMediaReferences(kbId, pageId, published.getId(), published.getMarkdown());
        drafts.deleteById(pageId);
        return published;
    }

    private void persistMediaReferences(long kbId, long pageId, long revisionId, String markdown) {
        if (mediaReferences != null) {
            mediaReferences.persistRevisionMedia(kbId, pageId, revisionId, markdown);
        }
    }

    /** Readers always receive the current published revision, never drafts. */
    public WikiPageRevision publishedContent(CurrentUser user, long kbId, long pageId) {
        requirePage(user, kbId, pageId, ResourceAction.READ, WikiAction.READ_PAGE);
        WikiPage page = requireActivePage(kbId, pageId);
        Long publishedId = page.getCurrentPublishedRevisionId();
        if (publishedId == null) {
            throw new NotFoundException("page has no published revision");
        }
        return revisions.findById(publishedId)
                .orElseThrow(() -> new NotFoundException("published revision not found"));
    }

    public String title(CurrentUser user, long kbId, long pageId) {
        requirePage(user, kbId, pageId, ResourceAction.READ, WikiAction.READ_PAGE);
        return requireActivePage(kbId, pageId).getTitle();
    }

    public WikiPageDraft draft(CurrentUser user, long kbId, long pageId) {
        requirePage(user, kbId, pageId, ResourceAction.EDIT, WikiAction.EDIT_PAGE);
        requireActivePage(kbId, pageId);
        return drafts.findById(pageId)
                .orElseThrow(() -> new NotFoundException("page has no draft"));
    }

    public List<WikiPageRevision> revisionHistory(CurrentUser user, long kbId, long pageId) {
        requirePage(user, kbId, pageId, ResourceAction.READ, WikiAction.VIEW_REVISION_HISTORY);
        requireActivePage(kbId, pageId);
        return revisions.findByPageIdAndPublishedAtIsNotNullOrderByRevisionNoDesc(pageId);
    }

    /** Restoration copies older content into a new current published revision. */
    @Transactional
    public WikiPageRevision restore(CurrentUser user, long kbId, long pageId, int revisionNo) {
        requirePage(user, kbId, pageId, ResourceAction.EDIT, WikiAction.RESTORE_REVISION);
        WikiPageRevision old = revisions.findByPageIdAndRevisionNo(pageId, revisionNo)
                .orElseThrow(() -> new NotFoundException("revision not found"));
        WikiPageRevision restored = new WikiPageRevision(
                pageId, nextRevisionNo(pageId), old.getMarkdown(),
                old.getPlainText(), "restored from revision " + revisionNo, user.id());
        restored.markPublished();
        restored = revisions.save(restored);
        WikiPage page = requireActivePage(kbId, pageId);
        page.setCurrentPublishedRevisionId(restored.getId());
        page.setCurrentDraftRevisionId(restored.getId());
        pages.save(page);
        indexingJobs.enqueuePageUpsert(pageId, restored.getId());
        pageLinks.refreshLinks(pageId, kbId, restored.getMarkdown());
        persistMediaReferences(kbId, pageId, restored.getId(), restored.getMarkdown());
        if (drafts != null) drafts.deleteById(pageId);
        return restored;
    }

    /**
     * Delegates to the unified recycle-bin service (own transaction boundary +
     * post-commit ES isolation); kept for the existing controller surface.
     */
    public void archive(CurrentUser user, long kbId, long pageId) {
        if (archiveService != null) {
            archiveService.archivePage(user, kbId, pageId);
            return;
        }
        // Test/fallback path without the recycle-bin wiring.
        requirePage(user, kbId, pageId, ResourceAction.MANAGE, WikiAction.ARCHIVE_PAGE);
        WikiPage page = requireActivePage(kbId, pageId);
        page.archive();
        pages.save(page);
        indexingJobs.enqueuePageDelete(pageId);
    }

    private WikiPage requireActivePage(long kbId, long pageId) {
        WikiPage page = pages.findByIdAndStatus(pageId, WikiPage.STATUS_ACTIVE)
                .orElseThrow(() -> new NotFoundException("page not found"));
        if (page.getKbId() != kbId) {
            throw new NotFoundException("page not found");
        }
        return page;
    }

    private int nextRevisionNo(long pageId) {
        return revisions.findFirstByPageIdOrderByRevisionNoDesc(pageId)
                .map(previous -> previous.getRevisionNo() + 1)
                .orElse(1);
    }

    private void requirePage(CurrentUser user, long kbId, long pageId,
                              ResourceAction action, WikiAction fallback) {
        if (resources != null) resources.requireInKnowledgeBase(user, kbId, pageId, action);
        else authorization.require(user, kbId, fallback);
    }
}
