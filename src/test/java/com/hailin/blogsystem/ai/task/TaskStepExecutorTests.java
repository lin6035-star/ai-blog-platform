package com.hailin.blogsystem.ai.task;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.hailin.blogsystem.ai.task.executor.ArticleSearchExecutor;
import com.hailin.blogsystem.ai.task.executor.ArticleWriteExecutor;
import com.hailin.blogsystem.ai.task.executor.NavigateExecutor;
import com.hailin.blogsystem.ai.rag.ArticleRagSearchService;
import com.hailin.blogsystem.entity.Articles;
import com.hailin.blogsystem.entity.dto.AiIntent;
import com.hailin.blogsystem.entity.dto.ArticleRagContext;
import com.hailin.blogsystem.entity.dto.ArticleRagSearchResult;
import com.hailin.blogsystem.entity.dto.ArticleRagSearchStrategy;
import com.hailin.blogsystem.entity.dto.ArticleInteractionAction;
import com.hailin.blogsystem.entity.dto.ArticleInteractionResult;
import com.hailin.blogsystem.entity.dto.ArticleInteractionResultCode;
import com.hailin.blogsystem.entity.dto.PageContextDTO;
import com.hailin.blogsystem.service.ArticleFavoritesService;
import com.hailin.blogsystem.service.ArticleLikesService;
import com.hailin.blogsystem.service.ArticlesService;
import com.hailin.blogsystem.service.FollowService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 三个确定性执行器的测试（第三刀·第二步，2a）。
 *
 * <p>锁两类容易写错的语义：**DISPATCHED 不是 SUCCEEDED**（客户端步骤后端不知道结果）、
 * **SKIPPED 不是 FAILED**（没机会执行 ≠ 执行失败）。
 */
class TaskStepExecutorTests {

    private static final Long USER = 1L;
    private static final Long ARTICLE_ID = 12L;
    private static final Long AUTHOR_ID = 99L;

    private final ArticleLikesService likesService = mock(ArticleLikesService.class);
    private final ArticleFavoritesService favoritesService = mock(ArticleFavoritesService.class);
    private final FollowService followService = mock(FollowService.class);
    private final ArticlesService articlesService = mock(ArticlesService.class);
    private final ArticleRagSearchService ragSearchService = mock(ArticleRagSearchService.class);

    private TaskExecutionContext context(String articleId) {
        PageContextDTO pageContext = new PageContextDTO();
        pageContext.setPageType("article-detail");
        pageContext.setArticleId(articleId);
        TaskPlan plan = new TaskPlan("原话", List.of(), List.of());
        return new TaskExecutionContext(USER, 10L, pageContext, plan);
    }

    private TaskStep step(StepType type, Map<String, Object> input) {
        return new TaskStep("s1", type, input, StepDependency.none(), List.of("原话片段"));
    }

    private Articles article() {
        Articles article = new Articles();
        article.setId(ARTICLE_ID);
        article.setTitle("Redis 缓存设计");
        article.setAuthorId(AUTHOR_ID);
        return article;
    }

    // ==================== C0.2：防御分支不泄露内部协议值 ====================

    @Test
    void unknownArticleActionDoesNotLeakRawValue() {
        ArticleWriteExecutor executor = new ArticleWriteExecutor(
                likesService, favoritesService, followService, articlesService);

        // C0.1 是主闸门（规划期就拒），这里是执行器的防御分支——万一还有别的入口
        StepResult result = executor.execute(context("12"),
                step(StepType.ARTICLE_WRITE, Map.of("action", "changeTitle")));

        assertThat(result.state()).isEqualTo(StepState.FAILED);
        assertThat(result.summary())
                .as("内部协议值只能进日志，不能进用户可见文案")
                .doesNotContain("changeTitle");
    }

    @Test
    void unsupportedNavTargetDoesNotLeakRawValue() {
        when(articlesService.getById(ARTICLE_ID)).thenReturn(article());
        NavigateExecutor executor = new NavigateExecutor(articlesService);

        StepResult result = executor.execute(context("12"),
                step(StepType.NAVIGATE, Map.of("target", "evilTarget")));

        assertThat(result.summary())
                .as("导航目标同样来自模型输出，不能原样回给用户")
                .doesNotContain("evilTarget");
    }

    // ==================== ARTICLE_WRITE ====================

    @Test
    void likeSuccessReturnsApplied() {
        when(likesService.likeArticle(USER, ARTICLE_ID)).thenReturn(
                ArticleInteractionResult.success(ArticleInteractionAction.LIKE_ARTICLE,
                        ArticleInteractionResultCode.APPLIED, "点赞成功", false, true));

        ArticleWriteExecutor executor = new ArticleWriteExecutor(
                likesService, favoritesService, followService, articlesService);
        StepResult result = executor.execute(context("12"),
                step(StepType.ARTICLE_WRITE, Map.of("action", "likeArticle")));

        assertThat(result.state()).isEqualTo(StepState.SUCCEEDED);
        assertThat(result.code()).isEqualTo(StepCode.APPLIED);
        assertThat(result.summary()).isEqualTo("点赞成功");
        assertThat(result.output()).containsKey("interactionResult");
    }

