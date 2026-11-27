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
import tools.jackson.databind.json.JsonMapper;

class EquipmentIntentTest {
    @Test
    void decimalPowerDoesNotSpecifyProductVersion() {
        var requirements = new Requirements();
        requirements.extract("ибп online под нагрузку 1.98 квт");
        assertFalse(requirements.values.containsKey("version"));
        requirements.extract("версия 1.98");
        assertEquals("1.98", requirements.values.get("version"));
    }

    @Test
    void doesNotBroadenNamedAccessoriesOrUnsupportedConditions() {
        for (String q : new String[]{"диск для nas", "nas synology", "насадка для сканера штрихкодов",
                "сервер для установки wms", "ибп без двойного преобразования 1800 вт",
                "online ибп на 2000 ва", "бесперебойник до 1000 вт с двойным преобразованием"})
            assertNull(EquipmentIntent.parse(q), q);
    }

    @Test
    void checksAllRequirementsOnTheOfferedSkuBeforeSelectingFamily(@TempDir Path temp) throws Exception {
        String online = param("Тип ИБП", "С двойным преобразованием (online)");
        Path feed = temp.resolve("sample.xml");
        Files.writeString(feed, """
                <yml_catalog><shop><categories>
                <category id="1">Складские программы</category><category id="2">Сканеры штрихкодов</category>
                <category id="3">ИБП (Источники бесперебойного питания)</category><category id="4">Сетевые хранилища (NAS)</category>
                </categories><offers>
                """ + offer(101, 10, 1, "Acme WMS, версия Server", "")
                + offer(102, 10, 1, "Acme WMS, версия Web", "")
                + offer(103, 11, 1, "Сервер терминального оборудования", "")
                + offer(201, 20, 2, "Сканер штрихкодов B", param("Поддерживаемые штрих-коды", "EAN 13")
                        + param("Поддерживаемые штрих-коды", "DataMatrix"))
                + offer(202, 20, 2, "Сканер штрихкодов C", param("Поддерживаемые штрих-коды", "Code 39"))
                + offer(203, 21, 2, "Чехол для сканера 1D/2D", "")
                + offer(301, 30, 3, "ИБП 3000 ВА", online + param("Эффективная мощность", "1600"))
                + offer(302, 30, 3, "ИБП 2300 ВА", online + param("Эффективная мощность", "1980"))
                + offer(303, 31, 3, "ИБП 4000 ВА", param("Тип ИБП", "Линейно-интерактивный")
                        + param("Эффективная мощность", "3600"))
                + offer(401, 40, 4, "Сетевое хранилище Acme", "")
                + offer(402, 41, 4, "Диск для NAS HDD", "")
                + "</offers></shop></yml_catalog>");
        String offline = "http://127.0.0.1:1";
        Path snapshots = temp.resolve("snapshots");
        var json = JsonMapper.builder().build();
        try (var encoder = new EncoderClient(offline);
             var index = new SearchIndex(snapshots.toString(), offline, "lexical", 10)) {
            CatalogImporter.importSnapshot(feed, snapshots, false, encoder);
            index.open();
            var controller = new SearchController(index, new EventStore(temp.resolve("events.db").toString(), json));
            for (var item : Map.of("Серверная редакция WMS", "101", "Сканер штрихкодов 1D/2D", "201",
                    "ИБП с двойным преобразованием под нагрузку 1,98 кВт", "302",
                    "Ищем сетевое хранилище для офиса", "401").entrySet()) {
                var r = json.valueToTree(controller.search(Map.of("q", item.getKey())));
                assertEquals(1, r.required("direct_count").asInt(), item.getKey());
                assertEquals(item.getValue(), r.at("/top4/0/offer_id").asString(), item.getKey());
            }
            assertEquals(0, controller.search(Map.of("q", "ИБП online под нагрузку 1981 Вт")).get("direct_count"));
        }
    }

    private static String offer(int id, int family, int category, String title, String params) {
        return """
                <offer id="%s" available="true" group_id="%s"><name>%s</name><vendor>Acme</vendor>
                <vendorUrl>https://example.org/acme/</vendorUrl><url>https://example.org/acme/%s/</url>
                <categoryId>%s</categoryId><price>100</price><currencyId>RUB</currencyId>%s</offer>
                """.formatted(id, family, title, id, category, params);
    }

    private static String param(String name, String value) {
        return "<param name=\"" + name + "\">" + value + "</param>";
    }
}
