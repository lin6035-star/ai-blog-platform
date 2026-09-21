package com.hailin.blogsystem;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.constants.RedisConstants;
import com.hailin.blogsystem.constants.SeckillKeys;
import com.hailin.blogsystem.entity.SeckillActivity;
import com.hailin.blogsystem.entity.vo.SeckillResultVO;
import com.hailin.blogsystem.mapper.SeckillActivityMapper;
import com.hailin.blogsystem.service.SeckillLifecycleService;
import com.hailin.blogsystem.service.SeckillService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 秒杀活动状态流转：到点开始 / 到期结束 / 售罄结束 / 排空门。
 *
 * 设计稿：docs/redis/钱包与秒杀计划.md §6.6
 *
 * <p>用真 Redis（与 {@code SeckillLuaTests} 同一套做法）：排空门判的是 Stream 的
 * XLEN 与 PEL，而这两样正是「用 mock 验等于什么都没验」的东西。
 *
 * <p>用例直接调 {@link SeckillLifecycleService} 的方法，<b>不等调度器</b>
 * （调度周期 5 秒，靠等会让用例变得看运气）；测试上下文里调度任务已被
 * {@code blog.seckill.lifecycle-enabled=false} 关掉，所以不会来抢。
 */
@SpringBootTest
class SeckillLifecycleTests {

    private static final String GROUP = RedisConstants.SECKILL_CONSUMER_GROUP;
    private static final String ACTIVE = BlogConstants.Seckill.ACTIVITY_ACTIVE;
    private static final String DRAFT = BlogConstants.Seckill.ACTIVITY_DRAFT;
    private static final String ENDED = BlogConstants.Seckill.ACTIVITY_ENDED;

    @Autowired
    private SeckillService seckillService;

    @Autowired
    private SeckillLifecycleService seckillLifecycleService;

    @Autowired
    private SeckillActivityMapper seckillActivityMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private final long userId = 9_400_000_000_000L + System.nanoTime() % 1_000_000_000L;

    private SeckillActivity activity;

    /**
     * 清场。
     *
     * <p>这组用例断言的是「本轮流转了几个」，所以任何<b>别人留下的、状态仍然是 ACTIVE
     * 但已经到期或售罄</b>的活动都会被 {@code endDueActivities} 一起捞走，把返回值搞脏
     * （{@code SeckillSettleTests} 里把库存吃满的那几个用例正好会留下这种数据）。
     * 测试库里这些都是造出来的，直接推成终态即可。
     */
    @BeforeEach
    void clearDueActivitiesLeftByOtherTests() {
        seckillActivityMapper.update(null, new LambdaUpdateWrapper<SeckillActivity>()
                .eq(SeckillActivity::getStatus, ACTIVE)
                .and(w -> w.le(SeckillActivity::getEndAt, LocalDateTime.now())
                        .or()
                        .apply("sold_count >= total_stock"))
                .set(SeckillActivity::getStatus, ENDED));
    }

    @AfterEach
    void tearDown() {
        if (activity == null) {
            return;
        }
        Long id = activity.getId();
        redisTemplate.delete(List.of(SeckillKeys.stock(id), SeckillKeys.users(id),
                SeckillKeys.meta(id), SeckillKeys.events(id)));
        // 数据库行也要删：这个类里的用例互相之间不能留残留
        seckillActivityMapper.deleteById(id);
    }

    private SeckillActivity newActivity(String status, int totalStock,
                                        LocalDateTime startAt, LocalDateTime endAt) {
        SeckillActivity created = new SeckillActivity();
        created.setName("生命周期测试-" + System.nanoTime());
        created.setTotalStock(totalStock);
        created.setSoldCount(0);
        created.setCreditAmount(1000L);
        created.setStartAt(startAt);
        created.setEndAt(endAt);
        created.setStatus(status);
        seckillActivityMapper.insert(created);
        activity = created;
        return created;
    }

    private SeckillActivity reload() {
        return seckillActivityMapper.selectById(activity.getId());
    }

    /**
     * 前置条件检查用：直接读 XPENDING 的 summary 条数。
     *
     * <p>XPENDING 在 key 或消费组已不存在时抛 NOGROUP（被 Lettuce 包成
     * 「Unknown redis exception」），那种情况就是没有 PEL —— 按 0 算。
     */
    private long pendingOf(String eventsKey) {
        try {
            PendingMessagesSummary summary = redisTemplate.opsForStream().pending(eventsKey, GROUP);
            return summary == null ? 0 : summary.getTotalPendingMessages();
        } catch (RuntimeException e) {
            return 0;   // NOGROUP = key 或消费组已不存在 = 没有 PEL
        }
    }

    // ---------- 到点开始 ----------

