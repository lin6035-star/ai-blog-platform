package com.hailin.blogsystem.ai.task.plan;

import com.hailin.blogsystem.ai.task.StepResult;
import com.hailin.blogsystem.ai.task.StepType;
import com.hailin.blogsystem.ai.task.TaskExecutionContext;
import com.hailin.blogsystem.ai.task.TaskStep;
import com.hailin.blogsystem.ai.task.TaskStepExecutor;
import com.hailin.blogsystem.ai.task.TaskStepExecutorRegistry;
import com.hailin.blogsystem.entity.dto.AiIntent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 路由边界测试（第三刀·2c 收口）。
 *
 * <p><b>核心约束：不能让旧路径悄悄接管一个多目标请求。</b>
 * 旧路径对"点赞 + 优化并发布"只会执行点赞、然后照样声称完成——那正是要消灭的多目标静默丢失。
 * 所以退回旧路径必须是**确认它能完整处理**后才做的决定，不是兜底。
 *
 * <p>只登记 ARTICLE_WRITE，用来构造"有步骤没有执行器"的场景；
 * `ARTICLE_ANALYSIS` 故意不登记——它代表"未迁移但旧路径能完整处理"的那一类。
 */
class TaskPlanningFacadeTests {

    private final TaskPlanningFacade facade = new TaskPlanningFacade(
            new TaskPlanner(new TaskStepExecutorRegistry(List.of(fake(StepType.ARTICLE_WRITE)))));

    /**
     * 又登记了搜索执行器的门面：用来构造"计划里既有写动作也有检索"的场景
     * （不登记时搜索目标会变成 uncovered，测的就不是分析判据那条分支了）。
     */
    private final TaskPlanningFacade facadeWithSearch = new TaskPlanningFacade(
            new TaskPlanner(new TaskStepExecutorRegistry(
                    List.of(fake(StepType.ARTICLE_WRITE), fake(StepType.ARTICLE_SEARCH)))));

    /**
     * 第四步（ARTICLE_ANALYSIS 接入）之后的形态：分析也有执行器。
     * 用来锁住「分析一旦进了计划，那条 fail-closed 判据自动退场」。
     */
    private final TaskPlanningFacade facadeWithAnalysis = new TaskPlanningFacade(
            new TaskPlanner(new TaskStepExecutorRegistry(
                    List.of(fake(StepType.ARTICLE_WRITE), fake(StepType.ARTICLE_ANALYSIS)))));

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

    @SafeVarargs
    private AiIntent intent(String requestKind, Map<String, Object>... goals) {
        AiIntent intent = new AiIntent();
        intent.setRequestKind(requestKind);
        intent.setTaskGoals(List.of(goals));
        return intent;
    }

    private Map<String, Object> goal(String type, String evidence) {
        return goal(type, Map.of("action", "likeArticle"), evidence);
    }

    /** C0.1 起参数按 StepType 校验：非文章写动作的目标不能沿用上面那份"点赞"参数 */
    private Map<String, Object> goal(String type, Map<String, Object> params, String evidence) {
        return Map.of("type", type, "params", params, "goalEvidence", List.of(evidence));
    }

    /** 页面手测真实越界值的形态：action 不在文章交互白名单内 → 规划期即被拒 → 未覆盖 */
    private Map<String, Object> illegalWriteGoal(String evidence) {
        return goal("ARTICLE_WRITE", Map.of("action", "changeTitle"), evidence);
    }

    private static final String MESSAGE = "点赞这篇文章，再跳转到作者主页";

    // ==================== C0.3：未承接的写动作交回旧链重新裁决 ====================

    private AiIntent articleAgentIntent(Map<String, Object>... goals) {
        AiIntent intent = intent("TASK", goals);
        intent.setIntent("ARTICLE_AGENT");
        intent.setSuggestedAction("AGENT");
        return intent;
    }

    @Test
    void unwiredArticleWriteFallsBackWhenOldChainCanReAdjudicate() {
        AiIntent intent = articleAgentIntent(illegalWriteGoal("点赞这篇文章"));

        assertThat(facade.decide(MESSAGE, intent, 1L).route())
                .as("四个窄条件都满足时才交回旧链——它只说明旧链有重新裁决资格，不代表认可该参数")
                .isEqualTo(TaskRoute.Route.LEGACY);
    }

    @Test
    void unwiredArticleWriteGoesCtaWhenIntentIsNotArticleAgent() {
        AiIntent intent = intent("TASK", illegalWriteGoal("点赞这篇文章"));
        intent.setIntent("ARTICLE_DETAIL_QA");   // 不是文章 Agent 链，没有重新裁决资格
        intent.setSuggestedAction("AGENT");

        assertThat(facade.decide(MESSAGE, intent, 1L).route()).isEqualTo(TaskRoute.Route.CTA);
    }

