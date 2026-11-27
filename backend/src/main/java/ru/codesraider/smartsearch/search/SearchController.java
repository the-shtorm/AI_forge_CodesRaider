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

    private static final Pattern IDENTIFIER = Pattern.compile("(?U)^(id|sku|артикул)[:# ]+(.+)$");

    private final ModelClient model;
    // SQLite journals responses across restarts. Serialize the read/choose/journal cycle for
    // simultaneous sends from the same chat without retaining unbounded session identifiers.
    private final Object[] chatLocks = java.util.stream.Stream.generate(Object::new).limit(256).toArray();

    public SearchController(SearchIndex index, EventStore events) {
        this(index, events, new ModelClient("", "softline", "", 3000));
    }

    @org.springframework.beans.factory.annotation.Autowired
    SearchController(SearchIndex index, EventStore events, ModelClient model) {
        this.model = model;
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
        SearchInput input = SearchInput.parse(request);
        var chat = input.strategy().equals("llm") ? AssistantTurn.from(request) : null;
        if (chat != null && chat.session() != null) {
            synchronized (chatLocks[Math.floorMod(chat.session().hashCode(), chatLocks.length)]) {
                return run(request, input, chat, start);
            }
        }
        return run(request, input, chat, start);
    }

    private Map<String, Object> run(Map<String, Object> request, SearchInput input, AssistantTurn chat, long start)
            throws SQLException, IOException {
        Instant time = Instant.now();
        Map<String, Object> response;
        var diagnostics = new LinkedHashMap<String, Object>();
        var detailOffers = new LinkedHashMap<String, String>();
        try (var db = open()) {
            var effective = input;
            if (chat != null && input.target().isEmpty()) {
                String query = chat.resolve(input.query(), () -> chat.session() == null ? null
                        : events.lastChatQuery(chat.session(), index.version()));
                if (!query.equals(input.query()))
                    effective = new SearchInput(query, SearchInput.normalize(query), input.limit(), input.sort(),
                            input.strategy(), input.context(), input.filters(), input.target());
            }
            response = search(effective, db, diagnostics, detailOffers, chat);
            response.put("normalized_q", input.normalized());
        }
        try {
            events.logSearch(request, response, diagnostics, detailOffers, chat == null ? time : Instant.now(), (System.nanoTime() - start) / 1_000_000);
        } catch (ApiProblem failure) {
            throw ApiProblem.searchJournal(failure);
        }
        return response;
    }

    private Map<String, Object> search(SearchInput input, SearchCatalog db,
                                       Map<String, Object> diagnostics, Map<String, String> detailOffers, AssistantTurn chat)
            throws SQLException, IOException {
        String q = input.productQuery();
        Row target = null;
        String reason = "LEXICAL_MATCH", mode = "lexical";
        List<String> ids = null, families = null;
        boolean includeServerSoftware = false;
        String spelling = null;
        String missingModel = null;
        Requirements constraints = new Requirements();
        constraints.task = CatalogTask.parse(q);
        String licensingQuery = constraints.task instanceof InventoryIntent inventory ? inventory.licensingQuery() : q;
        QueryPlan plan = QueryPlan.words(q);
        String terms = q;
        Set<String> aliases = Set.of();
        if (chat != null && input.target().isEmpty() && chat.needsClarification(q)) {
            constraints.task = null;
            ids = List.of();
            plan = new QueryPlan("rules", QueryPlan.Purpose.UNKNOWN, q, List.of(), "all");
        } else if (!input.target().isEmpty()) {
            constraints.extractQuery(licensingQuery);
            target =
                    db.entity(
                            (String) input.target().get("kind"),
                            (String) input.target().get("target_id"));
            if (target == null)
                throw new ApiProblem(
                        HttpStatus.BAD_REQUEST, "UNKNOWN_TARGET", "Подсказка отсутствует в активном снимке.");
        } else {
            var identifier = IDENTIFIER.matcher(q);
            boolean qualified = identifier.matches();
            var exact = qualified ? db.identifier(identifier.group(1), identifier.group(2))
                    : db.exact(SearchIndex.normalizeSku(q));
            if (exact.isEmpty() && q.matches("(?iu)[a-z][a-z0-9]* \\d+"))
                exact = db.exact(q.replace(' ', '-'));
            if (qualified || !exact.isEmpty()) {
                ids = exact.stream().map(r -> r.str("id")).toList();
                if (exact.size() == 1) target = exact.getFirst();
                reason = "EXACT_SKU";
            } else {
                String category = (String) input.filters().getOrDefault("category_id", input.context().get("category_id"));
                // Resolve hard constraints on the same SKU before grouping or counting facets.
                terms = constraints.extractQuery(licensingQuery);
                target = db.purchaseTarget(terms);
                terms = terms.replaceAll("(?U)\\b(?:покажи|найди|мне|нужен|нужна|купить|пожалуйста)\\b", " ")
                        .strip().replaceAll(" +", " ");
                aliases = db.aliasTerms(category);
                terms = QueryPlan.canonical(terms, aliases);
                if (constraints.edition != null && terms.matches("[a-z][a-z0-9 -]+ (?:19|20)\\d{2}"))
                    constraints.editionYear = terms.substring(terms.length() - 4);
                if (target == null) target = db.alias(q, category);
                if (target == null) target = db.alias(terms, category);
                if (target == null) target = db.qualifiedSku(terms);
                if (target == null && constraints.task == null && !input.requestsCompleteServer() && !input.requestsFileRecovery()) {
                    target = db.exactName(terms, index.exactNames(terms));
                    if (target == null && !terms.isEmpty() && !MODEL_CODE.matcher(terms).matches()
                            && !CATALOG_CODE.matcher(terms).matches()) {
                        String family = index.namedFamily(terms);
                        if (family != null) target = db.byKey(family);
                    }
                }
                if (target == null && constraints.task == null) {
                    spelling = spellingIfUnknown(terms, aliases);
                    if (spelling != null) {
                        terms = spelling;
                        target = db.alias(terms, category);
                    }
                }
                plan = target == null && constraints.task == null && !input.requestsFileRecovery()
                        ? QueryPlan.compile(terms, aliases, db, model) : QueryPlan.words(terms);
            }
        }
        var required = new LinkedHashMap<>(db.scope(target));
        boolean requiredConflict = merge(required, constraints.values) || constraints.conflict;
        // Legacy phrasing without «на» (e.g. «2 года») is still accepted.
        if (target == null || !"offer".equals(target.str("kind"))) {
            var inferred = input.inferredFilters(licensingQuery);
            if (!constraints.excludedLicenses.isEmpty()) inferred.remove("license_type");
            if (!constraints.excludedVersions.isEmpty()) inferred.remove("version");
            if (plan.purpose() == QueryPlan.Purpose.SERVER_SOFTWARE && db.entity("category", "369") == null) inferred.remove("category_id");
            requiredConflict |= merge(required, inferred);
        }
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
                var exact = db.printerModel(SearchIndex.normalizeSku(code), terms);
                matchedIds.addAll(exact.stream().map(r -> r.str("id")).toList());
                if (exact.isEmpty() && db.exact(SearchIndex.normalizeSku(code)).isEmpty()) missingModel = code.toUpperCase(Locale.ROOT);
            }
            if (matcher.reset().find()) {
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
        } else if (ids == null && constraints.task != null) {
            if (constraints.task.purpose() != QueryPlan.Purpose.WORDS)
                plan = new QueryPlan("task_rules", constraints.task.purpose(), terms, List.of(), "all");
            reason = "PRODUCT_TYPE_MATCH";
        } else if (ids == null && !conflict && (input.requestsCompleteServer() || terms.equals("сервер") || terms.equals("серверы"))) {
            ids = db.serverOfferIds();
            reason = "PRODUCT_TYPE_MATCH";
            // Only the bare term is ambiguous. "Готовый сервер" still requests a device.
            includeServerSoftware = (q.equals("сервер") || q.equals("серверы") || ("сервер".equals(spelling) && !q.contains(" ")))
                    && ("software_admin".equals(input.context().get("profile_id"))
                        || "369".equals(filters.get("category_id"))
                        || "369".equals(input.context().get("category_id")));
        } else if (ids == null
                && (MODEL_CODE.matcher(q).matches() || CATALOG_CODE.matcher(q).matches())) {
            // A cartridge may mention an absent printer model. It cannot satisfy an exact model
            // lookup.
            ids = List.of();
        } else if (ids == null && plan.purpose() != QueryPlan.Purpose.WORDS) {
            reason = "PURPOSE_MATCH";
        } else if (ids == null) {
            boolean task = q.contains(" ") && !MODEL_CODE.matcher(q).find();
            boolean failedProgram = plan.compiler().equals("llm_fallback");
            boolean semantic = task && !failedProgram;
            ranking = index.prepareRanking(terms, semantic, failedProgram || (task && !input.requestsFileRecovery()));
            if (!conflict) {
                var ranked = ranking.in((String) filters.get("vendor_id"), (String) filters.get("category_id"));
                families = ranked.families();
                mode = ranked.mode();
                if (mode.equals("hybrid")) reason = "HYBRID_MATCH";
            }
        }
        boolean fileRecovery = target == null && ids == null && input.requestsFileRecovery();
        if (!conflict && target == null && ids == null && constraints.values.containsKey("version")
                && families != null && families.size() == 1) {
            ProductTitle product = ProductTitle.from(terms);
            if (product != null) constraints.namedVersions = db.namedVersions(families.getFirst(), product);
        }
        var selection = new Selection(target, ids,
                input.requestsSupport(),
                fileRecovery, fileRecovery && input.requestsStandaloneFileRecovery(), includeServerSoftware,
                target == null && ids == null && !fileRecovery && constraints.task == null ? plan : null, constraints);
        List<Row> direct = conflict ? List.of() : db.representatives(filters, selection, families);
        if (direct.isEmpty() && !conflict && selection.program() != null && plan.isSinglePurpose()
                && !db.hasPurpose(plan.purpose())) {
            // Missing taxonomy in this snapshot. Keep every SKU constraint and require text evidence.
            diagnostics.put("unavailable_purpose", plan.purpose().name());
            plan = new QueryPlan("catalog_fallback", QueryPlan.Purpose.WORDS, terms, List.of(), "all");
            selection = new Selection(null, null, selection.support(), false, false, false, plan, constraints);
            ranking = index.prepareRanking(terms, true, true);
            var ranked = ranking.in((String) filters.get("vendor_id"), (String) filters.get("category_id"));
            families = ranked.families();
            mode = ranked.mode();
            reason = mode.equals("hybrid") ? "HYBRID_MATCH" : "LEXICAL_MATCH";
            direct = db.representatives(filters, selection, families);
        }
        // Reassess an empty result or an off-topic relevance ranking. Explicit targets bypass this path.
        if (!conflict && (direct.isEmpty() || input.sort().equals("relevance"))
                && constraints.task == null && families != null && selection.program() != null
                && plan.purpose() == QueryPlan.Purpose.WORDS && plan.compiler().equals("rules")) {
            var interpreted = TaskInterpreter.interpret(terms, aliases, db, model, ranking, direct);
            if (interpreted != null) {
                var alternative = new Selection(null, null, selection.support(), false, false, false, interpreted, constraints);
                var eligible = db.representatives(filters, alternative, null);
                if (direct.isEmpty() || !eligible.isEmpty()) {
                    diagnostics.put("interpretation_trigger", direct.isEmpty() ? "empty" : "purpose_mismatch");
                    plan = interpreted;
                    selection = alternative;
                    ranking = null;
                    families = null;
                    mode = "lexical";
                    reason = "PURPOSE_MATCH";
                    direct = eligible;
                }
            }
        }
        String chatMessage = null;
        var replacedCards = new HashSet<String>();
        if (chat != null && !conflict && !fileRecovery && families != null && selection.program() != null
                && plan.purpose() == QueryPlan.Purpose.WORDS && constraints.namedVersions.isEmpty()) {
            boolean weak = direct.isEmpty() || !index.matchesDirectTitle(terms, direct);
            // When all eligible families fit on screen, a mere change of order cannot help.
            // Only a free-form lexical task may be recompiled. A known target keeps its identity.
            boolean retry = !weak && !plan.appliedModel() && chat.session() != null && !direct.isEmpty()
                    && direct.size() <= chat.cardLimit() && input.sort().equals("relevance")
                    && TaskInterpreter.describesTask(terms) && chat.canRewrite(terms)
                    && events.repeatsChatCards(chat.session(), index.version(), chat.cards(direct), Instant.now());
            if ((weak || retry) && !db.mentionsVendor(terms) && !aliases.contains(terms)) {
                var priorTitles = retry ? direct.stream().map(row -> row.str("name")).toList() : List.<String>of();
                QueryPlan proposal = chat.canRewrite(terms) && model.configured()
                        ? TaskInterpreter.chatPlan(terms, chat, model, db, priorTitles) : null;
                var candidate = proposal != null ? proposal
                        : new QueryPlan("rules", QueryPlan.Purpose.UNKNOWN, terms, List.of(), "all");
                var alternative = new Selection(null, null, selection.support(), false, false, false, candidate, constraints);
                var eligible = db.representatives(filters, alternative, null);
                var priorIds = retry ? chat.cards(direct) : List.<String>of();
                if (!retry || eligible.stream().anyMatch(row -> !priorIds.contains(row.str("offer_id")))) {
                    plan = candidate;
                    selection = alternative;
                    ranking = null;
                    families = null;
                    mode = "lexical";
                    reason = plan.purpose() == QueryPlan.Purpose.WORDS ? "CATEGORY_MATCH" : "PURPOSE_MATCH";
                    direct = eligible;
                    replacedCards.addAll(priorIds);
                }
            }
        }
        if (selection.program() != null && plan.isSinglePurpose() && direct.size() > 1
                && input.sort().equals("relevance")) {
            // The catalog decides eligibility. BM25 breaks ties inside the verified purpose.
            var ranked = index.rank(terms, (String) filters.get("vendor_id"), (String) filters.get("category_id"), false);
            SearchCatalog.orderByFamilies(direct, ranked.families(), false);
            if (TaskInterpreter.wantsDesktopUps(plan))
                direct.sort(Comparator.comparing(row -> TaskInterpreter.highCapacityUps(row.str("name"))));
        }
        if (direct.size() > 1) {
            if (!input.sort().equals("relevance")) {
                Comparator<Long> byPrice = input.sort().equals("price_asc")
                        ? Comparator.naturalOrder() : Comparator.reverseOrder();
                direct.sort(Comparator.comparing(row -> Row.positive(row.get("price_minor")),
                        Comparator.nullsLast(byPrice)));
            } else if (includeServerSoftware) {
                direct.sort(Comparator.comparing(row -> !"369".equals(row.str("category_id"))));
            } else if (input.context().get("category_id") instanceof String category) {
                direct.sort(Comparator.comparing(row -> !category.equals(row.str("category_id"))));
            }
        }
        if (chat != null) {
            if (!replacedCards.isEmpty()) {
                direct.sort(Comparator.comparing(row -> replacedCards.contains(row.str("offer_id"))));
                diagnostics.put("chat_repetition", true);
                diagnostics.put("chat_order", "NEXT_MATCH");
                chatMessage = "Подобрал другие товары для той же задачи. Условия поиска сохранены.";
            }
            var cards = chat.cards(direct);
            if (replacedCards.isEmpty() && chat.session() != null && events.repeatsChatCards(chat.session(), index.version(), cards, Instant.now())) {
                diagnostics.put("chat_repetition", true);
                if (input.sort().equals("relevance")) {
                    chatMessage = chat.diversify(direct, q, model, diagnostics);
                    cards = chat.cards(direct);
                } else {
                    chatMessage = "Эти товары снова первыми по выбранной сортировке. Какой критерий подбора изменить?";
                }
            }
            diagnostics.put("chat_cards", cards);
            diagnostics.put("chat_query", input.query());
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
        if (direct.isEmpty() && !constraints.namedVersions.isEmpty() && !input.filters().containsKey("version")) {
            var relaxed = new LinkedHashMap<>(filters);
            String requested = relaxed.remove("version").toString();
            for (Row sku : db.representatives(relaxed, selection, families)) {
                var alternative = sku.card("alternative", "FUNCTIONAL_ALTERNATIVE");
                alternative.put("differences", List.of("Версия " + constraints.namedVersions.get(sku.str("offer_id"))
                        + " вместо запрошенной " + requested + "."));
                alternatives.add(alternative);
            }
        }
        List<Map<String, Object>> upsell = List.of();
        if (!conflict && !input.skipsUpsell() && (chat == null || !direct.isEmpty())) {
            Set<String> shownFamilies = new HashSet<>();
            for (var block : List.of(top, more, alternatives))
                for (var card : block) shownFamilies.add((String) card.get("family_key"));

            var evaluations = new ArrayList<Map<String, Object>>();
            var verified = db.complements(anchors, filters, 4, evaluations).stream()
                    .filter(card -> shownFamilies.add((String) card.get("family_key"))).toList();
            // Keep the established top4 contract. The independent upsell block also works with four direct hits.
            top.addAll(verified.subList(0, Math.min(4 - top.size(), verified.size())));
            var suggestions = new ArrayList<>(verified);
            // A category or vendor navigation target scopes the main results. A stand can belong to
            // a different category than the laptops on that page. Keep explicit shopper filters.
            var merchandisingFilters = new LinkedHashMap<>(filters);
            if (!anchors.isEmpty()) {
                for (String key : List.of("category_id", "vendor_id"))
                    if (!input.filters().containsKey(key)) merchandisingFilters.remove(key);
            }
            if (!merchandisingFilters.equals(filters) && suggestions.size() < 4) {
                for (var card : db.complements(anchors, merchandisingFilters, 4, evaluations)) {
                    if (shownFamilies.add((String) card.get("family_key"))) suggestions.add(card);
                    if (suggestions.size() == 4) break;
                }
            }
            if (!evaluations.isEmpty()) diagnostics.put("relation_checks", evaluations.stream().distinct().toList());
            if (suggestions.size() < 4) {
                suggestions.addAll(db.catalogConsumables(anchors, merchandisingFilters, shownFamilies, 4 - suggestions.size()));
            }
            if (suggestions.isEmpty()) {
                var shoppingFilters = new LinkedHashMap<>(input.filters());
                for (String key : List.of("available_only", "max_price_minor", "currency"))
                    if (filters.containsKey(key)) shoppingFilters.put(key, filters.get(key));
                Row contextHint = anchors.isEmpty() ? db.upsellContext(target, alternatives, families,
                        (String) input.context().get("category_id")) : null;
                suggestions.addAll(db.upsellSuggestions(anchors, merchandisingFilters, shoppingFilters, shownFamilies,
                        (String) input.context().get("profile_id"), 4, contextHint));
            }
            upsell = suggestions;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("search_id", UUID.randomUUID().toString());
        result.put("index_version", index.version());
        result.put("mode", mode);
        result.put("normalized_q", input.normalized());
        var applied = new LinkedHashMap<>(filters);
        applied.putIfAbsent("available_only", false);
        applied.putIfAbsent("currency", "RUB");
        result.put("applied_filters", applied);
        result.put("primary_target", target == null ? null : target.destination());
        result.put("direct_count", direct.size());
        result.put("top4", top);
        result.put("more_results", more);
        result.put("alternatives", alternatives);
        result.put("upsell", upsell);
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
        var trace = new LinkedHashMap<>(plan.trace(constraints));
        if (diagnostics.containsKey("chat_order")) trace.put("order", diagnostics.get("chat_order"));
        result.put("program", trace);
        diagnostics.put("program", trace);
        result.put("llm_applied", plan.appliedModel());
        if (chat != null) {
            String message = "Показаны совпадения каталога по текущей фразе. Для уточнения примените фильтры.";
            if (direct.isEmpty()) {
                message = plan.purpose() == QueryPlan.Purpose.UNKNOWN || (plan.purpose() == QueryPlan.Purpose.WORDS
                        && !plan.appliedModel() && target == null && ids == null && constraints.task == null && !fileRecovery && !conflict)
                        ? AssistantTurn.CLARIFY : AssistantTurn.FILTERED;
                if (!alternatives.isEmpty()) {
                    var first = alternatives.getFirst();
                    var differences = (List<?>) first.get("differences");
                    message = "Точного совпадения нет. В каталоге есть замена — " + first.get("name")
                            + ". " + differences.getFirst();
                }
            }
            result.put("assistant_message", chatMessage == null ? message : chatMessage);
            if (diagnostics.containsKey("chat_model_order")) result.put("llm_applied", true);
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
        shown.addAll(upsell);
        db.addAttributes(shown);
        detailOffers.putAll(db.detailOffers(shown, filters));
        return result;
    }

    private static boolean merge(Map<String, Object> result, Map<String, ?> next) {
        boolean conflict = false;
        for (var field : next.entrySet()) {
            Object previous = result.put(field.getKey(), field.getValue());
            if (previous != null && !previous.equals(field.getValue())) {
                if (field.getKey().equals("max_price_minor"))
                    result.put(field.getKey(), Math.min((long) previous, (long) field.getValue()));
                else if (!(previous instanceof String left && field.getValue() instanceof String right
                        && field.getKey().equals("platform") && SearchInput.normalize(left).equals(SearchInput.normalize(right))))
                    conflict = true;
            }
        }
        return conflict;
    }

    private String spellingIfUnknown(String text, Set<String> aliases) throws IOException {
        String suggestion = QueryPlan.missingLetter(text, aliases);
        if (suggestion == null || !index.exactNames(text).isEmpty()) return null;
        return index.rank(text, null, null, false).families().isEmpty() ? suggestion : null;
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
            var literal = db.suggest(prefix, category, profile, index.suggestNames(prefix));
            if (!prefix.matches("[a-z]{4,47}") || !db.exact(prefix).isEmpty()) return literal;
            var aliases = db.aliasTerms(category);
            if (aliases.contains(prefix)) return literal;
            String correction = spellingIfUnknown(prefix, aliases);
            if (correction == null) return literal;
            var corrected = db.suggest(correction, category, profile, index.suggestNames(correction));
            if (literal.isEmpty()) return corrected;
            // A shorter input can also be a real brand prefix (sever → Severcart).
            var choices = new LinkedHashSet<Map<String, Object>>();
            choices.add(literal.getFirst());
            corrected.stream().limit(2).forEach(choices::add);
            choices.addAll(literal);
            return choices.stream().limit(6).toList();
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
    @PostMapping(path = "/explain", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> explain(@RequestBody Map<String, Object> request) {
        if (!request.keySet().equals(Set.of("search_id", "offer_id"))) throw SearchInput.invalid("explain");
        String searchId = SearchInput.text(request.get("search_id"), "search_id");
        String offerId = SearchInput.text(request.get("offer_id"), "offer_id");
        var c = events.shownCard(searchId, offerId);
        if (c == null || !c.has("url"))
            throw new ApiProblem(HttpStatus.NOT_FOUND, "CARD_NOT_FOUND", "Для этой карточки нет сохранённого показа с фактами.");
        String reason = "related".equals(c.path("role").asString())
                ? c.path("recommendation_reason").asString("Предложение из дополнительной подборки каталога.")
                : switch (c.path("reason_codes").path(0).asString("")) {
            case "EXACT_SKU", "MODEL_MATCH" -> "Совпал артикул или идентификатор предложения.";
            case "COMPATIBLE_CONSUMABLE" -> "Для этой модели найден совместимый расходный материал.";
            case "PURPOSE_MATCH" -> "Каталожные признаки товара соответствуют задаче из запроса.";
            case "FUNCTIONAL_ALTERNATIVE" -> "Предложена другая модель с подтверждёнными характеристиками.";
            case "FAMILY_MATCH", "VENDOR_MATCH", "COLLECTION_MATCH", "CATEGORY_MATCH" ->
                    "Предложение входит в выбранное семейство, подборку или раздел каталога.";
            default -> "Поиск нашёл совпадение в каталоге.";
        };
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
                    "Источник совместимости для "
                            + check.path("anchor_value").asString()
                            + " указывает "
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
