package com.kwiki.wiki.archive;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Shared lifecycle mutex between archive/restore and the indexing worker.
 * Both GET_LOCK and RELEASE_LOCK run on one dedicated connection held for the
 * whole critical section — never two pooled connections — so a process crash
 * releases the lock with the connection. When no DataSource is wired
 * (offline/unit tests) the mutex degrades to a no-op.
 */
@Component
public class ResourceIndexMutex {

    public static final String LOCK_PREFIX = "kwiki:index:kb:";

    private final ObjectProvider<DataSource> dataSource;

    public ResourceIndexMutex(ObjectProvider<DataSource> dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Acquires the per-knowledge-base index mutex. The returned handle must be
     * closed in a finally block; it releases the lock and closes the connection.
     *
     * @throws InterruptedException when the calling thread is interrupted while waiting
     * @throws IllegalStateException when the lock cannot be acquired in time
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
                        // connection close releases the lock regardless
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
