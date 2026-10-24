package com.hailin.blogsystem.ai.rag;

import com.hailin.blogsystem.entity.dto.ArticleRagContext;

import java.util.List;

public record ArticleRagRankResult(Status status, List<Candidate> candidates) {

    public ArticleRagRankResult {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    public static ArticleRagRankResult unavailable(List<ArticleRagContext> contexts) {
        return new ArticleRagRankResult(Status.RERANK_UNAVAILABLE, toCandidates(contexts, false));
    }

    public static ArticleRagRankResult empty() {
        return new ArticleRagRankResult(Status.EMPTY, List.of());
    }

    public List<ArticleRagContext> contexts() {
        return candidates.stream().map(Candidate::context).toList();
    }

    private static List<Candidate> toCandidates(List<ArticleRagContext> contexts, boolean scored) {
        if (contexts == null || contexts.isEmpty()) {
            return List.of();
        }
        return java.util.stream.IntStream.range(0, contexts.size())
                .mapToObj(index -> new Candidate(contexts.get(index), scored ? 0D : null, index + 1))
                .toList();
    }

    public enum Status {
        EMPTY,
        MODEL_APPLIED,
        RERANK_UNAVAILABLE
    }

    public record Candidate(ArticleRagContext context, Double relevanceScore, int rank) {
    }
}
