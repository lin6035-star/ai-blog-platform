package com.hailin.blogsystem;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hailin.blogsystem.ai.TokenUsageAccumulator;
import com.hailin.blogsystem.ai.agent.ArticleInteractionBatchService;
import com.hailin.blogsystem.ai.billing.ChatReserveCalculator;
import com.hailin.blogsystem.ai.planner.AgentPlannerSupport;
import com.hailin.blogsystem.entity.AiChatEventType;
import com.hailin.blogsystem.entity.AiMessages;
import com.hailin.blogsystem.entity.AiPrompt;
import com.hailin.blogsystem.entity.AiSessions;
import com.hailin.blogsystem.entity.dto.AgentAction;
import com.hailin.blogsystem.entity.dto.AgentDecision;
import com.hailin.blogsystem.entity.dto.AiChatDTO;
import com.hailin.blogsystem.entity.dto.AiIntent;
import com.hailin.blogsystem.entity.dto.ArticleActionBatch;
import com.hailin.blogsystem.entity.dto.ArticleInteractionAction;
import com.hailin.blogsystem.entity.dto.ArticleInteractionResult;
import com.hailin.blogsystem.entity.dto.ArticleInteractionResultCode;
import com.hailin.blogsystem.entity.dto.PageContextDTO;
import com.hailin.blogsystem.entity.vo.AiChatEventVO;
import com.hailin.blogsystem.entity.vo.AiMessageVO;
import com.hailin.blogsystem.mapper.AiMessageMapper;
import com.hailin.blogsystem.mapper.AiSessionMapper;
import com.hailin.blogsystem.service.AiBillingService;
import com.hailin.blogsystem.service.AiConversationSummaryService;
import com.hailin.blogsystem.service.AiEpisodicMemoryExtractorService;
import com.hailin.blogsystem.service.AiIntentClassifier;
import com.hailin.blogsystem.service.AiMemoryCandidateExtractorService;
import com.hailin.blogsystem.service.AiMessageService;
import com.hailin.blogsystem.service.AiModelService;
import com.hailin.blogsystem.service.AiPromptService;
import com.hailin.blogsystem.utils.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.ai.chat.metadata.Usage;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
class ArticleInteractionStreamTests {

    private static final Long USER_ID = 9_991_001L;
    private static final Long SESSION_ID = 9_991_002L;
    private static final Long ARTICLE_ID = 9_991_003L;
    private static final Long AGENT_RUN_ID = 9_991_004L;
    private static final String MESSAGE = "帮我给这篇文章点赞并收藏";
    private static final String TASK_CTA_MESSAGE = "给这篇文章生成一个视频";

    @Autowired
    private AiMessageService aiMessageService;

    @Autowired
    private AiSessionMapper sessionMapper;

    @Autowired
    private AiMessageMapper messageMapper;

    @MockBean
    private AiIntentClassifier intentClassifier;

    @MockBean
    private AgentPlannerSupport planner;

    @MockBean
    private ArticleInteractionBatchService batchService;

    @MockBean
    private AiModelService modelService;

    @MockBean
    private AiPromptService promptService;

    @MockBean
    private AiBillingService billingService;

    @MockBean
    private ChatReserveCalculator reserveCalculator;

    @MockBean
    private AiMemoryCandidateExtractorService memoryExtractor;

    @MockBean
    private AiEpisodicMemoryExtractorService episodicMemoryExtractor;

    @MockBean
    private AiConversationSummaryService summaryService;

    private ArticleActionBatch batch;
    private List<ArticleInteractionResult> actionResults;

    @BeforeEach
    void setUp() {
        UserContext.set(USER_ID);
        AiSessions session = new AiSessions();
        session.setId(SESSION_ID);
        session.setUserId(USER_ID);
        session.setTitle("article interaction stream");
        session.setCreatedAt(LocalDateTime.now());
        session.setUpdatedAt(LocalDateTime.now());
        sessionMapper.insert(session);

        batch = new ArticleActionBatch(ARTICLE_ID, List.of(
                ArticleInteractionAction.LIKE_ARTICLE,
                ArticleInteractionAction.FAVORITE_ARTICLE));
        actionResults = List.of(
                ArticleInteractionResult.success(
                        ArticleInteractionAction.LIKE_ARTICLE,
                        ArticleInteractionResultCode.APPLIED,
                        "点赞成功",
                        false,
                        true),
                ArticleInteractionResult.success(
                        ArticleInteractionAction.FAVORITE_ARTICLE,
                        ArticleInteractionResultCode.APPLIED,
                        "收藏成功",
                        false,
                        true));

        AiIntent intent = new AiIntent();
        intent.setIntent("ARTICLE_ACTION");
        intent.setConfidence(1.0);
        intent.setRequestedActions(List.of("likeArticle", "favoriteArticle"));
        when(intentClassifier.classify(eq(MESSAGE), any(PageContextDTO.class), eq(USER_ID),
                any(TokenUsageAccumulator.class))).thenReturn(intent);
        when(planner.decide(eq(MESSAGE), eq(intent), any(PageContextDTO.class), eq(USER_ID), any(AiSessions.class)))
                .thenReturn(AgentDecision.builder()
                        .action(AgentAction.CHAT)
                        .intent("ARTICLE_ACTION")
                        .articleActionBatch(batch)
                        .retrievalMode("NONE")
                        .ruleHits(List.of("article_action_batch_valid"))
                        .build());
        when(batchService.execute(USER_ID, SESSION_ID, MESSAGE, batch))
                .thenReturn(new ArticleInteractionBatchService.Execution(
                        AGENT_RUN_ID,
                        "已完成：点赞成功；收藏成功。",
                        actionResults));

        // RED 阶段旧路径会落入普通聊天，给它足够的桩以便测试失败在契约断言而不是空指针。
        when(promptService.buildPrompt(eq(MESSAGE), any(PageContextDTO.class), eq(SESSION_ID)))
                .thenReturn(AiPrompt.builder().finalPromptContext(MESSAGE).userMessage(MESSAGE).build());
        when(reserveCalculator.estimate(anyString())).thenReturn(100L);
        when(modelService.streamChat(any(AiPrompt.class), anyString(), any(TokenUsageAccumulator.class)))
                .thenReturn(Flux.just("模型自由生成的回答"));
    }

