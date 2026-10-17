package com.hailin.blogsystem.ai.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.entity.AiAgentRun;
import com.hailin.blogsystem.entity.AiAgentStep;
import com.hailin.blogsystem.entity.AiMessages;
import com.hailin.blogsystem.entity.AiWorkflowRun;
import com.hailin.blogsystem.entity.dto.AgentStepActionType;
import com.hailin.blogsystem.entity.dto.AiAgentRunStatus;
import com.hailin.blogsystem.entity.dto.AiAgentStepStatus;
import com.hailin.blogsystem.entity.dto.AiWorkflowStatus;
import com.hailin.blogsystem.mapper.AiAgentRunMapper;
import com.hailin.blogsystem.mapper.AiAgentStepMapper;
import com.hailin.blogsystem.mapper.AiMessageMapper;
import com.hailin.blogsystem.mapper.AiWorkflowRunMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * 恢复协调者（5b 阶段 2）——**这是"用例 B"的后端部分**。
 *
 * <p>计划刻意只放一个 handoff 步骤：这样续跑时没有任何步骤要真正执行
 * （它已有终态结果），测试聚焦在"谁有资格续跑、续跑后事实有没有落定"，
 * 而不是又去跑一遍业务执行器。
 */
@SpringBootTest
/*
 * 2026-10-17：续跑现在接入了计费（预扣 → 按子树 token 差量结算）。
 * 本类用例的测试用户（H2 里没有钱包）会撞余额不足，把续跑本身打断——
 * 而本类验证的是「谁有资格续跑、事实有没有落定」，不是计费。
 * 计费语义由 WorkflowActionBillingTests 覆盖，这里显式关掉。
 */
@TestPropertySource(properties = "blog.ai.billing.enabled=false")
class TaskWorkflowContinuationServiceTests {

    private static final Long USER = 881L;
    private static final Long SESSION = 882L;
    private static final String STEP_ID = "s1";
    private static final long MESSAGE_TOKENS_BEFORE = 500L;

    @Autowired
    private AiAgentRunMapper runMapper;
    @Autowired
    private AiAgentStepMapper stepMapper;
    @Autowired
    private AiWorkflowRunMapper workflowRunMapper;
    @Autowired
    private AiMessageMapper messageMapper;
    @Autowired
    private TaskWorkflowContinuationService continuationService;
    @Autowired
    private TaskPlanSnapshotCodec snapshotCodec;
    @Autowired
    private TaskOrchestrationService orchestrationService;
    @Autowired
    private TaskResultRenderer renderer;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private TaskRunStateTransitions stateTransitions;
    @Autowired
    private TransactionTemplate transactionTemplate;
    private final List<Long> createdRuns = new ArrayList<>();
    private final List<Long> createdWorkflows = new ArrayList<>();
    private final List<Long> createdMessages = new ArrayList<>();

    private record Fixture(Long runId, AiWorkflowRun workflow, Long messageId) {
    }

    @AfterEach
    void tearDown() {
        for (Long id : createdMessages) {
            messageMapper.deleteById(id);
        }
        for (Long id : createdWorkflows) {
            workflowRunMapper.deleteById(id);
        }
        for (Long runId : createdRuns) {
            stepMapper.delete(new LambdaQueryWrapper<AiAgentStep>().eq(AiAgentStep::getAgentRunId, runId));
            runMapper.deleteById(runId);
        }
        createdMessages.clear();
        createdWorkflows.clear();
        createdRuns.clear();
    }

    @Test
    @DisplayName("没有父关联的 Workflow：完全不动（普通 Workflow 不受影响）")
    void ignoresStandaloneWorkflow() {
        AiWorkflowRun workflow = insertWorkflow(AiWorkflowStatus.COMPLETED, null, null);

        continuationService.onWorkflowAdvanced(workflow.getId());

        assertThat(workflowRunMapper.selectById(workflow.getId()).getStatus())
                .isEqualTo(AiWorkflowStatus.COMPLETED.name());
    }

    @Test
    @DisplayName("WAITING_*：只刷新快照，不恢复、不动根 run 状态")
    void waitingWorkflowOnlyRefreshesSnapshot() {
        Fixture fixture = fixture(AiWorkflowStatus.WAITING_PLAN_CONFIRM);

        continuationService.onWorkflowAdvanced(fixture.workflow().getId());

        assertThat(runMapper.selectById(fixture.runId()).getStatus())
                .as("Workflow 还在等用户确认，恢复时机没到")
                .isEqualTo(AiAgentRunStatus.WAITING_WORKFLOW.name());
        assertThat(handoffRow(fixture.runId()).getStatus())
                .isEqualTo(AiAgentStepStatus.WAITING_CONFIRM.name());
    }

