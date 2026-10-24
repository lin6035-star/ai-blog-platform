package com.hailin.blogsystem.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.entity.ArticleComments;
import com.hailin.blogsystem.entity.Articles;
import com.hailin.blogsystem.entity.NotificationType;
import com.hailin.blogsystem.entity.Notifications;
import com.hailin.blogsystem.entity.Users;
import com.hailin.blogsystem.entity.dto.NotificationGroupRow;
import com.hailin.blogsystem.entity.vo.NotificationVO;
import com.hailin.blogsystem.entity.vo.PageVO;
import com.hailin.blogsystem.mapper.ArticlesMapper;
import com.hailin.blogsystem.mapper.CommentsMapper;
import com.hailin.blogsystem.mapper.NotificationsMapper;
import com.hailin.blogsystem.mapper.UsersMapper;
import com.hailin.blogsystem.service.NotificationService;
import com.hailin.blogsystem.utils.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 站内通知实现（设计稿：docs/notification/通知中心设计.md）。
 *
 * <p>四个关键性质，改这个类时别弄丢：
 * <ol>
 *   <li><b>不丢</b>：{@link #notify} 在调用方事务内执行，业务成功 ⟺ 通知在</li>
 *   <li><b>不重</b>：唯一键 + 捕获 {@link DuplicateKeyException} 静默忽略</li>
 *   <li><b>不越权</b>：每条查询/更新的 SQL 都带 {@code receiver_id = 当前用户}（不靠前端传）</li>
 *   <li><b>按组</b>：列表与已读都是**按业务对象聚合**的，一条 = 一组，不是一行一条</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    /** 客户端可控的 LIMIT 必须有天花板（与首页列表同一个口径） */
    private static final long DEFAULT_PAGE_SIZE = 20L;
    private static final long MAX_PAGE_SIZE = 50L;

    private static final int UNREAD = 0;
    private static final int READ = 1;

    /** 列表只需要够看的摘要——通知不是阅读器 */
    private static final int EXCERPT_LENGTH = 50;

    /**
     * {@code target_id} 指向**评论**的类型。其余类型指向文章
     * （点赞 / 收藏 / 评论文章 → articleId）或被关注者（关注 → followingId），见设计稿 §4.3。
     */
    private static final Set<String> COMMENT_TARGET_TYPES = Set.of(
            NotificationType.ARTICLE_COMMENTED.name(),
            NotificationType.COMMENT_REPLIED.name(),
            NotificationType.COMMENT_LIKED.name());

    private final NotificationsMapper notificationsMapper;
    private final UsersMapper usersMapper;
    private final ArticlesMapper articlesMapper;
    private final CommentsMapper commentsMapper;

    // ---------- 写 ----------

    /**
     * {@inheritDoc}
     *
     * <p><b>刻意没有 {@code @Transactional}</b>：它必须加入**调用方**的事务，
     * 而不是自己开一个（自己开就变成了"业务回滚、通知还在"）。
     */
    @Override
    public void notify(Long receiverId, Long actorId, NotificationType type, Long targetId, Long articleId) {
        if (receiverId == null || actorId == null || type == null || targetId == null) {
            return;
        }
        // 不给自己发通知：自己评论/回复自己的文章、自己赞自己的文章或评论，都不该收到提醒
        if (receiverId.equals(actorId)) {
            return;
        }

        Notifications notification = new Notifications();
        notification.setReceiverId(receiverId);
        notification.setActorId(actorId);
        notification.setType(type.name());
        notification.setTargetId(targetId);
        notification.setArticleId(articleId);
        notification.setIsRead(UNREAD);
        notification.setCreatedAt(LocalDateTime.now());

        try {
            notificationsMapper.insert(notification);
        } catch (DuplicateKeyException e) {
            /*
             * 唯一键幂等：同一人对同一对象的同类事件已经通知过。
             * **这是正常分支，不是错误**——连点两次赞、取消点赞后再赞都会走到这里。
             *
             * 捕获后事务仍可继续提交（MySQL 只回滚这一条语句），所以不影响同事务里的业务写。
             */
            log.debug("[NOTIFY] 重复通知已忽略 receiver={} actor={} type={} target={}",
                    receiverId, actorId, type, targetId);
        }
    }

    // ---------- 读 ----------

    @Override
    public PageVO<NotificationVO> listMine(Long page, Long pageSize) {
        Long me = requireLogin();
        long safePage = page == null || page < 1 ? 1L : page;
        long safeSize = pageSize == null || pageSize < 1
                ? DEFAULT_PAGE_SIZE
                : Math.min(pageSize, MAX_PAGE_SIZE);

        /*
         * **按业务对象聚合**：同一篇文章被 10 个人点赞 = 一行「10 人赞了你的文章」，
         * 而不是刷满 10 行。
         *
         * 聚合放在 SQL 里（窗口函数）而不是这里，是因为**分页必须按组算**：
         * 先按行分页再在内存里合并，会把同一组切到两页去——第 1 页显示"3 人赞了"、
         * 第 2 页又冒出"7 人赞了"，两个数字都不对。
         */
        long offset = (safePage - 1) * safeSize;
        List<NotificationGroupRow> rows = notificationsMapper.selectGroupPage(me, safeSize, offset);
        long total = notificationsMapper.countGroups(me);

        return new PageVO<>(assembleGroups(rows, me), total, safePage, safeSize);
    }

    @Override
    public long unreadCount() {
        Long me = requireLogin();
        Long count = notificationsMapper.selectCount(new LambdaQueryWrapper<Notifications>()
                .eq(Notifications::getReceiverId, me)
                .eq(Notifications::getIsRead, UNREAD));
        return count == null ? 0L : count;
    }

    // ---------- 标记已读 ----------

    /**
     * 标记「这些通知**所在的组**」为已读。
     *
     * <p>列表是按业务对象聚合的，一个列表项代表一组。所以传进来的 id 是**代表行**的 id，
     * 真正要标记的是它所在整组——只标最新那条的话，组里另外 9 条还是未读，
     * 红点只减 1，用户会以为"点了已读没生效"。
     */
    @Override
    public void markRead(List<Long> ids) {
        Long me = requireLogin();
        // 空集合必须短路：放进 SQL 会生成 `IN ()`，那是语法错误（关注流那边踩过同一个坑）
        if (ids == null || ids.isEmpty()) {
            return;
        }

        List<Notifications> rows = notificationsMapper.selectList(new LambdaQueryWrapper<Notifications>()
                // 越权防护：别人的 id 传进来查不出任何行
                .eq(Notifications::getReceiverId, me)
                .in(Notifications::getId, ids));

        Set<String> handledGroups = new HashSet<>();
        for (Notifications row : rows) {
            // 前端可能把同一组的多条 id 都传上来，按组去重后每组只更新一次
            if (!handledGroups.add(row.getType() + "|" + row.getTargetId())) {
                continue;
            }
            notificationsMapper.update(null, new LambdaUpdateWrapper<Notifications>()
                    .eq(Notifications::getReceiverId, me)
                    .eq(Notifications::getType, row.getType())
                    .eq(Notifications::getTargetId, row.getTargetId())
                    .eq(Notifications::getIsRead, UNREAD)
                    .set(Notifications::getIsRead, READ));
        }
    }

    @Override
    public void markAllRead() {
        Long me = requireLogin();
        notificationsMapper.update(null, new LambdaUpdateWrapper<Notifications>()
                .eq(Notifications::getReceiverId, me)
                .eq(Notifications::getIsRead, UNREAD)
                .set(Notifications::getIsRead, READ));
    }

    // ---------- 组装（批量回查，不是 N+1） ----------

    /**
     * 一页**聚合项** → VO。回查是**按类型收 id 后 3 次 IN 查询**，不是每项查一次。
     *
     * <p>三个实体（{@code Users} / {@code Articles} / {@code ArticleComments}）都带
     * {@code @TableLogic}，所以**已删的记录查不出来**——"查不到 = 已删"，
     * 降级判断直接建立在这上面，不需要额外判 {@code deleted_at}。
     */
    private List<NotificationVO> assembleGroups(List<NotificationGroupRow> rows, Long receiverId) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }

        Set<Long> actorIds = new HashSet<>();
        Set<Long> articleIds = new HashSet<>();
        Set<Long> commentIds = new HashSet<>();
        for (NotificationGroupRow row : rows) {
            if (row.getActorId() != null) {
                actorIds.add(row.getActorId());
            }
            if (row.getArticleId() != null) {
                articleIds.add(row.getArticleId());
            }
            if (COMMENT_TARGET_TYPES.contains(row.getType()) && row.getTargetId() != null) {
                commentIds.add(row.getTargetId());
            }
        }

        Map<Long, Users> actors = indexById(actorIds, usersMapper::selectBatchIds, Users::getId);
        Map<Long, Articles> articles = indexById(articleIds, articlesMapper::selectBatchIds, Articles::getId);
        Map<Long, ArticleComments> comments =
                indexById(commentIds, commentsMapper::selectBatchIds, ArticleComments::getId);

        List<NotificationVO> list = new ArrayList<>(rows.size());
        for (NotificationGroupRow row : rows) {
            list.add(toVO(row, receiverId, actors, articles, comments));
        }
        return list;
    }

    private NotificationVO toVO(NotificationGroupRow row, Long receiverId, Map<Long, Users> actors,
                                Map<Long, Articles> articles, Map<Long, ArticleComments> comments) {
        NotificationVO vo = new NotificationVO();
        vo.setId(row.getId());
        vo.setType(row.getType());
        vo.setActorId(row.getActorId());
        vo.setArticleId(row.getArticleId());
        vo.setCreatedAt(row.getCreatedAt());
        vo.setActorCount(row.getActorCount() == null ? 1 : row.getActorCount());
        vo.setGroupUnread(row.getGroupUnread() == null ? 0 : row.getGroupUnread());

        Users actor = actors.get(row.getActorId());
        if (actor != null) {
            vo.setActorName(actor.getNickname());
            vo.setActorAvatarUrl(actor.getAvatarUrl());
        } else {
            // 触发者已注销/被删：给一个中性名字，别让前端显示空白或 "null"
            vo.setActorName("某位用户");
        }

        Articles article = row.getArticleId() == null ? null : articles.get(row.getArticleId());
        if (article != null && canExposeTitle(article, receiverId)) {
            vo.setArticleTitle(article.getTitle());
        }
        // article 为 null（已删）或标题不允许暴露时，articleTitle 保持 null —— 前端据此降级

        if (COMMENT_TARGET_TYPES.contains(row.getType())) {
            ArticleComments comment = comments.get(row.getTargetId());
            if (comment != null) {
                vo.setCommentExcerpt(excerpt(comment.getContent()));
            }
            // comment 为 null（已删）→ 保持 null，前端显示"评论已删除"
        }
        return vo;
    }

    /**
     * 文章标题能不能给这个接收者看。
     *
     * <p>点赞 / 收藏 / 评论 / 赞评论的接收者都是**参与者本人**——他当然能看自己的隐藏文章。
     * 但 {@code AUTHOR_PUBLISHED}（第 2 批）的接收者是**粉丝**：作者事后把文章改成隐藏或草稿时，
     * **标题本身就是内容**，不能从通知里漏出去。所以按"非作者必须仍是已发布"收口。
     */
    private static boolean canExposeTitle(Articles article, Long receiverId) {
        if (receiverId != null && receiverId.equals(article.getAuthorId())) {
            return true;
        }
        return article.getStatus() != null
                && article.getStatus() == BlogConstants.ArticlesStatus.PUBLISHED;
    }

    /** 压平空白后截断：评论正文可能带换行，列表里只当一行显示 */
    private static String excerpt(String content) {
        if (content == null) {
            return null;
        }
        String flat = content.replaceAll("\\s+", " ").trim();
        return flat.length() <= EXCERPT_LENGTH ? flat : flat.substring(0, EXCERPT_LENGTH) + "…";
    }

    /**
     * 批量回查并按 id 建索引。
     *
     * <p>空集合直接返回空 map —— **不要拿空集合去查**：`IN ()` 是语法错误，
     * 而一页通知里"没有评论类事件"是完全正常的情况。
     */
    private static <T> Map<Long, T> indexById(Collection<Long> ids,
                                              Function<Collection<Long>, List<T>> loader,
                                              Function<T, Long> idGetter) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, T> map = new HashMap<>();
        for (T item : loader.apply(ids)) {
            map.put(idGetter.apply(item), item);
        }
        return map;
    }

    /** 防御性判空：{@code /api/users/me/**} 已由 JwtInterceptor 保护，正常到不了这里 */
    private static Long requireLogin() {
        Long userId = UserContext.get();
        if (userId == null) {
            throw new IllegalArgumentException("请先登录");
        }
        return userId;
    }
}
