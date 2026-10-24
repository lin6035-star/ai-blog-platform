package com.hailin.blogsystem;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hailin.blogsystem.ai.TokenUsageAccumulator;
import com.hailin.blogsystem.ai.agent.AgentAnswerStreamException;
import com.hailin.blogsystem.ai.agent.AgentCompletionKind;
import com.hailin.blogsystem.ai.agent.AgentRunResult;
import com.hailin.blogsystem.ai.agent.AgentStepEmitter;
import com.hailin.blogsystem.ai.agent.ArticleAgentRuntime;
import com.hailin.blogsystem.ai.planner.AgentPlannerSupport;
import com.hailin.blogsystem.ai.task.AiOrchestrationTaskAdmission;
import com.hailin.blogsystem.ai.task.plan.TaskPlanningFacade;
import com.hailin.blogsystem.ai.task.plan.TaskRoute;
import com.hailin.blogsystem.ai.trace.AgentDecisionTraceSink;
import com.hailin.blogsystem.entity.AiChatEventType;
import com.hailin.blogsystem.entity.AiMessages;
import com.hailin.blogsystem.entity.AiSessions;
import com.hailin.blogsystem.entity.dto.AgentAction;
import com.hailin.blogsystem.entity.dto.AgentDecision;
import com.hailin.blogsystem.entity.dto.AiAgentRunStatus;
import com.hailin.blogsystem.entity.dto.AiChatDTO;
import com.hailin.blogsystem.entity.dto.AiIntent;
import com.hailin.blogsystem.entity.dto.PageContextDTO;
import com.hailin.blogsystem.entity.vo.AiChatEventVO;
import com.hailin.blogsystem.mapper.AiMessageMapper;
import com.hailin.blogsystem.mapper.AiSessionMapper;
import com.hailin.blogsystem.service.AiBillingService;
import com.hailin.blogsystem.service.AiConversationSummaryService;
import com.hailin.blogsystem.service.AiEpisodicMemoryExtractorService;
import com.hailin.blogsystem.service.AiIntentClassifier;
import com.hailin.blogsystem.service.AiMemoryCandidateExtractorService;
import com.hailin.blogsystem.service.AiMessageService;
import com.hailin.blogsystem.utils.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
class ArticleAgentDirectStreamTests {

    private static final Long USER_ID = 9_992_001L;
    private static final Long SESSION_ID = 9_992_002L;
    private static final Long AGENT_RUN_ID = 9_992_003L;
    private static final String MESSAGE = "分析一下这篇文章";

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
    private TaskPlanningFacade taskPlanningFacade;

    @MockBean
    private ArticleAgentRuntime articleAgentRuntime;

    @MockBean
    private AiOrchestrationTaskAdmission admission;

    @MockBean
    private AgentDecisionTraceSink decisionTraceSink;

    @MockBean
    private AiMemoryCandidateExtractorService memoryExtractor;

    @MockBean
    private AiEpisodicMemoryExtractorService episodicMemoryExtractor;

    @MockBean
    private AiConversationSummaryService summaryService;

    /**
     * B3 起 Agent 路径也要预扣：本类测的是流式交付与失败语义，不关心计费，
     * 所以把计费 mock 掉（mock 的 reserve 返回 null → 整条计费链路空转），
     * 否则测试用户没有钱包余额，预扣会抛 InsufficientBalanceException 把流程引到失败分支。
     */
    @MockBean
    private AiBillingService billingService;

