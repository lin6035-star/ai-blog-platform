package com.hailin.blogsystem;

import com.hailin.blogsystem.interceptor.JwtInterceptor;
import com.hailin.blogsystem.interceptor.OptionalJwtInterceptor;
import com.hailin.blogsystem.security.TokenBlacklist;
import com.hailin.blogsystem.security.TokenVersionStore;
import com.hailin.blogsystem.utils.JwtUtil;
import com.hailin.blogsystem.utils.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthInterceptorUserContextTests {

    private JwtUtil jwtUtil;
    private TokenBlacklist tokenBlacklist;
    private TokenVersionStore tokenVersionStore;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private Object handler;
    private OptionalJwtInterceptor optional;
    private JwtInterceptor required;

    @BeforeEach
    void setUp() throws Exception {
        jwtUtil = mock(JwtUtil.class);
        tokenBlacklist = mock(TokenBlacklist.class);
        tokenVersionStore = mock(TokenVersionStore.class);
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        handler = new Object();
        optional = new OptionalJwtInterceptor(jwtUtil, tokenBlacklist, tokenVersionStore);
        required = new JwtInterceptor(jwtUtil, tokenBlacklist, tokenVersionStore);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void optionalRequestWithoutHeaderClearsReusedThread() {
        UserContext.set(99L);
        when(request.getHeader("Authorization")).thenReturn(null);

        assertThat(optional.preHandle(request, response, handler)).isTrue();
        assertThat(UserContext.get()).isNull();
    }

    @Test
    void optionalRequestWithInvalidTokenClearsReusedThread() {
        UserContext.set(99L);
        when(request.getHeader("Authorization")).thenReturn("Bearer invalid");
        when(jwtUtil.parsePayload("invalid")).thenThrow(new IllegalArgumentException("invalid"));

        assertThat(optional.preHandle(request, response, handler)).isTrue();
        assertThat(UserContext.get()).isNull();
    }

    @Test
    void optionalRequestWithRevokedTokenClearsReusedThread() {
        UserContext.set(99L);
        when(request.getHeader("Authorization")).thenReturn("Bearer revoked");
        when(jwtUtil.parsePayload("revoked"))
                .thenReturn(new JwtUtil.Payload(12L, "revoked-jti", 0, 1L));
        when(tokenBlacklist.isRevoked("revoked-jti")).thenReturn(true);

        assertThat(optional.preHandle(request, response, handler)).isTrue();
        assertThat(UserContext.get()).isNull();
    }

    @Test
    void requiredRequestWithoutHeaderClearsReusedThread() throws Exception {
        UserContext.set(99L);
        when(request.getHeader("Authorization")).thenReturn(null);

        assertThat(required.preHandle(request, response, handler)).isFalse();
        assertThat(UserContext.get()).isNull();
    }

    @Test
    void asyncHandoffClearsOriginalThread() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer valid");
        when(jwtUtil.parsePayload("valid"))
                .thenReturn(new JwtUtil.Payload(12L, "valid-jti", 0, 1L));
        when(tokenBlacklist.isRevoked("valid-jti")).thenReturn(false);
        when(tokenVersionStore.current(12L)).thenReturn(0);

        assertThat(optional.preHandle(request, response, handler)).isTrue();
        assertThat(UserContext.get()).isEqualTo(12L);

        optional.afterConcurrentHandlingStarted(request, response, handler);

        assertThat(UserContext.get()).isNull();
    }

    /**
     * 代次过期（用户改过密码 / 重置过密码）的 token 必须被拒。
     *
     * <p>这是「换密码后踢掉所有设备」的唯一实现点——黑名单按 jti 只能拉黑单个，
     * 拦不住"用户自己那台没登出的设备"。没有这条断言的话，把代次比对删掉不会有任何测试变红。
     */
    @Test
    void requiredRequestWithStaleTokenVersionIsRejected() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer stale");
        when(jwtUtil.parsePayload("stale"))
                .thenReturn(new JwtUtil.Payload(12L, "stale-jti", 0, 1L));
        when(tokenBlacklist.isRevoked("stale-jti")).thenReturn(false);
        when(tokenVersionStore.current(12L)).thenReturn(3);   // 用户已经改到第 3 代

        assertThat(required.preHandle(request, response, handler)).isFalse();
        assertThat(UserContext.get()).isNull();
    }

    /** 公开接口遇到代次过期的 token：按游客放行（不报错），但身份必须被清掉 */
    @Test
    void optionalRequestWithStaleTokenVersionFallsBackToGuest() {
        UserContext.set(99L);
        when(request.getHeader("Authorization")).thenReturn("Bearer stale");
        when(jwtUtil.parsePayload("stale"))
                .thenReturn(new JwtUtil.Payload(12L, "stale-jti", 0, 1L));
        when(tokenBlacklist.isRevoked("stale-jti")).thenReturn(false);
        when(tokenVersionStore.current(12L)).thenReturn(3);

        assertThat(optional.preHandle(request, response, handler)).isTrue();
        assertThat(UserContext.get()).isNull();
    }
}
