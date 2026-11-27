package ru.codesraider.smartsearch.search;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Semaphore;

@Component
class ModelClient {
    private final URI endpoint;
    private final String model, key;
    private final Duration timeout;
    private final Semaphore slots = new Semaphore(2);
    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final JsonMapper json = JsonMapper.builder().build();

    ModelClient(
            @Value("${SEARCH_LLM_URL:}") String url,
            @Value("${SEARCH_LLM_MODEL:softline}") String model,
            @Value("${SEARCH_LLM_KEY:}") String key,
            @Value("${SEARCH_LLM_TIMEOUT_MS:3000}") long ms) {
        endpoint = url.isBlank() ? null : URI.create(url);
        this.model = model;
        this.key = key;
        timeout = Duration.ofMillis(ms);
    }

    boolean configured() {
        return endpoint != null;
    }

    // Each output is one instruction from a finite vocabulary. No executable model text is
    // evaluated.
    String choose(String task, Object data, List<String> choices) {
        var schema = objectSchema(Map.of("op", Map.of("type", "string", "enum", choices)));
        var parsed = generate(task, data, schema);
        if (parsed == null || parsed.size() != 1) return null;
        String op = parsed.path("op").asString("");
        return choices.contains(op) ? op : null;
    }

    record Instructions(List<QueryPlan.Purpose> find, String join) {}

    Instructions compile(String task, String query, List<String> operations) {
        List<String> words = Arrays.asList(query.split(" "));
        boolean disjunction = words.contains("или");
        boolean conjunction = words.contains("и");
        if (!disjunction && !conjunction) {
            String op =
                    choose(
                            task + " Return one JSON field op for the single requested function.",
                            query,
                            operations);
            return op == null
                    ? null
                    : new Instructions(List.of(QueryPlan.Purpose.valueOf(op)), "all");
        }
        String requiredJoin = disjunction ? "any" : "all";
        var fields = new LinkedHashMap<String, Object>();
        fields.put(
                "find",
                Map.of(
                        "type",
                        "array",
                        "minItems",
                        2,
                        "maxItems",
                        2,
                        "items",
                        Map.of("type", "string", "enum", operations)));
        fields.put("join", Map.of("type", "string", "enum", List.of(requiredJoin)));
        var node =
                generate(
                        task
                                + " Write two distinct operations in find and use join="
                                + requiredJoin
                                + ".",
                        query,
                        objectSchema(fields));
        if (node == null || node.size() != 2) return null;
        var find = node.path("find");
        String join = node.path("join").asString("");
        if (!find.isArray() || find.size() != 2 || !requiredJoin.equals(join)) return null;
        List<QueryPlan.Purpose> ops = new ArrayList<>();
        for (var item : find) {
            String op = item.asString("");
            if (!operations.contains(op)) return null;
            ops.add(QueryPlan.Purpose.valueOf(op));
        }
        if (new HashSet<>(ops).size() != ops.size()) return null;
        if (ops.contains(QueryPlan.Purpose.UNKNOWN)) return null;
        return new Instructions(List.copyOf(ops), join);
    }

    private static Map<String, Object> objectSchema(Map<String, ?> fields) {
        return Map.of(
                "type",
                "object",
                "properties",
                fields,
                "required",
                List.copyOf(fields.keySet()),
                "additionalProperties",
                false);
    }

    private JsonNode generate(String task, Object data, Map<String, Object> schema) {
        if (endpoint == null || !slots.tryAcquire()) return null;
        try {
            var payload =
                    Map.of(
                            "model",
                            model,
                            "temperature",
                            0,
                            "max_tokens",
                            80,
                            "messages",
                            List.of(
                                    Map.of("role", "system", "content", task),
                                    Map.of(
                                            "role",
                                            "user",
                                            "content",
                                            json.writeValueAsString(data))),
                            "response_format",
                            Map.of(
                                    "type",
                                    "json_schema",
                                    "json_schema",
                                    Map.of(
                                            "name",
                                            "instruction",
                                            "strict",
                                            true,
                                            "schema",
                                            schema)));
            var request =
                    HttpRequest.newBuilder(endpoint)
                            .header("Content-Type", "application/json")
                            .timeout(timeout)
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            json.writeValueAsString(payload)));
            if (!key.isBlank()) request.header("Authorization", "Bearer " + key);
            var result = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (result.statusCode() != 200) return null;
            String content =
                    json.readTree(result.body()).at("/choices/0/message/content").asString("");
            JsonNode parsed = json.readTree(content);
            return parsed != null && parsed.isObject() ? parsed : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        } finally {
            slots.release();
        }
    }
}
