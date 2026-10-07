package com.hailin.blogsystem.ai.classifier.jev;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.entity.dto.PageContextDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

@Component
public final class OpenRouterJevDecisionGateway implements JevDecisionGateway {

    private final URI endpoint;
    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Duration requestTimeout;

    @Autowired
    public OpenRouterJevDecisionGateway(BlogAiProperties properties, ObjectMapper objectMapper) {
        this(
                URI.create(properties.getJev().getEndpoint()),
                properties.getJev().getApiKey(),
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofMillis(properties.getJev().getConnectTimeoutMs()))
                        .build(),
                objectMapper,
                Duration.ofMillis(properties.getJev().getRequestTimeoutMs()));
    }

    public OpenRouterJevDecisionGateway(URI endpoint, String apiKey, HttpClient httpClient,
                                        ObjectMapper objectMapper, Duration requestTimeout) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
    }

    @Override
    public JevDecisionResult decide(String message, PageContextDTO pageContext) {
        if (apiKey.isBlank()) {
            throw new IllegalStateException("JEV_API_KEY is not configured");
        }
        try {
            String json = objectMapper.writeValueAsString(JevDecisionProtocol.request(message, pageContext));
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(requestTimeout)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();

            long startedAt = System.nanoTime();
            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException(
                        "OpenRouter Decisions API returned HTTP " + response.statusCode());
            }
            JsonNode root = objectMapper.readTree(response.body());
            return JevDecisionProtocol.mapResponse(root, elapsedMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("OpenRouter Decisions API call interrupted", e);
        } catch (IOException e) {
            throw new IllegalStateException("OpenRouter Decisions API call failed", e);
        }
    }
}
