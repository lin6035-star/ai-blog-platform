package com.hailin.blogsystem.entity.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent Run 步骤（V2.2 inspection 层，只读）。
 *
 * message 为思考面板展示文案（与实时 AGENT_STEP 事件一致，刷新前后不串味）；
 * thoughtSummary 为 V3.10 思考摘要（清洗后，nullable——前端行文本优先于 message）；
 * summary 为动作执行后的观察摘要（outputJson.summary），
 * 不返回 inputJson（决策输入可能含内部信息）。
 */
@Data
public class AgentStepVO {
    private Integer stepNo;
    private String actionType;
    private String status;
    private String errorMessage;
    private Long durationMs;
    private String message;
    private String thoughtSummary;
    private String summary;
    private LocalDateTime createdAt;

    /**
     * 第三刀：步骤层级——{@code AGENT}（Agent 循环内的思考步骤）/ {@code TASK}（外层任务步骤）。
     *
     * <p>两层都落在 {@code ai_agent_steps}，但**展示层级不同**：TASK 是「用户要的这件事」，
     * AGENT 是「为做成它 AI 内部查了什么」。历史恢复时前端据此分流到两个区域，
     * 否则任务步骤会被当成思考步骤渲染（它没有思考文案，会显示成默认占位）。
     */
    private String kind;

    /**
     * 第四步：该任务步骤由**子 run** 承接时透出子 run 的 id（否则 null）。
     *
     * <p>前端刷新恢复靠它二次补拉内部明细——子 run 的步骤属于子 run 自己，
     * **不在外层 run 的 steps 列表里**，不给这个 id 前端就无从定位。
     * 数据来源是任务步骤 {@code outputJson} 的 {@code data.agentRunId}。
     */
    private String childRunId;
}
