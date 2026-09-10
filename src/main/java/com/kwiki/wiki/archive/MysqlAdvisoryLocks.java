package com.kwiki.wiki.archive;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * MySQL advisory-lock helper that always runs GET_LOCK and RELEASE_LOCK on the
 * same dedicated connection (never two pooled connections), so the lock is
 * released with the connection even when the holder dies mid-critical-section.
 */
@Component
public class MysqlAdvisoryLocks {

    public MysqlAdvisoryLocks(ObjectProvider<DataSource> dataSource) {
        this.dataSource = dataSource;
    }

    private final ObjectProvider<DataSource> dataSource;

    /**
     * Runs {@code body} under the named advisory lock. Returns false when the
     * lock cannot be acquired immediately (another instance holds it).
     */
    public boolean tryWithLock(String name, Runnable body) {
        DataSource source = dataSource == null ? null : dataSource.getIfAvailable();
        if (source == null) {
            body.run(); // offline/unit context: no coordination needed
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
                    // connection close releases the lock regardless
                }
            }
            return true;
        } catch (SQLException e) {
            throw new IllegalStateException("advisory lock unavailable: " + name, e);
        }
    }
}
