package ru.codesraider.smartsearch.analytics;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import ru.codesraider.smartsearch.search.SearchApi.Card;
import ru.codesraider.smartsearch.search.SearchApi.Filters;
import ru.codesraider.smartsearch.search.SearchApi.Offer;
import ru.codesraider.smartsearch.search.SearchApi.SearchRequest;
import ru.codesraider.smartsearch.search.SearchApi.SearchResponse;
import ru.codesraider.smartsearch.storage.Sqlite;
import ru.codesraider.smartsearch.web.ApiProblem;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

class EventStoreTest {
    private final JsonMapper json = JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();

    @Test
    void rollsBackInvalidBatchAndAttributesLateClicksByExactOffer(@TempDir Path folder) throws Exception {
        Path db = folder.resolve("events.db");
        var store = new EventStore(db.toString(), json);
        var analytics = new AnalyticsService(store);
        var sql = new JdbcTemplate(Sqlite.dataSource(db, true));
        var offer = new Offer("01", "SKU-01", "Offer", "Vendor", "/offer", true, 100L, "RUB", "primary", null, Map.of());
        var card = new Card(offer, "family/9", List.of("EXACT_MATCH"), "direct", null, null, null);
        var response = new SearchResponse("search", "version", "lexical", "sku-01", Filters.empty(), null, 1,
                List.of(card), List.of(), List.of(), "NOT_ENOUGH_VERIFIED_FAMILIES");
        var request = new SearchRequest("SKU-01", "session", null, null, null, null);
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