    @AfterEach
    void cleanup() {
        UserContext.clear();
        messageMapper.delete(new LambdaQueryWrapper<AiMessages>()
                .eq(AiMessages::getSessionId, SESSION_ID));
        sessionMapper.deleteById(SESSION_ID);
    }

    @Test
    @SuppressWarnings("unchecked")
    void streamsAuthoritativeBatchResultsWithoutCallingTheAnswerModel() {
        AiChatDTO request = new AiChatDTO();
        request.setSessionId(String.valueOf(SESSION_ID));
        request.setMessage(MESSAGE);
        PageContextDTO pageContext = new PageContextDTO();
        pageContext.setPageType("article-detail");
        pageContext.setArticleId(String.valueOf(ARTICLE_ID));
        request.setPageContext(pageContext);

        List<AiChatEventVO> events = aiMessageService.streamChat(request).collectList().block();

        assertThat(events).isNotNull();
        assertThat(events).extracting(AiChatEventVO::getEventType)
                .containsExactly(
                        AiChatEventType.PARAM.getValue(),
                        AiChatEventType.DATA.getValue(),
                        AiChatEventType.STOP.getValue());

        Map<String, Object> stop = (Map<String, Object>) events.get(2).getEventData();
        assertThat(stop).containsKey("actionResults");
        assertThat((List<ArticleInteractionResult>) stop.get("actionResults"))
                .containsExactlyElementsOf(actionResults);
        assertThat(stop).doesNotContainKey("articleAction");
        assertThat(((AiMessageVO) stop.get("assistantMessage")).getAgentRunId())
                .isEqualTo(String.valueOf(AGENT_RUN_ID));

        List<AiMessages> messages = messageMapper.selectList(
                new LambdaQueryWrapper<AiMessages>()
                        .eq(AiMessages::getSessionId, SESSION_ID)
                        .orderByAsc(AiMessages::getCreatedAt));
        assertThat(messages).hasSize(2);
        assertThat(messages.get(1).getAgentRunId()).isEqualTo(AGENT_RUN_ID);
        verify(batchService).execute(USER_ID, SESSION_ID, MESSAGE, batch);
        verify(modelService, never()).streamChat(any(), anyString(), any());
    }

    @Test
    void taskCtaPersistsClassifierTokens() {
        AiIntent intent = new AiIntent();
        intent.setIntent("TASK");
        intent.setRequestKind("TASK");
        intent.setConfidence(1.0);
        intent.setTaskGoals(List.of(Map.of(
                "type", "VIDEO_EDIT",
                "params", Map.of(),
                "goalEvidence", List.of("生成一个视频"))));
        Usage usage = org.mockito.Mockito.mock(Usage.class);
        when(usage.getPromptTokens()).thenReturn(100);
        when(usage.getCompletionTokens()).thenReturn(23);
        when(usage.getTotalTokens()).thenReturn(123);
        when(intentClassifier.classify(eq(TASK_CTA_MESSAGE), any(PageContextDTO.class), eq(USER_ID),
                any(TokenUsageAccumulator.class))).thenAnswer(invocation -> {
                    TokenUsageAccumulator accumulator = invocation.getArgument(3);
                    accumulator.add(usage);
                    return intent;
                });
        when(planner.decide(eq(TASK_CTA_MESSAGE), eq(intent), any(PageContextDTO.class),
                eq(USER_ID), any(AiSessions.class)))
                .thenReturn(AgentDecision.builder()
                        .action(AgentAction.CHAT)
                        .intent("TASK")
                        .retrievalMode("NONE")
                        .ruleHits(List.of())
                        .build());

        AiChatDTO request = new AiChatDTO();
        request.setSessionId(String.valueOf(SESSION_ID));
        request.setMessage(TASK_CTA_MESSAGE);
        request.setPageContext(new PageContextDTO());
        aiMessageService.streamChat(request).collectList().block();

        AiMessages assistant = messageMapper.selectOne(new LambdaQueryWrapper<AiMessages>()
                .eq(AiMessages::getSessionId, SESSION_ID)
                .eq(AiMessages::getRole, "assistant")
                .orderByDesc(AiMessages::getId)
                .last("limit 1"));
        assertThat(assistant.getTokenCount()).isEqualTo(123L);
    }
}
