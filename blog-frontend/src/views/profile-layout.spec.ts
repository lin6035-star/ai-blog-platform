// @vitest-environment node
// 本文件只读源码文本做断言，不需要 DOM；跑在 jsdom 下会让 node:fs 被 externalize 而收集失败
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const profileView = readFileSync(resolve(__dirname, 'ProfileView.vue'), 'utf8')

describe('profile article tabs layout', () => {
  it('shows six profile tabs and defaults to published articles', () => {
    expect(profileView).toContain("activeTab = ref<ProfileTabKey>('published')")
    expect(profileView).toContain("key: 'published'")
    expect(profileView).toContain("label: '我发布的'")
    expect(profileView).toContain("label: '我喜欢的'")
    expect(profileView).toContain("label: '我收藏的'")
    expect(profileView).toContain("label: '我评论的'")
    expect(profileView).toContain("label: '我关注的'")
    expect(profileView).toContain("label: '关注我的'")
  })

  it('loads article and relation tabs from the corresponding endpoints', () => {
    expect(profileView).toContain('const pageSize = 2')
    expect(profileView).toContain('myArticleApi.getList(page, pageSize)')
    expect(profileView).toContain('myArticleApi.getLiked(page, pageSize)')
    expect(profileView).toContain('myArticleApi.getFavorited(page, pageSize)')
    expect(profileView).toContain('myArticleApi.getCommented(page, pageSize)')
    expect(profileView).toContain("publicUserApi.getFollowing(user.value!.id, page, pageSize)")
    expect(profileView).toContain("publicUserApi.getFollowers(user.value!.id, page, pageSize)")
    expect(profileView).toContain('loadArticlesForTab(key, 1)')
    expect(profileView).not.toContain('profile-placeholder-panel')
    expect(profileView).not.toContain('这个模块的后端接口还没接入')
  })

  it('keeps edit controls only on published article management tab', () => {
    expect(profileView).toContain('v-if="activeTab === \'published\'"')
    expect(profileView).toContain('<span v-else class="profile-article-status muted">')
    expect(profileView).toContain('profile-tab-empty-title')
  })

  it('places profile identity in a top horizontal card above the content tabs', () => {
    expect(profileView).toContain('class="profile-top-row"')
    expect(profileView).toContain('class="profile-hero-card"')
    expect(profileView).toContain('class="profile-identity"')
    expect(profileView).toContain('class="profile-hero-actions"')
    expect(profileView).toContain('class="profile-side-card"')
    expect(profileView).toContain('class="profile-main"')
    expect(profileView.indexOf('class="profile-top-row"')).toBeLessThan(
      profileView.indexOf('class="profile-main"'),
    )
    expect(profileView).not.toContain('class="profile-sidebar"')
  })

  it('uses a single-column Juejin-like profile page layout', () => {
    expect(profileView).toContain('max-width: 1200px')
    expect(profileView).toContain('grid-template-columns: 1fr')
    expect(profileView).toContain('grid-template-columns: minmax(0, 1fr) 300px')
    expect(profileView).toContain('width: 100%')
    expect(profileView).not.toContain('justify-self: center')
    expect(profileView).not.toContain('grid-template-columns: 280px minmax(0, 1fr)')
  })

  it('keeps the profile content close to the top header', () => {
    expect(profileView).toContain('margin: 6px auto 0')
    expect(profileView).toContain('margin: 0 auto')
    expect(profileView).toContain('padding: 10px 18px 56px')
  })

  it('makes the relation content panel wider and taller than the profile card', () => {
    expect(profileView).toContain('width: 100%')
    expect(profileView).toContain('min-height: 520px')
    expect(profileView).toContain('padding: 92px 20px')
    expect(profileView.indexOf('class="profile-avatar-wrap"')).toBeLessThan(
      profileView.indexOf('class="profile-identity"'),
    )
  })

  it('positions nickname beside the avatar near the upper edge', () => {
    expect(profileView).toContain('align-items: start')
    expect(profileView).toContain('align-self: start')
    expect(profileView).toContain('padding-top: 6px')
    expect(profileView).toContain('text-align: left')
    expect(profileView).toContain('position: static')
  })

  it('moves the profile bio into the lower intro area and limits editing to 50 chars', () => {
    expect(profileView).toContain('const PROFILE_BIO_MAX_LENGTH = 50')
    expect(profileView).toContain('class="profile-bio-panel"')
    expect(profileView.indexOf('class="profile-identity"')).toBeLessThan(
      profileView.indexOf('class="profile-bio-panel"'),
    )
    expect(profileView.indexOf('class="profile-bio-panel"')).toBeLessThan(
      profileView.indexOf('class="profile-hero-actions"'),
    )
    expect(profileView).toContain(':maxlength="PROFILE_BIO_MAX_LENGTH"')
    expect(profileView).toContain('.slice(0, PROFILE_BIO_MAX_LENGTH)')
  })

  it('shows reserved follow metrics and account join date from current user profile', () => {
    expect(profileView).toContain('const joinedAtText = computed')
    expect(profileView).toContain('formatArticleDate(user.value.createdAt)')
    expect(profileView).toContain('关注了')
    expect(profileView).toContain('关注者')
    expect(profileView).toContain('user?.followingCount')
    expect(profileView).toContain('user?.followersCount')
    expect(profileView).toContain('加入于')
    expect(profileView).toContain('{{ joinedAtText }}')
  })

  it('reserves the wallet slot while the balance request is pending', () => {
    const walletCard = readFileSync(resolve(__dirname, '../components/common/WalletBalanceCard.vue'), 'utf8')
    expect(walletCard).toContain('const walletLoading = ref(true)')
    expect(walletCard).toContain('class="wallet-card wallet-card-placeholder"')
    expect(walletCard).toContain('v-else-if="walletLoading"')
    expect(walletCard).toContain('额度暂不可用')
    expect(walletCard).toContain('.wallet-card-placeholder')
    expect(walletCard).toContain('min-height: 102px')
  })

  it('uses a history back button instead of refreshing home', () => {
    expect(profileView).toContain('function goBack()')
    expect(profileView).toContain('router.back()')
    expect(profileView).toContain('class="profile-back"')
    expect(profileView).toContain('@click="goBack"')
    expect(profileView).not.toContain('refresh: String(Date.now())')
  })
})
