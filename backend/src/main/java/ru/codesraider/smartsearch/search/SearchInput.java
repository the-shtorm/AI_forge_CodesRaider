package ru.codesraider.smartsearch.search;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

record SearchInput(
        String query,
        String normalized,
        int limit,
        String sort,
        String strategy,
        Map<String, Object> context,
        Map<String, Object> filters,
        Map<String, Object> target) {

    static final Set<String> KINDS = Set.of("offer", "family", "category", "vendor", "collection");
    private static final Set<String> FILTERS =
            Set.of(
                    "category_id",
                    "vendor_id",
                    "platform",
                    "version",
                    "license_type",
                    "term_months",
                    "device_count",
                    "seat_count",
                    "available_only",
                    "max_price_minor",
                    "currency");
    private static final Pattern VERSION =
            Pattern.compile(
                    "(?U)(?:\\bверси[ияю]\\s+|\\bcsp\\s+|\\bбэкап\\s+)(\\d+\\.\\d+(?:\\.\\d+)*)\\b");
    private static final Pattern DEVICES =
            Pattern.compile("(?U)\\b(\\d+)\\s*(?:пк|устройств\\w*)\\b");
    private static final Pattern TERM =
            Pattern.compile("(?U)\\b(\\d+)\\s*(месяц\\w*|год\\w*|лет)\\b");

    static String normalize(String value) {
        return SearchIndex.normalize(value);
    }

    static String text(Object value, String field) {
        if (!(value instanceof String s) || s.isBlank()) throw SearchFailure.invalid(field);
        return s.strip();
    }

    static SearchInput parse(Map<String, Object> body) {
        verifyKeys(
                body, Set.of("q", "session_id", "context", "filters", "selected_target", "limit",
                        "strategy", "assistant_history", "sort"));
        String q = text(body.get("q"), "q");
        int limit = (int) integer(body.getOrDefault("limit", 4), 4, 50, "limit");
        String sort = text(body.getOrDefault("sort", "relevance"), "sort");
        if (!Set.of("relevance", "price_asc", "price_desc").contains(sort))
            throw SearchFailure.invalid("sort");
        String strategy = text(body.getOrDefault("strategy", "standard"), "strategy");
        if (!Set.of("standard", "llm").contains(strategy)) throw SearchFailure.invalid("strategy");
        validateHistory(body.get("assistant_history"));
        if (body.get("session_id") != null) text(body.get("session_id"), "session_id");
        var context = object(body.get("context"), "context");
        verifyKeys(context, Set.of("category_id", "segment", "profile_id"));
        context.forEach(
                (k, v) -> {
                    if (v != null) text(v, k);
                });
        var target = object(body.get("selected_target"), "selected_target");
        if (body.get("selected_target") != null) {
            verifyKeys(target, Set.of("kind", "target_id"));
            if (!KINDS.contains(text(target.get("kind"), "kind")))
                throw SearchFailure.invalid("kind");
            target.put("target_id", text(target.get("target_id"), "target_id"));
        }
        if (body.containsKey("filters") && body.get("filters") == null)
            throw SearchFailure.invalid("filters");
        return new SearchInput(
                q,
                normalize(q),
                limit,
                sort,
                strategy,
                context,
                filters(object(body.get("filters"), "filters"), false),
                target);
    }

    private static void validateHistory(Object value) {
        if (value == null) return;
        if (!(value instanceof List<?> history) || history.size() > 8)
            throw SearchFailure.invalid("assistant_history");
        for (Object entry : history) {
            var turn = object(entry, "assistant_history");
            verifyKeys(turn, Set.of("role", "text"));
            if (!Set.of("user", "assistant").contains(text(turn.get("role"), "role")))
                throw SearchFailure.invalid("role");
            String message = text(turn.get("text"), "text");
            if (message.codePointCount(0, message.length()) > 2000) throw SearchFailure.invalid("text");
        }
    }

    Map<String, Object> inferredFilters() {
        var f = new LinkedHashMap<String, Object>();
        if (normalized.equals("по для сервера") || normalized.equals("серверное по"))
            f.put("category_id", "369");
        Matcher version = VERSION.matcher(normalized);
        if (version.find()) f.put("version", version.group(1));
        if (normalized.contains("не продление") || normalized.contains("новая лицензия"))
            f.put("license_type", "new");
        else if (normalized.contains("продлен")) f.put("license_type", "renewal");
        else if (normalized.contains("обновление лицензии") || normalized.contains("апгрейд"))
            f.put("license_type", "upgrade");
        Matcher devices = DEVICES.matcher(normalized);
        if (devices.find()) f.put("device_count", quantity(devices.group(1)));
        Matcher term = TERM.matcher(normalized);
        if (term.find())
            f.put(
                    "term_months",
                    quantity(term.group(1)) * (term.group(2).startsWith("месяц") ? 1L : 12L));
        return f;
    }

    boolean requestsCompleteServer() {
        return normalized.matches("(?U)(?:купить |нужен |покажи )?(?:готовый )?сервер[ы]?");
    }

    boolean requestsFileRecovery() {
        boolean restores =
                normalized.contains("восстанов")
                        || normalized.contains("вернуть")
                        || normalized.contains("возврат");
        boolean files =
                normalized.contains("файл")
                        || normalized.contains("документ")
                        || normalized.contains("данн");
        boolean negated =
                normalized.matches(
                        "(?U).*(?:\\bбез\\s+восстанов\\w*"
                            + "|\\bне\\s+(?:(?:нуж\\w*|надо|хочу|требу\\w*)\\s+)?"
                            + "(?:восстанов\\w*|вернуть|возврат\\w*)|\\bне\\s+(?:файл|документ|данн)\\w*).*");
        return restores
                && files
                && !negated
                && !normalized.contains("парол")
                && !normalized.matches("(?s).*(?:sql|баз[ыу] данных|exchange|oracle).*");
    }

    boolean skipsConsumables() {
        return normalized.matches(
                "(?s).*(?:без|кроме)\\s+(?:расходник\\w*|расходных материалов|картридж\\w*).*");
    }

    private static long quantity(String value) {
        try {
            return integer(new BigInteger(value), 1, Integer.MAX_VALUE, "quantity");
        } catch (NumberFormatException e) {
            throw SearchFailure.invalid("quantity");
        }
    }

    static Map<String, Object> filters(Map<String, ?> raw, boolean queryString) {
        verifyKeys(raw, FILTERS);
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach(
                (k, v) -> {
                    if (v == null) {
                        if (k.equals("available_only") || k.equals("currency"))
                            throw SearchFailure.invalid(k);
                        return;
                    }
                    switch (k) {
                        case "available_only" -> {
                            if (v instanceof Boolean b) result.put(k, b);
                            else if (queryString && Set.of("true", "false").contains(v))
                                result.put(k, "true".equals(v));
                            else throw SearchFailure.invalid(k);
                        }
                        case "term_months", "device_count", "seat_count", "max_price_minor" -> {
                            Object parsed = v;
                            if (queryString) {
                                try {
                                    parsed = Long.parseLong(v.toString());
                                } catch (NumberFormatException e) {
                                    throw SearchFailure.invalid(k);
                                }
                            }
                            result.put(
                                    k,
                                    integer(
                                            parsed,
                                            k.equals("max_price_minor") ? 0 : 1,
                                            Long.MAX_VALUE,
                                            k));
                        }
                        default -> {
                            String s = text(v, k);
                            if (k.equals("license_type")
                                    && !Set.of("new", "renewal", "upgrade").contains(s))
                                throw SearchFailure.invalid(k);
                            if (k.equals("currency") && !s.matches("[A-Z]{3}"))
                                throw SearchFailure.invalid(k);
                            result.put(k, s);
                        }
                    }
                });
        return result;
    }

    private static long integer(Object value, long min, long max, String field) {
        if (!(value instanceof Integer || value instanceof Long || value instanceof BigInteger))
            throw SearchFailure.invalid(field);
        BigInteger n = new BigInteger(value.toString());
        if (n.compareTo(BigInteger.valueOf(min)) < 0 || n.compareTo(BigInteger.valueOf(max)) > 0)
            throw SearchFailure.invalid(field);
        return n.longValue();
    }

    private static Map<String, Object> object(Object value, String field) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value == null) return result;
        if (!(value instanceof Map<?, ?> map)) throw SearchFailure.invalid(field);
        map.forEach((k, v) -> result.put(k.toString(), v));
        return result;
    }

    private static void verifyKeys(Map<String, ?> m, Set<String> allowed) {
        for (String key : m.keySet()) if (!allowed.contains(key)) throw SearchFailure.invalid(key);
    }
}
