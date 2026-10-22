package com.hailin.blogsystem.ai.mq;

import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.MessageConst;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/** RocketMQ 适配器。业务层只依赖 MemoryExtractionPublisher。 */
@Component
@ConditionalOnProperty(prefix = "blog.ai.memory.extraction", name = "use-mq", havingValue = "true")
@RequiredArgsConstructor
public class RocketMqMemoryExtractionPublisher implements MemoryExtractionPublisher {

    private final RocketMQTemplate rocketMQTemplate;

    @Value("${blog.ai.memory.memory-extraction-topic:ai-memory-extraction}")
    private String topic;

    @Override
    public void publish(MemoryExtractionEvent event) {
        SendResult result = rocketMQTemplate.syncSend(
                topic,
                MessageBuilder.withPayload(event)
                        .setHeader(MessageConst.PROPERTY_KEYS, event.eventId())
                        .setHeader(MessageConst.PROPERTY_TAGS, event.eventType().name())
                        .build()
        );
        if (result == null) {
            throw new IllegalStateException("RocketMQ returned no send result");
        }
    }
}
