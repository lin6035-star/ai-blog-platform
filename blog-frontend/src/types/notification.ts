/**
 * 站内通知（后端：docs/notification/通知中心设计.md）。
 */

export type NotificationType =
  | 'ARTICLE_LIKED'
  | 'ARTICLE_FAVORITED'
  | 'ARTICLE_COMMENTED'
  | 'COMMENT_REPLIED'
  | 'COMMENT_LIKED'
  | 'USER_FOLLOWED'

export interface NotificationItem {
  id: string
  type: NotificationType
  actorId: string
  /** 触发者昵称；触发者注销时为"某位用户"（后端兜底） */
  actorName: string
  actorAvatarUrl?: string | null
  articleId?: string | null
  /**
   * 文章标题。**为 null 是降级信号**：文章已删，或（你不是作者时）文章已不是"已发布"
   * ——隐藏/草稿的标题不该从通知里漏出去。此时整条不可点击。
   */
  articleTitle?: string | null
  /** 评论类事件才有；**为 null = 评论已删**，前端显示"评论已删除" */
  commentExcerpt?: string | null
  /**
   * 组内人数。列表是**按业务对象聚合**的：同一篇文章被 3 个人点赞 = 一条
   * 「张三 等 3 人赞了你的文章」。只有一个人操作过时是 1（不是 undefined）。
   */
  actorCount?: number
  /** 组内还有几条**未读**。>0 = 这条还没读过；已读后是 0 */
  groupUnread?: number
  /** 0=未读 1=已读。这是**代表行**的状态，判断整组读没读要用 groupUnread */
  isRead: number
  createdAt: string
}
