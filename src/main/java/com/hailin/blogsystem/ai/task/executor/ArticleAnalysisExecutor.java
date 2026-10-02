package com.hailin.blogsystem.ai.task.executor;

import com.hailin.blogsystem.ai.agent.AgentCompletionKind;
import com.hailin.blogsystem.ai.agent.AgentRunResult;
import com.hailin.blogsystem.ai.agent.AgentStepEmitter;
import com.hailin.blogsystem.ai.agent.ArticleAgentRuntime;
import com.hailin.blogsystem.ai.task.StepCode;
import com.hailin.blogsystem.ai.task.StepResult;
import com.hailin.blogsystem.ai.task.StepType;
import com.hailin.blogsystem.ai.task.TaskExecutionContext;
import com.hailin.blogsystem.ai.task.TaskStep;
import com.hailin.blogsystem.ai.task.TaskStepExecutor;
import com.hailin.blogsystem.entity.dto.AiAgentRunStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文章分析执行器（第四步，2026-09-30）——把内部 ReAct 子 run 的结果**如实映射**回外层 TaskStep。
 *
 * <p><b>它不是"搬运回答"</b>：子 run 有四种收尾都落 {@code COMPLETED}（正常回答 / 空答案兜底 /
 * 证据不足保守收尾 / 步数到顶总结），外加终局失败也是 {@code COMPLETED}。
 * 直接按 {@code COMPLETED -> SUCCEEDED} 回填，{@code TaskResultRenderer} 会把它们全放进 done 列表，
 * 输出「已全部完成：目前收集到的信息还不足以让我给出可靠结论」——那是第一刀明令禁止的谎报。
 *
 * <p><b>映射原则</b>：{@code StepState} 表达的是**用户目标是否达成**，不是「内部代码有没有跑完」；
 * 只有 {@code ANSWER} 算达成，其余一律 {@code FAILED}，靠 {@link StepCode} 区分原因。
 * 判据只读 {@code completionKind} 枚举，**不解析 finalAnswer 文案**。
 *
 * <p>设计稿：`docs/agent/agent-task-orchestration-design.md` §13.3 阶段 3 / §13.4 映射表。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ArticleAnalysisExecutor implements TaskStepExecutor {

    private final ArticleAgentRuntime articleAgentRuntime;

    @Override
    public StepType supports() {
        return StepType.ARTICLE_ANALYSIS;
    }

    @Override
    public StepResult execute(TaskExecutionContext context, TaskStep step) {
        Long userId = context.userId();
        if (userId == null) {
            return StepResult.failed(step.stepId(), StepCode.FORBIDDEN, "请先登录");
        }
        if (context.taskRunId() == null) {
            // 子 run 必须挂在明确的外层任务上；没有外层 run 就建 run 会产出无法归属的孤儿记录。
            // 纯协议路径（Orchestrator 单测）会走到这里——如实失败，不静默降级。
            return StepResult.failed(step.stepId(), StepCode.INTERNAL_ERROR,
                    "任务上下文缺少外层 run，无法执行分析");
        }

        String goal = buildGoal(context, step);
        // 内部过程事件显式带上本步骤 id：前端据此把 AGENT_STEP 挂到对应 TaskStep 之下
        AgentStepEmitter emitter = (stepNo, actionType, status, message, thoughtSummary) ->
                context.emitStepEvent(step.stepId(), stepNo, actionType, status, message, thoughtSummary);

        AgentRunResult result;
        try {
            result = articleAgentRuntime.runAnalysisOnly(
                    userId, context.sessionId(), goal, context.pageContext(),
                    emitter, context.taskRunId());
        } catch (RuntimeException e) {
            // 执行器约定不抛业务异常：系统异常也转成带 code 的结果，让汇总能如实说明
            log.error("[TASK-ANALYSIS] 子 run 异常: stepId={}", step.stepId(), e);
            return StepResult.failed(step.stepId(), StepCode.INTERNAL_ERROR, "分析没能完成，请稍后重试");
        }

        return toStepResult(step, result);
    }

    /**
     * 子 run 结果 → 外层步骤结果（设计稿 §13.4 映射表）。
     *
     * <p>注意 {@code output} 里**不放 Token**——子 run 的 token 只存在自己的 run / step 行上，
     * 由根 run 按 {@code parent_run_id} 查询聚合（单一事实源，避免第二份账）。
     */
    private StepResult toStepResult(TaskStep step, AgentRunResult result) {
        Map<String, Object> output = new LinkedHashMap<>();
        if (result.agentRunId() != null) {
            output.put("agentRunId", String.valueOf(result.agentRunId()));
        }
        output.put("usedSteps", result.usedSteps());
        if (result.completionKind() != null) {
            output.put("completionKind", result.completionKind().name());
        }

        if (result.status() != AiAgentRunStatus.COMPLETED) {
            // analysis-only 策略禁止等待态（allowWaitingUser=false），出现即契约被破坏。
            // 第一版**不暂停外层计划**：标失败后由下游 onFailure 决定走向。
            log.warn("[TASK-ANALYSIS] 子 run 非 COMPLETED: stepId={}, status={}",
                    step.stepId(), result.status());
            if (result.status() != null) {
                output.put("status", result.status().name());
            }
            return StepResult.failed(step.stepId(), StepCode.INTERNAL_ERROR,
                    "分析没能完成，请稍后重试", output);
        }

        AgentCompletionKind kind = result.completionKind();
        if (kind == null) {
            // §13.8③：完成类型缺失时**防御性失败**，不能当成正常答案放行——
            // 一次误用就会悄悄回到"谎报完成"。
            log.warn("[TASK-ANALYSIS] 子 run 缺少完成类型，按失败处理: stepId={}", step.stepId());
            return StepResult.failed(step.stepId(), StepCode.INTERNAL_ERROR,
                    "分析结果缺少完成类型，无法确认是否完成", output);
        }

        return switch (kind) {
            case ANSWER -> StepResult.answered(step.stepId(), result.finalAnswer(), output);

            case EMPTY_ANSWER_FALLBACK -> StepResult.failed(step.stepId(), StepCode.INTERNAL_ERROR,
                    "分析没有产出可用正文", output);

            case CONSERVATIVE_SHUTDOWN -> StepResult.failed(step.stepId(),
                    StepCode.INSUFFICIENT_EVIDENCE, result.finalAnswer(), output);

            case MAX_STEPS_SUMMARY -> StepResult.failed(step.stepId(),
                    StepCode.INCOMPLETE_RESULT, result.finalAnswer(), output);

            case TERMINAL_FAILURE -> StepResult.failed(step.stepId(), StepCode.TARGET_NOT_FOUND,
                    // 不区分"文章不存在"与"不是你的文章"——沿用项目既有的隐私安全合并语义
                    result.finalAnswer() == null || result.finalAnswer().isBlank()
                            ? "没有找到可以分析的文章"
                            : result.finalAnswer(),
                    output);
        };
    }

    /**
     * 分析段的 goal：用户原话 + 已完成步骤的事实 + 本步诉求证据。
     *
     * <p>沿用第二刀「动作结果注入 goal」的做法：让模型知道**已经做过什么**，
     * 避免在回答里重复许诺已经执行过的动作。
     */
    private String buildGoal(TaskExecutionContext context, TaskStep step) {
        StringBuilder sb = new StringBuilder(context.plan().goal());

        List<String> done = new ArrayList<>();
        for (TaskStep other : context.plan().steps()) {
            if (other.stepId().equals(step.stepId())) {
                continue;
            }
            StepResult previous = context.resultOf(other.stepId());
            if (previous != null && previous.isSuccessLike()
                    && previous.summary() != null && !previous.summary().isBlank()) {
                done.add(previous.summary());
            }
        }
        if (!done.isEmpty()) {
            sb.append("\n\n【已完成的前置动作】\n").append(String.join("\n", done));
        }
        appendSearchEvidence(sb, context, step);
        if (!step.goalEvidence().isEmpty()) {
            sb.append("\n\n【这一步要完成的】").append(String.join("；", step.goalEvidence()));
        }
        return sb.toString();
    }

    /**
     * 搜索结果比较的轻量证据：只取已完成搜索步骤的 ID / 标题 / 摘要，不注入多篇完整正文。
     * 上限与 ArticleSearchExecutor 一致为 5 条，摘要再截断，避免比较任务把 Prompt 撑大。
     */
    private void appendSearchEvidence(StringBuilder sb, TaskExecutionContext context, TaskStep current) {
        List<String> lines = new ArrayList<>();
        for (TaskStep other : context.plan().steps()) {
            if (other.stepId().equals(current.stepId())) {
                break;
            }
            if (other.type() != StepType.ARTICLE_SEARCH) {
                continue;
            }
            StepResult result = context.resultOf(other.stepId());
            if (result == null || !result.isSuccessLike()) {
                continue;
            }
            Object rawArticles = result.output().get("articles");
            if (!(rawArticles instanceof List<?> articles)) {
                continue;
            }
            for (Object rawArticle : articles) {
                if (!(rawArticle instanceof Map<?, ?> article) || lines.size() >= 5) {
                    continue;
                }
                String id = text(article.get("articleId"));
                String title = text(article.get("title"));
                String summary = limit(text(article.get("summary")), 240);
                if (title.isBlank()) {
                    continue;
                }
                StringBuilder line = new StringBuilder("- 《").append(title).append("》");
                if (!id.isBlank()) {
                    line.append('(').append(id).append(')');
                }
                if (!summary.isBlank()) {
                    line.append("：").append(summary);
                }
                lines.add(line.toString());
            }
        }
        if (!lines.isEmpty()) {
            sb.append("\n\n【搜索结果证据】\n")
                    .append(String.join("\n", lines))
                    .append("\n只基于以上证据比较；证据未覆盖的维度明确写无法判断，不要声称已经读完全文。");
        }
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String limit(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength) + "…";
    }
}