    @Test
    @DisplayName("FAILED：步骤转 WAITING_USER（可重试），根 run 保持等待态")
    void failedWorkflowKeepsWaitingForRetry() {
        Fixture fixture = fixture(AiWorkflowStatus.FAILED);

        continuationService.onWorkflowAdvanced(fixture.workflow().getId());

        assertThat(handoffRow(fixture.runId()).getStatus())
                .as("FAILED 是可重试故障态，不能提前消费成永久失败")
                .isEqualTo(AiAgentStepStatus.WAITING_USER.name());
        assertThat(runMapper.selectById(fixture.runId()).getStatus())
                .as("等用户决定重试还是放弃——这时候不能把 run 变成正在执行")
                .isEqualTo(AiAgentRunStatus.WAITING_WORKFLOW.name());
    }

    @Test
    @DisplayName("COMPLETED：CAS 抢权 → 续跑 → 收敛，Workflow token 差额只入账一次")
    void completedWorkflowResumesAndAccountsTokensOnce() {
        Fixture fixture = fixture(AiWorkflowStatus.COMPLETED);

        continuationService.onWorkflowAdvanced(fixture.workflow().getId());

        assertThat(runMapper.selectById(fixture.runId()).getStatus())
                .isEqualTo(AiAgentRunStatus.COMPLETED.name());
        AiAgentStep row = handoffRow(fixture.runId());
        assertThat(row.getStatus())
                .as("续跑会跳过已有结果的步骤，所以这一行的终态必须由恢复服务自己落定")
                .isEqualTo(AiAgentStepStatus.SUCCESS.name());
        assertThat(row.getOutputJson()).contains("workflowRunId");

        long after = messageMapper.selectById(fixture.messageId()).getTokenCount();
        assertThat(after - MESSAGE_TOKENS_BEFORE)
                .as("Workflow 的 120 token 追加到原消息")
                .isEqualTo(120L);

        // 事件重放 / 巡检并发：同一份终态再触发一次，既不能重复续跑也不能重复计费
        continuationService.onWorkflowAdvanced(fixture.workflow().getId());
        assertThat(messageMapper.selectById(fixture.messageId()).getTokenCount()).isEqualTo(after);
        assertThat(runMapper.selectById(fixture.runId()).getStatus())
                .isEqualTo(AiAgentRunStatus.COMPLETED.name());
    }

    @Test
    @DisplayName("复位等待态只能从 RUNNING 转换，不能覆盖已落下的终态")
    void resetToWaitingDoesNotOverwriteCompletedRun() {
        Fixture fixture = fixture(AiWorkflowStatus.COMPLETED);
        AiAgentRun completed = new AiAgentRun();
        completed.setId(fixture.runId());
        completed.setStatus(AiAgentRunStatus.COMPLETED.name());
        completed.setUpdatedAt(LocalDateTime.now());
        runMapper.updateById(completed);

        boolean reset = new TaskRunStateTransitions(runMapper)
                .resetWaitingFromRunning(fixture.runId());

        assertThat(reset).isFalse();
        assertThat(runMapper.selectById(fixture.runId()).getStatus())
                .isEqualTo(AiAgentRunStatus.COMPLETED.name());
    }

    @Test
    @DisplayName("消息更新失败：根 run、handoff 终态与 token 快照必须一起回滚")
    void messageUpdateFailureDoesNotPartiallyFinalizeContinuation() {
        Fixture fixture = fixture(AiWorkflowStatus.COMPLETED);
        AiMessageMapper failingMessageMapper = mock(
                AiMessageMapper.class, delegatesTo(messageMapper));
        doThrow(new IllegalStateException("message update failed"))
                .when(failingMessageMapper).updateById(any(AiMessages.class));
        TaskWorkflowContinuationService failingService = new TaskWorkflowContinuationService(
                runMapper, stepMapper, workflowRunMapper, failingMessageMapper,
                orchestrationService, snapshotCodec, renderer, objectMapper, stateTransitions,
                transactionTemplate,
                // 2026-10-17 计费补口：续跑现在要预扣/结算。mock 的 reserve 返回 null → 空转，
                // 本用例只验证短事务回滚，计费不影响断言。
                mock(com.hailin.blogsystem.ai.billing.AiRequestBillingSupport.class));

        failingService.onWorkflowAdvanced(fixture.workflow().getId());

        assertThat(runMapper.selectById(fixture.runId()).getStatus())
                .as("短事务失败后应释放恢复权，留给巡检重试")
                .isEqualTo(AiAgentRunStatus.WAITING_WORKFLOW.name());
        AiAgentStep row = handoffRow(fixture.runId());
        assertThat(row.getStatus()).isEqualTo(AiAgentStepStatus.WAITING_CONFIRM.name());
        assertThat(row.getOutputJson()).doesNotContain("accountedWorkflowTokens");
    }

