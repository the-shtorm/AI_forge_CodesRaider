package ru.codesraider.smartsearch.search;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

final class VendorPrices {
    record Comparison(String vendor, String sourceUrl, String referenceType, String currency,
                      long storePriceMinor, long vendorPriceMinor, long differenceMinor,
                      LocalDate catalogDate, Instant checkedAt) {}

    private record Source(String url, String type, BiFunction<Document, String, Long> parser) {}
    private record Quote(Long priceMinor, Instant checkedAt, Instant expiresAt) {}

    private static final Logger LOG = LoggerFactory.getLogger(VendorPrices.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PANTUM = "https://www.pantum.ru/products/laser-devices/";
    private static final String SHOP = "https://pantum-shop.ru/collection/";
    // Only precisely identified variants with public RUB prices have a source. URLs never come from requests or feeds.
    private static final Map<String, Source> SOURCES = Map.of(
            "PANTUM/P2500W", new Source(PANTUM + "monohromnyj-printer-p2500w/", "rrp", VendorPrices::pantumRrp),
            "PANTUM/BM1800W", new Source(SHOP + "mnogofunktsionalnye-ustroystva/product/mfu-pantum-bm1800w", "vendor_price", VendorPrices::pantumShop),
            "PANTUM/PC-211P", new Source(SHOP + "rashodnye-materialy/product/toner-kartridzh-pantum-pc-211p", "vendor_price", VendorPrices::pantumShop),
            "PANTUM/TL-C2318H", new Source(SHOP + "rashodnye-materialy/product/tl-c2318h-toner-kartridzh-pantum-1600-str", "vendor_price", VendorPrices::pantumShop),
            "КИБЕРПРОТЕКТ/СBSS1PC0GB-1Y-SUB", new Source("https://cyberprotect.ru/products/backup-start/", "vendor_price",
                    (page, sku) -> cyberBackupStart(page)));
    private static final String RUB_AMOUNT = "([0-9]+(?:\\h[0-9]{3})*(?:[,.][0-9]{1,2})?)";
    private static final Pattern RRP = Pattern.compile("Рекомендованная розничная цена:\\h*" + RUB_AMOUNT + "\\h+руб\\.?");
    private static final Pattern AMOUNT = Pattern.compile(RUB_AMOUNT);
    private static final Pattern BACKUP_VARIANTS = Pattern.compile("(?s)\\blet\\s+buyCardVersions_1\\s*=\\s*\\[(.*?)\\]\\s*;");
    private static final Pattern BACKUP_ONE_PC = Pattern.compile(
            "\\{\\s*pkOption:\\s*\"1\",\\s*cloudVolume:\\s*\"0\",\\s*unit:\\s*\"ГБ\",\\s*price:\\s*\"([^\"]+)\"\\s*,");
    private final Map<Source, Quote> quotes = new ConcurrentHashMap<>();

    Comparison compare(SearchCatalog.Row offer, LocalDate catalogDate) {
        if (offer == null || catalogDate == null || offer.number("available") != 1 || !"RUB".equals(offer.str("currency"))) return null;
        Long local = SearchCatalog.Row.positive(offer.get("price_minor"));
        String sku = offer.str("sku");
        String vendor = offer.str("vendor");
        if (local == null || sku == null || vendor == null) return null;
        sku = sku.strip().toUpperCase(Locale.ROOT);
        Source source = SOURCES.get(vendor.strip().toUpperCase(Locale.ROOT) + "/" + sku);
        if (source == null) return null;

        Quote quote = quote(source, sku);
        if (quote.priceMinor() == null) return null;
        return new Comparison(vendor, source.url(), source.type(), "RUB", local, quote.priceMinor(),
                quote.priceMinor() - local, catalogDate, quote.checkedAt());
    }

    private Quote quote(Source source, String sku) {
        Quote cached = quotes.get(source);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) return cached;
        return quotes.compute(source, (key, previous) -> {
            if (previous != null && previous.expiresAt().isAfter(Instant.now())) return previous;
            Long price = fetch(key, sku);
            Instant checked = Instant.now();
            return new Quote(price, checked, checked.plus(price == null ? Duration.ofMinutes(10) : Duration.ofHours(6)));
        });
    }

    private static Long fetch(Source source, String sku) {
        try {
            var response = Jsoup.connect(source.url()).timeout(8_000).maxBodySize(1_000_000)
                    .userAgent("CodesRaider-PriceComparison/1.0").followRedirects(false).execute();
            if (response.statusCode() != 200) return null;
            return source.parser().apply(response.parse(), sku);
        } catch (IOException failure) {
            LOG.debug("Could not check vendor price at {}: {}", source.url(), failure.getMessage());
            return null;
        }
    }

    static Long pantumRrp(Document page, String sku) {
        var title = page.select("h1.device-details__title");
        var prices = page.select("p.device-details__price");
        if (title.size() != 1 || prices.size() != 1 || !title.text().toUpperCase(Locale.ROOT).endsWith(" " + sku)) return null;
        var match = RRP.matcher(prices.text());
        return match.matches() ? rubles(match.group(1)) : null;
    }

    static Long pantumShop(Document page, String sku) {
        Long price = null;
        for (var script : page.select("script[type=application/ld+json]")) {
            try {
                var product = JSON.readTree(script.data());
                if (product == null || !"Product".equals(product.path("@type").asString())
                        || !sku.equalsIgnoreCase(product.path("sku").asString())) continue;
                var offers = product.path("offers");
                if (price != null || !offers.isArray() || offers.size() != 1) return null;
                var offer = offers.get(0);
                if (!"Offer".equals(offer.path("@type").asString()) || !"RUB".equals(offer.path("priceCurrency").asString())
                        || !"https://schema.org/InStock".equals(offer.path("availability").asString())) return null;
                price = rubles(offer.path("price").asString());
                if (price == null) return null;
            } catch (JacksonException invalid) {
                // Some pages contain unrelated structured snippets. Only a complete matching Product is usable.
            }
        }
        return price;
    }

    static Long cyberBackupStart(Document page) {
        var sections = page.select("section:has(.price-box-id_1)");
        if (sections.size() != 1 || !sections.select("h2").text().equals("Лицензирование Кибер Бэкап Старт")) return null;
        var card = sections.select(".card:has(.price-box-id_1)");
        if (card.size() != 1 || !"Стандартная".equals(card.select(".cardTitle").text())
                || !"Только компьютеры".equals(card.select(".cardSubTitle").text())
                || !"₽/год".equals(card.select(".price-box-id_1 .base-price-unit").text())
                || card.select("#pcCount_1 option[value=1][selected]").size() != 1) return null;
        var variants = BACKUP_VARIANTS.matcher(card.select("script").stream().map(script -> script.data())
                .collect(java.util.stream.Collectors.joining("\n")));
        if (!variants.find()) return null;
        var option = BACKUP_ONE_PC.matcher(variants.group(1));
        if (variants.find() || !option.find()) return null;
        Long price = rubles(option.group(1));
        Long displayed = rubles(card.select(".price-box-id_1 .base-price-value").text());
        return price != null && price.equals(displayed) && !option.find() ? price : null;
    }

    private static Long rubles(String value) {
        if (!AMOUNT.matcher(value).matches()) return null;
        try {
            long amount = new BigDecimal(value.replaceAll("\\h", "").replace(',', '.')).movePointRight(2).longValueExact();
            return amount > 0 ? amount : null;
        } catch (ArithmeticException invalid) {
            return null;
        }
    }
}
