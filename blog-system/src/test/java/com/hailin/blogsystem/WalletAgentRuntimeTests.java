package com.hailin.blogsystem;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.ai.TokenUsageAccumulator;
import com.hailin.blogsystem.ai.agent.AgentStepDecision;
import com.hailin.blogsystem.ai.agent.AgentStepEmitter;
import com.hailin.blogsystem.ai.agent.AgentWriteProposal;
import com.hailin.blogsystem.ai.agent.GeneralAgentActionExecutor;
import com.hailin.blogsystem.ai.agent.WalletAgentRuntime;
import com.hailin.blogsystem.ai.agent.WalletAgentStepDecider;
import com.hailin.blogsystem.entity.AiAgentRun;
import com.hailin.blogsystem.entity.AiAgentStep;
import com.hailin.blogsystem.entity.dto.AgentStepActionType;
import com.hailin.blogsystem.entity.dto.AiAgentRunStatus;
import com.hailin.blogsystem.mapper.AiAgentRunMapper;
import com.hailin.blogsystem.mapper.AiAgentStepMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.metadata.Usage;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 钱包域 Runtime 的提案端。
 *
 * <p>这里最重要的一组断言是**「校验失败必须直接终态，不能回到循环」**：
 * 本域的决策器是确定性的，每一轮输出完全相同，任何「拒绝后让下一轮收尾」的写法
 * 都会变成真死循环（SSE 永不 complete，直到准入超时）。所以校验失败一律
 * {@code markFailed}，绝不返回 null。
 */
class WalletAgentRuntimeTests {

    private WalletAgentStepDecider decider;
    private AiAgentRunMapper runMapper;
    private AiAgentStepMapper stepMapper;
    private WalletAgentRuntime runtime;

    @BeforeEach
    void setUp() {
        decider = mock(WalletAgentStepDecider.class);
        runMapper = mock(AiAgentRunMapper.class);
        stepMapper = mock(AiAgentStepMapper.class);

        // MyBatis-Plus 的 ASSIGN_ID 在 mock 下不生效，insert 时手动赋 id
        when(runMapper.insert(any(AiAgentRun.class))).thenAnswer(invocation -> {
            AiAgentRun run = invocation.getArgument(0);
            run.setId(1L);
            return 1;
        });
        when(runMapper.updateById(any(AiAgentRun.class))).thenReturn(1);

        runtime = new WalletAgentRuntime(decider, mock(GeneralAgentActionExecutor.class),
                runMapper, stepMapper, new ObjectMapper());
    }

    private void stubDecision(AgentStepDecision decision) {
        when(decider.decide(anyString(), any(), anyInt(), anyInt(), any())).thenReturn(decision);
    }

    private static AgentStepDecision suggestWrite(Map<String, Object> input) {
        return AgentStepDecision.of(AgentStepActionType.SUGGEST_WRITE).withInput(input);
    }

    @Test
    void producesWriteProposalWithAmount() {
        stubDecision(suggestWrite(Map.of(
                "actionType", AgentWriteProposal.TYPE_RECHARGE_WALLET,
                "payYuan", "10")));

        var result = runtime.run(100L, 200L, "帮我充10块", null, AgentStepEmitter.noop());

        assertThat(result.status()).isEqualTo(AiAgentRunStatus.WAITING_WRITE_CONFIRM);
        assertThat(result.pendingWriteAction()).isNotNull();
        assertThat(result.pendingWriteAction().actionType())
                .isEqualTo(AgentWriteProposal.TYPE_RECHARGE_WALLET);
        assertThat(result.pendingWriteAction().payYuan()).isEqualTo("10");
        // 文案不能说「已完成」——这一步只是把卡递到用户面前，钱要等他点确认
        assertThat(result.finalAnswer()).contains("确认").doesNotContain("已完成");
    }

