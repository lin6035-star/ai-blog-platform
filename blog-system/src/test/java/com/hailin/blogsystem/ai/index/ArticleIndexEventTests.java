package com.hailin.blogsystem.ai.index;

import org.apache.rocketmq.spring.support.RocketMQMessageConverter;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.converter.MessageConverter;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class ArticleIndexEventTests {

    private final MessageConverter converter =
            new RocketMQMessageConverter().getMessageConverter();

    @Test
    void changedEventUsesArticleIdAndType() {
        ArticleIndexEvent event = ArticleIndexEvent.changed(123L, "trace-1");

        assertThat(event.eventType()).isEqualTo(ArticleIndexEvent.TYPE_ARTICLE_CHANGED);
        assertThat(event.eventId()).isEqualTo("article-index:changed:123");
        assertThat(event.articleId()).isEqualTo(123L);
        assertThat(event.traceId()).isEqualTo("trace-1");
        assertThat(event.occurredAt()).isNotNull();
    }

    @Test
    void eventRejectsMissingArticleId() {
        assertThatNullPointerException()
                .isThrownBy(() -> ArticleIndexEvent.changed(null, "trace-1"))
                .withMessage("articleId");
    }

    /** 真实序列化往返：锁住 record + Instant 能过 starter 的转换器（S2 消费者靠它）。 */
    @Test
    void eventSurvivesProducerConsumerRoundTrip() {
        ArticleIndexEvent event = ArticleIndexEvent.changed(999L, "trace-rt");

        Message<?> message = converter.toMessage(event, new MessageHeaders(Map.of()));
        assertThat(message).isNotNull();

        Object payload = converter.fromMessage(message, ArticleIndexEvent.class);
        assertThat(payload).isInstanceOf(ArticleIndexEvent.class);
        ArticleIndexEvent actual = (ArticleIndexEvent) payload;
        assertThat(actual.articleId()).isEqualTo(999L);
        assertThat(actual.eventType()).isEqualTo(ArticleIndexEvent.TYPE_ARTICLE_CHANGED);
        assertThat(actual.traceId()).isEqualTo("trace-rt");
        assertThat(actual.occurredAt()).isNotNull();
    }
}
