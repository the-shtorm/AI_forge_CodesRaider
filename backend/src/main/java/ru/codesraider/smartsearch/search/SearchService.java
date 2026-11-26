package ru.codesraider.smartsearch.search;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriUtils;

import ru.codesraider.smartsearch.analytics.EventStore;
import ru.codesraider.smartsearch.catalog.CatalogAccess;
import ru.codesraider.smartsearch.catalog.CatalogSnapshot;
import ru.codesraider.smartsearch.catalog.CatalogSnapshot.Product;
import ru.codesraider.smartsearch.catalog.CatalogSnapshot.Scope;
import ru.codesraider.smartsearch.catalog.CatalogText;
import ru.codesraider.smartsearch.web.ApiProblem;
import tools.jackson.databind.json.JsonMapper;

import static ru.codesraider.smartsearch.search.SearchApi.*;

@Service
class SearchService {
    private static final String PANTUM_PRINTERS = "93995";
    private static final Pattern PANTUM_MODEL = Pattern.compile("\\b(?:p|bm|bp)\\d{4}[a-z]*\\b");
    private static final Comparator<Product> OFFER_ORDER =
            Comparator.comparingInt(SearchService::tier).thenComparing(Product::id);
    private final CatalogAccess catalog;
    private final EventStore events;
    private final JsonMapper json;

    SearchService(CatalogAccess catalog, EventStore events, JsonMapper json) {
        this.catalog = catalog;
        this.events = events;
        this.json = json;
    }

    SearchResponse search(SearchRequest request) {
        long started = System.nanoTime();
        Instant time = Instant.now();
        var diagnostics = new LinkedHashMap<String, Object>();
        var detailOffers = new LinkedHashMap<String, String>();
        var response = withCatalog(snapshot -> execute(snapshot, request, diagnostics, detailOffers));
        events.logSearch(request, response, diagnostics, detailOffers, time, (System.nanoTime() - started) / 1_000_000L);
        return response;
    }

    private SearchResponse execute(CatalogSnapshot snapshot, SearchRequest request, Map<String, Object> diagnostics,
                                   Map<String, String> detailOffers) {
        String text = CatalogText.normalize(request.q());
        int limit = request.limit() == null ? 4 : request.limit();
        Filters requested = request.filters() == null ? Filters.empty() : request.filters();
        String contextCategory = request.context() == null ? null : request.context().categoryId();
        var target = resolve(snapshot, request.selectedTarget(), text, requested, contextCategory);
        Scope scope = snapshot.scope(target);
        Filters filters = requested.withScope(scope.categoryId(), scope.vendorId());
        boolean conflict = conflicts(scope.categoryId(), filters.categoryId()) || conflicts(scope.vendorId(), filters.vendorId());
        List<String> models = target == null ? pantumModels(text) : List.of();
        List<Product> candidates = conflict ? List.of() : candidates(snapshot, target, scope, filters, text, models);
        List<Product> eligible = candidates.stream().filter(p -> fits(snapshot, p, filters)).toList();
        var direct = representatives(eligible, contextCategory);
        int directCount = direct.size();
        var top = new ArrayList<Card>();
        var more = new ArrayList<Card>();
        var alternatives = new ArrayList<Card>();
        String reason = target == null ? "LEXICAL_MATCH" : "TARGET_MATCH";
        if (!models.isEmpty() || (target != null && "offer".equals(target.kind()))) reason = "EXACT_MATCH";
        for (Product product : direct.stream().limit(limit).toList()) {
            var card = new Card(offer(snapshot, product), product.family(), List.of(reason), "direct", null, null, null);
            (top.size() < 4 ? top : more).add(card);
        }
        if (directCount > 0 && top.size() < 4) {
            appendComplements(snapshot, direct, filters, top, diagnostics);
        } else if (directCount == 0 && !conflict && models.size() == 1 && text.contains("принтер")) {
            var available = snapshot.products(new Scope(null, null, "pantum", PANTUM_PRINTERS)).stream()
                    .filter(p -> p.model() != null && !models.contains(p.model()))
                    .filter(p -> fits(snapshot, p, filters))
                    .filter(p -> printerFacts(snapshot, p, text)).toList();
            for (var product : representatives(available, contextCategory).stream().limit(4).toList()) {
                alternatives.add(new Card(offer(snapshot, product), product.family(), List.of("FUNCTIONAL_ALTERNATIVE"),
                        "alternative", null, null, List.of("Другая модель: " + product.sku())));
            }
        }
        if (directCount == 0) {
            String failure = conflict || (!candidates.isEmpty() && eligible.isEmpty())
                    ? "filter_conflict" : alternatives.isEmpty() ? "unknown" : "catalog_gap";
            diagnostics.put("failure_reason", failure);
        }
        var shownFamilies = java.util.stream.Stream.of(top, more, alternatives).flatMap(List::stream)
                .map(Card::familyKey).distinct().toList();
        for (var product : snapshot.familyProducts(shownFamilies)) {
            if (fits(snapshot, product, filters)) detailOffers.put(product.id(), product.family());
        }
        String shortfall = top.size() == 4 ? null
                : directCount == 0 ? "NO_DIRECT_MATCH" : "NOT_ENOUGH_VERIFIED_FAMILIES";
        return new SearchResponse(UUID.randomUUID().toString(), snapshot.version(), "lexical", text, filters,
                target == null ? null : toTarget(target), directCount, List.copyOf(top), List.copyOf(more),
                List.copyOf(alternatives), shortfall);
    }

