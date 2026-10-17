package com.hailin.blogsystem.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.security.TokenBlacklist;
import com.hailin.blogsystem.security.TokenVersionStore;
import com.hailin.blogsystem.utils.JwtUtil;
import com.hailin.blogsystem.utils.Result;
import com.hailin.blogsystem.utils.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.AsyncHandlerInterceptor;

import java.io.IOException;

/**
 * JWT 登录拦截器：从 Authorization 头提取 token，验证后写入 UserContext。
 * 只拦截需要登录的接口，公开接口由 WebMvcConfig 放行。
 */
@Component
@RequiredArgsConstructor
public class JwtInterceptor implements AsyncHandlerInterceptor {

    private final JwtUtil jwtUtil;
    private final TokenBlacklist tokenBlacklist;
    private final TokenVersionStore tokenVersionStore;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        UserContext.clear();
        String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            writeUnauthorized(response, "未登录，请先登录");
            return false;
        }

        String token = authHeader.substring(7);
        try {
            JwtUtil.Payload payload = jwtUtil.parsePayload(token);
            // 签名与过期都对，但可能已经登出——JWT 无状态，这是唯一能提前作废的入口
            if (tokenBlacklist.isRevoked(payload.jti())) {
                writeUnauthorized(response, "登录已失效，请重新登录");
                return false;
            }
            // 代次比对：改密 / 重置密码后，该用户**已签发的全部** token 立即失效。
            // 黑名单只能按 jti 拉黑单个，批量作废只能靠它（见 TokenVersionStore）
            if (payload.tokenVersion() != tokenVersionStore.current(payload.userId())) {
                writeUnauthorized(response, "登录已失效，请重新登录");
                return false;
            }
            UserContext.set(payload.userId());
            return true;
        } catch (Exception e) {
            writeUnauthorized(response, "token 无效或已过期，请重新登录");
            return false;
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        // 请求结束时清除 ThreadLocal，防止内存泄漏
        UserContext.clear();
    }

    @Override
    public void afterConcurrentHandlingStarted(HttpServletRequest request,
                                               HttpServletResponse response,
                                               Object handler) {
        UserContext.clear();
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setContentType("application/json;charset=UTF-8");
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        Result<Void> result = Result.error(BlogConstants.ErrorCode.UNAUTHORIZED, message);
        response.getWriter().write(objectMapper.writeValueAsString(result));
    }
}
