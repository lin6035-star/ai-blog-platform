package com.hailin.blogsystem.entity.dto;

/**
 * Agent Run 状态。
 *
 * - RUNNING：循环执行中
 * - COMPLETED：正常结束（FINAL_ANSWER 或 maxSteps 收尾）
 * - FAILED：非法动作 / LLM 决策无法修复 / 严重异常
 * - CANCELLED：取消（V1 预留，同步 Loop 暂不出现；V2.1 用于过期建议清理）
 * - WAITING_USER：ASK_USER 终态（V1 不恢复，用户下句话开新请求）
 * - WAITING_WORKFLOW_CONFIRM：SUGGEST_WORKFLOW 终态（V2.1，等待用户确认，确认后才启动 Workflow）
 * - WAITING_WRITE_CONFIRM：SUGGEST_WRITE 终态（V2.4，等待用户确认，确认后才执行写动作）
 * - CONFIRMING：confirm 进行中的短转移态（V2.1，仅后端内部，前端不可见）
 * - WAITING_WORKFLOW：**外层 TaskPlan 等待关联 Workflow 收敛**（5b）——它和上面两个 WAITING_*_CONFIRM
 *   的区别是语义方向：那两个是「Agent 提出建议、Workflow **还没创建**」，
 *   这个是「Workflow **已经创建**、外层计划在等它的结果」。进入等待态后原请求结束、释放 worker。
 */
public enum AiAgentRunStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
    WAITING_USER,
    WAITING_WORKFLOW_CONFIRM,
    WAITING_WRITE_CONFIRM,
    CONFIRMING,
    /** 外层 TaskPlan 等待关联 Workflow（5b）：合法等待态，启动恢复不得当残留 RUNNING 清掉 */
    WAITING_WORKFLOW
}
