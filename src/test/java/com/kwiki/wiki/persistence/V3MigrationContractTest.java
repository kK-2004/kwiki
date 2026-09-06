package com.kwiki.wiki.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Schema constraint contract for V3 against an operator-provided MySQL
 * (KWIKI_IT_MYSQL_URL/USER/PASSWORD). Verifies idempotency-key uniqueness
 * (duplicate job rejection), state/attempt CHECK constraints, lease field
 * defaults, and monotonic scope-version rows.
 */
@EnabledIfEnvironmentVariable(named = "KWIKI_IT_MYSQL_URL", matches = ".+")
class V3MigrationContractTest {

    private static final String URL = System.getenv().getOrDefault("KWIKI_IT_MYSQL_URL", "");

    @BeforeAll
    static void migrateSchema() {
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

    private static void insertJob(Connection c, String idempotencyKey, String state)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO indexing_job (job_type, resource_type, resource_id, idempotency_key, state) "
                        + "VALUES ('UPSERT', 'PAGE', 1, ?, ?)")) {
            ps.setString(1, idempotencyKey);
            ps.setString(2, state);
            ps.executeUpdate();
        }
    }

    @Test
    void duplicateIdempotencyKeysAreRejected() throws Exception {
        try (Connection c = open()) {
            insertJob(c, "PAGE:1:2:UPSERT", "PENDING");
            assertThatThrownBy(() -> insertJob(c, "PAGE:1:2:UPSERT", "PENDING"))
                    .isInstanceOf(SQLIntegrityConstraintViolationException.class)
                    .hasMessageContaining("uk_indexing_job_idempotency");
        }
    }

    @Test
    void invalidJobStateIsRejected() throws Exception {
        try (Connection c = open()) {
            assertThatThrownBy(() -> insertJob(c, "unique-key-state", "RUNNING"))
                    .isInstanceOf(SQLIntegrityConstraintViolationException.class)
                    .hasMessageContaining("ck_indexing_job_state");
        }
    }

    @Test
    void leaseAndRetryFieldsStartNullableWithDefaults() throws Exception {
        try (Connection c = open()) {
            insertJob(c, "unique-key-lease", "PENDING");
            try (Statement st = c.createStatement();
                 var rs = st.executeQuery(
                         "SELECT attempts, max_attempts, lease_owner, lease_expires_at, next_attempt_at "
                                 + "FROM indexing_job WHERE idempotency_key = 'unique-key-lease'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt("attempts")).isZero();
                assertThat(rs.getInt("max_attempts")).isEqualTo(8);
                assertThat(rs.getString("lease_owner")).isNull();
                assertThat(rs.getTimestamp("lease_expires_at")).isNull();
                assertThat(rs.getTimestamp("next_attempt_at")).isNull();
            }
        }
    }

    @Test
    void scopeVersionRowIsUniquePerKnowledgeBaseAndMonotonic() throws Exception {
        try (Connection c = open()) {
            long user;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO app_user (username, display_name) VALUES (?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, "scope-user");
                ps.setString(2, "scope-user");
                ps.executeUpdate();
                var keys = ps.getGeneratedKeys();
                keys.next();
                user = keys.getLong(1);
            }
            long kb;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO knowledge_base (uuid, name, created_by) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, java.util.UUID.randomUUID().toString());
                ps.setString(2, "scope kb");
                ps.setLong(3, user);
                ps.executeUpdate();
                var keys = ps.getGeneratedKeys();
                keys.next();
                kb = keys.getLong(1);
            }

            try (PreparedStatement insert = c.prepareStatement(
                    "INSERT INTO scope_version (kb_id) VALUES (?)")) {
                insert.setLong(1, kb);
                insert.executeUpdate();
            }
            try (PreparedStatement duplicate = c.prepareStatement(
                    "INSERT INTO scope_version (kb_id) VALUES (?)")) {
                duplicate.setLong(1, kb);
                assertThatThrownBy(duplicate::executeUpdate)
                        .isInstanceOf(SQLIntegrityConstraintViolationException.class);
            }
            try (Statement st = c.createStatement();
                 var rs = st.executeQuery("SELECT version FROM scope_version WHERE kb_id = " + kb)) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong("version")).isEqualTo(1);
            }
            // decrementing a scope version is structurally rejected
            try (PreparedStatement bad = c.prepareStatement(
                    "UPDATE scope_version SET version = 0 WHERE kb_id = ?")) {
                bad.setLong(1, kb);
                assertThatThrownBy(bad::executeUpdate)
                        .isInstanceOf(SQLIntegrityConstraintViolationException.class);
            }
        }
    }
}
