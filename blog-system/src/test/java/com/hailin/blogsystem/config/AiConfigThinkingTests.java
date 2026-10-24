package com.hailin.blogsystem.config;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ClientHttpRequest;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 思考开关在<b>真实 WebClient 管线</b>上的验证。
 *
 * <p>为什么不能只测策略类：策略只决定"要不要改"，而历史上漏掉的是<b>接线</b>——
 * 旧实现压根没接流式管线。这里用本机 {@link HttpServer} 把 WebClient 真实发出的请求体
 * 抓下来断言，覆盖「Jackson 序列化 → BodyInserter 捕获 → 改写 → 真正发送」整条链。
 *
 * <p>这也是唯一能证明捕获型 {@link org.springframework.http.client.reactive.ClientHttpRequestDecorator}
 * 可用的手段：流式请求体在过滤器里还没有字节，且 reactive 管线的 body 是
 * {@code DataBuffer} 流而不是 {@code OutputStream}，捕获方式与同步管线完全不同。
 */
class AiConfigThinkingTests {

    private static final String CHAT_PATH = "/v1/chat/completions";
    private static final String MODEL = "qwen3.7-flash-2026-07-15";

    private HttpServer server;
    private final AtomicReference<String> capturedBody = new AtomicReference<>();
    private String baseUrl;

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
        server.createContext(CHAT_PATH, handler);
        // 非对话端点的对照组：必须也能成功响应，否则测的是 404 而不是"没被改写"
        server.createContext("/v1/other", handler);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static AiConfig config(AiThinkingPolicy policy) {
        return new AiConfig(policy);
    }

    private static AiThinkingPolicy policy(boolean disable, List<String> exempt) {
        BlogAiProperties properties = new BlogAiProperties();
        properties.getThinking().setDisable(disable);
        properties.getThinking().setExemptModels(exempt);
        return new AiThinkingPolicy(properties);
    }

    /** 走定制后的 WebClient 发一次真实请求，返回服务端收到的请求体。 */
    private String send(AiConfig aiConfig, String uri, Map<String, Object> payload) {
        WebClient.Builder builder = WebClient.builder().baseUrl(baseUrl);
        aiConfig.aiThinkingWebClientCustomizer().customize(builder);
        WebClient client = builder.build();

        client.post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(String.class)
                .block();
        return capturedBody.get();
    }

    private static Map<String, Object> chatPayload(String model) {
        return Map.of("model", model, "stream", true, "messages", List.of());
    }

    @Test
    void injectsFlagIntoRealStreamingRequestBody() {
        String sent = send(config(policy(true, List.of())), CHAT_PATH, chatPayload(MODEL));

        assertNotNull(sent, "服务端没有收到请求体");
        assertTrue(sent.contains("\"enable_thinking\":false"), "实际发送的请求体: " + sent);
        // 原有字段不能被改写弄丢
        assertTrue(sent.contains("\"stream\":true"), "实际发送的请求体: " + sent);
        assertTrue(sent.contains(MODEL), "实际发送的请求体: " + sent);
    }

    @Test
    void leavesExemptModelUntouchedOnRealRequest() {
        String sent = send(config(policy(true, List.of("qwen3.7-max-2026-05-17"))), CHAT_PATH,
                chatPayload("qwen3.7-max-2026-05-17"));

        assertNotNull(sent);
        assertFalse(sent.contains("enable_thinking"), "豁免模型不该被注入: " + sent);
    }

    @Test
    void doesNotInjectWhenMasterSwitchOffOnRealRequest() {
        String sent = send(config(policy(false, List.of())), CHAT_PATH, chatPayload(MODEL));

        assertNotNull(sent);
        assertFalse(sent.contains("enable_thinking"), "总开关关闭时不该注入: " + sent);
    }

    /** 非对话端点直接放行，连装饰都不做（embedding 收到该参数会 400）。 */
    @Test
    void nonChatEndpointIsNotDecorated() {
        String sent = send(config(policy(true, List.of())), "/v1/other", chatPayload(MODEL));

        assertNotNull(sent);
        assertFalse(sent.contains("enable_thinking"), "非对话端点不该被改写: " + sent);
    }

    /** 改写后长度变了，Content-Length 必须同步——否则服务端按旧长度截断，请求体直接坏掉。 */
    @Test
    void rewritesContentLengthToMatchModifiedBody() {
        AiConfig aiConfig = config(policy(true, List.of()));
        String original = "{\"model\":\"" + MODEL + "\",\"stream\":true}";

        DefaultDataBufferFactory factory = new DefaultDataBufferFactory();
        HttpHeaders headers = new HttpHeaders();
        ByteArrayOutputStream sink = new ByteArrayOutputStream();

        ClientHttpRequest outputMessage = mock(ClientHttpRequest.class);
        when(outputMessage.getHeaders()).thenReturn(headers);
        when(outputMessage.bufferFactory()).thenReturn(factory);
        when(outputMessage.writeWith(any())).thenAnswer(invocation -> {
            Publisher<? extends DataBuffer> publisher = invocation.getArgument(0);
            return Flux.from(publisher).doOnNext(dataBuffer -> {
                byte[] bytes = new byte[dataBuffer.readableByteCount()];
                dataBuffer.read(bytes);
                sink.write(bytes, 0, bytes.length);
            }).then();
        });

        @SuppressWarnings("unchecked")
        BodyInserter<Object, ClientHttpRequest> inserter =
                (BodyInserter<Object, ClientHttpRequest>) aiConfig.rewritingInserter(
                        rawInserter(original), CHAT_PATH);

        inserter.insert(outputMessage, mock(BodyInserter.Context.class)).block();

        String written = sink.toString(StandardCharsets.UTF_8);
        assertTrue(written.contains("\"enable_thinking\":false"), "实际写出: " + written);
        assertEquals(written.getBytes(StandardCharsets.UTF_8).length, headers.getContentLength(),
                "Content-Length 必须是改写后的长度");
    }

    /** 模拟 encoder：把字节作为 DataBuffer 写进请求体。 */
    private static BodyInserter<?, ? super ClientHttpRequest> rawInserter(String json) {
        BodyInserter<Object, ClientHttpRequest> inserter = (outputMessage, context) ->
                outputMessage.writeWith(Mono.just(
                        outputMessage.bufferFactory().wrap(json.getBytes(StandardCharsets.UTF_8))));
        return inserter;
    }
}
