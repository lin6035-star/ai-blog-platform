package com.hailin.blogsystem.security;

import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.constants.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

/**
 * AI 接口固定时间窗口限流器。
 *
 * Lua 脚本一次完成 INCR + 首次设置 TTL + 返回当前计数，
 * 避免 INCR / EXPIRE 分开调用在并发下计数与 TTL 不一致。
 *
 * Key 结构：ai:rate:{bucket}:{user|ip}:{identity}:{window}
 * bucket = chat / workflow / rag，identity = userId 或 IP。
 *
 * Redis 异常时按 fail-open 配置降级：
 * - failOpen=true：放行（不阻断业务，配额保护失效）
 * - failOpen=false：拒绝（严格模式，抛 RateLimitExceededException）
 */
@Slf4j
@Component
public class AiRateLimiter {

    private static final DefaultRedisScript<Long> INCR_AND_EXPIRE_SCRIPT =
            new DefaultRedisScript<>(
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
    private final BlogAiProperties properties;

    public AiRateLimiter(
            StringRedisTemplate redisTemplate,
            BlogAiProperties properties
    ) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    /**
     * 尝试获取一次配额。
     *
     * @param bucket       限流桶：chat / workflow / rag
     * @param type         身份类型：user / ip
     * @param identity     userId 或 IP
     * @param limit        窗口内限额
     * @param windowSeconds 窗口秒数
     * @return true=放行，false=超限
     */
    public boolean tryAcquire(
            String bucket,
            String type,
            String identity,
            int limit,
            long windowSeconds
    ) {
        String key = RedisConstants.AI_RATE_LIMIT_KEY_PREFIX
                + bucket + ":"
                + type + ":"
                + identity + ":"
                + windowSeconds;

        try {
            Long count = redisTemplate.execute(
                    INCR_AND_EXPIRE_SCRIPT,
                    List.of(key),
                    String.valueOf(windowSeconds)
            );

            long current = count == null ? 0 : count;
            boolean allowed = current <= limit;

            if (!allowed) {
                log.info(
                        "[RATE-LIMIT] rejected bucket={} type={} identity={} count={}/{} window={}s",
                        bucket, type, identity, current, limit, windowSeconds
                );
            }

            return allowed;
        } catch (RuntimeException e) {
            if (properties.getRateLimit().isFailOpen()) {
                log.warn(
                        "[RATE-LIMIT] Redis 故障，fail-open 放行: bucket={}",
                        bucket,
                        e
                );
                return true;
            }

            log.error(
                    "[RATE-LIMIT] Redis 故障，fail-open=false 拒绝: bucket={}",
                    bucket,
                    e
            );
            throw new RateLimitExceededException(windowSeconds);
        }
    }

    /**
     * 日维度配额（游客 AI 日额度 / 全站游客日预算）。
     *
     * <p>与 {@link #tryAcquire} 的关键区别：<b>key 里不含 window 秒数</b>，计数靠 TTL
     * 在次日 0 点自然过期。为什么必须这样：若按 tryAcquire 的拼法把「到次日 0 点的剩余秒数」
     * 拼进 key，同一天不同时刻的请求会落到不同 key，计数被切成碎片，日额度永远攒不满。
     *
     * @param bucket        业务桶名（guest-ip / guest-global）
     * @param identity      维度值（IP 或 all）
     * @param limit         TTL 周期内的限额
     * @param expireSeconds TTL：到次日 0 点的秒数
     * @return true=放行，false=超限
     */
    public boolean tryAcquireDaily(
            String bucket,
            String identity,
            int limit,
            long expireSeconds
    ) {
        String key = RedisConstants.AI_RATE_LIMIT_KEY_PREFIX + "daily:" + bucket + ":" + identity;
        long ttl = expireSeconds <= 0 ? 60 : expireSeconds;

        try {
            Long count = redisTemplate.execute(
                    INCR_AND_EXPIRE_SCRIPT,
                    List.of(key),
                    String.valueOf(ttl)
            );

            long current = count == null ? 0 : count;
            boolean allowed = current <= limit;

            if (!allowed) {
                log.info(
                        "[RATE-LIMIT] daily rejected bucket={} identity={} count={}/{} ttl={}s",
                        bucket, identity, current, limit, ttl
                );
            }

            return allowed;
        } catch (RuntimeException e) {
            if (properties.getRateLimit().isFailOpen()) {
                log.warn("[RATE-LIMIT] Redis 故障，daily fail-open 放行: bucket={}", bucket, e);
                return true;
            }

            log.error("[RATE-LIMIT] Redis 故障，daily fail-open=false 拒绝: bucket={}", bucket, e);
            throw new RateLimitExceededException(ttl);
        }
    }

    /**
     * 记录游客 AI 的估算 token 用量（按自然日累计）。
     *
     * <p>游客不计费，但平台要真金白银付给供应商。改造前游客 usage 被直接丢弃
     * （{@code AiMessageServiceImpl} 里 new 一个 accumulator 用掉就扔），
     * 被刷之后连「刷了多少」都查不到；这里落 Redis 供事后排查与容量预估。
     *
     * <p>统计失败不影响业务：只记 warn。
     */
    public void recordGuestUsage(long tokens) {
        if (tokens <= 0) {
            return;
        }

        try {
            String key = RedisConstants.AI_GUEST_USAGE_KEY_PREFIX + LocalDate.now();
            redisTemplate.opsForValue().increment(key, tokens);
            // 留两天：当天可观测、次日可对比
            redisTemplate.expire(key, Duration.ofDays(2));
        } catch (RuntimeException e) {
            log.warn("[GUEST-AI] 用量记账失败 tokens={}", tokens, e);
        }
    }
}
