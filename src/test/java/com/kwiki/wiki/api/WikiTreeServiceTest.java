package com.kwiki.wiki.api;

import com.kwiki.security.CurrentUser;
import com.kwiki.wiki.access.KnowledgeBaseAuthorizationService;
import com.kwiki.wiki.domain.WikiPage;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WikiTreeServiceTest {

    private static final CurrentUser EDITOR = new CurrentUser(5L, "editor", false);
    private static final long KB = 1L;

    @Mock
    WikiPageRepository pages;

    WikiTreeService service;

    @BeforeEach
    void setUp() {
        service = new WikiTreeService(pages, authorization());
        lenient().when(pages.save(any(WikiPage.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private static KnowledgeBaseAuthorizationService authorization() {
        return new KnowledgeBaseAuthorizationService(nullLookup());
    }

    private static ObjectProvider<com.kwiki.wiki.access.MembershipLookup> nullLookup() {
        return new ObjectProvider<>() {
            @Override
            public com.kwiki.wiki.access.MembershipLookup getIfAvailable() {
                return null;
            }
        };
    }

    private static final CurrentUser ADMIN = new CurrentUser(1L, "root", true);

    private WikiPage page(long id, long kbId, Long parentId, String title, int order) {
        WikiPage page = new WikiPage("uuid-" + id, kbId, parentId, title,
                WikiPage.TYPE_PAGE, order, ADMIN.id());
        org.springframework.test.util.ReflectionTestUtils.setField(page, "id", id);
        return page;
    }

    @Test
    void createAssignsStableUuidAndAppendsOrdering() {
        when(pages.findByKbIdAndParentIdAndStatusOrderBySiblingOrderAsc(
                KB, null, WikiPage.STATUS_ACTIVE))
                .thenReturn(List.of(page(1, KB, null, "first", 0)));

        WikiPage created = service.createNode(ADMIN, KB, null, "second page",
                WikiPage.TYPE_PAGE, null);

        assertThat(created.getUuid()).isNotBlank();
        assertThat(created.getSiblingOrder()).isEqualTo(1);
        assertThat(created.getTitle()).isEqualTo("second page");
    }

    @Test
    void createUnderParentOfAnotherKnowledgeBaseIsRejected() {
        when(pages.findByIdAndStatus(50L, WikiPage.STATUS_ACTIVE))
                .thenReturn(Optional.of(page(50, 999L, null, "foreign parent", 0)));

        assertThatThrownBy(() ->
                service.createNode(ADMIN, KB, 50L, "child", WikiPage.TYPE_PAGE, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void moveKeepsIdentityWhileChangingHierarchy() {
        WikiPage page = page(7, KB, 1L, "movable", 0);
        when(pages.findByIdAndStatus(7L, WikiPage.STATUS_ACTIVE)).thenReturn(Optional.of(page));
        when(pages.findByIdAndStatus(9L, WikiPage.STATUS_ACTIVE))
                .thenReturn(Optional.of(page(9, KB, null, "new parent", 0)));
        when(pages.findByKbIdAndParentIdAndStatusOrderBySiblingOrderAsc(
                KB, 9L, WikiPage.STATUS_ACTIVE)).thenReturn(List.of());

        service.move(ADMIN, KB, 7L, 9L, null);

        assertThat(page.getParentId()).isEqualTo(9L);
        assertThat(page.getUuid()).isEqualTo("uuid-7");
        assertThat(page.getId()).isEqualTo(7L);
    }

    @Test
    void moveBeneathOwnDescendantIsRejectedWithoutHierarchyChange() {
        WikiPage parent = page(7, KB, null, "parent", 0);
        WikiPage child = page(8, KB, 7L, "child", 0);
        when(pages.findByIdAndStatus(7L, WikiPage.STATUS_ACTIVE))
                .thenReturn(Optional.of(parent));
        when(pages.findByIdAndStatus(8L, WikiPage.STATUS_ACTIVE))
                .thenReturn(Optional.of(child));
        // child is the attempted new parent: its ancestor chain contains 7 -> cycle

        assertThatThrownBy(() -> service.move(ADMIN, KB, 7L, 8L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("descendant");

        assertThat(parent.getParentId()).as("hierarchy must stay unchanged").isNull();
    }

    @Test
    void moveToItselfIsRejected() {
        when(pages.findByIdAndStatus(7L, WikiPage.STATUS_ACTIVE))
                .thenReturn(Optional.of(page(7, KB, null, "self", 0)));

        assertThatThrownBy(() -> service.move(ADMIN, KB, 7L, 7L, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void crossKnowledgeBaseMoveIsRejected() {
        when(pages.findByIdAndStatus(7L, WikiPage.STATUS_ACTIVE))
                .thenReturn(Optional.of(page(7, KB, null, "movable", 0)));
        when(pages.findByIdAndStatus(60L, WikiPage.STATUS_ACTIVE))
                .thenReturn(Optional.of(page(60, 999L, null, "foreign parent", 0)));

        assertThatThrownBy(() -> service.move(ADMIN, KB, 7L, 60L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("another knowledge base");
    }

    @Test
    void archivedPagesDisappearFromTree() {
        WikiTreeService service = new WikiTreeService(pages, authorization());
        when(pages.findByKbIdAndStatusOrderByParentIdAscSiblingOrderAsc(KB, WikiPage.STATUS_ACTIVE))
                .thenReturn(List.of(
                        page(1, KB, null, "folder", 0),
                        page(2, KB, 1L, "visible child", 0)));

        List<WikiTreeService.TreeNodeView> tree = service.tree(ADMIN, KB);

        assertThat(tree).hasSize(1);
        assertThat(tree.get(0).children()).hasSize(1);
        assertThat(tree.get(0).children().get(0).title()).isEqualTo("visible child");
    }
}
