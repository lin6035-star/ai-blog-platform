package com.hailin.blogsystem.ai.task;

import java.util.List;

/**
 * 步骤依赖（第三刀）——**三分类，不是一个 dependsOn**。
 *
 * <p>三者语义不同，合并会让实现各自解读：
 * <ul>
 *   <li>{@code after} —— **顺序要求**。用户明确说了先后。去掉它结果可能一样，但用户会认为"你没按我说的做"。</li>
 *   <li>{@code requires} —— **数据依赖**。没有前序的输出就做不了（跳转作者主页需要 authorId）。缺了必须 SKIPPED 并说明原因。</li>
 *   <li>{@code onFailure} —— **失败策略**。"点赞失败还要不要搜索"必须显式定义。</li>
 * </ul>
 *
 * <p>{@code after} 只判「前序是否执行过」，**不判成败**；成败由 {@code onFailure} 决定。
 */
public record StepDependency(
        List<String> after,
        List<String> requires,
        OnFailure onFailure
) {

    public StepDependency {
        after = after == null ? List.of() : List.copyOf(after);
        requires = requires == null ? List.of() : List.copyOf(requires);
        onFailure = onFailure == null ? OnFailure.CONTINUE : onFailure;
    }

    /** 起点步骤：无顺序要求、无数据依赖 */
    public static StepDependency none() {
        return new StepDependency(List.of(), List.of(), OnFailure.CONTINUE);
    }

    /** 仅顺序要求（用户说了"先…再…"） */
    public static StepDependency after(String... stepIds) {
        return new StepDependency(List.of(stepIds), List.of(), OnFailure.CONTINUE);
    }

    /** 需要前序输出（硬约束，拿不到就 SKIPPED） */
    public static StepDependency requires(String... stepIds) {
        return new StepDependency(List.of(stepIds), List.of(stepIds), OnFailure.SKIP);
    }

    public StepDependency withOnFailure(OnFailure strategy) {
        return new StepDependency(after, requires, strategy);
    }
}
