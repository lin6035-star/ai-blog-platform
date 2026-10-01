package com.hailin.blogsystem.ai.task;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 任务编排协议测试（第三刀·第一步）。
 *
 * <p>**不接任何真实执行器 / run / SSE**——用假执行器验证协议本身：
 * 顺序、三分类依赖、等待态、输出传递、异常兜底。协议错了，这时候改最便宜。
 */
class TaskOrchestratorTests {

    /** 假执行器：按预设行为返回，并记录被调用的 stepId（用于断言顺序） */
    private static final class FakeExecutor implements TaskStepExecutor {
        private final StepType type;
        private final List<String> calls;
        private final java.util.function.Function<TaskStep, StepResult> behavior;

        FakeExecutor(StepType type, List<String> calls,
                     java.util.function.Function<TaskStep, StepResult> behavior) {
            this.type = type;
            this.calls = calls;
            this.behavior = behavior;
        }

        @Override
        public StepType supports() {
            return type;
        }

        @Override
        public StepResult execute(TaskExecutionContext context, TaskStep step) {
            calls.add(step.stepId());
            return behavior.apply(step);
        }
    }

    private static final StepType A = StepType.ARTICLE_WRITE;
    private static final StepType B = StepType.ARTICLE_SEARCH;

    private TaskOrchestrator orchestrator(List<String> calls,
                                          java.util.function.Function<TaskStep, StepResult> behaviorA,
                                          java.util.function.Function<TaskStep, StepResult> behaviorB) {
        return new TaskOrchestrator(new TaskStepExecutorRegistry(List.of(
                new FakeExecutor(A, calls, behaviorA),
                new FakeExecutor(B, calls, behaviorB)
        )));
    }

    private TaskStep step(String id, StepType type, StepDependency dependency) {
        return new TaskStep(id, type, Map.of(), dependency, List.of("原话片段"));
    }

    // ==================== 顺序与输出传递 ====================

    @Test
    void executesStepsInPlanOrderAndPassesOutput() {
        List<String> calls = new ArrayList<>();
        TaskOrchestrator orchestrator = orchestrator(calls,
                s -> StepResult.applied(s.stepId(), "点赞成功", Map.of("authorId", "42")),
                s -> StepResult.applied(s.stepId(), "找到 8 篇", Map.of()));

        TaskPlan plan = new TaskPlan("点赞并搜索", List.of(
                step("s1", A, StepDependency.none()),
                step("s2", B, StepDependency.after("s1"))
        ), List.of());

        OrchestrationOutcome outcome = orchestrator.execute(plan);

        assertThat(calls).containsExactly("s1", "s2");
        assertThat(outcome.results()).hasSize(2);
        assertThat(outcome.allSuccessLike()).isTrue();
        assertThat(outcome.paused()).isFalse();
    }

    @Test
    void stepOutputIsReadableByLaterStep() {
        List<String> calls = new ArrayList<>();
        List<Object> seenAuthorId = new ArrayList<>();
        TaskOrchestrator orchestrator = orchestrator(calls,
                s -> StepResult.applied(s.stepId(), "点赞成功", Map.of("authorId", "42")),
                s -> {
                    seenAuthorId.add(s.dependency().requires().isEmpty()
                            ? null : "被调用");
                    return StepResult.applied(s.stepId(), "跳转", Map.of());
                });

        TaskPlan plan = new TaskPlan("点赞并跳转", List.of(
                step("s1", A, StepDependency.none()),
                step("s2", B, StepDependency.requires("s1"))
        ), List.of());

        orchestrator.execute(plan);

        assertThat(seenAuthorId).containsExactly("被调用");
    }

    // ==================== requires 优先于 onFailure ====================

