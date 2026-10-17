import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from './auth'
import type { UsersVO } from '@/types/user'

const user: UsersVO = {
  id: 1,
  username: 'lin6035-star',
  nickname: '海林',
  avatarUrl: '',
  bio: 'Java learner',
  followersCount: 0,
  followingCount: 0,
  createdAt: '2026-07-03T10:00:00',
}

/** 造一个前端能解析的 JWT：本地只看 payload 里的 exp，签名不参与判断 */
function makeToken(expSeconds: number) {
  const payload = btoa(JSON.stringify({ userId: 1, exp: expSeconds }))
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '')

  return `header.${payload}.signature`
}

describe('useAuthStore', () => {
  beforeEach(() => {
    localStorage.clear()
    // 两个 storage 都要清：auth store 现在两处都读写，只清一个会让用例之间互相污染
    sessionStorage.clear()
    setActivePinia(createPinia())
  })

  it('persists token and usersVO after login or register succeeds', () => {
    const authStore = useAuthStore()

    authStore.setAuth('token-123', user, true)

    expect(authStore.token).toBe('token-123')
    expect(authStore.usersVO?.nickname).toBe('海林')
    expect(localStorage.getItem('blog_token')).toBe('token-123')
    expect(localStorage.getItem('blog_user')).toContain('lin6035-star')
  })

  it('restores auth state from localStorage after page refresh', () => {
    const token = makeToken(Math.floor(Date.now() / 1000) + 3600)
    localStorage.setItem('blog_token', token)
    localStorage.setItem('blog_user', JSON.stringify(user))
    const authStore = useAuthStore()

    authStore.restoreAuth()

    expect(authStore.token).toBe(token)
    expect(authStore.usersVO?.username).toBe('lin6035-star')
  })

  it('clears an expired token so the UI stops pretending to be logged in', () => {
    const expired = makeToken(Math.floor(Date.now() / 1000) - 60)
    localStorage.setItem('blog_token', expired)
    localStorage.setItem('blog_user', JSON.stringify(user))
    const authStore = useAuthStore()

    authStore.restoreAuth()

    expect(authStore.isLoggedIn).toBe(false)
    expect(authStore.usersVO).toBeNull()
    expect(localStorage.getItem('blog_token')).toBeNull()
    expect(localStorage.getItem('blog_user')).toBeNull()
  })

  it('treats a malformed token as expired rather than trusting it', () => {
    localStorage.setItem('blog_token', 'not-a-jwt')
    localStorage.setItem('blog_user', JSON.stringify(user))
    const authStore = useAuthStore()

    authStore.restoreAuth()

    expect(authStore.isLoggedIn).toBe(false)
    expect(localStorage.getItem('blog_token')).toBeNull()
  })

  it('clears stale token when stored user profile is missing', () => {
    localStorage.setItem('blog_token', 'stale-token')
    const authStore = useAuthStore()

    authStore.restoreAuth()

    expect(authStore.isLoggedIn).toBe(false)
    expect(localStorage.getItem('blog_token')).toBeNull()
  })

  it('clears local auth state after logout', () => {
    const authStore = useAuthStore()
    authStore.setAuth('token-123', user, true)

    authStore.clearAuth()

    expect(authStore.token).toBe('')
    expect(authStore.usersVO).toBeNull()
    expect(localStorage.getItem('blog_token')).toBeNull()
    expect(localStorage.getItem('blog_user')).toBeNull()
  })

  it('keeps credentials in sessionStorage when「记住我」is unchecked', () => {
    const authStore = useAuthStore()

    authStore.setAuth('token-123', user, false)

    // 不勾「记住我」→ 关掉标签页就没了，这是公用电脑上的正确姿势
    expect(sessionStorage.getItem('blog_token')).toBe('token-123')
    expect(localStorage.getItem('blog_token')).toBeNull()
  })

  it('drops the persistent copy when switching from remembered to not-remembered', () => {
    const authStore = useAuthStore()

    authStore.setAuth('token-123', user, true)
    authStore.setAuth('token-456', user, false)

    // 关键：localStorage 里那份必须消失。只写不清的话，下次打开浏览器它会被重新读出来，
    // "这次不记住"等于没生效——而且这个 bug 在"先勾后不勾"的路径上才出现，很难自然发现
    expect(localStorage.getItem('blog_token')).toBeNull()
    expect(sessionStorage.getItem('blog_token')).toBe('token-456')
  })

  it('restores a session-scoped login too', () => {
    const token = makeToken(Math.floor(Date.now() / 1000) + 3600)
    sessionStorage.setItem('blog_token', token)
    sessionStorage.setItem('blog_user', JSON.stringify(user))
    const authStore = useAuthStore()

    authStore.restoreAuth()

    expect(authStore.token).toBe(token)
    expect(authStore.isLoggedIn).toBe(true)
  })
})
