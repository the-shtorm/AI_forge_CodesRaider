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

class DeletedFileRecoveryTest {
    @Test
    void deletedPhotosRequireRecoveryOfLocalFilesOnTheRequestedPlatform(@TempDir Path temp) throws Exception {
        Path feed = temp.resolve("catalog.xml");
        Files.writeString(feed, """
                <yml_catalog date="test"><shop><categories>
                <category id="5922">ПО для восстановления данных</category>
                <category id="1033">Резервное копирование</category></categories><offers>
                """ + offer("101", "recovery", "NTFS Undelete для ПК", "5922", "Windows", 900)
                    + offer("102", "recovery", "Undelete Desktop", "5922", "Linux", 100)
                    + offer("103", "mobile", "Photo Recovery for Android", "5922", "Windows", 90)
                    + offer("104", "backup", "Backup Suite", "1033", "Windows", 200)
                    + "</offers></shop></yml_catalog>");
        Path snapshots = temp.resolve("snapshots");
        String offline = "http://127.0.0.1:1";
        try (var encoder = new EncoderClient(offline)) {
            CatalogImporter.importSnapshot(feed, snapshots, false, encoder);
        }
        try (var index = new SearchIndex(snapshots.toString(), offline, "lexical", 10)) {
            index.open();
            JsonMapper json = JsonMapper.builder().build();
            var search = new SearchController(index, new EventStore(temp.resolve("events.db").toString(), json),
                    new ModelClient("", "", "", 100));
            JsonNode restored = json.valueToTree(search.search(Map.of(
                    "q", "Удалил фотографии на компьютере с Windows. Чем их вернуть?")));
            assertEquals(1, restored.at("/direct_count").asInt());
            assertEquals("101", restored.at("/top4/0/offer_id").asString());
            assertEquals("windows", restored.at("/applied_filters/platform").asString());
            JsonNode fromBackup = json.valueToTree(search.search(Map.of(
                    "q", "Как восстановить удаленные файлы из резервной копии?", "filters", Map.of("category_id","1033"))));
            assertEquals("104", fromBackup.at("/top4/0/offer_id").asString());
        }
    }

    private static String offer(String id, String family, String name, String category, String os, int price) {
        return """
                <offer id="%s" available="true" group_id="%s">
                   <vendor>Example</vendor><vendorUrl>https://store.softline.ru/example/</vendorUrl>
                   <name>%s</name><url>https://store.softline.ru/example/%s/</url>
                   <categoryId>%s</categoryId><param name="Платформа">%s</param>
                   <price>%d</price><currencyId>RUB</currencyId>
                </offer>
                """.formatted(id, family, name, id, category, os, price);
    }
}
