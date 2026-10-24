package com.hailin.blogsystem.task;

import com.hailin.blogsystem.ai.rag.ArticleRagConsistencyService;
import com.hailin.blogsystem.entity.vo.ArticleRagConsistencyReportVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 索引日对账（RAG + 站内搜索）。
 *
 * <p>为什么要有它：MQ 是"至少一次 + 重试 + 死信"，但**没有对账就不知道最终一致性守没守住**。
 * 这条任务只读不写：发现差异打 ERROR/INFO 日志（可接告警），修复仍走手动接口/重建——
 * 与项目"哪个数是对的只有人知道"的口径一致，不自动重放、不自动删文档。</p>
 *
 * <p>只在 {@code blog.ai.article-index.use-mq=true} 时启用：旧链路有自己的失败标记语义，
 * 默认关闭时不该多跑一个每天扫 ES 的任务。</p>
 *
 * <p>两个索引**分开 try**：RAG 对账失败不能把搜索对账也跳过，否则永远只有一半数据。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "blog.ai.article-index", name = "use-mq", havingValue = "true")
public class ArticleIndexConsistencyTask {

    private final ArticleRagConsistencyService consistencyService;

    @Scheduled(cron = "${blog.ai.article-index.reconcile-cron:0 0 4 * * *}")
    public void reconcile() {
        reconcileRag();
        reconcileSearch();
    }

    private void reconcileRag() {
        try {
            ArticleRagConsistencyReportVO report = consistencyService.check();
            boolean consistent = report.getMissingInEs().isEmpty() && report.getExtraInEs().isEmpty();
            String message = "[INDEX-RECONCILE] rag published={} indexed={} missing={} extra={}";
            if (consistent) {
                log.info(message, report.getPublishedArticleCount(), report.getIndexedArticleCount(),
                        report.getMissingInEs().size(), report.getExtraInEs().size());
            } else {
                log.error(message + " missingIds={} extraIds={}",
                        report.getPublishedArticleCount(), report.getIndexedArticleCount(),
                        report.getMissingInEs().size(), report.getExtraInEs().size(),
                        report.getMissingInEs(), report.getExtraInEs());
            }
        } catch (Exception e) {
            // 对账本身失败（ES 抖动/索引缺失）不影响下一轮；下一轮会重跑
            log.error("[INDEX-RECONCILE] RAG 对账失败，本轮跳过", e);
        }
    }

    private void reconcileSearch() {
        try {
            ArticleRagConsistencyService.SearchIndexReport report = consistencyService.checkSearchIndex();
            String message = "[INDEX-RECONCILE] search published={} indexed={} missing={} extra={}";
            if (report.consistent()) {
                log.info(message, report.publishedArticleCount(), report.indexedArticleCount(),
                        report.missingInEs().size(), report.extraInEs().size());
            } else {
                log.error(message + " missingIds={} extraIds={}",
                        report.publishedArticleCount(), report.indexedArticleCount(),
                        report.missingInEs().size(), report.extraInEs().size(),
                        report.missingInEs(), report.extraInEs());
            }
        } catch (Exception e) {
            log.error("[INDEX-RECONCILE] 站内搜索对账失败，本轮跳过", e);
        }
    }
}
