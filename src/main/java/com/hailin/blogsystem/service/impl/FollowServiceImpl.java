package com.hailin.blogsystem.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hailin.blogsystem.entity.NotificationType;
import com.hailin.blogsystem.entity.UserFollows;
import com.hailin.blogsystem.entity.Users;
import com.hailin.blogsystem.entity.dto.ArticleInteractionAction;
import com.hailin.blogsystem.entity.dto.ArticleInteractionResult;
import com.hailin.blogsystem.entity.dto.ArticleInteractionResultCode;
import com.hailin.blogsystem.mapper.FollowMapper;
import com.hailin.blogsystem.mapper.UsersMapper;
import com.hailin.blogsystem.service.FollowService;
import com.hailin.blogsystem.service.NotificationService;
import com.hailin.blogsystem.utils.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.cglib.core.Local;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class FollowServiceImpl extends ServiceImpl<FollowMapper, UserFollows>
implements FollowService {

    private final UsersMapper usersMapper;
    private final NotificationService notificationService;

    @Override  //关注用户
    @Transactional
    public void followUser(Long followingId) {
        Long userId = UserContext.get();

        if(userId == null){
            throw new IllegalArgumentException("请先登录再操作亲");
        }

        ArticleInteractionResult result = followUser(userId, followingId);
        throwForLegacyFollowFailure(result);
    }

    @Override
    @Transactional
    public ArticleInteractionResult followUser(Long userId, Long followingId) {
        ArticleInteractionResult validation = validateFollowTarget(
                ArticleInteractionAction.FOLLOW_AUTHOR, userId, followingId);
        if (validation != null) {
            return validation;
        }

        UserFollows history = lambdaQuery()
                .eq(UserFollows::getFollowerId, userId)
                .eq(UserFollows::getFollowingId, followingId)
                .one();

        UserFollows userFollows = new UserFollows();

        if(history == null){
            userFollows.setFollowerId(userId);
            userFollows.setFollowingId(followingId);
            userFollows.setCreatedAt(LocalDateTime.now());
            userFollows.setUpdatedAt(LocalDateTime.now());

            try {
                save(userFollows);
            } catch (DuplicateKeyException e) {
                return ArticleInteractionResult.success(
                        ArticleInteractionAction.FOLLOW_AUTHOR,
                        ArticleInteractionResultCode.ALREADY_SATISFIED,
                        "已经关注该up",
                        true,
                        true);
            }
            incrementFollowCounters(userId, followingId);

            // 通知被关注的人。接收者就是方法入参，**零额外查询**——所有挂点里最干净的一个。
            // 与业务同事务；上面的 ALREADY_SATISFIED 分支已 return，能走到这里必是 APPLIED
            notificationService.notify(followingId, userId,
                    NotificationType.USER_FOLLOWED, followingId, null);

            return ArticleInteractionResult.success(
                    ArticleInteractionAction.FOLLOW_AUTHOR,
                    ArticleInteractionResultCode.APPLIED,
                    "关注成功",
                    false,
                    true);
        }

        if(history.getDeletedAt() == null){
            return ArticleInteractionResult.success(
                    ArticleInteractionAction.FOLLOW_AUTHOR,
                    ArticleInteractionResultCode.ALREADY_SATISFIED,
                    "已经关注该up",
                    true,
                    true);
        }

        boolean restored = update(new LambdaUpdateWrapper<UserFollows>()
                .eq(UserFollows::getFollowerId, userId)
                .eq(UserFollows::getFollowingId, followingId)
                .isNotNull(UserFollows::getDeletedAt)
                .set(UserFollows::getUpdatedAt, LocalDateTime.now())
                .set(UserFollows::getDeletedAt, null));

        if (!restored) {
            return ArticleInteractionResult.success(
                    ArticleInteractionAction.FOLLOW_AUTHOR,
                    ArticleInteractionResultCode.ALREADY_SATISFIED,
                    "已经关注该up",
                    true,
                    true);
        }
        incrementFollowCounters(userId, followingId);

        /*
         * 取关后重新关注（复活那条软删行）同样发一次。
         *
         * ⚠️ 它和首次关注的通知**唯一键完全相同**（receiver/actor/target 都没变），
         * 所以只要上一条通知还在，这次插入会被唯一键静默忽略——即"取关再关注不会重复打扰"，
         * 与点赞"取消再赞不重复通知"是同一个口径（设计稿 §6.1 的有意防刷，不是漏发）。
         */
        notificationService.notify(followingId, userId,
                NotificationType.USER_FOLLOWED, followingId, null);

        return ArticleInteractionResult.success(
                ArticleInteractionAction.FOLLOW_AUTHOR,
                ArticleInteractionResultCode.APPLIED,
                "关注成功",
                false,
                true);
    }


    @Override  //取消关注
    @Transactional
    public void cancelFollow(Long followingId) {
        Long userId = UserContext.get();

        if(userId == null){
            throw new IllegalArgumentException("请先登录再操作亲");
        }

        ArticleInteractionResult result = cancelFollow(userId, followingId);
        throwForLegacyCancelFailure(result);
    }

    @Override
    @Transactional
    public ArticleInteractionResult cancelFollow(Long userId, Long followingId) {
        ArticleInteractionResult validation = validateFollowTarget(
                ArticleInteractionAction.UNFOLLOW_AUTHOR, userId, followingId);
        if (validation != null) {
            return validation;
        }

        boolean removed = update(new LambdaUpdateWrapper<UserFollows>()
                .eq(UserFollows::getFollowerId, userId)
                .eq(UserFollows::getFollowingId, followingId)
                .isNull(UserFollows::getDeletedAt)
                .set(UserFollows::getDeletedAt, LocalDateTime.now())
                .set(UserFollows::getUpdatedAt, LocalDateTime.now()));
        if (!removed) {
            return ArticleInteractionResult.success(
                    ArticleInteractionAction.UNFOLLOW_AUTHOR,
                    ArticleInteractionResultCode.ALREADY_SATISFIED,
                    "尚未关注该用户",
                    false,
                    false);
        }

        int actorUpdated = usersMapper.update(null,
                new LambdaUpdateWrapper<Users>()
                        .eq(Users::getId,userId)
                        .setSql("following_count = GREATEST(following_count - 1, 0)"));

        int targetUpdated = usersMapper.update(null,
                new LambdaUpdateWrapper<Users>()
                        .eq(Users::getId,followingId)
                        .setSql("followers_count = GREATEST(followers_count - 1,0)"));
        if (actorUpdated != 1 || targetUpdated != 1) {
            throw new IllegalStateException("取消关注计数更新失败");
        }
        return ArticleInteractionResult.success(
                ArticleInteractionAction.UNFOLLOW_AUTHOR,
                ArticleInteractionResultCode.APPLIED,
                "已取消关注",
                true,
                false);
    }

    private ArticleInteractionResult validateFollowTarget(
            ArticleInteractionAction action,
            Long userId,
            Long followingId
    ) {
        if (userId.equals(followingId)) {
            return ArticleInteractionResult.failure(
                    action,
                    ArticleInteractionResultCode.FORBIDDEN,
                    "不能关注自己",
                    null,
                    null);
        }
        if (usersMapper.selectById(followingId) == null) {
            return ArticleInteractionResult.failure(
                    action,
                    ArticleInteractionResultCode.TARGET_NOT_FOUND,
                    "用户不存在",
                    null,
                    null);
        }
        return null;
    }

    @Override
    public List<Long> listFollowingIds(Long followerId) {
        if (followerId == null) {
            return List.of();
        }
        return lambdaQuery()
                .eq(UserFollows::getFollowerId, followerId)
                // 取关走的是软删除（deleted_at 记的是取关时间），这里只要「当前还有效」的。
                // 注意与 followUser 里那次查询的区别：那边特意**不带**这个条件，
                // 因为它要判断「是否曾经关注过」来决定是新建还是恢复。
                .isNull(UserFollows::getDeletedAt)
                .list()
                .stream()
                .map(UserFollows::getFollowingId)
                .toList();
    }

    private void incrementFollowCounters(Long userId, Long followingId) {
        int actorUpdated = usersMapper.update(null,
                new LambdaUpdateWrapper<Users>()
                        .eq(Users::getId, userId)
                        .setSql("following_count = GREATEST(following_count + 1, 0)"));
        int targetUpdated = usersMapper.update(null,
                new LambdaUpdateWrapper<Users>()
                        .eq(Users::getId, followingId)
                        .setSql("followers_count = GREATEST(followers_count + 1,0)"));
        if (actorUpdated != 1 || targetUpdated != 1) {
            throw new IllegalStateException("关注计数更新失败");
        }
    }

    private void throwForLegacyFollowFailure(ArticleInteractionResult result) {
        if (result.resultCode() == ArticleInteractionResultCode.FORBIDDEN) {
            throw new IllegalArgumentException("不能关注自己");
        }
        if (result.resultCode() == ArticleInteractionResultCode.TARGET_NOT_FOUND) {
            throw new IllegalArgumentException("用户不存在");
        }
        if (result.resultCode() == ArticleInteractionResultCode.ALREADY_SATISFIED) {
            throw new IllegalArgumentException("已经关注该up");
        }
    }

    private void throwForLegacyCancelFailure(ArticleInteractionResult result) {
        if (result.resultCode() == ArticleInteractionResultCode.FORBIDDEN) {
            throw new IllegalArgumentException("不能关注自己");
        }
        if (result.resultCode() == ArticleInteractionResultCode.TARGET_NOT_FOUND) {
            throw new IllegalArgumentException("用户不存在");
        }
        if (result.resultCode() == ArticleInteractionResultCode.ALREADY_SATISFIED) {
            throw new IllegalArgumentException("尚未关注该用户");
        }
    }
}
