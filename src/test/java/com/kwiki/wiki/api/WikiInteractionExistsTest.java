package com.kwiki.wiki.api;

import com.kwiki.wiki.access.ResourceAuthorizationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.ResultSetExtractor;

import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WikiInteractionExistsTest {

    @SuppressWarnings("unchecked")
    private static WikiInteractionService serviceReturningRow(boolean hasRow) throws Exception {
        JdbcOperations jdbc = mock(JdbcOperations.class);
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(hasRow);
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), any(Object[].class)))
                .thenAnswer(invocation -> ((ResultSetExtractor<Boolean>) invocation.getArgument(1)).extractData(rs));
        ObjectProvider<JdbcOperations> jdbcProvider = mock(ObjectProvider.class);
        when(jdbcProvider.getIfAvailable()).thenReturn(jdbc);
        return new WikiInteractionService(jdbcProvider, mock(ResourceAuthorizationService.class),
                mock(ObjectProvider.class), mock(com.kwiki.infrastructure.redis.WikiStatisticsCache.class));
    }

    @Test
    void 没有点赞记录时返回false而不是抛异常() throws Exception {
        assertThat(serviceReturningRow(false)
                .exists("SELECT 1 FROM page_like WHERE page_id = ? AND user_id = ?", 1L, 2L)).isFalse();
    }

    @Test
    void 有记录时返回true() throws Exception {
        assertThat(serviceReturningRow(true)
                .exists("SELECT 1 FROM page_like WHERE page_id = ? AND user_id = ?", 1L, 2L)).isTrue();
    }
}
