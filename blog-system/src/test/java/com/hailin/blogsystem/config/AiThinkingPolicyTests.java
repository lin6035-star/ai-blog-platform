package com.hailin.blogsystem.config;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 思考模式策略的离线门。
 *
 * <p>这里锁的不是"实现细节"，而是几条<b>被实测推翻过或踩过坑的边界</b>：
 * 默认必须全关（旧实现是白名单，默认不关）；曾被误判为 thinking-only 的 max 系列必须注入；
 * 豁免只认登记的前缀；非对话端点绝不能碰（embedding 收到该参数会 400）。
 */
class AiThinkingPolicyTests {

    private static final String CHAT_PATH = "/v1/chat/completions";

    private static AiThinkingPolicy policy(boolean disable, List<String> exempt) {
        BlogAiProperties properties = new BlogAiProperties();
        properties.getThinking().setDisable(disable);
        properties.getThinking().setExemptModels(exempt);
        return new AiThinkingPolicy(properties);
    }

    private static String body(String model) {
        return "{\"model\":\"" + model + "\",\"stream\":true,\"messages\":[]}";
    }

    /** 返回改写后的 JSON；未改写返回 null（调用方据此沿用原 body）。 */
    private static String rewrite(AiThinkingPolicy policy, String path, String json) {
        byte[] out = policy.applyToChatBody(path, json == null ? null : json.getBytes(StandardCharsets.UTF_8));
        return out == null ? null : new String(out, StandardCharsets.UTF_8);
    }

    @Test
    void injectsForEveryModelByDefault() {
        String out = rewrite(policy(true, List.of()), CHAT_PATH, body("qwen3.7-flash-2026-07-15"));
        assertNotNull(out);
        assertTrue(out.contains("\"enable_thinking\":false"));
    }

    /**
     * 回归锁：旧实现用 {@code "qwen3.7-max"} 前缀豁免了整个系列，
     * 但 2026-10-04 实测 05-20 / 06-08 两个版本都接受该参数且成功关闭思考。
     * 这个豁免曾让默认 judge-model（qwen3.7-max-2026-06-08）一直开着思考。
     */
    @Test
    void injectsForMaxSeriesWhichWasWronglyTreatedAsThinkingOnly() {
        String out = rewrite(policy(true, List.of()), CHAT_PATH, body("qwen3.7-max-2026-06-08"));
        assertNotNull(out);
        assertTrue(out.contains("\"enable_thinking\":false"));
    }

    @Test
    void skipsWhenModelMatchesExemptPrefix() {
        assertNull(rewrite(policy(true, List.of("qwen3.7-max-2026-05-17")), CHAT_PATH,
                body("qwen3.7-max-2026-05-17")));
    }

    @Test
    void exemptMatchesByPrefixSoVersionSuffixStillCovered() {
        assertNull(rewrite(policy(true, List.of("qwen3.8-max")), CHAT_PATH,
                body("qwen3.8-max-2027-01-01")));
    }

    @Test
    void doesNotInjectWhenMasterSwitchOff() {
        assertNull(rewrite(policy(false, List.of()), CHAT_PATH, body("qwen3.7-flash-2026-07-15")));
    }

    /** embedding 等端点不接受 enable_thinking，注入了会 400。 */
    @Test
    void neverTouchesNonChatEndpoints() {
        assertNull(rewrite(policy(true, List.of()), "/v1/embeddings", body("qwen3.7-text-embedding")));
    }

    @Test
    void skipsWhenModelMissing() {
        assertNull(rewrite(policy(true, List.of()), CHAT_PATH, "{\"messages\":[]}"));
    }

    @Test
    void passesThroughNonJsonBody() {
        assertNull(rewrite(policy(true, List.of()), CHAT_PATH, "not-json-at-all"));
    }

    @Test
    void passesThroughEmptyBody() {
        AiThinkingPolicy policy = policy(true, List.of());
        assertNull(policy.applyToChatBody(CHAT_PATH, new byte[0]));
        assertNull(policy.applyToChatBody(CHAT_PATH, null));
    }

    /** 模型若已自行带了这个参数，必须被覆盖成 false，不能两者并存。 */
    @Test
    void overridesExistingTrueValue() {
        String out = rewrite(policy(true, List.of()), CHAT_PATH,
                "{\"model\":\"qwen3.7-flash\",\"enable_thinking\":true}");
        assertNotNull(out);
        assertTrue(out.contains("\"enable_thinking\":false"));
        assertFalse(out.contains("\"enable_thinking\":true"));
    }

    /** 改写是 JSON 树级别的，其它字段必须原样保留（否则会静默丢掉 stream / max_tokens）。 */
    @Test
    void keepsOtherFieldsIntact() {
        String out = rewrite(policy(true, List.of()), CHAT_PATH,
                "{\"model\":\"qwen3.7-flash\",\"stream\":true,\"max_tokens\":4000,"
                        + "\"stream_options\":{\"include_usage\":true}}");
        assertNotNull(out);
        assertTrue(out.contains("\"stream\":true"));
        assertTrue(out.contains("\"max_tokens\":4000"));
        assertTrue(out.contains("\"include_usage\":true"));
    }
}
