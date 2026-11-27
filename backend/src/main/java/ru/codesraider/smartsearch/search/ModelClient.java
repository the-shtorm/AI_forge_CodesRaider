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
        return choose(task, data, choices, 0);
    }

    String choose(String task, Object data, List<String> choices, double temperature) {
        var schema = objectSchema(Map.of("op", Map.of("type", "string", "enum", choices)));
        var parsed = generate(task, data, schema, temperature);
        if (parsed == null || parsed.size() != 1) return null;
        String op = parsed.path("op").asString("");
        return choices.contains(op) ? op : null;
    }

    boolean confirmsTool(String tool, String query) {
        String question = "Determine if " + tool + " would accomplish the user's requested task. Answer yes or no.";
        String answer = complete(question, query, null, 4, 0);
        if (answer == null) return false;
        String decision = answer.strip();
        return decision.equalsIgnoreCase("yes") || decision.equalsIgnoreCase("yes.");
    }

    /** An unrelated product need never be forced into a catalog class. The caller accepts only exact tool names. */
    String toolName(String query) {
        String task = """
                Translate the buyer's goal into the common English name of the tool they would use
                themselves. Give a short product type, not a brand. Any kind of product is allowed.
                For content, a service or no identifiable tool, write "unknown".
                Examples: послушать музыку с телефона -> music player; вызвать мастера на дом -> unknown.
                Return a JSON object with the field tool.
                """;
        var parsed = generate(task, query, objectSchema(Map.of("tool", Map.of("type", "string"))));
        if (parsed == null || parsed.size() != 1 || !parsed.path("tool").isString()) return null;
        String name = parsed.get("tool").asString().strip().toLowerCase(Locale.ROOT);
        return name.length() <= 64 ? name : null;
    }

    record ChatPrompt(List<AssistantTurn.Message> history, String question) {}
    record ChatInstruction(String tool, String category) {}

    ChatInstruction chatInstruction(String question, AssistantTurn chat, List<String> previousTitles) {
        String task = """
                Identify the IT product the buyer needs to operate themselves. Return the common English
                tool type in tool and its Russian shop category in the plural in category.
                Example: cut footage into clips -> tool: video editor, category: Видеоредакторы.
                If the question is vague, asks for content, a service, advice or anything other than
                a product to use, return unknown in tool and an empty category.
                History can resolve references. The current question sets the task.
                """;
        var fields = new LinkedHashMap<String, Object>();
        fields.put("tool", Map.of("type", "string"));
        fields.put("category", Map.of("type", "string"));
        Object prompt = new ChatPrompt(chat.promptHistory(question), question);
        if (!previousTitles.isEmpty()) {
            task += " The prior text search keeps returning the same products. Seek another tool for exactly "
                    + "the same task. Keep every stated requirement. Return unknown if no such interpretation fits.";
            prompt = Map.of("request", prompt, "previous_titles", previousTitles);
        }
        var parsed = generate(task, prompt, objectSchema(fields), previousTitles.isEmpty() ? 0 : 0.4);
        if (parsed == null || parsed.size() != 2 || !parsed.path("tool").isString()
                || !parsed.path("category").isString()) return null;
        String tool = SearchInput.normalize(parsed.get("tool").asString());
        String category = parsed.get("category").asString().strip();
        if (tool.equals("unknown") || tool.length() > 64 || category.length() > 80
                || !tool.matches("[a-z][a-z0-9 -]+")) return null;
        return new ChatInstruction(tool, category);
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
        return generate(task, data, schema, 0);
    }

    private JsonNode generate(String task, Object data, Map<String, Object> schema, double temperature) {
        String content = complete(task, data, schema, 80, temperature);
        if (content == null) return null;
        try {
            JsonNode parsed = json.readTree(content);
            return parsed != null && parsed.isObject() ? parsed : null;
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private String complete(String task, Object data, Map<String, Object> schema, int maxTokens, double temperature) {
        if (endpoint == null || !slots.tryAcquire()) return null;
        try {
            var payload = new LinkedHashMap<String, Object>();
            payload.put("model", model);
            payload.put("temperature", temperature);
            payload.put("max_tokens", maxTokens);
            payload.put("messages", List.of(Map.of("role", "system", "content", task),
                    Map.of("role", "user", "content", json.writeValueAsString(data))));
            if (schema != null) payload.put("response_format", Map.of("type", "json_schema",
                    "json_schema", Map.of("name", "instruction", "strict", true, "schema", schema)));
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
            var choice = json.readTree(result.body()).at("/choices/0");
            if ("length".equals(choice.path("finish_reason").asString(""))) return null;
            return choice.at("/message/content").asString("");
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
