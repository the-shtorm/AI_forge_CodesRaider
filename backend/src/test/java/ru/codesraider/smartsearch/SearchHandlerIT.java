package ru.codesraider.smartsearch;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

class SearchHandlerIT {
    private static final URI BASE = URI.create(System.getProperty("search.baseUrl", "http://localhost:8080"));
    private static final String SCENARIO = System.getProperty("search.scenario", "baseline");
    private static final String COLLECTION = "cyberprotect-1033";
    private static final String ENTITY = "/v1/entities/collection/" + COLLECTION;
    private static final String PREFIX = "кибер бэ";
    private static final String BACKUP = "кибер бэкап";
    private static final Map<String, String> TARGET = Map.of("kind", "collection", "target_id", COLLECTION);
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Map<String, String> BACKUP_FAMILIES = Map.of(
            "418697", "173614", "421468", "173614", "421471", "173614",
            "418698", "173615", "536456", "442025", "539763", "444870");
    private static final Map<String, Integer> PRIMARY_PRICES = Map.of(
            "418697", 7925000, "418698", 18716900, "536456", 117200, "539763", 137000);
    private static final Set<String> P2500W = Set.of("363882", "521380");

    private static JsonNode request(String path, Object body, int expectedStatus) throws Exception {
        var builder = HttpRequest.newBuilder(BASE.resolve(path)).timeout(Duration.ofSeconds(10));
        if (body != null) {
            builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        }
        var response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(expectedStatus, response.statusCode(), () -> path + " returned " + response.body());
        String mediaType = response.headers().firstValue("content-type").orElse("").split(";")[0].trim();
        assertEquals(expectedStatus >= 400 ? "application/problem+json" : "application/json", mediaType);
        return JSON.readTree(response.body());
    }

    private static JsonNode search(Map<String, ?> body) throws Exception {
        JsonNode response = request("/v1/search", body, 200);
        assertFalse(response.required("search_id").asString().isBlank());
        assertFalse(response.required("index_version").asString().isBlank());
        return response;
    }

    @BeforeAll
    static void requireSpringStand() throws Exception {
        assertTrue(Set.of("baseline", "missing-code", "priced-reserve", "unpriced-reserve").contains(SCENARIO),
                () -> "Unknown fixture: " + SCENARIO);
        assertEquals("up", request("/health", null, 200).required("status").asString());
    }

    private static void assertCollection(JsonNode target) {
        assertEquals("collection", target.required("kind").asString());
        assertEquals(COLLECTION, target.required("id").asString());
        URI url = URI.create("https://store.softline.ru").resolve(target.required("url").asString());
        assertEquals("https", url.getScheme());
        assertEquals("store.softline.ru", url.getAuthority());
        assertEquals("/cyberprotect/", url.getPath());
        assertEquals("filter[category][]=1033", url.getQuery());
    }

    private static void assertBackupCards(JsonNode response) {
        assertCollection(response.required("primary_target"));
        JsonNode filters = response.required("applied_filters");
        assertEquals("cyberprotect", filters.required("vendor_id").asString());
        assertEquals("1033", filters.required("category_id").asString());
        Set<String> expectedGroups = switch (SCENARIO) {
            case "missing-code" -> Set.of("173614", "173615");
            case "priced-reserve" -> Set.of("173614");
            default -> Set.copyOf(BACKUP_FAMILIES.values());
        };
        Set<String> groups = new HashSet<>();
        JsonNode cards = response.required("top4");
        assertTrue(cards.isArray());
        for (JsonNode card : cards) {
            String id = card.required("offer_id").asString();
            String group = BACKUP_FAMILIES.get(id);
            assertNotNull(group, () -> "Offer outside collection: " + id);
            assertEquals("family/" + group, card.required("family_key").asString());
            assertTrue(groups.add(group), () -> "Duplicate family: " + group);
            assertEquals("direct", card.required("role").asString());
            assertEquals("RUB", card.required("currency").asString());
            JsonNode price = card.required("price_minor");
            boolean available = card.required("available").asBoolean();
            String tier = card.required("offer_tier").asString();
            switch (SCENARIO) {
                case "priced-reserve" -> {
                    assertEquals("421468", id);
                    assertEquals("reserve", tier);
                    assertFalse(available);
                    assertEquals(10323600, price.asInt());
                }
                case "unpriced-reserve" -> {
                    assertEquals("reserve", tier);
                    assertFalse(available);
                    assertTrue(price.isNull(), "A zero XML price must be exposed as null");
                }
                default -> {
                    assertEquals("primary", tier);
                    assertTrue(available);
                    assertTrue(PRIMARY_PRICES.containsKey(id), () -> "Unexpected representative: " + id);
                    assertEquals(PRIMARY_PRICES.get(id).intValue(), price.asInt());
                }
            }
        }
        assertEquals(expectedGroups, groups);
        assertEquals(groups.size(), response.required("direct_count").asInt());
        assertEmptyList(response, "more_results");
        assertShortfall(response, groups.size());
    }

