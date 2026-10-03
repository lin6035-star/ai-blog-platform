package com.hailin.blogsystem.ai.rag.index;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 索引重建的全局互斥锁。
 *
 * <p>重建是「建新物理索引 → 写全量 → canary 校验 → 切 alias」的长流程（实测数十秒），
 * 并发执行会让两个构建者互相覆盖 alias，并留下无人引用的孤儿物理索引。
 *
 * <p>与 {@code WorkflowActionLock} 相比有两点刻意的差异：
 *
 * <ul>
 *   <li>它是**全局单锁**，不按业务 ID 分片——重建天然是全局动作；</li>
 *   <li>**Redis 故障时 fail-closed**：Workflow 那边 Redis 挂了可以降级放行，因为后面还有
 *       数据库 version CAS 兜底；重建没有等价兜底，放行就等于允许并发重建，
 *       所以这里让异常直接冒到调用方，宁可拒绝本次重建。</li>
 * </ul>
 */
@Slf4j
@Component
public class IndexRebuildLock {

    private static final String LOCK_KEY = "ai:rag:index:rebuild:lock";
    /** 重建实测数十秒；600s 覆盖异常慢的情况，正常路径由 finally 释放。 */
    private static final long LOCK_TTL_SECONDS = 600L;

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT =
            new DefaultRedisScript<>(
                    """
                    if redis.call('get', KEYS[1]) == ARGV[1] then
                        return redis.call('del', KEYS[1])
                    else
                        return 0
                    end
                    """,
                    Long.class
            );

    private final StringRedisTemplate redisTemplate;

    public IndexRebuildLock(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 尝试获取重建锁。
     *
     * @return 释放令牌；抢不到（已有重建在进行）返回 {@code null}。
     *         Redis 不可用时抛异常，由调用方按 fail-closed 处理。
     */
    public String tryAcquire() {
        String token = UUID.randomUUID().toString();
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(LOCK_KEY, token, LOCK_TTL_SECONDS, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(acquired) ? token : null;
    }

    public void release(String token) {
        if (token == null) {
            return;
        }
        try {
            redisTemplate.execute(UNLOCK_SCRIPT, List.of(LOCK_KEY), token);
        } catch (RuntimeException e) {
            // 释放失败不抛：锁有 TTL 兜底，不能让清理动作覆盖真正的重建结果
            log.warn("索引重建锁释放失败（将由 TTL 自动过期）: {}", e.getMessage());
        }
    }
}