    /**
     * 到点后必须<b>真的能抢</b>，不能只断言「Redis key 存在」。
     *
     * <p>这条流程最容易出的坏法是「key 都在，但 meta.status 还是 DRAFT」——
     * 那时抢购脚本会一路拒绝，而存在性断言对它完全免疫。所以这里断言真实调用结果。
     */
    @Test
    void draftBecomesActiveAndGrabbableWhenDue() {
        newActivity(DRAFT, 10, LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusMinutes(30));

        int activated = seckillLifecycleService.activateDueActivities();

        assertThat(activated).isEqualTo(1);
        assertThat(reload().getStatus()).isEqualTo(ACTIVE);
        assertThat(reload().getStatusChangedAt()).as("流转时间戳要写下来").isNotNull();
        assertThat(seckillService.grab(activity.getId(), userId).getStatus())
                .as("激活后必须真的抢得到——key 存在不等于 meta.status 是对的")
                .isEqualTo(SeckillResultVO.QUEUED);
    }

    @Test
    void draftStaysUntouchedBeforeStartTime() {
        newActivity(DRAFT, 10, LocalDateTime.now().plusHours(1), LocalDateTime.now().plusHours(2));

        assertThat(seckillLifecycleService.activateDueActivities()).isZero();

        assertThat(reload().getStatus()).isEqualTo(DRAFT);
        assertThat(redisTemplate.hasKey(SeckillKeys.meta(activity.getId())))
                .as("没到点不该预热——白建一批 7 天 TTL 的 key")
                .isFalse();
    }

    /** 窗口完全过去的 DRAFT 不该被激活：激活它等于白建 key，然后下一轮又结束掉，全程没人看见 */
    @Test
    void expiredDraftIsNotActivated() {
        newActivity(DRAFT, 10, LocalDateTime.now().minusHours(2), LocalDateTime.now().minusHours(1));

        assertThat(seckillLifecycleService.activateDueActivities()).isZero();
        assertThat(reload().getStatus()).isEqualTo(DRAFT);
    }

    @Test
    void endedActivityIsNotReactivated() {
        newActivity(ENDED, 10, LocalDateTime.now().minusHours(2), LocalDateTime.now().minusHours(1));

        assertThat(seckillLifecycleService.activateDueActivities()).isZero();
        assertThat(reload().getStatus()).isEqualTo(ENDED);
    }

    // ---------- 到期结束 ----------

    @Test
    void activeEndsAfterEndTimeWhenNothingInFlight() {
        newActivity(ACTIVE, 10, LocalDateTime.now().minusHours(1), LocalDateTime.now().minusMinutes(1));

        assertThat(seckillLifecycleService.endDueActivities()).isEqualTo(1);

        assertThat(reload().getStatus()).isEqualTo(ENDED);
        assertThat(reload().getStatusChangedAt()).isNotNull();
    }

    @Test
    void activeWithFutureEndTimeIsNotEnded() {
        newActivity(ACTIVE, 10, LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1));

