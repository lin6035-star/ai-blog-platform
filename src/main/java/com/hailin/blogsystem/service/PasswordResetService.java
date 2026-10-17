package com.hailin.blogsystem.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.constants.RedisConstants;
import com.hailin.blogsystem.entity.Users;
import com.hailin.blogsystem.exception.BusinessException;
import com.hailin.blogsystem.mapper.LoginMapper;
import com.hailin.blogsystem.security.PasswordResetRateLimiter;
import com.hailin.blogsystem.security.TokenVersionStore;
import com.hailin.blogsystem.utils.EmailUtils;
import com.hailin.blogsystem.utils.PasswordPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

/**
 * 忘记密码：申请重置 + 凭 token 改密。
 *
 * <p><b>这个类的第一原则是「不泄露某个邮箱是否注册过」</b>，所以：
 * <ul>
 *   <li>{@link #requestReset} 无论邮箱存在与否、是不是 GitHub 账号、有没有被限流，
 *       都<b>什么都不返回</b>——调用方永远回同一句话；</li>
 *   <li>发信是异步的，所以两种分支的<b>响应耗时也一致</b>。只做到"文案一样"是不够的：
 *       如果"邮箱存在"这条路径因为要发信慢了 200ms，那个时间差本身就是探针。</li>
 * </ul>
 *
 * <p><b>为什么 token 用随机串而不是 JWT</b>：JWT 签发之后作废不了，而重置凭证必须能
 * 一次性消费、能被主动作废。这里用 32 字节随机数 + Redis 存映射 + <b>Lua 原子取删</b>。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /**
     * 原子「取并删」。
     *
     * <p>先 GET 再 DEL 会有竞态：两个并发请求可能都读到同一个 token，
     * 于是同一个链接能改两次密码——**一次性**这条性质必须由 Redis 侧保证。
     * 不用 {@code GETDEL} 是因为它要求 Redis ≥ 6.2，Lua 没有这个版本约束。
     */
    private static final DefaultRedisScript<String> CONSUME_TOKEN_SCRIPT = new DefaultRedisScript<>(
            """
            local value = redis.call('GET', KEYS[1])
            if value then
                redis.call('DEL', KEYS[1])
            end
            return value
            """,
            String.class
    );

    /**
     * 申请重置的结果。
     *
     * <p><b>只有两个值，而且必须保持只有两个</b>：
     * 任何"这个邮箱怎样怎样"的细分都会立刻变成用户枚举的探针。
     */
    public enum RequestOutcome {
        /**
         * 已受理。**邮箱存在 / 不存在 / 是 GitHub 账号 / 被限流——全部归到这里**，
         * 调用方不得再细分。
         */
        ACCEPTED,
        /**
         * 邮件服务不可用（未配置）。
         *
         * <p>**这是全局状态，与任何用户无关**，所以可以明确告诉调用方——
         * 不说的话就是"界面一片绿、实际什么都没发生"，配置错误只能靠翻日志发现
         * （真实踩过：没配 mail 时点忘密码，界面提示已发送，收件箱什么都没有）。
         */
        MAIL_UNAVAILABLE
    }

    private final LoginMapper loginMapper;
    private final StringRedisTemplate redisTemplate;
    private final MailService mailService;
    private final PasswordResetRateLimiter rateLimiter;
    private final TokenVersionStore tokenVersionStore;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Value("${app.frontend-url}")
    private String frontendUrl;

    /**
     * 申请重置。
     *
     * <p><b>返回值只有"受理"和"邮件不可用"两种</b>，理由见 {@link RequestOutcome}：
     * 前者覆盖所有与用户相关的分支（存在 / 不存在 / GitHub 账号 / 被限流），
     * 后者是全局故障。
     */
    public RequestOutcome requestReset(String rawEmail, String clientIp) {
        String email = EmailUtils.normalize(rawEmail);
        if (email == null) {
            return RequestOutcome.ACCEPTED;
        }

        // 邮件没配是**全局**故障，而且与用户无关，所以放在最前面直接返回——
        // 一是让配置错误立刻可见，二是**不消耗限流配额**：
        // 配好之前用户可能已经点过几次，那些点击不该算进"3 次/15 分钟"里
        if (!mailService.isConfigured()) {
            log.error("[PWD-RESET] 邮件未配置，无法发送重置邮件（配置 MAIL_USERNAME / MAIL_PASSWORD，"
                    + "QQ 邮箱填的是授权码）");
            return RequestOutcome.MAIL_UNAVAILABLE;
        }

        // 限流在查库之前：被限流时不该多查一次库（那点耗时差异也是探针）
        if (!rateLimiter.tryAcquire(email, clientIp)) {
            return RequestOutcome.ACCEPTED;
        }

        Users user = loginMapper.selectOne(new LambdaQueryWrapper<Users>()
                .eq(Users::getEmail, email)
                .eq(Users::getLoginType, "password")
                .last("LIMIT 1"));

        if (user == null) {
            // 两种情况都会走到这里，而且**都必须什么都不做**：
            //   1. 这个邮箱没注册过；
            //   2. 注册过，但那是 GitHub 账号（没有密码可重置）。
            // 对第 2 种情况回一句"你是 GitHub 用户"就等于确认了这个邮箱在站内注册过——
            // 这是刻意的取舍，**不要把它当 bug 修掉**。
            log.debug("[PWD-RESET] 无可用密码账号，按防枚举策略静默返回: {}", mask(email));
            return RequestOutcome.ACCEPTED;
        }

        String token = issueToken(user.getId());
        mailService.sendPasswordResetEmail(email, buildResetLink(token));
        log.info("[PWD-RESET] 已受理重置申请: userId={}", user.getId());
        return RequestOutcome.ACCEPTED;
    }

    /**
     * 凭 token 改密。成功后**该用户所有已签发的 token 立即失效**（踢掉全部设备）。
     *
     * <p>用 {@link BlogConstants.ErrorCode#PASSWORD_RESET_INVALID} 而不是参数错误：
     * 前端据此给出"重新申请一次"的动作，而不是让用户对着一个死链接反复重试。
     */
    public void resetPassword(String token, String newPassword) {
        if (!StringUtils.hasText(token)) {
            throw invalidLink();
        }

        // 与注册同一套策略：重置时能设弱密码的话，注册侧刚立的规矩转头就被绕过去了
        String weakReason = PasswordPolicy.validate(newPassword);
        if (weakReason != null) {
            throw new BusinessException(BlogConstants.ErrorCode.BAD_REQUEST, weakReason);
        }

        String userIdText = redisTemplate.execute(
                CONSUME_TOKEN_SCRIPT,
                List.of(RedisConstants.PWD_RESET_TOKEN_KEY_PREFIX + token));

        if (userIdText == null) {
            // 已过期 / 已被用过 / 压根不存在 —— 三种情况同一个响应
            throw invalidLink();
        }

        long userId;
        try {
            userId = Long.parseLong(userIdText);
        } catch (NumberFormatException e) {
            // 缓存里被塞了非法值：当成失效处理，不要抛 500 把内部细节露出去
            log.error("[PWD-RESET] 凭证对应的 userId 非法: {}", userIdText);
            throw invalidLink();
        }

        Users user = loginMapper.selectById(userId);
        if (user == null) {
            throw invalidLink();
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setUpdatedAt(LocalDateTime.now());
        loginMapper.updateById(user);

        // 改密后旧 token 必须全部作废：攻击者如果已经拿到某台设备上的 token，
        // 光改密码是踢不掉他的（JWT 无状态，有效到自然过期）
        tokenVersionStore.bump(userId);

        log.info("[PWD-RESET] 密码已重置，该用户全部旧 token 已作废: userId={}", userId);
    }

    /** 32 字节随机 + Base64URL（43 字符）：爆破空间足够，也不是那种会被截断的格式 */
    private String issueToken(Long userId) {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        redisTemplate.opsForValue().set(
                RedisConstants.PWD_RESET_TOKEN_KEY_PREFIX + token,
                String.valueOf(userId),
                Duration.ofMinutes(RedisConstants.PWD_RESET_TOKEN_TTL_MINUTES));

        return token;
    }

    private String buildResetLink(String token) {
        String base = frontendUrl.endsWith("/")
                ? frontendUrl.substring(0, frontendUrl.length() - 1)
                : frontendUrl;
        return base + "/reset-password?token=" + token;
    }

    private BusinessException invalidLink() {
        return new BusinessException(BlogConstants.ErrorCode.PASSWORD_RESET_INVALID,
                "链接已失效，请重新申请");
    }

    /** 日志里不打印完整邮箱（PII，日志会长期保留） */
    private String mask(String email) {
        int at = email.indexOf('@');
        return at <= 0 ? "***" : email.charAt(0) + "***" + email.substring(at);
    }
}
