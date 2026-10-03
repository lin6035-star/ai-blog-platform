package com.hailin.blogsystem;

import com.hailin.blogsystem.ai.memory.AiEpisodicMemoryIndexService;
import com.hailin.blogsystem.ai.memory.AiEpisodicMemoryRetrieveService;
import com.hailin.blogsystem.ai.memory.AiMemoryIndexService;
import com.hailin.blogsystem.ai.memory.AiMemoryRetrieveService;
import com.hailin.blogsystem.ai.rag.ArticleRagIndexService;
import com.hailin.blogsystem.ai.rag.ArticleRagRetrieveService;
import com.hailin.blogsystem.config.BlogAiProperties;
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

import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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
                .withBean(RestClient.class, () -> mock(RestClient.class))
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
                .withBean(RestClient.class, () -> mock(RestClient.class))
                .withBean(EmbeddingModel.class, () -> mock(EmbeddingModel.class))
                .run(context -> assertThat(context.getBeansOfType(VectorStore.class))
                        .containsOnlyKeys(ARTICLE_STORE, MEMORY_STORE));
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
