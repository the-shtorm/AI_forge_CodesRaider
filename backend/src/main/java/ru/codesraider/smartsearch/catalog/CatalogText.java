package ru.codesraider.smartsearch.catalog;

import java.text.Normalizer;
import java.util.Locale;

public final class CatalogText {
    private CatalogText() {}

    public static String normalize(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replace('ё', 'е').replaceAll("[‐‑‒–—−]", "-").replaceAll("(?U)\\s+", " ").strip();
    }

    public static String sku(String value) {
        return normalize(value).replace(" ", "");
    }
}
