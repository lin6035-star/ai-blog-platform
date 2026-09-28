package com.hailin.blogsystem.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hailin.blogsystem.component.DelayedCacheEvictor;
import com.hailin.blogsystem.component.AfterCommitExecutor;
import com.hailin.blogsystem.component.UserSetCache;
import com.hailin.blogsystem.constants.RedisConstants;
import com.hailin.blogsystem.entity.ArticleLikes;
import com.hailin.blogsystem.entity.Articles;
import com.hailin.blogsystem.entity.dto.ArticleInteractionAction;
import com.hailin.blogsystem.entity.dto.ArticleInteractionResult;
import com.hailin.blogsystem.entity.dto.ArticleInteractionResultCode;
import com.hailin.blogsystem.mapper.ArticleLikesMapper;
import com.hailin.blogsystem.mapper.ArticlesMapper;
import com.hailin.blogsystem.service.ArticleLikesService;
import com.hailin.blogsystem.utils.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@Slf4j
@RequiredArgsConstructor
public class ArticleLikesServiceImpl extends ServiceImpl<ArticleLikesMapper, ArticleLikes> implements ArticleLikesService {

    private final ArticlesMapper articlesMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final UserSetCache userSetCache;
    private final DelayedCacheEvictor delayedCacheEvictor;
    private final AfterCommitExecutor afterCommitExecutor;

    @Override  //1.点赞文章
    @Transactional
    public void likeArticle(Long articleId) {
        Long userId = UserContext.get();
        if (userId == null) {
            throw new IllegalArgumentException("请先登录");
        }

        ArticleInteractionResult result = likeArticle(userId, articleId);
        throwForLegacyLikeFailure(result);
    }

    @Override
    @Transactional
    public ArticleInteractionResult likeArticle(Long userId, Long articleId) {
        if (articlesMapper.selectById(articleId) == null) {
            return ArticleInteractionResult.failure(
                    ArticleInteractionAction.LIKE_ARTICLE,
                    ArticleInteractionResultCode.TARGET_NOT_FOUND,
                    "文章不存在",
                    null,
                    null);
        }

        boolean exists = lambdaQuery()
                .eq(ArticleLikes::getArticleId, articleId)
                .eq(ArticleLikes::getUserId, userId)
                .exists();

        if (exists) {
            return ArticleInteractionResult.success(
                    ArticleInteractionAction.LIKE_ARTICLE,
                    ArticleInteractionResultCode.ALREADY_SATISFIED,
                    "您已点过赞",
                    true,
                    true);
        }

        ArticleLikes articleLikes = new ArticleLikes();
        articleLikes.setArticleId(articleId);
        articleLikes.setUserId(userId);
        articleLikes.setCreateTime(LocalDateTime.now());
        try {
            save(articleLikes);
        } catch (DuplicateKeyException e) {
            return ArticleInteractionResult.success(
                    ArticleInteractionAction.LIKE_ARTICLE,
                    ArticleInteractionResultCode.ALREADY_SATISFIED,
                    "您已点过赞",
                    true,
                    true);
        }

        int updated = articlesMapper.update(null,
                new LambdaUpdateWrapper<Articles>()
                        .eq(Articles::getId, articleId)
                        .setSql("like_count = like_count + 1"));
        if (updated != 1) {
            throw new IllegalStateException("点赞计数更新失败");
        }
        //点赞成功后，获得对应的score，用户zset排名
        afterCommitExecutor.execute(() -> stringRedisTemplate.opsForZSet()
                .incrementScore(RedisConstants.ARTICLE_HOT_KEY, String.valueOf(articleId),
                        RedisConstants.ARTICLE_LIKE_HOT_SCORE));

        //维护用户点赞集合：仅在集合已加载时同步，未加载时什么都不做——
        //保持"未加载"让下次读从 DB 全量重建，否则会留下一个只有这一条记录的集合却自称全量
        userSetCache.addIfLoaded(
                RedisConstants.ARTICLE_LIKED_USER_KEY_PREFIX + userId, String.valueOf(articleId));

        evictArticleDetailCache(articleId);

        return ArticleInteractionResult.success(
                ArticleInteractionAction.LIKE_ARTICLE,
                ArticleInteractionResultCode.APPLIED,
                "点赞成功",
                false,
                true);
    }

