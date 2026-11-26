package ru.codesraider.smartsearch.catalog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.store.FSDirectory;
import org.springframework.jdbc.core.JdbcTemplate;

import ru.codesraider.smartsearch.storage.Sqlite;
import ru.codesraider.smartsearch.web.ApiProblem;
import tools.jackson.databind.json.JsonMapper;

public final class CatalogSnapshot implements AutoCloseable {
    private static final String SELECT_ENTITY = "SELECT entity_key,kind,id,name,canonical_url FROM entity";
    private static final String SELECT_OFFER = """
            SELECT o.*, e.name AS vendor_name FROM offer o
            JOIN vendor v ON v.vendor_id=o.vendor_id JOIN entity e ON e.entity_key=v.entity_key
            """;
    private final String version;
    private final FSDirectory directory;
    private final DirectoryReader reader;
    private final IndexSearcher searcher;
    private final JdbcTemplate sql;

    private CatalogSnapshot(String version, FSDirectory directory, DirectoryReader reader, JdbcTemplate sql) {
        this.version = version;
        this.directory = directory;
        this.reader = reader;
        this.searcher = new IndexSearcher(reader);
        this.sql = sql;
    }

    static CatalogSnapshot open(Path root, String version) throws IOException, SQLException {
        if (!version.matches("[a-zA-Z0-9][a-zA-Z0-9._-]*")) return null;
        Path snapshot = root.resolve(version);
        String manifestText = Files.readString(snapshot.resolve("manifest.json"));
        var manifest = JsonMapper.builder().build().readTree(manifestText);
        if (!version.equals(manifest.path("index_version").asString(null)) || manifest.path("schema_version").asInt(0) != 1) {
            return null;
        }
        var source = Sqlite.dataSource(snapshot.resolve("catalog.db"), true);
        try (var connection = source.getConnection()) {
            if (Sqlite.version(connection) != 1) return null;
        }
        var jdbc = new JdbcTemplate(source);
        int expectedOffers = manifest.path("offer_count").asInt(-1);
        if (expectedOffers <= 0 || expectedOffers != jdbc.queryForObject("SELECT count(*) FROM offer", Integer.class)) return null;
        var directory = FSDirectory.open(snapshot.resolve("lucene"));
        try {
            var reader = DirectoryReader.open(directory);
            try {
                if (version.equals(reader.getIndexCommit().getUserData().get("index_version"))) {
                    var result = new CatalogSnapshot(version, directory, reader, jdbc);
                    if (CatalogIndex.countOffers(result.searcher) == expectedOffers) return result;
                }
            } catch (IOException | RuntimeException exception) {
                reader.close();
                throw exception;
            }
            reader.close();
            directory.close();
            return null;
        } catch (IOException | RuntimeException exception) {
            directory.close();
            throw exception;
        }
    }

    public String version() {
        return version;
    }

    public Entity entity(String kind, String id) {
        return sql.query(SELECT_ENTITY + " WHERE kind=? AND id=?", CatalogSnapshot::entityRow, kind, id)
                .stream().findFirst().orElse(null);
    }

    public Map<String, Entity> entities(Collection<String> keys) {
        var result = new LinkedHashMap<String, Entity>();
        if (keys.isEmpty()) return result;
        sql.query(SELECT_ENTITY + " WHERE entity_key IN (" + placeholders(keys.size()) + ")", CatalogSnapshot::entityRow,
                keys.toArray()).forEach(entity -> result.put(entity.key(), entity));
        return result;
    }

    public List<Entity> matchingNames(String normalized, String category) {
        return sql.query("""
                SELECT e.entity_key,e.kind,e.id,e.name,e.canonical_url
                FROM alias a JOIN entity e ON e.entity_key=a.entity_key
                WHERE a.term=? AND (a.scope='*' OR a.scope=?)
                ORDER BY a.scope='*', e.entity_key
                """, CatalogSnapshot::entityRow, normalized, "category/" + category);
    }

    public Scope scope(Entity entity) {
        if (entity == null) return Scope.empty();
        return switch (entity.kind()) {
            case "offer" -> new Scope(entity.id(), null, null, null);
            case "family" -> new Scope(null, entity.key(), null, null);
            case "vendor" -> new Scope(null, null, entity.id(), null);
            case "category" -> new Scope(null, null, null, entity.id());
            case "collection" -> sql.query("""
                    SELECT vendor_id,category_id FROM collection_scope WHERE entity_key=?
                    """, (row, number) -> new Scope(null, null, row.getString(1), row.getString(2)), entity.key())
                    .stream().findFirst().orElseThrow(ApiProblem::catalog);
            default -> throw ApiProblem.catalog();
        };
    }

    public List<Product> bySku(String sku) {
        return sql.query(SELECT_OFFER + " WHERE o.sku_norm=? ORDER BY o.offer_id", CatalogSnapshot::productRow, sku);
    }

    public List<Product> products(Scope scope) {
        var query = new StringBuilder(SELECT_OFFER).append(" WHERE 1=1");
        var args = new java.util.ArrayList<String>();
        restrict(query, args, "offer_id", scope.offerId());
        restrict(query, args, "family_key", scope.familyKey());
        restrict(query, args, "vendor_id", scope.vendorId());
        restrict(query, args, "category_id", scope.categoryId());
        return sql.query(query.append(" ORDER BY o.offer_id").toString(), CatalogSnapshot::productRow, args.toArray());
    }

