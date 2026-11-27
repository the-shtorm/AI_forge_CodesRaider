package ru.codesraider.smartsearch.search;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Names read once from the pinned snapshot. Ambiguous spellings never choose a family. */
final class CatalogNames {
    private final Map<String, String> families = new HashMap<>();
    private final Map<Integer, List<String>> byLength = new HashMap<>();

    CatalogNames(Connection db) throws SQLException {
        try (var statement = db.createStatement();
             var rows = statement.executeQuery("SELECT name,family_key FROM offer WHERE family_key IS NOT NULL")) {
            while (rows.next()) {
                // The feed appends license/edition details after the product title.
                String title = rows.getString(1);
                int end = title.length();
                for (char delimiter : new char[]{'(', ','}) {
                    int at = title.indexOf(delimiter);
                    if (at >= 0) end = Math.min(end, at);
                }
                String name = SearchIndex.normalize(title.substring(0, end));
                if (!name.isEmpty()) families.merge(name, rows.getString(2),
                        (first, next) -> first.equals(next) ? first : "");
            }
        }
        families.keySet().forEach(name -> byLength.computeIfAbsent(name.length(), k -> new ArrayList<>()).add(name));
    }

    String family(String query) {
        String exact = families.get(query);
        if (exact != null) return exact.isEmpty() ? null : exact;
        if (query.length() < 5 || !query.contains(" ") && query.chars().anyMatch(Character::isDigit)) return null;
        String result = null;
        for (int length = query.length() - 1; length <= query.length() + 1; length++) {
            for (String name : byLength.getOrDefault(length, List.of())) {
                if (!oneEdit(query, name, true)) continue;
                String next = families.get(name);
                if (next.isEmpty() || result != null && !next.equals(result)) return null;
                result = next;
            }
        }
        return result;
    }

    /** A catalog title may contain a typo in a word, but a model token must match in full. */
    static boolean oneEdit(String a, String b, boolean lettersOnly) {
        if (a.length() > b.length()) return oneEdit(b, a, lettersOnly);
        if (b.length() - a.length() > 1) return false;
        int i = 0;
        while (i < a.length() && a.charAt(i) == b.charAt(i)) i++;
        if (i == b.length()) return true;
        if (lettersOnly && !Character.isLetter(b.charAt(i))) return false;
        if (lettersOnly) {
            int end = b.indexOf(' ', i);
            int start = b.lastIndexOf(' ', i - 1) + 1;
            for (int k = start; k < (end < 0 ? b.length() : end); k++) {
                if (Character.isDigit(b.charAt(k))) return false;
            }
        }
        if (a.length() < b.length()) return b.regionMatches(i + 1, a, i, a.length() - i);
        if (lettersOnly && !Character.isLetter(a.charAt(i))) return false;
        if (a.regionMatches(i + 1, b, i + 1, a.length() - i - 1)) return true;
        return i + 1 < a.length() && a.charAt(i) == b.charAt(i + 1) && a.charAt(i + 1) == b.charAt(i)
                && a.regionMatches(i + 2, b, i + 2, a.length() - i - 2);
    }
}
