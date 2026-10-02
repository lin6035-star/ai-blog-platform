package com.hailin.blogsystem;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.entity.LearningPlans;
import com.hailin.blogsystem.entity.dto.AiIntent;
import com.hailin.blogsystem.entity.dto.AiWorkflowType;
import com.hailin.blogsystem.entity.dto.PageContextDTO;
import com.hailin.blogsystem.mapper.LearningPlanMapper;
import com.hailin.blogsystem.service.AiIntentClassifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("integration")
@Tag("eval")
@SpringBootTest
@EnabledIfSystemProperty(named = "runRealLlm", matches = "true")
class AiIntentClassifierIntegrationTests {

    @Autowired
    private AiIntentClassifier aiIntentClassifier;

    /** mock 掉真 ES vectorStore（评测门只测分类器 prompt，不依赖本地 ES 可用性） */
    @MockBean
    private VectorStore vectorStore;

    /** 别名选中用例的计划 fixture 用户：避开其他测试类占用的 101/102/103/106/107 */
    private static final Long PLAN_TEST_USER = 109L;

    @Autowired
    private LearningPlanMapper learningPlanMapper;

    // ------------------------------------------------------------------
    // 评测集：JSON 文件驱动（classifier-evaluation-cases.json）
    // 加样例 = 加 JSON 行；-DevalCase=子串 可只跑名称匹配的样例（省 LLM 调用）
    // ------------------------------------------------------------------

    /**
     * 一条分类器评测样例。字段全部可空，非 null 才断言；
     * 显式 false 也能断言（expectedNeedsThinking），不能靠 null 跳过。
     */
    record ClassifierEvalCase(
            String name,
            String category,
            String message,
            String pageType,
            String pageArticleId,
            String expectedIntent,
            String expectedPlanRefContains,
            String expectedStageRefContains,
            Boolean expectedPlanRefNonBlank,
            Double expectedConfidenceMin,
            Boolean expectedNeedsThinking,
            Boolean expectedNeedsThinkingReasonNonBlank,
            String expectedSuggestedAction,
            // 复合目标（动作 + 分析）：这两个字段是本次改动最核心的提取质量指标。
            // record 必须先扩——否则 Jackson 会把 JSON 里的新字段当未知属性直接炸。
            List<String> expectedRequestedActionsContains,
            String expectedAnalysisGoalEvidenceContains,
            // 反例专用：断言 analysisGoalEvidence 必须为空 = 没有被误判成复合目标。
            // 反例的 expectedIntent 不写死（同一句在 QA / AGENT 之间本就有合理漂移），
            // 真正要锁的是"不能把它当成动作 + 分析"。
            Boolean expectedAnalysisGoalEvidenceBlank,
            // 第三刀补（2026-09-30）：taskGoals / requestKind 的回归维度。
            // 之前这两个字段**完全没有断言**——于是"模型只拆出一个目标"这种多目标静默丢失
            // 在离线门与在线门之间穿了过去（实测见 TaskGoalsProbeTests）。
            String expectedRequestKind,
            List<String> expectedTaskGoalTypesContains,
            Integer expectedTaskGoalCountAtLeast,
            // 「页面缺文章上下文」反例：断言 articleId 必须为空——ID 只能由后端解析，模型不许硬编
            Boolean expectedArticleIdBlank,
            // 5a（2026-10-01）：WORKFLOW_HANDOFF 契约——**旧字段与新字段都要断言**。
            // 只测 taskGoals 会漏掉"新协议把旧字段挤没了"，而旧字段才是真正启动 Workflow 的东西。
            String expectedSuggestedWorkflowType,
            // 多 Workflow 目标时旧单值字段必须为空，不能强行挑一个造成语义冲突。
            Boolean expectedSuggestedWorkflowTypeBlank,
            List<String> expectedTaskGoalWorkflowTypesContains,
            // 反例专用：断言 taskGoals 里**不能**出现这些类型
            // （「讨论一下 Workflow 是什么」绝不能被拆成任务目标）
            List<String> expectedTaskGoalTypesNotContains,
            // 样例可以先落库、后改 prompt：未就绪的显式跳过，
            // 而不是等 prompt 改完才想起来补（那样评测集永远滞后于实现）
            Boolean pendingUntilPromptReady
    ) {
    }

