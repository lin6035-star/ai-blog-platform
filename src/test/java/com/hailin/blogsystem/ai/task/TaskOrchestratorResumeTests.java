package com.hailin.blogsystem.ai.task;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 协议层的**续跑**语义（5b 阶段 2）。
 *
 * <p>这里锁的是"续跑不等于重跑"：已经有结果的步骤必须被跳过，而且它的结果要在
 * **后置步骤执行之前**就已经在上下文里——否则 {@code requires} 拿不到数据，
 * 一个已经成功的前置步骤反而会把后置步骤拖成 SKIPPED。
 */
class TaskOrchestratorResumeTests {

    /** 记录执行次数与依赖输入的假执行器 */
    private static final class CountingExecutor implements TaskStepExecutor {

        private final StepType type;
        private final List<String> calls = new ArrayList<>();
        private Object observedAuthorId;

        private CountingExecutor(StepType type) {
            this.type = type;
        }

        /** 实际被执行过的 stepId（按调用顺序） */
        private List<String> calls() {
            return calls;
        }

        @Override
        public StepType supports() {
            return type;
        }

        @Override
        public StepResult execute(TaskExecutionContext context, TaskStep step) {
            calls.add(step.stepId());
            observedAuthorId = context.outputOf("s1").get("authorId");
            return StepResult.applied(step.stepId(), "搜索完成", Map.of());
        }
    }

    private static TaskStep step(String id, StepType type, StepDependency dependency) {
        return new TaskStep(id, type, Map.of(), dependency, List.of("证据"));
    }

    @Test
    @DisplayName("续跑：已有结果的步骤不重新执行（点赞不会被点第二次）")
    void doesNotReExecuteStepsWithPersistedResult() {
        CountingExecutor write = new CountingExecutor(StepType.ARTICLE_WRITE);
        CountingExecutor search = new CountingExecutor(StepType.ARTICLE_SEARCH);
        TaskOrchestrator orchestrator = new TaskOrchestrator(
                new TaskStepExecutorRegistry(List.of(write, search)));

        TaskPlan plan = new TaskPlan("点赞并搜索", List.of(
                step("s1", StepType.ARTICLE_WRITE, StepDependency.none()),
                step("s2", StepType.ARTICLE_SEARCH, StepDependency.after("s1"))
        ), List.of());

        Map<String, StepResult> persisted = Map.of("s1",
                StepResult.applied("s1", "点赞成功", Map.of("authorId", 7L)));
        TaskExecutionContext context = TaskExecutionContext.resume(
                1L, 2L, null, plan, 99L, TaskStepEventSink.noop(), persisted);

        OrchestrationOutcome outcome = orchestrator.resume(plan, context, null);

        assertThat(write.calls())
                .as("s1 已有结果，一次都不许再执行")
                .isEmpty();
        assertThat(search.calls()).containsExactly("s2");
        assertThat(outcome.results()).hasSize(2);
        assertThat(outcome.results().get(0).summary())
                .as("历史结果原样进汇总，不能被'重跑一遍'覆盖")
                .isEqualTo("点赞成功");
        assertThat(outcome.paused()).isFalse();
    }

    @Test
    @DisplayName("续跑：历史结果在执行前就可作为依赖输入")
    void persistedResultsAreVisibleAsDependencyInput() {
        CountingExecutor write = new CountingExecutor(StepType.ARTICLE_WRITE);
        CountingExecutor search = new CountingExecutor(StepType.ARTICLE_SEARCH);
        TaskOrchestrator orchestrator = new TaskOrchestrator(
                new TaskStepExecutorRegistry(List.of(write, search)));

        TaskPlan plan = new TaskPlan("点赞并搜索", List.of(
                step("s1", StepType.ARTICLE_WRITE, StepDependency.none()),
                step("s2", StepType.ARTICLE_SEARCH, StepDependency.requires("s1"))
        ), List.of());

        TaskExecutionContext context = TaskExecutionContext.resume(
                1L, 2L, null, plan, 99L, TaskStepEventSink.noop(),
                Map.of("s1", StepResult.applied("s1", "点赞成功", Map.of("authorId", 7L))));

        orchestrator.resume(plan, context, null);

        // 如果历史结果进上下文太晚，"跳转作者主页"这类 requires 会拿不到 authorId 而被判 SKIPPED
        assertThat(search.observedAuthorId).isEqualTo(7L);
    }

