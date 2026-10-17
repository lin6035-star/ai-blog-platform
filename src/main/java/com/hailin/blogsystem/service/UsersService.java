package com.hailin.blogsystem.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hailin.blogsystem.entity.Users;
import com.hailin.blogsystem.entity.dto.UserProfileDTO;
import com.hailin.blogsystem.entity.vo.ArticleDetailVO;
import com.hailin.blogsystem.entity.vo.PageVO;
import com.hailin.blogsystem.entity.vo.UserInfoVO;
import com.hailin.blogsystem.entity.vo.UserRelationVO;
import com.hailin.blogsystem.entity.vo.UsersVO;

public interface UsersService extends IService<Users> {
    UsersVO getUsersInfo();

    UsersVO updateProfile(UserProfileDTO userProfileDTO);

    PageVO<ArticleDetailVO> getMyArticles(Long page,Long pageSize,Long status);

    PageVO<ArticleDetailVO> getMyLiked(Long page, Long pageSize);

    PageVO<ArticleDetailVO> getMyFavorites(Long page, Long pageSize);

    PageVO<ArticleDetailVO> getComment(Long page, Long pageSize);

    UserInfoVO getPublicUserInfo(Long id);

    PageVO<UserRelationVO> getPublicUserFollowing(Long id, Long page, Long pageSize);

    PageVO<UserRelationVO> getPublicUserFollowers(Long id, Long page, Long pageSize);

    /**
     * 按昵称搜索用户（公开接口，游客可访问）。
     *
     * <p>只搜 {@code nickname}：{@code username} 是登录账号，可搜等于让任何人批量试探账号是否存在；
     * {@code bio} 噪声太大，靠简介里的词把无关用户全捞出来。
     *
     * <p>登录用户访问时返回 {@code followed} / {@code self}，游客访问时两者恒为默认值——
     * 与关注/粉丝列表的口径一致（复用 {@code fillRelationState}）。
     */
    PageVO<UserRelationVO> searchUsers(String keyword, Long page, Long pageSize);
}
