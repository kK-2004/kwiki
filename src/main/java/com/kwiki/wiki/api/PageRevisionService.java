package com.kwiki.wiki.api;

import com.kk2004.common.exception.NotFoundException;

import com.kwiki.indexing.job.IndexingJobEnqueuer;
import com.kwiki.security.CurrentUser;
import com.kwiki.wiki.access.KnowledgeBaseAuthorizationService;
import com.kwiki.wiki.access.WikiAction;
import com.kwiki.wiki.domain.WikiPage;
import com.kwiki.wiki.domain.WikiPageRevision;
import com.kwiki.wiki.persistence.WikiPageRepository;
import com.kwiki.wiki.persistence.WikiPageRevisionRepository;
import com.kwiki.wiki.render.MarkdownPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Draft/publish lifecycle over immutable revisions. Saving a draft never changes
 * what readers see; publishing atomically moves the published pointer and records
 * an indexing job; restoring an old revision creates a new revision instead of
 * mutating history.
 */
@Service
public class PageRevisionService {

    private final WikiPageRepository pages;
    private final WikiPageRevisionRepository revisions;
    private final KnowledgeBaseAuthorizationService authorization;
    private final MarkdownPort markdown;
    private final IndexingJobEnqueuer indexingJobs;
    private final PageLinkService pageLinks;

    public PageRevisionService(WikiPageRepository pages,
                               WikiPageRevisionRepository revisions,
                               KnowledgeBaseAuthorizationService authorization,
                               MarkdownPort markdown,
                               IndexingJobEnqueuer indexingJobs,
                               PageLinkService pageLinks) {
        this.pages = pages;
        this.revisions = revisions;
        this.authorization = authorization;
        this.markdown = markdown;
        this.indexingJobs = indexingJobs;
        this.pageLinks = pageLinks;
    }

    @Transactional
    public WikiPageRevision saveDraft(CurrentUser user, long kbId, long pageId, String markdownText,
                                      String changeNote, Integer expectedLockVersion) {
        authorization.require(user, kbId, WikiAction.EDIT_PAGE);
        WikiPage page = requireActivePage(kbId, pageId);
        if (expectedLockVersion != null && page.getLockVersion() != expectedLockVersion) {
            throw new ConflictException("page was modified concurrently");
        }
        WikiPageRevision revision = revisions.save(new WikiPageRevision(
                pageId, nextRevisionNo(pageId), markdownText,
                markdown.plainText(markdownText), changeNote, user.id()));
        if (revision.getId() == null) {
            // repository mocks in tests do not generate ids; production JPA does
            revision = revisions.save(revision);
        }
        page.setCurrentDraftRevisionId(revision.getId());
        pages.save(page);
        return revision;
    }

    @Transactional
    public WikiPageRevision publish(CurrentUser user, long kbId, long pageId) {
        authorization.require(user, kbId, WikiAction.EDIT_PAGE);
        WikiPage page = requireActivePage(kbId, pageId);
        Long draftId = page.getCurrentDraftRevisionId();
        if (draftId == null) {
            throw new IllegalArgumentException("no draft revision to publish");
        }
        page.setCurrentPublishedRevisionId(draftId);
        pages.save(page);
        // same-transaction contract: job exists iff publish commits (task 4.2)
        indexingJobs.enqueuePageUpsert(pageId, draftId);
        WikiPageRevision published = revisions.findById(draftId)
                .orElseThrow(() -> new NotFoundException("draft revision not found"));
        pageLinks.refreshLinks(pageId, kbId, published.getMarkdown());
        return published;
    }

    /** Readers always receive the current published revision, never drafts. */
    public WikiPageRevision publishedContent(CurrentUser user, long kbId, long pageId) {
        authorization.require(user, kbId, WikiAction.READ_PAGE);
        WikiPage page = requireActivePage(kbId, pageId);
        Long publishedId = page.getCurrentPublishedRevisionId();
        if (publishedId == null) {
            throw new NotFoundException("page has no published revision");
        }
        return revisions.findById(publishedId)
                .orElseThrow(() -> new NotFoundException("published revision not found"));
    }

    public WikiPageRevision draft(CurrentUser user, long kbId, long pageId) {
        authorization.require(user, kbId, WikiAction.EDIT_PAGE);
        WikiPage page = requireActivePage(kbId, pageId);
        Long draftId = page.getCurrentDraftRevisionId();
        if (draftId == null) {
            throw new NotFoundException("page has no draft revision");
        }
        return revisions.findById(draftId)
                .orElseThrow(() -> new NotFoundException("draft revision not found"));
    }

    public List<WikiPageRevision> revisionHistory(CurrentUser user, long kbId, long pageId) {
        authorization.require(user, kbId, WikiAction.VIEW_REVISION_HISTORY);
        requireActivePage(kbId, pageId);
        return revisions.findByPageIdOrderByRevisionNoDesc(pageId);
    }

    /** Restoration copies older content into a NEW revision; history stays immutable. */
    @Transactional
    public WikiPageRevision restore(CurrentUser user, long kbId, long pageId, int revisionNo) {
        WikiPageRevision old = revisions.findByPageIdAndRevisionNo(pageId, revisionNo)
                .orElseThrow(() -> new NotFoundException("revision not found"));
        WikiPageRevision restored = revisions.save(new WikiPageRevision(
                pageId, nextRevisionNo(pageId), old.getMarkdown(),
                old.getPlainText(), "restored from revision " + revisionNo, user.id()));
        WikiPage page = requireActivePage(kbId, pageId);
        authorization.require(user, kbId, WikiAction.RESTORE_REVISION);
        page.setCurrentDraftRevisionId(restored.getId());
        pages.save(page);
        return restored;
    }

    @Transactional
    public void archive(CurrentUser user, long kbId, long pageId) {
        authorization.require(user, kbId, WikiAction.ARCHIVE_PAGE);
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
}
