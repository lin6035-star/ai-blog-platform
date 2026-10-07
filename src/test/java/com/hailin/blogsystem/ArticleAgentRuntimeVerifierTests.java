package com.hailin.blogsystem;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.ai.TokenUsageAccumulator;
import com.hailin.blogsystem.ai.agent.AgentActionResult;
import com.hailin.blogsystem.ai.agent.AgentAnswerStreamException;
import com.hailin.blogsystem.ai.agent.AgentCompletionKind;
import com.hailin.blogsystem.ai.agent.AgentRunResult;
import com.hailin.blogsystem.ai.agent.AgentStepDecision;
import com.hailin.blogsystem.ai.agent.AgentStepEmitter;
import com.hailin.blogsystem.ai.agent.ArticleAgentActionExecutor;
import com.hailin.blogsystem.ai.agent.ArticleAgentRuntime;
import com.hailin.blogsystem.ai.agent.ArticleAgentStepDecider;
import com.hailin.blogsystem.ai.agent.ArticleAnalysisStepDecider;
import com.hailin.blogsystem.ai.agent.ArticleAnswerGenerator;
import com.hailin.blogsystem.ai.agent.ArticleEvidenceVerifier;
import com.hailin.blogsystem.ai.agent.ArticleSessionAnchorService;
import com.hailin.blogsystem.ai.agent.GeneratedAnswer;
import com.hailin.blogsystem.ai.agent.Verdict;
import com.hailin.blogsystem.entity.AiAgentRun;
import com.hailin.blogsystem.entity.AiAgentStep;
import com.hailin.blogsystem.entity.dto.AiAgentRunStatus;
import com.hailin.blogsystem.entity.dto.AgentStepActionType;
import com.hailin.blogsystem.entity.dto.PageContextDTO;
import com.hailin.blogsystem.mapper.AiAgentRunMapper;
import com.hailin.blogsystem.mapper.AiAgentStepMapper;
import com.hailin.blogsystem.service.ArticlesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 文章域证据收敛门集成测试（V3.11）。
 * mock 决策器/执行器/验证器，验证骨架收敛门四条路径：
 * - NEED_MORE 拦截 → 循环补查 → SUFFICIENT 放行收尾（审计留痕 + 提示 observation）
 * - ASK_USER 转问 → WAITING_USER，只落一条 ASK_USER step，无伪造 FINAL_ANSWER step
 * - S1b：验证拒绝从未满足 + maxSteps 到顶 → 保守收尾（不走 summarize）
 * - S1：目标文章从没读过 + 到顶 → 保守收尾
 */
class ArticleAgentRuntimeVerifierTests {

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

