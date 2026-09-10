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
 * V2 针对运维方提供的 MySQL 的表结构约束契约
 * （KWIKI_IT_MYSQL_URL/USER/PASSWORD）。校验修订版本唯一性、外键完整性、
 * 链接唯一性、标签作用域，以及附件 object-key 唯一性。
 */
@EnabledIfEnvironmentVariable(named = "KWIKI_IT_MYSQL_URL", matches = ".+")
class V2MigrationContractTest {

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

    private static long insertPage(Connection c, long kb, long creator, Long parentId,
                                   String title) throws SQLException {
        return insert(c, "INSERT INTO wiki_page (uuid, kb_id, parent_id, title, created_by) "
                        + "VALUES (?, ?, ?, ?, ?)",
                java.util.UUID.randomUUID().toString(), kb, parentId, title, creator);
    }

    private static long insertRevision(Connection c, long page, long author, int revisionNo)
            throws SQLException {
        return insert(c, "INSERT INTO wiki_page_revision (page_id, revision_no, markdown, "
                        + "plain_text, created_by) VALUES (?, ?, ?, ?, ?)",
                page, revisionNo, "# content " + revisionNo, "content " + revisionNo, author);
    }

    @Test
    void revisionNumbersAreUniquePerPageAndStartAtOne() throws Exception {
        try (Connection c = open()) {
            long kb = freshKnowledgeBase(c, "revision-unique");
            long creator = queryLong(c, "SELECT created_by FROM knowledge_base WHERE id = " + kb);
            long page = insertPage(c, kb, creator, null, "revision page");
            insertRevision(c, page, creator, 1);
            insertRevision(c, page, creator, 2);
            assertThatThrownBy(() -> insertRevision(c, page, creator, 2))
                    .isInstanceOf(SQLIntegrityConstraintViolationException.class);
            assertThatThrownBy(() -> insertRevision(c, page, creator, 0))
                    .isInstanceOf(SQLIntegrityConstraintViolationException.class)
                    .hasMessageContaining("ck_page_revision_no");
        }
    }

    @Test
    void pageParentMustReferenceExistingPage() throws Exception {
        try (Connection c = open()) {
            long kb = freshKnowledgeBase(c, "parent-fk");
            long creator = queryLong(c, "SELECT created_by FROM knowledge_base WHERE id = " + kb);
            assertThatThrownBy(() -> insertPage(c, kb, creator, 9_999_999L, "orphan child"))
                    .isInstanceOf(SQLIntegrityConstraintViolationException.class);
        }
    }

    @Test
    void linksAreUniqueAndSelfLinksRejected() throws Exception {
        try (Connection c = open()) {
            long kb = freshKnowledgeBase(c, "link-unique");
            long creator = queryLong(c, "SELECT created_by FROM knowledge_base WHERE id = " + kb);
            long source = insertPage(c, kb, creator, null, "link source");
            long target = insertPage(c, kb, creator, null, "link target");

            insert(c, "INSERT INTO wiki_link (source_page_id, target_page_id) VALUES (?, ?)",
                    source, target);
            assertThatThrownBy(() -> insert(c,
                    "INSERT INTO wiki_link (source_page_id, target_page_id) VALUES (?, ?)",
                    source, target))
                    .isInstanceOf(SQLIntegrityConstraintViolationException.class);
            assertThatThrownBy(() -> insert(c,
                    "INSERT INTO wiki_link (source_page_id, target_page_id) VALUES (?, ?)",
                    source, source))
                    .isInstanceOf(SQLIntegrityConstraintViolationException.class)
                    .hasMessageContaining("ck_wiki_link_no_self");
        }
    }

    @Test
    void tagsAreUniquePerKnowledgeBaseButSharedAcrossKbs() throws Exception {
        try (Connection c = open()) {
            long kb1 = freshKnowledgeBase(c, "tag-kb1");
            long kb2 = freshKnowledgeBase(c, "tag-kb2");
            insert(c, "INSERT INTO wiki_tag (kb_id, name) VALUES (?, ?)", kb1, "architecture");
            insert(c, "INSERT INTO wiki_tag (kb_id, name) VALUES (?, ?)", kb2, "architecture");
            assertThatThrownBy(() -> insert(c, "INSERT INTO wiki_tag (kb_id, name) VALUES (?, ?)",
                    kb1, "architecture"))
                    .isInstanceOf(SQLIntegrityConstraintViolationException.class);
        }
    }

    @Test
    void attachmentRowsWithoutFileIdsAreAccepted() throws Exception {
        try (Connection c = open()) {
            long kb = freshKnowledgeBase(c, "attachment-pending");
            long creator = queryLong(c, "SELECT created_by FROM knowledge_base WHERE id = " + kb);
            long first = insert(c, "INSERT INTO attachment (uuid, kb_id, uploaded_by, file_name, "
                            + "content_type, byte_size) VALUES (?, ?, ?, ?, ?, ?)",
                    java.util.UUID.randomUUID().toString(), kb, creator,
                    "spec.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    1024);
            long second = insert(c, "INSERT INTO attachment (uuid, kb_id, uploaded_by, file_name, "
                            + "content_type, byte_size) VALUES (?, ?, ?, ?, ?, ?)",
                    java.util.UUID.randomUUID().toString(), kb, creator,
                    "other.docx", "application/octet-stream", 512);
            assertThat(first).isPositive();
            assertThat(second).isPositive();
        }
    }

    @Test
    void sourceDocumentPairsAreUnique() throws Exception {
        try (Connection c = open()) {
            long kb = freshKnowledgeBase(c, "source-unique");
            long creator = queryLong(c, "SELECT created_by FROM knowledge_base WHERE id = " + kb);
            long page = insertPage(c, kb, creator, null, "sourced page");
            long attachment = insert(c,
                    "INSERT INTO attachment (uuid, kb_id, uploaded_by, file_name, content_type, "
                            + "byte_size) VALUES (?, ?, ?, ?, ?, ?)",
                    java.util.UUID.randomUUID().toString(), kb, creator,
                    "src.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    100);
            insert(c, "INSERT INTO source_document (page_id, attachment_id) VALUES (?, ?)",
                    page, attachment);
            assertThatThrownBy(() -> insert(c,
                    "INSERT INTO source_document (page_id, attachment_id) VALUES (?, ?)",
                    page, attachment))
                    .isInstanceOf(SQLIntegrityConstraintViolationException.class);
        }
    }

    private static long queryLong(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); var rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
