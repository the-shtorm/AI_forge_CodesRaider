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

import ru.codesraider.smartsearch.catalog.CatalogAccess;

@RestController
class HealthController {
    private final RestClient modelClient;
    private final CatalogAccess catalog;

    HealthController(RestClient.Builder builder, CatalogAccess catalog,
                     @Value("${MODEL_BASE_URL:http://localhost:8000}") String modelBaseUrl) {
        var factory = new SimpleClientHttpRequestFactory();
        this.catalog = catalog;
        factory.setConnectTimeout(Duration.ofSeconds(1));
        factory.setReadTimeout(Duration.ofSeconds(1));
        modelClient = builder.baseUrl(modelBaseUrl).requestFactory(factory).build();
    }

    @GetMapping("/health")
    Map<String, Object> health() {
        var result = new LinkedHashMap<String, Object>();
        result.put("status", "up");
        result.put("model_service", isModelServiceUp() ? "up" : "unavailable");
        result.put("search_ready", catalog.version() != null);
        result.put("semantic_ready", false);
        if (catalog.version() != null) result.put("index_version", catalog.version());
        return result;
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
