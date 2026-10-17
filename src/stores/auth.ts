import { defineStore } from 'pinia'
import type { UsersVO } from '@/types/user'

const TOKEN_KEY = 'blog_token'
const USER_KEY = 'blog_user'

/**
 * 「记住我」决定凭据放哪个 storage：
 * - 勾选 → `localStorage`：关掉浏览器再打开仍是登录态
 * - 不勾 → `sessionStorage`：**关掉标签页就没了**（公用电脑上的正确姿势）
 *
 * 注意这只是**客户端的一半**：登录态能不能长期保持，最终由后端签发的 token 有效期决定
 * （勾选 30 天 / 不勾 24 小时，见 `application.yml` 的 `jwt.expire`）。
 * 客户端存哪儿只解决"浏览器关掉后还在不在"。
 *
 * 两处都读、两处都清，是为了兼容"上次勾了、这次没勾"的切换：只认一个 storage 的话，
 * 切换选项后会出现「旧的那份还在、新的读不到」的诡异状态。
 */
function allStorages(): Storage[] {
  return [localStorage, sessionStorage]
}

function readItem(key: string): string | null {
  for (const storage of allStorages()) {
    const value = storage.getItem(key)
    if (value) {
      return value
    }
  }
  return null
}

function removeItem(key: string) {
  for (const storage of allStorages()) {
    storage.removeItem(key)
  }
}

function readStoredUser(): UsersVO | null {
  const rawUser = readItem(USER_KEY)

  if (!rawUser) {
    return null
  }

  try {
    return JSON.parse(rawUser) as UsersVO
  } catch {
    removeItem(USER_KEY)
    return null
  }
}

/**
 * 本地判断 JWT 是否过期：只读 exp，不验签（签名是后端的事）。
 *
 * 目的是让"已过期"在刷新页面时就被发现，而不是等用户点了操作、请求返回 401
 * 才知道——后者会让界面一直装作还登录着。
 */
function isTokenExpired(token: string): boolean {
  const payload = token.split('.')[1]

  if (!payload) {
    return true
  }

  try {
    // JWT 用 base64url，先转回 base64 并补齐 padding 再解
    const normalized = payload.replace(/-/g, '+').replace(/_/g, '/')
    const padded = normalized.padEnd(
      normalized.length + ((4 - (normalized.length % 4)) % 4),
      '=',
    )
    const { exp } = JSON.parse(atob(padded)) as { exp?: number }

    // exp 是秒；拿不到 exp 一律当作已过期，宁可让用户重登也不留下假登录态
    return typeof exp !== 'number' || exp * 1000 <= Date.now()
  } catch {
    return true
  }
}

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: '',
    usersVO: null as UsersVO | null,
  }),
  getters: {
    isLoggedIn: (state) => Boolean(state.token && state.usersVO),
    displayName: (state) =>
      state.usersVO?.nickname || state.usersVO?.username || '创作者',
  },
  actions: {
    restoreAuth() {
      const token = readItem(TOKEN_KEY) ?? ''
      const usersVO = readStoredUser()

      if (!token || !usersVO || isTokenExpired(token)) {
        this.clearAuth()
        return
      }

      this.token = token
      this.usersVO = usersVO
    },
    /**
     * @param remember 记住我：`true` → localStorage，`false` → sessionStorage
     */
    setAuth(token: string, usersVO: UsersVO, remember: boolean) {
      this.token = token
      this.usersVO = usersVO

      // **先清掉另一处再写**：从「记住」切到「不记住」时，localStorage 里那份必须消失，
      // 否则下次打开浏览器它会被重新读出来 —— "不记住"等于没生效
      removeItem(TOKEN_KEY)
      removeItem(USER_KEY)

      const target = remember ? localStorage : sessionStorage
      target.setItem(TOKEN_KEY, token)
      target.setItem(USER_KEY, JSON.stringify(usersVO))
    },
    updateUser(usersVO: UsersVO) {
      this.usersVO = usersVO
      // 写回原来那一处（哪边存着 token 就写哪边），不要凭空造出第二份
      const target = localStorage.getItem(TOKEN_KEY) ? localStorage : sessionStorage
      target.setItem(USER_KEY, JSON.stringify(usersVO))
    },
    clearAuth() {
      this.token = ''
      this.usersVO = null
      removeItem(TOKEN_KEY)
      removeItem(USER_KEY)
    },
  },
})
