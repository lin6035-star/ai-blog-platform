package com.hailin.blogsystem.ai.mq;

import com.hailin.blogsystem.service.AiConversationSummaryService;
import com.hailin.blogsystem.service.AiEpisodicMemoryExtractorService;
import com.hailin.blogsystem.service.AiMemoryCandidateExtractorService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryExtractionDispatcherTests {

    private final AiMemoryCandidateExtractorService legacy = mock(AiMemoryCandidateExtractorService.class);
    private final AiEpisodicMemoryExtractorService episodic = mock(AiEpisodicMemoryExtractorService.class);
    private final AiConversationSummaryService summary = mock(AiConversationSummaryService.class);
    private final MemoryExtractionPublisher publisher = mock(MemoryExtractionPublisher.class);
    private final ObjectProvider<MemoryExtractionPublisher> provider = providerOf(publisher);

    @Test
    void disabledMqKeepsUsingExistingAsyncExtractor() {
        MemoryExtractionDispatcher dispatcher = dispatcher(false);

        when(legacy.shouldExtractCandidate("用户消息")).thenReturn(true);
        dispatcher.dispatchCandidate(1L, 2L, 3L, "用户消息", "AI 回复");

        verify(legacy).extractAfterChat(1L, 2L, 3L, "用户消息", "AI 回复");
        verify(provider, never()).getIfAvailable();
    }

    @Test
    void disabledMqKeepsUsingAsyncEpisodicAndSummary() {
        MemoryExtractionDispatcher dispatcher = dispatcher(false);

        dispatcher.dispatchEpisodic(1L, 2L, 3L, 4L, "用户消息", "AI 回复");
        dispatcher.dispatchSessionSummary(1L, 2L, 4L);

        verify(episodic).extractAfterChat(1L, 2L, 3L, 4L, "用户消息", "AI 回复");
        verify(summary).compressAfterChat(1L, 2L);
        verify(provider, never()).getIfAvailable();
    }

    @Test
    void enabledMqPublishesCandidateEventInsteadOfCallingLegacyExtractor() {
        MemoryExtractionDispatcher dispatcher = dispatcher(true);

        when(legacy.shouldExtractCandidate("用户消息")).thenReturn(true);
        dispatcher.dispatchCandidate(1L, 2L, 3L, "用户消息", "AI 回复");

        ArgumentCaptor<MemoryExtractionEvent> captor = ArgumentCaptor.forClass(MemoryExtractionEvent.class);
        verify(publisher).publish(captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo(MemoryExtractionEvent.EventType.CANDIDATE);
        assertThat(captor.getValue().eventId()).isEqualTo("memory:candidate:3");
        verify(legacy, never()).extractAfterChat(any(), any(), any(), any(), any());
    }

    @Test
    void enabledMqPublishesEpisodicAndSessionSummaryEvents() {
        MemoryExtractionDispatcher dispatcher = dispatcher(true);

        dispatcher.dispatchEpisodic(1L, 2L, 3L, 4L, "用户消息", "AI 回复");
        dispatcher.dispatchSessionSummary(1L, 2L, 4L);

        ArgumentCaptor<MemoryExtractionEvent> captor = ArgumentCaptor.forClass(MemoryExtractionEvent.class);
        verify(publisher, org.mockito.Mockito.times(2)).publish(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(MemoryExtractionEvent::eventType)
                .containsExactly(MemoryExtractionEvent.EventType.EPISODIC,
                        MemoryExtractionEvent.EventType.SESSION_SUMMARY);
        assertThat(captor.getAllValues().get(0).eventId()).isEqualTo("memory:episodic:4");
        assertThat(captor.getAllValues().get(1).eventId()).isEqualTo("memory:summary:4");
        verify(episodic, never()).extractAfterChat(any(), any(), any(), any(), any(), any());
        verify(summary, never()).compressAfterChat(any(), any());
    }

    @Test
    void ruleMissSkipsBothLegacyExtractorAndMqPublisher() {
        MemoryExtractionDispatcher dispatcher = dispatcher(true);

        when(legacy.shouldExtractCandidate("普通问答")).thenReturn(false);
        dispatcher.dispatchCandidate(1L, 2L, 3L, "普通问答", "AI 回复");

        verify(legacy, never()).extractAfterChat(any(), any(), any(), any(), any());
        verify(provider, never()).getIfAvailable();
    }

    /**
     * fail-open：MQ 投递失败只能记日志，不能把异常抛回聊天收尾（STOP 事件）。
     * 迁移前 @Async 失败也是吞掉的，这条保持旧语义。
     */
    @Test
    void publishFailureIsFailOpenAndDoesNotThrow() {
        MemoryExtractionDispatcher dispatcher = dispatcher(true);
        when(legacy.shouldExtractCandidate("用户消息")).thenReturn(true);
        doThrow(new RuntimeException("broker down")).when(publisher).publish(any());

        assertThatCode(() -> dispatcher.dispatchCandidate(1L, 2L, 3L, "用户消息", "AI 回复"))
                .doesNotThrowAnyException();
        assertThatCode(() -> dispatcher.dispatchEpisodic(1L, 2L, 3L, 4L, "用户消息", "AI 回复"))
                .doesNotThrowAnyException();
        assertThatCode(() -> dispatcher.dispatchSessionSummary(1L, 2L, 4L))
                .doesNotThrowAnyException();
    }

    @Test
    void missingPublisherIsFailOpenForAllThreeChains() {
        ObjectProvider<MemoryExtractionPublisher> missing = mock(ObjectProvider.class);
        MemoryExtractionDispatcher dispatcher = new MemoryExtractionDispatcher(
                legacy, episodic, summary, missing);
        ReflectionTestUtils.setField(dispatcher, "useMq", true);

        assertThatCode(() -> dispatcher.dispatchEpisodic(1L, 2L, 3L, 4L, "用户消息", "AI 回复"))
                .doesNotThrowAnyException();
        assertThatCode(() -> dispatcher.dispatchSessionSummary(1L, 2L, 4L))
                .doesNotThrowAnyException();
    }

    private MemoryExtractionDispatcher dispatcher(boolean useMq) {
        MemoryExtractionDispatcher dispatcher =
                new MemoryExtractionDispatcher(legacy, episodic, summary, provider);
        ReflectionTestUtils.setField(dispatcher, "useMq", useMq);
        return dispatcher;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<MemoryExtractionPublisher> providerOf(MemoryExtractionPublisher publisher) {
        ObjectProvider<MemoryExtractionPublisher> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(publisher);
        return provider;
    }
}
