package com.kwiki.wiki.persistence;

import com.kwiki.testutil.StandardTestProperties;
import com.kwiki.wiki.domain.AppUser;
import com.kwiki.wiki.domain.KnowledgeBase;
import com.kwiki.wiki.domain.WikiPage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository contract (operator-provided MySQL via KWIKI_IT_MYSQL_*): default
 * queries must never return archived pages and must stay scoped to the knowledge
 * base ids passed in by the authorization-aware service layer.
 */
@EnabledIfEnvironmentVariable(named = "KWIKI_IT_MYSQL_URL", matches = ".+")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class WikiRepositoryContractTest {

    @DynamicPropertySource
    static void externalDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("KWIKI_IT_MYSQL_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("KWIKI_IT_MYSQL_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("KWIKI_IT_MYSQL_PASSWORD"));
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired
    AppUserRepository users;

    @Autowired
    KnowledgeBaseRepository knowledgeBases;

    @Autowired
    WikiPageRepository pages;

    private KnowledgeBase newKb(String label) {
        AppUser owner = users.save(new AppUser(label + "-owner", label, null, false));
        return knowledgeBases.save(
                new KnowledgeBase(UUID.randomUUID().toString(), label + "-kb", null, owner.getId()));
    }

    private WikiPage newPage(KnowledgeBase kb, String title) {
        return pages.save(new WikiPage(UUID.randomUUID().toString(), kb.getId(), null,
                title, WikiPage.TYPE_PAGE, 0, kb.getCreatedBy()));
    }

    @Test
    void defaultQueriesNeverReturnArchivedPages() {
        KnowledgeBase kb = newKb("default-filter");
        WikiPage active = newPage(kb, "active page");
        WikiPage archived = newPage(kb, "archived page");
        archived.archive();
        pages.save(archived);

        assertThat(pages.findByKbIdAndStatusOrderByParentIdAscSiblingOrderAsc(
                kb.getId(), WikiPage.STATUS_ACTIVE))
                .extracting(WikiPage::getTitle)
                .containsExactly(active.getTitle());

        assertThat(pages.findByIdAndStatus(archived.getId(), WikiPage.STATUS_ACTIVE)).isEmpty();
        assertThat(pages.findByKbIdInAndStatusOrderByKbIdAscParentIdAscSiblingOrderAsc(
                List.of(kb.getId()), WikiPage.STATUS_ACTIVE))
                .hasSize(1);
    }

    @Test
    void queriesStayScopedToRequestedKnowledgeBases() {
        KnowledgeBase mine = newKb("scope-mine");
        KnowledgeBase other = newKb("scope-other");
        newPage(mine, "my page");
        newPage(other, "someone else's page");

        assertThat(pages.findByKbIdInAndStatusOrderByKbIdAscParentIdAscSiblingOrderAsc(
                List.of(mine.getId()), WikiPage.STATUS_ACTIVE))
                .extracting(WikiPage::getKbId)
                .containsOnly(mine.getId());

        assertThat(pages.findByKbIdAndParentIdAndStatusOrderBySiblingOrderAsc(
                mine.getId(), null, WikiPage.STATUS_ACTIVE))
                .extracting(WikiPage::getTitle)
                .doesNotContain("someone else's page");
    }
}
