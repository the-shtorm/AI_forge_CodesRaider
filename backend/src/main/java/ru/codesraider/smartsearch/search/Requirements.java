package ru.codesraider.smartsearch.search;

import static ru.codesraider.smartsearch.search.Product.norm;

import java.util.*;
import java.util.regex.*;

class Requirements {
    private static final Set<String> NUMBERS =
            Set.of("term_months", "device_count", "seat_count", "max_price_minor");
    private static final Set<String> WORDS =
            Set.of("category_id", "vendor_id", "platform", "version", "license_type", "currency");
    final Map<String, Object> values = new LinkedHashMap<>();
    final Set<String> excludedLicenses = new TreeSet<>();
    final Set<String> excludedVersions = new TreeSet<>();
    boolean conflict;

    Requirements(Map<String, ?> input, boolean queryParams) {
        if (input == null) return;
        for (var e : input.entrySet()) {
            String k = e.getKey();
            Object v = e.getValue();
            if (v == null && (WORDS.contains(k) || NUMBERS.contains(k))) continue;
            if (NUMBERS.contains(k)) {
                String s = Objects.toString(v, "");
                if (!(queryParams || v instanceof Number) || !s.matches("\\d{1,15}"))
                    throw invalid();
                long n = Long.parseLong(s);
                if (n == 0 && !k.equals("max_price_minor")) throw invalid();
                values.put(k, n);
            } else if (k.equals("available_only")) {
                if (v instanceof Boolean b) values.put(k, b);
                else if (queryParams && List.of("true", "false").contains(v))
                    values.put(k, Boolean.parseBoolean(v.toString()));
                else throw invalid();
            } else if (WORDS.contains(k) && v instanceof String s && !s.isBlank()) {
                if (k.equals("license_type") && !Set.of("new", "renewal", "upgrade").contains(s))
                    throw invalid();
                if (k.equals("currency") && !s.matches("[A-Z]{3}")) throw invalid();
                values.put(k, s.strip());
            } else throw invalid();
        }
    }

