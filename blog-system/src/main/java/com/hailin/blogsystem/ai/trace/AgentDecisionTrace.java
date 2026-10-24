package com.hailin.blogsystem.ai.trace;

import com.hailin.blogsystem.entity.dto.AgentAction;
import com.hailin.blogsystem.entity.dto.AgentDecision;
import com.hailin.blogsystem.entity.dto.AiIntent;
import com.hailin.blogsystem.entity.dto.AiWorkflowType;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Agent 决策追踪：一次 Planner 裁决的快照。
 * 同时保存"模型建议"和"后端最终裁决"——看日志时能知道模型建议了什么、后端为什么接受或降级。
 */
@Data
@Builder
public class AgentDecisionTrace {

    private String requestId;
    private Long userId;
    private Long sessionId;

    // 只记录截断后的消息，避免日志过长
    private String messagePreview;

    // LLM 分类建议
    private String intent;
    private Double confidence;
    private String suggestedAction;
    private String suggestedWorkflowType;
    private String risk;

    /**
     * 分类器给出的任务字段（2026-10-18 补）。
     *
     * <p>事故当天只有 intent/action 被记录，{@code requestKind=TASK + taskGoals=[ARTICLE_SEARCH]}
     * 只存在于数据库的 task_plan_json 里——排障只能靠"steps=1"反推。
     * 这两个字段是"分类器到底怎么拆的"的最小证据集，不参与任何判定。
     */
    private String requestKind;
    private List<String> taskGoalTypes;

    // Planner 最终裁决
    private AgentAction action;
    private AiWorkflowType workflowType;
    private String toolName;
    private List<String> ruleHits;
    private String reason;

    private LocalDateTime createdAt;

    public static AgentDecisionTrace from(
            String requestId,
            Long userId,
            Long sessionId,
            String message,
            AiIntent intent,
            AgentDecision decision
    ) {
        return AgentDecisionTrace.builder()
                .requestId(requestId)
                .userId(userId)
                .sessionId(sessionId)
                .messagePreview(limit(message, 200))
                .intent(intent == null ? null : intent.getIntent())
                .confidence(intent == null ? null : intent.getConfidence())
                .suggestedAction(intent == null ? null : intent.getSuggestedAction())
                .suggestedWorkflowType(intent == null ? null : intent.getSuggestedWorkflowType())
                .risk(intent == null ? null : intent.getRisk())
                .requestKind(intent == null ? null : intent.getRequestKind())
                .taskGoalTypes(taskGoalTypes(intent))
                .action(decision == null ? null : decision.getAction())
                .workflowType(decision == null ? null : decision.getWorkflowType())
                .toolName(decision == null ? null : decision.getToolName())
                .ruleHits(decision == null || decision.getRuleHits() == null
                        ? List.of()
                        : List.copyOf(decision.getRuleHits()))
                .reason(decision == null ? null : decision.getReason())
                .createdAt(LocalDateTime.now())
                .build();
    }

    private static String limit(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength
                ? value
                : value.substring(0, maxLength);
    }

    /** taskGoals 只取 type 列表：够说明"模型拆了什么"，又不把 params 里的长文本灌进日志 */
    private static List<String> taskGoalTypes(AiIntent intent) {
        if (intent == null || intent.getTaskGoals() == null) {
            return List.of();
        }
        List<String> types = new ArrayList<>();
        for (var goal : intent.getTaskGoals()) {
            if (goal == null) {
                continue;
            }
            Object type = goal.get("type");
            if (type != null && !String.valueOf(type).isBlank()) {
                types.add(String.valueOf(type));
            }
        }
        return types;
    }
}