    @Test
    void alreadyLikedIsSuccessNotFailure() {
        when(likesService.likeArticle(USER, ARTICLE_ID)).thenReturn(
                ArticleInteractionResult.success(ArticleInteractionAction.LIKE_ARTICLE,
                        ArticleInteractionResultCode.ALREADY_SATISFIED, "已经点过赞", true, true));

        ArticleWriteExecutor executor = new ArticleWriteExecutor(
                likesService, favoritesService, followService, articlesService);
        StepResult result = executor.execute(context("12"),
                step(StepType.ARTICLE_WRITE, Map.of("action", "likeArticle")));

        // 目标状态本已满足 = 成功，不是失败
        assertThat(result.state()).isEqualTo(StepState.SUCCEEDED);
        assertThat(result.code()).isEqualTo(StepCode.ALREADY_SATISFIED);
    }

    @Test
    void targetNotFoundReturnsFailedWithMappedCode() {
        when(likesService.likeArticle(USER, ARTICLE_ID)).thenReturn(
                ArticleInteractionResult.failure(ArticleInteractionAction.LIKE_ARTICLE,
                        ArticleInteractionResultCode.TARGET_NOT_FOUND, "文章不存在", null, null));

        ArticleWriteExecutor executor = new ArticleWriteExecutor(
                likesService, favoritesService, followService, articlesService);
        StepResult result = executor.execute(context("12"),
                step(StepType.ARTICLE_WRITE, Map.of("action", "likeArticle")));

        assertThat(result.state()).isEqualTo(StepState.FAILED);
        assertThat(result.code()).isEqualTo(StepCode.TARGET_NOT_FOUND);
    }

    @Test
    void notLoggedInFailsBeforeCallingService() {
        PageContextDTO pageContext = new PageContextDTO();
        pageContext.setArticleId("12");
        TaskExecutionContext anonymous = new TaskExecutionContext(
                null, 10L, pageContext, new TaskPlan("x", List.of(), List.of()));

        ArticleWriteExecutor executor = new ArticleWriteExecutor(
                likesService, favoritesService, followService, articlesService);
        StepResult result = executor.execute(anonymous,
                step(StepType.ARTICLE_WRITE, Map.of("action", "likeArticle")));

        assertThat(result.state()).isEqualTo(StepState.FAILED);
        assertThat(result.code()).isEqualTo(StepCode.FORBIDDEN);
    }

    @Test
    void followAuthorResolvesAuthorIdFromArticle() {
        when(articlesService.getById(ARTICLE_ID)).thenReturn(article());
        when(followService.followUser(USER, AUTHOR_ID)).thenReturn(
                ArticleInteractionResult.success(ArticleInteractionAction.FOLLOW_AUTHOR,
                        ArticleInteractionResultCode.APPLIED, "关注成功", false, true));

        ArticleWriteExecutor executor = new ArticleWriteExecutor(
                likesService, favoritesService, followService, articlesService);
        StepResult result = executor.execute(context("12"),
                step(StepType.ARTICLE_WRITE, Map.of("action", "followAuthor")));

        // 关注的目标是作者，authorId 由后端查文章获得——不是 articleId
        assertThat(result.state()).isEqualTo(StepState.SUCCEEDED);
    }

    @Test
    void unknownActionFailsWithInternalError() {
        ArticleWriteExecutor executor = new ArticleWriteExecutor(
                likesService, favoritesService, followService, articlesService);
        StepResult result = executor.execute(context("12"),
                step(StepType.ARTICLE_WRITE, Map.of("action", "danceArticle")));

        assertThat(result.state()).isEqualTo(StepState.FAILED);
        assertThat(result.code()).isEqualTo(StepCode.INTERNAL_ERROR);
    }

    // ==================== ARTICLE_SEARCH ====================

