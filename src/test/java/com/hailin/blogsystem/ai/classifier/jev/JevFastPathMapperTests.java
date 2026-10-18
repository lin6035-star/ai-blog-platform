package com.hailin.blogsystem.ai.classifier.jev;

import com.hailin.blogsystem.entity.dto.AiIntent;
import com.hailin.blogsystem.entity.dto.PageContextDTO;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class JevFastPathMapperTests {

    private final JevFastPathMapper mapper = new JevFastPathMapper();

    @Test
    void mapsConfidentGeneralChatWithoutGoals() {
        JevFastPathDecision decision = mapper.map(result("general_chat", 0.91, Map.of(), false), null);

        assertThat(decision.accepted()).isTrue();
        assertCommonChatFields(decision.intent(), "GENERAL_CHAT", 0.91);
        assertThat(decision.intent().getNeedsThinking()).isFalse();
    }

    @Test
    void keepsNeedsThinkingAsSuggestionForGeneralChat() {
        JevFastPathDecision decision = mapper.map(result("general_chat", 0.91, Map.of(), true), null);

        assertThat(decision.accepted()).isTrue();
        assertThat(decision.intent().getNeedsThinking()).isTrue();
    }

    @Test
    void mapsCurrentArticleQaAndAnalysisToArticleDetailQa() {
        PageContextDTO page = articlePage();

        JevFastPathDecision qa = mapper.map(
                result("article_qa", 0.62, Map.of("article_qa", 0.96), false), page);
        JevFastPathDecision analysis = mapper.map(
                result("article_analysis", 0.55, Map.of("article_analysis", 0.94), false), page);

        assertCommonChatFields(qa.intent(), "ARTICLE_DETAIL_QA", 0.96);
        assertCommonChatFields(analysis.intent(), "ARTICLE_DETAIL_QA", 0.94);
    }

    @Test
    void mapsSingleArticleSearch() {
        JevFastPathDecision decision = mapper.map(
                result("article_search", 0.68, Map.of("article_search", 0.97), false), null);

        assertCommonChatFields(decision.intent(), "ARTICLE_SEARCH", 0.97);
    }

    /**
     * 学习计划查询：纯只读、且不依赖页面上下文。
     *
     * <p>2026-10-04 从"遗漏"补进白名单——第一批只挑了文章域三个，把同样是只读的它漏在外面。
     * 这里锁两件事：**无 pageContext 也要放行**（它不像文章问答需要当前文章），
     * 且 {@code suggestedAction=TOOL} 与 plus 对齐（Planner 会把它转成 dashboard 读工具）。
     */
    @Test
    void mapsLearningPlanQueryWithoutPageContext() {
        JevFastPathDecision decision = mapper.map(
                result("learning_plan_query", 0.99, Map.of("learning_plan_query", 0.99), false), null);

        assertThat(decision.accepted()).isTrue();
        AiIntent intent = decision.intent();
        assertThat(intent).isNotNull();
        assertThat(intent.getIntent()).isEqualTo("LEARNING_PLAN_QUERY");
        assertThat(intent.getSuggestedAction()).isEqualTo("TOOL");
        assertThat(intent.getRisk()).isEqualTo("LOW");
        assertThat(intent.getConfidence()).isEqualTo(0.99);
        assertThat(intent.getRequestKind()).isEqualTo("CHAT");
    }

    /** 学习计划查询同样受单目标 / primary 一致 / 置信度三道门槛约束。 */
    @Test
    void learningPlanQuerySharesTheSamePreconditions() {
        assertFallback(mapper.map(
                result("general_chat", 0.91, Map.of("learning_plan_query", 0.99), false), null),
                "PRIMARY_CONFLICT");
        assertFallback(mapper.map(
                result("learning_plan_query", 0.69, Map.of("learning_plan_query", 0.69), false), null),
                "LOW_CONFIDENCE");
    }

    @Test
    void rejectsMultipleGoals() {
        JevFastPathDecision decision = mapper.map(result("article_write", 0.90, Map.of(
                "article_write", 0.97,
                "article_analysis", 0.95), false), articlePage());

        assertFallback(decision, "MULTIPLE_GOALS");
    }

    /**
     * 第二批的安全边界：**直接执行、没有确认环节**的意图必须继续回退。
     *
     * <p>这三个的评测也是全绿的，但"离线全对"和"线上不出错"是两件事——
     * 它们判错会直接落到数据上（点赞 / 跳转 / 改草稿），没有确认卡兜底。
     * 一旦有人为了省时间把它们加进白名单，这条测试必须红。
     */
    @Test
    void stillRejectsDirectWriteActions() {
        PageContextDTO page = articlePage();

        assertFallback(mapper.map(
                result("article_write", 0.99, Map.of("article_write", 1.00), false), page),
                "UNSUPPORTED_GOAL");
        assertFallback(mapper.map(
                result("navigate", 0.99, Map.of("navigate", 1.00), false), page),
                "UNSUPPORTED_GOAL");
        assertFallback(mapper.map(
                result("editor_action", 0.99, Map.of("editor_action", 1.00), false), page),
                "UNSUPPORTED_GOAL");
        assertFallback(mapper.map(
                result("wallet", 0.99, Map.of("wallet", 1.00), false), page),
                "UNSUPPORTED_GOAL");
    }

    /**
     * 文章类 Workflow 仍是快速路径：动词明确（写/创建/优化），与只读意图分开判定。
     *
     * <p>锁的是**每个字段都有下游硬判据**：{@code suggestedAction=WORKFLOW} 决定
     * {@code isWorkflowSuggestion}；{@code suggestedWorkflowType} 必须等于
     * {@code AiWorkflowType.name()}，否则 {@code hasWorkflowSuggestion} 不通过而降级 CTA；
     * {@code intent} 名的后缀规则（文章带 {@code _WORKFLOW}）是既有协议。
     */
    @Test
    void mapsArticleWorkflowGoalsToPlannerContract() {
        PageContextDTO page = articlePage();

        assertWorkflowIntent(mapper.map(
                result("workflow_create", 0.78, Map.of("workflow_create", 0.78), false), null),
                "CREATE_ARTICLE_WORKFLOW", "CREATE_ARTICLE");

        assertWorkflowIntent(mapper.map(
                result("workflow_optimize", 0.99, Map.of("workflow_optimize", 1.00), false), page),
                "OPTIMIZE_ARTICLE_WORKFLOW", "OPTIMIZE_ARTICLE");
    }

    /**
     * 学习类 Workflow **一律回退 plus**（2026-10-18 生产事故）。
     *
     * <p>实测：用户「讲讲我的 Redis 学习计划」「讲一讲我的 Redis 学习计划」被 Jev 以
     * 0.83 / 0.76 判成 {@code workflow_learning_plan}，直接拉起"制定新计划"工作流——
     * 代价不是"多问一次"，而是 17 秒规划 + 错误计划卡 + 真实计费。
     * Jev 的独立二元 goal 无法区分「看/讲已有计划」与「制定新计划」，
     * 写意图必须交给带完整语义分界的 plus 分类器。
     *
     * <p>这条测试是白名单的**回归锁**：谁把学习类 Workflow 加回快速路径，这里就红。
     */
    @Test
    void learningWorkflowGoalsAlwaysFallBackToPlus() {
        assertFallback(mapper.map(
                result("workflow_learning_plan", 0.99, Map.of("workflow_learning_plan", 1.00), false), null),
                "UNSUPPORTED_GOAL");
        assertFallback(mapper.map(
                result("workflow_learning_progress", 0.99, Map.of("workflow_learning_progress", 0.99), false), null),
                "UNSUPPORTED_GOAL");
        assertFallback(mapper.map(
                result("workflow_learning_assist", 0.93, Map.of("workflow_learning_assist", 0.93), false), null),
                "UNSUPPORTED_GOAL");
    }

    /** 创建文章只需要用户原话，不要求当前文章上下文（requirement 用的是 message）。 */
    @Test
    void workflowCreateDoesNotRequireArticleContext() {
        JevFastPathDecision decision = mapper.map(
                result("workflow_create", 0.90, Map.of("workflow_create", 0.98), false), null);

        assertThat(decision.accepted()).isTrue();
    }

    /** 文章优化缺当前文章时回退——与 article_qa / article_analysis 同一处理。 */
    @Test
    void workflowOptimizeRequiresCurrentArticleContext() {
        PageContextDTO missingId = new PageContextDTO();
        missingId.setPageType("article-detail");

        assertFallback(mapper.map(
                result("workflow_optimize", 0.99, Map.of("workflow_optimize", 1.00), false), null),
                "ARTICLE_CONTEXT_MISSING");
        assertFallback(mapper.map(
                result("workflow_optimize", 0.99, Map.of("workflow_optimize", 1.00), false), missingId),
                "ARTICLE_CONTEXT_MISSING");
    }

    @Test
    void workflowGoalsShareTheSamePreconditionsAsReadOnly() {
        // primary 不一致
        assertFallback(mapper.map(
                result("general_chat", 0.91, Map.of("workflow_create", 0.97), false), null),
                "PRIMARY_CONFLICT");
        // 置信度不足（ACCEPT_THRESHOLD = 0.70）
        assertFallback(mapper.map(
                result("workflow_create", 0.69, Map.of("workflow_create", 0.69), false), null),
                "LOW_CONFIDENCE");
    }

    @Test
    void rejectsPrimaryConflict() {
        JevFastPathDecision decision = mapper.map(
                result("general_chat", 0.91, Map.of("article_search", 0.97), false), null);

        assertFallback(decision, "PRIMARY_CONFLICT");
    }

    @Test
    void rejectsLowConfidence() {
        JevFastPathDecision general = mapper.map(result("general_chat", 0.69, Map.of(), false), null);
        JevFastPathDecision search = mapper.map(
                result("article_search", 0.90, Map.of("article_search", 0.69), false), null);

        assertFallback(general, "LOW_CONFIDENCE");
        assertFallback(search, "LOW_CONFIDENCE");
    }

    @Test
    void rejectsArticleQaWithoutCurrentArticleContext() {
        JevFastPathDecision noPage = mapper.map(
                result("article_qa", 0.90, Map.of("article_qa", 0.96), false), null);
        PageContextDTO missingId = new PageContextDTO();
        missingId.setPageType("article-detail");
        JevFastPathDecision noId = mapper.map(
                result("article_qa", 0.90, Map.of("article_qa", 0.96), false), missingId);

        assertFallback(noPage, "ARTICLE_CONTEXT_MISSING");
        assertFallback(noId, "ARTICLE_CONTEXT_MISSING");
    }

    private static JevDecisionResult result(String primary, double primaryConfidence,
                                            Map<String, Double> goals, boolean needsThinking) {
        Map<String, Double> probabilities = new LinkedHashMap<>(goals);
        return new JevDecisionResult(
                "typesafe/jev-1.13", "TypeSafe", primary, primaryConfidence,
                Map.of(primary, primaryConfidence), probabilities, Set.copyOf(goals.keySet()),
                needsThinking ? 0.91 : 0.10, needsThinking, 100, 10, 0.001, 350);
    }

    private static PageContextDTO articlePage() {
        PageContextDTO page = new PageContextDTO();
        page.setPageType("article-detail");
        page.setArticleId("12");
        return page;
    }

    private static void assertCommonChatFields(AiIntent intent, String expectedIntent, double confidence) {
        assertThat(intent).isNotNull();
        assertThat(intent.getIntent()).isEqualTo(expectedIntent);
        assertThat(intent.getRequestKind()).isEqualTo("CHAT");
        assertThat(intent.getSuggestedAction()).isEqualTo("CHAT");
        assertThat(intent.getRisk()).isEqualTo("LOW");
        assertThat(intent.getConfidence()).isEqualTo(confidence);
        assertThat(intent.getTaskGoals()).isNull();
    }

    private static void assertWorkflowIntent(JevFastPathDecision decision,
                                             String expectedIntent, String expectedWorkflowType) {
        assertThat(decision.accepted()).as("应放行: %s", expectedWorkflowType).isTrue();
        AiIntent intent = decision.intent();
        assertThat(intent).isNotNull();
        assertThat(intent.getIntent()).isEqualTo(expectedIntent);
        // 下游 isWorkflowSuggestion 的唯一判据
        assertThat(intent.getSuggestedAction()).isEqualTo("WORKFLOW");
        // 下游 hasWorkflowSuggestion 要求与 AiWorkflowType.name() 相等
        assertThat(intent.getSuggestedWorkflowType()).isEqualTo(expectedWorkflowType);
        // 学习类 Workflow 要求 isLowRisk；文章类接受 LOW/MEDIUM
        assertThat(intent.getRisk()).isEqualTo("LOW");
        // 不进 TaskPlan 编排，走已验证的旧 Workflow 路径
        assertThat(intent.getRequestKind()).isEqualTo("CHAT");
        assertThat(intent.getNeedsThinking()).isFalse();
        assertThat(intent.getTaskGoals()).isNull();
    }

    private static void assertFallback(JevFastPathDecision decision, String reason) {
        assertThat(decision.accepted()).isFalse();
        assertThat(decision.intent()).isNull();
        assertThat(decision.fallbackReason()).isEqualTo(reason);
    }
}
