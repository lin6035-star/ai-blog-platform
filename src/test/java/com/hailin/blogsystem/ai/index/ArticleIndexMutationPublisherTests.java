package com.hailin.blogsystem.ai.index;

import com.hailin.blogsystem.ai.rag.ArticleRagSyncService;
import com.hailin.blogsystem.component.AfterCommitExecutor;
import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.entity.Articles;
import com.hailin.blogsystem.mapper.ArticlesMapper;
import com.hailin.blogsystem.search.ArticleSearchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArticleIndexMutationPublisherTests {

    private final ArticleIndexOutboxService outboxService = mock(ArticleIndexOutboxService.class);
    private final ArticleRagSyncService ragSyncService = mock(ArticleRagSyncService.class);
    private final ArticleSearchService searchService = mock(ArticleSearchService.class);
    private final ArticlesMapper articlesMapper = mock(ArticlesMapper.class);
    private final ArticleIndexMutationPublisher publisher = new ArticleIndexMutationPublisher(
            outboxService, ragSyncService, searchService, articlesMapper, new AfterCommitExecutor());

    @AfterEach
    void clearTransactionState() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    /** 不在事务里调用必须炸：否则又退化成"先写库、后投递"，崩溃即丢。 */
    @Test
    void refusesToRunOutsideTransaction() {
        assertThatThrownBy(() -> publisher.articleChanged(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("事务");
        verify(outboxService, never()).enqueue(anyLong());
    }

    @Test
    void mqModeOnlyWritesOutbox() {
        enableMq(true);
        inTransaction(() -> publisher.articleChanged(10L));

        verify(outboxService).enqueue(10L);
        verify(ragSyncService, never()).indexArticle(anyLong());
        verify(searchService, never()).indexArticle(any());
    }

    /** flag=false：提交后回查 DB，已发布 → 双索引都刷。 */
    @Test
    void legacyModeIndexesBothWhenArticlePublished() {
        Articles article = article(20L, BlogConstants.ArticlesStatus.PUBLISHED);
        when(articlesMapper.selectById(20L)).thenReturn(article);
        enableMq(false);

        inTransaction(() -> publisher.articleChanged(20L));

        verify(ragSyncService).indexArticle(20L);
        verify(searchService).indexArticle(article);
        verify(outboxService, never()).enqueue(anyLong());
    }

    /** flag=false：回查不到（已删除）→ 双索引都删。 */
    @Test
    void legacyModeDeletesBothWhenArticleMissing() {
        when(articlesMapper.selectById(30L)).thenReturn(null);
        enableMq(false);

        inTransaction(() -> publisher.articleChanged(30L));

        verify(ragSyncService).deleteArticleIndex(30L);
        verify(searchService).deleteArticle(30L);
    }

    @Test
    void legacyModeDeletesWhenHidden() {
        when(articlesMapper.selectById(40L))
                .thenReturn(article(40L, BlogConstants.ArticlesStatus.HIDDEN));
        enableMq(false);

        inTransaction(() -> publisher.articleChanged(40L));

        verify(ragSyncService).deleteArticleIndex(40L);
        verify(searchService).deleteArticle(40L);
    }

    private void enableMq(boolean enabled) {
        ReflectionTestUtils.setField(publisher, "useMq", enabled);
    }

    private static void inTransaction(Runnable action) {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            action.run();
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    private static Articles article(Long id, Integer status) {
        Articles article = new Articles();
        article.setId(id);
        article.setStatus(status);
        return article;
    }
}
