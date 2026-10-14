<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useDialog, useMessage } from 'naive-ui'
import { ArrowBack } from '@vicons/ionicons5'
import MainLayout from '@/layouts/MainLayout.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import { walletApi } from '@/api/wallet'
import type { Result } from '@/types/result'
import type {
  RechargeOrder,
  WalletBillEntry,
  WalletCustomConfig,
  WalletInfo,
  WalletPackage,
} from '@/types/wallet'
import { formatArticleDateTime, formatCredit, displayCredit, formatPayAmount } from '@/utils/format'

const router = useRouter()
const message = useMessage()
const dialog = useDialog()

const wallet = ref<WalletInfo | null>(null)

/**
 * 账单只展示最近 N 天（口径在后端 `blog.wallet.bill-retention-days`）。
 * **不硬编码那个 7**：后端调配置时这里跟着变，说明文字不会和实际口径打架。
 * 拿不到配置时为 0，整句提示不显示。
 */
const billRetentionDays = computed(() => wallet.value?.billRetentionDays ?? 0)
const packages = ref<WalletPackage[]>([])
const billEntries = ref<WalletBillEntry[]>([])
const loading = ref(false)
const paying = ref(false)

/** 配置接口没回来时的占位值——真正的边界在后端，这里只负责输入框不至于空着 */
const DEFAULT_CUSTOM_CONFIG: WalletCustomConfig = { minYuan: 1, maxYuan: 1000, creditPerYuan: 1000 }
const customConfig = ref<WalletCustomConfig>(DEFAULT_CUSTOM_CONFIG)
/** 配置是否成功取到。失败就不渲染自定义入口——它是个附加功能，不该拖累套餐和余额 */
const customEnabled = ref(false)

/**
 * ⚠️ 必须是 `number | null`：{@code n-input-number} 清空时给的是 **null**，
 * 不是 0 也不是 NaN。用 `!customYuan` 之类的假值判断会把 0 和 null 混成一类。
 */
const customYuan = ref<number | null>(null)

/** 本地估算的到账额度，仅用于确认框和预览；真正的额度由后端在下单时算 */
const customCredit = computed(() => {
  const yuan = customYuan.value
  if (yuan === null || !Number.isFinite(yuan)) {
    return 0
  }
  return yuan * customConfig.value.creditPerYuan
})

/** 业务类型 → 中文。后端返回的已经是聚合后的业务类型，不是流水类型 */
const typeLabel: Record<string, string> = {
  RECHARGE_ORDER: '充值到账',
  SECKILL_ORDER: '秒杀到账',
  INITIAL_GRANT: '注册赠送',
  AI_BILLING: 'AI 消费',
}

/**
 * 负余额的三态（设计稿 §3）。
 *
 * 「预占中」和「真的欠了」都表现为负余额，但用户要做的事完全不同：
 * 前者什么都不用做（调用结束会退回来），后者才需要充值。
 */
const balanceTone = computed(() => {
  const info = wallet.value
  if (!info || info.balance > 0) {
    return 'normal'
  }
  return info.pendingReserveCount > 0 ? 'pending' : 'empty'
})

const balanceHint = computed(() => {
  if (balanceTone.value === 'pending') {
    return '本次调用进行中，结束后会按实际用量结算'
  }
  if (balanceTone.value === 'empty') {
    return '额度不足，请充值后再使用 AI 助手'
  }
  return '可用于 AI 助手消费'
})

async function loadAll() {
  loading.value = true
  try {
    const [walletRes, packagesRes, billRes] = await Promise.all([
      walletApi.getWallet(),
      walletApi.getPackages(),
      walletApi.getBill(1, 20),
    ])
    wallet.value = walletRes.data
    packages.value = packagesRes.data ?? []
    billEntries.value = billRes.data?.list ?? []
  } catch (e) {
    message.error(e instanceof Error ? e.message : '钱包加载失败')
  } finally {
    loading.value = false
  }

  // 单独发、单独 catch：这是后加的接口，让它 reject 掉上面那个 Promise.all，
  // 等于把「自定义充值没取到配置」升级成「钱包页整个打不开」——用户会连余额都看不见
  try {
    const res = await walletApi.getCustomConfig()
    customConfig.value = res.data ?? DEFAULT_CUSTOM_CONFIG
    customEnabled.value = true
  } catch {
    customEnabled.value = false
  }
}

