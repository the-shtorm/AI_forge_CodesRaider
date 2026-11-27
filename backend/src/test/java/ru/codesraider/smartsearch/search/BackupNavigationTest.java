package ru.codesraider.smartsearch.search;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.codesraider.smartsearch.analytics.EventStore;
import ru.codesraider.smartsearch.catalog.CatalogImporter;
import ru.codesraider.smartsearch.modelclient.EncoderClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class BackupNavigationTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private SearchIndex index;
    private SearchController search;

    @BeforeEach
    void open(@TempDir Path root) throws Exception {
        Path feed = root.resolve("feed.xml");
        Files.writeString(feed, """
                <yml_catalog date="test"><shop><categories>
                <category id="434126">Резервное копирование</category>
                <category id="1033">Программное обеспечение для резервного копирования</category>
                </categories><offers>
                <offer id="469686" group_id="239661" available="true">
                  <name>Paragon VM Backup</name><vendor>Paragon</vendor>
                  <vendorUrl>https://store.softline.ru/paragon/</vendorUrl>
                  <url>https://store.softline.ru/paragon/vm-backup-469686/</url>
                  <categoryId>1033</categoryId><price>100</price><currencyId>RUB</currencyId>
                </offer>
                <offer id="469685" group_id="239661" available="true">
                  <name>Paragon Backup для физического сервера</name><vendor>Paragon</vendor>
                  <vendorUrl>https://store.softline.ru/paragon/</vendorUrl>
                  <url>https://store.softline.ru/paragon/backup-469685/</url>
                  <categoryId>1033</categoryId><price>50</price><currencyId>RUB</currencyId>
                </offer>
                <offer id="527421" group_id="434134" available="true">
                  <name>Дисковое пространство для хранения резервных копий виртуальных машин</name><vendor>Softline</vendor>
                  <vendorUrl>https://store.softline.ru/softline/</vendorUrl>
                  <url>https://store.softline.ru/softline/storage-527421/</url>
                  <categoryId>1033</categoryId><price>10</price><currencyId>RUB</currencyId>
                </offer></offers></shop></yml_catalog>
                """);
        Path snapshots = root.resolve("snapshots");
        String offline = "http://127.0.0.1:1";
        try (var encoder = new EncoderClient(offline)) {
            CatalogImporter.importSnapshot(feed, snapshots, false, encoder);
        }
        index = new SearchIndex(snapshots.toString(), offline, "lexical", 10);
        index.open();
        search = new SearchController(index, new EventStore(root.resolve("events.db").toString(), json),
                new ModelClient("", "", "", 100));
    }

    @AfterEach
    void close() throws Exception {
        if (index != null) index.close();
    }

    @Test
    void emptyHomonymFallsBackToBackupProductsUnlessTheCategoryIsExplicit() throws Exception {
        String query = "резервное копирование";
        JsonNode result = json.valueToTree(search.search(Map.of("q", query)));
        assertEquals("BACKUP", result.at("/program/purpose").asString());
        assertEquals("family/239661", result.at("/top4/0/family_key").asString());

        JsonNode filtered = json.valueToTree(search.search(Map.of("q", query,
                "filters", Map.of("category_id", "434126"))));
        assertEquals(0, filtered.get("direct_count").asInt());

        JsonNode selected = json.valueToTree(search.search(Map.of("q", query,
                "selected_target", Map.of("kind", "category", "target_id", "434126"))));
        assertEquals("434126", selected.at("/primary_target/id").asString());
        assertEquals(0, selected.get("direct_count").asInt());

        JsonNode populated = json.valueToTree(search.search(Map.of("q",
                "Программное обеспечение для резервного копирования")));
        assertEquals("1033", populated.at("/primary_target/id").asString());
        assertEquals("family/239661", populated.at("/top4/0/family_key").asString());
    }

    @Test
    void virtualMachineBackupSelectsTheSoftwareForThatWorkloadBeforeGrouping() throws Exception {
        String query = "Подбираем средство для резервных копий виртуальных машин.";
        JsonNode result = json.valueToTree(search.search(Map.of("q", query)));
        assertEquals("BACKUP", result.at("/program/purpose").asString());
        assertEquals(1, result.get("direct_count").asInt());
        assertEquals("469686", result.at("/top4/0/offer_id").asString());

        JsonNode storageOnly = json.valueToTree(search.search(Map.of("q", "Бэкап ВМ",
                "filters", Map.of("vendor_id", "softline"))));
        assertEquals(0, storageOnly.get("direct_count").asInt());
    }
}
