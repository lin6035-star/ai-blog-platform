package com.hailin.blogsystem.ai.agent;

import com.hailin.blogsystem.entity.dto.AiAgentRunStatus;

/**
 * Agent Run 执行结果（返回给聊天链路）。
 *
 * totalTokens：本次 run 循环的 token 消耗（含决策链 + 证据收敛门，**不含意图分类器**）。
 * 聊天链路把它加上分类器的消耗，一起写进 ai_messages.token_count——那个字段的语义是
 * 「这条消息的完整成本」，而 ai_agent_runs.total_tokens 只是 Agent 循环内部成本。
 *
 * pendingWorkflowSuggestion：仅 WAITING_WORKFLOW_CONFIRM 终态非空（V2.1），
 * pendingWriteAction：仅 WAITING_WRITE_CONFIRM 终态非空（V2.4），
 * 由聊天链路透出给前端渲染确认卡。
 *
 * completionKind（第四步·阶段 1，2026-09-30）：**只描述 status = COMPLETED 内部无法表达的差异**——
 * 同样落 COMPLETED，可能是正常回答、空答案兜底、证据不足保守收尾、步数到顶总结，或终局失败。
 * 外层任务编排据此如实回填 {@code StepResult}；**为 null 表示该出口不产出 COMPLETED、
 * 或来自尚未接线的兼容路径——此时外层必须防御性判失败，不能当成 ANSWER 放行**。
 */
public record AgentRunResult(
        Long agentRunId,
        AiAgentRunStatus status,
        String finalAnswer,
        int usedSteps,
        int totalTokens,
        AgentWorkflowSuggestion pendingWorkflowSuggestion,
        AgentWriteProposal pendingWriteAction,
        AgentCompletionKind completionKind
) {

    public static AgentRunResult of(
            Long agentRunId,
            AiAgentRunStatus status,
            String finalAnswer,
            int usedSteps,
            Integer totalTokens
    ) {
        return new AgentRunResult(agentRunId, status, finalAnswer, usedSteps, safe(totalTokens),
                null, null, null);
    }

    public static AgentRunResult of(
            Long agentRunId,
            AiAgentRunStatus status,
            String finalAnswer,
            int usedSteps,
            Integer totalTokens,
            AgentWorkflowSuggestion pendingWorkflowSuggestion
    ) {
        return new AgentRunResult(agentRunId, status, finalAnswer, usedSteps, safe(totalTokens),
                pendingWorkflowSuggestion, null, null);
    }

    public static AgentRunResult of(
            Long agentRunId,
            AiAgentRunStatus status,
            String finalAnswer,
            int usedSteps,
            Integer totalTokens,
            AgentWorkflowSuggestion pendingWorkflowSuggestion,
            AgentWriteProposal pendingWriteAction
    ) {
        return new AgentRunResult(agentRunId, status, finalAnswer, usedSteps, safe(totalTokens),
                pendingWorkflowSuggestion, pendingWriteAction, null);
    }

    /**
     * 带完成类型的收尾构造（第四步）：**只给 status = COMPLETED 的出口用**。
     *
     * <p>与上面三个重载不冲突——第 6 个参数类型不同（{@link AgentCompletionKind} 而非
     * {@link AgentWorkflowSuggestion}），编译器能区分。
     */
    public static AgentRunResult of(
            Long agentRunId,
            AiAgentRunStatus status,
            String finalAnswer,
            int usedSteps,
            Integer totalTokens,
            AgentCompletionKind completionKind
    ) {
        return new AgentRunResult(agentRunId, status, finalAnswer, usedSteps, safe(totalTokens),
                null, null, completionKind);
    }

    /** run 的 token 字段是 Integer（老数据/未统计时为 null），出口统一归一为 0。 */
    private static int safe(Integer totalTokens) {
        return totalTokens == null ? 0 : totalTokens;
    }
}
