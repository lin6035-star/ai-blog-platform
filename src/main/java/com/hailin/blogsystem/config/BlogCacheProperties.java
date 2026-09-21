package com.hailin.blogsystem.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 缓存配置。
 *
 * 设计稿：docs/redis/delayed-double-delete-design.md
 */
@Data
@ConfigurationProperties(prefix = "blog.cache")
public class BlogCacheProperties {

    private DoubleDelete doubleDelete = new DoubleDelete();

    /**
     * 延迟双删：提交后删一次 + D 毫秒后再删一次。
     *
     * <p>解决的是 Cache-Aside 残留的窗口——读请求在写事务<b>提交前</b>查库（拿到旧值），
     * 却在缓存<b>删除之后</b>才写回，于是旧值一直留到 TTL 过期（详情 10 分钟）。
     * 窗口宽度 = 读请求「查库 → 写缓存」的耗时，不是"删除到提交之间那几微秒"。
     */
    @Data
    public static class DoubleDelete {

        /**
         * 总开关。关闭 = 退化成"提交后删一次"（即加这一刀之前的行为）。
         *
         * <p>留着它是为了两件事：反向验证（关掉后对应测试必须精确变红），
         * 以及线上出问题时不用改代码就能止血。
         */
        private boolean enabled = true;

        /**
         * 第二次删除的延迟（毫秒）。
         *
         * <p><b>这个值由实测决定，不是拍脑袋</b>——依据是「读请求从查库到写缓存」的耗时，
         * 取实测 max 的若干倍。实测数据与方法见设计稿。
         *
         * <p>方向性：<b>偏大是安全的</b>（代价只是多一次 DB 回源），偏小则窗口还在。
         */
        private long delayMs = 200;

        /**
         * 延迟重删的积压上限。超过即丢弃本次重删（退化成单次删除），不阻塞业务线程。
         *
         * <p>调度器底层是 {@code DelayedWorkQueue}，<b>无界且 JDK 层面不提供背压</b>，
         * 所以上限得自己兜。丢任务是安全的——本功能丢了只是退回加这一刀之前的行为。
         */
        private int maxPending = 4096;
    }
}
