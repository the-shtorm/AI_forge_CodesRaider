package ru.codesraider.smartsearch.search;

import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import ru.codesraider.smartsearch.analytics.EventStore;
import ru.codesraider.smartsearch.search.SearchCatalog.Row;
import ru.codesraider.smartsearch.search.SearchCatalog.Selection;
import ru.codesraider.smartsearch.web.ApiProblem;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

@RestController
@RequestMapping(path = "/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class SearchController {
    private final SearchIndex index;
    private final EventStore events;
    private static final Pattern MODEL_CODE =
            Pattern.compile("(?iu)\\b[a-z][a-z-]*\\d{3,}[a-z\\d-]*\\b");
    private static final Pattern CATALOG_CODE =
            Pattern.compile("(?U)[\\p{L}\\d]+(?:-[\\p{L}\\d]+)*-\\d{3,}");
    private static final Pattern PANTUM_MODEL = Pattern.compile("\\b(?:p|bm|bp)\\d{4}[a-z]*\\b");

    public SearchController(SearchIndex index, EventStore events) {
        this.index = index;
        this.events = events;
    }

    private SearchCatalog open() throws SQLException {
        if (!index.ready()) throw ApiProblem.catalog();
        return new SearchCatalog(index.catalogPath());
    }

    @PostMapping(path = "/search", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> search(@RequestBody Map<String, Object> request)
            throws SQLException, IOException {
        long start = System.nanoTime();
        Instant time = Instant.now();
        SearchInput input = SearchInput.parse(request);
        Map<String, Object> response;
        var diagnostics = new LinkedHashMap<String, Object>();
        var detailOffers = new LinkedHashMap<String, String>();
        try (var db = open()) {
            response = search(input, db, diagnostics, detailOffers);
        }
        try {
            events.logSearch(request, response, diagnostics, detailOffers, time, (System.nanoTime() - start) / 1_000_000);
        } catch (ApiProblem failure) {
            throw ApiProblem.searchJournal(failure);
        }
        return response;
    }

    private Map<String, Object> search(SearchInput input, SearchCatalog db,
                                       Map<String, Object> diagnostics, Map<String, String> detailOffers)
            throws SQLException, IOException {
        String q = input.normalized();
        Row target = null;
        String reason = "LEXICAL_MATCH", mode = "lexical";
        List<String> ids = null, families = null;
        boolean includeServerSoftware = false;
        String missingModel = null;
        if (!input.target().isEmpty()) {
            target =
                    db.entity(
                            (String) input.target().get("kind"),
                            (String) input.target().get("target_id"));
            if (target == null)
                throw new ApiProblem(
                        HttpStatus.BAD_REQUEST, "UNKNOWN_TARGET", "Подсказка отсутствует в активном снимке.");
        } else {
            Row byId = db.entity("offer", q);
            var exact = byId == null ? db.exact(SearchIndex.normalizeSku(q)) : List.of(byId);
            if (!exact.isEmpty()) {
                ids = exact.stream().map(r -> r.str("id")).toList();
                if (exact.size() == 1) target = exact.getFirst();
                reason = "EXACT_SKU";
            } else {
                String category = (String) input.filters().getOrDefault("category_id", input.context().get("category_id"));
                target = db.alias(q, category);
                if (target == null && !input.requestsCompleteServer() && !input.requestsFileRecovery()) {
                    target = db.exactName(q, index.exactNames(q));
                }
            }
        }
        var required = new LinkedHashMap<>(db.scope(target));
        boolean requiredConflict = merge(required, input.inferredFilters());
        var filters = new LinkedHashMap<>(required);
        boolean conflict = merge(filters, input.filters()) || requiredConflict;
        SearchIndex.QueryRanking ranking = null;

        // A model code embedded in a request for a device must match the device's SKU,
        // even if the same code occurs in the description of a cartridge.
        if (!conflict
                && target == null
                && ids == null
                && (q.contains("pantum") || q.contains("пантум"))) {
            boolean deviceIntent = q.contains("устройств") || q.contains("принтер") || q.contains("мфу");
            var matcher = (deviceIntent ? MODEL_CODE : PANTUM_MODEL).matcher(q);
            var matchedIds = new ArrayList<String>();
            while (matcher.find()) {
                String code = matcher.group();
                var exact = db.printerModel(SearchIndex.normalizeSku(code));
                matchedIds.addAll(exact.stream().map(r -> r.str("id")).toList());
                if (exact.isEmpty()) missingModel = code.toUpperCase(Locale.ROOT);
            }
            if (!matchedIds.isEmpty() || missingModel != null) {
                ids = matchedIds;
                reason = "MODEL_MATCH";
            }
        }
        if (target != null) {
            reason =
                    switch (target.str("kind")) {
                        case "offer" -> "EXACT_SKU";
                        case "family" -> "FAMILY_MATCH";
                        case "vendor" -> "VENDOR_MATCH";
                        case "collection" -> "COLLECTION_MATCH";
                        default -> "CATEGORY_MATCH";
                    };
        } else if (ids == null && !conflict && input.requestsCompleteServer()) {
            ids = db.serverOfferIds();
            reason = "PRODUCT_TYPE_MATCH";
            // Only the bare term is ambiguous. "Готовый сервер" still requests a device.
            includeServerSoftware = (q.equals("сервер") || q.equals("серверы"))
                    && ("software_admin".equals(input.context().get("profile_id"))
                        || "369".equals(filters.get("category_id"))
                        || "369".equals(input.context().get("category_id")));
        } else if (ids == null
                && (MODEL_CODE.matcher(q).matches() || CATALOG_CODE.matcher(q).matches())) {
            // A cartridge may mention an absent printer model. It cannot satisfy an exact model
            // lookup.
            ids = List.of();
        } else if (ids == null) {
            boolean task = q.contains(" ") && !MODEL_CODE.matcher(q).find();
            ranking = index.prepareRanking(input.query(), task);
            if (!conflict) {
                var ranked = ranking.in((String) filters.get("vendor_id"), (String) filters.get("category_id"));
                families = ranked.families();
                mode = ranked.mode();
                if (mode.equals("hybrid")) reason = "HYBRID_MATCH";
            }
        }
        boolean fileRecovery = target == null && ids == null && input.requestsFileRecovery();
        var selection = new Selection(target, ids,
                q.contains("поддержк") || q.contains("сопровожден") || q.contains("support"),
                fileRecovery, includeServerSoftware);
        List<Row> direct = conflict ? List.of() : db.representatives(filters, selection, families);
        if (includeServerSoftware && direct.size() > 1 && input.sort().equals("relevance"))
            direct.sort(Comparator.comparing(row -> !"369".equals(row.str("category_id"))));
        else if (input.sort().equals("relevance") && input.context().get("category_id") instanceof String category)
            direct.sort(Comparator.comparing(row -> !category.equals(row.str("category_id"))));
        if (direct.size() > 1 && !input.sort().equals("relevance")) {
            Comparator<Long> byPrice = input.sort().equals("price_asc")
                    ? Comparator.naturalOrder() : Comparator.reverseOrder();
            direct.sort(Comparator.comparing(row -> Row.positive(row.get("price_minor")),
                    Comparator.nullsLast(byPrice)));
        }
        List<Row> anchors = direct.subList(0, Math.min(4, direct.size()));
        List<Map<String, Object>> top = new ArrayList<>(),
                more = new ArrayList<>(),
                alternatives = new ArrayList<>();
        for (int i = 0; i < Math.min(input.limit(), direct.size()); i++) {
            var row = direct.get(i);
            var card = row.card("direct", reason);
            if (includeServerSoftware && "369".equals(row.str("category_id"))
                    && "software_admin".equals(input.context().get("profile_id")))
                card.put("reason_codes", List.of(reason, "PROFILE_MATCH"));
            if (fileRecovery) {
                String evidence =
                        "14574".equals(row.str("category_id"))
                                ? "VERIFIED_RECOVERY_PRODUCT"
                                : "FILE_RECOVERY_CATEGORY";
                card.put(
                        "reason_codes",
                        families != null && families.contains(row.str("family_key"))
                                ? List.of(evidence, reason)
                                : List.of(evidence));
            }
            (i < 4 ? top : more).add(card);
        }
        if (!anchors.isEmpty() && top.size() < 4 && !input.skipsConsumables()) {
            var evaluations = new ArrayList<Map<String, Object>>();
            top.addAll(db.complements(anchors, filters, 4 - top.size(), evaluations));
            if (!evaluations.isEmpty()) diagnostics.put("relation_checks", evaluations);
        }
        if (!conflict && direct.isEmpty() && missingModel != null && q.contains("принтер")) {
            for (Row item : db.printerAlternatives(filters, missingModel, q)) {
                var card = item.card("alternative", "FUNCTIONAL_ALTERNATIVE");
                card.put(
                        "differences",
                        List.of(
                                "Другая модель принтера: "
                                        + item.str("base_model")
                                        + " вместо "
                                        + missingModel
                                        + "."));
                alternatives.add(card);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("search_id", UUID.randomUUID().toString());
        result.put("index_version", index.version());
        result.put("mode", mode);
        result.put("normalized_q", q);
        var applied = new LinkedHashMap<>(filters);
        applied.putIfAbsent("available_only", false);
        applied.putIfAbsent("currency", "RUB");
        result.put("applied_filters", applied);
        result.put("primary_target", target == null ? null : target.destination());
        result.put("direct_count", direct.size());
        result.put("top4", top);
        result.put("more_results", more);
        result.put("alternatives", alternatives);
        Map<String, List<Row>> facets = new LinkedHashMap<>();
        for (String field : List.of("vendor_id", "category_id")) {
            var explicit = new LinkedHashMap<>(input.filters());
            explicit.remove(field);
            var facetFilters = new LinkedHashMap<>(required);
            List<Row> options = List.of();
            if (!merge(facetFilters, explicit) && !requiredConflict) {
                List<String> facetFamilies = families;
                if (ranking != null && !fileRecovery && (conflict || !facetFilters.equals(filters))) {
                    facetFamilies = ranking.in((String) facetFilters.get("vendor_id"),
                            (String) facetFilters.get("category_id")).families();
                }
                options = db.facet(field, facetFilters, selection, facetFamilies);
            }
            facets.put(field.equals("vendor_id") ? "vendors" : "categories", options);
        }
        result.put("facets", facets);
        result.put("llm_applied", false);
        if (input.strategy().equals("llm")) {
            result.put("assistant_message", direct.isEmpty()
                    ? "По текущей фразе каталог не нашёл товаров. Введите название, артикул или уточните задачу."
                    : "Показаны совпадения каталога по текущей фразе. Для уточнения примените фильтры.");
        }
        result.put(
                "shortfall_reason",
                top.size() == 4
                        ? null
                        : (direct.isEmpty() ? "NO_DIRECT_MATCH" : "NOT_ENOUGH_VERIFIED_FAMILIES"));
        if (direct.isEmpty()) {
            boolean filtered = conflict || facets.values().stream().anyMatch(options -> !options.isEmpty());
            diagnostics.put("failure_reason", filtered ? "filter_conflict"
                    : alternatives.isEmpty() ? "unknown" : "catalog_gap");
        }
        var shown = new ArrayList<>(top);
        shown.addAll(more);
        shown.addAll(alternatives);
        db.addAttributes(shown);
        detailOffers.putAll(db.detailOffers(shown, filters));
        return result;
    }

    private static boolean merge(Map<String, Object> result, Map<String, ?> next) {
        boolean conflict = false;
        for (var field : next.entrySet()) {
            Object previous = result.put(field.getKey(), field.getValue());
            if (previous != null && !previous.equals(field.getValue())) conflict = true;
        }
        return conflict;
    }

    @GetMapping("/suggest")
    public List<Map<String, Object>> suggest(
            @RequestParam("q") String q,
            @RequestParam(name = "category_id", required = false) String category,
            @RequestParam(name = "profile_id", required = false) String profile)
            throws SQLException, IOException {
        String prefix = SearchInput.normalize(SearchInput.text(q, "q"));
        if (category != null) SearchInput.text(category, "category_id");
        if (profile != null) SearchInput.text(profile, "profile_id");
        try (var db = open()) {
            return db.suggest(prefix, category, profile, index.suggestNames(prefix));
        }
    }

    @GetMapping("/entities/{kind}/{id}")
    public Map<String, Object> entity(
            @PathVariable("kind") String kind,
            @PathVariable("id") String id,
            @RequestParam Map<String, String> query)
            throws SQLException {
        if (!SearchInput.KINDS.contains(kind)) throw SearchInput.invalid("kind");
        SearchInput.text(id, "id");
        var filters = SearchInput.filters(query, true);
        try (var db = open()) {
            Row target = db.entity(kind, id);
            if (target == null)
                throw new ApiProblem(
                        HttpStatus.NOT_FOUND, "ENTITY_NOT_FOUND", "Сущность отсутствует в активном снимке.");
            var scope = new LinkedHashMap<>(db.scope(target));
            boolean conflict = merge(scope, filters);
            Map<String, Object> result = new LinkedHashMap<>(target.destination());
            result.put("name", target.str("name"));
            result.put("offers", List.of());
            List<Map<String, Object>> children = new ArrayList<>();
            result.put("children", children);
            if (conflict) return result;
            if (kind.equals("offer") || kind.equals("family")) {
                var variants = db.variants(target, scope);
                result.put("offers", db.detailedOffers(variants));
                if (kind.equals("offer")
                        && !variants.isEmpty()
                        && variants.getFirst().get("family_key") != null) {
                    Row family = db.byKey(variants.getFirst().str("family_key"));
                    if (db.variants(family, scope).size() > 1) children.add(family.destination());
                }
            } else {
                for (var rep : db.representatives(scope, new Selection(target, null, false, false), null)) {
                    // Link to the verified offer, preserving membership even for a mixed-category
                    // family.
                    children.add(
                            Map.of(
                                    "kind",
                                    "offer",
                                    "id",
                                    rep.str("offer_id"),
                                    "url",
                                    rep.str("url")));
                }
            }
            return result;
        }
    }
}
