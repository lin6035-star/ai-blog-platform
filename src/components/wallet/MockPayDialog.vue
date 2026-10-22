<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { useMessage } from 'naive-ui'
import { walletApi } from '@/api/wallet'
import type { RechargeOrder } from '@/types/wallet'
import { formatCredit, formatPayAmount } from '@/utils/format'

/**
 * 模拟支付弹窗（钱包页与 AI 助手共用）。
 *
 * 真实支付里这里会跳去渠道收银台；虚拟支付用同构弹窗代替，但流程一点没省：
 * 金额 + 倒计时 → 用户确认 → 模拟渠道回调 → **轮询订单终态**才算成功。
 *
 * ⚠️ 两个不变量：
 *   1. 支付成功与否只认订单轮询结果，不认 `mockPay` 的接口返回（回调可能延迟）；
 *   2. 倒计时到 0 关闭弹窗——服务端那边订单也会被关单任务/惰性关单关掉，两边一致。
 */
const props = defineProps<{
  show: boolean
  order: RechargeOrder | null
  payToken: string
  /**
   * 自定义金额订单的「元」值（仅展示用）。
   *
   * ⚠️ 绝不能走 formatPayAmount——那个函数吃「分」，5 元会显示成 ¥0.05。
   */
  customYuan?: number | null
}>()

const emit = defineEmits<{
  'update:show': [value: boolean]
  /** 支付成功（父组件刷新余额 / 订单列表） */
  paid: [order: RechargeOrder]
  /** 订单取消 / 超时 / 支付失败关闭（父组件刷新订单列表） */
  closed: []
}>()

const message = useMessage()

const paying = ref(false)
const remaining = ref(0)
let timer: number | null = null

const visible = computed({
  get: () => props.show,
  set: (value: boolean) => emit('update:show', value),
})

const payText = computed(() => {
  if (!props.order) {
    return ''
  }
  if (props.customYuan !== null && props.customYuan !== undefined) {
    return `¥${props.customYuan}`
  }
  return formatPayAmount(props.order.payAmount)
})

const countdownText = computed(() => {
  const seconds = remaining.value
  const minutes = Math.floor(seconds / 60)
  const rest = seconds % 60
  return `${String(minutes).padStart(2, '0')}:${String(rest).padStart(2, '0')}`
})

watch(
  () => props.show,
  (value) => {
    if (value) {
      startCountdown()
    } else {
      clearTimer()
    }
  },
  { immediate: true },
)

onUnmounted(clearTimer)

function startCountdown() {
  clearTimer()
  const expireAt = props.order?.expireAt
  if (!expireAt) {
    remaining.value = 0
    return
  }
  const deadline = new Date(expireAt).getTime()
  const tick = () => {
    const remain = Math.max(0, Math.floor((deadline - Date.now()) / 1000))
    remaining.value = remain
    if (remain <= 0) {
      clearTimer()
      message.warning('订单已超时关闭，请重新下单')
      emit('update:show', false)
      emit('closed')
    }
  }
  tick()
  timer = window.setInterval(tick, 1000)
}

function clearTimer() {
  if (timer !== null) {
    window.clearInterval(timer)
    timer = null
  }
}

async function confirmPay() {
  const order = props.order
  if (!order || !props.payToken || paying.value) {
    return
  }
  paying.value = true
  try {
    // 模拟渠道确认：服务端构造签名回调，走真实的验签 / 幂等 / 金额校验链路
    await walletApi.mockPay(order.orderNo, props.payToken)
    const finalOrder = await pollOrderStatus(order.orderNo)
    if (!finalOrder) {
      message.info('支付处理中，请稍后在订单列表查看')
    } else if (finalOrder.status === 'PAID') {
      message.success(`充值成功，到账 ${formatCredit(finalOrder.creditAmount)} 额度`)
      emit('paid', finalOrder)
    } else if (finalOrder.status === 'CLOSED') {
      message.warning('订单已关闭，未到账')
      emit('closed')
    } else {
      message.info('支付处理中，请稍后在订单列表查看')
    }
    emit('update:show', false)
  } catch (e) {
    message.error(e instanceof Error ? e.message : '支付失败')
    // 失败也要关：订单可能已被其它路径推进（关单 / 已支付）
    emit('update:show', false)
    emit('closed')
  } finally {
    paying.value = false
  }
}

/** 轮询订单终态：最多 5 次 × 800ms，避免把「回调延迟」误报成失败 */
async function pollOrderStatus(orderNo: string): Promise<RechargeOrder | null> {
  for (let i = 0; i < 5; i += 1) {
    try {
      const res = await walletApi.getRechargeOrder(orderNo)
      const order = res.data
      if (order && (order.status === 'PAID' || order.status === 'CLOSED')) {
        return order
      }
    } catch {
      // 单次查询失败不打断轮询
    }
    await new Promise((resolve) => window.setTimeout(resolve, 800))
  }
  return null
}

async function cancelOrder() {
  const order = props.order
  if (!order || paying.value) {
    return
  }
  paying.value = true
  try {
    await walletApi.cancelRechargeOrder(order.orderNo)
    message.success('订单已取消')
  } catch (e) {
    message.error(e instanceof Error ? e.message : '取消失败')
  } finally {
    paying.value = false
    emit('update:show', false)
    emit('closed')
  }
}
</script>

<template>
  <n-modal
    v-model:show="visible"
    preset="card"
    title="模拟支付"
    class="wallet-pay-modal"
    :mask-closable="false"
  >
    <div v-if="order" class="wallet-pay">
      <div class="wallet-pay-amount">{{ payText }}</div>
      <div class="wallet-pay-credit">
        到账 {{ formatCredit(order.creditAmount) }} 额度
      </div>
      <div v-if="customYuan !== null && customYuan !== undefined" class="wallet-pay-custom">
        确认支付 ¥{{ customYuan }} 购买 {{ formatCredit(order.creditAmount) }} 额度吗？
      </div>
      <div class="wallet-pay-countdown">
        支付剩余时间 <strong>{{ countdownText }}</strong>
      </div>
      <p class="wallet-pay-note">
        这是虚拟支付，不会真的扣款；点击「确认支付」后服务端会模拟渠道回调入账。
      </p>
      <div class="wallet-pay-actions">
        <n-button type="primary" :loading="paying" @click="confirmPay">确认支付</n-button>
        <n-button :disabled="paying" @click="cancelOrder">取消订单</n-button>
      </div>
    </div>
  </n-modal>
</template>

<style scoped>
.wallet-pay {
  display: grid;
  gap: 10px;
  text-align: center;
}

.wallet-pay-amount {
  color: #1f2937;
  font-size: 32px;
  font-weight: 700;
  line-height: 1.1;
}

.wallet-pay-credit {
  color: #4f46e5;
  font-size: 14px;
}

.wallet-pay-custom {
  color: #64748b;
  font-size: 13px;
}

.wallet-pay-countdown {
  color: #b45309;
  font-size: 13px;
}

.wallet-pay-countdown strong {
  font-variant-numeric: tabular-nums;
}

.wallet-pay-note {
  margin: 0;
  color: #94a3b8;
  font-size: 12px;
  line-height: 1.6;
}

.wallet-pay-actions {
  display: flex;
  justify-content: center;
  gap: 10px;
  margin-top: 4px;
}
</style>
