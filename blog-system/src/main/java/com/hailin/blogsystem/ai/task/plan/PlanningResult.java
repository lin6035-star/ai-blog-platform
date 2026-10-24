package com.hailin.blogsystem.ai.task.plan;

import com.hailin.blogsystem.ai.task.TaskPlan;

/**
 * 计划校验结果（第三刀·2c）。
 *
 * <p>三态而不是"计划或空"——**"这不是一个任务"和"这是个任务但我不接"必须分开**：
 * 前者该走原有聊天路径，后者该给用户一个明确的 CTA。混在一起会让路由层无法判断。
 */
public record PlanningResult(
        Outcome outcome,
        TaskPlan plan,
        String reason
) {

    public enum Outcome {
        /** 校验通过，可以交给 Orchestrator */
        TASK,
        /** 不是任务（普通问答 / 闲聊），走原有路径 */
        NOT_TASK,
        /** 是任务，但当前无法完整承接——给用户明确说明，**不执行半截** */
        CTA
    }

    public static PlanningResult task(TaskPlan plan) {
        return new PlanningResult(Outcome.TASK, plan, null);
    }

    public static PlanningResult notTask(String reason) {
        return new PlanningResult(Outcome.NOT_TASK, null, reason);
    }

    public static PlanningResult cta(String reason) {
        return new PlanningResult(Outcome.CTA, null, reason);
    }

    public boolean isTask() {
        return outcome == Outcome.TASK;
    }
}
