package com.kwiki.indexing.gray;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V33 契约：同一知识库不能同时处于两个未结束的灰度；结束后可再次加入。需要 KWIKI_IT_MYSQL_URL。 */
@EnabledIfEnvironmentVariable(named = "KWIKI_IT_MYSQL_URL", matches = ".+")
class GrayReleaseMigrationContractTest {

    private static final String URL = System.getenv().getOrDefault("KWIKI_IT_MYSQL_URL", "");

    @BeforeAll
    static void migrate() {
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

    @Test
    void 活跃灰度中知识库唯一_结束后释放() throws SQLException {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO index_gray_release (id, name, parser_version, index_version_number, status, created_by)"
                    + " VALUES (1, 'a', 'kwiki-parse-2', 90, 'CREATED', 'admin'), (2, 'b', 'kwiki-parse-2', 91, 'CREATED', 'admin')");
            statement.executeUpdate("INSERT INTO index_gray_release_kb (release_id, kb_id, active_kb_id) VALUES (1, 7, 7)");
            assertThatThrownBy(() -> statement.executeUpdate(
                    "INSERT INTO index_gray_release_kb (release_id, kb_id, active_kb_id) VALUES (2, 7, 7)"))
                    .isInstanceOf(SQLException.class);
            statement.executeUpdate("UPDATE index_gray_release_kb SET active_kb_id = NULL WHERE release_id = 1");
            statement.executeUpdate("INSERT INTO index_gray_release_kb (release_id, kb_id, active_kb_id) VALUES (2, 7, 7)");
            assertThatThrownBy(() -> statement.executeUpdate(
                    "UPDATE index_gray_release SET status = 'BOGUS' WHERE id = 1"))
                    .isInstanceOf(SQLException.class);
        }
    }
}
