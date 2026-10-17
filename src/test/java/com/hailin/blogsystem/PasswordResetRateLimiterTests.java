package com.hailin.blogsystem;

import com.hailin.blogsystem.security.PasswordResetRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 忘记密码的申请限流：两个维度各自独立生效，Redis 故障时 fail-open。
 */
@ExtendWith(MockitoExtension.class)
class PasswordResetRateLimiterTests {

    @Mock
    private StringRedisTemplate redisTemplate;

    private PasswordResetRateLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new PasswordResetRateLimiter(redisTemplate);
    }

    @Test
    void withinLimitIsAllowed() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString())).thenReturn(3L);

        assertThat(limiter.tryAcquire("a@b.com", "1.2.3.4")).isTrue();
    }

    /** 邮箱维度：第 4 次起拒绝（阈值 3）——防的是"把别人的邮箱当邮件轰炸机" */
    @Test
    void emailDimensionRejectsAboveItsThreshold() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString())).thenReturn(4L);

        assertThat(limiter.tryAcquire("a@b.com", "1.2.3.4")).isFalse();
    }

    /**
     * IP 维度阈值宽得多（10），而且**与邮箱维度独立**：
     * 同一个邮箱第 1 次就命中邮箱维度，但"每个邮箱只试一两次"的扫库行为
     * 只能被 IP 维度看见——这条模拟的就是后者。
     */
    @Test
    void ipDimensionRejectsAboveItsOwnThreshold() {
        // 第一次调用（邮箱维度）返回 1 = 未超限；第二次（IP 维度）返回 11 = 超限
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString())).thenReturn(1L, 11L);

        assertThat(limiter.tryAcquire("a@b.com", "1.2.3.4")).isFalse();
    }

    /** Redis 故障 fail-open：拒绝会让 Redis 一抖 = 全站找不回密码 */
    @Test
    void redisFailureFailsOpen() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString()))
                .thenThrow(new RuntimeException("redis down"));

        assertThat(limiter.tryAcquire("a@b.com", "1.2.3.4")).isTrue();
    }

    /** 拿不到 IP 时降级为只做邮箱维度，不能退化成"所有空 IP 共用一个键" */
    @Test
    void blankIpSkipsIpDimension() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString())).thenReturn(1L);

        assertThat(limiter.tryAcquire("a@b.com", "  ")).isTrue();

        // 只调了一次（邮箱维度）
        org.mockito.Mockito.verify(redisTemplate, org.mockito.Mockito.times(1))
                .execute(any(RedisScript.class), anyList(), anyString());
    }

    /** 两个维度的 key 前缀不同，不能混用一个桶 */
    @Test
    void bothDimensionsUseSeparateKeys() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString())).thenReturn(1L);

        limiter.tryAcquire("a@b.com", "1.2.3.4");

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<String>> keys = org.mockito.ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(redisTemplate, org.mockito.Mockito.times(2))
                .execute(any(RedisScript.class), keys.capture(), anyString());

        assertThat(keys.getAllValues().get(0).get(0)).isEqualTo("pwd:reset:rate:email:a@b.com");
        assertThat(keys.getAllValues().get(1).get(0)).isEqualTo("pwd:reset:rate:ip:1.2.3.4");
    }
}
