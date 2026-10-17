<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useMessage } from 'naive-ui'
import { authApi } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'
import AnimatedCharacters from '@/components/auth/AnimatedCharacters.vue'
import SliderCaptcha from '@/components/auth/SliderCaptcha.vue'
import { useEyeTracking } from '@/composables/useEyeTracking'
import type { LoginDTO } from '@/types/user'

/* ---- 眼动追踪 ---- */
useEyeTracking()

const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()
const message = useMessage()

/* ---- 表单状态 ---- */
const loading = ref(false)
const githubLoading = ref(false)
// 默认勾选「记住登录状态」：与改造前的实际行为保持一致（那时复选框写死 checked、
// token 也存 localStorage），不给用户"升级后反而要天天重新登录"的倒退感
const form = reactive<LoginDTO>({
  username: '',
  password: '',
  captchaVerification: '',
  rememberMe: true,
})
const formEl = ref<HTMLFormElement>()

/* ---- 滑块验证码 ---- */
const captchaVisible = ref(false)

/* ---- 角色交互状态 ---- */
type FocusType = 'none' | 'email' | 'password'
const focusType = ref<FocusType>('none')
const passwordVisible = ref(false)
const loginSuccess = ref(false)
const loginFail = ref(false)

/* ---- 成功后的飞溅动画数据 ---- */
interface Splash {
  x: number
  y: number
  size: number
  color: string
}
const splashes = ref<Splash[]>([])
const showWipe = ref(false)

/* ---- 计算 CSS class ---- */
const pageClass = () => {
  const classes: string[] = []
  if (focusType.value === 'email') classes.push('focus-email')
  if (focusType.value === 'password') {
    classes.push(passwordVisible.value ? 'focus-password-visible' : 'focus-password-hidden')
  }
  if (loginSuccess.value) classes.push('login-success')
  if (loginFail.value) classes.push('login-fail')
  return classes
}

/* ---- 输入框焦点 ---- */
function onUsernameFocus() {
  focusType.value = 'email'
}
function onPasswordFocus() {
  focusType.value = 'password'
}
function onBlur() {
  focusType.value = 'none'
}

/* ---- 密码显隐 ---- */
function togglePassword(e: MouseEvent) {
  e.preventDefault()
  passwordVisible.value = !passwordVisible.value
  // 保持密码框焦点
  const pwdInput = formEl.value?.querySelector<HTMLInputElement>('#password')
  pwdInput?.focus()
}

/* ---- 登录提交 ---- */
function onEyeMousedown(e: MouseEvent) {
  e.preventDefault()
}

async function submit(e: Event) {
  e.preventDefault()

  if (!form.username.trim() || !form.password) {
    message.warning('请输入用户名和密码')
    return
  }

  // 登录改成两步：先过滑块拿一次性凭证，再带凭证提交。
  // 服务端强制校验（CaptchaVerifier），没有凭证的登录请求会被 40002 拒绝——
  // 这不是「可选加强」，所以不能在这里直接调 login。
  captchaVisible.value = true
}

/** 滑块通过：拿到一次性凭证后立刻提交登录 */
async function onCaptchaSuccess(token: string) {
  captchaVisible.value = false
  form.captchaVerification = token
  await doLogin()
}

/** SDK 加载 / 初始化失败：关掉弹窗并提示，不要让遮罩把页面卡死 */
function onCaptchaError(msg: string) {
  captchaVisible.value = false
  message.error(msg)
}

