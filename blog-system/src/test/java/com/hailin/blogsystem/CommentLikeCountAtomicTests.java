package com.hailin.blogsystem;

import com.hailin.blogsystem.entity.ArticleComments;
import com.hailin.blogsystem.entity.CommentLikes;
import com.hailin.blogsystem.mapper.CommentsMapper;
import com.hailin.blogsystem.mapper.LikeCommentsMapper;
import com.hailin.blogsystem.service.LikeCommentsService;
import com.hailin.blogsystem.utils.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 评论点赞计数（bug B）：计数必须用原子 SQL 维护，且不能减成负数。
 *
 * <p>旧实现是「selectById 读实体 → like_count ±1 → updateById 整行回写」：
 * 并发点赞会互相覆盖（丢更新），取消时若计数已是 0 会减成 -1。
 *
 * <p>「计数为 0 但点赞行还在」不是凭空造的病态数据——并发丢更新正好会留下这种状态，
 * 修复前的取消路径在这里必然写成负数，本用例据此锁住 0 下限。
 */
@SpringBootTest
@Transactional
class CommentLikeCountAtomicTests {

    private static final long COMMENT_AUTHOR = 100L;

    @Autowired
    private LikeCommentsService likeCommentsService;

    @Autowired
    private CommentsMapper commentsMapper;

    @Autowired
    private LikeCommentsMapper likeCommentsMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test
    void likeAndCancelKeepDatabaseCountInSync() {
        long commentId = 9_200_001L;
        insertComment(commentId, 0L);

        UserContext.set(101L);
        likeCommentsService.likeComment(commentId);
        assertThat(likeCountOf(commentId)).isEqualTo(1L);

        likeCommentsService.cancelLike(commentId);
        assertThat(likeCountOf(commentId)).isZero();
    }

    @Test
    void likesFromDifferentUsersAccumulate() {
        long commentId = 9_200_002L;
        insertComment(commentId, 0L);

        UserContext.set(101L);
        likeCommentsService.likeComment(commentId);
        UserContext.set(102L);
        likeCommentsService.likeComment(commentId);

        assertThat(likeCountOf(commentId)).isEqualTo(2L);
    }

    @Test
    void cancelLikeNeverDecrementsBelowZero() {
        long commentId = 9_200_003L;
        insertComment(commentId, 0L);
        // 丢更新遗留状态：点赞行在、计数已是 0
        CommentLikes like = new CommentLikes();
        like.setCommentId(commentId);
        like.setUserId(101L);
        like.setCreatedAt(LocalDateTime.now());
        likeCommentsMapper.insert(like);

        UserContext.set(101L);
        likeCommentsService.cancelLike(commentId);

        assertThat(likeCountOf(commentId))
                .as("取消点赞必须有 0 下限（旧实现会把 0 减成 -1）")
                .isZero();
    }

    private void insertComment(Long id, long likeCount) {
        ArticleComments comment = new ArticleComments();
        comment.setId(id);
        comment.setArticleId(1L);
        comment.setUserId(COMMENT_AUTHOR);
        comment.setContent("atomic like count probe " + id);
        comment.setLikeCount(likeCount);
        comment.setCreatedAt(LocalDateTime.now());
        commentsMapper.insert(comment);
    }

    private long likeCountOf(Long commentId) {
        Long stored = jdbcTemplate.queryForObject(
                "select like_count from article_comments where id = ?", Long.class, commentId);
        return stored == null ? 0L : stored;
    }
}
