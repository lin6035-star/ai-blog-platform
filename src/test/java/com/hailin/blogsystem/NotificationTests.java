package com.hailin.blogsystem;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.constants.RedisConstants;
import com.hailin.blogsystem.entity.ArticleComments;
import com.hailin.blogsystem.entity.ArticleLikes;
import com.hailin.blogsystem.entity.Articles;
import com.hailin.blogsystem.entity.CommentLikes;
import com.hailin.blogsystem.entity.NotificationType;
import com.hailin.blogsystem.entity.Notifications;
import com.hailin.blogsystem.entity.Users;
import com.hailin.blogsystem.entity.dto.ArticleInteractionResult;
import com.hailin.blogsystem.entity.dto.ArticleInteractionResultCode;
import com.hailin.blogsystem.entity.dto.CommentDTO;
import com.hailin.blogsystem.entity.vo.NotificationVO;
import com.hailin.blogsystem.entity.vo.PageVO;
import com.hailin.blogsystem.mapper.ArticleLikesMapper;
import com.hailin.blogsystem.mapper.ArticlesMapper;
import com.hailin.blogsystem.mapper.CommentsMapper;
import com.hailin.blogsystem.mapper.LikeCommentsMapper;
import com.hailin.blogsystem.mapper.NotificationsMapper;
import com.hailin.blogsystem.mapper.UsersMapper;
import com.hailin.blogsystem.service.ArticleLikesService;
import com.hailin.blogsystem.service.CommentsService;
import com.hailin.blogsystem.service.LikeCommentsService;
import com.hailin.blogsystem.service.NotificationService;
import com.hailin.blogsystem.task.ArticleViewCountSyncTask;
import com.hailin.blogsystem.task.NotificationCleanupTask;
import com.hailin.blogsystem.utils.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 站内通知的回归锁（设计稿：docs/notification/通知中心设计.md §15）。
 *
 * <p><b>为什么这些用例非有不可</b>：通知的性质几乎全是"静默失效"型的——
 * 幂等坏了只会让用户多收几条、不给自己发坏了只会让你收到自己的通知、
 * 聚合坏了只会让计数不对、降级坏了只会显示一个不该显示的标题。
 * <b>没有一条会让别的测试变红。</b>
 *
 * <p>每个用例都用**新用户 + 新文章**（nanoTime 命名）：通知是按 receiver 查的，
 * 于是用例之间天然隔离，不需要在方法之间清表。
 */
@SpringBootTest
class NotificationTests {

    private static final String PROBE_IP = "1.1.1.1";

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private ArticleLikesService articleLikesService;

    @Autowired
    private LikeCommentsService likeCommentsService;

    @Autowired
    private CommentsService commentsService;

    @Autowired
    private NotificationsMapper notificationsMapper;

    @Autowired
    private UsersMapper usersMapper;

    @Autowired
    private ArticlesMapper articlesMapper;

    @Autowired
    private CommentsMapper commentsMapper;

    @Autowired
    private ArticleLikesMapper articleLikesMapper;

    @Autowired
    private LikeCommentsMapper likeCommentsMapper;

    @Autowired
    private NotificationCleanupTask notificationCleanupTask;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /** 关掉定时任务：它会改 DB 里的浏览量，与本用例无关但会制造跨用例的状态漂移 */
    @MockBean
    private ArticleViewCountSyncTask articleViewCountSyncTask;

    private Long authorId;
    private Long actorId;
    private Long otherId;
    private Long articleId;