    @Test
    void selectedSuggestionAndFreeTextResolveTheSameScopedCollection() throws Exception {
        JsonNode suggestions = request("/v1/suggest?q=" + URLEncoder.encode(PREFIX, StandardCharsets.UTF_8), null, 200);
        assertTrue(suggestions.isArray() && suggestions.size() <= 6);
        var matching = suggestions.valueStream()
                .filter(s -> "collection".equals(s.required("kind").asString())
                        && COLLECTION.equals(s.required("target_id").asString())).toList();
        assertEquals(1, matching.size());
        var picked = matching.get(0);
        assertFalse(picked.required("label").asString().isBlank());
        var selected = Map.of("kind", picked.get("kind").asString(), "target_id", picked.get("target_id").asString());
        assertBackupCards(search(Map.of("q", PREFIX, "selected_target", selected, "limit", 8)));
        assertBackupCards(search(Map.of("q", BACKUP, "limit", 8)));
    }

    @Test
    void entityNeverLinksOutsideItsCollection() throws Exception {
        JsonNode entity = request(ENTITY, null, 200);
        assertCollection(entity);
        JsonNode children = entity.required("children");
        assertTrue(children.isArray() && !children.isEmpty(), "Collection must provide navigation");
        for (JsonNode child : children) {
            String id = child.required("id").asString();
            switch (child.required("kind").asString()) {
                case "offer" -> assertTrue(BACKUP_FAMILIES.containsKey(id), id);
                case "family" -> assertTrue(BACKUP_FAMILIES.containsValue(id), id);
                default -> fail("Child expands the collection scope: " + child);
            }
        }
        for (JsonNode offer : entity.required("offers")) {
            assertTrue(BACKUP_FAMILIES.containsKey(offer.required("offer_id").asString()));
        }
    }

    @Test
    void explicitFiltersCanEmptyButCannotReplaceTheCollection() throws Exception {
        var conflicts = new HashMap<>(Map.of("category_id", "687", "vendor_id", "uranium-backup", "version", "17.2"));
        if (SCENARIO.endsWith("-reserve")) {
            conflicts.put("available_only", "true");
        }
        for (var conflict : conflicts.entrySet()) {
            String field = conflict.getKey();
            Object value = field.equals("available_only") ? Boolean.TRUE : conflict.getValue();
            JsonNode result = search(Map.of("q", PREFIX, "selected_target", TARGET, "filters", Map.of(field, value)));
            assertCollection(result.required("primary_target"));
            assertEquals(JSON.valueToTree(value), result.required("applied_filters").required(field));
            assertEmptyList(result, "top4");
            assertEmptyList(result, "more_results");
            assertEmptyList(result, "alternatives");
            assertEquals(0, result.required("direct_count").asInt());
            assertShortfall(result, 0);
            JsonNode entity = request(ENTITY + "?" + field + "=" + value, null, 200);
            assertCollection(entity);
            assertEmptyList(entity, "offers");
            assertEmptyList(entity, "children");
        }
    }

    @Test
    void staleSelectionAndUnknownEntityHaveDifferentErrors() throws Exception {
        var stale = Map.of("q", PREFIX, "selected_target", Map.of("kind", "collection", "target_id", "missing"));
        assertEquals("UNKNOWN_TARGET", request("/v1/search", stale, 400).required("code").asString());
        assertEquals("ENTITY_NOT_FOUND", request("/v1/entities/collection/missing", null, 404).required("code").asString());
    }

    private static void assertEmptyList(JsonNode response, String field) {
        JsonNode items = response.required(field);
        assertTrue(items.isArray() && items.isEmpty(), () -> "Expected empty " + field + ": " + items);
    }

    private static void assertShortfall(JsonNode result, int size) {
        var reason = result.required("shortfall_reason");
        if (size == 4) {
            assertTrue(reason.isNull(), "A full top4 has no shortfall");
        } else {
            assertEquals(size == 0 ? "NO_DIRECT_MATCH" : "NOT_ENOUGH_VERIFIED_FAMILIES", reason.asString());
        }
    }

    @Test
    void absentPrinterGetsAFunctionalAlternativeInTheFirstResponse() throws Exception {
        var result = search(Map.of("q", "Нужен монохромный лазерный принтер A4 Pantum P2507"));
        assertEmptyList(result, "top4");
        assertEmptyList(result, "more_results");
        assertEquals(0, result.required("direct_count").asInt());
        assertShortfall(result, 0);
        var alternatives = result.required("alternatives");
        assertTrue(alternatives.isArray() && !alternatives.isEmpty(), "A suitable printer is in this fixture");
        for (var card : alternatives) {
            String id = card.required("offer_id").asString();
            assertTrue(P2500W.contains(id) || "521379".equals(id), () -> "Not a compatible printing device: " + id);
            assertEquals("alternative", card.required("role").asString());
            var differences = card.required("differences");
            assertTrue(differences.isArray() && differences.valueStream().anyMatch(d -> !d.asString().isBlank()));
        }
    }