    private static CatalogSnapshot.Entity resolve(CatalogSnapshot snapshot, SelectedTarget selected, String text,
                                                   Filters filters, String contextCategory) {
        if (selected != null) {
            var entity = snapshot.entity(selected.kind(), selected.targetId());
            if (entity == null) {
                throw new ApiProblem(HttpStatus.BAD_REQUEST, "UNKNOWN_TARGET", "Выбранная цель отсутствует в снимке.");
            }
            return entity;
        }
        var byId = snapshot.entity("offer", text);
        if (byId != null) return byId;
        var exact = snapshot.bySku(CatalogText.sku(text));
        if (exact.size() == 1) return snapshot.entity("offer", exact.getFirst().id());
        if (!exact.isEmpty()) return null;
        String category = filters.categoryId() == null ? contextCategory : filters.categoryId();
        var aliases = snapshot.matchingNames(text, category);
        if (!aliases.isEmpty()) return aliases.getFirst();
        var names = snapshot.entities(snapshot.prefixes(text));
        return names.values().stream()
                .filter(entity -> !entity.kind().equals("offer") && CatalogText.normalize(entity.name()).equals(text))
                .sorted(Comparator.comparingInt((CatalogSnapshot.Entity entity) -> switch (entity.kind()) {
                    case "collection" -> 0;
                    case "vendor" -> 1;
                    case "family" -> 2;
                    default -> 3;
                }).thenComparing(CatalogSnapshot.Entity::id))
                .findFirst().orElse(null);
    }

    private static List<Product> candidates(CatalogSnapshot snapshot, CatalogSnapshot.Entity target, Scope scope, Filters filters,
                                            String text, List<String> models) {
        List<Product> products;
        if (target != null) {
            products = snapshot.products(scope);
        } else if (!models.isEmpty()) {
            products = models.stream().flatMap(model -> snapshot.bySku(model).stream())
                    .filter(p -> "pantum".equals(p.vendorId()) && PANTUM_PRINTERS.equals(p.categoryId())).toList();
        } else {
            products = snapshot.bySku(CatalogText.sku(text));
            if (products.isEmpty()) {
                String keywords = text.replaceAll("(?U)\\b(?:найди|найдите|подбери|покажи|нужен|нужна|купить)\\b", " ");
                List<String> families = snapshot.lexicalFamilies(keywords, filters.categoryId(), filters.vendorId());
                var indexed = snapshot.familyProducts(families).stream()
                        .collect(Collectors.groupingBy(Product::family));
                products = families.stream().flatMap(family -> indexed.getOrDefault(family, List.of()).stream()).toList();
            }
        }
        return products;
    }

    List<Suggestion> suggest(String query, String category) {
        return withCatalog(snapshot -> {
            String text = CatalogText.normalize(query);
            List<String> keys = snapshot.prefixes(text);
            var entities = snapshot.entities(keys);
            return keys.stream().map(entities::get).filter(Objects::nonNull)
                    .filter(entity -> category == null || snapshot.inCategory(entity, category))
                    .sorted(Comparator.comparingInt((CatalogSnapshot.Entity entity) -> switch (entity.kind()) {
                        case "collection" -> 0;
                        case "offer" -> 1;
                        case "family" -> 2;
                        case "vendor" -> 3;
                        default -> 4;
                    }))
                    .limit(6).map(entity -> new Suggestion(entity.kind(), entity.id(), entity.name())).toList();
        });
    }

