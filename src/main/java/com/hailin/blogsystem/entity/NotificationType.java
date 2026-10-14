package com.hailin.blogsystem.entity;

/**
 * 站内通知的事件类型（设计稿：docs/notification/通知中心设计.md §5）。
 *
 * <p>落库用 {@link #name()}，列类型是 {@code VARCHAR(32)}——**不走 MyBatis 的枚举映射**：
 * 实体字段声明成 String，省掉 default-enum-type-handler 的隐式行为，
 * 也避免"枚举改名/调序导致历史数据读不出来"这类问题。
 *
 * <p>新增类型时记三件事：① 这里加枚举；② 触发点调 {@code NotificationService.notify}；
 * ③ 前端文案表加一行（前端按 type 分支渲染，没有默认兜底文案）。
 */
public enum NotificationType {

    /** 文章被点赞：接收者 = 文章作者 */
    ARTICLE_LIKED,

    /** 文章被收藏：接收者 = 文章作者 */
    ARTICLE_FAVORITED,

    /** 文章被评论（顶级评论）：接收者 = 文章作者 */
    ARTICLE_COMMENTED,

    /** 评论被回复：接收者 = 父评论作者。父评论作者恰好是文章作者时，**只发这一条** */
    COMMENT_REPLIED,

    /** 评论被点赞：接收者 = **评论作者**（不是文章作者）。target_id = 被赞的那条评论 id */
    COMMENT_LIKED,

    /** 被关注：接收者 = 被关注者 */
    USER_FOLLOWED
}
