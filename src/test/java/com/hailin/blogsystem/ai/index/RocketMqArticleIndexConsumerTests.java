package com.hailin.blogsystem.ai.index;

import com.hailin.blogsystem.ai.rag.ArticleRagIndexService;
import com.hailin.blogsystem.ai.rag.index.ArticleRagIndexHealthService;
import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.entity.Articles;
import com.hailin.blogsystem.mapper.ArticlesMapper;
import com.hailin.blogsystem.search.ArticleSearchService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RocketMqArticleIndexConsumerTests {

    private final ArticleIndexOutboxService outboxService = mock(ArticleIndexOutboxService.class);
    private final ArticleRagIndexService ragIndexService = mock(ArticleRagIndexService.class);
    private final ArticleSearchService searchService = mock(ArticleSearchService.class);
    private final ArticlesMapper articlesMapper = mock(ArticlesMapper.class);
    private final ArticleRagIndexHealthService healthService = mock(ArticleRagIndexHealthService.class);
    private final RocketMqArticleIndexConsumer consumer = new RocketMqArticleIndexConsumer(
            outboxService, ragIndexService, searchService, articlesMapper, healthService);

    @Test
    void publishedArticleIndexesRagAndSearch() {
        when(healthService.incrementalWritesAllowed()).thenReturn(true);
        Articles article = article(1L, BlogConstants.ArticlesStatus.PUBLISHED);
        when(articlesMapper.selectById(1L)).thenReturn(article);

        consumer.onMessage(ArticleIndexEvent.changed(1L, "trace-1"));

        verify(ragIndexService).indexArticle(1L);
        verify(searchService).indexArticleStrict(article);
        verify(outboxService, never()).enqueueDeferred(anyLong(), anyInt());
    }

    @Test
    void missingOrHiddenArticleDeletesBothIndexes() {
        when(healthService.incrementalWritesAllowed()).thenReturn(true);
        when(articlesMapper.selectById(2L)).thenReturn(null);
        when(articlesMapper.selectById(3L))
                .thenReturn(article(3L, BlogConstants.ArticlesStatus.HIDDEN));

        consumer.onMessage(ArticleIndexEvent.changed(2L, null));
        consumer.onMessage(ArticleIndexEvent.changed(3L, null));

        verify(ragIndexService).deleteArticleIndex(2L);
        verify(searchService).deleteArticleStrict(2L);
        verify(ragIndexService).deleteArticleIndex(3L);
        verify(searchService).deleteArticleStrict(3L);
        verify(ragIndexService, never()).indexArticle(anyLong());
    }

    /** 重建/不健康：必须先把新 outbox 行落库，listener 正常返回才 ACK；不健康期间不做索引。 */
    @Test
    void unhealthyIndexDefersEventIntoOutbox() {
        when(healthService.incrementalWritesAllowed()).thenReturn(false);

        consumer.onMessage(ArticleIndexEvent.changed(4L, null));

        verify(outboxService).enqueueDeferred(4L, 30);
        verify(ragIndexService, never()).indexArticle(anyLong());
        verify(searchService, never()).indexArticleStrict(any());
    }

    /** 回插失败必须抛出（不 ACK）→ MQ 重试，不能静默丢事件。 */
    @Test
    void deferInsertFailurePropagatesForRetry() {
        when(healthService.incrementalWritesAllowed()).thenReturn(false);
        org.mockito.Mockito.doThrow(new IllegalStateException("db down"))
                .when(outboxService).enqueueDeferred(5L, 30);

        assertThatThrownBy(() -> consumer.onMessage(ArticleIndexEvent.changed(5L, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("db down");
    }

    /** 索引失败必须抛出 → RocketMQ 顺序重试；不能吞掉后 ACK。 */
    @Test
    void indexFailurePropagatesForRetry() {
        when(healthService.incrementalWritesAllowed()).thenReturn(true);
        Articles article = article(6L, BlogConstants.ArticlesStatus.PUBLISHED);
        when(articlesMapper.selectById(6L)).thenReturn(article);
        when(ragIndexService.indexArticle(6L)).thenThrow(new IllegalStateException("embedding timeout"));

        assertThatThrownBy(() -> consumer.onMessage(ArticleIndexEvent.changed(6L, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("embedding timeout");
    }

    private static Articles article(Long id, Integer status) {
        Articles article = new Articles();
        article.setId(id);
        article.setStatus(status);
        return article;
    }
}
