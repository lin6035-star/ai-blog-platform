package com.hailin.blogsystem.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.entity.dto.PageContextDTO;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JevDecisionClientTests {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<JsonNode> requestBody = new AtomicReference<>();
    private final AtomicInteger responseStatus = new AtomicInteger(200);
    private final AtomicReference<String> responseBody = new AtomicReference<>();
    private HttpServer server;
    private URI endpoint;

    @BeforeEach
    void startServer() throws Exception {
        responseBody.set(successResponse());
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/alpha/decisions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(objectMapper.readTree(exchange.getRequestBody()));
            byte[] bytes = responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus.get(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                + "/api/alpha/decisions");
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void sendsOpenRouterDecisionRequestAndParsesCompositeResponse() {
        JevDecisionClient client = new JevDecisionClient(
                endpoint, "test-key", HttpClient.newHttpClient(), objectMapper, Duration.ofSeconds(2));
        PageContextDTO pageContext = new PageContextDTO();
        pageContext.setPageType("article-detail");
        pageContext.setArticleId("12");

        JevDecisionResult result = client.decide(
                "点赞这篇文章，再帮我优化一下", pageContext);

        assertThat(authorization.get()).isEqualTo("Bearer test-key");
        assertThat(requestBody.get().path("model").asText()).isEqualTo("typesafe/jev-1.13");
        assertThat(requestBody.get().path("state").path("message").asText())
                .isEqualTo("点赞这篇文章，再帮我优化一下");
        assertThat(requestBody.get().path("questions").has("risk")).isFalse();
        assertThat(result.acceptedGoals())
                .containsExactlyInAnyOrder("article_write", "workflow_optimize");
        assertThat(result.elapsedMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void rejectsNonSuccessfulHttpStatus() {
        responseStatus.set(429);
        responseBody.set("{\"error\":{\"message\":\"rate limited\"}}");
        JevDecisionClient client = new JevDecisionClient(
                endpoint, "test-key", HttpClient.newHttpClient(), objectMapper, Duration.ofSeconds(2));

        assertThatThrownBy(() -> client.decide("你好", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("429")
                .hasMessageNotContaining("test-key");
    }

    private String successResponse() throws IOException {
        var root = objectMapper.createObjectNode();
        root.put("model", "typesafe/jev-1.13-20260917");
        root.put("provider", "TypeSafe");
        var answers = root.putObject("answers");
        var primary = answers.putObject("primary");
        primary.put("type", "choice");
        primary.put("choice", "article_write");
        primary.put("confidence", 0.99);
        primary.putObject("probabilities").put("article_write", 0.99);
        for (String goal : JevDecisionProtocol.goalQuestionNames()) {
            double probability = switch (goal) {
                case "article_write" -> 0.97;
                case "workflow_optimize" -> 0.96;
                default -> 0.02;
            };
            answers.putObject(goal).put("type", "noul").put("noul", probability);
        }
        answers.putObject("needs_thinking").put("type", "noul").put("noul", 0.45);
        root.putObject("usage")
                .put("input_tokens", 2158)
                .put("output_tokens", 383)
                .put("cost", 0.000090636);
        return objectMapper.writeValueAsString(root);
    }
}
