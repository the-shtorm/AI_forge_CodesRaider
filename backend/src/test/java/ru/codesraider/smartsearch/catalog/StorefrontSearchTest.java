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

    @Test
    void searchesStandaloneOffersAndPreservesFamilyNavigation() throws Exception {
        Path feed = temp.resolve("feed.xml"), snapshots = temp.resolve("snapshots");
        String standalone = offer(201, 1, "alpha", 2, 20, "Термобутылка с логотипом, 500 мл.")
                .replace(" group_id=\"1\"", "").replace("SKU-201", "BTL-005");
        Files.writeString(feed, """
                <yml_catalog><shop><categories><category id="2">Аксессуары</category>
                <category id="3">Электроника</category></categories><offers>
                """ + standalone + offer(202, 72, "beta", 2, 50, "Acme Studio (годовая лицензия)")
                        .replace("</offer>", "<param name=\"Версия\">1.0</param></offer>")
                + offer(203, 72, "beta", 2, 80, "Acme Studio (бессрочная лицензия)")
                        .replace("</offer>", "<param name=\"Версия\">2.0</param></offer>")
                + offer(204, 73, "alpha", 3, 50, "Мышь SKU-204").replace(" group_id=\"73\"", "")
                + "</offers></shop></yml_catalog>");
        String offline = "http://127.0.0.1:1";
        try (var encoder = new EncoderClient(offline);
             var index = new SearchIndex(snapshots.toString(), offline, "lexical", 10)) {
            CatalogImporter.importSnapshot(feed, snapshots, false, encoder);
            index.open();
            var controller = new SearchController(index, new EventStore(temp.resolve("events.db").toString(), JSON));
            for (String query : List.of("термобутылка", "Термобутылка с логотипом, 500 мл.", "BTL-005")) {
                JsonNode result = JSON.valueToTree(controller.search(Map.of("q", query)));
                assertEquals(1, result.path("direct_count").asInt(), query);
                assertEquals("offer/201", result.at("/top4/0/family_key").asString());
                assertEquals("offer", result.at("/top4/0/result_kind").asString());
                assertEquals("201", result.at("/top4/0/target_id").asString());
                assertEquals(1, result.at("/top4/0/offer_count").asInt());
            }
            JsonNode family = JSON.valueToTree(controller.search(Map.of("q", "Acme Studio")));
            assertEquals(1, family.path("direct_count").asInt());
            assertEquals("family", family.at("/top4/0/result_kind").asString());
            assertEquals("72", family.at("/top4/0/target_id").asString());
            assertEquals(2, family.at("/top4/0/offer_count").asInt());
            JsonNode entity = JSON.valueToTree(controller.entity("family", "72", Map.of()));
            assertEquals(List.of("202", "203"), entity.path("offers").valueStream()
                    .map(item -> item.path("offer_id").asString()).toList());
            JsonNode hardware = JSON.valueToTree(controller.search(Map.of("q", "мышь")));
            assertEquals("family", hardware.at("/top4/0/target_kind").asString());
            assertEquals("alpha~sku-204", hardware.at("/top4/0/target_id").asString());
            JsonNode exactTitle = JSON.valueToTree(controller.search(Map.of("q", "Acme Studio (годовая лицензия)")));
            assertEquals("offer", exactTitle.at("/top4/0/target_kind").asString());
            assertEquals("202", exactTitle.at("/top4/0/target_id").asString());
            JsonNode version = JSON.valueToTree(controller.search(Map.of("q", "Acme Studio", "filters", Map.of("version", "2.0"))));
            assertEquals("offer", version.at("/top4/0/target_kind").asString());
            assertEquals("203", version.at("/top4/0/target_id").asString());
            assertTrue(controller.suggest("термобут", null, null).stream()
                    .anyMatch(item -> "offer".equals(item.get("kind")) && "201".equals(item.get("target_id"))));
            JsonNode selected = JSON.valueToTree(controller.search(Map.of("q", "Acme Studio",
                    "selected_target", Map.of("kind", "offer", "target_id", "203"))));
            assertEquals("offer", selected.at("/top4/0/target_kind").asString());
            assertEquals("203", selected.at("/top4/0/target_id").asString());
            assertEquals("family/72", selected.at("/top4/0/family_key").asString());
        }
    }

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
                offer(535950, 414326, "webinar", 7388, 100, "МТС Линк Optimum, до 30 участников вебинара"),
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
            JsonNode capacity = JSON.valueToTree(controller.search(Map.of("q", "Чем провести онлайн-семинар для группы из 30 слушателей?")));
            assertEquals(1, capacity.required("direct_count").asInt());
            assertEquals("535950", capacity.at("/top4/0/offer_id").asString());
            assertEquals(0, controller.search(Map.of("q", "Вебинар на 31 участника")).get("direct_count"));
        }
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
                        .replace("SKU-503", "460000 085 54")
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
            for (String query : List.of("460000 085 54", "sku: 460000 085 54", "артикул 460000 085 54")) {
                assertEquals("503", JSON.valueToTree(search.search(Map.of("q", query))).at("/primary_target/id").asString());
            }
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
    void budgetDistinguishesOneListedOfferFromARequestForCapacity() throws Exception {
        Path feed = temp.resolve("prices.xml"), snapshots = temp.resolve("snapshots");
        String minOne = "<param name=\"Минимальное количество\">1</param>";
        String devices = "<param name=\"Количество устройств\">1</param>";
        String offers = offer(1, 1, "acme", 1, 500).replace("</offer>", minOne + devices + "</offer>")
                + offer(2, 2, "acme", 1, 500)
                + offer(3, 3, "acme", 1, 500).replace("</offer>", devices + "</offer>")
                        .replace("available=\"true\"", "available=\"false\"")
                + offer(4, 4, "acme", 1, 500).replace("</offer>",
                        "<param name=\"Минимальное количество\">2</param>"
                        + "<param name=\"Единица продажи\">устройство</param>" + devices + "</offer>");
        Files.writeString(feed, "<yml_catalog><shop><categories><category id=\"1\">Разное</category></categories>"
                + "<offers>" + offers + "</offers></shop></yml_catalog>");
        String offline = "http://127.0.0.1:1";
        try (var encoder = new EncoderClient(offline); var index = new SearchIndex(snapshots.toString(), offline, "lexical", 10)) {
            CatalogImporter.importSnapshot(feed, snapshots, false, encoder);
            index.open();
            var search = new SearchController(index, new EventStore(temp.resolve("events.db").toString(), JSON));
            assertEquals(1, JSON.valueToTree(search.catalog(Map.of("max_price_minor", "50000"))).path("total").asInt());
            assertEquals(1, search.search(Map.of("q", "SKU-1", "filters", Map.of("max_price_minor", 50000))).get("direct_count"));
            for (var filters : List.of(Map.of("max_price_minor", 49999), Map.of("max_price_minor", 50000, "device_count", 1),
                    Map.of("max_price_minor", 50000, "currency", "USD"))) {
                assertEquals(0, search.search(Map.of("q", "SKU-1", "filters", filters)).get("direct_count"));
            }
            assertEquals(0, search.search(Map.of("q", "SKU-4", "filters",
                    Map.of("max_price_minor", 149999, "device_count", 3))).get("direct_count"));
            assertEquals(1, search.search(Map.of("q", "SKU-4", "filters",
                    Map.of("max_price_minor", 150000, "device_count", 3))).get("direct_count"));
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
