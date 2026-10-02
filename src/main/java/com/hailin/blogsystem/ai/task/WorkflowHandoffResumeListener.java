package com.hailin.blogsystem.ai.task;

import com.hailin.blogsystem.ai.workflow.WorkflowAdvancedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 把"Workflow 状态推进"接到外层任务恢复（5b，设计稿 §8.1）。
 *
 * <p>两条刻意的选择：
 * <ul>
 *   <li><b>{@code fallbackExecution = true}</b>：Workflow 的保存不一定在事务里
 *       （某些路径是单条 UPDATE 自动提交）。不加这个开关，那些路径的恢复会被**静默跳过**——
 *       而"少恢复一次"的表现是"用户点了确认，外层计划永远不动"，极难定位。</li>
 *   <li><b>异常必须吞掉并记录</b>：Workflow 已经提交成功了，恢复失败不能反过来把一次成功的操作
 *       表现成 HTTP 失败（设计稿 §8.1）。真正的兜底是巡检补偿。</li>
 * </ul>
 *
 * <p>为什么不复用项目里的 {@code AfterCommitExecutor}：它的边界是 Redis / 内存动作，
 * 并且会吞掉 after-commit 异常；5b 的 DB 恢复需要独立事务、独立日志和可补偿入口。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WorkflowHandoffResumeListener {

    private final TaskWorkflowContinuationService continuationService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAdvanced(WorkflowAdvancedEvent event) {
        if (event == null || event.workflowRunId() == null) {
            return;
        }
        try {
            continuationService.onWorkflowAdvanced(event.workflowRunId());
        } catch (RuntimeException e) {
            log.error("[TASK-CONTINUE] AFTER_COMMIT 触发失败（交给巡检补偿）: workflowRunId={}",
                    event.workflowRunId(), e);
        }
    }
}
