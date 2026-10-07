package com.hailin.blogsystem.config;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 闭合最后一个缺口：<b>容器里那个 {@code WebClient.Builder} 到底有没有带上思考开关</b>。
 *
 * <p>为什么必须有这一层：{@link AiConfigThinkingTests} 是手动调用 customizer 的，
 * 它只证明"customizer 本身有效"，证明不了"它被应用到了 Spring AI 实际使用的那个 Builder 上"。
 * Spring AI 通过 {@code ObjectProvider<WebClient.Builder>} 取 Bean——只要容器里那个
 * Bean 带着 filter，链路才成立。这里注入容器 Bean 直接发请求，把这个前提钉死。
 *
 * <p>测试自带 api-key，不依赖运行环境的环境变量。
 */
@SpringBootTest(properties = {
        "spring.ai.openai.api-key=test-key-not-used-in-this-test",
        "blog.ai.rag.health.initial-delay-ms=3600000"
})
class AiThinkingWebClientWiringTests {

    private static final String MODEL = "qwen3.7-flash-2026-07-15";

    @Autowired
    private WebClient.Builder containerWebClientBuilder;

    private HttpServer server;
    private final AtomicReference<String> capturedBody = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        HttpHandler handler = exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        };
        server.createContext("/v1/chat/completions", handler);
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** 容器 Bean 存在本身就是一个断言：说明 Boot 提供了它，Spring AI 的 ObjectProvider 才取得到。 */
    @Test
    void containerBuilderCarriesTheThinkingFilter() {
        assertNotNull(containerWebClientBuilder, "容器里没有 WebClient.Builder，Spring AI 将退回自建实例");

        WebClient client = containerWebClientBuilder.clone()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .build();

        client.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("model", MODEL, "stream", true, "messages", List.of()))
                .retrieve()
                .bodyToMono(String.class)
                .block();

        String sent = capturedBody.get();
        assertNotNull(sent, "服务端没有收到请求体");
        assertTrue(sent.contains("\"enable_thinking\":false"),
                "容器里的 WebClient.Builder 没有带上思考开关 filter，实际发送: " + sent);
        assertTrue(sent.contains("\"stream\":true"), "改写不应破坏原有字段: " + sent);
    }

    /** 反向对照：非对话端点即使走同一个 Builder 也不能被注入（embedding 会 400）。 */
    @Test
    void containerBuilderLeavesOtherEndpointsAlone() {
        HttpHandler echo = exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        };
        server.createContext("/v1/embeddings", echo);

        WebClient client = containerWebClientBuilder.clone()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .build();

        client.post()
                .uri("/v1/embeddings")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("model", "qwen3.7-text-embedding", "input", "hi"))
                .retrieve()
                .bodyToMono(String.class)
                .block();

        String sent = capturedBody.get();
        assertNotNull(sent);
        assertFalse(sent.contains("enable_thinking"), "embedding 端点不该被注入: " + sent);
    }
}
