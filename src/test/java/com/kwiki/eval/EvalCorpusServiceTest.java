package com.kwiki.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwiki.security.CurrentUser;
import com.kwiki.wiki.api.PageIndexStatusService;
import com.kwiki.wiki.api.PageRevisionService;
import com.kwiki.wiki.api.WikiTreeService;
import com.kwiki.wiki.domain.WikiPage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EvalCorpusServiceTest {

    final CurrentUser admin = new CurrentUser(1L, "admin", true);
    final WikiTreeService tree = mock(WikiTreeService.class);
    final PageRevisionService revisions = mock(PageRevisionService.class);
    final PageIndexStatusService statuses = mock(PageIndexStatusService.class);

    @SuppressWarnings("unchecked")
    EvalCorpusService service() {
        ObjectProvider<PageIndexStatusService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(statuses);
        return new EvalCorpusService(tree, revisions, provider, new ObjectMapper());
    }

    static WikiTreeService.TreeNodeView node(long id, String title) {
        return new WikiTreeService.TreeNodeView(id, "u" + id, title, WikiPage.TYPE_PAGE, List.of());
    }

    static WikiPage page(long id) {
        WikiPage page = mock(WikiPage.class);
        when(page.getId()).thenReturn(id);
        return page;
    }

    @Test
    void importsMissingDocumentsThroughTheNormalPublishPath() {
        when(tree.tree(admin, 9L)).thenReturn(List.of(node(100L, "甲公司-1")));
        WikiPage created = page(101L);
        when(tree.createNode(eq(admin), eq(9L), eq(null), eq("乙法院-2"), eq(WikiPage.TYPE_PAGE), eq(null)))
                .thenReturn(created);

        var result = service().importCorpus(admin, 9L, "test-mini");

        assertThat(result.createdPageIds()).containsExactly(101L);
        assertThat(result.skippedTitles()).containsExactly("甲公司-1");
        verify(revisions).saveDraft(eq(admin), eq(9L), eq(101L), eq("乙法院于2019年审理了一起合同纠纷。\n"),
                eq("评测语料导入 test-mini"), eq(null));
        verify(revisions).publish(admin, 9L, 101L);
        verify(revisions, never()).publish(admin, 9L, 100L);
    }

    @Test
    void mapsPagesToDocIdsByManifestTitle() {
        when(tree.tree(admin, 9L)).thenReturn(List.of(
                new WikiTreeService.TreeNodeView(50L, "u50", "目录", WikiPage.TYPE_FOLDER,
                        List.of(node(100L, "甲公司-1"))),
                node(101L, "乙法院-2"), node(102L, "手工页面")));

        assertThat(service().pageDocIds(admin, 9L, "test-mini")).isEqualTo(java.util.Map.of(100L, 1, 101L, 2));
    }

    @Test
    void rejectsCorpusNamesThatCouldEscapeTheResourceRoot() {
        assertThatThrownBy(() -> service().importCorpus(admin, 9L, "../secrets"))
                .isInstanceOf(EvalException.class);
    }

    @Test
    void readinessRequiresEveryPageIndexed() {
        when(tree.tree(admin, 9L)).thenReturn(List.of(node(100L, "甲公司-1"), node(101L, "乙法院-2")));
        when(statuses.status(admin, 9L, 100L)).thenReturn(
                new PageIndexStatusService.PageIndexStatus(PageIndexStatusService.IndexStatus.READY));
        when(statuses.status(admin, 9L, 101L)).thenReturn(
                new PageIndexStatusService.PageIndexStatus(PageIndexStatusService.IndexStatus.INDEXING));

        assertThatThrownBy(() -> service().requireReady(admin, 9L))
                .isInstanceOf(EvalException.class).hasMessageContaining("corpus-not-ready");
    }

    @Test
    void readinessRejectsEmptyKnowledgeBase() {
        when(tree.tree(admin, 9L)).thenReturn(List.of());
        assertThatThrownBy(() -> service().requireReady(admin, 9L))
                .isInstanceOf(EvalException.class).hasMessageContaining("corpus-empty");
    }

    @Test
    void readinessPassesWhenAllPagesAreReady() {
        when(tree.tree(admin, 9L)).thenReturn(List.of(node(100L, "甲公司-1")));
        when(statuses.status(any(), anyLong(), anyLong())).thenReturn(
                new PageIndexStatusService.PageIndexStatus(PageIndexStatusService.IndexStatus.READY));
        service().requireReady(admin, 9L);
    }
}
