package ru.codesraider.smartsearch.search;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;

@Service
class SearchEngine {
    record Input(
            String q,
            String session_id,
            Map<String, String> context,
            Map<String, Object> filters,
            Selection selected_target,
            Integer limit) {}

    record Selection(String kind, String target_id) {}

    record Suggestion(String kind, String target_id, String label) {}

    record Pair(String device, String cartridge, String source) {}

    static final List<Pair> PAIRS =
            List.of(
                    new Pair(
                            "P2500W",
                            "PC-211P",
                            "https://www.pantum.ru/products/laser-devices/monohromnyj-printer-p2500w/"),
                    new Pair(
                            "BM1800W",
                            "TL-C2318H",
                            "https://www.pantum.ru/options-and-supplies/toner-kartridzh-tl-c2318h/"));
    private final Catalog catalog;
    private final ModelClient model;
    private final SearchJournal journal;

    SearchEngine(Catalog catalog, ModelClient model, SearchJournal journal) {
        this.catalog = catalog;
        this.model = model;
        this.journal = journal;
    }

    static void validate(String q) {
        if (q == null || q.isBlank() || q.length() > 512) throw Requirements.invalid();
    }

    static class UnknownTarget extends RuntimeException {}

    static class Missing extends RuntimeException {}

    List<Suggestion> suggest(String q, String category) {
        validate(q);
        String prefix = Product.norm(q);
        if (prefix.isBlank()) return List.of();
        var found = new LinkedHashMap<String, Suggestion>();
        for (var a : catalog.aliases.entrySet())
            if (a.getKey().startsWith(prefix)) {
                var e = catalog.entries.get(a.getValue().key());
                if (category == null
                        || catalog.scoped(e.target()).stream()
                                .anyMatch(p -> catalog.inCategory(p, category)))
                    found.put(
                            e.target().key(),
                            new Suggestion(e.target().kind(), e.target().id(), e.label()));
                if (found.size() == 6) break;
            }
        for (Product p : catalog.byId.values()) {
            if (found.size() == 6) break;
            if (category != null && !catalog.inCategory(p, category)) continue;
            if (Product.norm(p.sku()).startsWith(prefix)
                    || Product.norm(p.name()).startsWith(prefix))
                found.put(
                        "offer/" + p.id(),
                        new Suggestion(
                                "offer",
                                p.id(),
                                p.name() + (p.sku() == null ? "" : " · " + p.sku())));
        }
        return List.copyOf(found.values());
    }

    Map<String, Object> entity(String kind, String id, Map<String, String> filters) {
        var target = catalog.target(kind, id);
        if (target == null) throw new Missing();
        var f = new Requirements(filters, true);
        var offers = catalog.scoped(target).stream().filter(p -> f.permits(p, catalog)).toList();
        var children = new LinkedHashSet<Catalog.Target>();
        for (var p : offers) {
            if (!kind.equals("family") && !p.family().isEmpty())
                children.add(catalog.target("family", p.familyId()));
            else if (!kind.equals("offer")) children.add(catalog.target("offer", p.id()));
        }
        return Map.of(
                "kind",
                kind,
                "id",
                id,
                "url",
                target.url(),
                "name",
                catalog.entries.get(target.key()).label(),
                "offers",
                offers.stream().map(Product::offer).toList(),
                "children",
                children);
    }

