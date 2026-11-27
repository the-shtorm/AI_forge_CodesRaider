package ru.codesraider.smartsearch.search;

import java.util.regex.Pattern;

final class CatalogFit {
    private CatalogFit() {}

    private static final Pattern MODEL = Pattern.compile("(?i)(?=.*[a-z])(?=.*[0-9])[a-z0-9][a-z0-9._-]{2,63}");
    private static final Pattern CARTRIDGE = Pattern.compile("(?U)\\b(?:картридж|тонер-картридж)\\p{L}*\\b");
    private static final Pattern FOR = Pattern.compile("(?U)\\bдля\\s+");
    private static final Pattern EXCEPTION = Pattern.compile("(?U)\\b(?:не|кроме|исключ\\p{L}*|несовместим\\p{L}*)\\b");

    static boolean modelCode(String code) {
        return code != null && MODEL.matcher(code).matches();
    }

    static boolean cartridgeFor(String title, String model) {
        String normalized = SearchIndex.normalize(title);
        var from = FOR.matcher(normalized);
        if (!from.find() || !CARTRIDGE.matcher(normalized.substring(0, from.start())).find()
                || EXCEPTION.matcher(normalized).find()) return false;
        Pattern token = Pattern.compile("(?U)(?<![\\w-])" + Pattern.quote(SearchIndex.normalize(model))
                + "(?=\\s*(?:$|[/,;()]|\\.$))");
        return token.matcher(normalized.substring(from.end())).find();
    }
}
