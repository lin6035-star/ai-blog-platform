package com.hailin.blogsystem.ai.rag;

import com.hailin.blogsystem.entity.dto.ArticleRagContext;
import com.hailin.blogsystem.entity.dto.ArticleRagSearchStrategy;

import java.util.List;

public record ArticleRagRetrievalTrace(
        String question,
        String intent,
        String lexicalQuery,
        boolean routed,
        String skipReason,
        List<Candidate> vectorCandidates,
        List<Candidate> keywordCandidates,
        List<Candidate> mergedCandidates,
        List<Candidate> rankedCandidates,
        List<Candidate> finalCandidates,
        /**
         * Gate 对每条候选的裁决与原因。缺了它就只能知道"拒了几条"，无法回答"为什么拒"——
         * 而"Gate 拒绝原因"正是评测要看的指标之一。
         */
        List<GateDecision> gateDecisions,
        ArticleRagSearchStrategy strategy,
        boolean gateApplied,
        /** keyword 零命中后是否触发了受限 OR fallback；用于统计 fallback 恢复率。 */
        boolean keywordFallbackTriggered,
        long vectorElapsedMs,
        long keywordElapsedMs,
        long rankElapsedMs,
        long totalElapsedMs
) {

    public ArticleRagRetrievalTrace {
        vectorCandidates = copyOf(vectorCandidates);
        keywordCandidates = copyOf(keywordCandidates);
        mergedCandidates = copyOf(mergedCandidates);
        rankedCandidates = copyOf(rankedCandidates);
        finalCandidates = copyOf(finalCandidates);
        gateDecisions = gateDecisions == null ? List.of() : List.copyOf(gateDecisions);
    }

    public static List<Candidate> candidatesOf(List<ArticleRagContext> contexts) {
        if (contexts == null || contexts.isEmpty()) {
            return List.of();
        }
        return contexts.stream()
                .map(context -> new Candidate(
                        context.articleId(),
                        context.chunkIndex(),
                        context.title()
                ))
                .toList();
    }

    /** 把 Gate 的裁决转成可序列化的 trace 形态（不直接持有 gate 内部类型）。 */
    public static List<GateDecision> decisionsOf(List<ArticleRagRelevanceGate.Decision> decisions) {
        if (decisions == null || decisions.isEmpty()) {
            return List.of();
        }
        return decisions.stream()
                .map(decision -> new GateDecision(
                        decision.context() == null ? null : decision.context().articleId(),
                        decision.context() == null ? null : decision.context().chunkIndex(),
                        decision.rerankScore(),
                        decision.reason() == null ? null : decision.reason().name()
                ))
                .toList();
    }

    private static List<Candidate> copyOf(List<Candidate> candidates) {
        return candidates == null ? List.of() : List.copyOf(candidates);
    }

    public record Candidate(Long articleId, Integer chunkIndex, String title) {
    }

    public record GateDecision(Long articleId, Integer chunkIndex, Double rerankScore, String reason) {
    }
}
