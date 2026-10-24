package com.hailin.blogsystem.ai.index;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hailin.blogsystem.mapper.ArticleIndexOutboxMapper;
import com.hailin.blogsystem.utils.MdcContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * outbox 的读写与状态机。
 *
 * <p><b>enqueue 必须调用方已开事务</b>：outbox 行要和文章写操作同事务提交，
 * 否则又退化成"先写库、后投递"（进程崩溃即丢）。误用直接抛异常，不静默降级。</p>
 *
 * <p><b>claim 用 CAS 而不是 DB 行锁</b>：`UPDATE ... WHERE id=? AND status='PENDING'` 影响行数为 1 才算抢到，
 * 多实例/多线程下只有一个能成功；不用 `FOR UPDATE SKIP LOCKED` 是为了 H2（测试库）也能跑。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArticleIndexOutboxService {

    private final ArticleIndexOutboxMapper outboxMapper;

    /**
     * 在**当前事务**内落一条待投递事件。
     */
    public void enqueue(Long articleId) {
        if (articleId == null) {
            throw new IllegalArgumentException("articleId 不能为空");
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "文章索引事件必须在事务内落 outbox：articleId=" + articleId);
        }

        LocalDateTime now = LocalDateTime.now();
        insertPending(articleId, now);
    }

    /**
     * 消费端在"索引不健康/重建中"把事件回插 outbox 延后处理。
     *
     * <p><b>独立新事务</b>：消费端本身没有事务，且必须先确保 outbox 行真正提交成功才允许 ACK
     * （listener 正常返回才 ACK）；插入失败会抛出去触发 MQ 重试，绝不能先 ACK 再补写。</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void enqueueDeferred(Long articleId, int delaySeconds) {
        if (articleId == null) {
            throw new IllegalArgumentException("articleId 不能为空");
        }
        LocalDateTime now = LocalDateTime.now();
        insertPending(articleId, now.plusSeconds(Math.max(1, delaySeconds)));
    }

    private void insertPending(Long articleId, LocalDateTime nextRetryAt) {
        LocalDateTime now = LocalDateTime.now();
        ArticleIndexOutbox row = new ArticleIndexOutbox();
        row.setArticleId(articleId);
        row.setStatus(ArticleIndexOutbox.STATUS_PENDING);
        row.setAttempts(0);
        row.setNextRetryAt(nextRetryAt);
        row.setTraceId(currentTraceId());
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        outboxMapper.insert(row);
    }

    /**
     * 取一批到期的 PENDING 行并 CAS 抢占成 SENDING。
     * 抢不到（被别的实例先改）的行直接跳过，不报错。
     */
    public List<ArticleIndexOutbox> claimDue(int batchSize, int leaseSeconds) {
        LocalDateTime now = LocalDateTime.now();
        List<ArticleIndexOutbox> candidates = outboxMapper.selectList(
                new LambdaQueryWrapper<ArticleIndexOutbox>()
                        .eq(ArticleIndexOutbox::getStatus, ArticleIndexOutbox.STATUS_PENDING)
                        .le(ArticleIndexOutbox::getNextRetryAt, now)
                        .orderByAsc(ArticleIndexOutbox::getId)
                        .last("LIMIT " + Math.max(1, batchSize))
        );
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        LocalDateTime leaseUntil = now.plusSeconds(Math.max(1, leaseSeconds));
        List<ArticleIndexOutbox> claimed = new ArrayList<>(candidates.size());
        for (ArticleIndexOutbox candidate : candidates) {
            int updated = outboxMapper.update(null,
                    new LambdaUpdateWrapper<ArticleIndexOutbox>()
                            .eq(ArticleIndexOutbox::getId, candidate.getId())
                            .eq(ArticleIndexOutbox::getStatus, ArticleIndexOutbox.STATUS_PENDING)
                            .set(ArticleIndexOutbox::getStatus, ArticleIndexOutbox.STATUS_SENDING)
                            .set(ArticleIndexOutbox::getLockedUntil, leaseUntil)
                            .set(ArticleIndexOutbox::getUpdatedAt, now));
            if (updated == 1) {
                candidate.setStatus(ArticleIndexOutbox.STATUS_SENDING);
                candidate.setLockedUntil(leaseUntil);
                claimed.add(candidate);
            }
        }
        return claimed;
    }

    /** 投递成功：SENDING → SENT。返回 false 表示租约已被恢复/被别的实例处理，仅记日志。 */
    public boolean markSent(Long id) {
        LocalDateTime now = LocalDateTime.now();
        int updated = outboxMapper.update(null,
                new LambdaUpdateWrapper<ArticleIndexOutbox>()
                        .eq(ArticleIndexOutbox::getId, id)
                        .eq(ArticleIndexOutbox::getStatus, ArticleIndexOutbox.STATUS_SENDING)
                        .set(ArticleIndexOutbox::getStatus, ArticleIndexOutbox.STATUS_SENT)
                        .set(ArticleIndexOutbox::getSentAt, now)
                        .set(ArticleIndexOutbox::getUpdatedAt, now));
        if (updated != 1) {
            log.warn("[INDEX-OUTBOX] markSent 未命中 SENDING 行（可能已被租约恢复）: id={}", id);
            return false;
        }
        return true;
    }

    /**
     * 投递失败：SENDING → PENDING（指数退避），超过上限 → FAILED 留给对账/人工。
     */
    public void markSendFailed(ArticleIndexOutbox row, String error, int maxAttempts) {
        int attempts = (row.getAttempts() == null ? 0 : row.getAttempts()) + 1;
        LocalDateTime now = LocalDateTime.now();

        LambdaUpdateWrapper<ArticleIndexOutbox> update = new LambdaUpdateWrapper<ArticleIndexOutbox>()
                .eq(ArticleIndexOutbox::getId, row.getId())
                .eq(ArticleIndexOutbox::getStatus, ArticleIndexOutbox.STATUS_SENDING)
                .set(ArticleIndexOutbox::getAttempts, attempts)
                .set(ArticleIndexOutbox::getLastError, truncate(error, 500))
                .set(ArticleIndexOutbox::getLockedUntil, null)
                .set(ArticleIndexOutbox::getUpdatedAt, now);

        if (attempts >= Math.max(1, maxAttempts)) {
            update.set(ArticleIndexOutbox::getStatus, ArticleIndexOutbox.STATUS_FAILED);
            log.error("[INDEX-OUTBOX] 投递重试耗尽，转 FAILED 等待对账/人工: id={}, articleId={}, attempts={}, error={}",
                    row.getId(), row.getArticleId(), attempts, truncate(error, 300));
        } else {
            update.set(ArticleIndexOutbox::getStatus, ArticleIndexOutbox.STATUS_PENDING)
                    .set(ArticleIndexOutbox::getNextRetryAt, now.plusSeconds(backoffSeconds(attempts)));
            log.warn("[INDEX-OUTBOX] 投递失败，{}s 后重试: id={}, articleId={}, attempt={}",
                    backoffSeconds(attempts), row.getId(), row.getArticleId(), attempts);
        }
        outboxMapper.update(null, update);
    }

    /** 崩溃恢复：租约过期的 SENDING 行退回 PENDING（发送后崩溃 → 重发，消费端幂等）。 */
    public int recoverStale(int leaseGraceSeconds) {
        LocalDateTime threshold = LocalDateTime.now().minusSeconds(Math.max(0, leaseGraceSeconds));
        return outboxMapper.update(null,
                new LambdaUpdateWrapper<ArticleIndexOutbox>()
                        .eq(ArticleIndexOutbox::getStatus, ArticleIndexOutbox.STATUS_SENDING)
                        .lt(ArticleIndexOutbox::getLockedUntil, threshold)
                        .set(ArticleIndexOutbox::getStatus, ArticleIndexOutbox.STATUS_PENDING)
                        .set(ArticleIndexOutbox::getLockedUntil, null)
                        .set(ArticleIndexOutbox::getNextRetryAt, LocalDateTime.now())
                        .set(ArticleIndexOutbox::getUpdatedAt, LocalDateTime.now()));
    }

    /** executor 拒绝任务时把已抢到的行立即退回 PENDING，避免白等一个租约周期。 */
    public int revertToPending(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now();
        return outboxMapper.update(null,
                new LambdaUpdateWrapper<ArticleIndexOutbox>()
                        .in(ArticleIndexOutbox::getId, ids)
                        .eq(ArticleIndexOutbox::getStatus, ArticleIndexOutbox.STATUS_SENDING)
                        .set(ArticleIndexOutbox::getStatus, ArticleIndexOutbox.STATUS_PENDING)
                        .set(ArticleIndexOutbox::getLockedUntil, null)
                        .set(ArticleIndexOutbox::getNextRetryAt, now)
                        .set(ArticleIndexOutbox::getUpdatedAt, now));
    }

    /** 清理已投递的旧行（SENT 保留 7 天便于排查，之后分批删）。 */
    public int cleanupSent(int retainDays) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(Math.max(1, retainDays));
        List<ArticleIndexOutbox> old = outboxMapper.selectList(
                new LambdaQueryWrapper<ArticleIndexOutbox>()
                        .select(ArticleIndexOutbox::getId)
                        .eq(ArticleIndexOutbox::getStatus, ArticleIndexOutbox.STATUS_SENT)
                        .lt(ArticleIndexOutbox::getSentAt, cutoff)
                        .orderByAsc(ArticleIndexOutbox::getId)
                        .last("LIMIT 10000")
        );
        if (old == null || old.isEmpty()) {
            return 0;
        }
        return outboxMapper.deleteBatchIds(old.stream().map(ArticleIndexOutbox::getId).toList());
    }

    public long countByStatus(String status) {
        return outboxMapper.selectCount(
                new LambdaQueryWrapper<ArticleIndexOutbox>().eq(ArticleIndexOutbox::getStatus, status));
    }

    /** 第一次失败后 5s、10s、20s… 上限 300s（attempt 从 1 开始）。 */
    static int backoffSeconds(int attempts) {
        int safe = Math.max(1, attempts);
        int exponent = Math.min(safe - 1, 6);
        return Math.min(300, 5 * (1 << exponent));
    }

    private static String currentTraceId() {
        Map<String, String> mdc = MdcContext.capture();
        return mdc == null ? null : mdc.get("traceId");
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
