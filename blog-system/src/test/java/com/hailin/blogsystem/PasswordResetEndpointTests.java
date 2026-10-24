package com.hailin.blogsystem;

import com.hailin.blogsystem.entity.Users;
import com.hailin.blogsystem.mapper.LoginMapper;
import com.hailin.blogsystem.security.CaptchaVerifier;
import com.hailin.blogsystem.service.MailService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 「忘记密码」接口的对外契约。
 *
 * <p><b>这组用例守的是防枚举</b>：申请接口对「已注册邮箱 / 未注册邮箱 / GitHub 账号邮箱」
 * 的响应必须**逐字节一致**。这一条是纯约定，任何一次"顺手优化提示文案"都会破坏它，
 * 而且破坏之后功能看起来完全正常——所以必须由测试锁住，而不是靠注释提醒。
 *
 * <p>邮箱一律随机生成：{@code users} 是逻辑删除 + email 唯一索引，
 * 固定邮箱会在第二次跑测试时撞唯一键。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PasswordResetEndpointTests {

    private static final String CAPTCHA = "test-only";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LoginMapper loginMapper;

    /** 逐字节比较响应，不能真去连 SMTP */
    @MockBean
    private MailService mailService;

    /** 本组测的是响应契约，不该先去滑一次滑块 */
    @MockBean
    private CaptchaVerifier captchaVerifier;

    @Test
    void forgotResponseIsIdenticalForKnownAndUnknownEmail() throws Exception {
        when(mailService.isConfigured()).thenReturn(true);

        String knownEmail = seedPasswordAccount();
        String unknownEmail = "nobody-" + UUID.randomUUID() + "@example.com";

        String knownBody = forgot(knownEmail);
        String unknownBody = forgot(unknownEmail);

        assertThat(knownBody)
                .as("已注册与未注册邮箱的响应必须完全一致，否则接口成了「这个邮箱注册过吗」的探针")
                .isEqualTo(unknownBody);
    }

    /**
     * GitHub 账号的邮箱也要走同一条路：它**有邮箱但没有密码可重置**，
     * 而对外不能表现出任何区别——告诉用户"你是 GitHub 用户"就等于确认这个邮箱注册过。
     */
    @Test
    void forgotResponseIsIdenticalForGithubAccountEmail() throws Exception {
        when(mailService.isConfigured()).thenReturn(true);

        String passwordEmail = seedPasswordAccount();
        String githubEmail = seedGithubAccount();

        assertThat(forgot(githubEmail)).isEqualTo(forgot(passwordEmail));
    }

    @Test
    void resetRejectsInvalidTokenWithDedicatedCode() throws Exception {
        mockMvc.perform(post("/api/auth/password/reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token":"definitely-not-a-real-token","newPassword":"newpass123"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40003))
                .andExpect(jsonPath("$.message").value("链接已失效，请重新申请"));
    }

    /**
     * 邮件没配 → **明确报 50301**，而不是假装"已发送"。
     *
     * <p>这是防枚举的**唯一豁免**：它是全局故障，跟"某个邮箱是否注册过"无关。
     * 真实踩过：没配 mail 时点忘记密码，界面提示已发送、收件箱什么都没有，
     * 只能翻日志才发现——这条用例就是防止那个体验回来。
     */
    @Test
    void forgotReportsErrorWhenMailIsNotConfigured() throws Exception {
        when(mailService.isConfigured()).thenReturn(false);

        mockMvc.perform(post("/api/auth/password/forgot")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"anyone@example.com","captchaVerification":"%s"}
                                """.formatted(CAPTCHA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(50301));
    }

    // ===== helpers =====

    private String forgot(String email) throws Exception {
        return mockMvc.perform(post("/api/auth/password/forgot")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","captchaVerification":"%s"}
                                """.formatted(email, CAPTCHA)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private String seedPasswordAccount() {
        String email = "pwd-probe-" + UUID.randomUUID() + "@example.com";
        insertUser(email, "password");
        return email;
    }

    private String seedGithubAccount() {
        String email = "gh-probe-" + UUID.randomUUID() + "@example.com";
        insertUser(email, "github");
        return email;
    }

    private void insertUser(String email, String loginType) {
        Users user = new Users();
        user.setUsername("probe_" + UUID.randomUUID().toString().substring(0, 8));
        user.setNickname("probe");
        user.setPasswordHash("");
        user.setLoginType(loginType);
        user.setEmail(email);
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        loginMapper.insert(user);
    }
}
