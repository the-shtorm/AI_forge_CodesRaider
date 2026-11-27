package ru.codesraider.smartsearch.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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

class SoftwareIntentTest {
    @Test
    void onlyOffersMeetingTheSoftwareTaskReachTheStorefront(@TempDir Path dir) throws Exception {
        Path feed = dir.resolve("catalog.xml"), snapshots = dir.resolve("snapshots");
        String windows = "<param name=\"Платформа\">Windows</param>";
        String year = "<param name=\"Срок действия\">12 мес.</param>";
        Files.writeString(feed, """
                <yml_catalog><shop><categories>
                  <category id="1">ПО для видеонаблюдения</category><category id="2">Камеры</category>
                  <category id="3">Конвертеры и кодировщики</category><category id="4">Переводчики</category>
                  <category id="5">Схемы и диаграммы</category>
                </categories><offers>
                """ + offer("rec", "rec", 1, "ПО видеонаблюдения, лицензия на канал", "")
                    + offer("face", "face", 1, "VideoRecord аналитика, распознавание лиц", "")
                    + offer("cam", "cam", 2, "IP-камера, видеозапись", "")
                    + offer("company", "convert", 3, "Video Converter, Business License", year)
                    + offer("personal", "convert", 3, "Video Converter Personal", year)
                    + offer("short", "other", 3, "Video Converter Business License", "<param name=\"Срок действия\">1 мес.</param>")
                    + offer("branded", "branded", 3, "Novalux Video Converter", "")
                    + offer("codec", "codec", 3, "HEVC Video Converter", "")
                    + offer("enru", "translate", 4, "Переводчик англо-русский", windows)
                    + offer("reverse", "reverse", 4, "Переводчик русско-английский", windows)
                    + offer("linux", "translate", 4, "Переводчик англо-русский", "<param name=\"Платформа\">Linux</param>")
                    + offer("charts-web", "web", 5, "Chart library for Web", "")
                    + offer("charts-wpf", "wpf", 5, "Chart library for WPF", "")
                    + offer("drawing", "drawing", 5, "Standalone drawing tool for Web", "")
                    + "</offers></shop></yml_catalog>");
        String offline = "http://127.0.0.1:1";
        try (var encoder = new EncoderClient(offline)) {
            CatalogImporter.importSnapshot(feed, snapshots, false, encoder);
        }
        try (var index = new SearchIndex(snapshots.toString(), offline, "lexical", 10)) {
            index.open();
            var mapper = JsonMapper.builder().build();
            var api = new SearchController(index, new EventStore(dir.resolve("events.db").toString(), mapper));
            assertEquals(Set.of("rec"), result(api, mapper, "Ищу софт для записи с IP-камеры"));
            assertEquals(Set.of("company"), result(api, mapper, "Видеоконвертер с лицензией для компании на год"));
            assertEquals(Set.of("branded"), result(api, mapper, "Novalux Video Converter"));
            assertEquals(Set.of("codec"), result(api, mapper, "HEVC Video Converter"));
            assertEquals(Set.of("enru"), result(api, mapper, "Ищу переводчик с английского на русский для Windows"));
            assertEquals(Set.of("reverse"), result(api, mapper, "Ищу переводчик с русского на английский для Windows"));
            assertEquals(Set.of("charts-web", "charts-wpf"), result(api, mapper, "Библиотека диаграмм для веба либо WPF"));
            assertEquals(Set.of(), result(api, mapper, "Библиотека диаграмм для веба и WPF"));
        }
        assertNull(SoftwareIntent.parse("купить камеру с по для записи"));
        assertNull(SoftwareIntent.parse("acme video converter"));
        assertNull(SoftwareIntent.parse("видеоконвертер для hevc на год"));
        assertNull(SoftwareIntent.parse("библиотека lucida для диаграмм wpf"));
        assertNull(SoftwareIntent.parse("переводчик с английского на русский для pdf"));
    }

    private static Set<String> result(SearchController api, JsonMapper mapper, String query) throws Exception {
        JsonNode response = mapper.valueToTree(api.search(Map.of("q", query)));
        return response.required("top4").valueStream().map(c -> c.required("offer_id").asString())
                .collect(Collectors.toSet());
    }

    private static String offer(String id, String family, int category, String name, String attributes) {
        return """
                <offer id="%s" group_id="%s" available="true">
                  <name>%s</name><vendor>Acme</vendor><vendorUrl>https://store.softline.ru/acme/</vendorUrl>
                  <url>https://store.softline.ru/acme/%s/</url><categoryId>%d</categoryId>
                  <price>100</price><currencyId>RUB</currencyId>%s
                </offer>
                """.formatted(id, family, name, id, category, attributes);
    }
}
