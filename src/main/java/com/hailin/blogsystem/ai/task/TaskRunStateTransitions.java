package com.hailin.blogsystem.ai.task;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hailin.blogsystem.entity.AiAgentRun;
import com.hailin.blogsystem.entity.dto.AiAgentRunStatus;
import com.hailin.blogsystem.mapper.AiAgentRunMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** Task run 状态转换入口。 */
@Component
@RequiredArgsConstructor
public class TaskRunStateTransitions {

    private final AiAgentRunMapper runMapper;

    public boolean completeFromRunning(Long runId, int usedSteps) {
        AiAgentRun patch = patch(AiAgentRunStatus.COMPLETED, usedSteps, null);
        return updateFrom(runId, AiAgentRunStatus.RUNNING, patch, null);
    }

    public boolean pauseFromRunning(Long runId, AiAgentRunStatus targetStatus, int usedSteps) {
        if (targetStatus != AiAgentRunStatus.RUNNING
                && targetStatus != AiAgentRunStatus.WAITING_WORKFLOW) {
            throw new IllegalArgumentException("不支持的 Task 暂停状态: " + targetStatus);
        }
        AiAgentRun patch = patch(targetStatus, usedSteps, null);
        return updateFrom(runId, AiAgentRunStatus.RUNNING, patch, null);
    }

    public boolean failFromRunning(Long runId, String reason) {
        AiAgentRun patch = patch(AiAgentRunStatus.FAILED, null, reason);
        return updateFrom(runId, AiAgentRunStatus.RUNNING, patch, null);
    }

    public boolean claimResumeFromWaiting(Long runId) {
        AiAgentRun patch = patch(AiAgentRunStatus.RUNNING, null, null);
        return updateFrom(runId, AiAgentRunStatus.WAITING_WORKFLOW, patch, null);
    }

    public boolean resetWaitingFromRunning(Long runId) {
        AiAgentRun patch = patch(AiAgentRunStatus.WAITING_WORKFLOW, null, null);
        return updateFrom(runId, AiAgentRunStatus.RUNNING, patch, null);
    }

    public boolean resetWaitingFromStaleRunning(Long runId, LocalDateTime staleBefore) {
        AiAgentRun patch = patch(AiAgentRunStatus.WAITING_WORKFLOW, null, null);
        return updateFrom(runId, AiAgentRunStatus.RUNNING, patch, staleBefore);
    }

    public boolean failStaleRunning(Long runId, LocalDateTime staleBefore, String reason) {
        AiAgentRun patch = patch(AiAgentRunStatus.FAILED, null, reason);
        return updateFrom(runId, AiAgentRunStatus.RUNNING, patch, staleBefore);
    }

    private AiAgentRun patch(AiAgentRunStatus status, Integer usedSteps, String errorMessage) {
        AiAgentRun patch = new AiAgentRun();
        patch.setStatus(status.name());
        patch.setUsedSteps(usedSteps);
        patch.setErrorMessage(errorMessage);
        patch.setUpdatedAt(LocalDateTime.now());
        return patch;
    }

    private boolean updateFrom(Long runId, AiAgentRunStatus expectedStatus,
                               AiAgentRun patch, LocalDateTime updatedBefore) {
        LambdaUpdateWrapper<AiAgentRun> condition = new LambdaUpdateWrapper<AiAgentRun>()
                .eq(AiAgentRun::getId, runId)
                .eq(AiAgentRun::getStatus, expectedStatus.name());
        if (updatedBefore != null) {
            condition.lt(AiAgentRun::getUpdatedAt, updatedBefore);
        }
        return runMapper.update(patch, condition) == 1;
    }
}