    @Test
    void unwiredArticleWriteGoesCtaWhenSuggestedActionIsNotAgent() {
        AiIntent intent = intent("TASK", illegalWriteGoal("点赞这篇文章"));
        intent.setIntent("ARTICLE_AGENT");
        intent.setSuggestedAction("CHAT");

        assertThat(facade.decide(MESSAGE, intent, 1L).route()).isEqualTo(TaskRoute.Route.CTA);
    }

    @Test
    void unwiredArticleWriteGoesCtaWhenPlanHasOtherSteps() {
        // 非法写动作 + 另一个能承接的步骤：旧单 Agent 会静默丢掉复合目标 → 只能 CTA
        AiIntent intent = articleAgentIntent(illegalWriteGoal("点赞这篇文章"),
                goal("ARTICLE_SEARCH", Map.of("query", "Redis"), "跳转到作者主页"));

        assertThat(facadeWithSearch.decide(MESSAGE, intent, 1L).route()).isEqualTo(TaskRoute.Route.CTA);
    }

    @Test
    void routeReasonNeverContainsRawModelValue() {
        // 入口级断言：模型给的越界值不能出现在**给用户看的文案**里
        // （CTA / LEGACY 的 reason 就是要展示给用户的那句话）
        AiIntent intent = intent("TASK", illegalWriteGoal("点赞这篇文章"));
        intent.setIntent("ARTICLE_DETAIL_QA");
        intent.setSuggestedAction("CHAT");

        TaskRoute route = facade.decide(MESSAGE, intent, 1L);

        assertThat(route.route()).isEqualTo(TaskRoute.Route.CTA);
        assertThat(route.reason()).doesNotContain("changeTitle");
    }

    // ==================== 不是任务 → 普通聊天 ====================

    @Test
    void chatKindRoutesToChat() {
        AiIntent intent = new AiIntent();
        intent.setRequestKind("CHAT");

        assertThat(facade.decide(MESSAGE, intent, 1L).route())
                .isEqualTo(TaskRoute.Route.CHAT);
    }

    @Test
    void taskWithoutGoalsRoutesToChat() {
        assertThat(facade.decide(MESSAGE, intent("TASK"), 1L).route())
                .isEqualTo(TaskRoute.Route.CHAT);
    }

    @Test
    void missingIntentRoutesToChat() {
        assertThat(facade.decide(MESSAGE, null, 1L).route())
                .isEqualTo(TaskRoute.Route.CHAT);
    }

    // ==================== 全部已接入 → 新编排 ====================

    @Test
    void singleGoalAllExecutableOrchestrates() {
        TaskRoute route = facade.decide(MESSAGE,
                intent("TASK", goal("ARTICLE_WRITE", "点赞这篇文章")), 1L);

        assertThat(route.isOrchestrate()).isTrue();
        assertThat(route.plan().steps()).hasSize(1);
    }

    @Test
    void multiGoalAllExecutableOrchestrates() {
        TaskRoute route = facade.decide(MESSAGE,
                intent("TASK",
                        goal("ARTICLE_WRITE", "点赞这篇文章"),
                        goal("ARTICLE_WRITE", "再跳转到作者主页")), 1L);

        assertThat(route.isOrchestrate()).isTrue();
        assertThat(route.plan().steps()).hasSize(2);
    }

    // ==================== 未迁移但旧路径能完整处理 → LEGACY ====================

    @Test
    void pureUnmigratedSingleTaskGoesLegacy() {
        // "分析这篇文章"——单任务未迁移，旧路径就是干这个的
        TaskRoute route = facade.decide(MESSAGE,
                intent("TASK", goal("ARTICLE_ANALYSIS", "点赞这篇文章")), 1L);

        assertThat(route.route()).isEqualTo(TaskRoute.Route.LEGACY);
    }

    @Test
    void actionPlusAnalysisGoesLegacy() {
        // ★ 第二刀专门做的组合——旧复合路径能整体收口，退回去不退步
        TaskRoute route = facade.decide(MESSAGE,
                intent("TASK",
                        goal("ARTICLE_WRITE", "点赞这篇文章"),
                        goal("ARTICLE_ANALYSIS", "再跳转到作者主页")), 1L);

        assertThat(route.route()).isEqualTo(TaskRoute.Route.LEGACY);
    }

    // ==================== 分析诉求没进计划 → 不得 ORCHESTRATE（2026-09-30 实测修复） ====================

    /** 探针实测句（真实 LLM 输出见 TaskGoalsProbeTests，此处逐字照抄不加工） */
    private static final String ANALYSIS_MESSAGE = "点赞这篇文章，再分析一下它写得怎么样";
    private static final String WRITE_SEARCH_MESSAGE = "点赞这篇文章，再帮我找找还有没有关于Redis的文章";

    private Map<String, Object> goalOf(String type, Map<String, Object> params, String evidence) {
        return Map.of("type", type, "params", params, "goalEvidence", List.of(evidence));
    }

