package com.kwiki.wiki.api;

import com.kk2004.common.exception.NotFoundException;
import com.kwiki.infrastructure.redis.WikiStatisticsCache;
import com.kwiki.testutil.StandardTestProperties;
import com.kwiki.wiki.access.ResourceAuthorizationService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class WikiInteractionNotFoundTest {

    @Test
    void pageDeletedAfterAuthorizationIsReportedAsNotFound() {
        JdbcOperations jdbc = (JdbcOperations) Proxy.newProxyInstance(
                JdbcOperations.class.getClassLoader(), new Class<?>[]{JdbcOperations.class},
                (proxy, method, args) -> {
                    if ("queryForObject".equals(method.getName())) {
                        String sql = String.valueOf(args[0]);
                        if (sql.startsWith("SELECT version FROM stats_revision")) {
                            throw new EmptyResultDataAccessException(1);
                        }
                        if (sql.startsWith("SELECT COUNT(*) FROM wiki_page")) {
                            return 0;
                        }
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        WikiInteractionService service = new WikiInteractionService(
                StandardTestProperties.providerOf(jdbc),
                mock(ResourceAuthorizationService.class),
                StandardTestProperties.nullProvider(),
                new WikiStatisticsCache(null, null, null));

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                service, "currentStatsVersion", 7L))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("wiki不存在");
    }
}
