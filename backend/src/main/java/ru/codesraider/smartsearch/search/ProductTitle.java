package ru.codesraider.smartsearch.search;

import java.util.Arrays;
import java.util.regex.Pattern;

/** Extract a version only when it is explicitly labeled or attached to the requested product name. */
final class ProductTitle {
    private static final Pattern VERSION = Pattern.compile("(?U)\\b(?:version|версия|версии)\\s+(\\d+(?:\\.\\d+)*)\\b");
    private final String[] words;
    private final Pattern namedRelease;

    private ProductTitle(String[] words) {
        this.words = words;
        // Editions can follow the release: "Maker Studio 26, Ultimate".
        StringBuilder product = new StringBuilder(Pattern.quote(words[0]) + "\\s+" + Pattern.quote(words[1]));
        for (int i = 2; i < words.length; i++) product.append("(?:\\s+").append(Pattern.quote(words[i])).append(")?");
        namedRelease = Pattern.compile("(?U)\\b" + product + "\\s+(?:v\\.?\\s*)?(\\d+(?:\\.\\d+)*)\\b");
    }

    static ProductTitle from(String terms) {
        if (!terms.matches("[a-z][a-z0-9-]+(?: [a-z][a-z0-9-]+){1,5}")) return null;
        return new ProductTitle(terms.split(" "));
    }

    String release(String name, String version, String edition) {
        String normalized = SearchIndex.normalize(name);
        var tokens = Arrays.asList((normalized + " " + SearchIndex.normalize(edition)).split("(?U)[^\\p{L}\\d-]+"));
        if (!tokens.containsAll(Arrays.asList(words))) return null;
        if (version != null && version.matches("\\d+(?:\\.\\d+)*")) return version;
        var nearName = namedRelease.matcher(normalized);
        if (nearName.find()) return nearName.group(1);
        var labeled = VERSION.matcher(normalized);
        return labeled.find() ? labeled.group(1) : null;
    }

    static boolean sameRelease(String a, String b) {
        return a.replaceFirst("(?:\\.0)+$", "").equals(b.replaceFirst("(?:\\.0)+$", ""));
    }

    static boolean hasEdition(String title, String edition, String requested) {
        if (edition != null && !edition.isBlank()) return requested.equals(SearchIndex.normalize(edition));
        return Arrays.asList(SearchIndex.normalize(title).split("(?U)[^\\p{L}\\d-]+")).contains(requested);
    }
}
