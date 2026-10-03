package com.hailin.blogsystem;

import com.hailin.blogsystem.ai.rag.ArticleRagIndexService;
import com.hailin.blogsystem.ai.rag.ArticleRagSyncService;
import com.hailin.blogsystem.ai.rag.RagRetryQueue;
import com.hailin.blogsystem.ai.rag.index.ArticleRagIndexHealthService;
import com.hailin.blogsystem.ai.rag.index.ArticleRagRebuildService;
import com.hailin.blogsystem.component.RedisKeyScanner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArticleRagSyncHealthTests {

    private final ArticleRagIndexService indexService = mock(ArticleRagIndexService.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final RedisKeyScanner redisKeyScanner = mock(RedisKeyScanner.class);
    private final RagRetryQueue retryQueue = mock(RagRetryQueue.class);
    private final ArticleRagRebuildService rebuildService = mock(ArticleRagRebuildService.class);
    private final ArticleRagIndexHealthService healthService = mock(ArticleRagIndexHealthService.class);
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    private final ArticleRagSyncService syncService = new ArticleRagSyncService(
            indexService,
            redisTemplate,
            redisKeyScanner,
            retryQueue,
            rebuildService,
            healthService
    );

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(healthService.current()).thenReturn(new ArticleRagIndexHealthService.Snapshot(
                ArticleRagIndexHealthService.State.UNHEALTHY,
                "canary mismatch",
                0.12D,
                Instant.now(),
                "article-rag-old"
        ));
    }

    @Test
    void unhealthyIndexDefersIncrementalWriteWithoutCallingEmbedding() {
        when(healthService.incrementalWritesAllowed()).thenReturn(false);

        syncService.indexArticle(42L);

        verify(indexService, never()).indexArticle(42L);
        verify(valueOperations).set(
                eq("rag:index:fail:index:42"),
                contains("canary mismatch"),
                eq(Duration.ofDays(7))
        );
        verify(retryQueue).enqueue("index", 42L, 1, 60L);
    }

    @Test
    void deleteRemainsAllowedWhenDenseIndexIsUnhealthy() {
        when(healthService.incrementalWritesAllowed()).thenReturn(false);

        syncService.deleteArticleIndex(42L);

        verify(indexService).deleteArticleIndex(42L);
    }

    @Test
    void deleteDuringRebuildAlsoLeavesPendingDeleteForNewAlias() {
        when(healthService.current()).thenReturn(new ArticleRagIndexHealthService.Snapshot(
                ArticleRagIndexHealthService.State.REBUILDING,
                "隔离索引正在构建",
                null,
                Instant.now(),
                null
        ));

        syncService.deleteArticleIndex(42L);

        verify(indexService).deleteArticleIndex(42L);
        verify(valueOperations).set(
                eq("rag:index:fail:delete:42"),
                contains("隔离索引正在构建"),
                eq(Duration.ofDays(7))
        );
        verify(retryQueue).enqueue("delete", 42L, 1, 60L);
    }

    @Test
    void queuedDeleteWaitsUntilRebuildFinishes() {
        when(healthService.current()).thenReturn(new ArticleRagIndexHealthService.Snapshot(
                ArticleRagIndexHealthService.State.REBUILDING,
                "隔离索引正在构建",
                null,
                Instant.now(),
                null
        ));

        syncService.retryFromQueue(new RagRetryQueue.Task("delete", 42L, 1));

        verify(indexService, never()).deleteArticleIndex(42L);
        verify(retryQueue).enqueue("delete", 42L, 2, 120L);
    }

    @Test
    void fullRebuildUsesIsolatedRebuildService() throws Exception {
        List<String> coveredFailureKeys = List.of(
                "rag:index:fail:index:11",
                "rag:index:fail:delete:22"
        );
        when(redisKeyScanner.scan("rag:index:fail:*")).thenReturn(coveredFailureKeys);
        when(rebuildService.rebuild()).thenReturn(
                new ArticleRagRebuildService.RebuildOutcome("article-rag-new", 136, List.of("article-rag-old"))
        );

        syncService.rebuildPublishedArticles();

        verify(rebuildService).rebuild();
        verify(redisTemplate).unlink(coveredFailureKeys);
        verify(redisKeyScanner, never()).scanAndDelete("rag:index:fail:*");
    }
}
