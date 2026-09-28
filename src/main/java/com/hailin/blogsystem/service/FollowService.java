package com.hailin.blogsystem.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hailin.blogsystem.entity.UserFollows;
import com.hailin.blogsystem.entity.dto.ArticleInteractionResult;

public interface FollowService extends IService<UserFollows> {
    void followUser(Long id);

    ArticleInteractionResult followUser(Long userId, Long followingId);

    void cancelFollow(Long id);

    ArticleInteractionResult cancelFollow(Long userId, Long followingId);
}
