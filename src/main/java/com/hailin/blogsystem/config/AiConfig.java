package com.hailin.blogsystem.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * AI 模块全局配置。
 * <p>
 * 自定义 {@code openAiRestClientBuilder} Bean 会优先于 Spring AI 自动装配的默认 Bean，
 * 在请求体注入 {@code enable_thinking=false}，关闭百炼模型的深度思考模式以避免额外计费。
 * 只对支持该参数的模型（前缀白名单）生效，其余模型原样发送，切换模型不受影响。
 * </p>
 */
@Configuration
public class AiConfig {

    /**
     * 支持 enable_thinking 参数的百炼模型前缀。
     *
     * 用前缀匹配而不是枚举：阿里模型版本迭代快（qwen3.7-max → qwen3.8-max…），
     * 前缀自动覆盖新版本号，切换模型不用改代码。
     * DashScope 没有公开“模型支持哪些参数”的查询接口，启发式前缀是成本最低的自动适配。
     */
    private static final List<String> THINKING_MODEL_PREFIXES = List.of("qwen3", "deepseek");

    /**
     * 只跑思考模式、**不接受** {@code enable_thinking=false} 的模型前缀。
     *
     * <p>阿里把模型分两类：Hybrid（思考可按请求开关）与 Thinking-only（思考即其运行方式，
     * 关掉这个模型就不成立了）。对后者发 {@code false} 会直接 400：
     * {@code InternalError.Algo.InvalidParameter: The value of the enable_thinking parameter is restricted to True}
     * （2026-09-29 实测于 qwen3.7-max-2026-05-17；社区记录的 qwen3.8-max 同样如此）。
     *
     * <p>处理办法是**不发这个参数**——省略即用模型默认（思考开启），而不是发 true：
     * 这样将来某代 max 若支持混合模式，删掉对应前缀就能回归"关闭思考省成本"的行为。
     * 前缀匹配的理由同 {@link #THINKING_MODEL_PREFIXES}，要覆盖带日期后缀的版本号。
     */
    private static final List<String> THINKING_ONLY_MODEL_PREFIXES =
            List.of("qwen3.7-max", "qwen3.8-max");

    /**
     * 供 Spring AI {@code OpenAiAutoConfiguration} 注入使用的 {@link RestClient.Builder}。
     * 通过请求拦截器直接修改请求体 JSON，写入 {@code "enable_thinking": false}，
     * 只对 chat completions 端点 + 白名单前缀模型生效；embedding 等端点不支持该参数，不注入。
     */
    @Bean
    RestClient.Builder openAiRestClientBuilder() {
        ObjectMapper mapper = new ObjectMapper();

        return RestClient.builder()
                .requestInterceptor((request, body, execution) -> {
                    // 只处理 chat completions：embedding 等端点不支持 enable_thinking，不注入
                    if (!request.getURI().getPath().endsWith("/chat/completions")) {
                        return execution.execute(request, body);
                    }

                    if (body == null || body.length == 0) {
                        return execution.execute(request, body);
                    }

                    try {
                        JsonNode root = mapper.readTree(body);
                        String model = root.path("model").asText("");
                        boolean supportsThinking = THINKING_MODEL_PREFIXES.stream()
                                .anyMatch(model::startsWith);

                        // thinking-only 模型不能收到 false（会 400），但也不能改成 true——
                        // 省略参数即为其默认行为，且保留了将来支持混合模式后回归关闭的余地。
                        boolean thinkingOnly = THINKING_ONLY_MODEL_PREFIXES.stream()
                                .anyMatch(model::startsWith);

                        if (supportsThinking && !thinkingOnly) {
                            ((ObjectNode) root).put("enable_thinking", false);
                            body = mapper.writeValueAsBytes(root);
                        }
                    } catch (Exception ignored) {
                        // 非 JSON 或解析失败，原样发送
                    }

                    return execution.execute(request, body);
                });
    }
}
