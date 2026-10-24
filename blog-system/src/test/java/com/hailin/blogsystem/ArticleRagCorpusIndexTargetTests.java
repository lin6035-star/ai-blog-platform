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
        /*
         * 必须是**带标题权重的 multi_match**（原来是只查 content 的单字段 match）。
         * 只查 content 时，"顺带提到"会盖过"主题就是它"——这正是高频配角词
         * （Java / 缓存）只找得回一两篇、而"消息队列"这类专属主题词正常的原因。
         * 所以这里同时锁住：① 标题进了检索字段且在权重位；② AND 语义没丢。
         */
        assertThat(captured.get().query().bool().must().get(0).multiMatch().operator())
                .isEqualTo(Operator.And);
        assertThat(captured.get().query().bool().must().get(0).multiMatch().fields())
                .containsExactly("metadata.title^3", "content");
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

        // size 现在来自配置（原来是硬编码的 5）。断言"等于配置值"而不是某个字面量：
        // 调参不会打破这条用例，但"忘了把配置接进来"仍然会被抓到
        assertThat(captured.get().size())
                .isEqualTo(properties.getRag().getKeyword().getFetchSize());
        // 兜底路径同样走「带标题权重的 multi_match」，只是语义放宽成 OR
        assertThat(captured.get().query().bool().must().get(0).multiMatch().operator())
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
