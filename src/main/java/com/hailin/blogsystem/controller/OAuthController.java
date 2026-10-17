package com.hailin.blogsystem.controller;

import com.hailin.blogsystem.exception.BusinessException;
import com.hailin.blogsystem.service.GitHubOAuthService;
import com.hailin.blogsystem.utils.Result;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class OAuthController {

    private final GitHubOAuthService gitHubOAuthService;

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
     */
    @GetMapping("/github/callback")
    public void handleCallback(@RequestParam String code,
                               @RequestParam(required = false) String state,
                               HttpServletResponse response) throws IOException {
        try {
            String token = gitHubOAuthService.handleCallback(code, state);
            response.sendRedirect(frontendUrl + "/auth/github/callback?token=" + token);
        } catch (BusinessException e) {
            response.sendRedirect(frontendUrl + "/auth/github/callback?error="
                    + URLEncoder.encode(e.getMessage(), StandardCharsets.UTF_8));
        }
    }
}
