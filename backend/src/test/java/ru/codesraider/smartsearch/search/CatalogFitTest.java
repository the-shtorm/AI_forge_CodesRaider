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

class CatalogFitTest {
    @Test
    void checksTheNamedModelBeforeShowingACatalogAccessory(@TempDir Path dir) throws Exception {
        String catalog = """
                <yml_catalog><shop><categories><category id="12">Принтеры</category>
                <category id="25">Расходные материалы</category></categories><offers>
                """ + offer("100","12","ML100","Принтер ML100")
                + offer("200","25","CT101","Картридж для ML100 Pro-Edition / ML105")
                + offer("201","25","CT102","Картридж для ML100X")
                + offer("202","25","CT103","Картридж для ML101, не для ML100")
                + offer("203","25","CT104","Картридж для Acme ML99/ML100/ML102")
                + "</offers></shop></yml_catalog>";
        Path feed = dir.resolve("feed.xml"), snapshots = dir.resolve("snapshots");
        Files.writeString(feed, catalog);
        String offline = "http://127.0.0.1:1";
        try (var encoder = new EncoderClient(offline); var index = new SearchIndex(snapshots.toString(), offline, "lexical", 10)) {
            CatalogImporter.importSnapshot(feed, snapshots, false, encoder);
            index.open();
            var json = JsonMapper.builder().build();
            var search = new SearchController(index, new EventStore(dir.resolve("events.db").toString(), json));
            JsonNode response = json.valueToTree(search.search(Map.of("q","ML100")));
            assertEquals("100", response.at("/top4/0/offer_id").asString());
            assertEquals(1, response.required("upsell").size());
            assertEquals("203", response.at("/upsell/0/offer_id").asString());
            assertEquals("100", response.at("/upsell/0/anchor_offer_id").asString());
            assertEquals("catalog_compatibility", response.at("/upsell/0/checks/0/field").asString());
            assertEquals("https://example.org/203/", response.at("/upsell/0/checks/0/source_url").asString());
            assertEquals("https://example.org/203/", search.explain(Map.of("search_id", response.get("search_id").asString(),
                    "offer_id", "203")).get("source_url"));
            for (String refusal : new String[]{"без картриджей", "не предлагайте дополнения"}) {
                JsonNode declined = json.valueToTree(search.search(Map.of("q", "ML100, " + refusal)));
                assertEquals("100", declined.at("/top4/0/offer_id").asString());
                assertEquals(0, declined.required("upsell").size());
            }
        }
    }

    private static String offer(String id, String category, String sku, String title) {
        return """
                <offer id="%s" group_id="%s" available="true"><name>%s</name>
                <categoryId>%s</categoryId><vendorCode>%s</vendorCode><vendor>Acme</vendor>
                <vendorUrl>https://example.org/acme/</vendorUrl><url>https://example.org/%s/</url>
                <price>100</price><currencyId>RUB</currencyId></offer>
                """.formatted(id,id,title,category,sku,id);
    }
}
