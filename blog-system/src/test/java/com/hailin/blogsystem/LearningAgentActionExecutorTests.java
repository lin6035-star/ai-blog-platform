package com.hailin.blogsystem;

import com.hailin.blogsystem.ai.agent.AgentStepDecision;
import com.hailin.blogsystem.ai.agent.AgentActionResult;
import com.hailin.blogsystem.ai.agent.LearningAgentActionExecutorImpl;
import com.hailin.blogsystem.ai.memory.AiEpisodicMemoryRetrieveService;
import com.hailin.blogsystem.ai.memory.AiMemoryRetrieveService;
import com.hailin.blogsystem.ai.rag.ArticleRagSearchService;
import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.entity.LearningPlans;
import com.hailin.blogsystem.entity.dto.ArticleRagContext;
import com.hailin.blogsystem.entity.dto.ArticleRagSearchResult;
import com.hailin.blogsystem.entity.dto.ArticleRagSearchStrategy;
import com.hailin.blogsystem.entity.dto.AgentStepActionType;
import com.hailin.blogsystem.entity.dto.EpisodicMemoryRagContext;
import com.hailin.blogsystem.entity.dto.MemoryRagContext;
import com.hailin.blogsystem.entity.vo.LearningPlansDetailVO;
import com.hailin.blogsystem.service.AiUserMemoryService;
import com.hailin.blogsystem.service.LearningPlansService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Learning Agent 动作执行器测试。
 * 全部 mock Service，验证三个只读动作的 observation 输出与失败语义。
 */
class LearningAgentActionExecutorTests {

    private LearningPlansService learningPlansService;
    private AiMemoryRetrieveService aiMemoryRetrieveService;
    private AiEpisodicMemoryRetrieveService aiEpisodicMemoryRetrieveService;
    private ArticleRagSearchService articleRagSearchService;
    private AiUserMemoryService aiUserMemoryService;
    private LearningAgentActionExecutorImpl executor;

    @BeforeEach
    void setUp() {
        learningPlansService = mock(LearningPlansService.class);
        aiMemoryRetrieveService = mock(AiMemoryRetrieveService.class);
        aiEpisodicMemoryRetrieveService = mock(AiEpisodicMemoryRetrieveService.class);
        articleRagSearchService = mock(ArticleRagSearchService.class);
        aiUserMemoryService = mock(AiUserMemoryService.class);

        BlogAiProperties properties = new BlogAiProperties();
        executor = new LearningAgentActionExecutorImpl(
                learningPlansService,
                aiMemoryRetrieveService,
                aiEpisodicMemoryRetrieveService,
                articleRagSearchService,
                properties,
                aiUserMemoryService
        );
    }

    @Test
    void notLoggedInReturnsFriendlyText() {
        String observation = executor.execute(
                AgentStepDecision.of(AgentStepActionType.QUERY_LEARNING_DASHBOARD),
                null,
                null
        ).summary();

        assertThat(observation).isEqualTo("当前未登录，无法查询学习信息。");
    }

    @Test
    void dashboardWithNoPlansReturnsEmptyText() {
        when(learningPlansService.listByUser(100L)).thenReturn(List.of());

        String observation = executor.execute(
                AgentStepDecision.of(AgentStepActionType.QUERY_LEARNING_DASHBOARD),
                100L,
                null
        ).summary();

        assertThat(observation).isEqualTo("当前没有任何学习计划。");
    }

    @Test
    void dashboardListsPlanSummaries() {
        LearningPlans planA = plan(1L, "Redis 学习计划", "ACTIVE");
        LearningPlans planB = plan(2L, "Java 并发计划", "ACTIVE");
        when(learningPlansService.listByUser(100L)).thenReturn(List.of(planA, planB));

        String observation = executor.execute(
                AgentStepDecision.of(AgentStepActionType.QUERY_LEARNING_DASHBOARD),
                100L,
                null
        ).summary();

        assertThat(observation).contains("共 2 个计划");
        assertThat(observation).contains("Redis 学习计划");
        assertThat(observation).contains("Java 并发计划");
    }

