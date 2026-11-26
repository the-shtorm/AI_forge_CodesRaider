package ru.codesraider.smartsearch.catalog;

import ru.codesraider.smartsearch.search.SearchIndex;

import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Source values from one offer only. Attributes may repeat in the YML feed. */
final class FeedOffer {
    record Param(String name, String value) {}

    private static final Pattern DEPO =
            Pattern.compile("(?iu)\\bdepo\\s+storm\\s+([\\p{L}\\d-]*\\d[\\p{L}\\d-]*)");
    private static final Pattern CSP_VERSION =
            Pattern.compile(
                    "(?iU)\\bкриптопро\\s+CSP\\s+(?:верси(?:я|и|ю)\\s+)?(\\d+(?:\\.\\d+)+)\\b");
    private static final Pattern NUMBERED_VERSION = Pattern.compile("(?U)\\b\\d+(?:\\.\\d+)+\\b");
    private static final Pattern TERM = Pattern.compile("(?iu)^(\\d{1,4})\\s*мес\\.?$");
    private static final Pattern SEATS =
            Pattern.compile(
                    "(?iU)\\bна\\s+(\\d{1,6})\\s+(?:пользовател\\p{L}*|рабочи\\p{L}*\\s+мест\\p{L}*)\\b");
    private static final Pattern DEVICES =
            Pattern.compile(
                    "(?iU)\\bна\\s+(\\d{1,6})\\s+(?:ПК|устройств\\p{L}*|компьютер\\p{L}*)\\b");
    static final JsonMapper JSON = JsonMapper.builder().build();

    final String id;
    final String group;
    final boolean available;
    final Map<String, String> fields = new HashMap<>();
    final List<Param> params = new ArrayList<>();
    final Map<String, Object> facts = new LinkedHashMap<>();
    String vendorId, skuNorm, baseModel, family, familyId, familyName, currency;
    String version, edition, licenseType, productType, priceUnit;
    Integer termMonths, deviceCount, seatCount, seatMax;
    Long price, oldPrice;

    FeedOffer(String id, String group, boolean available) {
        if (empty(id) == null) throw new IllegalArgumentException("Offer is missing id");
        this.id = id;
        this.group = empty(group);
        this.available = available;
    }

    String get(String key) {
        return fields.get(key);
    }

    static String empty(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }

    static String normalizedValue(String value) {
        String normalized = SearchIndex.normalize(value);
        return switch (normalized) {
            case "", "другое или отсутствует", "не определено", "другое" -> null;
            default -> normalized;
        };
    }

