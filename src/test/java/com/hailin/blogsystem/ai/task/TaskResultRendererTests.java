package com.hailin.blogsystem.ai.task;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 汇总文案测试（第三刀·第一步）。
 *
 * <p>锁的是三条硬约束：DISPATCHED 说"已发起"、SKIPPED 说明原因、
 * 有 uncoveredGoal 时**不得**说"全部完成"。
 */
class TaskResultRendererTests {

    private final TaskResultRenderer renderer = new TaskResultRenderer();

    private String render(StepResult... results) {
        return render(List.of(), false, results);
    }

    /** 计划与结果一次构造，避免两处参数不一致导致断言失去意义 */
    private String render(List<String> uncovered, boolean paused, StepResult... results) {
        List<TaskStep> steps = Arrays.stream(results)
                .map(r -> new TaskStep(r.stepId(), StepType.ARTICLE_WRITE, Map.of(),
                        StepDependency.none(), List.of("原话")))
                .toList();
        TaskPlan plan = new TaskPlan("原话", steps, uncovered);
        return renderer.render(OrchestrationOutcome.of(plan, List.of(results), paused));
    }

    @Test
    void insufficientAndIncompleteNeverRenderAsAllDone() {
        // 第四步·§13.8④：证据不足 / 阶段性结果都落 FAILED -> 进 undone -> allDone 自然为 false，
        // 所以渲染器**不需要改**。这条守住"别为了文案去动 allDone 判定"——
        // 一改就等于把刚修好的如实性又开一个口子。
        for (StepCode code : List.of(StepCode.INSUFFICIENT_EVIDENCE, StepCode.INCOMPLETE_RESULT)) {
            String text = render(StepResult.failed("s1", code, "阶段性内容，未证明完整达成", Map.of()));

            assertThat(text).as("code=%s 不得说全部完成", code).doesNotContain("已全部完成");
            assertThat(text).as("code=%s 应落在未完成区域", code).contains("未完成");
        }
    }

    @Test
    void allSuccessSaysAllDone() {
        String text = render(
                StepResult.applied("s1", "点赞成功", Map.of()),
                StepResult.applied("s2", "收藏成功", Map.of()));

        assertThat(text).startsWith("已全部完成：");
        assertThat(text).contains("点赞成功").contains("收藏成功");
    }

    @Test
    void dispatchedSaysStartedNotDone() {
        String text = render(StepResult.dispatched("s1", "跳转到作者主页", Map.of()));

        // 客户端步骤后端不知道结果——只能说"已发起"
        assertThat(text).contains("已发起");
        assertThat(text).doesNotContain("已完成：跳转到作者主页");
    }

    @Test
    void partialSuccessShowsBothSides() {
        String text = render(
                StepResult.applied("s1", "点赞成功", Map.of()),
                StepResult.failed("s2", StepCode.TARGET_NOT_FOUND, "没有找到这篇文章"));

        assertThat(text).contains("已完成：").contains("点赞成功");
        assertThat(text).contains("未完成：").contains("没有找到这篇文章");
        assertThat(text).doesNotContain("已全部完成");
    }

    @Test
    void skippedStepExplainsWhy() {
        String text = render(
                StepResult.applied("s1", "点赞成功", Map.of()),
                StepResult.skipped("s2", "缺少前置数据（s1 没有产出）"));

        assertThat(text).contains("缺少前置数据");
        assertThat(text).doesNotContain("已全部完成");
    }

    @Test
    void uncoveredGoalBlocksAllDoneClaim() {
        // 用户说了两件事、系统只承接了一件——"这件做完了"是真话，但不能说"全部完成"
        String text = render(List.of("再分析一下它写得怎么样"), false,
                StepResult.applied("s1", "点赞成功", Map.of()));

        assertThat(text).doesNotContain("已全部完成");
        assertThat(text).contains("当前还不支持");
        assertThat(text).contains("再分析一下它写得怎么样");
    }

    @Test
    void pausedPlanMentionsRemainingSteps() {
        String text = render(List.of(), true,
                StepResult.waitingConfirm("s1", "是否隐藏这篇文章"),
                new StepResult("s2", StepState.PENDING, null, "尚未执行", Map.of()));

        assertThat(text).contains("待你确认");
        assertThat(text).contains("还有 1 个步骤没有执行");
        assertThat(text).doesNotContain("已全部完成");
    }
}
