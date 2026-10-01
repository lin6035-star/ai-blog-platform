package com.hailin.blogsystem.ai.task;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 任务编排器（第三刀）——按计划顺序驱动步骤，收集真实结果。
 *
 * <p><b>它不知道 SSE、不知道 run/step 落库、不知道前端</b>：只在每步结束时通知
 * {@link TaskStepListener}。第一步（纯协议）连真实执行器都不接，只用假执行器验证协议本身。
 *
 * <p>判定顺序（两处刻意）：
 * <ol>
 *   <li><b>requires 优先于 onFailure</b>——没有前置数据就是做不了，{@code CONTINUE} 解决不了这个问题。</li>
 *   <li><b>onFailure 只看本步依赖的步骤</b>（{@code after} ∪ {@code requires}），
 *       不相干的步骤失败不该影响本步。</li>
 * </ol>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TaskOrchestrator {

    private final TaskStepExecutorRegistry registry;

    public OrchestrationOutcome execute(TaskPlan plan) {
        return execute(plan, null, TaskStepListener.noop());
    }

    public OrchestrationOutcome execute(TaskPlan plan, TaskExecutionContext context) {
        return execute(plan, context, TaskStepListener.noop());
    }

    /**
     * 执行一份已校验的计划。
     *
     * <p>串行、单请求内闭环；不做 replan、不做并行、不做步骤级重试（设计稿 §八）。
     */
    public OrchestrationOutcome execute(TaskPlan plan, TaskExecutionContext context,
                                        TaskStepListener listener) {
        TaskStepListener safeListener = listener == null ? TaskStepListener.noop() : listener;
        TaskExecutionContext ctx = context == null
                ? new TaskExecutionContext(null, null, null, plan)
                : context;

        List<StepResult> ordered = new ArrayList<>();
        boolean paused = false;

        for (TaskStep step : plan.steps()) {
            if (paused) {
                // 计划已暂停：剩余步骤保持 PENDING，不继续执行也不标记为失败
                ordered.add(pending(step));
                continue;
            }
            StepResult result = executeStep(ctx, step);
            ctx.record(result);
            ordered.add(result);
            safeListener.onStepFinished(step, result);
            if (result.isWaiting()) {
                paused = true;
            }
        }

        safeListener.onPlanFinished(plan, paused);
        // 纯协议层不建 run——runId 由应用服务（TaskOrchestrationService）回填
        return OrchestrationOutcome.of(plan, ordered, paused);
    }

    private StepResult executeStep(TaskExecutionContext ctx, TaskStep step) {
        Set<String> upstream = upstreamOf(step);

        // ① requires 优先：拿不到前置数据就做不了，这是硬约束
        for (String required : step.dependency().requires()) {
            if (ctx.outputOf(required).isEmpty()) {
                return StepResult.skipped(step.stepId(),
                        "缺少前置数据（" + required + " 没有产出）");
            }
        }

        // ② onFailure：只看本步依赖的步骤是否失败
        for (String dependency : upstream) {
            StepResult previous = ctx.resultOf(dependency);
            if (previous == null || previous.state() != StepState.FAILED) {
                continue;
            }
            switch (step.dependency().onFailure()) {
                case SKIP -> {
                    return StepResult.skipped(step.stepId(),
                            "前置步骤「" + dependency + "」失败，本步跳过");
                }
                case CONFIRM -> {
                    return StepResult.waitingUser(step.stepId(),
                            "前置步骤「" + dependency + "」失败，需要你确认是否继续");
                }
                case CONTINUE -> {
                    // 继续执行，交给执行器自己判断
                }
            }
        }

        // ③ 分派
        TaskStepExecutor executor = registry.resolve(step.type());
        if (executor == null) {
            // 路由层应已保证"计划里的 step 全部可执行"；走到这里说明契约被破坏
            log.warn("[TASK-ORCH] 无执行器承接: stepId={}, type={}", step.stepId(), step.type());
            // C0.2：StepType 是内部协议枚举名，不进用户可见文案（日志里已经有了）
            return StepResult.failed(step.stepId(), StepCode.INTERNAL_ERROR, "这个步骤暂时无法执行");
        }

        try {
            StepResult result = executor.execute(ctx, step);
            if (result == null) {
                return StepResult.failed(step.stepId(), StepCode.INTERNAL_ERROR, "执行器返回空结果");
            }
            return result;
        } catch (Exception e) {
            // 执行器约定不抛业务异常；这里兜的是真正的系统异常
            log.error("[TASK-ORCH] 步骤执行异常: stepId={}, type={}", step.stepId(), step.type(), e);
            return StepResult.failed(step.stepId(), StepCode.INTERNAL_ERROR, "执行异常，请稍后重试");
        }
    }

    /** 本步依赖的步骤集合：顺序要求 ∪ 数据依赖 */
    private Set<String> upstreamOf(TaskStep step) {
        Set<String> upstream = new LinkedHashSet<>(step.dependency().after());
        upstream.addAll(step.dependency().requires());
        return upstream;
    }

    private StepResult pending(TaskStep step) {
        return new StepResult(step.stepId(), StepState.PENDING, null, "尚未执行", Map.of());
    }
}
