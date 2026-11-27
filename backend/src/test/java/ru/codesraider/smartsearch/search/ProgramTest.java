package ru.codesraider.smartsearch.search;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import ru.codesraider.smartsearch.catalog.CatalogImporter;
import ru.codesraider.smartsearch.analytics.EventStore;
import ru.codesraider.smartsearch.modelclient.EncoderClient;
import ru.codesraider.smartsearch.web.ApiProblem;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.*;

class ProgramTest {
    @TempDir Path temp;
    SearchIndex index;
    HttpServer endpoint;
    SearchController search;
    private volatile String instruction;
    int calls;
    final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void start() throws Exception {
        Path snapshots = temp.resolve("snapshots");
        Path demo = Path.of(getClass().getResource("/search/demo.xml").toURI());
        try (var encoder = new EncoderClient("http://127.0.0.1:1")) {
            CatalogImporter.importSnapshot(demo, snapshots, false, encoder);
        }
        index = new SearchIndex(snapshots.toString(), "http://127.0.0.1:1", "lexical", 10);
        index.open();
        endpoint = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        endpoint.createContext(
                "/chat",
                exchange -> {
                    calls++;
                    var payload = json.readTree(exchange.getRequestBody());
                    assertTrue(payload.has("response_format"));
                    byte[] body =
                            json.writeValueAsBytes(
                                    Map.of(
                                            "choices",
                                            List.of(
                                                    Map.of(
                                                            "message",
                                                            Map.of("content", instruction)))));
                    exchange.sendResponseHeaders(200, body.length);
                    try (var out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                });
        endpoint.start();
        var model =
                new ModelClient(
                        "http://127.0.0.1:" + endpoint.getAddress().getPort() + "/chat",
                        "test",
                        "",
                        1000);
        search =
                new SearchController(index, new EventStore(temp.resolve("events.db").toString(), json), model);
    }

    @AfterEach
    void close() throws Exception {
        endpoint.stop(0);
        index.close();
    }

    JsonNode run(String q, Map<String, Object> filters) throws Exception {
        return json.valueToTree(
                search.search(Map.of("q", q, "filters", filters, "limit", 8)));
    }

    @Test
    void exactCartridgeWithVendorIsAnOfferAndNotAPrinter() throws Exception {
        var result = run("Картридж Pantum PC-211P", Map.of());
        assertEquals("526683", result.at("/top4/0/offer_id").asString());
        assertEquals("526683", result.at("/primary_target/id").asString());
        assertEquals(0, calls);
    }

    @Test
    void modelBuildsAUnionOverTwoCatalogIndexes() throws Exception {
        instruction =
                """
                {"find":["REMOTE_ACCESS","WEBINAR"],"join":"any"}
                """;
        var response = run("Хочу подключаться к компьютеру или проводить встречи", Map.of());
        assertEquals("llm", response.at("/program/compiler").asString());
        assertEquals(5, response.get("direct_count").asInt());
        assertEquals(1, calls);
        var replay = run("Хочу подключаться к компьютеру или проводить встречи", Map.of());
        assertEquals(response.get("top4"), replay.get("top4"));
        var narrowed =
                run(
                        "Хочу подключаться к компьютеру или проводить встречи",
                        Map.of("vendor_id", "anydesk"));
        assertEquals(1, narrowed.get("direct_count").asInt());
        assertEquals("demo-any", narrowed.at("/top4/0/offer_id").asString());
    }

    @Test
    void intersectionRequiresBothCapabilitiesOnSameSku() throws Exception {
        instruction = "{\"find\":[\"BACKUP\",\"ANTIVIRUS\"],\"join\":\"all\"}";
        assertEquals(
                0,
                run("Хочу сохранить копию и защитить компьютер", Map.of())
                        .get("direct_count")
                        .asInt());
    }

    @Test
    void malformedAndOutOfVocabularyInstructionsFallBack() throws Exception {
        for (String reply :
                List.of(
                        "invalid",
                        "{\"find\":[\"invented\"],\"join\":\"all\"}",
                        "{\"find\":[\"BACKUP\"],\"join\":\"all\",\"new_price\":0}")) {
            instruction = reply;
            var response = run("Хочу открыть документы из дома", Map.of());
            assertEquals("llm_fallback", response.at("/program/compiler").asString());
            assertTrue(response.get("top4").isEmpty());
        }
    }

    @Test
    void exactAndUnsupportedNegationsNeverInvokeModel() throws Exception {
        assertEquals("473582", run("ep2‑06605", Map.of()).at("/primary_target/id").asString());
        assertTrue(run("сервер без Windows", Map.of()).get("top4").isEmpty());
        assertEquals(0, calls);
    }

    @Test
    void budgetAndRequirementsAreVerifiedTogether() throws Exception {
        var response =
                run(
                        "продление Kaspersky Small Office Security до 450,00 рублей",
                        Map.of("term_months", 12));
        assertEquals("demo-ks-ren", response.at("/top4/0/offer_id").asString());
        assertEquals(45000, response.at("/applied_filters/max_price_minor").asInt());
        assertTrue(
                run("продление Kaspersky Small Office Security до 449,99 рублей", Map.of())
                        .get("top4")
                        .isEmpty());
        assertTrue(
                run("Kaspersky Small Office Security на 10 ПК", Map.of("license_type", "new"))
                        .get("top4")
                        .isEmpty());
        assertTrue(run("AnyDesk до 1 000 рублей", Map.of()).get("top4").isEmpty());
    }

    @Test
    void purchaseVerbResolvesItsObjectWithoutDroppingRestrictions() throws Exception {
        assertEquals("473582", run("Мы планируем заказать EP2-06605", Map.of()).at("/primary_target/id").asString());
        assertEquals("526683", run("Хочу приобрести PC-211P", Map.of()).at("/primary_target/id").asString());
        assertEquals(0, run("Мы не хотим купить EP2-06605", Map.of()).get("direct_count").asInt());
        assertEquals(0, run("Хочу купить EP2-06605", Map.of("platform", "macOS")).get("direct_count").asInt());
    }

    @Test
    void knownPrinterStillRequiresTheRequestedFeatures() throws Exception {
        assertEquals(1, run("Монохромный лазерный принтер A4 Pantum P2500W", Map.of()).get("direct_count").asInt());
        assertEquals(0, run("Цветной принтер A3 Pantum P2500W", Map.of()).get("direct_count").asInt());
        assertEquals(0, run("Принтер Pantum P2500W без Windows", Map.of()).get("direct_count").asInt());
    }

    @Test
    void explanationOnlyUsesAShownCardAndApprovedEvidence() throws Exception {
        var response = run("EP2-06605", Map.of());
        String id = response.get("search_id").asString();
        assertThrows(ApiProblem.class, () -> search.explain(Map.of("search_id", id, "offer_id", "363882")));
        instruction = "{\"op\":\"availability\"}";
        var explanation = search.explain(Map.of("search_id", id, "offer_id", "473582"));
        assertEquals("llm", explanation.get("source"));
        instruction = "{\"op\":\"бесплатно\"}";
        var fallback = search.explain(Map.of("search_id", id, "offer_id", "473582"));
        assertEquals("template", fallback.get("source"));
        assertFalse(fallback.get("text").toString().contains("бесплатно"));
        assertEquals(2, calls);
    }
}
