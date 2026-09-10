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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 针对运维方提供的 MySQL（KWIKI_IT_MYSQL_*）的租约并发契约：
 * 两次顺序领取绝不会共享同一任务，未过期的租约会阻止重复领取，
 * 已过期的租约会被确定性地重新领取。
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
        jdbc.execute("DELETE FROM indexing_job");
    }

    private static IndexingJobClaimer claimer() {
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(URL,
                        System.getenv().getOrDefault("KWIKI_IT_MYSQL_USERNAME", ""),
                        System.getenv().getOrDefault("KWIKI_IT_MYSQL_PASSWORD", ""));
        JdbcTemplate template = new JdbcTemplate(dataSource);
        return new IndexingJobClaimer(new org.springframework.beans.factory.ObjectProvider<>() {
            @Override
            public JdbcOperations getIfAvailable() {
                return template;
            }
        }, 300);
    }

    private static void enqueue(long id, String key) {
        jdbc.update("INSERT INTO indexing_job (id, job_type, resource_type, resource_id, "
                        + "idempotency_key, state) VALUES (?, 'UPSERT', 'PAGE', ?, ?, 'PENDING')",
                id, id, key);
    }

    @Test
    void concurrentClaimersNeverOwnTheSameLiveLease() {
        for (long id = 1; id <= 40; id++) {
            enqueue(id, "concurrent:" + id);
        }

        List<Long> first = claimer().claim("worker-a", 20);
        List<Long> second = claimer().claim("worker-b", 20);

        Set<Long> overlap = new HashSet<>(first);
        overlap.retainAll(new HashSet<>(second));
        assertThat(overlap).as("live leases must be exclusive").isEmpty();
        assertThat(first).hasSize(20);
        assertThat(second).hasSize(20);
    }

    @Test
    void unexpiredLeaseIsNotReclaimed() {
        enqueue(101L, "lease:101");
        List<Long> claimed = claimer().claim("worker-a", 1);
        assertThat(claimed).containsExactly(101L);

        List<Long> reclaimed = claimer().claim("worker-b", 5);
        assertThat(reclaimed).as("live lease must block others").doesNotContain(101L);
    }

    @Test
    void expiredLeaseIsReclaimedByAnotherWorker() {
        enqueue(201L, "lease:201");
        assertThat(claimer().claim("worker-a", 1)).containsExactly(201L);

        // 确定性地让租约过期
        jdbc.update("UPDATE indexing_job SET lease_expires_at = ? WHERE id = ?",
                Instant.now().minusSeconds(1), 201L);

        List<Long> reclaimed = claimer().claim("worker-b", 1);
        assertThat(reclaimed).as("expired lease must be reclaimable").contains(201L);
    }

    @Test
    void retryWaitJobIsClaimableOnlyAfterBackoffElapses() {
        jdbc.update("INSERT INTO indexing_job (job_type, resource_type, resource_id, "
                        + "idempotency_key, state, next_attempt_at) "
                        + "VALUES ('UPSERT', 'PAGE', 301, 'retry:301', 'RETRY_WAIT', ?)",
                Instant.now().plus(Duration.ofMinutes(5)));
        assertThat(claimer().claim("worker-a", 10)).doesNotContain(301L);
    }
}