    Map<String, Object> search(Input in) throws IOException {
        long started = System.nanoTime();
        validate(in.q());
        int limit = in.limit() == null ? 4 : in.limit();
        if (limit < 4 || limit > 50) throw Requirements.invalid();
        Selection pick = in.selected_target();
        Catalog.Target target = pick == null ? null : catalog.target(pick.kind(), pick.target_id());
        if (pick != null && target == null) throw new UnknownTarget();
        var f = new Requirements(in.filters(), false);
        QueryPlan plan = QueryPlan.compile(in.q(), target, f, catalog, model);
        if (plan.scope() != null && plan.scope().kind().equals("collection")) {
            f.values.putIfAbsent("category_id", "1033");
            f.values.putIfAbsent("vendor_id", "cyberprotect");
        }
        Collection<Product> candidates = catalog.retrieve(plan);
        List<Product> matches = new ArrayList<>(), alternatives = new ArrayList<>();
        for (Product p : candidates) {
            if (p.family().isEmpty()
                    || !f.permits(p, catalog)
                    || !plan.matchesPurpose(p, catalog)
                    || (plan.scope() != null && !plan.scope().includes(p, catalog))
                    || !plan.supportsPrinter(p)) continue;
            if (plan.models().isEmpty() || plan.models().contains(Product.code(p.sku())))
                matches.add(p);
            else if (!plan.unknownModel().isEmpty()
                    && !plan.printerFeatures().isEmpty()
                    && p.sku() != null) alternatives.add(p);
        }
        String contextCategory = in.context() == null ? null : in.context().get("category_id");
        // Stable sort retains Lucene rank among tied preferences. Tier is chosen globally before
        // grouping.
        matches.sort(
                Comparator.comparingInt(Product::tier)
                        .thenComparingInt(
                                p ->
                                        contextCategory != null
                                                        && catalog.inCategory(p, contextCategory)
                                                ? 0
                                                : 1));
        List<Product> direct = bestFamilies(matches);
        String reason =
                plan.scope() != null
                        ? switch (plan.scope().kind()) {
                            case "offer" -> "EXACT_SKU";
                            case "collection" -> "COLLECTION_MATCH";
                            case "vendor" -> "VENDOR_MATCH";
                            default -> "ENTITY_MATCH";
                        }
                        : !plan.models().isEmpty()
                                ? "MODEL_MATCH"
                                : plan.purpose() != QueryPlan.Purpose.WORDS
                                        ? "PURPOSE_MATCH"
                                        : "TEXT_MATCH";
        var top = new ArrayList<Map<String, Object>>();
        for (var p : direct.stream().limit(4).toList()) top.add(card(p, "direct", reason, plan));
        if (!top.isEmpty() && top.size() < 4) complements(direct, top, plan);
        var more =
                direct.stream()
                        .skip(4)
                        .limit(limit - 4)
                        .map(p -> card(p, "direct", reason, plan))
                        .toList();
        alternatives.sort(Comparator.comparingInt(Product::tier).thenComparing(Product::id));
        var analogs =
                bestFamilies(alternatives).stream()
                        .limit(4)
                        .map(
                                p -> {
                                    var card =
                                            card(p, "alternative", "FUNCTIONAL_ALTERNATIVE", plan);
                                    card.put(
                                            "differences",
                                            List.of(
                                                    "Модель "
                                                            + p.sku()
                                                            + " вместо "
                                                            + plan.unknownModel()
                                                                    .toUpperCase(Locale.ROOT)
                                                            + "."));
                                    return card;
                                })
                        .toList();
        var response = new LinkedHashMap<String, Object>();
        String searchId = UUID.randomUUID().toString();
        response.put("search_id", searchId);
        response.put("index_version", catalog.version);
        response.put("mode", "lexical");
        response.put("normalized_q", Product.norm(in.q()));
        response.put("applied_filters", f.values);
        Catalog.Target primary = plan.scope();
        if (primary == null && !direct.isEmpty())
            primary = catalog.target("family", direct.getFirst().familyId());
        response.put("primary_target", primary);
        response.put("direct_count", direct.size());
        response.put("top4", top);
        response.put("more_results", more);
        response.put("alternatives", analogs);
        response.put(
                "shortfall_reason",
                top.size() == 4
                        ? null
                        : direct.isEmpty() ? "NO_DIRECT_MATCH" : "NOT_ENOUGH_VERIFIED_FAMILIES");
        response.put("program", plan.trace());
        journal.record(in, response, (System.nanoTime() - started) / 1_000_000);
        return response;
    }

    private static List<Product> bestFamilies(List<Product> sorted) {
        if (sorted.isEmpty()) return List.of();
        Map<String, Product> unique = new LinkedHashMap<>();
        int tier = sorted.getFirst().tier();
        for (Product p : sorted) {
            if (p.tier() != tier) break;
            unique.putIfAbsent(p.family(), p);
        }
        return List.copyOf(unique.values());
    }

