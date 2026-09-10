package com.kwiki.wiki.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V4 针对运维方提供的一次性 MySQL 的表结构契约
 * （KWIKI_IT_MYSQL_URL/USER/PASSWORD）：MinIO 定位符列已移除、pending
 * 记录保持 file id 为 null、非空的内容中心 file id 唯一，且已存储
 * 记录可往返。仅通过显式开关启用；绝不指向共享数据库（BeforeAll
 * 会清理它所运行的表结构）。
 */
@EnabledIfEnvironmentVariable(named = "KWIKI_IT_MYSQL_URL", matches = ".+")
class V4MigrationContractTest {

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

    private static long insert(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.executeUpdate();
            var keys = ps.getGeneratedKeys();
            keys.next();
            return keys.getLong(1);
        }
    }

    private static long freshKnowledgeBase(Connection c, String label) throws SQLException {
        long user = insert(c, "INSERT INTO app_user (username, display_name) VALUES (?, ?)",
                label + "-user", label);
        return insert(c,
                "INSERT INTO knowledge_base (uuid, name, created_by) VALUES (?, ?, ?)",
                java.util.UUID.randomUUID().toString(), label + "-kb", user);
    }

    private static boolean columnExists(Connection c, String tableName, String columnName)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?")) {
            ps.setString(1, tableName);
            ps.setString(2, columnName);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1) > 0;
            }
        }
    }

    @Test
    void obsoleteObjectKeyColumnIsRemovedAndFileIdColumnIsNullable() throws Exception {
        try (Connection c = open()) {
            assertThat(columnExists(c, "attachment", "object_key"))
                    .as("object_key must be dropped by V4").isFalse();
            assertThat(columnExists(c, "attachment", "content_center_file_id"))
                    .as("content_center_file_id must exist").isTrue();

            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT IS_NULLABLE FROM information_schema.columns "
                         + "WHERE table_schema = DATABASE() AND table_name = 'attachment' "
                         + "AND column_name = 'content_center_file_id'")) {
                rs.next();
                assertThat(rs.getString(1)).as("pending rows need null file ids").isEqualTo("YES");
            }
        }
    }

    @Test
    void pendingRowsAcceptNullFileIds() throws Exception {
        try (Connection c = open()) {
            long kb = freshKnowledgeBase(c, "v4-pending");
            long creator = queryLong(c, "SELECT created_by FROM knowledge_base WHERE id = " + kb);
            String insert = "INSERT INTO attachment (uuid, kb_id, uploaded_by, file_name, "
                    + "content_type, byte_size, status) VALUES (?, ?, ?, ?, ?, ?, 'PENDING')";
            long first = insert(c, insert, java.util.UUID.randomUUID().toString(), kb, creator,
                    "pending-1.docx",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document", 10);
            long second = insert(c, insert, java.util.UUID.randomUUID().toString(), kb, creator,
                    "pending-2.docx",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document", 10);
            assertThat(first).isPositive();
            assertThat(second).isPositive();
        }
    }

    @Test
    void duplicateNonNullFileIdsAreRejected() throws Exception {
        try (Connection c = open()) {
            long kb = freshKnowledgeBase(c, "v4-unique");
            long creator = queryLong(c, "SELECT created_by FROM knowledge_base WHERE id = " + kb);
            String insert = "INSERT INTO attachment (uuid, kb_id, uploaded_by, file_name, "
                    + "content_type, byte_size, status, content_center_file_id) "
                    + "VALUES (?, ?, ?, ?, ?, ?, 'STORED', ?)";
            insert(c, insert, java.util.UUID.randomUUID().toString(), kb, creator,
                    "first.docx",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    10, 1001L);
            assertThatThrownBy(() -> insert(c, insert,
                    java.util.UUID.randomUUID().toString(), kb, creator,
                    "second.docx",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    10, 1001L))
                    .isInstanceOf(SQLIntegrityConstraintViolationException.class);
            // 不同的 file id 可以被接受
            insert(c, insert, java.util.UUID.randomUUID().toString(), kb, creator,
                    "third.docx",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    10, 1002L);
        }
    }

    @Test
    void storedRowsRoundTripWithFileId() throws Exception {
        try (Connection c = open()) {
            long kb = freshKnowledgeBase(c, "v4-roundtrip");
            long creator = queryLong(c, "SELECT created_by FROM knowledge_base WHERE id = " + kb);
            long id = insert(c, "INSERT INTO attachment (uuid, kb_id, uploaded_by, file_name, "
                            + "content_type, byte_size, status, content_center_file_id) "
                            + "VALUES (?, ?, ?, ?, ?, ?, 'STORED', ?)",
                    java.util.UUID.randomUUID().toString(), kb, creator, "roundtrip.docx",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    2048, 2002L);
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT content_center_file_id, status, byte_size FROM attachment "
                                 + "WHERE id = " + id)) {
                rs.next();
                assertThat(rs.getLong(1)).isEqualTo(2002L);
                assertThat(rs.wasNull()).isFalse();
                assertThat(rs.getString(2)).isEqualTo("STORED");
                assertThat(rs.getLong(3)).isEqualTo(2048L);
            }
        }
    }

    private static long queryLong(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); var rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
