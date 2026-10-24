package com.hailin.blogsystem.ai.index;

import java.time.Instant;

/**
 * 文章索引同步事件：**只带 articleId**，消费端回查 DB 决定 upsert 还是 delete。
 *
 * <p>不携带标题/正文快照：事件体积小、消费端永远基于「消费那一刻」的权威数据，
 * 发布 → 隐藏 → 再发布在消费端合并成一次执行也不会有旧覆盖新（前提是同 articleId 顺序消费）。</p>
 *
 * <p>{@code traceId} 用于把"文章写入请求"和"M Q 消费"的日志串起来；relay 线程没有请求上下文，
 * 所以 traceId 必须随 outbox 行一起落库（见 {@code ArticleIndexOutboxService#enqueue}）。</p>
 */
public record ArticleIndexEvent(
        String eventId,
        String eventType,
        Long articleId,
        String traceId,
        Instant occurredAt
) {

    public static final String TYPE_ARTICLE_CHANGED = "ARTICLE_CHANGED";

    public static ArticleIndexEvent changed(Long articleId, String traceId) {
        if (articleId == null) {
            throw new NullPointerException("articleId");
        }
        return new ArticleIndexEvent(
                "article-index:changed:" + articleId,
                TYPE_ARTICLE_CHANGED,
                articleId,
                traceId,
                Instant.now()
        );
    }
}
