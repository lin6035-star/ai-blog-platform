package com.hailin.blogsystem.component;

import com.hailin.blogsystem.config.BlogSeckillProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalTime;

/**
 * 每日开场配置的启动校验。
 *
 * 设计稿：docs/redis/钱包与秒杀计划.md §6.8
 *
 * <p><b>为什么需要它</b>：{@code end-time} 早于 {@code start-time} 时，
 * 激活判据 {@code start_at <= now AND end_at > now} 在同一天内<b>恒为假</b>——
 * 活动会永远停在 DRAFT、永不激活，而且<b>不报错、不打日志、没有告警</b>，
 * 用户只看到永远「未开始」。这是这个功能唯一「静默全废」的方向。
 *
 * <p>对照：yml 里 {@code 10:00} 忘了加引号是<b>响亮</b>的（YAML 1.1 把它当 60 进制
 * 整数解析成 600，绑定 {@link LocalTime} 失败，启动直接挂），不需要在这里兜。
 *
 * <p><b>关掉开关也照样校验</b>：配置错了但 {@code enabled=false}，等哪天有人打开它
 * 才炸——那时离改配置已经过去很久，现场的报错指不到根因。启动期是发现它的最好时机。
 */
@Component
@RequiredArgsConstructor
public class SeckillDailyValidator {

    private static final Logger log = LoggerFactory.getLogger(SeckillDailyValidator.class);

    private final BlogSeckillProperties blogSeckillProperties;

    @PostConstruct
    public void validate() {
        BlogSeckillProperties.Daily daily = blogSeckillProperties.getDaily();

        LocalTime start = daily.getStartTime();
        LocalTime end = daily.getEndTime();
        if (start == null || end == null) {
            throw new IllegalStateException("blog.seckill.daily 的 start-time / end-time 不能为空"
                    + "（yml 里要写成 \"10:00\" 这种带引号的形式，不加引号会被 YAML 解析成 60 进制整数）");
        }
        if (!end.isAfter(start)) {
            throw new IllegalStateException(String.format(
                    "blog.seckill.daily 的时间窗不自洽：start-time=%s，end-time=%s。"
                            + "end 必须晚于 start——否则 activateDueActivities 的 "
                            + "「start_at <= now AND end_at > now」在同一天内恒为假，"
                            + "活动会永远停在 DRAFT，不报错、不打日志、无法察觉。",
                    start, end));
        }
        if (daily.getTotalStock() <= 0) {
            throw new IllegalStateException(
                    "blog.seckill.daily.total-stock 必须为正数，当前 " + daily.getTotalStock());
        }
        if (daily.getCreditAmount() <= 0) {
            throw new IllegalStateException(
                    "blog.seckill.daily.credit-amount 必须为正数，当前 " + daily.getCreditAmount());
        }

        if (!daily.isEnabled()) {
            log.info("[秒杀每日开场] 配置校验通过，但功能已关闭（blog.seckill.daily.enabled=false）");
            return;
        }
        log.info("[秒杀每日开场] 校验通过：每天 {} ~ {} 一场，{} 份，每份 {} credit",
                start, end, daily.getTotalStock(), daily.getCreditAmount());
    }
}
