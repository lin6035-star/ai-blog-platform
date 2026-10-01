package com.hailin.blogsystem.ai.task;

import java.util.List;

/**
 * 编排结果（第三刀）。
 *
 * <p>{@code results} 与 {@code plan.steps()} **一一对应、同序**——包括还没轮到的步骤，
 * 它们以 {@link StepState#PENDING} 出现。这样渲染器和前端不必自己去算"哪几步没跑"。
 *
 * <p>{@code paused}：计划是否停在等待态（某步需要确认或需要用户补充信息）。
 * 暂停不是失败也不是完成，剩余步骤保持 PENDING 且**不自动继续**（设计稿 §3.3）。
 *
 * <p>{@code agentRunId}：外层任务 run。聊天链路据此把 assistant message 关联到它，
 * 前端刷新后才能从消息进 inspection。为 null 表示这次执行没有落 run（纯协议单测）。
 */
public record OrchestrationOutcome(
        TaskPlan plan,
        List<StepResult> results,
        boolean paused,
        Long agentRunId
) {

    public OrchestrationOutcome {
        results = results == null ? List.of() : List.copyOf(results);
    }

    /** 无 run 关联的便捷构造（纯协议测试用） */
    public static OrchestrationOutcome of(TaskPlan plan, List<StepResult> results, boolean paused) {
        return new OrchestrationOutcome(plan, results, paused, null);
    }

    public OrchestrationOutcome withAgentRunId(Long runId) {
        return new OrchestrationOutcome(plan, results, paused, runId);
    }

    public boolean allSuccessLike() {
        return results.stream().allMatch(StepResult::isSuccessLike);
    }

    public boolean hasFailure() {
        return results.stream().anyMatch(r -> r.state() == StepState.FAILED);
    }
}
