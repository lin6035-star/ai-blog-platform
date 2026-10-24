<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useMessage } from 'naive-ui'
import { ArrowBack } from '@vicons/ionicons5'
import MainLayout from '@/layouts/MainLayout.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import { seckillApi } from '@/api/seckill'
import type { SeckillActivity, SeckillStatus } from '@/types/seckill'
import { formatArticleDateTime, formatCredit } from '@/utils/format'

const router = useRouter()
const message = useMessage()

const activities = ref<SeckillActivity[]>([])
const loading = ref(false)
/** 正在发请求的活动 id——按钮转圈用 */
const grabbing = ref<string | null>(null)

/**
 * 结果轮询间隔。
 *
 * <p>入账本身是百毫秒级的，**轮询间隔成了主要感知延迟**：后端 settle 每 2 秒一轮，
 * 最坏要等 2 秒；1 秒轮询会在这之上再叠最多 1 秒。压到 300ms 后总感知延迟明显降下来。
 * 每轮请求很轻（只查一次 Redis 结果），10 次 × 300ms = 3 秒覆盖得下最坏情况。
 */
const POLL_INTERVAL_MS = 300
const MAX_POLL_ATTEMPTS = 10
const pollTimers = new Map<string, ReturnType<typeof setTimeout>>()

/** 边界到达后再等一拍，给后端的流转任务留出翻转状态的时间 */
const REFRESH_MARGIN_MS = 1000
/** 「startAt 已过、后端却还是 DRAFT」时的重试节奏——专门用来吃掉任务那 0~5 秒的延迟 */
const STALE_DRAFT_RETRY_MS = 3000
/** 下限，避免边界就在眼前时打出一串请求 */
const MIN_REFRESH_MS = 1000
/**
 * 一个活动都没有时的兜底重拉间隔。
 *
 * 取 30 秒而不是几分钟：这是**纯兜底**——正常路径下服务端始终会返回「今天 + 明天」里的
 * 至少一张卡（今天那场结束后仍以灰态留在列表上），不会出现空列表。但后端任务挂了、
 * 或者刚好卡在跨天那一瞬，空列表也得有东西兜住，不能就此冻死。
 * 空列表时请求很轻（0 个活动 = 1 次查询），勤一点无所谓。
 */
const IDLE_REFRESH_MS = 30 * 1000

let refreshTimer: ReturnType<typeof setTimeout> | null = null

async function loadActivities() {
  // 只在「还没有数据」时显示骨架屏：边界重试 / 兜底重拉都会走这里，
  // 每次刷新都切一次骨架屏，列表看起来就是一直在闪
  loading.value = activities.value.length === 0
  try {
    const res = await seckillApi.listActivities()
    activities.value = res.data ?? []
  } catch (e) {
    message.error(e instanceof Error ? e.message : '活动加载失败')
  } finally {
    loading.value = false
    scheduleRefresh()
  }
}

/**
 * 在最近的时间边界之后重新拉一次列表。
 *
 * 这个页面原先只在挂载时加载一次——用户停在页面上，活动开始了、结束了，界面都不会变。
 * 但**本地绝不自己判断「开始了」**（后端的状态由定时任务翻转，最长晚 5 秒，
 * 本地抢跑会造出「前端说能抢、后端说没开始」的窗口）。所以这里只做一件事：
 * 到点了去问服务端要新状态。
 */
function scheduleRefresh() {
  if (refreshTimer) {
    clearTimeout(refreshTimer)
    refreshTimer = null
  }

  const now = Date.now()
  const waits: number[] = []
  for (const activity of activities.value) {
    const start = new Date(activity.startAt).getTime()
    const end = new Date(activity.endAt).getTime()
    if (activity.status === 'DRAFT') {
      // 还没到点 → 等到点再拉；已经到点却还没翻转 → 短节奏重试，那 0~5 秒的延迟由这里吃掉
      waits.push(start > now ? start - now + REFRESH_MARGIN_MS : STALE_DRAFT_RETRY_MS)
    } else if (activity.status === 'ACTIVE' && end > now) {
      waits.push(end - now + REFRESH_MARGIN_MS)
    }
  }

  if (waits.length === 0) {
    // ⚠️ 没有这一兜，这一页会就此冻死：列表空 ⇒ 排不出任何边界 ⇒ 永远不再拉取，
    // 用户挂着页面等开场，什么都不会发生。
    // （正常也不该走到这里——服务端始终保留「今天 + 明天」的卡，今天那场结束后
    //  仍以灰态留在列表上；但后端任务挂了、或者刚好卡在跨天那一瞬，都会落到这）
    refreshTimer = setTimeout(loadActivities, IDLE_REFRESH_MS)
    return
  }
  refreshTimer = setTimeout(loadActivities, Math.max(MIN_REFRESH_MS, Math.min(...waits)))
}

