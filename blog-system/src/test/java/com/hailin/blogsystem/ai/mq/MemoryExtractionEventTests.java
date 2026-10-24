package com.hailin.blogsystem.ai.mq;

import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.MessageConst;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.Message;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class MemoryExtractionEventTests {

    @Test
    void candidateEventUsesStableUserMessageBasedIdempotencyKey() {
        MemoryExtractionEvent event = MemoryExtractionEvent.candidate(
                1L, 2L, 3L, "用户消息", "AI 回复");

        assertThat(event.eventType()).isEqualTo(MemoryExtractionEvent.EventType.CANDIDATE);
        assertThat(event.eventId()).isEqualTo("memory:candidate:3");
        assertThat(event.userMessageId()).isEqualTo(3L);
        assertThat(event.assistantMessageId()).isNull();
    }

    @Test
    void episodicEventUsesStableAssistantMessageBasedIdempotencyKey() {
        MemoryExtractionEvent event = MemoryExtractionEvent.episodic(
                1L, 2L, 3L, 4L, "用户消息", "AI 回复");

        assertThat(event.eventType()).isEqualTo(MemoryExtractionEvent.EventType.EPISODIC);
        assertThat(event.eventId()).isEqualTo("memory:episodic:4");
        assertThat(event.assistantMessageId()).isEqualTo(4L);
    }

    /**
     * 会话摘要按"每轮结束"触发：eventId 必须用 assistantMessageId，
     * 用 sessionId 做 key 会让同一会话只有第一轮能通过幂等表。
     */
    @Test
    void sessionSummaryEventUsesAssistantMessageBasedKeyAndCarriesNoText() {
        MemoryExtractionEvent event = MemoryExtractionEvent.sessionSummary(1L, 2L, 4L, "trace-1");

        assertThat(event.eventType()).isEqualTo(MemoryExtractionEvent.EventType.SESSION_SUMMARY);
        assertThat(event.eventId()).isEqualTo("memory:summary:4");
        assertThat(event.userMessage()).isNull();
        assertThat(event.assistantReply()).isNull();
        assertThat(event.traceId()).isEqualTo("trace-1");
    }

    @Test
    void traceIdIsCarriedIntoEventPayload() {
        MemoryExtractionEvent candidate = MemoryExtractionEvent.candidate(
                1L, 2L, 3L, "用户消息", "AI 回复", "trace-c");
        MemoryExtractionEvent episodic = MemoryExtractionEvent.episodic(
                1L, 2L, 3L, 4L, "用户消息", "AI 回复", "trace-e");

        assertThat(candidate.traceId()).isEqualTo("trace-c");
        assertThat(episodic.traceId()).isEqualTo("trace-e");
    }

    @Test
    void eventRejectsMissingBusinessIdentity() {
        assertThatNullPointerException()
                .isThrownBy(() -> MemoryExtractionEvent.candidate(
                        null, 2L, 3L, "用户消息", "AI 回复"))
                .withMessage("userId");
    }

    /**
     * tag 必须通过 destination（topic:tag）发送：RocketMQTemplate 不认 Message header 里的 TAGS，
     * 之前实际发出去的消息 Tags=[null]，被消费端 selector 全部过滤（offset 照涨、无日志无死信）。
     */
    @Test
    void publisherUsesEventTypeTagInDestinationAndStableMessageKey() {
        RocketMQTemplate template = mock(RocketMQTemplate.class);
        when(template.syncSend(eq("test-memory-topic:CANDIDATE"), any(Message.class)))
                .thenReturn(mock(SendResult.class));

        RocketMqMemoryExtractionPublisher publisher = new RocketMqMemoryExtractionPublisher(template);
        ReflectionTestUtils.setField(publisher, "topic", "test-memory-topic");

        MemoryExtractionEvent event = MemoryExtractionEvent.candidate(
                1L, 2L, 3L, "用户消息", "AI 回复");
        publisher.publish(event);

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(template).syncSend(eq("test-memory-topic:CANDIDATE"), captor.capture());
        assertThat(captor.getValue().getHeaders().get(MessageConst.PROPERTY_KEYS))
                .isEqualTo(event.eventId());
    }
}
