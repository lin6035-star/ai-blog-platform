package com.hailin.blogsystem.ai.mq;

import java.time.Instant;
import java.util.Objects;

/**
 * AI 记忆事件（候选记忆 / 情景记忆 / 会话摘要共用一个 topic，用 tag 分流）。
 *
 * <p><b>eventId 由业务消息 ID 派生</b>：生产端重试不会因为随机 UUID 产生重复业务事件；
 * 例如候选记忆用 userMessageId、情景记忆与会话摘要用 assistantMessageId。
 * 消费端仍必须做幂等，RocketMQ 的投递语义是 at-least-once。</p>
 *
 * <p><b>为什么 payload 直接带消息文本</b>：这些事件要还原"当时那轮对话"；
 * 只放 ID 回查在消息被删改后会抽错上下文。代价是消息体偏大 + 对话文本落在 broker 存储里，
 * 取舍登记见 {@code docs/mq/rocketmq-integration-design.md} §8.2。</p>
 *
 * <p><b>traceId</b>：从生产线程 MDC 摘出来带进消息；消费线程会恢复到 MDC，
 * 这样跨 MQ 的日志还能用同一个 traceId 串起来。</p>
 */
public record MemoryExtractionEvent(
        String eventId,
        EventType eventType,
        Long userId,
        Long sessionId,
        Long userMessageId,
        Long assistantMessageId,
        String userMessage,
        String assistantReply,
        Instant occurredAt,
        String traceId
) {

    public enum EventType {
        CANDIDATE,
        EPISODIC,
        SESSION_SUMMARY
    }

    public MemoryExtractionEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }

    public static MemoryExtractionEvent candidate(
            Long userId,
            Long sessionId,
            Long userMessageId,
            String userMessage,
            String assistantReply
    ) {
        return candidate(userId, sessionId, userMessageId, userMessage, assistantReply, null);
    }

    public static MemoryExtractionEvent candidate(
            Long userId,
            Long sessionId,
            Long userMessageId,
            String userMessage,
            String assistantReply,
            String traceId
    ) {
        Objects.requireNonNull(userMessageId, "userMessageId");
        return new MemoryExtractionEvent(
                "memory:candidate:" + userMessageId,
                EventType.CANDIDATE,
                userId,
                sessionId,
                userMessageId,
                null,
                userMessage,
                assistantReply,
                Instant.now(),
                traceId
        );
    }

    public static MemoryExtractionEvent episodic(
            Long userId,
            Long sessionId,
            Long userMessageId,
            Long assistantMessageId,
            String userMessage,
            String assistantReply
    ) {
        return episodic(userId, sessionId, userMessageId, assistantMessageId,
                userMessage, assistantReply, null);
    }

    public static MemoryExtractionEvent episodic(
            Long userId,
            Long sessionId,
            Long userMessageId,
            Long assistantMessageId,
            String userMessage,
            String assistantReply,
            String traceId
    ) {
        Objects.requireNonNull(assistantMessageId, "assistantMessageId");
        return new MemoryExtractionEvent(
                "memory:episodic:" + assistantMessageId,
                EventType.EPISODIC,
                userId,
                sessionId,
                userMessageId,
                assistantMessageId,
                userMessage,
                assistantReply,
                Instant.now(),
                traceId
        );
    }

    /**
     * 会话摘要事件。
     *
     * <p><b>为什么 eventId 用 assistantMessageId</b>：摘要按"每轮对话结束"触发，
     * 若用 sessionId 做 eventId，同一个会话只有第一轮能通过幂等表，后续摘要全被吞掉。
     * 真正的并发压缩互斥仍由 {@code AiConversationSummaryServiceImpl} 的 compressing 行锁负责。</p>
     */
    public static MemoryExtractionEvent sessionSummary(
            Long userId,
            Long sessionId,
            Long assistantMessageId,
            String traceId
    ) {
        Objects.requireNonNull(assistantMessageId, "assistantMessageId");
        return new MemoryExtractionEvent(
                "memory:summary:" + assistantMessageId,
                EventType.SESSION_SUMMARY,
                userId,
                sessionId,
                null,
                assistantMessageId,
                null,
                null,
                Instant.now(),
                traceId
        );
    }
}