/**
 * 抢购。
 *
 * ⚠️ 后端返回 `QUEUED` **不是「抢到了」**——那只是名额占住了、事件进了队列。
 * 真正的发放是异步的，必须轮询到 `GRANTED` 才能告诉用户到账。
 * 这里把两步分开，是为了让界面上「入账中」和「已到账」永远是两个不同的样子。
 */
async function grab(activity: SeckillActivity) {
  if (grabbing.value) {
    return
  }
  grabbing.value = activity.id
  try {
    const res = await seckillApi.grab(activity.id)
    const result = res.data
    applyStatus(activity.id, result.status)

    if (result.status === 'QUEUED') {
      message.info(result.message ?? '已为你占住名额，正在入账')
      pollUntilSettled(activity.id)
    } else {
      message.warning(result.message ?? '没抢到')
    }
  } catch (e) {
    message.error(e instanceof Error ? e.message : '抢购失败')
  } finally {
    grabbing.value = null
  }
}

/** 轮询到有确定性结论为止（GRANTED / FAILED / FAILED_RETRY），或者到达次数上限 */
async function pollUntilSettled(activityId: string, attempt = 1) {
  const scheduleNext = () => {
    pollTimers.set(
      activityId,
      setTimeout(() => pollUntilSettled(activityId, attempt + 1), POLL_INTERVAL_MS),
    )
  }

  try {
    const res = await seckillApi.getResult(activityId)
    const result = res.data
    applyStatus(activityId, result.status)

    if (result.status === 'GRANTED') {
      message.success(`已到账 ${formatCredit(result.creditAmount ?? 0)} 额度`)
      return
    }
    if (result.status === 'QUEUED') {
      if (attempt < MAX_POLL_ATTEMPTS) {
        scheduleNext()
      } else {
        message.info('还在处理中，稍后刷新页面看看')
      }
      return
    }
    if (result.status !== 'NONE') {
      message.warning(result.message ?? '很遗憾，没能抢到')
    }
  } catch {
    // 轮询失败不弹错：它是后台行为，弹窗只会打扰用户。继续重试到上限为止
    if (attempt < MAX_POLL_ATTEMPTS) {
      scheduleNext()
    }
  }
}

function applyStatus(activityId: string, status: SeckillStatus) {
  const target = activities.value.find((item) => item.id === activityId)
  if (!target) {
    return
  }
  const wasGranted = target.myStatus === 'GRANTED'
  target.myStatus = status
  if (status === 'GRANTED' && !wasGranted) {
    // 本地 +1，省一次整表刷新；后端下次加载会给出准确值
    target.soldCount += 1
    target.remainingStock = Math.max(0, target.remainingStock - 1)
  }
}

/** 活动相位（整场活动的状态，区别于 myStatus 那个人的参与状态） */
type ActivityPhase = 'draft' | 'active' | 'ended'

/**
 * 相位派生。
 *
 * ⚠️ **本地时钟只允许把状态推向「更不可抢」**：过了 `endAt` 就显示已结束。
 * 方向是保守的——最坏早显示几秒，绝不会出现「前端说能抢、后端说没开始」。
 * **`startAt` 永远不参与判断**，那一条只能等后端的 `status` 翻转解锁。
 *
 * ⚠️ **时间窗判断必须排在 `DRAFT` 之前**：服务在活动那半小时里没运行时，
 * 场次会一直停在 `DRAFT`（后端已补自愈，但前端不该等它）。先判 DRAFT 的话，
 * 一场早就结束的活动会整天显示「未开始」，还会连带把列表压成一场、并触发 3 秒一次的重拉。
 */
