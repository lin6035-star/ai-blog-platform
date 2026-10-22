package com.hailin.blogsystem.ai.mq;

import org.apache.rocketmq.spring.support.RocketMQMessageConverter;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.converter.MessageConverter;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实序列化往返测试（2026-10-22 补）。
 *
 * <p>此前的 MQ 单测全部 mock 了 {@code RocketMQTemplate}，等于**从没验证过消息真的能发出去/收回来**。
 * 这条用 starter 自己的 {@link RocketMQMessageConverter}（与生产消费者同一套转换器）
 * 把事件序列化成 {@link Message} 再反序列化，锁住：
 * <ul>
 *   <li>record + {@code Instant} 字段能正常往返（Jackson 没注册 JavaTimeModule 时会在这一步炸）；</li>
 *   <li>新增的 {@code traceId} 字段不会破坏老消息反序列化。</li>
 * </ul>
 */
class MemoryExtractionEventConversionTests {

    private final MessageConverter converter =
            new RocketMQMessageConverter().getMessageConverter();

    @Test
    void candidateEventSurvivesProducerConsumerRoundTrip() {
        MemoryExtractionEvent event = MemoryExtractionEvent.candidate(
                1L, 2L, 3L, "用户消息", "AI 回复", "trace-1");

        MemoryExtractionEvent actual = roundTrip(event);

        assertThat(actual.eventId()).isEqualTo("memory:candidate:3");
        assertThat(actual.eventType()).isEqualTo(MemoryExtractionEvent.EventType.CANDIDATE);
        assertThat(actual.userMessage()).isEqualTo("用户消息");
        assertThat(actual.assistantReply()).isEqualTo("AI 回复");
        assertThat(actual.occurredAt()).isNotNull();
        assertThat(actual.traceId()).isEqualTo("trace-1");
    }

    @Test
    void sessionSummaryEventSurvivesRoundTrip() {
        MemoryExtractionEvent event = MemoryExtractionEvent.sessionSummary(1L, 2L, 4L, null);

        MemoryExtractionEvent actual = roundTrip(event);

        assertThat(actual.eventType()).isEqualTo(MemoryExtractionEvent.EventType.SESSION_SUMMARY);
        assertThat(actual.eventId()).isEqualTo("memory:summary:4");
        assertThat(actual.userMessage()).isNull();
        assertThat(actual.assistantReply()).isNull();
        assertThat(actual.occurredAt()).isNotNull();
    }

    private MemoryExtractionEvent roundTrip(MemoryExtractionEvent event) {
        Message<?> message = converter.toMessage(event, new MessageHeaders(Map.of()));
        assertThat(message).as("序列化结果不能为 null").isNotNull();

        Object payload = converter.fromMessage(message, MemoryExtractionEvent.class);
        assertThat(payload).as("反序列化结果类型").isInstanceOf(MemoryExtractionEvent.class);
        return (MemoryExtractionEvent) payload;
    }
}
