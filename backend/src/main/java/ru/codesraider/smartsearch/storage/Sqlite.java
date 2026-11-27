package ru.codesraider.smartsearch.storage;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;

import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

public final class Sqlite {
    private Sqlite() {}

    public static SQLiteDataSource dataSource(Path path, boolean readOnly) {
        var config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        config.setBusyTimeout(3000);
        config.setReadOnly(readOnly);
        if (!readOnly) config.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
        var source = new SQLiteDataSource(config);
        source.setUrl("jdbc:sqlite:" + path.toAbsolutePath());
        return source;
    }

    public static void createV1(Connection connection, String database) {
        ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/" + database + "-v1.sql"));
    }

    public static int version(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA user_version")) {
            return rows.next() ? rows.getInt(1) : 0;
        }
    }

    public static void ensureEvents(Connection connection) throws SQLException {
        if (version(connection) == 2) return;
        // PRAGMA journal_mode returns one row. Close its cursor before starting the write transaction.
        try (var journal = connection.createStatement(); var mode = journal.executeQuery("PRAGMA journal_mode=WAL")) {
            if (!mode.next() || !"wal".equalsIgnoreCase(mode.getString(1))) throw new SQLException("Could not enable WAL");
        }
        connection.setAutoCommit(false); // dataSource sets BEGIN IMMEDIATE for writers.
        try (var statement = connection.createStatement()) {
            try {
                int current = version(connection);
                if (current == 1) {
                    // Keep all original rows until their new constraints have been checked.
                    statement.execute("ALTER TABLE event RENAME TO event_v1");
                    for (String name : new String[]{"event_session", "event_attribution", "event_search"})
                        statement.execute("DROP INDEX " + name);
                } else if (current != 0 && current != 2) {
                    throw new SQLException("Unsupported events schema version " + current);
                }
                if (current < 2) ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/events-v2.sql"));
                if (current == 1) {
                    statement.execute("INSERT INTO event SELECT * FROM event_v1");
                    statement.execute("DROP TABLE event_v1");
                }
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    public static boolean checkCatalog(Connection connection) throws SQLException {
        if (version(connection) != 1) return false;
        try (var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("PRAGMA quick_check")) {
                if (!rows.next() || !"ok".equals(rows.getString(1))) return false;
            }
            try (var rows = statement.executeQuery("PRAGMA foreign_key_check")) {
                return !rows.next();
            }
        }
    }
}
