package ru.codesraider.smartsearch.search;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

/** Queries for one immutable catalog version. One connection is owned by one HTTP request. */
final class SearchCatalog implements AutoCloseable {
    private final Connection db;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String TIER =
            "CASE WHEN o.price_minor>0 AND o.available=1 THEN 0 WHEN o.price_minor>0 THEN 1 ELSE 2"
                    + " END";
    // SQLite's built-in lower() handles ASCII. The internal fragments match both initial
    // uppercase and lowercase Russian words, e.g. «Техподдержка» and «техподдержки».
    private static final String SUPPORT_TITLE =
            "(instr(o.name,'оддержк')>0 OR instr(o.name,'опровожден')>0 OR lower(o.name) LIKE"
                    + " '%support%')";
    private static final String PURCHASE_ORDER =
            "CASE WHEN "
                    + SUPPORT_TITLE
                    + " THEN 4 WHEN o.license_type IN ('upgrade','renewal') OR"
                    + " instr(o.name,'родлени')>0 OR instr(o.name,'бновлени')>0 OR lower(o.name)"
                    + " LIKE '%cross-grade%' THEN 3 WHEN instr(o.name,'истрибутив')>0 OR"
                    + " instr(o.name,'омплект носител')>0 THEN 2 WHEN o.license_type='new' THEN 0"
                    + " ELSE 1 END";
    private static final String COLUMNS =
            "o.offer_id,o.family_key,o.category_id,o.vendor_id,o.sku_raw AS"
                + " sku,o.available,o.price_minor,o.old_price_minor,o.currency,o.url,o.name,o.base_model,o.version,o.edition,o.license_type,v.name"
                + " AS vendor,"
                    + TIER
                    + " AS tier";

    SearchCatalog(Path path) throws SQLException {
        db =
                DriverManager.getConnection(
                        "jdbc:sqlite:" + path.toUri().toASCIIString() + "?mode=ro");
    }

    @Override
    public void close() throws SQLException {
        db.close();
    }

    static final class Row extends LinkedHashMap<String, Object> {
        String str(String k) {
            return (String) get(k);
        }

        int number(String k) {
            return ((Number) get(k)).intValue();
        }

        Map<String, Object> destination() {
            String kind = str("kind"), id = str("id"), url = str("canonical_url");
            if (url == null || url.isBlank())
                url = "/?kind=" + kind + "&id=" + URLEncoder.encode(id, StandardCharsets.UTF_8);
            return Map.of("kind", kind, "id", id, "url", url);
        }

        Map<String, Object> offer() {
            Map<String, Object> out = new LinkedHashMap<>();
            for (String key : List.of("offer_id", "sku", "name", "vendor", "url", "currency"))
                out.put(key, get(key));
            out.put("available", number("available") != 0);
            Long price = positive(get("price_minor")), old = positive(get("old_price_minor"));
            out.put("price_minor", price);
            if (number("available") == 1 && price != null && old != null && old > price)
                out.put("old_price_minor", old);
            out.put("offer_tier", number("tier") == 0 ? "primary" : "reserve");
            return out;
        }

        Map<String, Object> card(String role, String reason) {
            Map<String, Object> out = offer();
            out.put("family_key", str("family_key"));
            out.put("role", role);
            out.put("reason_codes", List.of(reason));
            return out;
        }

        private static Long positive(Object n) {
            return n instanceof Number amount && amount.longValue() > 0 ? amount.longValue() : null;
        }
    }

    private List<Row> rows(String sql, List<?> args) throws SQLException {
        try (var ps = db.prepareStatement(sql)) {
            for (int i = 0; i < args.size(); i++) ps.setObject(i + 1, args.get(i));
            try (var rs = ps.executeQuery()) {
                List<Row> out = new ArrayList<>();
                var meta = rs.getMetaData();
                while (rs.next()) {
                    var row = new Row();
                    for (int i = 1; i <= meta.getColumnCount(); i++)
                        row.put(meta.getColumnLabel(i), rs.getObject(i));
                    out.add(row);
                }
                return out;
            }
        }
    }

    Row entity(String kind, String id) throws SQLException {
        return rows("SELECT * FROM entity WHERE kind=? AND id=?", List.of(kind, id)).stream()
                .findFirst()
                .orElse(null);
    }

