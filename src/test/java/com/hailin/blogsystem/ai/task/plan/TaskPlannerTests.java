package com.hailin.blogsystem.ai.task.plan;

import com.hailin.blogsystem.ai.task.OnFailure;
import com.hailin.blogsystem.ai.task.StepResult;
import com.hailin.blogsystem.ai.task.StepType;
import com.hailin.blogsystem.ai.task.TaskExecutionContext;
import com.hailin.blogsystem.ai.task.TaskStep;
import com.hailin.blogsystem.ai.task.TaskStepExecutor;
import com.hailin.blogsystem.ai.task.TaskStepExecutorRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 计划校验测试（第三刀·2c）。
 *
 * <p>锁三条规则：证据不成立整条拒绝、不认识的类型记入 uncovered 而不是丢弃、
 * 相反动作拒绝。**不锁"退回旧路径"**——那是路由层的策略，不属于 Planner。
 */
class TaskPlannerTests {

    /** 只登记两个类型：ARTICLE_WRITE / NAVIGATE。ARTICLE_SEARCH 故意不登记，用来测"无执行器" */
    private final TaskPlanner planner = new TaskPlanner(new TaskStepExecutorRegistry(List.of(
            fake(StepType.ARTICLE_WRITE), fake(StepType.NAVIGATE))));

    /** 5b 完整注册表：用来验证 handoff 后的第一版恢复边界，而不是“有没有执行器”。 */
    private final TaskPlanner handoffPlanner = new TaskPlanner(new TaskStepExecutorRegistry(List.of(
            fake(StepType.ARTICLE_WRITE),
            fake(StepType.ARTICLE_SEARCH),
            fake(StepType.NAVIGATE),
            fake(StepType.ARTICLE_ANALYSIS),
            fake(StepType.WORKFLOW_HANDOFF))));

    private static TaskStepExecutor fake(StepType type) {
        return new TaskStepExecutor() {
            @Override
            public StepType supports() {
                return type;
            }

            @Override
            public StepResult execute(TaskExecutionContext context, TaskStep step) {
                return StepResult.applied(step.stepId(), "ok", Map.of());
            }
        };
    }

    private TaskPlanCandidate.CandidateGoal goal(String type, String evidence) {
        return goal(type, Map.of("action", "likeArticle"), evidence);
    }

    /** C0.1 起参数按 StepType 校验：非文章写动作的目标不能沿用上面那份"点赞"参数 */
    private TaskPlanCandidate.CandidateGoal goal(String type, Map<String, Object> params, String evidence) {
        return new TaskPlanCandidate.CandidateGoal(
                type, params, List.of(evidence), List.of(), List.of(), null);
    }

    private TaskPlanCandidate candidate(TaskPlanCandidate.RequestKind kind,
                                        TaskPlanCandidate.CandidateGoal... goals) {
        return new TaskPlanCandidate("原话", kind, List.of(goals), List.of());
    }

    private static final String MESSAGE = "点赞这篇文章，再跳转到作者主页";

    // ==================== C0.1：参数校验 / 序号稳定 / 依赖不串线 ====================

    @Test
    void illegalParamGoesUncoveredWithoutDroppingOthers() {
        PlanningResult result = planner.plan(MESSAGE,
                candidate(TaskPlanCandidate.RequestKind.TASK,
                        goal("ARTICLE_WRITE", Map.of("action", "changeTitle"), "点赞这篇文章"),
                        goal("NAVIGATE", Map.of("target", "userProfile"), "跳转到作者主页")));

        assertThat(result.isTask()).isTrue();
        assertThat(result.plan().steps()).as("只有合法那条建步骤").hasSize(1);
        assertThat(result.plan().steps().get(0).type()).isEqualTo(StepType.NAVIGATE);
        assertThat(result.plan().uncoveredTypes()).containsExactly("ARTICLE_WRITE");
        assertThat(result.plan().hasUncoveredGoals()).isTrue();
    }

