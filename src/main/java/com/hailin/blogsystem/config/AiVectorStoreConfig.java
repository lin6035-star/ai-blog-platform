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
                corpus,
                corpus.isInitializeSchema()
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
                corpus,
                // 记忆索引是**物理索引**，但项目里没有它的重建/初始化流程：
                // 新环境（如 2026-10-18 换的服务器，ES 数据卷全新）里索引不存在时，
                // ElasticsearchVectorStore.doAdd() 第一步 indexExists() 就抛
                // IllegalArgumentException("Index not found")，而保存记忆是在 @Transactional
                // 里写 ES 的 → 事务回滚 → 前端「保存记忆失败」；召回失败则被 fail-open 吞掉，
                // 表现为 AI 静默读不到记忆。所以记忆侧默认开启自动建索引，
                // 文章侧不能开（article_rag_active 是 alias，见 initializeSchema 注释）。
                corpus.isMemoryInitializeSchema()
        );
    }

    private ElasticsearchVectorStore buildVectorStore(
            RestClient restClient,
            EmbeddingModel embeddingModel,
            String indexName,
            BlogAiProperties.Corpus corpus,
            boolean initializeSchema
    ) {
        ElasticsearchVectorStoreOptions options = new ElasticsearchVectorStoreOptions();
        options.setIndexName(indexName);
        options.setDimensions(corpus.getDimensions());

        return ElasticsearchVectorStore.builder(restClient, embeddingModel)
                .options(options)
                .initializeSchema(initializeSchema)
                .build();
    }
}
