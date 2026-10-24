import type { NotificationItem } from '@/types/notification'

/**
 * 通知的展示文案。
 *
 * <p>抽成独立的纯函数是为了能单独测——这些分支的**每一个都必须容忍可空字段**：
 * 文章已删 / 已隐藏时 `articleTitle` 是 null，评论已删时 `commentExcerpt` 是 null。
 * 漏掉任何一个，用户看到的就是"null 赞了你的文章"。
 *
 * <p>（后端那边还有一道 `canExposeTitle`：接收者不是作者时，非已发布文章的标题
 * 根本不会下发。所以前端这里的 null 分支是**第二道防线**，不是唯一防线。）
 */

/** 会带"评论摘要"那一行的事件类型。评论被赞也算——它指的正是那条评论的内容 */
export const COMMENT_NOTIFICATION_TYPES = [
  'ARTICLE_COMMENTED',
  'COMMENT_REPLIED',
  'COMMENT_LIKED',
]

/** 评论类事件才显示摘要那一行 */
export function isCommentNotification(item: NotificationItem): boolean {
  return COMMENT_NOTIFICATION_TYPES.includes(item.type)
}

/** 摘要行：评论已删时给一句人话，而不是空白或 "null" */
export function notificationExcerpt(item: NotificationItem): string {
  return item.commentExcerpt?.trim() || '评论已删除'
}

/**
 * 主语的写法：`张三` 或 `张三 等 3 人`。
 *
 * 列表是按业务对象聚合的，一条可能代表一组人。只说"张三 赞了你的文章"会让
 * 另外两个人凭空消失——用户看到的数字和实际发生的事对不上。
 */
export function notificationActorLabel(item: NotificationItem): string {
  const actor = item.actorName?.trim() || '某位用户'
  const count = item.actorCount ?? 1
  return count > 1 ? `${actor} 等 ${count} 人` : actor
}

/**
 * 把"谁"和"做了什么"拼成一句话。
 *
 * 这里的空格是有讲究的，别当成手滑：
 * <ul>
 *   <li>单人 →「张三 赞了你的文章」——人名后留一格，否则"张三赞了"看着太挤</li>
 *   <li>多人 →「张三 等 3 人赞了你的文章」——"人"后面**不留**空格，中文里那个空格是多余的</li>
 * </ul>
 */
function withActor(item: NotificationItem, action: string): string {
  const actor = item.actorName?.trim() || '某位用户'
  const count = item.actorCount ?? 1
  return count > 1 ? `${actor} 等 ${count} 人${action}` : `${actor} ${action}`
}

/**
 * 主文案。**不要在这里拼 `item.articleTitle` 以外的字段**——
 * 它可能为 null（文章已删 / 不该向你暴露标题），每个分支都要有兜底说法。
 */
export function describeNotification(item: NotificationItem): string {
  const title = item.articleTitle?.trim()

  switch (item.type) {
    case 'ARTICLE_LIKED':
      return withActor(item, title ? `赞了你的文章《${title}》` : '赞了你的文章')
    case 'ARTICLE_FAVORITED':
      return withActor(item, title ? `收藏了你的文章《${title}》` : '收藏了你的文章')
    case 'ARTICLE_COMMENTED':
      return withActor(item, title ? `评论了你的文章《${title}》` : '评论了你的文章')
    case 'COMMENT_REPLIED':
      return withActor(item, '回复了你')
    case 'COMMENT_LIKED':
      // 赞的是**评论**，所以这里说"你的评论"而不是"你的文章"
      return withActor(item, '赞了你的评论')
    case 'USER_FOLLOWED':
      return withActor(item, '关注了你')
    default:
      // 后端加了新类型而前端还没跟上时，宁可少说一句也不要渲染奇怪的占位
      return withActor(item, '与你互动')
  }
}

/** 有没有可跳转的目标。标题为 null = 文章已删 / 不该给你看，那就不可点 */
export function canOpenNotification(item: NotificationItem): boolean {
  return Boolean(item.articleId && item.articleTitle)
}

/** 这条还没读过。未读标记与"点一下要标记已读"都看它 */
export function isUnreadNotification(item: NotificationItem): boolean {
  return (item.groupUnread ?? 0) > 0
}
