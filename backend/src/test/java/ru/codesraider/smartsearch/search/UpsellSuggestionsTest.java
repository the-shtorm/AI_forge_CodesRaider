package ru.codesraider.smartsearch.search;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.codesraider.smartsearch.catalog.CatalogImporter;
import ru.codesraider.smartsearch.modelclient.EncoderClient;
import static org.junit.jupiter.api.Assertions.*;

class UpsellSuggestionsTest {
    @Test
    void selectsMerchandiseWithoutAnIndexMigration(@TempDir Path dir) throws Exception {
        String offers = offer("pc", "computer", "1", 400, true, "")
                + offer("stand", "support", "2", 50, true, "")
                + offer("term12", "subscription", "3", 100, true, months(12))
                + offer("term24", "subscription", "3", 180, true, months(24))
                + offer("cheap-renewal", "subscription", "3", 40, true, months(36)
                        + "<param name=\"Тип лицензии\">Продление</param>")
                + offer("text-tool", "text", "3", 65, true, "<param name=\"Тип продукта\">софт</param>")
                + offer("backorder", "reserve", "4", 30, false, "")
                + offer("no-price", "other", "5", 0, true, "");
        Path feed = dir.resolve("feed.xml"), snapshots = dir.resolve("snapshots");
        Files.writeString(feed, """
                <yml_catalog><shop><categories><category id="1">Ноутбуки</category>
                <category id="2">Подставки для ноутбуков</category><category id="3">Подписки</category>
                <category id="4">Под заказ</category><category id="5">Новинки</category>
                </categories><offers>%s</offers></shop></yml_catalog>
                """.formatted(offers));
        try (var encoder = new EncoderClient("http://127.0.0.1:1")) {
            CatalogImporter.importSnapshot(feed, snapshots, false, encoder);
        }
        Path version = snapshots.resolve(Files.readString(snapshots.resolve("active")).strip());
        try (var catalog = new SearchCatalog(version.resolve("catalog.db"))) {
            var paired = catalog.upsellSuggestions(List.of(anchor("pc")), Map.of(), Map.of(), Set.of("family/computer"), null, 4, null);
            assertEquals(1, paired.size());
            var stand = paired.getFirst();
            assertEquals("stand", stand.get("offer_id"));
            assertEquals("pc", stand.get("anchor_offer_id"));
            assertEquals("paired_category", stand.get("recommendation_kind"));
            assertFalse(stand.containsKey("checks"));

            var upgrade = catalog.upsellSuggestions(List.of(anchor("term12")), Map.of(), Map.of(), Set.of("family/subscription"), null, 1, null).getFirst();
            assertEquals("term24", upgrade.get("offer_id"));
            assertEquals("upgrade", upgrade.get("recommendation_kind"));
            assertTrue(upgrade.get("recommendation_reason").toString().contains("24"));
            assertTrue(upgrade.get("recommendation_reason").toString().contains("12"));

            var pick = catalog.upsellSuggestions(List.of(), Map.of(), Map.of("category_id", "2"), Set.of(), null, 3, null).getFirst();
            assertEquals("catalog_pick", pick.get("recommendation_kind"));
            assertEquals("stand", pick.get("offer_id"));
            assertFalse(pick.containsKey("anchor_offer_id"));
            assertTrue(catalog.upsellSuggestions(List.of(), Map.of(), Map.of("category_id", "2"), Set.of("family/support"), null, 3, null).isEmpty());
            assertTrue(catalog.upsellSuggestions(List.of(), Map.of(), Map.of("category_id", "4", "available_only", true), Set.of(), null, 3, null).isEmpty());
            assertFalse((boolean)catalog.upsellSuggestions(List.of(), Map.of(), Map.of("category_id", "4"), Set.of(), null, 3, null).getFirst().get("available"));
            assertNull(catalog.upsellSuggestions(List.of(), Map.of(), Map.of("category_id", "5"), Set.of(), null, 3, null).getFirst().get("price_minor"));
            assertTrue(catalog.upsellSuggestions(List.of(), Map.of(), Map.of("category_id", "5", "max_price_minor", 50000), Set.of(), null, 3, null).isEmpty());
            var hint = new SearchCatalog.Row();
            hint.put("category", "Ноутбуки");
            var contextual = catalog.upsellSuggestions(List.of(), Map.of(), Map.of(), Set.of(), null, 2, hint).getFirst();
            assertEquals("stand", contextual.get("offer_id"));
            assertEquals("catalog_pick", contextual.get("recommendation_kind"));
            assertFalse(contextual.containsKey("anchor_offer_id"));
            hint.put("category", "Подписки");
            hint.put("product_type", "софт");
            var sameType = catalog.upsellSuggestions(List.of(), Map.of(), Map.of(), Set.of(), "hardware_buyer", 2, hint);
            assertEquals("text-tool", sameType.getFirst().get("offer_id"));
        }
    }

    private static SearchCatalog.Row anchor(String id) {
        var row = new SearchCatalog.Row();
        row.put("offer_id", id);
        return row;
    }

    private static String months(int count) {
        return "<param name=\"Срок (мес.)\">" + count + "</param>";
    }

    private static String offer(String id, String group, String category, int price, boolean available, String extra) {
        return """
                <offer id="%s" group_id="%s" available="%s"><name>%s</name>
                <vendor>Acme</vendor><vendorUrl>https://example.org/acme</vendorUrl><url>https://example.org/%s</url>
                <categoryId>%s</categoryId><price>%d</price><currencyId>RUB</currencyId>%s</offer>
                """.formatted(id, group, available, id, id, category, price, extra);
    }
}
