package ru.codesraider.smartsearch.search;

import org.apache.lucene.analysis.ru.RussianAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.util.QueryBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

import javax.xml.stream.*;

@Component
class Catalog implements AutoCloseable {
    record Target(String kind, String id, String url) {
        String key() {
            return kind + "/" + id;
        }

        boolean includes(Product p, Catalog c) {
            return switch (kind) {
                case "offer" -> p.id().equals(id);
                case "family" -> !p.family().isEmpty() && p.familyId().equals(id);
                case "vendor" -> p.vendorId().equals(id);
                case "category" -> c.inCategory(p, id);
                case "collection" ->
                        p.vendorId().equals("cyberprotect") && p.category().equals("1033");
                default -> false;
            };
        }
    }

    record Entry(Target target, String label) {}

    final Map<String, Product> byId = new LinkedHashMap<>();
    final Map<String, List<Product>> bySku = new HashMap<>();
    final Map<String, String> categories = new HashMap<>(), parents = new HashMap<>();
    final Map<String, Entry> entries = new LinkedHashMap<>();
    final Map<String, Target> aliases = new LinkedHashMap<>();
    final String version;
    private final Map<String, List<Product>> members = new HashMap<>();
    private final Map<QueryPlan.Purpose, List<Product>> purposes =
            new EnumMap<>(QueryPlan.Purpose.class);
    private final Set<String> vendorNames = new HashSet<>();
    private final ByteBuffersDirectory directory = new ByteBuffersDirectory();
    private final RussianAnalyzer analyzer = new RussianAnalyzer();
    private final DirectoryReader reader;
    private final IndexSearcher searcher;

