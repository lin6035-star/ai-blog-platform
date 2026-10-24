package com.hailin.blogsystem;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.hailin.blogsystem.ai.rag.ArticleRagConsistencyService;
import com.hailin.blogsystem.ai.rag.ArticleRagIndexService;
import com.hailin.blogsystem.ai.rag.index.ArticleRagIndexHealthService;
import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.exception.BusinessException;
import com.hailin.blogsystem.mapper.ArticlesMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ArticleRagConsistencyHealthTests {

    @Test
    void refusesLocalRepairWhenVectorIndexIsNotHealthy() {
        ArticleRagIndexHealthService healthService = mock(ArticleRagIndexHealthService.class);
        when(healthService.incrementalWritesAllowed()).thenReturn(false);
        when(healthService.current()).thenReturn(new ArticleRagIndexHealthService.Snapshot(
                ArticleRagIndexHealthService.State.UNHEALTHY,
                "canary cosine too low",
                0.22D,
                java.time.Instant.now(),
                "article-rag-old"
        ));
        ArticleRagConsistencyService service = new ArticleRagConsistencyService(
                mock(ArticlesMapper.class),
                mock(ElasticsearchClient.class),
                mock(ArticleRagIndexService.class),
                new BlogAiProperties(),
                healthService
        );

        assertThatThrownBy(service::repair)
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("索引不健康")
                .hasMessageContaining("canary cosine too low");
    }
}