async function doLogin() {
  loading.value = true

  try {
    const result = await authApi.login(form)
    authStore.setAuth(result.data.token, result.data.usersVO, form.rememberMe)

    // 触发成功动画
    loginSuccess.value = true

    // // 1s 后彩色飞溅（暂时关闭）
    // setTimeout(() => {
    //   const vw = window.innerWidth
    //   const vh = window.innerHeight
    //   const size = Math.max(vw, vh) * 3
    //   splashes.value = [
    //     { x: vw * 0.15, y: vh * 0.35, size, color: '#A7F3D0' },
    //     { x: vw * 0.5, y: vh * 0.6, size, color: '#C4B5FD' },
    //     { x: vw * 0.8, y: vh * 0.3, size, color: '#FECDD3' },
    //   ]
    // }, 1000)

    // 1s 后白色遮罩 → 跳转
    setTimeout(() => {
      showWipe.value = true
    }, 1000)

    // 2s 后跳转首页
    setTimeout(() => {
      router.push(String(route.query.redirect || '/'))
    }, 2000)
  } catch (error) {
    const msg = error instanceof Error ? error.message : '登录失败'
    message.error(msg)

    // 凭证是一次性的（服务端 getAndRemove）：这次已经消费掉了，不清空的话
    // 用户再点登录会拿着过期凭证来回撞 40002
    form.captchaVerification = ''

    // 触发失败动画
    loginFail.value = true
    setTimeout(() => {
      loginFail.value = false
    }, 800)
  } finally {
    loading.value = false
  }
}

/* ---- GitHub OAuth 弹窗登录 ---- */
async function handleGitHubLogin() {
  if (githubLoading.value) return
  githubLoading.value = true

  try {
    const result = await authApi.getGitHubAuthUrl()
    const authUrl = result.data.url

    // 打开 GitHub 授权弹窗
    const left = window.screenX + (window.innerWidth - 600) / 2
    const top = window.screenY + (window.innerHeight - 700) / 2
    const popup = window.open(
      authUrl,
      'github-oauth',
      `width=600,height=700,left=${left},top=${top}`,
    )

    if (!popup) {
      message.warning('请允许浏览器弹窗后重试')
      return
    }

    // 监听弹窗发来的消息
    const cleanup = (e: MessageEvent) => {
      if (e.origin !== window.location.origin) return

      if (e.data?.type === 'github-oauth-success') {
        window.removeEventListener('message', cleanup)
        // 弹窗已写入 localStorage，主窗口恢复登录态
        authStore.restoreAuth()
        // 触发表面动画后跳转
        loginSuccess.value = true
        setTimeout(() => { showWipe.value = true }, 1000)
        setTimeout(() => { router.push(String(route.query.redirect || '/')) }, 2000)
      } else if (e.data?.type === 'github-oauth-failed') {
        window.removeEventListener('message', cleanup)
        message.error('GitHub 授权失败，请重试')
      }
    }

    window.addEventListener('message', cleanup)
  } catch (error) {
    const msg = error instanceof Error ? error.message : '获取授权链接失败'
    message.error(msg)
  } finally {
    githubLoading.value = false
  }
}
</script>