    static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Проверьте параметры запроса.");
    }

    void require(String key, Object value) {
        Object prior = values.putIfAbsent(key, value);
        if (prior == null || norm(prior.toString()).equals(norm(value.toString()))) return;
        if (key.equals("max_price_minor")) values.put(key, Math.min((long) prior, (long) value));
        else conflict = true;
    }

    String extract(String text) {
        text =
                take(
                        text,
                        "(?:без|кроме|не)\\s+версии\\s+(\\d+(?:\\.\\d+)+)",
                        m -> excludedVersions.add(m.group(1)));
        text = take(text, "(?:не|без)\\s+продлени[яйе]", m -> excludedLicenses.add("renewal"));
        text = take(text, "продлени[еяю]|продлить", m -> require("license_type", "renewal"));
        text = take(text, "нов(?:ая|ую)\\s+лицензи[яю]", m -> require("license_type", "new"));
        text = take(text, "обновлени[ея]", m -> require("license_type", "upgrade"));
        text =
                take(
                        text,
                        "(?:до|не дороже)\\s+((?:\\d{1,3}(?:"
                                + " \\d{3})+|\\d+)(?:\\.\\d{1,2})?)\\s*(?:рублей|рубля|руб|р\\b)?",
                        m -> {
                            var budget = Product.money(m.group(1).replace(" ", ""));
                            require("max_price_minor", budget == null ? 0L : budget);
                        });
        text =
                take(
                        text,
                        "(?:на\\s+)?(\\d+)\\s*(?:пк|устройств[а-я]*|компьютер[а-я]*)\\b",
                        m -> require("device_count", Long.parseLong(m.group(1))));
        text =
                take(
                        text,
                        "(?:на\\s+)?(\\d+)\\s*(?:мест[а-я]*|пользовател[а-я]*)\\b",
                        m -> require("seat_count", Long.parseLong(m.group(1))));
        text =
                take(
                        text,
                        "(?:на\\s+)?(\\d+)\\s*(?:мес(?:яц[а-я]*)?\\b)",
                        m -> require("term_months", Long.parseLong(m.group(1))));
        text =
                take(
                        text,
                        "(?:на\\s+)(?:(\\d+)\\s+)?(?:год|года|лет)\\b",
                        m ->
                                require(
                                        "term_months",
                                        Math.multiplyExact(
                                                12L,
                                                m.group(1) == null
                                                        ? 1
                                                        : Long.parseLong(m.group(1)))));
        text =
                take(
                        text,
                        "(?:верс(?:ия|ии|ию)\\s*)?\\b(\\d+\\.\\d+(?:\\.\\d+)*)\\b",
                        m -> require("version", m.group(1)));
        text =
                take(
                        text,
                        "(?:для|под)\\s+(windows|linux|macos)\\b",
                        m -> require("platform", m.group(1)));
        return take(text, "(?:только\\s+)?в наличии", m -> require("available_only", true))
                .strip()
                .replaceAll(" +", " ");
    }

    static String take(String s, String regex, java.util.function.Consumer<Matcher> onMatch) {
        Matcher m =
                Pattern.compile("(?U)(?<![\\p{L}\\d])(?:" + regex + ")(?![\\p{L}\\d])").matcher(s);
        var out = new StringBuilder();
        while (m.find()) {
            onMatch.accept(m);
            m.appendReplacement(out, " ");
        }
        return m.appendTail(out).toString();
    }

    static String license(Product p) {
        var types = p.values("Тип лицензии", "Тип поставки", "license_type");
        if (types.size() != 1) return "";
        return switch (types.getFirst()) {
            case "новая", "новая лицензия", "new" -> "new";
            case "продление", "продление лицензии", "renewal" -> "renewal";
            case "обновление", "upgrade" -> "upgrade";
            default -> "";
        };
    }

    private boolean amount(Product p, String key, String... names) {
        if (!values.containsKey(key)) return true;
        long need = (long) values.get(key);
        var unit = p.values("Единица продажи");
        if (key.equals("device_count")
                        && (unit.equals(List.of("device")) || unit.equals(List.of("устройство")))
                || key.equals("seat_count")
                        && (unit.equals(List.of("seat")) || unit.equals(List.of("место")))) {
            var low =
                    p.values(
                            key + "_min",
                            key.equals("seat_count") ? "Минимум мест" : "Минимум устройств");
            var high =
                    p.values(
                            key + "_max",
                            key.equals("seat_count") ? "Максимум мест" : "Максимум устройств");
            return low.size() == 1
                    && high.size() == 1
                    && low.getFirst().matches("[1-9]\\d{0,14}")
                    && high.getFirst().matches("[1-9]\\d{0,14}")
                    && Long.parseLong(low.getFirst()) <= need
                    && need <= Long.parseLong(high.getFirst());
        }
        var supported = p.values(names);
        return supported.size() == 1
                && supported.getFirst().matches("\\d{1,15}")
                && (key.equals("term_months")
                        ? Long.parseLong(supported.getFirst()) == need
                        : Long.parseLong(supported.getFirst()) >= need);
    }

    boolean permits(Product p, Catalog c) {
        if (conflict
                || values.containsKey("category_id")
                        && !c.inCategory(p, (String) values.get("category_id"))
                || values.containsKey("vendor_id") && !p.vendorId().equals(values.get("vendor_id"))
                || Boolean.TRUE.equals(values.get("available_only")) && !p.available())
            return false;
        if (!excludedVersions.isEmpty()) {
            var versions = p.values("Версия", "Версия продукта");
            if (versions.size() != 1 || excludedVersions.contains(versions.getFirst()))
                return false;
        }
        if (values.containsKey("version")
                && !p.values("Версия", "Версия продукта")
                        .equals(List.of(norm(values.get("version").toString())))) return false;
        if (values.containsKey("platform")
                && !p.values("Платформа", "Операционная система", "Поддерживаемые ОС")
                        .contains(norm(values.get("platform").toString()))) return false;
        String type = license(p);
        if (values.containsKey("license_type") && !type.equals(values.get("license_type"))
                || !excludedLicenses.isEmpty()
                        && (type.isEmpty() || excludedLicenses.contains(type))) return false;
        if (!amount(p, "term_months", "Срок (мес.)", "Срок в месяцах", "term_months")
                || !amount(p, "device_count", "Количество устройств", "device_count")
                || !amount(p, "seat_count", "Количество мест", "seat_count")) return false;
        if (values.containsKey("max_price_minor")) {
            if (p.price() == null || !p.currency().equals(values.getOrDefault("currency", "RUB")))
                return false;
            var units = p.values("Единица продажи");
            if (units.size() != 1) return false;
            long qty =
                    switch (units.getFirst()) {
                        case "шт", "пакет", "package" -> 1;
                        case "устройство", "device" ->
                                (long) values.getOrDefault("device_count", 1L);
                        case "место", "seat" -> (long) values.getOrDefault("seat_count", 1L);
                        default -> 0;
                    };
            if (qty == 0) return false;
            var minimum = p.values("Минимальное количество");
            if (!minimum.isEmpty()) {
                if (minimum.size() != 1 || !minimum.getFirst().matches("[1-9]\\d{0,14}"))
                    return false;
                qty = Math.max(qty, Long.parseLong(minimum.getFirst()));
            }
            return qty > 0 && p.price() <= (long) values.get("max_price_minor") / qty;
        }
        return true;
    }
}
