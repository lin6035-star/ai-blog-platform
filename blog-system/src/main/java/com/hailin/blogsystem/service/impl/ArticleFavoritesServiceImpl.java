package com.hailin.blogsystem.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hailin.blogsystem.component.DelayedCacheEvictor;
import com.hailin.blogsystem.component.AfterCommitExecutor;
import com.hailin.blogsystem.component.UserSetCache;
import com.hailin.blogsystem.constants.RedisConstants;
import com.hailin.blogsystem.entity.ArticleFavorites;
import com.hailin.blogsystem.entity.Articles;
import com.hailin.blogsystem.entity.NotificationType;
import com.hailin.blogsystem.entity.dto.ArticleInteractionAction;
import com.hailin.blogsystem.entity.dto.ArticleInteractionResult;
import com.hailin.blogsystem.entity.dto.ArticleInteractionResultCode;
import com.hailin.blogsystem.mapper.ArticleFavoritesMapper;
import com.hailin.blogsystem.mapper.ArticlesMapper;
import com.hailin.blogsystem.service.ArticleFavoritesService;
import com.hailin.blogsystem.service.NotificationService;
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
public class ArticleFavoritesServiceImpl extends ServiceImpl<ArticleFavoritesMapper, ArticleFavorites> implements ArticleFavoritesService {

    private final ArticlesMapper articlesMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final UserSetCache userSetCache;
    private final DelayedCacheEvictor delayedCacheEvictor;
    private final AfterCommitExecutor afterCommitExecutor;
    private final NotificationService notificationService;

    @Override  //1.收藏文章
    @Transactional
    public void favoriteArticle(Long articleId) {
        Long userId = UserContext.get();
        if (userId == null) {
            throw new IllegalArgumentException("请先登录");
        }

        ArticleInteractionResult result = favoriteArticle(userId, articleId);
        if (result.resultCode() == ArticleInteractionResultCode.TARGET_NOT_FOUND) {
            throw new IllegalArgumentException("文章不存在");
        }
        if (result.resultCode() == ArticleInteractionResultCode.ALREADY_SATISFIED) {
            throw new IllegalArgumentException("已经收藏过该博文,不能重复收藏");
        }
    }

    @Override
    @Transactional
    public ArticleInteractionResult favoriteArticle(Long userId, Long articleId) {
        // 同点赞：接住实体而不是只判 null——通知要拿文章作者当接收者
        Articles article = articlesMapper.selectById(articleId);
        if (article == null) {
            return ArticleInteractionResult.failure(
                    ArticleInteractionAction.FAVORITE_ARTICLE,
                    ArticleInteractionResultCode.TARGET_NOT_FOUND,
                    "文章不存在",
                    null,
                    null);
        }

        boolean exists = lambdaQuery()
                .eq(ArticleFavorites::getArticleId, articleId)
                .eq(ArticleFavorites::getUserId, userId).exists();

        if(exists){
            return ArticleInteractionResult.success(
                    ArticleInteractionAction.FAVORITE_ARTICLE,
                    ArticleInteractionResultCode.ALREADY_SATISFIED,
                    "已经收藏过该博文",
                    true,
                    true);
        }
        ArticleFavorites articleFavorites = new ArticleFavorites();
        articleFavorites.setArticleId(articleId);
        articleFavorites.setUserId(userId);
        articleFavorites.setCreateTime(LocalDateTime.now());
        try {
            save(articleFavorites);
        } catch (DuplicateKeyException e) {
            return ArticleInteractionResult.success(
                    ArticleInteractionAction.FAVORITE_ARTICLE,
                    ArticleInteractionResultCode.ALREADY_SATISFIED,
                    "已经收藏过该博文",
                    true,
                    true);
        }

        int updated = articlesMapper.update(null,
                new LambdaUpdateWrapper<Articles>()
                        .eq(Articles::getId,articleId)
                        .setSql("favorite_count = favorite_count + 1"));
        if (updated != 1) {
            throw new IllegalStateException("收藏计数更新失败");
        }
        //点击收藏增加相应的score分数
        afterCommitExecutor.execute(() -> stringRedisTemplate.opsForZSet().incrementScore(
                RedisConstants.ARTICLE_HOT_KEY,
                String.valueOf(articleId), RedisConstants.ARTICLE_FAVORITE_HOT_SCORE));

        //维护用户收藏集合：仅在集合已加载时同步，未加载时什么都不做——
        //保持"未加载"让下次读从 DB 全量重建，否则会留下一个只有这一条记录的集合却自称全量
        userSetCache.addIfLoaded(
                RedisConstants.ARTICLE_FAVORITED_USER_KEY_PREFIX + userId, String.valueOf(articleId));

        evictArticleDetailCache(articleId);

        /*
         * 通知文章作者。与业务同事务；走到这里一定是 APPLIED
         * （重复收藏的两个分支都在上面 return 了）。
         */
        notificationService.notify(article.getAuthorId(), userId,
                NotificationType.ARTICLE_FAVORITED, articleId, articleId);

        return ArticleInteractionResult.success(
                ArticleInteractionAction.FAVORITE_ARTICLE,
                ArticleInteractionResultCode.APPLIED,
                "收藏成功",
                false,
                true);
    }