    private Map<String, Object> card(Product p, String role, String reason, QueryPlan plan) {
        var m = p.offer();
        m.put("role", role);
        m.put("family_key", p.family());
        m.put("reason_codes", List.of(reason));
        String why =
                switch (reason) {
                    case "EXACT_SKU", "MODEL_MATCH" ->
                            "Совпали артикул или идентификатор предложения.";
                    case "COLLECTION_MATCH" -> "Киберпротект, раздел резервного копирования.";
                    case "VENDOR_MATCH" -> "В каталоге указан вендор " + p.vendor() + ".";
                    case "ENTITY_MATCH" -> "Предложение входит в выбранное семейство или раздел.";
                    case "PURPOSE_MATCH" ->
                            "Предложение «"
                                    + p.name()
                                    + "» входит в раздел «"
                                    + catalog.categories.getOrDefault(p.category(), p.category())
                                    + "».";
                    case "FUNCTIONAL_ALTERNATIVE" -> "Совпали проверенные характеристики печати.";
                    case "COMPATIBLE_CONSUMABLE" ->
                            "Производитель указывает расходник для выбранной модели.";
                    default -> "Слова запроса найдены в полях каталога.";
                };
        m.put("explanation", why);
        return m;
    }

    private void complements(List<Product> direct, List<Map<String, Object>> top, QueryPlan plan) {
        Set<String> seen = new HashSet<>();
        direct.forEach(p -> seen.add(p.family()));
        for (Product anchor : direct) {
            if (!anchor.vendorId().equals("pantum")) continue;
            for (Pair pair : PAIRS) {
                if (!pair.device.equalsIgnoreCase(anchor.sku())) continue;
                var candidates =
                        catalog.bySku.getOrDefault(Product.code(pair.cartridge), List.of());
                candidates.stream()
                        .filter(
                                p ->
                                        p.vendorId().equals("pantum")
                                                && p.tier() == 0
                                                && pair.cartridge.equalsIgnoreCase(p.sku())
                                                && !p.family().isEmpty()
                                                && plan.filters().permits(p, catalog)
                                                && !seen.contains(p.family()))
                        .min(Comparator.comparing(Product::id))
                        .ifPresent(
                                p -> {
                                    if (top.size() >= 4) return;
                                    var m = card(p, "complement", "COMPATIBLE_CONSUMABLE", plan);
                                    m.put("anchor_offer_id", anchor.id());
                                    m.put(
                                            "checks",
                                            List.of(
                                                    Map.of(
                                                            "field",
                                                            "supported_device",
                                                            "anchor_value",
                                                            anchor.sku(),
                                                            "candidate_value",
                                                            p.sku(),
                                                            "result",
                                                            "pass",
                                                            "source_url",
                                                            pair.source)));
                                    top.add(m);
                                    seen.add(p.family());
                                });
            }
        }
    }

    Map<String, Object> explain(String searchId, String offerId) {
        var c = journal.shownCard(searchId, offerId);
        if (c == null) throw new Missing();
        String reason = c.path("explanation").asString();
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("reason", reason);
        facts.put(
                "availability",
                c.path("available").asBoolean()
                        ? "В снимке товар доступен."
                        : "В снимке товар отмечен как недоступный.");
        if (c.has("checks")) {
            var check = c.path("checks").get(0);
            facts.put(
                    "pair",
                    "В таблице производителя для "
                            + check.path("anchor_value").asString()
                            + " указан "
                            + check.path("candidate_value").asString()
                            + ".");
        }
        if (c.has("differences")) facts.put("difference", c.path("differences").get(0).asString());
        var attrs = c.path("attributes");
        if (attrs.isObject())
            for (String key :
                    List.of(
                            "Версия",
                            "Тип лицензии",
                            "Платформа",
                            "Срок (мес.)",
                            "Количество устройств")) {
                if (attrs.path(key).size() == 1)
                    facts.put(key, key + " — " + attrs.path(key).get(0).asString() + ".");
            }
        var details = facts.keySet().stream().filter(k -> !k.equals("reason")).toList();
        String choice =
                model.choose(
                        "Select the most useful verified detail to explain this particular search"
                            + " result to the shopper. Return its key as op.",
                        Map.of(
                                "query",
                                c.path("query").asString(),
                                "name",
                                c.path("name").asString(),
                                "facts",
                                facts),
                        details);
        String detail =
                choice != null
                        ? choice
                        : facts.containsKey("pair")
                                ? "pair"
                                : facts.containsKey("difference") ? "difference" : "availability";
        return Map.of(
                "offer_id",
                offerId,
                "source",
                choice == null ? "template" : "llm",
                "text",
                reason + " " + facts.get(detail),
                "evidence",
                List.of("reason", detail),
                "source_url",
                c.has("checks")
                        ? c.path("checks").get(0).path("source_url").asString()
                        : c.path("url").asString());
    }
}
