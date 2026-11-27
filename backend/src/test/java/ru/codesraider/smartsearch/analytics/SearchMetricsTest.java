package ru.codesraider.smartsearch.analytics;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.codesraider.smartsearch.catalog.CatalogImporter;
import ru.codesraider.smartsearch.modelclient.EncoderClient;
import ru.codesraider.smartsearch.search.SearchController;
import ru.codesraider.smartsearch.search.SearchIndex;
import ru.codesraider.smartsearch.storage.Sqlite;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/** Exercise the published catalog, storefront and persistent analytics as one workflow. */
class SearchMetricsTest {
    @Test
    void reportsAllThreeRatesAndFailuresAfterRestart(@TempDir Path temp) throws Exception {
        var json = JsonMapper.builder().build();
        Path db = temp.resolve("events.db"), snapshots = temp.resolve("snapshots");
        var store = new EventStore(db.toString(), json);
        String offline = "http://127.0.0.1:1";
        try (var encoder = new EncoderClient(offline);
             var index = new SearchIndex(snapshots.toString(), offline, "lexical", 10)) {
            CatalogImporter.importSnapshot(Path.of(getClass().getResource("/search/demo.xml").toURI()), snapshots, false, encoder);
            index.open();
            var search = new SearchController(index, store);
            String empty = "неизвестный-99999";
            var missed = search.search(Map.of("q", empty, "session_id", "buyer"));
            var hit = search.search(Map.of("q", "КриптоПро CSP без версии 5.0", "session_id", "buyer"));
            search.search(Map.of("q", empty, "session_id", "left"));
            search.search(Map.of("q", "EP2-06605", "session_id", "reader"));
            assertEquals(0, missed.get("direct_count"));
            assertEquals(1, hit.get("direct_count"));
            var card = json.valueToTree(hit).at("/top4/0");
            String id = hit.get("search_id").toString(), offer = card.path("offer_id").asString();
            var before = Instant.parse("2026-01-01T00:00:00Z");
            // Move only our disposable clock fixture past the 30-minute session timeout.
            var sql = new JdbcTemplate(Sqlite.dataSource(db, false));
            sql.update("UPDATE search_log SET ts=?", Event.timestamp(before));
            var shown = Map.of("block", "top4", "search_id", id, "offer_id", offer,
                    "family_key", card.path("family_key").asString(), "role", "direct", "position", 1);
            var types = List.of("reformulation", "impression", "click", "add_to_cart", "order");
            var payload = json.createArrayNode();
            for (int i = 0; i < types.size(); i++) {
                String type = types.get(i);
                var event = new LinkedHashMap<String, Object>(Map.of("type", type, "session_id", "buyer",
                        "ts", before.plusSeconds(i + 1).toString(), "event_id", type));
                switch (type) {
                    case "reformulation" -> event.putAll(Map.of("search_id", missed.get("search_id"), "next_search_id", id));
                    case "click", "impression" -> event.putAll(shown);
                    case "add_to_cart" -> event.putAll(Map.of("offer_id", offer, "search_id", id));
                    case "order" -> event.put("offer_id", offer);
                }
                payload.add(json.valueToTree(event));
            }
            store.ingest(payload);
            store.ingest(payload);
            var reopened = new EventStore(db.toString(), json);
            var explanation = new SearchController(index, reopened).explain(Map.of("search_id", id, "offer_id", offer));
            assertEquals("template", explanation.get("source"));
            assertEquals(4, sql.queryForObject("SELECT count(*) FROM search_log", Integer.class));
            assertEquals(5, sql.queryForObject("SELECT count(*) FROM event", Integer.class));
            assertEquals(id, sql.queryForObject("SELECT search_id FROM event WHERE type='order'", String.class));
            assertEquals("4.0", card.at("/attributes/Версия/0").asString());
            var analytics = new AnalyticsService(reopened);
            var report = analytics.report(before, before.plusSeconds(60), "demo", index.version());
            assertEquals(new AnalyticsService.Metric(2, 4, 0.5), report.zeroResultRate());
            assertEquals(new AnalyticsService.Metric(1, 2, 0.5), report.abandonmentRate());
            assertEquals(new AnalyticsService.Metric(1, 3, 1.0 / 3), report.conversionRate());
            assertEquals(2, report.failures().stream().mapToLong(AnalyticsService.Failure::count).sum());
            assertEquals(0, analytics.report(before, before.plusSeconds(60), "seed", index.version()).zeroResultRate().denominator());
            assertEquals(0, analytics.report(before, before.plusSeconds(60), "demo", "other").zeroResultRate().denominator());
        }
    }
}
