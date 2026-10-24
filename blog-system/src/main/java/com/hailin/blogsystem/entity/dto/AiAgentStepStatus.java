package com.hailin.blogsystem.entity.dto;

/**
 * Agent Step 状态。
 *
 * - RUNNING：执行中
 * - SUCCESS：动作执行成功，observation 已落库
 * - FAILED：动作执行失败（Redis / RAG / Memory 故障等）
 * - SKIPPED：未执行（如重复查询被后端主动省略）
 *
 * <p><b>5b 新增的三个只服务「外层编排步骤」</b>（{@code action_type=TASK_STEP}）：
 * 外层计划会**预创建全部步骤**，所以「还没轮到」是一个明确的持久化事实（{@link #PENDING}）；
 * 等待用户确认 / 处理的步骤也不能被记成失败（{@link #WAITING_CONFIRM} / {@link #WAITING_USER}）。
 * 而内部 Agent 步骤是**边跑边写**、不存在 PENDING，也不会进等待态——两边不要混用。
 */
public enum AiAgentStepStatus {
    RUNNING,
    SUCCESS,
    FAILED,
    SKIPPED,
    /** 外层计划预创建、尚未轮到执行（5b） */
    PENDING,
    /** 外层步骤在等用户确认（5b）——不是失败，也不是完成 */
    WAITING_CONFIRM,
    /** 外层步骤在等用户回答，或停在可重试失败上（5b） */
    WAITING_USER
}
