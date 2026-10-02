package com.hailin.blogsystem.ai.task;

import com.hailin.blogsystem.ai.agent.AgentCompletionKind;
import com.hailin.blogsystem.ai.agent.AgentRunResult;
import com.hailin.blogsystem.ai.agent.AgentStepEmitter;
import com.hailin.blogsystem.ai.agent.ArticleAgentRuntime;
import com.hailin.blogsystem.ai.task.executor.ArticleAnalysisExecutor;
import com.hailin.blogsystem.entity.dto.AiAgentRunStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 文章分析执行器的**结果回填映射**测试（第四步·阶段 3，2026-09-30）。
 *
 * <p><b>锁的是什么</b>：设计稿 §13.4 的映射表——{@code StepState} 表达的是**用户目标是否达成**，
 * 不是「内部代码有没有跑完」。所以只有 {@code ANSWER} 落 {@code SUCCEEDED}，
 * 空答案 / 证据不足 / 到顶总结 / 终局失败**一律落 FAILED**，靠 {@code StepCode} 区分原因。
 *
 * <p>否则 {@code TaskResultRenderer} 的 {@code allDone} 判定会把它们全放进 done 列表，
 * 输出「已全部完成：目前收集到的信息还不足以让我给出可靠结论」——那是明令禁止的谎报。
 *
 * <p>注意两类枚举的命名分工：{@link AgentCompletionKind} 描述**子 run** 的收尾
 * （CONSERVATIVE_SHUTDOWN = 证据不足），{@link StepCode} 描述**外层步骤**的结果码
 * （INSUFFICIENT_EVIDENCE）。它们不能混用。
 *
 * <p>放在 {@code ai.task} 包而不是 executor 包：{@link TaskExecutionContext#record} 是包私有，
 * 构造「前序步骤已完成」的状态必须同包。
 */
class ArticleAnalysisExecutorTests {

    private ArticleAgentRuntime runtime;
    private ArticleAnalysisExecutor executor;

    @BeforeEach
    void setUp() {
        runtime = mock(ArticleAgentRuntime.class);
        executor = new ArticleAnalysisExecutor(runtime);
    }

    // ==================== §13.4 映射表 ====================

    @Test
    @DisplayName("ANSWER -> SUCCEEDED + ANSWERED：汇总才可以说「完成」")
    void answerMapsToSucceededAnswered() {
        stub(completed(AgentCompletionKind.ANSWER, "这篇文章结构清晰，建议补充重试策略"));

        StepResult result = executor.execute(context(null), step());

        assertThat(result.state()).isEqualTo(StepState.SUCCEEDED);
        assertThat(result.code()).isEqualTo(StepCode.ANSWERED);
        assertThat(result.summary()).isEqualTo("这篇文章结构清晰，建议补充重试策略");
        assertThat(result.isSuccessLike()).isTrue();
        assertThat(result.output()).containsEntry("agentRunId", "1");
        assertThat(result.output()).containsEntry("completionKind", "ANSWER");
    }

    @Test
    @DisplayName("EMPTY_ANSWER_FALLBACK -> FAILED：兜底文案不是结论")
    void emptyAnswerFallbackMapsToFailed() {
        stub(completed(AgentCompletionKind.EMPTY_ANSWER_FALLBACK, "已为你分析这篇文章，但未生成具体建议。"));

        StepResult result = executor.execute(context(null), step());

        assertThat(result.state()).isEqualTo(StepState.FAILED);
        assertThat(result.code()).isEqualTo(StepCode.INTERNAL_ERROR);
        assertThat(result.isSuccessLike()).isFalse();
    }

    @Test
    @DisplayName("CONSERVATIVE_SHUTDOWN -> FAILED + INSUFFICIENT_EVIDENCE：证据不足不算达成")
    void conservativeShutdownMapsToInsufficientEvidence() {
        stub(completed(AgentCompletionKind.CONSERVATIVE_SHUTDOWN,
                "目前收集到的信息还不足以让我给出可靠结论，我先不硬答。"));

        StepResult result = executor.execute(context(null), step());

        assertThat(result.state()).isEqualTo(StepState.FAILED);
        assertThat(result.code()).isEqualTo(StepCode.INSUFFICIENT_EVIDENCE);
        // 文案仍然如实带给用户，但**不能**进 done 列表
        assertThat(result.summary()).contains("不足以");
        assertThat(result.isSuccessLike()).isFalse();
    }

    @Test
    @DisplayName("MAX_STEPS_SUMMARY -> FAILED + INCOMPLETE_RESULT：阶段性内容不算完整达成")
    void maxStepsSummaryMapsToIncompleteResult() {
        stub(completed(AgentCompletionKind.MAX_STEPS_SUMMARY, "根据已读内容，前半部分主要讲缓存穿透"));

        StepResult result = executor.execute(context(null), step());

        assertThat(result.state()).isEqualTo(StepState.FAILED);
        assertThat(result.code()).isEqualTo(StepCode.INCOMPLETE_RESULT);
    }

    @Test
    @DisplayName("TERMINAL_FAILURE -> FAILED + TARGET_NOT_FOUND：不伪装成系统故障")
    void terminalFailureMapsToTargetNotFound() {
        stub(completed(AgentCompletionKind.TERMINAL_FAILURE, "这篇文章不属于你，无法为你分析"));

        StepResult result = executor.execute(context(null), step());

        assertThat(result.state()).isEqualTo(StepState.FAILED);
        assertThat(result.code()).isEqualTo(StepCode.TARGET_NOT_FOUND);
    }

    // ==================== §13.8 的实施注意 ====================

    @Test
    @DisplayName("§13.8③ 完成类型缺失 -> 防御性失败，不能当成 ANSWER 放行")
    void missingCompletionKindFailsDefensively() {
        stub(AgentRunResult.of(1L, AiAgentRunStatus.COMPLETED, "看着像正常答案", 3, 500));

        StepResult result = executor.execute(context(null), step());

        assertThat(result.state()).isEqualTo(StepState.FAILED);
        assertThat(result.code()).isEqualTo(StepCode.INTERNAL_ERROR);
        assertThat(result.isSuccessLike())
                .as("一次误用就会悄悄回到谎报完成")
                .isFalse();
    }

    @Test
    @DisplayName("非 COMPLETED（含 analysis-only 下不可达的 WAITING_USER）-> FAILED，不暂停外层计划")
    void nonCompletedStatusFails() {
        when(runtime.runAnalysisOnly(any(), any(), any(), any(), any(), any()))
                .thenReturn(AgentRunResult.of(1L, AiAgentRunStatus.FAILED, null, 2, 300));

        StepResult result = executor.execute(context(null), step());

        assertThat(result.state()).isEqualTo(StepState.FAILED);
        assertThat(result.code()).isEqualTo(StepCode.INTERNAL_ERROR);
        assertThat(result.output()).containsEntry("status", "FAILED");
    }

    @Test
    @DisplayName("§13.8① 失败也要带结构化事实（agentRunId / usedSteps / completionKind）")
    void failedResultStillCarriesStructuredFacts() {
        stub(completed(AgentCompletionKind.CONSERVATIVE_SHUTDOWN, "证据不足"));

        StepResult result = executor.execute(context(null), step());

        assertThat(result.output())
                .containsEntry("agentRunId", "1")
                .containsEntry("usedSteps", 3)
                .containsEntry("completionKind", "CONSERVATIVE_SHUTDOWN");
    }

    // ==================== 上下文与事件出口 ====================

    @Test
    @DisplayName("未登录 / 缺外层 run -> 明确失败，不建孤儿 run")
    void guardsLoginAndTaskRunId() {
        StepResult noLogin = executor.execute(
                new TaskExecutionContext(null, 200L, null, plan(), 999L, null), step());
        assertThat(noLogin.code()).isEqualTo(StepCode.FORBIDDEN);

        StepResult noTaskRun = executor.execute(
                new TaskExecutionContext(100L, 200L, null, plan(), null, null), step());
        assertThat(noTaskRun.state()).isEqualTo(StepState.FAILED);
        assertThat(noTaskRun.summary()).contains("缺少外层 run");
    }

    @Test
    @DisplayName("内部步骤事件显式带 taskStepId（不依赖串行，也不靠 ThreadLocal）")
    void innerStepsCarryTaskStepId() {
        List<String> emitted = new ArrayList<>();
        TaskStepEventSink sink = (taskStepId, stepNo, actionType, status, message, thought) ->
                emitted.add(taskStepId + "|" + stepNo + "|" + actionType + "|" + status);

        when(runtime.runAnalysisOnly(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    AgentStepEmitter emitter = invocation.getArgument(4);
                    emitter.emit(1, "QUERY_ARTICLE", "SUCCESS", "已读文章", null);
                    emitter.emit(2, "FINAL_ANSWER", "SUCCESS", "已作答", "想想");
                    return completed(AgentCompletionKind.ANSWER, "答案");
                });

        executor.execute(context(sink), step());

        assertThat(emitted).containsExactly(
                "s1|1|QUERY_ARTICLE|SUCCESS",
                "s1|2|FINAL_ANSWER|SUCCESS");
    }

    @Test
    @DisplayName("goal 带上已完成的前置步骤事实（沿用第二刀：避免模型重复许诺）")
    void goalCarriesCompletedStepFacts() {
        List<String> capturedGoals = new ArrayList<>();
        when(runtime.runAnalysisOnly(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    capturedGoals.add(invocation.getArgument(2));
                    return completed(AgentCompletionKind.ANSWER, "答案");
                });

        TaskStep write = new TaskStep("s0", StepType.ARTICLE_WRITE, Map.of("action", "likeArticle"),
                new StepDependency(List.of(), List.of(), OnFailure.CONTINUE), List.of("点赞这篇文章"));
        TaskStep analysis = step();
        TaskPlan plan = new TaskPlan("点赞这篇文章，再分析一下它写得怎么样",
                List.of(write, analysis), List.of(), List.of());
        TaskExecutionContext context = new TaskExecutionContext(100L, 200L, null, plan, 999L, null);
        context.record(StepResult.applied("s0", "点赞成功", Map.of()));

        executor.execute(context, analysis);

        assertThat(capturedGoals).hasSize(1);
        assertThat(capturedGoals.get(0))
                .contains("点赞这篇文章，再分析一下它写得怎么样")
                .contains("【已完成的前置动作】")
                .contains("点赞成功")
                .contains("【这一步要完成的】")
                .contains("分析一下它写得怎么样");
    }

    @Test
    @DisplayName("搜索后比较会把受限的文章证据注入分析 goal")
    void comparisonGoalCarriesStructuredSearchEvidence() {
        List<String> capturedGoals = new ArrayList<>();
        when(runtime.runAnalysisOnly(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    capturedGoals.add(invocation.getArgument(2));
                    return completed(AgentCompletionKind.ANSWER, "答案");
                });

        TaskStep search = new TaskStep("s0", StepType.ARTICLE_SEARCH, Map.of("query", "Redis"),
                StepDependency.none(), List.of("搜一下 Redis 相关文章"));
        TaskStep comparison = new TaskStep("s1", StepType.ARTICLE_ANALYSIS, Map.of(),
                StepDependency.requires("s0"), List.of("比较各篇文章"));
        TaskPlan plan = new TaskPlan("搜一下 Redis 相关文章，并比较各篇文章",
                List.of(search, comparison), List.of(), List.of());
        TaskExecutionContext context = new TaskExecutionContext(100L, 200L, null, plan, 999L, null);
        context.record(StepResult.applied("s0", "找到 1 篇 Redis 文章", Map.of(
                "query", "Redis",
                "articles", List.of(Map.of(
                        "articleId", "12",
                        "title", "Redis 缓存工程化实践",
                        "summary", "介绍缓存一致性与延迟双删")))));

        executor.execute(context, comparison);

        assertThat(capturedGoals).hasSize(1);
        assertThat(capturedGoals.get(0))
                .contains("【搜索结果证据】")
                .contains("《Redis 缓存工程化实践》(12)")
                .contains("介绍缓存一致性与延迟双删")
                .contains("只基于以上证据比较");
    }

    // ==================== helpers ====================

    private TaskStep step() {
        return new TaskStep("s1", StepType.ARTICLE_ANALYSIS, Map.of(),
                new StepDependency(List.of(), List.of(), OnFailure.CONTINUE),
                List.of("分析一下它写得怎么样"));
    }

    private TaskPlan plan() {
        return new TaskPlan("点赞这篇文章，再分析一下它写得怎么样",
                List.of(step()), List.of(), List.of());
    }

    private TaskExecutionContext context(TaskStepEventSink sink) {
        return new TaskExecutionContext(100L, 200L, null, plan(), 999L, sink);
    }

    private AgentRunResult completed(AgentCompletionKind kind, String answer) {
        return AgentRunResult.of(1L, AiAgentRunStatus.COMPLETED, answer, 3, 500, kind);
    }

    private void stub(AgentRunResult result) {
        when(runtime.runAnalysisOnly(any(), any(), any(), any(), any(), any())).thenReturn(result);
    }
}
