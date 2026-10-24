package com.hailin.blogsystem;

import com.hailin.blogsystem.ai.memory.AiEpisodicMemoryIndexService;
import com.hailin.blogsystem.ai.memory.AiEpisodicMemoryRetrieveService;
import com.hailin.blogsystem.ai.memory.AiMemoryIndexService;
import com.hailin.blogsystem.ai.memory.AiMemoryRetrieveService;
import com.hailin.blogsystem.ai.rag.ArticleRagIndexService;
import com.hailin.blogsystem.ai.rag.ArticleRagRetrieveService;
import com.hailin.blogsystem.config.BlogAiProperties;
import org.apache.http.HttpHost;
import org.apache.http.ProtocolVersion;
import org.apache.http.message.BasicStatusLine;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.elasticsearch.ElasticsearchVectorStore;
import org.springframework.ai.vectorstore.elasticsearch.ElasticsearchVectorStoreOptions;
import org.springframework.ai.vectorstore.elasticsearch.autoconfigure.ElasticsearchVectorStoreAutoConfiguration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class AiVectorStoreCorpusWiringTests {

    private static final String CONFIG_CLASS =
            "com.hailin.blogsystem.config.AiVectorStoreConfig";
    private static final String ARTICLE_STORE = "articleRagVectorStore";
    private static final String MEMORY_STORE = "memoryVectorStore";

    @Test
    void createsIndependentArticleAndMemoryVectorStores() throws Exception {
        Class<?> configClass = Class.forName(CONFIG_CLASS);
        BlogAiProperties properties = new BlogAiProperties();
        Object corpus = properties.getRag().getClass().getMethod("getCorpus").invoke(properties.getRag());
        corpus.getClass().getMethod("setArticleIndexName", String.class).invoke(corpus, "article-rag-test");
        corpus.getClass().getMethod("setMemoryIndexName", String.class).invoke(corpus, "memory-rag-test");
        corpus.getClass().getMethod("setInitializeSchema", boolean.class).invoke(corpus, false);

        new ApplicationContextRunner()
                .withUserConfiguration(configClass)
                .withBean(BlogAiProperties.class, () -> properties)
                .withBean(RestClient.class, AiVectorStoreCorpusWiringTests::indexExistsRestClient)
                .withBean(EmbeddingModel.class, () -> mock(EmbeddingModel.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasBean(ARTICLE_STORE);
                    assertThat(context).hasBean(MEMORY_STORE);

                    VectorStore articleStore = context.getBean(ARTICLE_STORE, VectorStore.class);
                    VectorStore memoryStore = context.getBean(MEMORY_STORE, VectorStore.class);

                    assertThat(articleStore).isNotSameAs(memoryStore);
                    assertThat(indexName(articleStore)).isEqualTo("article-rag-test");
                    assertThat(indexName(memoryStore)).isEqualTo("memory-rag-test");
                    assertThat(initializeSchema(articleStore)).isFalse();
                    // 记忆索引必须允许自动建：线上 2026-10-18 换新服务器后 ES 是空实例，
                    // spring-ai-document-index 无人创建，而 ElasticsearchVectorStore.doAdd()
                    // 第一步就 indexExists()，缺失时抛 "Index not found" → 保存记忆事务回滚。
                    // 文章侧相反：article_rag_active 是 alias，由 rebuild 流程维护，必须 false。
                    assertThat(initializeSchema(memoryStore)).isTrue();
                });
    }

    @Test
    void memoryInitializeSchemaCanBeDisabledForEsFreeTestContexts() throws Exception {
        // 测试环境的约定：不依赖真实 ES（见 application.properties）。
        Class<?> configClass = Class.forName(CONFIG_CLASS);
        BlogAiProperties properties = new BlogAiProperties();
        Object corpus = properties.getRag().getClass().getMethod("getCorpus").invoke(properties.getRag());
        corpus.getClass().getMethod("setArticleIndexName", String.class).invoke(corpus, "article-rag-test");
        corpus.getClass().getMethod("setMemoryIndexName", String.class).invoke(corpus, "memory-rag-test");
        corpus.getClass().getMethod("setInitializeSchema", boolean.class).invoke(corpus, false);
        corpus.getClass().getMethod("setMemoryInitializeSchema", boolean.class).invoke(corpus, false);

        new ApplicationContextRunner()
                .withUserConfiguration(configClass)
                .withBean(BlogAiProperties.class, () -> properties)
                // 关掉后 bean 初始化阶段不应再发 ES 探测；这里故意给一个不可用的 RestClient
                .withBean(RestClient.class, () -> mock(RestClient.class))
                .withBean(EmbeddingModel.class, () -> mock(EmbeddingModel.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    VectorStore memoryStore = context.getBean(MEMORY_STORE, VectorStore.class);
                    assertThat(initializeSchema(memoryStore)).isFalse();
                });
    }

    @Test
    void applicationContextDoesNotCreateTheLegacyUnqualifiedVectorStore() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ElasticsearchVectorStoreAutoConfiguration.class))
                .withUserConfiguration(loadConfigClass())
                .withBean(BlogAiProperties.class, () -> {
                    BlogAiProperties properties = new BlogAiProperties();
                    properties.getRag().getCorpus().setInitializeSchema(false);
                    return properties;
                })
                .withBean(RestClient.class, AiVectorStoreCorpusWiringTests::indexExistsRestClient)
                .withBean(EmbeddingModel.class, () -> mock(EmbeddingModel.class))
                .run(context -> assertThat(context.getBeansOfType(VectorStore.class))
                        .containsOnlyKeys(ARTICLE_STORE, MEMORY_STORE));
    }

    /**
     * 让 {@code ElasticsearchVectorStore.afterPropertiesSet()} 的 indexExists() 返回 true，
     * 避免测试真的去建索引。
     *
     * <p>记忆 store 的 initializeSchema=true，bean 初始化阶段会发一次 HEAD 探测；
     * mock 不走真实连接，只能把 RestClient 的 performRequest 桩成 200 响应。
     */
    private static RestClient indexExistsRestClient() {
        try {
            RestClient restClient = mock(RestClient.class);
            Response response = mock(Response.class);
            given(response.getStatusLine()).willReturn(
                    new BasicStatusLine(new ProtocolVersion("HTTP", 1, 1), 200, "OK"));
            given(response.getHost()).willReturn(new HttpHost("localhost", 9200, "http"));
            given(response.getHeader("X-Elastic-Product")).willReturn("Elasticsearch");
            given(restClient.performRequest(any(Request.class))).willReturn(response);
            return restClient;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void articleServicesRequireArticleStoreAndMemoryServicesRequireMemoryStore() {
        assertVectorStoreQualifier(ArticleRagIndexService.class, ARTICLE_STORE);
        assertVectorStoreQualifier(ArticleRagRetrieveService.class, ARTICLE_STORE);

        List.of(
                AiMemoryIndexService.class,
                AiMemoryRetrieveService.class,
                AiEpisodicMemoryIndexService.class,
                AiEpisodicMemoryRetrieveService.class
        ).forEach(type -> assertVectorStoreQualifier(type, MEMORY_STORE));
    }

    private void assertVectorStoreQualifier(Class<?> serviceType, String expectedBeanName) {
        Constructor<?> constructor = assertThat(serviceType.getDeclaredConstructors())
                .singleElement()
                .actual();

        Parameter vectorStoreParameter = assertThat(constructor.getParameters())
                .filteredOn(parameter -> parameter.getType().equals(VectorStore.class))
                .singleElement()
                .actual();

        Qualifier qualifier = vectorStoreParameter.getAnnotation(Qualifier.class);
        assertThat(qualifier)
                .as("%s 的 VectorStore 构造器参数必须显式限定 corpus", serviceType.getSimpleName())
                .isNotNull();
        assertThat(qualifier.value()).isEqualTo(expectedBeanName);
    }

    private String indexName(VectorStore vectorStore) {
        ElasticsearchVectorStoreOptions options = (ElasticsearchVectorStoreOptions)
                ReflectionTestUtils.getField(vectorStore, "options");
        assertThat(options).isNotNull();
        return options.getIndexName();
    }

    private boolean initializeSchema(VectorStore vectorStore) {
        assertThat(vectorStore).isInstanceOf(ElasticsearchVectorStore.class);
        Boolean value = (Boolean) ReflectionTestUtils.getField(vectorStore, "initializeSchema");
        assertThat(value).isNotNull();
        return value;
    }

    private Class<?> loadConfigClass() {
        try {
            return Class.forName(CONFIG_CLASS);
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
    }
}
