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
 * Schema constraint contract for V1. Runs only against an operator-provided MySQL
 * (KWIKI_IT_MYSQL_URL/USER/PASSWORD), keeping the default build Docker-free and
 * connection-free. Verifies unique keys, role CHECK constraints, and FK integrity.
 */
@EnabledIfEnvironmentVariable(named = "KWIKI_IT_MYSQL_URL", matches = ".+")
class V1MigrationContractTest {

    private static final String URL = env("KWIKI_IT_MYSQL_URL");

    private static String env(String key) {
        String value = System.getenv(key);
        return value == null ? "" : value;
    }

    @BeforeAll
    static void migrateSchema() {
        Flyway.configure()
                .dataSource(URL, env("KWIKI_IT_MYSQL_USERNAME"), env("KWIKI_IT_MYSQL_PASSWORD"))
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load()
                .clean();
        Flyway.configure()
                .dataSource(URL, env("KWIKI_IT_MYSQL_USERNAME"), env("KWIKI_IT_MYSQL_PASSWORD"))
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    private static Connection open() throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", env("KWIKI_IT_MYSQL_USERNAME"));
        props.setProperty("password", env("KWIKI_IT_MYSQL_PASSWORD"));
        return DriverManager.getConnection(URL, props);
    }

    private static long insertUser(Connection c, String username) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO app_user (username, display_name) VALUES (?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, username);
            ps.setString(2, username);
            ps.executeUpdate();
            var keys = ps.getGeneratedKeys();
            keys.next();
            return keys.getLong(1);
        }
    }

    private static long insertKnowledgeBase(Connection c, String name, long creator) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO knowledge_base (uuid, name, created_by) VALUES (?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, java.util.UUID.randomUUID().toString());
            ps.setString(2, name);
            ps.setLong(3, creator);
            ps.executeUpdate();
            var keys = ps.getGeneratedKeys();
            keys.next();
            return keys.getLong(1);
        }
    }

    @Test
    void duplicateUsernamesAreRejected() throws Exception {
        try (Connection c = open()) {
            insertUser(c, "dup-user");
            assertThatThrownBy(() -> insertUser(c, "dup-user"))
                    .isInstanceOf(SQLIntegrityConstraintViolationException.class);
        }
    }

    @Test
    void invalidKnowledgeBaseStatusIsRejected() throws Exception {
        try (Connection c = open()) {
            long creator = insertUser(c, "status-user");
            assertThatThrownBy(() -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO knowledge_base (uuid, name, status, created_by) VALUES (?, ?, ?, ?)")) {
                    ps.setString(1, java.util.UUID.randomUUID().toString());
                    ps.setString(2, "bad status kb");
                    ps.setString(3, "DELETED");
                    ps.setLong(4, creator);
                    ps.executeUpdate();
                }
            }).isInstanceOf(SQLIntegrityConstraintViolationException.class)
                    .hasMessageContaining("ck_knowledge_base_status");
        }
    }

    @Test
    void memberRoleIsRestrictedAndMembershipIsUnique() throws Exception {
        try (Connection c = open()) {
            long owner = insertUser(c, "member-owner");
            long member = insertUser(c, "member-user");
            long kb = insertKnowledgeBase(c, "member kb", owner);

            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO knowledge_base_member (kb_id, user_id, role, created_by) VALUES (?, ?, ?, ?)")) {
                ps.setLong(1, kb);
                ps.setLong(2, member);
                ps.setString(3, "EDITOR");
                ps.setLong(4, owner);
                ps.executeUpdate();
            }

            // duplicate membership rejected
            assertThatThrownBy(() -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO knowledge_base_member (kb_id, user_id, role, created_by) VALUES (?, ?, ?, ?)")) {
                    ps.setLong(1, kb);
                    ps.setLong(2, member);
                    ps.setString(3, "VIEWER");
                    ps.setLong(4, owner);
                    ps.executeUpdate();
                }
            }).isInstanceOf(SQLIntegrityConstraintViolationException.class);

            // invalid role rejected
            assertThatThrownBy(() -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO knowledge_base_member (kb_id, user_id, role, created_by) VALUES (?, ?, ?, ?)")) {
                    ps.setLong(1, kb);
                    ps.setLong(2, owner);
                    ps.setString(3, "ADMIN");
                    ps.setLong(4, owner);
                    ps.executeUpdate();
                }
            }).isInstanceOf(SQLIntegrityConstraintViolationException.class)
                    .hasMessageContaining("ck_kb_member_role");
        }
    }

    @Test
    void memberRequiresExistingKnowledgeBaseAndUser() throws Exception {
        try (Connection c = open()) {
            long owner = insertUser(c, "fk-owner");
            assertThatThrownBy(() -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO knowledge_base_member (kb_id, user_id, role, created_by) VALUES (?, ?, ?, ?)")) {
                    ps.setLong(1, 9_999_999L);
                    ps.setLong(2, owner);
                    ps.setString(3, "VIEWER");
                    ps.setLong(4, owner);
                    ps.executeUpdate();
                }
            }).isInstanceOf(SQLIntegrityConstraintViolationException.class);
        }
    }

    @Test
    void auditAndOptimisticLockColumnsHaveDefaults() throws Exception {
        try (Connection c = open()) {
            long creator = insertUser(c, "defaults-user");
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT created_at, updated_at, lock_version FROM app_user WHERE id = ?")) {
                ps.setLong(1, creator);
                var rs = ps.executeQuery();
                assertThat(rs.next()).isTrue();
                assertThat(rs.getTimestamp("created_at")).isNotNull();
                assertThat(rs.getTimestamp("updated_at")).isNotNull();
                assertThat(rs.getLong("lock_version")).isZero();
            }
        }
    }
}
