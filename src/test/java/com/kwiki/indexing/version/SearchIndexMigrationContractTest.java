package com.kwiki.indexing.version;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V21–V24 契约：从干净 schema 全量迁移成功；版本表的单选/单调约束、
 * 活动重建 run 的唯一约束与 CHECK 语义真实生效。需要
 * KWIKI_IT_MYSQL_URL（与其它迁移契约测试相同的门控），离线构建跳过。
 */
@EnabledIfEnvironmentVariable(named = "KWIKI_IT_MYSQL_URL", matches = ".+")
class SearchIndexMigrationContractTest {

    private static final String URL = System.getenv().getOrDefault("KWIKI_IT_MYSQL_URL", "");

    @BeforeAll
    static void migrateFromCleanSchema() {
        String user = System.getenv().getOrDefault("KWIKI_IT_MYSQL_USERNAME", "");
        String password = System.getenv().getOrDefault("KWIKI_IT_MYSQL_PASSWORD", "");
        Flyway.configure().dataSource(URL, user, password)
                .locations("classpath:db/migration").cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(URL, user, password)
                .locations("classpath:db/migration").load().migrate();
    }

    private static Connection open() throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", System.getenv().getOrDefault("KWIKI_IT_MYSQL_USERNAME", ""));
        props.setProperty("password", System.getenv().getOrDefault("KWIKI_IT_MYSQL_PASSWORD", ""));
        return DriverManager.getConnection(URL, props);
    }

    private static void insertVersion(Connection c, int number, boolean selected)
            throws SQLException {
        try (var ps = c.prepareStatement(
                "INSERT INTO search_index_version (version_number, physical_name, parser_version,"
                        + " chunker_version, embedding_provider, embedding_model, embedding_dimensions,"
                        + " mapping_schema_version, selected) VALUES (?, ?, 'kwiki-parse-1',"
                        + " 'kwiki-chunk-1', 'default', 'text-embedding-v4', 1024, 1, ?)")) {
            ps.setInt(1, number);
            ps.setString(2, "kwiki-chunks-v" + number);
            ps.setBoolean(3, selected);
            ps.executeUpdate();
        }
    }

    @Test
    void versionNumbersAndPhysicalNamesAreUniqueAndMonotonicCandidatesAccepted() throws Exception {
        try (Connection c = open()) {
            insertVersion(c, 1, true);
            insertVersion(c, 2, false);

            assertThatThrownBy(() -> insertVersion(c, 2, false))
                    .hasMessageContaining("Duplicate");
            assertThatThrownBy(() -> {
                try (var ps = c.prepareStatement(
                        "INSERT INTO search_index_version (version_number, physical_name,"
                                + " parser_version, chunker_version, embedding_provider,"
                                + " embedding_model, embedding_dimensions, mapping_schema_version)"
                                + " VALUES (2, 'kwiki-chunks-v2-dup', 'kwiki-parse-1',"
                                + " 'kwiki-chunk-1', 'default', 'text-embedding-v4', 1024, 1)")) {
                    ps.executeUpdate();
                }
            }).hasMessageContaining("Duplicate");

            // 同一时刻至多一个 selected（生成列唯一约束）。
            assertThatThrownBy(() -> {
                try (var ps = c.prepareStatement(
                        "UPDATE search_index_version SET selected = TRUE WHERE version_number = 2")) {
                    ps.executeUpdate();
                }
            }).hasMessageContaining("Duplicate");
        }
    }

    @Test
    void revisionOrderAndStateChecksAreEnforced() throws Exception {
        try (Connection c = open()) {
            insertVersion(c, 3, false);
            // built_config_revision 不得超过 config_revision。
            assertThatThrownBy(() -> {
                try (var ps = c.prepareStatement(
                        "UPDATE search_index_version SET built_config_revision = 5"
                                + " WHERE version_number = 3")) {
                    ps.executeUpdate();
                }
            }).hasMessageContaining("Check constraint");
        }
    }

    @Test
    void atMostOneActiveRebuildRunPerVersion() throws Exception {
        try (Connection c = open()) {
            insertVersion(c, 4, false);
            insertRun(c, 4, "RUNNING");
            assertThatThrownBy(() -> insertRun(c, 4, "PENDING"))
                    .hasMessageContaining("Duplicate");

            // 终态释放活动资格：同一版本可以再开新 run。
            try (var ps = c.prepareStatement(
                    "UPDATE search_index_rebuild_run SET state = 'COMPLETED', completed_at = NOW(6)"
                            + " WHERE version_number = 4")) {
                ps.executeUpdate();
            }
            insertRun(c, 4, "RUNNING");
        }
    }

    private static void insertRun(Connection c, long versionNumber, String state)
            throws SQLException {
        try (var ps = c.prepareStatement(
                "INSERT INTO search_index_rebuild_run (version_number, run_kind, config_revision,"
                        + " build_manifest, state, requested_by) VALUES (?, 'INITIAL', 1, '{}', ?, 'admin')")) {
            ps.setLong(1, versionNumber);
            ps.setString(2, state);
            ps.executeUpdate();
        }
    }
}
