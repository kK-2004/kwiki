package com.kwiki.wiki.access;

import com.kk2004.common.exception.NotFoundException;
import com.kwiki.security.CurrentUser;
import com.kwiki.testutil.StandardTestProperties;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcOperations;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResourceAuthorizationServiceTest {

    @Test
    void missingPageInKnowledgeBaseIsReportedAsNotFound() {
        JdbcOperations jdbc = mock(JdbcOperations.class);
        when(jdbc.queryForObject(
                "SELECT kb_id FROM wiki_page WHERE id = ? AND status = 'ACTIVE'",
                Long.class, 7L)).thenThrow(new EmptyResultDataAccessException(1));
        ResourceAuthorizationService service = new ResourceAuthorizationService(
                StandardTestProperties.providerOf(jdbc),
                mock(KnowledgeBaseAuthorizationService.class));

        assertThatThrownBy(() -> service.requireInKnowledgeBase(
                new CurrentUser(9L, "user", false), 5L, 7L, ResourceAction.READ))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("wiki不存在");
    }
}
