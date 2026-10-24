package com.hailin.blogsystem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 站内通知（设计稿：docs/notification/通知中心设计.md）。
 *
 * <p><b>它与业务写在同一个事务里</b>：调用方（点赞 / 收藏 / 评论 / 关注）在自己的
 * {@code @Transactional} 里同步调 {@code NotificationService.notify}，
 * 于是"业务成功 ⟺ 通知在"，不需要补偿、不需要对账。
 * 为什么不放 {@code AfterCommitExecutor}——那个类的契约明确只接受 Redis / 内存动作，见设计稿 §2.1。
 *
 * <p><b>不存快照</b>：只存 id 引用（actorId / articleId / targetId）。
 * 标题与评论内容在列表渲染时批量回查——文章改名显示新名，评论被删则降级成"评论已删除"。
 *
 * <p>⚠️ {@code @TableId(type = ASSIGN_ID)} **不能省**：全站 {@code mybatis-plus.id-type} 是
 * {@code auto}（application.yml:127），而 notifications 表**没有 AUTO_INCREMENT**
 * （见 db/init.sql 第 29 节）。漏了这行，insert 会直接报
 * {@code Field 'id' doesn't have a default value}——正是 articles 表那个坑的镜像。
 */
@Data
@TableName("notifications")
public class Notifications {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 接收者。所有查询/更新都必须带上它——这是越权边界，不是可选条件 */
    private Long receiverId;

    /** 触发者（"谁干的"） */
    private Long actorId;

    /** 事件类型，取值见 {@link NotificationType} */
    private String type;

    /** 事件指向的对象：文章 id / 新评论 id / 被关注者 id，语义见设计稿 §4.3 */
    private Long targetId;

    /** 跳转用文章 id（评论类事件也带上，省一次"由评论反查文章"） */
    private Long articleId;

    /** 0=未读 1=已读 */
    private Integer isRead;

    private LocalDateTime createdAt;
}
