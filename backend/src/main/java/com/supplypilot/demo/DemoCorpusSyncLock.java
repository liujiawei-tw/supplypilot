package com.supplypilot.demo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Serializes baseline corpus sync across DEMO processes sharing the MySQL database. */
@Component
@Profile("demo & !prod")
public class DemoCorpusSyncLock {
    private static final String LOCK_NAME = "supplypilot:SP-901:corpus-sync";
    private static final int WAIT_SECONDS = 300;

    private final JdbcTemplate jdbc;
    private final int waitSeconds;

    @Autowired
    public DemoCorpusSyncLock(JdbcTemplate jdbc) {
        this(jdbc, WAIT_SECONDS);
    }

    DemoCorpusSyncLock(JdbcTemplate jdbc, int waitSeconds) {
        if (waitSeconds < 0) {
            throw new IllegalArgumentException("waitSeconds must not be negative");
        }
        this.jdbc = jdbc;
        this.waitSeconds = waitSeconds;
    }

    public <T> T withLock(Supplier<T> operation) {
        return jdbc.execute((ConnectionCallback<T>) connection -> {
            acquire(connection);
            try {
                return operation.get();
            } finally {
                release(connection);
            }
        });
    }

    private void acquire(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT GET_LOCK(?, ?)")) {
            statement.setString(1, LOCK_NAME);
            statement.setInt(2, waitSeconds);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || result.getObject(1) == null || result.getInt(1) != 1) {
                    throw new IllegalStateException("Timed out waiting for the DEMO corpus synchronization lock");
                }
            }
        }
    }

    private void release(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
            statement.setString(1, LOCK_NAME);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || result.getObject(1) == null || result.getInt(1) != 1) {
                    throw new SQLException("Could not release the DEMO corpus synchronization lock");
                }
            }
        }
    }
}
