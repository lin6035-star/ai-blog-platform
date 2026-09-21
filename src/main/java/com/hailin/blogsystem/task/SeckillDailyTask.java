package com.hailin.blogsystem.task;

import com.hailin.blogsystem.config.BlogSeckillProperties;
import com.hailin.blogsystem.service.SeckillDailyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每日自动开场的调度入口。
 *
 * 设计稿：docs/redis/钱包与秒杀计划.md §6.8
 *
 * <p><b>为什么是每分钟轮询，而不是 {@code cron = "0 0 0 * * ?"}</b>：
 * 零点那一刻服务若在重启 / 宕机就会漏一天；每分钟检查能<b>自愈</b>——
 * 10:15 启动的进程会在几秒后建出当天那场（它的窗口还没过），
 * 生命周期任务 5 秒内激活并预热，用户 10:15:08 就能抢。
 *
 * <p>成本：每分钟一次主键点查（亚毫秒），量级不到同池 60 秒一轮的
 * {@code SeckillSettleTask} 的百分之一。**前提是服务层必须 O(1)**——
 * 写成 selectList 再遍历会让成本翻几十倍，还会和 2 秒一轮的秒杀消费者抢
 * {@code pool.size: 1} 的单线程调度池。
 *
 * <p>⚠️ {@code initialDelay} 取 3 秒，<b>不是照抄 {@code SeckillLifecycleTask} 的 10 秒</b>。
 * 那 10 秒是为了避开与 {@code SeckillPreheatRunner} 的竞态（{@code @Scheduled} 首跑
 * 早于 {@code ApplicationRunner}），而本任务只写 DB、不碰 Redis，没有那个竞态。
 * 反过来延迟过大是有害的：服务若在 10:29:50 启动，15 秒延迟意味着 10:30:05 才建，
 * 那一刻窗口已经过去，直接就不建了——白丢一场。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SeckillDailyTask {

    private final SeckillDailyService seckillDailyService;
    private final BlogSeckillProperties blogSeckillProperties;

    @Scheduled(fixedDelay = 60_000, initialDelay = 3_000)
    public void ensureUpcomingActivity() {
        if (!blogSeckillProperties.getDaily().isEnabled()) {
            return;
        }
        try {
            // 真的创建了才会有 INFO 日志（在 Service 里）——这里不补，
            // 否则每分钟一条会把日志刷得没法看。这个项目把「日志吵」当成真实成本
            seckillDailyService.ensureUpcomingActivity();
        } catch (RuntimeException e) {
            log.warn("[SECKILL-DAILY] 本轮检查失败（下一轮会重试，创建是幂等的）", e);
        }
    }
}
