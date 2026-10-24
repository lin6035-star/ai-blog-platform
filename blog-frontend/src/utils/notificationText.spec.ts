import { describe, expect, it } from 'vitest'
import type { NotificationItem } from '@/types/notification'
import {
  canOpenNotification,
  describeNotification,
  isCommentNotification,
  isUnreadNotification,
  notificationActorLabel,
  notificationExcerpt,
} from './notificationText'

function item(patch: Partial<NotificationItem> = {}): NotificationItem {
  return {
    id: '1',
    type: 'ARTICLE_LIKED',
    actorId: '9',
    actorName: '张三',
    isRead: 0,
    createdAt: '2026-10-11T20:31:00',
    ...patch,
  }
}

/**
 * 这些断言盯的是两类事故：
 *
 * 1. **可空字段被直接拼进文案**——后端会故意不下发两类字段（文章已删/已隐藏时
 *    `articleTitle`、评论已删时 `commentExcerpt`），漏了兜底就会渲染出"null 赞了你的文章"；
 * 2. **聚合后被当成单人**——一条可能代表一组人，只念最新的名字会让另外几个人凭空消失。
 *
 * 两类都不会让别的测试变红。
 */
describe('describeNotification', () => {
  it('renders the title when it is available', () => {
    expect(describeNotification(item({ articleTitle: 'Redis 缓存穿透实战' }))).toBe(
      '张三 赞了你的文章《Redis 缓存穿透实战》',
    )
  })

  it('falls back when the article title is missing', () => {
    // 文章已删，或你不是作者而文章已不是已发布 → 后端不下发标题
    expect(describeNotification(item({ articleTitle: null }))).toBe('张三 赞了你的文章')
    expect(describeNotification(item({ articleTitle: undefined }))).toBe('张三 赞了你的文章')
  })

  it('covers every notification type', () => {
    const withTitle = { articleTitle: '某篇文章' }
    expect(describeNotification(item({ type: 'ARTICLE_FAVORITED', ...withTitle }))).toBe(
      '张三 收藏了你的文章《某篇文章》',
    )
    expect(describeNotification(item({ type: 'ARTICLE_COMMENTED', ...withTitle }))).toBe(
      '张三 评论了你的文章《某篇文章》',
    )
    expect(describeNotification(item({ type: 'COMMENT_REPLIED' }))).toBe('张三 回复了你')
    expect(describeNotification(item({ type: 'COMMENT_LIKED' }))).toBe('张三 赞了你的评论')
    expect(describeNotification(item({ type: 'USER_FOLLOWED' }))).toBe('张三 关注了你')
  })

  it('never renders a null actor', () => {
    // 触发者注销后后端会兜底成"某位用户"，前端这里再兜一层（空串、空白都算）
    expect(describeNotification(item({ actorName: '' }))).toBe('某位用户 赞了你的文章')
    expect(describeNotification(item({ actorName: '   ' }))).toBe('某位用户 赞了你的文章')
  })

  it('degrades instead of leaking an unknown type', () => {
    // 后端加了新类型而前端还没跟上：宁可少说一句，也不要渲染奇怪的占位串
    expect(describeNotification(item({ type: 'SOMETHING_NEW' as never }))).toBe('张三 与你互动')
  })
})

describe('notificationActorLabel', () => {
  it('uses the bare name for a single actor', () => {
    expect(notificationActorLabel(item({ actorCount: 1 }))).toBe('张三')
    // 老数据 / 字段缺失时按单人处理，不能渲染成"张三 等 undefined 人"
    expect(notificationActorLabel(item({ actorCount: undefined }))).toBe('张三')
  })

  it('names the count when the group has more than one actor', () => {
    expect(notificationActorLabel(item({ actorCount: 3 }))).toBe('张三 等 3 人')
  })

  it('keeps the aggregated wording in the full sentence', () => {
    expect(describeNotification(item({ actorCount: 3, articleTitle: 'Redis 实战' }))).toBe(
      '张三 等 3 人赞了你的文章《Redis 实战》',
    )
    // 没人赞过两次，但聚合逻辑对评论点赞同样生效
    expect(describeNotification(item({ type: 'COMMENT_LIKED', actorCount: 2 }))).toBe(
      '张三 等 2 人赞了你的评论',
    )
  })
})

describe('notificationExcerpt', () => {
  it('returns the excerpt when the comment is alive', () => {
    expect(notificationExcerpt(item({ type: 'ARTICLE_COMMENTED', commentExcerpt: '写得不错' }))).toBe(
      '写得不错',
    )
  })

  it('says the comment was deleted instead of showing nothing', () => {
    expect(notificationExcerpt(item({ type: 'ARTICLE_COMMENTED', commentExcerpt: null }))).toBe(
      '评论已删除',
    )
    expect(notificationExcerpt(item({ type: 'ARTICLE_COMMENTED', commentExcerpt: '   ' }))).toBe(
      '评论已删除',
    )
  })
})

describe('isCommentNotification', () => {
  it('treats article comments, replies and comment likes as having an excerpt row', () => {
    expect(isCommentNotification(item({ type: 'ARTICLE_COMMENTED' }))).toBe(true)
    expect(isCommentNotification(item({ type: 'COMMENT_REPLIED' }))).toBe(true)
    expect(isCommentNotification(item({ type: 'COMMENT_LIKED' }))).toBe(true)
    expect(isCommentNotification(item({ type: 'ARTICLE_LIKED' }))).toBe(false)
    expect(isCommentNotification(item({ type: 'USER_FOLLOWED' }))).toBe(false)
  })
})

describe('canOpenNotification', () => {
  it('only allows opening when the article is actually visible', () => {
    expect(canOpenNotification(item({ articleId: '10', articleTitle: '标题' }))).toBe(true)
    // 标题为 null = 文章已删 / 不该给你看：点了会跳到一个 404 或别人的隐藏文章
    expect(canOpenNotification(item({ articleId: '10', articleTitle: null }))).toBe(false)
    expect(canOpenNotification(item({ articleId: null, articleTitle: '标题' }))).toBe(false)
  })
})

describe('isUnreadNotification', () => {
  it('reads the group unread count, not the representative row', () => {
    // 聚合后一条代表一组：组里还有 9 条未读，它就是未读，
    // 哪怕"代表行"（组内最新那条）已经被标过已读
    expect(isUnreadNotification(item({ groupUnread: 9, isRead: 1 }))).toBe(true)
    expect(isUnreadNotification(item({ groupUnread: 0, isRead: 1 }))).toBe(false)
    expect(isUnreadNotification(item({ groupUnread: undefined }))).toBe(false)
  })
})
