package com.hailin.blogsystem.ai.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.entity.AiAgentRun;
import com.hailin.blogsystem.entity.AiAgentStep;
import com.hailin.blogsystem.entity.dto.AgentStepActionType;
import com.hailin.blogsystem.entity.dto.AiAgentRunStatus;
import com.hailin.blogsystem.entity.dto.AiAgentStepStatus;
import com.hailin.blogsystem.entity.dto.PageContextDTO;
import com.hailin.blogsystem.mapper.AiAgentRunMapper;
import com.hailin.blogsystem.mapper.AiAgentStepMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务编排的应用服务（第三刀·2b）——**唯一负责 run/step 持久化的地方**。
 *
 * <p>为什么不让 {@link TaskOrchestrator} 自己建 run：它是**纯协议**（不知道 run / SSE / 数据库），
 * 第一步的 17 例因此不依赖任何环境。持久化通过 {@link TaskStepListener} 挂进来，
 * **Orchestrator 一行不改**——listener 就是为这个预留的。
 *
 * <p>run/step 的形态照 {@code ArticleInteractionBatchService}（第一刀）：同事务建 run + 首步，
 * 逐项更新。外层 run 的 {@code maxSteps} = 计划步数，`usedSteps` 随步骤推进。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TaskOrchestrationService {

    private final TaskOrchestrator orchestrator;
    private final AiAgentRunMapper runMapper;
    private final AiAgentStepMapper stepMapper;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    /**
     * 执行一份已校验的计划，并把执行过程落成可审计的 run / step。
     *
     * <p>本方法**不做准入**——调用方（聊天链路）必须先完成权威准入再进来，
     * 与第二刀「写副作用必须在 submit 成功之后」的要求一致。
     */
    public OrchestrationOutcome run(Long userId, Long sessionId, TaskPlan plan,
                                    PageContextDTO pageContext) {
        return run(userId, sessionId, plan, pageContext, null);
    }

    /**
     * @param extra 额外的步骤监听（聊天链路用它推 SSE 的 TASK_STEP）——
     *              落库与推送互不影响：落库失败只记日志，不影响推送，反之亦然
     */
    public OrchestrationOutcome run(Long userId, Long sessionId, TaskPlan plan,
                                    PageContextDTO pageContext, TaskStepListener extra) {
        return run(userId, sessionId, plan, pageContext, extra, null);
    }

    /**
     * @param eventSink 步骤**内部过程**事件出口（第四步）——子 run 的 AGENT_STEP 经它挂到对应 TaskStep；
     *                  为 null 表示不推内部事件（纯后端执行与单测）
     */
    public OrchestrationOutcome run(Long userId, Long sessionId, TaskPlan plan,
                                    PageContextDTO pageContext, TaskStepListener extra,
                                    TaskStepEventSink eventSink) {
        AiAgentRun run = createRun(userId, sessionId, plan);
        TaskExecutionContext context =
                new TaskExecutionContext(userId, sessionId, pageContext, plan, run.getId(), eventSink);
        StepRecorder recorder = new StepRecorder(run);
        TaskStepListener combined = (step, result) -> {
            if (extra != null) {
                try {
                    extra.onStepFinished(step, result);
                } catch (RuntimeException e) {
                    log.warn("[TASK-ORCH] 步骤推送失败（不影响执行）: stepId={}", step.stepId(), e);
                }
            }
            recorder.onStepFinished(step, result);
        };

        try {
            OrchestrationOutcome outcome = orchestrator.execute(plan, context, combined)
                    .withAgentRunId(run.getId());
            finishRun(run, outcome);
            return outcome;
        } catch (RuntimeException e) {
            // 编排层不该抛：执行器异常已被 Orchestrator 兜成 INTERNAL_ERROR。
            // 走到这里说明是基础设施异常（DB / 序列化），如实标失败再抛上去。
            log.error("[TASK-ORCH] 编排异常: runId={}", run.getId(), e);
            finishRun(run, null);
            throw e;
        }
    }

    private AiAgentRun createRun(Long userId, Long sessionId, TaskPlan plan) {
        AiAgentRun run = transactionTemplate.execute(status -> {
            LocalDateTime now = LocalDateTime.now();
            AiAgentRun created = new AiAgentRun();
            created.setUserId(userId);
            created.setSessionId(sessionId);
            created.setGoal(plan.goal());
            created.setStatus(AiAgentRunStatus.RUNNING.name());
            created.setCurrentStep(0);
            created.setMaxSteps(Math.max(1, plan.steps().size()));
            created.setUsedSteps(0);
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("taskStepCount", plan.steps().size());
            if (plan.hasUncoveredGoals()) {
                context.put("uncoveredGoalEvidence", plan.uncoveredGoalEvidence());
            }
            created.setContextJson(toJson(context));
            created.setInputTokens(0);
            created.setOutputTokens(0);
            created.setTotalTokens(0);
            created.setCreatedAt(now);
            created.setUpdatedAt(now);
            runMapper.insert(created);
            return created;
        });
        if (run == null || run.getId() == null) {
            throw new IllegalStateException("任务编排 run 创建失败");
        }
        log.info("[TASK-ORCH] run 创建: runId={}, steps={}", run.getId(), plan.steps().size());
        return run;
    }

    private void finishRun(AiAgentRun run, OrchestrationOutcome outcome) {        AiAgentRun patch = new AiAgentRun();
        patch.setId(run.getId());
        // 暂停不是失败也不是完成：停在 RUNNING，等后续能力处理（第一版不自动续跑）
        boolean paused = outcome != null && outcome.paused();
        patch.setStatus(paused
                ? AiAgentRunStatus.RUNNING.name()
                : AiAgentRunStatus.COMPLETED.name());
        if (outcome != null) {
            patch.setFinalAnswer(null);   // 文案由渲染器产生，run 只存执行状态
            patch.setUsedSteps(outcome.results().size());
        }
        patch.setUpdatedAt(LocalDateTime.now());
        runMapper.updateById(patch);
    }

    /**
     * 根 run 的**子树 Token 总量**（第四步）：本层 + 全部子 run。
     *
     * <p>口径见 Token 设计稿 §5.4：子 run 的 Token 只存在自己行上，**父 run 不复制**；
     * 需要总量时按 {@code parent_run_id} 查询聚合——单一事实源，
     * 不塞进 {@code StepResult.output} 充当第二份账。
     * 第一版只有一层子 run，一次列表查询求和即可。
     */
    public long subtreeTokens(Long rootRunId) {
        if (rootRunId == null) {
            return 0L;
        }
        AiAgentRun root = runMapper.selectById(rootRunId);
        long total = root == null || root.getTotalTokens() == null ? 0L : root.getTotalTokens();
        List<AiAgentRun> children = runMapper.selectList(new LambdaQueryWrapper<AiAgentRun>()
                .eq(AiAgentRun::getParentRunId, rootRunId));
        for (AiAgentRun child : children) {
            total += child.getTotalTokens() == null ? 0L : child.getTotalTokens();
        }
        return total;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("[TASK-ORCH] JSON 序列化失败，降级空对象", e);
            return "{}";
        }
    }

    /**
     * 把每个步骤落成 {@code ai_agent_step}。
     *
     * <p>闭包持有 run —— 这样 runId 不必进纯协议；每次编排 new 一个实例，
     * 不用 ThreadLocal 也不怕并发串号。
     *
     * <p>只有**实际执行过**的步骤会被回调（PENDING 的不会），所以这里落的就是执行事实。
     */
    private final class StepRecorder implements TaskStepListener {

        private final AiAgentRun run;
        private int stepNo = 0;

        private StepRecorder(AiAgentRun run) {
            this.run = run;
        }

        @Override
        public void onStepFinished(TaskStep step, StepResult result) {
            stepNo++;
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    AiAgentStep record = new AiAgentStep();
                    record.setAgentRunId(run.getId());
                    record.setStepNo(stepNo);
                    // 统一用 TASK_STEP 标识"这是编排步骤"，真实类型在 inputJson.type
                    record.setActionType(AgentStepActionType.TASK_STEP.name());
                    record.setInputJson(toJson(stepInput(step)));
                    record.setOutputJson(toJson(stepOutput(result)));
                    record.setStatus(toStepStatus(result).name());
                    record.setDurationMs(0L);
                    if (result.state() == StepState.FAILED || result.state() == StepState.SKIPPED) {
                        record.setErrorMessage(result.summary());
                    }
                    record.setInputTokens(0);
                    record.setOutputTokens(0);
                    record.setCreatedAt(LocalDateTime.now());
                    stepMapper.insert(record);

                    AiAgentRun progress = new AiAgentRun();
                    progress.setId(run.getId());
                    progress.setCurrentStep(stepNo);
                    progress.setUsedSteps(stepNo);
                    progress.setUpdatedAt(LocalDateTime.now());
                    runMapper.updateById(progress);
                });
            } catch (RuntimeException e) {
                // 落库失败不能改变执行事实——编排已发生，如实记录后继续
                log.error("[TASK-ORCH] 步骤落库失败: runId={}, stepId={}", run.getId(), step.stepId(), e);
            }
        }

        private Map<String, Object> stepInput(TaskStep step) {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("stepId", step.stepId());
            input.put("type", step.type().name());
            input.put("params", step.input());
            input.put("goalEvidence", step.goalEvidence());
            return input;
        }

        private Map<String, Object> stepOutput(StepResult result) {
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("state", result.state().name());
            if (result.code() != null) {
                output.put("code", result.code().name());
            }
            output.put("summary", result.summary());
            output.put("data", result.output());
            return output;
        }

        private AiAgentStepStatus toStepStatus(StepResult result) {
            return switch (result.state()) {
                case SUCCEEDED, DISPATCHED -> AiAgentStepStatus.SUCCESS;
                case SKIPPED -> AiAgentStepStatus.SKIPPED;
                default -> AiAgentStepStatus.FAILED;
            };
        }
    }
}
