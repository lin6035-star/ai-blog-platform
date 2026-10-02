package com.hailin.blogsystem.ai.task;

import com.hailin.blogsystem.entity.AiWorkflowRun;
import com.hailin.blogsystem.entity.dto.AiWorkflowStatus;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Workflow 状态 → 外层 {@link StepResult} 的**唯一**映射（5b）。
 *
 * <p>为什么必须共用一份：这个映射有**两个调用方**——执行器（创建后立刻翻译一次）和
 * 恢复服务（终态回填）。各写一份的话，"FAILED 该映射成什么"这种关键判断迟早漂移，
 * 而它一旦不一致，就会出现"执行器认为这条还能重试、恢复服务却已经判它失败"的最难查的一类错。
 *
 * <p>最关键的一条：<b>{@code FAILED} → {@code WAITING_USER}，不是失败</b>。
 * 对外层计划而言 FAILED 仍保有合法的 {@code retry}，直接判失败等于把用户"重试一次就好"的路堵死。
 */
public final class WorkflowHandoffResults {

    private WorkflowHandoffResults() {
    }

    public static StepResult toStepResult(String taskStepId, AiWorkflowRun workflow) {
        String status = workflow == null ? null : workflow.getStatus();
        if (AiWorkflowStatus.COMPLETED.name().equals(status)) {
            return StepResult.applied(taskStepId, "优化流程已完成", output(workflow));
        }
        if (AiWorkflowStatus.CANCELLED.name().equals(status)) {
            // 用户明确放弃了这个步骤：如实失败，后续步骤按 requires / onFailure 决定跳过还是继续
            return StepResult.failed(taskStepId, StepCode.INTERNAL_ERROR, "优化流程已取消", output(workflow));
        }
        if (AiWorkflowStatus.FAILED.name().equals(status)) {
            return StepResult.waitingUser(taskStepId, "优化流程中断了，可以重试或取消");
        }
        // 其余（所有 WAITING_* 与 RUNNING）：外层计划停下来等它收敛
        return StepResult.waitingConfirm(taskStepId, waitingSummary(status));
    }

    /**
     * handoff 步骤的 output（§7.4）：恢复与展示都靠它找到关联的 Workflow。
     *
     * <p>值一律不放 null：{@link StepResult} 的构造器用 {@code Map.copyOf} 收紧输出，
     * 而它对 null 值**直接抛 NPE**——"version 没设"这种小事不该炸掉整个步骤。
     */
    public static Map<String, Object> output(AiWorkflowRun workflow) {
        Map<String, Object> output = new LinkedHashMap<>();
        if (workflow == null) {
            return output;
        }
        output.put("workflowRunId", workflow.getId());
        output.put("workflowType", workflow.getWorkflowType());
        output.put("workflowStatus", workflow.getStatus());
        output.put("latestWorkflowRunVersion", workflow.getVersion() == null ? 0 : workflow.getVersion());
        return output;
    }

    private static String waitingSummary(String workflowStatus) {
        if (workflowStatus != null && workflowStatus.startsWith("WAITING_")) {
            return "优化流程在等你确认";
        }
        return "优化流程正在执行，稍后继续";
    }
}
