package com.hailin.blogsystem.config;

import lombok.RequiredArgsConstructor;
import org.reactivestreams.Publisher;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.client.reactive.ClientHttpRequest;
import org.springframework.http.client.reactive.ClientHttpRequestDecorator;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import reactor.core.publisher.Mono;

import java.io.ByteArrayOutputStream;

/**
 * AI 模块全局配置：把 {@link AiThinkingPolicy} 的判定接到 Spring AI 的<b>两条</b>请求管线上。
 *
 * <p><b>为什么必须两条都接</b>：Spring AI 的 {@code OpenAiApi} 同时持有
 * {@code RestClient}（非流式 {@code .call()}）与 {@code WebClient}（流式 {@code .stream()}），
 * 它们是两套独立管线——旧实现只挂了前者，于是<b>所有流式调用（正文生成）从未被注入过</b>，
 * 模型默认开着思考：用户干等 6~13 秒，而思考内容因为 Spring AI 1.0.0 的响应 DTO
 * 没有 {@code reasoning_content} 字段被直接丢弃（既不显示，也不计入业务层首字计时）。
 *
 * <p><b>两条管线的差别</b>：RestClient 的拦截器能直接拿到序列化后的 {@code byte[]}，改完再走；
 * WebClient 的过滤器只能拿到 {@code BodyInserter}（此刻还没有字节），所以用一个捕获型的
 * {@link ClientHttpRequestDecorator} 把 encoder 的输出截在内存里，改写后再写到真实请求。
 */
@Configuration
@RequiredArgsConstructor
public class AiConfig {

    private final AiThinkingPolicy thinkingPolicy;

    /**
     * 非流式管线（分类器 / Agent 决策器 / 证据验证器 / 非流式降级重跑）。
     *
     * <p>自定义 {@code openAiRestClientBuilder} Bean 会优先于 Spring AI 自动装配的默认 Bean
     * （{@code OpenAiChatAutoConfiguration} 用 {@code ObjectProvider} 取，见其方法签名）。
     * 只对 chat completions 端点生效：embedding 等端点不支持该参数，注入了会 400。
     */
    @Bean
    RestClient.Builder openAiRestClientBuilder() {
        return RestClient.builder()
                .requestInterceptor((request, body, execution) -> {
                    byte[] modified = thinkingPolicy.applyToChatBody(request.getURI().getPath(), body);
                    return execution.execute(request, modified == null ? body : modified);
                });
    }

    /**
     * 流式管线（正文生成）。
     *
     * <p>用 {@link WebClientCustomizer} 而不是自己 {@code @Bean WebClient.Builder}：
     * Spring AI 通过 {@code ObjectProvider<WebClient.Builder>} 取 Bean，用的正是 Boot
     * {@code WebClientAutoConfiguration} 提供的那个（prototype 作用域，每次注入都重新应用
     * customizer）。自己造一个裸 Builder 会丢掉 Boot 的连接器、编解码器与超时定制。
     */
    @Bean
    WebClientCustomizer aiThinkingWebClientCustomizer() {
        return builder -> builder.filter(aiThinkingFilter());
    }

    private ExchangeFilterFunction aiThinkingFilter() {
        return (request, next) -> {
            String path = request.url().getPath();
            // 先按路径快速放行：embedding 等端点不装饰、零开销
            if (!thinkingPolicy.isChatCompletionsPath(path)) {
                return next.exchange(request);
            }

            BodyInserter<?, ? super ClientHttpRequest> original = request.body();
            if (original == null) {
                return next.exchange(request);
            }

            return next.exchange(ClientRequest.from(request)
                    .body(rewritingInserter(original, path))
                    .build());
        };
    }

    /**
     * 包私有以便单测：验证「捕获 → 改写 → 写回」这条链真的产出了正确的字节。
     *
     * <p>{@code BodyInserter} 的第二个类型参数是通配符 {@code ? super ClientHttpRequest}，
     * 直接调用 {@code insert} 无法通过编译，所以做一次受检的窄化转换——
     * 实际类型由 {@code ClientRequest.body()} 保证，转换是安全的。
     */
    @SuppressWarnings("unchecked")
    BodyInserter<?, ? super ClientHttpRequest> rewritingInserter(
            BodyInserter<?, ? super ClientHttpRequest> delegate,
            String path) {

        BodyInserter<Object, ClientHttpRequest> target =
                (BodyInserter<Object, ClientHttpRequest>) delegate;

        return (outputMessage, context) -> {
            CapturingRequest capture = new CapturingRequest(outputMessage);
            // insert 返回 Mono，写入发生在订阅时——必须串在同一个链上，不能当同步调用
            return target.insert(capture, context)
                    .then(Mono.defer(() -> writeRewritten(outputMessage, capture, path)));
        };
    }

    private Mono<Void> writeRewritten(ClientHttpRequest outputMessage,
                                      CapturingRequest capture, String path) {
        byte[] raw = capture.captured();
        byte[] modified = thinkingPolicy.applyToChatBody(path, raw);
        byte[] out = modified == null ? raw : modified;

        if (out.length == 0) {
            // 原本就是空 body（encoder 走了 setComplete）：保持原语义，别把请求挂住
            return outputMessage.setComplete();
        }

        // 长度变了必须重设，否则 Netty 会按旧长度截断请求体
        outputMessage.getHeaders().setContentLength(out.length);
        return outputMessage.writeWith(Mono.just(outputMessage.bufferFactory().wrap(out)));
    }

    /**
     * 把 encoder 写出的字节截在内存里。
     *
     * <p>继承 {@link ClientHttpRequestDecorator} 而不是自己实现接口：头操作继续委托给真实请求
     * （encoder 设置的 {@code Content-Type} 必须落到真实请求上），只把
     * {@link #writeWith} 换成写进内存缓冲。
     *
     * <p>注意 reactive 管线的请求体不是 {@code OutputStream} 而是 {@code DataBuffer} 流，
     * 所以这里必须合并并释放缓冲，不能直接读字节。
     */
    static final class CapturingRequest extends ClientHttpRequestDecorator {

        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream(2048);

        CapturingRequest(ClientHttpRequest delegate) {
            super(delegate);
        }

        @Override
        public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
            return DataBufferUtils.join(body)
                    .doOnNext(dataBuffer -> {
                        byte[] bytes = new byte[dataBuffer.readableByteCount()];
                        dataBuffer.read(bytes);
                        DataBufferUtils.release(dataBuffer);
                        buffer.write(bytes, 0, bytes.length);
                    })
                    .then();
        }

        byte[] captured() {
            return buffer.toByteArray();
        }
    }
}
