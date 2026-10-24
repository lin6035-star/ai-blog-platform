package com.hailin.blogsystem;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hailin.blogsystem.ai.TokenUsageAccumulator;
import com.hailin.blogsystem.ai.billing.ChatReserveCalculator;
import com.hailin.blogsystem.ai.planner.AgentPlannerSupport;
import com.hailin.blogsystem.ai.rag.ArticleRagSearchService;
import com.hailin.blogsystem.entity.AiChatEventType;
import com.hailin.blogsystem.entity.AiMessages;
import com.hailin.blogsystem.entity.AiPrompt;
import com.hailin.blogsystem.entity.AiSessions;
import com.hailin.blogsystem.entity.dto.AgentAction;
import com.hailin.blogsystem.entity.dto.AgentDecision;
import com.hailin.blogsystem.entity.dto.AiChatDTO;
import com.hailin.blogsystem.entity.dto.AiIntent;
import com.hailin.blogsystem.entity.dto.ArticleRagContext;
import com.hailin.blogsystem.entity.dto.ArticleRagSearchResult;
import com.hailin.blogsystem.entity.dto.ArticleRagSearchStrategy;
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
import com.hailin.blogsystem.service.AiModelService;
import com.hailin.blogsystem.service.AiPromptService;
import com.hailin.blogsystem.utils.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
class ArticleSearchQualityPathTests {

    private static final Long USER_ID = 9_993_001L;
    private static final Long SESSION_ID = 9_993_002L;
    private static final String MESSAGE = "有没有关于 Claude Code 的文章";
    private static final String DETAILED_REPLY = "找到了两篇相关文章。第一篇介绍即兴编程范式 [1]，"
            + "第二篇讨论 Agent 工程实践 [2]。";

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
    private ArticleRagSearchService articleRagSearchService;

    @MockBean
    private AiPromptService promptService;

    @MockBean
    private AiModelService modelService;

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

    private AiIntent intent;

    @BeforeEach
    void setUp() {
        UserContext.set(USER_ID);

        AiSessions session = new AiSessions();
        session.setId(SESSION_ID);
        session.setUserId(USER_ID);
        session.setTitle("article search quality path");
        session.setCreatedAt(LocalDateTime.now());
        session.setUpdatedAt(LocalDateTime.now());
        sessionMapper.insert(session);

        intent = new AiIntent();
        intent.setIntent("ARTICLE_SEARCH");
        intent.setKeyWord("Claude Code");
        intent.setConfidence(1.0);
        when(intentClassifier.classify(
                eq(MESSAGE), any(PageContextDTO.class), eq(USER_ID), any(TokenUsageAccumulator.class)))
                .thenReturn(intent);
        when(planner.decide(
                eq(MESSAGE), eq(intent), any(PageContextDTO.class), eq(USER_ID), any(AiSessions.class)))
                .thenReturn(AgentDecision.builder()
                        .action(AgentAction.CHAT)
                        .intent("ARTICLE_SEARCH")
                        .retrievalMode("ARTICLE_SEARCH")
                        .ruleHits(List.of("article_search"))
                        .build());

        when(promptService.buildPrompt(eq(MESSAGE), any(PageContextDTO.class), eq(SESSION_ID)))
                .thenReturn(AiPrompt.builder()
                        .userMessage(MESSAGE)
                        .finalPromptContext("BASE PROMPT")
                        .build());
        when(articleRagSearchService.search(MESSAGE, intent)).thenReturn(new ArticleRagSearchResult(
                ArticleRagSearchStrategy.HYBRID,
                List.of(
                        new ArticleRagContext(
                                301L,
                                "Claude Code 与 Vibe Coding",
                                0,
                                "正文：文章解释 Vibe Coding 的工作方式、优势和局限。"),
                        new ArticleRagContext(
                                302L,
                                "Agent 工程实践",
                                0,
                                "正文：文章讨论 Agent 的规划、工具调用和验证边界。")
                )));
        when(reserveCalculator.estimate(anyString())).thenReturn(100L);
        when(modelService.streamChat(any(AiPrompt.class), anyString(), any(TokenUsageAccumulator.class)))
                .thenReturn(Flux.just(DETAILED_REPLY));
    }

    @AfterEach
    void cleanup() {
        UserContext.clear();
        messageMapper.delete(new LambdaQueryWrapper<AiMessages>()
                .eq(AiMessages::getSessionId, SESSION_ID));
        sessionMapper.deleteById(SESSION_ID);
    }

    @Test
    void articleSearchSendsAllRetrievedChunksToAnswerModel() {
        AiChatDTO request = new AiChatDTO();
        request.setSessionId(String.valueOf(SESSION_ID));
        request.setMessage(MESSAGE);
        request.setPageContext(new PageContextDTO());

        List<AiChatEventVO> events = aiMessageService.streamChat(request).collectList().block();

        assertThat(dataContent(events)).isEqualTo(DETAILED_REPLY);
        ArgumentCaptor<AiPrompt> promptCaptor = ArgumentCaptor.forClass(AiPrompt.class);
        verify(modelService).streamChat(
                promptCaptor.capture(), anyString(), any(TokenUsageAccumulator.class));
        assertThat(promptCaptor.getValue().getFinalPromptContext())
                .contains("Claude Code 与 Vibe Coding")
                .contains("文章解释 Vibe Coding 的工作方式、优势和局限")
                .contains("Agent 工程实践")
                .contains("文章讨论 Agent 的规划、工具调用和验证边界");
        verify(promptService).buildPrompt(MESSAGE, request.getPageContext(), SESSION_ID);
        verify(articleRagSearchService).search(MESSAGE, intent);
    }

    private String dataContent(List<AiChatEventVO> events) {
        return events.stream()
                .filter(event -> AiChatEventType.DATA.getValue().equals(event.getEventType()))
                .map(event -> String.valueOf(event.getEventData()))
                .reduce("", String::concat);
    }
}