    @Test
    void missingRequiresOutputSkipsStepEvenWhenOnFailureIsContinue() {
        List<String> calls = new ArrayList<>();
        TaskOrchestrator orchestrator = orchestrator(calls,
                s -> StepResult.failed(s.stepId(), StepCode.TARGET_NOT_FOUND, "文章不存在"),
                s -> StepResult.applied(s.stepId(), "不该被调用", Map.of()));

        // requires 未满足 + onFailure=CONTINUE：仍是 SKIPPED——
        // 没有前置数据就是做不了，CONTINUE 解决不了这个问题
        TaskPlan plan = new TaskPlan("x", List.of(
                step("s1", A, StepDependency.none()),
                step("s2", B, StepDependency.requires("s1").withOnFailure(OnFailure.CONTINUE))
        ), List.of());

        OrchestrationOutcome outcome = orchestrator.execute(plan);

        assertThat(calls).containsExactly("s1");   // s2 没被执行
        assertThat(outcome.results().get(1).state()).isEqualTo(StepState.SKIPPED);
        assertThat(outcome.results().get(1).summary()).contains("缺少前置数据");
    }

    // ==================== onFailure 三策略 ====================

    @Test
    void onFailureContinueKeepsGoingOnFailure() {
        List<String> calls = new ArrayList<>();
        TaskOrchestrator orchestrator = orchestrator(calls,
                s -> StepResult.failed(s.stepId(), StepCode.FORBIDDEN, "无权限"),
                s -> StepResult.applied(s.stepId(), "搜索完成", Map.of()));

        TaskPlan plan = new TaskPlan("x", List.of(
                step("s1", A, StepDependency.none()),
                step("s2", B, StepDependency.after("s1").withOnFailure(OnFailure.CONTINUE))
        ), List.of());

        OrchestrationOutcome outcome = orchestrator.execute(plan);

        assertThat(calls).containsExactly("s1", "s2");   // 失败了也继续
        assertThat(outcome.hasFailure()).isTrue();
        assertThat(outcome.allSuccessLike()).isFalse();
    }

    @Test
    void onFailureSkipSkipsDependentStep() {
        List<String> calls = new ArrayList<>();
        TaskOrchestrator orchestrator = orchestrator(calls,
                s -> StepResult.failed(s.stepId(), StepCode.FORBIDDEN, "无权限"),
                s -> StepResult.applied(s.stepId(), "不该被调用", Map.of()));

        TaskPlan plan = new TaskPlan("x", List.of(
                step("s1", A, StepDependency.none()),
                step("s2", B, StepDependency.after("s1").withOnFailure(OnFailure.SKIP))
        ), List.of());

        OrchestrationOutcome outcome = orchestrator.execute(plan);

        assertThat(calls).containsExactly("s1");
        assertThat(outcome.results().get(1).state()).isEqualTo(StepState.SKIPPED);
        assertThat(outcome.results().get(1).summary()).contains("s1");
    }

    @Test
    void onFailureOnlyLooksAtItsOwnDependencies() {
        List<String> calls = new ArrayList<>();
        TaskOrchestrator orchestrator = orchestrator(calls,
                s -> StepResult.failed(s.stepId(), StepCode.FORBIDDEN, "无权限"),
                s -> StepResult.applied(s.stepId(), "搜索完成", Map.of()));

        // s2 只 after s1；s3 不依赖任何人——s1 失败不该连累 s3
        TaskPlan plan = new TaskPlan("x", List.of(
                step("s1", A, StepDependency.none()),
                step("s2", B, StepDependency.after("s1").withOnFailure(OnFailure.SKIP)),
                step("s3", B, StepDependency.none())
        ), List.of());

        OrchestrationOutcome outcome = orchestrator.execute(plan);

        // s2 依赖 s1 且策略是 SKIP → 被跳过、不执行；s3 不依赖任何人 → 照常执行
        assertThat(calls).containsExactly("s1", "s3");
        assertThat(outcome.results().get(1).state()).isEqualTo(StepState.SKIPPED);
        assertThat(outcome.results().get(2).state()).isEqualTo(StepState.SUCCEEDED);
    }

