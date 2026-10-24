package com.hailin.blogsystem.task;

import com.hailin.blogsystem.config.BlogSeckillProperties;
import com.hailin.blogsystem.service.SeckillLifecycleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 秒杀活动状态流转的调度入口。
 *
 * 设计稿：docs/redis/钱包与秒杀计划.md §6.6
 *
 * <p><b>5 秒一轮的取舍</b>：它不是正确性依赖（时间窗和售罄都由抢购脚本的 Lua 判定），
 * 所以不需要精确。真正的成本不是 DB 而是<b>日志</b>——同一套理由写在
 * {@code SeckillSettleTask} 的注释里：2 秒一轮就是每天 4 万多次查询，本机开发时
 * MyBatis 的 SQL 日志会被刷得没法看。代价落在「开始」侧：活动 12:00:00 开始、
 * 任务恰好在 11:59:58 跑过，就得到 12:00:03 才激活——这一段由前端的边界重试吃掉
 * （见 SeckillView 的 scheduleRefresh），而不是靠把间隔调到 1 秒。
 *
 * <p>⚠️ <b>不能用 {@code BLOCK} / 重活</b>：调度池 {@code pool.size: 1} 是串行的，
 * 同池还有 2 秒一轮的入库消费者、浏览量同步、RAG 重试。单轮条数上限由 Service 控制。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SeckillLifecycleTask {

    private final SeckillLifecycleService seckillLifecycleService;
    private final BlogSeckillProperties blogSeckillProperties;

    /**
     * ⚠️ {@code initialDelay} 是必需的，不是保守起见：{@code @Scheduled} 在
     * {@code ContextRefreshedEvent} 时注册并立即首跑，<b>早于 {@code ApplicationRunner}</b>
     * ——不加延迟的话启动时序会变成「本任务先激活并预热 → {@code SeckillPreheatRunner}
     * 再把全部 ACTIVE 活动重建一遍」，白白多一次 delete + 重建。
     */
    @Scheduled(fixedDelay = 5000, initialDelay = 10_000)
    public void advance() {
        if (!blogSeckillProperties.isLifecycleEnabled()) {
            return;
        }
        try {
            int activated = seckillLifecycleService.activateDueActivities();
            int ended = seckillLifecycleService.endDueActivities();
            if (activated > 0 || ended > 0) {
                log.info("[SECKILL-LIFECYCLE] 本轮流转：开始 {} 个，结束 {} 个", activated, ended);
            }
        } catch (RuntimeException e) {
            // 单个活动出问题不该掀掉整轮：下一次调度会重来，而这两个操作都是幂等的
            log.warn("[SECKILL-LIFECYCLE] 本轮流转失败（下一轮重试）", e);
        }
    }
}