    public List<Product> familyProducts(Collection<String> keys) {
        if (keys.isEmpty()) return List.of();
        return sql.query(SELECT_OFFER + " WHERE o.family_key IN (" + placeholders(keys.size()) + ") ORDER BY o.offer_id",
                CatalogSnapshot::productRow, keys.toArray());
    }

    public boolean inCategory(Entity entity, String category) {
        Scope scope = scope(entity);
        if (scope.categoryId() != null) return scope.categoryId().equals(category);
        var query = new StringBuilder("SELECT EXISTS(SELECT 1 FROM offer o WHERE o.category_id=?");
        var args = new java.util.ArrayList<String>();
        args.add(category);
        restrict(query, args, "offer_id", scope.offerId());
        restrict(query, args, "family_key", scope.familyKey());
        restrict(query, args, "vendor_id", scope.vendorId());
        return Boolean.TRUE.equals(sql.queryForObject(query.append(')').toString(), Boolean.class, args.toArray()));
    }

    public List<String> lexicalFamilies(String text, String category, String vendor) {
        try {
            return CatalogIndex.families(searcher, text, category, vendor, 100);
        } catch (IOException exception) {
            throw ApiProblem.catalog(exception);
        }
    }

    public List<String> prefixes(String prefix) {
        try {
            return CatalogIndex.suggest(searcher, prefix, 256);
        } catch (IOException exception) {
            throw ApiProblem.catalog(exception);
        }
    }

    public List<Parameter> parameters(String offerId) {
        return sql.query("""
                SELECT name,value,field_key,value_norm FROM offer_param WHERE offer_id=? ORDER BY ordinal
                """, (row, number) -> new Parameter(row.getString(1), row.getString(2), row.getString(3), row.getString(4)),
                offerId);
    }

    public boolean hasPlatform(String offerId, String value) {
        return Boolean.TRUE.equals(sql.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM offer_param WHERE field_key='platform' AND value_norm=? AND offer_id=?)
                    OR EXISTS(SELECT 1 FROM offer o JOIN json_each(o.fact_sources_json,'$.platform.values') p
                              WHERE o.offer_id=? AND p.value=?)
                """, Boolean.class, value, offerId, offerId, value));
    }

    public List<Relation> relations(String family) {
        return sql.query("""
                SELECT candidate_family,rule_id,rule_version,source_url,predicates_json
                FROM relation WHERE anchor_family=? ORDER BY rule_id,candidate_family
                """, (row, number) -> new Relation(row.getString(1), row.getString(2), row.getString(3),
                row.getString(4), row.getString(5)), family);
    }

    private static void restrict(StringBuilder query, List<String> args, String column, String value) {
        if (value != null) {
            query.append(" AND o.").append(column).append("=?");
            args.add(value);
        }
    }

    private static String placeholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }

    private static Entity entityRow(ResultSet row, int number) throws SQLException {
        return new Entity(row.getString(1), row.getString(2), row.getString(3), row.getString(4), row.getString(5));
    }

    private static Product productRow(ResultSet row, int number) throws SQLException {
        return new Product(row.getString("offer_id"), row.getString("family_key"), row.getString("vendor_id"),
                row.getString("vendor_name"), row.getString("category_id"), row.getString("name"),
                row.getString("sku_raw"), row.getString("sku_norm"), row.getString("url"), row.getBoolean("available"),
                row.getString("currency"), nullableLong(row, "price_minor"), nullableLong(row, "old_price_minor"),
                row.getString("version"), row.getString("edition"), row.getString("license_type"),
                nullableLong(row, "term_months"), nullableLong(row, "device_count"), nullableLong(row, "seat_count"),
                nullableLong(row, "device_min"), nullableLong(row, "device_max"), nullableLong(row, "seat_min"),
                nullableLong(row, "seat_max"), nullableLong(row, "min_order_qty"), row.getString("price_unit"),
                row.getString("base_model"), row.getString("fact_sources_json"));
    }

    private static Long nullableLong(ResultSet row, String column) throws SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? null : value;
    }

    @Override
    public void close() throws IOException {
        try (directory; reader) {
            // Close the reader before its directory.
        }
    }

    public record Entity(String key, String kind, String id, String name, String canonicalUrl) {}

    public record Scope(String offerId, String familyKey, String vendorId, String categoryId) {
        public static Scope empty() {
            return new Scope(null, null, null, null);
        }
    }

    public record Product(String id, String family, String vendorId, String vendor, String categoryId, String name,
                          String sku, String normalizedSku, String url, boolean available, String currency,
                          Long price, Long oldPrice, String version, String edition, String licenseType, Long term,
                          Long devices, Long seats, Long deviceMin, Long deviceMax, Long seatMin, Long seatMax,
                          Long minOrder, String priceUnit, String model, String factSources) {
        public boolean hasPrice() {
            return price != null && price > 0;
        }

        public boolean primary() {
            return available && hasPrice();
        }
    }

    public record Parameter(String name, String value, String field, String normalized) {}

    public record Relation(String candidateFamily, String ruleId, String ruleVersion, String sourceUrl, String predicates) {}
}
