package com.kwiki.wiki.archive;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * MySQL 咨询锁辅助类，始终在同一个专用连接上执行 GET_LOCK 与 RELEASE_LOCK
 * （绝不使用两个池化连接），因此即便持有者在中途崩溃，锁也会
 * 随连接一起释放。
 */
@Component
public class MysqlAdvisoryLocks {

    public MysqlAdvisoryLocks(ObjectProvider<DataSource> dataSource) {
        this.dataSource = dataSource;
    }

    private final ObjectProvider<DataSource> dataSource;

    /**
     * 在指定名称的咨询锁保护下执行 {@code body}。若无法立即获取锁
     * （被其它实例持有）则返回 false。
     */
    public boolean tryWithLock(String name, Runnable body) {
        DataSource source = dataSource == null ? null : dataSource.getIfAvailable();
        if (source == null) {
            body.run(); // 离线/单元测试环境：无需协调
            return true;
        }
        try (Connection connection = source.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT GET_LOCK(?, 0)")) {
                statement.setString(1, name);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next() || resultSet.getInt(1) != 1) {
                        return false;
                    }
                }
            }
            try {
                body.run();
            } finally {
                try (PreparedStatement release = connection.prepareStatement(
                        "SELECT RELEASE_LOCK(?)")) {
                    release.setString(1, name);
                    release.executeQuery();
                } catch (SQLException ignored) {
                    // 无论如何，连接关闭都会释放锁
                }
            }
            return true;
        } catch (SQLException e) {
            throw new IllegalStateException("advisory lock unavailable: " + name, e);
        }
    }
}
