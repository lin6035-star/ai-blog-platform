package com.hailin.blogsystem.ai.task;

import com.hailin.blogsystem.entity.AiAgentRun;
import com.hailin.blogsystem.entity.AiWorkflowRun;
import com.hailin.blogsystem.entity.dto.AiAgentRunStatus;
import com.hailin.blogsystem.entity.dto.AiWorkflowStatus;
import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.mapper.AiAgentRunMapper;
import com.hailin.blogsystem.mapper.AiWorkflowRunMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@SpringBootTest
class TaskHandoffReconciliationTaskTests {

    private static final Long USER = 901L;
    private static final Long SESSION = 902L;

    @Autowired
    private AiAgentRunMapper runMapper;
    @Autowired
    private AiWorkflowRunMapper workflowRunMapper;

    private final List<Long> runIds = new ArrayList<>();
    private final List<Long> workflowIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        workflowIds.forEach(workflowRunMapper::deleteById);
        runIds.forEach(runMapper::deleteById);
        workflowIds.clear();
        runIds.clear();
    }

    @Test
    void freshRunningTaskIsNotReclaimed() {
        AiAgentRun run = insertRun(AiAgentRunStatus.RUNNING, LocalDateTime.now());
        TaskWorkflowContinuationService continuation = mock(TaskWorkflowContinuationService.class);

        task(continuation).reconcile();

        assertThat(runMapper.selectById(run.getId()).getStatus())
                .as("仍可能在正常执行的新鲜任务不能被巡检判死")
                .isEqualTo(AiAgentRunStatus.RUNNING.name());
    }

    @Test
    void terminalWorkflowBehindTwentyAbandonedWaitingRunsIsStillSelected() {
        for (int i = 0; i < 20; i++) {
            insertRun(AiAgentRunStatus.WAITING_WORKFLOW, LocalDateTime.now().minusHours(2));
        }
        AiAgentRun targetRun = insertRun(
                AiAgentRunStatus.WAITING_WORKFLOW, LocalDateTime.now().minusHours(1));
        AiWorkflowRun terminal = insertWorkflow(
                AiWorkflowStatus.COMPLETED, targetRun.getId(), "target-step");
        TaskWorkflowContinuationService continuation = mock(TaskWorkflowContinuationService.class);

        task(continuation).reconcile();

        verify(continuation).onWorkflowAdvanced(terminal.getId());
    }

    private TaskHandoffReconciliationTask task(TaskWorkflowContinuationService continuation) {
        BlogAiProperties properties = new BlogAiProperties();
        properties.getTask().setHandoffRecoveryStaleMinutes(15);
        return new TaskHandoffReconciliationTask(
                runMapper, workflowRunMapper, continuation,
                new TaskRunStateTransitions(runMapper), properties);
    }

    private AiAgentRun insertRun(AiAgentRunStatus status, LocalDateTime updatedAt) {
        AiAgentRun run = new AiAgentRun();
        run.setUserId(USER);
        run.setSessionId(SESSION);
        run.setGoal("巡检测试");
        run.setStatus(status.name());
        run.setCurrentStep(1);
        run.setMaxSteps(2);
        run.setUsedSteps(1);
        run.setTaskPlanJson("{}");
        run.setInputTokens(0);
        run.setOutputTokens(0);
        run.setTotalTokens(0);
        run.setCreatedAt(updatedAt.minusMinutes(1));
        run.setUpdatedAt(updatedAt);
        runMapper.insert(run);
        runIds.add(run.getId());
        return run;
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
        workflow.setVersion(1);
        workflow.setInputTokens(0);
        workflow.setOutputTokens(0);
        workflow.setTotalTokens(0);
        workflow.setTaskRunId(taskRunId);
        workflow.setTaskStepId(taskStepId);
        workflow.setCreatedAt(now);
        workflow.setUpdatedAt(now);
        workflowRunMapper.insert(workflow);
        workflowIds.add(workflow.getId());
        return workflow;
    }
}
