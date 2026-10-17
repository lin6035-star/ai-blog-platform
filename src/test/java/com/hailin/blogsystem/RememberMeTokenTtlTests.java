package com.hailin.blogsystem;

import com.hailin.blogsystem.entity.Users;
import com.hailin.blogsystem.mapper.LoginMapper;
import com.hailin.blogsystem.security.CaptchaVerifier;
import com.hailin.blogsystem.utils.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 「记住我」= **有效期不同**。
 *
 * <p>要抓的失败模式很具体：<b>复选框在界面上勾了、请求也传了，但服务端压根没读它</b>
 * ——那时功能看起来完全正常（能登录、能保持），只是所有人都是同一个有效期。
 * 单测测不到它（要看"哪个 ttl 被传下去"，得走完整链路），所以要拿真实签发的 token 反算 exp。
 *
 * <p>顺带锁住另一件容易漏的事：{@code rememberMe} 为 null（老前端不传）时必须按
 * **短有效期**处理，不能默默给 30 天。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RememberMeTokenTtlTests {

    /** 不勾：24 小时。给区间是留出执行耗时与时钟抖动的余量，不是宽松判定 */
    private static final long SHORT_MIN_DAYS = 0;
    private static final long SHORT_MAX_DAYS = 2;

    /** 勾选：30 天 */
    private static final long LONG_MIN_DAYS = 28;
    private static final long LONG_MAX_DAYS = 31;

    private static final String RAW_PASSWORD = "remember-me-test-pwd";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LoginMapper loginMapper;

    @Autowired
    private JwtUtil jwtUtil;

    /** 本组测的是 ttl，不该先真滑一次滑块 */
    @MockBean
    private CaptchaVerifier captchaVerifier;

    private String username;

    @BeforeEach
    void seedUser() {
        username = "remember_" + UUID.randomUUID().toString().substring(0, 8);
        Users user = new Users();
        user.setUsername(username);
        user.setNickname("remember-probe");
        user.setPasswordHash(new BCryptPasswordEncoder().encode(RAW_PASSWORD));
        user.setLoginType("password");
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        loginMapper.insert(user);
    }

    @Test
    void checkedRememberMeIssuesLongLivedToken() throws Exception {
        long days = remainingDays(login("true"));
        assertThat(days)
                .as("勾选「记住我」应给长有效期（%d 天档）", LONG_MIN_DAYS)
                .isBetween(LONG_MIN_DAYS, LONG_MAX_DAYS);
    }

    @Test
    void uncheckedRememberMeIssuesShortLivedToken() throws Exception {
        long days = remainingDays(login("false"));
        assertThat(days)
                .as("不勾「记住我」应给短有效期（24 小时档）")
                .isBetween(SHORT_MIN_DAYS, SHORT_MAX_DAYS);
    }

    /** 老前端不传这个字段时，宁可让用户多登一次，也不能默默给 30 天 */
    @Test
    void missingRememberMeFallsBackToShortTtl() throws Exception {
        long days = remainingDays(login(null));
        assertThat(days)
                .as("rememberMe 缺失必须按「不记住」处理")
                .isBetween(SHORT_MIN_DAYS, SHORT_MAX_DAYS);
    }

    // ===== helpers =====

    /** @param rememberMe {@code "true"} / {@code "false"} / {@code null}（字段整个不出现） */
    private String login(String rememberMe) throws Exception {
        String field = rememberMe == null ? "" : ",\"rememberMe\":" + rememberMe;

        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s","captchaVerification":"test-only"%s}
                                """.formatted(username, RAW_PASSWORD, field)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn()
                .getResponse()
                .getContentAsString();

        // 不引 JSON 库：token 在响应里是唯一一个 "token":"..." 片段
        int start = body.indexOf("\"token\":\"") + 9;
        int end = body.indexOf('"', start);
        assertThat(start).as("响应里应当有 token：%s", body).isGreaterThan(8);
        return body.substring(start, end);
    }

    private long remainingDays(String token) {
        long expireAt = jwtUtil.parsePayload(token).expireAtSeconds();
        return (expireAt - Instant.now().getEpochSecond()) / 86400;
    }
}
