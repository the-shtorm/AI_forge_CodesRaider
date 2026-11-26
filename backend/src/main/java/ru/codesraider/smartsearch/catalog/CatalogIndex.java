package ru.codesraider.smartsearch.catalog;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.apache.lucene.analysis.ru.RussianAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.DisjunctionMaxQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.FSDirectory;

import ru.codesraider.smartsearch.storage.Sqlite;

final class CatalogIndex {
    private static final List<String> SEARCH_FIELDS = List.of("family", "name", "vendor", "category", "facts", "description");
    private static final float[] WEIGHTS = {8, 6, 4, 3, 2, 1};
    private static final List<String> TIERS = List.of("primary", "priced", "unpriced");

    private CatalogIndex() {}

    record Counts(int offers, int families) {}

    static Counts build(Path db, Path path, String version) throws IOException, SQLException {
        int offers = 0;
        int families = 0;
        try (var directory = FSDirectory.open(path);
             var analyzer = new RussianAnalyzer();
             var writer = new IndexWriter(directory, new IndexWriterConfig(analyzer));
             var connection = Sqlite.dataSource(db, true).getConnection();
             var statement = connection.createStatement()) {
            writer.setLiveCommitData(Map.of("index_version", version).entrySet());
            try (var rows = statement.executeQuery("""
                    WITH RECURSIVE category_path(id,path) AS (
                        SELECT c.category_id,e.name FROM category c JOIN entity e ON e.entity_key=c.entity_key
                        WHERE c.parent_id IS NULL
                        UNION ALL
                        SELECT c.category_id,p.path || ' ' || e.name FROM category_path p
                        JOIN category c ON c.parent_id=p.id JOIN entity e ON e.entity_key=c.entity_key
                    )
                    SELECT o.offer_id, o.name, family.name, vendor.name, COALESCE(p.path, category.name), o.base_model,
                           o.description, o.sku_norm,
                           (SELECT group_concat(p.value_norm, ' ')
                            FROM offer_param p WHERE p.offer_id=o.offer_id AND p.value_norm IS NOT NULL),
                           o.family_key, o.vendor_id, o.category_id,
                           CASE WHEN o.price_minor>0 AND o.available=1 THEN 'primary'
                                WHEN o.price_minor>0 THEN 'priced' ELSE 'unpriced' END
                    FROM offer o
                    JOIN vendor v ON v.vendor_id = o.vendor_id
                    JOIN entity vendor ON vendor.entity_key = v.entity_key
                    JOIN category c ON c.category_id = o.category_id
                    JOIN entity category ON category.entity_key = c.entity_key
                    LEFT JOIN category_path p ON p.id=o.category_id
                    LEFT JOIN entity family ON family.entity_key = o.family_key
                    """)) {
                while (rows.next()) {
                    var doc = new Document();
                    exact(doc, "type", "offer", Field.Store.NO);
                    exact(doc, "id", rows.getString(1), Field.Store.YES);
                    text(doc, "name", rows.getString(2));
                    text(doc, "family", rows.getString(3));
                    text(doc, "vendor", rows.getString(4));
                    text(doc, "category", rows.getString(5));
                    text(doc, "facts", rows.getString(6));
                    text(doc, "description", rows.getString(7));
                    exact(doc, "sku", rows.getString(8), Field.Store.NO);
                    text(doc, "name", rows.getString(8));
                    text(doc, "facts", rows.getString(9));
                    exact(doc, "family_key", rows.getString(10), Field.Store.YES);
                    exact(doc, "vendor_id", rows.getString(11), Field.Store.NO);
                    exact(doc, "category_id", rows.getString(12), Field.Store.NO);
                    exact(doc, "tier", rows.getString(13), Field.Store.NO);
                    writer.addDocument(doc);
                    offers++;
                }
            }
            try (var rows = statement.executeQuery("""
                    SELECT entity_key, kind, name, NULL AS sku FROM entity WHERE kind <> 'offer'
                    UNION ALL
                    SELECT e.entity_key, e.kind, e.name, o.sku_norm
                    FROM offer o JOIN entity e ON e.entity_key = o.entity_key
                    WHERE o.sku_norm IS NOT NULL
                    """)) {
                while (rows.next()) {
                    var doc = new Document();
                    exact(doc, "type", "entity", Field.Store.NO);
                    exact(doc, "key", rows.getString(1), Field.Store.YES);
                    exact(doc, "prefix", CatalogText.normalize(rows.getString(3)), Field.Store.NO);
                    exact(doc, "prefix", rows.getString(4), Field.Store.NO);
                    writer.addDocument(doc);
                    if ("family".equals(rows.getString(2))) families++;
                }
            }
            try (var rows = statement.executeQuery("SELECT entity_key, term FROM alias")) {
                while (rows.next()) {
                    var doc = new Document();
                    exact(doc, "type", "alias", Field.Store.NO);
                    exact(doc, "key", rows.getString(1), Field.Store.YES);
                    exact(doc, "prefix", rows.getString(2), Field.Store.NO);
                    writer.addDocument(doc);
                }
            }
            writer.commit();
        }
        return new Counts(offers, families);
    }