    Entity entity(String kind, String id, Filters requested) {
        return withCatalog(snapshot -> {
            var entity = snapshot.entity(kind, id);
            if (entity == null) {
                throw new ApiProblem(HttpStatus.NOT_FOUND, "ENTITY_NOT_FOUND", "Сущность отсутствует в активном снимке.");
            }
            var scope = snapshot.scope(entity);
            var filters = requested.withScope(scope.categoryId(), scope.vendorId());
            var target = toTarget(entity);
            if (conflicts(scope.categoryId(), filters.categoryId()) || conflicts(scope.vendorId(), filters.vendorId())) {
                return new Entity(kind, id, entity.name(), target.url(), List.of(), List.of());
            }
            var products = snapshot.products(scope).stream().filter(p -> fits(snapshot, p, filters))
                    .sorted(OFFER_ORDER).toList();
            var children = new ArrayList<Target>();
            List<Offer> offers;
            if ("offer".equals(kind) || "family".equals(kind)) {
                offers = products.stream().map(p -> offer(snapshot, p)).toList();
                if ("offer".equals(kind) && !products.isEmpty() && products.getFirst().family() != null) {
                    var product = products.getFirst();
                    var siblings = snapshot.products(new Scope(null, product.family(), null, null));
                    if (siblings.stream().anyMatch(p -> !p.id().equals(id) && fits(snapshot, p, filters))) {
                        var family = snapshot.entities(List.of(product.family())).get(product.family());
                        children.add(toTarget(family));
                    }
                }
            } else {
                offers = List.of();
                var seen = new LinkedHashSet<String>();
                for (Product product : products) {
                    if (seen.add(product.family() == null ? "offer/" + product.id() : product.family())) {
                        children.add(new Target("offer", product.id(), product.url()));
                    }
                }
            }
            return new Entity(kind, id, entity.name(), target.url(), offers, List.copyOf(children));
        });
    }

    private <T> T withCatalog(Function<CatalogSnapshot, T> handler) {
        try {
            return handler.apply(catalog.require());
        } catch (DataAccessException exception) {
            throw ApiProblem.catalog(exception);
        }
    }

    private static Target toTarget(CatalogSnapshot.Entity entity) {
        String url = entity.canonicalUrl();
        if (url == null) {
            url = "/?kind=" + entity.kind() + "&id=" + UriUtils.encodeQueryParam(entity.id(), StandardCharsets.UTF_8);
        }
        return new Target(entity.kind(), entity.id(), url);
    }

    private static Offer offer(CatalogSnapshot snapshot, Product product) {
        var attributes = new LinkedHashMap<String, List<String>>();
        for (var param : snapshot.parameters(product.id())) {
            if (param.field() != null && param.normalized() != null) {
                attributes.computeIfAbsent(param.name(), ignored -> new ArrayList<>()).add(param.value());
            }
        }
        Long oldPrice = product.primary() && product.oldPrice() != null && product.oldPrice() > product.price()
                ? product.oldPrice() : null;
        return new Offer(product.id(), product.sku(), product.name(), product.vendor(), product.url(), product.available(),
                product.hasPrice() ? product.price() : null, product.currency(), product.primary() ? "primary" : "reserve",
                oldPrice, attributes);
    }

    private static int tier(Product product) {
        if (product.primary()) return 0;
        return product.hasPrice() ? 1 : 2;
    }

    private static List<Product> representatives(List<Product> candidates, String category) {
        int best = candidates.stream().filter(p -> p.family() != null).mapToInt(SearchService::tier).min().orElse(3);
        var eligible = candidates.stream().filter(p -> p.family() != null && tier(p) == best);
        if (category != null) {
            eligible = eligible.sorted(Comparator.comparing((Product p) -> !category.equals(p.categoryId())));
        }
        return List.copyOf(eligible.collect(Collectors.toMap(Product::family, Function.identity(),
                (first, second) -> first, LinkedHashMap::new)).values());
    }

    private void appendComplements(CatalogSnapshot snapshot, List<Product> direct, Filters filters,
                                   List<Card> top, Map<String, Object> diagnostics) {
        var seen = direct.stream().map(Product::family).collect(Collectors.toCollection(LinkedHashSet::new));
        var options = new ArrayList<Complement>();
        var evaluations = new ArrayList<Map<String, Object>>();
        for (var anchor : direct.stream().limit(4).toList()) {
            for (var relation : snapshot.relations(anchor.family())) {
                if (seen.contains(relation.candidateFamily())) continue;
                var predicates = json.readTree(relation.predicates());
                for (var candidate : snapshot.products(new Scope(null, relation.candidateFamily(), null, null))) {
                    if (!fits(snapshot, candidate, filters)) continue;
                    var checks = new ArrayList<Check>();
                    String result = predicates.isArray() && !predicates.isEmpty() ? "pass" : "unknown";
                    for (var predicate : predicates) {
                        boolean supported = "supported_device".equals(predicate.path("field").asString(null));
                        String vendor = predicate.path("vendor_id").asString(null);
                        String anchorSku = predicate.path("anchor_sku").asString(null);
                        String candidateSku = predicate.path("candidate_sku").asString(null);
                        if (!supported || vendor == null || anchorSku == null || candidateSku == null
                                || anchor.normalizedSku() == null || candidate.normalizedSku() == null) {
                            result = "unknown";
                            break;
                        }
                        if (!(vendor.equals(anchor.vendorId()) && vendor.equals(candidate.vendorId())
                                && anchor.normalizedSku().equals(anchorSku)
                                && candidate.normalizedSku().equals(candidateSku))) {
                            result = "fail";
                            break;
                        }
                        checks.add(new Check("supported_device", anchor.sku(), candidate.sku(), relation.sourceUrl(), "pass"));
                    }
                    var evaluation = new LinkedHashMap<String, Object>();
                    evaluation.put("anchor_offer_id", anchor.id());
                    evaluation.put("candidate_offer_id", candidate.id());
                    evaluation.put("rule_id", relation.ruleId());
                    evaluation.put("rule_version", relation.ruleVersion());
                    evaluation.put("source_url", relation.sourceUrl());
                    evaluation.put("anchor_value", anchor.sku());
                    evaluation.put("candidate_value", candidate.sku());
                    evaluation.put("result", result);
                    evaluations.add(evaluation);
                    if ("pass".equals(result)) options.add(new Complement(anchor, candidate, checks));
                }
            }
        }
        if (!evaluations.isEmpty()) diagnostics.put("relation_checks", evaluations);
        int best = options.stream().mapToInt(option -> tier(option.product())).min().orElse(3);
        for (var option : options) {
            Product product = option.product();
            if (top.size() == 4) break;
            if (tier(product) == best && seen.add(product.family())) {
                top.add(new Card(offer(snapshot, product), product.family(), List.of("VERIFIED_COMPATIBILITY"), "complement",
                        option.anchor().id(), option.checks(), null));
            }
        }
    }

