package com.hailin.blogsystem.security;

import com.hailin.blogsystem.constants.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 「忘记密码」申请接口的限流。
 *
 * <p><b>两个维度，防的不是同一件事</b>：
 * <ul>
 *   <li><b>邮箱维度（严：3 次 / 15 分钟）</b>——防**把别人的邮箱当邮件轰炸机**。
 *       不限的话，攻击者拿你的邮箱连点，你的收件箱就被重置邮件淹没。</li>
 *   <li><b>IP 维度（宽：10 次 / 15 分钟）</b>——防**拿一份邮箱列表扫库**。
 *       每个邮箱只试一次时邮箱维度攒不到阈值，只有 IP 维度看得见。</li>
 * </ul>
 *
 * <p><b>超限时对外仍然是"已发送"</b>（由调用方保证）：一旦限流返回不同的响应，
 * 攻击者立刻多出一个探针——"这个邮箱被限流了"本身就说明有人在用它。
 *
 * <p><b>Redis 故障时 fail-open（放行）+ ERROR 日志</b>：与
 * {@link LoginAttemptLimiter} / {@code AiRateLimiter} 同一取舍 ——
 * 拒绝会让 Redis 一抖 = 全站找不回密码。
 */
@Slf4j
@Component
public class PasswordResetRateLimiter {

    /** 邮箱维度：第 MAX_EMAIL_REQUESTS + 1 次起拒绝 */
    private static final int MAX_EMAIL_REQUESTS = 3;
    /** IP 维度：宽得多，正常家庭 / 公司出口不该被它拦下 */
    private static final int MAX_IP_REQUESTS = 10;
    /** 窗口（秒） */
    private static final long WINDOW_SECONDS = 900L;

    /**
     * INCR + 首次 EXPIRE 在一条脚本里完成。
     *
     * <p>分两次调用的话，INCR 成功而 EXPIRE 失败（或进程崩在中间）→ 计数键**永不过期** →
     * 这个邮箱**再也发不出重置邮件**，而且重启也不恢复。
     * 脚本正文与 {@link LoginAttemptLimiter} 里那份字节相同但**刻意不共享**：
     * 那个是"失败才计数、成功要清零"，这个是"每次请求都计数"，契约不同、演化方向也不同。
     */
    private static final DefaultRedisScript<Long> ACQUIRE_SCRIPT = new DefaultRedisScript<>(
            """
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return current
            """,
            Long.class
    );

    private final StringRedisTemplate redisTemplate;

    public PasswordResetRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 尝试占用一次配额。**任一维度超限即返回 false**，且这一维度的计数已经 +1
     * （窗口内继续被拒，不会因为"被拒就不计数"而给攻击者留空隙）。
     *
     * <p>Redis 故障时返回 true（放行），理由见类注释。
     */
    public boolean tryAcquire(String email, String clientIp) {
        try {
            if (StringUtils.hasText(email)) {
                Long count = incr(RedisConstants.PWD_RESET_RATE_EMAIL_KEY_PREFIX + email);
                if (count != null && count > MAX_EMAIL_REQUESTS) {
                    log.warn("[PWD-RESET-LIMIT] 邮箱维度超限（{} 次 / {} 分钟），已拒绝: {}",
                            count, WINDOW_SECONDS / 60, mask(email));
                    return false;
                }
            }

            if (StringUtils.hasText(clientIp)) {
                Long count = incr(RedisConstants.PWD_RESET_RATE_IP_KEY_PREFIX + clientIp);
                if (count != null && count > MAX_IP_REQUESTS) {
                    log.warn("[PWD-RESET-LIMIT] IP 维度超限（{} 次 / {} 分钟），已拒绝: {}",
                            count, WINDOW_SECONDS / 60, clientIp);
                    return false;
                }
            }

            return true;
        } catch (RuntimeException e) {
            log.error("[PWD-RESET-LIMIT] Redis 故障，fail-open 放行: ip={}", clientIp, e);
            return true;
        }
    }

    private Long incr(String key) {
        return redisTemplate.execute(ACQUIRE_SCRIPT, List.of(key), String.valueOf(WINDOW_SECONDS));
    }

    /**
     * 日志里不打印完整邮箱。
     *
     * <p>限流日志会落盘并且长期保留，而邮箱是 PII —— 打全量等于把"谁在用这个站"
     * 顺手写进日志。只留首字符和域名，够定位问题。
     */
    private String mask(String email) {
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}
