package com.hailin.blogsystem.ai.index;

import com.hailin.blogsystem.ai.rag.index.ArticleRagIndexHealthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArticleIndexOutboxRelayTaskTests {

    private final ArticleIndexOutboxService outboxService = mock(ArticleIndexOutboxService.class);
    private final ArticleIndexEventPublisher publisher = mock(ArticleIndexEventPublisher.class);
    private final ArticleRagIndexHealthService healthService = mock(ArticleRagIndexHealthService.class);
    private final ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
    private final ThreadPoolExecutor pool = mock(ThreadPoolExecutor.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<ArticleIndexEventPublisher> provider = mock(ObjectProvider.class);

    private final ArticleIndexOutboxRelayTask relay =
            new ArticleIndexOutboxRelayTask(outboxService, provider, healthService, executor);

    @BeforeEach
    void setUp() {
        when(provider.getIfAvailable()).thenReturn(publisher);
        when(executor.getThreadPoolExecutor()).thenReturn(pool);
        when(pool.getQueue()).thenReturn(new LinkedBlockingQueue<>(100));
        // 直接在当前线程执行，测试才能同步断言
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(executor).execute(any());
    }

    @Test
    void unhealthyIndexSkipsClaimEntirely() {
        when(healthService.incrementalWritesAllowed()).thenReturn(false);
        when(healthService.current()).thenReturn(snapshot(ArticleRagIndexHealthService.State.REBUILDING));

        relay.relay();

        verify(outboxService, never()).claimDue(anyInt(), anyInt());
        verify(publisher, never()).publish(any());
    }

    @Test
    void healthyRelaySendsAndMarksSent() {
        when(healthService.incrementalWritesAllowed()).thenReturn(true);
        ArticleIndexOutbox row = row(1L, 10L);
        when(outboxService.claimDue(20, 60)).thenReturn(List.of(row));

        relay.relay();

        verify(outboxService).recoverStale(60);
        verify(publisher).publish(any(ArticleIndexEvent.class));
        verify(outboxService).markSent(1L);
    }

    @Test
    void publishFailureIsRecordedForBackoff() {
        when(healthService.incrementalWritesAllowed()).thenReturn(true);
        ArticleIndexOutbox row = row(2L, 20L);
        when(outboxService.claimDue(20, 60)).thenReturn(List.of(row));
        doThrow(new RuntimeException("broker down")).when(publisher).publish(any());

        relay.relay();

        verify(outboxService).markSendFailed(eq(row), contains("broker down"), eq(20));
        verify(outboxService, never()).markSent(any());
    }

    @Test
    void rejectedTaskRevertsAllUndeliveredRows() {
        when(healthService.incrementalWritesAllowed()).thenReturn(true);
        ArticleIndexOutbox first = row(3L, 30L);
        ArticleIndexOutbox second = row(4L, 40L);
        when(outboxService.claimDue(20, 60)).thenReturn(List.of(first, second));
        doThrow(new RejectedExecutionException("full")).when(executor).execute(any());

        relay.relay();

        verify(outboxService).revertToPending(List.of(3L, 4L));
        verify(publisher, never()).publish(any());
    }

    private static ArticleIndexOutbox row(Long id, Long articleId) {
        ArticleIndexOutbox row = new ArticleIndexOutbox();
        row.setId(id);
        row.setArticleId(articleId);
        row.setStatus(ArticleIndexOutbox.STATUS_SENDING);
        row.setAttempts(0);
        row.setNextRetryAt(LocalDateTime.now());
        return row;
    }

    private static ArticleRagIndexHealthService.Snapshot snapshot(
            ArticleRagIndexHealthService.State state) {
        return new ArticleRagIndexHealthService.Snapshot(state, "test", null, Instant.now(), null);
    }
}
