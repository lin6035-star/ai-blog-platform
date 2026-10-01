package com.hailin.blogsystem.ai.agent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent 只读动作的执行结果（P2.2，Token 设计稿 §7.3 **路 B**）。
 *
 * <p><b>为什么要把 {@code String} 升级成结构</b>：{@code List<String> observations} 目前同时充当
 * 模型决策上下文、Verifier 证据、调试面板材料与 maxSteps 总结材料（§7.1）。
 * 要让 Reducer 消费"事实"，只有两条路——
 * 解析中文 observation（脆弱，且与项目「不让 LLM 改写的摘要成为权威文本」的既有原则冲突），
 * 或者把执行结果本身变成结构化的。这里是后者。
 *
 * <p><b>兼容策略（第一版只改文章域）</b>：{@code summary} 仍是现有的展示文本——
 * 历史 {@code outputJson.summary}、开发者面板、观察流**全部不变**；
 * {@code payload} 是新增的结构化事实，第一版只由文章域填充，其余域返回空 map。
 * 所以这次迁移对学习 / 通用 / 钱包域是**零行为变更**。
 *
 * <p><b>它不是给 LLM 看的</b>：payload 只喂 Reducer。模型仍只看裁剪后的展示文本，
 * 直到 P2.2 后半段把决策上下文切到 {@code ArticleAgentState}。
 *
 * @param summary 展示摘要（原 observation 文本；null 视为空串）
 * @param payload 结构化事实；无结构化事实时为空 map（不是 null）
 */
public record AgentActionResult(String summary, Map<String, Object> payload) {

    public AgentActionResult {
        summary = summary == null ? "" : summary;
        // 用 unmodifiableMap 而不是 Map.copyOf：payload 的值允许为 null
        // （有些事实"存在但未知"，用 null 表达比塞空串更诚实，也避免调用侧到处判空）
        payload = payload == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }

    /** 只有展示文本——尚未提供结构化事实的动作（学习域 / 通用域 / 钱包域，以及文章域的失败分支） */
    public static AgentActionResult of(String summary) {
        return new AgentActionResult(summary, Map.of());
    }

    /** 展示文本 + 结构化事实 */
    public static AgentActionResult of(String summary, Map<String, Object> payload) {
        return new AgentActionResult(summary, payload);
    }

    /**
     * 追加快照式事实：返回新实例，不修改自身。
     *
     * <p>让填充 payload 的代码保持一行一个事实，不必在调用侧先建 Map 再传。
     */
    public AgentActionResult with(String key, Object value) {
        Map<String, Object> merged = new LinkedHashMap<>(payload);
        merged.put(key, value);
        return new AgentActionResult(summary, merged);
    }

    public boolean hasPayload() {
        return !payload.isEmpty();
    }

    /**
     * 换掉展示文本但**保留结构化事实**。
     *
     * <p>Runtime 需要给观察加来源标注（{@code tagObservation}）或裁剪后再进上下文，
     * 而 payload 是 Reducer 的输入，不能被这些展示层处理丢掉。
     */
    public AgentActionResult withSummary(String newSummary) {
        return new AgentActionResult(newSummary, payload);
    }
}
