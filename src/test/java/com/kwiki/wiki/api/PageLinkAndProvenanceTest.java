package com.kwiki.wiki.api;

import com.kwiki.security.CurrentUser;
import com.kwiki.wiki.access.KnowledgeBaseAuthorizationService;
import com.kwiki.wiki.access.MembershipLookup;
import com.kwiki.wiki.domain.Attachment;
import com.kwiki.wiki.domain.SourceDocument;
import com.kwiki.wiki.domain.WikiLink;
import com.kwiki.wiki.domain.WikiPage;
import com.kwiki.wiki.persistence.AttachmentRepository;
import com.kwiki.wiki.persistence.SourceDocumentRepository;
import com.kwiki.wiki.persistence.WikiLinkRepository;
import com.kwiki.wiki.persistence.WikiPageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PageLinkAndProvenanceTest {

    private static final CurrentUser ADMIN = new CurrentUser(1L, "root", true);
    private static final long KB = 1L;

    private static final String UUID_SELF = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String UUID_VALID = "bbbbbbbb-0000-0000-0000-000000000002";
    private static final String UUID_ARCHIVED = "cccccccc-0000-0000-0000-000000000003";
    private static final String UUID_FOREIGN = "dddddddd-0000-0000-0000-000000000004";

    @Mock
    WikiLinkRepository links;

    @Mock
    WikiPageRepository pages;

    @Mock
    SourceDocumentRepository sources;

    @Mock
    AttachmentRepository attachments;

    PageLinkService linkService;
    PageProvenanceService provenanceService;

    @BeforeEach
    void setUp() {
        KnowledgeBaseAuthorizationService auth = new KnowledgeBaseAuthorizationService(
                new ObjectProvider<>() {
                    @Override
                    public MembershipLookup getIfAvailable() {
                        return null;
                    }
                });
        linkService = new PageLinkService(links, pages);
        provenanceService = new PageProvenanceService(sources, attachments, pages, auth);
        lenient().when(pages.save(any(WikiPage.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private WikiPage page(long id, long kbId, String uuid, String title) {
        WikiPage page = new WikiPage(uuid, kbId, null, title, WikiPage.TYPE_PAGE, 0, ADMIN.id());
        org.springframework.test.util.ReflectionTestUtils.setField(page, "id", id);
        return page;
    }

    @Test
    void backlinksSurviveRenameBecauseRowsUseStableIds() {
        when(links.findByTargetPageId(5L)).thenReturn(List.of(new WikiLink(2L, 5L)));
        WikiPage source = page(2, KB, "aaaaaaaa-0000-0000-0000-00000000000a", "旧标题");
        when(pages.findByIdAndStatus(2L, WikiPage.STATUS_ACTIVE)).thenReturn(Optional.of(source));

        assertThat(linkService.backlinks(5L))
                .extracting(WikiPage::getTitle).containsExactly("旧标题");

        source.rename("新标题");
        source.moveTo(9L, 3);

        assertThat(linkService.backlinks(5L))
                .extracting(WikiPage::getTitle).containsExactly("新标题");
        verify(links, atLeastOnce()).findByTargetPageId(5L);
    }

    @Test
    void archivedSourcesDisappearFromBacklinks() {
        when(links.findByTargetPageId(5L)).thenReturn(List.of(new WikiLink(2L, 5L)));
        when(pages.findByIdAndStatus(2L, WikiPage.STATUS_ACTIVE)).thenReturn(Optional.empty());

        assertThat(linkService.backlinks(5L)).isEmpty();
    }

    @Test
    void publishRefreshResolvesOnlyActiveSameKbTargetsAndSkipsSelfLinks() {
        when(pages.findByUuid(UUID_SELF)).thenReturn(Optional.of(page(1, KB, UUID_SELF, "self")));
        when(pages.findByUuid(UUID_FOREIGN)).thenReturn(Optional.of(page(9, 999L, UUID_FOREIGN, "foreign")));
        when(pages.findByUuid(UUID_ARCHIVED)).thenReturn(Optional.empty());
        when(pages.findByUuid(UUID_VALID)).thenReturn(Optional.of(page(7, KB, UUID_VALID, "valid")));

        linkService.refreshLinks(1, KB, "see [self](kwiki-page:" + UUID_SELF + "), "
                + "[foreign](kwiki-page:" + UUID_FOREIGN + "), "
                + "[archived](kwiki-page:" + UUID_ARCHIVED + "), "
                + "[valid](kwiki-page:" + UUID_VALID + ")");

        verify(links).deleteAllBySourcePageId(1L);
        var captor = org.mockito.ArgumentCaptor.forClass(WikiLink.class);
        verify(links).save(captor.capture());
        assertThat(captor.getValue().getTargetPageId()).isEqualTo(7L);
    }

    @Test
    void inaccessibleAttachmentsAreNotDisclosedInSources() {
        WikiPage target = page(3, KB, "eeeeeeee-0000-0000-0000-00000000000b", "sourced page");
        when(pages.findByIdAndStatus(3L, WikiPage.STATUS_ACTIVE)).thenReturn(Optional.of(target));

        Attachment sameKb = new Attachment("att-1", KB, ADMIN.id(), "spec.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                10);
        org.springframework.test.util.ReflectionTestUtils.setField(sameKb, "id", 11L);
        sameKb.markStored(101L);

        Attachment otherKb = new Attachment("att-2", 999L, ADMIN.id(), "secret.docx",
                "application/octet-stream", 10);
        org.springframework.test.util.ReflectionTestUtils.setField(otherKb, "id", 12L);
        otherKb.markStored(102L);

        when(sources.findByPageId(3L)).thenReturn(List.of(
                new SourceDocument(3L, 11L, SourceDocument.REL_DERIVED_FROM),
                new SourceDocument(3L, 12L, SourceDocument.REL_DERIVED_FROM)));
        when(attachments.findById(11L)).thenReturn(Optional.of(sameKb));
        when(attachments.findById(12L)).thenReturn(Optional.of(otherKb));

        var views = provenanceService.readableSources(ADMIN, KB, 3L);

        assertThat(views).hasSize(1);
        assertThat(views.get(0).fileName()).isEqualTo("spec.docx");
        assertThat(views).extracting(PageProvenanceService.SourceView::fileName)
                .doesNotContain("secret.docx");
    }
}
