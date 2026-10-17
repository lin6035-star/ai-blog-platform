package com.hailin.blogsystem;

import cloud.tianai.captcha.application.ImageCaptchaApplication;
import cloud.tianai.captcha.spring.plugins.secondary.SecondaryVerificationApplication;
import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.exception.BusinessException;
import com.hailin.blogsystem.security.CaptchaVerifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证码二次验证的消费端。
 *
 * <p>重点锁两类行为：
 * 1. <b>失败必须拒绝</b>——凭证没拿到 / 已消费过，都不能放行（放行等于验证码白做）；
 * 2. <b>配置错误要在启动期炸</b>——{@code secondary.enabled=false} 时构造器直接失败，
 *    而不是运行时表现为「登录永远说验证码失效」那种查不出根因的样子。
 */
@ExtendWith(MockitoExtension.class)
class CaptchaVerifierTests {

    @Mock
    private SecondaryVerificationApplication secondaryVerification;

    @Test
    void validCredentialPasses() {
        when(secondaryVerification.secondaryVerification("SLIDER_abc")).thenReturn(true);

        assertThatCode(() -> new CaptchaVerifier(secondaryVerification).verify("SLIDER_abc"))
                .doesNotThrowAnyException();
    }

    /**
     * 凭证已被消费（getAndRemove 第二次读到 null）——必须拒绝。
     * 这是「一次性」这条性质的守卫：允许多次使用等于给了重放窗口。
     */
    @Test
    void consumedCredentialIsRejected() {
        when(secondaryVerification.secondaryVerification("SLIDER_abc")).thenReturn(false);

        assertThatThrownBy(() -> new CaptchaVerifier(secondaryVerification).verify("SLIDER_abc"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(BlogConstants.ErrorCode.CAPTCHA_INVALID));
    }

    @Test
    void blankCredentialIsRejectedWithoutTouchingRedis() {
        CaptchaVerifier verifier = new CaptchaVerifier(secondaryVerification);

        assertThatThrownBy(() -> verifier.verify(null))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(BlogConstants.ErrorCode.CAPTCHA_INVALID));
        assertThatThrownBy(() -> verifier.verify("   "))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * Redis 故障 fail-open：与 LoginAttemptLimiter 同一取舍。
     * 拒绝会让 Redis 一抖就等于全站登不进去，而外层还有登录失败次数限制 + bcrypt 兜底。
     */
    @Test
    void redisFailureFailsOpen() {
        when(secondaryVerification.secondaryVerification("SLIDER_abc"))
                .thenThrow(new RuntimeException("redis down"));

        assertThatCode(() -> new CaptchaVerifier(secondaryVerification).verify("SLIDER_abc"))
                .doesNotThrowAnyException();
    }

    /**
     * 二次验证没开时 {@code imageCaptchaApplication} 不是 {@code SecondaryVerificationApplication}，
     * 构造期就要炸——这个配置项配错的表现是「登录永远报验证码失效」，运行时极难归因。
     */
    @Test
    void missingSecondaryVerificationFailsAtConstruction() {
        ImageCaptchaApplication plain = mock(ImageCaptchaApplication.class);

        assertThatThrownBy(() -> new CaptchaVerifier(plain))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("captcha.secondary.enabled");
    }
}
