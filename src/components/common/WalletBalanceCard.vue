<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { walletApi } from '@/api/wallet'
import type { WalletInfo } from '@/types/wallet'
import { formatCredit, displayCredit } from '@/utils/format'

/**
 * 钱包余额小卡片（仿 SiteActivityCard）。
 *
 * 独立组件而不是塞进 ProfileView：数据拉取、失败处理、样式都自包含，
 * 宿主页面只需要放一个标签。以后想挪到 AI 助手面板或侧边栏也不用改宿主逻辑。
 *
 * 整卡是一个 router-link ——卡片本身就是进入钱包页的入口，不再另加一条导航链接。
 */
const wallet = ref<WalletInfo | null>(null)
const walletLoading = ref(true)

/**
 * 负余额的三态（设计稿 §3）。
 *
 * 只看余额数字会把「预占中」误报成欠款：用户刚发起一次 AI 调用就看到负余额，
 * 会以为已经欠费，而实际上结算后会退回来。所以要带 pendingReserveCount 一起判断。
 */
const tone = computed(() => {
  const info = wallet.value
  if (!info || info.balance > 0) {
    return 'normal'
  }
  return info.pendingReserveCount > 0 ? 'pending' : 'empty'
})

const hint = computed(() => {
  if (tone.value === 'pending') {
    return '本次调用进行中'
  }
  if (tone.value === 'empty') {
    return '额度不足，请充值'
  }
  return '可用于 AI 助手消费'
})

onMounted(async () => {
  try {
    const res = await walletApi.getWallet()
    wallet.value = res.data
  } catch {
    // 余额是锦上添花：拿不到时保留固定占位，不影响个人中心其余内容的布局
  } finally {
    walletLoading.value = false
  }
})
</script>

<template>
  <router-link v-if="wallet" class="wallet-card" to="/me/wallet">
    <div class="wallet-card-title">我的额度</div>
    <div class="wallet-card-value" :class="`is-${tone}`">
      {{ formatCredit(displayCredit(wallet)) }}
    </div>
    <div class="wallet-card-hint" :class="`is-${tone}`">{{ hint }}</div>
  </router-link>
  <div v-else-if="walletLoading" class="wallet-card wallet-card-placeholder" aria-hidden="true">
    <span class="wallet-card-skeleton wallet-card-skeleton-title" />
    <span class="wallet-card-skeleton wallet-card-skeleton-value" />
    <span class="wallet-card-skeleton wallet-card-skeleton-hint" />
  </div>
  <div v-else class="wallet-card wallet-card-unavailable">
    <div class="wallet-card-title">我的额度</div>
    <div class="wallet-card-hint">额度暂不可用</div>
  </div>
</template>

<style scoped>
.wallet-card {
  display: grid;
  gap: 4px;
  min-height: 102px;
  box-sizing: border-box;
  padding: 14px 20px;
  border-top: 1px solid #eef0f2;
  text-decoration: none;
  transition: background-color 0.2s ease;
}

.wallet-card:hover {
  background-color: #f8fafc;
}

.wallet-card-placeholder {
  align-content: start;
}

.wallet-card-unavailable {
  align-content: center;
}

.wallet-card-skeleton {
  display: block;
  border-radius: 4px;
  background: #edf1f1;
}

.wallet-card-skeleton-title {
  width: 54px;
  height: 12px;
}

.wallet-card-skeleton-value {
  width: 86px;
  height: 26px;
}

.wallet-card-skeleton-hint {
  width: 116px;
  height: 12px;
}

.wallet-card-title {
  color: #94a3b8;
  font-size: 12px;
}

.wallet-card-value {
  color: #1f2937;
  font-size: 22px;
  font-weight: 700;
  line-height: 1.2;
}

/* 预占中：余额是负的但不必充值，用中性色，不要做成告警 */
.wallet-card-value.is-pending {
  color: #b45309;
}

/* 结算后仍为负：这才需要充值 */
.wallet-card-value.is-empty {
  color: #dc2626;
}

.wallet-card-hint {
  color: #64748b;
  font-size: 12px;
}

.wallet-card-hint.is-empty {
  color: #dc2626;
}
</style>
