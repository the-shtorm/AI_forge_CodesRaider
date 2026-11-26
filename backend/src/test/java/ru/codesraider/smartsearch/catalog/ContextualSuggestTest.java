package ru.codesraider.smartsearch.catalog;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.codesraider.smartsearch.analytics.EventStore;
import ru.codesraider.smartsearch.modelclient.EncoderClient;
import ru.codesraider.smartsearch.search.SearchController;
import ru.codesraider.smartsearch.search.SearchIndex;
import tools.jackson.databind.json.JsonMapper;

class ContextualSuggestTest {
    @TempDir Path root;

    @Test
    void suggestionsUseTheSelectedProfileAfterHonoringExactSkuAndCategory() throws Exception {
        // The wanted software family is outside the original 32-name cutoff.
        var offers = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            offers.append(offer(i, "86563", "Сервер " + i, "электроника"));
        }
        offers.append(offer(40, "369", "Сервер почты", "софт"));
        Path xml = root.resolve("catalog.xml"), snapshots = root.resolve("snapshots");
        Files.writeString(xml, """
                <yml_catalog><shop><categories>
                  <category id="86563">Rack-серверы</category>
                  <category id="369">Серверное ПО</category>
                </categories><offers>%s</offers></shop></yml_catalog>
                """.formatted(offers));
        try (var encoder = new EncoderClient("http://127.0.0.1:1")) {
            CatalogImporter.importSnapshot(xml, snapshots, false, encoder);
        }
        try (var index = new SearchIndex(snapshots.toString(), "http://127.0.0.1:1", "lexical", 100)) {
            index.open();
            var json = JsonMapper.builder().build();
            var api = new SearchController(index, new EventStore(root.resolve("events.db").toString(), json));
            var hardware = api.suggest("сервер", null, "hardware_buyer");
            var software = api.suggest("сервер", null, "software_admin");
            assertEquals("86563", hardware.getFirst().get("target_id"));
            assertEquals("369", software.getFirst().get("target_id"));
            assertEquals(6, hardware.size());
            assertEquals(6, hardware.stream().distinct().count());
            assertEquals(software, api.suggest("СЕРВЕР", null, "software_admin"));
            assertEquals(api.suggest("сервер", null, null), api.suggest("сервер", null, "other"));

            var scoped = api.suggest("сервер", "369", "hardware_buyer");
            assertEquals(List.of("369", "g40"), scoped.stream().map(s -> s.get("target_id")).toList());
            assertTrue(api.suggest("сервер", "absent", "hardware_buyer").isEmpty());
            assertEquals(Map.of("kind", "offer", "target_id", "40", "label", "Сервер почты"),
                    api.suggest("SKU-40", null, "hardware_buyer").getFirst());

            var admin = Map.of("profile_id", "software_admin");
            var softFirst = json.valueToTree(api.search(Map.of("q", "сервер", "context", admin)));
            assertEquals("40", softFirst.at("/top4/0/offer_id").asText());
            var device = json.valueToTree(api.search(Map.of("q", "готовый сервер", "context", admin)));
            assertFalse(device.get("top4").isEmpty());
            assertNotEquals("40", device.at("/top4/0/offer_id").asText());
            var hardScope = json.valueToTree(api.search(Map.of("q", "сервер", "context", admin,
                    "filters", Map.of("category_id", "86563"))));
            assertEquals(device.at("/top4/0/offer_id"), hardScope.at("/top4/0/offer_id"));
        }
    }

    private static String offer(int id, String category, String name, String type) {
        return """
                <offer id="%1$d" group_id="g%1$d" available="true">
                  <name>%3$s</name><url>https://test.invalid/shop/%1$d/</url>
                  <vendor>Тест</vendor><vendorUrl>https://test.invalid/shop/</vendorUrl>
                  <vendorCode>SKU-%1$d</vendorCode><categoryId>%2$s</categoryId>
                  <price>100</price><currencyId>RUR</currencyId>
                  <param name="Тип продукта">%4$s</param>
                </offer>
                """.formatted(id, category, name, type);
    }
}
