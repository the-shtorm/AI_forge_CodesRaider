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
        return offer(id, family, vendor, category, price, "Редактор " + id);
    }

    private static String offer(int id, int family, String vendor, int category, int price, String name) {
        return """
                <offer id="%1$d" group_id="%2$d" available="true"><name>%6$s</name>
                <url>https://example.org/p/%1$d</url><vendor>%3$s</vendor>
                <vendorUrl>https://example.org/%3$s/</vendorUrl><vendorCode>SKU-%1$d</vendorCode>
                <categoryId>%4$d</categoryId><price>%5$d</price><currencyId>RUR</currencyId></offer>
                """.formatted(id, family, vendor, category, price, name);
    }

    @Test
    void purposeSearchFindsVerifiedWebinarAndRemoteAccessOffersInTheFullCatalogTaxonomy() throws Exception {
        Path feed = temp.resolve("catalog.xml"), snapshots = temp.resolve("snapshots");
        Files.writeString(feed, """
                <yml_catalog><shop><categories>
                <category id="7388">Связь</category>
                <category id="327030">Другие решения удаленного доступа</category>
                </categories><offers>%s</offers></shop></yml_catalog>
                """.formatted(String.join("",
                offer(535950, 414326, "webinar", 7388, 100, "МТС Линк Optimum 30"),
                offer(541589, 447242, "webinar", 7388, 200, "МТС Линк Стандарт"),
                offer(541591, 447243, "webinar", 7388, 300, "МТС Линк Про"),
                offer(222275, 146933, "sarsys", 7388, 10, "SpRobot SIP-канал Автообзвона"),
                offer(100, 101, "webinar", 7388, 10, "Поддержка телефонии"),
                offer(402265, 306539, "anydesk", 327030, 400, "AnyDesk Solo"))));
        String offline = "http://127.0.0.1:1";
        try (var encoder = new EncoderClient(offline);
             var index = new SearchIndex(snapshots.toString(), offline, "lexical", 10)) {
            CatalogImporter.importSnapshot(feed, snapshots, false, encoder);
            index.open();
            var controller = new SearchController(index, new EventStore(temp.resolve("events.db").toString(), JSON));
            assertAll(
                    () -> {
                        JsonNode result = JSON.valueToTree(controller.search(Map.of("q", "вебинар")));
                        assertEquals("WEBINAR", result.at("/program/purpose").asString());
                        assertEquals(3, result.required("direct_count").asInt());
                        var offerIds = result.required("top4").valueStream()
                                .map(card -> card.required("offer_id").asString()).sorted().toList();
                        assertEquals(List.of("535950", "541589", "541591"), offerIds);
                    },
                    () -> {
                        JsonNode result = JSON.valueToTree(controller.search(Map.of("q", "удалённый доступ")));
                        assertEquals("REMOTE_ACCESS", result.at("/program/purpose").asString());
                        assertEquals(1, result.required("direct_count").asInt());
                        assertEquals("402265", result.at("/top4/0/offer_id").asString());
                    });
            for (String query : List.of("вебинар", "удалённый доступ")) {
                for (var filters : List.of(Map.of("max_price_minor", 1), Map.of("version", "9.9"))) {
                    JsonNode result = JSON.valueToTree(controller.search(Map.of("q", query, "filters", filters)));
                    assertEquals(0, result.required("direct_count").asInt());
                    assertTrue(result.required("top4").isEmpty());
                }
            }
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
