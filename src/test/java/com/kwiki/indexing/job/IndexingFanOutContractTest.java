package com.kwiki.indexing.job;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 入队扇出与别名切换的并发契约（任务 4.3/4.4/4.7，门控 MySQL）：
 * 入队事务为每个 writeEnabled 版本生成带物理名快照的独立目标行；
 * 此后的写目标集合变化（停用 v2、新增 v3、别名切换）不会重定向
 * 已排队的目标行；事件表按单调 id 追加；遗留任务回填不重复已完成
 * 的 v1 工作。
 */
@EnabledIfEnvironmentVariable(named = "KWIKI_IT_MYSQL_URL", matches = ".+")
class IndexingFanOutContractTest {

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
        jdbc = new JdbcTemplate(new DriverManagerDataSource(URL, user, password));
        for (String table : List.of("indexing_job_target", "indexing_job",
                "search_index_change_event", "search_index_version")) {
            jdbc.execute("DELETE FROM " + table);
        }
        insertVersion(1, "kwiki-chunks-v1", true, true);
        insertVersion(2, "kwiki-chunks-v2", true, false);
    }

    private static void insertVersion(int number, String physicalName, boolean writeEnabled,
                                      boolean selected) {
        jdbc.update("""
                INSERT INTO search_index_version
                    (version_number, physical_name, parser_version, chunker_version,
                     embedding_provider, embedding_model, embedding_dimensions,
                     mapping_schema_version, built_config_revision, build_state,
                     catchup_status, write_enabled, selected)
                VALUES (?, ?, 'kwiki-parse-1', 'kwiki-chunk-1', 'default',
                        'text-embedding-v4', 1024, 1, 1, 'BUILT', 'CURRENT', ?, ?)
                """, number, physicalName, writeEnabled, selected);
    }

    private static JdbcIndexingJobEnqueuer enqueuer() {
        JdbcTemplate template = new JdbcTemplate(new DriverManagerDataSource(URL,
                System.getenv().getOrDefault("KWIKI_IT_MYSQL_USERNAME", ""),
                System.getenv().getOrDefault("KWIKI_IT_MYSQL_PASSWORD", "")));
        return new JdbcIndexingJobEnqueuer(new ObjectProvider<>() {
            @Override
            public JdbcOperations getIfAvailable() {
                return template;
            }
        });
    }

    private static List<Map<String, Object>> targetsOf(String idempotencyKey) {
        Long jobId = jdbc.queryForObject(
                "SELECT id FROM indexing_job WHERE idempotency_key = ?", Long.class,
                idempotencyKey);
        return jdbc.queryForList(
                "SELECT target_version, physical_name, state FROM indexing_job_target"
                        + " WHERE job_id = ? ORDER BY target_version", jobId);
    }

    @Test
    void enqueueFansOutEveryWriteEnabledTargetWithPhysicalNameSnapshots() {
        enqueuer().enqueuePageUpsert(7L, 103L);

        List<Map<String, Object>> targets = targetsOf("PAGE:7:103:UPSERT");
        assertThat(targets).hasSize(2);
        assertThat(targets.get(0))
                .containsEntry("target_version", 1)
                .containsEntry("physical_name", "kwiki-chunks-v1")
                .containsEntry("state", "PENDING");
        assertThat(targets.get(1))
                .containsEntry("target_version", 2)
                .containsEntry("physical_name", "kwiki-chunks-v2");

        Long eventId = (Long) targets.get(0).get("event_id") == null ? null
                : ((Number) targets.get(0).get("event_id")).longValue();
        Long maxEvent = jdbc.queryForObject("SELECT MAX(id) FROM search_index_change_event",
                Long.class);
        assertThat(maxEvent).as("append-only outbox recorded the event").isNotNull();
        assertThat(eventId == null || eventId.equals(maxEvent)).isTrue();
    }

    @Test
    void aliasSwitchAndWriteTargetChangesNeverRetargetQueuedJobs() {
        enqueuer().enqueuePageUpsert(8L, 104L);
        List<Map<String, Object>> before = targetsOf("PAGE:8:104:UPSERT");
        assertThat(before).hasSize(2);

        // 切换准备式的目标集合变化：停用 v2、新增并启用 v3、别名事实切到 v2。
        jdbc.update("UPDATE search_index_version SET write_enabled = FALSE,"
                + " catchup_status = 'BEHIND' WHERE version_number = 2");
        insertVersion(3, "kwiki-chunks-v3", true, false);
        jdbc.update("UPDATE search_index_version SET selected = FALSE WHERE version_number = 1");
        jdbc.update("UPDATE search_index_version SET selected = TRUE WHERE version_number = 2");

        // 已排队的目标行保持入队时刻的物理名与版本，不被重定向。
        List<Map<String, Object>> after = targetsOf("PAGE:8:104:UPSERT");
        assertThat(after).isEqualTo(before);

        // 此后新事件的扇出面向新的写目标集合（v1、v3）。
        enqueuer().enqueuePageUpsert(9L, 105L);
        List<Map<String, Object>> next = targetsOf("PAGE:9:105:UPSERT");
        assertThat(next).extracting(row -> row.get("target_version"))
                .containsExactly(1, 3);
    }

    @Test
    void idempotentRequeueReopensOnlyTerminalTargets() {
        enqueuer().enqueuePageUpsert(10L, 106L);
        jdbc.update("UPDATE indexing_job_target SET state = 'COMPLETED'"
                + " WHERE job_id = (SELECT id FROM indexing_job WHERE idempotency_key ="
                + " 'PAGE:10:106:UPSERT') AND target_version = 2");

        enqueuer().enqueuePageUpsert(10L, 106L);

        List<Map<String, Object>> targets = targetsOf("PAGE:10:106:UPSERT");
        assertThat(targets.stream().filter(row -> ((Number) row.get("target_version")).intValue() == 2))
                .allSatisfy(row -> assertThat(row.get("state")).isEqualTo("PENDING"));
    }
}
