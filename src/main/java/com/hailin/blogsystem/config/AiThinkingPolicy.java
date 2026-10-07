package com.hailin.blogsystem.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 思考模式（{@code enable_thinking}）的<b>唯一判定点</b> + 请求体改写。
 *
 * <p>存在的理由：RestClient 与 WebClient 是两套完全不同的请求管线（前者能在拦截器里
 * 直接拿到序列化后的 {@code byte[]}，后者只能拿到 {@code BodyInserter}），
 * 如果各自实现一遍判定，必然漂移——<b>这正是本类要消灭的历史缺陷</b>：
 * 旧实现只接了 RestClient，于是所有流式调用（正文生成）从来没被注入过，
 * 模型默认开着思考，用户干等 6~13 秒，而思考内容因 Spring AI 的响应 DTO
 * 没有 {@code reasoning_content} 字段被直接丢弃。
 *
 * <p>默认全关的取舍见 {@link BlogAiProperties.Thinking}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiThinkingPolicy {

    /** 只处理对话补全端点：embedding 等端点不接受该参数，注入了会 400。 */
    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";

    private static final String PARAM = "enable_thinking";

    /**
     * 只做 JSON 树读写，刻意不注入业务的 ObjectMapper——
     * 请求改写不应该被业务序列化配置（时间格式、fail-on-unknown 等）牵连。
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BlogAiProperties blogAiProperties;

    /**
     * 启动时把生效策略打出来。
     *
     * <p>这个开关的价值全在"出事时能不能一眼确认自己改对了"——配置名写错、
     * profile 覆盖、环境变量没生效，都会让"我以为关了"变成又一次误判。一行日志成本最低。
     * 与 {@code classifier-mode} / {@code classifier-engine} 同一套路。
     */
    @PostConstruct
    void logEffectivePolicy() {
        List<String> exempt = blogAiProperties.getThinking().getExemptModels();
        boolean on = blogAiProperties.getThinking().isDisable();
        log.info("[THINKING] 思考模式关闭={} | 豁免模型={} | 生效范围=RestClient(非流式)+WebClient(流式)",
                on ? "开启（默认）" : "关闭（一键回退）",
                exempt == null || exempt.isEmpty() ? "无（全部模型都关）" : exempt);
        if (on && exempt != null && !exempt.isEmpty()) {
            log.warn("[THINKING] 以下前缀的模型将继续保持思考，首 token 会明显更慢：{}", exempt);
        }
    }

    /**
     * 是否为对话补全端点。
     *
     * <p>公开出来是为了让 WebClient 过滤器能在<b>装饰请求体之前</b>就用它快速放行
     * （embedding 等端点不装饰、零开销），同时保证两处判断不会各写一份而漂移。
     */
    public boolean isChatCompletionsPath(String path) {
        return path != null && path.endsWith(CHAT_COMPLETIONS_PATH);
    }

    /**
     * 该模型是否应被关闭思考。
     *
     * @param model 请求体里的 model 名；为空时<b>不注入</b>（拿不准就按原样发，不猜）
     */
    public boolean shouldDisableThinking(String model) {
        if (!blogAiProperties.getThinking().isDisable()) {
            return false;
        }
        if (model == null || model.isBlank()) {
            return false;
        }

        List<String> exempt = blogAiProperties.getThinking().getExemptModels();
        if (exempt != null) {
            for (String prefix : exempt) {
                if (prefix != null && !prefix.isBlank() && model.startsWith(prefix)) {
                    log.debug("[THINKING] 模型 {} 命中豁免前缀 {}，省略 enable_thinking（保持模型默认）",
                            model, prefix);
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 改写 chat/completions 请求体，写入 {@code enable_thinking=false}。
     *
     * @param path 请求路径（用于排除非对话端点）
     * @param body 原始请求体字节
     * @return 改写后的字节；<b>不需要改写时返回 {@code null}</b>——
     *         调用方据此沿用原 body，避免每次请求都白做一次重新序列化
     */
    public byte[] applyToChatBody(String path, byte[] body) {
        if (!isChatCompletionsPath(path)) {
            return null;
        }
        if (body == null || body.length == 0) {
            return null;
        }

        try {
            JsonNode root = MAPPER.readTree(body);
            if (root == null || !root.isObject()) {
                return null;
            }

            String model = root.path("model").asText("");
            if (!shouldDisableThinking(model)) {
                return null;
            }

            ((ObjectNode) root).put(PARAM, false);
            return MAPPER.writeValueAsBytes(root);
        } catch (Exception e) {
            // 解析失败就原样放行：这里是延迟优化，绝不能把请求打挂。
            // 用 debug 是因为它同时覆盖"非 JSON 端点"这种正常情况，不该刷屏。
            log.debug("[THINKING] 请求体改写跳过（非 JSON 或解析失败）: {}", e.getMessage());
            return null;
        }
    }
}
