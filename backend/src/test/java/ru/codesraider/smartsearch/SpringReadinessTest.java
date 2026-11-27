package ru.codesraider.smartsearch;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import ru.codesraider.smartsearch.catalog.CatalogImporter;
import ru.codesraider.smartsearch.modelclient.EncoderClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

class SpringReadinessTest {
    private static final String MODEL_OFFLINE = "http://127.0.0.1:1";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @Test
    void missingCatalogFailsReadinessWhileSpringRemainsLive(@TempDir Path temp) throws Exception {
        try (var app = start(temp.resolve("snapshots"), temp.resolve("events.db"))) {
            assertReadiness(app, false);
            assertEquals("CATALOG_UNAVAILABLE", search(app, 503).required("code").asString());
        }
    }

    @Test
    void springBecomesReadyWhenTheJournalRecovers(@TempDir Path temp) throws Exception {
        Path snapshots = temp.resolve("snapshots");
        try (var encoder = new EncoderClient(MODEL_OFFLINE)) {
            var feed = Path.of(getClass().getResource("/search/demo.xml").toURI());
            CatalogImporter.importSnapshot(feed, snapshots, false, encoder);
        }
        Path journal = Files.createDirectory(temp.resolve("events.db"));
        try (var app = start(snapshots, journal)) {
            assertReadiness(app, false);
            assertEquals("JOURNAL_UNAVAILABLE", search(app, 503).required("code").asString());

            Files.delete(journal);

            assertReadiness(app, true);
            assertTrue(Files.isRegularFile(journal));
            assertEquals(0, request(app, "/v1/analytics?from=2000-01-01T00:00:00Z&to=2100-01-01T00:00:00Z",
                    null, 200).at("/zero_result_rate/denominator").asLong());
            assertEquals("473582", search(app, 200).at("/top4/0/offer_id").asString());
        }
    }

    private static ConfigurableApplicationContext start(Path snapshots, Path journal) {
        return new SpringApplicationBuilder(SmartSearchApplication.class).run(
                "--server.address=127.0.0.1", "--server.port=0",
                "--search.snapshots-dir=" + snapshots, "--search.events-db=" + journal,
                "--MODEL_BASE_URL=" + MODEL_OFFLINE, "--SEMANTIC_MODE=lexical");
    }

    private static void assertReadiness(ConfigurableApplicationContext app, boolean ready) throws Exception {
        assertEquals(ready ? "UP" : "DOWN",
                request(app, "/actuator/health/readiness", null, ready ? 200 : 503).required("status").asString());
        assertEquals("UP", request(app, "/actuator/health/liveness", null, 200).required("status").asString());
        var publicHealth = request(app, "/health", null, 200);
        assertEquals(ready, publicHealth.required("search_ready").asBoolean());
        assertEquals("unavailable", publicHealth.required("model_service").asString());
    }

    private static JsonNode search(ConfigurableApplicationContext app, int status) throws Exception {
        return request(app, "/v1/search", "{\"q\":\"EP2-06605\"}", status);
    }

    private static JsonNode request(ConfigurableApplicationContext app, String path, String body, int status)
            throws Exception {
        int port = ((WebServerApplicationContext) app).getWebServer().getPort();
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10));
        if (body != null) request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        var response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(status, response.statusCode(), () -> path + " returned " + response.body());
        return JSON.readTree(response.body());
    }
}
