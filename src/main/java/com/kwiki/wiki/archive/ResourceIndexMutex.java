package com.kwiki.wiki.archive;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 归档/恢复与索引构建 worker 之间共享的生命周期互斥锁。
 * GET_LOCK 与 RELEASE_LOCK 都在整段临界区持有的同一专用连接上执行——
 * 绝不使用两个池化连接——因此进程崩溃时会随连接一起释放锁。
 * 当未接入 DataSource（离线/单元测试）时，该互斥锁降级为无操作。
 */
@Component
public class ResourceIndexMutex {

    public static final String LOCK_PREFIX = "kwiki:index:kb:";

    private final ObjectProvider<DataSource> dataSource;

    public ResourceIndexMutex(ObjectProvider<DataSource> dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 获取按知识库维度的索引互斥锁。返回的句柄必须在 finally 块中关闭；
     * 它会释放锁并关闭连接。
     *
     * @throws InterruptedException 当调用线程在等待过程中被中断时
     * @throws IllegalStateException 当无法及时获取锁时
     */
    public AutoCloseable acquireKb(long kbId, int timeoutSeconds)
            throws InterruptedException {
        DataSource source = dataSource == null ? null : dataSource.getIfAvailable();
        if (source == null) {
            return () -> {
            };
        }
        Connection connection = null;
        try {
            connection = source.getConnection();
            String name = LOCK_PREFIX + kbId;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT GET_LOCK(?, ?)")) {
                statement.setString(1, name);
                statement.setInt(2, timeoutSeconds);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next() || resultSet.getInt(1) != 1) {
                        throw new IllegalStateException("index mutex busy: " + name);
                    }
                }
            }
            Connection held = connection;
            return new AutoCloseable() {
                @Override
                public void close() {
                    try (PreparedStatement release = held.prepareStatement(
                            "SELECT RELEASE_LOCK(?)")) {
                        release.setString(1, name);
                        release.executeQuery();
                    } catch (SQLException ignored) {
                        // 无论如何，连接关闭都会释放锁
                    } finally {
                        try {
                            held.close();
                        } catch (SQLException ignored) {
                        }
                    }
                }
            };
        } catch (SQLException | IllegalStateException e) {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException ignored) {
                }
            }
            if (e instanceof IllegalStateException thrown) throw thrown;
            throw new IllegalStateException("index mutex unavailable", e);
        }
    }
}
