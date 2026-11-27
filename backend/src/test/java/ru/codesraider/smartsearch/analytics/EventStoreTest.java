package ru.codesraider.smartsearch.analytics;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import ru.codesraider.smartsearch.storage.Sqlite;
import ru.codesraider.smartsearch.web.ApiProblem;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

class EventStoreTest {
    private final JsonMapper json = JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();

    @Test
    void preservesOldEventsAndTracksUpsellInItsOwnBlock(@TempDir Path folder) throws Exception {
        Path db = folder.resolve("events.db");
        var source = Sqlite.dataSource(db, false);
        try (var conn = source.getConnection()) {
            Sqlite.createV1(conn, "events");
        }
        var sql = new JdbcTemplate(source);
        sql.update("""
                INSERT INTO event(event_id,source,session_id,ts,type,offer_id)
                VALUES('old','demo','buyer','2026-01-01T00:00:00.000000000Z','order','old-sku')
                """);
        var store = new EventStore(db.toString(), json);
        var related = Map.of("role", "related", "family_key", "family/new", "offer_id", "extra",
                "recommendation_kind", "catalog_pick", "recommendation_reason", "Предложение каталога.");
        var response = Map.ofEntries(
                Map.entry("search_id", "new-search"), Map.entry("index_version", "test"), Map.entry("mode", "lexical"),
                Map.entry("normalized_q", "unknown"), Map.entry("applied_filters", Map.of()), Map.entry("direct_count", 0),
                Map.entry("top4", List.of()), Map.entry("more_results", List.of()), Map.entry("alternatives", List.of()),
                Map.entry("upsell", List.of(related)), Map.entry("shortfall_reason", "NO_DIRECT_MATCH"));
        store.logSearch(Map.of("q", "unknown", "session_id", "buyer"), response, Map.of(), Map.of(), Instant.now(), 2);
        var shown = store.shownCard("new-search", "extra");
        assertEquals("Предложение каталога.", shown.required("recommendation_reason").asString());
        assertEquals("catalog_pick", shown.required("recommendation_kind").asString());
        assertEquals(0, sql.queryForObject("SELECT shown_family_count FROM search_log", Integer.class));
        String click = """
                [{"type":"click","event_id":"new","session_id":"buyer","ts":"2026-02-01T00:00:00Z",
                "search_id":"new-search","offer_id":"extra","family_key":"family/new","role":"related","block":"upsell","position":1}]
                """;
        assertThrows(ApiProblem.class, () -> store.ingest(json.readTree(click.replace("upsell","top4"))));
        store.ingest(json.readTree(click));
        store.ingest(json.readTree(click));
        assertEquals(2, sql.queryForObject("SELECT count(*) FROM event", Integer.class));
        assertEquals("old-sku", sql.queryForObject("SELECT offer_id FROM event WHERE event_id='old'", String.class));
        assertEquals(2, sql.queryForObject("PRAGMA user_version", Integer.class));
        assertTrue(sql.queryForList("PRAGMA foreign_key_check").isEmpty());
    }

    @Test
    void rollsBackInvalidBatchAndAttributesLateClicksByExactOffer(@TempDir Path folder) throws Exception {
        Path db = folder.resolve("events.db");
        var store = new EventStore(db.toString(), json);
        var analytics = new AnalyticsService(store);
        var sql = new JdbcTemplate(Sqlite.dataSource(db, true));
        var card = Map.of("offer_id", "01", "family_key", "family/9", "role", "direct");
        var response = Map.of("search_id", "search", "index_version", "version", "mode", "lexical",
                "normalized_q", "sku-01", "applied_filters", Map.of(), "direct_count", 1, "top4", List.of(card),
                "more_results", List.of(), "alternatives", List.of(), "shortfall_reason", "NOT_ENOUGH_VERIFIED_FAMILIES");
        var request = Map.of("q", "SKU-01", "session_id", "session");
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        store.logSearch(request, response, Map.of(), Map.of("01", "family/9", "02", "family/9"), from, 1);
        String order = """
                {"event_id":"order","session_id":"session","ts":"2026-01-01T04:00:00+03:00","type":"order","offer_id":"01"}
                """;
        String click = """
                {"event_id":"click","session_id":"session","ts":"2026-01-01T00:05:00.000001Z","type":"click",
                 "block":"top4","search_id":"search","offer_id":"01","family_key":"family/9","role":"direct","position":1}
                """;
        String invalidClick = click.replace("\"position\":1", "\"position\":2");
        assertThrows(ApiProblem.class, () -> store.ingest(json.readTree("[" + order + "," + invalidClick + "]")));
        assertEquals(0, sql.queryForObject("SELECT count(*) FROM event", Integer.class));

        store.ingest(json.readTree("[" + order + "]"));
        store.ingest(json.readTree("""
                [{"event_id":"neighbor","session_id":"session","ts":"2026-01-01T00:10:00Z","type":"click",
                  "block":"details","search_id":"search","offer_id":"02","family_key":"family/9"}]
                """));
        assertNull(sql.queryForObject("SELECT search_id FROM event WHERE event_id='order'", String.class));

        store.ingest(json.readTree("[" + click + "]"));
        store.ingest(json.readTree("[" + click + "," + order + "]"));
        assertEquals(3, sql.queryForObject("SELECT count(*) FROM event", Integer.class));
        assertEquals("search", sql.queryForObject("SELECT search_id FROM event WHERE event_id='order'", String.class));
        assertEquals("2026-01-01T00:05:00.000001000Z",
                sql.queryForObject("SELECT ts FROM event WHERE event_id='click'", String.class));
        var report = analytics.report(from, from.plusSeconds(1), "demo", "version");
        assertEquals(new AnalyticsService.Metric(1, 1, 1.0), report.conversionRate());
        assertEquals(new AnalyticsService.Metric(0, 1, 0.0), report.zeroResultRate());
    }
}
