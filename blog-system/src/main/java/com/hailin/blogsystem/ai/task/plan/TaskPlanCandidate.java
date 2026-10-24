package com.hailin.blogsystem.ai.task.plan;

import java.util.List;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 候选任务计划（第三刀·2c）——**模型给出的建议，不是权威计划**。
 *
 * <p>它与 {@code TaskPlan} 的区别，正是"候选"与"权威"的区别（沿用项目一贯的
 * 「LLM 判语义、后端判事实」）：
 * <ul>
 *   <li>{@code type} 是 **String 而不是枚举**——模型可能给出后端不认识的值，那属于正常输入，
 *       由 {@link TaskPlanner} 判定为"未覆盖目标"，而不是 DTO 解析失败；</li>
 *   <li>{@code goalEvidence} **未经验证**——只保证"模型说它来自原文"，是否真在原文里由 Planner 校验；</li>
 *   <li>没有 stepId——那是校验通过、生成正式计划时才分配的。</li>
 * </ul>
 *
 * <p>{@code requestKind} 让"这不是一个任务"（纯聊天）成为一个显式结论，
 * 而不是靠"goals 为空"隐式表达——后者会和"识别失败"混在一起。
 */
public record TaskPlanCandidate(
        String goal,
        RequestKind requestKind,
        List<CandidateGoal> goals,
        List<String> uncoveredGoalEvidence
) {

    public enum RequestKind {
        /** 这是一个可拆成步骤的任务 */
        TASK,
        /** 这不是任务（普通问答 / 闲聊），走原有路径 */
        CHAT
    }

    /** 一个候选目标——字段与 {@code TaskStep} 对应，但都不保证合法 */
    public record CandidateGoal(
            String type,
            Map<String, Object> params,
            List<String> goalEvidence,
            List<String> after,
            List<String> requires,
            String onFailure
    ) {
        public CandidateGoal {
            // 模型 JSON 里的显式 null 是合法的“缺失参数”表达，不能让 Map.copyOf
            // 在进入 Planner 前直接抛 NPE；参数校验器负责把它判为缺失。
            params = params == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(params));
            goalEvidence = goalEvidence == null ? List.of() : List.copyOf(goalEvidence);
            after = after == null ? List.of() : List.copyOf(after);
            requires = requires == null ? List.of() : List.copyOf(requires);
        }
    }

    public TaskPlanCandidate {
        goals = goals == null ? List.of() : List.copyOf(goals);
        uncoveredGoalEvidence = uncoveredGoalEvidence == null
                ? List.of() : List.copyOf(uncoveredGoalEvidence);
    }
}