/**
 * 下单 + 支付：套餐与自定义金额共用。
 *
 * 下单和支付分两步（对应后端两个接口），是为了和将来接真实支付时的流程一致：
 * 那时「下单」和「支付回调」之间会隔着一次真实的第三方支付。
 *
 * 两条路的后半段完全一样，分开写早晚会在「忘了 loadAll」「忘了复位 paying」这类地方漂移。
 */
async function runRecharge(
  createOrder: () => Promise<Result<RechargeOrder>>,
  successText: string,
) {
  paying.value = true
  try {
    const order = await createOrder()
    await walletApi.payRechargeOrder(order.data.orderNo)
    message.success(successText)
    await loadAll()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '充值失败')
  } finally {
    paying.value = false
  }
}

/** 选套餐 → 模拟支付 */
function choosePackage(pkg: WalletPackage) {
  // ⚠️ 在开对话框**之前**拦：paying 只在 onPositiveClick 里置位，
  // 第一个确认框还没点确认时再点一次，会叠开第二个对话框、最终生成两张订单、两次到账。
  // 订单状态 CAS 防不了这个——它只能防「同一张订单付两次」
  if (paying.value) {
    return
  }
  dialog.warning({
    title: '模拟支付',
    content: `确认支付 ${formatPayAmount(pkg.payAmount)} 购买 ${formatCredit(pkg.creditAmount)} 额度吗？`
      + '这是虚拟支付，不会真的扣款。',
    positiveText: '确认支付',
    negativeText: '取消',
    onPositiveClick: () =>
      runRecharge(
        () => walletApi.createRechargeOrder(pkg.code),
        `充值成功，到账 ${formatCredit(pkg.creditAmount)} 额度`,
      ),
  })
}

/** 自定义金额 → 模拟支付 */
function chooseCustomAmount() {
  if (paying.value) {
    return
  }

  const yuan = customYuan.value
  if (yuan === null || !Number.isFinite(yuan)) {
    message.warning('请输入充值金额')
    return
  }
  if (yuan < customConfig.value.minYuan || yuan > customConfig.value.maxYuan) {
    message.warning(
      `充值金额需在 ${customConfig.value.minYuan} ~ ${customConfig.value.maxYuan} 元之间`,
    )
    return
  }

  // ⚠️ 显示的是**元**，所以绝不能走 formatPayAmount——那个函数吃「分」，
  // formatPayAmount(5) 会显示成 ¥0.05，而 5 元和 5 分看起来都"对"，
  // 要等付完钱才会发现。整数元直接用原值，不做任何换算
  const credit = yuan * customConfig.value.creditPerYuan
  dialog.warning({
    title: '模拟支付',
    content: `确认支付 ¥${yuan} 购买 ${formatCredit(credit)} 额度吗？这是虚拟支付，不会真的扣款。`,
    positiveText: '确认支付',
    negativeText: '取消',
    onPositiveClick: () =>
      runRecharge(
        () => walletApi.createCustomRechargeOrder(yuan),
        `充值成功，到账 ${formatCredit(credit)} 额度`,
      ),
  })
}

// 返回个人中心：优先走浏览器历史（back 不新增记录），直接 URL 进入时兜底跳转
function goBackToProfile() {
  if (router.options.history.state.back) {
    router.back()
  } else {
    router.replace('/me')
  }
}

onMounted(loadAll)
</script>

