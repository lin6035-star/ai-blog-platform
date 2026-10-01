package com.hailin.blogsystem.ai.task;

import java.util.List;
import java.util.Map;

/**
 * 任务步骤（第三刀）。
 *
 * <p><b>粒度规则：一个 TaskStep = 用户能一眼看懂的一件事。</b>
 * 点赞这篇文章 / 跳转到作者主页 / 找 Redis 文章 ✅；
 * 查作者 ID / 读取文章 / 拼装 prompt ❌——这些是执行器内部的准备动作。
 *
 * <p>理由：计划长度要跟用户的心智模型对齐（用户说了两件事却看到三步计划会困惑），
 * 而且一旦允许拆细节，读文章、拼上下文这类都会冒出来，计划会碎掉。
 *
 * <p>不单独成步 ≠ 不留痕：执行器必须把内部解析出的 {@code authorId}、失败原因、最终命令
 * 写进该步骤的 {@link StepResult#output()}，否则出问题时无法解释"为什么导航没发起"。
 *
 * <p>{@code goalEvidence} 是模型摘录的用户原话**连续片段**，后端验证它真在原文里才接受——
 * 这是"没有静默丢目标"的唯一可验证手段（沿用第一刀 {@code analysisGoalEvidence} 的证据语义）。
 */
public record TaskStep(
        String stepId,
        StepType type,
        Map<String, Object> input,
        StepDependency dependency,
        List<String> goalEvidence
) {

    public TaskStep {
        if (stepId == null || stepId.isBlank()) {
            throw new IllegalArgumentException("stepId 不能为空");
        }
        if (type == null) {
            throw new IllegalArgumentException("type 不能为空：" + stepId);
        }
        input = input == null ? Map.of() : Map.copyOf(input);
        dependency = dependency == null ? StepDependency.none() : dependency;
        goalEvidence = goalEvidence == null ? List.of() : List.copyOf(goalEvidence);
    }
}
