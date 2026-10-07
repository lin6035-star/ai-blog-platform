package com.hailin.blogsystem;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.ai.agent.AgentActionResult;
import com.hailin.blogsystem.ai.agent.AgentStepDecision;
import com.hailin.blogsystem.ai.agent.AgentStepEmitter;
import com.hailin.blogsystem.ai.agent.ArticleAgentActionExecutor;
import com.hailin.blogsystem.ai.TokenUsageAccumulator;
import com.hailin.blogsystem.ai.agent.ArticleAnswerGenerator;
import com.hailin.blogsystem.ai.agent.ArticleAgentRuntime;
import com.hailin.blogsystem.ai.agent.ArticleAgentStepDecider;
import com.hailin.blogsystem.ai.agent.ArticleAnalysisStepDecider;
import com.hailin.blogsystem.ai.agent.ArticleEvidenceVerifier;
import com.hailin.blogsystem.ai.agent.ArticleSessionAnchorService;
import com.hailin.blogsystem.ai.agent.GeneratedAnswer;
import com.hailin.blogsystem.ai.agent.Verdict;
import com.hailin.blogsystem.entity.AiAgentRun;
import com.hailin.blogsystem.entity.AiAgentStep;
import com.hailin.blogsystem.entity.dto.AgentStepActionType;
import com.hailin.blogsystem.entity.dto.PageContextDTO;
import com.hailin.blogsystem.mapper.AiAgentRunMapper;
import com.hailin.blogsystem.mapper.AiAgentStepMapper;
import com.hailin.blogsystem.service.ArticlesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 断言 Decider **实际收到的决策上下文**（Token 设计稿 P2.3 修订）。
 *
 * <p><b>为什么单独一个类</b>：现有 Runtime 测试只预设"下一步决策返回什么"
 * （`when(decider.decide(...)).thenReturn(下一个动作)`），从不检查"模型看到了什么"。
 * 反馈断链——Verifier 的 NEED_MORE、重复查询拦截、Workflow / 写提案拒绝原先**只写进 observations**，
 * 而结构化路径下 Decider 只读 {@code decisionContext()}——正是从这个盲区漏过去的：
 * 18 例状态测试全绿、37 例 Runtime 测试全绿，模型却看不到"刚才为什么被拒"。
 *
 * <p>本类把"上下文里必须有什么"变成断言，堵住这一类回归。
 */
class ArticleAgentRuntimeDecisionContextTests {

    private ArticleAgentStepDecider decider;
    private ArticleAnalysisStepDecider analysisDecider;
    private ArticleAgentActionExecutor executor;
    private AiAgentRunMapper runMapper;
    private AiAgentStepMapper stepMapper;
    private ArticlesService articlesService;
    private ArticleSessionAnchorService anchorService;
    private ArticleEvidenceVerifier verifier;
    private ArticleAnswerGenerator answerGenerator;
    private ArticleAgentRuntime runtime;

    @BeforeEach
    void setUp() {
        decider = mock(ArticleAgentStepDecider.class);
        analysisDecider = mock(ArticleAnalysisStepDecider.class);
        executor = mock(ArticleAgentActionExecutor.class);
        runMapper = mock(AiAgentRunMapper.class);
        stepMapper = mock(AiAgentStepMapper.class);
        articlesService = mock(ArticlesService.class);
        anchorService = mock(ArticleSessionAnchorService.class);
        verifier = mock(ArticleEvidenceVerifier.class);
        answerGenerator = mock(ArticleAnswerGenerator.class);

        when(runMapper.insert(any(AiAgentRun.class))).thenAnswer(inv -> {
            AiAgentRun run = inv.getArgument(0);
            run.setId(1L);
            return 1;
        });
        when(answerGenerator.generate(any(), any(), any(), any())).thenAnswer(inv -> {
            AgentStepDecision decision = inv.getArgument(1);
            Object text = decision.input().getOrDefault("answerPlan",
                    decision.input().getOrDefault("answer", decision.input().getOrDefault("message", "")));
            return new GeneratedAnswer(String.valueOf(text), new TokenUsageAccumulator(), false);
        });

        runtime = new ArticleAgentRuntime(
                decider, analysisDecider, executor, runMapper, stepMapper, new ObjectMapper(),
                articlesService, anchorService, verifier, answerGenerator);
    }

