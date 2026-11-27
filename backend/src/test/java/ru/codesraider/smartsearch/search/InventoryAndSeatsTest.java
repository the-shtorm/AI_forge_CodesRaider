package ru.codesraider.smartsearch.search;

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
import static org.junit.jupiter.api.Assertions.*;

class InventoryAndSeatsTest {
    @Test
    void separatesInventoryCoverageFromConsoleInstallationsAndSeatsFromDevices(@TempDir Path dir) throws Exception {
        String offers = offer("100", "inventory", "Инвентаризация компьютеров, лицензия на 1 компьютер, учет 25 ПК", "")
                + offer("099", "inventory", "Инвентаризация компьютеров, лицензия на 1 компьютер, учет 78 ПК", "")
                + offer("101", "inventory", "Инвентаризация компьютеров, лицензия на 1 компьютер, учет 75 ПК", "")
                + offer("102", "kit", "Комплект Эксперт, коробочная версия", "<param name=\"К-во пользователей до\">5</param>"
                        + "<param name=\"Срок действия\">12 мес.</param>");
        Path feed = dir.resolve("input.xml"), snapshots = dir.resolve("snapshots");
        Files.writeString(feed, "<yml_catalog date=\"test\"><shop><categories><category id=\"1\">ПО</category></categories><offers>"
                + offers + "</offers></shop></yml_catalog>");
        String offline = "http://127.0.0.1:1";
        try (var encoder = new EncoderClient(offline); var index = new SearchIndex(snapshots.toString(), offline, "lexical", 10)) {
            CatalogImporter.importSnapshot(feed, snapshots, false, encoder);
            index.open();
            var json = JsonMapper.builder().build();
            var controller = new SearchController(index, new EventStore(dir.resolve("events.db").toString(), json));
            String q = "В офисе 60 компьютеров. Как провести их инвентаризацию?";
            JsonNode result = json.valueToTree(controller.search(Map.of("q", q)));
            assertEquals("101", result.at("/top4/0/offer_id").asString());
            assertFalse(result.required("applied_filters").has("device_count"));
            assertEquals(0, controller.search(Map.of("q", q, "filters", Map.of("device_count", 10))).get("direct_count"));
            assertEquals(0, controller.search(Map.of("q", q.replace("60", "80"))).get("direct_count"));
            String seats = "Комплект Эксперт в коробке на пять пользователей на год";
            JsonNode kit = json.valueToTree(controller.search(Map.of("q", seats)));
            assertEquals("102", kit.at("/top4/0/offer_id").asString());
            assertEquals(5, kit.at("/applied_filters/seat_count").asInt());
            JsonNode devices = json.valueToTree(controller.search(Map.of("q", seats.replace("пользователей", "устройств"))));
            assertEquals(5, devices.at("/applied_filters/device_count").asInt());
            assertEquals(0, devices.required("direct_count").asInt());
        }
    }

    private static String offer(String id, String group, String name, String params) {
        return """
                <offer id="%s" group_id="%s" available="true"><name>%s</name>
                <vendor>Acme</vendor><vendorUrl>https://example.org/acme/</vendorUrl>
                <url>https://example.org/%s/</url><price>250</price><currencyId>RUB</currencyId>
                <categoryId>1</categoryId>%s</offer>
                """.formatted(id, group, name, id, params);
    }
}
