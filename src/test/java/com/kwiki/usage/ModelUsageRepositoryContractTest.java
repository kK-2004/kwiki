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
        jdbc.update("DELETE FROM model_usage_minute");
        var bucket = Instant.parse("2026-10-21T01:02:00Z");
        var a = new MinuteAggregate(bucket, UsageSource.ONLINE, "ANSWER", "p", "m", 1, 1, 0, 10, 2, 5);
        repository().upsertMinutes(List.of(a));
        repository().upsertMinutes(List.of(a));

        assertThat(jdbc.queryForObject("SELECT input_tokens FROM model_usage_minute", Long.class)).isEqualTo(20L);
        assertThat(repository().deleteMinutesBefore(Instant.parse("2026-10-22T00:00:00Z"))).isEqualTo(1);
    }

    @Test
    void seriesBucketsAreAlignedToLocalDaysAndUsersJoinDisplayNames() {
        jdbc.update("DELETE FROM model_usage_minute");
        jdbc.update("DELETE FROM app_user WHERE id = 7");
        jdbc.update("INSERT INTO app_user (id, username, display_name) VALUES (7, 'zhangsan', '张三')");
        var repository = repository();
        repository.upsertMinutes(List.of(
                new MinuteAggregate(Instant.parse("2026-10-20T16:00:00Z"), UsageSource.ONLINE, "ANSWER", "p", "m",
                        0, 1, 0, 10, 1, 1),
                new MinuteAggregate(Instant.parse("2026-10-21T15:59:00Z"), UsageSource.ONLINE, "ANSWER", "p", "m",
                        0, 1, 0, 20, 1, 1),
                new MinuteAggregate(Instant.parse("2026-10-21T15:59:00Z"), UsageSource.EVAL, "ANSWER", "p", "m",
                        7, 1, 0, 5, 1, 1)));

        var rows = repository.querySeries(Instant.parse("2026-10-20T16:00:00Z"),
                Instant.parse("2026-10-21T16:00:00Z"), java.util.Set.of(), java.util.Set.of(), 86_400, 28_800);

        // 序列按（桶、步骤、provider、模型）汇总，来源与用户维度不参与分组
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().bucketEpochSeconds()).isEqualTo(Instant.parse("2026-10-20T16:00:00Z").getEpochSecond());
        assertThat(rows.getFirst().inputTokens()).isEqualTo(35);
        assertThat(rows.getFirst().calls()).isEqualTo(3);
        var users = repository.queryUsers(Instant.parse("2026-10-20T00:00:00Z"),
                Instant.parse("2026-10-22T00:00:00Z"), java.util.Set.of());
        assertThat(users).extracting(ModelUsageRepository.UserRow::userId).containsExactly(0L, 7L);
        assertThat(users.get(0).displayName()).isNull();
        assertThat(users.get(1).displayName()).isEqualTo("张三");
        // 来源过滤作用在聚合表上，评测来源的用量不计入线上口径
        assertThat(repository.queryUsers(Instant.parse("2026-10-20T00:00:00Z"),
                Instant.parse("2026-10-22T00:00:00Z"), java.util.Set.of(UsageSource.ONLINE)))
                .extracting(ModelUsageRepository.UserRow::userId).containsExactly(0L);
    }
}
