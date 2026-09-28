package com.hailin.blogsystem;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.ai.agent.ArticleInteractionBatchService;
import com.hailin.blogsystem.entity.AiAgentRun;
import com.hailin.blogsystem.entity.AiAgentStep;
import com.hailin.blogsystem.entity.Articles;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

@SpringBootTest
class ArticleInteractionBatchServiceTests {

    private static final Long USER_ID = 9_990_001L;
    private static final Long SESSION_ID = 9_990_002L;
    private static final Long AUTHOR_ID = 9_990_003L;

    @Autowired
    private ArticleInteractionBatchService batchService;

    @Autowired
    private AiAgentRunMapper runMapper;

    @Autowired
    private AiAgentStepMapper stepMapper;

    @Autowired
    private ArticlesMapper articlesMapper;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ArticleLikesService likesService;

    @MockBean
    private ArticleFavoritesService favoritesService;

    @MockBean
    private FollowService followService;

    private final List<Long> runIds = new ArrayList<>();
    private Long articleId;

    @BeforeEach
    void setUp() {
        Articles article = new Articles();
        article.setAuthorId(AUTHOR_ID);
        article.setCategoryId(1L);
        article.setTitle("batch-test-" + System.nanoTime());
        article.setSummary("summary");
        article.setContent("content");
        article.setStatus(1);
        article.setViewCount(0);
        article.setLikeCount(0);
        article.setFavoriteCount(0);
        article.setCommentCount(0);
        article.setShareCount(0);
        article.setCreatedAt(LocalDateTime.now());
        article.setUpdatedAt(LocalDateTime.now());
        articlesMapper.insert(article);
        articleId = article.getId();
    }

    @AfterEach
    void cleanup() {
        for (Long runId : runIds) {
            stepMapper.delete(new LambdaQueryWrapper<AiAgentStep>()
                    .eq(AiAgentStep::getAgentRunId, runId));
            runMapper.deleteById(runId);
        }
        articlesMapper.deleteById(articleId);
    }

    @Test
    void completesOneRunAndStepEvenWhenOneBusinessActionFails() throws Exception {
        when(likesService.likeArticle(USER_ID, articleId))
                .thenReturn(success(ArticleInteractionAction.LIKE_ARTICLE, "点赞成功"));
        when(followService.followUser(USER_ID, AUTHOR_ID))
                .thenReturn(failure(ArticleInteractionAction.FOLLOW_AUTHOR, "不能关注自己"));

        ArticleInteractionBatchService.Execution result = batchService.execute(
                USER_ID,
                SESSION_ID,
                "帮我点赞并关注作者",
                new ArticleActionBatch(articleId, List.of(
                        ArticleInteractionAction.LIKE_ARTICLE,
                        ArticleInteractionAction.FOLLOW_AUTHOR)));
        runIds.add(result.agentRunId());

        AiAgentRun run = runMapper.selectById(result.agentRunId());
        AiAgentStep step = onlyStep(result.agentRunId());
        JsonNode output = objectMapper.readTree(step.getOutputJson());

        assertThat(result.message()).isEqualTo("部分完成：点赞成功。未完成：不能关注自己。");
        assertThat(result.actionResults()).hasSize(2);
        assertThat(run.getGoal()).isEqualTo("帮我点赞并关注作者");
        assertThat(run.getStatus()).isEqualTo(AiAgentRunStatus.COMPLETED.name());
        assertThat(run.getMaxSteps()).isEqualTo(1);
        assertThat(run.getCurrentStep()).isEqualTo(1);
        assertThat(run.getUsedSteps()).isEqualTo(1);
        assertThat(step.getActionType()).isEqualTo("ARTICLE_INTERACTION_BATCH");
        assertThat(step.getStatus()).isEqualTo(AiAgentStepStatus.SUCCESS.name());
        assertThat(output.path("results")).hasSize(2);
    }

