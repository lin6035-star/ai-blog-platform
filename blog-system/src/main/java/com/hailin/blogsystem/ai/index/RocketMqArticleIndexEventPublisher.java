package com.hailin.blogsystem.ai.index;

import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.common.message.MessageConst;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * RocketMQ 投递适配器。
 *
 * <p>必须用 {@code syncSendOrderly} + articleId 当 hashKey：同一篇文章的事件落同一队列，
 * 配合消费端 ORDERLY 保证"同一 articleId 的索引动作不并发"，否则 embedding 的几秒耗时会让
 * 旧事件在新事件之后写入（§6.3 的旧覆盖新）。</p>
 */
@Component
@ConditionalOnProperty(prefix = "blog.ai.article-index", name = "use-mq", havingValue = "true")
@RequiredArgsConstructor
public class RocketMqArticleIndexEventPublisher implements ArticleIndexEventPublisher {

    private final RocketMQTemplate rocketMQTemplate;

    @Value("${blog.ai.article-index.topic:article-index-sync}")
    private String topic;

    @Override
    public void publish(ArticleIndexEvent event) {
        // tag 必须放进 destination：RocketMQTemplate 只从 topic:tag 里解析 tag，
        // 放 Message header（PROPERTY_TAGS）会被忽略 → 消息 Tags=null → 消费端按 tag 过滤掉
        String destination = topic + ":" + event.eventType();
        rocketMQTemplate.syncSendOrderly(
                destination,
                MessageBuilder.withPayload(event)
                        .setHeader(MessageConst.PROPERTY_KEYS, String.valueOf(event.articleId()))
                        .build(),
                String.valueOf(event.articleId())
        );
    }
}