<template>
  <MainLayout>
    <div class="wallet-page">
      <header class="wallet-header">
        <button class="wallet-back" type="button" @click="goBackToProfile">
          <n-icon :component="ArrowBack" size="18" />
          返回个人中心
        </button>
        <h1>我的钱包</h1>
        <p class="wallet-subtitle">额度用于 AI 助手消费，按实际 token 用量扣减</p>
      </header>

      <section class="wallet-balance" :class="`is-${balanceTone}`">
        <div class="wallet-balance-label">当前额度</div>
        <div class="wallet-balance-value">{{ formatCredit(displayCredit(wallet)) }}</div>
        <div class="wallet-balance-hint">{{ balanceHint }}</div>
      </section>

      <router-link class="wallet-seckill-entry" to="/seckill">
        <span class="wallet-seckill-title">额度秒杀</span>
        <span class="wallet-seckill-desc">抢到的额度直接进钱包，每人每场限一次</span>
        <span class="wallet-seckill-arrow">→</span>
      </router-link>

      <section class="wallet-section">
        <h2 class="wallet-section-title">充值</h2>
        <div class="wallet-packages">
          <button
            v-for="pkg in packages"
            :key="pkg.code"
            type="button"
            class="wallet-package"
            :disabled="paying"
            @click="choosePackage(pkg)"
          >
            <span class="wallet-package-credit">{{ formatCredit(pkg.creditAmount) }}</span>
            <span class="wallet-package-unit">额度</span>
            <span class="wallet-package-price">{{ formatPayAmount(pkg.payAmount) }}</span>
          </button>
        </div>
        <p class="wallet-packages-note">虚拟支付，点击后不会真的扣款</p>
      </section>

      <section v-if="customEnabled" class="wallet-section">
        <h2 class="wallet-section-title">自定义金额</h2>
        <div class="wallet-custom">
          <n-input-number
            v-model:value="customYuan"
            class="wallet-custom-input"
            :min="customConfig.minYuan"
            :max="customConfig.maxYuan"
            :precision="0"
            :show-button="false"
            :disabled="paying"
            :placeholder="`${customConfig.minYuan} ~ ${customConfig.maxYuan}`"
            @keyup.enter="chooseCustomAmount"
          />
          <span class="wallet-custom-unit">元</span>
          <button
            type="button"
            class="wallet-custom-submit"
            :disabled="paying"
            @click="chooseCustomAmount"
          >
            立即充值
          </button>
        </div>
        <p class="wallet-packages-note">
          只能填整数元，1 元 = {{ formatCredit(customConfig.creditPerYuan) }} 额度。套餐更划算。
          <template v-if="customCredit > 0">
            本次到账 {{ formatCredit(customCredit) }} 额度。
          </template>
        </p>
      </section>

      <section class="wallet-section">
        <h2 class="wallet-section-title">
          账单
          <span v-if="billRetentionDays" class="wallet-section-note">
            仅显示最近 {{ billRetentionDays }} 天
          </span>
        </h2>

        <div v-if="loading" class="wallet-loading">
          <n-skeleton text :repeat="4" />
        </div>

        <EmptyState
          v-else-if="billEntries.length === 0"
          title="还没有账单"
          description="充值和使用 AI 助手之后，这里会记录每一笔"
        />

        <ul v-else class="wallet-transactions">
          <li v-for="entry in billEntries" :key="entry.bizId" class="wallet-transaction">
            <div class="wallet-transaction-main">
              <span class="wallet-transaction-type">
                {{ typeLabel[entry.bizType] ?? entry.bizType }}
              </span>
              <span class="wallet-transaction-time">
                {{ formatArticleDateTime(entry.createdAt ?? '') }}
              </span>
            </div>
            <div class="wallet-transaction-amount" :class="entry.amount >= 0 ? 'is-in' : 'is-out'">
              <strong>{{ entry.amount >= 0 ? '+' : '' }}{{ formatCredit(entry.amount) }}</strong>
              <small>余额 {{ formatCredit(entry.balanceAfter) }}</small>
            </div>
          </li>
        </ul>
      </section>
    </div>
  </MainLayout>
</template>

<style scoped>
.wallet-page {
  width: 100%;
  max-width: 1200px;
  margin: 0 auto;
  padding: 10px 18px 56px;
}

.wallet-header {
  display: grid;
  gap: 8px;
  margin-bottom: 18px;
}

.wallet-back {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  width: fit-content;
  padding: 6px 10px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #fff;
  color: #4b5563;
  font-size: 13px;
  cursor: pointer;
}

.wallet-back:hover {
  background: #f8fafc;
}

.wallet-header h1 {
  margin: 0;
  color: #1f2937;
  font-size: 22px;
}

.wallet-subtitle {
  margin: 0;
  color: #94a3b8;
  font-size: 13px;
}

.wallet-balance {
  display: grid;
  gap: 6px;
  padding: 20px 24px;
  border: 1px solid #eef0f2;
  border-radius: 12px;
  background: #fff;
}

