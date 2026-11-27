package ru.codesraider.smartsearch.search;

import static org.junit.jupiter.api.Assertions.*;

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

class ProductLookupTest {
    @TempDir Path temp;

    @Test
    void noisyProductNamesRequireCatalogEvidenceAndVersionsKeepEditionAndFilters() throws Exception {
        String offers = offer("recorder", "1", "Softworks ScreenRecorder", 100)
                + offer("drawing", "2", "VectorDraw Essentials 2024", 150)
                + offer("base", "3", "Photo Maker 2023, Standard", 20)
                + offer("other-year", "3", "Photo Maker 2022, Ultimate", 10)
                + offer("requested", "3", "Photo Maker 2023, Ultimate", 200)
                + offer("version-basic", "4", "Creator Studio 26, Basic", 50)
                + offer("version-pro", "4", "Creator Studio 26, Ultimate", 150);
        Path xml = temp.resolve("feed.xml"), snapshots = temp.resolve("snapshots");
        Files.writeString(xml, "<yml_catalog date=\"test\"><shop><categories><category id=\"127\">ПО</category>"
                + "</categories><offers>" + offers + "</offers></shop></yml_catalog>");
        String offline = "http://127.0.0.1:1";
        try (var encoder = new EncoderClient(offline); var index = new SearchIndex(snapshots.toString(),offline,"lexical",50)) {
            CatalogImporter.importSnapshot(xml, snapshots, false, encoder);
            index.open();
            var json = JsonMapper.builder().build();
            var controller = new SearchController(index, new EventStore(temp.resolve("events.db").toString(),json));
            assertEquals("recorder", result(controller,"Softworks ScreenRecodrer",Map.of()).at("/top4/0/offer_id").asString());
            assertEquals("drawing", result(controller,"Вектор Дро Эссеншиалс 2024",Map.of()).at("/top4/0/offer_id").asString());
            assertEquals(0, result(controller,"Вектор Дро Эссеншиалс 2021",Map.of()).at("/direct_count").asInt());
            assertEquals("requested",result(controller,"Photo Maker 2023 именно в редакции Ultimate",Map.of()).at("/top4/0/offer_id").asString());
            JsonNode selected = json.valueToTree(controller.search(Map.of("q","Photo Maker 2023 именно в редакции Ultimate",
                    "selected_target",Map.of("kind","family","target_id","3"))));
            assertEquals("requested",selected.at("/top4/0/offer_id").asString());
            String unsupported = "Creator Studio Ultimate именно версии 25.0";
            JsonNode substitute = result(controller,unsupported,Map.of());
            assertEquals(0,substitute.at("/direct_count").asInt());
            assertEquals("version-pro",substitute.at("/alternatives/0/offer_id").asString());
            assertTrue(substitute.at("/alternatives/0/differences/0").asString().contains("26 вместо запрошенной 25.0"));
            JsonNode chat = json.valueToTree(controller.search(Map.of("q",unsupported,"strategy","llm")));
            assertTrue(chat.at("/assistant_message").asString().contains("26 вместо запрошенной 25.0"));
            assertTrue(result(controller,unsupported,Map.of("platform","linux")).required("alternatives").isEmpty());
            assertEquals("version-pro",result(controller,"Creator Studio Ultimate версии 26.0",Map.of()).at("/top4/0/offer_id").asString());
        }
    }

    private JsonNode result(SearchController controller, String q, Map<String,Object> filters) throws Exception {
        return JsonMapper.builder().build().valueToTree(controller.search(Map.of("q",q,"filters",filters)));
    }

    private static String offer(String id,String group,String name,int price) {
        return """
                <offer id="%s" group_id="%s" available="true"><name>%s</name>
                <vendor>Softworks</vendor><vendorUrl>https://store.softline.ru/softworks/</vendorUrl>
                <url>https://store.softline.ru/softworks/%s/</url><categoryId>127</categoryId>
                <price>%d</price><currencyId>RUB</currencyId><param name="Платформа">Windows</param></offer>
                """.formatted(id,group,name,id,price);
    }
}
