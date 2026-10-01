package com.hailin.blogsystem.ai.task;

/**
 * 步骤事件监听（第三刀）——为 SSE / 审计预留，但第一步不接。
 *
 * <p>第一版的 Orchestrator 完全不知道 SSE 存在：它只在每步结束时通知 listener，
 * 接 SSE 时实现一个 listener 即可。**单测也借它断言事件顺序**，不必等真实推送。
 *
 * <p>用 {@link #noop()} 作为默认，避免 Orchestrator 到处判空（照 {@code AgentStepEmitter.noop()} 的模式）。
 */
public interface TaskStepListener {

    /** 一步结束（终态或等待态）时回调 */
    void onStepFinished(TaskStep step, StepResult result);

    /** 计划整体结束（全部终态，或暂停在等待态）时回调 */
    default void onPlanFinished(TaskPlan plan, boolean paused) {
    }

    static TaskStepListener noop() {
        return (step, result) -> {
        };
    }
}