        assertThat(seckillLifecycleService.endDueActivities()).isZero();
        assertThat(reload().getStatus()).isEqualTo(ACTIVE);
    }

    // ---------- 售罄结束 ----------

    @Test
    void soldOutActivityEnds() {
        newActivity(ACTIVE, 2, LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1));
        seckillActivityMapper.increaseSoldCount(activity.getId());
        seckillActivityMapper.increaseSoldCount(activity.getId());

        assertThat(seckillLifecycleService.endDueActivities()).isEqualTo(1);
        assertThat(reload().getStatus()).isEqualTo(ENDED);
    }

    // ---------- 排空门（本次最要紧的一条） ----------

    /**
     * 还有没被读走的事件时**不能结束**。
     *
     * <p>否则 {@code SeckillSettleTask} 立刻不再消费该活动，事件烂在 Stream 里，
     * 用户永远停在「排队中」、额度永远不到。
     */
    @Test
    void doesNotEndWhileUnreadEventsRemain() {
        newActivity(ACTIVE, 10, LocalDateTime.now().minusHours(1), LocalDateTime.now().minusMinutes(1));
        redisTemplate.opsForStream().add(SeckillKeys.events(activity.getId()),
                Map.of("activityId", String.valueOf(activity.getId()), "userId", String.valueOf(userId)));

        assertThat(seckillLifecycleService.endDueActivities())
                .as("Stream 里还有没读走的事件 → 不能结束")
                .isZero();
        assertThat(reload().getStatus()).isEqualTo(ACTIVE);
    }

    /**
     * 事件被读走但**没 ACK**（正在处理中 / 处理失败留在 PEL）时也不能结束。
     *
     * <p>这一条走的是 {@code XPENDING} 分支，与上面那条的 {@code XLEN} 分支是两段代码。
     * 构造手法：读进 PEL 之后把消息 {@code XDEL} 掉——XLEN 归零，PEL 仍留着它。
     */
    @Test
    void doesNotEndWhilePendingEventsRemain() throws Exception {
        newActivity(ACTIVE, 10, LocalDateTime.now().minusHours(1), LocalDateTime.now().minusMinutes(1));
        String eventsKey = SeckillKeys.events(activity.getId());

        RecordId recordId = redisTemplate.opsForStream().add(eventsKey,
                Map.of("activityId", String.valueOf(activity.getId()), "userId", String.valueOf(userId)));
        redisTemplate.opsForStream().createGroup(eventsKey, ReadOffset.from("0"), GROUP);
        redisTemplate.opsForStream().read(Consumer.from(GROUP, "lifecycle-test"),
                StreamReadOptions.empty().count(10),
                StreamOffset.create(eventsKey, ReadOffset.lastConsumed()));
        redisTemplate.opsForStream().delete(eventsKey, recordId);

        assertThat(redisTemplate.opsForStream().size(eventsKey))
                .as("前置条件：XLEN 已归零，只剩 PEL")
                .isZero();

        assertThat(seckillLifecycleService.endDueActivities())
                .as("PEL 里还有未 ACK 的事件 → 不能结束")
                .isZero();
        assertThat(reload().getStatus()).isEqualTo(ACTIVE);
    }

    @Test
    void endsAfterPendingEventsAreAcked() {
        newActivity(ACTIVE, 10, LocalDateTime.now().minusHours(1), LocalDateTime.now().minusMinutes(1));
        String eventsKey = SeckillKeys.events(activity.getId());

        RecordId recordId = redisTemplate.opsForStream().add(eventsKey,
                Map.of("activityId", String.valueOf(activity.getId()), "userId", String.valueOf(userId)));
        redisTemplate.opsForStream().createGroup(eventsKey, ReadOffset.from("0"), GROUP);
        redisTemplate.opsForStream().read(Consumer.from(GROUP, "lifecycle-test"),
                StreamReadOptions.empty().count(10),
                StreamOffset.create(eventsKey, ReadOffset.lastConsumed()));
        redisTemplate.opsForStream().acknowledge(eventsKey, GROUP, recordId);
        redisTemplate.opsForStream().delete(eventsKey, recordId);

        assertThat(redisTemplate.opsForStream().size(eventsKey)).as("XLEN 应归零").isZero();
        assertThat(pendingOf(eventsKey)).as("PEL 应清空").isZero();

        assertThat(seckillLifecycleService.endDueActivities())
                .as("彻底排空之后就该结束")
                .isEqualTo(1);
        assertThat(reload().getStatus()).isEqualTo(ENDED);
    }

    // ---------- 幂等 ----------

    @Test
    void activationIsIdempotentOnSecondRun() {
        newActivity(DRAFT, 10, LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusMinutes(30));

        assertThat(seckillLifecycleService.activateDueActivities()).isEqualTo(1);
        assertThat(seckillLifecycleService.activateDueActivities())
                .as("第二次跑已经没有 DRAFT 了，CAS 拿不到行")
                .isZero();
        assertThat(reload().getStatus()).isEqualTo(ACTIVE);
    }

    // ---------- 预热修复：只改状态位，绝不重建 ----------

    /**
     * meta 的状态位与 DB 不一致时只做 {@code HSET}，**不能重建**。
     *
     * <p>重建（{@code preheatOne} 的 delete + 重建）会清掉在途预占的 {@code users} 成员、
     * 重置 {@code reservedBase/stockBase} 基准，对账下一轮就会报「名单增量 ≠ 库存减量」。
     * 所以这里先塞一个「在途用户」进去，跑完修复后断言它还活着。
     */
    @Test
    void repairsStatusBitWithoutRebuildingRedisState() {
        // 预热时活动还是 DRAFT，于是 meta.status 被写成 DRAFT；再手工加一个「在途」成员
        newActivity(DRAFT, 10, LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusMinutes(30));
        seckillService.preheat(activity.getId());
        redisTemplate.opsForSet().add(SeckillKeys.users(activity.getId()), String.valueOf(userId));

        assertThat(seckillLifecycleService.activateDueActivities()).isEqualTo(1);

        assertThat(redisTemplate.opsForHash().get(SeckillKeys.meta(activity.getId()), "status"))
                .as("状态位要被修正成 ACTIVE，否则抢购脚本一路拒绝")
                .isEqualTo(ACTIVE);
        assertThat(redisTemplate.opsForSet().isMember(SeckillKeys.users(activity.getId()),
                String.valueOf(userId)))
                .as("在途预占必须还在——重建会把它清掉")
                .isTrue();
    }

    /** 活动是 ACTIVE 却完全没有 Redis 状态（CAS 成功后进程崩了 / 多实例下 CAS 输家没预热）→ 补预热 */
    @Test
    void preheatsActiveActivityThatHasNoRedisState() {
        newActivity(DRAFT, 10, LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusMinutes(30));

        seckillLifecycleService.activateDueActivities();

        assertThat(redisTemplate.hasKey(SeckillKeys.meta(activity.getId()))).isTrue();
        assertThat(seckillService.grab(activity.getId(), userId).getStatus())
                .isEqualTo(SeckillResultVO.QUEUED);
    }
}