    @Test
    void searchReturnsTitles() {
        when(ragSearchService.search(eq("Redis"), any(AiIntent.class))).thenReturn(
                new ArticleRagSearchResult(ArticleRagSearchStrategy.HYBRID, List.of(
                        new ArticleRagContext(ARTICLE_ID, "Redis 缓存设计", 2,
                                "介绍 Redis 缓存一致性、击穿与高并发治理"),
                        new ArticleRagContext(ARTICLE_ID, "Redis 缓存设计", 4,
                                "同一篇文章的另一个命中片段"),
                        new ArticleRagContext(13L, "Redis 秒杀实践", 1,
                                "库存预扣与异步削峰")
                )));

        ArticleSearchExecutor executor = new ArticleSearchExecutor(ragSearchService);
        StepResult result = executor.execute(context("12"),
                step(StepType.ARTICLE_SEARCH, Map.of("query", "Redis")));

        assertThat(result.state()).isEqualTo(StepState.SUCCEEDED);
        assertThat(result.summary()).contains("Redis 缓存设计");
        assertThat(result.output()).containsEntry("total", 2L);
        assertThat(result.output().get("articles")).isEqualTo(List.of(
                Map.of(
                        "articleId", String.valueOf(ARTICLE_ID),
                        "title", "Redis 缓存设计",
                        "summary", "介绍 Redis 缓存一致性、击穿与高并发治理"),
                Map.of(
                        "articleId", "13",
                        "title", "Redis 秒杀实践",
                        "summary", "库存预扣与异步削峰")));
        assertThat(result.output().get("ragContexts")).isEqualTo(List.of(
                Map.of(
                        "articleId", String.valueOf(ARTICLE_ID),
                        "title", "Redis 缓存设计",
                        "chunkIndex", 2,
                        "content", "介绍 Redis 缓存一致性、击穿与高并发治理"),
                Map.of(
                        "articleId", String.valueOf(ARTICLE_ID),
                        "title", "Redis 缓存设计",
                        "chunkIndex", 4,
                        "content", "同一篇文章的另一个命中片段"),
                Map.of(
                        "articleId", "13",
                        "title", "Redis 秒杀实践",
                        "chunkIndex", 1,
                        "content", "库存预扣与异步削峰")));
        verify(ragSearchService).search(eq("Redis"), argThat(intent ->
                "ARTICLE_SEARCH".equals(intent.getIntent()) && "Redis".equals(intent.getKeyWord())));
    }

    @Test
    void emptyResultStillSucceeds() {
        when(ragSearchService.search(eq("不存在的词"), any(AiIntent.class))).thenReturn(
                new ArticleRagSearchResult(ArticleRagSearchStrategy.EMPTY, List.of()));

        ArticleSearchExecutor executor = new ArticleSearchExecutor(ragSearchService);
        StepResult result = executor.execute(context("12"),
                step(StepType.ARTICLE_SEARCH, Map.of("query", "不存在的词")));

        // "没找到"不是失败——搜索本身成功了
        assertThat(result.state()).isEqualTo(StepState.SUCCEEDED);
        assertThat(result.summary()).contains("没有找到");
    }

    @Test
    void searchCapsCurrentRunRagContextsAtFive() {
        List<ArticleRagContext> contexts = java.util.stream.IntStream.rangeClosed(1, 6)
                .mapToObj(index -> new ArticleRagContext(
                        ARTICLE_ID,
                        "Redis 缓存设计",
                        index,
                        "正文片段 " + index))
                .toList();
        when(ragSearchService.search(eq("Redis"), any(AiIntent.class))).thenReturn(
                new ArticleRagSearchResult(ArticleRagSearchStrategy.HYBRID, contexts));

        StepResult result = new ArticleSearchExecutor(ragSearchService).execute(
                context("12"),
                step(StepType.ARTICLE_SEARCH, Map.of("query", "Redis")));

        List<?> ragContexts = (List<?>) result.output().get("ragContexts");
        assertThat(ragContexts).hasSize(5);
        assertThat(ragContexts.stream()
                .map(item -> ((Map<?, ?>) item).get("chunkIndex"))
                .toList()).isEqualTo(List.of(1, 2, 3, 4, 5));
        assertThat((List<?>) result.output().get("articles")).hasSize(1);
    }

    @Test
    void blankQueryFails() {
        ArticleSearchExecutor executor = new ArticleSearchExecutor(ragSearchService);
        StepResult result = executor.execute(context("12"),
                step(StepType.ARTICLE_SEARCH, Map.of()));

        assertThat(result.state()).isEqualTo(StepState.FAILED);
    }

    // ==================== NAVIGATE ====================

    @Test
    void navigateReturnsDispatchedNotSucceeded() {
        when(articlesService.getById(ARTICLE_ID)).thenReturn(article());

        NavigateExecutor executor = new NavigateExecutor(articlesService);
        StepResult result = executor.execute(context("12"),
                step(StepType.NAVIGATE, Map.of("target", "userProfile")));

        // 客户端步骤由前端执行，后端不知道结果——只能是 DISPATCHED
        assertThat(result.state()).isEqualTo(StepState.DISPATCHED);
        assertThat(result.state()).isNotEqualTo(StepState.SUCCEEDED);
        assertThat(result.isSuccessLike()).isTrue();   // 但算达成目标，不是失败
        assertThat(result.output()).containsEntry("userId", String.valueOf(AUTHOR_ID));
        assertThat(result.output()).containsEntry("target", "userProfile");
        assertThat(result.output()).containsEntry("param", String.valueOf(AUTHOR_ID));
    }

