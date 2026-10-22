package com.hailin.blogsystem.service;

import com.hailin.blogsystem.entity.AiMessages;

import java.util.List;

public interface AiConversationMemoryService {
    //查询某个会话最近n条消息，按时间升序
    List<AiMessages> getRecentMessages(Long sessionId,int maxMessages);

    /**
     * 同 {@link #getRecentMessages}，但**显式传入 userId**（2026-10-19）。
     *
     * <p>原方法依赖 {@code UserContext}（ThreadLocal）——而 Agent Runtime 跑在
     * ai-task 线程池、没有请求线程的 UserContext。通用 Agent 要在 worker 线程里
     * 读会话历史（回答「这个会话聊了什么」），所以需要这条不依赖 ThreadLocal 的入口。
     * 归属校验仍然做（session.userId == userId），不因为参数化而放松。
     */
    List<AiMessages> getRecentMessagesForUser(Long sessionId, Long userId, int maxMessages);
}
