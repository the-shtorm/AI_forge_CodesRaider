package ru.codesraider.smartsearch.search;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.lucene.analysis.ru.RussianAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

class SearchIndexTest {
    @TempDir Path root;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static float[] unit(int axis) {
        var vector = new float[384];
        vector[axis] = 1f;
        return vector;
    }

    private void snapshot() throws Exception {
        Path version = Files.createDirectory(root.resolve("v1"));
        Files.writeString(root.resolve("active"), "v1");
        Files.writeString(version.resolve("manifest.json"),
                "{\"index_version\":\"v1\",\"profile_id\":\"profile-a\",\"vector_count\":2}");
        try (var conn = DriverManager.getConnection("jdbc:sqlite:" + version.resolve("catalog.db"));
             var statement = conn.createStatement()) {
            statement.execute("CREATE TABLE offer(family_key TEXT, vendor_id TEXT, category_id TEXT)");
            statement.execute("INSERT INTO offer VALUES ('backup','v1','cat1'),('printer','v2','cat2')");
        }
        try (var dir = FSDirectory.open(version.resolve("lucene")); var analyzer = new RussianAnalyzer();
             var writer = new IndexWriter(dir, new IndexWriterConfig(analyzer))) {
            addFamily(writer, "backup", "резервное копирование", "v1", "cat1", 0);
            addFamily(writer, "printer", "лазерный принтер", "v2", "cat2", 1);
            for (String type : List.of("entity", "entity", "offer")) {
                var doc = new Document();
                doc.add(new StringField("doc_type", type, Field.Store.NO));
                doc.add(new StringField("name_norm", "резервное копирование", Field.Store.NO));
                doc.add(new StringField("entity_key", type + "/backup", Field.Store.YES));
                writer.addDocument(doc);
            }
            writer.setLiveCommitData(Map.of("index_version","v1","profile_id","profile-a").entrySet());
        }
    }

    private void addFamily(IndexWriter writer, String family, String title, String vendor,
                           String category, int axis) throws IOException {
        Document offer = new Document();
        offer.add(new StringField("doc_type", "offer", Field.Store.NO));
        offer.add(new StringField("vendor_id", vendor, Field.Store.NO));
        offer.add(new StringField("category_id", category, Field.Store.NO));
        offer.add(new StringField("family_key", family, Field.Store.YES));
        offer.add(new SortedDocValuesField("family_key", new BytesRef(family)));
        offer.add(new TextField("name", title, Field.Store.NO));
        writer.addDocument(offer);
        Document vector = new Document();
        vector.add(new StringField("doc_type", "vector", Field.Store.NO));
        vector.add(new StringField("family_key", family, Field.Store.YES));
        vector.add(new KnnFloatVectorField("vector", unit(axis), VectorSimilarityFunction.DOT_PRODUCT));
        writer.addDocument(vector);
    }

    private static void json(HttpExchange exchange, int status, Object value) throws IOException {
        try (exchange) {
            var bytes = JSON.writeValueAsBytes(value);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    @Test
    void stillReadsMasterSnapshotsWithLegacyLuceneFields() throws Exception {
        Path version = Files.createDirectory(root.resolve("legacy"));
        Files.writeString(root.resolve("active"), "legacy");
        Files.writeString(version.resolve("manifest.json"),
                "{\"index_version\":\"legacy\",\"offer_count\":1,\"vector_count\":0}");
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + version.resolve("catalog.db"));
             var sql = db.createStatement()) {
            sql.execute("CREATE TABLE offer(family_key TEXT)");
            sql.execute("INSERT INTO offer VALUES ('family/9')");
        }
        try (var dir = FSDirectory.open(version.resolve("lucene")); var analyzer = new RussianAnalyzer();
             var writer = new IndexWriter(dir, new IndexWriterConfig(analyzer))) {
            var offer = new Document();
            offer.add(new StringField("type", "offer", Field.Store.NO));
            offer.add(new StringField("tier", "primary", Field.Store.NO));
            offer.add(new StringField("family_key", "family/9", Field.Store.YES));
            offer.add(new TextField("family", "редактор", Field.Store.NO));
            writer.addDocument(offer);
            var entity = new Document();
            entity.add(new StringField("type", "entity", Field.Store.NO));
            entity.add(new StringField("prefix", "редактор", Field.Store.NO));
            entity.add(new StringField("key", "family/9", Field.Store.YES));
            writer.addDocument(entity);
            writer.setLiveCommitData(Map.of("index_version", "legacy").entrySet());
        }
        try (var index = new SearchIndex(root.toString(), "http://127.0.0.1:1", "auto", 10)) {
            index.open();
            assertTrue(index.ready());
            assertFalse(index.semanticReady());
            assertEquals(new SearchIndex.Ranked(List.of("family/9"), "lexical"), index.rank("редактор", null, null, true));
            assertEquals(List.of("family/9"), index.suggestNames("ред"));
        }
    }

    @Test
    void knnSearchHonorsScopeAndFallsBackToBm25WhenEncoderCannotAnswer() throws Exception {
        snapshot();
        var outcome = new AtomicReference<>("ok");
        var calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ready", ex -> json(ex, 200,
                Map.of("ready", true, "dimension", 384, "profile_id", "profile-a")));
        server.createContext("/internal/v1/embeddings", ex -> {
            calls.incrementAndGet();
            var input = JSON.readTree(ex.getRequestBody());
            if (!"query".equals(input.path("input_type").asString()) || input.path("texts").size() != 1
                    || !"profile-a".equals(input.path("profile_id").asString())) {
                json(ex, 422, Map.of());
                return;
            }
            String behavior = outcome.get();
            if ("timeout".equals(behavior)) {
                try {
                    Thread.sleep(1200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            var reply = new LinkedHashMap<String,Object>();
            reply.put("profile_id", "mismatch".equals(behavior) ? "profile-b" : "profile-a");
            reply.put("dimension", 384);
            reply.put("vectors", List.of("invalid".equals(behavior) ? new float[384] : unit(0)));
            json(ex, "busy".equals(behavior) ? 503 : 200, reply);
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setExecutor(executor);
            server.start();
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            try (var index = new SearchIndex(root.toString(), base, "auto", 500)) {
                index.open();
                assertTrue(index.ready());
                assertEquals(List.of("entity/backup"), index.suggestNames("  РЕЗЕРВ"));
                assertTrue(index.suggestNames("неизвестная подборка").isEmpty());
                assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
                    while (!index.semanticReady()) Thread.sleep(20);
                });
                // No token from the input occurs in either document.
                var semantic = index.rank("утрачены файлы", null, null, true);
                assertEquals("hybrid", semantic.mode());
                assertEquals("backup", semantic.families().getFirst());
                assertEquals(List.of("printer"), index.rank("утрачены файлы", "v2", "cat2", true).families());
                assertTrue(index.rank("утрачены файлы", "v1", "cat2", true).families().isEmpty());
                int before = calls.get();
                assertEquals(List.of("printer"), index.rank("принтер", null, null, false).families());
                assertEquals(before, calls.get(), "Lexical-only intent must bypass the model");

                for (String failure : List.of("mismatch", "invalid", "busy", "timeout")) {
                    outcome.set(failure);
                    var degraded = index.rank("принтер", null, null, true);
                    assertEquals("lexical", degraded.mode(), failure);
                    assertEquals(List.of("printer"), degraded.families(), failure);
                }
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void duplicateFamiliesReceiveOneReciprocalRankContributionPerList() {
        var actual = SearchIndex.fuse(List.of("a", "a", "b"), List.of("b","c"));
        assertEquals(List.of("b", "a", "c"), actual);
    }
}