    Row byKey(String key) throws SQLException {
        return rows("SELECT * FROM entity WHERE entity_key=?", List.of(key)).stream()
                .findFirst()
                .orElse(null);
    }

    List<Row> exact(String q) throws SQLException {
        return rows(
                "SELECT e.* FROM offer o JOIN entity e ON e.entity_key=o.entity_key WHERE"
                        + " o.sku_norm=? OR o.offer_id=? ORDER BY o.offer_id",
                List.of(q, q));
    }

    List<String> serverOfferIds() throws SQLException {
        // Even the Rack-server leaf in this feed contains DEPO Race workstations.
        return rows(
                        "SELECT offer_id,name FROM offer WHERE category_id IN"
                            + " ('86563','87093','87268','87276') AND family_key IS NOT NULL ORDER"
                            + " BY family_key",
                        List.of())
                .stream()
                .filter(
                        r ->
                                SearchIndex.normalize(r.str("name"))
                                        .matches("(?U).*(?:сервер|server)\\b.*"))
                .map(r -> r.str("offer_id"))
                .toList();
    }

    Row alias(String q, String category) throws SQLException {
        var matches =
                rows(
                        "SELECT e.* FROM alias a JOIN entity e ON a.entity_key=e.entity_key WHERE"
                            + " a.term=? AND a.scope IN ('*',?) ORDER BY (a.scope='*'),e.entity_key"
                            + " LIMIT 1",
                        List.of(q, "category/" + category));
        return matches.isEmpty() ? null : matches.getFirst();
    }

    List<Map<String, Object>> suggest(String q, String category, List<String> matchingNames)
            throws SQLException {
        // Prefix scans use binary range bounds on stored normalized keys.
        String hi = q + Character.toString(Character.MAX_CODE_POINT);
        var clauses = new ArrayList<Object>(List.of(q, hi, "category/" + category, q, hi));
        String names = "";
        if (!matchingNames.isEmpty()) {
            names =
                    " UNION ALL SELECT entity_key,2 FROM entity WHERE entity_key IN ("
                            + String.join(",", Collections.nCopies(matchingNames.size(), "?"))
                            + ")";
            clauses.addAll(matchingNames);
        }
        String membership = "";
        if (category != null) {
            membership =
                    """
                     WHERE (e.kind='category' AND e.id=?) OR EXISTS(
                     SELECT 1 FROM offer o WHERE o.category_id=? AND
                     (o.entity_key=e.entity_key OR o.family_key=e.entity_key OR (e.kind='vendor' AND o.vendor_id=e.id)))
                     OR EXISTS(SELECT 1 FROM collection_scope c WHERE c.entity_key=e.entity_key AND c.category_id=?)
                    """;
            clauses.addAll(List.of(category, category, category));
        }
        var hits =
                rows(
                        """
                        WITH candidate(entity_key,priority) AS (
                          SELECT entity_key,0 FROM alias WHERE term>=? AND term<? AND scope IN ('*',?)
                          UNION ALL SELECT entity_key,1 FROM offer WHERE sku_norm>=? AND sku_norm<?
                        """
                                + names
                                + ") SELECT e.*,min(c.priority) AS priority FROM candidate c JOIN"
                                + " entity e USING(entity_key) "
                                + membership
                                + " GROUP BY e.entity_key ORDER BY priority,e.name,e.id LIMIT 6",
                        clauses);
        return hits.stream()
                .map(
                        r ->
                                Map.<String, Object>of(
                                        "kind",
                                        r.str("kind"),
                                        "target_id",
                                        r.str("id"),
                                        "label",
                                        r.str("name")))
                .toList();
    }

    private static final class Where {
        final List<String> parts = new ArrayList<>();
        final List<Object> args = new ArrayList<>();

        void add(String expression, Object... values) {
            parts.add(expression);
            Collections.addAll(args, values);
        }

        void oneOf(String column, List<String> values) {
            if (values.isEmpty()) {
                add("0");
                return;
            }
            parts.add(
                    column
                            + " IN ("
                            + String.join(",", Collections.nCopies(values.size(), "?"))
                            + ")");
            args.addAll(values);
        }

        String sql() {
            return parts.isEmpty() ? "1" : String.join(" AND ", parts);
        }
    }

