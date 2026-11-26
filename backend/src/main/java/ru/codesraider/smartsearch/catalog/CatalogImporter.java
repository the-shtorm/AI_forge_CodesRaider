package ru.codesraider.smartsearch.catalog;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;

import ru.codesraider.smartsearch.storage.Sqlite;
import tools.jackson.databind.json.JsonMapper;

public final class CatalogImporter {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String RULE_VERSION = "1";
    private static final Pattern TERM = Pattern.compile("(\\d+)\\s*мес\\.?");
    private static final Pattern MIN_ORDER = Pattern.compile("от ([1-9]\\d{0,8})(?: до [1-9]\\d{0,8})? шт\\.?");
    private static final Pattern DEVICE_PACKAGE = Pattern.compile("\\bлицензия на ([1-9]\\d{0,8}) пк\\b", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern MODEL = Pattern.compile("[a-z]+[a-z0-9]*\\d[a-z0-9-]*");
    private static final Set<String> EMPTY_PARAMS = Set.of("", "другое или отсутствует", "не определено");
    private static final Map<String, String> FIELDS = Map.ofEntries(
            Map.entry("тип продукта", "product_type"), Map.entry("версия", "version"),
            Map.entry("редакция", "edition"), Map.entry("тип лицензии", "license_type"),
            Map.entry("срок действия", "term_months"), Map.entry("к-во узлов", "device_count"),
            Map.entry("к-во пользователей до", "seat_max"), Map.entry("операционная система", "platform"),
            Map.entry("совместимая архитектура/платформа", "platform"), Map.entry("платформа", "platform"),
            Map.entry("язык интерфейса", "language"), Map.entry("модель принтера", "base_model"));

    private CatalogImporter() {}

    public static void run(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: import (set CATALOG_PATH)");
        String source = System.getenv("CATALOG_PATH");
        if (source == null || source.isBlank()) throw new IllegalArgumentException("CATALOG_PATH is required");
        String root = System.getenv().getOrDefault("SEARCH_SNAPSHOTS_DIR", "data/snapshots");
        String version = build(Path.of(source), Path.of(root));
        System.out.println("Activated catalog snapshot " + version);
    }

    public static String build(Path source, Path root) throws Exception {
        Files.createDirectories(root);
        root = root.toRealPath();
        Path staging = Files.createTempDirectory(root, ".import-");
        try {
            Path db = staging.resolve("catalog.db");
            int offers;
            String digest;
            String date;
            try (var connection = Sqlite.dataSource(db, false).getConnection();
                 var raw = new BufferedInputStream(Files.newInputStream(source))) {
                var hash = MessageDigest.getInstance("SHA-256");
                var hashed = new BufferedInputStream(new DigestInputStream(raw, hash));
                hashed.mark(2);
                boolean gzip = hashed.read() == 0x1f && hashed.read() == 0x8b;
                hashed.reset();
                InputStream decoded = gzip ? new GZIPInputStream(hashed) : hashed;
                Sqlite.createV1(connection, "catalog");
                connection.setAutoCommit(false);
                try (var writer = new CatalogWriter(connection)) {
                    date = readFeed(decoded, writer);
                    offers = writer.offerCount;
                    if (offers == 0) throw new IllegalArgumentException("Feed contains no offers");
                    writer.installRules();
                }
                // Include bytes after the XML end, and leave the digest stream open until it has been drained.
                hashed.transferTo(java.io.OutputStream.nullOutputStream());
                if (gzip) decoded.close();
                digest = HexFormat.of().formatHex(hash.digest());
                connection.commit();
                if (!Sqlite.checkCatalog(connection)) throw new SQLException("Catalog integrity check failed");
            }
            String version = "v1-r" + RULE_VERSION + "-" + digest;
            var counts = CatalogIndex.build(db, staging.resolve("lucene"), version);
            if (counts.offers() != offers) throw new IllegalStateException("SQLite/Lucene offer counts differ");
            var manifest = new LinkedHashMap<String, Object>();
            manifest.put("index_version", version);
            manifest.put("imported_at", Instant.now().toString());
            manifest.put("source_date", date);
            manifest.put("source_sha256", digest);
            manifest.put("schema_version", 1);
            manifest.put("rules_version", RULE_VERSION);
            manifest.put("offer_count", offers);
            manifest.put("family_count", counts.families());
            manifest.put("vector_count", 0);
            manifest.put("profile_id", null);
            Files.writeString(staging.resolve("manifest.json"), JSON.writeValueAsString(manifest) + "\n");

            Path destination = root.resolve(version);
            if (!Files.exists(destination)) {
                try {
                    Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.io.IOException concurrentImport) {
                    if (!Files.isDirectory(destination)) throw concurrentImport;
                }
            }
            try (var verified = CatalogSnapshot.open(root, version)) {
                if (verified == null) throw new IllegalStateException("Snapshot failed validation: " + version);
            }
            Path pointer = Files.createTempFile(root, ".active-", ".tmp");
            try {
                Files.writeString(pointer, version + "\n");
                Files.move(pointer, root.resolve("active"), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(pointer);
            }
            return version;
        } finally {
            if (Files.isDirectory(staging, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    && root.equals(staging.toRealPath().getParent())) {
                List<Path> paths;
                try (var files = Files.walk(staging)) {
                    paths = files.sorted(Comparator.reverseOrder()).toList();
                }
                for (Path path : paths) Files.deleteIfExists(path);
            }
        }
    }

    private static String readFeed(InputStream input, CatalogWriter writer) throws Exception {
        var factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        var xml = factory.createXMLStreamReader(new java.io.FilterInputStream(input) {
            @Override public void close() {} // StAX may close its input at END_DOCUMENT.
        });
        String date = null;
        try {
            while (xml.hasNext()) {
                if (xml.next() != XMLStreamConstants.START_ELEMENT) continue;
                switch (xml.getLocalName()) {
                    case "yml_catalog" -> date = attr(xml, "date");
                    case "category" -> {
                        String id = attr(xml, "id");
                        String parent = attr(xml, "parentId");
                        writer.category(id, parent, xml.getElementText().strip());
                    }
                    case "offer" -> writer.offer(readOffer(xml));
                    default -> { }
                }
            }
        } finally {
            xml.close();
        }
        return date;
    }

    private static RawOffer readOffer(XMLStreamReader xml) throws Exception {
        String id = attr(xml, "id");
        String group = attr(xml, "group_id");
        String available = attr(xml, "available");
        if (!Set.of("true", "false").contains(available == null ? "" : available)) {
            throw new IllegalArgumentException("Invalid availability for offer " + id);
        }
        var fields = new HashMap<String, String>();
        var params = new ArrayList<Param>();
        while (xml.hasNext()) {
            int event = xml.next();
            if (event == XMLStreamConstants.END_ELEMENT) break;
            if (event != XMLStreamConstants.START_ELEMENT) continue;
            String key = xml.getLocalName();
            String label = attr(xml, "name");
            String value = xml.getElementText();
            if (key.equals("param")) {
                String field = FIELDS.get(CatalogText.normalize(label));
                String norm = CatalogText.normalize(value);
                params.add(new Param(label == null ? "" : label, value, field, EMPTY_PARAMS.contains(norm) ? null : norm));
            } else {
                fields.putIfAbsent(key, blankToNull(value));
            }
        }
        return new RawOffer(id, group == null ? fields.get("group_id") : group,
                Boolean.parseBoolean(available), fields, params);
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }

    private static String attr(XMLStreamReader xml, String key) {
        return blankToNull(xml.getAttributeValue(null, key));
    }

    private static Long minor(String raw, String currency) {
        if (raw == null) return null;
        int scale = java.util.Currency.getInstance(currency).getDefaultFractionDigits();
        return new BigDecimal(raw).movePointRight(scale).longValueExact();
    }

    private record Param(String label, String value, String field, String normalized) {}
    private record RawOffer(String id, String group, boolean available, Map<String, String> fields, List<Param> params) {}

    private static final class CatalogWriter implements AutoCloseable {
        private final Connection connection;
        private final Map<String, PreparedStatement> statements = new LinkedHashMap<>();
        private final Set<String> entityKeys = new java.util.HashSet<>();
        private int offerCount;

        CatalogWriter(Connection connection) {
            this.connection = connection;
        }

        private void insert(String sql, Object... args) throws SQLException {
            PreparedStatement statement = statements.get(sql);
            if (statement == null) {
                statement = connection.prepareStatement(sql);
                statements.put(sql, statement);
            }
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            statement.executeUpdate();
        }

        private boolean entity(String key, String kind, String id, String name, String url) throws SQLException {
            if (!entityKeys.add(key)) return false;
            insert("INSERT INTO entity VALUES (?,?,?,?,?)", key, kind, id, name, url);
            return true;
        }

        void category(String id, String parent, String name) throws SQLException {
            String key = "category/" + id;
            entity(key, "category", id, name, null);
            insert("INSERT INTO category VALUES (?,?,?)", id, parent, key);
        }

        void offer(RawOffer raw) throws SQLException {
            var fields = raw.fields();
            String name = fields.get("name");
            String vendorUrl = fields.get("vendorUrl");
            String vendor = URI.create(vendorUrl).getPath().replaceFirst("^/+", "").split("/", 2)[0];
            if (entity("vendor/" + vendor, "vendor", vendor, fields.get("vendor"), vendorUrl)) {
                insert("INSERT INTO vendor VALUES (?,?)", vendor, "vendor/" + vendor);
            }

            var facts = new HashMap<String, Object>();
            var sources = new LinkedHashMap<String, Object>();
            var ambiguous = new java.util.HashSet<String>();
            for (int ordinal = 0; ordinal < raw.params().size(); ordinal++) {
                Param param = raw.params().get(ordinal);
                if (param.field() == null || param.normalized() == null) continue;
                Object value = fact(param.field(), param.normalized());
                if (value == null || ambiguous.contains(param.field())) continue;
                Object previous = facts.putIfAbsent(param.field(), value);
                if (previous == null) {
                    sources.put(param.field(), Map.of("param_ordinal", ordinal));
                } else if (!previous.equals(value)) {
                    facts.remove(param.field());
                    sources.remove(param.field());
                    ambiguous.add(param.field());
                }
            }
            addDescriptiveFacts(fields, facts, sources, ambiguous);

            String category = fields.get("categoryId");
            String sku = fields.get("vendorCode");
            String normalizedSku = blankToNull(CatalogText.sku(sku));
            String family = null;
            if (raw.group() != null) {
                family = "family/" + raw.group();
                String title = name.split(",", 2)[0].strip();
                String familyUrl = null;
                if ("391338".equals(raw.group()) && "microsoft".equals(vendor)) {
                    title = "Microsoft Office для дома и бизнеса 2024";
                    familyUrl = "https://store.softline.ru/microsoft/microsoft-office-dlya-doma-i-biznesa-2024/";
                }
                entity(family, "family", raw.group(), title, familyUrl);
            } else if (isPantumModel(vendor, category, normalizedSku)) {
                family = "hardware/" + vendor + "/" + normalizedSku;
                entity(family, "family", vendor + "~" + normalizedSku, fields.get("vendor") + " " + sku, null);
                facts.put("base_model", normalizedSku);
                sources.put("base_model", Map.of("field", "vendorCode"));
            } else if ("электроника".equals(facts.get("product_type")) && facts.get("base_model") instanceof String model) {
                family = "hardware/" + vendor + "/" + model;
                entity(family, "family", vendor + "~" + model, fields.get("vendor") + " " + model, null);
            }

            String id = raw.id();
            String key = "offer/" + id;
            String url = fields.get("url");
            entity(key, "offer", id, name, url);
            String currency = fields.get("currencyId");
            if ("RUR".equals(currency)) currency = "RUB";
            insert("""
                    INSERT INTO offer(offer_id,entity_key,group_id,family_key,vendor_id,category_id,name,
                        sku_raw,sku_norm,url,description,sales_notes,available,currency,price_minor,old_price_minor,
                        product_type,base_model,version,edition,license_type,term_months,device_count,seat_count,
                        seat_max,min_order_qty,price_unit,fact_sources_json)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, id, key, raw.group(), family, vendor, category, name, sku, normalizedSku, url,
                    fields.get("description"), fields.get("sales_notes"), raw.available() ? 1 : 0, currency,
                    minor(fields.get("price"), currency), minor(fields.get("oldprice"), currency),
                    facts.get("product_type"), facts.get("base_model"), facts.get("version"), facts.get("edition"),
                    facts.get("license_type"), facts.get("term_months"), facts.get("device_count"), facts.get("seat_count"),
                    facts.get("seat_max"), facts.get("min_order_qty"), facts.get("price_unit"),
                    JSON.writeValueAsString(sources));
            for (int i = 0; i < raw.params().size(); i++) {
                Param param = raw.params().get(i);
                insert("INSERT INTO offer_param VALUES (?,?,?,?,?,?)",
                        id, i, param.label(), param.value(), param.field(), param.normalized());
            }
            offerCount++;
        }

        private static void addDescriptiveFacts(Map<String, String> fields, Map<String, Object> facts,
                                                Map<String, Object> sources, Set<String> ambiguous) {
            for (String part : CatalogText.normalize(fields.get("description")).split("•")) {
                String[] pair = part.strip().split(":", 2);
                if (pair.length != 2) continue;
                String value = pair[1].strip();
                String key = switch (pair[0].strip()) {
                    case "срок действия" -> "term_months";
                    case "тип лицензии" -> "license_type";
                    case "к-во пользователей" -> "seat_count";
                    case "минимальная покупка" -> "min_order_qty";
                    default -> null;
                };
                if ("платформа".equals(pair[0].strip()) && !EMPTY_PARAMS.contains(value)) {
                    var values = java.util.Arrays.stream(value.split("[/,]")).map(String::strip)
                            .filter(text -> !EMPTY_PARAMS.contains(text)).distinct().toList();
                    if (!values.isEmpty()) sources.put("platform", Map.of("field", "description", "values", values));
                }
                if (key == null || ambiguous.contains(key)) continue;
                Object parsed = fact(key, value);
                if (parsed == null) continue;
                var previous = facts.putIfAbsent(key, parsed);
                if (previous == null) sources.put(key, Map.of("field", "description"));
                else if (!previous.equals(parsed)) {
                    facts.remove(key);
                    sources.remove(key);
                    ambiguous.add(key);
                }
            }
            var packageSize = DEVICE_PACKAGE.matcher(CatalogText.normalize(fields.get("name")));
            if (packageSize.find() && !ambiguous.contains("device_count")) {
                Long value = positive(packageSize.group(1));
                Object previous = facts.putIfAbsent("device_count", value);
                if (previous != null && !previous.equals(value)) {
                    facts.remove("device_count");
                    sources.remove("device_count");
                    return;
                }
                sources.putIfAbsent("device_count", Map.of("field", "name"));
                facts.put("price_unit", "package");
                sources.put("price_unit", Map.of("field", "name"));
            }
        }

        private static boolean isPantumModel(String vendor, String category, String sku) {
            if (!vendor.equals("pantum") || sku == null) return false;
            return ("93995".equals(category) && Set.of("p2500w", "bm1800w").contains(sku))
                    || ("94694".equals(category) && Set.of("pc-211p", "tl-c2318h").contains(sku));
        }

        private static Object fact(String field, String text) {
            return switch (field) {
                case "version" -> text.matches("[0-9][a-z0-9._-]*") ? text : null;
                case "license_type" -> switch (text) {
                    case "full", "полная версия" -> "new";
                    case "продление", "renewal" -> "renewal";
                    case "обновление", "upgrade" -> "upgrade";
                    default -> null;
                };
                case "term_months" -> {
                    var match = TERM.matcher(text);
                    yield match.matches() ? positive(match.group(1)) : null;
                }
                case "device_count", "seat_count", "seat_max" -> positive(text);
                case "min_order_qty" -> {
                    var match = MIN_ORDER.matcher(text);
                    yield match.matches() ? positive(match.group(1)) : null;
                }
                case "product_type", "edition" -> text;
                case "base_model" -> {
                    var code = text.substring(text.lastIndexOf(' ') + 1);
                    yield MODEL.matcher(code).matches() ? code : null;
                }
                default -> null;
            };
        }

        private static Long positive(String digits) {
            return digits.matches("[1-9][0-9]{0,8}") ? Long.valueOf(digits) : null;
        }

        void installRules() throws SQLException {
            if (entityKeys.containsAll(Set.of("vendor/cyberprotect", "category/1033"))) {
                String key = "collection/cyberprotect-1033";
                entity(key, "collection", "cyberprotect-1033", "Кибер Бэкап",
                        "https://store.softline.ru/cyberprotect/?filter%5Bcategory%5D%5B%5D=1033");
                insert("INSERT INTO collection_scope VALUES (?,?,?)", key, "cyberprotect", "1033");
                for (String alias : List.of("кибер бэкап", "кибер бекап", "cyber backup", "кибер backup", "кибербэкап")) {
                    insert("INSERT INTO alias VALUES (?,?,?)", alias, key, "*");
                }
            }
            if (entityKeys.contains("vendor/microsoft")) {
                insert("INSERT INTO alias VALUES (?,?,?)", "микрософт", "vendor/microsoft", "*");
            }
            compatible("p2500w", "pc-211p", "526683");
            compatible("bm1800w", "tl-c2318h", "513625");
        }

        private void compatible(String device, String cartridge, String id) throws SQLException {
            String anchor = "hardware/pantum/" + device;
            String candidate = "hardware/pantum/" + cartridge;
            if (!entityKeys.containsAll(Set.of(anchor, candidate, "offer/" + id))) return;
            String predicate = JSON.writeValueAsString(List.of(Map.of(
                    "field", "supported_device", "anchor_sku", device, "candidate_sku", cartridge, "vendor_id", "pantum")));
            insert("""
                    INSERT INTO relation
                    SELECT ?,?,?,?,url,? FROM offer WHERE offer_id=? AND family_key=?
                    """, anchor, candidate, "pantum-" + device, RULE_VERSION, predicate, id, candidate);
        }

        @Override
        public void close() throws SQLException {
            SQLException failure = null;
            for (PreparedStatement statement : statements.values()) {
                try {
                    statement.close();
                } catch (SQLException exception) {
                    if (failure == null) failure = exception;
                    else failure.addSuppressed(exception);
                }
            }
            if (failure != null) throw failure;
        }
    }
}
