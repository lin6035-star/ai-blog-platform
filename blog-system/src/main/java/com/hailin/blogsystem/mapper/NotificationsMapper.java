package com.hailin.blogsystem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hailin.blogsystem.entity.Notifications;
import com.hailin.blogsystem.entity.dto.NotificationGroupRow;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface NotificationsMapper extends BaseMapper<Notifications> {

    /**
     * 通知列表，**按业务对象聚合**：同一篇文章被 10 个人点赞 = 一行「10 人赞了你的文章」，
     * 而不是刷满 10 行。
     *
     * <p><b>为什么聚合放在 SQL 而不是 Java 里做</b>：分页必须按**组**算。
     * 先按行分页再在内存里合并的话，同一组会被页码切开——第 1 页显示"3 人赞了"、
     * 第 2 页又冒出"7 人赞了"，而且两页的计数都不对。窗口函数让"每组取最新一条 + 组内统计"
     * 和"按组分页"在同一条语句里完成。
     *
     * <p>{@code rn = 1} 取的是组内最新那条（{@code created_at DESC, id DESC} 保证全序——
     * {@code created_at} 只到秒，同秒创建的多条必须有 id 兜底）。
     *
     * <p>{@code group_unread} 用「组内条数 − 已读条数」算：{@code is_read} 只有 0/1 两种取值，
     * 所以 {@code SUM(is_read)} 就是已读条数。
     */
    @Select("""
            SELECT t.id            AS id,
                   t.type          AS type,
                   t.target_id     AS target_id,
                   t.article_id    AS article_id,
                   t.actor_id      AS actor_id,
                   t.created_at    AS created_at,
                   t.actor_count   AS actor_count,
                   t.group_unread  AS group_unread
            FROM (
                SELECT n.id, n.type, n.target_id, n.article_id, n.actor_id, n.created_at,
                       ROW_NUMBER() OVER (
                           PARTITION BY n.type, n.target_id
                           ORDER BY n.created_at DESC, n.id DESC) AS rn,
                       COUNT(*) OVER (PARTITION BY n.type, n.target_id) AS actor_count,
                       COUNT(*) OVER (PARTITION BY n.type, n.target_id)
                           - SUM(n.is_read) OVER (PARTITION BY n.type, n.target_id) AS group_unread
                FROM notifications n
                WHERE n.receiver_id = #{receiverId}
            ) t
            WHERE t.rn = 1
            ORDER BY t.created_at DESC, t.id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<NotificationGroupRow> selectGroupPage(@Param("receiverId") Long receiverId,
                                               @Param("limit") long limit,
                                               @Param("offset") long offset);

    /** 聚合后的**组数**（分页总数）。口径必须与 {@link #selectGroupPage} 的分组完全一致 */
    @Select("""
            SELECT COUNT(*)
            FROM (
                SELECT 1
                FROM notifications
                WHERE receiver_id = #{receiverId}
                GROUP BY type, target_id
            ) g
            """)
    long countGroups(@Param("receiverId") Long receiverId);

    /**
     * 分批删除过期通知（保留期清理，见 {@code NotificationCleanupTask}）。
     *
     * <p>「派生表再套一层」不是多余的写法：**MySQL 不允许在 DELETE 的子查询里直接引用同一张表**
     * （报 {@code You can't specify target table for update in FROM clause}），
     * 包一层 {@code (SELECT …) expired} 是标准绕法。
     *
     * <p>分批而不是一次删光：一次删几万行会撑大事务、长时间持锁。
     * 调用方按「返回值 &lt; limit」判断是否已经删完。
     */
    @Delete("""
            DELETE FROM notifications
            WHERE id IN (
                SELECT id FROM (
                    SELECT id FROM notifications
                    WHERE created_at < #{before}
                    LIMIT #{limit}
                ) expired
            )
            """)
    int deleteExpiredBatch(@Param("before") LocalDateTime before, @Param("limit") int limit);
}
