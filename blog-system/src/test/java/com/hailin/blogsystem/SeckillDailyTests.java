package com.hailin.blogsystem;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.constants.SeckillKeys;
import com.hailin.blogsystem.entity.SeckillActivity;
import com.hailin.blogsystem.entity.vo.SeckillActivityVO;
import com.hailin.blogsystem.entity.vo.SeckillResultVO;
import com.hailin.blogsystem.mapper.SeckillActivityMapper;
import com.hailin.blogsystem.service.SeckillDailyService;
import com.hailin.blogsystem.service.SeckillLifecycleService;
import com.hailin.blogsystem.service.SeckillService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 每日自动开场：创建 + 幂等 + 列表过滤 + 端到端。
 *
 * 设计稿：docs/redis/钱包与秒杀计划.md §6.8
 *
 * <p>⚠️ <b>这个类里每个用例必须用不同的日期</b>——活动 id 就是日期，是确定性的主键。
 * 两个用例用同一天的话，第二个会拿到第一个留下的「已存在」而失败。
 * 这是 date-as-PK 的真实代价，换来的是「幂等不需要任何额外机制」。
 *
 * <p>用例直接调 {@link SeckillDailyService}，<b>不等调度器</b>
 * （测试上下文里每日任务已被 {@code blog.seckill.daily.enabled=false} 关掉）。
 */
@SpringBootTest
class SeckillDailyTests {

    private static final String ACTIVE = BlogConstants.Seckill.ACTIVITY_ACTIVE;
    private static final String DRAFT = BlogConstants.Seckill.ACTIVITY_DRAFT;

    @Autowired
    private SeckillDailyService seckillDailyService;

    @Autowired
    private SeckillLifecycleService seckillLifecycleService;

    @Autowired
    private SeckillService seckillService;

    @Autowired
    private SeckillActivityMapper seckillActivityMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private final long userId = 9_500_000_000_000L + System.nanoTime() % 1_000_000_000L;

