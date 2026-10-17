package com.hailin.blogsystem;

import com.hailin.blogsystem.entity.ArticleComments;
import com.hailin.blogsystem.entity.Users;
import com.hailin.blogsystem.entity.vo.ArticleDetailVO;
import com.hailin.blogsystem.entity.vo.PageVO;
import com.hailin.blogsystem.entity.vo.UserRelationVO;
import com.hailin.blogsystem.mapper.CommentsMapper;
import com.hailin.blogsystem.mapper.UsersMapper;
import com.hailin.blogsystem.service.UsersService;
import com.hailin.blogsystem.utils.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class UsersServiceTests {

    @Autowired
    private UsersService usersService;

    @Autowired
    private CommentsMapper commentsMapper;

    @Autowired
    private UsersMapper usersMapper;

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test
    void commentedArticlesAreDistinctAndOrderedByLatestCommentTime() {
        commentsMapper.insert(comment(3001L, 1L, "2026-07-10T10:00:00"));
        commentsMapper.insert(comment(3002L, 3L, "2026-07-12T10:00:00"));
        commentsMapper.insert(comment(3003L, 1L, "2026-07-13T10:00:00"));
        UserContext.set(101L);

        PageVO<ArticleDetailVO> firstPage = usersService.getComment(1L, 1L);
        PageVO<ArticleDetailVO> secondPage = usersService.getComment(2L, 1L);

        assertThat(firstPage.getTotal()).isEqualTo(2L);
        assertThat(firstPage.getList()).extracting(ArticleDetailVO::getId).containsExactly(1L);
        assertThat(secondPage.getTotal()).isEqualTo(2L);
        assertThat(secondPage.getList()).extracting(ArticleDetailVO::getId).containsExactly(3L);
    }

    // ============ 用户搜索（2026-10-17）============

    @Test
    void searchUsersMatchesNicknameFuzzy() {
        UserContext.clear();

        // 种子用户：Author Nick / Reader Nick / Follower Nick —— 都含 "Nick"
        PageVO<UserRelationVO> result = usersService.searchUsers("Nick", 1L, 10L);

        assertThat(result.getList()).extracting(UserRelationVO::getNickname)
                .containsExactlyInAnyOrder("Author Nick", "Reader Nick", "Follower Nick");
        assertThat(result.getTotal()).isEqualTo(3L);
    }

    @Test
    void searchUsersExcludesDeletedAndDisabled() {
        usersMapper.insert(searchUser(200L, "Searchable Live", 1, null));
        usersMapper.insert(searchUser(201L, "Searchable Banned", 0, null));
        usersMapper.insert(searchUser(202L, "Searchable Gone", 1, LocalDateTime.now()));

        PageVO<UserRelationVO> result = usersService.searchUsers("Searchable", 1L, 10L);

        // 软删除（Users.deletedAt 的 @TableLogic）与禁用账号都不该出现在搜索结果里
        assertThat(result.getList()).extracting(UserRelationVO::getId).containsExactly(200L);
        assertThat(result.getTotal()).isEqualTo(1L);
    }

    @Test
    void searchUsersPaginatesWithStableTotal() {
        PageVO<UserRelationVO> first = usersService.searchUsers("Nick", 1L, 2L);
        PageVO<UserRelationVO> second = usersService.searchUsers("Nick", 2L, 2L);

        assertThat(first.getList()).hasSize(2);
        assertThat(first.getTotal()).isEqualTo(3L);
        assertThat(second.getList()).hasSize(1);
        assertThat(second.getTotal()).isEqualTo(3L);
    }

    @Test
    void searchUsersWithBlankKeywordReturnsEmptyPage() {
        PageVO<UserRelationVO> byNull = usersService.searchUsers(null, 1L, 10L);
        PageVO<UserRelationVO> byBlank = usersService.searchUsers("   ", 1L, 10L);

        // 空关键词是「没搜索」，不该退化成「列出全站用户」
        assertThat(byNull.getList()).isEmpty();
        assertThat(byNull.getTotal()).isZero();
        assertThat(byBlank.getList()).isEmpty();
    }

    private Users searchUser(Long id, String nickname, Integer status, LocalDateTime deletedAt) {
        Users user = new Users();
        user.setId(id);
        user.setUsername("search-test-" + id);
        user.setPasswordHash("$2a$10$gVwQ5x1Yz2X3y4Z5a6B7cO8P9qR0sT1uV2wX3yZ4a5B6c7D8e9F0G");
        user.setNickname(nickname);
        user.setStatus(status);
        user.setDeletedAt(deletedAt);
        user.setFollowersCount(0);
        user.setFollowingCount(0);
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        return user;
    }

    private ArticleComments comment(Long id, Long articleId, String createdAt) {
        ArticleComments comment = new ArticleComments();
        comment.setId(id);
        comment.setArticleId(articleId);
        comment.setUserId(101L);
        comment.setContent("comment " + id);
        comment.setLikeCount(0L);
        comment.setCreatedAt(LocalDateTime.parse(createdAt));
        return comment;
    }
}
