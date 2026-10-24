package com.hailin.blogsystem.service;

import com.hailin.blogsystem.ai.mq.MemoryExtractionEvent;
import com.hailin.blogsystem.entity.vo.AiConversationSummaryStatusVO;

public interface AiConversationSummaryService {

    void compressAfterChat(Long userId, Long sessionId);

    /**
     * RocketMQ 消费端入口：同步执行，失败向上抛以触发消息重试。
     */
    void processEvent(MemoryExtractionEvent event);

    String buildSummaryPrompt(Long userId, Long sessionId);

    /**
     * 查询当前登录用户某个会话的压缩状态（压缩中标记 + 最近压缩时间 + 已压缩消息数）。
     */
    AiConversationSummaryStatusVO getSummaryStatus(Long sessionId);

    /**
     * 删除会话时同步删除其压缩摘要。
     */
    void deleteBySession(Long userId, Long sessionId);
}