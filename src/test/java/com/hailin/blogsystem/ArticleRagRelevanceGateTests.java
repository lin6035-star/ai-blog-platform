package com.hailin.blogsystem;

import com.hailin.blogsystem.ai.rag.ArticleRagRankResult;
import com.hailin.blogsystem.ai.rag.ArticleRagRelevanceGate;
import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.entity.dto.ArticleRagContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ArticleRagRelevanceGateTests {

    private final BlogAiProperties properties = new BlogAiProperties();
    private final ArticleRagRelevanceGate gate = new ArticleRagRelevanceGate(properties);

    @Test
    void acceptsOnlyModelCandidatesAtOrAboveCalibratedThreshold() {
        ArticleRagContext accepted = context(1L, "Redis 缓存");
        ArticleRagContext rejected = context(2L, "Kafka 消息队列");
        ArticleRagRankResult rankResult = new ArticleRagRankResult(
                ArticleRagRankResult.Status.MODEL_APPLIED,
                List.of(
                        new ArticleRagRankResult.Candidate(accepted, 0.75D, 1),
                        new ArticleRagRankResult.Candidate(rejected, 0.7499D, 2)
                )
        );

        ArticleRagRelevanceGate.Result result = gate.apply("缓存一致性", rankResult);

        assertThat(result.acceptedContexts()).containsExactly(accepted);
        assertThat(result.decisions()).extracting(ArticleRagRelevanceGate.Decision::reason)
                .containsExactly(
                        ArticleRagRelevanceGate.Reason.ACCEPT_RERANK,
                        ArticleRagRelevanceGate.Reason.REJECT_LOW_RELEVANCE
                );
    }

    @Test
    void rerankUnavailableOnlyAllowsExactNormalizedTitleEvidence() {
        ArticleRagContext exactTitle = context(1L, "Java中的@Builder注解详解：优雅创建对象的建造者模式");
        ArticleRagContext semanticOnly = context(2L, "Java 语言全景");
        ArticleRagRankResult rankResult = ArticleRagRankResult.unavailable(
                List.of(exactTitle, semanticOnly)
        );

        ArticleRagRelevanceGate.Result result = gate.apply(
                "Java中的@Builder注解详解：优雅创建对象的建造者模式",
                rankResult
        );

        assertThat(result.acceptedContexts()).containsExactly(exactTitle);
        assertThat(result.decisions()).extracting(ArticleRagRelevanceGate.Decision::reason)
                .containsExactly(
                        ArticleRagRelevanceGate.Reason.ACCEPT_STRONG_LEXICAL,
                        ArticleRagRelevanceGate.Reason.REJECT_RERANK_UNAVAILABLE
                );
    }

    private ArticleRagContext context(long id, String title) {
        return new ArticleRagContext(id, title, 0, "content");
    }
}
