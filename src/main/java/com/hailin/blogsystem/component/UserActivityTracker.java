package com.hailin.blogsystem.component;

import com.hailin.blogsystem.constants.RedisConstants;
import com.hailin.blogsystem.utils.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 用户日活跃统计（**SET 集合**）。
 *
 * <p><b>为什么是 SET 而不是 Bitmap</b>：位图只有一个前提——**offset 就是 userId，所以 ID 必须
 * 密集且小**。单 key 上限 512MB 意味着 offset 不能超过 {@code 2^32}（约 43 亿）。
 * 而本项目的 {@code users.id} 是**雪花**（实体上是 {@code IdType.ASSIGN_ID}，19 位、量级 {@code 2^63}），
 * **根本进不了位图**。
 *
 * <p>原实现正是按「自增主键」写的（类注释里明确假设了 {@code IdType.AUTO}），
 * 并加了一道 {@code MAX_USER_OFFSET = 1 千万} 的防线防止脏 ID 撑爆内存。
 * 但现实是雪花 ID —— 结果<b>全部真实用户都被那道闸拦在门外</b>：这个功能从未产出过任何数据，
 * 个人主页的「站点活跃」永远是 0，而且拦得**静默**（裸 return、连一行日志都没有），
 * 很久都没人发现。**防线和现实正好相反，它防的就是常态。**
 *
 * <p>换成 SET 之后没有 offset 这个概念，约束自然消失。代价是每个成员多几十字节开销——
 * 对「一天几百个活跃用户」这个量级完全可以忽略；而位图省内存的优势，
 * 在一个 ID 根本进不了位图的系统里等于零。
 *
 * <p>三类指标的集合语义与原位图一一对应：
 * <ul>
 *   <li>今日活跃 = {@code SCARD}</li>
 *   <li>近 N 天活跃 = {@code SUNIONSTORE} 到临时 key + {@code SCARD}（逐天并集）</li>
 *   <li>留存 = {@code SINTERSTORE} 到临时 key + {@code SCARD}（两天交集）</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserActivityTracker {

    /** 记录 + 兜底 TTL 一次完成。理由同浏览量 / UV：「允许丢失的数据都有 TTL」是 volatile-lru 的前提 */
    private static final DefaultRedisScript<Long> MARK_ACTIVE_SCRIPT = new DefaultRedisScript<>(
            """
            redis.call('SADD', KEYS[1], ARGV[1])
            if redis.call('TTL', KEYS[1]) < 0 then
                redis.call('EXPIRE', KEYS[1], ARGV[2])
            end
            return 1
            """,
            Long.class
    );

    /** 统计用的临时 key 存活时间。正常路径 `finally` 会删掉它，这个 TTL 只兜「删除也失败了」 */
    private static final Duration TEMP_KEY_TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate stringRedisTemplate;

    /** 记录"当前登录用户今天活跃"。未登录时什么都不做——游客没有 userId */
    public void markActiveToday() {
        Long userId = UserContext.get();
        if (userId != null) {
            markActive(userId, LocalDate.now());
        }
    }

    /** 记录指定用户在某天活跃 */
    public void markActive(Long userId, LocalDate date) {
        if (userId == null || userId <= 0) {
            // 0 / 负数不是合法用户。留一行日志——这类脏数据本该在更上游就被拦住，
            // 而不是在这里不声不响地消失
            log.warn("跳过非法 userId 的活跃记录, userId={} date={}", userId, date);
            return;
        }

        String key = buildKey(date);
        try {
            stringRedisTemplate.execute(
                    MARK_ACTIVE_SCRIPT,
                    Collections.singletonList(key),
                    String.valueOf(userId),
                    String.valueOf(TimeUnit.DAYS.toSeconds(RedisConstants.USER_ACTIVE_TTL_DAYS))
            );
        } catch (Exception e) {
            // 活跃统计失败不影响业务主流程
            log.warn("用户活跃记录失败, userId={} date={}", userId, date, e);
        }
    }

    /** 某天的活跃用户数。key 不存在时 Redis 返回 0，不需要额外判断 */
    public long countActive(LocalDate date) {
        return cardinality(buildKey(date));
    }

    /** 区间内活跃过的用户数：[from, to] 逐天并集后计数（周活跃 / 月活跃） */
    public long countActiveBetween(LocalDate from, LocalDate to) {
        List<String> keys = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            keys.add(buildKey(d));
        }
        return aggregateCount(SetOperation.UNION, keys);
    }

    /** 两天**都**活跃的用户数（交集后计数）——留存分析的基础指标 */
    public long countRetained(LocalDate earlier, LocalDate later) {
        return aggregateCount(SetOperation.INTERSECT, List.of(buildKey(earlier), buildKey(later)));
    }

    private long cardinality(String key) {
        try {
            Long count = stringRedisTemplate.opsForSet().size(key);
            return count == null ? 0 : count;
        } catch (Exception e) {
            log.warn("活跃集合计数失败, key={}", key, e);
            return 0;
        }
    }

    /**
     * 先算到临时 key 再计数，用完即删。
     *
     * <p>临时 key 用 UUID 命名：统计接口可能被并发调用，固定名字会互相覆盖结果。
     *
     * <p>`finally` 里删除，并给它设了 {@link #TEMP_KEY_TTL}——删除本身若失败（比如连接断了），
     * 也只是留一个会自己过期的孤儿 key，既不污染正式数据也不长期占内存。
     */
    private long aggregateCount(SetOperation op, List<String> sourceKeys) {
        if (sourceKeys.isEmpty()) {
            return 0;
        }

        String destKey = RedisConstants.USER_ACTIVE_KEY_PREFIX + "tmp:" + UUID.randomUUID();
        try {
            // 源 key 不存在时，SUNIONSTORE / SINTERSTORE 会把 dest 直接删掉（返回 0）——
            // 这正是想要的：没有数据就是 0，而不是报错
            if (op == SetOperation.UNION) {
                stringRedisTemplate.opsForSet()
                        .unionAndStore(sourceKeys.get(0), sourceKeys.subList(1, sourceKeys.size()), destKey);
            } else {
                stringRedisTemplate.opsForSet()
                        .intersectAndStore(sourceKeys.get(0), sourceKeys.subList(1, sourceKeys.size()), destKey);
            }
            stringRedisTemplate.expire(destKey, TEMP_KEY_TTL);
            return cardinality(destKey);
        } catch (Exception e) {
            log.warn("活跃集合运算失败, op={} 参与天数={}", op, sourceKeys.size(), e);
            return 0;
        } finally {
            try {
                stringRedisTemplate.delete(destKey);
            } catch (Exception e) {
                log.warn("活跃临时 key 删除失败（UUID 命名 + {} 的 TTL 保证不会污染正式数据）: {}",
                        TEMP_KEY_TTL, destKey, e);
            }
        }
    }

    /** 活跃集合 key 按天分：active:user:{yyyyMMdd}（与文章 UV 的日期格式保持一致） */
    private String buildKey(LocalDate date) {
        return RedisConstants.USER_ACTIVE_KEY_PREFIX
                + date.format(DateTimeFormatter.BASIC_ISO_DATE);
    }

    private enum SetOperation {
        UNION,
        INTERSECT
    }
}