    @Test
    void analysisSignalMissingFromPlanGoesLegacy() {
        // ★ 在线探针实测：真实 LLM 对这句话输出
        //   requestKind=TASK + taskGoals=[ARTICLE_WRITE] + analysisGoalEvidence="分析一下它写得怎么样"
        //   ——分析目标根本没进 taskGoals。此时若不拦，路由会判 ORCHESTRATE，
        //     只执行点赞并回"已全部完成"，分析静默消失（第二刀能力回归）。
        AiIntent intent = intent("TASK", goal("ARTICLE_WRITE", "点赞这篇文章"));
        intent.setRequestedActions(List.of("likeArticle"));
        intent.setAnalysisGoalEvidence("分析一下它写得怎么样");

        TaskRoute route = facade.decide(ANALYSIS_MESSAGE, intent, 1L);

        assertThat(route.route()).isEqualTo(TaskRoute.Route.LEGACY);
    }

    @Test
    void analysisSignalWithSearchStepBecomesCta() {
        // 写 + 搜索 + 分析：旧路径只能整体收口「写 + 分析」，三个目标没有路径能完整处理
        AiIntent intent = intent("TASK",
                goal("ARTICLE_WRITE", "点赞这篇文章"),
                goalOf("ARTICLE_SEARCH", Map.of("query", "Redis"),
                        "再帮我找找还有没有关于Redis的文章"));
        intent.setRequestedActions(List.of("likeArticle"));
        intent.setAnalysisGoalEvidence("分析一下它写得怎么样");

        TaskRoute route = facadeWithSearch.decide(WRITE_SEARCH_MESSAGE, intent, 1L);

        assertThat(route.route()).isEqualTo(TaskRoute.Route.CTA);
    }

    @Test
    void analysisPlannedAsStepStillOrchestrates() {
        // 第四步演进规格：分析一旦成为计划里的一个步骤，fail-closed 判据自动退场，走新编排
        AiIntent intent = intent("TASK",
                goal("ARTICLE_WRITE", "点赞这篇文章"),
                goalOf("ARTICLE_ANALYSIS", Map.of(), "再分析一下它写得怎么样"));
        intent.setAnalysisGoalEvidence("分析一下它写得怎么样");

        TaskRoute route = facadeWithAnalysis.decide(ANALYSIS_MESSAGE, intent, 1L);

        assertThat(route.isOrchestrate()).isTrue();
        assertThat(route.plan().steps()).hasSize(2);
    }

    @Test
    void singleWriteWithoutAnalysisSignalStillOrchestrates() {
        // 反例：没有分析信号时，这条判据不得误伤普通单目标请求
        AiIntent intent = intent("TASK", goal("ARTICLE_WRITE", "点赞这篇文章"));
        intent.setRequestedActions(List.of("likeArticle"));

        assertThat(facade.decide("点赞这篇文章", intent, 1L).isOrchestrate()).isTrue();
    }

    // ==================== 多目标含未迁移 → CTA，不执行一半 ====================

    @Test
    void actionPlusUnmigratedWorkflowBecomesCta() {
        // ★ "点赞 + 优化并发布"——退回旧路径只会执行点赞、还声称完成
        TaskRoute route = facade.decide(MESSAGE,
                intent("TASK",
                        goal("ARTICLE_WRITE", "点赞这篇文章"),
                        goal("WORKFLOW_HANDOFF", "再跳转到作者主页")), 1L);

        assertThat(route.route()).isEqualTo(TaskRoute.Route.CTA);
        assertThat(route.reason()).isNotBlank();
    }

    @Test
    void actionPlusUnknownTypeBecomesCta() {
        TaskRoute route = facade.decide(MESSAGE,
                intent("TASK",
                        goal("ARTICLE_WRITE", "点赞这篇文章"),
                        goal("DANCE_AROUND", "再跳转到作者主页")), 1L);

        assertThat(route.route()).isEqualTo(TaskRoute.Route.CTA);
    }

    @Test
    void multipleWritesPlusAnalysisIsStillLegacyCapability() {
        // 两个写动作 + 分析：第二刀的复合路径本来就把 requestedActions 当**数组**处理，
        // 能整体收口——所以退回不退步，可以退
        TaskRoute route = facade.decide(MESSAGE,
                intent("TASK",
                        goal("ARTICLE_WRITE", "点赞这篇文章"),
                        goal("ARTICLE_WRITE", "再跳转到作者主页"),
                        goal("ARTICLE_ANALYSIS", "点赞这篇文章")), 1L);

        assertThat(route.route()).isEqualTo(TaskRoute.Route.LEGACY);
    }

    // ==================== 证据 ====================

    @Test
    void evidenceNotInMessageBecomesCta() {
        TaskRoute route = facade.decide(MESSAGE,
                intent("TASK", goal("ARTICLE_WRITE", "帮我收藏这篇文章")), 1L);

        assertThat(route.route()).isEqualTo(TaskRoute.Route.CTA);
    }
}