    @Override  //2.取消收藏文章
    @Transactional
    public void cancelFavorite(Long articleId) {
        Long userId = UserContext.get();
        if (userId == null) {
            throw new IllegalArgumentException("请先登录");
        }

        ArticleInteractionResult result = cancelFavorite(userId, articleId);
        if (result.resultCode() == ArticleInteractionResultCode.TARGET_NOT_FOUND) {
            throw new IllegalArgumentException("文章不存在");
        }
        if (result.resultCode() == ArticleInteractionResultCode.ALREADY_SATISFIED) {
            throw new IllegalArgumentException("您未收藏过，出现错误");
        }
    }

    @Override
    @Transactional
    public ArticleInteractionResult cancelFavorite(Long userId, Long articleId) {
        if (articlesMapper.selectById(articleId) == null) {
            return ArticleInteractionResult.failure(
                    ArticleInteractionAction.UNFAVORITE_ARTICLE,
                    ArticleInteractionResultCode.TARGET_NOT_FOUND,
                    "文章不存在",
                    null,
                    null);
        }

        ArticleFavorites one = lambdaQuery()
                .eq(ArticleFavorites::getArticleId, articleId)
                .eq(ArticleFavorites::getUserId, userId).one();

        if(one == null){
            return ArticleInteractionResult.success(
                    ArticleInteractionAction.UNFAVORITE_ARTICLE,
                    ArticleInteractionResultCode.ALREADY_SATISFIED,
                    "您未收藏过",
                    false,
                    false);
        }

        if (!removeById(one)) {
            return ArticleInteractionResult.success(
                    ArticleInteractionAction.UNFAVORITE_ARTICLE,
                    ArticleInteractionResultCode.ALREADY_SATISFIED,
                    "您未收藏过",
                    false,
                    false);
        }

        articlesMapper.update(null,
                new LambdaUpdateWrapper<Articles>().eq(Articles::getId,articleId)
                        .setSql("favorite_count = GREATEST(favorite_count - 1, 0)"));

        //点击取消收藏增加相应的score分数
        afterCommitExecutor.execute(() -> stringRedisTemplate.opsForZSet().incrementScore(
                RedisConstants.ARTICLE_HOT_KEY,
                String.valueOf(articleId), RedisConstants.ARTICLE_UNFAVORITE_HOT_SCORE));

        //维护用户收藏集合：取消最后一项时状态会转为 EMPTY，不留「全量标记 + 空集合」
        userSetCache.removeIfLoaded(
                RedisConstants.ARTICLE_FAVORITED_USER_KEY_PREFIX + userId, String.valueOf(articleId));

        evictArticleDetailCache(articleId);
        return ArticleInteractionResult.success(
                ArticleInteractionAction.UNFAVORITE_ARTICLE,
                ArticleInteractionResultCode.APPLIED,
                "已取消收藏",
                true,
                false);
    }

    /**
     * 收藏数会展示在文章详情页，所以收藏/取消要失效详情缓存。
     * 走 {@link DelayedCacheEvictor}：它内部先经 {@code AfterCommitExecutor} 推到提交后
     * （本类当前无事务，等价于立即执行，但去掉 `@Transactional` 或将来补上时行为自动正确），
     * 再延迟重删一次堵住"读请求在提交前查库、在删除之后才写回"的迟到写回。
     */
    private void evictArticleDetailCache(Long articleId) {
        delayedCacheEvictor.evict(RedisConstants.ARTICLE_DETAIL_KEY_PREFIX + articleId);
    }
}
