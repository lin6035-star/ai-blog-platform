<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useDialog, useMessage } from 'naive-ui'
import {
  ChevronDown,
  DocumentTextOutline,
  FileTrayOutline,
  NotificationsOutline,
  Person,
  SearchOutline,
} from '@vicons/ionicons5'
import { storeToRefs } from 'pinia'
import { useAuthStore } from '@/stores/auth'
import { useLoginGuard } from '@/composables/useLoginGuard'
import { authApi } from '@/api/auth'
import { notificationApi } from '@/api/notification'
import { unreadCount } from '@/utils/notificationBadge'
import SiteActivityCard from '@/components/common/SiteActivityCard.vue'

const route = useRoute()
const router = useRouter()
const dialog = useDialog()
const message = useMessage()
const authStore = useAuthStore()
const { isLoggedIn, usersVO } = storeToRefs(authStore)
const { check: requireLogin } = useLoginGuard()

const searchKeyword = ref((route.query.keyword as string) ?? '')

/**
 * 头像弹层里的活跃统计**懒挂载**：不 hover 就不请求。
 *
 * header 是每个页面都会挂载的组件，而活跃统计要跑 `SUNIONSTORE`（近 7 天并集）——
 * 挂在 onMounted 里等于每次进页面都算一遍，而用户大多数时候根本不会去看。
 */
const activityReady = ref(false)

/**
 * 未读数（铃铛右上角的红点）。
 *
 * `unreadCount` 来自 `utils/notificationBadge` —— 跨组件共享的极小状态：
 * 消息页"全部已读"的那一刻它要立刻清零，而 header 没法感知另一个页面做了什么。
 *
 * **60 秒节流**：header 是常驻组件，路由每次切换都会走到这里；不节流的话，
 * 用户在站内多点几下就变成一串隐形的高频请求。而"已读"是即时生效的
 * （消息页直接改那个共享值，不走这条拉取路径），所以红点不会因为节流而赖着不走。
 */
let lastUnreadFetchAt = 0

async function refreshUnreadCount(force = false) {
  if (!isLoggedIn.value) {
    unreadCount.value = 0
    return
  }
  const now = Date.now()
  if (!force && now - lastUnreadFetchAt < 60_000) {
    return
  }
  lastUnreadFetchAt = now
  try {
    const result = await notificationApi.getUnreadCount()
    unreadCount.value = result.data ?? 0
  } catch {
    // 红点拉不到不影响任何浏览行为：静默失败，下次路由切换会再试
  }
}

onMounted(() => refreshUnreadCount(true))
watch(() => route.fullPath, () => refreshUnreadCount())
watch(isLoggedIn, (loggedIn) => {
  if (loggedIn) {
    refreshUnreadCount(true)
  } else {
    // 退出登录要立刻清掉：否则下一个登录的人会看到上一个人的红点
    unreadCount.value = 0
  }
})

function goNotifications() {
  router.push('/me/notifications')
}

/** 退出登录。与个人主页那份是同一套流程（含确认弹窗） */
function handleLogout() {
  dialog.warning({
    title: '确认退出',
    content: '确定要退出登录吗？',
    positiveText: '退出',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        // 必须先通知后端把这个 token 拉黑，再清本地：顺序反了请求就带不上 token，
        // 后端拿不到要作废的是哪一个（JWT 无状态，不通知 = 它继续有效到过期）
        await authApi.logout()
      } catch {
        // 后端不可用时不能把用户卡在登录态：本地状态照清
      }
      authStore.clearAuth()
      message.success('已退出登录')
      router.push('/')
    },
  })
}

/**
 * 秒杀要登录才能参与（`SeckillView` 自身也有守卫，游客直接进会被踢到登录页）。
 * 这里**不跳转**——复用全站统一的登录提示弹窗，用户点「去登录」再走。
 *
 * 用 `<a>` 而不是 `<router-link>`：router-link 内部的导航处理器**先于**外部 `@click` 执行，
 * 所以在外部 `preventDefault()` 已经晚了（实测仍会跳到 /login）。这里自己接管点击。
 */
function handleSeckillClick() {
  if (!requireLogin()) {
    return
  }
  router.push('/seckill')
}

function handleSearch() {
  const kw = searchKeyword.value.trim()
  if (kw) {
    router.push({ path: '/', query: { keyword: kw } })
  } else {
    router.push({ path: '/' })
  }
}

function goHomeFresh() {
  router.push({ path: '/', query: { refresh: String(Date.now()) } })
}
</script>

<template>
  <header class="app-header">
    <a class="brand" href="/" @click.prevent="goHomeFresh">
      <span class="brand-mark">H</span>
      <span class="brand-text">海林Blog</span>
    </a>

    <!-- 额度秒杀入口。文案与样式移植自钱包页的 .wallet-seckill-entry：
         首页点 Logo 就能回、写文章在右侧「创作中心」里已有，所以原来那两个导航项去掉了 -->
    <a class="header-seckill" href="/seckill" @click.prevent="handleSeckillClick">
      <span class="header-seckill-title">额度秒杀</span>
      <span class="header-seckill-desc">抢到的额度直接进钱包，每人每场限一次</span>
      <span class="header-seckill-arrow">→</span>
    </a>

    <div class="header-actions">
      <label class="header-search" aria-label="搜索文章">
        <input
          v-model="searchKeyword"
          type="search"
          placeholder="搜索文章标题"
          @keyup.enter="handleSearch"
        />
        <n-icon @click="handleSearch" style="cursor: pointer"><SearchOutline /></n-icon>
      </label>

      <!-- 消息铃铛：放在**搜索框和创作中心之间**。
           图标刻意不上色（用 currentColor 继承 header 的文字色），
           只有右上角的未读数字是红的——它需要在一堆中性色里被一眼看到 -->
      <button
        v-if="isLoggedIn"
        class="header-notification"
        type="button"
        aria-label="消息"
        @click="goNotifications"
      >
        <n-icon :size="20"><NotificationsOutline /></n-icon>
        <span v-if="unreadCount > 0" class="header-notification-badge">
          {{ unreadCount > 99 ? '99+' : unreadCount }}
        </span>
      </button>

      <div class="creator-center">
        <button class="creator-trigger" type="button">
          创作中心
          <n-icon><ChevronDown /></n-icon>
        </button>
        <div class="creator-popover">
          <button type="button" @click="$router.push('/editor')">
            <span class="creator-icon">
              <n-icon><DocumentTextOutline /></n-icon>
            </span>
            <span>写文章</span>
          </button>
          <button type="button" @click="$router.push('/drafts')">
            <span class="creator-icon">
              <n-icon><FileTrayOutline /></n-icon>
            </span>
            <span>草稿箱</span>
          </button>
        </div>
      </div>

      <template v-if="isLoggedIn">
        <div class="avatar-center" @mouseenter="activityReady = true">
          <router-link class="header-avatar-link" to="/me" aria-label="进入个人中心">
            <n-avatar :size="38" :src="usersVO?.avatarUrl">
              <template #fallback>
                <n-icon><Person /></n-icon>
              </template>
            </n-avatar>
          </router-link>
          <div class="avatar-popover">
            <SiteActivityCard v-if="activityReady" />
            <button class="avatar-logout" type="button" @click="handleLogout">
              退出登录
            </button>
          </div>
        </div>
      </template>
      <template v-else>
        <router-link class="auth-link login-link" to="/login">登录</router-link>
        <router-link class="auth-link register-link" to="/register">注册</router-link>
      </template>
    </div>
  </header>
</template>
