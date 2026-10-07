package com.hailin.blogsystem;

import com.hailin.blogsystem.interceptor.JwtInterceptor;
import com.hailin.blogsystem.interceptor.OptionalJwtInterceptor;
import com.hailin.blogsystem.security.TokenBlacklist;
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
    private HttpServletRequest request;
    private HttpServletResponse response;
    private Object handler;
    private OptionalJwtInterceptor optional;
    private JwtInterceptor required;

    @BeforeEach
    void setUp() throws Exception {
        jwtUtil = mock(JwtUtil.class);
        tokenBlacklist = mock(TokenBlacklist.class);
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        handler = new Object();
        optional = new OptionalJwtInterceptor(jwtUtil, tokenBlacklist);
        required = new JwtInterceptor(jwtUtil, tokenBlacklist);
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
                .thenReturn(new JwtUtil.Payload(12L, "revoked-jti", 1L));
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
                .thenReturn(new JwtUtil.Payload(12L, "valid-jti", 1L));
        when(tokenBlacklist.isRevoked("valid-jti")).thenReturn(false);

        assertThat(optional.preHandle(request, response, handler)).isTrue();
        assertThat(UserContext.get()).isEqualTo(12L);

        optional.afterConcurrentHandlingStarted(request, response, handler);

        assertThat(UserContext.get()).isNull();
    }
}