.wallet-balance-value {
  color: #1f2937;
  font-size: 34px;
  font-weight: 700;
  line-height: 1.1;
}

/* 预占中：负余额但不必充值，用中性偏暖的提示色，不做成告警 */
.wallet-balance.is-pending .wallet-balance-value {
  color: #b45309;
}

.wallet-balance.is-empty .wallet-balance-value {
  color: #dc2626;
}

.wallet-balance-label {
  color: #94a3b8;
  font-size: 12px;
}

.wallet-balance-hint {
  color: #64748b;
  font-size: 13px;
}

.wallet-balance.is-empty .wallet-balance-hint {
  color: #dc2626;
}

.wallet-seckill-entry {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 18px;
  padding: 14px 18px;
  border: 1px solid #e0e7ff;
  border-radius: 12px;
  background: #eef2ff;
  text-decoration: none;
}

.wallet-seckill-entry:hover {
  background: #e0e7ff;
}

.wallet-seckill-title {
  color: #4338ca;
  font-size: 15px;
  font-weight: 600;
}

.wallet-seckill-desc {
  flex: 1;
  color: #6366f1;
  font-size: 13px;
}

.wallet-seckill-arrow {
  color: #6366f1;
  font-size: 16px;
}

.wallet-section {
  margin-top: 26px;
}

.wallet-section-title {
  margin: 0 0 12px;
  color: #1f2937;
  font-size: 16px;
}

/* 账单口径说明（"仅显示最近 N 天"）。轻一点：它是补充信息，不该和标题抢注意力 */
.wallet-section-note {
  margin-left: 8px;
  color: #94a3b8;
  font-size: 12px;
  font-weight: 400;
}

.wallet-packages {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
  gap: 12px;
}

.wallet-package {
  display: grid;
  gap: 4px;
  padding: 16px;
  border: 1px solid #e5e7eb;
  border-radius: 12px;
  background: #fff;
  cursor: pointer;
  transition: border-color 0.2s ease, box-shadow 0.2s ease;
}

.wallet-package:hover:not(:disabled) {
  border-color: #6366f1;
  box-shadow: 0 4px 12px rgba(99, 102, 241, 0.12);
}

.wallet-package:disabled {
  cursor: not-allowed;
  opacity: 0.6;
}

.wallet-package-credit {
  color: #1f2937;
  font-size: 20px;
  font-weight: 700;
}

.wallet-package-unit {
  color: #94a3b8;
  font-size: 12px;
}

.wallet-package-price {
  margin-top: 6px;
  color: #6366f1;
  font-size: 14px;
  font-weight: 600;
}

.wallet-packages-note {
  margin: 10px 0 0;
  color: #94a3b8;
  font-size: 12px;
}

.wallet-custom {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.wallet-custom-input {
  width: 180px;
}

.wallet-custom-unit {
  color: #64748b;
  font-size: 14px;
}

.wallet-custom-submit {
  padding: 9px 20px;
  border: 1px solid #6366f1;
  border-radius: 10px;
  background: #6366f1;
  color: #fff;
  font-size: 14px;
  cursor: pointer;
  transition: background 0.2s ease, border-color 0.2s ease;
}

.wallet-custom-submit:hover:not(:disabled) {
  background: #4f46e5;
  border-color: #4f46e5;
}

.wallet-custom-submit:disabled {
  cursor: not-allowed;
  opacity: 0.6;
}

.wallet-loading {
  padding: 8px 0;
}

.wallet-transactions {
  display: grid;
  gap: 8px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.wallet-transaction {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 12px 16px;
  border: 1px solid #eef0f2;
  border-radius: 10px;
  background: #fff;
}

.wallet-transaction-main {
  display: grid;
  gap: 3px;
}

.wallet-transaction-type {
  color: #1f2937;
  font-size: 14px;
}

.wallet-transaction-time {
  color: #94a3b8;
  font-size: 12px;
}

.wallet-transaction-amount {
  display: grid;
  justify-items: end;
  gap: 3px;
}

.wallet-transaction-amount strong {
  font-size: 15px;
}

.wallet-transaction-amount.is-in strong {
  color: #16a34a;
}

.wallet-transaction-amount.is-out strong {
  color: #dc2626;
}

.wallet-transaction-amount small {
  color: #94a3b8;
  font-size: 12px;
}
</style>