    @Test
    @DisplayName("P2.3 修订：Verifier 的拒绝原因必须进入下一轮决策上下文")
    void verifierRejectionReachesNextDecisionContext() {
        when(decider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE)
                        .withInput(Map.of("articleId", "12")))
                .thenReturn(finalAnswer("草案 A"))
                .thenReturn(finalAnswer("草案 B"));
        when(executor.execute(any(), any(), any()))
                .thenReturn(articleReadResult());
        when(verifier.verify(any(), any(), any(), any(), any()))
                .thenReturn(Verdict.needMore(Verdict.MissingEvidence.ARTICLE_CONTENT, "缺正文细节"))
                .thenReturn(null);

        runtime.run(100L, 200L, "分析这篇文章", articleContext("12"), AgentStepEmitter.noop());

        String afterReject = capturedContext(3, 2);
        assertThat(afterReject)
                .as("被拒原因必须出现在下一轮上下文——否则模型只会重复同样的回答直到步数耗尽")
                .contains("回答依据不足")
                .contains("缺正文细节");
    }

    @Test
    @DisplayName("P2.3 修订：Verifier 不能读取自己上一轮的拒绝反馈")
    void verifierDoesNotConsumePreviousVerifierFeedback() {
        when(decider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE)
                        .withInput(Map.of("articleId", "12")))
                .thenReturn(finalAnswer("草案 A"))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE)
                        .withInput(Map.of("articleId", "12", "focus", "缓存击穿")))
                .thenReturn(finalAnswer("草案 B"));
        when(executor.execute(any(), any(), any()))
                .thenReturn(articleReadResult())
                .thenReturn(articleReadResult("聚焦片段：互斥锁兜底", "缓存击穿"));
        when(verifier.verify(any(), any(), any(), any(), any()))
                .thenReturn(Verdict.needMore(Verdict.MissingEvidence.ARTICLE_CONTENT, "缺正文细节"))
                .thenReturn(Verdict.sufficient());

        runtime.run(100L, 200L, "分析这篇文章", articleContext("12"), AgentStepEmitter.noop());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> evidence = ArgumentCaptor.forClass(List.class);
        verify(verifier, times(2)).verify(any(), any(), evidence.capture(), any(), any());
        String secondVerifierContext = String.join("\n", evidence.getAllValues().get(1));
        assertThat(secondVerifierContext)
                .as("上一轮 Verifier 的判断不能反向污染下一轮证据判断")
                .doesNotContain("回答依据不足")
                .doesNotContain("缺正文细节");
    }

    @Test
    @DisplayName("P2.3 修订：重复查询拦截提示必须进入下一轮决策上下文")
    void duplicateQueryHintReachesNextDecisionContext() {
        // 第一次查询（真正执行那一步）查不到历史 → null；第二次（同参重复）查到第 1 步 → 拦截。
        // 一律返回 previous 会让**第 1 步**就被误判成重复，状态全空，测试也就失去意义。
        AiAgentStep previous = new AiAgentStep();
        previous.setStepNo(1);
        when(stepMapper.selectOne(any())).thenReturn(null, previous);

        when(decider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE)
                        .withInput(Map.of("articleId", "12")))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE)
                        .withInput(Map.of("articleId", "12")))
                .thenReturn(finalAnswer("最终答案"));
        when(executor.execute(any(), any(), any()))
                .thenReturn(articleReadResult());

        runtime.run(100L, 200L, "分析这篇文章", articleContext("12"), AgentStepEmitter.noop());

        String afterReject = capturedContext(3, 2);
        assertThat(afterReject)
                .as("重复查询提示必须出现在下一轮上下文——它是模型换角度的唯一依据")
                .contains("重复了第 1 步");
    }

    /** 取第 {@code index} 次决策（0 基）实际收到的上下文 */
    private String capturedContext(int expectedCalls, int index) {
        ArgumentCaptor<String> contexts = ArgumentCaptor.forClass(String.class);
        verify(decider, times(expectedCalls)).decide(any(), contexts.capture(), anyInt(), anyInt(), any());
        return contexts.getAllValues().get(index);
    }

    /** 同上，但不约束总调用次数（有的场景拒绝后还会再决策一次才终局） */
    private String capturedContextAt(int index) {
        ArgumentCaptor<String> contexts = ArgumentCaptor.forClass(String.class);
        verify(decider, atLeast(index + 1)).decide(any(), contexts.capture(), anyInt(), anyInt(), any());
        return contexts.getAllValues().get(index);
    }

    @Test
    @DisplayName("P2.3 修订：文章写提案被拒的提示也要进上下文（领域旁路，不走公共三处）")
    void suggestWriteRejectionReachesNextDecisionContext() {
        when(decider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.SUGGEST_WRITE)
                        .withInput(Map.of("actionType", "UPDATE_ARTICLE_TITLE", "newTitle", "新标题")))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE)
                        .withInput(Map.of("articleId", "12")))
                .thenReturn(finalAnswer("已完成查询，可以继续判断"));
        when(executor.execute(any(), any(), any())).thenReturn(articleReadResult());
        when(verifier.verify(any(), any(), any(), any(), any())).thenReturn(null);

        runtime.run(100L, 200L, "把标题改成新标题", articleContext("12"), AgentStepEmitter.noop());

        assertThat(capturedContextAt(1))
                .as("零观察写提案被拒的提示必须让模型看到——否则它会反复提同一个注定被拒的提案")
                .contains("后端已拒绝");
        assertThat(capturedContextAt(2))
                .as("完成只读查询后，临时前置反馈必须退出上下文")
                .doesNotContain("后端已拒绝")
                .doesNotContain("没有任何查询结果");
    }

    @Test
    @DisplayName("证据没有增量时第二次 FINAL_ANSWER 在 Verifier 前收敛")
    void repeatedEvidenceStopsBeforeSecondVerifier() {
        when(decider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE)
                        .withInput(Map.of("articleId", "12")))
                .thenReturn(finalAnswer("草案 A"))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE)
                        .withInput(Map.of("articleId", "12")))
                .thenReturn(finalAnswer("草案 B"));
        // 两次读文章返回**逐字相同**的内容 → 同键同内容 = 没有新证据
        when(executor.execute(any(), any(), any())).thenReturn(articleReadResult());
        when(verifier.verify(any(), any(), any(), any(), any()))
                .thenReturn(Verdict.needMore(Verdict.MissingEvidence.ARTICLE_CONTENT, "缺正文细节"))
                .thenReturn(null);

        runtime.run(100L, 200L, "分析这篇文章", articleContext("12"), AgentStepEmitter.noop());

        ArgumentCaptor<Boolean> flags = ArgumentCaptor.forClass(Boolean.class);
        verify(verifier, times(1)).verify(any(), any(), any(), any(), flags.capture());
        assertThat(flags.getValue()).as("第一次读文章是新证据").isTrue();

        ArgumentCaptor<AiAgentStep> steps = ArgumentCaptor.forClass(AiAgentStep.class);
        verify(stepMapper, atLeastOnce()).insert(steps.capture());
        assertThat(steps.getAllValues().get(steps.getAllValues().size() - 1).getStatus())
                .as("重复证据后的终稿不再送 Verifier，而是审计为 SKIPPED")
                .isEqualTo("SKIPPED");
    }

    @Test
    @DisplayName("P2.3 修订：analysis-only 真实入口用的是 analysisDecider（编排层走的就是它）")
    void analysisOnlyEntryUsesAnalysisDecider() {
        when(analysisDecider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(finalAnswer("分析答案"));
        when(verifier.verify(any(), any(), any(), any(), any())).thenReturn(null);

        runtime.runAnalysisOnly(100L, 200L, "分析一下这篇文章", articleContext("12"),
                AgentStepEmitter.noop(), 999L);

        verify(analysisDecider, atLeastOnce()).decide(any(), any(), anyInt(), anyInt(), any());
        verify(decider, never()).decide(any(), any(), anyInt(), anyInt(), any());
    }

    private AgentStepDecision finalAnswer(String answer) {
        return AgentStepDecision.of(AgentStepActionType.FINAL_ANSWER)
                .withInput(Map.of("answerPlan", answer));
    }

    /** 带结构化事实的读文章结果——结构化路径要靠它建状态 */
    private AgentActionResult articleReadResult() {
        return articleReadResult("结构摘要：10 节 / 1.3 万字", null);
    }

    private AgentActionResult articleReadResult(String summary, String focus) {
        return AgentActionResult.of(summary)
                .with("articleId", "12")
                .with("articleTitle", "Redis 缓存设计")
                .with("owned", true)
                .with("focus", focus)
                .with("focusHit", focus == null ? null : true);
    }

    private static PageContextDTO articleContext(String articleId) {
        PageContextDTO pageContext = new PageContextDTO();
        pageContext.setPageType("article-detail");
        pageContext.setArticleId(articleId);
        return pageContext;
    }
}
