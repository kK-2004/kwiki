package com.kwiki.usage;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelUsageRepositoryQueryTest {
    final JdbcOperations jdbc = mock(JdbcOperations.class);

    @SuppressWarnings("unchecked")
    ModelUsageRepository repository() {
        ObjectProvider<JdbcOperations> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(jdbc);
        return new ModelUsageRepository(provider);
    }

    @Test
    @SuppressWarnings("unchecked")
    void seriesQueryBucketsWithTimestampDiffAndBindsFilters() {
        var sources = new LinkedHashSet<>(List.of(UsageSource.ONLINE, UsageSource.EVAL));
        var users = new LinkedHashSet<>(List.of(0L, 7L));

        repository().querySeries(Instant.parse("2026-10-20T16:00:00Z"), Instant.parse("2026-10-21T16:00:00Z"),
                sources, users, 300, 28_800);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), args.capture());
        assertThat(sql.getValue()).contains("TIMESTAMPDIFF(SECOND, '1970-01-01 00:00:00', bucket_start)")
                .doesNotContain("UNIX_TIMESTAMP").contains("source IN (?, ?)").contains("user_id IN (?, ?)")
                .contains("GROUP BY bucket_epoch, step, provider, model");
        assertThat(args.getValue()).containsExactly(28_800L, 300L, 300L, 28_800L,
                LocalDateTime.parse("2026-10-20T16:00:00"), LocalDateTime.parse("2026-10-21T16:00:00"),
                "ONLINE", "EVAL", 0L, 7L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void emptyFiltersMeanAll() {
        repository().querySeries(Instant.EPOCH, Instant.EPOCH.plusSeconds(60), Set.of(), Set.of(), 300, 0);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sql.getValue()).doesNotContain("source IN").doesNotContain("user_id IN");
    }

    @Test
    @SuppressWarnings("unchecked")
    void usersQueryJoinsDisplayNames() {
        repository().queryUsers(Instant.EPOCH, Instant.EPOCH.plusSeconds(60), Set.of(UsageSource.ONLINE));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sql.getValue()).contains("LEFT JOIN app_user").contains("display_name")
                .contains("ORDER BY total_tokens DESC");
    }
}
