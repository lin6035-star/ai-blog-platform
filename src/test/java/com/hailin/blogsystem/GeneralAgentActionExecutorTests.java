package com.hailin.blogsystem;

import com.hailin.blogsystem.ai.agent.AgentStepDecision;
import com.hailin.blogsystem.ai.agent.GeneralAgentActionExecutorImpl;
import com.hailin.blogsystem.ai.memory.AiEpisodicMemoryRetrieveService;
import com.hailin.blogsystem.ai.memory.AiMemoryRetrieveService;
import com.hailin.blogsystem.ai.rag.ArticleRagSearchService;
import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.entity.AiUserMemories;
import com.hailin.blogsystem.entity.dto.ArticleRagContext;
import com.hailin.blogsystem.entity.dto.ArticleRagSearchResult;
import com.hailin.blogsystem.entity.dto.ArticleRagSearchStrategy;
import com.hailin.blogsystem.entity.dto.AgentStepActionType;
import com.hailin.blogsystem.entity.dto.EpisodicMemoryRagContext;
import com.hailin.blogsystem.entity.dto.MemoryRagContext;
import com.hailin.blogsystem.entity.dto.PageContextDTO;
import com.hailin.blogsystem.service.AiUserMemoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 通用域动作执行器测试（V3 通用思考模式）。
 * 验证 QUERY_MEMORY（V3 增量）/ SEARCH_RAG（证据补充）的观察输出与失败语义。
 */
class GeneralAgentActionExecutorTests {

    private AiMemoryRetrieveService aiMemoryRetrieveService;
    private AiEpisodicMemoryRetrieveService aiEpisodicMemoryRetrieveService;
    private ArticleRagSearchService articleRagSearchService;
    private AiUserMemoryService aiUserMemoryService;
    private GeneralAgentActionExecutorImpl executor;

    @BeforeEach
    void setUp() {
        aiMemoryRetrieveService = mock(AiMemoryRetrieveService.class);
        aiEpisodicMemoryRetrieveService = mock(AiEpisodicMemoryRetrieveService.class);
        articleRagSearchService = mock(ArticleRagSearchService.class);
        aiUserMemoryService = mock(AiUserMemoryService.class);
        executor = new GeneralAgentActionExecutorImpl(
                aiMemoryRetrieveService,
                aiEpisodicMemoryRetrieveService,
                articleRagSearchService,
                new BlogAiProperties(),
                aiUserMemoryService
        );
    }

    @Test
    void memoryReturnsSummaryWithRetrievals() {
        when(aiMemoryRetrieveService.retrieve(eq(100L), any())).thenReturn(List.of(
                new MemoryRagContext(1L, 100L, "USER_PREFERENCE", "偏好",
                        "用户正在学 Redis 缓存，偏好从实战切入", null, null)
        ));
        when(aiEpisodicMemoryRetrieveService.retrieveForPrompt(eq(100L), any(), any()))
                .thenReturn(List.of(
                        new EpisodicMemoryRagContext(2L, 100L, "blog-system", "EPISODIC", "情景",
                                "上周用户卡在缓存击穿，希望拆小任务", null, null, null)
                ));

        String observation = executor.execute(
                AgentStepDecision.of(AgentStepActionType.QUERY_MEMORY)
                        .withInput(Map.of("question", "用户最近情况")),
                100L,
                null
        ).summary();

        assertThat(observation).contains("记忆摘要");
        assertThat(observation).contains("语义记忆(USER_PREFERENCE)");
        assertThat(observation).contains("用户正在学 Redis 缓存");
        assertThat(observation).contains("情景记忆(EPISODIC)");
        assertThat(observation).contains("上周用户卡在缓存击穿");
    }

    @Test
    void memoryWithoutQueryDefaultsToGenericQuery() {
        when(aiMemoryRetrieveService.retrieve(eq(100L), any())).thenReturn(List.of());
        when(aiEpisodicMemoryRetrieveService.retrieveForPrompt(eq(100L), any(), any()))
                .thenReturn(List.of());

        String observation = executor.execute(
                AgentStepDecision.of(AgentStepActionType.QUERY_MEMORY),
                100L,
                null
        ).summary();

        // 无检索词时默认「用户情况」召回，不炸 Loop
        assertThat(observation).contains("记忆摘要");
        verify(aiMemoryRetrieveService).retrieve(eq(100L), eq("用户情况"));
    }

    /**
     * 2026-10-16：检索词优先用 run 原始目标（Runtime 经 prepareStepDecision 注入的 userGoal），
     * 而不是 LLM 改写的 question。
     *
     * <p>实测（线上同一 embedding 模型 + ES knn）：同一个问题上原话 cosine=0.6241、
     * LLM 改写=0.5607——改写丢掉的信息比口语噪声更伤召回。
     */
    @Test
    void memoryQueryPrefersRunGoalOverLlmRewrite() {
        when(aiMemoryRetrieveService.retrieve(eq(100L), any())).thenReturn(List.of());
        when(aiEpisodicMemoryRetrieveService.retrieveForPrompt(eq(100L), any(), any()))
                .thenReturn(List.of());

        String runGoal = "还记得我们之前决定的，RAG使用什么方法吗，为什么使用这个方法";
        executor.execute(
                AgentStepDecision.of(AgentStepActionType.QUERY_MEMORY)
                        .withInput(Map.of(
                                "question", "之前决定的RAG使用方法及原因",
                                "userGoal", runGoal)),
                100L,
                null
        );

        verify(aiMemoryRetrieveService).retrieve(eq(100L), eq(runGoal));
        verify(aiEpisodicMemoryRetrieveService).retrieveForPrompt(eq(100L), any(), eq(runGoal));
    }