    @Test
    void persistsEachCompletedItemBeforeExecutingTheNextOne() throws Exception {
        AtomicReference<String> outputSeenBeforeSecondAction = new AtomicReference<>();
        when(likesService.likeArticle(USER_ID, articleId))
                .thenReturn(success(ArticleInteractionAction.LIKE_ARTICLE, "点赞成功"));
        when(favoritesService.favoriteArticle(USER_ID, articleId)).thenAnswer(invocation -> {
            AiAgentStep runningStep = stepMapper.selectOne(new LambdaQueryWrapper<AiAgentStep>()
                    .eq(AiAgentStep::getActionType, "ARTICLE_INTERACTION_BATCH")
                    .eq(AiAgentStep::getStatus, AiAgentStepStatus.RUNNING.name())
                    .orderByDesc(AiAgentStep::getCreatedAt)
                    .last("LIMIT 1"));
            outputSeenBeforeSecondAction.set(runningStep.getOutputJson());
            return success(ArticleInteractionAction.FAVORITE_ARTICLE, "收藏成功");
        });

        ArticleInteractionBatchService.Execution result = batchService.execute(
                USER_ID,
                SESSION_ID,
                "帮我点赞并收藏",
                new ArticleActionBatch(articleId, List.of(
                        ArticleInteractionAction.LIKE_ARTICLE,
                        ArticleInteractionAction.FAVORITE_ARTICLE)));
        runIds.add(result.agentRunId());

        JsonNode intermediate = objectMapper.readTree(outputSeenBeforeSecondAction.get());
        assertThat(intermediate.path("results")).hasSize(1);
        assertThat(intermediate.path("results").get(0).path("actionType").asText())
                .isEqualTo(ArticleInteractionAction.LIKE_ARTICLE.name());
    }

    @Test
    void doesNotCancelExistingPendingConfirmationInTheSameSession() {
        AiAgentRun pending = insertPendingRun();
        runIds.add(pending.getId());
        when(likesService.likeArticle(USER_ID, articleId))
                .thenReturn(success(ArticleInteractionAction.LIKE_ARTICLE, "点赞成功"));

        ArticleInteractionBatchService.Execution result = batchService.execute(
                USER_ID,
                SESSION_ID,
                "帮我点赞",
                new ArticleActionBatch(articleId, List.of(ArticleInteractionAction.LIKE_ARTICLE)));
        runIds.add(result.agentRunId());

        assertThat(runMapper.selectById(pending.getId()).getStatus())
                .isEqualTo(AiAgentRunStatus.WAITING_WRITE_CONFIRM.name());
    }

    @Test
    void unexpectedFailureMarksRunAndStepFailedWithoutDiscardingPriorResults() throws Exception {
        when(likesService.likeArticle(USER_ID, articleId))
                .thenReturn(success(ArticleInteractionAction.LIKE_ARTICLE, "点赞成功"));
        when(favoritesService.favoriteArticle(USER_ID, articleId))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> batchService.execute(
                USER_ID,
                SESSION_ID,
                "帮我点赞并收藏",
                new ArticleActionBatch(articleId, List.of(
                        ArticleInteractionAction.LIKE_ARTICLE,
                        ArticleInteractionAction.FAVORITE_ARTICLE))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database unavailable");

        AiAgentRun run = latestRun();
        runIds.add(run.getId());
        AiAgentStep step = onlyStep(run.getId());
        JsonNode output = objectMapper.readTree(step.getOutputJson());

        assertThat(run.getStatus()).isEqualTo(AiAgentRunStatus.FAILED.name());
        assertThat(step.getStatus()).isEqualTo(AiAgentStepStatus.FAILED.name());
        assertThat(output.path("results")).hasSize(1);
        assertThat(output.path("results").get(0).path("actionType").asText())
                .isEqualTo(ArticleInteractionAction.LIKE_ARTICLE.name());
    }

    private AiAgentRun insertPendingRun() {
        AiAgentRun run = new AiAgentRun();
        run.setUserId(USER_ID);
        run.setSessionId(SESSION_ID);
        run.setGoal("旧确认卡");
        run.setStatus(AiAgentRunStatus.WAITING_WRITE_CONFIRM.name());
        run.setCurrentStep(1);
        run.setMaxSteps(3);
        run.setUsedSteps(1);
        run.setCreatedAt(LocalDateTime.now());
        run.setUpdatedAt(LocalDateTime.now());
        runMapper.insert(run);
        return run;
    }

    private AiAgentRun latestRun() {
        return runMapper.selectOne(new LambdaQueryWrapper<AiAgentRun>()
                .eq(AiAgentRun::getSessionId, SESSION_ID)
                .eq(AiAgentRun::getGoal, "帮我点赞并收藏")
                .orderByDesc(AiAgentRun::getCreatedAt)
                .last("LIMIT 1"));
    }

    private AiAgentStep onlyStep(Long runId) {
        return stepMapper.selectOne(new LambdaQueryWrapper<AiAgentStep>()
                .eq(AiAgentStep::getAgentRunId, runId));
    }

    private ArticleInteractionResult success(ArticleInteractionAction action, String summary) {
        return ArticleInteractionResult.success(
                action,
                ArticleInteractionResultCode.APPLIED,
                summary,
                false,
                true);
    }

    private ArticleInteractionResult failure(ArticleInteractionAction action, String summary) {
        return ArticleInteractionResult.failure(
                action,
                ArticleInteractionResultCode.FORBIDDEN,
                summary,
                false,
                false);
    }
}