<template>
  <div class="login-page" :class="pageClass()">
    <!-- 左上角品牌 -->
    <router-link class="login-brand" to="/">
      <span class="login-brand-mark">H</span>
      <span class="login-brand-text">海林Blog</span>
    </router-link>

    <!-- 左面板：SVG 角色 -->
    <div class="login-left">
      <AnimatedCharacters />
    </div>

    <!-- 右面板：登录表单 -->
    <div class="login-right">
      <div class="form-wrapper">
        <!-- Logo -->
        <svg class="login-star anim-item d-1" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
          <path d="M12 2L14.59 9.41L24 12L14.59 14.59L12 24L9.41 14.59L0 12L9.41 9.41L12 0Z" fill="#111" />
        </svg>

        <h1 class="login-title anim-item d-2">欢迎来到海林Blog</h1>
        <p class="login-subtitle anim-item d-3">请输入您的账号信息</p>

        <form ref="formEl" @submit="submit">
          <!-- 用户名 -->
          <div class="login-input-group anim-item d-4">
            <label for="username">用户名</label>
            <div class="login-input-wrap">
              <input
                id="username"
                v-model="form.username"
                type="text"
                placeholder="请输入用户名"
                autocomplete="username"
                @focus="onUsernameFocus"
                @blur="onBlur"
              />
            </div>
          </div>

          <!-- 密码 -->
          <div class="login-input-group anim-item d-5">
            <label for="password">密码</label>
            <div class="login-input-wrap">
              <input
                id="password"
                v-model="form.password"
                :type="passwordVisible ? 'text' : 'password'"
                placeholder="••••••••"
                autocomplete="current-password"
                @focus="onPasswordFocus"
                @blur="onBlur"
              />

              <button
                type="button"
                class="login-eye-toggle"
                tabindex="-1"
                @mousedown="onEyeMousedown"
                @click="togglePassword"
              >
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                  <template v-if="!passwordVisible">
                    <path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24" />
                    <line x1="1" y1="1" x2="23" y2="23" />
                  </template>
                  <template v-else>
                    <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z" />
                    <circle cx="12" cy="12" r="3" />
                  </template>
                </svg>
              </button>
            </div>
          </div>

          <!-- 选项 -->
          <div class="login-options anim-item d-6">
            <label class="login-checkbox-label">
              <input v-model="form.rememberMe" type="checkbox" /> 记住登录状态
            </label>
            <router-link to="/forgot-password" class="login-forgot">忘记密码？</router-link>
          </div>

          <!-- 登录按钮 -->
          <button
            type="submit"
            class="login-btn login-btn-primary anim-item d-7"
            :disabled="loading"
          >
            {{ loading ? '登录中…' : '登录' }}
          </button>
        </form>

        <!-- GitHub 登录 -->
        <div class="login-divider anim-item d-8">
          <span>或</span>
        </div>

        <button
          type="button"
          class="login-btn login-btn-github anim-item d-8"
          :disabled="githubLoading"
          @click="handleGitHubLogin"
        >
          <svg class="github-icon" width="20" height="20" viewBox="0 0 24 24" fill="currentColor">
            <path d="M12 0C5.37 0 0 5.37 0 12c0 5.31 3.435 9.795 8.205 11.385.6.105.825-.255.825-.57 0-.285-.015-1.23-.015-2.235-3.015.555-3.795-.735-4.035-1.41-.135-.345-.72-1.41-1.23-1.695-.42-.225-1.02-.78-.015-.795.945-.015 1.62.87 1.845 1.23 1.08 1.815 2.805 1.305 3.495.99.105-.78.42-1.305.765-1.605-2.67-.3-5.46-1.335-5.46-5.925 0-1.305.465-2.385 1.23-3.225-.12-.3-.54-1.53.12-3.18 0 0 1.005-.315 3.3 1.23.96-.27 1.98-.405 3-.405s2.04.135 3 .405c2.295-1.56 3.3-1.23 3.3-1.23.66 1.65.24 2.88.12 3.18.765.84 1.23 1.905 1.23 3.225 0 4.605-2.805 5.625-5.475 5.925.435.375.81 1.095.81 2.22 0 1.605-.015 2.895-.015 3.3 0 .315.225.69.825.57A12.02 12.02 0 0 0 24 12c0-6.63-5.37-12-12-12z" />
          </svg>
          {{ githubLoading ? '跳转中…' : 'GitHub 登录' }}
        </button>

        <p class="login-signup anim-item d-8">
          还没有账号？
          <router-link to="/register">去注册</router-link>
        </p>
      </div>
    </div>

    <!-- 成功后的彩色飞溅 -->
    <div
      v-for="(s, i) in splashes"
      :key="i"
      class="color-splash pop"
      :style="{
        width: `${s.size}px`,
        height: `${s.size}px`,
        left: `${s.x - s.size / 2}px`,
        top: `${s.y - s.size / 2}px`,
        background: s.color,
      }"
    />

    <!-- 滑块验证码弹窗（SDK 自身没有遮罩，遮罩与居中由组件提供） -->
    <SliderCaptcha
      :visible="captchaVisible"
      @success="onCaptchaSuccess"
      @close="captchaVisible = false"
      @error="onCaptchaError"
    />

    <!-- 白色遮罩 -->
    <div v-if="showWipe" class="white-wipe sweep" />
  </div>
</template>
