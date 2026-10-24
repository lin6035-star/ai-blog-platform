package com.hailin.blogsystem.ai.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.ai.billing.AiRequestBillingSupport;
import com.hailin.blogsystem.ai.billing.BillingHandle;
import com.hailin.blogsystem.entity.AiAgentRun;
import com.hailin.blogsystem.entity.AiAgentStep;
import com.hailin.blogsystem.entity.AiMessages;
import com.hailin.blogsystem.entity.AiWorkflowRun;
import com.hailin.blogsystem.entity.dto.AiAgentRunStatus;
import com.hailin.blogsystem.entity.dto.AiAgentStepStatus;
import com.hailin.blogsystem.entity.dto.AiWorkflowStatus;
import com.hailin.blogsystem.mapper.AiAgentRunMapper;
import com.hailin.blogsystem.mapper.AiAgentStepMapper;
import com.hailin.blogsystem.mapper.AiMessageMapper;
import com.hailin.blogsystem.mapper.AiWorkflowRunMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 外层 TaskPlan 因 {@code WORKFLOW_HANDOFF} 暂停后的**恢复协调者**（5b 阶段 2）。
 *
 * <p><b>它是幂等的，而且是唯一入口</b>：事件通知、启动巡检、低频巡检、详情查询补偿
 * 都调 {@link #onWorkflowAdvanced(Long)}。同一份终态被多方同时触发时，
 * 靠根 run 的 {@code status} CAS 决出唯一恢复者——所以这里不需要分布式锁。
 *
 * <p><b>它不做的事</b>：不调 LLM、不重新规划、不重跑已完成的步骤、不新建 run 或步骤行。
 * 续跑本身交给 {@link TaskOrchestrationService#resume}，协议层的"从第一个 PENDING 继续"
 * 交给 {@code TaskOrchestrator.resume}——这里只负责"什么时候可以续、依据是什么事实"。
 *
 * <p>不抛异常：触发它的地方可能是"Workflow 已经提交成功"之后的回调，
 * 让恢复失败反过来把一次成功的操作表现成失败是不对的（设计稿 §8.1）。
 * 失败时把根 run 复位成等待态，留给巡检重试。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TaskWorkflowContinuationService {

    private static final String LOG_PREFIX = "[TASK-CONTINUE]";

    private final AiAgentRunMapper runMapper;
    private final AiAgentStepMapper stepMapper;
    private final AiWorkflowRunMapper workflowRunMapper;
    private final AiMessageMapper messageMapper;
    private final TaskOrchestrationService orchestrationService;
    private final TaskPlanSnapshotCodec snapshotCodec;
    private final TaskResultRenderer renderer;
    private final ObjectMapper objectMapper;
    private final TaskRunStateTransitions stateTransitions;
    private final TransactionTemplate transactionTemplate;
    /** 2026-10-17 计费补口：续跑会执行剩余步骤（可能含 LLM），此前完全免费。 */
    private final AiRequestBillingSupport billingSupport;

    public void onWorkflowAdvanced(Long workflowRunId) {
        if (workflowRunId == null) {
            return;
        }
        AiWorkflowRun workflow = workflowRunMapper.selectById(workflowRunId);
        if (workflow == null) {
            return;
        }
        Long taskRunId = workflow.getTaskRunId();
        String taskStepId = workflow.getTaskStepId();
        if (taskRunId == null || taskStepId == null || taskStepId.isBlank()) {
            return;   // 普通独立 Workflow，与 5b 无关
        }

        AiAgentRun rootRun = runMapper.selectById(taskRunId);
        AiAgentStep handoffRow = findStepRow(taskRunId, taskStepId);
        if (rootRun == null || handoffRow == null) {
            log.warn("{} 关联记录不完整，跳过: workflowRunId={}, taskRunId={}",
                    LOG_PREFIX, workflowRunId, taskRunId);
            return;
        }
        // 归属一致性：父 run 与 Workflow 必须同属一个用户，否则是关联串了，宁可不动
        if (!Objects.equals(rootRun.getUserId(), workflow.getUserId())) {
            log.warn("{} 归属不一致，跳过: taskRunId={}, workflowRunId={}",
                    LOG_PREFIX, taskRunId, workflowRunId);
            return;
        }

        String status = workflow.getStatus();

        // ① 还在跑 / 还在等用户确认：只刷新快照。恢复时机是「Workflow 走到终态之后」
        if (!isTerminal(status)) {
            writeOutput(handoffRow, WorkflowHandoffResults.output(workflow));
            return;
        }

        // ② FAILED 是**可重试故障态**：步骤转 WAITING_USER，根 run 保持 WAITING_WORKFLOW。
        //    这里刻意不走下面的 CAS——那会把"等用户决定重试还是放弃"变成一个正在执行的任务。
        //    版本没变就不重复写：巡检每轮都会捞到同一个可重试失败快照，
        //    反复消费它只会刷日志（设计稿 §8.2）
        if (AiWorkflowStatus.FAILED.name().equals(status)) {
            JsonNode existing = readOutput(handoffRow);
            boolean sameVersion = existing != null
                    && existing.path("latestWorkflowRunVersion").asInt(-1) == versionOf(workflow);
            if (!sameVersion) {
                markHandoffWaitingUser(handoffRow, workflow);
            }
            return;
        }

        // ③ COMPLETED / CANCELLED：CAS 抢恢复权
        if (!stateTransitions.claimResumeFromWaiting(taskRunId)) {
            // 没抢到 = 别人已经在恢复（或恢复完了）。幂等返回，绝不重复续跑
            log.info("{} 未抢到恢复权（已有触发者处理）: taskRunId={}", LOG_PREFIX, taskRunId);
            return;
        }

        try {
            resumeRemaining(rootRun, handoffRow, workflow);
        } catch (RuntimeException e) {
            // CAS 已经把 run 置成 RUNNING。复位成等待态，让巡检还能再试一次；
            // 直接判失败会把"可重试"变成"已死"，而这里根本没有证据说明它不可恢复
            log.error("{} 续跑失败，复位等待态待巡检重试: taskRunId={}", LOG_PREFIX, taskRunId, e);
            stateTransitions.resetWaitingFromRunning(taskRunId);
        }
    }

    private void resumeRemaining(AiAgentRun rootRun, AiAgentStep handoffRow, AiWorkflowRun workflow) {
        TaskPlanSnapshotCodec.Snapshot snapshot = snapshotCodec.read(rootRun.getTaskPlanJson());
        if (snapshot == null) {
            // 快照坏了就不能续：宁可不恢复，也不要拿半份计划去执行——
            // 那会把"缺字段"变成"做错事"
            log.error("{} 计划快照不可用，无法续跑: taskRunId={}", LOG_PREFIX, rootRun.getId());
            stateTransitions.failFromRunning(rootRun.getId(), "任务计划已不可恢复");
            return;
        }
        TaskPlan plan = snapshot.plan();

        // 历史结果 = 已持久化的步骤结果 + 本步骤的 Workflow 终态。
        // 有了它，后置步骤的 requires 才能在**执行前**拿到前置数据（否则会被误判成 SKIPPED）
        Map<String, StepResult> persisted = persistedResults(rootRun.getId());
        StepResult handoffResult = WorkflowHandoffResults.toStepResult(handoffRow.getTaskStepId(), workflow);
        persisted.put(handoffRow.getTaskStepId(), handoffResult);

        /*
         * 2026-10-17 计费补口：续跑会执行剩余步骤（可能含 LLM，如 ARTICLE_ANALYSIS 子 run）。
         * 暂停那一刻（handoff 触发）已结算过已花的钱，但**续跑这部分此前完全免费**。
         * 结算依据 = 续跑前后 root run 子树的 token 差量；触发方可能是用户请求的
         * AFTER_COMMIT 回调，也可能是巡检线程，所以归属用户取自 rootRun 而不是 UserContext。
         * 余额不足时 reserve 会抛 402 语义的异常 → 外层复位等待态，用户充值后巡检可重试。
         */
        long tokensBefore = orchestrationService.subtreeTokens(rootRun.getId());
        BillingHandle resumeBilling = billingSupport.reserve(
                AiRequestBillingSupport.BIZ_AGENT,
                "TASK:RESUME:" + rootRun.getId() + ":" + handoffRow.getId(),
                rootRun.getUserId());
        try {
            OrchestrationOutcome outcome = orchestrationService.resume(rootRun, plan, snapshot.anchor(), persisted);

            // 剩余步骤已经在事务外执行并逐项落事实。最后只用一个短事务收敛四件事：
            // handoff 终态、token 入账快照、原消息、根 run 终态。
            // 任一写失败全部回滚，外层 catch 再把 RUNNING 复位成 WAITING_WORKFLOW。
            transactionTemplate.executeWithoutResult(status -> {
                AiMessages message = findOriginalMessage(rootRun);
                long routingTokens = messageRoutingTokens(rootRun, handoffRow, message);
                finishHandoffRow(handoffRow, workflow, handoffResult, routingTokens);
                updateOriginalMessage(rootRun, outcome, workflow, routingTokens, message);
                orchestrationService.finalizeResumedRun(rootRun, plan, outcome);
            });

            long consumed = Math.max(0L,
                    orchestrationService.subtreeTokens(rootRun.getId()) - tokensBefore);
            billingSupport.bindResource(resumeBilling, String.valueOf(rootRun.getId()));
            billingSupport.close(resumeBilling, consumed);

            log.info("{} 续跑结束: taskRunId={}, paused={}, steps={}, billedTokens={}",
                    LOG_PREFIX, rootRun.getId(), outcome.paused(), outcome.results().size(), consumed);
        } catch (RuntimeException e) {
            billingSupport.abort(resumeBilling);
            throw e;
        }
    }

    /**
     * **一次写入** handoff 步骤行：终态 + 最新 Workflow 快照 + token 入账 + 原消息文案。
     *
     * <p>"抢到恢复权的人负责把这一行落成终态"（设计稿 §7.6 第 7 步）是必须的：
     * 续跑会**跳过**已有结果的步骤、不再回调 listener，没有别人会替它写这一行——
     * 漏了它，步骤行会永远停在 {@code WAITING_CONFIRM}，看起来像"还在等确认"。
     */
    private void finishHandoffRow(AiAgentStep handoffRow, AiWorkflowRun workflow,
                                  StepResult handoffResult, long routingTokens) {
        Map<String, Object> merged = existingOutput(handoffRow);
        merged.putAll(WorkflowHandoffResults.output(workflow));
        merged.put("state", handoffResult.state().name());
        merged.put("summary", handoffResult.summary());
        if (handoffResult.code() != null) {
            merged.put("code", handoffResult.code().name());
        }

        merged.put("messageRoutingTokens", routingTokens);
        merged.put("accountedWorkflowTokens", totalTokens(workflow));
        merged.put("accountedWorkflowVersion", versionOf(workflow));

        AiAgentStep patch = new AiAgentStep();
        patch.setId(handoffRow.getId());
        patch.setStatus(toStepStatus(handoffResult).name());
        patch.setOutputJson(toJson(merged));
        if (handoffResult.state() == StepState.FAILED) {
            patch.setErrorMessage(handoffResult.summary());
        }
        if (stepMapper.updateById(patch) != 1) {
            throw new IllegalStateException("handoff 终态更新失败: stepId=" + handoffRow.getTaskStepId());
        }
    }

    /** 与 {@code TaskOrchestrationService} 的落库口径一致：穷尽枚举、不留 default */
    private static AiAgentStepStatus toStepStatus(StepResult result) {
        return switch (result.state()) {
            case SUCCEEDED, DISPATCHED -> AiAgentStepStatus.SUCCESS;
            case SKIPPED -> AiAgentStepStatus.SKIPPED;
            case PENDING -> AiAgentStepStatus.PENDING;
            case RUNNING -> AiAgentStepStatus.RUNNING;
            case WAITING_CONFIRM -> AiAgentStepStatus.WAITING_CONFIRM;
            case WAITING_USER -> AiAgentStepStatus.WAITING_USER;
            case FAILED -> AiAgentStepStatus.FAILED;
        };
    }

    /**
     * 已持久化的步骤结果（**只取外层步骤**：内部 Agent 步骤的 {@code task_step_id} 为 null）。
     *
     * <p>PENDING / RUNNING / 读不出来的行**不算历史结果**——它们恰恰是"要接着跑"的部分。
     */
    private Map<String, StepResult> persistedResults(Long runId) {
        Map<String, StepResult> results = new LinkedHashMap<>();
        List<AiAgentStep> rows = stepMapper.selectList(new LambdaQueryWrapper<AiAgentStep>()
                .eq(AiAgentStep::getAgentRunId, runId));
        for (AiAgentStep row : rows) {
            if (row.getTaskStepId() == null || row.getTaskStepId().isBlank()) {
                continue;
            }
            StepResult result = readResult(row);
            if (result != null) {
                results.put(row.getTaskStepId(), result);
            }
        }
        return results;
    }

    /**
     * 从 {@code output_json} 还原执行事实。
     *
     * <p>这里直接用 record 的规范构造器而不是工厂：工厂按状态分组是为了**防止构造出非法组合**，
     * 而这里是从已存在的事实反序列化，不是新造一个结果——用工厂反而会丢掉原始 code。
     */
    private StepResult readResult(AiAgentStep row) {
        String json = row.getOutputJson();
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            StepState state = StepState.valueOf(root.path("state").asText());
            if (state == StepState.PENDING || state == StepState.RUNNING) {
                return null;   // 没跑完的当没跑过
            }
            return new StepResult(row.getTaskStepId(), state,
                    parseCode(root.path("code").asText(null)),
                    root.path("summary").asText(null),
                    readData(root.path("data")));
        } catch (Exception e) {
            log.warn("{} 步骤结果读不出来，按未执行处理: stepId={}", LOG_PREFIX, row.getTaskStepId());
            return null;
        }
    }

    /**
     * 更新**原** assistant message（§11.3）：收敛时改它的汇总内容，不额外插一条"幽灵回复"，
     * 保持"一次用户请求 = 一条消息"。
     *
     * <p>Token 差额在同一次写入里完成（§12），避免一条消息被改两遍。
     */
    private AiMessages findOriginalMessage(AiAgentRun rootRun) {
        AiMessages message = messageMapper.selectOne(new LambdaQueryWrapper<AiMessages>()
                .eq(AiMessages::getAgentRunId, rootRun.getId())
                .last("limit 1"));
        if (message == null) {
            throw new IllegalStateException("找不到原消息: taskRunId=" + rootRun.getId());
        }
        return message;
    }

    private void updateOriginalMessage(AiAgentRun rootRun, OrchestrationOutcome outcome,
                                       AiWorkflowRun workflow, long routingTokens, AiMessages message) {
        AiMessages patch = new AiMessages();
        patch.setId(message.getId());
        patch.setContent(renderer.render(outcome));
        patch.setTokenCount(routingTokens
                + orchestrationService.subtreeTokens(rootRun.getId())
                + totalTokens(workflow));
        if (messageMapper.updateById(patch) != 1) {
            throw new IllegalStateException("原消息更新失败: taskRunId=" + rootRun.getId());
        }
    }

    /**
     * 路由基线优先读暂停时固化的字段；历史记录没有时只反推一次，并随终态事务写回。
     */
    private long messageRoutingTokens(AiAgentRun rootRun, AiAgentStep handoffRow, AiMessages message) {
        Map<String, Object> output = existingOutput(handoffRow);
        Object stored = output.get("messageRoutingTokens");
        if (stored instanceof Number number) {
            return Math.max(0L, number.longValue());
        }
        long messageTokens = message.getTokenCount() == null ? 0L : message.getTokenCount();
        Object accounted = output.get("accountedWorkflowTokens");
        long accountedWorkflowTokens = accounted instanceof Number number ? number.longValue() : 0L;
        return Math.max(0L, messageTokens
                - orchestrationService.subtreeTokens(rootRun.getId())
                - accountedWorkflowTokens);
    }

    private void markHandoffWaitingUser(AiAgentStep handoffRow, AiWorkflowRun workflow) {
        AiAgentStep patch = new AiAgentStep();
        patch.setId(handoffRow.getId());
        patch.setStatus(AiAgentStepStatus.WAITING_USER.name());
        patch.setErrorMessage("优化流程中断了，可以重试或取消");
        stepMapper.updateById(patch);

        // 快照同步最新状态与版本：retry 成功后版本会变，差额入账才有依据
        writeOutput(handoffRow, WorkflowHandoffResults.output(workflow));
    }

    private AiAgentStep findStepRow(Long taskRunId, String taskStepId) {
        return stepMapper.selectOne(new LambdaQueryWrapper<AiAgentStep>()
                .eq(AiAgentStep::getAgentRunId, taskRunId)
                .eq(AiAgentStep::getTaskStepId, taskStepId)
                .last("limit 1"));
    }

    /** 合并写入 output_json：不能把执行器写的 state / summary 冲掉 */
    private void writeOutput(AiAgentStep handoffRow, Map<String, Object> values) {
        Map<String, Object> merged = existingOutput(handoffRow);
        merged.putAll(values);
        AiAgentStep patch = new AiAgentStep();
        patch.setId(handoffRow.getId());
        patch.setOutputJson(toJson(merged));
        stepMapper.updateById(patch);
    }

    private Map<String, Object> existingOutput(AiAgentStep handoffRow) {
        Map<String, Object> merged = new LinkedHashMap<>();
        JsonNode existing = readOutput(handoffRow);
        if (existing != null && existing.isObject()) {
            existing.fields().forEachRemaining(entry ->
                    merged.put(entry.getKey(), objectMapper.convertValue(entry.getValue(), Object.class)));
        }
        return merged;
    }

    private JsonNode readOutput(AiAgentStep handoffRow) {
        String json = handoffRow.getOutputJson();
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            log.warn("{} handoff 快照解析失败（按空处理）: stepId={}", LOG_PREFIX, handoffRow.getTaskStepId());
            return null;
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("{} handoff 快照序列化失败", LOG_PREFIX, e);
            return "{}";
        }
    }

    private static StepCode parseCode(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;   // 等待态本来就没有 code
        }
        try {
            return StepCode.valueOf(raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readData(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Map.of();
        }
        return objectMapper.convertValue(node, Map.class);
    }

    private static boolean isTerminal(String status) {
        return AiWorkflowStatus.COMPLETED.name().equals(status)
                || AiWorkflowStatus.CANCELLED.name().equals(status)
                || AiWorkflowStatus.FAILED.name().equals(status);
    }

    private static long totalTokens(AiWorkflowRun workflow) {
        return workflow.getTotalTokens() == null ? 0L : workflow.getTotalTokens();
    }

    private static int versionOf(AiWorkflowRun workflow) {
        return workflow.getVersion() == null ? 0 : workflow.getVersion();
    }
}
