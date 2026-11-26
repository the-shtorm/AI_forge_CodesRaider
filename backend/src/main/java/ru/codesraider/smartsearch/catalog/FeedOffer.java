package ru.codesraider.smartsearch.catalog;

import ru.codesraider.smartsearch.search.SearchIndex;

import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.util.Currency;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
    private static final Pattern MIN_ORDER = Pattern.compile("от ([1-9]\\d{0,7})(?: до [1-9]\\d{0,7})? шт\\.?");
    private static final Pattern MODEL = Pattern.compile("[a-z][a-z0-9]*\\d[a-z0-9-]*");
    private static final Pattern SEATS =
            Pattern.compile(
                    "(?iU)\\bна\\s+(\\d{1,6})\\s+(?:пользовател\\p{L}*|рабочи\\p{L}*\\s+мест\\p{L}*)\\b");
    private static final Pattern DEVICES =
            Pattern.compile(
                    "(?iU)\\bна\\s+(\\d{1,6})\\s+(?:ПК|устройств\\p{L}*|компьютер\\p{L}*)\\b");
    static final JsonMapper JSON = JsonMapper.builder().build();

    final String id;
    String group;
    final boolean available;
    final Map<String, String> fields = new HashMap<>();
    final List<Param> params = new ArrayList<>();
    final Map<String, Object> facts = new LinkedHashMap<>();
    String vendorId, skuNorm, baseModel, family, familyId, familyName, currency;
    String version, edition, licenseType, productType, priceUnit;
    Integer termMonths, deviceCount, seatCount, seatMax, minOrder;
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
            case "язык интерфейса" -> "language";
            case "модель принтера" -> "base_model";
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
        if (group == null) group = get("group_id");
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
        price = money(get("price"), currency);
        oldPrice = money(get("oldprice"), currency);
        if (group != null) {
            familyId = group;
            family = "family/" + group;
            familyName = get("name").split(",", 2)[0];
        } else {
            Matcher depo = DEPO.matcher(get("name"));
            if (depo.find() && vendorId.equals("depo")) {
                baseModel = "Storm " + depo.group(1);
            } else if ("pantum".equals(vendorId) && skuNorm != null
                    && (("93995".equals(get("categoryId")) && Set.of("p2500w", "bm1800w").contains(skuNorm))
                    || ("94694".equals(get("categoryId")) && Set.of("pc-211p", "tl-c2318h").contains(skuNorm)))) {
                baseModel = sku;
                facts.put("base_model", Map.of("field", "vendorCode"));
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
            } else if ("электроника".equalsIgnoreCase(productType)) {
                String model = confirmedParam("Модель принтера", "base_model");
                if (model != null) {
                    String code = SearchIndex.normalize(model);
                    code = code.substring(code.lastIndexOf(' ') + 1);
                    if (MODEL.matcher(code).matches()) baseModel = code;
                    else facts.remove("base_model");
                }
            }
            if (baseModel != null) {
                String key =
                        SearchIndex.normalizeSku(baseModel).replaceAll("[^\\p{L}\\d._-]+", "-");
                family = "hardware/" + vendorId + "/" + key;
                familyId = vendorId + "~" + key;
                familyName = get("vendor") + " " + baseModel;
            }
        }
        version = normalizedValue(confirmedParam("Версия", "version"));
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
        licenseType = license(confirmedParam("Тип лицензии", "license_type"));
        termMonths = matchedQuantity(TERM, confirmedParam("Срок действия", "term_months"));
        deviceCount = positive(confirmedParam("К-во узлов", "device_count"));
        Integer namedDevices = positive(fromName(DEVICES, "device_count"));
        if (namedDevices != null) {
            if (deviceCount == null || deviceCount.equals(namedDevices)) {
                deviceCount = namedDevices;
                if (SearchIndex.normalize(get("name")).contains("лицензия на")) {
                    priceUnit = "package";
                    facts.put("price_unit", Map.of("field", "name"));
                }
            } else {
                deviceCount = null;
                facts.remove("device_count");
            }
        }
        seatCount = positive(fromName(SEATS, "seat_count"));
        seatMax = positive(confirmedParam("К-во пользователей до", "seat_max"));
        if ("электроника".equalsIgnoreCase(productType)) {
            priceUnit = "package";
            facts.put("price_unit", Map.of("basis", "physical_unit"));
        }
        addDescriptionFacts();
        if (licenseType == null) facts.remove("license_type");
        if (termMonths == null) facts.remove("term_months");
        if (seatMax == null) facts.remove("seat_max");
    }

    private void addDescriptionFacts() {
        Map<String, Set<String>> labels = new HashMap<>();
        for (String part : SearchIndex.normalize(get("description")).split("•")) {
            String[] pair = part.strip().split(":", 2);
            if (pair.length != 2 || normalizedValue(pair[1]) == null) continue;
            labels.computeIfAbsent(pair[0].strip(), ignored -> new java.util.LinkedHashSet<>())
                    .add(pair[1].strip());
        }
        var platforms = labels.getOrDefault("платформа", Set.of()).stream()
                .flatMap(value -> java.util.Arrays.stream(value.split("[/,]")))
                .map(FeedOffer::normalizedValue).filter(java.util.Objects::nonNull).distinct().toList();
        if (!platforms.isEmpty()) facts.put("platform", Map.of("field", "description", "values", platforms));
        minOrder = mergeDescription("min_order_qty", null, labels.getOrDefault("минимальная покупка", Set.of()),
                value -> matchedQuantity(MIN_ORDER, value));
        seatCount = mergeDescription("seat_count", seatCount, labels.getOrDefault("к-во пользователей", Set.of()),
                FeedOffer::positive);
        termMonths = mergeDescription("term_months", termMonths, labels.getOrDefault("срок действия", Set.of()),
                value -> matchedQuantity(TERM, value));
        licenseType = mergeDescription("license_type", licenseType, labels.getOrDefault("тип лицензии", Set.of()),
                FeedOffer::license);
    }

    private <T> T mergeDescription(String field, T existing, Set<String> descriptions,
                                   java.util.function.Function<String, T> parse) {
        var values = descriptions.stream().map(parse).filter(java.util.Objects::nonNull).distinct().toList();
        if (values.isEmpty()) return existing;
        if (values.size() > 1 || (existing != null && !existing.equals(values.getFirst()))) {
            facts.remove(field);
            return null;
        }
        facts.putIfAbsent(field, Map.of("field", "description"));
        return values.getFirst();
    }

    private static Integer matchedQuantity(Pattern pattern, String text) {
        if (text == null) return null;
        var match = pattern.matcher(text);
        return match.matches() ? positive(match.group(1)) : null;
    }

    private static String license(String value) {
        return switch (SearchIndex.normalize(value)) {
            case "full", "полная версия", "новая лицензия" -> "new";
            case "продление", "renewal" -> "renewal";
            case "обновление", "перекрестное обновление", "upgrade", "upg" -> "upgrade";
            default -> null;
        };
    }

    private static Long money(String value, String currency) {
        return value == null
                ? null
                : new BigDecimal(value.replace(',', '.'))
                        .movePointRight(Currency.getInstance(currency).getDefaultFractionDigits()).longValueExact();
    }
}
