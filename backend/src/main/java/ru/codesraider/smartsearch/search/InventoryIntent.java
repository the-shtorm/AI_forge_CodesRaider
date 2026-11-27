package ru.codesraider.smartsearch.search;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Number of computers to be inventoried, as distinct from seats running the management console. */
record InventoryIntent(long computers, String licensingQuery) implements CatalogTask {
    private static final String PC = "(?:пк|компьютер\\p{L}*)\\b";
    private static final String COUNT = "(\\d{1,3}(?: \\d{3})+|\\d+)\\s*" + PC;
    private static final Pattern INVENTORY = Pattern.compile("(?U)\\b(?:инвентаризац\\p{L}*|уч[её]т\\p{L}* компьюте\\p{L}*)\\b");
    private static final Pattern FLEET = Pattern.compile("(?U)\\b(?:уч[её]т[ау]?|инвентаризац\\p{L}*|"
            + "в (?:филиале|сети|офисе|компании))\\s+(?:(?:до|от|для|как минимум|не менее)\\s+)?" + COUNT);
    private static final Pattern COVERAGE = Pattern.compile("(?U)\\b(?:уч[её]т[ау]?|инвентаризац\\p{L}*)\\s+(?:до\\s+)?" + COUNT);
    private static final Pattern REQUEST_WORDS = Pattern.compile("(?U)\\b(?:нам|мне|мы|их|в|во|для|и|как|по|"
            + "нужн\\p{L}*|требуется|программ\\p{L}*|которая|которая бы|прове[дс]\\p{L}*|провод\\p{L}*|"
            + "вести|автоматизировать|уч[её]т[ау]?|инвентаризац\\p{L}*)\\b");

    static InventoryIntent parse(String query) {
        if (!INVENTORY.matcher(query).find()) return null;
        Matcher m = FLEET.matcher(query);
        if (!m.find()) return null;
        String remainder = query.substring(0, m.start()) + " " + query.substring(m.end());
        if (!REQUEST_WORDS.matcher(remainder).replaceAll(" ").matches("[\\s.,!?;:–—-]*")) return null;
        long count;
        try {
            count = Long.parseLong(m.group(1).replace(" ", ""));
        } catch (NumberFormatException e) {
            throw SearchInput.invalid("quantity");
        }
        if (count <= 0 || count > Integer.MAX_VALUE) throw SearchInput.invalid("quantity");
        return new InventoryIntent(count, query.substring(0, m.start(1)) + " " + query.substring(m.end()));
    }

    static long coverage(String title) {
        Matcher m = COVERAGE.matcher(SearchIndex.normalize(title));
        if (!m.find()) return 0;
        try {
            return Long.parseLong(m.group(1).replace(" ", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Override public void constrain(SearchCatalog.Where where) {
        where.add("(o.name LIKE '%нвентаризац%омпьютер%' OR o.name LIKE '%чет компьютеров%'"
                + " OR o.name LIKE '%чёт компьютеров%')");
        where.add("inventory_capacity(o.name)>=?", computers);
    }
}
