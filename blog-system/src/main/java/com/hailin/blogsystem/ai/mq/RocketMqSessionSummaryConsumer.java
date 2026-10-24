package com.hailin.blogsystem.ai.mq;

import com.hailin.blogsystem.service.AiConversationSummaryService;
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
 * 会话摘要压缩消费端。
 *
 * <p>摘要按"每轮对话结束"触发，真正的幂等/互斥由压缩行锁
 * （{@code ai_conversation_summaries.compressing}）承担：MQ 重复投递最多多跑一次检查，
 * 不会重复压缩同一段消息。事件级幂等表仍统一走 {@link MemoryExtractionIdempotencyStore}。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "blog.ai.memory.extraction", name = "use-mq", havingValue = "true")
@RocketMQMessageListener(
        topic = "${blog.ai.memory.memory-extraction-topic:ai-memory-extraction}",
        consumerGroup = "${ROCKETMQ_SUMMARY_CONSUMER_GROUP:blog-summary-consumer}",
        selectorExpression = "SESSION_SUMMARY",
        consumeMode = ConsumeMode.CONCURRENTLY,
        messageModel = MessageModel.CLUSTERING,
        consumeThreadNumber = 1,
        consumeThreadMax = 2
)
@RequiredArgsConstructor
public class RocketMqSessionSummaryConsumer implements RocketMQListener<MemoryExtractionEvent> {

    private final AiConversationSummaryService summaryService;
    private final MemoryExtractionIdempotencyStore idempotencyStore;

    @Override
    @Transactional
    public void onMessage(MemoryExtractionEvent event) {
        Map<String, String> previousMdc = MqTraceContext.begin(event == null ? null : event.traceId());
        try {
            log.debug("[MQ-MEMORY] consume session-summary eventId={}", event == null ? null : event.eventId());
            if (event == null || event.eventType() != MemoryExtractionEvent.EventType.SESSION_SUMMARY) {
                throw new IllegalArgumentException("Unsupported conversation summary event");
            }
            if (!idempotencyStore.tryStart(event.eventId(), event.eventType().name())) {
                log.info("[MQ-MEMORY] duplicate session-summary event skipped, eventId={}", event.eventId());
                return;
            }
            summaryService.processEvent(event);
            idempotencyStore.markSucceeded(event.eventId());
        } finally {
            MqTraceContext.end(previousMdc);
        }
    }
}
