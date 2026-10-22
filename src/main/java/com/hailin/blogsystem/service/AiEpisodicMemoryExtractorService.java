package com.hailin.blogsystem.service;

import com.hailin.blogsystem.ai.mq.MemoryExtractionEvent;

public interface AiEpisodicMemoryExtractorService {

    void extractAfterChat(
            Long userId,
            Long sessionId,
            Long userMessageId,
            Long assistantMessageId,
            String userMessage,
            String assistantReply
    );

    /**
     * RocketMQ 消费端入口：同步执行，失败向上抛以触发消息重试。
     */
    void processEvent(MemoryExtractionEvent event);
}
