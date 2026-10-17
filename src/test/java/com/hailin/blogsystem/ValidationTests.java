package com.hailin.blogsystem;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code @Valid} 参数校验的对外契约。
 *
 * <p><b>为什么第一断言是 HTTP 200</b>：项目约定业务错误走 `200 + code`
 * （见 docs/api-convention.md），只有限流 / 额度不足 / 准入拒绝这些
 * **SSE 场景必须改状态码**的才例外。@Valid 失败属于普通业务错误，
 * 所以**必须保持 200**——改了状态码，前端按 code 判断的逻辑会全线错位。
 *
 * <p>本轮改造把校验从 controller 里的手写 if 挪到 DTO 注解上，
 * **对外契约一个字没改**，这个测试就是锁这件事的。
 *
 * <p>只测**失败路径**：校验不通过时请求根本到不了 service，零副作用；
 * 通过路径会真的写库（注册账号），不该在单测里跑。
 *
 * <p><b>⚠️ 每个请求体都必须把其它字段填成合法值，这不是多余的</b>：
 * {@code GlobalExceptionHandler} 处理 @Valid 失败时**只取第一个字段错误**，
 * 而字段遍历顺序不保证。少填一个字段就多一个违规项，它一旦排在前面，
 * 断言的消息就变成它了——那样测的就不是本条用例想测的东西。
 * 邮箱一律用 {@code @example.com}（RFC 保留域，永远不会真的发出去）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ValidationTests {

    private static final String OK_EMAIL = "tester@example.com";
    private static final String CAPTCHA = "test-only";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void registerRejectsBlankUsername() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"","password":"testpass123","confirmPassword":"testpass123","nickname":"小明","email":"%s","captchaVerification":"%s"}
                                """.formatted(OK_EMAIL, CAPTCHA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value("用户名不能为空"));
    }

    @Test
    void registerRejectsOverlongUsername() throws Exception {
        String tooLong = "u".repeat(51);   // 列宽是 varchar(50)
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"testpass123","confirmPassword":"testpass123","nickname":"小明","email":"%s","captchaVerification":"%s"}
                                """.formatted(tooLong, OK_EMAIL, CAPTCHA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value("用户名最长 50 个字符"));
    }

    /**
     * 邮箱格式：{@code @Email} 只做格式检查，不验证邮箱真实存在。
     * 这条锁的是「别把 {@code @NotBlank} 换成 {@code @Email} 就以为够了」——
     * 空的邮箱对 {@code @Email} 是合法的。
     */
    @Test
    void registerRejectsMalformedEmail() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"someone","password":"testpass123","confirmPassword":"testpass123","nickname":"小明","email":"not-an-email","captchaVerification":"%s"}
                                """.formatted(CAPTCHA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value("邮箱格式不正确"));
    }

    @Test
    void registerRejectsBlankEmail() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"someone","password":"testpass123","confirmPassword":"testpass123","nickname":"小明","email":"","captchaVerification":"%s"}
                                """.formatted(CAPTCHA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value("邮箱不能为空"));
    }

    @Test
    void loginRejectsBlankPassword() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"someone","password":"","captchaVerification":"%s"}
                                """.formatted(CAPTCHA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value("密码不能为空"));
    }

    /**
     * 请求体压根不是合法 JSON：这属于**客户端参数问题**，
     * 修复前会一路落到 Exception 兜底返回 500（「我坏了」），现在归到 40001（「你传错了」）。
     */
    @Test
    void malformedJsonIsClientErrorNotServerError() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-a-json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value("请求体格式不正确"));
    }

    /**
     * 跨字段校验（两次密码不一致）依然由 controller 承担 —— @Valid 的注解作用在单个字段上，
     * 表达不了字段之间的关系。这条锁的是「改造没有顺手把它弄丢」。
     *
     * <p>注意它必须**通过 @Valid**才能走到 controller 的跨字段判断，
     * 所以这里每个字段都得是合法值。
     */
    @Test
    void registerRejectsMismatchedConfirmPassword() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"someone","password":"testpass123","confirmPassword":"testpass456","nickname":"小明","email":"%s","captchaVerification":"%s"}
                                """.formatted(OK_EMAIL, CAPTCHA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value("两次密码不一致！"));
    }
}
