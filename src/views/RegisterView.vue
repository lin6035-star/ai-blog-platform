<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useMessage } from 'naive-ui'
import { authApi } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'
import AnimatedCharacters from '@/components/auth/AnimatedCharacters.vue'
import SliderCaptcha from '@/components/auth/SliderCaptcha.vue'
import { useEyeTracking } from '@/composables/useEyeTracking'
import type { RegisterDTO } from '@/types/user'

/* ---- 眼动追踪 ---- */
useEyeTracking()

const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()
const message = useMessage()

/* ---- 表单状态 ---- */
const loading = ref(false)
const githubLoading = ref(false)
const form = reactive<RegisterDTO>({
  username: '',
  nickname: '',
  email: '',
  password: '',
  confirmPassword: '',
  captchaVerification: '',
})
const formEl = ref<HTMLFormElement>()

/* ---- 滑块验证码 ---- */
const captchaVisible = ref(false)

/* ---- 角色交互状态 ---- */
type FocusType = 'none' | 'email' | 'password'
const focusType = ref<FocusType>('none')
const passwordVisible = ref(false)
const confirmVisible = ref(false)

// 任一密码框可见 → 角色探头
const anyPasswordVisible = computed(() => passwordVisible.value || confirmVisible.value)

const loginSuccess = ref(false)
const loginFail = ref(false)

/* ---- 前端校验 ---- */
const confirmError = ref('')

function validateForm(): boolean {
  confirmError.value = ''

  if (!form.username.trim()) {
    message.warning('请输入用户名')
    return false
  }
  if (!form.nickname.trim()) {
    message.warning('请输入昵称')
    return false
  }
  // 邮箱只做「非空」检查：格式交给后端 @Email（两边规则不一致时，
  // 前端拦下来的和后端拒绝的会不一样，用户看到两种口径反而更困惑）
  if (!form.email.trim()) {
    message.warning('请输入邮箱')
    return false
  }
  if (!form.password) {
    message.warning('请输入密码')
    return false
  }
  if (form.password !== form.confirmPassword) {
    confirmError.value = '两次密码不一致'
    loginFail.value = true
    setTimeout(() => { loginFail.value = false }, 800)
    return false
  }
  return true
}

/* ---- 成功后的飞溅动画 ---- */
interface Splash {
  x: number; y: number; size: number; color: string
}
const splashes = ref<Splash[]>([])
const showWipe = ref(false)

/* ---- 计算 CSS class ---- */
const pageClass = computed(() => {
  const classes: string[] = []
  if (focusType.value === 'email') classes.push('focus-email')
  if (focusType.value === 'password') {
    classes.push(anyPasswordVisible.value ? 'focus-password-visible' : 'focus-password-hidden')
  }
  if (loginSuccess.value) classes.push('login-success')
  if (loginFail.value) classes.push('login-fail')
  return classes
})

/* ---- 输入框焦点 ---- */
function onTextFocus() { focusType.value = 'email' }
function onPasswordFocus() { focusType.value = 'password' }
function onBlur() { focusType.value = 'none' }

/* ---- 密码显隐 ---- */
function togglePassword(e: MouseEvent) {
  e.preventDefault()
  passwordVisible.value = !passwordVisible.value
  const el = formEl.value?.querySelector<HTMLInputElement>('#password')
  el?.focus()
}
function toggleConfirm(e: MouseEvent) {
  e.preventDefault()
  confirmVisible.value = !confirmVisible.value
  const el = formEl.value?.querySelector<HTMLInputElement>('#confirm-password')
  el?.focus()
}
function onEyeMousedown(e: MouseEvent) { e.preventDefault() }

/* ---- 注册提交 ---- */
async function submit(e: Event) {
  e.preventDefault()

  if (!validateForm()) return

  // 同登录：先过滑块拿一次性凭证，再提交注册。服务端 RegistrationDTO 上
  // captchaVerification 是 @NotBlank，缺了直接 40001
  captchaVisible.value = true
}

async function onCaptchaSuccess(token: string) {
  captchaVisible.value = false
  form.captchaVerification = token
  await doRegister()
}

