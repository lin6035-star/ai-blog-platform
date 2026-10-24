<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import AiAssistant from '@/components/ai/AiAssistant.vue'

const route = useRoute()

/**
 * 登录流程的页面**不显示** AI 助手。
 *
 * 这几个页面都是未登录状态：助手没有会话上下文、也没有可用的工具，
 * 悬浮球只会压在表单和插画上挡视线（登录页尤其明显，右下角正好是"去注册"）。
 *
 * 用前缀匹配而不是精确路径：`/auth/` 覆盖了 GitHub 回调这类子路由。
 */
const AUTH_PAGE_PREFIXES = [
  '/login',
  '/register',
  '/forgot-password',
  '/reset-password',
  '/auth/',
]

const showAiAssistant = computed(
  () => !AUTH_PAGE_PREFIXES.some((prefix) => route.path.startsWith(prefix)),
)
</script>

<template>
  <n-dialog-provider>
    <n-message-provider>
      <router-view v-slot="{ Component }">
        <KeepAlive include="HomeView">
          <component :is="Component" />
        </KeepAlive>
      </router-view>
      <AiAssistant v-if="showAiAssistant" />
    </n-message-provider>
  </n-dialog-provider>
</template>
