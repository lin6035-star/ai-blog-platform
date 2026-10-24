package com.hailin.blogsystem.ai.mq;

import com.hailin.blogsystem.service.AiConversationSummaryService;
import com.hailin.blogsystem.service.AiEpisodicMemoryExtractorService;
import com.hailin.blogsystem.service.AiMemoryCandidateExtractorService;
import com.hailin.blogsystem.utils.MdcContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * AI 记忆异步链路的切换 seam：{@code @Async} ↔ RocketMQ。
 *
 * <p>三条链路（候选记忆 / 情景记忆 / 会话摘要）共用这个入口，业务代码只调它，
 * 不关心底层是线程池还是消息队列。{@code blog.ai.memory.extraction.use-mq=false} 时行为与迁移前一致。</p>
 *
 * <p><b>fail-open（2026-10-22 补）</b>：这些都不是用户可见的关键路径。
 * 原实现里 {@code @Async} 任务失败只打日志；接 MQ 后如果 {@code syncSend} 异常直接往上抛，
 * 会打断聊天收尾的 STOP 事件（{@code AiMessageServiceImpl} 的 {@code Mono.fromSupplier}），
 * 把"记忆丢了"升级成"这次对话收尾报错"。所以投递失败只记 WARN，不向外抛——
 * 它与旧语义一致：记忆是尽力而为，不能反噬聊天主链路。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryExtractionDispatcher {

    private final AiMemoryCandidateExtractorService legacyExtractor;
    private final AiEpisodicMemoryExtractorService episodicExtractor;
    private final AiConversationSummaryService conversationSummaryService;
    private final ObjectProvider<MemoryExtractionPublisher> publisherProvider;

    @Value("${blog.ai.memory.extraction.use-mq:false}")
    private boolean useMq;

    public void dispatchCandidate(Long userId, Long sessionId, Long messageId,
                                  String userMessage, String assistantReply) {
        // 规则预筛在生产者侧做一次：不命中的对话根本不值得发消息（消费者侧还会再判一次）
        if (!legacyExtractor.shouldExtractCandidate(userMessage)) {
            return;
        }
        if (!useMq) {
            legacyExtractor.extractAfterChat(userId, sessionId, messageId, userMessage, assistantReply);
            return;
        }
        publish(MemoryExtractionEvent.candidate(
                userId, sessionId, messageId, userMessage, assistantReply, currentTraceId()));
    }

    public void dispatchEpisodic(Long userId, Long sessionId,
                                 Long userMessageId, Long assistantMessageId,
                                 String userMessage, String assistantReply) {
        if (!useMq) {
            episodicExtractor.extractAfterChat(userId, sessionId, userMessageId,
                    assistantMessageId, userMessage, assistantReply);
            return;
        }
        // 情景记忆的触发条件含"会话消息数取模"，只能在消费时按 DB 现状判断，这里不做预筛
        publish(MemoryExtractionEvent.episodic(userId, sessionId, userMessageId,
                assistantMessageId, userMessage, assistantReply, currentTraceId()));
    }

    public void dispatchSessionSummary(Long userId, Long sessionId, Long assistantMessageId) {
        if (!useMq) {
            conversationSummaryService.compressAfterChat(userId, sessionId);
            return;
        }
        publish(MemoryExtractionEvent.sessionSummary(userId, sessionId, assistantMessageId, currentTraceId()));
    }

    /**
     * 投递失败不向外抛：记忆链路是尽力而为，不能反噬聊天收尾。
     * 失败留 WARN + eventId，便于按日志排查（对应消息确实没有进入 broker）。
     */
    private void publish(MemoryExtractionEvent event) {
        MemoryExtractionPublisher publisher = publisherProvider.getIfAvailable();
        if (publisher == null) {
            log.warn("[MQ-MEMORY] use-mq=true 但 publisher 不可用，事件未投递: eventId={}, type={}",
                    event.eventId(), event.eventType());
            return;
        }
        try {
            publisher.publish(event);
        } catch (Exception e) {
            log.warn("[MQ-MEMORY] publish failed, 事件未投递: eventId={}, type={}",
                    event.eventId(), event.eventType(), e);
        }
    }

    private String currentTraceId() {
        Map<String, String> mdc = MdcContext.capture();
        return mdc == null ? null : mdc.get("traceId");
    }
}
