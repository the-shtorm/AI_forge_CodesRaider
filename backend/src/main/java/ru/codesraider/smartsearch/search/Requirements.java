package ru.codesraider.smartsearch.search;

import java.math.BigDecimal;
import static ru.codesraider.smartsearch.search.SearchIndex.normalize;

import java.util.*;
import java.util.regex.*;

class Requirements {
    private static final String SERVER_DESTINATION = "(?:на\\s+сервер(?:е)?|для\\s+сервера)";
    private static final String SERVER_LICENSE = "(?:серверн(?:ая|ую|ой)\\s+лицензи[яюи]"
            + "|лицензи[яюи]\\s+(?:именно\\s+)?" + SERVER_DESTINATION + ")";
    final Map<String, Object> values = new LinkedHashMap<>();
    final Set<String> excludedLicenses = new TreeSet<>();
    final Set<String> excludedVersions = new TreeSet<>();
    String edition;
    String editionYear;
    private static final String EDITION = "(?:(?:именно|только)\\s+)?(?:в\\s+)?редакци[ияю]\\s+([a-z][a-z0-9-]*)";
    private static final Pattern PRODUCT_YEAR = Pattern.compile("(?U)\\b[a-z][a-z0-9-]*\\s+((?:19|20)\\d{2})\\s+" + EDITION);
    Map<String,String> namedVersions = Map.of();
    boolean requireServerLicense;
    boolean excludeServerLicense;
    boolean conflict;
    CatalogTask task;
    boolean boxed;
    boolean pdfEditing;
    private static final String PDF = "(?:pdf|пдф)";
    static final Pattern PDF_EDITOR_TITLE = Pattern.compile(
            "(?iuU)\\b(?:" + PDF + "[ -]+(?:editor|редактор)|(?:editor|редактор)[ -]+" + PDF + ")\\b");
    private static final Pattern EDIT_PDF = Pattern.compile(
            "(?U)\\b(?:редактир\\w*|правк[ауи]|edit(?:ing)?|редактор\\w*)\\s+"
                    + "(?:(?:текст\\w*|документ\\w*)\\s+)?(?:(?:в|для)\\s+)?" + PDF + "\\b");
    Long audience;
    // Webinar audience is a plan capacity, separate from paid seats and installation count.
    static final String AUDIENCE = "(\\d{1,3}(?: \\d{3})+|\\d+)\\s+(?:участник(?:а|ов|у)?|слушател(?:я|ей|ю))\\b";
    private static final Pattern CATALOG_AUDIENCE = Pattern.compile("(?U)\\b" + AUDIENCE);
    private static final Pattern LICENSE_QUANTITY_WORD = Pattern.compile(
            "(?U)\\b(один|одно|одного|два|двух|три|трех|четыре|четырех|пять|пяти|шесть|шести|семь|семи|восемь|восьми|девять|девяти|десять|десяти)"
                    + "(?=\\s+(?:устройств|пользовател|компьютер|пк\\b|мест))");

    static long audienceCapacity(String facts) {
        var m = CATALOG_AUDIENCE.matcher(normalize(facts));
        if (!m.find()) return 0;
        try {
            return Long.parseLong(m.group(1).replace(" ", ""));
        } catch (NumberFormatException invalidCatalogValue) {
            return 0;
        }
    }

    void require(String key, Object value) {
        Object prior = values.putIfAbsent(key, value);
        if (prior == null || normalize(prior.toString()).equals(normalize(value.toString()))) return;
        if (key.equals("max_price_minor")) values.put(key, Math.min((long) prior, (long) value));
        else conflict = true;
    }

    String extractLicenseScope(String text) {
        text = take(text, "(?:(?:без|кроме|не)\\s+(?:нужна\\s+)?" + SERVER_LICENSE
                        + "|лицензи[яюи]\\s+не\\s+" + SERVER_DESTINATION + ")",
                m -> excludeServerLicense = true);
        text = take(text, "(?:(новая|новую)\\s+)?" + SERVER_LICENSE, m -> {
            requireServerLicense = true;
            if (m.group(1) != null) require("license_type", "new");
        });
        if (requireServerLicense && excludeServerLicense) conflict = true;
        return text;
    }

    String extractEdition(String text) {
        var year = PRODUCT_YEAR.matcher(text);
        if (year.find()) editionYear = year.group(1);
        // An unrepresentable exclusion must not become a positive match on a selected family.
        text = take(text, "(?:без|кроме|не)\\s+" + EDITION, m -> conflict = true);
        return take(text, EDITION, m -> {
            if (edition != null && !edition.equals(m.group(1))) conflict = true;
            edition = m.group(1);
        });
    }

    String extractQuery(String text) {
        try {
            return extract(text.replaceAll("(?<=\\d),(?=\\d)", ".").replaceAll("[?!,;]+", " "));
        } catch (ArithmeticException | NumberFormatException invalid) {
            throw SearchInput.invalid("quantity");
        }
    }

    String extract(String text) {
        text = extractEdition(text);
        pdfEditing = PDF_EDITOR_TITLE.matcher(text).find() || EDIT_PDF.matcher(text).find();
        text = extractLicenseScope(text);
        text = take(text, "в\\s+коробке|коробочн(?:ая|ую)\\s+верси[яю]", m -> boxed = true);
        // Normalize small spoken quantities only when followed by a license dimension.
        text = LICENSE_QUANTITY_WORD.matcher(text).replaceAll(m -> switch (m.group(1)) {
            case "один", "одно", "одного" -> "1";
            case "два", "двух" -> "2";
            case "три", "трех" -> "3";
            case "четыре", "четырех" -> "4";
            case "пять", "пяти" -> "5";
            case "шесть", "шести" -> "6";
            case "семь", "семи" -> "7";
            case "восемь", "восьми" -> "8";
            case "девять", "девяти" -> "9";
            default -> "10";
        });
        if (QueryPlan.Purpose.WEBINAR.evidence(text) == 2)
            text = take(text, "(?:до\\s+|на\\s+|от\\s+)?" + AUDIENCE, m -> {
                long requested = Long.parseLong(m.group(1).replace(" ", ""));
                audience = audience == null ? requested : Math.max(audience, requested);
            });
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
                        "(?:(?:(?:именно|только)\\s+)?верс(?:ия|ии|ию)\\s+(\\d+(?:\\.\\d+)*)|(\\d+\\.\\d+(?:\\.\\d+)*)(?!\\s*(?:к?вт|k?w)\\b))\\b",
                        m -> require("version", m.group(1) != null ? m.group(1) : m.group(2)));
        text =
                take(
                        text,
                        "(?:для|под|на|(?:компьютер[а-я]*|ноутбук[а-я]*|пк)\\s+с)\\s+(windows|linux|macos)\\b",
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
