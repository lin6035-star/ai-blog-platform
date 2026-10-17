package com.hailin.blogsystem;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.entity.Users;
import com.hailin.blogsystem.exception.BusinessException;
import com.hailin.blogsystem.mapper.LoginMapper;
import com.hailin.blogsystem.security.PasswordResetRateLimiter;
import com.hailin.blogsystem.security.TokenVersionStore;
import com.hailin.blogsystem.service.MailService;
import com.hailin.blogsystem.service.PasswordResetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 忘记密码。
 *
 * <p>这组用例真正在守的只有一件事：<b>「申请」这一步在任何输入下都不泄露信息</b>——
 * 不返回值、不抛异常、不发信（除了该发的那一种）。因为一旦某个分支能"表现不同"，
 * 它就立刻变成一个"这个邮箱注册过吗"的探针。
 */
@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTests {

    private static final String EMAIL = "user@example.com";
    private static final String IP = "1.2.3.4";

    @Mock
    private LoginMapper loginMapper;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private MailService mailService;

    @Mock
    private PasswordResetRateLimiter rateLimiter;

    @Mock
    private TokenVersionStore tokenVersionStore;

    private PasswordResetService service;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        service = new PasswordResetService(loginMapper, redisTemplate, mailService, rateLimiter, tokenVersionStore);
        ReflectionTestUtils.setField(service, "frontendUrl", "http://localhost:5173");
    }

    // ===== 申请：所有分支都必须「静默」 =====

    /** 邮箱没注册过 → 什么都不做，且不抛异常 */
    @Test
    void unknownEmailIsSilentlyIgnored() {
        when(rateLimiter.tryAcquire(anyString(), any())).thenReturn(true);
        when(mailService.isConfigured()).thenReturn(true);
        when(loginMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        assertThat(service.requestReset(EMAIL, IP))
                .isEqualTo(PasswordResetService.RequestOutcome.ACCEPTED);

        verify(mailService, never()).sendPasswordResetEmail(anyString(), anyString());
    }

    /**
     * 被限流 → 同样静默，而且**不该去查库**。
     *
     * <p>"被限流就不查库"不只是省一次 IO：如果限流分支和正常分支的耗时不一样，
     * 那个差异本身就是探针。限流放在查库之前，两条路径的库访问次数才一致。
     */
    @Test
    void rateLimitedRequestIsSilentlyIgnored() {
        // 邮件必须先算"配好了"，否则会先撞上 MAIL_UNAVAILABLE——
        // 那个检查刻意排在限流之前（不消耗配额），见 unconfiguredMailReportsUnavailable
        when(mailService.isConfigured()).thenReturn(true);
        when(rateLimiter.tryAcquire(anyString(), any())).thenReturn(false);

        assertThat(service.requestReset(EMAIL, IP))
                .isEqualTo(PasswordResetService.RequestOutcome.ACCEPTED);

        verify(loginMapper, never()).selectOne(any(Wrapper.class));
        verify(mailService, never()).sendPasswordResetEmail(anyString(), anyString());
    }

    /**
     * 邮件没配 → 明确返回 {@code MAIL_UNAVAILABLE}，**而不是**假装受理。
     *
     * <p>它是**全局**故障，与"某个邮箱是否注册过"无关，所以说出来不削弱防枚举；
     * 反过来说，不说的话用户会看到"已发送"却什么都没发生——真实踩过。
     */
    @Test
    void unconfiguredMailReportsUnavailable() {
        when(mailService.isConfigured()).thenReturn(false);

        assertThat(service.requestReset(EMAIL, IP))
                .isEqualTo(PasswordResetService.RequestOutcome.MAIL_UNAVAILABLE);

        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
        verify(mailService, never()).sendPasswordResetEmail(anyString(), anyString());
        // 也不该消耗限流配额：配好之前点的那几次，不该算进"3 次 / 15 分钟"
        verify(rateLimiter, never()).tryAcquire(anyString(), anyString());
    }

    @Test
    void blankEmailIsIgnored() {
        assertThat(service.requestReset("   ", IP))
                .isEqualTo(PasswordResetService.RequestOutcome.ACCEPTED);

        verify(rateLimiter, never()).tryAcquire(anyString(), anyString());
    }

    /** 正常路径：邮箱归一化后进限流键、生成 token、发信，链接指向配置的前端地址 */
    @Test
    void existingAccountGetsResetMail() {
        when(rateLimiter.tryAcquire(anyString(), any())).thenReturn(true);
        when(mailService.isConfigured()).thenReturn(true);
        Users user = new Users();
        user.setId(7L);
        when(loginMapper.selectOne(any(Wrapper.class))).thenReturn(user);

        service.requestReset("  User@Example.COM ", IP);

        // 归一化后再用（否则 A@x.com 与 a@x.com 会走两个限流桶）
        verify(rateLimiter).tryAcquire(EMAIL, IP);

        ArgumentCaptor<String> linkCaptor = ArgumentCaptor.forClass(String.class);
        verify(mailService).sendPasswordResetEmail(eq(EMAIL), linkCaptor.capture());
        assertThat(linkCaptor.getValue()).startsWith("http://localhost:5173/reset-password?token=");

        // token 落 Redis 且带 TTL
        verify(valueOperations).set(anyString(), eq("7"), any(Duration.class));
    }

    // ===== 重置：一次性 + 改密后作废全部旧 token =====

    @Test
    void invalidTokenIsRejected() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList())).thenReturn(null);

        assertThatThrownBy(() -> service.resetPassword("bad-token", "newpass123"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(BlogConstants.ErrorCode.PASSWORD_RESET_INVALID));
    }

    @Test
    void blankTokenIsRejectedWithoutTouchingRedis() {
        assertThatThrownBy(() -> service.resetPassword("  ", "newpass123"))
                .isInstanceOf(BusinessException.class);

        verify(redisTemplate, never()).execute(any(DefaultRedisScript.class), anyList());
    }

    /**
     * 成功路径：改成新哈希 + **作废该用户所有旧 token**。
     *
     * <p>后半句是这条用例的重点：只改密码不作废 token，等于「攻击者已经拿到 token 的那台
     * 设备照样能用」——用户以为改完密码就安全了，实际没有。
     */
    @Test
    void validTokenChangesHashAndBumpsTokenVersion() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList())).thenReturn("7");
        Users user = new Users();
        user.setId(7L);
        user.setPasswordHash("$2a$10$oldhasholdhasholdhasholdhash");
        when(loginMapper.selectById(7L)).thenReturn(user);

        service.resetPassword("good-token", "newpass123");

        verify(loginMapper).updateById(any(Users.class));
        assertThat(user.getPasswordHash()).isNotEqualTo("$2a$10$oldhasholdhasholdhasholdhash");
        assertThat(user.getPasswordHash()).startsWith("$2");   // BCrypt
        verify(tokenVersionStore).bump(7L);
    }

    /** 凭证对应的用户已被软删 → 按失效处理，不要抛 500 把内部状态露出去 */
    @Test
    void missingUserIsTreatedAsInvalidLink() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList())).thenReturn("7");
        when(loginMapper.selectById(7L)).thenReturn(null);

        assertThatThrownBy(() -> service.resetPassword("good-token", "newpass123"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(BlogConstants.ErrorCode.PASSWORD_RESET_INVALID));
    }
}
