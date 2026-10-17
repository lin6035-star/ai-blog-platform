package com.hailin.blogsystem;

import com.hailin.blogsystem.security.CaptchaVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 注册 / 登录接口的错误路径。
 *
 * <p>{@code CaptchaVerifier} 用 {@code @MockBean} 挡掉：这几条测的是**注册与登录自身**的
 * 错误分支（参数、重名、密码错），跑它们不该先去滑一次滑块。验证码自己有
 * {@code CaptchaVerifierTests} 和端到端脚本覆盖。
 *
 * <p>但 {@code captchaVerification} 与 {@code email} **必须在请求体里给合法值**——
 * DTO 上分别是 {@code @NotBlank} 和 {@code @NotBlank + @Email}，缺了或写错会在 @Valid
 * 阶段就返回 40001，那测到的就不是本用例想测的分支了。mock 只让验证码"校验通过"，
 * 不改变参数校验。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerTests {

    private static final String OK_EMAIL = "tester@example.com";
    private static final String CAPTCHA = "test-only";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CaptchaVerifier captchaVerifier;

    @Test
    void rejectsBlankUsernameOnRegister() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": " ",
                                  "password": "testpass123",
                                  "confirmPassword": "testpass123",
                                  "nickname": "Tester",
                                  "email": "%s",
                                  "captchaVerification": "%s"
                                }
                                """.formatted(OK_EMAIL, CAPTCHA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value("用户名不能为空"));
    }

    @Test
    void rejectsBlankNicknameOnRegister() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "tester",
                                  "password": "testpass123",
                                  "confirmPassword": "testpass123",
                                  "nickname": "",
                                  "email": "%s",
                                  "captchaVerification": "%s"
                                }
                                """.formatted(OK_EMAIL, CAPTCHA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value("昵称不能为空"));
    }

    /**
     * 用户名重名。注意它必须**通过 @Valid** 才能走到 service 的重名判断，
     * 所以 email / captchaVerification 都得填合法值。
     */
    @Test
    void convertsDuplicateUsernameExceptionToResult() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "existing",
                                  "password": "testpass123",
                                  "confirmPassword": "testpass123",
                                  "nickname": "Tester",
                                  "email": "%s",
                                  "captchaVerification": "%s"
                                }
                                """.formatted(OK_EMAIL, CAPTCHA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value("用户名已存在"));
    }

    @Test
    void returnsLoginFailedCodeForBadCredentials() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "missing",
                                  "password": "testpass123",
                                  "captchaVerification": "%s"
                                }
                                """.formatted(CAPTCHA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40101))
                .andExpect(jsonPath("$.message").value("用户名或密码错误"));
    }
}
