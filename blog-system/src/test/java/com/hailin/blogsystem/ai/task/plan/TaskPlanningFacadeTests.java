package com.hailin.blogsystem.ai.task.plan;

import com.hailin.blogsystem.ai.task.StepResult;
import com.hailin.blogsystem.ai.task.StepType;
import com.hailin.blogsystem.ai.task.TaskExecutionContext;
import com.hailin.blogsystem.ai.task.TaskStep;
import com.hailin.blogsystem.ai.task.TaskStepExecutor;
import com.hailin.blogsystem.ai.task.TaskStepExecutorRegistry;
import com.hailin.blogsystem.entity.dto.AiIntent;
import com.hailin.blogsystem.entity.dto.AiWorkflowType;
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

    /** 又登记了导航执行器的门面：用来构造「Workflow + 导航」这类复合请求 */
    private final TaskPlanningFacade facadeWithNavigation = new TaskPlanningFacade(
            new TaskPlanner(new TaskStepExecutorRegistry(
                    List.of(fake(StepType.ARTICLE_WRITE), fake(StepType.NAVIGATE)))));

    /**
     * 5b：**注册了 handoff 执行器之后**的门面。
     *
     * <p>此时 {@code registry.supports(WORKFLOW_HANDOFF)} 转真，单一 Workflow 请求不再是
     * "未覆盖目标"，而是变成一条正常计划——所以"它还能不能留在旧链"必须被单独锁住。
     */
    private final TaskPlanningFacade facadeWithHandoff = new TaskPlanningFacade(
            new TaskPlanner(new TaskStepExecutorRegistry(
                    List.of(fake(StepType.ARTICLE_WRITE), fake(StepType.WORKFLOW_HANDOFF)))));

    private final TaskPlanningFacade facadeWithSearchAndHandoff = new TaskPlanningFacade(
            new TaskPlanner(new TaskStepExecutorRegistry(List.of(
                    fake(StepType.ARTICLE_WRITE),
                    fake(StepType.ARTICLE_SEARCH),
                    fake(StepType.WORKFLOW_HANDOFF)))));

    private final TaskPlanningFacade facadeWithFullHandoff = new TaskPlanningFacade(
            new TaskPlanner(new TaskStepExecutorRegistry(List.of(
                    fake(StepType.ARTICLE_WRITE),
                    fake(StepType.ARTICLE_SEARCH),
                    fake(StepType.NAVIGATE),
                    fake(StepType.ARTICLE_ANALYSIS),
                    fake(StepType.WORKFLOW_HANDOFF)))));

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
        /*
         * 默认主意图给文章域：2026-10-18 起 Facade 会先做**域仲裁**——
         * 非文章域意图（学习 / 钱包 / 泛闲聊）即使带着可执行候选也不进编排。
         * 这些用例测的是计划本身的判据，所以默认置于可编排域，避免被仲裁误拦；
         * 域仲裁本身由专门的用例覆盖。
         */
        intent.setIntent("ARTICLE_ACTION");
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

    // ==================== 5a：单个已知 Workflow 目标交回旧链 ====================

    /** 5a 最小协议形态：只有 type + params.workflowType + goalEvidence */
    private Map<String, Object> workflowGoal(String workflowType) {
        return Map.of("type", "WORKFLOW_HANDOFF",
                "params", Map.of("workflowType", workflowType),
                "goalEvidence", List.of("点赞这篇文章"));
    }

    @Test
    void singleKnownWorkflowFallsBackToLegacy() {
        // 不回归的关键：分类器开始输出 WORKFLOW_HANDOFF 后，
        // 「帮我优化这篇文章」这类请求必须仍能启动，而不是被拦成 CTA
        AiIntent intent = intent("TASK", workflowGoal("OPTIMIZE_ARTICLE"));
        intent.setSuggestedAction("WORKFLOW");
        intent.setSuggestedWorkflowType("OPTIMIZE_ARTICLE");

        assertThat(facade.decide(MESSAGE, intent, 1L).route()).isEqualTo(TaskRoute.Route.LEGACY);
    }

    @Test
    void legacyFieldsMissingGoesCta() {
        // 旧字段残缺 → 旧链的 isWorkflowSuggestion / hasWorkflowSuggestion 会直接降级 CTA，
        // 那 LEGACY 就是**假路由**：Facade 说"交给旧链"，旧链说"做不了"，用户看到的还是失败
        AiIntent withoutAction = intent("TASK", workflowGoal("OPTIMIZE_ARTICLE"));
        withoutAction.setSuggestedWorkflowType("OPTIMIZE_ARTICLE");
        assertThat(facade.decide(MESSAGE, withoutAction, 1L).route())
                .as("缺 suggestedAction=WORKFLOW")
                .isEqualTo(TaskRoute.Route.CTA);

        AiIntent withoutType = intent("TASK", workflowGoal("OPTIMIZE_ARTICLE"));
        withoutType.setSuggestedAction("WORKFLOW");
        assertThat(facade.decide(MESSAGE, withoutType, 1L).route())
                .as("缺 suggestedWorkflowType")
                .isEqualTo(TaskRoute.Route.CTA);
    }

    @Test
    void everyRegisteredWorkflowTypeIsAccepted() {
        // 这里锁的是「旧链兼容契约」而不是 enum.valueOf 本身：
        // 将来新增第 6 个 Workflow 却忘了同步旧路由，显式 switch 应先让这条测试变红。
        for (AiWorkflowType type : AiWorkflowType.values()) {
            AiIntent intent = intent("TASK", workflowGoal(type.name()));
            intent.setSuggestedAction("WORKFLOW");
            intent.setSuggestedWorkflowType(type.name());

            assertThat(facade.decide(MESSAGE, intent, 1L).route())
                    .as("已登记类型 %s 必须能交回旧链", type)
                    .isEqualTo(TaskRoute.Route.LEGACY);
        }
    }

    // ==================== 5b：单一 handoff 留在旧链，复合才进新编排 ====================

    @Test
    void singleHandoffStaysOnLegacyEvenAfterExecutorRegistered() {
        // 不回归的关键：注册 handoff 执行器之后，「帮我优化这篇文章」会变成
        // "有计划步骤、没有未覆盖目标"的请求。若不显式拦住，它会顺着通用判据走进新编排器，
        // 而设计稿 §3.1 要的是：5b 第一版**只接「Workflow + 其他目标」的复合请求**，
        // 单独那一条继续走已验证的旧 Workflow 链。
        AiIntent intent = intent("TASK", workflowGoal("OPTIMIZE_ARTICLE"));
        intent.setSuggestedAction("WORKFLOW");
        intent.setSuggestedWorkflowType("OPTIMIZE_ARTICLE");

        assertThat(facadeWithHandoff.decide(MESSAGE, intent, 1L).route())
                .as("单一 handoff：即使已有执行器，也要留在旧链")
                .isEqualTo(TaskRoute.Route.LEGACY);
    }

    @Test
    void handoffPlusWriteGoesToNewOrchestrator() {
        // 复合请求才是 5b 要接的：旧链只会执行一半（点赞照做、优化丢掉），必须进新编排器
        AiIntent intent = intent("TASK",
                goal("ARTICLE_WRITE", "点赞这篇文章"),
                workflowGoal("OPTIMIZE_ARTICLE"));
        intent.setSuggestedAction("WORKFLOW");
        intent.setSuggestedWorkflowType("OPTIMIZE_ARTICLE");

        TaskRoute route = facadeWithHandoff.decide(MESSAGE, intent, 1L);
        assertThat(route.route())
                .as("handoff + 其他目标：必须进新编排器")
                .isEqualTo(TaskRoute.Route.ORCHESTRATE);
        assertThat(route.plan().steps()).hasSize(2);
    }

    @Test
    void analysisAfterHandoffFailsClosedEvenWhenBothExecutorsExist() {
        String message = "优化这篇文章，再分析一下它写得怎么样";
        AiIntent intent = intent("TASK",
                goal("WORKFLOW_HANDOFF", Map.of("workflowType", "OPTIMIZE_ARTICLE"), "优化这篇文章"),
                goal("ARTICLE_ANALYSIS", Map.of(), "分析一下它写得怎么样"));
        intent.setSuggestedAction("WORKFLOW");
        intent.setSuggestedWorkflowType("OPTIMIZE_ARTICLE");

        assertThat(facadeWithFullHandoff.decide(message, intent, 1L).route())
                .isEqualTo(TaskRoute.Route.CTA);
    }

    @Test
    void legacyWorkflowSignalMissingFromExecutablePlanFailsClosed() {
        AiIntent intent = intent("TASK", goal("ARTICLE_WRITE", "点赞这篇文章"));
        intent.setSuggestedAction("WORKFLOW");
        intent.setSuggestedWorkflowType("CREATE_ARTICLE");

        assertThat(facade.decide("点赞这篇文章，再写一篇新文章", intent, 1L).route())
                .as("旧字段声明还有 Workflow，但计划没承接时不能只执行点赞")
                .isEqualTo(TaskRoute.Route.CTA);
    }

    @Test
    void unknownWorkflowTypeGoesCta() {
        // 其余条件都给全，确保它挂在"类型不认识"这一条上，而不是挂在"字段缺失"上
        AiIntent intent = intent("TASK", workflowGoal("UNKNOWN_WORKFLOW"));
        intent.setSuggestedAction("WORKFLOW");
        intent.setSuggestedWorkflowType("UNKNOWN_WORKFLOW");

        assertThat(facade.decide(MESSAGE, intent, 1L).route())
                .as("未知 Workflow 不能进旧链")
                .isEqualTo(TaskRoute.Route.CTA);
    }

    @Test
    void conflictingWorkflowFieldsGoCta() {
        // 脏数据形态：taskGoals 说 CREATE_ARTICLE，顶层旧字段说 OPTIMIZE_ARTICLE。
        // 不能择一猜测——挑错了就是执行了用户没要的 Workflow
        AiIntent intent = intent("TASK", workflowGoal("CREATE_ARTICLE"));
        intent.setSuggestedAction("WORKFLOW");
        intent.setSuggestedWorkflowType("OPTIMIZE_ARTICLE");

        assertThat(facade.decide(MESSAGE, intent, 1L).route()).isEqualTo(TaskRoute.Route.CTA);
    }

    @Test
    void missingWorkflowTypeGoesCta() {
        AiIntent intent = intent("TASK",
                Map.of("type", "WORKFLOW_HANDOFF", "params", Map.of(),
                        "goalEvidence", List.of("点赞这篇文章")));
        intent.setSuggestedAction("WORKFLOW");
        intent.setSuggestedWorkflowType("OPTIMIZE_ARTICLE");

        assertThat(facade.decide(MESSAGE, intent, 1L).route())
                .as("workflowType 缺失 → 没有可裁决的类型")
                .isEqualTo(TaskRoute.Route.CTA);
    }

    @Test
    void workflowParamsWithExtraKeysGoCta() {
        AiIntent intent = intent("TASK",
                Map.of("type", "WORKFLOW_HANDOFF",
                        "params", Map.of("workflowType", "OPTIMIZE_ARTICLE", "articleId", "12"),
                        "goalEvidence", List.of("点赞这篇文章")));
        intent.setSuggestedAction("WORKFLOW");
        intent.setSuggestedWorkflowType("OPTIMIZE_ARTICLE");

        assertThat(facade.decide(MESSAGE, intent, 1L).route())
                .as("5a 最小契约只允许 workflowType，额外业务参数不能进入旧链")
                .isEqualTo(TaskRoute.Route.CTA);
    }

    @Test
    void workflowPlusWriteGoesCta() {
        // 「点赞 + 优化」：旧链只会执行点赞还声称完成 → 不能执行半边
        AiIntent intent = intent("TASK", goal("ARTICLE_WRITE", "点赞这篇文章"),
                workflowGoal("OPTIMIZE_ARTICLE"));
        intent.setSuggestedAction("WORKFLOW");
        intent.setSuggestedWorkflowType("OPTIMIZE_ARTICLE");

        assertThat(facade.decide(MESSAGE, intent, 1L).route()).isEqualTo(TaskRoute.Route.CTA);
    }

    @Test
    void workflowPlusNavigateGoesCta() {
        // 「优化后跳转作者主页」：等 5b 的执行器，现在不猜。
        // 旧字段同样给全——否则测的是"字段缺失"而不是"多目标"
        AiIntent intent = intent("TASK", workflowGoal("OPTIMIZE_ARTICLE"),
                goal("NAVIGATE", Map.of("target", "userProfile"), "跳转到作者主页"));
        intent.setSuggestedAction("WORKFLOW");
        intent.setSuggestedWorkflowType("OPTIMIZE_ARTICLE");

        assertThat(facadeWithNavigation.decide(MESSAGE, intent, 1L).route())
                .isEqualTo(TaskRoute.Route.CTA);
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

    @Test
    void explicitSearchComparisonMissingFromPlanBecomesCta() {
        String message = "点赞并收藏这篇文章，再帮我优化一下，再搜一下 Redis 相关文章，并比较各篇文章";
        AiIntent intent = intent("TASK",
                goalOf("ARTICLE_WRITE", Map.of("action", "likeArticle"), "点赞"),
                goalOf("ARTICLE_WRITE", Map.of("action", "favoriteArticle"), "收藏这篇文章"),
                goalOf("WORKFLOW_HANDOFF", Map.of("workflowType", "OPTIMIZE_ARTICLE"), "优化一下"),
                goalOf("ARTICLE_SEARCH", Map.of("query", "Redis"), "Redis 相关文章"));

        TaskRoute route = facadeWithSearchAndHandoff.decide(message, intent, 1L);

        assertThat(route.route())
                .as("明确要求搜索后比较时，不能只执行前四步并静默漏掉比较")
                .isEqualTo(TaskRoute.Route.CTA);
    }

    @Test
    void standaloneArticleSearchUsesLegacyRagPipeline() {
        String message = "有没有关于 Redis 的文章？";
        AiIntent intent = intent("TASK",
                goalOf("ARTICLE_SEARCH", Map.of("query", "Redis"), "有没有关于 Redis 的文章"));
        intent.setIntent("ARTICLE_SEARCH");

        assertThat(facadeWithSearch.decide(message, intent, 1L).route())
                .isEqualTo(TaskRoute.Route.LEGACY);
    }

    // ==================== 域仲裁：非文章域主意图不得进入编排（2026-10-18 生产事故） ====================

    /**
     * 事故原句（2026-10-18 日志 09:48）：分类器同时给出
     * {@code intent=LEARNING_PLAN_QUERY + suggestedAction=TOOL}（正确）和
     * {@code taskGoals=[ARTICLE_SEARCH(query=Redis学习计划)]}（多余）。
     *
     * <p>没有域仲裁时：单步 ARTICLE_SEARCH 有执行器 → ORCHESTRATE 抢在 Planner 的
     * dashboard TOOL 之前执行 → 用户看到"已全部完成：没有找到与「Redis学习计划」相关的文章"。
     * 现在必须先按主意图拦下，把执行权交回既有链路。
     */
    @Test
    void readOnlyLearningQueryWithArticleSearchGoalDoesNotOrchestrate() {
        String message = "帮我看看我的Redis学习计划";
        AiIntent intent = intent("TASK",
                goalOf("ARTICLE_SEARCH", Map.of("query", "Redis学习计划"), message));
        intent.setIntent("LEARNING_PLAN_QUERY");
        intent.setSuggestedAction("TOOL");

        TaskRoute route = facadeWithSearch.decide(message, intent, 1L);

        assertThat(route.isOrchestrate()).isFalse();
        assertThat(route.route()).isEqualTo(TaskRoute.Route.LEGACY);
        assertThat(route.reason()).contains("可编排域");
    }

    /** 多步版本同理：只读意图 + 文章目标（哪怕全都有执行器）也不能占掉主意图。 */
    @Test
    void readOnlyLearningQueryWithMultipleArticleGoalsDoesNotOrchestrate() {
        AiIntent intent = intent("TASK",
                goalOf("ARTICLE_SEARCH", Map.of("query", "Redis"), "看看Redis的学习计划"),
                goalOf("ARTICLE_ANALYSIS", Map.of(), "看看Redis的学习计划"));
        intent.setIntent("LEARNING_PLAN_QUERY");
        intent.setSuggestedAction("TOOL");

        assertThat(facadeWithAnalysis.decide("看看Redis的学习计划", intent, 1L).route())
                .isEqualTo(TaskRoute.Route.LEGACY);
    }

    /** 学习类 Workflow 意图同样不是编排域：主意图该启动工作流，而不是被文章步骤抢走。 */
    @Test
    void learningWorkflowIntentWithArticleGoalDoesNotOrchestrate() {
        AiIntent intent = intent("TASK",
                goalOf("ARTICLE_SEARCH", Map.of("query", "Redis"), "帮我制定Redis学习计划"));
        intent.setIntent("LEARNING_PLAN");
        intent.setSuggestedAction("WORKFLOW");
        intent.setSuggestedWorkflowType("LEARNING_PLAN");

        assertThat(facadeWithSearch.decide("帮我制定Redis学习计划", intent, 1L).route())
                .isEqualTo(TaskRoute.Route.LEGACY);
    }

    /** 钱包域同理：没有执行器的域不该被文章候选"拖进"编排。 */
    @Test
    void walletAgentIntentWithArticleGoalDoesNotOrchestrate() {
        AiIntent intent = intent("TASK",
                goalOf("ARTICLE_SEARCH", Map.of("query", "Redis"), "查完余额再搜 Redis 文章"));
        intent.setIntent("WALLET_AGENT");

        assertThat(facadeWithSearch.decide("查完余额再搜 Redis 文章", intent, 1L).route())
                .isEqualTo(TaskRoute.Route.LEGACY);
    }

    /** 缺主意图 = 没有"计划归谁"的依据 → fail-closed，不能让模型候选覆盖旧链决策。 */
    @Test
    void missingIntentWithExecutablePlanDoesNotOrchestrate() {
        AiIntent intent = intent("TASK", goal("ARTICLE_WRITE", "点赞这篇文章"));
        intent.setIntent(null);

        assertThat(facadeWithSearch.decide(MESSAGE, intent, 1L).route())
                .isEqualTo(TaskRoute.Route.LEGACY);
    }

    /** 反例：文章域主意图不得被域仲裁误伤（域判据只拦非文章域）。 */
    @Test
    void articleDomainIntentStillOrchestrates() {
        AiIntent intent = intent("TASK", goal("ARTICLE_WRITE", "点赞这篇文章"));
        intent.setIntent("ARTICLE_ACTION");

        assertThat(facadeWithSearch.decide(MESSAGE, intent, 1L).isOrchestrate()).isTrue();
    }

    @Test
    void articleWritePlusSearchStillOrchestrates() {
        AiIntent intent = intent("TASK",
                goalOf("ARTICLE_WRITE", Map.of("action", "likeArticle"), "点赞这篇文章"),
                goalOf("ARTICLE_SEARCH", Map.of("query", "Redis"), "找找 Redis 文章"));
        intent.setIntent("ARTICLE_ACTION");
        intent.setRequestedActions(List.of("likeArticle"));

        TaskRoute route = facadeWithSearch.decide("点赞这篇文章，再找找 Redis 文章", intent, 1L);

        assertThat(route.isOrchestrate()).isTrue();
        assertThat(route.plan().steps()).extracting(step -> step.type())
                .containsExactly(StepType.ARTICLE_WRITE, StepType.ARTICLE_SEARCH);
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
    void uncoveredAfterHandoffCtaExplainsInUserLanguage() {
        // 2026-10-23 线上原话：点赞 + 收藏 + 优化 + 搜索 + 比较。
        // 比较（ARTICLE_ANALYSIS）落在 handoff 之后，5b 第一版按未覆盖处理；
        // CTA 不能再说「有一部分我暂时做不到」，要按真实步骤生成人话。
        String message = "点赞并收藏这篇文章，再帮我优化一下，再搜一下 RAG相关文章，并比较各篇文章";
        AiIntent intent = intent("TASK",
                goal("ARTICLE_WRITE", Map.of("action", "likeArticle"), "点赞并收藏这篇文章"),
                goal("ARTICLE_WRITE", Map.of("action", "favoriteArticle"), "点赞并收藏这篇文章"),
                handoffGoal("OPTIMIZE_ARTICLE", "再帮我优化一下"),
                goal("ARTICLE_SEARCH", Map.of("query", "RAG"), "再搜一下 RAG相关文章"),
                goal("ARTICLE_ANALYSIS", Map.of(), "并比较各篇文章"));
        intent.setIntent("OPTIMIZE_ARTICLE_WORKFLOW");
        intent.setSuggestedAction("WORKFLOW");
        intent.setSuggestedWorkflowType("OPTIMIZE_ARTICLE");

        TaskRoute route = facadeWithFullHandoff.decide(message, intent, 1L);

        assertThat(route.route()).isEqualTo(TaskRoute.Route.CTA);
        assertThat(route.reason())
                .contains("点赞", "收藏", "优化文章", "搜索文章", "分析文章", "拆成两句")
                .doesNotContain("ARTICLE_ANALYSIS", "WORKFLOW_HANDOFF", "先不动手——能拆开");
    }

    @Test
    void uncoveredCtaAdaptsToAnotherWorkflow() {
        // 5b 白名单当前只开放 OPTIMIZE_ARTICLE；换「创建文章」时它落在未覆盖里，
        // 文案也要说人话（从 suggestedWorkflowType 取名），不能只显示"需要确认的流程"
        String message = "点赞这篇文章，再帮我创建一篇新文章，再比较各篇文章";
        AiIntent intent = intent("TASK",
                goal("ARTICLE_WRITE", Map.of("action", "likeArticle"), "点赞这篇文章"),
                handoffGoal("CREATE_ARTICLE", "再帮我创建一篇新文章"),
                goal("ARTICLE_ANALYSIS", Map.of(), "再比较各篇文章"));
        intent.setIntent("CREATE_ARTICLE_WORKFLOW");
        intent.setSuggestedAction("WORKFLOW");
        intent.setSuggestedWorkflowType("CREATE_ARTICLE");

        TaskRoute route = facadeWithFullHandoff.decide(message, intent, 1L);

        assertThat(route.route()).isEqualTo(TaskRoute.Route.CTA);
        assertThat(route.reason())
                .contains("创建文章", "分析文章")
                .doesNotContain("优化文章", "WORKFLOW_HANDOFF", "需要确认的流程");
    }

    /** handoff 目标的证据必须取自用户原话，所以由调用方传入，避免复用固定文案。 */
    private Map<String, Object> handoffGoal(String workflowType, String evidence) {
        return Map.of("type", "WORKFLOW_HANDOFF",
                "params", Map.of("workflowType", workflowType),
                "goalEvidence", List.of(evidence));
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

    // ==================== 声明的动作未被计划承接（2026-10-07） ====================

    /**
     * 页面实测原话（五个目标，四个是文章交互动作 + 一段分析）。
     * 实测失败那次 plan 只有三步，漏掉的 {@code followAuthor} 与 {@code NAVIGATE} 都落在后半段。
     */
    private static final String FIVE_GOAL_MESSAGE =
            "点赞收藏文章，并分析一下文章，分析完文章之后再关注作者";

    @Test
    void declaredActionMissingFromPlanGoesCta() {
        AiIntent intent = intent("TASK",
                goal("ARTICLE_WRITE", Map.of("action", "likeArticle"), "点赞"),
                goal("ARTICLE_WRITE", Map.of("action", "favoriteArticle"), "收藏文章"),
                goal("ARTICLE_ANALYSIS", Map.<String, Object>of(), "分析一下文章"));
        // 分类器声明了三个动作，但 taskGoals 只拆出前两个——实测漏的正是最后那个
        intent.setRequestedActions(List.of("likeArticle", "favoriteArticle", "followAuthor"));

        assertThat(facadeWithAnalysis.decide(FIVE_GOAL_MESSAGE, intent, 1L).route())
                .as("followAuthor 被声明却没进计划；计划含分析（不是纯写）→ 没有任何路径能完整处理")
                .isEqualTo(TaskRoute.Route.CTA);
    }

    /** 全是写动作时退回第二刀复合路径——它按数组处理 requestedActions，能整体收口。 */
    @Test
    void declaredActionMissingFromAllWritePlanFallsBackToLegacy() {
        AiIntent intent = intent("TASK",
                goal("ARTICLE_WRITE", Map.of("action", "likeArticle"), "点赞"),
                goal("ARTICLE_WRITE", Map.of("action", "favoriteArticle"), "收藏文章"));
        intent.setRequestedActions(List.of("likeArticle", "favoriteArticle", "followAuthor"));

        assertThat(facade.decide(FIVE_GOAL_MESSAGE, intent, 1L).route())
                .as("退回不退步：兼容链路本来就能处理这个组合")
                .isEqualTo(TaskRoute.Route.LEGACY);
    }

    /**
     * 客户端动作不进计划是**正常**的——{@code requestedActions} 的取值域比
     * {@code ARTICLE_WRITE} 能承接的宽。拿全量比对会把每个带"复制链接 / 看评论"的
     * 复合请求都判成"漏目标"，于是正常请求全被拦下。
     */
    @Test
    void clientSideActionsDoNotTripTheGuard() {
        AiIntent intent = intent("TASK",
                goal("ARTICLE_WRITE", Map.of("action", "likeArticle"), "点赞"));
        intent.setRequestedActions(List.of("likeArticle", "copyArticleLink", "scrollToComments"));

        assertThat(facade.decide(FIVE_GOAL_MESSAGE, intent, 1L).route())
                .as("只有 ArticleInteractionAction 白名单内的六项参与比对")
                .isEqualTo(TaskRoute.Route.ORCHESTRATE);
    }

    @Test
    void fullyPlannedActionsDoNotTripTheGuard() {
        AiIntent intent = intent("TASK",
                goal("ARTICLE_WRITE", Map.of("action", "likeArticle"), "点赞"),
                goal("ARTICLE_WRITE", Map.of("action", "followAuthor"), "关注作者"));
        intent.setRequestedActions(List.of("likeArticle", "followAuthor"));

        assertThat(facade.decide(FIVE_GOAL_MESSAGE, intent, 1L).route())
                .isEqualTo(TaskRoute.Route.ORCHESTRATE);
    }

    /** 旧协议下分类器不给 requestedActions——判据必须退场，不能把所有请求都拦下。 */
    @Test
    void missingRequestedActionsDoesNotTripTheGuard() {
        AiIntent intent = intent("TASK",
                goal("ARTICLE_WRITE", Map.of("action", "likeArticle"), "点赞"));

        assertThat(facade.decide(FIVE_GOAL_MESSAGE, intent, 1L).route())
                .isEqualTo(TaskRoute.Route.ORCHESTRATE);
    }

    // ==================== 证据 ====================

    @Test
    void evidenceNotInMessageBecomesCta() {
        TaskRoute route = facade.decide(MESSAGE,
                intent("TASK", goal("ARTICLE_WRITE", "帮我收藏这篇文章")), 1L);

        assertThat(route.route()).isEqualTo(TaskRoute.Route.CTA);
    }
}
