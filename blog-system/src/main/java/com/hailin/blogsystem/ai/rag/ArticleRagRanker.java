package com.hailin.blogsystem.ai.rag;

import com.hailin.blogsystem.entity.dto.ArticleRagContext;

import java.util.List;
//预留rerank扩展点，当前no-op
public interface ArticleRagRanker {
    List<ArticleRagContext> rank(String question,List<ArticleRagContext> contexts);

    default ArticleRagRankResult rankWithEvidence(String question, List<ArticleRagContext> contexts) {
        List<ArticleRagContext> ranked = rank(question, contexts);
        return ranked == null || ranked.isEmpty()
                ? ArticleRagRankResult.empty()
                : ArticleRagRankResult.unavailable(ranked);
    }
}
