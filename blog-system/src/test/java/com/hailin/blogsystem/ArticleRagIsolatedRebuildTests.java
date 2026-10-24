package com.hailin.blogsystem;

import com.hailin.blogsystem.ai.rag.ArticleRagIndexService;
import com.hailin.blogsystem.ai.rag.index.ArticleRagIndexManager;
import com.hailin.blogsystem.ai.rag.index.ArticleRagCanary;
import com.hailin.blogsystem.ai.rag.index.ArticleRagIndexHealthService;
import com.hailin.blogsystem.ai.rag.index.ArticleRagRebuildService;
import com.hailin.blogsystem.ai.rag.index.IndexRebuildLock;
import com.hailin.blogsystem.config.BlogAiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.elasticsearch.client.RestClient;

import java.util.List;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ArticleRagIsolatedRebuildTests {

    @Test
    void validationFailureDoesNotTouchActiveAlias() throws Exception {
        ArticleRagIndexManager manager = mock(ArticleRagIndexManager.class);
        ArticleRagIndexService indexService = mock(ArticleRagIndexService.class);
        ArticleRagRebuildService.Validator validator = mock(ArticleRagRebuildService.Validator.class);
        ArticleRagCanary canary = healthyCanary();
        ArticleRagIndexHealthService healthService = mock(ArticleRagIndexHealthService.class);
        when(manager.physicalIndexName(any(), anyInt(), any())).thenReturn("article-rag-new");
        when(manager.currentAliasTargets()).thenReturn(List.of("article-rag-old"));
        when(indexService.indexPublishedArticles(any(VectorStore.class))).thenReturn(126);
        doThrow(new IllegalStateException("count mismatch"))
                .when(validator).validate("article-rag-new", 126);

        ArticleRagRebuildService service = service(manager, indexService, validator, canary, healthService);

        assertThatThrownBy(service::rebuild).isInstanceOf(IllegalStateException.class);
        verify(manager).createPhysicalIndex("article-rag-new", 1024);
        verify(manager, never()).switchActiveAlias(any(), any());
    }

    @Test
    void writerFailureDoesNotTouchActiveAlias() throws Exception {
        ArticleRagIndexManager manager = mock(ArticleRagIndexManager.class);
        ArticleRagIndexService indexService = mock(ArticleRagIndexService.class);
        ArticleRagRebuildService.Validator validator = mock(ArticleRagRebuildService.Validator.class);
        ArticleRagCanary canary = healthyCanary();
        ArticleRagIndexHealthService healthService = mock(ArticleRagIndexHealthService.class);
        when(manager.physicalIndexName(any(), anyInt(), any())).thenReturn("article-rag-new");
        doThrow(new IllegalStateException("embedding unavailable"))
                .when(indexService).indexPublishedArticles(any(VectorStore.class));

        ArticleRagRebuildService service = service(manager, indexService, validator, canary, healthService);

        assertThatThrownBy(service::rebuild).isInstanceOf(IllegalStateException.class);
        verify(manager).createPhysicalIndex("article-rag-new", 1024);
        verify(validator, never()).validate(any(), anyInt());
        verify(manager, never()).switchActiveAlias(any(), any());
    }

    @Test
    void successfulRebuildSwitchesAliasOnlyAfterValidation() throws Exception {
        ArticleRagIndexManager manager = mock(ArticleRagIndexManager.class);
        ArticleRagIndexService indexService = mock(ArticleRagIndexService.class);
        ArticleRagRebuildService.Validator validator = mock(ArticleRagRebuildService.Validator.class);
        ArticleRagCanary canary = healthyCanary();
        ArticleRagIndexHealthService healthService = mock(ArticleRagIndexHealthService.class);
        when(manager.physicalIndexName(any(), anyInt(), any())).thenReturn("article-rag-new");
        when(manager.currentAliasTargets()).thenReturn(List.of("article-rag-old"));
        when(indexService.indexPublishedArticles(any(VectorStore.class))).thenReturn(126);

        ArticleRagRebuildService service = service(manager, indexService, validator, canary, healthService);

        service.rebuild();

        verify(manager).createPhysicalIndex("article-rag-new", 1024);
        verify(canary).writeTo(any(VectorStore.class));
        verify(validator).validate("article-rag-new", 126);
        verify(canary).probe(any(VectorStore.class));
        verify(manager).switchActiveAlias("article-rag-new", List.of("article-rag-old"));
        verify(healthService).completeRebuild(eq("article-rag-new"), any(ArticleRagCanary.ProbeResult.class));
    }

    @Test
    void unhealthyCanaryDoesNotSwitchAlias() throws Exception {
        ArticleRagIndexManager manager = mock(ArticleRagIndexManager.class);
        ArticleRagIndexService indexService = mock(ArticleRagIndexService.class);
        ArticleRagRebuildService.Validator validator = mock(ArticleRagRebuildService.Validator.class);
        ArticleRagCanary canary = mock(ArticleRagCanary.class);
        ArticleRagIndexHealthService healthService = mock(ArticleRagIndexHealthService.class);
        ArticleRagIndexHealthService.Snapshot previous = new ArticleRagIndexHealthService.Snapshot(
                ArticleRagIndexHealthService.State.HEALTHY,
                "OK",
                0.99D,
                Instant.now(),
                "article-rag-old"
        );
        when(healthService.beginRebuild()).thenReturn(previous);
        when(manager.physicalIndexName(any(), anyInt(), any())).thenReturn("article-rag-new");
        when(indexService.indexPublishedArticles(any(VectorStore.class))).thenReturn(126);
        when(canary.probe(any(VectorStore.class)))
                .thenReturn(new ArticleRagCanary.ProbeResult(false, 0.32D, "空间漂移"));

        ArticleRagRebuildService service = service(manager, indexService, validator, canary, healthService);

        assertThatThrownBy(service::rebuild)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("canary");
        verify(manager, never()).switchActiveAlias(any(), any());
        verify(healthService).restoreAfterFailedRebuild(eq(previous), contains("空间漂移"));
    }

    @Test
    void concurrentRebuildIsRejectedWhenLockIsHeld() throws Exception {
        ArticleRagIndexManager manager = mock(ArticleRagIndexManager.class);
        ArticleRagIndexService indexService = mock(ArticleRagIndexService.class);
        ArticleRagRebuildService.Validator validator = mock(ArticleRagRebuildService.Validator.class);
        ArticleRagCanary canary = healthyCanary();
        ArticleRagIndexHealthService healthService = mock(ArticleRagIndexHealthService.class);

        IndexRebuildLock busyLock = mock(IndexRebuildLock.class);
        when(busyLock.tryAcquire()).thenReturn(null);

        ArticleRagRebuildService service =
                service(manager, indexService, validator, canary, healthService, busyLock);

        assertThatThrownBy(service::rebuild)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已有索引重建在进行中");
        verify(manager, never()).createPhysicalIndex(any(), anyInt());
        verify(busyLock, never()).release(any());
    }

    @Test
    void releasesRebuildLockEvenWhenRebuildFails() throws Exception {
        ArticleRagIndexManager manager = mock(ArticleRagIndexManager.class);
        ArticleRagIndexService indexService = mock(ArticleRagIndexService.class);
        ArticleRagRebuildService.Validator validator = mock(ArticleRagRebuildService.Validator.class);
        ArticleRagCanary canary = healthyCanary();
        ArticleRagIndexHealthService healthService = mock(ArticleRagIndexHealthService.class);
        when(manager.physicalIndexName(any(), anyInt(), any())).thenReturn("article-rag-new");
        doThrow(new IllegalStateException("embedding unavailable"))
                .when(indexService).indexPublishedArticles(any(VectorStore.class));

        IndexRebuildLock lock = mock(IndexRebuildLock.class);
        when(lock.tryAcquire()).thenReturn("token-1");

        ArticleRagRebuildService service =
                service(manager, indexService, validator, canary, healthService, lock);

        assertThatThrownBy(service::rebuild).isInstanceOf(IllegalStateException.class);
        // 失败路径也必须释放锁，否则 TTL 之前再也无法重建
        verify(lock).release("token-1");
    }

    private ArticleRagRebuildService service(
            ArticleRagIndexManager manager,
            ArticleRagIndexService indexService,
            ArticleRagRebuildService.Validator validator,
            ArticleRagCanary canary,
            ArticleRagIndexHealthService healthService
    ) {
        return service(manager, indexService, validator, canary, healthService, acquiredLock());
    }

    private ArticleRagRebuildService service(
            ArticleRagIndexManager manager,
            ArticleRagIndexService indexService,
            ArticleRagRebuildService.Validator validator,
            ArticleRagCanary canary,
            ArticleRagIndexHealthService healthService,
            IndexRebuildLock rebuildLock
    ) {
        BlogAiProperties properties = new BlogAiProperties();
        properties.getRag().getCorpus().setDimensions(1024);
        return new ArticleRagRebuildService(
                manager,
                indexService,
                validator,
                mock(RestClient.class),
                mock(EmbeddingModel.class),
                properties,
                canary,
                healthService,
                rebuildLock
        );
    }

    private IndexRebuildLock acquiredLock() {
        IndexRebuildLock lock = mock(IndexRebuildLock.class);
        when(lock.tryAcquire()).thenReturn("token-1");
        return lock;
    }

    private ArticleRagCanary healthyCanary() {
        ArticleRagCanary canary = mock(ArticleRagCanary.class);
        when(canary.probe(any(VectorStore.class)))
                .thenReturn(new ArticleRagCanary.ProbeResult(true, 0.99D, "OK"));
        return canary;
    }
}
