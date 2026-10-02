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
import java.util.ArrayList;
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
 * 逐项更新。外层 run 的 {@code maxSteps} = 计划步数，{@code usedSteps} 随步骤推进。
 *
 * <p><b>5b 之后这里多了一层职责：它是「跨请求恢复」的事实来源。</b>具体两处变化：
 * <ul>
 *   <li>建 run 时**按计划预创建全部步骤行**（{@code PENDING}），并把计划快照写进
 *       {@code task_plan_json}——"还没轮到"从"查不到记录"变成明确的持久化事实；</li>
 *   <li>步骤落库不再是 best-effort：它是恢复依据，**失败必须中止**而不是记日志继续
 *       （见 {@link StepRecorder#onStepFinished}）。</li>
 * </ul>
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
    private final TaskPlanSnapshotCodec snapshotCodec;
    private final TaskRunStateTransitions stateTransitions;

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
     *              落库与推送互不影响：推送失败只记日志，落库失败则中止（5b 起，见类注释）
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
        AiAgentRun run = createRun(userId, sessionId, plan, pageContext);
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
            finishRun(run, plan, outcome);
            return outcome;
        } catch (RuntimeException e) {
            // 编排层不该抛：执行器异常已被 Orchestrator 兜成 INTERNAL_ERROR。
            // 走到这里说明是基础设施异常（DB / 序列化 / 步骤行缺失），如实标失败再抛上去。
            log.error("[TASK-ORCH] 编排异常: runId={}", run.getId(), e);
            stateTransitions.failFromRunning(run.getId(), technicalError(e));
            throw e;
        }
    }

    private AiAgentRun createRun(Long userId, Long sessionId, TaskPlan plan, PageContextDTO pageContext) {
        AiAgentRun run = transactionTemplate.execute(status -> {
            LocalDateTime now = LocalDateTime.now();
            AiAgentRun created = new AiAgentRun();
            created.setUserId(userId);
            created.setSessionId(sessionId);
            created.setGoal(plan.goal());
            created.setStatus(AiAgentRunStatus.RUNNING.name());
            created.setCurrentStep(0);
            // 0 步计划不能变成 0：maxSteps 是"上限"语义，除零与前端展示都要靠它兜住
            created.setMaxSteps(Math.max(1, plan.steps().size()));
            created.setUsedSteps(0);
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("taskStepCount", plan.steps().size());
            if (plan.hasUncoveredGoals()) {
                context.put("uncoveredGoalEvidence", plan.uncoveredGoalEvidence());
            }
            created.setContextJson(toJson(context));
            // 5b：可恢复的正式快照（计划 + 可信页面锚点）。与 plan_json 不是一回事，见实体注释。
            // 读写走同一个 codec——写的时候少存一个字段，读出来就是一份语义残缺的计划
            created.setTaskPlanJson(snapshotCodec.write(plan, pageContext));
            created.setInputTokens(0);
            created.setOutputTokens(0);
            created.setTotalTokens(0);
            created.setCreatedAt(now);
            created.setUpdatedAt(now);
            runMapper.insert(created);

            // 5b：**按计划预创建全部步骤行**（PENDING）。
            // 这样"计划里有几步 / 跑了哪几步 / 还剩哪几步"都是明确的持久化事实，
            // 恢复时能直接对齐，不必靠"表里没有这一行"反推。
            // step_no 用**计划序号**（不是回调顺序号）——它同时是 current_step 的口径。
            int stepNo = 0;
            for (TaskStep step : plan.steps()) {
                stepNo++;
                AiAgentStep row = new AiAgentStep();
                row.setAgentRunId(created.getId());
                row.setStepNo(stepNo);
                row.setTaskStepId(step.stepId());
                row.setActionType(AgentStepActionType.TASK_STEP.name());
                row.setInputJson(toJson(stepInput(step)));
                row.setStatus(AiAgentStepStatus.PENDING.name());
                row.setDurationMs(0L);
                row.setInputTokens(0);
                row.setOutputTokens(0);
                row.setCreatedAt(now);
                stepMapper.insert(row);
            }
            return created;
        });
        if (run == null || run.getId() == null) {
            throw new IllegalStateException("任务编排 run 创建失败");
        }
        log.info("[TASK-ORCH] run 创建: runId={}, steps={}", run.getId(), plan.steps().size());
        return run;
    }

    /**
     * **用已有 run 执行剩余步骤**（5b）——不新建 run、不重建步骤行，也不在这里提交根终态。
     *
     * <p>与 {@link #run} 的分工：{@code run} 负责"从零开始并落盘"；这里负责"接着跑"——
     * run 和步骤行（含 PENDING 占位）都已存在，历史结果由调用方从持久化事实重建后传进来。
     *
     * <p>步骤落库仍然走同一个 {@link StepRecorder}：续跑的每一步同样写进那一行预创建的步骤，
     * 进度口径（`current_step` = 计划序号、`used_steps` = 已产生结果数）与首跑完全一致。
     *
     * <p>根 run / handoff 行 / 原消息必须在恢复协调者的同一个短事务里收敛，
     * 所以本方法只执行并落步骤事实。基础设施异常直接抛给恢复协调者，
     * 由它把 {@code RUNNING} 复位为 {@code WAITING_WORKFLOW}，留给巡检重试。
     */
    public OrchestrationOutcome resume(AiAgentRun run, TaskPlan plan, PageContextDTO pageContext,
                                       Map<String, StepResult> persistedResults) {
        TaskExecutionContext context = TaskExecutionContext.resume(
                run.getUserId(), run.getSessionId(), pageContext, plan, run.getId(),
                null, persistedResults);
        StepRecorder recorder = new StepRecorder(run);
        try {
            return orchestrator.resume(plan, context, recorder);
        } catch (RuntimeException e) {
            log.error("[TASK-ORCH] 续跑异常: runId={}", run.getId(), e);
            throw e;
        }
    }

    /** 在调用方事务内提交续跑根终态；旧状态不是 RUNNING 时按并发冲突处理。 */
    public void finalizeResumedRun(AiAgentRun run, TaskPlan plan, OrchestrationOutcome outcome) {
        finishRun(run, plan, outcome);
    }

    private void finishRun(AiAgentRun run, TaskPlan plan, OrchestrationOutcome outcome) {
        boolean paused = outcome != null && outcome.paused();
        int usedSteps = executedCount(outcome);
        boolean updated;
        if (paused) {
            updated = stateTransitions.pauseFromRunning(
                    run.getId(), pausedStatus(plan, outcome), usedSteps);
        } else {
            updated = stateTransitions.completeFromRunning(run.getId(), usedSteps);
        }
        if (!updated) {
            throw new IllegalStateException("根 run 状态转换冲突: runId=" + run.getId());
        }
    }

    /**
     * 暂停时根 run 停在哪个状态（5b）。
     *
     * <p>5b 之前一律停 {@code RUNNING}（注释原话"等后续能力处理"）。但接上 {@code WORKFLOW_HANDOFF}
     * 之后，"在等一个**已经创建**的 Workflow 收敛"是一个语义明确、可能跨小时的等待：
     * 启动恢复只匹配 {@code status=RUNNING}，会把这条合法等待任务当成残留标成 FAILED
     * ——重启一次就把它判死了。所以这种情况必须有自己的状态。
     *
     * <p>其余暂停（例如等用户补充信息）**保持原来的 RUNNING**，不顺手改语义：
     * 状态越多，"谁在等我"就越难一眼看清。
     */
    private static AiAgentRunStatus pausedStatus(TaskPlan plan, OrchestrationOutcome outcome) {
        List<TaskStep> steps = plan == null || plan.steps() == null ? List.of() : plan.steps();
        List<StepResult> results = outcome.results() == null ? List.of() : outcome.results();
        // results 与 steps 同序（Orchestrator 按计划顺序收集），所以按下标对齐即可
        for (int i = 0; i < steps.size() && i < results.size(); i++) {
            if (steps.get(i).type() == StepType.WORKFLOW_HANDOFF && results.get(i).isWaiting()) {
                return AiAgentRunStatus.WAITING_WORKFLOW;
            }
        }
        return AiAgentRunStatus.RUNNING;
    }

    private static String technicalError(RuntimeException error) {
        String detail = error.getMessage();
        String value = error.getClass().getSimpleName()
                + (detail == null || detail.isBlank() ? "" : ": " + detail);
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    /** 已产生结果的步骤数（**不含** PENDING 占位） */
    private static int executedCount(OrchestrationOutcome outcome) {
        if (outcome == null || outcome.results() == null) {
            return 0;
        }
        return (int) outcome.results().stream()
                .filter(result -> result.state() != StepState.PENDING)
                .count();
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

    /**
     * 暂停在 handoff 时固化消息的路由基线，供跨请求续跑做绝对 Token 重算。
     *
     * <p>不能在恢复时才用 {@code message.token_count - subtree} 永久代替它：恢复期间可能新增子 run，
     * 那时再倒推会把新增子 run 误扣进路由消耗。字段放在 handoff 步骤快照里，与 Workflow 关联事实同寿命。
     */
    public void recordMessageRoutingTokens(Long rootRunId, TaskPlan plan, long routingTokens) {
        if (rootRunId == null || plan == null || plan.steps() == null) {
            return;
        }
        TaskStep handoff = plan.steps().stream()
                .filter(step -> step.type() == StepType.WORKFLOW_HANDOFF)
                .findFirst()
                .orElse(null);
        if (handoff == null) {
            return;
        }
        transactionTemplate.executeWithoutResult(status -> {
            AiAgentStep row = stepMapper.selectOne(new LambdaQueryWrapper<AiAgentStep>()
                    .eq(AiAgentStep::getAgentRunId, rootRunId)
                    .eq(AiAgentStep::getTaskStepId, handoff.stepId())
                    .last("limit 1"));
            if (row == null) {
                throw new IllegalStateException("handoff 步骤行不存在: runId="
                        + rootRunId + ", stepId=" + handoff.stepId());
            }
            Map<String, Object> output = parseStepOutput(row);
            output.put("messageRoutingTokens", Math.max(0L, routingTokens));

            AiAgentStep patch = new AiAgentStep();
            patch.setId(row.getId());
            patch.setOutputJson(toJson(output));
            if (stepMapper.updateById(patch) != 1) {
                throw new IllegalStateException("handoff 路由 Token 基线更新失败: stepId=" + handoff.stepId());
            }
        });
    }

    private Map<String, Object> parseStepOutput(AiAgentStep row) {
        Map<String, Object> output = new LinkedHashMap<>();
        if (row.getOutputJson() == null || row.getOutputJson().isBlank()) {
            return output;
        }
        try {
            objectMapper.readTree(row.getOutputJson()).fields().forEachRemaining(entry ->
                    output.put(entry.getKey(), objectMapper.convertValue(entry.getValue(), Object.class)));
            return output;
        } catch (Exception e) {
            throw new IllegalStateException("handoff 步骤快照无法解析: stepId=" + row.getTaskStepId(), e);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("[TASK-ORCH] JSON 序列化失败，降级空对象", e);
            return "{}";
        }
    }

    private static Map<String, Object> stepInput(TaskStep step) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("stepId", step.stepId());
        input.put("type", step.type().name());
        input.put("params", step.input());
        input.put("goalEvidence", step.goalEvidence());
        return input;
    }

    private static Map<String, Object> stepOutput(StepResult result) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("state", result.state().name());
        if (result.code() != null) {
            output.put("code", result.code().name());
        }
        output.put("summary", result.summary());
        output.put("data", result.output());
        return output;
    }

    /**
     * 把每个步骤的终态落到**预创建的那一行**上。
     *
     * <p>闭包持有 run —— 这样 runId 不必进纯协议；每次编排 new 一个实例，
     * 不用 ThreadLocal 也不怕并发串号。
     *
     * <p><b>落库失败不再吞掉</b>（5b）：在此之前这里是 best-effort（"编排已发生，记日志继续"），
     * 那在"没有恢复需求"时成立；5b 之后步骤行是**恢复依据**，缺了它继续跑就等于
     * 产生没有审计事实的副作用。所以这里如实抛出，由 {@code run(...)} 把根 run 标成可诊断失败。
     * 内部 Agent 的展示日志仍保持原来的 best-effort 语义，两者不混用。
     */
    private final class StepRecorder implements TaskStepListener {

        private final AiAgentRun run;
        /** 已回调（= 已产生结果）的步骤数，等价于"非 PENDING 的步骤数" */
        private int executed = 0;

        private StepRecorder(AiAgentRun run) {
            this.run = run;
        }

        @Override
        public void onStepFinished(TaskStep step, StepResult result) {
            executed++;
            transactionTemplate.executeWithoutResult(status -> {
                // 5b：步骤行在 createRun 时已按计划预创建，这里**更新同一行**
                AiAgentStep row = stepMapper.selectOne(new LambdaQueryWrapper<AiAgentStep>()
                        .eq(AiAgentStep::getAgentRunId, run.getId())
                        .eq(AiAgentStep::getTaskStepId, step.stepId())
                        .last("limit 1"));
                if (row == null) {
                    // 预创建是这条链路的不变量。破坏它却继续执行，就会产生没有审计事实的副作用，
                    // 所以这里必须抛——由 run(...) 的 catch 收敛成可诊断失败，绝不静默补一行蒙混过去
                    throw new IllegalStateException("步骤行不存在（预创建不变量被破坏）: runId="
                            + run.getId() + ", stepId=" + step.stepId());
                }

                AiAgentStep patch = new AiAgentStep();
                patch.setId(row.getId());
                patch.setInputJson(toJson(stepInput(step)));
                patch.setOutputJson(toJson(stepOutput(result)));
                patch.setStatus(toStepStatus(result).name());
                patch.setDurationMs(0L);
                if (result.state() == StepState.FAILED || result.state() == StepState.SKIPPED) {
                    patch.setErrorMessage(result.summary());
                }
                stepMapper.updateById(patch);

                AiAgentRun progress = new AiAgentRun();
                progress.setId(run.getId());
                // current_step = 该步骤的**计划序号**（不是回调顺序号）：外部看到"跑到第几步"才对得上计划
                progress.setCurrentStep(row.getStepNo());
                progress.setUsedSteps(executed);
                progress.setUpdatedAt(LocalDateTime.now());
                runMapper.updateById(progress);
            });
        }

        /**
         * StepState → 表级状态。
         *
         * <p>**穷尽枚举、不留 default**：5b 之前这里写的是 {@code default -> FAILED}，
         * 于是 {@code WAITING_CONFIRM / WAITING_USER / PENDING} 全被记成失败——
         * inspection 与前端据此显示的语义是错的。让编译器逼着补齐，比事后排查便宜。
         */
        private AiAgentStepStatus toStepStatus(StepResult result) {
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
    }
}
