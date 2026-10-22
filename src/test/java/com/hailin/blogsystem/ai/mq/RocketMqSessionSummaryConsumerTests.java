package com.hailin.blogsystem.ai.mq;

import com.hailin.blogsystem.service.AiConversationSummaryService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RocketMqSessionSummaryConsumerTests {

    private final AiConversationSummaryService summaryService = mock(AiConversationSummaryService.class);
    private final MemoryExtractionIdempotencyStore store = mock(MemoryExtractionIdempotencyStore.class);
    private final RocketMqSessionSummaryConsumer consumer =
            new RocketMqSessionSummaryConsumer(summaryService, store);

    @Test
    void duplicateEventSkipsCompression() {
        MemoryExtractionEvent event = MemoryExtractionEvent.sessionSummary(1L, 2L, 4L, null);
        when(store.tryStart(event.eventId(), event.eventType().name())).thenReturn(false);

        consumer.onMessage(event);

        verify(summaryService, never()).processEvent(event);
        verify(store, never()).markSucceeded(event.eventId());
    }

    @Test
    void firstDeliveryCompressesAndMarksSuccess() {
        MemoryExtractionEvent event = MemoryExtractionEvent.sessionSummary(1L, 2L, 4L, null);
        when(store.tryStart(event.eventId(), event.eventType().name())).thenReturn(true);

        consumer.onMessage(event);

        verify(summaryService).processEvent(event);
        verify(store).markSucceeded(event.eventId());
    }

    @Test
    void compressionFailurePropagatesForMqRetry() {
        MemoryExtractionEvent event = MemoryExtractionEvent.sessionSummary(1L, 2L, 4L, null);
        when(store.tryStart(event.eventId(), event.eventType().name())).thenReturn(true);
        org.mockito.Mockito.doThrow(new RuntimeException("llm timeout"))
                .when(summaryService).processEvent(event);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> consumer.onMessage(event))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("llm timeout");
        verify(store, never()).markSucceeded(event.eventId());
    }
}
