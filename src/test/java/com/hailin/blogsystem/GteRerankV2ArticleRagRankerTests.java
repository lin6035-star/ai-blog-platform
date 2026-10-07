package com.hailin.blogsystem;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.ai.rag.DefaultArticleRagRanker;
import com.hailin.blogsystem.ai.rag.GteRerankV2ArticleRagRanker;
import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.entity.dto.ArticleRagContext;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GteRerankV2ArticleRagRankerTests {

    @Test
    void parseResultKeepsRelevanceScoreAndRank() throws Exception {
        GteRerankV2ArticleRagRanker ranker = new GteRerankV2ArticleRagRanker(
                new BlogAiProperties(),
                new DefaultArticleRagRanker()
        );
        List<ArticleRagContext> contexts = List.of(
                new ArticleRagContext(1L, "A", 0, "alpha"),
                new ArticleRagContext(2L, "B", 0, "beta")
        );
        JsonNode response = new ObjectMapper().readTree("""
                {"output":{"results":[
                  {"index":1,"relevance_score":0.87},
                  {"index":0,"relevance_score":0.42}
                ]}}
                """);

        Object parsed = ReflectionTestUtils.invokeMethod(ranker, "parseRanked", response, contexts, 5);
        assertThat(parsed).isInstanceOf(List.class);
        Object first = ((List<?>) parsed).get(0);

        assertThat(first.getClass().getMethod("relevanceScore").invoke(first)).isEqualTo(0.87D);
        assertThat(first.getClass().getMethod("rank").invoke(first)).isEqualTo(1);
        assertThat(first.getClass().getMethod("context").invoke(first)).isEqualTo(contexts.get(1));
    }

    @Test
    void selectRerankCandidatesCapsChunksPerArticleAndKeepsLaterArticles() {
        GteRerankV2ArticleRagRanker ranker = new GteRerankV2ArticleRagRanker(
                new BlogAiProperties(),
                new DefaultArticleRagRanker()
        );
        List<ArticleRagContext> contexts = List.of(
                context(1L, 0),
                context(1L, 1),
                context(1L, 2),
                context(1L, 3),
                context(2L, 0),
                context(3L, 0)
        );

        Object selected = ReflectionTestUtils.invokeMethod(
                ranker,
                "selectRerankCandidates",
                contexts,
                5,
                2
        );

        assertThat(selected).isEqualTo(List.of(
                contexts.get(0),
                contexts.get(1),
                contexts.get(4),
                contexts.get(5)
        ));
    }

    @Test
    void selectRerankCandidatesDoesNotCollapseContextsWithoutArticleId() {
        GteRerankV2ArticleRagRanker ranker = new GteRerankV2ArticleRagRanker(
                new BlogAiProperties(),
                new DefaultArticleRagRanker()
        );
        List<ArticleRagContext> contexts = List.of(
                context(null, 0),
                context(null, 1),
                context(1L, 0)
        );

        Object selected = ReflectionTestUtils.invokeMethod(
                ranker,
                "selectRerankCandidates",
                contexts,
                3,
                1
        );

        assertThat(selected).isEqualTo(contexts);
    }

    private ArticleRagContext context(Long articleId, int chunkIndex) {
        return new ArticleRagContext(articleId, "article-" + articleId, chunkIndex, "chunk-" + chunkIndex);
    }
}
