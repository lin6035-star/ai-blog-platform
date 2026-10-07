package com.hailin.blogsystem.ai.classifier.jev;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.ai.agent.AgentRuntimeRouteRegistry;
import com.hailin.blogsystem.ai.agent.ArticleAgentRuntime;
import com.hailin.blogsystem.ai.agent.ArticleSessionAnchorService;
import com.hailin.blogsystem.ai.agent.GeneralAgentRuntime;
import com.hailin.blogsystem.ai.agent.LearningAgentRuntime;
import com.hailin.blogsystem.ai.agent.WalletAgentRuntime;
import com.hailin.blogsystem.ai.planner.AgentPlannerSupport;
import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.entity.AiSessions;
import com.hailin.blogsystem.entity.dto.AgentAction;
import com.hailin.blogsystem.entity.dto.AgentDecision;
import com.hailin.blogsystem.entity.dto.AiWorkflowType;
import com.hailin.blogsystem.entity.dto.PageContextDTO;
import com.hailin.blogsystem.mapper.AiWorkflowRunMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 接缝测试：<b>Jev 的输出 → Planner 的裁决</b>。
 *
 * <p>为什么单独测这条缝：Mapper 自己的测试只断言"我按契约填了字段"，
 * Planner 的测试只断言"给我合法字段我能裁决"——<b>两边都绿，中间对不上照样出事</b>。
 * Workflow 的判据散落在 {@code suggestedAction} / {@code suggestedWorkflowType} /
 * {@code confidence} / {@code risk} 四个字段上，任何一个拼错（比如少个 {@code _WORKFLOW} 后缀、
 * 或者把 risk 写成 MEDIUM 导致学习类被 {@code isLowRisk} 拦下），Mapper 测试都不会红。
 *
 * <p>这里把两段真实代码接起来跑，锁住"Jev 直通后确实能拉起 Workflow"。
 */
class JevWorkflowHandoffTests {

    private JevFastPathMapper mapper;
    private AgentPlannerSupport planner;

    @BeforeEach
    void setUp() {
        mapper = new JevFastPathMapper();

        BlogAiProperties properties = new BlogAiProperties();
        AiWorkflowRunMapper workflowRunMapper = mock(AiWorkflowRunMapper.class);
        when(workflowRunMapper.selectList(any())).thenReturn(List.of());
        ArticleSessionAnchorService anchorService = mock(ArticleSessionAnchorService.class);
        AgentRuntimeRouteRegistry routeRegistry = new AgentRuntimeRouteRegistry(
                mock(LearningAgentRuntime.class),
                mock(ArticleAgentRuntime.class),
                mock(GeneralAgentRuntime.class),
                mock(WalletAgentRuntime.class)
        );
        planner = new AgentPlannerSupport(properties, workflowRunMapper, new ObjectMapper(),
                routeRegistry, anchorService);
    }

    @Test
    void jevWorkflowOptimizeReachesPlannerAsWorkflow() {
        PageContextDTO page = articlePage();
        JevFastPathDecision jev = mapper.map(
                result("workflow_optimize", 0.99, Map.of("workflow_optimize", 1.00)), page);
        assertThat(jev.accepted()).isTrue();

        AgentDecision decision = planner.decide("帮我优化这篇文章", jev.intent(), page, 1L, session());

        assertThat(decision.getAction()).isEqualTo(AgentAction.WORKFLOW);
        assertThat(decision.getWorkflowType()).isEqualTo(AiWorkflowType.OPTIMIZE_ARTICLE);
    }

    @Test
    void jevWorkflowCreateReachesPlannerAsWorkflow() {
        JevFastPathDecision jev = mapper.map(
                result("workflow_create", 0.80, Map.of("workflow_create", 0.80)), null);
        assertThat(jev.accepted()).isTrue();

        AgentDecision decision = planner.decide(
                "帮我写一篇 Redis 缓存设计的文章", jev.intent(), null, 1L, session());

        assertThat(decision.getAction()).isEqualTo(AgentAction.WORKFLOW);
        assertThat(decision.getWorkflowType()).isEqualTo(AiWorkflowType.CREATE_ARTICLE);
    }

    /** 学习类走 decideLearning 那条裁决链（Planner 对学习 Workflow 有独立的低风险要求）。 */
    @Test
    void jevLearningPlanReachesPlannerAsWorkflow() {
        JevFastPathDecision jev = mapper.map(
                result("workflow_learning_plan", 0.99, Map.of("workflow_learning_plan", 1.00)), null);
        assertThat(jev.accepted()).isTrue();

        AgentDecision decision = planner.decideLearning(jev.intent(), 1L, session());

        assertThat(decision.getAction()).isEqualTo(AgentAction.WORKFLOW);
        assertThat(decision.getWorkflowType()).isEqualTo(AiWorkflowType.LEARNING_PLAN);
    }

    @Test
    void jevLearningAssistReachesPlannerAsWorkflow() {
        JevFastPathDecision jev = mapper.map(
                result("workflow_learning_assist", 0.94, Map.of("workflow_learning_assist", 0.94)), null);
        assertThat(jev.accepted()).isTrue();

        AgentDecision decision = planner.decideLearning(jev.intent(), 1L, session());

        assertThat(decision.getAction()).isEqualTo(AgentAction.WORKFLOW);
        assertThat(decision.getWorkflowType()).isEqualTo(AiWorkflowType.LEARNING_ASSIST);
    }

    /** 反向锁：只读意图不能因为这次扩展被误判成 Workflow。 */
    @Test
    void jevReadOnlyIntentStaysChat() {
        PageContextDTO page = articlePage();
        JevFastPathDecision jev = mapper.map(
                result("article_qa", 0.96, Map.of("article_qa", 0.96)), page);

        AgentDecision decision = planner.decide("这篇文章讲什么", jev.intent(), page, 1L, session());

        assertThat(decision.getAction()).isNotEqualTo(AgentAction.WORKFLOW);
    }

    /**
     * 学习计划查询：不启动 Workflow，而是由 Planner 转成 dashboard <b>读工具</b>。
     *
     * <p>这条锁的是"意图名 → Planner 动作"的映射——名字差一个词就退化成普通聊天，
     * 而普通聊天不会去查用户的计划，用户看到的就是答非所问。
     */
    @Test
    void jevLearningPlanQueryReachesDashboardTool() {
        JevFastPathDecision jev = mapper.map(
                result("learning_plan_query", 0.99, Map.of("learning_plan_query", 0.99)), null);
        assertThat(jev.accepted()).isTrue();

        AgentDecision decision = planner.decide(
                "我的MySQL学习计划学到哪了", jev.intent(), null, 1L, session());

        assertThat(decision.getAction()).isEqualTo(AgentAction.TOOL);
        assertThat(decision.getToolName()).isEqualTo("getLearningDashboard");
    }

    private static JevDecisionResult result(String primary, double confidence,
                                            Map<String, Double> goals) {
        return new JevDecisionResult(
                "typesafe/jev-1.13", "TypeSafe", primary, confidence,
                Map.of(primary, confidence), new LinkedHashMap<>(goals), Set.copyOf(goals.keySet()),
                0.10, false, 100, 10, 0.001, 350);
    }

    private static PageContextDTO articlePage() {
        PageContextDTO page = new PageContextDTO();
        page.setPageType("article-detail");
        page.setArticleId("12");
        return page;
    }

    private static AiSessions session() {
        AiSessions session = new AiSessions();
        session.setId(10L);
        session.setUserId(1L);
        session.setActiveWorkflowRunId(null);
        return session;
    }
}