    @Test
    @DisplayName("续跑收敛按路由基线 + 当前子树 + Workflow 绝对重算消息 Token")
    void recomputesAbsoluteMessageTokensIncludingPersistedPostHandoffChildRun() {
        Fixture fixture = fixture(AiWorkflowStatus.COMPLETED);
        AiAgentStep handoff = handoffRow(fixture.runId());
        AiAgentStep handoffPatch = new AiAgentStep();
        handoffPatch.setId(handoff.getId());
        handoffPatch.setOutputJson("{\"state\":\"WAITING_CONFIRM\",\"summary\":\"等你确认\"," +
                "\"messageRoutingTokens\":500}");
        stepMapper.updateById(handoffPatch);

        AiAgentRun child = new AiAgentRun();
        child.setUserId(USER);
        child.setSessionId(SESSION);
        child.setGoal("续跑后置分析");
        child.setStatus(AiAgentRunStatus.COMPLETED.name());
        child.setCurrentStep(1);
        child.setMaxSteps(1);
        child.setUsedSteps(1);
        child.setInputTokens(150);
        child.setOutputTokens(50);
        child.setTotalTokens(200);
        child.setParentRunId(fixture.runId());
        child.setCreatedAt(LocalDateTime.now());
        child.setUpdatedAt(LocalDateTime.now());
        runMapper.insert(child);
        createdRuns.add(child.getId());

        continuationService.onWorkflowAdvanced(fixture.workflow().getId());

        assertThat(messageMapper.selectById(fixture.messageId()).getTokenCount())
                .isEqualTo(820L);
    }

    private AiAgentStep handoffRow(Long runId) {
        return stepMapper.selectOne(new LambdaQueryWrapper<AiAgentStep>()
                .eq(AiAgentStep::getAgentRunId, runId)
                .eq(AiAgentStep::getTaskStepId, STEP_ID)
                .last("limit 1"));
    }

    /** 造一份"暂停在 handoff 上"的完整现场：根 run + 步骤行 + 关联 Workflow + 原消息 */
    private Fixture fixture(AiWorkflowStatus status) {
        LocalDateTime now = LocalDateTime.now();
        TaskPlan plan = new TaskPlan("帮我优化这篇文章", List.of(
                new TaskStep(STEP_ID, StepType.WORKFLOW_HANDOFF,
                        Map.of("workflowType", "OPTIMIZE_ARTICLE"), StepDependency.none(),
                        List.of("优化这篇文章"))), List.of());

        AiAgentRun run = new AiAgentRun();
        run.setUserId(USER);
        run.setSessionId(SESSION);
        run.setGoal(plan.goal());
        run.setStatus(AiAgentRunStatus.WAITING_WORKFLOW.name());
        run.setCurrentStep(1);
        run.setMaxSteps(1);
        run.setUsedSteps(1);
        run.setTaskPlanJson(snapshotCodec.write(plan, null));
        run.setInputTokens(0);
        run.setOutputTokens(0);
        run.setTotalTokens(0);
        run.setCreatedAt(now);
        run.setUpdatedAt(now);
        runMapper.insert(run);
        createdRuns.add(run.getId());

        AiAgentStep row = new AiAgentStep();
        row.setAgentRunId(run.getId());
        row.setStepNo(1);
        row.setTaskStepId(STEP_ID);
        row.setActionType(AgentStepActionType.TASK_STEP.name());
        row.setInputJson("{}");
        row.setOutputJson("{\"state\":\"WAITING_CONFIRM\",\"summary\":\"等你确认\"}");
        row.setStatus(AiAgentStepStatus.WAITING_CONFIRM.name());
        row.setDurationMs(0L);
        row.setInputTokens(0);
        row.setOutputTokens(0);
        row.setCreatedAt(now);
        stepMapper.insert(row);

        AiWorkflowRun workflow = insertWorkflow(status, run.getId(), STEP_ID);

        AiMessages message = new AiMessages();
        message.setSessionId(SESSION);
        message.setAgentRunId(run.getId());
        message.setRole("assistant");
        message.setContent("我先点了赞，优化流程还等你确认。");
        message.setTokenCount(MESSAGE_TOKENS_BEFORE);
        message.setCreatedAt(now);
        messageMapper.insert(message);
        createdMessages.add(message.getId());

        return new Fixture(run.getId(), workflow, message.getId());
    }

    private AiWorkflowRun insertWorkflow(AiWorkflowStatus status, Long taskRunId, String taskStepId) {
        LocalDateTime now = LocalDateTime.now();
        AiWorkflowRun workflow = new AiWorkflowRun();
        workflow.setUserId(USER);
        workflow.setConversationId(SESSION);
        workflow.setWorkflowType("OPTIMIZE_ARTICLE");
        workflow.setWorkflowVersion("1.0");
        workflow.setStatus(status.name());
        workflow.setCurrentStep("FILL_ARTICLE");
        workflow.setContextJson("{}");
        workflow.setRetryCount(0);
        workflow.setVersion(3);
        workflow.setInputTokens(60);
        workflow.setOutputTokens(60);
        workflow.setTotalTokens(120);
        workflow.setTaskRunId(taskRunId);
        workflow.setTaskStepId(taskStepId);
        workflow.setCreatedAt(now);
        workflow.setUpdatedAt(now);
        workflowRunMapper.insert(workflow);
        createdWorkflows.add(workflow.getId());
        return workflow;
    }
}
