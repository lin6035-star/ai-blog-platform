package com.hailin.blogsystem.security;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hailin.blogsystem.constants.RedisConstants;
import com.hailin.blogsystem.entity.Users;
import com.hailin.blogsystem.mapper.LoginMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * token 代次（{@code users.token_version}）的读写口。
 *
 * <p><b>它解决什么问题</b>：JWT 是无状态的，签发之后就作废不了。{@link TokenBlacklist}
 * 只能按 {@code jti} 拉黑**单个** token——而"用户改了密码，把他其它设备上的登录全部踢掉"
 * 需要的是**批量作废**，服务端根本不知道这个用户签发过哪些 jti。
 * 所以给每个用户一个计数器 {@code token_version}：签发时写进 payload，
 * 校验时比对；改密时 +1，所有旧 token 立刻不匹配。
 *
 * <p><b>为什么权威值放 DB 而不是 Redis</b>：如果代次只存 Redis，一次 {@code flushdb}
 * 或一次主从切换丢数据，代次就被读回 0——**已经作废的 token 会集体复活**，
 * 而且没有任何迹象。Redis 在这里只是缓存：读不到就回源 DB（权威），
 * 回源失败也**不会**退化成 0（那等于放行），而是记为 -1 拒绝。
 *
 * <p><b>失败模式</b>：
 * <ul>
 *   <li>缓存读不到 / 读失败 → 回源 DB，不 fail-open；</li>
 *   <li>DB 里查不到这个用户（已软删）→ 返回 -1，与任何 token 都不匹配 → 拒绝；</li>
 *   <li>删缓存失败 → 只影响可见延迟，由缓存 TTL 兜底。</li>
 * </ul>
 */
@Slf4j
@Component
public class TokenVersionStore {

    /** 用户不存在（已软删）时的代次：与任何 token 的 ver 都不相等，等于拒绝 */
    private static final int NO_SUCH_USER = -1;

    private final StringRedisTemplate redisTemplate;
    private final LoginMapper loginMapper;

    public TokenVersionStore(StringRedisTemplate redisTemplate, LoginMapper loginMapper) {
        this.redisTemplate = redisTemplate;
        this.loginMapper = loginMapper;
    }

    /**
     * 该用户当前的 token 代次。**权威值在 DB**，Redis 只做缓存。
     *
     * <p>调用点在拦截器里，每个需登录的请求都会走到——命中缓存时是一次 Redis GET，
     * 与已有的黑名单查询同量级。
     */
    public int current(Long userId) {
        if (userId == null) {
            return NO_SUCH_USER;
        }

        String cacheKey = key(userId);
        try {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                return Integer.parseInt(cached);
            }
        } catch (RuntimeException e) {
            // 缓存不可用不是错误：下面回源 DB（权威）。**不能 fail-open 成 0**——
            // 那正好等于放行所有旧代次的 token，把这道闸变成摆设
            log.error("[TOKEN-VER] 缓存读取失败，回源 DB: userId={}", userId, e);
        }

        int version = loadFromDb(userId);
        cache(cacheKey, version);
        return version;
    }

    /**
     * 作废该用户已签发的**全部** token（改密 / 重置密码后调用）。
     *
     * <p>DB 用 {@code token_version = token_version + 1} 的原子自增而不是"读出来 +1 再写回"：
     * 并发改密时后者会丢计数，两个请求都写回同一个值，等于只 +1 了一次。
     */
    public void bump(Long userId) {
        if (userId == null) {
            return;
        }

        loginMapper.update(null, new LambdaUpdateWrapper<Users>()
                .eq(Users::getId, userId)
                .setSql("token_version = token_version + 1"));

        try {
            redisTemplate.delete(key(userId));
        } catch (RuntimeException e) {
            // 删不掉也只是缓存脏一会儿：TTL 到期后自然读到新代次。
            // 这里不做重试——它影响的是"最迟多久生效"，不是"会不会生效"
            log.error("[TOKEN-VER] 代次缓存删除失败，将由 TTL 兜底: userId={}", userId, e);
        }
    }

    private int loadFromDb(Long userId) {
        Users user = loginMapper.selectById(userId);
        if (user == null) {
            // 已软删（@TableLogic 会过滤掉）——不接受任何 token，
            // 而不是返回 0：返回 0 会让"ver=0 的老 token"继续可用
            return NO_SUCH_USER;
        }
        Integer version = user.getTokenVersion();
        return version == null ? 0 : version;
    }

    private void cache(String cacheKey, int version) {
        try {
            redisTemplate.opsForValue().set(cacheKey, String.valueOf(version),
                    Duration.ofMinutes(RedisConstants.USER_TOKEN_VERSION_TTL_MINUTES));
        } catch (RuntimeException e) {
            // 写不进缓存就每次都回源 DB，正确但慢一点，不影响判定
            log.error("[TOKEN-VER] 代次缓存写入失败，后续将回源 DB: key={}", cacheKey, e);
        }
    }

    private String key(Long userId) {
        return RedisConstants.USER_TOKEN_VERSION_KEY_PREFIX + userId;
    }
}
