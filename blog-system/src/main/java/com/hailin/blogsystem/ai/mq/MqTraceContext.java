package com.hailin.blogsystem.ai.mq;

import org.slf4j.MDC;

import java.util.Map;

/**
 * MQ 消费线程的日志上下文恢复。
 *
 * <p>生产端把 {@code traceId} 放进消息体；消费线程是 RocketMQ 自己的线程，
 * MDC 里没有请求上下文——不恢复的话，这条链路在日志里就断了。
 * 只恢复 traceId（不恢复 spanId）：消费是一次新的处理，不该冒充生产请求的 span。</p>
 */
final class MqTraceContext {

    private MqTraceContext() {
    }

    static Map<String, String> begin(String traceId) {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        if (traceId != null && !traceId.isBlank()) {
            MDC.put("traceId", traceId);
        }
        return previous;
    }

    static void end(Map<String, String> previous) {
        if (previous == null || previous.isEmpty()) {
            MDC.clear();
        } else {
            MDC.setContextMap(previous);
        }
    }
}