    @BeforeEach
    void setUp() {
        authorId = insertUser("author");
        actorId = insertUser("actor");
        otherId = insertUser("other");

        Articles article = new Articles();
        article.setTitle("notification-probe-" + System.nanoTime());
        article.setSummary("probe summary");
        article.setContent("probe content");
        article.setAuthorId(authorId);
        article.setCategoryId(1L);
        article.setStatus(BlogConstants.ArticlesStatus.PUBLISHED);
        article.setViewCount(0);
        article.setLikeCount(0);
        article.setFavoriteCount(0);
        article.setCommentCount(0);
        article.setShareCount(0);
        article.setPublishedAt(LocalDateTime.now());
        article.setCreatedAt(LocalDateTime.now());
        article.setUpdatedAt(LocalDateTime.now());
        articlesMapper.insert(article);
        articleId = article.getId();
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
        notificationsMapper.delete(new LambdaQueryWrapper<Notifications>()
                .in(Notifications::getReceiverId, List.of(authorId, actorId, otherId)));

        // 评论点赞要先清——它引用评论 id，而评论马上要被删掉
        List<Long> commentIds = commentsMapper.selectList(new LambdaQueryWrapper<ArticleComments>()
                        .eq(ArticleComments::getArticleId, articleId))
                .stream()
                .map(ArticleComments::getId)
                .toList();
        if (!commentIds.isEmpty()) {
            likeCommentsMapper.delete(new LambdaQueryWrapper<CommentLikes>()
                    .in(CommentLikes::getCommentId, commentIds));
        }

        commentsMapper.delete(new LambdaQueryWrapper<ArticleComments>()
                .eq(ArticleComments::getArticleId, articleId));
        articleLikesMapper.delete(new LambdaQueryWrapper<ArticleLikes>()
                .eq(ArticleLikes::getArticleId, articleId));
        articlesMapper.deleteById(articleId);
        usersMapper.deleteById(authorId);
        usersMapper.deleteById(actorId);
        usersMapper.deleteById(otherId);
        // 点赞会往热榜 ZSet 加分，留着会污染其他用例
        stringRedisTemplate.opsForZSet().remove(RedisConstants.ARTICLE_HOT_KEY, String.valueOf(articleId));
    }

    // ---------- 点赞：幂等 + 只认 APPLIED + 不给自己发 ----------

    @Test
    void likeNotifiesArticleAuthor() {
        UserContext.set(actorId);
        articleLikesService.likeArticle(actorId, articleId);

        assertThat(typesFor(authorId)).containsExactly(NotificationType.ARTICLE_LIKED.name());
        assertThat(unreadFor(authorId)).isEqualTo(1L);
    }

    @Test
    void secondLikeIsAlreadySatisfiedAndDoesNotNotifyAgain() {
        UserContext.set(actorId);
        articleLikesService.likeArticle(actorId, articleId);
        ArticleInteractionResult second = articleLikesService.likeArticle(actorId, articleId);

        // 关键：第二次返回的是 success + ALREADY_SATISFIED（不是异常），
        // 如果通知挂在"方法没抛异常"上，作者就会收到第二条
        assertThat(second.resultCode()).isEqualTo(ArticleInteractionResultCode.ALREADY_SATISFIED);
        assertThat(countFor(authorId)).isEqualTo(1L);
    }

    @Test
    void likeCancelLikeStillNotifiesOnlyOnce() {
        UserContext.set(actorId);
        articleLikesService.likeArticle(actorId, articleId);
        articleLikesService.cancelLikeArticle(articleId);
        articleLikesService.likeArticle(actorId, articleId);

        // 唯一键的有意防刷：同一个人对同一篇文章只打扰一次（设计稿 §6.1）
        assertThat(countFor(authorId)).isEqualTo(1L);
    }

    @Test
    void likingOwnArticleDoesNotNotify() {
        UserContext.set(authorId);
        articleLikesService.likeArticle(authorId, articleId);

        assertThat(countFor(authorId)).isZero();
    }

    // ---------- 聚合：一条 = 一组 ----------

    @Test
    void listAggregatesSameTargetIntoSingleRow() {
        UserContext.set(actorId);
        articleLikesService.likeArticle(actorId, articleId);
        UserContext.set(otherId);
        articleLikesService.likeArticle(otherId, articleId);

        UserContext.set(authorId);
        PageVO<NotificationVO> page = notificationService.listMine(1L, 20L);

        // 两个人赞同一篇文章 = **一行**，而不是两行
        assertThat(page.getList()).hasSize(1);
        NotificationVO vo = page.getList().get(0);
        assertThat(vo.getActorCount()).isEqualTo(2);
        assertThat(vo.getGroupUnread()).isEqualTo(2);
        // 分页总数也按**组**算：按行算的话前端会多出空页
        assertThat(page.getTotal()).isEqualTo(1L);
    }