    static String paramKey(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "совместимая архитектура/платформа", "платформа", "операционная система" ->
                    "platform";
            case "версия" -> "version";
            case "тип лицензии" -> "license_type";
            case "срок действия" -> "term_months";
            case "к-во пользователей до" -> "seat_max";
            case "к-во узлов" -> "device_count";
            case "тип продукта" -> "product_type";
            case "редакция" -> "edition";
            default -> null;
        };
    }

    private String confirmedParam(String source, String fact) {
        String value = null;
        int ordinal = -1;
        for (int i = 0; i < params.size(); i++) {
            Param p = params.get(i);
            if (!p.name.equalsIgnoreCase(source) || normalizedValue(p.value) == null) continue;
            if (value != null && !value.equalsIgnoreCase(p.value)) return null;
            value = p.value;
            ordinal = i;
        }
        if (value != null) facts.put(fact, Map.of("param_ordinal", ordinal));
        return value;
    }

    private String fromName(Pattern pattern, String key) {
        Matcher m = pattern.matcher(get("name"));
        if (!m.find()) return null;
        String value = m.group(1);
        while (m.find()) if (!value.equalsIgnoreCase(m.group(1))) return null;
        facts.put(key, Map.of("field", "name"));
        return value;
    }

    private static Integer positive(String s) {
        if (s == null || !s.matches("[1-9][0-9]{0,7}")) return null;
        return Integer.valueOf(s);
    }

    void finish() {
        for (String field :
                List.of("name", "url", "vendor", "vendorUrl", "categoryId", "currencyId")) {
            if (get(field) == null)
                throw new IllegalArgumentException("Offer " + id + " lacks " + field);
        }
        vendorId = URI.create(get("vendorUrl")).getPath().replaceAll("^/+|/+$", "");
        if (vendorId.isBlank())
            throw new IllegalArgumentException("Invalid vendor path at offer " + id);
        String sku = get("vendorCode");
        skuNorm = sku == null ? null : SearchIndex.normalizeSku(sku);
        productType = confirmedParam("Тип продукта", "product_type");
        currency =
                get("currencyId").equalsIgnoreCase("RUR")
                        ? "RUB"
                        : get("currencyId").toUpperCase(Locale.ROOT);
        price = money(get("price"));
        oldPrice = money(get("oldprice"));
        if (group != null) {
            familyId = group;
            family = "family/" + group;
            familyName = get("name").split(",", 2)[0];
        } else {
            Matcher depo = DEPO.matcher(get("name"));
            if (depo.find() && vendorId.equals("depo")) {
                baseModel = "Storm " + depo.group(1);
            } else if (sku != null
                    && sku.length() >= 3
                    && sku.codePoints().anyMatch(Character::isDigit)
                    && Pattern.compile(
                                    "(?iu)(?<![\\p{L}\\d])"
                                            + Pattern.quote(sku)
                                            + "(?![\\p{L}\\d])")
                            .matcher(get("name"))
                            .find()) {
                baseModel = sku;
            }
            if (baseModel != null) {
                String key =
                        SearchIndex.normalizeSku(baseModel).replaceAll("[^\\p{L}\\d._-]+", "-");
                family = "hardware/" + vendorId + "/" + key;
                familyId = vendorId + "~" + key;
                familyName = get("vendor") + " " + baseModel;
            }
        }
        version = confirmedParam("Версия", "version");
        if ("22268".equals(group) && "kripto-pro".equals(vendorId)) {
            // A version mentioned for a platform, another product or an upgrade source
            // cannot establish the version of this supply.
            String name = get("name");
            Matcher csp = CSP_VERSION.matcher(name);
            if (SearchIndex.normalize(name).startsWith("криптопро csp") && csp.find()) {
                String candidate = csp.group(1);
                boolean unambiguous =
                        NUMBERED_VERSION
                                .matcher(name)
                                .results()
                                .allMatch(m -> m.group().equals(candidate));
                boolean compatibleParams =
                        params.stream()
                                .filter(p -> "Версия".equalsIgnoreCase(p.name()))
                                .map(p -> normalizedValue(p.value()))
                                .filter(java.util.Objects::nonNull)
                                .allMatch(candidate::equals);
                if (unambiguous && compatibleParams && version == null) {
                    version = candidate;
                    facts.put("version", Map.of("field", "name", "matched", csp.group()));
                } else if (!compatibleParams || !unambiguous) {
                    version = null;
                    facts.remove("version");
                }
            }
        }
        edition = confirmedParam("Редакция", "edition");
        String license = confirmedParam("Тип лицензии", "license_type");
        licenseType =
                switch (license == null ? "" : SearchIndex.normalize(license)) {
                    case "full", "полная версия", "новая лицензия" -> "new";
                    case "продление", "renewal" -> "renewal";
                    case "обновление", "перекрестное обновление", "upgrade", "upg" -> "upgrade";
                    default -> null;
                };
        String term = confirmedParam("Срок действия", "term_months");
        if (term != null) {
            Matcher m = TERM.matcher(term);
            if (m.matches()) termMonths = positive(m.group(1));
        }
        deviceCount = positive(fromName(DEVICES, "device_count"));
        seatCount = positive(fromName(SEATS, "seat_count"));
        seatMax = positive(confirmedParam("К-во пользователей до", "seat_max"));
        if ("электроника".equalsIgnoreCase(productType)) {
            priceUnit = "package";
            facts.put("price_unit", Map.of("basis", "physical_unit"));
        }
        if (licenseType == null) facts.remove("license_type");
        if (termMonths == null) facts.remove("term_months");
        if (seatMax == null) facts.remove("seat_max");
    }

    private static Long money(String value) {
        return value == null
                ? null
                : new BigDecimal(value.replace(',', '.')).movePointRight(2).longValueExact();
    }
}