/** SDK 加载 / 初始化失败：关掉弹窗并提示，不要让遮罩把页面卡死 */
function onCaptchaError(msg: string) {
  captchaVisible.value = false
  message.error(msg)
}

async function doRegister() {
  loading.value = true

  try {
    const result = await authApi.register(form)
    // 注册后必然还在自己的设备上 → 与后端 buildAuthVO(user, longTokenTtl) 对齐
    authStore.setAuth(result.data.token, result.data.usersVO, true)
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

    setTimeout(() => { showWipe.value = true }, 1000)
    setTimeout(() => { router.push(String(route.query.redirect || '/')) }, 2000)
  } catch (error) {
    const msg = error instanceof Error ? error.message : '注册失败'
    message.error(msg)

    // 凭证一次性，消费后必须清掉，否则用户重试会一直撞「验证已失效」
    form.captchaVerification = ''

    loginFail.value = true
    setTimeout(() => { loginFail.value = false }, 800)
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

    const cleanup = (e: MessageEvent) => {
      if (e.origin !== window.location.origin) return

      if (e.data?.type === 'github-oauth-success') {
        window.removeEventListener('message', cleanup)
        // 弹窗已写入 localStorage，主窗口恢复登录态
        authStore.restoreAuth()
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
  <div class="login-page register-page" :class="pageClass">
    <!-- 左上角品牌 -->
    <router-link class="login-brand" to="/">
      <span class="login-brand-mark">H</span>
      <span class="login-brand-text">海林Blog</span>
    </router-link>

    <!-- 左面板：SVG 角色 -->
    <div class="login-left">
      <AnimatedCharacters />
    </div>

    <!-- 右面板：注册表单 -->
    <div class="login-right">
      <div class="form-wrapper">
        <svg class="login-star anim-item d-1" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
          <path d="M12 2L14.59 9.41L24 12L14.59 14.59L12 24L9.41 14.59L0 12L9.41 9.41L12 0Z" fill="#111" />
        </svg>

        <h1 class="login-title anim-item d-2">创建账号</h1>
        <p class="login-subtitle anim-item d-3">加入 海林Blog,开始记录你的技术轨迹</p>

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
                @focus="onTextFocus"
                @blur="onBlur"
              />
            </div>
          </div>

          <!-- 昵称 -->
          <div class="login-input-group anim-item d-4">
            <label for="nickname">昵称</label>
            <div class="login-input-wrap">
              <input
                id="nickname"
                v-model="form.nickname"
                type="text"
                placeholder="给自己取个名字"
                @focus="onTextFocus"
                @blur="onBlur"
              />
            </div>
          </div>

          <!-- 邮箱：找回密码的唯一通道，必填 -->
          <div class="login-input-group anim-item d-4">
            <label for="email">邮箱</label>
            <div class="login-input-wrap">
              <input
                id="email"
                v-model="form.email"
                type="email"
                placeholder="you@example.com"
                autocomplete="email"
                @focus="onTextFocus"
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
                autocomplete="new-password"
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

          <!-- 确认密码 -->
          <div class="login-input-group anim-item d-5">
            <label for="confirm-password">确认密码</label>
            <div class="login-input-wrap">
              <input
                id="confirm-password"
                v-model="form.confirmPassword"
                :type="confirmVisible ? 'text' : 'password'"
                placeholder="••••••••"
                autocomplete="new-password"
                @focus="onPasswordFocus"
                @blur="onBlur"
              />
              <button
                type="button"
                class="login-eye-toggle"
                tabindex="-1"
                @mousedown="onEyeMousedown"
                @click="toggleConfirm"
              >
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                  <template v-if="!confirmVisible">
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
            <p v-if="confirmError" style="color: #e74c3c; font-size: 13px; margin-top: 6px;">{{ confirmError }}</p>
          </div>

          <!-- 注册按钮 -->
          <button
            type="submit"
            class="login-btn login-btn-primary anim-item d-7"
            :disabled="loading"
          >
            {{ loading ? '注册中…' : '注册并登录' }}
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
          已有账号？
          <router-link to="/login">去登录</router-link>
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