    @Test
    void stepIdKeepsCandidateIndexWhenEarlierGoalRejected() {
        PlanningResult result = planner.plan(MESSAGE,
                candidate(TaskPlanCandidate.RequestKind.TASK,
                        goal("ARTICLE_WRITE", Map.of("action", "changeTitle"), "点赞这篇文章"),
                        goal("NAVIGATE", Map.of("target", "userProfile"), "跳转到作者主页")));

        assertThat(result.plan().steps()).hasSize(1);
        assertThat(result.plan().steps().get(0).stepId())
                .as("序号按候选位置分配——重排会让别处写 s2 的依赖指错对象")
                .isEqualTo("s2");
    }

    @Test
    void stepDependingOnRejectedCandidateIsAlsoRejected() {
        TaskPlanCandidate.CandidateGoal illegal = new TaskPlanCandidate.CandidateGoal(
                "ARTICLE_WRITE", Map.of("action", "changeTitle"),
                List.of("点赞这篇文章"), List.of(), List.of(), null);
        // 这一条本身完全合法，但它 requires 的是被拒的那条 → 不能静默丢掉依赖后照跑
        TaskPlanCandidate.CandidateGoal dependent = new TaskPlanCandidate.CandidateGoal(
                "NAVIGATE", Map.of("target", "userProfile"),
                List.of("跳转到作者主页"), List.of(), List.of("s1"), null);

        PlanningResult result = planner.plan(MESSAGE,
                candidate(TaskPlanCandidate.RequestKind.TASK, illegal, dependent));

        assertThat(result.plan().steps()).as("依赖非法步骤的那条同样不可执行").isEmpty();
        assertThat(result.plan().uncoveredTypes())
                .containsExactlyInAnyOrder("ARTICLE_WRITE", "NAVIGATE");
    }

    @Test
    void dependencyOnAcceptedCandidateStillWorks() {
        TaskPlanCandidate.CandidateGoal first = new TaskPlanCandidate.CandidateGoal(
                "NAVIGATE", Map.of("target", "userProfile"),
                List.of("跳转到作者主页"), List.of(), List.of(), null);
        TaskPlanCandidate.CandidateGoal second = new TaskPlanCandidate.CandidateGoal(
                "ARTICLE_WRITE", Map.of("action", "likeArticle"),
                List.of("点赞这篇文章"), List.of(), List.of("s1"), null);

        PlanningResult result = planner.plan(MESSAGE,
                candidate(TaskPlanCandidate.RequestKind.TASK, first, second));

        assertThat(result.plan().steps()).as("合法依赖不能被连带误杀").hasSize(2);
        assertThat(result.plan().steps().get(1).dependency().requires()).containsExactly("s1");
        assertThat(result.plan().steps().get(1).dependency().onFailure())
                .as("requires 是硬约束，拿不到数据要 SKIP")
                .isEqualTo(OnFailure.SKIP);
    }

    // ==================== C0 收尾：onFailure 三态 ====================

    @Test
    void explicitlyUnknownOnFailureIsRejected() {
        // 模型编出来的 STOP：不能静默降级成 CONTINUE——
        // 那等于把"失败就停"执行成"失败也照常跑"，语义正好相反
        TaskPlanCandidate.CandidateGoal goal = new TaskPlanCandidate.CandidateGoal(
                "ARTICLE_WRITE", Map.of("action", "likeArticle"),
                List.of("点赞这篇文章"), List.of(), List.of(), "STOP");

        PlanningResult result = planner.plan(MESSAGE,
                candidate(TaskPlanCandidate.RequestKind.TASK, goal));

        assertThat(result.plan().steps()).as("显式非法值必须让该目标不被承接").isEmpty();
        assertThat(result.plan().uncoveredTypes()).containsExactly("ARTICLE_WRITE");
    }

    @Test
    void blankOnFailureFallsBackToContinue() {
        // 空白 = 未提供（模型经常这么输出），允许默认 CONTINUE
        TaskPlanCandidate.CandidateGoal goal = new TaskPlanCandidate.CandidateGoal(
                "ARTICLE_WRITE", Map.of("action", "likeArticle"),
                List.of("点赞这篇文章"), List.of(), List.of(), "   ");

        PlanningResult result = planner.plan(MESSAGE,
                candidate(TaskPlanCandidate.RequestKind.TASK, goal));

        assertThat(result.plan().steps()).hasSize(1);
        assertThat(result.plan().steps().get(0).dependency().onFailure())
                .isEqualTo(OnFailure.CONTINUE);
    }

