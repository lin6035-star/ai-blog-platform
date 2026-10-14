package com.hailin.blogsystem.task;

import com.hailin.blogsystem.mapper.NotificationsMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 通知保留期清理：把超过保留期的通知删掉。
 *
 * <p><b>为什么需要它</b>：通知是**只增不减**的表，而它是"时效性信息"——
 * 半年前的"有人赞了你"没有任何价值，用户也不会翻到那里。
 * 保留期默认 30 天，可用 {@code blog.notification.retention-days}（或环境变量
 * {@code BLOG_NOTIFICATION_RETENTION_DAYS}）覆盖。
 *
 * <p><b>为什么单次限制总量</b>：三个定时任务共享同一条调度线程
 * （{@code spring.task.scheduling.pool.size: 1}，见 {@link RagRetryTask} 的注释），
 * 一次删几万行会把 RAG 重试、浏览量同步一起卡在它后面。
 * 所以每批 {@code BATCH_SIZE} 条、最多 {@code MAX_BATCHES} 批 —— 删不完的留给明天，
 * 而每天的**新增**通知量远小于这个上限。
 *
 * <p><b>为什么分批</b>：一条大 DELETE 会撑大事务、长时间持锁，出问题也不好收场。
 *
 * <p><b>为什么删除条件带得上索引</b>：{@code idx_created_at}——现有那两条索引都以
 * {@code receiver_id} 开头，而这里**不带 receiver**，少了那条索引就是全表扫描。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationCleanupTask {

    private static final int BATCH_SIZE = 1000;
    /** 单次最多 2 万条；删不完明天接着删（这个上限是给"调度线程不能被占太久"留的余量） */
    private static final int MAX_BATCHES = 20;

    private final NotificationsMapper notificationsMapper;

    @Value("${blog.notification.retention-days:30}")
    private int retentionDays;

    /** 每天凌晨 3:30：低峰期。刻意与其他定时任务错开，避免挤在同一条调度线程上 */
    @Scheduled(cron = "0 30 3 * * ?")
    public void cleanupExpired() {
        LocalDateTime before = LocalDateTime.now().minusDays(retentionDays);

        int total = 0;
        for (int i = 0; i < MAX_BATCHES; i++) {
            int deleted = notificationsMapper.deleteExpiredBatch(before, BATCH_SIZE);
            total += deleted;
            // 这一批没取满，说明没有更多过期数据了
            if (deleted < BATCH_SIZE) {
                break;
            }
        }

        if (total > 0) {
            log.info("[NOTIFY-CLEANUP] 保留期清理完成 retentionDays={} before={} deleted={}",
                    retentionDays, before, total);
        }
    }
}
