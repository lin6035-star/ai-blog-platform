package com.hailin.blogsystem.ai.task.executor;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hailin.blogsystem.ai.LogValues;
import com.hailin.blogsystem.constants.BlogConstants;
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
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    public static final String TARGET_HOME = "home";
    public static final String TARGET_PROFILE = "profile";
    public static final String TARGET_EDITOR = "editor";
    public static final String TARGET_DRAFTS = "drafts";
    public static final String TARGET_HOT_RANK = "hotRank";
    public static final String TARGET_ARTICLE = "article";
    public static final String TARGET_USER_PROFILE = "userProfile";

    private static final Set<String> STATIC_TARGETS = Set.of(
            TARGET_HOME, TARGET_PROFILE, TARGET_EDITOR, TARGET_DRAFTS, TARGET_HOT_RANK);
    private static final Set<String> SUPPORTED_TARGETS = Set.of(
            TARGET_HOME, TARGET_PROFILE, TARGET_EDITOR, TARGET_DRAFTS, TARGET_HOT_RANK,
            TARGET_ARTICLE, TARGET_USER_PROFILE);

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
        if (TARGET_ARTICLE.equals(target)) {
            return navigateToArticle(step);
        }
        if (STATIC_TARGETS.contains(target)) {
            return dispatchStaticTarget(step, target);
        }
        log.warn("[TASK-EXEC] 不支持的导航目标（疑似模型越界）: stepId={}, target={}",
                step.stepId(), LogValues.safe(target));
        return StepResult.failed(step.stepId(), StepCode.INTERNAL_ERROR, "这个跳转我暂时做不了");
    }

    public static boolean supportsTarget(String target) {
        return target != null && SUPPORTED_TARGETS.contains(target);
    }

    public static boolean isArticleTarget(String target) {
        return TARGET_ARTICLE.equals(target);
    }

    private StepResult dispatchStaticTarget(TaskStep step, String target) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("target", target);
        return StepResult.dispatched(step.stepId(), "跳转到" + staticTargetLabel(target), output);
    }

    private String staticTargetLabel(String target) {
        return switch (target) {
            case TARGET_HOME -> "首页";
            case TARGET_PROFILE -> "个人中心";
            case TARGET_EDITOR -> "文章编辑器";
            case TARGET_DRAFTS -> "草稿箱";
            case TARGET_HOT_RANK -> "热门排行";
            default -> "目标页面";
        };
    }

    /** 标题由后端解析真实 ID；已有 ID 也必须回库确认文章当前仍为已发布状态。 */
    private StepResult navigateToArticle(TaskStep step) {
        String articleTitle = normalizeArticleTitle(step.input().get("articleTitle"));
        if (!articleTitle.isBlank()) {
            return navigateToArticleTitle(step, articleTitle);
        }

        Long articleId = positiveLong(step.input().get("param"));
        if (articleId == null) {
            return StepResult.skipped(step.stepId(), "没有识别到要打开的文章");
        }
        Articles article = articlesService.getOne(new QueryWrapper<Articles>()
                .select("id", "title")
                .eq("id", articleId)
                .eq("status", BlogConstants.ArticlesStatus.PUBLISHED));
        if (article == null) {
            return StepResult.skipped(step.stepId(), "没有找到这篇已发布文章");
        }
        return dispatchArticle(step, article);
    }

    private StepResult navigateToArticleTitle(TaskStep step, String articleTitle) {
        List<Articles> matched = articlesService.list(new QueryWrapper<Articles>()
                .select("id", "title")
                .eq("title", articleTitle)
                .eq("status", BlogConstants.ArticlesStatus.PUBLISHED)
                .last("LIMIT 2"));
        if (matched == null || matched.isEmpty()) {
            return StepResult.skipped(step.stepId(), "没有找到标题为《" + articleTitle + "》的已发布文章");
        }
        if (matched.size() > 1) {
            return StepResult.skipped(step.stepId(), "存在多篇同名文章，暂时无法确定要打开哪一篇");
        }
        return dispatchArticle(step, matched.get(0));
    }

    private StepResult dispatchArticle(TaskStep step, Articles article) {
        if (article.getId() == null) {
            return StepResult.failed(step.stepId(), StepCode.INTERNAL_ERROR, "文章缺少有效 ID，暂时无法打开");
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("target", TARGET_ARTICLE);
        output.put("param", String.valueOf(article.getId()));
        output.put("articleId", String.valueOf(article.getId()));
        output.put("articleTitle", article.getTitle());
        return StepResult.dispatched(step.stepId(), "打开文章《" + article.getTitle() + "》", output);
    }

    private Long positiveLong(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            long value = Long.parseLong(String.valueOf(raw).trim());
            return value > 0 ? value : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String normalizeArticleTitle(Object raw) {
        String value = raw == null ? "" : String.valueOf(raw).trim();
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '《' && last == '》')
                    || (first == '“' && last == '”')
                    || (first == '"' && last == '"')
                    || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1).trim();
            }
        }
        return value;
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