    /**
     * 2026-10-18 修：计划详情必须进结构化事实（Token 优化后模型只看 payload）。
     *
     * <p>事故：payload 只有元数据，模型回答"观察仅含计划元数据"并反复重查被去重跳过。
     */
    @Test
    void dashboardPlanDetailIsExposedInPayload() {
        LearningPlans plan = plan(1L, "两个月 Agent 开发入门到实战计划", "ACTIVE");
        when(learningPlansService.listByUser(100L)).thenReturn(List.of(plan));
        when(learningPlansService.matchPlansByMessage(eq(100L), any()))
                .thenReturn(List.of(plan));
        when(learningPlansService.getDetail(1L, 100L)).thenReturn(detailWithOneStage());

        AgentActionResult result = executor.execute(
                AgentStepDecision.of(AgentStepActionType.QUERY_LEARNING_DASHBOARD)
                        .withInput(Map.of("planRef", "两个月 Agent 开发入门到实战计划")),
                100L,
                null
        );

        assertThat(result.payload().get("planDetail")).asString()
                .contains("目标计划《两个月 Agent 开发入门到实战计划》")
                .contains("总进度：1/2")
                .contains("阶段《第1周：基础》")
                .contains("[x] 已完成任务")
                .contains("[ ] 未完成任务");
    }

    @Test
    void dashboardWithPlanRefShowsTargetPlanDetail() {
        LearningPlans plan = plan(1L, "Redis 学习计划", "ACTIVE");
        when(learningPlansService.listByUser(100L)).thenReturn(List.of(plan));
        when(learningPlansService.matchPlansByMessage(eq(100L), any()))
                .thenReturn(List.of(plan));
        // getDetail 未 mock，返回 null：详情分支容错，不炸

        AgentActionResult result = executor.execute(
                AgentStepDecision.of(AgentStepActionType.QUERY_LEARNING_DASHBOARD)
                        .withInput(Map.of("planRef", "Redis")),
                100L,
                null
        );

        assertThat(result.summary()).contains("目标计划");
        assertThat(result.summary()).contains("Redis 学习计划");
        assertThat(result.payload())
                .containsEntry("status", "SUCCESS")
                .containsEntry("planRef", "Redis")
                .containsEntry("planCount", 1)
                .containsEntry("activePlanCount", 1)
                .containsEntry("matchedCount", 1)
                .containsEntry("selectedPlanId", 1L);
    }

    @Test
    void dashboardWithMultipleMatchesListsCandidatesInsteadOfGuessing() {
        LearningPlans planA = plan(1L, "Redis 学习计划", "ACTIVE");
        LearningPlans planB = plan(2L, "Redis 进阶计划", "ACTIVE");
        when(learningPlansService.listByUser(100L)).thenReturn(List.of(planA, planB));
        when(learningPlansService.matchPlansByMessage(eq(100L), any()))
                .thenReturn(List.of(planA, planB));

        AgentActionResult result = executor.execute(
                AgentStepDecision.of(AgentStepActionType.QUERY_LEARNING_DASHBOARD)
                        .withInput(Map.of("planRef", "Redis")),
                100L,
                null
        );

        // 并列 = 歧义：只列候选，不猜（Agent 下一步应 ASK_USER）
        assertThat(result.summary()).contains("请确认具体是哪个");
        assertThat(result.summary()).contains("Redis 学习计划");
        assertThat(result.summary()).contains("Redis 进阶计划");
        assertThat(result.payload().get("planCandidates")).asString()
                .contains("Redis 学习计划")
                .contains("Redis 进阶计划");
    }

    @Test
    void dashboardWithoutPlanRefAndSingleActiveShowsDetail() {
        LearningPlans plan = plan(1L, "Redis 学习计划", "ACTIVE");
        when(learningPlansService.listByUser(100L)).thenReturn(List.of(plan));
        // matchPlansByMessage 未 mock，返回 null/空：点名分支不触发

        String observation = executor.execute(
                AgentStepDecision.of(AgentStepActionType.QUERY_LEARNING_DASHBOARD),
                100L,
                null
        ).summary();

        // 未点名且唯一 ACTIVE → 直接详情（getDetail 未 mock 返回 null，详情内部容错）
        assertThat(observation).contains("目标计划");
    }