    private static PageContextDTO articleContext(String articleId) {
        PageContextDTO pageContext = new PageContextDTO();
        pageContext.setPageType("article-detail");
        pageContext.setArticleId(articleId);
        return pageContext;
    }

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
                articlesService, anchorService, verifier, answerGenerator
        );
    }

    private AgentStepDecision finalAnswer(String answer) {
        return AgentStepDecision.of(AgentStepActionType.FINAL_ANSWER)
                .withInput(Map.of("answerPlan", answer));
    }

    @Test
    void needMoreInterceptsThenSupplementedAnswerCompletes() {
        // 决策：FINAL_ANSWER（草案被拦）→ QUERY_ARTICLE（补查）→ FINAL_ANSWER（验证过）
        when(decider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(finalAnswer("缓存击穿那段写得不错"))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE))
                .thenReturn(finalAnswer("缓存击穿那段用互斥锁兜底，逻辑清晰，建议补充重试策略"));
        when(executor.execute(any(), any(), any()))
                .thenReturn(AgentActionResult.of("当前文章分析：\n- 标题：《Redis 缓存》\n- 小标题结构：缓存穿透/缓存击穿"));
        when(verifier.verify(any(), any(), any(), any(), any()))
                .thenReturn(Verdict.needMore(Verdict.MissingEvidence.ARTICLE_CONTENT,
                        "草案提到缓存击穿段，但证据里没有该段内容"))
                .thenReturn(Verdict.sufficient());

        AgentRunResult result = runtime.run(100L, 200L, "缓存击穿那段写得怎么样",
                articleContext("12"), com.hailin.blogsystem.ai.agent.AgentStepEmitter.noop());

        assertThat(result.status()).isEqualTo(AiAgentRunStatus.COMPLETED);
        assertThat(result.completionKind()).isEqualTo(AgentCompletionKind.ANSWER);
        assertThat(result.finalAnswer()).contains("互斥锁兜底");
        assertThat(result.usedSteps()).isEqualTo(3);

        // 审计（落库顺序）：FINAL_ANSWER FAILED(拦截) + QUERY SUCCESS(补查) + FINAL_ANSWER SUCCESS
        List<AiAgentStep> steps = captureSteps();
        assertThat(steps).hasSize(3);
        assertThat(steps.get(0).getActionType()).isEqualTo("FINAL_ANSWER");
        assertThat(steps.get(0).getStatus()).isEqualTo("FAILED");
        assertThat(steps.get(0).getErrorMessage()).contains("证据不足");
        assertThat(steps.get(1).getActionType()).isEqualTo("QUERY_ARTICLE");
        assertThat(steps.get(2).getActionType()).isEqualTo("FINAL_ANSWER");
        assertThat(steps.get(2).getStatus()).isEqualTo("SUCCESS");
        verify(verifier, times(2)).verify(any(), any(), any(), any(), any());
        // 拦截提示进上下文（下一轮决策器看到缺什么）
        AiAgentRun saved = captureRun();
        assertThat(saved.getContextJson()).contains("回答依据不足");
        assertThat(saved.getContextJson()).contains("缓存击穿");
    }

    /**
     * analysis-only 第二层（白名单）：决策器若仍输出 ASK_USER，骨架直接终局失败。
     * 这条锁的是「专用 prompt 是必需项」——没有它，这条路径试一次就废一轮。
     */
    @Test
    void analysisOnlyRejectsAskUserFromDecider() {
        when(analysisDecider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.ASK_USER)
                        .withInput(Map.of("question", "你想分析哪个方面？")));

        AgentRunResult result = runtime.runAnalysisOnly(
                1L, 10L, "分析一下这篇文章", articleContext("12"), AgentStepEmitter.noop());

        assertThat(result.status()).isEqualTo(AiAgentRunStatus.FAILED);
    }

    /**
     * analysis-only 第三层（Verifier 转问分流）：判 ASK_USER 时不转问，改保守收尾。
     * 转问会产生 WAITING_USER，把复合请求变成跨请求——那正是本刀要守住的边界（§4.7）。
     */
    @Test
    void analysisOnlyTurnsVerifierAskUserIntoConservativeShutdown() {
        when(analysisDecider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE))
                .thenReturn(finalAnswer("这篇节奏偏快"));
        when(executor.execute(any(), any(), any()))
                .thenReturn(AgentActionResult.of("当前文章分析：\n- 标题：《Redis 缓存》"));
        when(verifier.verify(any(), any(), any(), any(), any()))
                .thenReturn(Verdict.askUser("你想分析哪个方面？", "指代不清"));

        AgentRunResult result = runtime.runAnalysisOnly(
                1L, 10L, "分析一下这篇文章", articleContext("12"), AgentStepEmitter.noop());

        // 核心断言：不进入等待态，本轮就结束
        assertThat(result.status()).isEqualTo(AiAgentRunStatus.COMPLETED);
        assertThat(result.completionKind())
                .as("证据不足必须能被外层识别——否则任务编排会把它回填成「已完成」")
                .isEqualTo(AgentCompletionKind.CONSERVATIVE_SHUTDOWN);
        assertThat(result.finalAnswer()).contains("不足");
    }

    @Test
    void askUserVerdictConvertsToWaitingUserWithSingleAskStep() {
        when(decider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE))
                .thenReturn(finalAnswer("这篇的优点是节奏快"));
        when(executor.execute(any(), any(), any()))
                .thenReturn(AgentActionResult.of("当前文章分析：\n- 标题：《Redis 缓存》"));
        when(verifier.verify(any(), any(), any(), any(), any()))
                .thenReturn(Verdict.askUser("你想让我分析这篇文章的哪个方面？", "用户没说清维度"));

        AgentRunResult result = runtime.run(100L, 200L, "分析一下这篇",
                articleContext("12"), com.hailin.blogsystem.ai.agent.AgentStepEmitter.noop());

        assertThat(result.status()).isEqualTo(AiAgentRunStatus.WAITING_USER);
        assertThat(result.completionKind())
                .as("非 COMPLETED 由 status 表达，完成类型保持 null（不建第二套状态机）")
                .isNull();
        assertThat(result.finalAnswer()).isEqualTo("你想让我分析这篇文章的哪个方面？");

        // 只落 QUERY + ASK_USER 两条 step，无伪造 FINAL_ANSWER step
        List<AiAgentStep> steps = captureSteps();
        assertThat(steps).hasSize(2);
        assertThat(steps.get(1).getActionType()).isEqualTo("ASK_USER");
        assertThat(steps.get(1).getStatus()).isEqualTo("SUCCESS");
    }

    @Test
    void repeatedFinalAnswerWithoutEvidenceProgressStopsAtSecondStep() {
        // 决策器固执地反复 FINAL_ANSWER：第二次没有任何新增证据，Runtime 必须立即收敛
        when(decider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(finalAnswer("这篇不错"));
        when(verifier.verify(any(), any(), any(), any(), any()))
                .thenReturn(Verdict.needMore(Verdict.MissingEvidence.RAG,
                        "还缺站内知识对照"));

        AgentRunResult result = runtime.run(100L, 200L, "这篇和主流写法对比下",
                articleContext("12"), com.hailin.blogsystem.ai.agent.AgentStepEmitter.noop());

        assertThat(result.status()).isEqualTo(AiAgentRunStatus.COMPLETED);
        assertThat(result.usedSteps()).isEqualTo(2);
        // 保守收尾文案（不走 summarize 硬答）
        assertThat(result.finalAnswer()).contains("不足以让我给出可靠结论");
        verify(verifier, times(1)).verify(any(), any(), any(), any(), any());
        verify(answerGenerator, never()).generate(any(), any(), any(), any());
        assertThat(captureSteps())
                .extracting(AiAgentStep::getStatus)
                .containsExactly("FAILED", "SKIPPED");
        verify(decider, never()).summarize(any(), any(), any());
    }

    @Test
    void maxStepsWithArticleNeverReadShutsDownConservatively() {
        // 全程只 SEARCH_RAG（从没 QUERY_ARTICLE 目标文章）→ 到顶 S1 保守收尾
        when(decider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.SEARCH_RAG));
        when(executor.execute(any(), any(), any()))
                .thenReturn(AgentActionResult.of("站内文章知识检索结果：\n1. 《缓存穿透》..."));

        AgentRunResult result = runtime.run(100L, 200L, "分析这篇文章",
                articleContext("12"), com.hailin.blogsystem.ai.agent.AgentStepEmitter.noop());

        assertThat(result.status()).isEqualTo(AiAgentRunStatus.COMPLETED);
        assertThat(result.usedSteps()).isEqualTo(6);
        assertThat(result.finalAnswer()).contains("不足以让我给出可靠结论");
        verify(decider, never()).summarize(any(), any(), any());
    }

    @Test
    void rejectionFollowedBySuccessfulSupplementAtTopUsesSummarize() {
        // 2026-09-10 手测修正：拦后补查成功（材料已在手）→ 到顶不保守，走 summarize 基于新证据收尾
        when(decider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(finalAnswer("草案一"))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE))
                .thenReturn(finalAnswer("草案二"))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE))
                .thenReturn(finalAnswer("草案三"))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE));
        when(executor.execute(any(), any(), any()))
                .thenReturn(AgentActionResult.of("当前文章分析：\n- 标题：《Redis 缓存》\n- 正文片段：第一处"))
                .thenReturn(AgentActionResult.of("当前文章分析：\n- 标题：《Redis 缓存》\n- 正文片段：第二处"))
                .thenReturn(AgentActionResult.of("当前文章分析：\n- 标题：《Redis 缓存》\n- 正文片段：第三处"));
        when(verifier.verify(any(), any(), any(), any(), any()))
                .thenReturn(Verdict.needMore(Verdict.MissingEvidence.ARTICLE_CONTENT, "缺细节"));

        AgentRunResult result = runtime.run(100L, 200L, "这篇写得怎么样",
                articleContext("12"), com.hailin.blogsystem.ai.agent.AgentStepEmitter.noop());

        // 最后一次拦截(5) 之后有成功补查(6) → unresolved=false → 不保守 → summarize 收尾
        assertThat(result.status()).isEqualTo(AiAgentRunStatus.COMPLETED);
        assertThat(result.completionKind())
                .as("到顶 summarize 有阶段性内容，但没经过最终答案同等级的证据放行")
                .isEqualTo(AgentCompletionKind.MAX_STEPS_SUMMARY);
        assertThat(result.usedSteps()).isEqualTo(6);
        assertThat(result.finalAnswer()).doesNotContain("不足以让我给出可靠结论");
        verify(decider, org.mockito.Mockito.atLeastOnce()).summarize(any(), any(), any());
    }

    @Test
    void verifierNullKeepsOriginalPath() {
        // 验证器返回 null（默认/未接线）→ 收敛门放行，行为与 V3.10 一致
        when(decider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(finalAnswer("直接回答"));
        when(verifier.verify(any(), any(), any(), any(), any())).thenReturn(null);

        AgentRunResult result = runtime.run(100L, 200L, "这篇文章怎么样",
                articleContext("12"), com.hailin.blogsystem.ai.agent.AgentStepEmitter.noop());

        assertThat(result.status()).isEqualTo(AiAgentRunStatus.COMPLETED);
        assertThat(result.finalAnswer()).isEqualTo("直接回答");
        verify(answerGenerator, times(1)).generate(any(), any(), any(), any());
    }

    @Test
    void generatedAnswerTransitionsSameFinalStepFromRunningToSuccess() {
        when(decider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(finalAnswer("结论：结构清晰"));
        when(verifier.verify(any(), any(), any(), any(), any())).thenReturn(Verdict.sufficient());
        doReturn(new GeneratedAnswer("最终正文", new TokenUsageAccumulator(), false))
                .when(answerGenerator).generate(any(), any(), any(), any());
        List<String> statuses = new ArrayList<>();
        when(stepMapper.insert(any(AiAgentStep.class))).thenAnswer(inv -> {
            AiAgentStep step = inv.getArgument(0);
            step.setId(88L);
            if ("FINAL_ANSWER".equals(step.getActionType())) {
                statuses.add(step.getStatus());
            }
            return 1;
        });
        when(stepMapper.updateById(any(AiAgentStep.class))).thenAnswer(inv -> {
            AiAgentStep step = inv.getArgument(0);
            statuses.add(step.getStatus());
            return 1;
        });

        AgentRunResult result = runtime.run(100L, 200L, "分析文章",
                articleContext("12"), AgentStepEmitter.noop());

        assertThat(result.status()).isEqualTo(AiAgentRunStatus.COMPLETED);
        assertThat(result.finalAnswer()).isEqualTo("最终正文");
        assertThat(statuses).containsExactly("RUNNING", "SUCCESS");
    }

    @Test
    void generationFailureTransitionsSameStepToFailedAndCountsUsage() {
        when(decider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(finalAnswer("结论：结构清晰"));
        when(verifier.verify(any(), any(), any(), any(), any())).thenReturn(Verdict.sufficient());
        TokenUsageAccumulator usage = new TokenUsageAccumulator();
        org.springframework.ai.chat.metadata.Usage rawUsage = mock(org.springframework.ai.chat.metadata.Usage.class);
        when(rawUsage.getPromptTokens()).thenReturn(10);
        when(rawUsage.getCompletionTokens()).thenReturn(2);
        when(rawUsage.getTotalTokens()).thenReturn(12);
        usage.add(rawUsage);
        doThrow(new AgentAnswerStreamException("生成失败", new RuntimeException("503"), usage, false))
                .when(answerGenerator).generate(any(), any(), any(), any());
        List<String> statuses = new ArrayList<>();
        when(stepMapper.insert(any(AiAgentStep.class))).thenAnswer(inv -> {
            AiAgentStep step = inv.getArgument(0);
            step.setId(89L);
            statuses.add(step.getStatus());
            return 1;
        });
        when(stepMapper.updateById(any(AiAgentStep.class))).thenAnswer(inv -> {
            statuses.add(inv.<AiAgentStep>getArgument(0).getStatus());
            return 1;
        });

        AgentRunResult result = runtime.run(100L, 200L, "分析文章",
                articleContext("12"), AgentStepEmitter.noop());

        assertThat(result.status()).isEqualTo(AiAgentRunStatus.FAILED);
        assertThat(result.totalTokens()).isEqualTo(12);
        assertThat(statuses).containsExactly("RUNNING", "FAILED");
        verify(anchorService, never()).markConclusion(any(), any(), any(), any(), any());
    }

    @Test
    void deliveredGenerationFailureEscapesRuntimeAfterMarkingFailed() {
        when(decider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(finalAnswer("结论：结构清晰"));
        when(verifier.verify(any(), any(), any(), any(), any())).thenReturn(Verdict.sufficient());
        doThrow(new AgentAnswerStreamException(
                "流中断", new RuntimeException("503"), new TokenUsageAccumulator(), true))
                .when(answerGenerator).generate(any(), any(), any(), any());

        assertThatThrownBy(() -> runtime.run(100L, 200L, "分析文章",
                articleContext("12"), AgentStepEmitter.noop()))
                .isInstanceOf(AgentAnswerStreamException.class);
        ArgumentCaptor<AiAgentRun> runCaptor = ArgumentCaptor.forClass(AiAgentRun.class);
        verify(runMapper, atLeastOnce()).updateById(runCaptor.capture());
        assertThat(runCaptor.getAllValues())
                .anySatisfy(run -> assertThat(run.getStatus()).isEqualTo(AiAgentRunStatus.FAILED.name()));
    }

    @Test
    void finalAnswerStepDurationIncludesDeciderTime() {
        when(decider.decide(any(), any(), anyInt(), anyInt(), any())).thenAnswer(invocation -> {
            Thread.sleep(20);
            return finalAnswer("直接回答");
        });
        when(verifier.verify(any(), any(), any(), any(), any())).thenReturn(null);

        runtime.run(100L, 200L, "这篇文章怎么样",
                articleContext("12"), AgentStepEmitter.noop());

        assertThat(captureSteps()).singleElement()
                .extracting(AiAgentStep::getDurationMs)
                .as("step duration 应覆盖 Decider，而不是把终态固定写成 0")
                .satisfies(durationMs -> assertThat(durationMs).isPositive());
    }

    // ==================== 第四步·阶段 2：父子 run 拓扑 ====================

    @Test
    void childRunDoesNotCancelPendingConfirmations() {
        // 子 run（编排内部的分析执行）**不能**清理同 session 的待确认卡——
        // 否则一次内部分析会把用户已有的 Workflow / 写动作确认卡取消掉，
        // 用户再去点确认只会看到"状态不允许"。
        //
        // 判据精确落在 cancelStalePendingSuggestions 的那次两参 update(entity, wrapper)：
        // token 兜底 / 计划落库走的是 update(null, UpdateWrapper)，第一个参数是 null，
        // 不会被 any(AiAgentRun.class) 匹配，所以这条 never() 只针对「取消卡片」。
        when(analysisDecider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(finalAnswer("分析完成"));

        runtime.runAnalysisOnly(100L, 200L, "分析一下这篇文章",
                articleContext("12"), AgentStepEmitter.noop(), 999L);

        verify(runMapper, never()).update(any(AiAgentRun.class), any());
    }

    @Test
    void rootRunStillCancelsPendingConfirmations() {
        // 对照：根 run（用户发起的新一轮判断）仍要让旧的待确认建议 / 提案过期
        when(analysisDecider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(finalAnswer("分析完成"));

        runtime.runAnalysisOnly(100L, 200L, "分析一下这篇文章",
                articleContext("12"), AgentStepEmitter.noop(), null);

        verify(runMapper, atLeastOnce()).update(any(AiAgentRun.class), any());
    }

    @Test
    void childRunPersistsParentRunId() {
        // 父子归属必须真的落库——否则 Token 子树聚合、列表过滤、启动恢复全都失去依据
        when(analysisDecider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(finalAnswer("分析完成"));

        runtime.runAnalysisOnly(100L, 200L, "分析一下这篇文章",
                articleContext("12"), AgentStepEmitter.noop(), 999L);

        ArgumentCaptor<AiAgentRun> captor = ArgumentCaptor.forClass(AiAgentRun.class);
        verify(runMapper).insert(captor.capture());
        assertThat(captor.getValue().getParentRunId()).isEqualTo(999L);
        assertThat(captor.getValue().getUserId()).isEqualTo(100L);
    }

    private AiAgentRun captureRun() {
        ArgumentCaptor<AiAgentRun> captor = ArgumentCaptor.forClass(AiAgentRun.class);
        verify(runMapper, atLeastOnce()).updateById(captor.capture());
        return captor.getValue();
    }

    private List<AiAgentStep> captureSteps() {
        ArgumentCaptor<AiAgentStep> captor = ArgumentCaptor.forClass(AiAgentStep.class);
        verify(stepMapper, atLeastOnce()).insert(captor.capture());
        return captor.getAllValues();
    }
}