    Map<String, Object> scope(Row entity) throws SQLException {
        if (entity == null) return Map.of();
        return switch (entity.str("kind")) {
            case "collection" ->
                    rows(
                                    "SELECT vendor_id,category_id FROM collection_scope WHERE"
                                            + " entity_key=?",
                                    List.of(entity.str("entity_key")))
                            .getFirst();
            case "vendor" -> Map.of("vendor_id", entity.str("id"));
            case "category" -> Map.of("category_id", entity.str("id"));
            default -> Map.of();
        };
    }

    private Where where(Map<String, Object> filters) {
        var w = new Where();
        for (String k :
                List.of("category_id", "vendor_id", "version", "license_type", "term_months"))
            if (filters.containsKey(k)) w.add("o." + k + "=?", filters.get(k));
        if (Boolean.TRUE.equals(filters.get("available_only"))) w.add("o.available=1");
        if (filters.containsKey("platform"))
            w.add(
                    "EXISTS(SELECT 1 FROM offer_param p WHERE p.offer_id=o.offer_id"
                            + " AND field_key='platform' AND value_norm=?)",
                    SearchInput.normalize(filters.get("platform").toString()));
        for (String dim : List.of("seat", "device")) {
            Object count = filters.get(dim + "_count");
            if (count != null)
                w.add(
                        "(o."
                                + dim
                                + "_count>=? OR (? BETWEEN o."
                                + dim
                                + "_min AND o."
                                + dim
                                + "_max))",
                        count,
                        count);
        }
        if (filters.containsKey("max_price_minor")) {
            w.add(
                    "o.currency=? AND o.price_minor>0 AND (CASE o.price_unit WHEN 'package' THEN"
                            + " CASE WHEN COALESCE(o.min_order_qty,1)=1 THEN o.price_minor END WHEN"
                            + " 'seat' THEN o.price_minor * max(COALESCE(o.min_order_qty,1),?) WHEN"
                            + " 'device' THEN o.price_minor * max(COALESCE(o.min_order_qty,1),?)"
                            + " END)<=?",
                    filters.getOrDefault("currency", "RUB"),
                    filters.getOrDefault("seat_count", 1),
                    filters.getOrDefault("device_count", 1),
                    filters.get("max_price_minor"));
        }
        return w;
    }

    /** Distinct families from the highest nonempty price/availability tier. */
    List<Row> representatives(
            Map<String, Object> filters,
            Row target,
            List<String> families,
            List<String> offerIds,
            boolean support,
            boolean fileRecovery)
            throws SQLException {
        Where w = where(filters);
        if (fileRecovery) {
            // Rank relevant families first; a category match also supplies candidates when a
            // paraphrase has no useful lexical hits. Explicit filters stay on this same SKU.
            // Snapshot 2026-11-25 classifies Recoverit as a file manager. Verify vendor AND
            // family rather than admitting every file manager. https://recoverit.wondershare.com/
            w.add(
                    "(o.category_id IN ('5922','1033') OR (o.category_id='14574' AND"
                        + " o.vendor_id='wondershare' AND o.family_key='family/301714'))");
            // An engine backup requires the matching database, not just a disk with lost files.
            w.add(
                    "lower(o.name) NOT LIKE '%sql%' AND lower(o.name) NOT LIKE '%oracle%'"
                            + " AND lower(o.name) NOT LIKE '%exchange%'");
        } else if (families != null) w.oneOf("o.family_key", families);
        if (offerIds != null) w.oneOf("o.offer_id", offerIds);
        if (target != null && "family".equals(target.str("kind")))
            w.add("o.family_key=?", target.str("entity_key"));
        if (target != null && "offer".equals(target.str("kind")))
            w.add("o.offer_id=?", target.str("id"));
        w.add("o.family_key IS NOT NULL");
        if (support && offerIds == null && (target == null || !"offer".equals(target.str("kind"))))
            w.add(SUPPORT_TITLE);
        // Select one SKU per family in SQL before reading the result across JDBC.
        var hits =
                rows(
                        "SELECT * FROM (SELECT "
                                + COLUMNS
                                + ",row_number() OVER (PARTITION BY o.family_key ORDER BY "
                                + TIER
                                + ","
                                + PURCHASE_ORDER
                                + ",o.offer_id) AS rn FROM offer o JOIN entity v ON"
                                + " v.entity_key='vendor/'||o.vendor_id WHERE "
                                + w.sql()
                                + ") WHERE rn=1 ORDER BY tier,family_key",
                        w.args);
        if (hits.isEmpty()) return hits;
        int best = hits.getFirst().number("tier");
        hits.removeIf(row -> row.number("tier") != best);
        if (families != null) {
            Map<String, Integer> order = new HashMap<>();
            for (int i = 0; i < families.size(); i++) order.putIfAbsent(families.get(i), i);
            Comparator<Row> byRank =
                    Comparator.comparingInt(
                            row -> order.getOrDefault(row.str("family_key"), Integer.MAX_VALUE));
            if (fileRecovery) {
                // A recovery tool can help after the loss. A backup suite can recover an
                // existing copy, so offer it after products with a direct recovery purpose.
                byRank =
                        Comparator.<Row>comparingInt(
                                        row -> "1033".equals(row.str("category_id")) ? 1 : 0)
                                .thenComparing(byRank)
                                .thenComparingInt(
                                        row ->
                                                "family/301714".equals(row.str("family_key"))
                                                        ? 0
                                                        : 1);
            }
            hits.sort(byRank);
        }
        return hits;
    }