    private record Complement(Product anchor, Product product, List<Check> checks) {}

    private static List<String> pantumModels(String text) {
        if (!text.contains("pantum") && !text.contains("пантум")) return List.of();
        return PANTUM_MODEL.matcher(text).results().map(match -> match.group()).distinct().toList();
    }

    private static boolean printerFacts(CatalogSnapshot snapshot, Product product, String text) {
        String description = CatalogText.normalize(product.name() + " " + snapshot.parameters(product.id()).stream()
                .map(CatalogSnapshot.Parameter::value).collect(Collectors.joining(" ")));
        return (!text.contains("лазер") || description.contains("лазер"))
                && (!text.contains("монохром") || description.contains("черно-бел") || description.contains("монохром"))
                && (!text.contains("a4") || description.contains("a4") || description.contains("а4"));
    }

    private static boolean conflicts(String scope, String filter) {
        return scope != null && filter != null && !scope.equals(filter);
    }

    private boolean fits(CatalogSnapshot snapshot, Product product, Filters filters) {
        if (conflicts(product.categoryId(), filters.categoryId()) || conflicts(product.vendorId(), filters.vendorId())
                || (Boolean.TRUE.equals(filters.availableOnly()) && !product.available())
                || (filters.version() != null && !CatalogText.normalize(filters.version()).equals(product.version()))
                || (filters.licenseType() != null && !filters.licenseType().equals(product.licenseType()))
                || (filters.termMonths() != null && !filters.termMonths().equals(product.term()))
                || (filters.platform() != null && !snapshot.hasPlatform(product.id(),
                        CatalogText.normalize(filters.platform())))) return false;
        if (!covers(product.devices(), product.deviceMin(), product.deviceMax(), filters.deviceCount(), product.priceUnit(),
                    "device", product.factSources())
                || !covers(product.seats(), product.seatMin(), product.seatMax(), filters.seatCount(), product.priceUnit(),
                    "seat", product.factSources())) return false;
        return filters.maxPriceMinor() == null || withinBudget(product, filters);
    }

    private boolean covers(Long fixed, Long min, Long max, Long count, String priceUnit, String type, String factSources) {
        if (count == null) return true;
        if (type.equals(priceUnit) && fixed != null && fixed > 0 && min == null && max == null) return true;
        if (fixed != null) return fixed >= count;
        var sources = min == null || max == null ? json.readTree(factSources) : null;
        return (min != null ? count >= min : sources.path(type + "_min").path("open").asBoolean(false))
                && (max != null ? count <= max : sources.path(type + "_max").path("open").asBoolean(false));
    }

    private static boolean withinBudget(Product product, Filters filters) {
        if (!product.hasPrice() || !filters.currency().equals(product.currency()) || product.priceUnit() == null
                || product.minOrder() == null) return false;
        long units = product.minOrder();
        if (product.priceUnit().equals("package")) {
            if (units != 1) return false;
        } else {
            boolean device = product.priceUnit().equals("device");
            Long count = device ? filters.deviceCount() : filters.seatCount();
            Long coverage = device ? product.devices() : product.seats();
            if (count == null || coverage == null || coverage <= 0) return false;
            units = Math.max(units, 1 + (count - 1) / coverage);
        }
        return BigInteger.valueOf(product.price()).multiply(BigInteger.valueOf(units))
                .compareTo(BigInteger.valueOf(filters.maxPriceMinor())) <= 0;
    }
}