    @Test
    void dashboardWithoutPlanRefAndMultipleActiveListsCandidates() {
        LearningPlans planA = plan(1L, "Redis 学习计划", "ACTIVE");
        LearningPlans planB = plan(2L, "Java 并发计划", "ACTIVE");
        when(learningPlansService.listByUser(100L)).thenReturn(List.of(planA, planB));

        String observation = executor.execute(
                AgentStepDecision.of(AgentStepActionType.QUERY_LEARNING_DASHBOARD),
                100L,
                null
        ).summary();

        // 多个 ACTIVE 只列候选
        assertThat(observation).contains("共 2 个计划");
        assertThat(observation).contains("1. 《Redis 学习计划》");
        assertThat(observation).contains("2. 《Java 并发计划》");
    }

    @Test
    void dashboardPlanRefNotFoundDoesNotFallbackToSingleActivePlan() {
        // 用户点名 RocketMQ，系统只有 Redis 计划：
        // 必须只提示未找到 + 候选，绝不能 fallback 出《Redis 计划》详情误导 Agent
        LearningPlans plan = plan(1L, "Redis 学习计划", "ACTIVE");
        when(learningPlansService.listByUser(100L)).thenReturn(List.of(plan));
        when(learningPlansService.matchPlansByMessage(eq(100L), any()))
                .thenReturn(List.of());

        AgentActionResult result = executor.execute(
                AgentStepDecision.of(AgentStepActionType.QUERY_LEARNING_DASHBOARD)
                        .withInput(Map.of("planRef", "RocketMQ")),
                100L,
                null
        );

        assertThat(result.summary()).contains("没有找到");
        assertThat(result.summary()).doesNotContain("目标计划");
        // 候选列表仍给出（Agent 下一步可 ASK_USER 让用户选）
        assertThat(result.summary()).contains("Redis 学习计划");
        assertThat(result.payload())
                .containsEntry("status", "SUCCESS")
                .containsEntry("planRef", "RocketMQ")
                .containsEntry("planCount", 1)
                .containsEntry("activePlanCount", 1)
                .containsEntry("matchedCount", 0)
                .containsEntry("selectedPlanId", null);
        assertThat(result.payload().get("planCandidates")).asString()
                .as("候选也要进结构化事实，模型才能重试正确标题或向用户确认")
                .contains("Redis 学习计划");
    }

    @Test
    void memoryReturnsSummaryWithEmptyRetrieval() {
        when(aiMemoryRetrieveService.retrieve(eq(100L), any())).thenReturn(List.of());
        when(aiEpisodicMemoryRetrieveService.retrieveForPrompt(eq(100L), any(), any()))
                .thenReturn(List.of());

        AgentActionResult result = executor.execute(
                AgentStepDecision.of(AgentStepActionType.QUERY_MEMORY)
                        .withInput(Map.of("question", "学习偏好")),
                100L,
                null
        );

        assertThat(result.summary()).contains("记忆摘要");
        assertThat(result.payload())
                .containsEntry("status", "SUCCESS")
                .containsEntry("query", "学习偏好")
                .containsEntry("hitCount", 0);
    }

    @Test
    void memoryRetrievalFailureStillReturnsSummary() {
        when(aiMemoryRetrieveService.retrieve(eq(100L), any()))
                .thenThrow(new RuntimeException("ES 连接失败"));
        when(aiEpisodicMemoryRetrieveService.retrieveForPrompt(eq(100L), any(), any()))
                .thenThrow(new RuntimeException("ES 连接失败"));

        AgentActionResult result = executor.execute(
                AgentStepDecision.of(AgentStepActionType.QUERY_MEMORY)
                        .withInput(Map.of("question", "学习偏好")),
                100L,
                null
        );

        // 记忆检索失败不能炸掉 Agent Loop，返回带失败标记的摘要
        assertThat(result.summary()).contains("语义记忆检索失败");
        assertThat(result.summary()).contains("情景记忆检索失败");
        assertThat(result.payload())
                .containsEntry("status", "FAILED")
                .containsEntry("query", "学习偏好")
                .containsEntry("hitCount", 0);
    }

