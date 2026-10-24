package com.hailin.blogsystem.ai.mq;

import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.MessageConst;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * RocketMQ 适配器。业务层只依赖 MemoryExtractionPublisher。
 *
 * <p><b>tag 必须放进 destination（{@code topic:tag}）</b>：{@code RocketMQTemplate} 的转换器
 * 只从 destination 里解析 tag，放在 Spring Message header 的 {@code MessageConst.PROPERTY_TAGS}
 * 不会写进 RocketMQ 消息的 tags 字段。踩过的坑：消息 Tags=[null]，消费端 selector 是
 * CANDIDATE/EPISODIC/SESSION_SUMMARY → 被客户端按"不匹配"过滤并推进 offset，
 * 表现为「没有消费日志、也没有死信、offset 却一直在涨」。</p>
 */
@Component
@ConditionalOnProperty(prefix = "blog.ai.memory.extraction", name = "use-mq", havingValue = "true")
@RequiredArgsConstructor
public class RocketMqMemoryExtractionPublisher implements MemoryExtractionPublisher {

    private final RocketMQTemplate rocketMQTemplate;

    @Value("${blog.ai.memory.memory-extraction-topic:ai-memory-extraction}")
    private String topic;

    @Override
    public void publish(MemoryExtractionEvent event) {
        String destination = topic + ":" + event.eventType().name();
        SendResult result = rocketMQTemplate.syncSend(
                destination,
                MessageBuilder.withPayload(event)
                        .setHeader(MessageConst.PROPERTY_KEYS, event.eventId())
                        .build()
        );
        if (result == null) {
            throw new IllegalStateException("RocketMQ returned no send result");
        }
    }
}