    Catalog(@Value("${CATALOG_PATH:}") String path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream source =
                        path.isBlank()
                                ? resource("demo.xml")
                                : Files.newInputStream(Path.of(path));
                var data = new DigestInputStream(new BufferedInputStream(source), digest);
                var writer = new IndexWriter(directory, new IndexWriterConfig(analyzer))) {
            read(data, writer);
        }
        if (byId.isEmpty()) throw new IOException("Пустой каталог");
        version =
                (path.isBlank() ? "demo-" : "xml-")
                        + HexFormat.of().formatHex(digest.digest()).substring(0, 16)
                        + "-v1";
        for (Product p : byId.values()) {
            indexMember("vendor/" + p.vendorId(), p);
            if (!p.family().isEmpty()) indexMember("family/" + p.familyId(), p);
            Set<String> seen = new HashSet<>();
            for (String key = p.category(); key != null && seen.add(key); key = parents.get(key))
                indexMember("category/" + key, p);
            if (p.vendorId().equals("cyberprotect") && p.category().equals("1033"))
                indexMember("collection/cyberprotect-1033", p);
            vendorNames.add(Product.norm(p.vendor()));
            for (var purpose : QueryPlan.Purpose.values())
                if (purpose != QueryPlan.Purpose.WORDS && purpose.matches(p, this))
                    purposes.computeIfAbsent(purpose, k -> new ArrayList<>()).add(p);
        }
        try (var lines =
                new BufferedReader(
                        new InputStreamReader(
                                resource("navigation.tsv"), StandardCharsets.UTF_8))) {
            for (String line : lines.lines().filter(s -> !s.startsWith("#")).toList()) {
                String[] v = line.split("\t");
                Target target = new Target(v[0], v[1], "https://store.softline.ru" + v[3]);
                if (target.kind().equals("collection")
                        ? !members.containsKey(target.key())
                        : !entries.containsKey(target.key())) continue;
                put(target, v[2]);
                for (String alias : v[4].split("\\|")) aliases.put(Product.norm(alias), target);
            }
        }
        reader = DirectoryReader.open(directory);
        searcher = new IndexSearcher(reader);
    }

    private void indexMember(String key, Product p) {
        members.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
    }

    List<Product> scoped(Target t) {
        return t.kind().equals("offer")
                ? List.of(byId.get(t.id()))
                : members.getOrDefault(t.key(), List.of());
    }

    boolean mentionsVendor(String text) {
        return vendorNames.stream().filter(v -> !v.isBlank()).anyMatch(text::contains);
    }

    Collection<Product> retrieve(QueryPlan plan) throws IOException {
        if (!plan.text().isBlank()) return lexical(plan.text());
        if (plan.scope() != null) return scoped(plan.scope());
        if (plan.purpose() != QueryPlan.Purpose.WORDS) {
            List<Product> first = purposes.getOrDefault(plan.purpose(), List.of());
            if (plan.additionalPurposes().isEmpty() || plan.join().equals("all")) return first;
            Map<String, Product> union = new LinkedHashMap<>();
            for (Product p : first) union.put(p.id(), p);
            for (var op : plan.additionalPurposes())
                for (Product p : purposes.getOrDefault(op, List.of())) union.putIfAbsent(p.id(), p);
            return union.values();
        }
        return plan.models().stream()
                .flatMap(code -> bySku.getOrDefault(code, List.of()).stream())
                .toList();
    }

    private static InputStream resource(String name) {
        return Objects.requireNonNull(Catalog.class.getResourceAsStream("/search/" + name), name);
    }

    private void read(InputStream data, IndexWriter writer) throws Exception {
        XMLInputFactory xml = XMLInputFactory.newFactory();
        xml.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        xml.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        XMLStreamReader r = xml.createXMLStreamReader(data);
        while (r.hasNext()) {
            if (r.next() != XMLStreamConstants.START_ELEMENT) continue;
            if (r.getLocalName().equals("category")) {
                String id = r.getAttributeValue(null, "id"),
                        parent = r.getAttributeValue(null, "parentId");
                if (parent != null) parents.put(id, parent);
                String label = r.getElementText().strip();
                categories.put(id, label);
                put(new Target("category", id, Product.localUrl("category", id)), label);
            } else if (r.getLocalName().equals("offer")) {
                Map<String, String> fields = new HashMap<>();
                Map<String, List<String>> attrs = new LinkedHashMap<>();
                for (int i = 0; i < r.getAttributeCount(); i++)
                    fields.put(r.getAttributeLocalName(i), r.getAttributeValue(i));
                while (r.nextTag() == XMLStreamConstants.START_ELEMENT) {
                    String key = r.getLocalName(), param = r.getAttributeValue(null, "name");
                    String value = readText(r).strip();
                    if (value.isEmpty()) continue;
                    if (key.equals("param") && param != null)
                        attrs.computeIfAbsent(param, k -> new ArrayList<>()).add(value);
                    else fields.put(key, value);
                }
                add(Product.read(fields, attrs), writer);
            }
        }
        r.close();
    }

    private static String readText(XMLStreamReader r) throws XMLStreamException {
        var out = new StringBuilder();
        for (int depth = 1; depth > 0; ) {
            switch (r.next()) {
                case XMLStreamConstants.START_ELEMENT -> {
                    depth++;
                    out.append(' ');
                }
                case XMLStreamConstants.END_ELEMENT -> depth--;
                case XMLStreamConstants.CDATA, XMLStreamConstants.CHARACTERS ->
                        out.append(r.getText());
            }
        }
        return out.toString();
    }

    boolean inCategory(Product p, String id) {
        Set<String> seen = new HashSet<>();
        for (String key = p.category(); key != null && seen.add(key); key = parents.get(key))
            if (id.equals(key)) return true;
        return false;
    }

    private void put(Target t, String label) {
        entries.put(t.key(), new Entry(t, label));
    }

    private void add(Product p, IndexWriter w) throws IOException {
        if (byId.putIfAbsent(p.id(), p) != null) throw new IOException("Повтор offer_id " + p.id());
        if (p.sku() != null)
            bySku.computeIfAbsent(Product.code(p.sku()), k -> new ArrayList<>()).add(p);
        put(new Target("offer", p.id(), p.url()), p.name());
        if (!p.family().isEmpty())
            entries.putIfAbsent(
                    "family/" + p.familyId(),
                    new Entry(
                            new Target(
                                    "family",
                                    p.familyId(),
                                    Product.localUrl("family", p.familyId())),
                            p.name()));
        entries.putIfAbsent(
                "vendor/" + p.vendorId(),
                new Entry(
                        new Target(
                                "vendor", p.vendorId(), Product.localUrl("vendor", p.vendorId())),
                        p.vendor()));
        Document d = new Document();
        d.add(new StoredField("id", p.id()));
        d.add(new TextField("text", p.name() + " " + p.description(), Field.Store.NO));
        d.add(
                new TextField(
                        "name", p.name() + " " + Objects.toString(p.sku(), ""), Field.Store.NO));
        d.add(
                new TextField(
                        "facets",
                        p.vendor()
                                + " "
                                + categories.getOrDefault(p.category(), "")
                                + " "
                                + String.join(
                                        " ",
                                        p.attributes().values().stream()
                                                .flatMap(List::stream)
                                                .toList()),
                        Field.Store.NO));
        w.addDocument(d);
    }

    List<Product> lexical(String text) throws IOException {
        QueryBuilder qb = new QueryBuilder(analyzer);
        var query = new BooleanQuery.Builder();
        for (String field : List.of("name", "facets", "text")) {
            Query q = qb.createBooleanQuery(field, text, BooleanClause.Occur.MUST);
            if (q != null)
                query.add(
                        new BoostQuery(
                                q, field.equals("name") ? 6 : field.equals("facets") ? 2 : 1),
                        BooleanClause.Occur.SHOULD);
        }
        var hits = searcher.search(query.build(), reader.maxDoc());
        var docs = searcher.storedFields();
        List<Product> products = new ArrayList<>(hits.scoreDocs.length);
        for (ScoreDoc hit : hits.scoreDocs)
            products.add(byId.get(docs.document(hit.doc).get("id")));
        return products;
    }

    Target target(String kind, String id) {
        Entry entry = entries.get(kind + "/" + id);
        return entry == null ? null : entry.target;
    }

    public void close() throws IOException {
        reader.close();
        directory.close();
        analyzer.close();
    }
}
