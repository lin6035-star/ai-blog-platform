package com.hailin.blogsystem.ai.mq;

import com.hailin.blogsystem.service.AiEpisodicMemoryExtractorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.MessageModel;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * 情景记忆提取消费端。
 *
 * <p>与候选记忆共用 topic、共用幂等表，但**独立 consumer group**：
 * 两条链路的失败率、重试耗尽与死信互不影响（一个 group 的 DLQ 不会被另一条的噪声污染）。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "blog.ai.memory.extraction", name = "use-mq", havingValue = "true")
@RocketMQMessageListener(
        topic = "${blog.ai.memory.memory-extraction-topic:ai-memory-extraction}",
        consumerGroup = "${ROCKETMQ_EPISODIC_CONSUMER_GROUP:blog-episodic-consumer}",
        selectorExpression = "EPISODIC",
        consumeMode = ConsumeMode.CONCURRENTLY,
        messageModel = MessageModel.CLUSTERING,
        consumeThreadNumber = 1,
        consumeThreadMax = 2
)
@RequiredArgsConstructor
public class RocketMqEpisodicMemoryConsumer implements RocketMQListener<MemoryExtractionEvent> {

    private final AiEpisodicMemoryExtractorService extractor;
    private final MemoryExtractionIdempotencyStore idempotencyStore;

    @Override
    @Transactional
    public void onMessage(MemoryExtractionEvent event) {
        Map<String, String> previousMdc = MqTraceContext.begin(event == null ? null : event.traceId());
        try {
            log.debug("[MQ-MEMORY] consume episodic eventId={}", event == null ? null : event.eventId());
            if (event == null || event.eventType() != MemoryExtractionEvent.EventType.EPISODIC) {
                throw new IllegalArgumentException("Unsupported episodic memory event");
            }
            if (!idempotencyStore.tryStart(event.eventId(), event.eventType().name())) {
                log.info("[MQ-MEMORY] duplicate episodic event skipped, eventId={}", event.eventId());
                return;
            }
            extractor.processEvent(event);
            idempotencyStore.markSucceeded(event.eventId());
        } finally {
            MqTraceContext.end(previousMdc);
        }
    }
}
