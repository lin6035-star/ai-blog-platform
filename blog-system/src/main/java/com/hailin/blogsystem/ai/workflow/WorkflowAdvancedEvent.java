package com.hailin.blogsystem.ai.workflow;

/**
 * "Workflow 状态已推进"领域事件（5b）。
 *
 * <p>只带 id 不带实体：监听发生在**事务提交之后**，那时手上的实体快照可能已经过期，
 * 重新按 id 查一次才拿到真正的当前状态。
 */
public record WorkflowAdvancedEvent(Long workflowRunId) {
}
