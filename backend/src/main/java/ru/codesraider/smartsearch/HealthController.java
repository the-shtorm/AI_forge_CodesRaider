package ru.codesraider.smartsearch;

import java.time.Duration;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@RestController
class HealthController {
    private final RestClient modelClient;

    HealthController(RestClient.Builder builder,
                     @Value("${MODEL_BASE_URL:http://localhost:8000}") String modelBaseUrl) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(1));
        factory.setReadTimeout(Duration.ofSeconds(1));
        modelClient = builder.baseUrl(modelBaseUrl).requestFactory(factory).build();
    }

    @GetMapping("/health")
    Map<String, String> health() {
        return Map.of("status", "up", "model_service", isModelServiceUp() ? "up" : "unavailable");
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
