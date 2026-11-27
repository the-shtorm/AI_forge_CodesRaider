package ru.codesraider.smartsearch.search;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.codesraider.smartsearch.catalog.CatalogImporter;
import ru.codesraider.smartsearch.analytics.EventStore;
import ru.codesraider.smartsearch.modelclient.EncoderClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class UpsellSuggestionsTest {
    @Test
    void fillsSeparateStableRecommendationsFromEligibleCuratedOffers(@TempDir Path dir) throws Exception {
        var pool = IntStream.range(0, 12).mapToObj(i -> "curated" + i).toList();
        var offers = new StringBuilder(offer("anchor", "anchor-family", "1", 400, true, ""));
        for (String id : pool) offers.append(offer(id, id, "1", 50, true, ""));
        offers.append(offer("unavailable", "reserve", "1", 50, false, ""));
        offers.append(offer("unpriced", "other", "1", 0, true, ""));
        offers.append(offer("foreign", "foreign", "1", 50, true, "").replace(">RUB<", ">USD<"));
        offers.append(offer("duplicate-family", "curated0", "1", 50, true, ""));
        Path feed = dir.resolve("feed.xml"), snapshots = dir.resolve("snapshots");
        Files.writeString(feed, """
                <yml_catalog><shop><categories><category id="1">Инструменты</category></categories>
                <offers>%s</offers></shop></yml_catalog>
                """.formatted(offers));
        String offline = "http://127.0.0.1:1";
        try (var encoder = new EncoderClient(offline); var index = new SearchIndex(snapshots.toString(), offline, "lexical", 10)) {
            CatalogImporter.importSnapshot(feed, snapshots, false, encoder);
            index.open();
            var json = JsonMapper.builder().build();
            var configured = new java.util.ArrayList<>(List.of("missing", "unavailable", "unpriced", "foreign", "anchor", "duplicate-family"));
            configured.addAll(pool);
            var search = new SearchController(index, new EventStore(dir.resolve("events.db").toString(), json),
                    new ModelClient("", "softline", "", 3000), configured);
            JsonNode matched = json.valueToTree(search.search(Map.of("q", "anchor")));
            assertEquals(1, matched.path("direct_count").asInt());
            assertRecommendations(matched.path("upsell"), 3, true);
            JsonNode empty = json.valueToTree(search.search(Map.of("q", "квазизвёздная ягода")));
            assertEquals(0, empty.path("direct_count").asInt());
            assertEquals("NO_DIRECT_MATCH", empty.path("shortfall_reason").asString());
            assertTrue(empty.path("top4").isEmpty());
            assertRecommendations(empty.path("upsell"), 4, false);
            JsonNode repeated = json.valueToTree(search.search(Map.of("q", "  КВАЗИЗВЁЗДНАЯ   ЯГОДА  ")));
            assertEquals(empty.path("upsell"), repeated.path("upsell"));
            JsonNode filtered = json.valueToTree(search.search(Map.of("q", "квазизвёздная ягода",
                    "filters", Map.of("category_id", "absent"))));
            assertTrue(filtered.path("upsell").isEmpty());
        }
    }

    private static void assertRecommendations(JsonNode upsell, int minimum, boolean hasAnchor) {
        assertTrue(upsell.size() >= minimum && upsell.size() <= 8);
        var families = new java.util.HashSet<String>();
        for (var card : upsell) {
            String id = card.path("offer_id").asString();
            assertFalse(Set.of("missing", "unavailable", "unpriced", "foreign").contains(id));
            assertFalse(hasAnchor && "anchor".equals(id));
            assertTrue(families.add(card.path("family_key").asString()));
            assertEquals("related", card.path("role").asString());
            assertEquals("curated_fallback", card.path("recommendation_kind").asString());
            assertTrue(card.path("available").asBoolean());
            assertTrue(card.path("price_minor").asLong() > 0);
        }
    }
    @Test
    void prioritizesPairedCategoriesAndHonorsRecommendationFilters(@TempDir Path dir) throws Exception {
        String offers = offer("pc", "computer", "1", 400, true, "")
                + offer("ungrouped", "orphan", "1", 420, true, "").replace(" group_id=\"orphan\"", "")
                + offer("stand", "support", "2", 50, true, "")
                + offer("text-tool", "text", "3", 65, true, "<param name=\"Тип продукта\">софт</param>");
        Path feed = dir.resolve("feed.xml"), snapshots = dir.resolve("snapshots");
        Files.writeString(feed, """
                <yml_catalog><shop><categories><category id="1">Ноутбуки</category>
                <category id="2">Подставки для ноутбуков</category>
                <category id="3">Программное обеспечение для работы с документами</category>
                </categories><offers>%s</offers></shop></yml_catalog>
                """.formatted(offers));
        try (var encoder = new EncoderClient("http://127.0.0.1:1")) {
            CatalogImporter.importSnapshot(feed, snapshots, false, encoder);
        }
        Path version = snapshots.resolve(Files.readString(snapshots.resolve("active")).strip());
        try (var catalog = new SearchCatalog(version.resolve("catalog.db"))) {
            var excluded = new java.util.HashSet<>(Set.of("family/computer", "offer/ungrouped"));
            var cards = catalog.upsellSuggestions(List.of(anchor("pc"), anchor("ungrouped")), Map.of(),
                    excluded, "software_admin", 4, null);
            assertEquals(List.of("stand", "text-tool"), cards.stream().map(card -> card.get("offer_id")).toList());
            assertEquals("pc", cards.getFirst().get("anchor_offer_id"));
            assertEquals("paired_category", cards.getFirst().get("recommendation_kind"));
            assertFalse(cards.getFirst().containsKey("checks"));
            assertEquals("catalog_pick", cards.getLast().get("recommendation_kind"));

            var hint = new SearchCatalog.Row();
            hint.put("category", "Ноутбуки");
            var contextual = catalog.upsellSuggestions(List.of(), Map.of(), new java.util.HashSet<>(), null, 2, hint);
            assertEquals("stand", contextual.getFirst().get("offer_id"));
            assertFalse(contextual.getFirst().containsKey("anchor_offer_id"));
            assertTrue(catalog.upsellSuggestions(List.of(), Map.of("category_id", "3"),
                    new java.util.HashSet<>(), null, 2, hint).isEmpty());
        }
    }

    private static SearchCatalog.Row anchor(String id) {
        var row = new SearchCatalog.Row();
        row.put("offer_id", id);
        return row;
    }

    private static String offer(String id, String group, String category, int price, boolean available, String extra) {
        return """
                <offer id="%s" group_id="%s" available="%s"><name>%s</name>
                <vendor>Acme</vendor><vendorUrl>https://example.org/acme</vendorUrl><url>https://example.org/%s</url>
                <categoryId>%s</categoryId><price>%d</price><currencyId>RUB</currencyId>%s</offer>
                """.formatted(id, group, available, id, id, category, price, extra);
    }
}
