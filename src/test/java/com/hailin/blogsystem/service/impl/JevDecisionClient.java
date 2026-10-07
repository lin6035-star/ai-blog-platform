package com.hailin.blogsystem.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.entity.dto.PageContextDTO;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

public final class JevDecisionClient {

    public static final URI OPENROUTER_ENDPOINT =
            URI.create("https://openrouter.ai/api/alpha/decisions");

    private final URI endpoint;
    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Duration timeout;

    public JevDecisionClient(URI endpoint, String apiKey, HttpClient httpClient,
                             ObjectMapper objectMapper, Duration timeout) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("JEV_API_KEY must not be blank");
        }
        this.apiKey = apiKey.trim();
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    public static JevDecisionClient openRouter(String apiKey, ObjectMapper objectMapper) {
        return new JevDecisionClient(
                OPENROUTER_ENDPOINT,
                apiKey,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                objectMapper,
                Duration.ofSeconds(15));
    }

    public JevDecisionResult decide(String message, PageContextDTO pageContext) {
        try {
            String json = objectMapper.writeValueAsString(
                    JevDecisionProtocol.request(message, pageContext));
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();

            long start = System.nanoTime();
            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

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
