package com.hailin.blogsystem;

import com.hailin.blogsystem.utils.JwtUtil;
import com.hailin.blogsystem.utils.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UsersControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtil jwtUtil;

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test
    void updatesCurrentUserProfile() throws Exception {
        String token = jwtUtil.generateToken(101L, 0);

        mockMvc.perform(put("/api/users/me/profile")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "nickname": "新的昵称",
                                  "bio": "新的个人简介"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(101L))
                .andExpect(jsonPath("$.data.nickname").value("新的昵称"))
                .andExpect(jsonPath("$.data.bio").value("新的个人简介"));
    }

    @Test
    void getsCurrentUserInfoWithFollowCounts() throws Exception {
        String token = jwtUtil.generateToken(100L, 0);

        mockMvc.perform(get("/api/users/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(100L))
                .andExpect(jsonPath("$.data.followingCount").value(1))
                .andExpect(jsonPath("$.data.followersCount").value(1));
    }

    @Test
    void getsPublicUserFollowingWithPagination() throws Exception {
        mockMvc.perform(get("/api/users/100/following")
                        .param("page", "1")
                        .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.list", hasSize(1)))
                .andExpect(jsonPath("$.data.list[0].id").value(101L))
                .andExpect(jsonPath("$.data.list[0].nickname").value("Reader Nick"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(10));
    }

    @Test
    void getsPublicUserFollowersAndExcludesSoftDeletedFollows() throws Exception {
        String authorToken = jwtUtil.generateToken(100L, 0);

        mockMvc.perform(get("/api/users/100/followers")
                        .header("Authorization", "Bearer " + authorToken)
                        .param("page", "1")
                        .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.list", hasSize(1)))
                .andExpect(jsonPath("$.data.list[0].id").value(102L))
                .andExpect(jsonPath("$.data.list[0].nickname").value("Follower Nick"))
                .andExpect(jsonPath("$.data.list[0].followed").value(false))
                .andExpect(jsonPath("$.data.list[0].self").value(false))
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    void keepsMutualFollowStateAfterRefreshingFollowersList() throws Exception {
        String authorToken = jwtUtil.generateToken(100L, 0);

        mockMvc.perform(post("/api/users/102/follow")
                        .header("Authorization", "Bearer " + authorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(get("/api/users/100/followers")
                        .header("Authorization", "Bearer " + authorToken)
                        .param("page", "1")
                        .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.list", hasSize(1)))
                .andExpect(jsonPath("$.data.list[0].id").value(102L))
                .andExpect(jsonPath("$.data.list[0].followed").value(true))
                .andExpect(jsonPath("$.data.list[0].self").value(false));
    }

    @Test
    void publicUserInfoUsesActiveFollowState() throws Exception {
        String authorToken = jwtUtil.generateToken(100L, 0);

        mockMvc.perform(get("/api/users/101")
                        .header("Authorization", "Bearer " + authorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.followed").value(true))
                .andExpect(jsonPath("$.data.self").value(false));

        mockMvc.perform(get("/api/users/100")
                        .header("Authorization", "Bearer " + authorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.followed").value(false))
                .andExpect(jsonPath("$.data.self").value(true));
    }

    @Test
    void keepsFollowStateAfterRefreshingPublicProfile() throws Exception {
        String authorToken = jwtUtil.generateToken(100L, 0);

        mockMvc.perform(post("/api/users/102/follow")
                        .header("Authorization", "Bearer " + authorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(get("/api/users/102")
                        .header("Authorization", "Bearer " + authorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.followed").value(true));

        mockMvc.perform(delete("/api/users/102/follow")
                        .header("Authorization", "Bearer " + authorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(get("/api/users/102")
                        .header("Authorization", "Bearer " + authorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.followed").value(false));
    }

    // ============ 用户搜索（2026-10-17）============

    /**
     * 路径不被 {@code /api/users/{id}} 吞掉的契约测试。
     *
     * <p>UserInfoController 是 {@code /api/users/{id}}，本接口是 {@code /api/users/search}——
     * 一旦 Spring 把 "search" 当成 id，`Long.valueOf("search")` 会直接失败（400/500），
     * 前端看到的就是"搜索坏了"。字面量优先于变量段，这里把它钉死。
     *
     * <p>同时验证游客可访问（不带 Authorization），且空的关键词不报错。
     */
    @Test
    void searchesUsersByNicknameWithoutAuth() throws Exception {
        mockMvc.perform(get("/api/users/search").param("keyword", "Nick"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.list", hasSize(3)))
                .andExpect(jsonPath("$.data.total").value(3));

        mockMvc.perform(get("/api/users/search"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.list", hasSize(0)));
    }

    @Test
    void userSearchFillsFollowStateForLoggedInUser() throws Exception {
        String authorToken = jwtUtil.generateToken(100L, 0);

        // 种子数据：100 关注了 101，所以搜到 101 时 followed=true
        mockMvc.perform(get("/api/users/search")
                        .header("Authorization", "Bearer " + authorToken)
                        .param("keyword", "Reader"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.list", hasSize(1)))
                .andExpect(jsonPath("$.data.list[0].nickname").value("Reader Nick"))
                .andExpect(jsonPath("$.data.list[0].followed").value(true))
                .andExpect(jsonPath("$.data.list[0].self").value(false));
    }
}
