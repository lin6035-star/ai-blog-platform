package com.hailin.blogsystem.component;

import com.hailin.blogsystem.config.BlogCacheProperties;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 延迟双删：在「事务提交后删」之上再叠一层「延迟重放」。
 *
 * <p><b>它补的是什么</b>：{@link AfterCommitExecutor} 消除了"删早了"那个必然出现的窗口，但仍剩一个——
 * 读请求在写事务<b>提交前</b>查库（拿到旧值），却在缓存<b>删除之后</b>才写回，
 * 于是旧值一直留到 TTL 过期（详情 10 分钟）。
 *
 * <pre>
 * 读请求 R：TR1 查库（读到旧值） ──────┐
 * 写请求 W：        T3 提交 → T4 删缓存 │
 *                          R 在 T4 之后才写回 ──┘  → 缓存里是旧值
 * </pre>
 *
 * 触发条件不是"恰好并发"：R 的「查库 → 写缓存」区间只要跨越提交时刻就会中，
 * 而这个区间有几十毫秒宽。把删除动作延迟 D 毫秒再执行一次，覆盖的正是那次迟到写回。
 *
 * <p><b>三条契约</b>（改动本类前必须逐条确认仍然成立）：
 * <ol>
 *   <li><b>动作必须幂等</b>——"重复执行 == 执行一次"是敢重放的唯一前提。删除天然满足；
 *       将来要往里塞 {@code INCR} / {@code LPUSH} 这类动作，必须先重新设计本类</li>
 *   <li><b>任务可丢</b>——丢了只是退化成"没有第二次删除"（= 加这一刀之前的行为），
 *       任何情况下都不允许因为丢任务产生正确性问题</li>
 *   <li><b>不抛异常</b>——无事务调用方会走 {@link AfterCommitExecutor} 的立即执行分支，
 *       而那条分支<b>不兜异常</b>。所有 Redis 异常必须在本类内部消化</li>
 * </ol>
 *
 * <p><b>范围</b>：只接删除型动作。{@link UserSetCache} 的集合维护走的是同一个
 * {@code AfterCommitExecutor} 但<b>不接本类</b>——它是 Lua 增量维护而非删除，
 * 且它的读路径回填是"全量快照覆盖"，用"删两次"关不上，理由详见设计稿。
 *
 * <p>设计稿：docs/redis/delayed-double-delete-design.md
 *
 * <p>⚠️ <b>下面的调度器必须私有 new，绝不能声明成 Spring Bean</b>：上下文里一旦出现任何类型为
 * {@code TaskScheduler} 的 bean，Spring Boot 自动配置的 {@code taskScheduler} 会因
 * {@code @ConditionalOnMissingBean} 退避，{@code spring.task.scheduling.pool.size: 1} 静默失效，
 * 且<b>全部 {@code @Scheduled} 任务都会被搬到本池</b>——项目刻意维持的"串行调度"不变量
 * （浏览量同步与热度榜重建读同一批数据，并发会重复计分）会被无声破坏。
 */
@Slf4j
@Component
public class DelayedCacheEvictor {

    /**
     * 一个线程足够：重放动作是毫秒级的 DEL。
     *
     * <p>唯一会占住线程的是 pattern 删除的 SCAN（几十毫秒）。<b>晚执行只会让残留窗口更宽、
     * 不会更窄</b>，所以排队的代价是"多一次回源"，没有正确性风险。
     */
    private static final int POOL_SIZE = 1;
    private static final String THREAD_NAME_PREFIX = "cache-evict-";

    private final StringRedisTemplate stringRedisTemplate;
    private final RedisKeyScanner redisKeyScanner;
    private final AfterCommitExecutor afterCommitExecutor;
    private final BlogCacheProperties.DoubleDelete config;

    private final ThreadPoolTaskScheduler scheduler;
    private final AtomicInteger pending = new AtomicInteger();

    public DelayedCacheEvictor(StringRedisTemplate stringRedisTemplate,
                               RedisKeyScanner redisKeyScanner,
                               AfterCommitExecutor afterCommitExecutor,
                               BlogCacheProperties properties) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.redisKeyScanner = redisKeyScanner;
        this.afterCommitExecutor = afterCommitExecutor;
        this.config = properties.getDoubleDelete();

        // 刻意不走容器，理由见类注释
        this.scheduler = new ThreadPoolTaskScheduler();
        this.scheduler.setPoolSize(POOL_SIZE);
        this.scheduler.setThreadNamePrefix(THREAD_NAME_PREFIX);
        // 沿用项目里其余线程池的做法：重放日志仍带 traceId
        this.scheduler.setTaskDecorator(new ContextPropagatingTaskDecorator());
        this.scheduler.initialize();
    }

    /**
     * 删除单个 key：提交后删一次，D 毫秒后再删一次。
     *
     * @param key 完整 key（调用方自行用 {@code RedisConstants} 前缀拼装）
     */
    public void evict(String key) {
        afterCommitExecutor.execute(() -> {
            deleteQuietly(key, false);
            scheduleReplay(() -> deleteQuietly(key, true), key);
        });
    }

    /**
     * 按 pattern 扫描删除（SCAN + UNLINK）：提交后扫一次，D 毫秒后再扫一次。
     *
     * @param pattern glob 表达式，如 {@code article:list:*}
     */
    public void evictPattern(String pattern) {
        afterCommitExecutor.execute(() -> {
            // scanAndDelete 内部已兜异常并打日志，这里不重复包
            redisKeyScanner.scanAndDelete(pattern);
            scheduleReplay(() -> redisKeyScanner.scanAndDelete(pattern), pattern);
        });
    }

    @PreDestroy
    public void destroy() {
        scheduler.destroy();
    }

    private void deleteQuietly(String key, boolean replay) {
        try {
            stringRedisTemplate.delete(key);
        } catch (Exception e) {
            log.warn("[CACHE-EVICT-FAIL] 删除失败，靠 TTL 兜底: key={} 延迟重删={}", key, replay, e);
        }
    }

    /**
     * 排一次延迟重放。
     *
     * <p>不做按 key 合并去重：合并要么把最早的删除推后（反而扩大窗口），
     * 要么需要一个"待删集合"来维护——而 DEL 幂等且只有毫秒级，重复执行无害。
     * 上限由 {@code maxPending} + 丢弃兜住。
     */
    private void scheduleReplay(Runnable action, String desc) {
        if (!config.isEnabled()) {
            return;
        }
        if (pending.incrementAndGet() > config.getMaxPending()) {
            pending.decrementAndGet();
            log.warn("[CACHE-EVICT-DEFER-DROP] 延迟重删积压超上限 {}，本次跳过（退化为单次删除）: {}",
                    config.getMaxPending(), desc);
            return;
        }
        try {
            scheduler.schedule(() -> {
                try {
                    action.run();
                } finally {
                    pending.decrementAndGet();
                }
            }, Instant.now().plusMillis(config.getDelayMs()));
        } catch (Exception e) {
            pending.decrementAndGet();
            log.warn("[CACHE-EVICT-DEFER-DROP] 延迟重删提交失败: {}", desc, e);
        }
    }
}
