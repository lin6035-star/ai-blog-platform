package com.hailin.blogsystem.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.constants.RedisConstants;
import com.hailin.blogsystem.entity.ArticleComments;
import com.hailin.blogsystem.entity.Articles;
import com.hailin.blogsystem.entity.CommentLikes;
import com.hailin.blogsystem.entity.NotificationType;
import com.hailin.blogsystem.entity.Users;
import com.hailin.blogsystem.entity.dto.CommentDTO;
import com.hailin.blogsystem.entity.vo.CommentsVO;
import com.hailin.blogsystem.entity.vo.PageVO;
import com.hailin.blogsystem.mapper.ArticlesMapper;
import com.hailin.blogsystem.mapper.CommentsMapper;
import com.hailin.blogsystem.mapper.LikeCommentsMapper;
import com.hailin.blogsystem.mapper.UsersMapper;
import com.hailin.blogsystem.service.CommentsService;
import com.hailin.blogsystem.service.IpLocationService;
import com.hailin.blogsystem.service.NotificationService;
import com.hailin.blogsystem.utils.UserContext;
import lombok.RequiredArgsConstructor;
import com.hailin.blogsystem.component.AfterCommitExecutor;
import com.hailin.blogsystem.component.DelayedCacheEvictor;
import com.hailin.blogsystem.component.CacheTtlSupport;
import com.hailin.blogsystem.component.UserActivityTracker;
import com.hailin.blogsystem.component.UserSetCache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class CommentsServiceImpl extends ServiceImpl<CommentsMapper, ArticleComments> implements CommentsService {

    private final UsersMapper usersMapper;
    private final LikeCommentsMapper likeCommentsMapper;
    private final ArticlesMapper articlesMapper;
    private final IpLocationService ipLocationService;
    private final StringRedisTemplate stringRedisTemplate;
    private final CacheTtlSupport cacheTtlSupport;
    private final DelayedCacheEvictor delayedCacheEvictor;
    private final ObjectMapper objectMapper;
    private final UserSetCache userSetCache;
    private final UserActivityTracker userActivityTracker;
    private final NotificationService notificationService;
    private final AfterCommitExecutor afterCommitExecutor;

    @Override  //1.获取文章评论列表，游客可访问，每条主评论带前几条回复
    public PageVO<CommentsVO> getComments(Long articleId, Long page, Long pageSize, String sort) {

        String cacheKey = buildCommentListCacheKey(articleId,page,pageSize,sort);
        PageVO<CommentsVO> cachedPage = getCommentListFromCache(cacheKey);

        if(cachedPage != null){
            fillLiked(cachedPage.getList());
            for(CommentsVO commentsVO : cachedPage.getList()){
                fillLiked(commentsVO.getReplies());
            }

            return cachedPage;
        }

        Page<ArticleComments> pageResult = lambdaQuery()
                .eq(ArticleComments::getArticleId, articleId)
                .orderByDesc("hot".equals(sort),ArticleComments::getLikeCount)
                .orderByDesc(ArticleComments::getCreatedAt)
                .isNull(ArticleComments::getParentId)
                .page(new Page<>(page, pageSize));

        List<CommentsVO> list = pageResult.getRecords()
                .stream()
                .map(CommentsVO::from)
                .toList();  //这一步只是把评论表字段转换成VO，但是还有一些字段是别的表的，要通过查询别的表才能查询到

        //从VO里收集所有的userId
        fillUserInfo(list);


        //关于replies字段
        List<Long> rootIds = list.stream()
                .map(CommentsVO::getId)
                .filter(Objects::nonNull)
                .toList();

        if (list.isEmpty()) {
            return new PageVO<>(list, pageResult.getTotal(), page, pageSize);
        }

        List<ArticleComments> allReplies = lambdaQuery()
                .in(ArticleComments::getRootId, rootIds)
                .orderByAsc(ArticleComments::getCreatedAt)
                .list();

        Map<Long, List<ArticleComments>> repliesByRootId = allReplies.stream()
                .collect(Collectors.groupingBy(ArticleComments::getRootId));

        for (CommentsVO mainComment : list) {
            List<ArticleComments> replyEntities = repliesByRootId.getOrDefault(
                    mainComment.getId(),
                    List.of()
            );

            List<CommentsVO> replies = replyEntities.stream()
                    .limit(4)
                    .map(CommentsVO::from)
                    .toList();

            mainComment.setReplies(replies);
            mainComment.setReplyCount((long) replyEntities.size());
        }

        PageVO<CommentsVO> pageVO = new PageVO<>(
                list,
                pageResult.getTotal(),
                page,
                pageSize
        );

        //关于liked字段
        fillLiked(list);

        for(CommentsVO comment : list){
            List<CommentsVO> replies = comment.getReplies();
            if(replies != null){
                fillUserInfo(replies);
                fillReplyToNickname(replies);
                fillLiked(replies);
            }
        }

        //所有字段填充完成后才保存缓存，否则缓存里回复的 nickname/avatarUrl 为空
        saveCommentListToCache(cacheKey,pageVO);

        return pageVO;
    }


    @Override  //2.获取某条主评论下面的更多回复
    public PageVO<CommentsVO> queryMoreComments(Long rootId, Long page, Long pageSize) {

        Page<ArticleComments> pageResult = lambdaQuery().eq(ArticleComments::getRootId, rootId)
                .page(new Page<>(page, pageSize));

        List<CommentsVO> list = pageResult.getRecords().stream()
                .map(CommentsVO::from)
                .toList();

        fillUserInfo(list);
        fillReplyToNickname(list);
        fillLiked(list);

        //关于replies字段
        List<Long> rootIds = list.stream()
                .map(CommentsVO::getId)
                .filter(Objects::nonNull)
                .toList();

        if (list.isEmpty()) {
            return new PageVO<>(list, pageResult.getTotal(), page, pageSize);
        }

        List<ArticleComments> allReplies = lambdaQuery()
                .in(ArticleComments::getRootId, rootIds)
                .orderByAsc(ArticleComments::getCreatedAt)
                .list();

        Map<Long, List<ArticleComments>> repliesByRootId = allReplies.stream()
                .collect(Collectors.groupingBy(ArticleComments::getRootId));

        for (CommentsVO mainComment : list) {
            List<ArticleComments> replyEntities = repliesByRootId.getOrDefault(
                    mainComment.getId(),
                    List.of()
            );

            List<CommentsVO> replies = replyEntities.stream()
                    .map(CommentsVO::from)
                    .toList();

            mainComment.setReplies(replies);
            mainComment.setReplyCount((long) replyEntities.size());
        }

        for(CommentsVO comment : list){
            List<CommentsVO> replies = comment.getReplies();
            if(replies != null){
                fillUserInfo(replies);
                fillReplyToNickname(replies);
                fillLiked(replies);
            }
        }

        return new PageVO<>(
                list,
                pageResult.getTotal(),
                page,
                pageSize
        );
    }


    /**
     * 发表评论 / 回复评论。
     *
     * <p><b>2026-10-11 补上 {@code @Transactional}</b>：原先这个方法体里是两次独立写
     * （{@code save(评论)} + {@code comment_count + 1}），计数更新失败时评论已经落库、
     * 计数永久少 1，而且没有任何补偿。现在"评论 / 计数 / 通知"三处写在同一个事务里。
     *
     * <p>顺带把 Redis 热榜加分挪进了 {@code AfterCommitExecutor}：一旦有了事务，
     * 留在方法体里的 Redis 写就会变成"事务回滚了但热榜分数已经加上"的脏数据。
     * 点赞 / 收藏早就是这个写法，评论这边先前没有事务，所以没暴露。
     */
    @Override  //3.发表评论或回复评论，登录用户可访问
    @Transactional
    public void postComment(Long articleId, CommentDTO commentDTO, String clientIp,
                            String cloudflareCountryCode) {
        ArticleComments articleComments = new ArticleComments();
        BeanUtil.copyProperties(commentDTO,articleComments);

        /*
         * parentComment **提到了方法级**（原先声明在 else 块内，出块即销毁）：
         * 通知"评论被回复"需要它的作者 id 作为接收者。
         */
        Long parentId = commentDTO.getParentId();
        ArticleComments parentComment = null;
        if (parentId == null) {
            articleComments.setRootId(null);
        } else {
            parentComment = getById(parentId);
            if (parentComment == null) {
                throw new IllegalArgumentException("父评论不存在");
            }
            if (!articleId.equals(parentComment.getArticleId())) {
                throw new IllegalArgumentException("父评论不属于当前文章");
            }

            articleComments.setRootId(
                    parentComment.getRootId() == null
                            ? parentComment.getId()
                            : parentComment.getRootId()
            );
        }

        Long commenterId = UserContext.get();
        articleComments.setArticleId(articleId);
        articleComments.setCreatedAt(LocalDateTime.now());
        articleComments.setUserId(commenterId);
        articleComments.setLikeCount(0L);
        articleComments.setIp(clientIp);
        articleComments.setIpLocation(ipLocationService.getLocation(clientIp, cloudflareCountryCode));

        save(articleComments);

        articlesMapper.update(null,
                new LambdaUpdateWrapper<Articles>()
                        .eq(Articles::getId, articleId)
                        .setSql("comment_count = comment_count + 1"));

        //评论成功后，文章加入对应的score。
        //**必须 afterCommit**：本方法现在有事务，写在事务内的 Redis 副作用会在回滚后残留
        afterCommitExecutor.execute(() -> stringRedisTemplate.opsForZSet().incrementScore(
                RedisConstants.ARTICLE_HOT_KEY,
                String.valueOf(articleId),RedisConstants.ARTICLE_COMMENT_HOT_SCORE));

        evictArticleDetailCache(articleId);

        deleteCommentListCache(articleId);

        // 发表评论是强活跃信号（浏览可能只是路过，评论一定是有意为之）
        userActivityTracker.markActiveToday();

        notifyComment(articleId, articleComments.getId(), commenterId, parentComment);
    }

    /**
     * 评论 / 回复产生的通知（设计稿 §5）。
     *
     * <p><b>两条规则，别弄反</b>：
     * <ol>
     *   <li><b>回复优先</b>：回复场景先通知**被回复的人**（父评论作者）</li>
     *   <li><b>同一件事只打扰一次</b>：文章作者也要知道"有人评论了"，
     *       但若他**恰好就是被回复的那个人**，上面那条已经通知过他了——
     *       这里必须显式跳过。**不能指望 {@code notify} 的"不给自己发"去重**：
     *       那条判的是"接收者 == 触发者"，与"两个接收者是同一人"根本不是一回事</li>
     * </ol>
     *
     * <p>接收者需要文章作者，故多一次 {@code selectById}。文章查不到（理论上不该发生）
     * 就只是不发通知，**不影响评论本身**——这里刻意不新增"文章必须存在"的校验，保持原行为。
     */
    private void notifyComment(Long articleId, Long commentId, Long commenterId,
                               ArticleComments parentComment) {
        Articles article = articlesMapper.selectById(articleId);
        Long authorId = article == null ? null : article.getAuthorId();
        Long replyToUserId = parentComment == null ? null : parentComment.getUserId();

        if (replyToUserId != null) {
            notificationService.notify(replyToUserId, commenterId,
                    NotificationType.COMMENT_REPLIED, commentId, articleId);
        }

        if (authorId != null && !authorId.equals(replyToUserId)) {
            notificationService.notify(authorId, commenterId,
                    NotificationType.ARTICLE_COMMENTED, commentId, articleId);
        }
    }


    @Override  //4.删除评论，评论者或文章作者可访问
    public void deleteComment(Long commentId) {
        ArticleComments comment = getById(commentId);
        Long userId = UserContext.get();
        if (comment == null) {
            throw new IllegalArgumentException("评论不存在");
        }
        if (userId == null) {
            throw new IllegalArgumentException("请先登录");
        }

        Articles article = articlesMapper.selectById(comment.getArticleId());
        if (article == null) {
            throw new IllegalArgumentException("文章不存在");
        }

        if(!userId.equals(comment.getUserId()) && !userId.equals(article.getAuthorId())){
            throw new IllegalArgumentException("暂无权限");
        }

        List<Long> commentIds = new ArrayList<>();
        commentIds.add(commentId);
        commentIds.addAll(findDescendantCommentIds(commentId));

        lambdaUpdate()
                .set(ArticleComments::getDeletedAt, LocalDateTime.now())
                .set(ArticleComments::getDeletedBy, userId)
                .in(ArticleComments::getId, commentIds)
                .update();

        int deletedCount = commentIds.size();
        articlesMapper.update(null,
                new LambdaUpdateWrapper<Articles>()
                        .eq(Articles::getId, article.getId())
                        .setSql("comment_count = GREATEST(comment_count - " + deletedCount + ", 0)"));

        stringRedisTemplate.opsForZSet().incrementScore(RedisConstants.ARTICLE_HOT_KEY,
                String.valueOf(article.getId()),deletedCount * RedisConstants.ARTICLE_DELETE_COMMENT_HOT_SCORE);

        evictArticleDetailCache(article.getId());

        deleteCommentListCache(article.getId());
    }

    /** 评论数展示在文章详情页，发评论 / 删评论要失效详情缓存 */
    private void evictArticleDetailCache(Long articleId) {
        delayedCacheEvictor.evict(RedisConstants.ARTICLE_DETAIL_KEY_PREFIX + articleId);
    }

    private void deleteCommentListCache(Long articleId) {
        // SCAN 游标迭代 + UNLINK：原 KEYS 会阻塞 Redis 服务端单线程。
        // 走 DelayedCacheEvictor：**本类 2026-10-11 起 postComment 带事务了**（发通知要与评论同生共死），
        // 于是这里的失效从"立即执行"变成"提交后 + 延迟重放"——这正是延迟双删的设计意图，
        // 不需要因为加了事务而改这个出口
        delayedCacheEvictor.evictPattern(RedisConstants.COMMENT_LIST_KEY_PREFIX + articleId + ":*");
    }


    private List<Long> findDescendantCommentIds(Long commentId) {
        List<Long> descendants = new ArrayList<>();
        List<Long> currentLevelIds = List.of(commentId);

        while (!currentLevelIds.isEmpty()) {
            List<Long> childIds = lambdaQuery()
                    .select(ArticleComments::getId)
                    .in(ArticleComments::getParentId, currentLevelIds)
                    .list()
                    .stream()
                    .map(ArticleComments::getId)
                    .toList();

            if (childIds.isEmpty()) {
                break;
            }

            descendants.addAll(childIds);
            currentLevelIds = childIds;
        }

        return descendants;
    }


    private String buildCommentListCacheKey(Long articleId,Long page,Long pageSize,String sort){
        String normalizedSort = "hot".equals(sort) ? "hot" : "time";

        return RedisConstants.COMMENT_LIST_KEY_PREFIX + articleId
                + ":page:" + page
                + ":pageSize:" + pageSize
                + ":sort:" + normalizedSort;
    }

    //从缓存读评论列表
    private PageVO<CommentsVO> getCommentListFromCache(String key){
        String json = null;

        try{
            json = stringRedisTemplate.opsForValue().get(key);
        }
        catch (Exception e){
            return null;
        }

        if (json == null || json.isBlank()) {
            return null;
        }

        try{
            return objectMapper.readValue(json, new TypeReference<PageVO<CommentsVO>>() {});
        }
        catch(JsonProcessingException e){
            try{
                stringRedisTemplate.delete(key);
            } catch (Exception ex) {

            }
            return null;
        }


    }

    //保存评论列表缓存
    private void saveCommentListToCache(String key, PageVO<CommentsVO> pageVO){
        if (pageVO == null) {
            return;
        }

        try {
            stringRedisTemplate.opsForValue().set(
                    key,
                    objectMapper.writeValueAsString(pageVO),
                    cacheTtlSupport.jitter(Duration.ofMinutes(RedisConstants.COMMENT_LIST_TTL_MINUTES))
            );
        } catch (Exception e) {
            // 缓存失败不影响评论列表返回
        }
    }


    //这一块全是批量查询
    //关于avatarUser和nickname字段
    public void fillUserInfo(List<CommentsVO> list){
        List<Long> userIds = list.stream().map(CommentsVO::getUserId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        List<Users> users = new ArrayList<>();
        if (!userIds.isEmpty()) {
            users = usersMapper.selectBatchIds(userIds);
        }
        Map<Long, Users> usersById = users.stream().collect(Collectors.toMap(Users::getId, Function.identity()));

        for(CommentsVO comment : list){
            Users user = usersById.get(comment.getUserId());
            if (user != null) {
                comment.setNickname(user.getNickname());
                comment.setAvatarUrl(user.getAvatarUrl());
            }
        }
    }

    //关于replyToNickname字段
    public void fillReplyToNickname(List<CommentsVO> list){
        List<Long> parentIds = list.stream().map(CommentsVO::getParentId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        List<ArticleComments> parentComments = new ArrayList<>();
        if (!parentIds.isEmpty()) {
            parentComments = listByIds(parentIds);  //然后查询到该parentId的评论
        }
        Map<Long, ArticleComments> parentCommentById = parentComments.stream()
                .collect(Collectors.toMap(ArticleComments::getId, Function.identity()));

        List<Long> replyToUserIds = parentComments.stream()
                .map(ArticleComments::getUserId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();   //接着通过这些评论就可以拿到userId

        Map<Long, Users> replyToUsersById = replyToUserIds.isEmpty()
                ? Map.of()
                : usersMapper.selectBatchIds(replyToUserIds)
                .stream()
                .collect(Collectors.toMap(Users::getId, Function.identity()));

        for (CommentsVO comment : list) {  //拿到userId就可以拿到nickname了
            if (comment.getParentId() == null) {
                comment.setReplyToNickname(null);
                continue;
            }

            ArticleComments parentComment = parentCommentById.get(comment.getParentId());
            if (parentComment == null) {
                comment.setReplyToNickname(null);
                continue;
            }

            Users replyToUser = replyToUsersById.get(parentComment.getUserId());
            if (replyToUser != null) {
                comment.setReplyToNickname(replyToUser.getNickname());
            }
        }
    }

    //关于liked字段
    public void fillLiked(List<CommentsVO> list){
        Long currentUserId = UserContext.get();

        if(list == null || list.isEmpty() || currentUserId == null){
            return;
        }
        List<Long> commentIds = list.stream()
                .map(CommentsVO::getId)
                .toList();

        if(commentIds.isEmpty()){
            return;
        }

        //改为Redis Set优先，再来判断是否要查询数据库
        String key = RedisConstants.COMMENT_LIKED_USER_KEY_PREFIX + currentUserId;
        // 三态：null = 未加载或状态不可信（下面回源）；空 Set = 确认没点过赞；非空 = 命中
        Set<String> likedIdStrings = userSetCache.readIfLoaded(key);

        if (likedIdStrings == null) {
            List<CommentLikes> commentLikes = likeCommentsMapper.selectList(
                    new LambdaQueryWrapper<CommentLikes>()
                            .eq(CommentLikes::getUserId, currentUserId)
            );

            likedIdStrings = commentLikes.stream()
                    .map(like -> String.valueOf(like.getCommentId()))
                    .collect(Collectors.toSet());

            userSetCache.markLoaded(key, likedIdStrings);
        }
        Set<Long> likedCommentIds = likedIdStrings.stream()
                .map(Long::valueOf)
                .collect(Collectors.toSet());

        for(CommentsVO comment : list){
            comment.setLiked(likedCommentIds.contains(comment.getId()));
        }
    }
}
