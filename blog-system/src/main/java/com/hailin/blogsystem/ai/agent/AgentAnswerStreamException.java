package com.hailin.blogsystem.ai.agent;

import com.hailin.blogsystem.ai.TokenUsageAccumulator;

/** 最终正文生成失败；携带已经消耗的 usage 和是否已向外部交付正文。 */
public class AgentAnswerStreamException extends RuntimeException {

    private final TokenUsageAccumulator usage;
    private final boolean deliveredAnyChunk;

    public AgentAnswerStreamException(
            String message,
            Throwable cause,
            TokenUsageAccumulator usage,
            boolean deliveredAnyChunk
    ) {
        super(message, cause);
        this.usage = usage;
        this.deliveredAnyChunk = deliveredAnyChunk;
    }

    public TokenUsageAccumulator usage() {
        return usage;
    }

    public boolean deliveredAnyChunk() {
        return deliveredAnyChunk;
    }
}