    /** 没有 run 目标时（直连执行器 / 非 Runtime 调用）回退到 LLM 改写词。 */
    @Test
    void memoryQueryFallsBackToLlmQuestionWithoutRunGoal() {
        when(aiMemoryRetrieveService.retrieve(eq(100L), any())).thenReturn(List.of());
        when(aiEpisodicMemoryRetrieveService.retrieveForPrompt(eq(100L), any(), any()))
                .thenReturn(List.of());

        executor.execute(
                AgentStepDecision.of(AgentStepActionType.QUERY_MEMORY)
                        .withInput(Map.of("question", "用户最近情况")),
                100L,
                null
        );

        verify(aiMemoryRetrieveService).retrieve(eq(100L), eq("用户最近情况"));
    }

    /**
     * 常驻记忆通道：向量召回全空时也必须带出用户画像/偏好。
     *
     * 2026-10-13 线上回归——记忆向量仍留在旧 embedding 空间，与当前 query 向量近似正交
     * （实测 cos≈0.0125），纯向量召回恒空，"你说说你所了解的我"只能答"我还没有关于你的
     * 个人记忆记录"。常驻通道不依赖相似度，是这条链路的兜底。
     */
    @Test
    void alwaysOnMemoriesSurviveEmptyVectorRecall() {
        when(aiMemoryRetrieveService.retrieve(eq(100L), any())).thenReturn(List.of());
        when(aiEpisodicMemoryRetrieveService.retrieveForPrompt(eq(100L), any(), any()))
                .thenReturn(List.of());
        when(aiUserMemoryService.listPromptMemories(eq(100L))).thenReturn(List.of(
                alwaysMemory(1L, "PROFILE", "2028届计算机本科学生"),
                alwaysMemory(2L, "PREFERENCE", "回答直接一点，不要铺垫")
        ));

        String observation = executor.execute(
                AgentStepDecision.of(AgentStepActionType.QUERY_MEMORY)
                        .withInput(Map.of("question", "用户背景信息")),
                100L,
                null
        ).summary();

        assertThat(observation).contains("常驻记忆(PROFILE)");
        assertThat(observation).contains("2028届计算机本科学生");
        assertThat(observation).contains("常驻记忆(PREFERENCE)");
        assertThat(observation).contains("回答直接一点，不要铺垫");
    }

    private static AiUserMemories alwaysMemory(Long id, String memoryType, String content) {
        AiUserMemories memory = new AiUserMemories();
        memory.setId(id);
        memory.setMemoryType(memoryType);
        memory.setContent(content);
        return memory;
    }

    @Test
    void memoryRetrievalFailureStillReturnsSummary() {
        when(aiMemoryRetrieveService.retrieve(eq(100L), any()))
                .thenThrow(new RuntimeException("ES 连接失败"));
        when(aiEpisodicMemoryRetrieveService.retrieveForPrompt(eq(100L), any(), any()))
                .thenThrow(new RuntimeException("ES 连接失败"));

        String observation = executor.execute(
                AgentStepDecision.of(AgentStepActionType.QUERY_MEMORY)
                        .withInput(Map.of("question", "用户最近情况")),
                100L,
                null
        ).summary();

        assertThat(observation).contains("语义记忆检索失败");
        assertThat(observation).contains("情景记忆检索失败");
    }

    @Test
    void ragReturnsContextsFormatted() {
        when(articleRagSearchService.search(any(), any()))
                .thenReturn(new ArticleRagSearchResult(
                        ArticleRagSearchStrategy.HYBRID,
                        List.of(new ArticleRagContext(1L, "Redis缓存设计", 0,
                                "缓存穿透的防护思路是互斥锁加逻辑过期"))
                ));

        String observation = executor.execute(
                AgentStepDecision.of(AgentStepActionType.SEARCH_RAG)
                        .withInput(Map.of("keyword", "缓存穿透")),
                100L,
                null
        ).summary();

        assertThat(observation).contains("站内文章知识检索结果");
        assertThat(observation).contains("《Redis缓存设计》");
        assertThat(observation).contains("缓存穿透的防护思路");
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

        String observation = executor.execute(
                AgentStepDecision.of(AgentStepActionType.SEARCH_RAG)
                        .withInput(Map.of("keyword", "Redis 缓存击穿")),
                100L,
                null
        ).summary();

        assertThat(observation).contains("没有检索到");
    }

    @Test
    void unauthenticatedReturnsHint() {
        String observation = executor.execute(
                AgentStepDecision.of(AgentStepActionType.QUERY_MEMORY),
                null,
                null
        ).summary();

        assertThat(observation).contains("未登录");
    }

    @Test
    void domainActionMustNotReachGeneralExecutor() {
        assertThatThrownBy(() -> executor.execute(
                AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE),
                100L,
                new PageContextDTO()
        )).isInstanceOf(UnsupportedOperationException.class);
    }
}
