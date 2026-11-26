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
