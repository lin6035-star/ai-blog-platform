package com.hailin.blogsystem.ai;

import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.openai.api.OpenAiApi;

import java.util.concurrent.atomic.AtomicReference;

/**
 * token 用量累加器：把每次 LLM 调用的 usage 累加起来。
 * 工具调用（tool calling）是多轮请求，每轮都有一个 usage，需要跨轮累计才是本次请求的真实用量。
 *
 * **流式场景必须配合 {@link #trackPeak} 使用，不能对每个 chunk 直接调用 {@link #add}。**
 * 原因见 {@link #trackPeak} 的注释——直接逐 chunk 累加会让结果成倍放大（实测 ×2）。
 */
public class TokenUsageAccumulator {

    private int promptTokens;
    private int completionTokens;
    private int totalTokens;

    /**
     * 被累加过的 LLM 调用次数（P3.0）。
     *
     * <p>为什么需要它：token 总量看不出"这些 token 是几次调用花掉的"。
     * P3 的两个候选（单调用模块化 vs 多标签 Route + Extractor）差别**恰恰在调用次数**——
     * 只看总 Token 会把"两次短调用"和"一次长调用"当成同一件事，而它们的
     * 延迟、限流风险、失败面完全不同。
     *
     * <p>{@link #add(Usage)} 正是"每次调用提交一次 usage"的位置（流式走峰值法，也是流末提交一次），
     * 所以在此自增等价于调用次数。
     */
    private int callCount;

    /** 隐藏推理 token（thinking-only 模型会把思考算进 completionTokens，见 collectNativeDetails） */
    private int reasoningTokens;

    /** 命中 prompt cache 的输入 token（不计费、也更快；不拆出来 A/B 的省钱结论会失真） */
    private int cachedTokens;

    /**
     * 跟踪单次流式调用的**峰值 usage**，流结束后用 {@link #add} 提交一次。
     *
     * **为什么不能对每个 chunk 直接 add**：同一个"累计 usage"会出现在**多个 chunk** 里。
     * OpenAI 规范是在流末尾补**一个** `choices:[]` 的收尾 chunk 带 usage，但兼容实现
     * （实测 Qwen）在最后一个正常 chunk 里**也带一份同样的累计值**——逐 chunk 累加就翻倍。
     *
     * **为什么取峰值等价于取最终值**：Spring AI 的 `UsageCalculator.getCumulativeUsage`
     * 返回的是"到目前为止的累计"（要么是上一轮的累计、要么是累计+本轮），**单调不减**，
     * 所以最大值就是最终累计。
     *
     * **为什么不取"最后一个 chunk 的 usage"**：带 usage 的 chunk 后面还跟着大量纯文本
     * chunk（usage 为空），取最后一个会取到空值。峰值法同时也**抗流中断**
     * （客户端断开时已经消耗的 token 仍然算得出来）。
     *
     * @param peak     单次流式调用的峰值容器，调用方持有
     * @param candidate 本 chunk 的 usage，允许为 null
     */
    public static void trackPeak(AtomicReference<Usage> peak, Usage candidate) {
        if (candidate == null || candidate.getTotalTokens() == null) {
            return;
        }
        Usage current = peak.get();
        if (current == null || current.getTotalTokens() == null
                || candidate.getTotalTokens() > current.getTotalTokens()) {
            peak.set(candidate);
        }
    }

    /**
     * 空安全累加：容器为 null 时静默忽略。
     * 用于「调用方可能不需要统计」的场景——非流式调用点的 usage 是出参，
     * 实现方不该为了统计去写 null 判断（也不该因为调用方传 null 就 NPE）。
     */
    public static void addTo(TokenUsageAccumulator accumulator, Usage usage) {
        if (accumulator != null) {
            accumulator.add(usage);
        }
    }

    /** 累加非 Spring AI 调用返回的整数 usage，例如 OpenRouter Decisions API。 */
    public static void addTo(TokenUsageAccumulator accumulator, int promptTokens, int completionTokens) {
        if (accumulator != null) {
            accumulator.add(promptTokens, completionTokens);
        }
    }

    public void add(Usage usage) {
        if (usage == null || usage.getPromptTokens() == null) {
            return;
        }
        callCount++;
        promptTokens += usage.getPromptTokens();
        int out = usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
        completionTokens += out;
        totalTokens += usage.getTotalTokens() == null ? usage.getPromptTokens() + out : usage.getTotalTokens();
        collectNativeDetails(usage);
    }

    public void add(int promptTokens, int completionTokens) {
        if (promptTokens < 0 || completionTokens < 0) {
            return;
        }
        callCount++;
        this.promptTokens += promptTokens;
        this.completionTokens += completionTokens;
        this.totalTokens += promptTokens + completionTokens;
    }

    /**
     * 采集 Provider 原生 usage 里的**细分项**（P3.0）。
     *
     * <p>为什么必须把这两项单独拆出来：
     * <ul>
     *   <li>{@code reasoningTokens}——当前判断模型 {@code qwen3.7-max} 是 thinking-only
     *       （见 {@code AiConfig.THINKING_ONLY_MODEL_PREFIXES}，思考关不掉），推理 token 会
     *       **混进 completionTokens**。不分出来，就会把"模型在思考"误读成"JSON 输出太长"，
     *       转头去优化一个根本不大的东西。</li>
     *   <li>{@code cachedTokens}——命中 prompt cache 的输入量。P3 要比较"砍 prompt"的实际收益，
     *       而缓存命中的输入不计费也更快；不拆出来，A/B 的省钱结论会失真。</li>
     * </ul>
     *
     * <p>只有 OpenAI 兼容实现（{@code OpenAiApi.Usage}）带这些细节，换 Provider 拿不到就保持 0——
     * 所以用 {@code instanceof} 而不是强转。
     */
    private void collectNativeDetails(Usage usage) {
        if (!(usage.getNativeUsage() instanceof OpenAiApi.Usage nativeUsage)) {
            return;
        }
        OpenAiApi.Usage.CompletionTokenDetails completion = nativeUsage.completionTokenDetails();
        if (completion != null && completion.reasoningTokens() != null) {
            reasoningTokens += completion.reasoningTokens();
        }
        OpenAiApi.Usage.PromptTokensDetails prompt = nativeUsage.promptTokensDetails();
        if (prompt != null && prompt.cachedTokens() != null) {
            cachedTokens += prompt.cachedTokens();
        }
    }

    /** 合并另一个累加器的用量（如把一个暂存容器并进另一个）。null 安全。 */
    public void add(TokenUsageAccumulator other) {
        if (other == null) {
            return;
        }
        callCount += other.getCallCount();
        reasoningTokens += other.getReasoningTokens();
        cachedTokens += other.getCachedTokens();
        promptTokens += other.getPromptTokens();
        completionTokens += other.getCompletionTokens();
        totalTokens += other.getTotalTokens();
    }

    public int getPromptTokens() {
        return promptTokens;
    }

    public int getCompletionTokens() {
        return completionTokens;
    }

    public int getTotalTokens() {
        return totalTokens;
    }

    /** P3.0：本次统计覆盖的 LLM 调用次数（含 repair 重试与「点名计划」的二次分类） */
    public int getCallCount() {
        return callCount;
    }

    /** P3.0：隐藏推理 token（completionTokens 的一部分，只有 Provider 原生 usage 提供） */
    public int getReasoningTokens() {
        return reasoningTokens;
    }

    /** P3.0：命中 prompt cache 的输入 token */
    public int getCachedTokens() {
        return cachedTokens;
    }
}