    /** 建过的活动 id，@AfterEach 逐个清干净——它会渗进列表断言，也占着调度池 */
    private final List<Long> createdIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (Long id : createdIds) {
            redisTemplate.delete(List.of(SeckillKeys.stock(id), SeckillKeys.users(id),
                    SeckillKeys.meta(id), SeckillKeys.events(id)));
            seckillActivityMapper.deleteById(id);
        }
        createdIds.clear();
    }

    private static long activityId(LocalDate date) {
        return Long.parseLong(date.format(DateTimeFormatter.BASIC_ISO_DATE));
    }

    /** 建一场并登记清理。无论成败都登记——删一个不存在的 id 是无害的 */
    private boolean createTracked(LocalDate date) {
        createdIds.add(activityId(date));
        return seckillDailyService.createForDate(date);
    }

    // ---------- 创建与幂等 ----------

    @Test
    void createsActivityFromConfig() {
        LocalDate date = LocalDate.now().plusDays(10);

        assertThat(createTracked(date)).isTrue();

        // 断言 reload 出来的行，不是内存里那个对象——后者会锁住一个假的约定
        SeckillActivity created = seckillActivityMapper.selectById(activityId(date));
        assertThat(created).isNotNull();
        assertThat(created.getStatus()).isEqualTo(DRAFT);
        assertThat(created.getTotalStock()).isPositive();
        assertThat(created.getSoldCount()).as("显式设 0，别留个 null 给后面拆箱").isZero();
        assertThat(created.getCreditAmount()).isPositive();
        assertThat(created.getStartAt().toLocalDate()).isEqualTo(date);
        assertThat(created.getEndAt()).isAfter(created.getStartAt());
        assertThat(created.getName()).contains(String.valueOf(date.getMonthValue()));
    }

    /** 幂等靠主键：同一天建第二次，`selectById` 直接命中，连 INSERT 都不会发 */
    @Test
    void doesNotCreateTwiceForSameDate() {
        LocalDate date = LocalDate.now().plusDays(11);

        assertThat(createTracked(date)).isTrue();
        assertThat(createTracked(date)).as("第二次应当直接返回 false").isFalse();
    }

    /**
     * 不管测试在几点跑，「下一场」都必须是还没结束的。
     *
     * <p>这条同时锁住那个坑：如果今天已经过了结束时刻还去建今天那场，
     * 它的窗口全在过去——`activateDueActivities` 要求 {@code end_at > now}，
     * 那场会永远停在 DRAFT、永不激活、无异常无日志，还会一直挂在列表上。
     */
    @Test
    void upcomingActivityAlwaysHasAFutureWindow() {
        LocalDate today = LocalDate.now();
        createdIds.add(activityId(today));
        createdIds.add(activityId(today.plusDays(1)));

        seckillDailyService.ensureUpcomingActivity();

        SeckillActivity todayActivity = seckillActivityMapper.selectById(activityId(today));
        SeckillActivity tomorrowActivity = seckillActivityMapper.selectById(activityId(today.plusDays(1)));
        SeckillActivity upcoming = todayActivity != null ? todayActivity : tomorrowActivity;

        assertThat(upcoming).as("必须有一场「下一场」").isNotNull();
        assertThat(upcoming.getEndAt()).as("它必须是还没结束的").isAfter(LocalDateTime.now());
    }

    // ---------- 列表过滤 ----------

    /**
     * 列表口径 = 「今天和明天」。
     *
     * <p>今天那场**结束后仍然留在列表里**（灰态卡片）——这是 2026-10-11 定的：列表口径改成
     * 「今天 + 明天」之后，10:30 那一刻今天的卡不会消失，用户不会看到空列表。
     * 而昨天及更早的是历史、后天的是还没到时候，都不该出现。
     *
     * <p><b>只断言自己这几个 id</b>，不断言列表长度或顺序——测试库里还有别的类
     * （比如 `SeckillSettleTests`，它连清理都没有）留下的活动行。
     */
    @Test
    void listCoversTodayAndTomorrowOnly() {
        LocalDate today = LocalDate.now();
        long yesterdayId = activityId(today.minusDays(1));
        long todayId = activityId(today);
        long dayAfterTomorrowId = activityId(today.plusDays(2));

        createTracked(today.minusDays(1));
        createTracked(today);
        createTracked(today.plusDays(2));

        List<Long> visibleIds = seckillService.listActivities(null).stream()
                .map(SeckillActivityVO::getId)
                .toList();

        assertThat(visibleIds)
                .as("今天那场要在——哪怕已经结束（灰态），否则 10:30 之后列表会空")
                .contains(todayId);
        assertThat(visibleIds)
                .as("昨天的属于历史，不是「接下来能抢什么」")
                .doesNotContain(yesterdayId);
        assertThat(visibleIds)
                .as("后天的不提前暴露")
                .doesNotContain(dayAfterTomorrowId);
    }

    // ---------- 端到端 ----------

    /**
     * 建出来的活动能被生命周期任务激活、并且真的抢得到。
     *
     * <p>⚠️ 不断言 {@code activateDueActivities()} 的返回值（会被同库里别的活动污染），
     * 只断言自己那一行的状态与真实调用结果——「key 存在」是不够的，
     * 最容易出的坏法是 key 都在但 {@code meta.status} 还是错的。
     */
    @Test
    void dailyActivityBecomesGrabbableAfterLifecycleActivates() {
        LocalDate today = LocalDate.now();
        long id = activityId(today);
        createTracked(today);

        // 配置的窗口是 10:00-10:30，而用例可能在一天里任何时刻跑——
        // 直接把它挪到现在，避免用例只在上午十点才绿
        seckillActivityMapper.update(null, new LambdaUpdateWrapper<SeckillActivity>()
                .eq(SeckillActivity::getId, id)
                .set(SeckillActivity::getStartAt, LocalDateTime.now().minusMinutes(1))
                .set(SeckillActivity::getEndAt, LocalDateTime.now().plusMinutes(10)));

        seckillLifecycleService.activateDueActivities();

        assertThat(seckillActivityMapper.selectById(id).getStatus()).isEqualTo(ACTIVE);
        assertThat(seckillService.grab(id, userId).getStatus())
                .as("激活之后必须真的抢得到")
                .isEqualTo(SeckillResultVO.QUEUED);
        assertThat(seckillActivityMapper.selectById(id).getStatus())
                .as("整个过程中它不该被改成别的状态")
                .isEqualTo(ACTIVE);
    }
}