    @Test
    void onFailureParsingIsCaseInsensitive() {
        TaskPlanCandidate.CandidateGoal goal = new TaskPlanCandidate.CandidateGoal(
                "ARTICLE_WRITE", Map.of("action", "likeArticle"),
                List.of("点赞这篇文章"), List.of(), List.of(), "confirm");

        PlanningResult result = planner.plan(MESSAGE,
                candidate(TaskPlanCandidate.RequestKind.TASK, goal));

        assertThat(result.plan().steps()).as("小写要能用，别修着修着把它弄成非法").hasSize(1);
        assertThat(result.plan().steps().get(0).dependency().onFailure())
                .isEqualTo(OnFailure.CONFIRM);
    }

    // ==================== 不是任务 ====================

    @Test
    void chatKindIsNotTask() {
        PlanningResult result = planner.plan(MESSAGE,
                candidate(TaskPlanCandidate.RequestKind.CHAT));

        assertThat(result.outcome()).isEqualTo(PlanningResult.Outcome.NOT_TASK);
    }

    @Test
    void emptyGoalsIsNotTask() {
        PlanningResult result = planner.plan(MESSAGE,
                candidate(TaskPlanCandidate.RequestKind.TASK));

        assertThat(result.outcome()).isEqualTo(PlanningResult.Outcome.NOT_TASK);
    }

    // ==================== 正常路径 ====================

    @Test
    void singleGoalBecomesOneStepPlan() {
        PlanningResult result = planner.plan(MESSAGE,
                candidate(TaskPlanCandidate.RequestKind.TASK,
                        goal("ARTICLE_WRITE", "点赞这篇文章")));

        assertThat(result.isTask()).isTrue();
        assertThat(result.plan().steps()).hasSize(1);
        assertThat(result.plan().steps().get(0).type()).isEqualTo(StepType.ARTICLE_WRITE);
        assertThat(result.plan().steps().get(0).stepId()).isEqualTo("s1");
    }

    @Test
    void multiGoalBecomesMultiStepPlanInOrder() {
        PlanningResult result = planner.plan(MESSAGE,
                candidate(TaskPlanCandidate.RequestKind.TASK,
                        goal("ARTICLE_WRITE", "点赞这篇文章"),
                        goal("NAVIGATE", Map.of("target", "userProfile"), "跳转到作者主页")));

        assertThat(result.isTask()).isTrue();
        assertThat(result.plan().steps()).hasSize(2);
        assertThat(result.plan().steps().get(0).stepId()).isEqualTo("s1");
        assertThat(result.plan().steps().get(1).stepId()).isEqualTo("s2");
    }

    // ==================== 5b：handoff 后置步骤边界 ====================

    @Test
    void searchIsAllowedAfterWorkflowHandoff() {
        String message = "优化这篇文章，再搜索 Redis 相关文章";
        PlanningResult result = handoffPlanner.plan(message,
                candidate(TaskPlanCandidate.RequestKind.TASK,
                        goal("WORKFLOW_HANDOFF", Map.of("workflowType", "OPTIMIZE_ARTICLE"), "优化这篇文章"),
                        goal("ARTICLE_SEARCH", Map.of("query", "Redis"), "搜索 Redis 相关文章")));

        assertThat(result.plan().steps())
                .extracting(TaskStep::type)
                .containsExactly(StepType.WORKFLOW_HANDOFF, StepType.ARTICLE_SEARCH);
        assertThat(result.plan().hasUncoveredGoals()).isFalse();
    }

    @Test
    void analysisAfterWorkflowHandoffIsUncoveredEvenWhenExecutorExists() {
        String message = "优化这篇文章，再分析一下它写得怎么样";
        PlanningResult result = handoffPlanner.plan(message,
                candidate(TaskPlanCandidate.RequestKind.TASK,
                        goal("WORKFLOW_HANDOFF", Map.of("workflowType", "OPTIMIZE_ARTICLE"), "优化这篇文章"),
                        goal("ARTICLE_ANALYSIS", Map.of(), "分析一下它写得怎么样")));

        assertThat(result.plan().steps())
                .extracting(TaskStep::type)
                .containsExactly(StepType.WORKFLOW_HANDOFF);
        assertThat(result.plan().uncoveredTypes()).containsExactly("ARTICLE_ANALYSIS");
    }

