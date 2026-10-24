package com.hailin.blogsystem.ai.task.executor;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hailin.blogsystem.ai.task.StepCode;
import com.hailin.blogsystem.ai.task.StepResult;
import com.hailin.blogsystem.ai.task.StepType;
import com.hailin.blogsystem.ai.task.TaskExecutionContext;
import com.hailin.blogsystem.ai.task.TaskStep;
import com.hailin.blogsystem.ai.task.TaskStepExecutor;
import com.hailin.blogsystem.ai.task.WorkflowHandoffResults;
import com.hailin.blogsystem.ai.task.WorkflowHandoffTypes;
import com.hailin.blogsystem.ai.workflow.AiWorkflowStepEmitter;
import com.hailin.blogsystem.entity.AiWorkflowRun;
import com.hailin.blogsystem.entity.dto.AiWorkflowOptimizeArticleDTO;
import com.hailin.blogsystem.entity.dto.PageContextDTO;
import com.hailin.blogsystem.mapper.AiWorkflowRunMapper;
import com.hailin.blogsystem.service.AiWorkflowRunService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * {@code WORKFLOW_HANDOFF} 执行器（5b，2026-10-01）——把**一次 Workflow 当成一个黑盒步骤**。
 *
 * <p><b>它不拆 Workflow</b>：那 5 条固定流程的内部步骤、确认门、Action Lock 全都保持原样。
 * 它只做三件事：按外层步骤找（或创建）关联的 Workflow、把它的当前状态翻译成一个 {@link StepResult}、
 * 把关联信息写进 output 供恢复与展示使用。
 *
 * <p><b>它不负责</b>：approve / reject / retry / cancel、Workflow 内部步骤、
 * 外层剩余计划的续跑、自己拼 prompt 或额外调 LLM。这些分别属于现有接口、Workflow 自身
 * 和 {@code TaskWorkflowContinuationService}（阶段 2）。
 *
 * <p><b>幂等</b>：先按 {@code task_run_id + task_step_id} 查已有 Workflow；
 * 外层步骤被重试（或事件重放）时不会创建第二条。数据库唯一键
 * {@code uk_workflow_task_step} 是最后一道防线。
 *
 * <p><b>为什么不走准入</b>：外层 TaskPlan 在提交时**已经占过** AI 长任务名额，
 * 执行器在自己的 worker 里再 {@code admission.submit} 会重复占名额（嵌套准入）。
 * Workflow 自身的 approve / retry 由用户请求驱动、各自走现有准入，与这里无关。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WorkflowHandoffExecutor implements TaskStepExecutor {

    private final AiWorkflowRunService aiWorkflowRunService;
    private final AiWorkflowRunMapper workflowRunMapper;

    @Override
    public StepType supports() {
        return StepType.WORKFLOW_HANDOFF;
    }

    @Override
    public StepResult execute(TaskExecutionContext context, TaskStep step) {
        // ① 白名单（规划期与前置换校验都查过；这里是执行前的最后一道，防"绕过 Planner"的调用方）
        String workflowType = stringParam(step, "workflowType");
        if (!WorkflowHandoffTypes.isSupported(workflowType)) {
            log.warn("[TASK-HANDOFF] workflowType 不在 5b 白名单: type={}", workflowType);
            return StepResult.failed(step.stepId(), StepCode.INTERNAL_ERROR, "这个流程我暂时还接不了");
        }

        // ② 锚点：只从后端校验过的 pageContext 取
        Long articleId = parseArticleId(context.pageContext());
        if (articleId == null) {
            return StepResult.failed(step.stepId(), StepCode.TARGET_NOT_FOUND, "没能确定要优化哪篇文章");
        }

        AiWorkflowRun run = findLinked(context.taskRunId(), step.stepId());
        if (run == null) {
            run = createLinked(context, step, articleId);
        }
        if (run == null) {
            return StepResult.failed(step.stepId(), StepCode.INTERNAL_ERROR, "优化流程没能启动，请稍后重试");
        }
        return toStepResult(step, run);
    }

    private AiWorkflowRun createLinked(TaskExecutionContext context, TaskStep step, Long articleId) {
        AiWorkflowOptimizeArticleDTO dto = new AiWorkflowOptimizeArticleDTO();
        dto.setConversationId(context.sessionId());
        dto.setArticleId(articleId);
        dto.setInstruction(instructionOf(context, step));
        dto.setPageContext(context.pageContext());
        // 父关联走 DTO，Service 会在 Workflow 首次 save 之前写进 run（见 createArticleOptimizeWorkflow）
        dto.setTaskRunId(context.taskRunId());
        dto.setTaskStepId(step.stepId());

        try {
            // 5b：把 Workflow 的步骤事件转发到外层 SSE——**流程一启动，前端就能把卡片建出来**。
            // runId 要等 Workflow 入库后由 bindWorkflowRunId 回填（与旧链同一套路），
            // 在那之前不发明细，免得前端建出一张空卡
            aiWorkflowRunService.createArticleOptimizeWorkflow(dto, emitterFor(context, step));
        } catch (RuntimeException e) {
            // 创建失败（含会话已有活跃 Workflow 的冲突检查）→ 这一步如实失败，
            // 由外层按 onFailure 语义收尾；不能在这里吞掉假装成功
            log.warn("[TASK-HANDOFF] 创建优化 Workflow 失败: taskRunId={}, stepId={}",
                    context.taskRunId(), step.stepId(), e);
            return null;
        }
        AiWorkflowRun created = findLinked(context.taskRunId(), step.stepId());
        if (created != null) {
            log.info("[TASK-HANDOFF] 已关联优化 Workflow: taskRunId={}, stepId={}, workflowRunId={}",
                    context.taskRunId(), step.stepId(), created.getId());
        }
        return created;
    }

    /**
     * 把 Workflow 的步骤事件接到**外层任务的事件出口**（5b）。
     *
     * <p>为什么必须接：不接的话（原先的 {@code noop}）整个初始链一个事件都不发，
     * 前端要等最终 STOP 里的快照才知道有这张卡——而那时优化方案都快好了。
     * 用户看到的是"点了发送，几十秒里什么都没有，然后突然冒出一张卡"。
     *
     * <p>runId 采用**入库后回填**（{@code bindWorkflowRunId}）而不是提前生成：
     * 与旧链 Workflow 路径完全一致，不发明细时前端就不会建出指向不存在 run 的卡。
     */
    private static AiWorkflowStepEmitter emitterFor(TaskExecutionContext context, TaskStep step) {
        return new AiWorkflowStepEmitter() {
            private final AtomicReference<Long> workflowRunId = new AtomicReference<>();

            @Override
            public void emit(String workflowStep, String status, String message) {
                Long runId = workflowRunId.get();
                if (runId == null) {
                    return;   // 还没入库：与旧链一致，不发明细
                }
                context.emitWorkflowStep(runId, WorkflowHandoffTypes.OPTIMIZE_ARTICLE,
                        workflowStep, status, message, step.stepId());
            }

            @Override
            public void bindWorkflowRunId(Long id) {
                workflowRunId.set(id);
            }
        };
    }

    /**
     * Workflow 状态 → 外层 StepResult：**委托给共用映射**（{@link WorkflowHandoffResults}）。
     *
     * <p>这段映射恢复服务也要用同一份，所以不能留在这里各写一遍——两处一旦漂移，
     * 就会出现"执行器认为这条还能重试、恢复服务却已判它失败"这种最难查的错。
     */
    private StepResult toStepResult(TaskStep step, AiWorkflowRun run) {
        return WorkflowHandoffResults.toStepResult(step.stepId(), run);
    }

    private AiWorkflowRun findLinked(Long taskRunId, String taskStepId) {
        if (taskRunId == null || taskStepId == null || taskStepId.isBlank()) {
            return null;
        }
        return workflowRunMapper.selectOne(new LambdaQueryWrapper<AiWorkflowRun>()
                .eq(AiWorkflowRun::getTaskRunId, taskRunId)
                .eq(AiWorkflowRun::getTaskStepId, taskStepId)
                .last("limit 1"));
    }

    /**
     * 传给 Workflow 的指令用**用户原话**（计划目标）。
     *
     * <p>5a 冻结协议不允许把 {@code topic} 塞进 handoff 的 params（那是模型提取值），
     * 而"需求本身"就是用户原话——Workflow 自己的需求分析步骤会从中提炼主题，
     * 需求确实模糊时它会追问用户（{@code isRequirementUnclear}），不需要在这里替它猜。
     */
    private static String instructionOf(TaskExecutionContext context, TaskStep step) {
        String goal = context.plan() == null ? null : context.plan().goal();
        if (goal != null && !goal.isBlank()) {
            return goal;
        }
        return step.goalEvidence() == null ? "" : String.join("；", step.goalEvidence());
    }

    private static String stringParam(TaskStep step, String key) {
        Object value = step.input() == null ? null : step.input().get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static Long parseArticleId(PageContextDTO pageContext) {
        if (pageContext == null || pageContext.getArticleId() == null) {
            return null;
        }
        try {
            return Long.valueOf(pageContext.getArticleId().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
