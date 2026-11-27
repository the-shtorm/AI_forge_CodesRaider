package ru.codesraider.smartsearch.search;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Queries for one immutable catalog version. One connection is owned by one HTTP request. */
final class SearchCatalog implements AutoCloseable {
    private final Connection db;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern TITLE_PLATFORM = Pattern.compile(
            "(?iuU)(?<!не )(?<!not )\\b(?:для|for)\\s+(linux|windows|macos)\\b");
    private static final String TIER =
            "CASE WHEN o.price_minor>0 AND o.available=1 THEN 0 WHEN o.price_minor>0 THEN 1 ELSE 2"
                    + " END";
    private static final String POPULATED_CATEGORY =
            "(e.kind<>'category' OR EXISTS (SELECT 1 FROM offer o"
                    + " WHERE o.category_id=e.id AND o.family_key IS NOT NULL))";
    // SQLite's built-in lower() handles ASCII. The internal fragments match both initial
    // uppercase and lowercase Russian words, e.g. «Техподдержка» and «техподдержки».
    private static final String SUPPORT_TITLE =
            "(instr(o.name,'оддержк')>0 OR instr(o.name,'опровожден')>0 OR lower(o.name) LIKE"
                    + " '%support%')";
    // A client access licence can mention a server. Require a licence for deployment on
    // the server itself, using this SKU's title rather than another variant in its family.
    private static final String SERVER_LICENSE_TITLE = """
            ((o.name LIKE '%ерверная лиценз%' OR lower(o.name) LIKE '%server license%'
              OR (o.name LIKE '%ицензи%' AND (o.name LIKE '% на сервере%'
                  OR o.name LIKE '% для сервера%' OR o.name LIKE '% для физического сервера%'
                  OR (o.name || ' ') GLOB '* на сервер[ ,.)]*')))
             AND o.name NOT LIKE '% не на сервер%' AND o.name NOT LIKE '% не для сервера%')
            """;
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
        org.sqlite.Function.create(db, "has_edition", new org.sqlite.Function() {
            @Override protected void xFunc() throws SQLException {
                result(ProductTitle.hasEdition(value_text(0), value_text(1), value_text(2)) ? 1 : 0);
            }
        }, 3, org.sqlite.Function.FLAG_DETERMINISTIC);
        org.sqlite.Function.create(db, "audience_capacity", new org.sqlite.Function() {
            @Override protected void xFunc() throws SQLException {
                result(Requirements.audienceCapacity(value_text(0)));
            }
        }, 1, org.sqlite.Function.FLAG_DETERMINISTIC);
        org.sqlite.Function.create(db, "inventory_capacity", new org.sqlite.Function() {
            @Override protected void xFunc() throws SQLException {
                result(InventoryIntent.coverage(value_text(0)));
            }
        }, 1, org.sqlite.Function.FLAG_DETERMINISTIC);
        org.sqlite.Function.create(db, "pdf_editor", new org.sqlite.Function() {
            @Override protected void xFunc() throws SQLException {
                result(Requirements.PDF_EDITOR_TITLE.matcher(value_text(0)).find() ? 1 : 0);
            }
        }, 1, org.sqlite.Function.FLAG_DETERMINISTIC);
        org.sqlite.Function.create(db, "title_supports_platform", new org.sqlite.Function() {
            @Override protected void xFunc() throws SQLException {
                String platform = value_text(1);
                var mentions = TITLE_PLATFORM.matcher(value_text(0));
                while (mentions.find()) {
                    if (mentions.group(1).equalsIgnoreCase(platform)) { result(1); return; }
                }
                result(0);
            }
        }, 2, org.sqlite.Function.FLAG_DETERMINISTIC);
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

