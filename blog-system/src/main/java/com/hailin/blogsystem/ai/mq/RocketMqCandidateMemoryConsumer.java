package com.hailin.blogsystem.ai.mq;

import com.hailin.blogsystem.service.AiMemoryCandidateExtractorService;
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
 * 候选记忆提取消费端；抛异常触发 RocketMQ 重试。
 *
 * <p><b>消费线程数刻意压到 2</b>（原 {@code memoryCandidateTaskExecutor} 是 core=1/max=2）：
 * RocketMQ 默认每消费者 20 个消费线程，而这里的处理会调 LLM——项目压测结论是
 * 真瓶颈在供应商速率限制，不显式限流等于把有界准入拆掉，429 会成片出现。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "blog.ai.memory.extraction", name = "use-mq", havingValue = "true")
@RocketMQMessageListener(
        topic = "${blog.ai.memory.memory-extraction-topic:ai-memory-extraction}",
        consumerGroup = "${ROCKETMQ_MEMORY_CONSUMER_GROUP:blog-memory-consumer}",
        selectorExpression = "CANDIDATE",
        consumeMode = ConsumeMode.CONCURRENTLY,
        messageModel = MessageModel.CLUSTERING,
        consumeThreadNumber = 1,
        consumeThreadMax = 2
)
@RequiredArgsConstructor
public class RocketMqCandidateMemoryConsumer implements RocketMQListener<MemoryExtractionEvent> {

    private final AiMemoryCandidateExtractorService extractor;
    private final MemoryExtractionIdempotencyStore idempotencyStore;

    @Override
    @Transactional
    public void onMessage(MemoryExtractionEvent event) {
        Map<String, String> previousMdc = MqTraceContext.begin(event == null ? null : event.traceId());
        try {
            log.debug("[MQ-MEMORY] consume candidate eventId={}", event == null ? null : event.eventId());
            if (event == null || event.eventType() != MemoryExtractionEvent.EventType.CANDIDATE) {
                throw new IllegalArgumentException("Unsupported memory extraction event");
            }
            if (!idempotencyStore.tryStart(event.eventId(), event.eventType().name())) {
                log.info("[MQ-MEMORY] duplicate candidate event skipped, eventId={}", event.eventId());
                return;
            }
            extractor.processEvent(event);
            idempotencyStore.markSucceeded(event.eventId());
        } finally {
            MqTraceContext.end(previousMdc);
        }
    }
}
