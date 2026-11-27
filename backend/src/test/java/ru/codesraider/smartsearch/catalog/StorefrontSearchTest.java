package ru.codesraider.smartsearch.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.codesraider.smartsearch.analytics.EventStore;
import ru.codesraider.smartsearch.modelclient.EncoderClient;
import ru.codesraider.smartsearch.search.SearchController;
import ru.codesraider.smartsearch.search.SearchIndex;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class StorefrontSearchTest {
    @TempDir Path temp;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static String offer(int id, int family, String vendor, int category, int price) {
        return """
                <offer id="%1$d" group_id="%2$d" available="true"><name>Редактор %1$d</name>
                <url>https://example.org/p/%1$d</url><vendor>%3$s</vendor>
                <vendorUrl>https://example.org/%3$s/</vendorUrl><vendorCode>SKU-%1$d</vendorCode>
                <categoryId>%4$d</categoryId><price>%5$d</price><currencyId>RUR</currencyId></offer>
                """.formatted(id, family, vendor, category, price);
    }

    @Test
    void exactIdentifiersAndCatalogNamesSurviveGroupingAndEmptyCategories() throws Exception {
        Path feed = temp.resolve("feed.xml"), snapshots = temp.resolve("snapshots");
        String standalone = offer(201, 1, "alpha", 2, 20).replace(" group_id=\"1\"", "")
                .replace("SKU-201", "500");
        Files.writeString(feed, """
                <yml_catalog><shop><categories><category id="1">Acme Studio</category>
                <category id="2">Приложения</category></categories><offers>
                """ + standalone + offer(500, 72, "beta", 2, 50).replace("Редактор 500", "Acme Studio (годовая лицензия)")
                + offer(503, 75, "beta", 2, 80).replace("Редактор 503", "Видеоредактор Studio")
                + "</offers></shop></yml_catalog>");
        String offline = "http://127.0.0.1:1";
        try (var encoder = new EncoderClient(offline);
             var index = new SearchIndex(snapshots.toString(), offline, "lexical", 10)) {
            CatalogImporter.importSnapshot(feed, snapshots, false, encoder);
            index.open();
            var search = new SearchController(index, new EventStore(temp.resolve("events.db").toString(), JSON));
            var bare = JSON.valueToTree(search.search(Map.of("q", "500")));
            assertTrue(bare.path("primary_target").isNull());
            assertEquals(2, bare.path("direct_count").asInt());
            assertEquals(2, bare.at("/facets/categories/0/count").asInt());
            var choices = search.suggest("500", null, null);
            assertTrue(choices.stream().anyMatch(c -> c.get("label").toString().endsWith(" · ID 500")));
            assertTrue(choices.stream().anyMatch(c -> c.get("label").toString().endsWith(" · артикул 500")));
            assertEquals("500", JSON.valueToTree(search.search(Map.of("q", "id:500"))).at("/primary_target/id").asString());
            var sku = JSON.valueToTree(search.search(Map.of("q", "артикул 500")));
            assertEquals("201", sku.at("/primary_target/id").asString());
            assertEquals("offer/201", sku.at("/top4/0/family_key").asString());
            assertEquals("201", search.explain(Map.of("search_id", sku.path("search_id").asString(),
                    "offer_id", "201")).get("offer_id"));
            assertEquals(0, search.search(Map.of("q", "sku:500", "filters", Map.of("vendor_id", "beta"))).get("direct_count"));
            for (String name : List.of("Acme Studio", "Acme Stduio")) {
                assertEquals("72", JSON.valueToTree(search.search(Map.of("q", name))).at("/primary_target/id").asString());
            }
            var fallback = JSON.valueToTree(search.search(Map.of("q", "Подобрать видеоредактор")));
            assertEquals("catalog_fallback", fallback.at("/program/compiler").asString());
            assertEquals("503", fallback.at("/top4/0/offer_id").asString());
            assertEquals(0, search.search(Map.of("q", "Подобрать видеоредактор", "filters", Map.of("vendor_id", "alpha"))).get("direct_count"));
        }
    }

    @Test
    void compressedCatalogPreservesMasterPlatformCoverageAndBudgetFilters() throws Exception {
        Path feed = temp.resolve("catalog.xml.gz"), snapshots = temp.resolve("snapshots");
        try (var output = new GZIPOutputStream(Files.newOutputStream(feed))) {
            output.write("""
                    <yml_catalog><shop><categories><category id="1">Текст</category></categories><offers>
                    <offer id="200" available="true">
                    <name>Редактор, электронная лицензия на 1 ПК</name><group_id>12</group_id>
                    <url>https://example.org/p/200</url><vendor>Example</vendor><vendorUrl>https://example.org/example/</vendorUrl>
                    <vendorCode>SKU-200</vendorCode><categoryId>1</categoryId><price>20.15</price><currencyId>RUR</currencyId>
                    <description>Платформа: Windows/Linux • Минимальная покупка: от 1 шт. • Срок действия: 12 мес.</description>
                    </offer></offers></shop></yml_catalog>
                    """.getBytes(StandardCharsets.UTF_8));
        }
        String offline = "http://127.0.0.1:1";
        try (var encoder = new EncoderClient(offline);
             var index = new SearchIndex(snapshots.toString(), offline, "lexical", 10)) {
            CatalogImporter.importSnapshot(feed, snapshots, false, encoder);
            index.open();
            var controller = new SearchController(index, new EventStore(temp.resolve("events.db").toString(), JSON));
            var filters = new java.util.LinkedHashMap<String, Object>(Map.of("platform", "Windows",
                    "device_count", 1, "term_months", 12, "max_price_minor", 2015));
            assertEquals(1, controller.search(Map.of("q", "SKU-200", "filters", filters)).get("direct_count"));
            filters.put("max_price_minor", 2014);
            assertEquals(0, controller.search(Map.of("q", "SKU-200", "filters", filters)).get("direct_count"));
            filters.put("max_price_minor", 2015);
            filters.put("device_count", 2);
            assertEquals(0, controller.search(Map.of("q", "SKU-200", "filters", filters)).get("direct_count"));
        }
    }

    @Test
    void liveStorefrontSelectsSortedFamiliesAndOffersAlternativeFilterValues() throws Exception {
        Path feed = temp.resolve("feed.xml"), snapshots = temp.resolve("snapshots");
        Files.writeString(feed, """
                <yml_catalog date="2026-11-25"><shop><categories>
                <category id="1">Графика</category><category id="2">Текст</category></categories><offers>
                """ + offer(100, 10, "acme", 1, 20) + offer(101, 10, "acme", 2, 25)
                + offer(102, 20, "beta", 2, 10) + offer(103, 30, "beta", 1, 30)
                + offer(104, 40, "acme", 1, 40) + offer(105, 50, "acme", 1, 50)
                + "</offers></shop></yml_catalog>");
        String offline = "http://127.0.0.1:1";
        try (var client = new EncoderClient(offline);
             var index = new SearchIndex(snapshots.toString(), offline, "off", 10)) {
            CatalogImporter.importSnapshot(feed, snapshots, false, client);
            index.open();
            var events = new EventStore(temp.resolve("events.db").toString(), JSON);
            var controller = new SearchController(index, events);
            var result = JSON.valueToTree(controller.search(Map.of("q", "редактор",
                    "strategy", "standard", "sort", "price_asc")));
            assertEquals(5, result.get("direct_count").asInt());
            assertEquals("102", result.at("/top4/0/offer_id").asString());
            assertEquals("acme", result.at("/facets/vendors/0/id").asString());
            assertEquals(3, result.at("/facets/vendors/0/count").asInt());
            // Both categories contain family 10, but within a facet value it counts only once.
            assertEquals(4, result.at("/facets/categories/0/count").asInt());
            assertEquals(2, result.at("/facets/categories/1/count").asInt());
            assertEquals("Текст", result.at("/facets/categories/1/label").asString());

            JsonNode scoped = JSON.valueToTree(controller.search(Map.of("q", "редактор",
                    "filters", Map.of("vendor_id", "acme", "category_id", "2"))));
            assertEquals("101", scoped.at("/top4/0/offer_id").asString());
            assertEquals(2, scoped.at("/facets/vendors").size());
            assertEquals(2, scoped.at("/facets/categories").size());
            assertEquals(3, scoped.at("/facets/categories/0/count").asInt());

            JsonNode chat = JSON.valueToTree(controller.search(Map.of("q", "редактор",
                    "strategy", "llm", "sort", "price_desc",
                    "assistant_history", List.of(Map.of("role", "user", "text", "Нужен редактор")))));
            assertFalse(chat.get("llm_applied").asBoolean());
            assertFalse(chat.get("assistant_message").asString().isBlank());
            assertEquals("105", chat.at("/top4/0/offer_id").asString());
            assertEquals("101", JSON.valueToTree(controller.search(Map.of(
                    "q", "SKU-101", "sort", "price_desc", "strategy", "standard")))
                    .at("/top4/0/offer_id").asString());
            assertThrows(RuntimeException.class, () -> controller.search(Map.of("q", "x",
                    "assistant_history", Collections.nCopies(9, Map.of("role", "user", "text", "x")))));
        }
    }
}
