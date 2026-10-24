package com.hailin.blogsystem.ai.task;

import java.util.Map;

/**
 * 步骤执行结果（第三刀）——**执行事实**，不是文案。
 *
 * <p>程序判断只读 {@code state} / {@code code}；{@code summary} 只用于展示。
 * 汇总由 {@link TaskResultRenderer} 按 state/code 渲染，**不解析自然语言**。
 *
 * <p>工厂方法按状态分组，避免构造出"SUCCEEDED 配 CONFLICT"这类非法组合。
 */
public record StepResult(
        String stepId,
        StepState state,
        StepCode code,
        String summary,
        Map<String, Object> output
) {

    public StepResult {
        if (stepId == null || stepId.isBlank()) {
            throw new IllegalArgumentException("stepId 不能为空");
        }
        if (state == null) {
            throw new IllegalArgumentException("state 不能为空：" + stepId);
        }
        output = output == null ? Map.of() : Map.copyOf(output);
    }

    // ==================== 成功类 ====================

    /** 动作已实际应用 */
    public static StepResult applied(String stepId, String summary, Map<String, Object> output) {
        return new StepResult(stepId, StepState.SUCCEEDED, StepCode.APPLIED, summary, output);
    }

    /** 目标状态本已满足——属于成功 */
    public static StepResult alreadySatisfied(String stepId, String summary) {
        return new StepResult(stepId, StepState.SUCCEEDED, StepCode.ALREADY_SATISFIED, summary, Map.of());
    }

    /** 目标状态本已满足，同时保留领域层的结构化结果供前端刷新权威状态。 */
    public static StepResult alreadySatisfied(String stepId, String summary,
                                              Map<String, Object> output) {
        return new StepResult(stepId, StepState.SUCCEEDED, StepCode.ALREADY_SATISFIED, summary, output);
    }

    /** 客户端命令已发起——**后端不知道结果**，所以不是 SUCCEEDED */
    public static StepResult dispatched(String stepId, String summary, Map<String, Object> output) {
        return new StepResult(stepId, StepState.DISPATCHED, StepCode.APPLIED, summary, output);
    }

    /**
     * 分析类步骤得到了经证据门放行的答案（第四步）。
     *
     * <p>与 {@link #applied} 的区别只在 {@link StepCode}：分析不改状态，
     * 用 APPLIED 描述它会让「状态变更」与「只读结论」在审计里混为一谈。
     */
    public static StepResult answered(String stepId, String summary, Map<String, Object> output) {
        return new StepResult(stepId, StepState.SUCCEEDED, StepCode.ANSWERED, summary, output);
    }

    // ==================== 失败 / 跳过 ====================

    public static StepResult failed(String stepId, StepCode code, String summary) {
        return new StepResult(stepId, StepState.FAILED, code, summary, Map.of());
    }

    /**
     * 失败且需要携带结构化事实（第四步）。
     *
     * <p>为什么必须有这个重载：「证据不足」「只拿到阶段性结果」这类失败**不是系统故障**，
     * 外层需要知道子 run 是谁、用了几步、完成类型是什么（{@code agentRunId} / {@code usedSteps} /
     * {@code completionKind}）——没有它就只能返回 {@code Map.of()}，审计信息全丢。
     * record 本身从不限制 FAILED 携带 output，缺的只是工厂方法。
     */
    public static StepResult failed(String stepId, StepCode code, String summary,
                                    Map<String, Object> output) {
        return new StepResult(stepId, StepState.FAILED, code, summary, output);
    }

    /** 前置条件未满足——**没执行**，不是失败；reason 必须说明缺什么 */
    public static StepResult skipped(String stepId, String reason) {
        return new StepResult(stepId, StepState.SKIPPED, StepCode.TARGET_NOT_FOUND, reason, Map.of());
    }

    // ==================== 等待态 ====================

    public static StepResult waitingConfirm(String stepId, String summary) {
        return new StepResult(stepId, StepState.WAITING_CONFIRM, null, summary, Map.of());
    }

    public static StepResult waitingUser(String stepId, String summary) {
        return new StepResult(stepId, StepState.WAITING_USER, null, summary, Map.of());
    }

    // ==================== 判定 ====================

    /** 本步是否还会变化 */
    public boolean isTerminal() {
        return state != StepState.PENDING && state != StepState.RUNNING && !isWaiting();
    }

    public boolean isWaiting() {
        return state == StepState.WAITING_CONFIRM || state == StepState.WAITING_USER;
    }

    /**
     * 是否算「达成目标」。
     *
     * <p>DISPATCHED 算——它不保证前端真的跳成功，但后端该做的已经做完，
     * 且**不得**把它降级成失败；文案层面由渲染器说"已发起"而不是"已完成"。
     */
    public boolean isSuccessLike() {
        return state == StepState.SUCCEEDED || state == StepState.DISPATCHED;
    }
}
