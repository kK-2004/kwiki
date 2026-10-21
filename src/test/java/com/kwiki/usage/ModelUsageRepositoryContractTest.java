package com.kwiki.usage;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 针对运维方提供的 MySQL（KWIKI_IT_MYSQL_URL/USERNAME/PASSWORD）验证 upsert 累加与清理。 */
@EnabledIfEnvironmentVariable(named = "KWIKI_IT_MYSQL_URL", matches = ".+")
class ModelUsageRepositoryContractTest {

    static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        String url = System.getenv("KWIKI_IT_MYSQL_URL");
        String user = System.getenv().getOrDefault("KWIKI_IT_MYSQL_USERNAME", "");
        String password = System.getenv().getOrDefault("KWIKI_IT_MYSQL_PASSWORD", "");
        Flyway.configure().dataSource(url, user, password).locations("classpath:db/migration")
                .cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(url, user, password).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
    }

    @SuppressWarnings("unchecked")
    static ModelUsageRepository repository() {
        ObjectProvider<JdbcOperations> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(jdbc);
        return new ModelUsageRepository(provider);
    }

    @Test
    void upsertAccumulatesAndRetentionDeletes() {
        var bucket = Instant.parse("2026-10-21T01:02:00Z");
        var a = new MinuteAggregate(bucket, UsageSource.ONLINE, "ANSWER", "p", "m", 1, 1, 0, 10, 2, 5);
        repository().upsertMinutes(List.of(a));
        repository().upsertMinutes(List.of(a));

        assertThat(jdbc.queryForObject("SELECT input_tokens FROM model_usage_minute", Long.class)).isEqualTo(20L);
        assertThat(repository().deleteMinutesBefore(Instant.parse("2026-10-22T00:00:00Z"))).isEqualTo(1);
    }
}
