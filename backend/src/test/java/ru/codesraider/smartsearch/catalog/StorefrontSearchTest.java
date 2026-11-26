package ru.codesraider.smartsearch.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.codesraider.smartsearch.analytics.SearchJournal;
import ru.codesraider.smartsearch.modelclient.EncoderClient;
import ru.codesraider.smartsearch.search.SearchController;
import ru.codesraider.smartsearch.search.SearchIndex;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

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
            var journal = new SearchJournal(JSON, temp.resolve("events.db").toString());
            var controller = new SearchController(index, journal);
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