function activityPhase(activity: SeckillActivity): ActivityPhase {
  if (activity.status === 'ENDED' || activity.remainingStock <= 0) {
    return 'ended'
  }
  if (Date.now() >= new Date(activity.endAt).getTime()) {
    return 'ended'
  }
  if (activity.status === 'DRAFT') {
    return 'draft'
  }
  return 'active'
}

function buttonLabel(activity: SeckillActivity): string {
  // 个人结果优先：已经到账、正在入账、发放异常的人，不该因为活动结束而看到「已结束」
  switch (activity.myStatus) {
    case 'GRANTED':
      return '已到账'
    case 'QUEUED':
      return '入账中…'
    case 'FAILED_RETRY':
      return '发放异常'
    case 'FAILED':
      return '未抢到'
    default:
      break
  }

  const phase = activityPhase(activity)
  if (phase === 'draft') {
    return '未开始'
  }
  if (phase === 'ended') {
    return activity.remainingStock > 0 ? '已结束' : '已抢完'
  }
  return '立即抢'
}

function isBusy(activity: SeckillActivity): boolean {
  return (
    grabbing.value === activity.id ||
    activity.myStatus === 'QUEUED' ||
    activity.myStatus === 'GRANTED' ||
    // 未开始 / 已结束都不该点得动——原先是「点了才被后端拒」
    activityPhase(activity) !== 'active'
  )
}

/**
 * 实际展示的列表。
 *
 * 后端返回的口径是「今天 + 明天」，但**明天那场不该提前剧透**：
 * 今天还没开始 / 正在跑的时候，明天那场对用户没有决策价值，只显示今天的；
 * 今天全部结束（灰态）之后才把明天那场接上——列表也就不会出现「10:30 之后一片空白」。
 *
 * 依赖 `activities` 这个 ref，所以每次 `loadActivities` 之后都会重算；
 * 而活动结束的那一刻由 `scheduleRefresh` 负责重新拉取（见那边的时间边界计算）。
 *
 * ⚠️ **位置有讲究，别挪到 `activityPhase` 和 `buttonLabel` 之间**：
 * `seckill-lifecycle.spec.ts` 用「从 `function activityPhase(` 截到 `function buttonLabel(`」
 * 来锁「本地时钟不许判断开始」这条约束，把本块挪进去会被算成 `activityPhase` 的一部分
 * ——它用了 `startAt`，断言会红。
 */
const visibleActivities = computed(() => {
  const list = activities.value
  if (list.length === 0) {
    return list
  }

  const now = new Date()
  const isToday = (iso: string) => {
    const d = new Date(iso)
    return (
      d.getFullYear() === now.getFullYear() &&
      d.getMonth() === now.getMonth() &&
      d.getDate() === now.getDate()
    )
  }

  const todayList = list.filter((a) => isToday(a.startAt))
  // 今天这场还没结束 → 明天的不显示；今天全结束了 → 两张都显示
  const todayStillOpen = todayList.some((a) => activityPhase(a) !== 'ended')
  return todayStillOpen ? todayList : list
})

function goBackToWallet() {
  if (router.options.history.state.back) {
    router.back()
  } else {
    router.replace('/me/wallet')
  }
}

onMounted(loadActivities)

onUnmounted(() => {
  pollTimers.forEach((timer) => clearTimeout(timer))
  pollTimers.clear()
  if (refreshTimer) {
    clearTimeout(refreshTimer)
  }
})
</script>

