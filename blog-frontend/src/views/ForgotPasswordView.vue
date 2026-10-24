<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useMessage } from 'naive-ui'
import { authApi } from '@/api/auth'
import SliderCaptcha from '@/components/auth/SliderCaptcha.vue'
import type { ForgotPasswordDTO } from '@/types/user'

const message = useMessage()

const loading = ref(false)
const captchaVisible = ref(false)
const form = reactive<ForgotPasswordDTO>({ email: '', captchaVerification: '' })

/** 先过验证码再提交：这是未登录接口，不加验证码就能被脚本拿去刷邮箱 */
function submit(e: Event) {
  e.preventDefault()
  if (!form.email.trim()) {
    message.warning('请输入邮箱')
    return
  }
  captchaVisible.value = true
}

async function onCaptchaSuccess(token: string) {
  captchaVisible.value = false
  form.captchaVerification = token
  await doSubmit()
}

function onCaptchaError(msg: string) {
  captchaVisible.value = false
  message.error(msg)
}

async function doSubmit() {
  loading.value = true
  try {
    await authApi.forgotPassword(form)

    // ⚠️ 文案必须固定成这一句，**不要**改成「已发送到 xxx@xx」。
    // 后端刻意让「邮箱存在 / 不存在 / 那是 GitHub 账号」三种情况返回完全一样的响应，
    // 这里若报出具体邮箱，等于把防枚举那道功夫在界面上又泄回去。
    message.success('如果该邮箱已注册，我们已经发送了重置链接，请查收（也看看垃圾箱）', {
      duration: 6000,
    })
    form.email = ''
  } catch (error) {
    message.error(error instanceof Error ? error.message : '提交失败，请稍后重试')
  } finally {
    // 凭证是一次性的：无论成败都作废，下次提交重新滑
    form.captchaVerification = ''
    loading.value = false
  }
}
</script>

<template>
  <div class="pwd-page">
    <div class="pwd-card">
      <h1 class="pwd-title">忘记密码</h1>
      <p class="pwd-subtitle">输入注册时使用的邮箱，我们会把重置链接发过去</p>

      <form @submit="submit">
        <div class="pwd-field">
          <label for="email">邮箱</label>
          <input
            id="email"
            v-model="form.email"
            type="email"
            placeholder="you@example.com"
            autocomplete="email"
          />
        </div>

        <button type="submit" class="pwd-btn" :disabled="loading">
          {{ loading ? '提交中…' : '发送重置链接' }}
        </button>
      </form>

      <p class="pwd-foot">
        <router-link to="/login">返回登录</router-link>
        <span class="pwd-sep">·</span>
        <router-link to="/register">注册新账号</router-link>
      </p>
    </div>

    <SliderCaptcha
      :visible="captchaVisible"
      @success="onCaptchaSuccess"
      @close="captchaVisible = false"
      @error="onCaptchaError"
    />
  </div>
</template>

<style scoped>
/* 刻意不复用 login-page 的两栏布局：那一版左半边是 SVG 角色动画，
   这里是单人操作的窄流程，居中卡片更合适 */
.pwd-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f5f5f5;
  padding: 24px;
}

.pwd-card {
  width: 100%;
  max-width: 400px;
  background: #fff;
  border-radius: 12px;
  padding: 40px 36px;
  box-shadow: 0 8px 30px rgba(0, 0, 0, 0.06);
}

.pwd-title {
  font-size: 24px;
  font-weight: 700;
  margin: 0 0 8px;
  color: #111;
}

.pwd-subtitle {
  font-size: 14px;
  color: #888;
  margin: 0 0 28px;
  line-height: 1.5;
}

.pwd-field {
  margin-bottom: 20px;
}

.pwd-field label {
  display: block;
  font-size: 13px;
  color: #333;
  margin-bottom: 8px;
}

.pwd-field input {
  width: 100%;
  height: 44px;
  padding: 0 14px;
  border: 1px solid #ddd;
  border-radius: 8px;
  font-size: 14px;
  outline: none;
  box-sizing: border-box;
  transition: border-color 0.2s;
}

.pwd-field input:focus {
  border-color: #111;
}

.pwd-btn {
  width: 100%;
  height: 46px;
  border: none;
  border-radius: 8px;
  background: #111;
  color: #fff;
  font-size: 15px;
  cursor: pointer;
  transition: opacity 0.2s;
}

.pwd-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.pwd-foot {
  margin: 24px 0 0;
  text-align: center;
  font-size: 13px;
  color: #999;
}

.pwd-foot a {
  color: #111;
  text-decoration: none;
}

.pwd-foot a:hover {
  text-decoration: underline;
}

.pwd-sep {
  margin: 0 8px;
}
</style>
