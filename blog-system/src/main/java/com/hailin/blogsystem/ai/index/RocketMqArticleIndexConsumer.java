package com.hailin.blogsystem.ai.index;

import com.hailin.blogsystem.ai.rag.ArticleRagIndexService;
import com.hailin.blogsystem.ai.rag.index.ArticleRagIndexHealthService;
import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.entity.Articles;
import com.hailin.blogsystem.mapper.ArticlesMapper;
import com.hailin.blogsystem.search.ArticleSearchService;
import com.hailin.blogsystem.utils.MdcContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.MessageModel;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 文章索引变更消费端：ORDERLY，按 articleId 选队列（生产端 hashKey=articleId）。
 *
 * <p><b>不套 {@code @Transactional}</b>：里面要调 embedding + 两个 ES 索引，单篇可能几秒；
 * 包进 DB 事务会长时间占连接。DB 侧只有一条 outbox 回插需要事务，由
 * {@link ArticleIndexOutboxService#enqueueDeferred} 自己开 REQUIRES_NEW。</p>
 *
 * <p><b>幂等</b>：不需要事件幂等表。每次消费都回查 DB 当前状态再覆盖写，重复投递结果相同。
 * 但重复消费仍会重复做查询/切片/embedding——是成本不是正确性问题（设计稿 §6.2）。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "blog.ai.article-index", name = "use-mq", havingValue = "true")
@RocketMQMessageListener(
        topic = "${blog.ai.article-index.topic:article-index-sync}",
        consumerGroup = "${ROCKETMQ_ARTICLE_INDEX_CONSUMER_GROUP:blog-article-index-consumer}",
        selectorExpression = "ARTICLE_CHANGED",
        consumeMode = ConsumeMode.ORDERLY,
        messageModel = MessageModel.CLUSTERING,
        consumeThreadNumber = 1,
        consumeThreadMax = 2,
        maxReconsumeTimes = 3
)
@RequiredArgsConstructor
public class RocketMqArticleIndexConsumer implements RocketMQListener<ArticleIndexEvent> {

    private final ArticleIndexOutboxService outboxService;
    private final ArticleRagIndexService articleRagIndexService;
    private final ArticleSearchService articleSearchService;
    private final ArticlesMapper articlesMapper;
    private final ArticleRagIndexHealthService healthService;

    /** 重建/不健康期事件延后多少秒再试（outbox 里重新排队） */
    @Value("${blog.ai.article-index.defer-seconds:30}")
    private int deferSeconds = 30;

    @Override
    public void onMessage(ArticleIndexEvent event) {
        Map<String, String> previousMdc = MdcContext.capture();
        restoreTrace(event, previousMdc);
        try {
            if (event == null || event.articleId() == null) {
                throw new IllegalArgumentException("Unsupported article index event");
            }

            if (!healthService.incrementalWritesAllowed()) {
                // 只有 outbox 插入事务提交成功后才会走到 return → listener 正常返回 = ACK；
                // 插入失败则异常冒出 → 不 ACK → MQ 重试（绝不先 ACK 再补写）
                outboxService.enqueueDeferred(event.articleId(), deferSeconds);
                log.info("[INDEX-MQ] 索引非 HEALTHY，事件回插 outbox 延后 {}s: articleId={}",
                        deferSeconds, event.articleId());
                return;
            }

            Articles article = articlesMapper.selectById(event.articleId());
            boolean published = article != null
                    && Objects.equals(article.getStatus(), BlogConstants.ArticlesStatus.PUBLISHED);
            long startedAt = System.currentTimeMillis();

            if (published) {
                articleRagIndexService.indexArticle(event.articleId());
                articleSearchService.indexArticleStrict(article);
            } else {
                articleRagIndexService.deleteArticleIndex(event.articleId());
                articleSearchService.deleteArticleStrict(event.articleId());
            }

            log.info("[INDEX-MQ] articleId={}, action={}, durationMs={}",
                    event.articleId(), published ? "upsert" : "delete",
                    System.currentTimeMillis() - startedAt);
        } finally {
            MdcContext.restore(previousMdc);
        }
    }

    private void restoreTrace(ArticleIndexEvent event, Map<String, String> previousMdc) {
        if (event == null || event.traceId() == null || event.traceId().isBlank()) {
            return;
        }
        Map<String, String> merged = previousMdc == null ? new HashMap<>() : new HashMap<>(previousMdc);
        merged.put("traceId", event.traceId());
        MdcContext.restore(merged);
    }
}
