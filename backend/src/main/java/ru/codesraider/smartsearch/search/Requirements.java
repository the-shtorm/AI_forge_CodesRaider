package ru.codesraider.smartsearch.search;

import java.math.BigDecimal;
import static ru.codesraider.smartsearch.search.SearchIndex.normalize;

import java.util.*;
import java.util.regex.*;

class Requirements {
    final Map<String, Object> values = new LinkedHashMap<>();
    final Set<String> excludedLicenses = new TreeSet<>();
    final Set<String> excludedVersions = new TreeSet<>();
    boolean conflict;

    void require(String key, Object value) {
        Object prior = values.putIfAbsent(key, value);
        if (prior == null || normalize(prior.toString()).equals(normalize(value.toString()))) return;
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
                            long budget = new BigDecimal(m.group(1).replace(" ", "")).movePointRight(2).longValueExact();
                            require("max_price_minor", budget);
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

}