    @Test
    @DisplayName("续跑：历史步骤不再回调 listener（避免重复推送与重复落库）")
    void doesNotNotifyListenerForPersistedSteps() {
        CountingExecutor write = new CountingExecutor(StepType.ARTICLE_WRITE);
        CountingExecutor search = new CountingExecutor(StepType.ARTICLE_SEARCH);
        TaskOrchestrator orchestrator = new TaskOrchestrator(
                new TaskStepExecutorRegistry(List.of(write, search)));

        TaskPlan plan = new TaskPlan("点赞并搜索", List.of(
                step("s1", StepType.ARTICLE_WRITE, StepDependency.none()),
                step("s2", StepType.ARTICLE_SEARCH, StepDependency.after("s1"))
        ), List.of());
        TaskExecutionContext context = TaskExecutionContext.resume(
                1L, 2L, null, plan, 99L, TaskStepEventSink.noop(),
                Map.of("s1", StepResult.applied("s1", "点赞成功", Map.of())));

        List<String> notified = new ArrayList<>();
        orchestrator.resume(plan, context, (step, result) -> notified.add(step.stepId()));

        assertThat(notified).containsExactly("s2");
    }

    @Test
    @DisplayName("续跑：全部已有结果时不执行任何步骤（重复触发的幂等兜底）")
    void allCompletedResumesToNothing() {
        CountingExecutor write = new CountingExecutor(StepType.ARTICLE_WRITE);
        TaskOrchestrator orchestrator = new TaskOrchestrator(
                new TaskStepExecutorRegistry(List.of(write)));

        TaskPlan plan = new TaskPlan("点赞", List.of(
                step("s1", StepType.ARTICLE_WRITE, StepDependency.none())
        ), List.of());
        TaskExecutionContext context = TaskExecutionContext.resume(
                1L, 2L, null, plan, 99L, TaskStepEventSink.noop(),
                Map.of("s1", StepResult.applied("s1", "点赞成功", Map.of())));

        OrchestrationOutcome outcome = orchestrator.resume(plan, context, null);

        assertThat(write.calls()).isEmpty();
        assertThat(outcome.paused()).isFalse();
        assertThat(outcome.results()).hasSize(1);
    }

    @Test
    @DisplayName("续跑时再次等待：剩余步骤保持 PENDING，计划重新进入暂停")
    void resumeCanPauseAgain() {
        TaskOrchestrator orchestrator = new TaskOrchestrator(new TaskStepExecutorRegistry(List.of(
                new TaskStepExecutor() {
                    @Override
                    public StepType supports() {
                        return StepType.WORKFLOW_HANDOFF;
                    }

                    @Override
                    public StepResult execute(TaskExecutionContext context, TaskStep step) {
                        return StepResult.waitingConfirm(step.stepId(), "又需要确认");
                    }
                })));

        TaskPlan plan = new TaskPlan("两步都要确认", List.of(
                step("s1", StepType.WORKFLOW_HANDOFF, StepDependency.none()),
                step("s2", StepType.WORKFLOW_HANDOFF, StepDependency.after("s1"))
        ), List.of());
        TaskExecutionContext context = TaskExecutionContext.resume(
                1L, 2L, null, plan, 99L, TaskStepEventSink.noop(),
                Map.of("s1", StepResult.applied("s1", "第一步已完成", Map.of())));

        OrchestrationOutcome outcome = orchestrator.resume(plan, context, null);

        assertThat(outcome.paused()).isTrue();
        assertThat(outcome.results().get(0).state()).isEqualTo(StepState.SUCCEEDED);
        assertThat(outcome.results().get(1).state()).isEqualTo(StepState.WAITING_CONFIRM);
    }
}
