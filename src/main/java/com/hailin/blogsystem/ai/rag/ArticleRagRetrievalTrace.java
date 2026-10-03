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
        ArticleRagSearchStrategy strategy,
        boolean gateApplied,
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

    private static List<Candidate> copyOf(List<Candidate> candidates) {
        return candidates == null ? List.of() : List.copyOf(candidates);
    }

    public record Candidate(Long articleId, Integer chunkIndex, String title) {
    }
}
