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
        var offers = new StringBuilder(offer("anchor", "anchor-family", "1", 400, true, "Опора"));
        for (String id : pool) offers.append(offer(id, id, "1", 50, true, id));
        offers.append(offer("unavailable", "reserve", "1", 50, false, "Нет в наличии"));
        offers.append(offer("unpriced", "other", "1", 0, true, "Без цены"));
        offers.append(offer("foreign", "foreign", "1", 50, true, "Другая валюта").replace(">RUB<", ">USD<"));
        offers.append(offer("duplicate-family", "curated0", "1", 50, true, "Другой вариант"));
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
            JsonNode empty = json.valueToTree(search.search(Map.of("q", "???")));
            assertEquals(0, empty.path("direct_count").asInt());
            assertEquals("NO_DIRECT_MATCH", empty.path("shortfall_reason").asString());
            assertTrue(empty.path("top4").isEmpty());
            var upsell = empty.path("upsell");
            assertTrue(upsell.size() >= 4 && upsell.size() <= 8);
            var families = new java.util.HashSet<String>();
            for (var card : upsell) {
                assertFalse(Set.of("missing", "unavailable", "unpriced", "foreign").contains(card.path("offer_id").asString()));
                assertTrue(families.add(card.path("family_key").asString()));
                assertEquals("related", card.path("role").asString());
                assertEquals("curated_fallback", card.path("recommendation_kind").asString());
                assertTrue(card.path("available").asBoolean());
                assertTrue(card.path("price_minor").asLong() > 0);
            }
            JsonNode repeated = json.valueToTree(search.search(Map.of("q", "  ???  ")));
            assertEquals(upsell, repeated.path("upsell"));
            JsonNode filtered = json.valueToTree(search.search(Map.of("q", "???",
                    "filters", Map.of("category_id", "absent"))));
            assertTrue(filtered.path("upsell").isEmpty());
            for (String query : List.of("цветной лазерный принтер A3", "sku:UNKNOWN-1234")) {
                JsonNode meaningful = json.valueToTree(search.search(Map.of("q", query)));
                assertEquals(0, meaningful.path("direct_count").asInt());
                assertEquals("NO_DIRECT_MATCH", meaningful.path("shortfall_reason").asString());
                assertCurated(meaningful.path("upsell"));
            }
            JsonNode matched = json.valueToTree(search.search(Map.of("q", "id:anchor")));
            assertEquals(1, matched.path("direct_count").asInt());
            assertCurated(matched.path("upsell"));
            assertTrue(matched.path("upsell").valueStream().noneMatch(card ->
                    "family/anchor-family".equals(card.path("family_key").asString())));
            JsonNode optedOut = json.valueToTree(search.search(Map.of("q", "id:anchor без рекомендаций")));
            assertEquals(1, optedOut.path("direct_count").asInt());
            assertTrue(optedOut.path("upsell").isEmpty());
        }
    }

    private static void assertCurated(JsonNode cards) {
        assertTrue(cards.size() >= 4 && cards.size() <= 8);
        assertTrue(cards.valueStream().allMatch(card -> "related".equals(card.path("role").asString())
                && "curated_fallback".equals(card.path("recommendation_kind").asString())));
    }

    @Test
    void recommendsDistinctRankedOffersFromTheCategoryOfTheDirectMatch(@TempDir Path dir) throws Exception {
        String offers = offer("anchor", "anchor-family", "77", 400, true, "Акустический датчик вибрации")
                + offer("same-family", "anchor-family", "77", 410, true, "Акустический датчик вибрации с комплектом")
                + offer("portable", "portable-family", "77", 50, true, "Акустический датчик вибрации переносной")
                + offer("portable-backup", "portable-family", "77", 55, true, "Акустический датчик переносной")
                + offer("standalone", null, "77", 65, true, "Акустический датчик шума")
                + offer("out-of-stock", "reserve", "77", 60, false, "Акустический датчик настольный")
                + offer("wrong-category", "accessory", "88", 50, true, "Акустический датчик, чехол");
        Path feed = dir.resolve("feed.xml"), snapshots = dir.resolve("snapshots");
        Files.writeString(feed, """
                <yml_catalog><shop><categories><category id="77">Датчики новой выгрузки</category>
                <category id="88">Комплектующие испытательного стенда</category></categories>
                <offers>%s</offers></shop></yml_catalog>
                """.formatted(offers));
        String offline = "http://127.0.0.1:1";
        try (var encoder = new EncoderClient(offline); var index = new SearchIndex(snapshots.toString(), offline, "lexical", 10)) {
            CatalogImporter.importSnapshot(feed, snapshots, false, encoder);
            index.open();
            var json = JsonMapper.builder().build();
            var search = new SearchController(index, new EventStore(dir.resolve("events.db").toString(), json),
                    new ModelClient("", "softline", "", 3000), List.of("wrong-category"));
            var result = json.valueToTree(search.search(Map.of("q", "id:anchor")));
            assertEquals(1, result.path("direct_count").asInt());
            assertEquals("anchor", result.at("/top4/0/offer_id").asString());
            var cards = result.path("upsell").valueStream().toList();
            assertEquals(2, cards.size());
            assertEquals(Set.of("family/portable-family", "offer/standalone"),
                    Set.copyOf(cards.stream().map(card -> card.path("family_key").asString()).toList()));
            assertTrue(cards.stream().allMatch(card -> "catalog_pick".equals(card.path("recommendation_kind").asString())));
        }
    }

    private static String offer(String id, String group, String category, int price, boolean available, String name) {
        String groupAttribute = group == null ? "" : " group_id=\"" + group + "\"";
        return """
                <offer id="%s"%s available="%s"><name>%s</name>
                <vendor>Acme</vendor><vendorUrl>https://example.org/acme</vendorUrl><url>https://example.org/%s</url>
                <categoryId>%s</categoryId><price>%d</price><currencyId>RUB</currencyId></offer>
                """.formatted(id, groupAttribute, available, name, id, category, price);
    }
}
