package com.hailin.blogsystem;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hailin.blogsystem.component.DelayedCacheEvictor;
import com.hailin.blogsystem.component.UserSetCache;
import com.hailin.blogsystem.constants.RedisConstants;
import com.hailin.blogsystem.entity.ArticleFavorites;
import com.hailin.blogsystem.entity.ArticleLikes;
import com.hailin.blogsystem.entity.Articles;
import com.hailin.blogsystem.entity.UserFollows;
import com.hailin.blogsystem.entity.Users;
import com.hailin.blogsystem.entity.dto.ArticleInteractionResult;
import com.hailin.blogsystem.entity.dto.ArticleInteractionResultCode;
import com.hailin.blogsystem.mapper.ArticleFavoritesMapper;
import com.hailin.blogsystem.mapper.ArticleLikesMapper;
import com.hailin.blogsystem.mapper.ArticlesMapper;
import com.hailin.blogsystem.mapper.FollowMapper;
import com.hailin.blogsystem.mapper.UsersMapper;
import com.hailin.blogsystem.service.ArticleFavoritesService;
import com.hailin.blogsystem.service.ArticleLikesService;
import com.hailin.blogsystem.service.FollowService;
import com.hailin.blogsystem.task.ArticleViewCountSyncTask;
import com.hailin.blogsystem.utils.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
class ArticleInteractionDomainTests {

    private static final AtomicLong USER_ID_SEQUENCE = new AtomicLong(9_980_000L);

    @Autowired
    private ArticleLikesService articleLikesService;

    @Autowired
    private ArticleFavoritesService articleFavoritesService;

    @Autowired
    private FollowService followService;

    @Autowired
    private ArticlesMapper articlesMapper;

    @Autowired
    private ArticleLikesMapper articleLikesMapper;

    @Autowired
    private ArticleFavoritesMapper articleFavoritesMapper;

    @Autowired
    private UsersMapper usersMapper;

    @Autowired
    private FollowMapper followMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    @MockBean
    private UserSetCache userSetCache;

    @MockBean
    private DelayedCacheEvictor delayedCacheEvictor;

    @MockBean
    private ArticleViewCountSyncTask articleViewCountSyncTask;

    @SuppressWarnings("unchecked")
    private final ZSetOperations<String, String> zSetOperations =
            (ZSetOperations<String, String>) org.mockito.Mockito.mock(ZSetOperations.class);

    private Long articleId;
    private Long actorId;
    private Long targetId;

    @BeforeEach
    void setUp() {
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);
        actorId = USER_ID_SEQUENCE.incrementAndGet();
        targetId = USER_ID_SEQUENCE.incrementAndGet();
        insertUser(actorId, "interaction_actor_" + actorId);
        insertUser(targetId, "interaction_target_" + targetId);

