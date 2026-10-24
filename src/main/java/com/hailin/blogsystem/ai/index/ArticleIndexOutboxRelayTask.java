package com.hailin.blogsystem.ai.index;

import com.hailin.blogsystem.ai.rag.index.ArticleRagIndexHealthService;
import com.hailin.blogsystem.config.ArticleIndexAsyncConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * outbox → RocketMQ 的 relay。
 *
 * <p><b>只做"捞 + 投递"</b>：调度线程池全局只有一个线程（`spring.task.scheduling.pool.size: 1`），
 * 这里如果同步等 broker/embedding，浏览量同步、热度榜这些定时任务都会被拖住。所以真正的
 * `syncSendOrderly` 交给 {@code articleIndexOutboxExecutor}（单线程），调度线程只负责 claim 和提交。</p>
 *
 * <p><b>重建期门禁</b>：健康状态不是 HEALTHY 时直接不 claim——事件留在 PENDING，等重建/巡检结束后
 * 自然恢复；不让大量事件先进 MQ 再在消费端反复回插。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "blog.ai.article-index", name = "use-mq", havingValue = "true")
public class ArticleIndexOutboxRelayTask {

    private static final long GATE_LOG_INTERVAL_MS = 60_000L;

    private final ArticleIndexOutboxService outboxService;
    private final ObjectProvider<ArticleIndexEventPublisher> publisherProvider;
    private final ArticleRagIndexHealthService healthService;
    private final ThreadPoolTaskExecutor outboxExecutor;

    @Value("${blog.ai.article-index.relay.batch-size:20}")
    private int batchSize = 20;

    @Value("${blog.ai.article-index.relay.lease-seconds:60}")
    private int leaseSeconds = 60;

    @Value("${blog.ai.article-index.relay.max-attempts:20}")
    private int maxAttempts = 20;

    private final AtomicLong lastGateLogAt = new AtomicLong(0L);

    public ArticleIndexOutboxRelayTask(
            ArticleIndexOutboxService outboxService,
            ObjectProvider<ArticleIndexEventPublisher> publisherProvider,
            ArticleRagIndexHealthService healthService,
            @Qualifier(ArticleIndexAsyncConfig.OUTBOX_EXECUTOR) ThreadPoolTaskExecutor outboxExecutor
    ) {
        this.outboxService = outboxService;
        this.publisherProvider = publisherProvider;
        this.healthService = healthService;
        this.outboxExecutor = outboxExecutor;
    }

    @Scheduled(fixedDelayString = "${blog.ai.article-index.relay.interval-ms:2000}")
    public void relay() {
        if (!healthService.incrementalWritesAllowed()) {
            logGateBlocked();
            return;
        }

        if (outboxExecutor.getThreadPoolExecutor().getQueue().remainingCapacity() < batchSize) {
            log.warn("[INDEX-OUTBOX] 发送队列接近满，本轮跳过 claim（避免全进 SENDING 白等租约）");
            return;
        }

        try {
            outboxService.recoverStale(leaseSeconds);
            List<ArticleIndexOutbox> claimed = outboxService.claimDue(batchSize, leaseSeconds);
            if (claimed.isEmpty()) {
                return;
            }

            long backlog = outboxService.countByStatus(ArticleIndexOutbox.STATUS_PENDING);
            log.info("[INDEX-OUTBOX] claimed={}, backlog={}", claimed.size(), backlog);

            for (int i = 0; i < claimed.size(); i++) {
                ArticleIndexOutbox row = claimed.get(i);
                try {
                    outboxExecutor.execute(() -> sendOne(row));
                } catch (RejectedExecutionException e) {
                    // 本轮剩余行立即退回 PENDING；已提交出去的不动（它们会正常结束）
                    List<Long> undelivered = claimed.subList(i, claimed.size()).stream()
                            .map(ArticleIndexOutbox::getId)
                            .toList();
                    outboxService.revertToPending(undelivered);
                    log.warn("[INDEX-OUTBOX] executor 拒绝任务，{} 行退回 PENDING", undelivered.size());
                    return;
                }
            }
        } catch (Exception e) {
            // relay 自身异常不能把定时任务打死：下一轮会重新扫 PENDING/SENDING
            log.error("[INDEX-OUTBOX] relay 本轮失败，下一轮重试", e);
        }
    }

    /** 单条投递：成功标 SENT，失败回 PENDING 退避（超过上限转 FAILED）。 */
    void sendOne(ArticleIndexOutbox row) {
        ArticleIndexEventPublisher publisher = publisherProvider.getIfAvailable();
        if (publisher == null) {
            outboxService.markSendFailed(row, "ArticleIndexEventPublisher 不可用", maxAttempts);
            return;
        }

        try {
            publisher.publish(ArticleIndexEvent.changed(row.getArticleId(), row.getTraceId()));
            outboxService.markSent(row.getId());
            log.debug("[INDEX-OUTBOX] sent: id={}, articleId={}, traceId={}",
                    row.getId(), row.getArticleId(), row.getTraceId());
        } catch (Exception e) {
            String error = e.getClass().getSimpleName() + ": " + e.getMessage();
            outboxService.markSendFailed(row, error, maxAttempts);
        }
    }

    private void logGateBlocked() {
        long now = System.currentTimeMillis();
        long last = lastGateLogAt.get();
        if (now - last >= GATE_LOG_INTERVAL_MS && lastGateLogAt.compareAndSet(last, now)) {
            ArticleRagIndexHealthService.Snapshot snapshot = healthService.current();
            log.warn("[INDEX-GATE] 索引健康状态 {}（{}），outbox 投递暂停，事件留在 PENDING",
                    snapshot.state(), snapshot.reason());
        }
    }
}