        static Long positive(Object n) {
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

    private static final String PRINTER_CATEGORY = "o.category_id IN (SELECT id FROM entity WHERE kind='category' AND (id='93995' OR name LIKE '%ринтер%' OR name LIKE '%МФУ%'))";
    private static final String SERVER_OFFER = "(o.category_id IN ('86563','87093','87268','87276') AND (o.name LIKE '%ервер%' OR lower(o.name) LIKE '%server%' OR (o.vendor_id='depo' AND lower(o.base_model) LIKE 'storm %')))";

    // Category proves device type, the title, description and labeled feed parameters prove features.
    private static final String PRINT_EVIDENCE = "o.name || ' ' || coalesce(o.description,'') || ' ' || "
            + "coalesce((SELECT group_concat(p.value,' ') FROM offer_param p WHERE p.offer_id=o.offer_id "
            + "AND p.name IN ('Технология печати','Тип печати','Цветность','Цветность печати','Формат','Формат бумаги','Максимальный формат')), '')";
    // An accessory request is satisfied by the checked relation of the displayed printer.
    private static final Pattern CARTRIDGE_ADD_ON = Pattern.compile(
            "(?U)\\bсо?\\s+(?:(?:подходящим|совместимым)и?\\s+)?картридж(?:ем|ами)"
                    + "(?:\\s+для\\s+каждого(?:\\s+(?:из\\s+них|устройства|принтера|мфу))?)?[.!?]*\\s*$");

    List<Row> printerModel(String sku, String request) throws SQLException {
        return rows("SELECT e.*, " + PRINT_EVIDENCE + " AS evidence FROM offer o JOIN entity e ON e.entity_key=o.entity_key "
                + "WHERE o.sku_norm=? AND o.vendor_id='pantum' AND " + PRINTER_CATEGORY + " ORDER BY o.offer_id", List.of(sku))
                .stream().filter(row -> printerFeatures(request, row.str("evidence"))).toList();
    }

    private static boolean printerFeatures(String request, String facts) {
        String deviceRequest = CARTRIDGE_ADD_ON.matcher(request).replaceFirst("");
        String evidence = SearchInput.normalize(facts);
        for (String token : deviceRequest.split("(?U)[^\\p{L}\\d-]+")) {
            if (token.matches("(?:покажи|нужен|нужны|купить|ищу|найти|хочу|мне|пожалуйста|устройств[а-я]*|и|или|принтер[а-я]*|мфу|pantum|пантум|[a-z][a-z-]*\\d{3,}[a-z\\d-]*)")) continue;
            boolean proved = switch (token) {
                case "лазерный" -> evidence.contains("лазер");
                case "а4", "a4" -> evidence.matches("(?sU).*\\b[aа]4\\b.*");
                case "монохромный", "черно-белый" -> evidence.contains("монохром") || evidence.contains("черно-бел") || evidence.contains("ч/б");
                case "" -> true;
                default -> false;
            };
            if (!proved) return false;
        }
        return true;
    }

    Row exactName(String name, List<String> keys, boolean needsOffers) throws SQLException {
        if (keys.isEmpty()) return null;
        var where = new Where();
        where.oneOf("e.entity_key", keys);
        // An empty category with a generic name must not shadow a known product intent.
        if (needsOffers || QueryPlan.INTENTS.containsKey(name)) where.add(POPULATED_CATEGORY);
        return rows("SELECT e.* FROM entity e WHERE " + where.sql()
                + " ORDER BY CASE kind WHEN 'collection' THEN 0 WHEN 'vendor' THEN 1"
                + " WHEN 'family' THEN 2 WHEN 'category' THEN 3 ELSE 4 END,id", where.args)
                .stream().filter(row -> !"offer".equals(row.str("kind"))
                        && name.equals(SearchInput.normalize(row.str("name")))).findFirst().orElse(null);
    }

    List<String> serverOfferIds() throws SQLException {
        // Even the Rack-server leaf in this feed contains DEPO Race workstations.
        return rows("SELECT offer_id FROM offer o WHERE " + SERVER_OFFER + " AND family_key IS NOT NULL ORDER BY family_key", List.of())
                .stream().map(row -> row.str("offer_id")).toList();
    }

    Set<String> aliasTerms(String category) throws SQLException {
        var terms = new HashSet<String>();
        for (var row : rows("SELECT DISTINCT term FROM alias WHERE scope IN ('*',?)", List.of("category/" + category)))
            terms.add(row.str("term"));
        return terms;
    }

    boolean mentionsVendor(String text) throws SQLException {
        for (var row : rows("SELECT e.name,v.vendor_id FROM vendor v JOIN entity e USING(entity_key)", List.of())) {
            if (text.contains(SearchInput.normalize(row.str("name"))) || text.contains(row.str("vendor_id"))) return true;
        }
        return false;
    }

    private static final java.util.regex.Pattern PURCHASE_SKU = java.util.regex.Pattern.compile(
            "(?U)^(?:(?:[а-я]+ ){0,3}(?:хочу|хотим|хочет|хотят|прошу|просит|планируем) )?"
                    + "(?:купить|заказать|приобрести) ([\\p{L}\\d][\\p{L}\\d-]*\\d[\\p{L}\\d-]*)$");
    private static final Pattern LABELED_SKU = Pattern.compile(
            "(?U)^(?:(?:нужна|нужен|ищу|покажи|найди) )?(?:(?:лицензи[яю]|товар) )?"
                    + "(?:(?:с|по) )?(?:артикул(?:ом|у)?|sku)\\s*[:№]?\\s+([\\p{L}\\d-]*\\d[\\p{L}\\d-]*)$");

    Row purchaseTarget(String terms) throws SQLException {
        var match = PURCHASE_SKU.matcher(terms);
        if (!match.matches()) match = LABELED_SKU.matcher(terms);
        if (!match.matches() || terms.matches("(?U).*(?:\\bне|\\bбез|\\bкроме|\\bвместо|\\bесли|\\bдля)\\b.*")) return null;
        var matches = exact(SearchIndex.normalizeSku(match.group(1)));
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    Row qualifiedSku(String terms) throws SQLException {
        var words = Arrays.asList(terms.split(" "));
        for (String word : words) {
            if (!word.matches("(?U)[\\p{L}\\d-]*\\d[\\p{L}\\d-]*")) continue;
            var matches = exact(SearchIndex.normalizeSku(word));
            if (matches.size() != 1) continue;
            Row match = matches.getFirst();
            var known = new HashSet<>(Arrays.asList(SearchInput.normalize(match.str("name")).split("(?U)[^\\p{L}\\d-]+")));
            known.removeAll(Set.of("для", "или", "без", "вместо"));
            known.add(word);
            if (known.containsAll(words)) return match;
        }
        return null;
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

    List<Map<String, Object>> suggest(String q, String category, String profile, List<String> matchingNames)
            throws SQLException {
        String preferredType = switch (Objects.toString(profile, "")) {
            case "hardware_buyer" -> "электроника";
            case "software_admin" -> "софт";
            default -> null;
        };
        // Lookup-only role signals: a mixed family/vendor can fit either role. If a category
        // was selected, the evidence for the preference must come from an offer in that category.
        String membership = category == null ? "1" : """
                (CASE e.kind
                   WHEN 'category' THEN e.id=?5
                   WHEN 'collection' THEN EXISTS (SELECT 1 FROM collection_scope c
                                           WHERE c.entity_key=e.entity_key AND c.category_id=?5)
                   WHEN 'family' THEN EXISTS (SELECT 1 FROM offer o WHERE o.family_key=e.entity_key AND o.category_id=?5)
                   WHEN 'vendor' THEN EXISTS (SELECT 1 FROM offer o WHERE o.vendor_id=e.id AND o.category_id=?5)
                   ELSE EXISTS (SELECT 1 FROM offer o WHERE o.offer_id=e.id AND o.category_id=?5)
                 END)
                """;
        String preferred = preferredType == null ? "0" : """
                (CASE e.kind
                   WHEN 'category' THEN EXISTS (SELECT 1 FROM offer o WHERE o.category_id=e.id AND o.product_type=?8)
                   WHEN 'collection' THEN EXISTS (SELECT 1 FROM collection_scope c JOIN offer o
                     ON o.vendor_id=c.vendor_id AND o.category_id=c.category_id
                     WHERE c.entity_key=e.entity_key AND o.product_type=?8)
                   WHEN 'offer' THEN EXISTS (SELECT 1 FROM offer o WHERE o.offer_id=e.id AND o.product_type=?8)
                   WHEN 'family' THEN EXISTS (SELECT 1 FROM offer o WHERE o.family_key=e.entity_key
                     AND o.product_type=?8 AND (?5 IS NULL OR o.category_id=?5))
                   WHEN 'vendor' THEN EXISTS (SELECT 1 FROM offer o WHERE o.vendor_id=e.id
                     AND o.product_type=?8 AND (?5 IS NULL OR o.category_id=?5))
                 END)
                """;
        String sql = """
                WITH matches(entity_key,priority) AS (
                  SELECT entity_key,1 FROM alias WHERE term>=?1 AND term<?2 AND scope IN ('*',?3)
                  UNION ALL SELECT entity_key,CASE WHEN sku_norm=?4 THEN 0 ELSE 2 END
                    FROM offer WHERE sku_norm>=?4 AND sku_norm<?6
                  UNION ALL SELECT value,3 FROM json_each(?7)
                ), candidates AS (SELECT entity_key,min(priority) AS priority FROM matches GROUP BY entity_key)
                SELECT e.kind,e.id,e.name,%s AS preferred FROM candidates c JOIN entity e USING(entity_key)
                WHERE %s AND %s
                ORDER BY c.priority,preferred DESC,
                  CASE WHEN c.priority=3 AND e.kind='category' THEN 0 ELSE 1 END,
                  CASE WHEN c.priority=3 THEN length(e.name) ELSE 0 END,e.name,e.id,e.kind LIMIT 6
                """.formatted(preferred, membership, POPULATED_CATEGORY);
        String end = Character.toString(Character.MAX_CODE_POINT);
        String sku = SearchIndex.normalizeSku(q);
        // JSON binds the entire Lucene result as a single parameter, including common prefixes.
        var args = new ArrayList<Object>(Arrays.asList(q, q + end, "category/" + category,
                sku, category, sku + end, JSON.writeValueAsString(matchingNames)));
        if (preferredType != null) args.add(preferredType);
        return rows(sql, args).stream()
                .map(r -> Map.<String, Object>of("kind", r.str("kind"), "target_id", r.str("id"), "label", r.str("name")))
                .toList();
    }

    static final class Where {
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
            if (filters.containsKey(k)) w.add("o." + k + "=?",
                    k.equals("version") ? SearchInput.normalize(filters.get(k).toString()) : filters.get(k));
        if (Boolean.TRUE.equals(filters.get("available_only"))) w.add("o.available=1");
        if (filters.containsKey("platform")) {
            String platform = SearchInput.normalize(filters.get("platform").toString());
            // A generic platform also covers a versioned platform, such as "linux glibc 2.28".
            String versioned = Set.of("linux", "windows", "macos").contains(platform) ? platform + " *" : null;
            w.add("""
                    (o.offer_id IN (SELECT p.offer_id FROM offer_param p
                            WHERE p.field_key='platform' AND (p.value_norm=? OR p.value_norm GLOB ?))
                        OR EXISTS(SELECT 1 FROM json_each(o.fact_sources_json,'$.platform.values') p
                                  WHERE p.value=? OR p.value GLOB ?)
                        OR (instr(lower(o.name),?)>0 AND title_supports_platform(o.name,?)))
                    """, platform, versioned, platform, versioned, platform, platform);
        }
        for (String dim : List.of("seat", "device")) {
            Object count = filters.get(dim + "_count");
            if (count != null)
                w.add("""
                        (o.%1$s_count>=? OR o.%1$s_max=? OR (o.price_unit='%1$s' AND o.%1$s_count>0
                                AND o.%1$s_min IS NULL AND o.%1$s_max IS NULL)
                         OR ((o.%1$s_min<=? OR (o.%1$s_min IS NULL
                                    AND json_extract(o.fact_sources_json,'$.%1$s_min.open')=1))
                             AND (o.%1$s_max>=? OR (o.%1$s_max IS NULL
                                    AND json_extract(o.fact_sources_json,'$.%1$s_max.open')=1))))
                        """.formatted(dim), count, count, count, count);
        }
        if (filters.containsKey("max_price_minor")) {
            w.add(
                    """
                    o.currency=? AND o.price_minor>0 AND (CASE o.price_unit
                        WHEN 'package' THEN coalesce(o.min_order_qty, CASE WHEN
                            json_extract(o.fact_sources_json,'$.price_unit.basis')='physical_unit'
                            OR json_type(o.fact_sources_json,'$.price_unit.param_ordinal')='integer' THEN 1 END)
                        WHEN 'seat' THEN max(o.min_order_qty,1 + (? - 1) / o.seat_count)
                        WHEN 'device' THEN max(o.min_order_qty,1 + (? - 1) / o.device_count)
                    END)<=? / o.price_minor
                    """,
                    filters.getOrDefault("currency", "RUB"),
                    filters.get("seat_count"),
                    filters.get("device_count"),
                    filters.get("max_price_minor"));
        }
        return w;
    }

    record Selection(Row target, List<String> offerIds, boolean support, boolean fileRecovery,
                     boolean deletedDesktopFiles, boolean includeServerSoftware, QueryPlan program, Requirements constraints) {
        Selection(Row target, List<String> offerIds, boolean support, boolean fileRecovery) {
            this(target, offerIds, support, fileRecovery, false, false, null, new Requirements());
        }
    }

    // All SQL fragments come from this enum. The model supplies neither SQL nor product IDs.
    private static String predicate(QueryPlan.Purpose purpose) {
        return switch (purpose) {
            case WORDS -> "1";
            case BACKUP -> inCategory("id='1033' OR name LIKE '%езервное копирование%'");
            // Softline also lists these three MTS Link webinar plans in the general «Связь» section.
            case WEBINAR -> "(" + inCategory("name LIKE '%ебинар%' OR name LIKE '%идеоконференц%'")
                    + " OR (o.vendor_id='webinar' AND o.family_key IN"
                    + " ('family/414326','family/447242','family/447243')))";
            case REMOTE_ACCESS -> inCategory("id='327030' OR name LIKE '%даленный доступ%'"
                    + " OR name LIKE '%далённый доступ%' OR name LIKE '%даленное администрирование%'");
            case ANTIVIRUS -> inCategory("name LIKE '%нтивирус%'");
            case PRINTER -> inCategory("id='93995' OR name LIKE '%ринтер%' OR name LIKE '%МФУ%'");
            case SERVER -> SERVER_OFFER;
            case SERVER_SOFTWARE -> "(o.category_id='369' OR (o.group_id IS NOT NULL AND (o.name LIKE '%ервер%' OR lower(o.name) LIKE '%server%')))";
            case UNKNOWN -> "0";
        };
    }

    private static String inCategory(String condition) {
        return "o.category_id IN (SELECT id FROM entity WHERE kind='category' AND (" + condition + "))";
    }

    Map<String,String> namedVersions(String family, ProductTitle product) throws SQLException {
        var result = new LinkedHashMap<String,String>();
        for (var sku : rows("SELECT offer_id,name,version,edition FROM offer WHERE family_key=?", List.of(family))) {
            String version = product.release(sku.str("name"), sku.str("version"), sku.str("edition"));
            if (version != null) result.put(sku.str("offer_id"), version);
        }
        return result;
    }

    private Where candidateWhere(Map<String, Object> filters, Selection selection, List<String> families) {
        var constraints = selection.constraints();
        Where w;
        if (constraints.namedVersions.isEmpty()) {
            w = where(filters);
        } else {
            var remaining = new LinkedHashMap<>(filters);
            boolean exactVersion = remaining.remove("version") != null;
            String requested = constraints.values.get("version").toString();
            w = where(remaining);
            w.oneOf("o.offer_id", constraints.namedVersions.entrySet().stream()
                    .filter(e -> ProductTitle.sameRelease(requested, e.getValue()) == exactVersion)
                    .map(Map.Entry::getKey).toList());
        }
        if (constraints.edition != null) w.add("has_edition(o.name,o.edition,?)", constraints.edition);
        if (constraints.editionYear != null) w.add("(o.version=? OR has_edition(o.name,NULL,?))",
                constraints.editionYear, constraints.editionYear);
        Row target = selection.target();
        if (selection.constraints().task != null) selection.constraints().task.constrain(w);
        if (selection.constraints().boxed)
            w.add("(o.name LIKE '%оробочн%' OR (' ' || lower(o.name) || ' ') LIKE '% box %')");
        if (selection.constraints().pdfEditing) w.add("pdf_editor(o.name)");
        if (selection.constraints().audience != null) {
            w.add(predicate(QueryPlan.Purpose.WEBINAR));
            w.add("max(audience_capacity(o.name), audience_capacity(o.description))>=?", selection.constraints().audience);
        }
        for (String excluded : selection.constraints().excludedVersions) w.add("o.version<>?", excluded);
        for (String excluded : selection.constraints().excludedLicenses) w.add("o.license_type<>?", excluded);
        if (selection.constraints().requireServerLicense) w.add(SERVER_LICENSE_TITLE);
        if (selection.constraints().excludeServerLicense) w.add("NOT " + SERVER_LICENSE_TITLE);
        if (selection.program() != null) {
            var plan = selection.program();
            if (plan.purpose() == QueryPlan.Purpose.BACKUP && QueryPlan.requestsVmBackup(plan.text())
                    && plan.additionalPurposes().isEmpty()) {
                // The category also sells storage capacity and licenses for physical hosts.
                // Check both function and protected workload on the individual offer.
                w.add("(lower(o.name) LIKE '%backup%' OR o.name LIKE '%экап%' OR o.name LIKE '%екап%'"
                        + " OR o.name LIKE '%езервн% копи%')");
                w.add("(lower(' ' || o.name || ' ') LIKE '% vm %' OR o.name LIKE '%иртуальн% машин%'"
                        + " OR o.name LIKE '%латформ% виртуализации%' OR o.name LIKE '%ирт/физ машин%')");
                w.add("o.name NOT LIKE '%исковое пространство%' AND o.name NOT LIKE '%есто для хранения%'");
                if (!filters.containsKey("license_type"))
                    w.add("(o.license_type IS NULL OR o.license_type='new')");
            }
            var predicates = new ArrayList<String>();
            predicates.add(predicate(plan.purpose()));
            for (var op : plan.additionalPurposes()) predicates.add(predicate(op));
            w.add("(" + String.join(plan.join().equals("any") ? " OR " : " AND ", predicates) + ")");
        }
        if (selection.fileRecovery()) {
            // Rank relevant families first; a category match also supplies candidates when a
            // paraphrase has no useful lexical hits. Explicit filters stay on this same SKU.
            // Snapshot 2026-11-25 classifies Recoverit as a file manager. Verify vendor AND
            // family rather than admitting every file manager. https://recoverit.wondershare.com/
            String recoverit = "(o.category_id='14574' AND o.vendor_id='wondershare' AND o.family_key='family/301714')";
            w.add("(o.category_id IN ('5922','1033') OR " + recoverit + ")");
            if (selection.deletedDesktopFiles()) {
                w.add("(" + recoverit + """
                         OR lower(o.name) LIKE '%undelete%' OR lower(o.name) LIKE '%file recovery%'
                         OR lower(o.name) LIKE '%photo recovery%' OR o.name LIKE '%осстанов%удал%файл%'
                         OR o.name LIKE '%осстанов%удал%фото%')
                         AND lower(o.name) NOT LIKE '%android%' AND lower(o.name) NOT LIKE '%iphone%'
                         AND lower(o.name) NOT LIKE '%ios %'
                        """);
                w.add("NOT " + SUPPORT_TITLE);
            }
            // An engine backup requires the matching database, not just a disk with lost files.
            w.add(
                    "lower(o.name) NOT LIKE '%sql%' AND lower(o.name) NOT LIKE '%oracle%'"
                            + " AND lower(o.name) NOT LIKE '%exchange%'");
        } else if (families != null) w.oneOf("o.family_key", families);
        if (selection.offerIds() != null) {
            if (selection.includeServerSoftware()) {
                // Category 369 is the catalog's server software section. Keep the verified
                // hardware candidates eligible for explicit filters and sparse snapshots.
                var hardware = new Where();
                hardware.oneOf("o.offer_id", selection.offerIds());
                w.add("(o.category_id='369' OR " + hardware.sql() + ")", hardware.args.toArray());
            } else {
                w.oneOf("o.offer_id", selection.offerIds());
            }
        }
        if (target != null && "family".equals(target.str("kind")))
            w.add("o.family_key=?", target.str("entity_key"));
        if (target != null && "offer".equals(target.str("kind")))
            w.add("o.offer_id=?", target.str("id"));
        w.add("o.family_key IS NOT NULL");
        if (selection.support() && selection.offerIds() == null && (target == null || !"offer".equals(target.str("kind"))))
            w.add(SUPPORT_TITLE);
        return w;
    }

    /** Count families as they would appear after selecting each individual facet value. */
    List<Row> facet(String field, Map<String, Object> filters, Selection selection, List<String> families)
            throws SQLException {
        String kind = switch (field) {
            case "vendor_id" -> "vendor";
            case "category_id" -> "category";
            default -> throw new IllegalArgumentException("Unknown facet " + field);
        };
        Where w = candidateWhere(filters, selection, families);
        String sql = """
                WITH eligible AS (
                    SELECT o.%s AS id,o.family_key,min(%s) AS tier FROM offer o
                    WHERE %s GROUP BY o.%s,o.family_key
                ), ranked AS (
                    SELECT id,tier,min(tier) OVER (PARTITION BY id) AS best FROM eligible
                )
                SELECT r.id,e.name AS label,count(*) AS count FROM ranked r
                JOIN entity e ON e.kind=? AND e.id=r.id WHERE r.tier=r.best
                GROUP BY r.id,e.name ORDER BY count DESC,e.name,r.id
                """.formatted(field, TIER, w.sql(), field);
        w.args.add(kind);
        return rows(sql, w.args);
    }

    /** Distinct families from the highest nonempty price/availability tier. */
    List<Row> representatives(Map<String, Object> filters, Selection selection, List<String> families)
            throws SQLException {
        Where w = candidateWhere(filters, selection, families);
        String inventoryOrder = selection.constraints().task instanceof InventoryIntent
                ? ",inventory_capacity(o.name),o.price_minor" : "";
        // Select one SKU per family in SQL before reading the result across JDBC.
        var hits =
                rows(
                        "SELECT * FROM (SELECT "
                                + COLUMNS
                                + ",row_number() OVER (PARTITION BY o.family_key ORDER BY "
                                + TIER
                                + ","
                                + PURCHASE_ORDER
                                + inventoryOrder
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
            if (selection.fileRecovery()) {
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

    /** Cross-sell runs independently from retrieval, including when there are no direct results. */
    List<Map<String, Object>> upsellSuggestions(List<Row> anchors, Map<String, Object> upgradeFilters,
            Map<String, Object> shoppingFilters,
            Set<String> excludedFamilies, String profile, int capacity, Row contextHint) throws SQLException {
        if (capacity <= 0) return List.of();
        var seeds = new ArrayList<Row>();
        for (var anchor : anchors) {
            var facts = rows("SELECT o.offer_id,o.family_key,o.product_type,o.seat_count,o.device_count,o.term_months,"
                    + " c.name AS category FROM offer o JOIN entity c ON c.entity_key='category/'||o.category_id"
                    + " WHERE o.offer_id=?", List.of(anchor.str("offer_id")));
            seeds.addAll(facts);
        }
        var excluded = new LinkedHashSet<>(excludedFamilies);
        seeds.forEach(a -> excluded.add(a.str("family_key")));
        // A priced, available product always gets first consideration. If a constrained catalog
        // contains only reserve items, retain their actual availability and price on the card.
        for (boolean primary : List.of(true, false)) {
            var cards = new ArrayList<Map<String, Object>>();
            var pairedCategories = new LinkedHashSet<String>();
            for (var seed : seeds) {
                var upgrade = licenseUpgrade(seed, upgradeFilters, primary);
                if (upgrade != null) {
                    cards.add(upgrade);
                    if (cards.size() == capacity) return cards;
                }
                for (var category : Merchandising.pairedCategories(seed.str("category"))) {
                    if (!pairedCategories.add(category)) continue;
                    var candidate = suggestedOffer(shoppingFilters, excluded, primary, category, null);
                    if (candidate == null) continue;
                    cards.add(related(candidate, "paired_category", seed,
                            "К разделу «" + seed.str("category") + "» подобран товар из смежного раздела «"
                                    + candidate.str("category") + "»."));
                    excluded.add(candidate.str("family_key"));
                    break;
                }
                if (cards.size() == capacity) return cards;
            }
            if (!cards.isEmpty()) return cards;

            // With no stronger relation, display one labelled catalog suggestion. This also
            // covers imports whose taxonomy differs from Softline's and searches with no hits.
            var context = seeds.isEmpty() ? contextHint : seeds.getFirst();
            String type = Merchandising.preferredType(context == null ? null : context.str("product_type"), profile);
            Row pick = null;
            if (seeds.isEmpty() && context != null && context.str("category") != null) {
                for (String category : Merchandising.pairedCategories(context.str("category"))) {
                    pick = suggestedOffer(shoppingFilters, excluded, primary, category, null);
                    if (pick != null) break;
                }
            }
            for (String category : Merchandising.catalogSections(type)) {
                if (pick != null) break;
                pick = suggestedOffer(shoppingFilters, excluded, primary, category, type);
            }
            if (pick == null && type != null) pick = suggestedOffer(shoppingFilters, excluded, primary, null, type);
            // The requested budget may exclude every other product of this type. Prefer an
            // ordinary standalone catalog item over the first component in opaque offer-ID order.
            if (pick == null && type != null) {
                for (String category : Merchandising.catalogSections(null)) {
                    pick = suggestedOffer(shoppingFilters, excluded, primary, category, null);
                    if (pick != null) break;
                }
            }
            if (pick == null) pick = suggestedOffer(shoppingFilters, excluded, primary, null, null);
            if (pick != null) return List.of(related(pick, "catalog_pick", null,
                    "Отдельное предложение каталога из раздела «" + pick.str("category") + "»."));
        }
        return List.of();
    }

    private Where suggestionWhere(Map<String, Object> filters, boolean primary) {
        Where w = where(filters);
        w.add("o.family_key IS NOT NULL");
        w.add(primary ? "o.available=1 AND o.price_minor>0" : "(" + TIER + ")>0");
        if (filters.containsKey("currency")) w.add("o.currency=?", filters.get("currency"));
        return w;
    }

    private static final String SUGGESTION_FROM = " FROM offer o"
            + " JOIN entity v ON v.entity_key='vendor/'||o.vendor_id"
            + " JOIN entity c ON c.entity_key='category/'||o.category_id";

    private Row suggestedOffer(Map<String, Object> filters, Set<String> excluded, boolean primary, String category,
            String productType)
            throws SQLException {
        Where w = suggestionWhere(filters, primary);
        if (!excluded.isEmpty()) w.add("o.family_key NOT IN (SELECT value FROM json_each(?))",
                JSON.writeValueAsString(excluded));
        if (category != null) w.add("o.category_id IN (SELECT id FROM entity WHERE kind='category' AND name=?)", category);
        if (productType != null) w.add("o.product_type=?", productType);
        String order = primary ? "" : "tier,";
        // The fallback can walk the offer_id index and stop at the first eligible item. Limit
        // purchase/price sorting to the smaller indexed category set.
        if (category != null) order += PURCHASE_ORDER + "," + Merchandising.preferredTitleOrder(category) + "o.price_minor,";
        return rows("SELECT " + COLUMNS + ",c.name AS category" + SUGGESTION_FROM
                + " WHERE " + w.sql() + " ORDER BY " + order + "o.offer_id LIMIT 1", w.args)
                .stream().findFirst().orElse(null);
    }

    private Map<String, Object> licenseUpgrade(Row seed, Map<String, Object> filters, boolean primary)
            throws SQLException {
        if (seed.get("seat_count") == null && seed.get("device_count") == null && seed.get("term_months") == null)
            return null;
        Where w = suggestionWhere(filters, primary);
        w.add("a.offer_id=? AND o.family_key=a.family_key AND o.offer_id<>a.offer_id", seed.str("offer_id"));
        w.add("""
                o.category_id=a.category_id AND o.vendor_id=a.vendor_id AND o.currency=a.currency
                AND o.product_type IS a.product_type AND coalesce(o.product_type,'')<>'электроника'
                AND o.version IS a.version AND o.edition IS a.edition AND o.license_type IS a.license_type
                AND o.price_unit IS a.price_unit AND o.min_order_qty IS a.min_order_qty
                AND o.seat_min IS a.seat_min AND o.seat_max IS a.seat_max
                AND o.device_min IS a.device_min AND o.device_max IS a.device_max
                AND (o.seat_count IS a.seat_count OR o.seat_count>a.seat_count)
                AND (o.device_count IS a.device_count OR o.device_count>a.device_count)
                AND (o.term_months IS a.term_months OR o.term_months>a.term_months)
                AND (o.seat_count>a.seat_count OR o.device_count>a.device_count OR o.term_months>a.term_months)
                AND json_extract(o.fact_sources_json,'$.platform.values') IS json_extract(a.fact_sources_json,'$.platform.values')
                AND NOT EXISTS(SELECT field_key,value_norm FROM offer_param WHERE offer_id=a.offer_id AND field_key IN ('platform','language')
                               EXCEPT SELECT field_key,value_norm FROM offer_param WHERE offer_id=o.offer_id AND field_key IN ('platform','language'))
                """);
        var found = rows("SELECT " + COLUMNS + ",o.seat_count,o.device_count,o.term_months" + SUGGESTION_FROM
                + " JOIN offer a ON o.family_key=a.family_key WHERE " + w.sql()
                + " ORDER BY tier,o.price_minor,o.offer_id LIMIT 1", w.args);
        if (found.isEmpty()) return null;
        var candidate = found.getFirst();
        var gains = new ArrayList<String>();
        for (String field : List.of("seat_count", "device_count", "term_months")) {
            if (seed.get(field) == null || candidate.number(field) <= seed.number(field)) continue;
            String unit = switch (field) {
                case "seat_count" -> "число пользователей";
                case "device_count" -> "число устройств";
                default -> "срок в месяцах";
            };
            gains.add(unit + " — " + candidate.number(field) + " вместо " + seed.number(field));
        }
        return related(candidate, "upgrade", seed, "У варианта той же линейки " + String.join(", ", gains) + ".");
    }

    private static Map<String, Object> related(Row candidate, String kind, Row anchor, String reason) {
        var card = candidate.card("related", kind.toUpperCase(Locale.ROOT));
        card.put("recommendation_kind", kind);
        card.put("recommendation_reason", reason);
        if (anchor != null) card.put("anchor_offer_id", anchor.str("offer_id"));
        return card;
    }

    List<Map<String, Object>> detailedOffers(List<Row> rows) throws SQLException {
        var offers = rows.stream().map(Row::offer).toList();
        addAttributes(offers);
        return offers;
    }

    void addAttributes(List<Map<String, Object>> offers) throws SQLException {
        Map<String, Map<String, List<String>>> byId = new HashMap<>();
        for (int i = 0; i < offers.size(); i += 300) {
            var ids =
                    offers.subList(i, Math.min(i + 300, offers.size())).stream()
                            .map(r -> r.get("offer_id"))
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
        for (var offer : offers) {
            var attributes = byId.get(offer.get("offer_id"));
            if (attributes != null) offer.put("attributes", attributes);
        }
    }

    Map<String, String> detailOffers(List<Map<String, Object>> shown, Map<String, Object> filters)
            throws SQLException {
        if (shown.isEmpty()) return Map.of();
        var keys = shown.stream().map(card -> (String) card.get("family_key")).distinct().toList();
        var where = where(filters);
        where.oneOf("o.family_key", keys);
        var detail = new LinkedHashMap<String, String>();
        for (var row : rows("SELECT o.offer_id,o.family_key FROM offer o WHERE " + where.sql()
                + " ORDER BY o.offer_id", where.args)) {
            detail.put(row.str("offer_id"), row.str("family_key"));
        }
        return detail;
    }

    List<Map<String, Object>> complements(
            List<Row> anchors, Map<String, Object> filters, int capacity,
            List<Map<String, Object>> evaluations) throws SQLException {
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
                    List<Map<String, Object>> checks = check(anchor, candidate, relation, evaluations);
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
        int best = cards.stream().mapToInt(card -> (int) card.get("_tier")).min().orElse(-1);
        for (var card : cards) {
            if (out.size() == capacity) break;
            if ((int) card.get("_tier") == best && used.add((String) card.get("family_key"))) {
                card.remove("_tier");
                out.add(card);
            }
        }
        return out;
    }

    /** When no direct offer was shown the known category can still guide a separate catalog pick. */
    Row upsellContext(Row target, List<Map<String,Object>> alternatives, List<String> rankedFamilies,
                      String browsingCategory) throws SQLException {
        String kind = target == null ? "" : target.str("kind");
        String field, value;
        if (kind.equals("offer") || !alternatives.isEmpty()) {
            field = "offer_id";
            value = kind.equals("offer") ? target.str("id") : (String) alternatives.getFirst().get("offer_id");
        } else if (kind.equals("family") || (rankedFamilies != null && rankedFamilies.size() == 1)) {
            field = "family_key";
            value = kind.equals("family") ? target.str("entity_key") : rankedFamilies.getFirst();
        } else {
            field = "category_id";
            value = kind.equals("category") ? target.str("id") : browsingCategory;
        }
        if (value == null) return null;
        return rows("SELECT c.name AS category,o.product_type FROM offer o JOIN entity c"
                + " ON c.entity_key='category/'||o.category_id WHERE o." + field + "=? LIMIT 1", List.of(value))
                .stream().findFirst().orElse(null);
    }

    /** Feed evidence is evaluated on the individual offers, including on older snapshots. */
    List<Map<String,Object>> catalogConsumables(List<Row> anchors, Map<String,Object> filters,
            Set<String> shownFamilies, int capacity) throws SQLException {
        var perDevice = new ArrayList<List<Map<String,Object>>>();
        for (Row anchor : anchors) {
            Row category = entity("category", anchor.str("category_id"));
            String model = anchor.str("sku");
            if (category == null || model == null || !CatalogFit.modelCode(model)) continue;
            String categoryName = category.str("name");
            Where w = where(filters);
            w.add("o.vendor_id=? AND o.sku_norm IS NOT NULL AND o.family_key IS NOT NULL", anchor.str("vendor_id"));
            boolean printer = Set.of("Принтеры и МФУ", "Принтеры", "МФУ").contains(categoryName);
            String evidence = "o.sku_raw";
            if (printer) {
                w.add("o.category_id IN (SELECT id FROM entity WHERE kind='category' AND name='Расходные материалы')");
                w.add("instr(lower(o.name),lower(?))>0", model);
            } else if (categoryName.equals("ИБП (Источники бесперебойного питания)")) {
                w.add("o.category_id IN (SELECT id FROM entity WHERE kind='category' AND name='Сменные батареи для ИБП')");
                // Compare the declared part number with the battery SKU, allowing its own vendor prefix.
                evidence = "(SELECT p.value FROM offer_param p WHERE p.offer_id=? AND p.name='Совместимая батарея'"
                        + " AND (o.sku_norm=lower(trim(p.value)) OR o.sku_norm=lower(v.name||trim(p.value))) LIMIT 1)";
                w.add("o.sku_norm IN (SELECT lower(trim(value)) FROM offer_param WHERE offer_id=? AND name='Совместимая батарея'"
                        + " UNION ALL SELECT lower(?||trim(value)) FROM offer_param WHERE offer_id=? AND name='Совместимая батарея')",
                        anchor.str("offer_id"), anchor.str("vendor"), anchor.str("offer_id"));
            } else continue;
            var args = new ArrayList<Object>();
            if (!printer) args.add(anchor.str("offer_id"));
            args.addAll(w.args);
            var candidates = rows("SELECT " + COLUMNS + "," + evidence + " AS part FROM offer o JOIN entity v"
                    + " ON v.entity_key='vendor/'||o.vendor_id WHERE " + w.sql() + " ORDER BY tier,o.price_minor,o.offer_id", args);
            var options = new ArrayList<Map<String,Object>>();
            var families = new HashSet<>(shownFamilies);
            for (Row item : candidates) {
                if (printer ? !CatalogFit.cartridgeFor(item.str("name"), model) : !CatalogFit.modelCode(item.str("part"))) continue;
                if (!families.add(item.str("family_key"))) continue;
                var card = item.card("complement", "COMPATIBLE_CONSUMABLE");
                card.put("anchor_offer_id", anchor.str("offer_id"));
                card.put("checks", List.of(Map.of("field", "catalog_compatibility", "result", "pass",
                        "anchor_value", printer ? model : item.str("part"), "candidate_value", item.str("sku"),
                        "source_url", printer ? item.str("url") : anchor.str("url"))));
                options.add(card);
                if (options.size() == capacity) break;
            }
            perDevice.add(options);
        }
        // Offer a consumable for each device before offering several colours for the first printer.
        var result = new ArrayList<Map<String,Object>>();
        for (int i = 0; i < capacity; i++)
            for (var options : perDevice) {
                if (options.size() <= i) continue;
                var card = options.get(i);
                if (shownFamilies.add((String) card.get("family_key"))) result.add(card);
                if (result.size() == capacity) return result;
            }
        return result;
    }

    private List<Map<String, Object>> check(Row anchor, Row candidate, Row rule,
                                           List<Map<String, Object>> evaluations) {
        List<Map<String, Object>> checks = new ArrayList<>();
        JsonNode predicates = JSON.readTree(rule.str("predicates_json"));
        if (!predicates.isArray()) return List.of();
        for (JsonNode pred : predicates) {
            String field = pred.path("field").asString("");
            String a = null, b = null;
            String result = "unknown";
            if ("supported_device".equals(field)) {
                String vendor = pred.path("vendor_id").asString("");
                a = anchor.str("sku");
                b = candidate.str("sku");
                if (a != null && b != null && !vendor.isBlank()) {
                    result = vendor.equals(anchor.str("vendor_id")) && vendor.equals(candidate.str("vendor_id"))
                            && SearchIndex.normalizeSku(a).equals(SearchIndex.normalizeSku(pred.path("anchor_sku").asString("")))
                            && SearchIndex.normalizeSku(b).equals(SearchIndex.normalizeSku(pred.path("candidate_sku").asString("")))
                            ? "pass" : "fail";
                }
            } else if ("compatible_versions".equals(field)) {
                String ak = pred.path("anchor_field").asString(""),
                        bk = pred.path("candidate_field").asString("");
                Set<String> fields = Set.of("version", "edition", "license_type");
                if (fields.contains(ak) && fields.contains(bk)) {
                    a = anchor.str(ak);
                    b = candidate.str(bk);
                    if (a != null && b != null) {
                        result = a.equalsIgnoreCase(pred.path("anchor_equals").asString(""))
                                && b.equalsIgnoreCase(pred.path("candidate_equals").asString("")) ? "pass" : "fail";
                    }
                }
            }
            var evaluation = new LinkedHashMap<String, Object>();
            evaluation.put("field", field);
            evaluation.put("anchor_offer_id", anchor.str("offer_id"));
            evaluation.put("candidate_offer_id", candidate.str("offer_id"));
            evaluation.put("rule_id", rule.str("rule_id"));
            evaluation.put("rule_version", rule.str("rule_version"));
            evaluation.put("source_url", rule.str("source_url"));
            evaluation.put("anchor_value", a);
            evaluation.put("candidate_value", b);
            evaluation.put("result", result);
            evaluations.add(evaluation);
            if (!"pass".equals(result)) return List.of();
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
        Where w = where(filters);
        w.add("o.vendor_id='pantum' AND " + PRINTER_CATEGORY + " AND o.base_model IS NOT NULL");
        var variants =
                rows(
                        "SELECT "
                                + COLUMNS
                                + "," + PRINT_EVIDENCE + " AS evidence FROM offer o JOIN entity v ON"
                                + " v.entity_key='vendor/'||o.vendor_id WHERE "
                                + w.sql()
                                + " ORDER BY tier,o.offer_id",
                        w.args);
        Map<String, Row> chosen = new LinkedHashMap<>();
        int best = Integer.MAX_VALUE;
        for (var row : variants) {
            if (row.str("base_model").equalsIgnoreCase(requested) || !printerFeatures(q, row.str("evidence"))) continue;
            if (row.number("tier") > best) break;
            best = row.number("tier");
            chosen.putIfAbsent(row.str("family_key"), row);
            if (chosen.size() >= 4) break;
        }
        return new ArrayList<>(chosen.values());
    }
}
