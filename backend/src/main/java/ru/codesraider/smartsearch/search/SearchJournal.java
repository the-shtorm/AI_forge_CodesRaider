package ru.codesraider.smartsearch.search;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;

@Component
class SearchJournal {
    private final String url;
    private final JsonMapper json = JsonMapper.builder().build();

    SearchJournal(@Value("${EVENTS_PATH:./events.db}") String path) throws Exception {
        Path file = Path.of(path).toAbsolutePath();
        Files.createDirectories(file.getParent());
        url = "jdbc:sqlite:" + file;
        try (var conn = open();
                var stmt = conn.createStatement();
                var rs = stmt.executeQuery("PRAGMA user_version")) {
            int version = rs.getInt(1);
            rs.close();
            if (version == 0)
                try (var source =
                        Objects.requireNonNull(getClass().getResourceAsStream("/events-v1.sql"))) {
                    // Xerial executes the entire versioned DDL (including its transaction) with
                    // executeUpdate.
                    stmt.executeUpdate(new String(source.readAllBytes(), StandardCharsets.UTF_8));
                }
            else if (version != 1) throw new SQLException("Неизвестная версия журнала " + version);
        }
    }

    private Connection open() throws SQLException {
        var conn = DriverManager.getConnection(url);
        try (var stmt = conn.createStatement()) {
            stmt.execute("PRAGMA busy_timeout=3000");
            stmt.execute("PRAGMA foreign_keys=ON");
        }
        return conn;
    }

    void record(SearchEngine.Input in, Map<String, Object> response, long ms) {
        String sql =
                """
                INSERT INTO search_log(search_id, source, session_id, ts, q, normalized_q, selected_target_kind,
                  selected_target_id, context_json, applied_filters_json, direct_count, shown_family_count, shortfall_reason,
                  mode, index_version, elapsed_ms, shown_json, diagnostics_json)
                VALUES (?, 'demo', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        var shown =
                Map.of(
                        "top4",
                        response.get("top4"),
                        "more_results",
                        response.get("more_results"),
                        "alternatives",
                        response.get("alternatives"));
        var pick = in.selected_target();
        var params =
                Arrays.asList(
                        response.get("search_id"),
                        in.session_id(),
                        new DateTimeFormatterBuilder()
                                .appendInstant(9)
                                .toFormatter()
                                .format(Instant.now()),
                        in.q(),
                        response.get("normalized_q"),
                        pick == null ? null : pick.kind(),
                        pick == null ? null : pick.target_id(),
                        json.writeValueAsString(in.context() == null ? Map.of() : in.context()),
                        json.writeValueAsString(response.get("applied_filters")),
                        response.get("direct_count"),
                        ((List<?>) response.get("top4")).size(),
                        response.get("shortfall_reason"),
                        response.get("mode"),
                        response.get("index_version"),
                        ms,
                        json.writeValueAsString(shown),
                        json.writeValueAsString(response.get("program")));
        try (var conn = open();
                var stmt = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) stmt.setObject(i + 1, params.get(i));
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Не удалось записать поиск", e);
        }
    }

    JsonNode shownCard(String searchId, String offerId) {
        try (var conn = open();
                var stmt =
                        conn.prepareStatement(
                                "SELECT q, shown_json FROM search_log WHERE search_id=?")) {
            stmt.setString(1, searchId);
            try (var rs = stmt.executeQuery()) {
                if (!rs.next()) return null;
                for (var block : json.readTree(rs.getString("shown_json")))
                    for (var card : block) {
                        if (card.path("offer_id").asString().equals(offerId)) {
                            ((ObjectNode) card).put("query", rs.getString("q"));
                            return card;
                        }
                    }
            }
            return null;
        } catch (SQLException e) {
            throw new IllegalStateException("Не удалось прочитать поиск", e);
        }
    }
}
