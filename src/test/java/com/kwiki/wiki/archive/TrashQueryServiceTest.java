package com.kwiki.wiki.archive;

import com.kwiki.security.CurrentUser;
import com.kwiki.testutil.StandardTestProperties;
import com.kwiki.wiki.access.KnowledgeBaseAuthorizationService;
import com.kwiki.wiki.domain.ArchiveBatch;
import com.kwiki.wiki.domain.WikiPage;
import com.kwiki.wiki.persistence.ArchiveBatchRepository;
import com.kwiki.wiki.persistence.KnowledgeBaseRepository;
import com.kwiki.wiki.persistence.WikiPageRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TrashQueryServiceTest {

    private static final Instant ARCHIVED_AT = Instant.parse("2026-09-14T00:00:00Z");

    @Test
    void globalTrashIncludesAnArchivedWikiPageBatch() {
        ArchiveBatch pageBatch = batch(11L, ArchiveBatch.SCOPE_PAGE, 42L, 7L);
        ArchiveBatchRepository batches = proxy(ArchiveBatchRepository.class, Map.of(
                "findByStateOrderByArchivedAtDesc", List.of(pageBatch)));
        WikiPage page = new WikiPage("page-42", 7L, null, "归档 Wiki", WikiPage.TYPE_PAGE, 0, 9L);
        ReflectionTestUtils.setField(page, "id", 42L);
        WikiPageRepository pages = proxy(WikiPageRepository.class, Map.of(
                "findById", Optional.of(page)));
        KnowledgeBaseRepository knowledgeBases = proxy(KnowledgeBaseRepository.class, Map.of());
        KnowledgeBaseAuthorizationService authorization = new KnowledgeBaseAuthorizationService(
                StandardTestProperties.nullProvider());
        TrashQueryService service = new TrashQueryService(
                batches, pages, knowledgeBases, authorization, null,
                Clock.fixed(ARCHIVED_AT, ZoneOffset.UTC));

        TrashQueryService.TrashPage result = service.list(
                new CurrentUser(9L, "admin", true), null, null, null, 20);

        assertThat(result.items()).singleElement()
                .satisfies(item -> {
                    assertThat(item.batchId()).isEqualTo(11L);
                    assertThat(item.resourceType()).isEqualTo(ArchiveBatch.SCOPE_PAGE);
                    assertThat(item.title()).isEqualTo("归档 Wiki");
                });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Map<String, Object> responses) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> {
                    if (responses.containsKey(method.getName())) {
                        return responses.get(method.getName());
                    }
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    if (method.getReturnType() == long.class) return 0L;
                    return null;
                });
    }

    private static ArchiveBatch batch(long id, String scopeType, long rootResourceId, long kbId) {
        ArchiveBatch batch = new ArchiveBatch(
                "batch-" + id, scopeType, rootResourceId, kbId, 9L,
                ARCHIVED_AT, ARCHIVED_AT.plusSeconds(7 * 24 * 60 * 60), 1,
                ArchiveBatch.ORIGIN_NORMAL);
        ReflectionTestUtils.setField(batch, "id", id);
        return batch;
    }
}
