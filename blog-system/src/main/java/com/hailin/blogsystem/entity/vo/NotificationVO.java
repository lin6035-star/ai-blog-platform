package com.hailin.blogsystem.entity.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 通知列表项（设计稿 §10）。
 *
 * <p>扁平结构，与 {@link ArticleDetailVO} 一致。
 *
 * <p><b>可空字段是降级信号，前端必须按它分支</b>：
 * <ul>
 *   <li>{@code articleTitle == null} —— 文章已删，**或者**接收者不是作者而文章已不是"已发布"
 *       （隐藏/草稿的标题不能泄露给别人）→ 不显示标题、不可点击</li>
 *   <li>{@code commentExcerpt == null} 且 type 是评论类 —— 评论已删 → 显示"评论已删除"</li>
 *   <li>{@code actorName} 兜底为"某位用户"—— 触发者已注销 / 查不到</li>
 * </ul>
 *
 * <p>⚠️ {@code commentExcerpt} 与 {@code actorName} 都是**用户输入**，
 * 前端用插值渲染（`{{ }}`），**不要 v-html**。
 */
@Data
public class NotificationVO {

    private Long id;

    /** 取值见 {@code NotificationType}：前端按它选文案 */
    private String type;

    private Long actorId;

    private String actorName;

    private String actorAvatarUrl;

    private Long articleId;

    /** 可空：见类注释的降级规则 */
    private String articleTitle;

    /** 评论类事件才有；可空（评论已删） */
    private String commentExcerpt;

    /**
     * 组内人数。列表是**按业务对象聚合**的（见 {@code NotificationsMapper.selectGroupPage}），
     * 所以一条 = 一组：{@code actorCount = 3} 就是「张三 等 3 人赞了你的文章」。
     * 只有一个人操作过时它是 1。
     */
    private Integer actorCount;

    /** 组内还有几条**未读**。0 = 整组已读；前端据此决定要不要给这一条加未读标记 */
    private Integer groupUnread;

    /**
     * 0=未读 1=已读。**这是"代表行"（组内最新那条）的状态**——
     * 判断整组读没读要用 {@link #groupUnread}，不要用它。
     */
    private Integer isRead;

    private LocalDateTime createdAt;
}