    @Test
    void markReadClearsTheWholeGroupNotJustTheRepresentativeRow() {
        UserContext.set(actorId);
        articleLikesService.likeArticle(actorId, articleId);
        UserContext.set(otherId);
        articleLikesService.likeArticle(otherId, articleId);

        UserContext.set(authorId);
        NotificationVO vo = notificationService.listMine(1L, 20L).getList().get(0);
        // 只传"代表行"的 id——组里另外那一条也要跟着变成已读
        notificationService.markRead(List.of(vo.getId()));

        // 红点要一次减 2。只标最新那条的话这里会剩 1，用户会以为"点了已读没生效"
        assertThat(notificationService.unreadCount()).isZero();
        assertThat(notificationService.listMine(1L, 20L).getList().get(0).getGroupUnread()).isZero();
    }

    @Test
    void differentTargetsStayAsSeparateRows() {
        // 同一篇文章的"点赞"和"评论"是两个不同的组（type 不同），不该被合并
        UserContext.set(actorId);
        articleLikesService.likeArticle(actorId, articleId);
        commentsService.postComment(articleId, comment("评论一下", null), PROBE_IP, null);

        UserContext.set(authorId);
        assertThat(notificationService.listMine(1L, 20L).getList()).hasSize(2);
    }

    // ---------- 评论 / 回复 / 赞评论：接收者与"只打扰一次" ----------

    @Test
    void commentNotifiesArticleAuthor() {
        UserContext.set(actorId);
        commentsService.postComment(articleId, comment("写得不错", null), PROBE_IP, null);

        assertThat(typesFor(authorId)).containsExactly(NotificationType.ARTICLE_COMMENTED.name());
    }

    @Test
    void commentingOwnArticleDoesNotNotify() {
        UserContext.set(authorId);
        commentsService.postComment(articleId, comment("自问自答", null), PROBE_IP, null);

        assertThat(countFor(authorId)).isZero();
    }

    @Test
    void replyNotifiesParentAuthorAndOnlyOnceWhenParentIsTheAuthor() {
        // 作者先发一条顶级评论
        UserContext.set(authorId);
        commentsService.postComment(articleId, comment("我是作者", null), PROBE_IP, null);
        Long authorCommentId = firstCommentId();

        // 别人回复作者
        UserContext.set(actorId);
        commentsService.postComment(articleId, comment("回复作者", authorCommentId), PROBE_IP, null);

        /*
         * **本类最有价值的一条断言**：父评论作者恰好就是文章作者时，
         * 作者只能收到 COMMENT_REPLIED 这一条——不能再补一条 ARTICLE_COMMENTED。
         * 去重靠 notifyComment 里的显式比较，不能指望 notify 的"不给自己发"
         * （那条判的是"接收者 == 触发者"，与"两个接收者相同"不是一回事）。
         */
        assertThat(typesFor(authorId)).containsExactly(NotificationType.COMMENT_REPLIED.name());
    }

    @Test
    void replyNotifiesBothWhenParentAuthorDiffersFromArticleAuthor() {
        // 路人 other 先评论
        UserContext.set(otherId);
        commentsService.postComment(articleId, comment("路人评论", null), PROBE_IP, null);
        Long otherCommentId = firstCommentId();

        // actor 回复 other
        UserContext.set(actorId);
        commentsService.postComment(articleId, comment("回复路人", otherCommentId), PROBE_IP, null);

        // 被回复的人收到"回复"，文章作者收到"有人评论了你的文章"（顶级评论那条 + 回复这条）
        assertThat(typesFor(otherId)).containsExactly(NotificationType.COMMENT_REPLIED.name());
        assertThat(typesFor(authorId)).containsExactly(
                NotificationType.ARTICLE_COMMENTED.name(),
                NotificationType.ARTICLE_COMMENTED.name());
    }

    @Test
    void likingACommentNotifiesTheCommentAuthorRatherThanTheArticleAuthor() {
        // other 发一条评论，actor 去赞它
        UserContext.set(otherId);
        commentsService.postComment(articleId, comment("等着被赞", null), PROBE_IP, null);
        Long commentId = firstCommentId();

        UserContext.set(actorId);
        likeCommentsService.likeComment(commentId);

        // 接收者是**评论作者**（other），不是文章作者
        assertThat(typesFor(otherId)).containsExactly(NotificationType.COMMENT_LIKED.name());
        // 文章作者只有"有人评论了你的文章"那一条，不该多出"有人赞了你的评论"
        assertThat(typesFor(authorId)).containsExactly(NotificationType.ARTICLE_COMMENTED.name());

        // 摘要给的正是被赞的那条评论
        UserContext.set(otherId);
        assertThat(notificationService.listMine(1L, 20L).getList().get(0).getCommentExcerpt())
                .isEqualTo("等着被赞");
    }