    List<Row> variants(Row target, Map<String, Object> filters) throws SQLException {
        var w = where(filters);
        if ("family".equals(target.str("kind"))) w.add("o.family_key=?", target.str("entity_key"));
        else w.add("o.offer_id=?", target.str("id"));
        return rows(
                "SELECT "
                        + COLUMNS
                        + " FROM offer o JOIN entity v ON v.entity_key='vendor/'||o.vendor_id WHERE"
                        + " "
                        + w.sql()
                        + " ORDER BY tier,o.offer_id",
                w.args);
    }

    List<Map<String, Object>> detailedOffers(List<Row> rows) throws SQLException {
        Map<String, Map<String, List<String>>> byId = new HashMap<>();
        for (int i = 0; i < rows.size(); i += 300) {
            var ids =
                    rows.subList(i, Math.min(i + 300, rows.size())).stream()
                            .map(r -> r.str("offer_id"))
                            .toList();
            for (var p :
                    rows(
                            "SELECT offer_id,name,value FROM offer_param WHERE offer_id IN ("
                                    + String.join(",", Collections.nCopies(ids.size(), "?"))
                                    + ") ORDER BY offer_id,ordinal",
                            ids)) {
                byId.computeIfAbsent(p.str("offer_id"), k -> new LinkedHashMap<>())
                        .computeIfAbsent(p.str("name"), k -> new ArrayList<>())
                        .add(p.str("value"));
            }
        }
        return rows.stream()
                .map(
                        r -> {
                            var dto = r.offer();
                            dto.put("attributes", byId.getOrDefault(r.str("offer_id"), Map.of()));
                            return dto;
                        })
                .toList();
    }