    @Test
    void memoryPartialFailurePreservesSuccessfulHitsAndStatus() {
        when(aiMemoryRetrieveService.retrieve(eq(100L), any()))
                .thenReturn(List.of(new MemoryRagContext(1L, 100L, "PREFERENCE", "style", "回答直接一点", null, null)));
        when(aiEpisodicMemoryRetrieveService.retrieveForPrompt(eq(100L), any(), any()))
                .thenThrow(new RuntimeException("ES 连接失败"));

        AgentActionResult result = executor.execute(
                AgentStepDecision.of(AgentStepActionType.QUERY_MEMORY)
                        .withInput(Map.of("question", "学习偏好")),
                100L,
                null
        );

        assertThat(result.summary()).contains("回答直接一点");
        assertThat(result.payload())
                .containsEntry("status", "PARTIAL_FAILURE")
                .containsEntry("query", "学习偏好")
                .containsEntry("hitCount", 1);
        assertThat(result.payload().get("memoryContent")).asString()
                .as("命中的记忆内容要进结构化事实（模型只看 payload）")
                .contains("回答直接一点");
    }

    @Test
    void ragWithoutKeywordReturnsHint() {
        String observation = executor.execute(
                AgentStepDecision.of(AgentStepActionType.SEARCH_RAG),
                100L,
                null
        ).summary();

        assertThat(observation).contains("缺少关键词");
    }

    @Test
    void ragWithNoResultReturnsEmptyText() {
        when(articleRagSearchService.search(any(), any()))
                .thenReturn(new ArticleRagSearchResult(ArticleRagSearchStrategy.EMPTY, List.of()));

        AgentActionResult result = executor.execute(
                AgentStepDecision.of(AgentStepActionType.SEARCH_RAG)
                        .withInput(Map.of("keyword", "Redis 缓存击穿")),
                100L,
                null
        );

        assertThat(result.summary()).contains("没有检索到");
        assertThat(result.payload())
                .containsEntry("status", "SUCCESS")
                .containsEntry("keyword", "Redis 缓存击穿")
                .containsEntry("hitCount", 0);
    }

    @Test
    void ragWithHitsReturnsStructuredFact() {
        when(articleRagSearchService.search(any(), any()))
                .thenReturn(new ArticleRagSearchResult(
                        ArticleRagSearchStrategy.VECTOR,
                        List.of(new ArticleRagContext(7L, "Redis 缓存", 0, "缓存击穿的处理方式"))));

        AgentActionResult result = executor.execute(
                AgentStepDecision.of(AgentStepActionType.SEARCH_RAG)
                        .withInput(Map.of("keyword", "Redis 缓存击穿")),
                100L,
                null
        );

        assertThat(result.summary()).contains("Redis 缓存");
        assertThat(result.payload())
                .containsEntry("status", "SUCCESS")
                .containsEntry("keyword", "Redis 缓存击穿")
                .containsEntry("hitCount", 1);
        assertThat(result.payload().get("ragContent")).asString()
                .as("检索片段要进结构化事实（模型只看 payload）")
                .contains("Redis 缓存")
                .contains("缓存击穿的处理方式");
    }

    @Test
    void terminalActionMustNotReachExecutor() {
        assertThatThrownBy(() -> executor.execute(
                AgentStepDecision.of(AgentStepActionType.ASK_USER),
                100L,
                null
        )).isInstanceOf(UnsupportedOperationException.class);
    }

    private LearningPlans plan(Long id, String title, String status) {
        LearningPlans plan = new LearningPlans();
        plan.setId(id);
        plan.setTitle(title);
        plan.setStatus(status);
        return plan;
    }

    private LearningPlansDetailVO detailWithOneStage() {
        LearningPlansDetailVO detail = new LearningPlansDetailVO();
        detail.setDoneTasks(1);
        detail.setTotalTasks(2);

        LearningPlansDetailVO.TaskItem done = new LearningPlansDetailVO.TaskItem();
        done.setTitle("已完成任务");
        done.setDone(true);
        LearningPlansDetailVO.TaskItem pending = new LearningPlansDetailVO.TaskItem();
        pending.setTitle("未完成任务");
        pending.setDone(false);

        LearningPlansDetailVO.StageProgress stage = new LearningPlansDetailVO.StageProgress();
        stage.setTitle("第1周：基础");
        stage.setTasks(List.of(done, pending));

        detail.setStages(List.of(stage));
        return detail;
    }
}
