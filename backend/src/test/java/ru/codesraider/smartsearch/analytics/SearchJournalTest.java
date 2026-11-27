package ru.codesraider.smartsearch.analytics;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

class SearchJournalTest {
    @Test
    void preservesEmptySearchAndSelectedTargetAcrossRestart(@TempDir Path temp) throws Exception {
        var path = temp.resolve("events/events.db");
        var mapper = JsonMapper.builder().build();
        var events = new EventStore(path.toString(), mapper);
        var request = Map.of("q", "кибер бэ", "selected_target",
                Map.of("kind", "collection", "target_id", "cyberprotect-1033"));
        var response = Map.of("search_id", "s1", "normalized_q", "кибер бэ", "direct_count", 0,
                "top4", List.of(), "more_results", List.of(), "alternatives", List.of(),
                "applied_filters", Map.of("version", "17.2"), "mode", "lexical",
                "index_version", "catalog-v1", "shortfall_reason", "NO_DIRECT_MATCH");
        events.logSearch(request, response, Map.of(), Map.of(), Instant.now(), 4);
        new AnalyticsService(new EventStore(path.toString(), mapper))
                .report(Instant.EPOCH, Instant.now().plusSeconds(1), null, null);
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + path);
             var sql = db.createStatement();
             var result = sql.executeQuery("SELECT * FROM search_log")) {
            assertTrue(result.next());
            assertEquals("s1", result.getString("search_id"));
            assertEquals("cyberprotect-1033", result.getString("selected_target_id"));
            assertEquals("17.2", mapper.readTree(result.getString("applied_filters_json"))
                    .get("version").asString());
            assertTrue(mapper.readTree(result.getString("shown_json")).get("top4").isEmpty());
            assertNull(result.getString("session_id"));
            assertFalse(result.next());
        }
    }
}
