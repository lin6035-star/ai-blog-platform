package com.hailin.blogsystem.ai.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hailin.blogsystem.entity.AiAgentRun;
import com.hailin.blogsystem.entity.AiWorkflowRun;
import com.hailin.blogsystem.entity.dto.AiAgentRunStatus;
import com.hailin.blogsystem.entity.dto.AiWorkflowStatus;
import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.mapper.AiAgentRunMapper;
import com.hailin.blogsystem.mapper.AiWorkflowRunMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 外层任务恢复的**低频补偿扫描**（5b，设计稿 §8.2 / §8.3）。
 *
 * <p>只靠进程内事件不够：服务可能在"Workflow 已提交、恢复还没执行"之间崩溃，那次事件就永远丢了。
 * 这里定期把这种记录捞回来，调用**同一个幂等入口** {@link TaskWorkflowContinuationService}。
 *
 * <p>扫两类：
 * <ol>
 *   <li><b>仍在等待的根 run</b>：捞出已终态的关联 Workflow 去续跑。
 *       入口选"等待方"而不是"所有终态 Workflow"，是因为后者每轮都会捞到同一批早已处理完的历史记录——
 *       功能上幂等，但每 60 秒白扫一遍并刷日志。</li>
 *   <li><b>启动恢复刻意跳过的外层 Task run</b>（{@code RUNNING} + 有步骤快照）：
 *       它们不能被当成残留判死（快照还在），但也不能永远停在 RUNNING 没人管。
 *       按关联 Workflow 的实际情况分三种处理，见 {@link #handleOrphanRunningTaskRuns()}。</li>
 * </ol>
 *
 * <p>不上 MQ、不加重试表：第一版的补偿范围就这么大，加基础设施反而更难讲清边界。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TaskHandoffReconciliationTask {

    private static final String LOG_PREFIX = "[TASK-CONTINUE]";
    /** 每批上限：补偿是兜底路径，不该和正常请求抢资源 */
    private static final int BATCH = 20;
    private static final long FIXED_DELAY_MS = 60_000L;

    private final AiAgentRunMapper runMapper;
    private final AiWorkflowRunMapper workflowRunMapper;
    private final TaskWorkflowContinuationService continuationService;
    private final TaskRunStateTransitions stateTransitions;
    private final BlogAiProperties properties;

    /** 启动后先等一会再扫：让启动恢复（{@code AiRunStartupRecoveryRunner}）先跑完 */
    @Scheduled(initialDelay = 120_000L, fixedDelay = FIXED_DELAY_MS)
    public void reconcile() {
        handleWaitingRuns();
        handleOrphanRunningTaskRuns();
    }

    /** ① 还在等待的根 run：关联 Workflow 已经终态的，去续跑 */
    private void handleWaitingRuns() {
        List<AiWorkflowRun> finished =
                workflowRunMapper.selectTerminalLinkedToWaitingTaskRuns(BATCH);
        if (finished.isEmpty()) {
            return;
        }

        int handled = 0;
        for (AiWorkflowRun workflow : finished) {
            try {
                continuationService.onWorkflowAdvanced(workflow.getId());
                handled++;
            } catch (RuntimeException e) {
                // 单条失败不能拖垮整批：下一轮还会再来
                log.warn("{} 巡检补偿单条失败: workflowRunId={}", LOG_PREFIX, workflow.getId(), e);
            }
        }
        log.info("{} 巡检补偿: 已终态且父任务仍等待={}, 本轮处理={}",
                LOG_PREFIX, finished.size(), handled);
    }

    /**
     * ② 启动遗留的"外层 Task run 停在 RUNNING"（设计稿 §8.3）。
     *
     * <p>它们被启动恢复**刻意跳过**（有步骤快照、不该直接判死），所以必须有人接手，
     * 否则会永远显示成"正在执行"。按关联 Workflow 分三种：
     * <ul>
     *   <li>已终态 → 复位成等待态后交给续跑（CAS 要求它必须是 {@code WAITING_WORKFLOW}）；</li>
     *   <li>未终态 → 复位成等待态（当时就是停在 handoff 上等它）；</li>
     *   <li>没有关联 Workflow → 崩在 handoff 创建之前，没有可用的恢复事实，
     *       **如实判失败**。这是第一版的边界：不恢复"崩在中途写步骤"的任务。</li>
     * </ul>
     */
    private void handleOrphanRunningTaskRuns() {
        long staleMinutes = Math.max(1L, properties.getTask().getHandoffRecoveryStaleMinutes());
        LocalDateTime staleBefore = LocalDateTime.now().minusMinutes(staleMinutes);
        List<AiAgentRun> orphans = runMapper.selectList(new LambdaQueryWrapper<AiAgentRun>()
                .select(AiAgentRun::getId)
                .isNotNull(AiAgentRun::getTaskPlanJson)
                .eq(AiAgentRun::getStatus, AiAgentRunStatus.RUNNING.name())
                .lt(AiAgentRun::getUpdatedAt, staleBefore)
                .orderByAsc(AiAgentRun::getId)
                .last("limit " + BATCH));
        if (orphans.isEmpty()) {
            return;
        }

        for (AiAgentRun orphan : orphans) {
            AiWorkflowRun workflow = workflowRunMapper.selectOne(new LambdaQueryWrapper<AiWorkflowRun>()
                    .eq(AiWorkflowRun::getTaskRunId, orphan.getId())
                    .orderByDesc(AiWorkflowRun::getId)
                    .last("limit 1"));

            if (workflow == null) {
                log.warn("{} 外层任务无可恢复的关联流程，判失败: taskRunId={}", LOG_PREFIX, orphan.getId());
                stateTransitions.failStaleRunning(
                        orphan.getId(), staleBefore, "任务在恢复前被中断");
                continue;
            }

            if (!stateTransitions.resetWaitingFromStaleRunning(orphan.getId(), staleBefore)) {
                continue;
            }
            if (isTerminal(workflow.getStatus())) {
                try {
                    continuationService.onWorkflowAdvanced(workflow.getId());
                } catch (RuntimeException e) {
                    log.warn("{} 遗留任务续跑失败: taskRunId={}", LOG_PREFIX, orphan.getId(), e);
                }
            }
        }
        log.info("{} 遗留外层任务巡检: 处理 {} 条", LOG_PREFIX, orphans.size());
    }

    private static String[] terminalStatuses() {
        return new String[]{
                AiWorkflowStatus.COMPLETED.name(),
                AiWorkflowStatus.CANCELLED.name(),
                AiWorkflowStatus.FAILED.name()
        };
    }

    private static boolean isTerminal(String status) {
        for (String terminal : terminalStatuses()) {
            if (terminal.equals(status)) {
                return true;
            }
        }
        return false;
    }
}
