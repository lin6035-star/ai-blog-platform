<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useMessage } from 'naive-ui'
import { authApi } from '@/api/auth'
import type { ResetPasswordDTO } from '@/types/user'

const route = useRoute()
const router = useRouter()
const message = useMessage()

const loading = ref(false)
const done = ref(false)
const failed = ref('')

const form = reactive<Omit<ResetPasswordDTO, 'token'>>({
  newPassword: '',
})

/**
 * token 从 query 里取。**取完立刻从地址栏抹掉**：
 * 留在 URL 里的话，用户点页面上的任何外链都会通过 Referer 把它带给第三方，
 * 而这个 token 等价于"改这个账号密码的权限"。
 */
const token = ref('')

onMounted(() => {
  token.value = String(route.query.token ?? '')
  if (!token.value) {
    failed.value = '链接不完整，请回到登录页重新申请'
  }
  if (route.query.token) {
    void router.replace({ path: '/reset-password' })
  }
})

async function submit(e: Event) {
  e.preventDefault()
  if (form.newPassword.length < 1) {
    message.warning('请输入新密码')
    return
  }

  loading.value = true
  try {
    await authApi.resetPassword({ token: token.value, newPassword: form.newPassword })
    done.value = true
    message.success('密码已重置，请用新密码登录')

    // 后端已经把这个账号**所有**已签发的 token 都作废了（含其它设备），
    // 所以这里不需要清本地登录态——本来也进不来
    setTimeout(() => void router.push('/login'), 2000)
  } catch (error) {
    failed.value = error instanceof Error ? error.message : '重置失败，请重新申请'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="pwd-page">
    <div class="pwd-card">
      <h1 class="pwd-title">重置密码</h1>

      <!-- 链接不可用：给出明确的下一步，而不是让用户对着表单反复重试 -->
      <template v-if="failed">
        <p class="pwd-subtitle pwd-error">{{ failed }}</p>
        <router-link class="pwd-btn pwd-btn-link" to="/forgot-password">
          重新申请一次
        </router-link>
      </template>

      <template v-else-if="done">
        <p class="pwd-subtitle">密码已重置，正在跳转到登录页…</p>
      </template>

      <template v-else>
        <p class="pwd-subtitle">设置一个新密码。链接 30 分钟内有效，且只能用一次</p>
        <form @submit="submit">
          <div class="pwd-field">
            <label for="new-password">新密码</label>
            <input
              id="new-password"
              v-model="form.newPassword"
              type="password"
              placeholder="••••••••"
              autocomplete="new-password"
            />
          </div>
          <button type="submit" class="pwd-btn" :disabled="loading">
            {{ loading ? '提交中…' : '确认修改' }}
          </button>
        </form>
      </template>

      <p class="pwd-foot">
        <router-link to="/login">返回登录</router-link>
      </p>
    </div>
  </div>
</template>

<style scoped>
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

.pwd-error {
  color: #e74c3c;
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
  display: block;
  width: 100%;
  height: 46px;
  border: none;
  border-radius: 8px;
  background: #111;
  color: #fff;
  font-size: 15px;
  cursor: pointer;
  transition: opacity 0.2s;
  box-sizing: border-box;
  line-height: 46px;
  text-align: center;
  text-decoration: none;
}

.pwd-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.pwd-btn-link:hover {
  opacity: 0.85;
}

.pwd-foot {
  margin: 24px 0 0;
  text-align: center;
  font-size: 13px;
}

.pwd-foot a {
  color: #111;
  text-decoration: none;
}

.pwd-foot a:hover {
  text-decoration: underline;
}
</style>
