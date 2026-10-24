package com.hailin.blogsystem;

import com.hailin.blogsystem.entity.Users;
import com.hailin.blogsystem.mapper.LoginMapper;
import com.hailin.blogsystem.security.TokenVersionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * token 代次的读写口。
 *
 * <p>重点锁两条**失败模式**，它们都不会让别的测试变红：
 * 1. 缓存不可用时**不能退化成 0**——那正好等于放行所有旧代次的 token，把这道闸变成摆设；
 * 2. 用户不存在（软删）时返回哨兵值而不是 0，否则 ver=0 的老 token 还能用。
 */
@ExtendWith(MockitoExtension.class)
class TokenVersionStoreTests {

    private static final String KEY = "user:token:ver:1";

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private LoginMapper loginMapper;

    private TokenVersionStore store;

    @BeforeEach
    void setUp() {
        // lenient：不是每个用例都读缓存（null userId / bump 的用例）
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        store = new TokenVersionStore(redisTemplate, loginMapper);
    }

    @Test
    void cacheHitSkipsDatabase() {
        when(valueOperations.get(KEY)).thenReturn("3");

        assertThat(store.current(1L)).isEqualTo(3);
        verify(loginMapper, never()).selectById(any());
    }

    @Test
    void cacheMissLoadsFromDatabaseAndBackfillsCache() {
        when(valueOperations.get(KEY)).thenReturn(null);
        when(loginMapper.selectById(1L)).thenReturn(userWithVersion(5));

        assertThat(store.current(1L)).isEqualTo(5);
        verify(valueOperations).set(eq(KEY), eq("5"), any(Duration.class));
    }

    /**
     * 缓存读失败时回源 DB，**而不是放行**。
     *
     * <p>这条是整套机制的底线：如果这里 fail-open 成 0，用户改完密码后
     * 只要 Redis 抖一下，他所有旧 token 就全部复活，而且没有任何迹象。
     */
    @Test
    void cacheFailureFallsBackToDatabaseRatherThanZero() {
        when(valueOperations.get(KEY)).thenThrow(new RuntimeException("redis down"));
        when(loginMapper.selectById(1L)).thenReturn(userWithVersion(7));

        assertThat(store.current(1L)).isEqualTo(7);
    }

    /** 软删的用户返回哨兵 -1：与任何 token 的 ver 都不相等，等于拒绝 */
    @Test
    void missingUserReturnsSentinelThatNeverMatches() {
        when(valueOperations.get(KEY)).thenReturn(null);
        when(loginMapper.selectById(1L)).thenReturn(null);

        assertThat(store.current(1L)).isEqualTo(-1);
    }

    @Test
    void nullUserIdIsRejected() {
        assertThat(store.current(null)).isEqualTo(-1);
        verifyNoInteractions(loginMapper);
    }

    @Test
    void nullColumnValueIsTreatedAsZero() {
        when(valueOperations.get(KEY)).thenReturn(null);
        when(loginMapper.selectById(1L)).thenReturn(userWithVersion(null));

        assertThat(store.current(1L)).isZero();
    }

    /** bump 走 DB 自增（并发改密不丢计数）+ 删缓存 */
    @Test
    void bumpIncrementsAndDropsCache() {
        store.bump(1L);

        verify(loginMapper).update(isNull(), any());
        verify(redisTemplate).delete(KEY);
    }

    /** 删缓存失败不能把异常抛给调用方：它影响的是"最迟多久生效"，不是"会不会生效" */
    @Test
    void bumpSurvivesCacheDeleteFailure() {
        when(redisTemplate.delete(anyString())).thenThrow(new RuntimeException("redis down"));

        assertThatCode(() -> store.bump(1L)).doesNotThrowAnyException();
    }

    @Test
    void nullUserBumpIsNoop() {
        store.bump(null);

        verifyNoInteractions(loginMapper);
        verifyNoInteractions(redisTemplate);
    }

    private Users userWithVersion(Integer version) {
        Users user = new Users();
        user.setId(1L);
        user.setTokenVersion(version);
        return user;
    }
}
