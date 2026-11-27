package ru.codesraider.smartsearch.catalog;

import org.apache.lucene.analysis.ru.RussianAnalyzer;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.FSDirectory;

import ru.codesraider.smartsearch.modelclient.EncoderClient;
import ru.codesraider.smartsearch.storage.Sqlite;

import java.io.BufferedInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

public final class CatalogImporter {
    private static final String RECIPE = "catalog-v1.5";

    private CatalogImporter() {}

    public static void run(String[] args) throws Exception {
        if (args.length > 2 || (args.length == 2 && !"--embeddings".equals(args[1]))) {
            throw new IllegalArgumentException(
                    "Usage: import [--embeddings] (set CATALOG_PATH and optionally SEARCH_SNAPSHOTS_DIR)");
        }
        Path source = Path.of(System.getenv().getOrDefault("CATALOG_PATH", "Mindbox.yml.gz.xml"));
        Path root =
                Path.of(System.getenv().getOrDefault("SEARCH_SNAPSHOTS_DIR",
                                System.getenv().getOrDefault("SNAPSHOTS_DIR", "data/snapshots")))
                        .toAbsolutePath();
        try (var client =
                new EncoderClient(
                        System.getenv().getOrDefault("MODEL_BASE_URL", "http://model:8000"))) {
            importSnapshot(source, root, args.length == 2, client);
        }
    }

    public static void importSnapshot(Path source, Path root, boolean embeddings, EncoderClient client)
            throws Exception {
        Files.createDirectories(root);
        root = root.toRealPath();
        try (var channel =
                        FileChannel.open(
                                root.resolve(".import.lock"),
                                StandardOpenOption.CREATE,
                                StandardOpenOption.WRITE);
                var lock = channel.tryLock()) {
            if (lock == null)
                throw new IOException("Another importer is already building a snapshot");
            String profile = embeddings ? awaitProfile(client) : null;
            String sourceHash;
            try (InputStream in = Files.newInputStream(source)) {
                MessageDigest md = digest();
                new DigestInputStream(in, md).transferTo(java.io.OutputStream.nullOutputStream());
                sourceHash = HexFormat.of().formatHex(md.digest());
            }
            String version =
                    sourceHash.substring(0, 16)
                            + "-"
                            + HexFormat.of()
                                    .formatHex(
                                            digest().digest(
                                                            (RECIPE + ":" + profile)
                                                                    .getBytes(
                                                                            StandardCharsets
                                                                                    .UTF_8)))
                                    .substring(0, 12);
            Path target = root.resolve(version);
            if (Files.notExists(target)) {
                Path staging = Files.createTempDirectory(root, ".building-");
                try {
                    build(source, staging, version, sourceHash, profile, client);
                    Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
                } finally {
                    if (Files.isDirectory(staging, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                            && root.equals(staging.toRealPath().getParent())) {
                        try (var entries = Files.walk(staging)) {
                            for (Path item : entries.sorted(Comparator.reverseOrder()).toList())
                                Files.delete(item);
                        }
                    }
                }
            } else {
                verifySnapshot(target, version, sourceHash, profile);
            }
            Path pointer = Files.createTempFile(root, ".active-", ".tmp");
            try {
                Files.writeString(pointer, version + "\n", StandardCharsets.UTF_8);
                Files.move(
                        pointer,
                        root.resolve("active"),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(pointer);
            }
            System.out.println("Activated catalog snapshot " + version);
        }
    }

    private static String awaitProfile(EncoderClient client)
            throws IOException, InterruptedException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(60).toNanos();
        while (true) {
            try {
                return client.readyProfile();
            } catch (IOException unavailable) {
                if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) {
                    throw new IOException(
                            "Encoder did not reach readiness for vector import", unavailable);
                }
                Thread.sleep(1000);
            }
        }
    }

    private static void verifySnapshot(Path dir, String version, String hash, String profile)
            throws Exception {
        var manifest = FeedOffer.JSON.readTree(dir.resolve("manifest.json").toFile());
        if (!version.equals(manifest.path("index_version").asString())
                || !hash.equals(manifest.path("source_sha256").asString())
                || !java.util.Objects.equals(profile, manifest.path("profile_id").asString(null))
                || !Files.isRegularFile(dir.resolve("catalog.db")))
            throw new IOException("Incomplete snapshot: " + dir);
        long offerCount = manifest.path("offer_count").asLong();
        try (var db = Sqlite.dataSource(dir.resolve("catalog.db"), true).getConnection();
                var stmt = db.createStatement()) {
            if (!Sqlite.checkCatalog(db)) throw new IOException("Catalog integrity check failed: " + version);
            try (var counts = stmt.executeQuery("SELECT count(*) FROM offer")) {
                if (!counts.next() || counts.getLong(1) != offerCount) {
                    throw new IOException("Catalog count does not match manifest: " + version);
                }
            }
        }
        try (var directory = FSDirectory.open(dir.resolve("lucene"));
                var reader = DirectoryReader.open(directory)) {
            var metadata = reader.getIndexCommit().getUserData();
            if (!version.equals(metadata.get("index_version"))
                    || !java.util.Objects.equals(profile, metadata.get("profile_id"))
                    || new IndexSearcher(reader).count(new TermQuery(new Term("doc_type", "offer"))) != offerCount) {
                throw new IOException("Lucene does not match snapshot " + version);
            }
        }
    }

