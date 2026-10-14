package com.hailin.blogsystem.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.constants.RedisConstants;
import com.hailin.blogsystem.constants.SeckillKeys;
import com.hailin.blogsystem.entity.SeckillActivity;
import com.hailin.blogsystem.mapper.SeckillActivityMapper;
import com.hailin.blogsystem.service.SeckillLifecycleService;
import com.hailin.blogsystem.service.SeckillService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 秒杀活动状态流转实现。
 *
 * 设计稿：docs/redis/钱包与秒杀计划.md §6.6
 *
 * <p><b>这个类是个「向时间窗收敛」的循环</b>：任何与 {@code start_at} / {@code end_at}
 * 矛盾的中间状态，都会在下一轮被覆盖掉。所以把 ENDED 的活动手工改回 ACTIVE，
 * 只要 {@code end_at} 还在过去，5 秒内就会被改回去——<b>重开一场活动必须同时改
 * {@code end_at}</b>（运维约定见验证指南 §七）。
 *
 * <p>⚠️ <b>刻意不加 {@code @Transactional}</b>：CAS 是单条 UPDATE，本身就原子；
 * 把预热包进事务会让行锁跨越十来次 Redis 往返，而且 {@code preheat()} 会同步
 * 发布 {@code SeckillPreheatedEvent}——事件在提交前就发出去了，消费者被叫醒后
 * 读到的还是旧状态。拆成 Service 的理由是职责分离与可测试性，不是「代理才有事务」
 * （那条是 {@code AiBillingExpireTask} 的教训，语境不同）。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SeckillLifecycleServiceImpl implements SeckillLifecycleService {

    /**
     * 单轮上限。
     *
     * <p>防的不是日常，是「服务停了一天，几百个活动同时到点」的追赶场景。
     * 它同时也限住了 Redis 异常时的最坏阻塞时间——每条探测最长 3 秒（{@code timeout: 3s}），
     * 而调度池是 {@code pool.size: 1} 串行，无上限的话会把 2 秒一轮的秒杀消费者饿死。
     */
    private static final int BATCH = 20;

    /** 结束时间已过这么久却还是 ACTIVE → 排空门卡住了，自监控告警 */
    private static final Duration STUCK_THRESHOLD = Duration.ofMinutes(10);

    private static final String ACTIVE = BlogConstants.Seckill.ACTIVITY_ACTIVE;
    private static final String DRAFT = BlogConstants.Seckill.ACTIVITY_DRAFT;
    private static final String ENDED = BlogConstants.Seckill.ACTIVITY_ENDED;

    private final SeckillActivityMapper seckillActivityMapper;
    private final StringRedisTemplate redisTemplate;
    private final SeckillService seckillService;

    @Override
    public int activateDueActivities() {
        LocalDateTime now = LocalDateTime.now();
        List<SeckillActivity> due = seckillActivityMapper.selectList(
                new LambdaQueryWrapper<SeckillActivity>()
                        .eq(SeckillActivity::getStatus, DRAFT)
                        .le(SeckillActivity::getStartAt, now)
                        // ⚠️ 带上 end_at：窗口完全过去的 DRAFT 不该被激活——
                        // 激活它等于白建一批 7 天 TTL 的 key，然后下一轮又把它结束掉，全程没人看见
                        .gt(SeckillActivity::getEndAt, now)
                        .last("LIMIT " + BATCH));

        int activated = 0;
        for (SeckillActivity activity : due) {
            if (seckillActivityMapper.casStatus(activity.getId(), DRAFT, ACTIVE) == 0) {
                continue;   // 别人推过了（多实例 / 手工改），静默——定时任务是幂等的
            }
            activated++;
            log.info("[SECKILL-LIFECYCLE] 活动 {} 到点开始", activity.getId());

            // Redis 不可用就停在这一条：剩下的留到下一轮。
            // 继续跑会让每条探测各阻塞最长 3 秒（timeout: 3s），N 条叠加会把
            // pool.size: 1 串行池里 2 秒一轮的秒杀消费者饿死
            if (!ensurePreheated(activity.getId())) {
                break;
            }
        }
        return activated;
    }

    @Override
    public int endDueActivities() {
        LocalDateTime now = LocalDateTime.now();

        // 先收尾「窗口已过却还停在 DRAFT」的场次（见 endStaleDrafts 的注释）
        int ended = endStaleDrafts(now);

        List<SeckillActivity> due = seckillActivityMapper.selectList(
                new LambdaQueryWrapper<SeckillActivity>()
                        .eq(SeckillActivity::getStatus, ACTIVE)
                        .and(w -> w.le(SeckillActivity::getEndAt, now)
                                .or()
                                .apply("sold_count >= total_stock"))
                        .last("LIMIT " + BATCH));
        for (SeckillActivity activity : due) {
            boolean drained;
            try {
                drained = drained(activity.getId());
            } catch (DataAccessException e) {
                log.warn("[SECKILL-LIFECYCLE] Redis 不可用，本轮结束中止（下一轮重试）", e);
                break;
            }

            if (!drained) {
                // 还有在途事件：**不能结束**。活动留在 ACTIVE 集合里，消费者和对账
                // 都还在盯着它——「卡住的排空」本来就该被对账发现，不需要额外的探测器。
                if (activity.getEndAt().isBefore(now.minus(STUCK_THRESHOLD))) {
                    log.error("[SECKILL-LIFECYCLE] 活动 {} 结束已超过 {} 分钟仍有在途事件未排空"
                                    + "（排空门卡住 / 事件永远排不空 / XCLAIM 未按设计递增投递计数），需人工介入",
                            activity.getId(), STUCK_THRESHOLD.toMinutes());
                }
                continue;
            }

            if (seckillActivityMapper.casStatus(activity.getId(), ACTIVE, ENDED) == 0) {
                continue;   // 别人推过了，静默
            }
            ended++;
            log.info("[SECKILL-LIFECYCLE] 活动 {} 结束（售罄 {}，已排空）",
                    activity.getId(), activity.getSoldCount() >= activity.getTotalStock());
        }
        return ended;
    }

    /**
     * 把「窗口整体已过、却还停在 DRAFT」的场次收敛成 ENDED。
     *
     * <p><b>为什么必须有这一步</b>：{@link #activateDueActivities} 刻意不激活窗口已过的 DRAFT
     * （激活它等于白建一批 7 天 TTL 的 key、下一轮又立刻结束掉），但**原先没人负责给它收尾**——
     * 于是「服务在活动那半小时里没运行」就会永久留下一个 DRAFT 场次。本地开发（服务不常开）
     * 必然踩到，线上停机也会，实测库里 10-04 / 10-05 / 10-08 / 10-10 / 10-12 五场都卡在这。
     *
     * <p>它不只是脏数据，会实打实地坏掉前端：
     * <ul>
     *   <li>{@code activityPhase} 见到 DRAFT 就返回「未开始」（不看时间）→ 今天那场结束了大半天
     *       还写着未开始</li>
     *   <li>「今天那场还没结束」⇒ 明天那场被判定为不该提前剧透 → <b>列表只剩一场</b>，
     *       而正常应当是「今天已结束 + 明天未开始」两场</li>
     *   <li>{@code scheduleRefresh} 判定「该开始却还没翻转」→ 每 3 秒重拉一次列表
     *       → 骨架屏一直闪</li>
     * </ul>
     *
     * <p>不需要像 ACTIVE 那样等排空：DRAFT 从未激活，抢购脚本会按 meta 里的 status 直接拒掉，
     * 不可能有在途事件。
     */
    private int endStaleDrafts(LocalDateTime now) {
        List<SeckillActivity> stale = seckillActivityMapper.selectList(
                new LambdaQueryWrapper<SeckillActivity>()
                        .eq(SeckillActivity::getStatus, DRAFT)
                        .le(SeckillActivity::getEndAt, now)
                        .last("LIMIT " + BATCH));

        int ended = 0;
        for (SeckillActivity activity : stale) {
            if (seckillActivityMapper.casStatus(activity.getId(), DRAFT, ENDED) == 0) {
                continue;   // 别人推过了（多实例 / 手工改），静默——定时任务是幂等的
            }
            ended++;
            log.info("[SECKILL-LIFECYCLE] 活动 {} 窗口已过仍是 DRAFT（那段时间服务没运行），收敛为 ENDED",
                    activity.getId());
        }
        return ended;
    }

    /**
     * 活动是否已排空——<b>ENDED 的前置条件</b>。
     *
     * <p>为什么不能只看时间：{@code SeckillSettleTask} 消费的活动列表只查
     * {@code status = ACTIVE}。活动一置 ENDED，消费者立刻不再看它，但 Stream 的 PEL 里
     * 可能还有在途事件（用户已经抢到、正等入账）——那条事件会永远烂在 PEL 里，
     * 用户永远停在「排队中」，额度永远不到。
     *
     * <p>两个条件都要：{@code XLEN} 管「还没被读走的」，{@code XPENDING} 管
     * 「读走了还没 ACK 的」。只看一个都会漏。
     */
    private boolean drained(Long activityId) {
        String eventsKey = SeckillKeys.events(activityId);
        if (!Boolean.TRUE.equals(redisTemplate.hasKey(eventsKey))) {
            // events 由抢购脚本的 XADD 创建，key 不存在 = 根本没人抢过
            return true;
        }
        Long length = redisTemplate.opsForStream().size(eventsKey);
        if (length != null && length != 0) {
            return false;
        }
        return pendingCount(eventsKey) == 0;
    }

    /**
     * PEL 待处理条数。
     *
     * <p>⚠️ <b>「查不到」必须抛出去，不能像对账任务那样返回 0</b>——
     * 在这里「未知」被当成「已排空」会让一个还有在途事件的活动被结束，
     * 那正是这个类唯一会丢数据的那个方向。对账那边返回 0 是无所谓的：
     * 漏一轮下一轮还会来，而状态错了不会自愈。
     */
    private long pendingCount(String eventsKey) {
        // ⚠️ 必须走类型化的 opsForStream().pending()，**不能用**
        // connection.execute("XPENDING", ...) 那样的原生命令：
        // XPENDING 的 summary 首元素是整数，而原生调用的 ByteArrayOutput 不支持
        // 解析整数，会抛 UnsupportedOperationException（被 Lettuce 包成
        // RedisSystemException: Unknown redis exception）——表面上像 Redis 挂了，
        // 实际上是解析不了返回值。这个坑在 SeckillReconcileTask 里踩过一次
        try {
            PendingMessagesSummary summary = redisTemplate.opsForStream()
                    .pending(eventsKey, RedisConstants.SECKILL_CONSUMER_GROUP);
            return summary == null ? 0 : summary.getTotalPendingMessages();
        } catch (RuntimeException e) {
            // NOGROUP = 消费组还没建（刚预热、还没人抢过）→ 确实没有 PEL，是 0。
            // 除此之外（连接失败、超时）都是「未知」，交给上层当作没排空
            if (hasNoGroup(e)) {
                return 0;
            }
            throw e;
        }
    }

    /**
     * 异常链里是否有 NOGROUP。
     *
     * <p>⚠️ <b>不能只看顶层异常的消息</b>：Lettuce 会把 Redis 的报错包成
     * {@code RedisSystemException: Unknown redis exception}，真正的
     * {@code NOGROUP No such key ... or consumer group ...} 藏在 cause 里。
     * 只看顶层的话这里会被判成「未知故障」重新抛出，上层再当成 Redis 挂了中止整轮——
     * 而它其实是个再正常不过的情况（活动刚预热、还没人抢过）。
     */
    private static boolean hasNoGroup(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (String.valueOf(t.getMessage()).contains("NOGROUP")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 确保活动在 Redis 里有可用的状态位，返回 false 表示 Redis 不可用（本轮该停）。
     *
     * <p>探针用 {@code HGET meta status} 而不是 {@code hasKey}：后者只能发现「没建起来」，
     * 发现不了<b>「建起来了但状态位是错的」</b>——而后者正是这条流程最容易产出的坏状态
     * （CAS 成功后拿 CAS 之前查出来的旧实体去预热，写进 meta 的就是 DRAFT；
     * 多实例下 CAS 输家也不会去预热）。一次 HGET 同时覆盖两种。
     *
     * <p>⚠️ <b>能只改状态位就绝不重建</b>：{@code preheatOne} 是 delete + 重建，
     * 会清掉在途预占的 {@code users} 成员、重置 {@code reservedBase/stockBase} 基准，
     * 对账下一轮就会报一次「名单增量 ≠ 库存减量」。
     */
    private boolean ensurePreheated(Long activityId) {
        try {
            Object metaStatus = redisTemplate.opsForHash().get(SeckillKeys.meta(activityId), "status");

            if (metaStatus == null) {
                // 真没建起来 → 只能重建。preheat(id) 内部会重新 selectById，
                // 所以写进 meta 的是 DB 里最新的 ACTIVE，不是调用方的旧实体
                seckillService.preheat(activityId);
                log.error("[SECKILL-LIFECYCLE] 活动 {} 刚置 ACTIVE 却没有 Redis 状态，已补预热", activityId);
                return true;
            }
            if (!ACTIVE.equals(String.valueOf(metaStatus))) {
                redisTemplate.opsForHash().put(SeckillKeys.meta(activityId), "status", ACTIVE);
                log.error("[SECKILL-LIFECYCLE] 活动 {} 的 Redis 状态位是 {}，与 DB 不一致，已修正（未重建）",
                        activityId, metaStatus);
            }
            return true;
        } catch (DataAccessException e) {
            log.warn("[SECKILL-LIFECYCLE] 活动 {} 预热探测失败（Redis 不可用，下一轮重试）", activityId, e);
            return false;
        }
    }
}