    @Test
    void noArticleContextSkipsInsteadOfFailing() {
        NavigateExecutor executor = new NavigateExecutor(articlesService);
        StepResult result = executor.execute(context(null),
                step(StepType.NAVIGATE, Map.of("target", "userProfile")));

        // 定位不到文章是"没机会执行"，不是"执行失败"——汇总里要说明原因
        assertThat(result.state()).isEqualTo(StepState.SKIPPED);
        assertThat(result.state()).isNotEqualTo(StepState.FAILED);
        assertThat(result.summary()).isNotBlank();
    }

    @Test
    void missingAuthorSkipsInsteadOfFailing() {
        when(articlesService.getById(anyLong())).thenReturn(null);

        NavigateExecutor executor = new NavigateExecutor(articlesService);
        StepResult result = executor.execute(context("12"),
                step(StepType.NAVIGATE, Map.of("target", "userProfile")));

        assertThat(result.state()).isEqualTo(StepState.SKIPPED);
    }

    @Test
    void articleTitleNavigationResolvesPublishedArticleId() {
        Articles matched = article();
        matched.setTitle("Redis 缓存：从入门到高并发场景的实践");
        when(articlesService.list(org.mockito.ArgumentMatchers.<Wrapper<Articles>>any()))
                .thenReturn(List.of(matched));

        NavigateExecutor executor = new NavigateExecutor(articlesService);
        StepResult result = executor.execute(context(null),
                step(StepType.NAVIGATE, Map.of(
                        "target", "article",
                        "articleTitle", "《Redis 缓存：从入门到高并发场景的实践》")));

        assertThat(result.state()).isEqualTo(StepState.DISPATCHED);
        assertThat(result.output())
                .containsEntry("target", "article")
                .containsEntry("param", String.valueOf(ARTICLE_ID))
                .containsEntry("articleId", String.valueOf(ARTICLE_ID));
    }

    @Test
    void articleIdNavigationRechecksPublishedArticleBeforeDispatch() {
        Articles matched = article();
        matched.setTitle("Redis 缓存：从入门到高并发场景的实践");
        when(articlesService.getOne(org.mockito.ArgumentMatchers.<Wrapper<Articles>>any()))
                .thenReturn(matched);

        NavigateExecutor executor = new NavigateExecutor(articlesService);
        StepResult result = executor.execute(context(null),
                step(StepType.NAVIGATE, Map.of(
                        "target", "article",
                        "param", String.valueOf(ARTICLE_ID))));

        assertThat(result.state()).isEqualTo(StepState.DISPATCHED);
        assertThat(result.output())
                .containsEntry("target", "article")
                .containsEntry("param", String.valueOf(ARTICLE_ID))
                .containsEntry("articleTitle", "Redis 缓存：从入门到高并发场景的实践");
    }

    @Test
    void unknownArticleIdDoesNotDispatch() {
        when(articlesService.getOne(org.mockito.ArgumentMatchers.<Wrapper<Articles>>any()))
                .thenReturn(null);

        NavigateExecutor executor = new NavigateExecutor(articlesService);
        StepResult result = executor.execute(context(null),
                step(StepType.NAVIGATE, Map.of(
                        "target", "article",
                        "param", String.valueOf(ARTICLE_ID))));

        assertThat(result.state()).isEqualTo(StepState.SKIPPED);
        assertThat(result.output()).doesNotContainKeys("param", "articleId");
    }

    @Test
    void staticNavigationTargetDispatchesWithoutArticleContext() {
        NavigateExecutor executor = new NavigateExecutor(articlesService);

        StepResult result = executor.execute(context(null),
                step(StepType.NAVIGATE, Map.of("target", "profile")));

        assertThat(result.state()).isEqualTo(StepState.DISPATCHED);
        assertThat(result.output()).containsEntry("target", "profile");
    }

    @Test
    void unknownArticleTitleSkipsWithoutGuessingId() {
        when(articlesService.list(org.mockito.ArgumentMatchers.<Wrapper<Articles>>any()))
                .thenReturn(List.of());

        NavigateExecutor executor = new NavigateExecutor(articlesService);
        StepResult result = executor.execute(context(null),
                step(StepType.NAVIGATE, Map.of(
                        "target", "article",
                        "articleTitle", "不存在的文章")));

        assertThat(result.state()).isEqualTo(StepState.SKIPPED);
        assertThat(result.output()).doesNotContainKeys("param", "articleId");
    }
}
