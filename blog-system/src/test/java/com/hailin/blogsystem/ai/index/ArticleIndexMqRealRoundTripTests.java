package com.hailin.blogsystem.ai.index;

import com.hailin.blogsystem.ai.rag.ArticleRagIndexService;
import com.hailin.blogsystem.ai.rag.index.ArticleRagIndexHealthService;
import com.hailin.blogsystem.mapper.ArticlesMapper;
import com.hailin.blogsystem.search.ArticleSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 真实 RocketMQ 往返探针，默认跳过。
 *
 * <p>它验证的不是业务 mock，而是最容易静默出错的框架链路：
 * {@code RocketMQTemplate -> Broker -> TAG 过滤 -> ORDERLY consumer -> JSON 反序列化}。
 * 2026-10-23 曾因 tag 放在 Spring Message header，真实消息 {@code Tags=null}，
 * consumer offset 正常推进但消息被过滤；普通 Mockito 单测无法发现。</p>
 */
@SpringBootTest(properties = {
        "blog.ai.article-index.use-mq=true",
        "blog.ai.article-index.topic=article-index-sync-probe",
        "ROCKETMQ_ARTICLE_INDEX_CONSUMER_GROUP=blog-article-index-probe-consumer",
        "rocketmq.name-server=127.0.0.1:9876",
        "rocketmq.producer.group=blog-article-index-probe-producer",
        "blog.ai.rag.health.initial-delay-ms=3600000",
        "blog.ai.article-index.relay.interval-ms=3600000"
})
@EnabledIfSystemProperty(named = "runRealArticleIndexMq", matches = "true")
class ArticleIndexMqRealRoundTripTests {

    @Autowired
    private ArticleIndexEventPublisher publisher;

    @MockBean
    private ArticleIndexOutboxService outboxService;

    @MockBean
    private ArticleRagIndexService articleRagIndexService;

    @MockBean
    private ArticleSearchService articleSearchService;

    @MockBean
    private ArticlesMapper articlesMapper;

    @MockBean
    private ArticleRagIndexHealthService healthService;

    @BeforeEach
    void setUp() {
        when(healthService.incrementalWritesAllowed()).thenReturn(true);
        when(outboxService.claimDue(anyInt(), anyInt())).thenReturn(List.of());
    }

    @Test
    void messageWithArticleChangedTagReachesOrderlyConsumer() {
        long articleId = 9_000_000_000_000_000_001L;
        when(articlesMapper.selectById(articleId)).thenReturn(null);

        publisher.publish(ArticleIndexEvent.changed(articleId, "real-mq-probe"));

        verify(articleRagIndexService, timeout(15_000)).deleteArticleIndex(articleId);
        verify(articleSearchService, timeout(15_000)).deleteArticleStrict(articleId);
    }
}
