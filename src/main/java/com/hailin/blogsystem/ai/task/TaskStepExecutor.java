package com.hailin.blogsystem.ai.task;

/**
 * 单个任务步骤的执行器（第三刀）。
 *
 * <p>由 {@link TaskStepExecutorRegistry} 按 {@link StepType} 分派，Orchestrator 不写大 switch。
 *
 * <p><b>职责边界</b>：Orchestrator 负责顺序、依赖、失败策略、汇总；
 * 执行器负责**本步骤的事实校验与真实结果返回**——它不判断意图、不生成计划、不决定后续走向。
 *
 * <p>执行器需要的上下文（当前用户、页面上下文、前序步骤输出）统一从 {@link TaskExecutionContext} 取，
 * 而不是靠参数拼接：前序输出用 {@code context.outputOf(stepId)}，由执行器自己知道要什么键。
 */
public interface TaskStepExecutor {

    /** 本执行器承接的步骤类型 */
    StepType supports();

    /**
     * 执行一步。
     *
     * <p>实现约定：**不抛业务异常**——业务失败要返回带 {@link StepCode} 的
     * {@link StepResult#failed}，让汇总能如实说明原因。只有真正的系统异常才允许抛出，
     * 由 Orchestrator 兜底成 INTERNAL_ERROR。
     */
    StepResult execute(TaskExecutionContext context, TaskStep step);
}
