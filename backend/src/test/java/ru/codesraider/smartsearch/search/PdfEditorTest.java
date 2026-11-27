package ru.codesraider.smartsearch.search;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.codesraider.smartsearch.analytics.EventStore;
import ru.codesraider.smartsearch.catalog.CatalogImporter;
import ru.codesraider.smartsearch.modelclient.EncoderClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class PdfEditorTest {
    @TempDir Path temp;

    @Test
    void editingAndLinuxMustBeProvenForEachDisplayedOffer() throws Exception {
        Path xml = temp.resolve("input.xml");
        Files.writeString(xml, """
                <yml_catalog date="test"><shop><categories><category id="127">ПО</category></categories><offers>
                """ + offer("linux-in-title", "1", "ПДФ Редактор, локальная лицензия для Linux", "", 900)
                    + offer("cheaper-windows", "1", "ПДФ Редактор для Windows", "Windows 10", 100)
                    + offer("linux-glibc", "2", "Master PDF Editor", "Linux glibc 2.28", 800)
                    + offer("reader", "3", "PDF Reader для Linux", "Linux", 100)
                    + offer("unknown-os", "4", "PDF Editor", "", 100)
                    + "</offers></shop></yml_catalog>");
        Path snapshots = temp.resolve("snapshots");
        String offline = "http://127.0.0.1:1";
        try (var encoder = new EncoderClient(offline)) {
            CatalogImporter.importSnapshot(xml, snapshots, false, encoder);
        }
        try (var index = new SearchIndex(snapshots.toString(), offline, "lexical", 10)) {
            index.open();
            var json = JsonMapper.builder().build();
            var controller = new SearchController(index, new EventStore(temp.resolve("events.db").toString(), json));
            JsonNode result = json.valueToTree(controller.search(Map.of("q",
                    "Какой редактор поставить на Linux, чтобы вносить правки в PDF-документы?")));
            assertEquals("linux", result.at("/applied_filters/platform").asString());
            assertEquals(Set.of("linux-in-title", "linux-glibc"), result.required("top4").valueStream()
                    .map(c -> c.required("offer_id").asString()).collect(Collectors.toSet()));
        }
    }

    private static String offer(String id, String family, String name, String platform, int price) {
        String param = platform.isEmpty() ? "" : "<param name=\"Платформа\">" + platform + "</param>";
        return """
                <offer id="%s" group_id="%s" available="true">
                <name>%s</name><vendor>Acme</vendor><vendorUrl>https://store.softline.ru/acme/</vendorUrl><categoryId>127</categoryId>
                <url>https://store.softline.ru/acme/%s/</url><currencyId>RUB</currencyId><price>%d</price>%s
                </offer>
                """.formatted(id, family, name, id, price, param);
    }
}
