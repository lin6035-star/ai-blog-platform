package com.hailin.blogsystem.ai.workflow;

import com.hailin.blogsystem.ai.TokenUsageAccumulator;

/**
 * 流式 LLM 调用失败时携带本次调用已经消耗的 usage。
 *
 * <p>流可能在收到 usage 后才断开，或者收到 usage 但只返回空内容。
 * 如果只抛普通 RuntimeException，Workflow 的失败 step 无法知道这次调用已经花了多少 token。</p>
 */
public class LlmStreamException extends RuntimeException {

    private final TokenUsageAccumulator usage;

    public LlmStreamException(
            String message,
            Throwable cause,
            TokenUsageAccumulator usage
    ) {
        super(message, cause);
        this.usage = usage == null ? new TokenUsageAccumulator() : usage;
    }

    public TokenUsageAccumulator usage() {
        return usage;
    }

    public LlmStreamException withUsage(TokenUsageAccumulator accumulatedUsage) {
        return new LlmStreamException(getMessage(), getCause(), accumulatedUsage);
    }
}
