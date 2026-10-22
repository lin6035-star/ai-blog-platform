package com.hailin.blogsystem.ai.mq;

/** AI 记忆提取消息的持久化幂等记录。 */
public interface MemoryExtractionIdempotencyStore {

    /**
     * 尝试占用事件。返回 false 表示该事件已经被其他投递处理过或正在处理。
     */
    boolean tryStart(String eventId, String eventType);

    /** 标记事件已完成。调用方应与业务处理处于同一事务。 */
    void markSucceeded(String eventId);
}