<template>
  <MainLayout>
    <div class="seckill-page">
      <header class="seckill-header">
        <button class="seckill-back" type="button" @click="goBackToWallet">
          <n-icon :component="ArrowBack" size="18" />
          返回
        </button>
        <h1>额度秒杀</h1>
        <p class="seckill-subtitle">
          抢到的额度直接进钱包，可用于 AI 助手消费。每人每场限一次。
        </p>
      </header>

      <div v-if="loading" class="seckill-loading">
        <n-skeleton text :repeat="3" />
      </div>

      <EmptyState
        v-else-if="visibleActivities.length === 0"
        title="暂无可参与的活动"
        description="每天定时开场，名额有限、先到先得——稍后刷新看看"
      />

      <ul v-else class="seckill-list">
        <li
          v-for="activity in visibleActivities"
          :key="activity.id"
          class="seckill-card"
          :class="[`is-${activity.myStatus.toLowerCase()}`, `phase-${activityPhase(activity)}`]"
        >
          <div class="seckill-card-main">
            <h2 class="seckill-name">{{ activity.name }}</h2>
            <p class="seckill-credit">
              +{{ formatCredit(activity.creditAmount) }}
              <span class="seckill-credit-unit">额度</span>
            </p>
            <p class="seckill-stock">
              剩余 {{ activity.remainingStock }} / {{ activity.totalStock }}
              <span class="seckill-time">
                {{ formatArticleDateTime(activity.startAt) }} 起
              </span>
            </p>
          </div>

          <div class="seckill-card-action">
            <button
              type="button"
              class="seckill-button"
              :disabled="isBusy(activity)"
              @click="grab(activity)"
            >
              {{ buttonLabel(activity) }}
            </button>
            <p v-if="activity.myStatus === 'QUEUED'" class="seckill-hint">
              名额已占住，正在入账
            </p>
          </div>
        </li>
      </ul>
    </div>
  </MainLayout>
</template>

<style scoped>
.seckill-page {
  width: 100%;
  max-width: 1200px;
  margin: 0 auto;
  padding: 10px 18px 56px;
}

.seckill-header {
  display: grid;
  gap: 8px;
  margin-bottom: 18px;
}

.seckill-back {
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

.seckill-back:hover {
  background: #f8fafc;
}

.seckill-header h1 {
  margin: 0;
  color: #1f2937;
  font-size: 22px;
}

.seckill-subtitle {
  margin: 0;
  color: #94a3b8;
  font-size: 13px;
}

.seckill-loading {
  padding: 8px 0;
}

.seckill-list {
  display: grid;
  gap: 12px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.seckill-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  padding: 18px 22px;
  border: 1px solid #eef0f2;
  border-radius: 12px;
  background: #fff;
}

/* 未开始 / 已结束：中性灰。
   刻意不抢「入账中」的暖黄与「已到账」的绿——那两色表达的是个人结果，
   而这两色表达的是「这场活动现在跟你能做的事无关」。 */
.seckill-card.phase-draft,
.seckill-card.phase-ended {
  border-color: #e2e8f0;
  background: #f8fafc;
}

/* 入账中：暖黄，和钱包的「预占中」三态保持一致——它是进行中，不是告警 */
.seckill-card.is-queued {
  border-color: #fde68a;
  background: #fffbeb;
}

.seckill-card.is-granted {
  border-color: #bbf7d0;
  background: #f0fdf4;
}

.seckill-name {
  margin: 0 0 6px;
  color: #1f2937;
  font-size: 16px;
}

.seckill-credit {
  margin: 0 0 6px;
  color: #6366f1;
  font-size: 22px;
  font-weight: 700;
}

.seckill-credit-unit {
  color: #94a3b8;
  font-size: 13px;
  font-weight: 400;
}

.seckill-stock {
  display: flex;
  gap: 12px;
  margin: 0;
  color: #64748b;
  font-size: 13px;
}

.seckill-time {
  color: #94a3b8;
}

.seckill-card-action {
  display: grid;
  justify-items: center;
  gap: 6px;
}

.seckill-button {
  min-width: 108px;
  padding: 9px 18px;
  border: none;
  border-radius: 9px;
  background: #6366f1;
  color: #fff;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.2s ease;
}

.seckill-button:hover:not(:disabled) {
  background: #4f46e5;
}

.seckill-button:disabled {
  background: #e5e7eb;
  color: #9ca3af;
  cursor: not-allowed;
}

.seckill-card.is-granted .seckill-button:disabled {
  background: #dcfce7;
  color: #16a34a;
}

.seckill-card.is-queued .seckill-button:disabled {
  background: #fef3c7;
  color: #b45309;
}

.seckill-hint {
  margin: 0;
  color: #b45309;
  font-size: 12px;
}
</style>
