package com.hailin.blogsystem.ai.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.entity.AiAgentRun;
import com.hailin.blogsystem.entity.AiAgentStep;
import com.hailin.blogsystem.entity.Articles;
import com.hailin.blogsystem.entity.dto.AgentStepActionType;
import com.hailin.blogsystem.entity.dto.AiAgentRunStatus;
import com.hailin.blogsystem.entity.dto.AiAgentStepStatus;
import com.hailin.blogsystem.entity.dto.ArticleActionBatch;
import com.hailin.blogsystem.entity.dto.ArticleInteractionAction;
import com.hailin.blogsystem.entity.dto.ArticleInteractionResult;
import com.hailin.blogsystem.entity.dto.ArticleInteractionResultCode;
import com.hailin.blogsystem.mapper.AiAgentRunMapper;
import com.hailin.blogsystem.mapper.AiAgentStepMapper;
import com.hailin.blogsystem.mapper.ArticlesMapper;
import com.hailin.blogsystem.service.ArticleFavoritesService;
import com.hailin.blogsystem.service.ArticleLikesService;
import com.hailin.blogsystem.service.FollowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 执行一批低风险文章交互，并逐项持久化权威结果。 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ArticleInteractionBatchService {

    private final AiAgentRunMapper runMapper;
    private final AiAgentStepMapper stepMapper;
    private final ArticlesMapper articlesMapper;
    private final ArticleLikesService likesService;
    private final ArticleFavoritesService favoritesService;
    private final FollowService followService;
    private final ArticleInteractionResultRenderer renderer;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public Execution execute(Long userId, Long sessionId, String goal, ArticleActionBatch batch) {
        validate(userId, goal, batch);
        RunAndStep state = createRunAndStep(userId, sessionId, goal, batch);
        List<ArticleInteractionResult> results = new ArrayList<>();
        long startedAt = System.currentTimeMillis();

        try {
            Articles article = articlesMapper.selectById(batch.articleId());
            for (ArticleInteractionAction action : batch.actions()) {
                ArticleInteractionResult result = executeOne(userId, batch.articleId(), article, action);
                if (result.actionType() != action) {
                    throw new IllegalStateException("文章动作结果类型不一致: expected=" + action
                            + ", actual=" + result.actionType());
                }
                results.add(result);
                persistResults(state.step(), batch.articleId(), results);
            }

            String message = renderer.render(batch.actions(), results);
            complete(state, message, System.currentTimeMillis() - startedAt);
            long failedCount = results.stream().filter(result -> !result.outcome().name().equals("SUCCESS")).count();
            if (failedCount > 0) {
                log.warn("[ARTICLE-ACTION-BATCH] 部分业务动作未完成 runId={} articleId={} failed={} total={}",
                        state.run().getId(), batch.articleId(), failedCount, results.size());
            }
            return new Execution(state.run().getId(), message, List.copyOf(results));
        } catch (RuntimeException e) {
            fail(state, e, System.currentTimeMillis() - startedAt);
            throw e;
        }
    }

    private RunAndStep createRunAndStep(
            Long userId,
            Long sessionId,
            String goal,
            ArticleActionBatch batch
    ) {
        RunAndStep created = transactionTemplate.execute(status -> {
            LocalDateTime now = LocalDateTime.now();
            AiAgentRun run = new AiAgentRun();
            run.setUserId(userId);
            run.setSessionId(sessionId);
            run.setGoal(goal);
            run.setStatus(AiAgentRunStatus.RUNNING.name());
            run.setCurrentStep(0);
            run.setMaxSteps(1);
            run.setUsedSteps(0);
            run.setContextJson(toJson(Map.of("articleId", batch.articleId())));
            run.setInputTokens(0);
            run.setOutputTokens(0);
            run.setTotalTokens(0);
            run.setCreatedAt(now);
            run.setUpdatedAt(now);
            runMapper.insert(run);

            AiAgentStep step = new AiAgentStep();
            step.setAgentRunId(run.getId());
            step.setStepNo(1);
            step.setActionType(AgentStepActionType.ARTICLE_INTERACTION_BATCH.name());
            step.setInputJson(toJson(Map.of(
                    "articleId", batch.articleId(),
                    "actions", batch.actions())));
            step.setOutputJson(toJson(Map.of(
                    "articleId", batch.articleId(),
                    "results", List.of())));
            step.setStatus(AiAgentStepStatus.RUNNING.name());
            step.setInputTokens(0);
            step.setOutputTokens(0);
            step.setCreatedAt(now);
            stepMapper.insert(step);
            return new RunAndStep(run, step);
        });
        if (created == null) {
            throw new IllegalStateException("创建文章动作批次失败");
        }
        return created;
    }

    private ArticleInteractionResult executeOne(
            Long userId,
            Long articleId,
            Articles article,
            ArticleInteractionAction action
    ) {
        if (article == null) {
            return ArticleInteractionResult.failure(
                    action,
                    ArticleInteractionResultCode.TARGET_NOT_FOUND,
                    "文章不存在",
                    null,
                    null);
        }
        return switch (action) {
            case LIKE_ARTICLE -> likesService.likeArticle(userId, articleId);
            case UNLIKE_ARTICLE -> likesService.cancelLikeArticle(userId, articleId);
            case FAVORITE_ARTICLE -> favoritesService.favoriteArticle(userId, articleId);
            case UNFAVORITE_ARTICLE -> favoritesService.cancelFavorite(userId, articleId);
            case FOLLOW_AUTHOR -> followService.followUser(userId, article.getAuthorId());
            case UNFOLLOW_AUTHOR -> followService.cancelFollow(userId, article.getAuthorId());
        };
    }

    private void persistResults(
            AiAgentStep step,
            Long articleId,
            List<ArticleInteractionResult> results
    ) {
        step.setOutputJson(toJson(Map.of(
                "articleId", articleId,
                "results", List.copyOf(results))));
        stepMapper.updateById(step);
    }

    private void complete(RunAndStep state, String message, long durationMs) {
        transactionTemplate.executeWithoutResult(status -> {
            AiAgentStep step = state.step();
            step.setStatus(AiAgentStepStatus.SUCCESS.name());
            step.setDurationMs(durationMs);
            stepMapper.updateById(step);

            AiAgentRun run = state.run();
            run.setStatus(AiAgentRunStatus.COMPLETED.name());
            run.setCurrentStep(1);
            run.setUsedSteps(1);
            run.setFinalAnswer(message);
            run.setUpdatedAt(LocalDateTime.now());
            runMapper.updateById(run);
        });
    }

    private void fail(RunAndStep state, RuntimeException error, long durationMs) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                AiAgentStep step = state.step();
                step.setStatus(AiAgentStepStatus.FAILED.name());
                step.setDurationMs(durationMs);
                step.setErrorMessage(error.getMessage());
                stepMapper.updateById(step);

                AiAgentRun run = state.run();
                run.setStatus(AiAgentRunStatus.FAILED.name());
                run.setCurrentStep(1);
                run.setUsedSteps(1);
                run.setErrorMessage(error.getMessage());
                run.setUpdatedAt(LocalDateTime.now());
                runMapper.updateById(run);
            });
        } catch (RuntimeException persistenceError) {
            error.addSuppressed(persistenceError);
            log.error("[ARTICLE-ACTION-BATCH] 失败状态落库失败 runId={}", state.run().getId(), persistenceError);
        }
    }

    private void validate(Long userId, String goal, ArticleActionBatch batch) {
        if (userId == null) {
            throw new IllegalArgumentException("文章动作需要登录");
        }
        if (goal == null || goal.isBlank()) {
            throw new IllegalArgumentException("用户目标不能为空");
        }
        if (batch == null || batch.articleId() == null
                || batch.actions() == null || batch.actions().isEmpty()) {
            throw new IllegalArgumentException("文章动作批次不能为空");
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("文章动作批次 JSON 序列化失败", e);
        }
    }

    private record RunAndStep(AiAgentRun run, AiAgentStep step) {
    }

    public record Execution(
            Long agentRunId,
            String message,
            List<ArticleInteractionResult> actionResults
    ) {
        public Execution {
            actionResults = List.copyOf(actionResults);
        }
    }
}
