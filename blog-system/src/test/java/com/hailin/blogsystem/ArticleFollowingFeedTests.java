package com.hailin.blogsystem;

import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.constants.RedisConstants;
import com.hailin.blogsystem.entity.Articles;
import com.hailin.blogsystem.entity.UserFollows;
import com.hailin.blogsystem.entity.vo.ArticleDetailVO;
import com.hailin.blogsystem.entity.vo.PageVO;
import com.hailin.blogsystem.service.ArticlesService;
import com.hailin.blogsystem.service.FollowService;
import com.hailin.blogsystem.utils.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 首页「关注」流。
 *
 * <p>它和另外两档最大的不同是：**结果是每个人各自的私有视图**。所以除了排序正确性，
 * 这里专门锁两件容易静默出错的事：
 * <ul>
 *   <li>未登录 / 一个都没关注时返回**空**，而不是报错、更不能退化成「全站最新」
 *       ——后者用户完全看不出来，只会以为自己关注的作者突然发了好多文章；</li>
 *   <li>这条流**不进共享缓存**：缓存 key 里没有 userId，缓存了就是 A 用户读到 B 用户的关注流。</li>
 * </ul>
 *
 * <p>断言只针对自己造的这几条，不断言列表长度——H2 是多个测试类共享的库。
 */
@SpringBootTest
class ArticleFollowingFeedTests {

    private static final long ME = 9_700_000_000_001L;
    private static final long AUTHOR_A = 9_700_000_000_002L;
    private static final long AUTHOR_B = 9_700_000_000_003L;
    private static final long STRANGER = 9_700_000_000_004L;

    private static final long PAGE_SIZE = 20L;

    @Autowired
    private ArticlesService articlesService;

    @Autowired
    private FollowService followService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private final List<Long> probeArticleIds = new ArrayList<>();
    private final List<Long> probeFollowIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        probeArticleIds.forEach(articlesService::removeById);
        probeArticleIds.clear();
        probeFollowIds.forEach(followService::removeById);
        probeFollowIds.clear();
        // ThreadLocal 必须清：下一个用例可能以「游客」身份跑，留着就会串味
        UserContext.clear();
    }

    /** 发一篇文章，{@code minutesAgo} 越大越早；返回 id */
    private Long publish(long authorId, int minutesAgo) {
        Articles article = new Articles();
        article.setTitle("following-probe-" + System.nanoTime() + "-" + minutesAgo);
        article.setSummary("probe");
        article.setContent("probe content");
        article.setAuthorId(authorId);
        article.setCategoryId(1L);
        article.setStatus(BlogConstants.ArticlesStatus.PUBLISHED);
        article.setViewCount(0);
        article.setLikeCount(0);
        article.setFavoriteCount(0);
        article.setCommentCount(0);
        article.setShareCount(0);
        article.setPublishedAt(LocalDateTime.now().minusMinutes(minutesAgo));
        article.setCreatedAt(LocalDateTime.now());
        article.setUpdatedAt(LocalDateTime.now());
        articlesService.save(article);
        probeArticleIds.add(article.getId());
        return article.getId();
    }

    /** 直接插关系表，不走 {@code followUser}——那条路会顺带更新 users 的计数，测试里没有这些用户 */
    private void follow(long followingId) {
        UserFollows relation = new UserFollows();
        relation.setFollowerId(ME);
        relation.setFollowingId(followingId);
        relation.setCreatedAt(LocalDateTime.now());
        relation.setUpdatedAt(LocalDateTime.now());
        followService.save(relation);
        probeFollowIds.add(relation.getId());
    }

    private List<Long> feedIds() {
        PageVO<ArticleDetailVO> page =
                articlesService.getArticles(null, PAGE_SIZE, null, null, "following");
        return page.getList().stream().map(ArticleDetailVO::getId).toList();
    }

    @Test
    void returnsEmptyWhenNotLoggedIn() {
        // 不 set UserContext，模拟游客。首页是游客可见的，这个 tab 不能因此报错
        publish(AUTHOR_A, 5);

        PageVO<ArticleDetailVO> page =
                articlesService.getArticles(null, PAGE_SIZE, null, null, "following");

        assertThat(page.getList()).as("游客没有关注流，返回空列表").isEmpty();
        assertThat(page.getNextCursor()).isNull();
    }

    @Test
    void returnsEmptyWhenFollowingNobody() {
        UserContext.set(ME);
        publish(AUTHOR_A, 5);   // 有人发了文章，但我没关注他

        assertThat(feedIds())
                .as("一个都没关注时是空的——而不是把所有文章都倒出来")
                .isEmpty();
    }

    @Test
    void interleavesFollowedAuthorsByPublishTime() {
        UserContext.set(ME);
        // 时间交错：A、B、A、B（minutesAgo 越大越早）
        long a1 = publish(AUTHOR_A, 40);
        long b1 = publish(AUTHOR_B, 30);
        long a2 = publish(AUTHOR_A, 20);
        long b2 = publish(AUTHOR_B, 10);
        long stranger = publish(STRANGER, 5);

        follow(AUTHOR_A);
        follow(AUTHOR_B);

        List<Long> ids = feedIds();

        assertThat(ids)
                .as("两个作者的文章按发布时间穿插，不是「看完一个人再看下一个」")
                .containsSubsequence(b2, a2, b1, a1);
        assertThat(ids).as("没关注的人的文章不该出现").doesNotContain(stranger);
    }

    @Test
    void excludedAfterUnfollow() {
        UserContext.set(ME);
        long fromA = publish(AUTHOR_A, 10);
        long fromB = publish(AUTHOR_B, 5);
        follow(AUTHOR_A);
        follow(AUTHOR_B);

        // 取关 B：走软删除（deleted_at 记取关时间），与 cancelFollow 的语义一致
        followService.lambdaUpdate()
                .eq(UserFollows::getFollowerId, ME)
                .eq(UserFollows::getFollowingId, AUTHOR_B)
                .set(UserFollows::getDeletedAt, LocalDateTime.now())
                .update();

        List<Long> ids = feedIds();

        assertThat(ids).contains(fromA);
        assertThat(ids).as("取关之后他的文章立刻从关注流消失").doesNotContain(fromB);
    }

    @Test
    void followingFeedIsNotCached() {
        UserContext.set(ME);
        publish(AUTHOR_A, 10);
        follow(AUTHOR_A);

        feedIds();

        String cacheKey = RedisConstants.ARTICLE_LIST_KEY_PREFIX
                + "first:size:" + PAGE_SIZE + ":category:all:sort:following";

        assertThat(stringRedisTemplate.hasKey(cacheKey))
                .as("关注流是私有视图，缓存 key 里没有 userId——缓存了就是 A 读到 B 的关注流")
                .isFalse();
    }
}
