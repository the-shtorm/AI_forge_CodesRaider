package ru.codesraider.smartsearch.modelclient;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** HTTP boundary shared by the offline indexer and online retrieval. */
public final class EncoderClient implements AutoCloseable {
    public static final int DIMENSION = 384;
    private static final Duration READY_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration IMPORT_TIMEOUT = Duration.ofMinutes(2);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final HttpClient http;
    private final URI base;
    private final Duration queryTimeout;

    public EncoderClient(String baseUrl) {
        this(baseUrl, Duration.ofMillis(200));
    }

    public EncoderClient(String baseUrl, Duration queryTimeout) {
        this.base = URI.create(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/");
        this.queryTimeout = queryTimeout;
        this.http = HttpClient.newBuilder().connectTimeout(READY_TIMEOUT)
                .version(HttpClient.Version.HTTP_1_1).build();
    }

    public String readyProfile() throws IOException {
        var request = HttpRequest.newBuilder(base.resolve("ready")).timeout(READY_TIMEOUT).GET().build();
        var json = response(send(request));
        var profile = json.path("profile_id");
        if (!json.path("ready").isBoolean() || !json.path("ready").asBoolean()
                || !profile.isString() || profile.asString().isBlank()
                || !json.path("dimension").isIntegralNumber() || json.path("dimension").asInt() != DIMENSION) {
            throw new IOException("Invalid encoder readiness response");
        }
        return profile.asString();
    }

    public List<float[]> encodePassages(String profile, List<String> texts) throws IOException {
        if (texts.isEmpty() || texts.size() > 32) {
            throw new IllegalArgumentException("A passage batch must have 1 to 32 items");
        }
        return vectors(response(send(request("passage", profile, texts, IMPORT_TIMEOUT))), profile, texts.size());
    }

    public CompletableFuture<float[]> encodeQuery(String profile, String text) {
        var request = request("query", profile, List.of(text), queryTimeout);
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray()).thenApply(reply -> {
            try {
                return vectors(response(reply), profile, 1).getFirst();
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
        });
    }

    private HttpRequest request(String type, String profile, List<String> texts, Duration timeout) {
        var body = JSON.writeValueAsBytes(Map.of("input_type", type, "profile_id", profile, "texts", texts));
        return HttpRequest.newBuilder(base.resolve("internal/v1/embeddings")).timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
    }

    private HttpResponse<byte[]> send(HttpRequest request) throws IOException {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for the encoder", interrupted);
        }
    }

    private static JsonNode response(HttpResponse<byte[]> response) throws IOException {
        if (response.statusCode() != 200) {
            throw new IOException("Encoder returned HTTP " + response.statusCode());
        }
        try {
            JsonNode payload = JSON.readTree(response.body());
            if (payload == null || !payload.isObject()) {
                throw new IOException("Expected an encoder response object");
            }
            return payload;
        } catch (RuntimeException invalidJson) {
            throw new IOException("Malformed encoder response", invalidJson);
        }
    }

    static List<float[]> vectors(JsonNode response, String profile, int expected) throws IOException {
        var rows = response.path("vectors");
        if (!profile.equals(response.path("profile_id").asString(""))
                || !response.path("dimension").isIntegralNumber() || response.path("dimension").asInt() != DIMENSION
                || !rows.isArray() || rows.size() != expected) {
            throw new IOException("Encoder profile, dimension or batch length mismatch");
        }
        var result = new ArrayList<float[]>(expected);
        for (JsonNode row : rows) {
            if (!row.isArray() || row.size() != DIMENSION) {
                throw new IOException("Invalid vector dimension");
            }
            float[] vector = new float[DIMENSION];
            double normSquared = 0;
            for (int i = 0; i < DIMENSION; i++) {
                JsonNode value = row.get(i);
                float component = value.isNumber() ? (float) value.asDouble() : Float.NaN;
                if (!Float.isFinite(component)) {
                    throw new IOException("Non-finite or non-numeric embedding component");
                }
                vector[i] = component;
                normSquared += (double) component * component;
            }
            if (Math.abs(Math.sqrt(normSquared) - 1.0) > 1e-4) {
                throw new IOException("Expected an L2-normalized embedding");
            }
            result.add(vector);
        }
        return result;
    }

    @Override
    public void close() {
        http.close();
    }
}
