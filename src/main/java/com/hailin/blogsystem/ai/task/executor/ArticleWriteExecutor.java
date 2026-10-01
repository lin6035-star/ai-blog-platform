package com.hailin.blogsystem.ai.task.executor;

import com.hailin.blogsystem.ai.LogValues;
import com.hailin.blogsystem.ai.task.StepCode;
import com.hailin.blogsystem.ai.task.StepResult;
import com.hailin.blogsystem.ai.task.StepType;
import com.hailin.blogsystem.ai.task.TaskExecutionContext;
import com.hailin.blogsystem.ai.task.TaskStep;
import com.hailin.blogsystem.ai.task.TaskStepExecutor;
import com.hailin.blogsystem.entity.Articles;
import com.hailin.blogsystem.entity.dto.ArticleInteractionAction;
import com.hailin.blogsystem.entity.dto.ArticleInteractionOutcome;
import com.hailin.blogsystem.entity.dto.ArticleInteractionResult;
import com.hailin.blogsystem.entity.dto.ArticleInteractionResultCode;
import com.hailin.blogsystem.service.ArticleFavoritesService;
import com.hailin.blogsystem.service.ArticleLikesService;
import com.hailin.blogsystem.service.ArticlesService;
import com.hailin.blogsystem.service.FollowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 文章低风险写动作执行器（第三刀）——点赞 / 取消点赞 / 收藏 / 取消收藏 / 关注 / 取消关注。
 *
 * <p><b>为什么不复用 {@code ArticleInteractionBatchService}</b>：它的职责是"跑一整批 + 建 run/step +
 * 渲染文案"，而在 Orchestrator 里**每个 TaskStep 是一个动作，不存在"一整批"这个概念**。
 * 直接用它会让一次请求出现两套审计。这里改为**直接调第一刀建好的结构化领域方法**
 * （{@code likeArticle(userId, articleId)} 等），旧的批次服务保持不动、继续服务老路径（§5.1 并存）。
 *
 * <p><b>参数只从后端可信来源取</b>：{@code articleId} 只认页面上下文，{@code authorId} 由本执行器
 * 查库获得——不接受 LLM 自报（沿用「LLM 判语义、后端判事实」）。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ArticleWriteExecutor implements TaskStepExecutor {

    private final ArticleLikesService articleLikesService;
    private final ArticleFavoritesService articleFavoritesService;
    private final FollowService followService;
    private final ArticlesService articlesService;

    @Override
    public StepType supports() {
        return StepType.ARTICLE_WRITE;
    }

    @Override
    public StepResult execute(TaskExecutionContext context, TaskStep step) {
        Long userId = context.userId();
        if (userId == null) {
            return StepResult.failed(step.stepId(), StepCode.FORBIDDEN, "请先登录");
        }

        String rawAction = String.valueOf(step.input().getOrDefault("action", ""));
        ArticleInteractionAction action = ArticleInteractionAction
                .fromClassifierValue(rawAction)
                .orElse(null);
        if (action == null) {
            // C0.2：原始值是模型给的内部协议值，只能进日志——进了用户回复就是"协议泄露"
            // （参数校验是主闸门，这里只是防御分支，保留是为了万一还有别的入口）
            log.warn("[TASK-EXEC] 未知文章动作（疑似模型越界）: stepId={}, rawAction={}",
                    step.stepId(), LogValues.safe(rawAction));
            return StepResult.failed(step.stepId(), StepCode.INTERNAL_ERROR, "这个操作我暂时做不了");
        }

        Long articleId = resolveArticleId(context);
        if (articleId == null) {
            return StepResult.failed(step.stepId(), StepCode.TARGET_NOT_FOUND, "没有定位到目标文章");
        }

        // 关注类动作的目标是作者而不是文章——同样由后端查库获得，不让 LLM 提供
        Long authorId = action == ArticleInteractionAction.FOLLOW_AUTHOR
                || action == ArticleInteractionAction.UNFOLLOW_AUTHOR
                ? resolveAuthorId(articleId)
                : null;
        if (needsAuthor(action) && authorId == null) {
            return StepResult.failed(step.stepId(), StepCode.TARGET_NOT_FOUND, "没有找到文章作者");
        }

        try {
            ArticleInteractionResult result = dispatch(action, userId, articleId, authorId);
            return toStepResult(step.stepId(), result);
        } catch (Exception e) {
            // 领域服务约定不抛业务异常；这里兜的是真正的系统异常
            log.error("[TASK-EXEC] 文章写动作异常: stepId={}, action={}, articleId={}",
                    step.stepId(), action, articleId, e);
            return StepResult.failed(step.stepId(), StepCode.INTERNAL_ERROR, "操作失败，请稍后重试");
        }
    }

    private boolean needsAuthor(ArticleInteractionAction action) {
        return action == ArticleInteractionAction.FOLLOW_AUTHOR
                || action == ArticleInteractionAction.UNFOLLOW_AUTHOR;
    }

    private ArticleInteractionResult dispatch(ArticleInteractionAction action,
                                              Long userId, Long articleId, Long authorId) {
        return switch (action) {
            case LIKE_ARTICLE -> articleLikesService.likeArticle(userId, articleId);
            case UNLIKE_ARTICLE -> articleLikesService.cancelLikeArticle(userId, articleId);
            case FAVORITE_ARTICLE -> articleFavoritesService.favoriteArticle(userId, articleId);
            case UNFAVORITE_ARTICLE -> articleFavoritesService.cancelFavorite(userId, articleId);
            case FOLLOW_AUTHOR -> followService.followUser(userId, authorId);
            case UNFOLLOW_AUTHOR -> followService.cancelFollow(userId, authorId);
        };
    }

    /** 目标文章只认页面上下文——与第一刀一致，不接受 LLM 自报 */
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

    private Long resolveAuthorId(Long articleId) {
        Articles article = articlesService.getById(articleId);
        return article == null ? null : article.getAuthorId();
    }

    /** 领域结果 → 步骤结果。**不解析 summary**，只读 outcome / resultCode */
    private StepResult toStepResult(String stepId, ArticleInteractionResult result) {
        if (result == null) {
            return StepResult.failed(stepId, StepCode.INTERNAL_ERROR, "操作没有返回结果");
        }
        Map<String, Object> output = new java.util.LinkedHashMap<>();
        output.put("interactionResult", result);
        output.put("action", result.actionType() == null ? "" : result.actionType().getClassifierValue());
        output.put("before", result.before() == null ? "" : String.valueOf(result.before()));
        output.put("after", result.after() == null ? "" : String.valueOf(result.after()));

        if (result.outcome() == ArticleInteractionOutcome.SUCCESS) {
            return result.resultCode() == ArticleInteractionResultCode.ALREADY_SATISFIED
                    ? StepResult.alreadySatisfied(stepId, result.summary(), output)
                    : StepResult.applied(stepId, result.summary(), output);
        }
        return StepResult.failed(stepId, mapCode(result.resultCode()), result.summary());
    }

    private StepCode mapCode(ArticleInteractionResultCode code) {
        if (code == null) {
            return StepCode.INTERNAL_ERROR;
        }
        return switch (code) {
            case APPLIED -> StepCode.APPLIED;
            case ALREADY_SATISFIED -> StepCode.ALREADY_SATISFIED;
            case TARGET_NOT_FOUND -> StepCode.TARGET_NOT_FOUND;
            case FORBIDDEN -> StepCode.FORBIDDEN;
            case CONFLICT -> StepCode.CONFLICT;
            case INTERNAL_ERROR -> StepCode.INTERNAL_ERROR;
        };
    }
}
