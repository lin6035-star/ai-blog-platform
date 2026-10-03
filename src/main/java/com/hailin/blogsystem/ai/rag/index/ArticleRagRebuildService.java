package com.hailin.blogsystem.ai.rag.index;

import com.hailin.blogsystem.ai.rag.ArticleRagIndexService;
import com.hailin.blogsystem.config.BlogAiProperties;
import org.elasticsearch.client.RestClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.elasticsearch.ElasticsearchVectorStore;
import org.springframework.ai.vectorstore.elasticsearch.ElasticsearchVectorStoreOptions;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

/** 隔离构建文章索引，校验完成后才切换 active alias。 */
@Service
public class ArticleRagRebuildService {

    private final ArticleRagIndexManager indexManager;
    private final ArticleRagIndexService indexService;
    private final Validator validator;
    private final RestClient restClient;
    private final EmbeddingModel embeddingModel;
    private final BlogAiProperties properties;
    private final ArticleRagCanary canary;
    private final ArticleRagIndexHealthService healthService;
    private final IndexRebuildLock rebuildLock;

    public ArticleRagRebuildService(
            ArticleRagIndexManager indexManager,
            ArticleRagIndexService indexService,
            Validator validator,
            RestClient restClient,
            EmbeddingModel embeddingModel,
            BlogAiProperties properties,
            ArticleRagCanary canary,
            ArticleRagIndexHealthService healthService,
            IndexRebuildLock rebuildLock
    ) {
        this.indexManager = indexManager;
        this.indexService = indexService;
        this.validator = validator;
        this.restClient = restClient;
        this.embeddingModel = embeddingModel;
        this.properties = properties;
        this.canary = canary;
        this.healthService = healthService;
        this.rebuildLock = rebuildLock;
    }

    public RebuildOutcome rebuild() throws IOException {
        // 全局互斥：并发重建会互相覆盖 alias 并留下孤儿物理索引。
        // Redis 不可用时 tryAcquire 抛异常 → 本次重建被拒绝（没有 DB CAS 可兜底，必须 fail-closed）。
        String lockToken = rebuildLock.tryAcquire();
        if (lockToken == null) {
            throw new IllegalStateException("已有索引重建在进行中，请稍后再试");
        }

        try {
            return doRebuild();
        } finally {
            rebuildLock.release(lockToken);
        }
    }

    private RebuildOutcome doRebuild() throws IOException {
        ArticleRagIndexHealthService.Snapshot previousHealth = healthService.beginRebuild();
        BlogAiProperties.Corpus corpus = properties.getRag().getCorpus();
        String physicalIndex = indexManager.physicalIndexName(
                corpus.getEmbeddingModel(),
                corpus.getDimensions(),
                "chunk-v1"
        );

        try {
            indexManager.createPhysicalIndex(physicalIndex, corpus.getDimensions());
            VectorStore target = buildTargetStore(physicalIndex, corpus);
            int chunkCount = indexService.indexPublishedArticles(target);
            canary.writeTo(target);
            validator.validate(physicalIndex, chunkCount);

            ArticleRagCanary.ProbeResult canaryResult = canary.probe(target);
            if (!canaryResult.healthy()) {
                throw new IllegalStateException("文章 RAG 新索引 canary 校验失败: " + canaryResult.reason());
            }

            List<String> oldTargets = indexManager.currentAliasTargets();
            indexManager.switchActiveAlias(physicalIndex, oldTargets);
            healthService.completeRebuild(physicalIndex, canaryResult);
            return new RebuildOutcome(physicalIndex, chunkCount, oldTargets);
        } catch (IOException | RuntimeException e) {
            healthService.restoreAfterFailedRebuild(previousHealth, safeMessage(e));
            throw e;
        }
    }

    private String safeMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private VectorStore buildTargetStore(String physicalIndex, BlogAiProperties.Corpus corpus) {
        ElasticsearchVectorStoreOptions options = new ElasticsearchVectorStoreOptions();
        options.setIndexName(physicalIndex);
        options.setDimensions(corpus.getDimensions());
        return ElasticsearchVectorStore.builder(restClient, embeddingModel)
                .options(options)
                .initializeSchema(false)
                .build();
    }

    public record RebuildOutcome(String physicalIndexName, int chunkCount, List<String> previousAliasTargets) {
    }

    /** 默认实现后续接入 ES count/canary/hash 校验；当前先由重建调用方显式提供。 */
    public interface Validator {
        void validate(String physicalIndexName, int expectedChunkCount) throws IOException;
    }
}
