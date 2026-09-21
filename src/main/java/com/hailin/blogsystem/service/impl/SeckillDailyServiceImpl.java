package com.hailin.blogsystem.service.impl;

import com.hailin.blogsystem.config.BlogSeckillProperties;
import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.entity.SeckillActivity;
import com.hailin.blogsystem.mapper.SeckillActivityMapper;
import com.hailin.blogsystem.service.SeckillDailyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * 每日自动开场实现。
 *
 * 设计稿：docs/redis/钱包与秒杀计划.md §6.8
 *
 * <p><b>活动 id 就是 {@code yyyyMMdd}</b>（如 {@code 20260921}），幂等完全靠主键：
 * 重复创建直接撞主键，不需要任何额外机制，也不用给表加唯一列。
 * 8 位数和 19 位雪花不冲突，所以手工建的临时活动照常能共存。
 *
 * <p>id 段位约定（也写进了验证指南）：
 * <ul>
 *   <li><b>8 位</b> {@code yyyyMMdd} —— 本类自动建的每日活动</li>
 *   <li><b>12 位</b> {@code yyyyMMddHHmm} —— 手工开「一天两场」</li>
 *   <li><b>19 位</b> 雪花 —— 手工建的临时活动</li>
 * </ul>
 * ⚠️ <b>不要把这招推广到别的表</b>：{@code articles.id} 是 {@code AUTO_INCREMENT}，
 * 在那张表手写 {@code 20260921} 会把自增计数器顶到两千万，后面所有插入的 id 全跳。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SeckillDailyServiceImpl implements SeckillDailyService {

    private final SeckillActivityMapper seckillActivityMapper;
    private final BlogSeckillProperties blogSeckillProperties;

    @Override
    public boolean ensureUpcomingActivity() {
        BlogSeckillProperties.Daily daily = blogSeckillProperties.getDaily();

        // 今天的窗口还没过就保今天，过了就预告明天。于是 10:30 那一刻，
        // 今天的卡被列表的 end_at 过滤掉、明天的卡同时建出来——列表无缝衔接、永不空
        LocalDate target = LocalTime.now().isBefore(daily.getEndTime())
                ? LocalDate.now()
                : LocalDate.now().plusDays(1);
        return createForDate(target);
    }

    @Override
    public boolean createForDate(LocalDate date) {
        BlogSeckillProperties.Daily daily = blogSeckillProperties.getDaily();

        // ⚠️ 必须 O(1)：这个检查每分钟跑一轮。写成 selectList 再遍历的话成本翻几十倍，
        // 而且会和 2 秒一轮的秒杀消费者抢 pool.size: 1 的单线程调度池
        long id = activityId(date);
        if (seckillActivityMapper.selectById(id) != null) {
            return false;
        }

        SeckillActivity activity = new SeckillActivity();
        activity.setId(id);
        // String.format 而不是 DateTimeFormatter.ofPattern("M月d日")：后者把 月/日
        // 当作模式字面量，靠的是「非 ASCII 字母视为字面量」这条偏门规则，没必要赌它
        activity.setName(String.format("额度秒杀 · %d月%d日",
                date.getMonthValue(), date.getDayOfMonth()));
        activity.setTotalStock(daily.getTotalStock());
        // ⚠️ 显式设 0：MyBatis-Plus 的 insert 策略是 NOT_NULL，null 字段不进 INSERT
        // （列默认值 0 兜住了 DB），但**内存里的实体仍然是 null**——
        // 后面任何一次 totalStock - soldCount 的拆箱都会 NPE
        activity.setSoldCount(0);
        activity.setCreditAmount(daily.getCreditAmount());
        activity.setStartAt(date.atTime(daily.getStartTime()));
        activity.setEndAt(date.atTime(daily.getEndTime()));
        activity.setStatus(BlogConstants.Seckill.ACTIVITY_DRAFT);

        try {
            seckillActivityMapper.insert(activity);
        } catch (DuplicateKeyException e) {
            // 并发下别人刚建了同一场。主键就是这里的防线，撞上不算错误
            return false;
        }

        log.info("[SECKILL-DAILY] 已创建 {} 的活动：{} ~ {}，{} 份，每份 {} credit",
                date, activity.getStartAt(), activity.getEndAt(),
                daily.getTotalStock(), daily.getCreditAmount());
        return true;
    }

    /**
     * 活动 id = 日期数字。
     *
     * <p>{@code BASIC_ISO_DATE} 正好就是 {@code yyyyMMdd}（没有分隔符），
     * 比手写拼接少一次拼错的机会。
     */
    private static long activityId(LocalDate date) {
        return Long.parseLong(date.format(DateTimeFormatter.BASIC_ISO_DATE));
    }
}
