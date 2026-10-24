package com.hailin.blogsystem.ai.agent;

import com.hailin.blogsystem.entity.dto.AgentStepActionType;

import java.util.List;
import java.util.Map;

/**
 * Agent 每一步的决策结果。
 *
 * 由决策器（V1 为 LLM，测试时为 mock）产生：
 * - actionType：白名单内的动作
 * - input：动作参数（如 QUERY_LEARNING_DASHBOARD 的关键词、SEARCH_RAG 的检索词）
 * - thoughtSummary：V3.10 思考摘要（清洗后，展示副产品）——LLM 决策 JSON 顶层 "thought"
 *   经 AgentThoughtSanitizer 分级闸门清洗后存入；null 表示无摘要（展示回退模板文案）。
 *   仅用于展示，不参与白名单校验/执行/下一轮决策。
 * - plan：V3.13 Plan Preview 子目标列表（LLM 决策 JSON 顶层 "plan"，仅首步采集）。
 *   与 thoughtSummary 是**两个独立字段**：plan 要落库、要被观测、阶段二还要进决策，
 *   生命周期与消费方都不同，不能合。阶段一同样不进 observations / 不进决策上下文。
 *   仅文章域启用（supportsPlanPreview），其他域恒为 null。
 */
public record AgentStepDecision(
        AgentStepActionType actionType,
        Map<String, Object> input,
        String thoughtSummary,
        List<String> plan
) {

    public static AgentStepDecision of(AgentStepActionType actionType) {
        return new AgentStepDecision(actionType, Map.of(), null, null);
    }

    /** 兼容旧调用（无计划）。 */
    public AgentStepDecision(
            AgentStepActionType actionType,
            Map<String, Object> input,
            String thoughtSummary
    ) {
        this(actionType, input, thoughtSummary, null);
    }

    public AgentStepDecision withInput(Map<String, Object> newInput) {
        // 保留 thoughtSummary / plan：V3.8 文章域 prepareStepDecision 是后端并入决议目标，
        // 不改变决策动机叙述与计划
        return new AgentStepDecision(actionType, newInput, thoughtSummary, plan);
    }

    /**
     * QUERY_MEMORY 的检索词候选，按优先级取第一个非空：
     * ① {@code userGoal}——Runtime 在 {@code prepareStepDecision} 里并入的 run 原始目标原话；
     * ② {@code question}——LLM 改写的检索词；③ {@code keyword}。
     *
     * <p><b>为什么原话排第一</b>（2026-10-16 实测，线上同一 embedding 模型 + ES knn 复现）：
     * 同一个问题上，原话 cosine=0.6241、LLM 改写=0.5607、两者拼接=0.6023——
     * 改写丢掉的信息比口语噪声更伤召回，拼接也不如原话干净。
     *
     * <p>返回 {@code null} 表示三者都空，由调用方用自己的领域兜底词
     * （通用域"用户情况"/ 文章域"写作偏好"/ 学习域"学习偏好"）。
     */
    public String memoryQuery() {
        String goal = text("userGoal");
        if (goal != null) {
            return goal;
        }
        String question = text("question");
        if (question != null) {
            return question;
        }
        return text("keyword");
    }

    private String text(String key) {
        if (input == null) {
            return null;
        }
        Object value = input.get(key);
        if (value == null) {
            return null;
        }
        String trimmed = String.valueOf(value).trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