    record ActionAnalysisRecallCase(
            String name,
            String message,
            String pageType,
            String pageArticleId,
            List<String> expectedRequestedActionsContains
    ) {
    }

    static Stream<Arguments> evalCases() throws Exception {
        InputStream inputStream = AiIntentClassifierIntegrationTests.class
                .getResourceAsStream("/agent/classifier-evaluation-cases.json");

        assertThat(inputStream)
                .as("分类器评测集文件必须存在")
                .isNotNull();

        List<ClassifierEvalCase> cases = new ObjectMapper().readValue(
                Objects.requireNonNull(inputStream),
                new TypeReference<>() {
                }
        );

        assertThat(cases)
                .as("分类器评测集不能为空")
                .isNotEmpty();

        // 每例必须带 name/message，否则无法定位失败
        for (ClassifierEvalCase c : cases) {
            assertThat(c.name())
                    .as("样例缺少 name")
                    .isNotBlank();
            assertThat(c.message())
                    .as("样例 %s 缺少 message", c.name())
                    .isNotBlank();
        }

        String evalCaseFilter = System.getProperty("evalCase");
        if (evalCaseFilter != null && !evalCaseFilter.isBlank()) {
            cases = cases.stream()
                    .filter(c -> c.name().contains(evalCaseFilter))
                    .toList();
            assertThat(cases)
                    .as("-DevalCase=%s 无匹配样例", evalCaseFilter)
                    .isNotEmpty();
        }

        return cases.stream().map(c -> Arguments.of(c.name(), c));
    }

    // ------------------------------------------------------------------
    // V4.x：分类器拿到「用户真实计划列表」之后的别名选中能力
    // 与固定样例集分开：这两条依赖库内 fixture（两个易混标题），锁的是「换个说法对不对得上」与「没点名不许猜」
    // ------------------------------------------------------------------

    @Test
    void picksPlanFromInjectedListByAlias() {
        assumeNoSingleCaseFilter();
        // 旧行为：learningPlanRef="c++学习计划" 被分词切成 "c"，与「C语言系统学习计划」同分并列 → 弹卡让用户重选
        insertActivePlan("C语言系统学习计划");
        LearningPlans cppPlan = insertActivePlan("C++ 系统学习与工程化实践计划");

        AiIntent intent = aiIntentClassifier.classify(
                "帮我把c++的第一阶段浓缩成四个小任务", null, PLAN_TEST_USER, null);

        assertThat(intent.getLearningPlanId())
                .as("用户说了 c++，应选中《C++ 系统学习与工程化实践计划》，而不是《C语言系统学习计划》")
                .isEqualTo(String.valueOf(cppPlan.getId()));
    }

    @Test
    void doesNotGuessWhenNoPlanMentioned() {
        assumeNoSingleCaseFilter();
        insertActivePlan("C语言系统学习计划");
        insertActivePlan("C++ 系统学习与工程化实践计划");

        AiIntent intent = aiIntentClassifier.classify(
                "帮我把第一阶段浓缩成四个小任务", null, PLAN_TEST_USER, null);

        assertThat(intent.getLearningPlanId())
                .as("用户没点名计划、多候选下不得猜——宁可留给后端追问")
                .isNull();
    }

    private LearningPlans insertActivePlan(String title) {
        LearningPlans plan = new LearningPlans();
        plan.setUserId(PLAN_TEST_USER);
        plan.setTitle(title);
        plan.setStatus(LearningPlans.STATUS_ACTIVE);
        plan.setCreatedAt(LocalDateTime.now());
        plan.setUpdatedAt(LocalDateTime.now());
        learningPlanMapper.insert(plan);
        return plan;
    }

