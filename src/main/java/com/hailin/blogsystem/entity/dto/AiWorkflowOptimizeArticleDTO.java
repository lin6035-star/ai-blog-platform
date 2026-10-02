package com.hailin.blogsystem.entity.dto;

import lombok.Data;

@Data
public class AiWorkflowOptimizeArticleDTO {

    private Long conversationId;  //关联的 AI 会话 ID，可为空
    private Long articleId;  //待优化的文章 ID
    private String instruction;  //用户对本次优化的具体要求，例如：补充更多 Redis 实战细节
    private PageContextDTO pageContext;  //当前页面上下文，例如编辑器页、文章详情页

    /**
     * 外层任务 run id（5b）——由 {@code WORKFLOW_HANDOFF} 创建时才有值，普通独立 Workflow 为 null。
     *
     * <p>**必须在 Workflow 首次 save 前写入**（设计稿 §7.3）：创建后再补关联会留下
     * "Workflow 已存在但没挂到外层步骤"的窗口，恢复时那条记录就成了孤儿。
     */
    private Long taskRunId;

    /** 外层 TaskStep 标识（5b）——与 {@code ai_agent_steps.task_step_id} 对齐 */
    private String taskStepId;
}