    @Test
    void likingOwnCommentDoesNotNotify() {
        UserContext.set(otherId);
        commentsService.postComment(articleId, comment("自己赞自己", null), PROBE_IP, null);
        Long commentId = firstCommentId();

        UserContext.set(otherId);
        likeCommentsService.likeComment(commentId);

        assertThat(typesFor(otherId)).isEmpty();
    }

    // ---------- 未读与已读：越权边界 ----------

    @Test
    void markAllReadOnlyAffectsOwnNotifications() {
        UserContext.set(actorId);
        articleLikesService.likeArticle(actorId, articleId);

        // author 有 1 条未读；换成 other 的身份"全部已读"
        UserContext.set(otherId);
        notificationService.markAllRead();

        assertThat(unreadFor(authorId)).isEqualTo(1L);
    }

    @Test
    void unreadCountAndMarkAllReadFollowReceiver() {
        UserContext.set(actorId);
        articleLikesService.likeArticle(actorId, articleId);

        UserContext.set(authorId);
        assertThat(notificationService.unreadCount()).isEqualTo(1L);
        assertThat(notificationService.listMine(1L, 20L).getList()).hasSize(1);

        notificationService.markAllRead();
        assertThat(notificationService.unreadCount()).isZero();
    }

    @Test
    void markReadIgnoresNotificationsOwnedByOthers() {
        UserContext.set(actorId);
        articleLikesService.likeArticle(actorId, articleId);

        UserContext.set(authorId);
        Long authorsNotificationId = notificationService.listMine(1L, 20L).getList().get(0).getId();

        // other 拿 author 的通知 id 去标已读
        UserContext.set(otherId);
        notificationService.markRead(List.of(authorsNotificationId));

        // 越权边界：作者那条不该被改动。SQL 里的 receiver_id 条件是**唯一**的防线
        // （接口按 id 操作，id 是客户端可控的）
        assertThat(unreadFor(authorId)).isEqualTo(1L);
    }

    @Test
    void markReadAcceptsMultipleIdsFromTheSameGroup() {
        UserContext.set(actorId);
        articleLikesService.likeArticle(actorId, articleId);
        UserContext.set(otherId);
        articleLikesService.likeArticle(otherId, articleId);

        UserContext.set(authorId);
        // 列表只给"代表 id"，但接口是开放的：把组内两条原始行的 id 都传上来也不该出问题
        List<Long> rawRowIds = notificationsMapper.selectList(new LambdaQueryWrapper<Notifications>()
                        .eq(Notifications::getReceiverId, authorId))
                .stream()
                .map(Notifications::getId)
                .toList();
        assertThat(rawRowIds).hasSize(2);

        notificationService.markRead(rawRowIds);

        assertThat(notificationService.unreadCount()).isZero();
    }

    @Test
    void unreadCountIsScopedToCurrentUser() {
        UserContext.set(actorId);
        articleLikesService.likeArticle(actorId, articleId);

        // 触发者自己不该有任何通知
        assertThat(notificationService.unreadCount()).isZero();
        assertThat(notificationService.listMine(1L, 20L).getList()).isEmpty();
    }

    // ---------- 保留期清理 ----------

    @Test
    void cleanupRemovesOnlyExpiredNotifications() {
        UserContext.set(actorId);
        articleLikesService.likeArticle(actorId, articleId);

        // 手工造一条 40 天前的（唯一键与上面那条不同，不会撞）
        Notifications expired = new Notifications();
        expired.setReceiverId(authorId);
        expired.setActorId(otherId);
        expired.setType(NotificationType.USER_FOLLOWED.name());
        expired.setTargetId(authorId);
        expired.setIsRead(1);
        expired.setCreatedAt(LocalDateTime.now().minusDays(40));
        notificationsMapper.insert(expired);

        assertThat(countFor(authorId)).isEqualTo(2L);

        notificationCleanupTask.cleanupExpired();

        // 只删过期那条。边界写错（把 < 写成 <=、或干脆按 receiver 全删）会立刻红
        assertThat(countFor(authorId)).isEqualTo(1L);
        assertThat(typesFor(authorId)).containsExactly(NotificationType.ARTICLE_LIKED.name());
    }

    // ---------- 渲染期回查与降级（不存快照的代价） ----------