    List<Map<String, Object>> complements(
            List<Row> anchors, Map<String, Object> filters, int capacity) throws SQLException {
        Set<String> used =
                anchors.stream().map(r -> r.str("family_key")).collect(Collectors.toSet());
        List<Map<String, Object>> cards = new ArrayList<>();
        for (Row anchor : anchors) {
            for (var relation :
                    rows(
                            "SELECT * FROM relation WHERE anchor_family=? ORDER BY"
                                    + " candidate_family,rule_id",
                            List.of(anchor.str("family_key")))) {
                String family = relation.str("candidate_family");
                if (used.contains(family)) continue;
                Row candidateFamily = byKey(family);
                for (var candidate : variants(candidateFamily, filters)) {
                    List<Map<String, Object>> checks = check(anchor, candidate, relation);
                    if (checks.isEmpty()) continue;
                    var card = candidate.card("complement", "COMPATIBLE_CONSUMABLE");
                    card.put("checks", checks);
                    card.put("anchor_offer_id", anchor.str("offer_id"));
                    card.put("_tier", candidate.number("tier"));
                    cards.add(card);
                    break;
                }
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Row anchor : anchors) {
            var available =
                    cards.stream()
                            .filter(c -> anchor.str("offer_id").equals(c.get("anchor_offer_id")))
                            .filter(c -> !used.contains(c.get("family_key")))
                            .sorted(Comparator.comparingInt(c -> (int) c.get("_tier")))
                            .toList();
            if (available.isEmpty()) continue;
            int best = (int) available.getFirst().get("_tier");
            for (var card : available) {
                if ((int) card.get("_tier") != best || out.size() == capacity) break;
                if (used.add((String) card.get("family_key"))) {
                    card.remove("_tier");
                    out.add(card);
                }
            }
        }
        return out;
    }

    private List<Map<String, Object>> check(Row anchor, Row candidate, Row rule) {
        List<Map<String, Object>> checks = new ArrayList<>();
        JsonNode predicates = JSON.readTree(rule.str("predicates_json"));
        if (!predicates.isArray()) return List.of();
        for (JsonNode pred : predicates) {
            String field = pred.path("field").asString("");
            String a = null, b = null;
            if ("supported_device".equals(field)) {
                String vendor = pred.path("vendor_id").asString("");
                if (!vendor.equals(anchor.str("vendor_id"))
                        || !vendor.equals(candidate.str("vendor_id"))) return List.of();
                a = anchor.str("sku");
                b = candidate.str("sku");
                if (a == null
                        || b == null
                        || !a.equalsIgnoreCase(pred.path("anchor_sku").asString(""))
                        || !b.equalsIgnoreCase(pred.path("candidate_sku").asString("")))
                    return List.of();
            } else if ("compatible_versions".equals(field)) {
                String ak = pred.path("anchor_field").asString(""),
                        bk = pred.path("candidate_field").asString("");
                if (!Set.of("version", "edition", "license_type").contains(ak)
                        || !Set.of("version", "edition", "license_type").contains(bk))
                    return List.of();
                a = anchor.str(ak);
                b = candidate.str(bk);
                if (a == null
                        || b == null
                        || !a.equalsIgnoreCase(pred.path("anchor_equals").asString(""))
                        || !b.equalsIgnoreCase(pred.path("candidate_equals").asString("")))
                    return List.of();
            } else return List.of();
            checks.add(
                    Map.of(
                            "field",
                            field,
                            "anchor_value",
                            a,
                            "candidate_value",
                            b,
                            "source_url",
                            rule.str("source_url"),
                            "result",
                            "pass"));
        }
        return checks;
    }

    /**
     * Only confirmed printer SKUs with every requested print attribute can substitute the named
     * model.
     */
    List<Row> printerAlternatives(Map<String, Object> filters, String requested, String q)
            throws SQLException {
        // This rule has evidence for type, print technology, paper format and monochrome output.
        // Leave other requests unresolved until there is a predicate for every requested attribute.
        var supported =
                Set.of(
                        "нужен",
                        "нужны",
                        "купить",
                        "покажи",
                        "ищу",
                        "найти",
                        "хочу",
                        "мне",
                        "пожалуйста",
                        "принтер",
                        "принтеры",
                        "монохромный",
                        "лазерный",
                        "черно-белый",
                        "a4",
                        "а4",
                        "pantum");
        for (String token : q.split("(?U)[^\\p{L}\\d-]+"))
            if (!token.isEmpty()
                    && !token.equalsIgnoreCase(requested)
                    && !supported.contains(token)) return List.of();
        Where w = where(filters);
        w.add("o.vendor_id='pantum' AND o.category_id='93995' AND o.base_model IS NOT NULL");
        var variants =
                rows(
                        "SELECT "
                                + COLUMNS
                                + ",o.description FROM offer o JOIN entity v ON"
                                + " v.entity_key='vendor/'||o.vendor_id WHERE "
                                + w.sql()
                                + " ORDER BY tier,o.offer_id",
                        w.args);
        Map<String, Row> chosen = new LinkedHashMap<>();
        int best = Integer.MAX_VALUE;
        for (var row : variants) {
            String evidence =
                    SearchInput.normalize(
                            row.str("name") + " " + Objects.toString(row.get("description"), ""));
            if (row.str("base_model").equalsIgnoreCase(requested) || !evidence.contains("принтер"))
                continue;
            if (q.contains("лазер") && !evidence.contains("лазер")) continue;
            if (q.matches("(?s).*\\b[aа]4\\b.*") && !evidence.matches("(?s).*\\b[aа]4\\b.*"))
                continue;
            if ((q.contains("монохром") || q.contains("черно-бел"))
                    && !(evidence.contains("монохром")
                            || evidence.contains("ч/б")
                            || evidence.contains("черно-бел"))) continue;
            if (row.number("tier") > best) break;
            best = row.number("tier");
            chosen.putIfAbsent(row.str("family_key"), row);
            if (chosen.size() >= 4) break;
        }
        return new ArrayList<>(chosen.values());
    }
}
