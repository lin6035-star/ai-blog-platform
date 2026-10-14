package com.hailin.blogsystem.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hailin.blogsystem.entity.UserFollows;
import com.hailin.blogsystem.entity.dto.ArticleInteractionResult;

import java.util.List;

public interface FollowService extends IService<UserFollows> {
    void followUser(Long id);

    ArticleInteractionResult followUser(Long userId, Long followingId);

    void cancelFollow(Long id);

    ArticleInteractionResult cancelFollow(Long userId, Long followingId);

    /**
     * 当前**还有效**的关注对象 id（已取关的不算），给首页「关注」流用。
     *
     * <p>未登录（{@code followerId == null}）返回空列表而不是抛异常——首页是游客也能看的页面，
     * 未登录时那个 tab 显示空状态即可，不该因此报错。
     */
    List<Long> listFollowingIds(Long followerId);
}