    static List<String> families(IndexSearcher searcher, String normalized, String category, String vendor, int limit)
            throws IOException {
        var tokens = new LinkedHashSet<String>();
        try (var analyzer = new RussianAnalyzer(); var stream = analyzer.tokenStream("name", normalized)) {
            var token = stream.addAttribute(CharTermAttribute.class);
            stream.reset();
            while (stream.incrementToken()) {
                tokens.add(token.toString());
                if (tokens.size() == 48) {
                    while (stream.incrementToken()) { }
                    break;
                }
            }
            stream.end();
        }
        if (tokens.isEmpty()) return List.of();
        var query = new BooleanQuery.Builder().add(new TermQuery(new Term("type", "offer")), Occur.FILTER);
        if (category != null) query.add(new TermQuery(new Term("category_id", category)), Occur.FILTER);
        if (vendor != null) query.add(new TermQuery(new Term("vendor_id", vendor)), Occur.FILTER);
        for (String token : tokens) {
            var alternatives = new ArrayList<org.apache.lucene.search.Query>();
            for (int i = 0; i < SEARCH_FIELDS.size(); i++) {
                alternatives.add(new BoostQuery(new TermQuery(new Term(SEARCH_FIELDS.get(i), token)), WEIGHTS[i]));
            }
            query.add(new DisjunctionMaxQuery(alternatives, 0.1f), Occur.MUST);
        }
        var lexical = query.build();
        var result = new LinkedHashSet<String>();
        var stored = searcher.storedFields();
        for (String tier : TIERS) {
            var inTier = new BooleanQuery.Builder().add(lexical, Occur.MUST)
                    .add(new TermQuery(new Term("tier", tier)), Occur.FILTER).build();
            var groups = new LinkedHashSet<String>();
            ScoreDoc cursor = null;
            while (groups.size() < limit) {
                var page = searcher.searchAfter(cursor, inTier, 128).scoreDocs;
                if (page.length == 0) break;
                for (var hit : page) {
                    String family = stored.document(hit.doc).get("family_key");
                    if (family != null) groups.add(family);
                    if (groups.size() == limit) break;
                }
                cursor = page[page.length - 1];
            }
            result.addAll(groups);
        }
        return List.copyOf(result);
    }

    static int countOffers(IndexSearcher searcher) throws IOException {
        return searcher.count(new TermQuery(new Term("type", "offer")));
    }

    static List<String> suggest(IndexSearcher searcher, String prefix, int limit) throws IOException {
        var query = new PrefixQuery(new Term("prefix", prefix));
        return hits(searcher, query, "key", limit).stream().distinct().toList();
    }

    private static List<String> hits(IndexSearcher searcher, org.apache.lucene.search.Query query,
                                     String field, int limit) throws IOException {
        var result = new ArrayList<String>();
        var fields = searcher.storedFields();
        for (var hit : searcher.search(query, limit).scoreDocs) {
            result.add(fields.document(hit.doc).get(field));
        }
        return result;
    }

    private static void exact(Document doc, String field, String value, Field.Store store) {
        if (value != null && !value.isBlank()) doc.add(new StringField(field, value, store));
    }

    private static void text(Document doc, String field, String value) {
        if (value != null && !value.isBlank()) {
            doc.add(new TextField(field, CatalogText.normalize(value), Field.Store.NO));
        }
    }
}
