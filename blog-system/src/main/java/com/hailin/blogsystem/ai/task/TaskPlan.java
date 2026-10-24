package com.hailin.blogsystem.ai.task;

import java.util.List;

/**
 * 任务计划（第三刀）——一次用户请求要完成的目标列表。
 *
 * <p>它是**后端校验后**的权威计划（由 {@code TaskPlanCandidate} 转换而来），
 * 不是分类器的原始输出：类别器只提候选，后端负责白名单 / 对象 / 权限 / 冲突 / 证据校验。
 *
 * <p><b>单任务 = 长度为 1 的 steps</b>，走完全相同的执行协议，不存在第二套路径。
 *
 * <p>{@code uncoveredGoalEvidence} 是"识别出来了、但当前没有执行器能承接"的目标证据。
 * 它的存在意味着**不得以"全部完成"收尾**——这是弱模型下的安全底线，
 * 不要求后端证明自然语言目标被 100% 理解，但要求它不谎报完成。
 */
public record TaskPlan(
        String goal,
        List<TaskStep> steps,
        List<String> uncoveredGoalEvidence,
        /**
         * 未覆盖目标的类型（与 {@code steps} 的 {@link StepType} 同一套命名）。
         *
         * <p>路由判据需要它：**"旧路径能不能完整处理这个请求"取决于未覆盖的是什么类型**——
         * 未覆盖的是 `ARTICLE_ANALYSIS` 时可以退回旧路径（第二刀已验证那个组合），
         * 是 `WORKFLOW_HANDOFF` 时退回只会执行一半、还声称完成。
         * 只有证据文本判不了这一点。
         */
        List<String> uncoveredTypes
) {

    public TaskPlan {
        if (goal == null || goal.isBlank()) {
            throw new IllegalArgumentException("goal 不能为空");
        }
        steps = steps == null ? List.of() : List.copyOf(steps);
        uncoveredGoalEvidence = uncoveredGoalEvidence == null
                ? List.of() : List.copyOf(uncoveredGoalEvidence);
        uncoveredTypes = uncoveredTypes == null ? List.of() : List.copyOf(uncoveredTypes);
    }

    /** 兼容旧签名（无类型的未覆盖目标） */
    public TaskPlan(String goal, List<TaskStep> steps, List<String> uncoveredGoalEvidence) {
        this(goal, steps, uncoveredGoalEvidence, List.of());
    }

    /** 是否存在尚未承接的目标——渲染器据此禁止"全部完成"文案 */
    public boolean hasUncoveredGoals() {
        return !uncoveredGoalEvidence.isEmpty();
    }

    /**
     * 本计划是否需要过 AI 长任务准入（第四步，2026-09-30）。
     *
     * <p>判据只看有没有长 LLM 步骤（{@link StepType#isLongRunningLlm()}）：
     * 纯确定性计划走快路径、不占长任务池。
     */
    public boolean requiresLongTaskAdmission() {
        return steps.stream().anyMatch(step -> step.type().isLongRunningLlm());
    }
}
