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
            } else if (hasTitleEvidence(lexicalQuery, candidate.context())) {
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

    /**
     * 标题证据：**这个词组是不是这篇文章的主题**。
     *
     * <p>这是 Gate 里最可靠的一条放行通道，因为**标题是作者自己定的主题标签**——
     * 它能把"主题就是它"和"顺带提到了它"分开，而 rerank 的分数做不到这件事。
     *
     * <p>实测（2026-10-13，问"有没有关于缓存的文章"）：
     * <ul>
     *   <li>正文含"缓存"的 **10 篇**，标题含"缓存"的只有 **2 篇**——"提及"是"主题"的五倍。
     *       而且《MySQL 核心机制》（顺带提了缓存）得 0.7182，**高于**《Redis 缓存工程化实践》
     *       的 0.6786：两类的分数是**重叠**的，拿绝对分数切不开</li>
     *   <li>同理"Java"：标题命中 4 篇，正文命中十几篇</li>
     * </ul>
     *
     * <p>⚠️ <b>原来的判定是"标题与查询整串相等"（{@code equals}），等于从未生效过</b>
     * ——谁会拿"缓存"两个字当标题？这里放宽成"标题包含这些词"。
     *
     * <p>按空白拆词后**逐词判断**，而不是整串 {@code contains}，原因有二：
     * <ul>
     *   <li>词序倒置（"缓存 Redis" vs 标题"Redis 缓存…"）整串匹配会漏，逐词不会</li>
     *   <li>{@code lexicalQuery} 里可能残留标点（日志里出现过 {@code "Redis ？"}），
     *       拆出的"？"经 {@link #normalize} 后是空串，应当**跳过而不是判失败**</li>
     * </ul>
     *
     * <p>全空（比如查询只有标点）时返回 false —— 那不构成任何证据，该交回给分数门判断。
     */
    private boolean hasTitleEvidence(String lexicalQuery, ArticleRagContext context) {
        String title = normalize(context == null ? null : context.title());
        if (title.isBlank() || lexicalQuery == null || lexicalQuery.isBlank()) {
            return false;
        }

        boolean hasAnyToken = false;
        for (String rawToken : lexicalQuery.trim().split("\\s+")) {
            String token = normalize(rawToken);
            if (token.isBlank()) {
                continue;
            }
            hasAnyToken = true;
            if (!title.contains(token)) {
                return false;
            }
        }
        return hasAnyToken;
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
