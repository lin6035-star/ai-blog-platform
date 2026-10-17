package com.hailin.blogsystem.controller;

import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.constants.RedisConstants;
import com.hailin.blogsystem.exception.BusinessException;
import com.hailin.blogsystem.service.GitHubOAuthService;
import com.hailin.blogsystem.utils.Result;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class OAuthController {

    private final GitHubOAuthService gitHubOAuthService;
    private final StringRedisTemplate stringRedisTemplate;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    /** 获取 GitHub 授权页 URL，前端拿到来跳转 */
    @GetMapping("/github/url")
    public Result<Map<String, String>> getAuthorizationUrl() {
        String url = gitHubOAuthService.getAuthorizationUrl();
        return Result.success(Map.of("url", url));
    }

    /**
     * GitHub 回调：**先校验 state**，再换 token、拿用户信息、登录/注册、重定向到前端。
     *
     * <p><b>为什么必须校验 state</b>：不校验就存在 **Login CSRF**。攻击者自己走一遍授权流程
     * 拿到 {@code code}，然后把 {@code .../github/callback?code=xxx} 这个链接发给受害者。
     * 受害者一点开就**登录成了攻击者的账号** —— 他之后写的文章、留的评论、充的钱
     * 全记在攻击者名下，攻击者反过来能看到他的一切行为。
     *
     * <p>失败时**重定向回前端并带 error**，而不是让它抛出去变成一坨 JSON：
     * 这个端点是浏览器整页跳转过来的，用户看到的应该是页面而不是错误响应体。
     *
     * <p><b>2026-10-17 上线审计：回跳 URL 不再直接带 JWT</b>。原实现是
     * {@code ?token=<7 天有效的 JWT>}，而 query 会被 Nginx / Cloudflare 的 access log
     * 完整记录、也会留在浏览器历史里——等于把登录态写进日志表。
     * 现在改成一次性 code（60 秒、消费即删），token 只在服务端与前端 exchange 请求体之间传。
     */
    @GetMapping("/github/callback")
    public void handleCallback(@RequestParam String code,
                               @RequestParam(required = false) String state,
                               HttpServletResponse response) throws IOException {
        try {
            String token = gitHubOAuthService.handleCallback(code, state);

            String oneTimeCode = UUID.randomUUID().toString().replace("-", "");
            stringRedisTemplate.opsForValue().set(
                    RedisConstants.OAUTH_ONE_TIME_CODE_KEY_PREFIX + oneTimeCode,
                    token,
                    Duration.ofSeconds(RedisConstants.OAUTH_ONE_TIME_CODE_TTL_SECONDS));

            response.sendRedirect(frontendUrl + "/auth/github/callback?code=" + oneTimeCode);
        } catch (BusinessException e) {
            response.sendRedirect(frontendUrl + "/auth/github/callback?error="
                    + URLEncoder.encode(e.getMessage(), StandardCharsets.UTF_8));
        }
    }

    /**
     * 用一次性 code 换 JWT（OAuth 回调页调用）。
     *
     * <p>为什么需要单独一个端点：回调是浏览器整页跳转，响应体无法被前端 JS 读取，
     * 所以由前端回调页带着 code 调这里换 token。
     *
     * <p>{@code getAndDelete} 原子消费：重放必然拿不到；即使 code 泄漏，窗口也只有 60 秒。
     * 这个端点本身不需要登录（它就是登录流程的一部分），安全性由「一次性 + 短 TTL」保证。
     */
    @PostMapping("/github/exchange")
    public Result<Map<String, String>> exchange(@RequestBody Map<String, String> body) {
        String code = body == null ? null : body.get("code");
        if (code == null || code.isBlank()) {
            throw new BusinessException(BlogConstants.ErrorCode.BAD_REQUEST, "登录凭证不能为空");
        }

        String token = stringRedisTemplate.opsForValue()
                .getAndDelete(RedisConstants.OAUTH_ONE_TIME_CODE_KEY_PREFIX + code);
        if (token == null) {
            throw new BusinessException(BlogConstants.ErrorCode.BAD_REQUEST,
                    "登录凭证已失效，请重新登录");
        }

        return Result.success(Map.of("token", token));
    }
}
