package com.hailin.blogsystem.ai.agent;

import com.hailin.blogsystem.entity.dto.PageContextDTO;

/**
 * 领域 Agent Runtime 统一接口（V2.5）。
 *
 * 学习域 / 文章域各自实现，由聊天链路（AiMessageServiceImpl 公共 SSE 管道）按意图分发调用。
 * pageContext 为后端权威页面上下文（可为 null），领域可忽略。
 */
public interface AgentRuntime {

    AgentRunResult run(
            Long userId,
            Long sessionId,
            String goal,
            PageContextDTO pageContext,
            AgentStepEmitter emitter
    );

    /**
     * 复合请求（动作 + 分析）里「分析段」的执行入口（2026-09-28，第二刀）。
     *
     * <p>默认退化为普通 {@link #run}——只有文章域覆写它，改走 analysis-only 策略
     * （窄 prompt + 只读白名单 + 禁止等待态）。其他域不会被调到这里：
     * 只有文章域的 Planner 分支会产出复合决策。
     *
     * <p>放接口上而不是让编排层做 {@code instanceof}，是为了让公共 SSE 管道
     * 对领域实现保持无类型判断——它只认这一个方法。
     */
    default AgentRunResult runAnalysisOnly(
            Long userId,
            Long sessionId,
            String goal,
            PageContextDTO pageContext,
            AgentStepEmitter emitter
    ) {
        return run(userId, sessionId, goal, pageContext, emitter);
    }

    /**
     * 编排层入口（第四步，2026-09-30）：显式带外层 Task run 的 ID。
     *
     * <p>与 5 参版本的差别只有**父子归属**——默认退化为 5 参，只有文章域覆写它：
     * 子 run 不清同 session 的待确认卡、日志额外带 taskRunId、Token 由父 run 按
     * {@code parent_run_id} 查询聚合。
     *
     * @param parentRunId 外层 Task run 的 ID（非空；编排层总是已创建外层 run 才调这里）
     */
    default AgentRunResult runAnalysisOnly(
            Long userId,
            Long sessionId,
            String goal,
            PageContextDTO pageContext,
            AgentStepEmitter emitter,
            Long parentRunId
    ) {
        return runAnalysisOnly(userId, sessionId, goal, pageContext, emitter);
    }
}