    @Test
    void listFillsTitleAndExcerptFromLiveRows() {
        UserContext.set(actorId);
        commentsService.postComment(articleId, comment("写得不错", null), PROBE_IP, null);

        UserContext.set(authorId);
        NotificationVO vo = notificationService.listMine(1L, 20L).getList().get(0);

        assertThat(vo.getType()).isEqualTo(NotificationType.ARTICLE_COMMENTED.name());
        assertThat(vo.getActorName()).isEqualTo("actor");
        assertThat(vo.getArticleTitle()).startsWith("notification-probe-");
        assertThat(vo.getCommentExcerpt()).isEqualTo("写得不错");
    }

    @Test
    void listDegradesExcerptWhenCommentIsDeleted() {
        UserContext.set(actorId);
        commentsService.postComment(articleId, comment("马上要被删", null), PROBE_IP, null);

        // 软删评论（实体带 @TableLogic，回查自然查不到）
        commentsMapper.deleteById(firstCommentId());

        UserContext.set(authorId);
        NotificationVO vo = notificationService.listMine(1L, 20L).getList().get(0);

        // 关键：不是"显示幽灵评论"，而是让前端知道该显示"评论已删除"
        assertThat(vo.getCommentExcerpt()).isNull();
        assertThat(vo.getArticleTitle()).isNotNull();
    }

    @Test
    void listHidesTitleFromNonAuthorWhenArticleIsNotPublished() {
        // 造一条"接收者不是文章作者"的通知（正是第 2 批 AUTHOR_PUBLISHED 的形态）
        notificationService.notify(otherId, actorId,
                NotificationType.ARTICLE_LIKED, articleId, articleId);

        Articles article = articlesMapper.selectById(articleId);
        article.setStatus(BlogConstants.ArticlesStatus.HIDDEN);
        articlesMapper.updateById(article);

        UserContext.set(otherId);
        NotificationVO vo = notificationService.listMine(1L, 20L).getList().get(0);

        // 标题本身就是内容：接收者不是作者时，非已发布文章不能把标题漏给粉丝
        assertThat(vo.getArticleTitle()).isNull();
    }

    @Test
    void listKeepsTitleForAuthorEvenWhenArticleIsHidden() {
        UserContext.set(actorId);
        articleLikesService.likeArticle(actorId, articleId);

        Articles article = articlesMapper.selectById(articleId);
        article.setStatus(BlogConstants.ArticlesStatus.HIDDEN);
        articlesMapper.updateById(article);

        // 作者看自己的隐藏文章没问题——这条边界是按"接收者是不是作者"分的，不是按状态一刀切
        UserContext.set(authorId);
        NotificationVO vo = notificationService.listMine(1L, 20L).getList().get(0);
        assertThat(vo.getArticleTitle()).startsWith("notification-probe-");
    }

    // ---------- 工具 ----------

    private Long insertUser(String tag) {
        Users user = new Users();
        user.setUsername(tag + "-" + System.nanoTime());
        user.setPasswordHash("x");
        user.setNickname(tag);
        usersMapper.insert(user);
        return user.getId();
    }

    private static CommentDTO comment(String content, Long parentId) {
        CommentDTO dto = new CommentDTO();
        dto.setContent(content);
        dto.setParentId(parentId);
        return dto;
    }

    /** 本用例的文章下第一条评论（每个用例只发 1~2 条，取最早的即可） */
    private Long firstCommentId() {
        List<ArticleComments> list = commentsMapper.selectList(
                new LambdaQueryWrapper<ArticleComments>()
                        .eq(ArticleComments::getArticleId, articleId)
                        .orderByAsc(ArticleComments::getId));
        return list.get(0).getId();
    }

    private List<String> typesFor(Long receiverId) {
        return notificationsMapper.selectList(new LambdaQueryWrapper<Notifications>()
                        .eq(Notifications::getReceiverId, receiverId)
                        .orderByAsc(Notifications::getId))
                .stream()
                .map(Notifications::getType)
                .toList();
    }

    private long countFor(Long receiverId) {
        Long count = notificationsMapper.selectCount(new LambdaQueryWrapper<Notifications>()
                .eq(Notifications::getReceiverId, receiverId));
        return count == null ? 0L : count;
    }

    private Long unreadFor(Long receiverId) {
        UserContext.set(receiverId);
        return notificationService.unreadCount();
    }
}
