package com.hailin.blogsystem.ai.agent;

import com.hailin.blogsystem.ai.TokenUsageAccumulator;

/** Article Generator 的权威结果；SSE chunk 只负责展示，不参与正文拼装。 */
public record GeneratedAnswer(
        String content,
        TokenUsageAccumulator usage,
        boolean deliveredAnyChunk
) {
}
