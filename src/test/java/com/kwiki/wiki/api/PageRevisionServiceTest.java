package com.kwiki.wiki.api;

import com.kwiki.indexing.job.IndexingJobEnqueuer;
import com.kwiki.security.CurrentUser;
import com.kwiki.wiki.access.KnowledgeBaseAuthorizationService;
import com.kwiki.wiki.access.MembershipLookup;
import com.kwiki.wiki.domain.WikiPage;
import com.kwiki.wiki.domain.WikiPageDraft;
import com.kwiki.wiki.domain.WikiPageRevision;
import com.kwiki.wiki.persistence.WikiPageDraftRepository;
import com.kwiki.wiki.persistence.WikiPageRepository;
import com.kwiki.wiki.persistence.WikiPageRevisionRepository;
import com.kwiki.wiki.render.CommonMarkMarkdownPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PageRevisionServiceTest {

    private static final CurrentUser ADMIN = new CurrentUser(1L, "root", true);
    private static final long KB = 1L;

    @Mock
    WikiPageRepository pages;

    @Mock
    WikiPageRevisionRepository revisions;

    @Mock
    WikiPageDraftRepository drafts;

    @Mock
    IndexingJobEnqueuer indexingJobs;

    @Mock
    PageLinkService pageLinks;

    PageRevisionService service;

    private final AtomicLong revisionIds = new AtomicLong(100);

    private WikiPage page;

    @BeforeEach
    void setUp() {
        service = new PageRevisionService(pages, revisions, authorization(),
                new CommonMarkMarkdownPort(), indexingJobs, pageLinks,
                null, null, null, drafts);
        page = activePage(7L, KB);
        lenient().when(pages.findByIdAndStatus(7L, WikiPage.STATUS_ACTIVE))
                .thenReturn(Optional.of(page));
        lenient().when(pages.save(any(WikiPage.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(revisions.save(any(WikiPageRevision.class))).thenAnswer(inv -> {
            WikiPageRevision revision = inv.getArgument(0);
            if (revision.getId() == null) {
                org.springframework.test.util.ReflectionTestUtils.setField(
                        revision, "id", revisionIds.incrementAndGet());
            }
            return revision;
        });
        lenient().when(drafts.save(any(WikiPageDraft.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static KnowledgeBaseAuthorizationService authorization() {
        return new KnowledgeBaseAuthorizationService(new ObjectProvider<>() {
            @Override
            public MembershipLookup getIfAvailable() {
                return null;
            }
        });
    }

    private WikiPage activePage(long id, long kbId) {
        WikiPage page = new WikiPage("uuid-" + id, kbId, null, "page " + id,
                WikiPage.TYPE_PAGE, 0, ADMIN.id());
        org.springframework.test.util.ReflectionTestUtils.setField(page, "id", id);
        return page;
    }

    private void published(long pageId, long revisionId, int revisionNo) {
        page.setCurrentPublishedRevisionId(revisionId);
        lenient().when(revisions.findById(revisionId)).thenReturn(Optional.of(
                revision(pageId, revisionId, revisionNo, "# Published\nold content")));
    }

    private WikiPageRevision revision(long pageId, long revisionId, int revisionNo, String markdown) {
        WikiPageRevision revision = new WikiPageRevision(pageId, revisionNo, markdown,
                new CommonMarkMarkdownPort().plainText(markdown), null, ADMIN.id());
        org.springframework.test.util.ReflectionTestUtils.setField(revision, "id", revisionId);
        return revision;
    }

    @Test
    void draftSaveKeepsReaderVisiblePublishedContent() {
        published(7L, 50L, 3);
        lenient().when(revisions.findFirstByPageIdOrderByRevisionNoDesc(7L))
                .thenReturn(Optional.of(revision(7L, 50L, 3, "# Published")));

        service.saveDraft(ADMIN, KB, 7L, "# Draft\nnew stuff", "wip", null);

        verify(drafts).save(any(WikiPageDraft.class));
        assertThat(page.getCurrentPublishedRevisionId()).as("readers must keep seeing revision 3")
                .isEqualTo(50L);
        verify(revisions, never()).save(any(WikiPageRevision.class));
        verify(indexingJobs, never()).enqueuePageUpsert(any(Long.class), any(Long.class));
    }

    @Test
    void publishMovesPublishedPointerAndEnqueuesIndexingJob() {
        lenient().when(revisions.findFirstByPageIdOrderByRevisionNoDesc(7L))
                .thenReturn(Optional.empty());

        WikiPageDraft draft = service.saveDraft(ADMIN, KB, 7L, "# New draft", "ready", null);
        when(drafts.findById(7L)).thenReturn(Optional.of(draft));

        WikiPageRevision published = service.publish(ADMIN, KB, 7L);

        assertThat(page.getCurrentPublishedRevisionId()).isEqualTo(published.getId());
        assertThat(published.getMarkdown()).isEqualTo("# New draft");
        verify(indexingJobs).enqueuePageUpsert(7L, published.getId());
    }

    @Test
    void staleLockVersionIsRejectedWithConflict() {
        org.springframework.test.util.ReflectionTestUtils.setField(page, "lockVersion", 3L);

        assertThatThrownBy(() ->
                service.saveDraft(ADMIN, KB, 7L, "# overwrite", null, 2))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void restoreCreatesNewRevisionInsteadOfMutatingHistory() {
        WikiPageRevision older = revision(7L, 50L, 2, "# Old content");
        when(revisions.findByPageIdAndRevisionNo(7L, 2)).thenReturn(Optional.of(older));
        when(revisions.findFirstByPageIdOrderByRevisionNoDesc(7L))
                .thenReturn(Optional.of(revision(7L, 60L, 5, "# Current")));

        WikiPageRevision restored = service.restore(ADMIN, KB, 7L, 2);

        assertThat(restored.getRevisionNo()).isEqualTo(6);
        assertThat(restored.getMarkdown()).isEqualTo("# Old content");
        assertThat(restored.getChangeNote()).contains("restored from revision 2");
        assertThat(page.getCurrentDraftRevisionId()).isEqualTo(restored.getId());
        assertThat(page.getCurrentPublishedRevisionId()).isEqualTo(restored.getId());
        assertThat(older.getRevisionNo()).as("history entry untouched").isEqualTo(2);
    }

    @Test
    void archiveRecordsIndexDeleteJob() {
        service.archive(ADMIN, KB, 7L);
        assertThat(page.isArchived()).isTrue();
        verify(indexingJobs).enqueuePageDelete(7L);
    }

    @Test
    void plainTextProjectionIsStoredWithRevision() {
        lenient().when(revisions.findFirstByPageIdOrderByRevisionNoDesc(7L))
                .thenReturn(Optional.empty());

        WikiPageDraft draft = service.saveDraft(ADMIN, KB, 7L,
                "# Heading\n\n**bold** and `code`", null, null);

        assertThat(draft.getPlainText())
                .contains("Heading")
                .contains("bold")
                .doesNotContain("**")
                .doesNotContain("`");
    }
}