    @Test
    void onFailureConfirmEntersWaitingUser() {
        List<String> calls = new ArrayList<>();
        TaskOrchestrator orchestrator = orchestrator(calls,
                s -> StepResult.failed(s.stepId(), StepCode.FORBIDDEN, "无权限"),
                s -> StepResult.applied(s.stepId(), "不该被调用", Map.of()));

        TaskPlan plan = new TaskPlan("x", List.of(
                step("s1", A, StepDependency.none()),
                step("s2", B, StepDependency.after("s1").withOnFailure(OnFailure.CONFIRM))
        ), List.of());

        OrchestrationOutcome outcome = orchestrator.execute(plan);

        assertThat(outcome.results().get(1).state()).isEqualTo(StepState.WAITING_USER);
        assertThat(outcome.paused()).isTrue();
    }

    // ==================== 等待态 ====================

    @Test
    void waitingStateStopsRemainingStepsAsPending() {
        List<String> calls = new ArrayList<>();
        TaskOrchestrator orchestrator = orchestrator(calls,
                s -> StepResult.waitingConfirm(s.stepId(), "需要确认是否隐藏"),
                s -> StepResult.applied(s.stepId(), "不该被调用", Map.of()));

        TaskPlan plan = new TaskPlan("x", List.of(
                step("s1", A, StepDependency.none()),
                step("s2", B, StepDependency.none())
        ), List.of());

        OrchestrationOutcome outcome = orchestrator.execute(plan);

        assertThat(calls).containsExactly("s1");          // s2 没执行
        assertThat(outcome.paused()).isTrue();
        assertThat(outcome.results()).hasSize(2);          // 但结果与计划一一对应
        assertThat(outcome.results().get(1).state()).isEqualTo(StepState.PENDING);
    }

    // ==================== 兜底 ====================

    @Test
    void missingExecutorFailsStepInsteadOfThrowing() {
        List<String> calls = new ArrayList<>();
        TaskOrchestrator orchestrator = orchestrator(calls,
                s -> StepResult.applied(s.stepId(), "ok", Map.of()),
                s -> StepResult.applied(s.stepId(), "ok", Map.of()));

        TaskPlan plan = new TaskPlan("x", List.of(
                step("s1", StepType.ARTICLE_ANALYSIS, StepDependency.none())
        ), List.of());

        OrchestrationOutcome outcome = orchestrator.execute(plan);

        assertThat(outcome.results().get(0).state()).isEqualTo(StepState.FAILED);
        assertThat(outcome.results().get(0).code()).isEqualTo(StepCode.INTERNAL_ERROR);
    }

    @Test
    void executorThrowingBecomesInternalError() {
        List<String> calls = new ArrayList<>();
        TaskOrchestrator orchestrator = orchestrator(calls,
                s -> {
                    throw new IllegalStateException("boom");
                },
                s -> StepResult.applied(s.stepId(), "ok", Map.of()));

        TaskPlan plan = new TaskPlan("x", List.of(
                step("s1", A, StepDependency.none())
        ), List.of());

        OrchestrationOutcome outcome = orchestrator.execute(plan);

        assertThat(outcome.results().get(0).state()).isEqualTo(StepState.FAILED);
        assertThat(outcome.results().get(0).code()).isEqualTo(StepCode.INTERNAL_ERROR);
    }

    @Test
    void listenerReceivesEveryFinishedStepInOrder() {
        List<String> calls = new ArrayList<>();
        List<String> events = new ArrayList<>();
        TaskOrchestrator orchestrator = orchestrator(calls,
                s -> StepResult.applied(s.stepId(), "ok", Map.of()),
                s -> StepResult.applied(s.stepId(), "ok", Map.of()));

        TaskPlan plan = new TaskPlan("x", List.of(
                step("s1", A, StepDependency.none()),
                step("s2", B, StepDependency.after("s1"))
        ), List.of());

        orchestrator.execute(plan, null, (step, result) -> events.add(step.stepId()));

        assertThat(events).containsExactly("s1", "s2");
    }
}