    @BeforeEach
    void setUp() {
        UserContext.set(USER_ID);
        AiSessions session = new AiSessions();
        session.setId(SESSION_ID);
        session.setUserId(USER_ID);
        session.setTitle("article agent direct stream");
        session.setCreatedAt(LocalDateTime.now());
        session.setUpdatedAt(LocalDateTime.now());
        sessionMapper.insert(session);

        AiIntent intent = new AiIntent();
        intent.setIntent("ARTICLE_AGENT");
        intent.setConfidence(1.0);
        when(intentClassifier.classify(eq(MESSAGE), any(PageContextDTO.class), eq(USER_ID),
                any(TokenUsageAccumulator.class))).thenReturn(intent);
        when(planner.decide(eq(MESSAGE), eq(intent), any(PageContextDTO.class),
                eq(USER_ID), any(AiSessions.class))).thenReturn(AgentDecision.builder()
                        .action(AgentAction.AGENT)
                        .intent("ARTICLE_AGENT")
                        .retrievalMode("NONE")
                        .ruleHits(List.of("article_agent_intent"))
                        .build());
        when(taskPlanningFacade.decide(MESSAGE, intent, USER_ID))
                .thenReturn(TaskRoute.legacy("article agent direct stream test"));
        doAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return null;
        }).when(admission).submit(any(), any(Runnable.class));
    }

    @AfterEach
    void cleanup() {
        UserContext.clear();
        messageMapper.delete(new LambdaQueryWrapper<AiMessages>()
                .eq(AiMessages::getSessionId, SESSION_ID));
        sessionMapper.deleteById(SESSION_ID);
    }

    @Test
    void streamsGeneratedChunksExactlyOnceWithoutCompletedAnswerReplay() {
        when(articleAgentRuntime.run(eq(USER_ID), eq(SESSION_ID), eq(MESSAGE),
                any(PageContextDTO.class), any(AgentStepEmitter.class))).thenAnswer(invocation -> {
                    AgentStepEmitter emitter = invocation.getArgument(4);
                    assertThat(emitter.emitAnswerChunk("甲")).isTrue();
                    assertThat(emitter.emitAnswerChunk("乙")).isTrue();
                    return AgentRunResult.of(AGENT_RUN_ID, AiAgentRunStatus.COMPLETED,
                            "甲乙", 1, 20, AgentCompletionKind.ANSWER);
                });

        List<AiChatEventVO> events = aiMessageService.streamChat(request()).collectList().block();

        assertThat(events).isNotNull();
        List<String> chunks = events.stream()
                .filter(event -> AiChatEventType.DATA.getValue().equals(event.getEventType()))
                .map(event -> String.valueOf(event.getEventData()))
                .toList();
        assertThat(chunks).containsExactly("甲", "乙");
        assertThat(String.join("", chunks)).isEqualTo("甲乙");
        assertThat(events).extracting(AiChatEventVO::getEventType)
                .containsExactly(
                        AiChatEventType.PARAM.getValue(),
                        AiChatEventType.DATA.getValue(),
                        AiChatEventType.DATA.getValue(),
                        AiChatEventType.STOP.getValue());
    }

    @Test
    void partialGenerationFailureDoesNotPersistAssistantOrEmitStop() {
        when(articleAgentRuntime.run(eq(USER_ID), eq(SESSION_ID), eq(MESSAGE),
                any(PageContextDTO.class), any(AgentStepEmitter.class))).thenAnswer(invocation -> {
                    AgentStepEmitter emitter = invocation.getArgument(4);
                    assertThat(emitter.emitAnswerChunk("甲")).isTrue();
                    throw new AgentAnswerStreamException(
                            "流中断", new RuntimeException("503"), new TokenUsageAccumulator(), true);
                });

        List<AiChatEventVO> events = new ArrayList<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        aiMessageService.streamChat(request()).subscribe(events::add, error::set);

        assertThat(error.get()).isInstanceOf(AgentAnswerStreamException.class);
        assertThat(events).extracting(AiChatEventVO::getEventType)
                .containsExactly(AiChatEventType.PARAM.getValue(), AiChatEventType.DATA.getValue());
        assertThat(events.get(1).getEventData()).isEqualTo("甲");

        List<AiMessages> messages = messageMapper.selectList(
                new LambdaQueryWrapper<AiMessages>()
                        .eq(AiMessages::getSessionId, SESSION_ID)
                        .orderByAsc(AiMessages::getCreatedAt));
        assertThat(messages).singleElement()
                .satisfies(message -> assertThat(message.getRole()).isEqualTo("user"));
        verify(memoryExtractor, never()).extractAfterChat(any(), any(), any(), any(), any());
        verify(episodicMemoryExtractor, never()).extractAfterChat(any(), any(), any(), any(), any(), any());
        verify(summaryService, never()).compressAfterChat(any(), any());
    }

    @Test
    void genericAgentFailurePersistsRouteTokensOnFailureAssistantMessage() {
        when(intentClassifier.classify(eq(MESSAGE), any(PageContextDTO.class), eq(USER_ID),
                any(TokenUsageAccumulator.class))).thenAnswer(invocation -> {
            TokenUsageAccumulator usage = invocation.getArgument(3);
            Usage routeUsage = org.mockito.Mockito.mock(Usage.class);
            when(routeUsage.getPromptTokens()).thenReturn(100);
            when(routeUsage.getCompletionTokens()).thenReturn(37);
            when(routeUsage.getTotalTokens()).thenReturn(137);
            usage.add(routeUsage);
            return intent();
        });
        when(articleAgentRuntime.run(eq(USER_ID), eq(SESSION_ID), eq(MESSAGE),
                any(PageContextDTO.class), any(AgentStepEmitter.class)))
                .thenThrow(new IllegalStateException("agent unavailable"));

        List<AiChatEventVO> events = aiMessageService.streamChat(request()).collectList().block();

        assertThat(events).extracting(AiChatEventVO::getEventType)
                .containsExactly(AiChatEventType.PARAM.getValue(), AiChatEventType.STOP.getValue());
        List<AiMessages> messages = messageMapper.selectList(
                new LambdaQueryWrapper<AiMessages>()
                        .eq(AiMessages::getSessionId, SESSION_ID)
                        .orderByAsc(AiMessages::getCreatedAt));
        assertThat(messages).hasSize(2);
        assertThat(messages).filteredOn(message -> "assistant".equals(message.getRole()))
                .singleElement()
                .satisfies(message -> assertThat(message.getTokenCount()).isEqualTo(137L));
    }

    private AiIntent intent() {
        AiIntent intent = new AiIntent();
        intent.setIntent("ARTICLE_AGENT");
        intent.setConfidence(1.0);
        return intent;
    }

    private AiChatDTO request() {
        AiChatDTO request = new AiChatDTO();
        request.setSessionId(String.valueOf(SESSION_ID));
        request.setMessage(MESSAGE);
        PageContextDTO pageContext = new PageContextDTO();
        pageContext.setPageType("article-detail");
        pageContext.setArticleId("12");
        request.setPageContext(pageContext);
        return request;
    }
}
