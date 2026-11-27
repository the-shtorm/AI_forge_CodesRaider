package ru.codesraider.smartsearch.search;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.apache.lucene.analysis.ru.RussianAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.DisjunctionMaxQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermInSetQuery;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.grouping.GroupingSearch;
import org.apache.lucene.search.grouping.TopGroups;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.codesraider.smartsearch.modelclient.EncoderClient;
import ru.codesraider.smartsearch.storage.Sqlite;
import tools.jackson.databind.json.JsonMapper;

/** A reader pins one immutable SQLite/Lucene snapshot for the lifetime of this process. */
@Component
public class SearchIndex implements AutoCloseable {
    public record Ranked(List<String> families, String mode) {}
    private static final Logger LOG = LoggerFactory.getLogger(SearchIndex.class);
    private static final Map<String, Float> FIELDS = Map.of("family_name", 8f, "name", 6f, "vendor", 4f,
            "category", 3f, "attributes", 2f, "description", 1f);
    private static final Map<String, Float> LEGACY_FIELDS = Map.of("family", 8f, "name", 6f, "vendor", 4f,
            "category", 3f, "facts", 2f, "description", 1f);
    private static final int LEXICAL_LIMIT = 100;
    private static final int VECTOR_LIMIT = 50;
    private static final Pattern DASHES = Pattern.compile("[‐‑‒–—−]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private final Path snapshots;
    private final EncoderClient encoder;
    private final boolean useVectors;
    private final RussianAnalyzer analyzer = new RussianAnalyzer();
    private FSDirectory directory;
    private DirectoryReader reader;
    private IndexSearcher searcher;
    private CatalogNames catalogNames;
    private Path catalog;
    private String version;
    private String profile;
    private boolean legacy;
    private ScheduledExecutorService monitor;
    private volatile boolean compatible;
    @Value("${SEARCH_TASK_SEMANTICS:false}")
    private boolean suggestTaskMeanings;
    private volatile TaskMeanings taskMeanings;

    public SearchIndex(@Value("${search.snapshots-dir:${SNAPSHOTS_DIR:data/snapshots}}") String snapshots,
                       @Value("${MODEL_BASE_URL:http://model:8000}") String baseUrl,
                       @Value("${SEMANTIC_MODE:auto}") String mode,
                       @Value("${MODEL_QUERY_TIMEOUT_MS:200}") int timeoutMillis) {
        this.snapshots = Path.of(snapshots).toAbsolutePath().normalize();
        this.encoder = new EncoderClient(baseUrl, Duration.ofMillis(timeoutMillis));
        this.useVectors = "auto".equalsIgnoreCase(mode) || "hybrid".equalsIgnoreCase(mode);
    }

    @PostConstruct
    public void open() {
        try {
            String active = Files.readString(snapshots.resolve("active")).strip();
            Path folder = snapshots.resolve(active).normalize();
            if (!snapshots.equals(folder.getParent()) || !folder.getFileName().toString().equals(active)) {
                throw new IOException("Invalid active snapshot pointer");
            }
            var manifest = JsonMapper.builder().build().readTree(folder.resolve("manifest.json").toFile());
            if (!active.equals(manifest.path("index_version").asString(""))) {
                throw new IOException("Active pointer and manifest disagree");
            }
            Path db = folder.resolve("catalog.db");
            if (!Files.isRegularFile(db) || !Files.isDirectory(folder.resolve("lucene"))) {
                throw new IOException("Incomplete snapshot");
            }
            long declaredOffers = manifest.path("offer_count").asLong(-1);
            boolean hasSchema = manifest.has("schema_version");
            if (hasSchema && (manifest.path("schema_version").asInt(0) != 1 || declaredOffers <= 0)) {
                throw new IOException("Unsupported catalog schema or missing offer count");
            }
            try (var conn = DriverManager.getConnection(sqliteUrl(db)); var stmt = conn.createStatement()) {
                if (hasSchema && Sqlite.version(conn) != 1) throw new IOException("SQLite schema does not match the manifest");
                try (var rs = stmt.executeQuery(declaredOffers < 0 ? "SELECT 1 FROM offer LIMIT 1" : "SELECT count(*) FROM offer")) {
                    if (!rs.next() || (declaredOffers >= 0 && (declaredOffers <= 0 || rs.getLong(1) != declaredOffers))) {
                        throw new IOException("Catalog offer count does not match the manifest");
                    }
                }
            }
            if (hasSchema) {
                try (var conn = DriverManager.getConnection(sqliteUrl(db))) {
                    catalogNames = new CatalogNames(conn);
                }
            }
            directory = FSDirectory.open(folder.resolve("lucene"));
            reader = DirectoryReader.open(directory);
            var metadata = reader.getIndexCommit().getUserData();
            if (!active.equals(metadata.get("index_version"))) {
                throw new IOException("Lucene and SQLite snapshot versions disagree");
            }
            var candidateSearcher = new IndexSearcher(reader);
            int count = candidateSearcher.count(term("doc_type", "offer"));
            legacy = count == 0;
            if (legacy) count = candidateSearcher.count(term("type", "offer"));
            if (count == 0 || (declaredOffers >= 0 && count != declaredOffers)) {
                throw new IOException("Lucene offer count does not match the manifest");
            }
            if (!legacy && useVectors && manifest.path("vector_count").asLong(0) > 0) {
                String candidate = manifest.path("profile_id").asString("");
                if (!candidate.isBlank() && candidate.equals(metadata.get("profile_id"))) {
                    profile = candidate;
                    monitor = Executors.newSingleThreadScheduledExecutor(
                            Thread.ofPlatform().daemon().name("encoder-readiness").factory());
                    monitor.scheduleWithFixedDelay(this::probe, 0, 3, TimeUnit.SECONDS);
                }
            }
            catalog = db;
            version = active;
            searcher = candidateSearcher;
        } catch (IOException | SQLException | RuntimeException invalid) {
            LOG.warn("Search snapshot unavailable: {}", invalid.getMessage());
        }
    }

    private void probe() {
        try {
            compatible = profile.equals(encoder.readyProfile());
        } catch (IOException | RuntimeException failure) {
            compatible = false;
        }
        if (suggestTaskMeanings && compatible && taskMeanings == null) {
            try {
                taskMeanings = TaskMeanings.load(encoder, profile);
            } catch (IOException | RuntimeException unavailable) {
                LOG.debug("Task descriptions unavailable", unavailable);
            }
        }
    }

    public boolean ready() { return searcher != null; }
    public String version() { return version; }
    String namedFamily(String query) { return catalogNames == null ? null : catalogNames.family(query); }
    public Path catalogPath() { return catalog; }
    public boolean semanticReady() { return compatible && ready(); }
    private static String sqliteUrl(Path db) { return "jdbc:sqlite:" + db.toUri().toASCIIString() + "?mode=ro"; }

    public static String normalize(String value) {
        if (value == null) return "";
        String folded = Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .strip().replace('ё', 'е');
        return WHITESPACE.matcher(DASHES.matcher(folded).replaceAll("-")).replaceAll(" ");
    }

    public static String normalizeSku(String value) {
        return normalize(value).replace(" ", "");
    }

    public Ranked rank(String query, String vendorId, String categoryId, boolean allowSemantic) throws IOException {
        return prepareRanking(query, allowSemantic).in(vendorId, categoryId);
    }

    final class QueryRanking {
        private final String query;
        private final boolean requireAll;
        private final CompletableFuture<float[]> vector;

        QueryRanking(String query, boolean requireAll, CompletableFuture<float[]> vector) {
            this.query = query;
            this.requireAll = requireAll;
            this.vector = vector;
        }

        Ranked in(String vendorId, String categoryId) throws IOException {
            return SearchIndex.this.rank(query, vendorId, categoryId, vector, requireAll);
        }

        QueryPlan.Purpose suggestedPurpose() {
            var meanings = taskMeanings;
            if (meanings == null || vector == null || !vector.isDone() || vector.isCompletedExceptionally()) return null;
            return meanings.nearest(vector.join());
        }
    }

    /** Reuse the same embedding when the storefront removes a filter for a facet. */
    QueryRanking prepareRanking(String query, boolean allowSemantic) throws IOException {
        return prepareRanking(query, allowSemantic, false);
    }

    QueryRanking prepareRanking(String query, boolean allowSemantic, boolean requireAll) throws IOException {
        if (!ready()) throw new IOException("No active catalog snapshot");
        CompletableFuture<float[]> vector = allowSemantic && semanticReady()
                ? encoder.encodeQuery(profile, query) : null;
        return new QueryRanking(query, requireAll, vector);
    }

    private Ranked rank(String query, String vendorId, String categoryId, CompletableFuture<float[]> vector, boolean requireAll)
            throws IOException {
        List<String> lexical = lexical(words(normalize(query), requireAll), vendorId, categoryId);
        if (requireAll && lexical.isEmpty()) {
            lexical = lexical(titleEvidence(query), vendorId, categoryId);
        }
        if (vector != null) {
            try {
                List<String> semantic = neighbors(vector.join(), vendorId, categoryId);
                if (requireAll) {
                    // A nearest neighbour alone is never evidence for a weak query.
                    var allowed = Set.copyOf(lexical);
                    semantic = semantic.stream().filter(allowed::contains).toList();
                }
                return new Ranked(fuse(lexical, semantic), "hybrid");
            } catch (CompletionException failure) {
                // A restart, mismatched profile or timeout degrades only semantic retrieval.
                // The readiness probe can restore it without reopening the index.
                LOG.debug("Using lexical results after an encoder failure", failure);
            }
        }
        return new Ranked(lexical, "lexical");
    }

    public List<String> suggestNames(String prefix) throws IOException {
        if (!ready()) throw new IOException("No active catalog snapshot");
        String normalized = normalize(prefix);
        if (normalized.isEmpty()) return List.of();
        if (legacy) return entityKeys(new PrefixQuery(new Term("prefix", normalized)), "key");
        var names = new BooleanQuery.Builder()
                .add(new PrefixQuery(new Term("name_norm", normalized)), Occur.SHOULD);
        // Entity titles often begin with a brand or a form factor, e.g. «Rack-серверы».
        // Raw SKU punctuation and multi-word phrases retain whole-prefix semantics.
        if (normalized.length() >= 2 && normalized.codePoints().allMatch(Character::isLetter))
            names.add(new PrefixQuery(new Term("name", normalized)), Occur.SHOULD);
        var query = new BooleanQuery.Builder()
                .add(term("doc_type", "entity"), Occur.FILTER)
                .add(names.build(), Occur.MUST).build();
        // Filtering and profile ranking happen in the catalog. Truncating here can
        // discard every entity from the selected category on a common prefix.
        return entityKeys(query, "entity_key");
    }

    List<String> exactNames(String name) throws IOException {
        if (!ready()) throw new IOException("No active catalog snapshot");
        var query = new BooleanQuery.Builder()
                .add(term(legacy ? "type" : "doc_type", "entity"), Occur.FILTER)
                .add(term(legacy ? "prefix" : "name_norm", name), Occur.MUST).build();
        return entityKeys(query, legacy ? "key" : "entity_key");
    }

    private List<String> entityKeys(Query query, String field) throws IOException {
        int count = searcher.count(query);
        if (count == 0) return List.of();
        var keys = new LinkedHashSet<String>();
        var stored = searcher.storedFields();
        for (var hit : searcher.search(query, count).scoreDocs) {
            String key = stored.document(hit.doc, Set.of(field)).get(field);
            if (key != null) keys.add(key);
        }
        return List.copyOf(keys);
    }

    private List<String> lexical(Query content, String vendorId, String categoryId) throws IOException {
        var query = new BooleanQuery.Builder().add(content, Occur.MUST)
                .add(term(legacy ? "type" : "doc_type", "offer"), Occur.FILTER);
        if (vendorId != null) query.add(term("vendor_id", vendorId), Occur.FILTER);
        if (categoryId != null) query.add(term("category_id", categoryId), Occur.FILTER);
        if (legacy) return legacyFamilies(query.build());
        var grouping = new GroupingSearch("family_key").setGroupDocsLimit(1).setIgnoreDocsWithoutGroupField(true);
        TopGroups<BytesRef> groups = grouping.search(searcher, query.build(), 0, LEXICAL_LIMIT);
        var result = new ArrayList<String>(groups.groups.length);
        for (var group : groups.groups) {
            if (group.groupValue() != null) result.add(group.groupValue().utf8ToString());
        }
        return result;
    }

    private List<String> legacyFamilies(Query query) throws IOException {
        var result = new LinkedHashSet<String>();
        var stored = searcher.storedFields();
        for (String tier : List.of("primary", "priced", "unpriced")) {
            Query scoped = new BooleanQuery.Builder().add(query, Occur.MUST)
                    .add(term("tier", tier), Occur.FILTER).build();
            var groups = new LinkedHashSet<String>();
            ScoreDoc after = null;
            while (groups.size() < LEXICAL_LIMIT) {
                var page = searcher.searchAfter(after, scoped, 128).scoreDocs;
                if (page.length == 0) break;
                for (var hit : page) {
                    String family = stored.document(hit.doc, Set.of("family_key")).get("family_key");
                    if (family != null) groups.add(family);
                    if (groups.size() == LEXICAL_LIMIT) break;
                }
                after = page[page.length - 1];
            }
            result.addAll(groups);
        }
        return List.copyOf(result);
    }

    private static final Pattern REQUEST_WORDS = Pattern.compile(
            "(?U)\\b(?:как|хочу|хотим|нужно|нужны|надо|купить|ищу|подобрать|подбираю|заказать|чем|"
                    + "программа|программу|программы|сервис|средство|офисный|рабочий)\\b");

    private Query titleEvidence(String text) throws IOException {
        var terms = tokens(REQUEST_WORDS.matcher(text).replaceAll(" "));
        if (terms.isEmpty()) return new MatchNoDocsQuery();
        var query = new BooleanQuery.Builder();
        int optional = 0;
        for (String word : terms) {
            var title = new DisjunctionMaxQuery(List.of(term("name", word),
                    term(legacy ? "family" : "family_name", word)), 0f);
            if (word.chars().anyMatch(Character::isDigit)) query.add(title, Occur.MUST);
            else {
                query.add(title, Occur.SHOULD);
                optional++;
            }
        }
        // Require two thirds of the content words in the title, not scattered in descriptions.
        query.setMinimumNumberShouldMatch((optional * 2 + 2) / 3);
        return query.build();
    }

    private Set<String> tokens(String text) throws IOException {
        if (legacy) text = text.replaceAll("(?U)\\b(?:найди|найдите|подбери|покажи|нужен|нужна|купить)\\b", " ");
        Set<String> terms = new LinkedHashSet<>();
        try (var stream = analyzer.tokenStream("name", text)) {
            var token = stream.addAttribute(CharTermAttribute.class);
            stream.reset();
            while (terms.size() < 32 && stream.incrementToken()) terms.add(token.toString());
            stream.end();
        }
        return terms;
    }

    private Query words(String text, boolean requireAll) throws IOException {
        var terms = tokens(text);
        if (terms.isEmpty()) return new MatchNoDocsQuery();
        var result = new BooleanQuery.Builder();
        for (String word : terms) {
            var fields = new ArrayList<Query>();
            (legacy ? LEGACY_FIELDS : FIELDS)
                    .forEach((field, weight) -> fields.add(new BoostQuery(term(field, word), weight)));
            result.add(new DisjunctionMaxQuery(fields, 0.1f), legacy || requireAll || word.matches(".*\\d.*") ? Occur.MUST : Occur.SHOULD);
        }
        return result.build();
    }

    private static TermQuery term(String field, String value) {
        return new TermQuery(new Term(field, value));
    }

    private List<String> neighbors(float[] vector, String vendorId, String categoryId) throws IOException {
        Query scope = term("doc_type", "vector");
        if (vendorId != null || categoryId != null) {
            var keys = scopedFamilies(vendorId, categoryId);
            if (keys.isEmpty()) return List.of();
            scope = new BooleanQuery.Builder().add(scope, Occur.FILTER)
                    .add(new TermInSetQuery("family_key", keys), Occur.FILTER).build();
        }
        var hits = searcher.search(new KnnFloatVectorQuery("vector", vector, VECTOR_LIMIT, scope), VECTOR_LIMIT);
        var families = new LinkedHashSet<String>();
        var stored = searcher.storedFields();
        for (var hit : hits.scoreDocs) {
            families.add(stored.document(hit.doc, Set.of("family_key")).get("family_key"));
        }
        return List.copyOf(families);
    }

    private List<BytesRef> scopedFamilies(String vendorId, String categoryId) throws IOException {
        String sql = "SELECT DISTINCT family_key FROM offer WHERE family_key IS NOT NULL"
                + (vendorId == null ? "" : " AND vendor_id=?") + (categoryId == null ? "" : " AND category_id=?");
        try (var conn = DriverManager.getConnection(sqliteUrl(catalog)); var statement = conn.prepareStatement(sql)) {
            int i = 1;
            if (vendorId != null) statement.setString(i++, vendorId);
            if (categoryId != null) statement.setString(i, categoryId);
            var keys = new ArrayList<BytesRef>();
            try (var rs = statement.executeQuery()) {
                while (rs.next()) keys.add(new BytesRef(rs.getString(1)));
            }
            return keys;
        } catch (SQLException e) {
            throw new IOException("Cannot read catalog scope", e);
        }
    }

    static List<String> fuse(List<String> lexical, List<String> semantic) {
        var scores = new HashMap<String, Double>();
        for (var list : List.of(lexical, semantic)) {
            int rank = 1;
            for (String family : new LinkedHashSet<>(list)) {
                scores.merge(family, 1.0 / (60 + rank++), Double::sum);
            }
        }
        return scores.keySet().stream().sorted(Comparator.<String>comparingDouble(scores::get)
                .reversed().thenComparing(Comparator.naturalOrder())).toList();
    }

    @Override
    @PreDestroy
    public void close() throws IOException {
        if (monitor != null) monitor.shutdownNow();
        encoder.close();
        if (reader != null) reader.close();
        if (directory != null) directory.close();
        analyzer.close();
    }
}
