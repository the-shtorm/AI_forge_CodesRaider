package ru.codesraider.smartsearch.search;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.codesraider.smartsearch.analytics.EventStore;
import ru.codesraider.smartsearch.catalog.CatalogImporter;
import ru.codesraider.smartsearch.modelclient.EncoderClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ServerLicenseTest {
    @TempDir Path temp;
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void serverRightVersionAndPlatformBelongToOneOffer() throws Exception {
        Path source = temp.resolve("catalog.xml");
        Files.writeString(source, """
                <yml_catalog date="test"><shop><categories><category id="687">ПО</category></categories><offers>
                """ + offer("25266", "4.0", "Клиентская лицензия на подключение к серверу", "Windows", 100)
                    + offer("25267", "4.0", "Лицензия на право использования на сервере", "Windows", 900)
                    + offer("other-version", "5.0", "Серверная лицензия", "Linux", 150)
                    + "</offers></shop></yml_catalog>");
        Path snapshots = temp.resolve("snapshots");
        String offline = "http://127.0.0.1:1";
        try (var encoder = new EncoderClient(offline)) {
            CatalogImporter.importSnapshot(source, snapshots, false, encoder);
        }
        try (var index = new SearchIndex(snapshots.toString(), offline, "lexical", 10)) {
            index.open();
            var search = new SearchController(index, new EventStore(temp.resolve("events.db").toString(), json),
                    new ModelClient("", "", "", 100));
            String requested = "КриптоПро CSP 4.0, нужна лицензия именно на сервер";
            assertEquals("25267", result(search, requested, Map.of()).at("/top4/0/offer_id").asString());
            JsonNode selected = json.valueToTree(search.search(Map.of("q", requested, "selected_target",
                    Map.of("kind", "family", "target_id", "22268"))));
            assertEquals("25267", selected.at("/top4/0/offer_id").asString());
            assertEquals(0, result(search, requested, Map.of("platform", "Linux")).at("/direct_count").asInt());
            assertEquals("25266", result(search, "КриптоПро CSP 4.0", Map.of()).at("/top4/0/offer_id").asString());
            assertEquals("25266", result(search, "КриптоПро CSP 4.0, лицензия не на сервер", Map.of())
                    .at("/top4/0/offer_id").asString());
        }
    }

    private JsonNode result(SearchController search, String q, Map<String, String> filters) throws Exception {
        return json.valueToTree(search.search(Map.of("q", q, "filters", filters)));
    }

    private static String offer(String id, String version, String license, String platform, int price) {
        return """
                <offer id="%s" available="true" group_id="22268">
                  <name>КриптоПро CSP %s, %s</name><vendor>КриптоПро</vendor><vendorUrl>https://store.softline.ru/kripto-pro/</vendorUrl>
                  <url>https://store.softline.ru/kripto-pro/%s/</url><categoryId>687</categoryId>
                  <currencyId>RUB</currencyId><price>%d</price><param name="Платформа">%s</param>
                </offer>
                """.formatted(id, version, license, id, price, platform);
    }
}
