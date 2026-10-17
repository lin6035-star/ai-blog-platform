package com.hailin.blogsystem.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hailin.blogsystem.component.UserSetCache;
import com.hailin.blogsystem.constants.RedisConstants;
import com.hailin.blogsystem.entity.ArticleComments;
import com.hailin.blogsystem.entity.CommentLikes;
import com.hailin.blogsystem.entity.NotificationType;
import com.hailin.blogsystem.mapper.CommentsMapper;
import com.hailin.blogsystem.mapper.LikeCommentsMapper;
import com.hailin.blogsystem.service.LikeCommentsService;
import com.hailin.blogsystem.service.NotificationService;
import com.hailin.blogsystem.utils.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class LikeCommentsServiceImpl extends ServiceImpl<LikeCommentsMapper, CommentLikes> implements LikeCommentsService {

    private final CommentsMapper commentsMapper;
    private final UserSetCache userSetCache;
    private final NotificationService notificationService;

    @Override  //1.点赞评论，登录用户可访问
    @Transactional
    public void likeComment(Long commentId) {

        CommentLikes one = lambdaQuery().eq(CommentLikes::getCommentId, commentId)
                .eq(CommentLikes::getUserId, UserContext.get()).one();
        if(one != null){
            throw new IllegalArgumentException("不能重复点赞");
        }

        ArticleComments comment = commentsMapper.selectById(commentId);

        //改为原子自增：原实现「读实体 → like_count+1 → updateById 整行回写」并发会丢更新，
        //且整行回写会覆盖同一行其他字段。与文章点赞（ArticleLikesServiceImpl）同一写法。
        int updated = commentsMapper.update(null,
                new LambdaUpdateWrapper<ArticleComments>()
                        .eq(ArticleComments::getId, commentId)
                        .setSql("like_count = like_count + 1"));
        if (updated != 1) {
            throw new IllegalStateException("评论点赞计数更新失败");
        }

        CommentLikes commentLikes = new CommentLikes();
        commentLikes.setCommentId(commentId);
        commentLikes.setUserId(UserContext.get());
        commentLikes.setCreatedAt(LocalDateTime.now());

        save(commentLikes);

        //维护用户评论点赞集合：仅在集合已加载时同步，未加载时什么都不做——
        //保持"未加载"让下次读从 DB 全量重建，否则会留下一个只有这一条记录的集合却自称全量
        Long userId = UserContext.get();
        userSetCache.addIfLoaded(
                RedisConstants.COMMENT_LIKED_USER_KEY_PREFIX + userId, String.valueOf(commentId));

        /*
         * 通知**评论作者**——注意与"文章被评论"是两条不同的通知：
         * 文章作者收"有人评论了你的文章"，评论作者收"有人赞了你的评论"。
         *
         * 与业务同事务；能走到这里一定是真的点上了（重复点赞在上面就抛异常了）。
         * 赞自己的评论由 notify 的"不给自己发"兜住。
         * 接收者与文章 id 都来自上面那次 selectById —— **零额外查询**。
         */
        notificationService.notify(comment.getUserId(), userId,
                NotificationType.COMMENT_LIKED, commentId, comment.getArticleId());
    }


    @Override  //2.取消点赞评论，登录用户可访问
    @Transactional
    public void cancelLike(Long commentId) {

        boolean remove = removeById(lambdaQuery().eq(CommentLikes::getCommentId, commentId)
                .eq(CommentLikes::getUserId, UserContext.get()).one());

        if(remove){
            //原子自减 + 0 下限：原实现读实体后 -1 再整行回写，并发丢更新，
            //计数已为 0 的脏数据还会被减成负数
            commentsMapper.update(null,
                    new LambdaUpdateWrapper<ArticleComments>()
                            .eq(ArticleComments::getId, commentId)
                            .setSql("like_count = GREATEST(like_count - 1, 0)"));
        }

        //维护用户评论点赞集合：取消最后一项时状态会转为 EMPTY，不留「全量标记 + 空集合」
        Long userId = UserContext.get();
        userSetCache.removeIfLoaded(
                RedisConstants.COMMENT_LIKED_USER_KEY_PREFIX + userId, String.valueOf(commentId));
    }
}
