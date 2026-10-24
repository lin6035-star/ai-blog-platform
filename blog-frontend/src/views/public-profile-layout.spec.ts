// @vitest-environment node
// 本文件只读源码文本做断言，不需要 DOM；跑在 jsdom 下会让 node:fs 被 externalize 而收集失败
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const publicProfileView = readFileSync(resolve(__dirname, 'PublicProfileView.vue'), 'utf8')
const routerFile = readFileSync(resolve(__dirname, '../router/index.ts'), 'utf8')

describe('public profile page layout', () => {
  it('registers a public user profile route separate from the current user center', () => {
    expect(routerFile).toContain("path: '/users/:id'")
    expect(routerFile).toContain("name: 'public-profile'")
    expect(routerFile).toContain('PublicProfileView')
    expect(routerFile).not.toContain("path: '/users/:id',\n      name: 'profile'")
  })

  it('defaults to published public articles and keeps public tabs separate', () => {
    expect(publicProfileView).toContain("activeTab = ref<PublicProfileTabKey>('articles')")
    expect(publicProfileView).toContain("key: 'articles'")
    expect(publicProfileView).toContain("label: '文章'")
    expect(publicProfileView).toContain("label: '喜欢'")
    expect(publicProfileView).toContain("label: '收藏'")
    expect(publicProfileView).toContain("label: '评论过'")
    expect(publicProfileView).toContain("label: '关注了'")
    expect(publicProfileView).toContain("label: '关注者'")
    expect(publicProfileView).toContain('publicUserApi.getArticles')
    expect(publicProfileView).toContain('publicUserApi.getLiked')
    expect(publicProfileView).toContain('publicUserApi.getFavorited')
    expect(publicProfileView).toContain('publicUserApi.getCommented')
    expect(publicProfileView).toContain('publicUserApi.getFollowing')
    expect(publicProfileView).toContain('publicUserApi.getFollowers')
  })

  it('renders paged relation rows that can open another public profile', () => {
    expect(publicProfileView).toContain('relationUsers')
    expect(publicProfileView).toContain('public-relation-list')
    expect(publicProfileView).toContain('goToUserProfile')
    expect(publicProfileView).toContain("@click=\"goToUserProfile(user.id)\"")
  })

  it('renders public-only profile actions and avoids current-user controls', () => {
    expect(publicProfileView).toContain('profile.followed')
    expect(publicProfileView).toContain('handleFollowToggle')
    expect(publicProfileView).toContain('profile.self')
    expect(publicProfileView).not.toContain('退出登录')
    expect(publicProfileView).not.toContain('刷新资料')
    expect(publicProfileView).not.toContain('去写文章')
    expect(publicProfileView).not.toContain('n-upload')
  })
})
