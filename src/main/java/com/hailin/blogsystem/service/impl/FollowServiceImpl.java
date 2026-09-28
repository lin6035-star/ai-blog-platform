package com.hailin.blogsystem.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hailin.blogsystem.entity.UserFollows;
import com.hailin.blogsystem.entity.Users;
import com.hailin.blogsystem.entity.dto.ArticleInteractionAction;
import com.hailin.blogsystem.entity.dto.ArticleInteractionResult;
import com.hailin.blogsystem.entity.dto.ArticleInteractionResultCode;
import com.hailin.blogsystem.mapper.FollowMapper;
import com.hailin.blogsystem.mapper.UsersMapper;
import com.hailin.blogsystem.service.FollowService;
import com.hailin.blogsystem.utils.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.cglib.core.Local;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class FollowServiceImpl extends ServiceImpl<FollowMapper, UserFollows>
implements FollowService {

    private final UsersMapper usersMapper;

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
