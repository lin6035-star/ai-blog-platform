package com.hailin.blogsystem.ai.index;

import org.apache.rocketmq.common.message.MessageConst;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.Message;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RocketMqArticleIndexEventPublisherTests {

    @Test
    void publishesOrderlyWithArticleIdAsHashKeyAndTagInDestination() {
        RocketMQTemplate template = mock(RocketMQTemplate.class);
        RocketMqArticleIndexEventPublisher publisher = new RocketMqArticleIndexEventPublisher(template);
        ReflectionTestUtils.setField(publisher, "topic", "article-index-sync");

        publisher.publish(ArticleIndexEvent.changed(55L, "trace-1"));

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        // tag 必须在 destination 里：RocketMQTemplate 只从 topic:tag 解析，Message header 的 TAGS 会被忽略
        verify(template).syncSendOrderly(
                eq("article-index-sync:ARTICLE_CHANGED"), messageCaptor.capture(), eq("55"));
        Message<?> message = messageCaptor.getValue();
        assertThat(message.getPayload()).isInstanceOf(ArticleIndexEvent.class);
        assertThat(message.getHeaders().get(MessageConst.PROPERTY_KEYS)).isEqualTo("55");
    }
}
