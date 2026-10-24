package com.hailin.blogsystem.ai.index;

import com.hailin.blogsystem.ai.rag.ArticleRagSyncService;
import com.hailin.blogsystem.component.AfterCommitExecutor;
import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.entity.Articles;
import com.hailin.blogsystem.mapper.ArticlesMapper;
import com.hailin.blogsystem.search.ArticleSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;

/**
 * 文章索引变更的唯一入口：业务代码只调 {@link #articleChanged(Long)}，不关心底层是 MQ 还是旧链路。
 *
 * <p><b>必须在文章写事务内调用</b>：MQ 模式下 outbox 行要和文章写同事务提交；
 * 旧链路模式（use-mq=false）在这里把动作注册到 after-commit，顺带修掉"事务内就触发异步索引"的老问题。
 * 不在事务内调用直接抛异常——这类误用只会让事件丢失窗口重新出现，必须尽早炸。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ArticleIndexMutationPublisher {

    private final ArticleIndexOutboxService outboxService;
    private final ArticleRagSyncService articleRagSyncService;
    private final ArticleSearchService articleSearchService;
    private final ArticlesMapper articlesMapper;
    private final AfterCommitExecutor afterCommitExecutor;

    @Value("${blog.ai.article-index.use-mq:false}")
    private boolean useMq;

    public void articleChanged(Long articleId) {
        if (articleId == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "文章索引事件必须在事务内触发：articleId=" + articleId);
        }

        if (useMq) {
            outboxService.enqueue(articleId);
            return;
        }

        // 旧链路：提交后再触发（无事务时 AfterCommitExecutor 会立即执行，保持原语义）
        afterCommitExecutor.execute(() -> legacySync(articleId));
    }

    /**
     * 旧链路（flag=false）：提交后回查最新状态，再同步双索引。
     *
     * <p>原实现是"调用点根据更新前的实体状态二选一"，这里统一改成回查 DB——
     * 与 MQ 消费端同一套决策逻辑，避免两条路各写一份状态判断；提交后读到的就是权威值。</p>
     */
    private void legacySync(Long articleId) {
        Articles article = articlesMapper.selectById(articleId);
        boolean published = article != null
                && Objects.equals(article.getStatus(), BlogConstants.ArticlesStatus.PUBLISHED);

        try {
            if (published) {
                articleRagSyncService.indexArticle(articleId);
            } else {
                articleRagSyncService.deleteArticleIndex(articleId);
            }
        } catch (Exception e) {
            log.warn("文章 RAG 索引同步失败，articleId={}", articleId, e);
        }

        try {
            if (published) {
                articleSearchService.indexArticle(article);
            } else {
                articleSearchService.deleteArticle(articleId);
            }
        } catch (Exception e) {
            log.warn("文章搜索索引同步失败，articleId={}", articleId, e);
        }
    }
}