    @Test
    void navigationAfterWorkflowHandoffIsUncoveredByPositionNotDependency() {
        String message = "优化这篇文章，然后跳转到作者主页";
        PlanningResult result = handoffPlanner.plan(message,
                candidate(TaskPlanCandidate.RequestKind.TASK,
                        goal("WORKFLOW_HANDOFF", Map.of("workflowType", "OPTIMIZE_ARTICLE"), "优化这篇文章"),
                        goal("NAVIGATE", Map.of("target", "userProfile"), "跳转到作者主页")));

        assertThat(result.plan().steps())
                .extracting(TaskStep::type)
                .containsExactly(StepType.WORKFLOW_HANDOFF);
        assertThat(result.plan().uncoveredTypes()).containsExactly("NAVIGATE");
    }

    // ==================== 证据校验 ====================

    @Test
    void evidenceNotInMessageRejectsWholeCandidate() {
        PlanningResult result = planner.plan(MESSAGE,
                candidate(TaskPlanCandidate.RequestKind.TASK,
                        goal("ARTICLE_WRITE", "帮我收藏这篇文章")));   // 原话里没有

        // 证据不成立说明模型的拆解本身不可信——整条拒绝，不是只丢这一步
        assertThat(result.outcome()).isEqualTo(PlanningResult.Outcome.CTA);
    }

    @Test
    void oneBadEvidenceRejectsEvenIfOtherGoalsAreValid() {
        PlanningResult result = planner.plan(MESSAGE,
                candidate(TaskPlanCandidate.RequestKind.TASK,
                        goal("ARTICLE_WRITE", "点赞这篇文章"),
                        goal("NAVIGATE", "帮我关注这个作者")));   // 原话里没有

        assertThat(result.outcome()).isEqualTo(PlanningResult.Outcome.CTA);
    }

    // ==================== 未覆盖目标 ====================

    @Test
    void unknownTypeBecomesUncoveredInsteadOfDropped() {
        PlanningResult result = planner.plan(MESSAGE,
                candidate(TaskPlanCandidate.RequestKind.TASK,
                        goal("ARTICLE_WRITE", "点赞这篇文章"),
                        goal("DANCE_AROUND", "跳转到作者主页")));

        assertThat(result.isTask()).isTrue();
        assertThat(result.plan().steps()).hasSize(1);
        // 不认识的目标记入 uncovered，不静默丢
        assertThat(result.plan().uncoveredGoalEvidence()).contains("跳转到作者主页");
        assertThat(result.plan().hasUncoveredGoals()).isTrue();
    }

    @Test
    void typeWithoutExecutorBecomesUncovered() {
        PlanningResult result = planner.plan(MESSAGE,
                candidate(TaskPlanCandidate.RequestKind.TASK,
                        goal("ARTICLE_WRITE", "点赞这篇文章"),
                        goal("ARTICLE_SEARCH", "跳转到作者主页")));   // 类型认识但没登记执行器

        assertThat(result.isTask()).isTrue();
        assertThat(result.plan().uncoveredGoalEvidence()).contains("跳转到作者主页");
    }

    @Test
    void allGoalsUncoveredStillReturnsTaskForRouterToDecide() {
        PlanningResult result = planner.plan(MESSAGE,
                candidate(TaskPlanCandidate.RequestKind.TASK,
                        goal("DANCE_AROUND", "点赞这篇文章")));

        // 校验器**不决定路由**：它只把"一步都承接不了"这个事实摆出来
        //（TASK + 空 steps + uncovered）。该 CTA 还是该退回兼容路径，
        // 由 TaskPlanningFacade 按未覆盖目标的类型决定——那是路由策略，不是校验。
        assertThat(result.isTask()).isTrue();
        assertThat(result.plan().steps()).isEmpty();
        assertThat(result.plan().uncoveredTypes()).contains("DANCE_AROUND");
    }

