package ru.codesraider.smartsearch.search;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class VendorPricesTest {
    @Test
    void parsesPublishedRrpOnlyForTheSameModel() {
        var page = Jsoup.parse("""
                <h1 class="device-details__title f-38 fb">Монохромный принтер P2500W</h1>
                <p class="device-details__price mt25">Рекомендованная розничная цена: <strong>10&nbsp;590 руб</strong></p>
                """);
        assertEquals(1_059_000L, VendorPrices.pantumRrp(page, "P2500W"));
        assertNull(VendorPrices.pantumRrp(page, "P2500"));
    }

    @Test
    void acceptsOnlyAnAvailableRubOfferForTheExactSku() {
        String html = """
                <script type="application/ld+json">{
                  "@type": "Product", "sku": "TL-C2318H",
                  "offers": [{ "@type": "Offer", "availability": "https://schema.org/InStock",
                               "priceCurrency": "RUB", "price": "3790.0" }]
                }</script>
                """;
        assertEquals(379_000L, VendorPrices.pantumShop(Jsoup.parse(html), "TL-C2318H"));
        assertNull(VendorPrices.pantumShop(Jsoup.parse(html), "TL-C2318"));
        assertNull(VendorPrices.pantumShop(Jsoup.parse(html.replace("InStock", "OutOfStock")), "TL-C2318H"));
        assertNull(VendorPrices.pantumShop(Jsoup.parse(html.replace("RUB", "USD")), "TL-C2318H"));
    }

    @Test
    void matchesOnlyTheStandardAnnualBackupVariantWithoutCloudStorage() {
        String html = """
                <section><h2>Лицензирование Кибер Бэкап Старт</h2>
                  <div class="card">
                    <div class="cardTitle">Стандартная</div><div class="cardSubTitle">Только компьютеры</div>
                    <div class="price-box-id_1"><div class="base-price-value">1 370</div><div class="base-price-unit">₽/год</div></div>
                    <select id="pcCount_1"><option value="1" selected>1 устройство</option></select>
                    <script>let buyCardVersions_1 = [
                      {pkOption: "1", cloudVolume: "0", unit: "ГБ", price: "1370", oldPrice: ""},
                      {pkOption: "1", cloudVolume: "250", unit: "ГБ", price: "1830", oldPrice: ""}
                    ];</script>
                  </div>
                </section>
                """;
        assertEquals(137_000L, VendorPrices.cyberBackupStart(Jsoup.parse(html)));
        assertNull(VendorPrices.cyberBackupStart(Jsoup.parse(html.replace("Стандартная", "Расширенная"))));
        assertNull(VendorPrices.cyberBackupStart(Jsoup.parse(html.replace("₽/год", "₽/мес"))));
        assertNull(VendorPrices.cyberBackupStart(Jsoup.parse(html.replace("1 370", "1 830"))));
    }
}
