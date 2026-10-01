package com.hailin.blogsystem.ai.task;

import com.hailin.blogsystem.entity.dto.PageContextDTO;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 任务执行上下文（第三刀）——执行器读取事实的地方。
 *
 * <p>为什么不用占位符（{@code "${step1.authorId}"} 那种）：要解析、易错、类型不安全。
 * 让执行器**自己从上下文取前序结果**更简单——它知道自己要什么键，也知道取不到时该怎么办。
 *
 * <p>{@link #record(StepResult)} 由 Orchestrator 调用，执行器只读。
 *
 * <p>第四步（2026-09-30）新增两项：{@link #taskRunId()}（执行器创建子 run 时作为 parentRunId）
 * 与 {@link #emitStepEvent}（把内部过程挂到对应 TaskStep 下）。
 */
public final class TaskExecutionContext {

    private final Long userId;
    private final Long sessionId;
    private final PageContextDTO pageContext;
    private final TaskPlan plan;

    /** 外层任务 run 的 id；纯协议单测为 null（此时执行器不该建子 run） */
    private final Long taskRunId;

    /** 内部过程事件出口；未接线时为 noop */
    private final TaskStepEventSink eventSink;

    private final Map<String, StepResult> results = new LinkedHashMap<>();

    /** 纯协议路径：不落 run、不推内部事件（Orchestrator 直调与单测走它） */
    public TaskExecutionContext(Long userId, Long sessionId, PageContextDTO pageContext, TaskPlan plan) {
        this(userId, sessionId, pageContext, plan, null, null);
    }

    public TaskExecutionContext(Long userId, Long sessionId, PageContextDTO pageContext, TaskPlan plan,
                                Long taskRunId, TaskStepEventSink eventSink) {
        this.userId = userId;
        this.sessionId = sessionId;
        this.pageContext = pageContext;
        this.plan = plan;
        this.taskRunId = taskRunId;
        this.eventSink = eventSink == null ? TaskStepEventSink.noop() : eventSink;
    }

    public Long userId() {
        return userId;
    }

    public Long sessionId() {
        return sessionId;
    }

    /** 后端权威页面上下文——执行器定位文章/作者时用它，不接受 LLM 自报的 ID */
    public PageContextDTO pageContext() {
        return pageContext;
    }

    public TaskPlan plan() {
        return plan;
    }

    /**
     * 外层任务 run 的 id——**执行器创建子 run 时把它作为 parentRunId**（第四步）。
     *
     * <p>为 null 表示这次执行没有外层 run（纯协议单测）：需要父子归属的执行器
     * 应当拒绝执行或明确降级，而不是建一个无父的 run。
     */
    public Long taskRunId() {
        return taskRunId;
    }

    /**
     * 推一条「步骤内部过程」事件（第四步）。
     *
     * <p>由执行器调用，**taskStepId 显式传入**——执行器本来就知道自己在为哪个步骤干活，
     * 不需要依赖编排串行，也不用 ThreadLocal。
     */
    public void emitStepEvent(String taskStepId, int stepNo, String actionType,
                              String status, String message, String thoughtSummary) {
        eventSink.emit(taskStepId, stepNo, actionType, status, message, thoughtSummary);
    }

    /** 前序步骤的完整结果；未执行或不存在返回 null */
    public StepResult resultOf(String stepId) {
        return results.get(stepId);
    }

    /**
     * 前序步骤的输出——{@code requires} 的数据来源。
     *
     * <p>返回空 Map 而不是 null：调用方通常只关心"这个键有没有"，
     * 而"步骤没跑过"和"跑过但没有输出"对它来说是同一件事（都拿不到数据，都该 SKIPPED）。
     */
    public Map<String, Object> outputOf(String stepId) {
        StepResult result = results.get(stepId);
        return result == null ? Map.of() : result.output();
    }

    /** 由 Orchestrator 在每步结束后调用 */
    void record(StepResult result) {
        results.put(result.stepId(), result);
    }

    /** 已记录的结果快照（供汇总与测试） */
    Map<String, StepResult> results() {
        return Map.copyOf(results);
    }
}
