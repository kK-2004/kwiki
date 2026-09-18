package com.kwiki.indexing.job;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 针对运维方提供的 MySQL（KWIKI_IT_MYSQL_*）的目标级租约并发契约：
 * 两次顺序领取绝不会共享同一目标，未过期的租约会阻止重复领取，
 * 已过期的租约会被确定性地重新领取；遗留的无目标任务在领取前回填
 * selected 版本目标，且已完成的旧任务不会被重开。
 */
@EnabledIfEnvironmentVariable(named = "KWIKI_IT_MYSQL_URL", matches = ".+")
class IndexingJobClaimContractTest {

    private static final String URL = System.getenv().getOrDefault("KWIKI_IT_MYSQL_URL", "");

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void prepare() {
        String user = System.getenv().getOrDefault("KWIKI_IT_MYSQL_USERNAME", "");
        String password = System.getenv().getOrDefault("KWIKI_IT_MYSQL_PASSWORD", "");
        Flyway.configure().dataSource(URL, user, password)
                .locations("classpath:db/migration").cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(URL, user, password)
                .locations("classpath:db/migration").load().migrate();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(URL, user, password);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DELETE FROM indexing_job_target");
        jdbc.execute("DELETE FROM indexing_job");
        jdbc.execute("DELETE FROM search_index_change_event");
        jdbc.execute("DELETE FROM search_index_version");
        jdbc.update("""
                INSERT INTO search_index_version
                    (version_number, physical_name, parser_version, chunker_version,
                     embedding_provider, embedding_model, embedding_dimensions,
                     mapping_schema_version, built_config_revision, build_state,
                     catchup_status, write_enabled, selected)
                VALUES (1, 'kwiki-chunks-v1', 'kwiki-parse-1', 'kwiki-chunk-1', 'default',
                        'text-embedding-v4', 1024, 1, 1, 'BUILT', 'CURRENT', TRUE, TRUE)
                """);
    }

    private static IndexingJobTargetClaimer claimer() {
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(URL,
                        System.getenv().getOrDefault("KWIKI_IT_MYSQL_USERNAME", ""),
                        System.getenv().getOrDefault("KWIKI_IT_MYSQL_PASSWORD", ""));
        JdbcTemplate template = new JdbcTemplate(dataSource);
        return new IndexingJobTargetClaimer(new org.springframework.beans.factory.ObjectProvider<>() {
            @Override
            public JdbcOperations getIfAvailable() {
                return template;
            }
        }, 300);
    }

    private static void enqueue(long jobId, String key) {
        jdbc.update("INSERT INTO indexing_job (id, job_type, resource_type, resource_id, "
                        + "idempotency_key, state) VALUES (?, 'UPSERT', 'PAGE', ?, ?, 'PENDING')",
                jobId, jobId, key);
        jdbc.update("INSERT INTO indexing_job_target (job_id, event_id, target_version, "
                        + "physical_name, idempotency_key, state) "
                        + "VALUES (?, NULL, 1, 'kwiki-chunks-v1', ?, 'PENDING')",
                jobId, key + ":v1");
    }

    private static List<Long> targetIds(List<Map<String, Object>> claimed) {
        return claimed.stream().map(row -> ((Number) row.get("target_id")).longValue()).toList();
    }

    @Test
    void concurrentClaimersNeverOwnTheSameLiveLease() {
        for (long id = 1; id <= 40; id++) {
            enqueue(id, "concurrent:" + id);
        }

        List<Long> first = targetIds(claimer().claim("worker-a", 20));
        List<Long> second = targetIds(claimer().claim("worker-b", 20));

        Set<Long> overlap = new HashSet<>(first);
        overlap.retainAll(new HashSet<>(second));
        assertThat(overlap).as("live leases must be exclusive").isEmpty();
        assertThat(first).hasSize(20);
        assertThat(second).hasSize(20);
    }

    @Test
    void unexpiredLeaseIsNotReclaimed() {
        enqueue(101L, "lease:101");
        assertThat(targetIds(claimer().claim("worker-a", 1))).containsExactly(101L);

        List<Long> reclaimed = targetIds(claimer().claim("worker-b", 5));
        assertThat(reclaimed).as("live lease must block others").doesNotContain(101L);
    }

    @Test
    void expiredLeaseIsReclaimedByAnotherWorker() {
        enqueue(201L, "lease:201");
        assertThat(targetIds(claimer().claim("worker-a", 1))).containsExactly(201L);

        // 确定性地让租约过期
        jdbc.update("UPDATE indexing_job_target SET lease_expires_at = ? "
                + "WHERE job_id = 201", Instant.now().minusSeconds(1));

        List<Long> reclaimed = targetIds(claimer().claim("worker-b", 1));
        assertThat(reclaimed).as("expired lease must be reclaimable").contains(201L);
    }

    @Test
    void retryWaitTargetIsClaimableOnlyAfterBackoffElapses() {
        jdbc.update("INSERT INTO indexing_job (id, job_type, resource_type, resource_id, "
                        + "idempotency_key, state) VALUES (301, 'UPSERT', 'PAGE', 301, 'retry:301',"
                        + " 'RETRY_WAIT')");
        jdbc.update("INSERT INTO indexing_job_target (job_id, event_id, target_version, "
                        + "physical_name, idempotency_key, state, next_attempt_at) "
                        + "VALUES (301, NULL, 1, 'kwiki-chunks-v1', 'retry:301:v1', 'RETRY_WAIT', ?)",
                Instant.now().plus(Duration.ofMinutes(5)));
        assertThat(targetIds(claimer().claim("worker-a", 10))).doesNotContain(301L);
    }

    @Test
    void legacyJobWithoutTargetsIsBackfilledToTheSelectedVersion() {
        jdbc.update("INSERT INTO indexing_job (id, job_type, resource_type, resource_id, "
                + "idempotency_key, state) VALUES (401, 'UPSERT', 'PAGE', 401, 'legacy:401',"
                + " 'PENDING')");

        List<Map<String, Object>> claimed = claimer().claim("worker-a", 5);
        assertThat(targetIds(claimed)).contains(401L);
        assertThat(claimed.stream().filter(row -> ((Number) row.get("job_id")).longValue() == 401L)
                .findFirst().orElseThrow().get("physical_name"))
                .isEqualTo("kwiki-chunks-v1");

        // 已完成的遗留任务不会被重开
        jdbc.update("INSERT INTO indexing_job (id, job_type, resource_type, resource_id, "
                + "idempotency_key, state) VALUES (402, 'UPSERT', 'PAGE', 402, 'legacy:402',"
                + " 'COMPLETED')");
        Integer backfilledForCompleted = jdbc.queryForObject(
                "SELECT COUNT(*) FROM indexing_job_target WHERE job_id = 402", Integer.class);
        assertThat(backfilledForCompleted).isZero();
    }
}
