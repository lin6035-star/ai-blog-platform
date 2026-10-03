package com.hailin.blogsystem;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.json.JsonData;
import co.elastic.clients.transport.ElasticsearchTransport;
import com.hailin.blogsystem.ai.rag.ArticleRagConsistencyService;
import com.hailin.blogsystem.ai.rag.ArticleRagIndexService;
import com.hailin.blogsystem.ai.rag.ArticleRagKeywordRetriever;
import com.hailin.blogsystem.ai.rag.index.ArticleRagIndexHealthService;
import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.mapper.ArticlesMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ArticleRagCorpusIndexTargetTests {

    @Test
    void keywordRetrieverUsesArticleCorpusIndex() throws Exception {
        BlogAiProperties properties = propertiesWithSeparateCorpusIndexes();
        AtomicReference<SearchRequest> captured = new AtomicReference<>();
        ElasticsearchClient client = clientCapturingSearch(captured);

        ArticleRagKeywordRetriever retriever = newInstance(
                ArticleRagKeywordRetriever.class,
                new Class<?>[]{ElasticsearchClient.class, BlogAiProperties.class},
                client,
                properties
        );

        retriever.retrieve("Redis");

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().index()).containsExactly("article-rag-test");
    }

    @Test
    void keywordRetrieverUsesAndOperatorToAvoidOrNoise() throws Exception {
        BlogAiProperties properties = propertiesWithSeparateCorpusIndexes();
        AtomicReference<SearchRequest> captured = new AtomicReference<>();
        ElasticsearchClient client = clientCapturingSearch(captured);

        ArticleRagKeywordRetriever retriever = newInstance(
                ArticleRagKeywordRetriever.class,
                new Class<?>[]{ElasticsearchClient.class, BlogAiProperties.class},
                client,
                properties
        );

        retriever.retrieve("Redis 缓存");

        assertThat(captured.get().query().bool().must()).hasSize(1);
        assertThat(captured.get().query().bool().must().get(0).match().operator())
                .isEqualTo(Operator.And);
        assertThat(captured.get().query().bool().filter())
                .anySatisfy(filter -> {
                    assertThat(filter.term().field()).isEqualTo("metadata.articleStatus");
                    assertThat(filter.term().value().longValue()).isEqualTo(1L);
                });
    }

    @Test
    void keywordFallbackUsesBoundedOrQuery() throws Exception {
        BlogAiProperties properties = propertiesWithSeparateCorpusIndexes();
        AtomicReference<SearchRequest> captured = new AtomicReference<>();
        ElasticsearchClient client = clientCapturingSearch(captured);

        ArticleRagKeywordRetriever retriever = newInstance(
                ArticleRagKeywordRetriever.class,
                new Class<?>[]{ElasticsearchClient.class, BlogAiProperties.class},
                client,
                properties
        );

        retriever.retrieveFallback("Redis 缓存");

        assertThat(captured.get().size()).isEqualTo(5);
        assertThat(captured.get().query().bool().must().get(0).match().operator())
                .isEqualTo(Operator.Or);
    }

    @Test
    void consistencyCheckUsesArticleCorpusIndex() throws Exception {
        BlogAiProperties properties = propertiesWithSeparateCorpusIndexes();
        AtomicReference<SearchRequest> captured = new AtomicReference<>();
        ElasticsearchClient client = clientCapturingSearch(captured);
        ArticlesMapper articlesMapper = mock(ArticlesMapper.class);
        when(articlesMapper.selectList(any())).thenReturn(List.of());

        ArticleRagConsistencyService service = newInstance(
                ArticleRagConsistencyService.class,
                new Class<?>[]{
                        ArticlesMapper.class,
                        ElasticsearchClient.class,
                        ArticleRagIndexService.class,
                        BlogAiProperties.class,
                        ArticleRagIndexHealthService.class
                },
                articlesMapper,
                client,
                mock(ArticleRagIndexService.class),
                properties,
                mock(ArticleRagIndexHealthService.class)
        );

        ReflectionTestUtils.invokeMethod(service, "loadIndexedArticleIds");

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().index()).containsExactly("article-rag-test");
    }

    private BlogAiProperties propertiesWithSeparateCorpusIndexes() {
        BlogAiProperties properties = new BlogAiProperties();
        properties.getRag().getCorpus().setArticleIndexName("article-rag-test");
        properties.getRag().getCorpus().setMemoryIndexName("memory-rag-test");
        return properties;
    }

    private ElasticsearchClient clientCapturingSearch(AtomicReference<SearchRequest> captured) throws Exception {
        ElasticsearchTransport transport = mock(ElasticsearchTransport.class);
        SearchResponse<JsonData> response = SearchResponse.of(builder -> builder
                .took(0)
                .timedOut(false)
                .shards(shards -> shards
                        .total(1)
                        .successful(1)
                        .failed(0))
                .hits(hits -> hits.hits(List.of()))
        );

        doAnswer(invocation -> {
            captured.set(invocation.getArgument(0));
            return response;
        }).when(transport).performRequest(any(), any(), any());

        return new ElasticsearchClient(transport);
    }

    private <T> T newInstance(
            Class<T> type,
            Class<?>[] parameterTypes,
            Object... arguments
    ) throws Exception {
        Constructor<T> constructor = type.getDeclaredConstructor(parameterTypes);
        return constructor.newInstance(arguments);
    }
}