    @AfterEach
    void cleanupPlanFixture() {
        learningPlanMapper.delete(new LambdaQueryWrapper<LearningPlans>()
                .eq(LearningPlans::getUserId, PLAN_TEST_USER));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("evalCases")
    void classifierMatchesFixedEvalCase(String caseName, ClassifierEvalCase c) {
        // 5a：prompt 未就绪的样例先落库、显式跳过——别等改完 prompt 才补评测集
        assumeFalse(Boolean.TRUE.equals(c.pendingUntilPromptReady()),
                "5a 样例待分类器 prompt 就绪（先搭骨架，不动 prompt）");
        // 样例可带页面上下文（V3.4 起：article-detail 分界依赖 pageType/articleId）
        PageContextDTO pageContext = null;
        if (c.pageType() != null) {
            pageContext = new PageContextDTO();
            pageContext.setPageType(c.pageType());
            pageContext.setArticleId(c.pageArticleId());
        }
        // userId 传 null：固定样例集锁的是「分类器只看原话能理解到什么」（原有 22 例语义不变）；
        // 注入真实计划列表后的「别名选中」能力由 picksPlanFromInjectedListByAlias 单独验证
        AiIntent intent = aiIntentClassifier.classify(c.message(), pageContext, null, null);

        assertClassifierCallSucceeded(caseName, intent);

        if (c.expectedIntent() != null) {
            assertThat(intent.getIntent())
                    .as("用例 %s：intent", caseName)
                    .isEqualTo(c.expectedIntent());
        }
        if (c.expectedPlanRefContains() != null) {
            assertThat(intent.getLearningPlanRef())
                    .as("用例 %s：learningPlanRef 应包含", caseName)
                    .contains(c.expectedPlanRefContains());
        }
        if (c.expectedStageRefContains() != null) {
            assertThat(intent.getLearningStageRef())
                    .as("用例 %s：learningStageRef 应包含", caseName)
                    .contains(c.expectedStageRefContains());
        }
        if (Boolean.TRUE.equals(c.expectedPlanRefNonBlank())) {
            assertThat(intent.getLearningPlanRef())
                    .as("用例 %s：learningPlanRef 不应为空", caseName)
                    .isNotBlank();
        }
        if (c.expectedConfidenceMin() != null) {
            assertThat(intent.getConfidence())
                    .as("用例 %s：confidence", caseName)
                    .isGreaterThanOrEqualTo(c.expectedConfidenceMin());
        }
        if (c.expectedNeedsThinking() != null) {
            assertThat(intent.getNeedsThinking())
                    .as("用例 %s：needsThinking", caseName)
                    .isEqualTo(c.expectedNeedsThinking());
        }
        if (Boolean.TRUE.equals(c.expectedNeedsThinkingReasonNonBlank())) {
            assertThat(intent.getNeedsThinkingReason())
                    .as("用例 %s：needsThinkingReason 不应为空", caseName)
                    .isNotBlank();
        }
        if (c.expectedSuggestedAction() != null) {
            assertThat(intent.getSuggestedAction())
                    .as("用例 %s：suggestedAction", caseName)
                    .isEqualTo(c.expectedSuggestedAction());
        }
        if (Boolean.TRUE.equals(c.expectedArticleIdBlank())) {
            assertThat(intent.getArticleId())
                    .as("用例 %s：articleId 不许由模型硬编（只能来自页面上下文或会话锚）", caseName)
                    .isBlank();
        }
        if (c.expectedRequestedActionsContains() != null) {
            assertThat(intent.getRequestedActions())
                    .as("用例 %s：requestedActions 应包含", caseName)
                    .containsAll(c.expectedRequestedActionsContains());
        }
        if (c.expectedAnalysisGoalEvidenceContains() != null) {
            assertThat(intent.getAnalysisGoalEvidence())
                    .as("用例 %s：analysisGoalEvidence 应包含", caseName)
                    .contains(c.expectedAnalysisGoalEvidenceContains());
        }
        if (Boolean.TRUE.equals(c.expectedAnalysisGoalEvidenceBlank())) {
            assertThat(intent.getAnalysisGoalEvidence())
                    .as("用例 %s：analysisGoalEvidence 必须为空（不得误判为复合目标）", caseName)
                    .isBlank();
        }
        if (c.expectedRequestKind() != null) {
            assertThat(intent.getRequestKind())
                    .as("用例 %s：requestKind（TASK 才进任务编排）", caseName)
                    .isEqualTo(c.expectedRequestKind());
        }
        if (c.expectedTaskGoalCountAtLeast() != null) {
            List<Map<String, Object>> goals = intent.getTaskGoals();
            assertThat(goals == null ? List.<Map<String, Object>>of() : goals)
                    .as("用例 %s：taskGoals 目标数——少拆一个目标就是一次静默丢目标", caseName)
                    .hasSizeGreaterThanOrEqualTo(c.expectedTaskGoalCountAtLeast());
        }
        if (c.expectedTaskGoalTypesContains() != null) {
            List<String> actualTypes = intent.getTaskGoals() == null
                    ? List.of()
                    : intent.getTaskGoals().stream()
                    .map(g -> g == null ? "" : String.valueOf(g.get("type")))
                    .toList();
            assertThat(actualTypes)
                    .as("用例 %s：taskGoals 类型集合", caseName)
                    .containsAll(c.expectedTaskGoalTypesContains());
        }
        if (c.expectedTaskGoalTypesNotContains() != null) {
            List<String> actualTypes = intent.getTaskGoals() == null
                    ? List.of()
                    : intent.getTaskGoals().stream()
                    .map(g -> g == null ? "" : String.valueOf(g.get("type")))
                    .toList();
            assertThat(actualTypes)
                    .as("用例 %s：taskGoals 不得包含这些类型", caseName)
                    .doesNotContainAnyElementsOf(c.expectedTaskGoalTypesNotContains());
        }
        // ---- 5a：双写契约（新协议 + 旧字段都要有） ----
        if (c.expectedSuggestedWorkflowType() != null) {
            assertThat(intent.getSuggestedWorkflowType())
                    .as("用例 %s：旧字段 suggestedWorkflowType 不能被新协议覆盖或清空——"
                            + "真正启动 Workflow 的仍是它", caseName)
                    .isEqualTo(c.expectedSuggestedWorkflowType());
        }
        if (Boolean.TRUE.equals(c.expectedSuggestedWorkflowTypeBlank())) {
            assertThat(intent.getSuggestedWorkflowType())
                    .as("用例 %s：多 Workflow 目标时 suggestedWorkflowType 必须为空", caseName)
                    .isBlank();
        }
        // 不只校验正例：某条反例若意外产出 WORKFLOW_HANDOFF，也必须遵守最小契约，
        // 不能因为该样例没有写 expectedWorkflowType 就放过伪造 ID / 额外参数 / 改写证据。
        assertWorkflowGoalContract(caseName, c.message(), intent);
        if (c.expectedTaskGoalWorkflowTypesContains() != null) {
            assertThat(taskGoalWorkflowTypes(intent))
                    .as("用例 %s：taskGoals[].params.workflowType（5a 新协议）", caseName)
                    .containsAll(c.expectedTaskGoalWorkflowTypesContains());
        }
    }

    /**
     * 5a 最小契约：类型合法、params 只有 workflowType、证据非空且确实来自用户原话。
     *
     * <p>包私有（非 private）是刻意的：P3 spike 的硬门**必须复用这一套**——
     * 两处各写一份，就会出现"候选 A 过得了这处、过不了那处"，而 P3 的排名建立在硬门之上。
     */
    static void assertWorkflowGoalContract(String caseName, String message, AiIntent intent) {
        List<Map<String, Object>> workflowGoals = intent.getTaskGoals() == null
                ? List.of()
                : intent.getTaskGoals().stream()
                .filter(Objects::nonNull)
                .filter(g -> "WORKFLOW_HANDOFF".equals(String.valueOf(g.get("type"))))
                .toList();
        String normalizedMessage = message == null ? "" : message.replaceAll("\\s+", "");
        for (Map<String, Object> goal : workflowGoals) {
            assertThat(goal.get("params"))
                    .as("用例 %s：WORKFLOW_HANDOFF.params 必须是对象", caseName)
                    .isInstanceOf(Map.class);
            Map<?, ?> params = (Map<?, ?>) goal.get("params");
            List<String> paramKeys = params.keySet().stream().map(String::valueOf).toList();
            assertThat(paramKeys)
                    .as("用例 %s：params 只能包含 workflowType，业务 ID/引用由后端解析", caseName)
                    .containsExactlyInAnyOrder("workflowType");
            assertThat(String.valueOf(params.get("workflowType")))
                    .as("用例 %s：workflowType 必须是已登记值", caseName)
                    .isIn(Stream.of(AiWorkflowType.values()).map(Enum::name).toList());

            List<String> evidence = textValues(goal.get("goalEvidence"));
            assertThat(evidence)
                    .as("用例 %s：goalEvidence 不能为空", caseName)
                    .isNotEmpty()
                    .allSatisfy(item -> assertThat(normalizedMessage)
                            .as("用例 %s：goalEvidence 必须逐字来自用户原话", caseName)
                            .contains(item.replaceAll("\\s+", "")));
        }
    }

    static List<String> textValues(Object value) {
        if (value instanceof String text) {
            return text.isBlank() ? List.of() : List.of(text);
        }
        if (value instanceof List<?> list) {
            return list.stream()
                    .filter(Objects::nonNull)
                    .map(String::valueOf)
                    .filter(text -> !text.isBlank())
                    .toList();
        }
        return List.of();
    }

    /** 从 taskGoals 里抽 {@code params.workflowType}——5a 新协议的业务参数（P3 spike 也复用） */
    static List<String> taskGoalWorkflowTypes(AiIntent intent) {
        List<Map<String, Object>> goals = intent.getTaskGoals();
        if (goals == null) {
            return List.of();
        }
        List<String> types = new ArrayList<>();
        for (Map<String, Object> goal : goals) {
            if (goal == null) {
                continue;
            }
            Object params = goal.get("params");
            if (params instanceof Map<?, ?> map) {
                Object workflowType = map.get("workflowType");
                if (workflowType != null) {
                    types.add(String.valueOf(workflowType));
                }
            }
        }
        return types;
    }

    /**
     * 弱模型允许少量口语化表达漏判，但不能把安全边界一起放松：
     * 核心句和反例仍在固定样例里逐条断言；这里只统计扩展正例的召回率。
     */
    @Test
    void actionAnalysisExtendedExpressionsReachEightyPercentRecall() throws Exception {
        assumeNoSingleCaseFilter();

        List<ActionAnalysisRecallCase> cases = loadActionAnalysisRecallCases();
        int requiredSuccesses = (int) Math.ceil(cases.size() * 0.8);
        int successes = 0;
        List<String> misses = new ArrayList<>();

        for (ActionAnalysisRecallCase c : cases) {
            PageContextDTO pageContext = new PageContextDTO();
            pageContext.setPageType(c.pageType());
            pageContext.setArticleId(c.pageArticleId());

            AiIntent intent = aiIntentClassifier.classify(c.message(), pageContext, null, null);
            assertClassifierCallSucceeded(c.name(), intent);

            boolean matched = "ARTICLE_ACTION".equals(intent.getIntent())
                    && intent.getRequestedActions() != null
                    && intent.getRequestedActions().containsAll(c.expectedRequestedActionsContains())
                    && intent.getAnalysisGoalEvidence() != null
                    && !intent.getAnalysisGoalEvidence().isBlank()
                    && c.message().contains(intent.getAnalysisGoalEvidence());
            if (matched) {
                successes++;
            } else {
                misses.add(c.name() + " -> intent=" + intent.getIntent()
                        + ", actions=" + intent.getRequestedActions()
                        + ", evidence=" + intent.getAnalysisGoalEvidence());
            }
        }

        assertThat(successes)
                .as("动作 + 分析扩展表达召回率应 >= 80%%（至少 %s/%s），漏判=%s",
                        requiredSuccesses, cases.size(), misses)
                .isGreaterThanOrEqualTo(requiredSuccesses);
    }

    private List<ActionAnalysisRecallCase> loadActionAnalysisRecallCases() throws Exception {
        InputStream inputStream = AiIntentClassifierIntegrationTests.class
                .getResourceAsStream("/agent/action-analysis-recall-cases.json");
        assertThat(inputStream)
                .as("动作 + 分析召回评测集文件必须存在")
                .isNotNull();

        List<ActionAnalysisRecallCase> cases = new ObjectMapper().readValue(
                Objects.requireNonNull(inputStream),
                new TypeReference<>() {
                }
        );
        assertThat(cases)
                .as("动作 + 分析召回评测集不能为空")
                .isNotEmpty();
        return cases;
    }

    private void assertClassifierCallSucceeded(String caseName, AiIntent intent) {
        assertThat(intent.getReason())
                .as("用例 %s：分类器调用失败，当前结果是降级值，不属于模型意图误判；请先排查 429/超时/供应商异常",
                        caseName)
                .isNotEqualTo("分类器调用失败，降级为普通聊天");
    }

    private void assumeNoSingleCaseFilter() {
        String evalCaseFilter = System.getProperty("evalCase");
        assumeTrue(evalCaseFilter == null || evalCaseFilter.isBlank(),
                "-DevalCase 只运行固定样例，跳过其他在线模型用例");
    }
}