    private static void build(
            Path source,
            Path staging,
            String version,
            String hash,
            String profile,
            EncoderClient client)
            throws Exception {
        var manifest = new LinkedHashMap<String, Object>();
        var md = digest();
        try (var con = Sqlite.dataSource(staging.resolve("catalog.db"), false).getConnection();
                var analyzer = new RussianAnalyzer();
                var lucene = FSDirectory.open(staging.resolve("lucene"));
                var writer = new IndexWriter(lucene, new IndexWriterConfig(analyzer));
                var input = new BufferedInputStream(new DigestInputStream(Files.newInputStream(source), md))) {
            var db = new CatalogDatabase(con);
            var index = new CatalogIndex(writer, profile != null);
            XMLInputFactory factory = XMLInputFactory.newFactory();
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            input.mark(2);
            boolean gzip = input.read() == 0x1f && input.read() == 0x8b;
            input.reset();
            int count = 0;
            try (InputStream decoded = gzip ? new GZIPInputStream(input) : input) {
                var xml = factory.createXMLStreamReader(new FilterInputStream(decoded) {
                    @Override public void close() {} // StAX must leave the stream open for the source hash check.
                });
                try {
                    while (xml.hasNext()) {
                        if (xml.next() != XMLStreamConstants.START_ELEMENT) continue;
                        switch (xml.getLocalName()) {
                            case "yml_catalog" ->
                                    manifest.put("date", xml.getAttributeValue(null, "date"));
                            case "category" -> {
                                String id = xml.getAttributeValue(null, "id");
                                String parent = xml.getAttributeValue(null, "parentId");
                                db.category(id, xml.getElementText().strip(), parent);
                            }
                            case "offer" -> {
                                FeedOffer offer = readOffer(xml);
                                db.offer(offer);
                                index.offer(offer, db.categoryPath(offer.get("categoryId")));
                                if (++count % 25000 == 0)
                                    System.out.println("Indexed " + count + " offers");
                            }
                            default -> {}
                        }
                    }
                } finally {
                    xml.close();
                }
                decoded.transferTo(java.io.OutputStream.nullOutputStream());
                if (gzip) input.transferTo(java.io.OutputStream.nullOutputStream());
            }
            if (count == 0) throw new IOException("YML contains no offers");
            if (!hash.equals(HexFormat.of().formatHex(md.digest())))
                throw new IOException("XML changed during import");
            db.finish();
            index.entities(con);
            int vectors = profile == null ? 0 : index.writeVectors(client, profile);
            if (profile != null && vectors == 0)
                throw new IOException("No indexable semantic documents");
            manifest.put("index_version", version);
            manifest.put("source_sha256", hash);
            manifest.put("schema_version", 1);
            manifest.put("rules_version", RECIPE);
            manifest.put("offer_count", count);
            manifest.put("family_count", db.families.size());
            manifest.put("curated_offer_diagnostics", CuratedOffers.skipped(con, CuratedOffers.configured()));
            manifest.put("vector_count", vectors);
            manifest.put("profile_id", profile);
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("index_version", version);
            metadata.put("card_key_field", "card_key");
            if (profile != null) metadata.put("profile_id", profile);
            writer.setLiveCommitData(metadata.entrySet());
            writer.commit();
        }
        Files.writeString(
                staging.resolve("manifest.json"), FeedOffer.JSON.writeValueAsString(manifest));
        verifySnapshot(staging, version, hash, profile);
        System.out.println(FeedOffer.JSON.writeValueAsString(manifest));
    }

    private static FeedOffer readOffer(XMLStreamReader xml) throws XMLStreamException {
        String available = xml.getAttributeValue(null, "available");
        if (!"true".equalsIgnoreCase(available) && !"false".equalsIgnoreCase(available)) {
            throw new XMLStreamException("Invalid offer availability for " + xml.getAttributeValue(null, "id"));
        }
        var offer =
                new FeedOffer(
                        xml.getAttributeValue(null, "id"),
                        xml.getAttributeValue(null, "group_id"),
                        "true".equalsIgnoreCase(available));
        while (xml.hasNext()) {
            int event = xml.next();
            if (event == XMLStreamConstants.END_ELEMENT && "offer".equals(xml.getLocalName()))
                break;
            if (event != XMLStreamConstants.START_ELEMENT) continue;
            String tag = xml.getLocalName();
            if ("param".equals(tag)) {
                String name = xml.getAttributeValue(null, "name");
                offer.params.add(new FeedOffer.Param(name, xml.getElementText().strip()));
            } else {
                switch (tag) {
                    case "url",
                            "price",
                            "oldprice",
                            "currencyId",
                            "categoryId",
                            "name",
                            "vendor",
                            "vendorUrl",
                            "vendorCode",
                            "description",
                            "group_id",
                            "sales_notes" -> {
                        String value = FeedOffer.empty(xml.getElementText());
                        if (value != null) offer.fields.put(tag, value);
                    }
                    default -> {
                        int depth = 1;
                        while (depth > 0) {
                            int nested = xml.next();
                            if (nested == XMLStreamConstants.START_ELEMENT) depth++;
                            if (nested == XMLStreamConstants.END_ELEMENT) depth--;
                        }
                    }
                }
            }
        }
        offer.finish();
        return offer;
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
