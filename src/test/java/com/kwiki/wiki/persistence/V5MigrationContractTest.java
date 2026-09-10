package com.kwiki.wiki.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/** V5 契约：开发用超级管理员以 BCrypt 密码哈希播种。 */
@EnabledIfEnvironmentVariable(named = "KWIKI_IT_MYSQL_URL", matches = ".+")
class V5MigrationContractTest {

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

    @Test
    void developmentSuperAdminIsSeededWithHashedPassword() throws Exception {
        try (Connection c = open();
             var ps = c.prepareStatement(
                     "SELECT id, username, is_admin, is_active, password_hash "
                             + "FROM app_user WHERE username = 'kk'")) {
            try (var rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong("id")).isPositive();
                assertThat(rs.getString("username")).isEqualTo("kk");
                assertThat(rs.getBoolean("is_admin")).isTrue();
                assertThat(rs.getBoolean("is_active")).isTrue();

                String hash = rs.getString("password_hash");
                assertThat(hash).isNotBlank().isNotEqualTo("admin");
                assertThat(new BCryptPasswordEncoder().matches("admin", hash)).isTrue();
            }
        }
    }
}
