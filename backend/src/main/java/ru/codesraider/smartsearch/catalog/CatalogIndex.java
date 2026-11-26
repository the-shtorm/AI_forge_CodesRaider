package ru.codesraider.smartsearch.catalog;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.util.BytesRef;

import ru.codesraider.smartsearch.modelclient.EncoderClient;
import ru.codesraider.smartsearch.search.SearchIndex;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class CatalogIndex {
    private static final class Passage {
        final String family;
        final Set<String> vendors = new HashSet<>();
        final Set<String> categories = new HashSet<>();
        String text;
        boolean primary;

        Passage(String family) {
            this.family = family;
        }
    }

    private final IndexWriter writer;
    private final Map<String, Passage> passages = new LinkedHashMap<>();
    private final boolean vectorIndex;

    CatalogIndex(IndexWriter writer, boolean vectorIndex) {
        this.writer = writer;
        this.vectorIndex = vectorIndex;
    }

    private static void exact(Document d, String field, String value) {
        if (value != null) d.add(new StringField(field, value, Field.Store.YES));
    }

    private static void text(Document d, String field, String value) {
        if (value != null && !value.isBlank()) d.add(new TextField(field, value, Field.Store.NO));
    }

    void offer(FeedOffer o, String category) throws IOException {
        Document d = new Document();
        exact(d, "doc_type", "offer");
        exact(d, "offer_id", o.id);
        exact(d, "family_key", o.family);
        if (o.family != null) d.add(new SortedDocValuesField("family_key", new BytesRef(o.family)));
        exact(d, "sku_norm", o.skuNorm);
        exact(d, "vendor_id", o.vendorId);
        exact(d, "category_id", o.get("categoryId"));
        exact(d, "base_model", o.baseModel);
        exact(d, "version", o.version);
        exact(d, "license_type", o.licenseType);
        text(d, "name", o.get("name"));
        text(d, "family_name", o.familyName);
        text(d, "vendor", o.get("vendor"));
        text(d, "category", category);
        String purpose = "";
        var attrs = new StringBuilder();
        for (var p : o.params) {
            if (FeedOffer.normalizedValue(p.value()) == null) continue;
            attrs.append(p.value()).append(' ');
            if ("Назначение".equalsIgnoreCase(p.name())) purpose = p.value();
        }
        text(d, "attributes", attrs.toString());
        String description = plain(o.get("description"));
        text(d, "description", description);
        writer.addDocument(d);

        if (vectorIndex && o.family != null) {
            String key = o.group == null ? "offer/" + o.id : o.family;
            var p = passages.computeIfAbsent(key, unused -> new Passage(o.family));
            p.categories.add(o.get("categoryId"));
            p.vendors.add(o.vendorId);
            boolean primary = o.available && o.price != null && o.price > 0;
            if (p.text == null || (primary && !p.primary)) {
                // Keep task and product identity ahead of generic license and supply metadata.
                String passage =
                        String.join(
                                ". ", o.familyName, purpose, category, o.get("name"), description);
                p.text = passage.substring(0, Math.min(520, passage.length()));
                p.primary = primary;
            }
        }
    }

    static String plain(String value) {
        return value == null
                ? ""
                : value.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").strip();
    }

    int writeVectors(EncoderClient client, String profile) throws IOException {
        var batch = new ArrayList<Passage>(32);
        int count = 0;
        for (var p : passages.values()) {
            batch.add(p);
            if (batch.size() == 32) {
                encodeBatch(client, profile, batch);
                count += batch.size();
                batch.clear();
                if (count % 512 == 0) System.out.println("Indexed " + count + " vectors");
            }
        }
        if (!batch.isEmpty()) encodeBatch(client, profile, batch);
        return count + batch.size();
    }

    private void encodeBatch(EncoderClient client, String profile, List<Passage> batch)
            throws IOException {
        var vectors = client.encodePassages(profile, batch.stream().map(p -> p.text).toList());
        for (int i = 0; i < batch.size(); i++) {
            Passage p = batch.get(i);
            var d = new Document();
            exact(d, "doc_type", "vector");
            exact(d, "family_key", p.family);
            // DocValues are also needed on vector docs when grouping mixed results.
            d.add(new SortedDocValuesField("family_key", new BytesRef(p.family)));
            for (String vendor : p.vendors) exact(d, "vendor_id", vendor);
            for (String category : p.categories) exact(d, "category_id", category);
            d.add(
                    new KnnFloatVectorField(
                            "vector", vectors.get(i), VectorSimilarityFunction.DOT_PRODUCT));
            writer.addDocument(d);
        }
    }

    void entities(java.sql.Connection db) throws java.sql.SQLException, IOException {
        try (var stmt = db.createStatement();
                var rs =
                        stmt.executeQuery(
                                """
                                SELECT e.entity_key,e.kind,e.id,e.name,group_concat(a.term,' ') AS aliases
                                FROM entity e LEFT JOIN alias a ON a.entity_key=e.entity_key WHERE e.kind <> 'offer'
                                GROUP BY e.entity_key\
                                """)) {
            while (rs.next()) {
                var d = new Document();
                exact(d, "doc_type", "entity");
                exact(d, "entity_key", rs.getString(1));
                exact(d, "entity_kind", rs.getString(2));
                exact(d, "entity_id", rs.getString(3));
                exact(d, "name_norm", SearchIndex.normalize(rs.getString(4)));
                text(d, "name", rs.getString(4));
                text(d, "aliases", rs.getString(5));
                writer.addDocument(d);
            }
        }
    }
}