    @Override  //2.取消点赞文章
    @Transactional
    public void cancelLikeArticle(Long articleId) {
        Long userId = UserContext.get();
        if (userId == null) {
            throw new IllegalArgumentException("请先登录");
        }

        ArticleInteractionResult result = cancelLikeArticle(userId, articleId);
        if (result.resultCode() == ArticleInteractionResultCode.TARGET_NOT_FOUND) {
            throw new IllegalArgumentException("文章不存在");
        }
        if (result.resultCode() == ArticleInteractionResultCode.ALREADY_SATISFIED) {
            throw new IllegalArgumentException("您未点过赞，出现错误");
        }
    }

    @Override
    @Transactional
    public ArticleInteractionResult cancelLikeArticle(Long userId, Long articleId) {
        if (articlesMapper.selectById(articleId) == null) {
            return ArticleInteractionResult.failure(
                    ArticleInteractionAction.UNLIKE_ARTICLE,
                    ArticleInteractionResultCode.TARGET_NOT_FOUND,
                    "文章不存在",
                    null,
                    null);
        }

        ArticleLikes one = lambdaQuery()
                .eq(ArticleLikes::getArticleId, articleId)
                .eq(ArticleLikes::getUserId, userId)
                .one();

        if (one == null) {
            return ArticleInteractionResult.success(
                    ArticleInteractionAction.UNLIKE_ARTICLE,
                    ArticleInteractionResultCode.ALREADY_SATISFIED,
                    "您未点过赞",
                    false,
                    false);
        }

        if (!removeById(one.getId())) {
            return ArticleInteractionResult.success(
                    ArticleInteractionAction.UNLIKE_ARTICLE,
                    ArticleInteractionResultCode.ALREADY_SATISFIED,
                    "您未点过赞",
                    false,
                    false);
        }

        articlesMapper.update(null,
                new LambdaUpdateWrapper<Articles>()
                        .eq(Articles::getId, articleId)
                        .setSql("like_count = GREATEST(like_count - 1, 0)"));

        //取消点赞后，减去对应的score，用户zset排名
        afterCommitExecutor.execute(() -> stringRedisTemplate.opsForZSet().incrementScore(
                RedisConstants.ARTICLE_HOT_KEY,
                String.valueOf(articleId),
                RedisConstants.ARTICLE_UNLIKE_HOT_SCORE));

        //维护用户点赞集合：取消最后一项时状态会转为 EMPTY，不留「全量标记 + 空集合」
        userSetCache.removeIfLoaded(
                RedisConstants.ARTICLE_LIKED_USER_KEY_PREFIX + userId, String.valueOf(articleId));

        evictArticleDetailCache(articleId);

        return ArticleInteractionResult.success(
                ArticleInteractionAction.UNLIKE_ARTICLE,
                ArticleInteractionResultCode.APPLIED,
                "已取消点赞",
                true,
                false);
    }

    private void throwForLegacyLikeFailure(ArticleInteractionResult result) {
        if (result.resultCode() == ArticleInteractionResultCode.TARGET_NOT_FOUND) {
            throw new IllegalArgumentException("文章不存在");
        }
        if (result.resultCode() == ArticleInteractionResultCode.ALREADY_SATISFIED) {
            throw new IllegalArgumentException("您已点过赞");
        }
    }

    /**
     * 点赞数展示在文章详情页，点赞/取消要失效详情缓存。
     * **这两个方法都在 `@Transactional` 里**：提交前删会让"删完到提交之间"的读请求
     * 读到库里的旧点赞数并回填，缓存一直脏到 TTL——所以走 {@link DelayedCacheEvictor}
     * （它内部先经 {@code AfterCommitExecutor} 推到提交后，再延迟重删一次堵住迟到写回）。
     */
    private void evictArticleDetailCache(Long articleId) {
        delayedCacheEvictor.evict(RedisConstants.ARTICLE_DETAIL_KEY_PREFIX + articleId);
    }

}
