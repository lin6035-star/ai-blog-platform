<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import request from '@/utils/request'
import { useAuthStore } from '@/stores/auth'
import type { UsersVO } from '@/types/user'

const route = useRoute()
const authStore = useAuthStore()
const error = ref('')
const isPopup = !!window.opener

onMounted(async () => {
  // 后端校验失败（state 无效 / 过期 / 已被用过）会带着 error 跳回来，
  // 优先级高于 token：出现了它说明这次回调根本没被受理
  const upstreamError = route.query.error as string
  if (upstreamError) {
    error.value = upstreamError
    return
  }

  const oneTimeCode = route.query.code as string

  if (!oneTimeCode) {
    error.value = '授权失败：未收到登录凭证'
    return
  }

  try {
    // 2026-10-17 上线审计：回跳 URL 只带一次性 code（60 秒、消费即删），
    // 用它换 JWT——token 不再经过 URL（那会进 Nginx 日志与浏览器历史）。
    const exchanged = await request.post<{ token: string }>('/auth/github/exchange', {
      code: oneTimeCode,
    })
    const token = exchanged.data?.token
    if (!token) {
      throw new Error('登录凭证换取失败，请重试')
    }

    // 写入 token 供后续请求使用
    localStorage.setItem('blog_token', token)
    localStorage.setItem('blog_user', JSON.stringify({ id: 0 }))

    // 用 token 换取用户信息
    const result = await request.get<UsersVO>('/users/me')
    // OAuth 登录没有「记住我」这个选项，按记住处理（与 login 的默认勾选一致）：
    // 用户刚在 GitHub 上授权过，关掉浏览器就要重来一次说不过去
    authStore.setAuth(token, result.data, true)

    if (isPopup) {
      // 弹窗场景：通知主窗口后关闭弹窗
      window.opener!.postMessage({ type: 'github-oauth-success' }, window.location.origin)
      window.close()
    } else {
      // 非弹窗场景（直接被重定向）：正常跳转首页
      window.location.replace('/')
    }
  } catch (err) {
    error.value = err instanceof Error ? err.message : '登录失败'
    localStorage.removeItem('blog_token')
    localStorage.removeItem('blog_user')

    if (isPopup) {
      window.opener!.postMessage({ type: 'github-oauth-failed' }, window.location.origin)
      window.close()
    }
  }
})
</script>

<template>
  <div class="oauth-callback-page">
    <template v-if="!error">
      <n-spin size="large" />
      <p>正在登录…</p>
    </template>
    <template v-else>
      <p class="oauth-error">{{ error }}</p>
      <p>请关闭此窗口后重试</p>
    </template>
  </div>
</template>

<style scoped>
.oauth-callback-page {
  display: grid;
  place-items: center;
  place-content: center;
  gap: 20px;
  height: 100vh;
  color: #555;
  font-size: 16px;
}

.oauth-error {
  color: #e74c3c;
  font-weight: 700;
  margin: 0;
}
</style>
