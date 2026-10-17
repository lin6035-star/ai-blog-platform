package com.hailin.blogsystem.security;

import cloud.tianai.captcha.application.ImageCaptchaApplication;
import cloud.tianai.captcha.spring.plugins.secondary.SecondaryVerificationApplication;
import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 验证码「二次验证」的消费端：登录 / 注册必须持有一次有效的验证凭证。
 *
 * <p><b>为什么必须有这一层</b>：{@code /api/auth/captcha/check} 只证明「这条滑动轨迹算得上对」，
 * 它由前端主动调用。如果业务接口不校验，攻击者**绕过前端直接 POST /api/auth/login**
 * 就完全跳过了验证码——滑块再难也拦不住他。所以验证通过后要留一条一次性记录，
 * 业务侧必须来消费它。
 *
 * <p><b>凭证就是 captchaId 本身</b>，不是另发的 token：starter 的
 * {@code SecondaryVerificationApplication.matching()} 在匹配成功时写入
 * {@code {keyPrefix}:{id}}，{@code secondaryVerification(id)} 用 {@code getAndRemove} 读——
 * <b>天然一次性</b>，用完即删，重放同一个 id 会失败。
 *
 * <p><b>Redis 故障时 fail-open（放行）+ ERROR 日志</b>：与 {@link LoginAttemptLimiter}
 * 同一取舍。拒绝会让 Redis 一抖就等于全站登不进去；而放行的风险有「外层还有一道
 * 登录失败次数限制 + 每次约 100ms 的 bcrypt」兜底。刻意不做成配置开关——
 * 这里没有「两种都合理」的情形，留开关只是给人配错的机会。
 *
 * <p><b>构造期自检</b>：二次验证只有在 {@code captcha.secondary.enabled=true} 时才生效，
 * 而配成 false 的表现是「登录永远报验证码失效」——排查起来毫无线索。所以在构造器里
 * 直接 fail-fast，把配置错误挡在启动阶段（与 {@code BillingReserveLimitValidator} 同一思路）。
 */
@Slf4j
@Component
public class CaptchaVerifier {

    private final SecondaryVerificationApplication secondaryVerification;

    public CaptchaVerifier(ImageCaptchaApplication imageCaptchaApplication) {
        if (!(imageCaptchaApplication instanceof SecondaryVerificationApplication secondary)) {
            throw new IllegalStateException("""
                    验证码二次验证未启用：请把 captcha.secondary.enabled 配成 true。
                    关掉它等于业务接口不再校验验证码——攻击者绕过前端直接调登录接口即可，
                    滑块拦不住任何人。""");
        }
        this.secondaryVerification = secondary;
    }

    /**
     * 消费一次验证凭证，失败抛 {@link BlogConstants.ErrorCode#CAPTCHA_INVALID}。
     *
     * <p>调用点必须排在<b>登录失败计数（checkBlocked）之前</b>：
     * 验证码没过不代表密码错，不该计入失败次数——否则任何人拿别人的用户名
     * 连续提交无效凭证，就能把真实用户的账号锁死 15 分钟。
     */
    public void verify(String captchaId) {
        if (!StringUtils.hasText(captchaId)) {
            throw new BusinessException(BlogConstants.ErrorCode.CAPTCHA_INVALID, "请先完成安全验证");
        }

        boolean passed;
        try {
            passed = secondaryVerification.secondaryVerification(captchaId);
        } catch (RuntimeException e) {
            log.error("[CAPTCHA] Redis 故障，fail-open 放行: captchaId={}", captchaId, e);
            return;
        }

        if (!passed) {
            throw new BusinessException(BlogConstants.ErrorCode.CAPTCHA_INVALID,
                    "验证已失效，请重新滑动验证");
        }
    }
}
