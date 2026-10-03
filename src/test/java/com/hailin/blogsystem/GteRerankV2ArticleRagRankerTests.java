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
}
