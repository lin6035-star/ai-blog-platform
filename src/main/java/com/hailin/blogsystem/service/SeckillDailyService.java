package com.hailin.blogsystem.service;

import java.time.LocalDate;

/**
 * 秒杀活动的每日自动开场。
 *
 * 设计稿：docs/redis/钱包与秒杀计划.md §6.8
 *
 * <p>只负责**建一场 DRAFT 活动**——到点激活、预热、结束全交给
 * {@link SeckillLifecycleService}，这里一行都不重复实现。
 */
public interface SeckillDailyService {

    /**
     * 确保「下一场」活动已创建：今天的窗口还没过就保今天，过了就预告明天。
     *
     * @return 本次是否真的创建了（已经有了就返回 false）
     */
    boolean ensureUpcomingActivity();

    /**
     * 为指定日期创建一场活动（幂等）。活动 id 就是 {@code yyyyMMdd}。
     *
     * <p>公开出来是因为它本身就是一个有意义的能力（「给某天开场」），
     * 同时也是 {@link #ensureUpcomingActivity()} 的基础——用例直接调它并传任意日期，
     * 就不需要为了测试引入时钟抽象（项目里没有 {@code Clock} 先例）。
     *
     * @return 本次是否真的创建了
     */
    boolean createForDate(LocalDate date);
}
