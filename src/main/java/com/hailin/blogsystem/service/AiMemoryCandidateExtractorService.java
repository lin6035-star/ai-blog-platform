package com.hailin.blogsystem.service;

import com.hailin.blogsystem.ai.mq.MemoryExtractionEvent;

public interface AiMemoryCandidateExtractorService {

    boolean shouldExtractCandidate(String userMessage);

    /** RocketMQ 消费端入口；实现必须向上抛异常以触发消息重试。 */
    void processEvent(MemoryExtractionEvent event);

    //聊天结束后分析这一轮对话，必要时写入 ai_user_memory_candidates
    void extractAfterChat(Long userId,Long sessionId,Long messageId, String userMessage, String assistantReply);
}
