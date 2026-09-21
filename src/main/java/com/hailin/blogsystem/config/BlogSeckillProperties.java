package com.hailin.blogsystem.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 秒杀配置。
 *
 * 设计稿：docs/redis/钱包与秒杀计划.md §6
 */
@Data
@ConfigurationProperties(prefix = "blog.seckill")
public class BlogSeckillProperties {

    /**
     * 可以调用预热接口的 userId 白名单。
     *
     * <p><b>默认空 = 谁都不能调</b>（fail-safe）。项目目前没有角色体系
     * （{@code users} 表没有 role 字段），这是最小权限的临时方案
     * ——写死在配置里而不是代码里，至少换人/换环境不用改代码。
     *
     * <p>升级路径：给 users 加 role 字段 + 统一鉴权拦截器，那时删掉这个配置项。
     */
    private List<Long> adminUserIds = new ArrayList<>();

    /**
     * 启动完成后自动预热全部 ACTIVE 活动。
     *
     * <p>测试里关掉：它会在上下文启动阶段就去连 Redis 写 key。
     */
    private boolean preheatOnStartup = true;

    /**
     * 是否启用活动状态自动流转（DRAFT → ACTIVE → ENDED）。
     *
     * <p>测试里关掉：{@code @Scheduled} 在测试上下文里是<b>真会跑</b>的，会把用例自己
     * 造的活动在断言中途改成 ENDED（或者反过来把 DRAFT 激活）。
     * 用例显式调 {@link com.hailin.blogsystem.service.SeckillLifecycleService}
     * 的方法，不受这个开关影响——开关只管「要不要让调度器跑它」。
     */
    private boolean lifecycleEnabled = true;

    /**
     * 每日自动开场：每天固定时段一场，活动由 {@code SeckillDailyTask} 自动创建。
     *
     * <p>⚠️ yml 里 {@code start-time} / {@code end-time} <b>必须加引号</b>：
     * YAML 1.1 会把不加引号的 {@code 10:00} 当成 60 进制整数解析成 {@code 600}，
     * 绑定 {@link LocalTime} 直接失败。那个失败是<b>响亮</b>的（启动就挂），所以不用怕它；
     * 真正要怕的是 {@code end-time} 早于 {@code start-time}——它<b>静默生效</b>，
     * 活动会永远停在 DRAFT，见 {@code SeckillDailyValidator}。
     *
     * <p>字段统一用 {@code = new Daily()} 初始化：{@code @Data} 生成的 setter 在嵌套属性上
     * 的绑定行为不如初始化可靠，缺了它配置缺省时可能拿到 null。
     */
    private Daily daily = new Daily();

    @Data
    public static class Daily {

        /**
         * 测试里关掉：{@code @Scheduled} 在测试上下文里是真会跑的，
         * 它会在用例断言中途建出一场活动。用例直接调 {@code SeckillDailyService}
         * 的方法，不受这个开关影响——开关只管「要不要让调度器跑它」。
         */
        private boolean enabled = true;

        /** 每天开场时刻。yml 里要写 {@code "10:00"}（带引号，见类注释） */
        private LocalTime startTime = LocalTime.of(10, 0);

        /** 每天结束时刻。必须晚于 {@code startTime}，否则启动直接失败 */
        private LocalTime endTime = LocalTime.of(10, 30);

        /** 每场名额。字段名对齐列名 {@code total_stock} */
        private int totalStock = 50;

        /** 每场发多少额度。字段名对齐列名 {@code credit_amount} */
        private long creditAmount = 5000;
    }
}