        Articles article = new Articles();
        article.setTitle("interaction-domain-" + System.nanoTime());
        article.setSummary("summary");
        article.setContent("content");
        article.setAuthorId(targetId);
        article.setCategoryId(1L);
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
        clearInvocations(zSetOperations);
    }

    @AfterEach
    void tearDown() {
        followMapper.delete(new LambdaQueryWrapper<UserFollows>()
                .eq(UserFollows::getFollowerId, actorId)
                .eq(UserFollows::getFollowingId, targetId));
        articleLikesMapper.delete(new LambdaQueryWrapper<ArticleLikes>()
                .eq(ArticleLikes::getArticleId, articleId));
        articleFavoritesMapper.delete(new LambdaQueryWrapper<ArticleFavorites>()
                .eq(ArticleFavorites::getArticleId, articleId));
        articlesMapper.deleteById(articleId);
        UserContext.clear();
    }

    @Test
    void explicitActorLikeIsIdempotentAndDoesNotDependOnUserContext() {
        UserContext.clear();

        ArticleInteractionResult first = articleLikesService.likeArticle(actorId, articleId);
        ArticleInteractionResult second = articleLikesService.likeArticle(actorId, articleId);

        assertThat(first.resultCode()).isEqualTo(ArticleInteractionResultCode.APPLIED);
        assertThat(second.resultCode()).isEqualTo(ArticleInteractionResultCode.ALREADY_SATISFIED);
        assertThat(reloadArticle().getLikeCount()).isEqualTo(1);
        assertThat(articleLikesMapper.selectCount(new LambdaQueryWrapper<ArticleLikes>()
                .eq(ArticleLikes::getArticleId, articleId)
                .eq(ArticleLikes::getUserId, actorId))).isEqualTo(1);
    }

    @Test
    void explicitActorFavoriteAndUnfavoriteReturnAuthoritativeState() {
        ArticleInteractionResult applied = articleFavoritesService.favoriteArticle(actorId, articleId);
        ArticleInteractionResult already = articleFavoritesService.favoriteArticle(actorId, articleId);
        ArticleInteractionResult removed = articleFavoritesService.cancelFavorite(actorId, articleId);
        ArticleInteractionResult alreadyRemoved = articleFavoritesService.cancelFavorite(actorId, articleId);

        assertThat(applied.resultCode()).isEqualTo(ArticleInteractionResultCode.APPLIED);
        assertThat(already.resultCode()).isEqualTo(ArticleInteractionResultCode.ALREADY_SATISFIED);
        assertThat(removed.resultCode()).isEqualTo(ArticleInteractionResultCode.APPLIED);
        assertThat(alreadyRemoved.resultCode()).isEqualTo(ArticleInteractionResultCode.ALREADY_SATISFIED);
        assertThat(reloadArticle().getFavoriteCount()).isZero();
    }

    @Test
    void manualFavoriteStillRequiresLoginWithExistingMessage() {
        UserContext.clear();

        assertThatThrownBy(() -> articleFavoritesService.favoriteArticle(articleId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("请先登录");
    }

    @Test
    void hotRankMutationRunsOnlyAfterTransactionCommit() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> {
            articleLikesService.likeArticle(actorId, articleId);
            verify(zSetOperations, never()).incrementScore(
                    RedisConstants.ARTICLE_HOT_KEY,
                    String.valueOf(articleId),
                    RedisConstants.ARTICLE_LIKE_HOT_SCORE
            );
        });

        verify(zSetOperations).incrementScore(
                RedisConstants.ARTICLE_HOT_KEY,
                String.valueOf(articleId),
                RedisConstants.ARTICLE_LIKE_HOT_SCORE
        );
    }

    @Test
    void followAndUnfollowExposeIdempotentAuthoritativeResults() {
        ArticleInteractionResult followed = followService.followUser(actorId, targetId);
        ArticleInteractionResult alreadyFollowed = followService.followUser(actorId, targetId);
        ArticleInteractionResult unfollowed = followService.cancelFollow(actorId, targetId);
        ArticleInteractionResult alreadyUnfollowed = followService.cancelFollow(actorId, targetId);

        assertThat(followed.resultCode()).isEqualTo(ArticleInteractionResultCode.APPLIED);
        assertThat(alreadyFollowed.resultCode()).isEqualTo(ArticleInteractionResultCode.ALREADY_SATISFIED);
        assertThat(unfollowed.resultCode()).isEqualTo(ArticleInteractionResultCode.APPLIED);
        assertThat(alreadyUnfollowed.resultCode()).isEqualTo(ArticleInteractionResultCode.ALREADY_SATISFIED);
        assertThat(usersMapper.selectById(actorId).getFollowingCount()).isZero();
        assertThat(usersMapper.selectById(targetId).getFollowersCount()).isZero();
    }

    @Test
    void followRejectsSelfAndMissingTargetWithStableCodes() {
        ArticleInteractionResult self = followService.followUser(actorId, actorId);
        ArticleInteractionResult missing = followService.followUser(actorId, 999_999_999L);

        assertThat(self.resultCode()).isEqualTo(ArticleInteractionResultCode.FORBIDDEN);
        assertThat(missing.resultCode()).isEqualTo(ArticleInteractionResultCode.TARGET_NOT_FOUND);
    }

    @Test
    void concurrentRestoreChangesFollowCountersOnlyOnce() throws Exception {
        assertThat(followService.followUser(actorId, targetId).resultCode())
                .isEqualTo(ArticleInteractionResultCode.APPLIED);
        assertThat(followService.cancelFollow(actorId, targetId).resultCode())
                .isEqualTo(ArticleInteractionResultCode.APPLIED);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CompletableFuture<ArticleInteractionResult> first = concurrentFollow(ready, start);
        CompletableFuture<ArticleInteractionResult> second = concurrentFollow(ready, start);
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        List<ArticleInteractionResultCode> codes = List.of(first.get().resultCode(), second.get().resultCode());
        assertThat(codes).containsExactlyInAnyOrder(
                ArticleInteractionResultCode.APPLIED,
                ArticleInteractionResultCode.ALREADY_SATISFIED);
        assertThat(usersMapper.selectById(actorId).getFollowingCount()).isEqualTo(1);
        assertThat(usersMapper.selectById(targetId).getFollowersCount()).isEqualTo(1);
    }

    private Articles reloadArticle() {
        return articlesMapper.selectById(articleId);
    }

    private CompletableFuture<ArticleInteractionResult> concurrentFollow(
            CountDownLatch ready,
            CountDownLatch start
    ) {
        return CompletableFuture.supplyAsync(() -> {
            ready.countDown();
            try {
                start.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return followService.followUser(actorId, targetId);
        });
    }

    private void insertUser(Long id, String username) {
        Users user = new Users();
        user.setId(id);
        user.setUsername(username);
        user.setPasswordHash("test");
        user.setNickname(username);
        user.setStatus(1);
        user.setFollowersCount(0);
        user.setFollowingCount(0);
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        usersMapper.insert(user);
    }
}
