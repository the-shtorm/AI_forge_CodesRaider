package ru.codesraider.smartsearch.search;

import java.math.BigDecimal;
import java.net.URI;
import java.text.Normalizer;
import java.util.*;

record Product(
        String id,
        String group,
        String name,
        String sku,
        String vendor,
        String vendorId,
        String category,
        String url,
        String description,
        boolean available,
        Long price,
        Long oldPrice,
        String currency,
        Map<String, List<String>> attributes) {

    static String norm(String s) {
        return Normalizer.normalize(Objects.toString(s, ""), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replace('ё', 'е')
                .replaceAll("[‐‑‒–—−]", "-")
                .replaceAll("[^\\p{L}\\p{N}.\\-]+", " ")
                .trim()
                .replaceAll(" +", " ");
    }

    static String code(String s) {
        return norm(s).replaceAll("[\\s-]+", "");
    }

    static String safeUrl(String url, String fallback) {
        try {
            URI uri = URI.create(url);
            if (Set.of("http", "https").contains(Objects.toString(uri.getScheme(), ""))
                    && uri.getHost() != null) return url;
            if (url.startsWith("/") && !url.startsWith("//") && !url.contains("\\")) return url;
        } catch (IllegalArgumentException ignored) {
        }
        return fallback;
    }

    static String localUrl(String kind, String id) {
        return "/?kind="
                + kind
                + "&id="
                + java.net.URLEncoder.encode(id, java.nio.charset.StandardCharsets.UTF_8);
    }

    static Long money(String s) {
        if (s == null || s.isBlank()) return null;
        long value =
                new BigDecimal(s.strip().replace(',', '.'))
                        .movePointRight(2)
                        .setScale(0, java.math.RoundingMode.HALF_UP)
                        .longValueExact();
        return value > 0 ? value : null;
    }

    static Product read(Map<String, String> f, Map<String, List<String>> attrs) {
        String id = Objects.requireNonNull(f.get("id"));
        String url = safeUrl(f.getOrDefault("url", ""), localUrl("offer", id));
        String path = URI.create(safeUrl(f.getOrDefault("vendorUrl", ""), url)).getPath();
        String vendor = f.getOrDefault("vendor", "Не указан");
        String vendorId =
                Arrays.stream(path.split("/"))
                        .filter(s -> !s.isBlank())
                        .findFirst()
                        .orElse(norm(vendor));
        String currency = f.getOrDefault("currencyId", "").toUpperCase(Locale.ROOT);
        if (currency.equals("RUR")) currency = "RUB";
        if (!currency.matches("[A-Z]{3}"))
            throw new IllegalArgumentException("Нет валюты у SKU " + id);
        return new Product(
                id,
                f.getOrDefault("group_id", ""),
                Objects.requireNonNull(f.get("name")),
                f.get("vendorCode"),
                vendor,
                vendorId,
                f.getOrDefault("categoryId", ""),
                url,
                f.getOrDefault("description", "").replaceAll("<[^>]*>", " "),
                Boolean.parseBoolean(f.get("available")),
                money(f.get("price")),
                money(f.get("oldprice")),
                currency,
                Map.copyOf(attrs));
    }

    List<String> values(String... keys) {
        List<String> names = Arrays.stream(keys).map(Product::norm).toList();
        return attributes.entrySet().stream()
                .filter(e -> names.contains(norm(e.getKey())))
                .flatMap(e -> e.getValue().stream())
                .map(Product::norm)
                .filter(v -> !v.isBlank() && !v.equals("другое или отсутствует"))
                .distinct()
                .toList();
    }

    String baseModel() {
        var models = values("Базовая модель", "Модель");
        if (models.size() == 1) return models.getFirst();
        if (vendorId.equals("pantum") && sku != null) return norm(sku);
        if (Set.of("86563", "87268").contains(category)) {
            var m =
                    java.util.regex.Pattern.compile(
                                    "(?iu)\\b(storm|superserver)\\s+([\\w-]*\\d[\\w-]*)")
                            .matcher(name);
            if (m.find()) return norm(m.group());
        }
        return "";
    }

    String family() {
        if (!group.isEmpty()) return "family/" + group;
        String model = baseModel();
        return model.isEmpty() ? "" : "hardware/" + vendorId + "/" + model;
    }

    String familyId() {
        return group.isEmpty() ? vendorId + "~" + baseModel() : group;
    }

    int tier() {
        return available && price != null ? 0 : price != null ? 1 : 2;
    }

    Map<String, Object> offer() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("offer_id", id);
        m.put("sku", sku);
        m.put("name", name);
        m.put("vendor", vendor);
        m.put("url", url);
        m.put("available", available);
        m.put("price_minor", price);
        m.put("old_price_minor", oldPrice);
        m.put("currency", currency);
        m.put("offer_tier", tier() == 0 ? "primary" : "reserve");
        m.put("attributes", attributes);
        return m;
    }
}
