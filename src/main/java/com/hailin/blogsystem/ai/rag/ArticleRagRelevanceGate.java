package com.hailin.blogsystem.ai.rag;

import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.entity.dto.ArticleRagContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class ArticleRagRelevanceGate {

    private final BlogAiProperties properties;

    public ArticleRagRelevanceGate(BlogAiProperties properties) {
        this.properties = properties;
    }

    public Result apply(String lexicalQuery, ArticleRagRankResult rankResult) {
        if (rankResult == null || rankResult.candidates().isEmpty()) {
            return new Result(List.of(), List.of());
        }

        BlogAiProperties.Gate gate = properties.getRag().getGate();
        boolean enabled = gate != null && gate.isEnabled();
        double threshold = gate == null ? 0.75D : gate.getRerankThreshold();
        List<ArticleRagContext> accepted = new ArrayList<>();
        List<Decision> decisions = new ArrayList<>();

        for (ArticleRagRankResult.Candidate candidate : rankResult.candidates()) {
            Reason reason;
            if (!enabled) {
                reason = Reason.ACCEPT_GATE_DISABLED;
            } else if (hasExactTitleEvidence(lexicalQuery, candidate.context())) {
                reason = Reason.ACCEPT_STRONG_LEXICAL;
            } else if (rankResult.status() == ArticleRagRankResult.Status.MODEL_APPLIED
                    && candidate.relevanceScore() != null
                    && candidate.relevanceScore() >= threshold) {
                reason = Reason.ACCEPT_RERANK;
            } else if (rankResult.status() != ArticleRagRankResult.Status.MODEL_APPLIED
                    || candidate.relevanceScore() == null) {
                reason = Reason.REJECT_RERANK_UNAVAILABLE;
            } else {
                reason = Reason.REJECT_LOW_RELEVANCE;
            }

            boolean acceptedCandidate = reason.accepted();
            if (acceptedCandidate) {
                accepted.add(candidate.context());
            }
            decisions.add(new Decision(candidate.context(), candidate.relevanceScore(), reason));
        }

        return new Result(accepted, decisions);
    }

    private boolean hasExactTitleEvidence(String lexicalQuery, ArticleRagContext context) {
        String query = normalize(lexicalQuery);
        String title = normalize(context == null ? null : context.title());
        return !query.isBlank() && query.equals(title);
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9+.#\\u4e00-\\u9fa5]", "");
    }

    public record Result(List<ArticleRagContext> acceptedContexts, List<Decision> decisions) {
        public Result {
            acceptedContexts = acceptedContexts == null ? List.of() : List.copyOf(acceptedContexts);
            decisions = decisions == null ? List.of() : List.copyOf(decisions);
        }
    }

    public record Decision(ArticleRagContext context, Double rerankScore, Reason reason) {
    }

    public enum Reason {
        ACCEPT_GATE_DISABLED(true),
        ACCEPT_STRONG_LEXICAL(true),
        ACCEPT_RERANK(true),
        REJECT_RERANK_UNAVAILABLE(false),
        REJECT_LOW_RELEVANCE(false);

        private final boolean accepted;

        Reason(boolean accepted) {
            this.accepted = accepted;
        }

        public boolean accepted() {
            return accepted;
        }
    }
}
