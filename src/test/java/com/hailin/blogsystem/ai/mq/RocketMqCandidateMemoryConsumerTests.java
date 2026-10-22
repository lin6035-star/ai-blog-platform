package com.hailin.blogsystem.ai.mq;

import com.hailin.blogsystem.service.AiMemoryCandidateExtractorService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RocketMqCandidateMemoryConsumerTests {

    @Test
    void duplicateEventSkipsExtraction() {
        AiMemoryCandidateExtractorService extractor = mock(AiMemoryCandidateExtractorService.class);
        MemoryExtractionIdempotencyStore store = mock(MemoryExtractionIdempotencyStore.class);
        RocketMqCandidateMemoryConsumer consumer = new RocketMqCandidateMemoryConsumer(extractor, store);
        MemoryExtractionEvent event = MemoryExtractionEvent.candidate(
                1L, 2L, 3L, "记住我喜欢 Java", "好的");

        when(store.tryStart(event.eventId(), event.eventType().name())).thenReturn(false);
        consumer.onMessage(event);

        verify(extractor, never()).processEvent(event);
        verify(store, never()).markSucceeded(event.eventId());
    }

    @Test
    void firstDeliveryExtractsAndMarksSuccess() {
        AiMemoryCandidateExtractorService extractor = mock(AiMemoryCandidateExtractorService.class);
        MemoryExtractionIdempotencyStore store = mock(MemoryExtractionIdempotencyStore.class);
        RocketMqCandidateMemoryConsumer consumer = new RocketMqCandidateMemoryConsumer(extractor, store);
        MemoryExtractionEvent event = MemoryExtractionEvent.candidate(
                1L, 2L, 3L, "记住我喜欢 Java", "好的");

        when(store.tryStart(event.eventId(), event.eventType().name())).thenReturn(true);
        consumer.onMessage(event);

        verify(extractor).processEvent(event);
        verify(store).markSucceeded(event.eventId());
    }

    @Test
    void extractionFailurePropagatesAndDoesNotMarkSuccess() {
        AiMemoryCandidateExtractorService extractor = mock(AiMemoryCandidateExtractorService.class);
        MemoryExtractionIdempotencyStore store = mock(MemoryExtractionIdempotencyStore.class);
        RocketMqCandidateMemoryConsumer consumer = new RocketMqCandidateMemoryConsumer(extractor, store);
        MemoryExtractionEvent event = MemoryExtractionEvent.candidate(
                1L, 2L, 3L, "记住我喜欢 Java", "好的");
        RuntimeException failure = new RuntimeException("llm unavailable");

        when(store.tryStart(event.eventId(), event.eventType().name())).thenReturn(true);
        org.mockito.Mockito.doThrow(failure).when(extractor).processEvent(event);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> consumer.onMessage(event))
                .isSameAs(failure);
        verify(store, never()).markSucceeded(event.eventId());
    }
}