    @Test
    void writeProposalTerminalStepKeepsDecisionTokenUsage() {
        Usage suggestUsage = mockUsage(80, 8, 88);
        when(decider.decide(anyString(), any(), anyInt(), anyInt(), any()))
                .thenAnswer(inv -> {
                    inv.getArgument(4, TokenUsageAccumulator.class).add(suggestUsage);
                    return suggestWrite(Map.of(
                            "actionType", AgentWriteProposal.TYPE_RECHARGE_WALLET,
                            "payYuan", "10"));
                });

        var result = runtime.run(100L, 200L, "帮我充10块", null, AgentStepEmitter.noop());

        assertThat(result.totalTokens()).isEqualTo(88);
        List<AiAgentStep> steps = captureSteps();
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).getActionType()).isEqualTo("SUGGEST_WRITE");
        assertThat(steps.get(0).getInputTokens()).isEqualTo(80);
        assertThat(steps.get(0).getOutputTokens()).isEqualTo(8);
    }

    /** 不认识的 actionType → 直接终态，**不能**回到循环顶 */
    @Test
    void marksFailedOnUnsupportedActionType() {
        Usage rejectedUsage = mockUsage(90, 9, 99);
        when(decider.decide(anyString(), any(), anyInt(), anyInt(), any()))
                .thenAnswer(invocation -> {
                    invocation.getArgument(4, com.hailin.blogsystem.ai.TokenUsageAccumulator.class)
                            .add(rejectedUsage);
                    return suggestWrite(Map.of(
                            "actionType", "SOMETHING_ELSE",
                            "payYuan", "10"));
                });

        var result = runtime.run(100L, 200L, "帮我充10块", null, AgentStepEmitter.noop());

        assertThat(result.status()).isEqualTo(AiAgentRunStatus.FAILED);
        assertThat(result.totalTokens()).isEqualTo(99);
        assertThat(result.usedSteps()).isEqualTo(1);
        assertThat(result.pendingWriteAction()).isNull();
        List<AiAgentStep> steps = captureSteps();
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).getStatus()).isEqualTo("FAILED");
        assertThat(steps.get(0).getInputTokens()).isEqualTo(90);
        assertThat(steps.get(0).getOutputTokens()).isEqualTo(9);
    }

    @Test
    void marksFailedOnMissingAmount() {
        stubDecision(suggestWrite(Map.of(
                "actionType", AgentWriteProposal.TYPE_RECHARGE_WALLET)));

        var result = runtime.run(100L, 200L, "帮我充10块", null, AgentStepEmitter.noop());

        assertThat(result.status()).isEqualTo(AiAgentRunStatus.FAILED);
        assertThat(result.pendingWriteAction()).isNull();
    }

    /**
     * ⚠️ {@code isTerminalExtension} 必须返回 false。
     *
     * <p>语义上它有悖直觉（SUGGEST_WRITE 确实是终态动作），但返回 true 的话，
     * 万一将来有人加了一条「返回 null」的出口，流程会 {@code continue} 回循环顶，
     * 撞上确定性决策器的同一个提案 → **死循环**。返回 false 则会掉进 executor
     * 抛异常 → 落 FAILED step + 推进步数 → 有界。
     *
     * <p>这里用「提案成功时不会误走 executor」间接锁住它：正常路径走的是
     * {@code handleExtraTerminalAction} 的非 null 出口，压根到不了这个钩子。
     */
    @Test
    void proposalNeverFallsThroughToExecutor() {
        stubDecision(suggestWrite(Map.of(
                "actionType", AgentWriteProposal.TYPE_RECHARGE_WALLET,
                "payYuan", "10")));

        var result = runtime.run(100L, 200L, "帮我充10块", null, AgentStepEmitter.noop());

        // 掉进 executor 的话会被通用域执行器抛 UnsupportedOperationException，run 变 FAILED
        assertThat(result.status()).isEqualTo(AiAgentRunStatus.WAITING_WRITE_CONFIRM);
    }

    private List<AiAgentStep> captureSteps() {
        ArgumentCaptor<AiAgentStep> captor = ArgumentCaptor.forClass(AiAgentStep.class);
        verify(stepMapper, atLeastOnce()).insert(captor.capture());
        return captor.getAllValues();
    }

    private static Usage mockUsage(int prompt, int completion, int total) {
        Usage usage = mock(Usage.class);
        when(usage.getPromptTokens()).thenReturn(prompt);
        when(usage.getCompletionTokens()).thenReturn(completion);
        when(usage.getTotalTokens()).thenReturn(total);
        return usage;
    }
}
