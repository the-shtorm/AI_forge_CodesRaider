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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

class ProgramTest {
    @TempDir Path temp;
    SearchIndex index;
    HttpServer endpoint;
    SearchController search;
    ModelClient model;
    private volatile String instruction;
    int calls;
    java.util.function.Function<JsonNode, String> reply;
    final List<Double> temperatures = new java.util.concurrent.CopyOnWriteArrayList<>();
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
                    assertEquals(payload.path("max_tokens").asInt() == 80, payload.has("response_format"));
                    temperatures.add(payload.get("temperature").asDouble());
                    byte[] body =
                            json.writeValueAsBytes(
                                    Map.of(
                                            "choices",
                                            List.of(
                                                    Map.of(
                                                            "message",
                                                            Map.of("content", reply == null ? instruction : reply.apply(payload))))));
                    exchange.sendResponseHeaders(200, body.length);
                    try (var out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                });
        endpoint.start();
        model =
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
    void emptyTextSearchUsesOnlyTheConfirmedOperationAndKeepsSkuFilters() throws Exception {
        String q = "Сохранить содержимое базы";
        instruction = "Yes.";
        var hit = run(q, Map.of());
        assertEquals("llm_paraphrase", hit.at("/program/compiler").asString());
        assertEquals("BACKUP", hit.at("/program/purpose").asString());
        assertTrue(hit.get("llm_applied").asBoolean());
        assertTrue(hit.get("direct_count").asInt() > 0);
        assertEquals("1033", hit.at("/facets/categories/0/id").asString());

        var filtered = run(q + ".", Map.of("vendor_id", "pantum"));
        assertEquals("BACKUP", filtered.at("/program/purpose").asString());
        assertEquals(0, filtered.get("direct_count").asInt());
        assertTrue(filtered.at("/facets/vendors").size() > 0);
        assertEquals(2, calls);
    }

    @Test
    void reinterpretsOnlyUnusableOrOffTopicTextMatches() throws Exception {
        Path feed = temp.resolve("books-and-editors.xml"), snapshots = temp.resolve("books-and-editors");
        Files.writeString(feed, """
                <yml_catalog><shop><categories>
                  <category id="10">Руководства</category><category id="495">Видеоредакторы</category>
                </categories><offers>
                  <offer id="11" group_id="11" available="false"><name>Руководство: как склеить видеофрагменты</name>
                    <url>https://example.org/p/11/</url><vendor>Studio</vendor><vendorUrl>https://example.org/studio/</vendorUrl>
                    <categoryId>10</categoryId><price>100</price><currencyId>RUR</currencyId></offer>
                  <offer id="22" group_id="22" available="true"><name>Studio Video Editor Pro</name><description>Наложить титры на ролик</description>
                    <url>https://example.org/p/22/</url><vendor>Studio</vendor><vendorUrl>https://example.org/studio/</vendorUrl>
                    <categoryId>495</categoryId><price>200</price><currencyId>RUR</currencyId></offer>
                </offers></shop></yml_catalog>
                """);
        String offline = "http://127.0.0.1:1";
        try (var encoder = new EncoderClient(offline); var bookIndex = new SearchIndex(snapshots.toString(), offline, "lexical", 10)) {
            CatalogImporter.importSnapshot(feed, snapshots, false, encoder);
            bookIndex.open();
            var controller = new SearchController(bookIndex, new EventStore(temp.resolve("books.db").toString(), json), model);
            String query = "Склеить видеофрагменты";
            instruction = "{\"tool\":\"unknown\"}";
            var unfiltered = json.valueToTree(controller.search(Map.of("q", query)));
            assertEquals("11", unfiltered.at("/top4/0/offer_id").asString());
            assertFalse(unfiltered.get("llm_applied").asBoolean());
            instruction = "{\"tool\":\"video editing software\"}";
            var corrected = json.valueToTree(controller.search(Map.of("q", query)));
            assertEquals("llm_rewrite", corrected.at("/program/compiler").asString());
            assertEquals("22", corrected.at("/top4/0/offer_id").asString());
            assertEquals("495", corrected.at("/facets/categories/0/id").asString());
            var scoped = json.valueToTree(controller.search(Map.of("q", query, "filters", Map.of("category_id", "10"))));
            assertEquals("11", scoped.at("/top4/0/offer_id").asString());
            assertFalse(scoped.get("llm_applied").asBoolean());
            assertEquals(3, calls);
            instruction = "Yes.";
            var filtered = json.valueToTree(controller.search(Map.of("q", query, "filters", Map.of("available_only", true))));
            assertEquals("llm_paraphrase", filtered.at("/program/compiler").asString());
            assertEquals(1, filtered.get("direct_count").asInt());
            assertEquals("22", filtered.at("/top4/0/offer_id").asString());
            assertTrue(filtered.at("/top4/0/available").asBoolean());
            assertEquals("495", filtered.at("/facets/categories/0/id").asString());
            assertEquals(4, calls);
            var suitable = json.valueToTree(controller.search(Map.of("q", "Наложить титры на ролик")));
            assertEquals("22", suitable.at("/top4/0/offer_id").asString());
            assertFalse(suitable.get("llm_applied").asBoolean());
            assertEquals(4, calls);

            // The low-temperature pass returns the same literal book twice. The warmer tool
            // proposal must actually execute a new plan and rebuild facets for the verified editor.
            reply = payload -> {
                if (!payload.has("response_format")) return "yes";
                return payload.get("temperature").asDouble() > 0
                        ? "{\"tool\":\"video editor\",\"category\":\"Видеоредакторы\"}"
                        : "{\"tool\":\"unknown\"}";
            };
            var request = Map.<String, Object>of("q", query, "strategy", "llm", "session_id", "narrow");
            for (int i = 0; i < 2; i++)
                assertEquals("11", json.valueToTree(controller.search(request)).at("/top4/0/offer_id").asString());
            var retried = json.valueToTree(controller.search(request));
            assertEquals("22", retried.at("/top4/0/offer_id").asString());
            assertEquals("llm_chat", retried.at("/program/compiler").asString());
            assertEquals("NEXT_MATCH", retried.at("/program/order").asString());
            assertEquals("495", retried.at("/facets/categories/0/id").asString());
        }
    }

    @Test
    void videoTasksSelectStandaloneEditorsAndCountOnlyEligibleFacets() throws Exception {
        var xml = new StringBuilder("<yml_catalog><shop><categories><category id='495'>Видеоредакторы</category>"
                + "</categories><offers>");
        record Offer(String vendor, String name, String license) {}
        var offers = List.of(new Offer("studio", "Studio Video Editor Pro", "полная версия"),
                new Offer("studio", "Lens Effects", "модуль"),
                new Offer("studio", "Subtitle plug-in for Studio Video Editor", ""),
                new Offer("corel", "Corel Pinnacle Studio 26 (лицензия), Standard", "полная версия"),
                new Offer("wondershare", "Wondershare Filmora (Annual Plan), Boris FX", "модуль"));
        for (int i = 0; i < offers.size(); i++) {
            Offer offer = offers.get(i);
            xml.append("""
                    <offer id="%1$d" group_id="%1$d" available="true"><name>%2$s</name>
                    <url>https://example.org/p/%1$d/</url><vendor>%3$s</vendor>
                    <vendorUrl>https://example.org/%3$s/</vendorUrl><categoryId>495</categoryId>
                    <price>100</price><currencyId>RUR</currencyId><param name="Тип лицензии">%4$s</param></offer>
                    """.formatted(i + 1, offer.name(), offer.vendor(), offer.license()));
        }
        Path feed = temp.resolve("editors.xml"), snapshots = temp.resolve("media");
        Files.writeString(feed, xml.append("</offers></shop></yml_catalog>"));
        try (var encoder = new EncoderClient("http://127.0.0.1:1")) {
            CatalogImporter.importSnapshot(feed, snapshots, false, encoder);
        }
        String version = Files.readString(snapshots.resolve("active")).strip();
        try (var db = new SearchCatalog(snapshots.resolve(version).resolve("catalog.db"))) {
            for (String compiler : List.of("rules", "llm_rewrite")) {
                var plan = new QueryPlan(compiler, QueryPlan.Purpose.VIDEO_EDITOR, "ролики", List.of(), "all");
                var selection = new SearchCatalog.Selection(null, null, false, false, false, false, plan, new Requirements());
                assertEquals(List.of("1", "4"), db.representatives(Map.of(), selection, null).stream()
                        .map(row -> row.str("offer_id")).sorted().toList());
                assertEquals(2, db.facet("category_id", Map.of(), selection, null).getFirst().number("count"));
                assertEquals(List.of("4"), db.representatives(Map.of("vendor_id", "corel"), selection, null)
                        .stream().map(row -> row.str("offer_id")).toList());
            }
        }
    }

    @Test
    void industrialUpsIsLowerPriorityForAnInferredSinglePcLoad() {
        for (String name : List.of("ИБП 30000Вт 30000ВА", "ИБП 30 kVA", "UPS 10 000 ВA"))
            assertTrue(TaskInterpreter.highCapacityUps(name), name);
        for (String name : List.of("APC 800VA", "ИБП 1,5 кВА", "BX1600MI", "ИБП 20000 Вт"))
            assertFalse(TaskInterpreter.highCapacityUps(name), name);
        for (String q : List.of("автономное питание пк", "питать компьютер при аварии"))
            assertTrue(TaskInterpreter.wantsDesktopUps(new QueryPlan("llm_paraphrase", QueryPlan.Purpose.UPS, q, List.of(), "all")));
        assertFalse(TaskInterpreter.wantsDesktopUps(new QueryPlan("llm_rewrite", QueryPlan.Purpose.UPS,
                "запитать пк и серверную стойку", List.of(), "all")));
    }

    @Test
    void openEndedTranslationMustNameAKnownToolForTheOriginalSubject() throws Exception {
        String q = "Подстраховать базу на всякий случай";
        instruction = "{\"tool\":\"Backup software\"}";
        var result = run(q, Map.of());
        assertEquals("llm_rewrite", result.at("/program/compiler").asString());
        assertEquals("BACKUP", result.at("/program/purpose").asString());
        assertTrue(result.get("llm_applied").asBoolean());
        for (String reply : List.of("printer", "backup software please", "unknown")) {
            instruction = "{\"tool\":\"" + reply + "\"}";
            assertEquals(0, run(q, Map.of()).get("direct_count").asInt());
        }
    }

    @Test
    void rejectedAndMalformedParaphrasesKeepTheOriginalResult() throws Exception {
        for (String reply : List.of("no", "yes, if you buy something else", "invalid")) {
            instruction = reply;
            var result = run("Сохранить содержимое базы", Map.of());
            assertEquals("WORDS", result.at("/program/purpose").asString());
            assertFalse(result.get("llm_applied").asBoolean());
            assertEquals(0, result.get("direct_count").asInt());
        }
        assertEquals(3, calls);
        run("Склеить видеофрагменты", Map.of()); // Demo snapshot has no video editors.
        run("Сохранить содержимое базы без шифрования", Map.of());
        run("Купить питание для ребенка", Map.of());
        assertEquals(3, calls);
    }

    @Test
    void explicitTaskBeatsAnIncidentalServerAndPreservesRestrictions() throws Exception {
        String query = "Делать запасной экземпляр содержимого сервера по расписанию";
        var found = run(query, Map.of());
        assertEquals("BACKUP", found.at("/program/purpose").asString());
        assertEquals("task_rules", found.at("/program/compiler").asString());
        assertFalse(found.get("llm_applied").asBoolean());
        assertTrue(found.get("direct_count").asInt() > 0);
        assertEquals(0, run(query, Map.of("vendor_id", "pantum")).get("direct_count").asInt());
        assertEquals("BACKUP", run("Дубликат документов на случай сбоя", Map.of()).at("/program/purpose").asString());
        assertEquals(0, calls);
    }

    @Test
    void manualEntryIsAnOcrGoalOnlyWhenTheRestOfTheTaskSupportsIt() throws Exception {
        String query = "Перенести текст со снимка без ручного набора";
        try (var db = new SearchCatalog(index.catalogPath())) {
            var ocr = QueryPlan.compile(query.toLowerCase(Locale.ROOT), Set.of(), db, null);
            assertEquals(QueryPlan.Purpose.OCR, ocr.purpose());
            assertEquals(QueryPlan.Purpose.UNKNOWN,
                    QueryPlan.compile(query.toLowerCase(Locale.ROOT) + " без шифрования", Set.of(), db, null).purpose());
        }
        assertFalse(TaskGoals.matches(QueryPlan.Purpose.ARCHIVER, "извлечь архивную справку"));
    }

    @Test
    void askingForSuppliesOrAServiceDoesNotSelectATool() throws Exception {
        for (String query : List.of("Купить фотобумагу для печати портрета",
                "Заказать нотариальную копию документа", "Записаться на фотосессию с размытым фоном")) {
            var result = run(query, Map.of());
            assertEquals("WORDS", result.at("/program/purpose").asString());
            assertFalse(result.get("llm_applied").asBoolean());
            assertEquals(0, result.get("direct_count").asInt());
        }
        assertEquals(0, calls);
    }

    @Test
    void shortTyposShareSearchAndSuggestDestinations() throws Exception {
        assertEquals("121231", run("asra", Map.of()).at("/primary_target/id").asString());
        assertEquals("121231", search.suggest("asra", null, null).getFirst().get("target_id"));
        assertEquals(run("сервер", Map.of()).at("/top4/0/offer_id"), run("sever", Map.of()).at("/top4/0/offer_id"));
        assertEquals(0, run("asra", Map.of("vendor_id", "pantum")).get("direct_count").asInt());
        assertNull(QueryPlan.missingLetter("asra", Set.of("astra linux", "astra cloud")));
        assertNull(QueryPlan.missingLetter("asr4", Set.of("astra linux")));
        assertEquals(0, calls);
    }

    @Test
    void strongClausesCompileWithoutModelAndKeepIntersectionStrict() throws Exception {
        var union = run("Нужны вебинары или удаленный доступ", Map.of());
        assertEquals("rules", union.at("/program/compiler").asString());
        assertEquals("any", union.at("/program/join").asString());
        assertEquals(5, union.get("direct_count").asInt());
        var intersection = run("Нужны вебинары и удаленный доступ", Map.of());
        assertEquals("all", intersection.at("/program/join").asString());
        assertTrue(intersection.get("top4").isEmpty());
        assertEquals(0, calls);
    }

    @Test
    void fuzzyNamesOnlyChangeLetters() {
        assertEquals("astra linux", QueryPlan.canonical("astra-linux", Set.of("astra linux")));
        assertTrue(CatalogNames.oneEdit("suite 10", "sutie 10", true));
        assertFalse(CatalogNames.oneEdit("suite 10", "suite 11", true));
        assertFalse(CatalogNames.oneEdit("suite 10", "suite 1", true));
        assertFalse(CatalogNames.oneEdit("suite-10", "suite 10", true));
    }

    @Test
    void exactCartridgeWithVendorIsAnOfferAndNotAPrinter() throws Exception {
        var result = run("Картридж Pantum PC-211P", Map.of());
        assertEquals("526683", result.at("/top4/0/offer_id").asString());
        assertEquals("526683", result.at("/primary_target/id").asString());
        assertEquals(0, calls);
    }

    @Test
    void chatCompilesAClearTaskAndKeepsAudienceConstraint() throws Exception {
        var response = json.valueToTree(search.search(Map.of(
                "q", "Как провести онлайн-семинар на 40 участников?", "strategy", "llm")));
        assertEquals("rules", response.at("/program/compiler").asString());
        assertFalse(response.get("llm_applied").asBoolean());
        assertEquals(0, calls);
        assertEquals(0, response.get("direct_count").asInt()); // No demo SKU proves capacity for 40.
    }

    private JsonNode chat(String session, String question, List<Map<String, String>> history) throws Exception {
        return json.valueToTree(search.search(Map.of("q", question, "session_id", session,
                "strategy", "llm", "assistant_history", history)));
    }

    @Test
    void thirdIdenticalVisibleSetDiversifiesOnlyWithinThisConversation() throws Exception {
        instruction = "{\"op\":\"NEXT_MATCH\"}";
        var first = chat("alice", "сервер", List.of());
        assertTrue(first.get("direct_count").asInt() > 3);
        var second = chat("alice", "сервер", List.of());
        assertEquals(first.get("top4"), second.get("top4"));
        var bob = chat("bob", "сервер", List.of());
        assertEquals(first.get("top4"), bob.get("top4"));
        assertTrue(temperatures.isEmpty());
        var history = List.of(Map.of("role", "user", "text", "сервер"));
        var third = chat("alice", "Покажи ещё", history);
        assertNotEquals(first.at("/top4/0/offer_id"), third.at("/top4/0/offer_id"));
        assertEquals(first.get("direct_count"), third.get("direct_count"));
        assertEquals(first.get("facets"), third.get("facets"));
        assertEquals(first.get("applied_filters"), third.get("applied_filters"));
        assertEquals(List.of(0.4), temperatures);
        assertEquals("NEXT_MATCH", third.at("/program/order").asString());
        assertTrue(third.get("llm_applied").asBoolean());
        var fourth = chat("alice", "сервер", List.of());
        assertEquals(first.get("top4"), fourth.get("top4"));
        assertEquals(1, calls);
    }

    @Test
    void exactOfferStaysFixedAndEmptyTurnBreaksTheStreak() throws Exception {
        String sku = "EP2-06605";
        var first = chat("alice", sku, List.of());
        chat("alice", sku, List.of());
        var unknown = chat("alice", "Нужно что-нибудь для работы", List.of());
        assertEquals("UNKNOWN", unknown.at("/program/purpose").asString());
        assertEquals(AssistantTurn.CLARIFY, unknown.get("assistant_message").asString());
        assertTrue(unknown.get("top4").isEmpty());
        assertTrue(unknown.get("upsell").valueStream().allMatch(card ->
                "curated_fallback".equals(card.path("recommendation_kind").asString())));
        assertEquals(first.get("assistant_message"), chat("alice", sku, List.of()).get("assistant_message"));
        chat("alice", sku, List.of());
        var third = chat("alice", sku, List.of());
        assertEquals(first.get("top4"), third.get("top4"));
        assertTrue(third.get("assistant_message").asString().contains("Других совпадений"));
        assertEquals(0, calls);
    }

    @Test
    void repeatWindowIncludesExactlyTwoMinutesAndCannotCrossSnapshots() throws Exception {
        var first = chat("clock", "EP2-06605", List.of());
        var second = chat("clock", "EP2-06605", List.of());
        String version = first.get("index_version").asString();
        var cards = List.of(first.at("/top4/0/offer_id").asString());
        var store = new EventStore(temp.resolve("events.db").toString(), json);
        try (var con = java.sql.DriverManager.getConnection("jdbc:sqlite:" + temp.resolve("events.db"));
             var update = con.prepareStatement("UPDATE search_log SET ts=? WHERE search_id=?")) {
            update.setString(1, "2026-01-01T12:00:00.000000000Z");
            update.setString(2, first.get("search_id").asString());
            update.executeUpdate();
            update.setString(1, "2026-01-01T12:01:00.000000000Z");
            update.setString(2, second.get("search_id").asString());
            update.executeUpdate();
        }
        var end = java.time.Instant.parse("2026-01-01T12:02:00Z");
        assertTrue(store.repeatsChatCards("clock", version, cards, end));
        assertFalse(store.repeatsChatCards("clock", version, cards, end.plusNanos(1)));
        assertFalse(store.repeatsChatCards("clock", "new-snapshot", cards, end));
    }

    @Test
    void openEndedPlanUsesConversationAndPreservesExplicitFilters() throws Exception {
        var history = List.of(Map.of("role", "user", "text", "I work with scanned pages"));
        reply = payload -> payload.has("response_format")
                ? "{\"tool\":\"printer\",\"category\":\"Принтеры и МФУ\"}" : "yes";
        var result = json.valueToTree(search.search(Map.of("q", "I want a paper copy of this", "strategy", "llm",
                "assistant_history", history, "filters", Map.of("max_price_minor", 1))));
        assertEquals("llm_chat", result.at("/program/compiler").asString());
        assertEquals("PRINTER", result.at("/program/purpose").asString());
        assertEquals(1, result.at("/applied_filters/max_price_minor").asInt());
        assertEquals(0, result.get("direct_count").asInt());
        assertTrue(result.get("upsell").isEmpty());
        assertEquals(List.of(0.0, 0.0), temperatures);
    }

    @Test
    void exactCatalogCategoryCanExecuteAnUnlistedTool() throws Exception {
        reply = payload -> payload.has("response_format")
                ? "{\"tool\":\"pointing device\",\"category\":\"Мыши\"}" : "yes";
        var result = chat("unlisted", "How can I move a cursor by sliding a device on my desk?", List.of());
        assertEquals("llm_chat", result.at("/program/compiler").asString());
        assertEquals("Мыши", result.at("/program/terms").asString());
        assertTrue(result.get("direct_count").asInt() > 0);
        for (var category : result.at("/facets/categories")) assertEquals("mouse", category.get("id").asString());
    }

    @Test
    void threeConcurrentRequestsHaveExactlyOneDiversifiedResponse() throws Exception {
        instruction = "{\"op\":\"NEXT_MATCH\"}";
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(3)) {
            var results = new ArrayList<java.util.concurrent.Future<JsonNode>>();
            for (int i = 0; i < 3; i++) results.add(workers.submit(() -> chat("concurrent", "сервер", List.of())));
            var ids = new HashSet<String>();
            int changed = 0;
            for (var future : results) {
                var result = future.get(10, java.util.concurrent.TimeUnit.SECONDS);
                ids.add(result.get("search_id").asString());
                if (result.get("llm_applied").asBoolean()) changed++;
            }
            assertEquals(3, ids.size());
            assertEquals(1, changed);
            assertEquals(List.of(0.4), temperatures);
        }
    }

    @Test
    void refinementsReplacePriorBudgetAndSurviveATruncatedHistory() throws Exception {
        var start = chat("budget", "продление Kaspersky Small Office Security до 1 рубля", List.of());
        assertEquals(0, start.get("direct_count").asInt());
        var refined = chat("budget", "А до 450 рублей?", List.of());
        assertEquals(45000, refined.at("/applied_filters/max_price_minor").asInt());
        assertEquals("demo-ks-ren", refined.at("/top4/0/offer_id").asString());
        assertEquals("renewal", refined.at("/applied_filters/license_type").asString());
        var thousands = chat("budget", "а до 5 тыс. рублей?", List.of());
        assertEquals(500000, thousands.at("/applied_filters/max_price_minor").asInt());
        var changedTopic = chat("budget", "сервер", List.of());
        assertFalse(changedTopic.get("applied_filters").has("max_price_minor"));
        assertEquals(0, calls);
    }

    @Test
    void buyerCanProvideBudgetBeforeClarifyingTheProduct() throws Exception {
        var history = List.of(Map.of("role", "user", "text", "до 1 рубля"),
                Map.of("role", "assistant", "text", AssistantTurn.CLARIFY));
        var clarified = chat("budget-first", "EP2-06605", history);
        assertEquals(100, clarified.at("/applied_filters/max_price_minor").asInt());
        assertEquals(0, clarified.get("direct_count").asInt());
    }

    @Test
    void unrecognizedOrInvalidModelInstructionAsksForDetail() throws Exception {
        for (String invalid : List.of("no JSON", "{\"tool\":\"unknown\",\"category\":\"\"}",
                "{\"tool\":\"made up device\",\"category\":\"Вымышленный раздел\"}")) {
            instruction = invalid;
            var result = chat("unsure", "Как мне шмякнуть шурумбик", List.of());
            assertTrue(result.get("top4").isEmpty());
            assertTrue(result.get("upsell").valueStream().allMatch(card ->
                    "curated_fallback".equals(card.path("recommendation_kind").asString())));
            assertEquals(AssistantTurn.CLARIFY, result.get("assistant_message").asString());
        }
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
        assertEquals("473582", run("Нужна лицензия с артикулом EP2-06605", Map.of()).at("/primary_target/id").asString());
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
        var response = run("EP2-06605 без рекомендаций", Map.of());
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