    // ==================== 冲突 ====================

    @Test
    void oppositeActionsAreRejected() {
        TaskPlanCandidate.CandidateGoal like = new TaskPlanCandidate.CandidateGoal(
                "ARTICLE_WRITE", Map.of("action", "likeArticle"),
                List.of("点赞这篇文章"), List.of(), List.of(), null);
        TaskPlanCandidate.CandidateGoal unlike = new TaskPlanCandidate.CandidateGoal(
                "ARTICLE_WRITE", Map.of("action", "unlikeArticle"),
                List.of("跳转到作者主页"), List.of(), List.of(), null);

        PlanningResult result = planner.plan(MESSAGE,
                candidate(TaskPlanCandidate.RequestKind.TASK, like, unlike));

        assertThat(result.outcome()).isEqualTo(PlanningResult.Outcome.CTA);
    }

    // ==================== 依赖映射 ====================

    @Test
    void requiresIsKeptAndForcesSkipOnFailure() {
        TaskPlanCandidate.CandidateGoal first = new TaskPlanCandidate.CandidateGoal(
                "ARTICLE_WRITE", Map.of("action", "likeArticle"),
                List.of("点赞这篇文章"), List.of(), List.of(), null);
        TaskPlanCandidate.CandidateGoal second = new TaskPlanCandidate.CandidateGoal(
                "NAVIGATE", Map.of("target", "userProfile"),
                List.of("跳转到作者主页"), List.of("s1"), List.of("s1"), "CONTINUE");

        PlanningResult result = planner.plan(MESSAGE,
                candidate(TaskPlanCandidate.RequestKind.TASK, first, second));

        assertThat(result.isTask()).isTrue();
        // requires 是硬约束：不能被候选里的 onFailure=CONTINUE 覆盖
        assertThat(result.plan().steps().get(1).dependency().requires()).containsExactly("s1");
        assertThat(result.plan().steps().get(1).dependency().onFailure()).isEqualTo(OnFailure.SKIP);
    }

    @Test
    void dependencyPointingToMissingStepIsDropped() {
        TaskPlanCandidate.CandidateGoal only = new TaskPlanCandidate.CandidateGoal(
                "NAVIGATE", Map.of("target", "userProfile"),
                List.of("跳转到作者主页"), List.of("s9"), List.of("s9"), null);

        PlanningResult result = planner.plan(MESSAGE,
                candidate(TaskPlanCandidate.RequestKind.TASK, only));

        // s9 不存在：不能静默丢弃 requires/after，否则会把有前置条件的步骤变成无条件执行
        assertThat(result.plan().steps()).isEmpty();
        assertThat(result.plan().uncoveredTypes()).containsExactly("NAVIGATE");
    }

    @Test
    void unrecognizedDependencyReferenceIsRejectedInsteadOfDropped() {
        TaskPlanCandidate.CandidateGoal only = new TaskPlanCandidate.CandidateGoal(
                "NAVIGATE", Map.of("target", "userProfile"),
                List.of("跳转到作者主页"), List.of("第一步"), List.of(), null);

        PlanningResult result = planner.plan(MESSAGE,
                candidate(TaskPlanCandidate.RequestKind.TASK, only));

        assertThat(result.plan().steps()).isEmpty();
        assertThat(result.plan().uncoveredTypes()).containsExactly("NAVIGATE");
    }

    @Test
    void explicitNullParamIsValidatedInsteadOfThrowingBeforePlanner() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("action", null);
        TaskPlanCandidate.CandidateGoal only = new TaskPlanCandidate.CandidateGoal(
                "ARTICLE_WRITE", params, List.of("点赞这篇文章"), List.of(), List.of(), null);

        PlanningResult result = planner.plan("点赞这篇文章",
                candidate(TaskPlanCandidate.RequestKind.TASK, only));

        assertThat(result.plan().steps()).isEmpty();
        assertThat(result.plan().uncoveredTypes()).containsExactly("ARTICLE_WRITE");
    }
}
