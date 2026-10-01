package com.hailin.blogsystem.ai.task.executor;

import com.hailin.blogsystem.ai.LogValues;
import com.hailin.blogsystem.ai.task.StepCode;
import com.hailin.blogsystem.ai.task.StepResult;
import com.hailin.blogsystem.ai.task.StepType;
import com.hailin.blogsystem.ai.task.TaskExecutionContext;
import com.hailin.blogsystem.ai.task.TaskStep;
import com.hailin.blogsystem.ai.task.TaskStepExecutor;
import com.hailin.blogsystem.entity.Articles;
import com.hailin.blogsystem.service.ArticlesService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 客户端导航执行器（第三刀）。
 *
 * <p><b>它不执行导航</b>——导航在前端。这里只负责：解析出目标 + 产出前端可执行的命令，
 * 然后返回 {@link StepResult#dispatched}（**不是 SUCCEEDED**：后端不知道前端有没有跳成功）。
 *
 * <p><b>为什么自己查 authorId</b>：设计稿 §3.1 的步骤粒度规则——"查作者 ID"是内部准备动作，
 * 不是用户能一眼看懂的一件事，所以不单独成步、由本执行器负责。但不留痕也不行：
 * 解析出的 authorId、失败原因、最终命令都写进 {@code output}，否则出了问题时
 * 无法解释"为什么导航没有发起"（用户看到的只是"未执行"）。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class NavigateExecutor implements TaskStepExecutor {

    /** 前端约定的导航目标（与 AgentPlannerSupport.NAVIGATION_TARGETS 同一套语义） */
    /**
     * 本执行器实际支持的导航目标。
     *
     * <p>public 是刻意的：{@code TaskStepParamValidator} 要在**规划期**按同一份白名单校验，
     * 各写一份常量迟早漂移（改了执行器忘了改校验，或反之）。
     */
    public static final String TARGET_USER_PROFILE = "userProfile";

    private final ArticlesService articlesService;

    @Override
    public StepType supports() {
        return StepType.NAVIGATE;
    }

    @Override
    public StepResult execute(TaskExecutionContext context, TaskStep step) {
        String target = String.valueOf(step.input().getOrDefault("target", "")).trim();
        if (target.isBlank()) {
            return StepResult.failed(step.stepId(), StepCode.INTERNAL_ERROR, "没有指定导航目标");
        }

        if (TARGET_USER_PROFILE.equals(target)) {
            return navigateToUserProfile(context, step);
        }
        log.warn("[TASK-EXEC] 不支持的导航目标（疑似模型越界）: stepId={}, target={}",
                step.stepId(), LogValues.safe(target));
        return StepResult.failed(step.stepId(), StepCode.INTERNAL_ERROR, "这个跳转我暂时做不了");
    }

    /** 跳作者主页需要 authorId——由后端从页面文章查得，不让 LLM 提供 */
    private StepResult navigateToUserProfile(TaskExecutionContext context, TaskStep step) {
        Long articleId = resolveArticleId(context);
        if (articleId == null) {
            return StepResult.skipped(step.stepId(), "没有定位到当前文章，无法确定作者");
        }

        Articles article = articlesService.getById(articleId);
        if (article == null || article.getAuthorId() == null) {
            return StepResult.skipped(step.stepId(), "没有找到文章作者信息");
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("target", TARGET_USER_PROFILE);
        output.put("userId", String.valueOf(article.getAuthorId()));
        output.put("param", String.valueOf(article.getAuthorId()));
        output.put("articleId", String.valueOf(articleId));

        return StepResult.dispatched(step.stepId(),
                "跳转到《" + article.getTitle() + "》作者的主页", output);
    }

    private Long resolveArticleId(TaskExecutionContext context) {
        if (context.pageContext() == null) {
            return null;
        }
        String raw = context.pageContext().getArticleId();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
