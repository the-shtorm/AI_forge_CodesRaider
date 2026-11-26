package ru.codesraider.smartsearch.analytics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class SearchJournal {
    public static final class WriteFailure extends RuntimeException {
        private WriteFailure(SQLException cause) {
            super("Could not persist search", cause);
        }
    }

    private static final DateTimeFormatter UTC = new DateTimeFormatterBuilder()
            .appendInstant(9).toFormatter();
    private static final String INSERT = """
            INSERT INTO search_log(
                search_id, source, session_id, ts, q, normalized_q, selected_target_kind,
                selected_target_id, context_json, applied_filters_json, direct_count,
                shown_family_count, shortfall_reason, mode, index_version, elapsed_ms,
                shown_json, diagnostics_json)
            VALUES(?, 'demo', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final ObjectMapper json;
    private final String jdbcUrl;

    public SearchJournal(ObjectMapper json, @Value("${EVENTS_DB:data/events/events.db}") String location)
            throws IOException, SQLException {
        this.json = json;
        Path file = Path.of(location).toAbsolutePath();
        Files.createDirectories(file.getParent());
        jdbcUrl = "jdbc:sqlite:" + file;
        try (var db = connection(); var statement = db.createStatement()) {
            int version;
            try (var rows = statement.executeQuery("PRAGMA user_version")) {
                if (!rows.next()) throw new SQLException("Missing events.db schema version");
                version = rows.getInt(1);
            }
            if (version == 0) {
                var resource = new ClassPathResource("sql/events-v1.sql");
                statement.executeUpdate(resource.getContentAsString(StandardCharsets.UTF_8));
            } else if (version != 1) {
                throw new SQLException("Unsupported events.db schema version " + version);
            }
        }
    }

    private Connection connection() throws SQLException {
        var config = new org.sqlite.SQLiteConfig();
        config.enforceForeignKeys(true);
        config.setBusyTimeout(5000);
        return DriverManager.getConnection(jdbcUrl, config.toProperties());
    }

    public void record(Map<String, ?> request, Map<String, ?> response, long elapsedMs) {
        var target = request.get("selected_target") instanceof Map<?, ?> value ? value : Map.of();
        Map<String, Object> shown = new LinkedHashMap<>();
        for (var key : List.of("primary_target", "top4", "more_results", "alternatives")) {
            shown.put(key, response.get(key));
        }
        try (var db = connection(); var insert = db.prepareStatement(INSERT)) {
            insert.setObject(1, response.get("search_id"));
            insert.setObject(2, request.get("session_id"));
            insert.setString(3, UTC.format(Instant.now()));
            insert.setObject(4, request.get("q"));
            insert.setObject(5, response.get("normalized_q"));
            insert.setObject(6, target.get("kind"));
            insert.setObject(7, target.get("target_id"));
            insert.setString(8, json.writeValueAsString(request.get("context") == null ? Map.of() : request.get("context")));
            insert.setString(9, json.writeValueAsString(response.get("applied_filters")));
            insert.setObject(10, response.get("direct_count"));
            insert.setInt(11, ((List<?>) response.get("top4")).size());
            insert.setObject(12, response.get("shortfall_reason"));
            insert.setObject(13, response.get("mode"));
            insert.setObject(14, response.get("index_version"));
            insert.setLong(15, elapsedMs);
            insert.setString(16, json.writeValueAsString(shown));
            insert.setString(17, "{}");
            insert.executeUpdate();
        } catch (SQLException e) {
            throw new WriteFailure(e);
        }
    }
}
