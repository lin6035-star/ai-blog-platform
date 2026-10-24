package com.hailin.blogsystem.ai.index;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文章索引同步 outbox 行（表 {@code article_index_outbox}）。
 *
 * <p>与文章写操作**同一 DB 事务**插入，保证"文章提交成功 ⟺ 索引事件存在"；
 * relay 扫描 PENDING 行投递到 RocketMQ，消费端回查文章当前状态后同步双索引。</p>
 *
 * <p>状态机：{@code PENDING → SENDING → SENT}；投递失败回到 PENDING 并指数退避，
 * 超过上限转 FAILED 留给对账/人工。{@code SENDING} 带租约（{@code locked_until}），
 * 进程崩溃后由 relay 恢复成 PENDING 重投（至少一次，消费端幂等）。</p>
 */
@Data
@TableName("article_index_outbox")
public class ArticleIndexOutbox {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_SENDING = "SENDING";
    public static final String STATUS_SENT = "SENT";
    public static final String STATUS_FAILED = "FAILED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long articleId;

    private String status;

    /** 仅统计 relay 投递失败次数；消费失败由 RocketMQ 重试，不计在这里 */
    private Integer attempts;

    private LocalDateTime nextRetryAt;

    /** SENDING 租约到期时间；过期视为投递者已崩溃，可被恢复重投 */
    private LocalDateTime lockedUntil;

    private LocalDateTime sentAt;

    /** 写入请求的 traceId，供 relay/消费端日志串联 */
    private String traceId;

    private String lastError;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