    @Test
    void pantumDeduplicatesDevicesAndChecksTheSpecificCartridgePair() throws Exception {
        for (boolean bothDevices : new boolean[] {true, false}) {
            String q = bothDevices ? "Покажи устройства Pantum P2500W или BM1800W" : "Покажи устройство Pantum P2500W";
            assertPantumCards(search(Map.of("q", q)), bothDevices);
        }
    }

    @Test
    void explicitExclusionAndUnverifiedPrinterRequirementsSurviveSelection() throws Exception {
        var printer = search(Map.of("q", "Покажи устройство Pantum P2500W без картриджей"));
        assertEquals(1, printer.required("top4").size());
        assertEquals("direct", printer.required("top4").get(0).required("role").asString());
        var unsupported = search(Map.of("q", "Нужен цветной лазерный принтер A3 Pantum P2507"));
        assertEmptyList(unsupported, "top4");
        assertEmptyList(unsupported, "alternatives");
        var modelOnly = search(Map.of("q", "P2507"));
        assertEmptyList(modelOnly, "top4");
    }

    private static void assertPantumCards(JsonNode result, boolean bothDevices) {
        JsonNode cards = result.required("top4");
        Set<String> devices = bothDevices ? Set.of("P2500W", "BM1800W") : Set.of("P2500W");
        Set<String> cartridges = new HashSet<>();
        if (bothDevices) {
            cartridges.add("513625");
        }
        if (!SCENARIO.equals("missing-code")) {
            cartridges.add("526683");
        }
        assertEquals(devices.size(), result.required("direct_count").asInt());
        assertEquals(devices.size() + cartridges.size(), cards.size());
        assertShortfall(result, cards.size());

        Map<String, String> shownDevices = new HashMap<>();
        Set<String> shownCartridges = new HashSet<>();
        Set<String> families = new HashSet<>();
        for (JsonNode card : cards) {
            assertTrue(families.add(card.required("family_key").asString()), "Repeated family in top4");
            String id = card.required("offer_id").asString();
            if ("direct".equals(card.required("role").asString())) {
                assertTrue(shownCartridges.isEmpty(), "Devices must precede consumables");
                String sku = card.required("sku").asString();
                assertTrue(devices.contains(sku));
                assertTrue(sku.equals("P2500W") ? P2500W.contains(id) : id.equals("521379"));
                assertNull(shownDevices.put(sku, id), "Duplicate model");
            } else {
                assertEquals("complement", card.required("role").asString());
                assertTrue(cartridges.contains(id) && shownCartridges.add(id), () -> "Invalid cartridge " + id);
                String device = id.equals("526683") ? "P2500W" : "BM1800W";
                String sku = id.equals("526683") ? "PC-211P" : "TL-C2318H";
                assertEquals(sku, card.required("sku").asString());
                assertEquals(shownDevices.get(device), card.required("anchor_offer_id").asString());
                var checks = card.required("checks");
                boolean verifiedPair = false;
                for (JsonNode check : checks) {
                    assertEquals("pass", check.required("result").asString());
                    assertFalse(check.required("source_url").asString().isBlank());
                    verifiedPair |= device.equalsIgnoreCase(check.required("anchor_value").asString())
                            && sku.equalsIgnoreCase(check.required("candidate_value").asString());
                }
                assertTrue(verifiedPair, "Missing evidence for this device and consumable");
            }
        }
        assertEquals(devices, shownDevices.keySet());
        assertEquals(cartridges, shownCartridges);
    }

    @Test
    void fileRecoveryKeepsOnlyRecoveryOrBackupOffersAndPreservesExplicitFilters() throws Exception {
        Set<String> fixtureRecovery = new HashSet<>(BACKUP_FAMILIES.keySet());
        fixtureRecovery.add("53955"); // Uranium Backup, same category, another vendor
        for (String q : Set.of("Как восстановить файлы после сбоя?", "Как вернуть документы с повреждённого диска?")) {
            var result = search(Map.of("q", q));
            assertTrue(result.required("direct_count").asInt() > 0);
            for (var card : result.required("top4")) {
                assertTrue(fixtureRecovery.contains(card.required("offer_id").asString()));
                assertTrue(card.required("reason_codes").valueStream()
                        .anyMatch(code -> code.asString().equals("FILE_RECOVERY_CATEGORY")));
            }
            var conflict = search(Map.of("q", q, "filters", Map.of("category_id", "687")));
            assertEmptyList(conflict, "top4");
        }
        var negated = search(Map.of("q", "Не хочу восстанавливать файлы, нужен принтер"));
        for (var card : negated.required("top4"))
            assertFalse(card.required("reason_codes").valueStream()
                    .anyMatch(code -> code.asString().equals("FILE_RECOVERY_CATEGORY")));
    }
}
