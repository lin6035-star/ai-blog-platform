package com.hailin.blogsystem.interceptor;

import com.hailin.blogsystem.security.TokenBlacklist;
import com.hailin.blogsystem.security.TokenVersionStore;
import com.hailin.blogsystem.utils.JwtUtil;
import com.hailin.blogsystem.utils.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.AsyncHandlerInterceptor;

/**
 * 公开接口的可选登录态：有有效 token 就写入 UserContext，没有 token 仍按游客放行。
 */
@Component
@RequiredArgsConstructor
public class OptionalJwtInterceptor implements AsyncHandlerInterceptor {

    private final JwtUtil jwtUtil;
    private final TokenBlacklist tokenBlacklist;
    private final TokenVersionStore tokenVersionStore;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) {
        UserContext.clear();
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return true;
        }

        String token = authHeader.substring(7);
        try {
            JwtUtil.Payload payload = jwtUtil.parsePayload(token);
            // 已登出、或改密后代次已过期的 token：公开接口不报错，但不能再以这个身份做写操作
            boolean stale = tokenBlacklist.isRevoked(payload.jti())
                    || payload.tokenVersion() != tokenVersionStore.current(payload.userId());
            if (stale) {
                UserContext.clear();
            } else {
                UserContext.set(payload.userId());
            }
        } catch (Exception ignored) {
            UserContext.clear();
        }

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserContext.clear();
    }

    @Override
    public void afterConcurrentHandlingStarted(HttpServletRequest request,
                                               HttpServletResponse response,
                                               Object handler) {
        UserContext.clear();
    }
}
