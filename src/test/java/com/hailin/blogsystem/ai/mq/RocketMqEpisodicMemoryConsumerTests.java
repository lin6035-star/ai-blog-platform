package com.hailin.blogsystem.ai.mq;

import com.hailin.blogsystem.service.AiEpisodicMemoryExtractorService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RocketMqEpisodicMemoryConsumerTests {

    private final AiEpisodicMemoryExtractorService extractor = mock(AiEpisodicMemoryExtractorService.class);
    private final MemoryExtractionIdempotencyStore store = mock(MemoryExtractionIdempotencyStore.class);
    private final RocketMqEpisodicMemoryConsumer consumer =
            new RocketMqEpisodicMemoryConsumer(extractor, store);

    @Test
    void duplicateEventSkipsExtraction() {
        MemoryExtractionEvent event = MemoryExtractionEvent.episodic(
                1L, 2L, 3L, 4L, "完成 Redis 练习", "好的");
        when(store.tryStart(event.eventId(), event.eventType().name())).thenReturn(false);

        consumer.onMessage(event);

        verify(extractor, never()).processEvent(event);
        verify(store, never()).markSucceeded(event.eventId());
    }

    @Test
    void firstDeliveryExtractsAndMarksSuccess() {
        MemoryExtractionEvent event = MemoryExtractionEvent.episodic(
                1L, 2L, 3L, 4L, "完成 Redis 练习", "好的");
        when(store.tryStart(event.eventId(), event.eventType().name())).thenReturn(true);

        consumer.onMessage(event);

        verify(extractor).processEvent(event);
        verify(store).markSucceeded(event.eventId());
    }

    /** 失败必须向上抛：这是 RocketMQ 重试的前提，不能被 catch 吞掉。 */
    @Test
    void extractionFailurePropagatesForMqRetry() {
        MemoryExtractionEvent event = MemoryExtractionEvent.episodic(
                1L, 2L, 3L, 4L, "完成 Redis 练习", "好的");
        when(store.tryStart(event.eventId(), event.eventType().name())).thenReturn(true);
        org.mockito.Mockito.doThrow(new RuntimeException("llm timeout"))
                .when(extractor).processEvent(event);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> consumer.onMessage(event))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("llm timeout");
        verify(store, never()).markSucceeded(event.eventId());
    }
}
