package ru.codesraider.smartsearch;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import ru.codesraider.smartsearch.search.SearchIndex;

@RestController
class HealthController {
    private final RestClient modelClient;
    private final SearchIndex index;
    private final SearchReadiness readiness;

    HealthController(RestClient.Builder builder, SearchIndex index, SearchReadiness readiness,
                     @Value("${MODEL_BASE_URL:http://localhost:8000}") String modelBaseUrl) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(1));
        factory.setReadTimeout(Duration.ofSeconds(1));
        modelClient = builder.baseUrl(modelBaseUrl).requestFactory(factory).build();
        this.index = index;
        this.readiness = readiness;
    }

    @GetMapping("/health")
    Map<String, Object> health() {
        boolean modelUp = isModelServiceUp();
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("status", "up");
        health.put("model_service", modelUp ? "up" : "unavailable");
        health.put("search_ready", readiness.ready());
        if (index.ready()) health.put("index_version", index.version());
        health.put("semantic_ready", modelUp && index.semanticReady());
        return health;
    }

    private boolean isModelServiceUp() {
        try {
            var response = modelClient.get().uri("/health").retrieve().toBodilessEntity();
            return response.getStatusCode().is2xxSuccessful();
        } catch (RestClientException unavailable) {
            return false;
        }
    }
}
