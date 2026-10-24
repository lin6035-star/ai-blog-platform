package com.hailin.blogsystem.ai.task;

/**
 * 任务步骤的**内部过程**事件出口（第四步，2026-09-30）。
 *
 * <p><b>为什么用独立函数式接口而不是直接依赖 SSE 的 {@code AiChatEventVO}</b>：
 * 编排协议层（{@code ai.task} 包）不该知道传输层 DTO，纯协议单测也不该被迫构造聊天事件。
 * 聊天链路在装配 {@link TaskExecutionContext} 时把它接到 SSE sink 上。
 *
 * <p><b>为什么 taskStepId 显式携带</b>：不依赖「编排目前是串行」这个前提，也不靠 ThreadLocal。
 * 将来若真上并行步骤，需要重新处理的是 sink 的并发发送、listener 顺序与持久化计步，
 * 而**不是**「这条事件属于哪个步骤」——那件事在执行器里本来就是确定的。
 */
@FunctionalInterface
public interface TaskStepEventSink {

    /**
     * @param taskStepId 外层任务步骤的 id——事件挂载到哪一行的唯一依据
     * @param stepNo     步骤**内部**的序号（子 run 自己的 ReAct 步号，与外层计步互不干扰）
     */
    void emit(String taskStepId, int stepNo, String actionType, String status,
              String message, String thoughtSummary);

    /**
     * **Workflow 内部步骤事件**（5b，2026-10-01）。
     *
     * <p>为什么必须单开一个方法而不是复用 {@link #emit}：两者在前端是**两个层级**——
     * {@code emit} 发的是 AGENT_STEP，会挂到某个任务步骤下的展开明细里；
     * 这个方法发的是 WORKFLOW_STEP，作用是让 **Workflow 卡片立刻出现**。
     * 合成一个的话，"流程已经启动"在界面上就没有任何表示，用户要等方案生成完
     * 才看得到卡——中间那几十秒完全不知道发生了什么。
     *
     * <p>默认空实现：纯协议单测与不接线的链路不需要它。
     *
     * @param taskStepId 归属的外层任务步骤（Workflow 由这个步骤 handoff 而来）
     */
    default void emitWorkflowStep(Long workflowRunId, String workflowType, String step,
                                  String status, String message, String taskStepId) {
    }

    /** 未接线的默认出口：纯协议单测与不推事件的链路 */
    static TaskStepEventSink noop() {
        return (taskStepId, stepNo, actionType, status, message, thoughtSummary) -> {
        };
    }
}
