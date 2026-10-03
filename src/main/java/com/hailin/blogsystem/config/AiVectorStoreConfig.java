package com.hailin.blogsystem.config;

import org.elasticsearch.client.RestClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.elasticsearch.ElasticsearchVectorStore;
import org.springframework.ai.vectorstore.elasticsearch.ElasticsearchVectorStoreOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class AiVectorStoreConfig {

    public static final String ARTICLE_RAG_VECTOR_STORE = "articleRagVectorStore";
    public static final String MEMORY_VECTOR_STORE = "memoryVectorStore";

    @Bean(ARTICLE_RAG_VECTOR_STORE)
    @Primary
    ElasticsearchVectorStore articleRagVectorStore(
            RestClient restClient,
            EmbeddingModel embeddingModel,
            BlogAiProperties properties
    ) {
        BlogAiProperties.Corpus corpus = properties.getRag().getCorpus();
        return buildVectorStore(
                restClient,
                embeddingModel,
                corpus.getArticleIndexName(),
                corpus
        );
    }

    @Bean(MEMORY_VECTOR_STORE)
    ElasticsearchVectorStore memoryVectorStore(
            RestClient restClient,
            EmbeddingModel embeddingModel,
            BlogAiProperties properties
    ) {
        BlogAiProperties.Corpus corpus = properties.getRag().getCorpus();
        return buildVectorStore(
                restClient,
                embeddingModel,
                corpus.getMemoryIndexName(),
                corpus
        );
    }

    private ElasticsearchVectorStore buildVectorStore(
            RestClient restClient,
            EmbeddingModel embeddingModel,
            String indexName,
            BlogAiProperties.Corpus corpus
    ) {
        ElasticsearchVectorStoreOptions options = new ElasticsearchVectorStoreOptions();
        options.setIndexName(indexName);
        options.setDimensions(corpus.getDimensions());

        return ElasticsearchVectorStore.builder(restClient, embeddingModel)
                .options(options)
                .initializeSchema(corpus.isInitializeSchema())
                .build();
    }
}
