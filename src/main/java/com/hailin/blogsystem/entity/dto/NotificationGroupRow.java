package com.hailin.blogsystem.entity.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 通知聚合查询的投影：**一行 = 一组**（同一个业务对象上的同类事件）。
 *
 * <p>由 {@code NotificationsMapper.selectGroupPage} 的窗口函数产出，
 * 再经 Service 回查触发者 / 文章 / 评论，组装成 {@code NotificationVO}。
 *
 * <p>分组键是 {@code (type, target_id)}——{@code target_id} 的语义由 type 决定
 * （文章 id / 评论 id / 被关注者 id），所以同一个 type 里它唯一定位一个对象，
 * 两者合起来才是正确的分组键。
 */
@Data
public class NotificationGroupRow {

    /** 组内**最新**那条通知的 id。它同时是前端手里的"代表 id"——标记已读时按它展开整组 */
    private Long id;

    private String type;

    private Long targetId;

    private Long articleId;

    /** 组内最新的触发者（用来渲染"张三 等 3 人"） */
    private Long actorId;

    /** 组内最新的时间，列表按它倒序 */
    private LocalDateTime createdAt;

    /** 组内人数：同一个对象被几个人操作过 */
    private Integer actorCount;

    /** 组内还有几条未读。0 = 整组都已读 */
    private Integer groupUnread;
}
