<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  Add,
  ArrowBack,
  Attach,
  BookmarkOutline,
  Close,
  Contract,
  Expand,
  Send,
  Time,
  TrashOutline,
  CopyOutline,
  CheckmarkOutline,
  RefreshOutline,
  StopCircleOutline,
} from '@vicons/ionicons5'
import { useDialog, useMessage } from 'naive-ui'
import { aiApi } from '@/api/ai'
import type {
  AiMessage,
  AiSession,
  AiConversationSummaryStatus,
  PageContext,
  EditorAction,
  ArticleAction,
  ArticleRagReference,
  AiMemoryCandidate,
  AiMemory,
  AiWorkflowRun,
  AiWorkflowStepLog,
  WorkflowStatus,
  WorkflowStepEvent,
  WorkflowContentDeltaEvent,
  WorkflowStreamResult,
  AgentStepView,
  AgentStepEvent,
  AgentPlanEvent,
  TaskPlanEvent,
  TaskStepEvent,
  ChatStatusEvent,
  AgentStepHistoryItem,
  AgentWriteProposal,
  WorkflowType,
} from '@/api/ai'
import { emitAiEditorAction } from '@/utils/aiEditorBus'
import { emitAiArticleAction } from '@/utils/aiArticleActionBus'
import { emitAiArticleResults } from '@/utils/aiArticleResultBus'
import { renderMarkdown } from '@/utils/markdown'
import { completeLiveProcessSteps, finalizeFailedStreamMessage } from './streamMessageState'

import { useAuthStore } from '@/stores/auth'
import { walletApi } from '@/api/wallet'
import type { WalletInfo } from '@/types/wallet'
import { displayCredit, formatCredit } from '@/utils/format'

// ============================================================
// Auth & guest
// ============================================================

const authStore = useAuthStore()
const route = useRoute()
const router = useRouter()
const message = useMessage()
const dialog = useDialog()

const userAvatar = computed(() => authStore.usersVO?.avatarUrl ?? null)
const isGuest = computed(() => !authStore.isLoggedIn)

// 游客已使用次数（响应式，用于欢迎页判断）
const guestUsedCount = ref(0)

// ============================================================
// 游客 sessionStorage
// ============================================================

const GUEST_AI_MESSAGES_KEY = 'blog_ai_guest_messages'
const GUEST_AI_USED_COUNT_KEY = 'blog_ai_guest_used_count'
const GUEST_MAX = 3

function loadGuestMessages(): AiMessage[] {
  try {
    const raw = sessionStorage.getItem(GUEST_AI_MESSAGES_KEY)
    return raw ? JSON.parse(raw) : []
  } catch {
    return []
  }
}

function saveGuestMessages(msgs: AiMessage[]) {
  sessionStorage.setItem(GUEST_AI_MESSAGES_KEY, JSON.stringify(msgs))
}

function loadGuestUsedCount(): number {
  const raw = sessionStorage.getItem(GUEST_AI_USED_COUNT_KEY)
  return raw ? Number(raw) : 0
}

function saveGuestUsedCount(count: number) {
  sessionStorage.setItem(GUEST_AI_USED_COUNT_KEY, String(count))
}

function clearGuestData() {
  sessionStorage.removeItem(GUEST_AI_MESSAGES_KEY)
  sessionStorage.removeItem(GUEST_AI_USED_COUNT_KEY)
}

// ============================================================
// Page context
// ============================================================

function buildPageContext(): PageContext {
  const name = String(route.name ?? '')
  const path = route.path
  const pageContext: PageContext = { pageType: name, path }

  if (name === 'article-detail') {
    pageContext.articleId = String(route.params.id ?? '')
    // 从 document.title 提取文章标题
    const t = document.title.replace(/\s*-\s*海林Blog$/, '')
    if (t && t !== '文章详情') pageContext.articleTitle = t
  }
  // 编辑已有文章的页面也带 articleId（此前只有详情页带，导致「这篇文章怎么样」没有指代对象，
  // 直接被 Planner CTA 拦住）。后端按 pageType 区分措辞：编辑器里可能有未保存修改，
  // AI 读到的是已保存版本，这一点必须对用户讲清楚。
  if (name === 'editor-edit') {
    pageContext.articleId = String(route.params.id ?? '')
  }
  if (name === 'public-profile') {
    pageContext.userId = String(route.params.id ?? '')
  }
  if (name === 'profile') {
    const myId = authStore.usersVO?.id
    if (myId) pageContext.userId = String(myId)
  }

  if (name === 'profile') {
    pageContext.userId = String(authStore.usersVO?.id ?? '')
  }

  return pageContext
}

// ============================================================
// State
// ============================================================

const visible = ref(false)
const isFullscreen = ref(false)
const viewingHistory = ref(false)
/**
 * 会话级流式状态。
 *
 * 原来 `sending` / `abortController` 都是组件级单例，于是「一个流在跑、整个面板锁死」：
 * 切到别的会话（含新建会话）后输入框仍禁用、发送键仍是暂停键 ——
 * 而用户想做的恰恰是「在另一个会话里并行跑一个任务」。
 *
 * 现在按会话隔离。key 取会话 id；新会话首次发送时后端还没返回 id，用
 * {@link PENDING_SESSION_KEY} 占位（那时 currentSessionId 为 null，
 * 直到流结束 onStop 才会被赋上真实 id）。
 */
const PENDING_SESSION_KEY = '__pending__'

/**
 * 新会话的临时 key —— **每次新建会话都换一个**。
 *
 * 不能所有新会话共用一个固定值：第一个新会话的流还在跑时再点「新建会话」，
 * 第二个新会话会被误判成「正在流式」，输入框照样锁住。
 */
let pendingKeySeq = 0
const pendingSessionKey = ref(`${PENDING_SESSION_KEY}0`)

const streamingKeys = ref<string[]>([])
const streamControllers = new Map<string, AbortController>()

function currentStreamKey(): string {
  return currentSessionId.value ?? pendingSessionKey.value
}

/** 当前**显示的**会话是否正在流式（输入框禁用 / 发送键切换成暂停键都用它） */
const sending = computed(() => streamingKeys.value.includes(currentStreamKey()))

/**
 * 已与 UI 脱离的流 key。
 *
 * 切换 / 新建会话时把当时所有活跃流标记为脱离：它们的后续事件不再更新视图
 * （messages 已被重新加载、占位气泡索引失效），只在后台照常跑完，
 * 内容由切回该会话时的重新拉取补上。
 *
 * 不能只靠「当前 key 与流 key 是否相等」判断——切走再切回来，两者又相等了。
 */
const detachedStreamKeys = ref<string[]>([])

/** 切换 / 新建会话：当前视图不再属于任何正在跑的流 */
function detachAllStreams() {
  if (streamingKeys.value.length > 0) {
    detachedStreamKeys.value = [...streamingKeys.value]
  }
}

/** 该流是否仍应更新视图（判据见 detachedStreamKeys 的注释） */
function streamAttached(key: string): boolean {
  return !detachedStreamKeys.value.includes(key)
}

/** 开启一个流；同一会话内的旧请求先取消，不同会话的流互不影响 */
function startStream(key: string): AbortController {
  // 同一 key 的新流要重新附着（清掉上一轮留下的脱离标记）
  detachedStreamKeys.value = detachedStreamKeys.value.filter((k) => k !== key)
  streamControllers.get(key)?.abort()
  const controller = new AbortController()
  streamControllers.set(key, controller)
  if (!streamingKeys.value.includes(key)) {
    streamingKeys.value = [...streamingKeys.value, key]
  }
  return controller
}

function endStream(key: string) {
  streamControllers.delete(key)
  streamingKeys.value = streamingKeys.value.filter((k) => k !== key)
}

const loadingMessages = ref(false)
const input = ref('')
const messageListRef = ref<HTMLElement | null>(null)
// 滚动跟随状态：用户手动上滑后停止自动滚动，滑到底部后恢复
const shouldAutoScroll = ref(true)
const AUTO_SCROLL_THRESHOLD = 48

const sessions = ref<AiSession[]>([])
const currentSessionId = ref<string | null>(null)
const messages = ref<AiMessage[]>([])

// ============================================================
// 会话压缩提示：compressing=正在压缩 done=已完成（两行提示）
// ============================================================

type CompressionPhase = 'idle' | 'compressing' | 'done'
const compressionPhase = ref<CompressionPhase>('idle')
const compressionCoveredMessageCount = ref(0)
// 上次已知的压缩完成时间：轮询发现 lastCompressedAt 变化即判断"本次压缩已完成"
let compressionBaseline: string | null = null

function resetCompressionHint() {
  compressionPhase.value = 'idle'
  compressionCoveredMessageCount.value = 0
  compressionBaseline = null
}

function showCompressionDone(lastCompressedAt: string, coveredMessageCount?: number) {
  compressionBaseline = lastCompressedAt
  if (typeof coveredMessageCount === 'number') {
    compressionCoveredMessageCount.value = coveredMessageCount
  }
  compressionPhase.value = 'done'
}

function sleep(ms: number) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

/** 编排回复是完成后批量切块到达，按一帧多一点的节奏展示，避免肉眼看成整段跳出。 */
const TASK_REPLY_CHUNK_DELAY_MS = 24

// 压缩轮询序号：连续回复时旧轮询让位，避免旧状态覆盖新状态
let compressionCheckSeq = 0

// 回复结束后轮询压缩状态：
// 后端压缩是异步的（LLM 调用 + 线程池排队，可能延迟数秒才启动），
// 所以只要"上次压缩时间没变"就持续轮询（最多约 40s）：
// compressing=true 显示"正在自动压缩上下文"，lastCompressedAt 变化显示"已自动压缩上下文"
async function checkCompressionAfterReply(sessionId: string) {
  if (isGuest.value || !sessionId) return
  const seq = ++compressionCheckSeq
  const baseline = compressionBaseline

  // 延迟启动给异步压缩线程留启动窗口
  await sleep(800)

  for (let i = 0; i < 20; i++) {
    if (seq !== compressionCheckSeq) return // 已发起新的检查，让位

    let status: AiConversationSummaryStatus | null = null
    try {
      status = (await aiApi.getSummaryStatus(sessionId)).data
    } catch {
      console.warn('查询会话压缩状态失败', sessionId)
      return
    }
    if (!status) return

    if (!status.eligible) {
      resetCompressionHint()
      return
    }

    if (status.lastCompressedAt && status.lastCompressedAt !== baseline) {
      // 压缩已完成（无论是否抓到"压缩中"阶段）
      showCompressionDone(status.lastCompressedAt, status.coveredMessageCount)
      return
    }
    if (status.compressing) {
      compressionCoveredMessageCount.value = status.coveredMessageCount
      compressionPhase.value = 'compressing'
    }
    await sleep(2000)
  }
  // 超时后保持最后一次可见状态，不自动消失
}

async function syncCompressionHint(sessionId: string) {
  if (isGuest.value || !sessionId) return

  try {
    const status = (await aiApi.getSummaryStatus(sessionId)).data
    if (!status) {
      resetCompressionHint()
      return
    }

    if (!status.eligible) {
      resetCompressionHint()
      return
    }

    if (status.compressing) {
      compressionBaseline = status.lastCompressedAt
      compressionCoveredMessageCount.value = status.coveredMessageCount
      compressionPhase.value = 'compressing'
      return
    }

    if (status.lastCompressedAt) {
      showCompressionDone(status.lastCompressedAt, status.coveredMessageCount)
      return
    }

    resetCompressionHint()
  } catch {
    // 查询失败时保留当前状态，不强行清空
  }
}

// 用于取消正在进行的流式请求

// ============================================================
// Workflow 状态
// ============================================================

const activeWorkflow = ref<AiWorkflowRun | null>(null)

/**
 * 正在执行操作的 workflow id 集合。
 *
 * 原来是单个布尔量：在 A 会话点「同意」后切到 B 会话，B 的 workflow 按钮也会被禁用。
 * 改成按 workflow id 记录后，`workflowBusy` 只反映**当前显示的那个** workflow ——
 * 模板里的 `:disabled="workflowBusy"` 一处都不用改。
 */
const busyWorkflowIds = ref<string[]>([])
const workflowBusy = computed(() =>
  activeWorkflow.value ? busyWorkflowIds.value.includes(activeWorkflow.value.id) : false,
)

function markWorkflowBusy(id: string, busy: boolean) {
  busyWorkflowIds.value = busy
    ? [...new Set([...busyWorkflowIds.value, id])]
    : busyWorkflowIds.value.filter((x) => x !== id)
}
const workflowRejectEditing = ref(false)
const workflowFeedback = ref('')

// Workflow 步骤执行日志：按 workflowId 缓存 + 展开态 + 加载态
const workflowStepLogs = ref<Record<string, AiWorkflowStepLog[]>>({})
const workflowStepLogsExpanded = ref<Record<string, boolean>>({})
const workflowStepLogsLoading = ref<Record<string, boolean>>({})

// Workflow 操作幂等 Key：同一 workflow 同一步操作复用同一 Key，
// 网络失败重试不重复执行（后端按 Key 返回上次结果）；成功/业务失败后清除
const WORKFLOW_ACTION_KEY_PREFIX = 'ai_workflow_action_key:'

type PendingWorkflowAction = {
  workflowId: string
  action: 'APPROVE' | 'REJECT' | 'RETRY' | 'CONFIRM_SUGGEST'
  payload: string
  key: string
}

function createWorkflowIdempotencyKey(): string {
  if (
    typeof crypto !== 'undefined'
    && typeof crypto.randomUUID === 'function'
  ) {
    return crypto.randomUUID()
  }

  return `${Date.now()}-${Math.random().toString(36).slice(2)}`
}

function workflowActionStorageKey(
  workflowId: string,
  action: string,
): string {
  return `${WORKFLOW_ACTION_KEY_PREFIX}${workflowId}:${action}`
}

function getWorkflowActionKey(
  workflowId: string,
  action: PendingWorkflowAction['action'],
  payload = '',
): string {
  const storageKey =
    workflowActionStorageKey(workflowId, action)

  try {
    const raw = sessionStorage.getItem(storageKey)

    if (raw) {
      const pending =
        JSON.parse(raw) as PendingWorkflowAction

      if (
        pending.workflowId === workflowId
        && pending.action === action
        && pending.payload === payload
        && pending.key
      ) {
        return pending.key
      }
    }
  } catch {
    // sessionStorage 或 JSON 异常时重新生成
  }

  const pending: PendingWorkflowAction = {
    workflowId,
    action,
    payload,
    key: createWorkflowIdempotencyKey(),
  }

  try {
    sessionStorage.setItem(
      storageKey,
      JSON.stringify(pending),
    )
  } catch {
    // 存储失败时仍使用当前内存 Key
  }

  return pending.key
}

function clearWorkflowActionKey(
  workflowId: string,
  action: PendingWorkflowAction['action'],
): void {
  try {
    sessionStorage.removeItem(
      workflowActionStorageKey(workflowId, action),
    )
  } catch {
    // ignore storage errors
  }
}

// V2.3：Agent 思考面板展开状态（全局：同一时刻通常只有一条 AI 消息在思考）
const thinkingStepsExpanded = ref(false)
// V3.10：思考面板自动展开/收起——思考期间（AGENT_STEP）自动展开，
// 正文开始输出（首个 DATA chunk）自动收起，用户可手动再展开（点击 toggle 即退出自动模式）
const thinkingAutoMode = ref(false)

/*
 * V4.x：过程步骤（瞬态，累积展示）。
 *
 * 从 V4.5 的"单条状态"升级为"步骤列表"：新步骤追加、前一步自动算完成，
 * 渲染成 ⌛（进行中）/ ✓（已完成）——实测反馈："步骤累计，而不是新的覆盖旧的，
 * 像是思考过程一样，有步骤，还有打勾，沙漏表示状态"。
 *
 * 仍然是瞬态的（不放进 AiMessage）：消息会写 localStorage，混进去刷新后会残留假步骤。
 */
const CHAT_STATUS_DELAY_MS = 250
const liveChatSteps = ref<string[]>([])

/*
 * V4.x：过程面板展开态（按消息索引；默认展开）。
 * 与思考面板的全局单 ref 不同——过程链是**每条消息各自**的，共用一个开合会互相干扰。
 */
const processPanelCollapsed = ref<Record<number, boolean>>({})

function isProcessPanelOpen(index: number) {
  return !processPanelCollapsed.value[index]
}

function toggleProcessPanel(index: number) {
  processPanelCollapsed.value[index] = !processPanelCollapsed.value[index]
}

/**
 * V4.x：工作流步骤也进同一条过程链。
 *
 * 用户要的是"一条链看完整过程"，而不是过程状态一条、工作流卡一条各说各的（实测反馈：
 * "思考过程怎么是和工作流卡一起出现"）。工作流卡保留——它承担内容展示与交互，
 * 面板只负责"这一轮都做了什么"的时序。
 */
function pushWorkflowStepToProcess(event: WorkflowStepEvent) {
  const text = (event?.message ?? '').trim()
  if (!text) return
  if (liveChatSteps.value[liveChatSteps.value.length - 1] === text) return
  liveChatSteps.value.push(text)
}
let chatStatusShowTimer: ReturnType<typeof setTimeout> | null = null
let chatStatusShownAt = 0

// Workflow 流式正文：生成草稿时按 workflowId 累积 delta
const workflowStreamingContent = ref<Record<string, string>>({})
// Workflow 流式大纲：重写大纲时按 workflowId 累积 delta
const workflowStreamingOutline = ref<Record<string, string>>({})

// 本地过渡态：点击按钮后立刻切换面板，解决"卡住"体感
type LocalTransition = 'APPROVING' | 'REJECTING' | 'RETRYING' | 'CANCELLING'

/** 每个 workflow 各自的过渡态（理由同 workflowBusy：切会话时不能互相影响） */
const transitionsByWorkflow = ref<Record<string, LocalTransition>>({})
const localTransition = computed<LocalTransition | null>(() => {
  const id = activeWorkflow.value?.id
  return id ? (transitionsByWorkflow.value[id] ?? null) : null
})

function setLocalTransition(id: string, t: LocalTransition | null) {
  const next = { ...transitionsByWorkflow.value }
  if (t === null) {
    delete next[id]
  } else {
    next[id] = t
  }
  transitionsByWorkflow.value = next
}

const isTransitioning = computed(() => localTransition.value !== null)

const transitionLabel = computed(() => {
  const t = localTransition.value
  const status = activeWorkflow.value?.status
  if (t === 'APPROVING') {
    if (status === 'WAITING_OUTLINE_CONFIRM') return '正在根据大纲生成草稿...'
    if (status === 'WAITING_DRAFT_CONFIRM') return '正在校验草稿质量...'
    if (status === 'WAITING_FILL_CONFIRM') return '正在填充到编辑器...'
    return 'AI 正在处理...'
  }
  if (t === 'REJECTING') {
    if (status === 'WAITING_OUTLINE_CONFIRM') return '正在根据意见重新生成大纲...'
    if (status === 'WAITING_DRAFT_CONFIRM') return '正在根据修改意见重写草稿...'
    if (status === 'WAITING_REQUIREMENT_CONFIRM') return '正在分析补充需求...'
    return 'AI 正在处理...'
  }
  if (t === 'RETRYING') return '正在重试失败步骤...'
  if (t === 'CANCELLING') return '正在取消工作流...'
  return ''
})

// 确认卡片：后端停在等待确认态时写入，前端按 type 渲染对应面板
const workflowConfirmation = computed(() => {
  return activeWorkflow.value?.context?.confirmation ?? null
})

const workflowWaitingConfirm = computed(() => {
  const confirmation = workflowConfirmation.value
  if (confirmation === null || confirmation.type === 'REQUIREMENT') return false
  // 只在等待确认态显示确认面板：确认被消费后卡片可能残留（存量 run），
  // WAITING_USER_SAVE 应显示"已保存/发布"面板而不是"同意"按钮
  const status = activeWorkflow.value?.status
  return (
    status === 'WAITING_OUTLINE_CONFIRM' ||
    status === 'WAITING_DRAFT_CONFIRM' ||
    status === 'WAITING_PLAN_CONFIRM' ||
    status === 'WAITING_LEARNING_PLAN_CONFIRM' ||
    status === 'WAITING_FILL_CONFIRM'
  )
})

const workflowNeedRequirement = computed(() => {
  return (
    workflowConfirmation.value?.type === 'REQUIREMENT' &&
    activeWorkflow.value?.status === 'WAITING_REQUIREMENT_CONFIRM'
  )
})

const workflowFailed = computed(() => {
  return activeWorkflow.value?.status === 'FAILED'
})

const workflowQualityCheck = computed(() => {
  const ctx = activeWorkflow.value?.context
  // contentCheck=优化稿检查 / qualityCheck=创作草稿检查；顶层兜底兼容历史 run 数据位置
  return ctx?.stepResults?.contentCheck ?? ctx?.stepResults?.qualityCheck ?? ctx?.qualityCheck ?? null
})

//学习规划 Workflow 生成的结构化计划（确认面板渲染用）
const workflowLearningPlan = computed(() => {
  return activeWorkflow.value?.context?.stepResults?.plan ?? null
})

const workflowHasBlockingIssues = computed(() => {
  const check = workflowQualityCheck.value
  if (!check) return false
  return check.passed === false || (check.issues?.length ?? 0) > 0
})

// 草稿被打回：确认卡片为 DRAFT + 存在阻塞问题 → 直接展开修改意见输入框（重写模式）
const workflowDraftNeedsFix = computed(() => {
  return (
    workflowConfirmation.value?.type === 'DRAFT' &&
    workflowHasBlockingIssues.value
  )
})

function workflowStatusLabel(status?: WorkflowStatus) {
  if (status === 'WAITING_REQUIREMENT_CONFIRM') return '等待补充需求'
  if (status === 'WAITING_OUTLINE_CONFIRM') return '等待确认大纲'
  if (status === 'WAITING_PLAN_CONFIRM') return '等待确认优化方案'
  if (status === 'WAITING_LEARNING_PLAN_CONFIRM') return '等待确认学习计划'
  if (status === 'WAITING_DRAFT_CONFIRM') return '等待确认草稿'
  if (status === 'WAITING_FILL_CONFIRM') return '等待填充编辑器'
  if (status === 'WAITING_USER_SAVE') return '已填充编辑器，等待保存/发布'
  if (status === 'PAUSED') return '已暂停'
  if (status === 'FAILED') return '执行失败'
  if (status === 'COMPLETED') return '已完成'
  if (status === 'CANCELLED') return '已取消'
  return 'Workflow'
}

function workflowStepLabel(step?: string) {
  if (step === 'REQUIREMENT_ANALYZE') return '需求分析'
  if (step === 'MEMORY_RETRIEVE') return '记忆召回'
  if (step === 'RAG_SEARCH') return '知识库检索'
  if (step === 'GENERATE_OUTLINE') return '生成大纲'
  if (step === 'GENERATE_DRAFT') return '生成草稿'
  if (step === 'QUALITY_CHECK') return '质量检查'
  if (step === 'FILL_ARTICLE') return '填充编辑器'
  if (step === 'LOAD_ARTICLE') return '加载文章'
  if (step === 'ANALYZE_ARTICLE') return '分析文章'
  if (step === 'GENERATE_OPTIMIZATION_PLAN') return '生成优化方案'
  if (step === 'REWRITE_ARTICLE') return '重写文章'
  if (step === 'CONTENT_CHECK') return '内容检查'
  if (step === 'ANALYZE_GOAL') return '分析学习目标'
  if (step === 'GENERATE_PLAN') return '生成结构化学习计划'
  if (step === 'SAVE_PLAN') return '保存学习计划'
  if (step === 'LOAD_PLAN') return '加载学习计划'
  if (step === 'ANALYZE_CHANGE') return '分析调整诉求'
  if (step === 'LOCATE_STAGE') return '定位难点阶段'
  if (step === 'GENERATE_TASKS') return '拆解任务点'
  if (step === 'APPEND_TASKS') return '追加任务点'
  // stream 占位日志兜底：后端没推断出 step 时显示 action
  if (step === 'APPROVE') return '确认推进'
  if (step === 'REJECT') return '按反馈重新生成'
  if (step === 'RETRY') return '重试失败步骤'
  return step || '未知'
}

function workflowStepStatusLabel(status?: string) {
  if (status === 'RUNNING') return '执行中'
  if (status === 'SUCCESS') return '成功'
  if (status === 'FAILED') return '失败'
  if (status === 'SKIPPED') return '跳过'
  return status || '未知'
}

function workflowStepStatusClass(status?: string) {
  if (status === 'SUCCESS') return 'ai-workflow-step-log__status--success'
  if (status === 'FAILED') return 'ai-workflow-step-log__status--failed'
  if (status === 'RUNNING') return 'ai-workflow-step-log__status--running'
  if (status === 'SKIPPED') return 'ai-workflow-step-log__status--skipped'
  return ''
}

function formatDuration(ms?: number) {
  if (ms === undefined || ms === null) return '—'
  if (ms < 1000) return `${ms}ms`
  return `${(ms / 1000).toFixed(1)}s`
}

function formatWorkflowQualityCheck(check?: {
  passed?: boolean
  issues?: string[]
  suggestions?: string[]
}) {
  // passed 为空时表示质量检查尚未执行（如大纲确认阶段），不展示
  if (!check || check.passed === undefined) return ''

  const lines: string[] = []
  lines.push(`质量检查：${check.passed === false ? '未通过' : '通过'}`)

  if (check.issues?.length) {
    lines.push(`\n问题：\n- ${check.issues.join('\n- ')}`)
  }

  if (check.suggestions?.length) {
    lines.push(`\n建议：\n- ${check.suggestions.join('\n- ')}`)
  }

  return lines.join('\n')
}

function buildWorkflowSnapshotContent(workflow: AiWorkflowRun) {
  // 学习规划 / 学习进度 Workflow：显示计划标题 + 阶段摘要
  if (workflow.workflowType === 'LEARNING_PLAN' || workflow.workflowType === 'LEARNING_PROGRESS') {
    const plan = workflow.context?.stepResults?.plan
    if (plan?.title) {
      const stageCount = plan.stages?.length ?? 0
      const prefix = workflow.workflowType === 'LEARNING_PROGRESS' ? '调整后计划' : '学习计划'
      return `${prefix}：${plan.title}${stageCount ? `（共 ${stageCount} 个阶段）` : ''}`
    }
    return workflow.workflowType === 'LEARNING_PROGRESS' ? '学习进度 Workflow' : '学习规划 Workflow'
  }

  // 难点攻坚 Workflow：显示目标计划 + 阶段 + 新增任务点数
  if (workflow.workflowType === 'LEARNING_ASSIST') {
    const plan = workflow.context?.stepResults?.plan
    const planTitle = workflow.context?.planTitle
    const stageTitle = workflow.context?.targetStageTitle
    const taskCount = plan?.stages?.[0]?.tasks?.length ?? 0
    if (taskCount) {
      return `难点攻坚：${planTitle || ''}「${stageTitle || ''}」新增 ${taskCount} 个任务点`
    }
    return '难点攻坚 Workflow'
  }

  // 文章优化 Workflow：显示优化方案 / 优化稿
  if (workflow.workflowType === 'OPTIMIZE_ARTICLE') {
    const stepResults = workflow.context?.stepResults
    const plan = stepResults?.optimizationPlan?.trim()
    const optimizedContent = stepResults?.optimizedContent?.trim()
    const qualityText = formatWorkflowQualityCheck(stepResults?.contentCheck)

    if (workflow.status === 'WAITING_PLAN_CONFIRM') {
      return plan || '已进入文章优化 Workflow，正在生成优化方案。'
    }

    if (
      workflow.status === 'WAITING_DRAFT_CONFIRM' ||
      workflow.status === 'WAITING_USER_SAVE' ||
      workflow.status === 'COMPLETED'
    ) {
      return [optimizedContent, qualityText].filter(Boolean).join('\n\n') ||
        '已进入文章优化 Workflow，正在重写文章。'
    }

    return `Workflow 状态：${workflow.status}`
  }

  // 文章创作 Workflow：原有逻辑（stepResults 优先，顶层兜底兼容历史 run）
  const requirement = workflow.context?.requirement?.topic?.trim() || '文章'
  const stepResults = workflow.context?.stepResults
  const outline = stepResults?.outline?.trim() || workflow.context?.outline?.trim()
  const draft = stepResults?.draft ?? workflow.context?.draft
  const qualityCheck = stepResults?.qualityCheck ?? workflow.context?.qualityCheck

  if (workflow.status === 'WAITING_OUTLINE_CONFIRM') {
    return outline || `已进入 ${requirement} 工作流，正在生成大纲。`
  }

  if (
    workflow.status === 'WAITING_DRAFT_CONFIRM' ||
    workflow.status === 'WAITING_FILL_CONFIRM' ||
    workflow.status === 'WAITING_USER_SAVE' ||
    workflow.status === 'COMPLETED'
  ) {
    const title = draft?.title?.trim() || requirement
    const summary = draft?.summary?.trim()
    const content = draft?.content?.trim()
    const qualityText = formatWorkflowQualityCheck(qualityCheck)

    return [
      `## ${title}`,
      summary,
      content,
      qualityText,
    ].filter(Boolean).join('\n\n')
  }

  return `Workflow 状态：${workflow.status}`
}

/** 创作 Workflow 卡片渲染数据：stepResults 优先，顶层兜底兼容历史 run（C 批统一数据位置后旧 run 仍可渲染） */
function createCardData(ctx?: AiWorkflowRun['context']) {
  const sr = ctx?.stepResults
  return {
    outline: sr?.outline || ctx?.outline,
    draft: sr?.draft ?? ctx?.draft,
    qualityCheck: sr?.qualityCheck ?? ctx?.qualityCheck,
  }
}

function syncWorkflowSnapshotMessage(workflow: AiWorkflowRun) {
  // 找到消息列表中已有的 workflow 消息，更新其 workflow 字段
  const existingIndex = messages.value.findIndex(
    (msg) => msg.role === 'ai' && msg.workflow?.id === workflow.id,
  )
  if (existingIndex >= 0) {
    messages.value[existingIndex] = {
      ...messages.value[existingIndex],
      workflow,
      // 已有真实落库内容不覆盖（与 attachWorkflowToLatestAiMessage 一致），
      // 否则"请先确认生成方案"等快照消息会被后续状态快照刷掉
      content:
        messages.value[existingIndex].content || buildWorkflowSnapshotContent(workflow),
    }
  }
}

/** 刷新后历史消息没有 workflow 字段，把当前 active workflow 挂回最后一条 AI 消息 */
function attachWorkflowToLatestAiMessage(workflow: AiWorkflowRun) {
  const existingIndex = messages.value.findIndex(
    (msg) => msg.role === 'ai' && msg.workflow?.id === workflow.id,
  )

  if (existingIndex >= 0) {
    messages.value[existingIndex] = {
      ...messages.value[existingIndex],
      workflow,
      // 历史消息落库时已有真实 content，只在为空时用快照兜底，不覆盖
      content:
        messages.value[existingIndex].content || buildWorkflowSnapshotContent(workflow),
    }
    return
  }

  for (let i = messages.value.length - 1; i >= 0; i--) {
    if (messages.value[i].role === 'ai') {
      messages.value[i] = {
        ...messages.value[i],
        workflow,
        content: messages.value[i].content || buildWorkflowSnapshotContent(workflow),
      }
      return
    }
  }
}

function resetWorkflowRejectUI() {
  workflowRejectEditing.value = false
  workflowFeedback.value = ''
}

async function approveWorkflow() {
  if (!activeWorkflow.value || workflowBusy.value) return

  const workflowId = activeWorkflow.value.id
  // workflow 流用独立 key（会话 key + workflowId）：既不与同会话的聊天流互相 abort，
  // 也让「脱离」能精确到这个流
  const streamKey = `${currentStreamKey()}:wf:${workflowId}`
  const controller = startStream(streamKey)
  const attached = () => streamAttached(streamKey)
  const idempotencyKey =
    getWorkflowActionKey(workflowId, 'APPROVE')

  markWorkflowBusy(workflowId, true)
  setLocalTransition(workflowId, 'APPROVING')

  try {
    await aiApi.streamApproveWorkflow(
      workflowId,
      idempotencyKey,
      {
        // 步骤日志与 delta 都按 workflowId 存，用户切走时继续累积反而是对的
        // （切回来能看到完整内容），所以这两个不加守卫。
        onStep: applyWorkflowStepEvent,

        onContentDelta: applyWorkflowContentDelta,

        async onStop(data) {
          clearWorkflowActionKey(workflowId, 'APPROVE')
          resetWorkflowRejectUI()
          // 结果会写 activeWorkflow —— 用户已切走时那是**别的会话**的面板，不能碰。
          // 只维护本地会话锚点，切回去由 restoreActiveWorkflow 重新拉取。
          if (!attached()) {
            if (data.workflow) clearSessionActiveWorkflow(data.workflow.id)
            return
          }
          await applyWorkflowStreamResult(data)
        },

        async onWorkflowError(data) {
          // 后端已经执行完成，只是业务结果为 FAILED
          if (data.workflow) {
            clearWorkflowActionKey(workflowId, 'APPROVE')
          }

          message.error(
            data.message
              ?? data.workflow?.errorMessage
              ?? 'Workflow 执行失败',
          )

          if (!attached()) return
          await applyWorkflowStreamResult(data)
        },

        onError(error) {
          // 网络断开时保留 Key，下一次点击复用
          message.error(
            error?.message ?? 'Workflow 同意失败',
          )
        },
      },
      controller.signal,
    )
  } catch (error: any) {
    message.error(
      error?.message ?? 'Workflow 同意失败',
    )
  } finally {
    endStream(streamKey)
    markWorkflowBusy(workflowId, false)
    setLocalTransition(workflowId, null)
  }
}

async function rejectWorkflow() {
  const feedback = workflowFeedback.value.trim()

  if (!feedback) {
    message.warning('请先填写修改意见')
    return
  }

  if (!activeWorkflow.value || workflowBusy.value) return

  const workflowId = activeWorkflow.value.id
  const streamKey = `${currentStreamKey()}:wf:${workflowId}`
  const controller = startStream(streamKey)
  const attached = () => streamAttached(streamKey)
  const idempotencyKey =
    getWorkflowActionKey(
      workflowId,
      'REJECT',
      feedback,
    )

  markWorkflowBusy(workflowId, true)
  setLocalTransition(workflowId, 'REJECTING')

  try {
    await aiApi.streamRejectWorkflow(
      workflowId,
      feedback,
      idempotencyKey,
      {
        onStep: applyWorkflowStepEvent,

        onContentDelta: applyWorkflowContentDelta,

        async onStop(data) {
          clearWorkflowActionKey(workflowId, 'REJECT')
          resetWorkflowRejectUI()
          if (!attached()) {
            if (data.workflow) clearSessionActiveWorkflow(data.workflow.id)
            return
          }
          await applyWorkflowStreamResult(data)
        },

        async onWorkflowError(data) {
          if (data.workflow) {
            clearWorkflowActionKey(workflowId, 'REJECT')
          }

          message.error(
            data.message
              ?? data.workflow?.errorMessage
              ?? 'Workflow 修改失败',
          )

          if (!attached()) return
          await applyWorkflowStreamResult(data)
        },

        onError(error) {
          // 网络失败保留相同 Key
          message.error(
            error?.message ?? 'Workflow 修改失败',
          )
        },
      },
      controller.signal,
    )
  } catch (error: any) {
    message.error(
      error?.message ?? 'Workflow 修改失败',
    )
  } finally {
    endStream(streamKey)
    markWorkflowBusy(workflowId, false)
    setLocalTransition(workflowId, null)
  }
}

async function retryWorkflow() {
  if (!activeWorkflow.value || workflowBusy.value) return

  const workflowId = activeWorkflow.value.id
  const streamKey = `${currentStreamKey()}:wf:${workflowId}`
  const controller = startStream(streamKey)
  const attached = () => streamAttached(streamKey)
  const idempotencyKey =
    getWorkflowActionKey(workflowId, 'RETRY')

  markWorkflowBusy(workflowId, true)
  setLocalTransition(workflowId, 'RETRYING')

  try {
    await aiApi.streamRetryWorkflow(
      workflowId,
      idempotencyKey,
      {
        onStep: applyWorkflowStepEvent,

        onContentDelta: applyWorkflowContentDelta,

        async onStop(data) {
          clearWorkflowActionKey(workflowId, 'RETRY')
          resetWorkflowRejectUI()
          message.success('Workflow 已重试')
          if (!attached()) {
            if (data.workflow) clearSessionActiveWorkflow(data.workflow.id)
            return
          }
          await applyWorkflowStreamResult(data)
        },

        async onWorkflowError(data) {
          if (data.workflow) {
            clearWorkflowActionKey(workflowId, 'RETRY')
          }

          message.error(
            data.message
              ?? data.workflow?.errorMessage
              ?? 'Workflow 重试失败',
          )

          if (!attached()) return
          await applyWorkflowStreamResult(data)
        },

        onError(error) {
          message.error(
            error?.message ?? 'Workflow 重试失败',
          )
        },
      },
      controller.signal,
    )
  } catch (error: any) {
    message.error(
      error?.message ?? 'Workflow 重试失败',
    )
  } finally {
    endStream(streamKey)
    markWorkflowBusy(workflowId, false)
    setLocalTransition(workflowId, null)
  }
}

async function cancelWorkflow() {
  if (!activeWorkflow.value || workflowBusy.value) return

  const workflowId = activeWorkflow.value.id

  markWorkflowBusy(workflowId, true)
  setLocalTransition(workflowId, 'CANCELLING')
  try {
    await aiApi.cancelWorkflow(workflowId)
    await refreshBoundTaskResult(workflowId)
    clearSessionActiveWorkflow(workflowId)
    // 只有面板上还是这个 workflow 时才清 —— 用户切走后 activeWorkflow
    // 已经是别的会话的了，不能连带清掉
    if (activeWorkflow.value?.id === workflowId) {
      activeWorkflow.value = null
      workflowRejectEditing.value = false
      workflowFeedback.value = ''
    }
    message.success('Workflow 已取消')
  } catch (error: any) {
    message.error(error?.message ?? '取消 Workflow 失败')
  } finally {
    markWorkflowBusy(workflowId, false)
    setLocalTransition(workflowId, null)
  }
}

/** 完成/取消后清掉本地 sessions 缓存里的 activeWorkflowRunId */
function clearSessionActiveWorkflow(workflowId?: string) {
  const session = sessions.value.find((item) => item.id === currentSessionId.value)
  if (session && (!workflowId || session.activeWorkflowRunId === workflowId)) {
    session.activeWorkflowRunId = undefined
  }
}

/** 流式 delta：按 field 分派——创作走 draft.content / outline，优化走 optimizedContent / optimizationPlan */
function applyWorkflowContentDelta(event: WorkflowContentDeltaEvent) {
  if (!event.workflowRunId) return

  if (event.field === 'draft.content' || event.field === 'optimizedContent') {
    const old = workflowStreamingContent.value[event.workflowRunId] ?? ''
    workflowStreamingContent.value[event.workflowRunId] = old + (event.delta ?? '')
    return
  }

  if (event.field === 'outline' || event.field === 'optimizationPlan') {
    const old = workflowStreamingOutline.value[event.workflowRunId] ?? ''
    workflowStreamingOutline.value[event.workflowRunId] = old + (event.delta ?? '')
  }
}

/** V3.10：手动切换思考面板——用户干预后本次流式自动模式退出（不再自动展开/收起） */
function onToggleThinkingSteps() {
  thinkingAutoMode.value = false
  thinkingStepsExpanded.value = !thinkingStepsExpanded.value
}

/** V2.3：Agent 思考步骤实时累计到当前 AI 消息（按 stepNo upsert，RUNNING → SUCCESS/FAILED 更新状态） */
function applyAgentStepEvent(event: AgentStepEvent, messageIndex: number) {
  const current = messages.value[messageIndex]
  if (!current || current.role !== 'ai') return

  const steps = [...(current.thinkingSteps ?? [])]
  const idx = steps.findIndex((s) => s.stepNo === event.stepNo)
  const step: AgentStepView = {
    stepNo: event.stepNo,
    actionType: event.actionType,
    status: event.status,
    message: event.message,
    // V3.10：思考摘要透传（与后端事件同源，空则行文本回退 message）
    thoughtSummary: event.thoughtSummary ?? null,
  }
  if (idx >= 0) steps[idx] = step
  else steps.push(step)
  steps.sort((a, b) => a.stepNo - b.stepNo)

  messages.value[messageIndex] = { ...current, thinkingSteps: steps }
}

/**
 * 第三刀：任务计划实时落到当前 AI 消息。
 *
 * 计划到达时所有步骤都是 PENDING——先在界面上把"要做哪几件事"列出来，
 * 用户不必等结果回来才知道 AI 打算做什么。
 */
function applyTaskPlanEvent(event: TaskPlanEvent, messageIndex: number) {
  const current = messages.value[messageIndex]
  if (!current || current.role !== 'ai') return

  const steps: TaskStepEvent[] = event.steps.map((s) => ({
    stepId: s.stepId,
    type: s.type,
    state: 'PENDING',
    code: '',
    summary: '',
    goalEvidence: s.goalEvidence ?? [],
  }))
  messages.value[messageIndex] = { ...current, taskSteps: steps }
}

/** 第三刀：任务步骤实时 upsert（按 stepId，同一步骤状态会从 RUNNING 更新到终态） */
function applyTaskStepEvent(event: TaskStepEvent, messageIndex: number) {
  const current = messages.value[messageIndex]
  if (!current || current.role !== 'ai') return

  const steps = [...(current.taskSteps ?? [])]
  const idx = steps.findIndex((s) => s.stepId === event.stepId)
  if (idx >= 0) {
    steps[idx] = {
      ...steps[idx],
      ...event,
      goalEvidence: event.goalEvidence ?? steps[idx].goalEvidence,
    }
  }
  else steps.push(event)

  messages.value[messageIndex] = { ...current, taskSteps: steps }
}

/**
 * 第四步：把子 run 的内部步骤挂到对应任务步骤下（按 taskStepId）。
 *
 * 与 applyAgentStepEvent 的分工：那条记进 message.thinkingSteps（整条消息的过程），
 * 这条记进某个 taskSteps[].innerSteps（那一行的展开明细）——两个层级不能混。
 */
function applyInnerAgentStepEvent(event: AgentStepEvent, messageIndex: number) {
  const current = messages.value[messageIndex]
  if (!current || current.role !== 'ai' || !event.taskStepId) return

  const taskSteps = [...(current.taskSteps ?? [])]
  const idx = taskSteps.findIndex((s) => s.stepId === event.taskStepId)
  if (idx < 0) return

  const inner = [...(taskSteps[idx].innerSteps ?? [])]
  const step: AgentStepView = {
    stepNo: event.stepNo,
    actionType: event.actionType,
    status: event.status,
    message: event.message,
    thoughtSummary: event.thoughtSummary ?? null,
  }
  const existing = inner.findIndex((s) => s.stepNo === event.stepNo)
  if (existing >= 0) inner[existing] = step
  else inner.push(step)
  inner.sort((a, b) => a.stepNo - b.stepNo)

  taskSteps[idx] = { ...taskSteps[idx], innerSteps: inner }
  messages.value[messageIndex] = { ...current, taskSteps }
}

/** 第三刀：步骤状态图标。DISPATCHED 用"已发出"的语义，不用成功勾——后端不知道前端跳没跳成 */
function taskStateIcon(state: string): string {
  switch (state) {
    case 'SUCCEEDED':
      return '✅'
    case 'DISPATCHED':
      return '📤'
    case 'FAILED':
      return '❌'
    case 'SKIPPED':
      return '⏭️'
    case 'WAITING_CONFIRM':
    case 'WAITING_USER':
      return '⏸️'
    case 'RUNNING':
      return '⏳'
    default:
      return '•'
  }
}

/** 第三刀：步骤类型的中文标签（尚未执行时 summary 为空，用它兜底显示） */
function taskTypeLabel(type: string): string {
  switch (type) {
    case 'ARTICLE_WRITE':
      return '文章操作'
    case 'ARTICLE_SEARCH':
      return '搜索文章'
    case 'NAVIGATE':
      return '页面跳转'
    case 'ARTICLE_QUERY':
      return '读取文章'
    case 'ARTICLE_ANALYSIS':
      return '分析文章'
    case 'WORKFLOW_HANDOFF':
      return '启动流程'
    default:
      return type
  }
}

/** 任务清单始终显示原始目标；执行结果由消息正文承载，避免完成后重复整段正文。 */
function taskResultText(task: TaskStepEvent): string {
  const goal = (task.goalEvidence ?? []).map((item) => item.trim()).filter(Boolean).join('；')
  return goal || taskTypeLabel(task.type)
}

/**
 * V3.13：计划事件按**占位消息索引**归并。
 *
 * 本事件早于 STOP——那时最终消息尚未落库，占位消息还没有 agentRunId，
 * 按 agentRunId 匹配会把实时计划直接丢掉（症状：刷新后才看得到计划）。
 */
function applyAgentPlanEvent(event: AgentPlanEvent, messageIndex: number) {
  const current = messages.value[messageIndex]
  if (!current || current.role !== 'ai') return
  const plan = (event.plan ?? []).filter((item) => typeof item === 'string' && item.trim())
  if (!plan.length) return
  messages.value[messageIndex] = { ...current, plan }
}

/**
 * V4.x：过程步骤——**防抖 + 累积**。
 *
 * 防抖：检索常为百毫秒级，不防抖会闪一下，比不显示更糟。延迟 250ms 再入列，
 * 若首个正文在此之前到达就直接不渲染（见 clearChatStatus）。
 *
 * 累积：每条状态成为一步，前一步自动算完成（渲染时最后一条画沙漏、其余画打勾）——
 * 用户看到的是完整过程链条，而不是"新的覆盖旧的"。
 *
 * 文案由服务端给（前端不拼），事件可能早于 PARAM 到达——但从不依赖消息 ID，
 * 只作用于「当前正在流式的那条 AI 占位消息」（渲染处按 isMessageStreaming 判定）。
 */
function onChatStatusEvent(event: ChatStatusEvent) {
  const text = (event?.message ?? '').trim()
  if (!text) return

  if (chatStatusShowTimer) clearTimeout(chatStatusShowTimer)
  chatStatusShowTimer = setTimeout(() => {
    chatStatusShowTimer = null
    // 同一条消息里重复文案不重复入列（服务端重发/重试时不刷屏）
    if (liveChatSteps.value[liveChatSteps.value.length - 1] !== text) {
      liveChatSteps.value.push(text)
    }
    if (!chatStatusShownAt) chatStatusShownAt = Date.now()
  }, CHAT_STATUS_DELAY_MS)
}

/**
 * V4.5：清理过程状态（首个 DATA / STOP / error / abort 都调）。
 *
 * 已展示的情况下保证**最小显示时长**——否则首字秒到时状态会一闪而过，比不显示更晃眼。
 */
function clearChatStatus() {
  if (chatStatusShowTimer) {
    clearTimeout(chatStatusShowTimer)
    chatStatusShowTimer = null
  }
  // V4.x：不再清空步骤——流式结束后由 flushProcessStepsToMessage 落进消息（可展开回看）。
  // 这里只取消「还没显示出来的」防抖计时器：首字秒到时不该再冒出步骤（防抖语义保留）。
  chatStatusShownAt = 0
}

/** V2.3：历史消息按 agentRunId 补拉思考步骤与计划（刷新/切会话恢复思考面板） */
async function hydrateThinkingSteps() {
  // 注意：不排除有 workflow 的消息——confirm 后思考步骤与 Workflow 卡共存
  const ids = Array.from(
    new Set(
      messages.value
        .filter(
          (msg) =>
            msg.role === 'ai' &&
            msg.agentRunId &&
            // 5b：taskSteps 也是这条消息的一块独立数据。原先只判思考步骤与计划，
            // 于是"外层任务步骤没被实时接住"的消息不会在这里补回来——
            // 表现就是任务列表少一行（handoff 那步），直到整页刷新才出现
            (!msg.thinkingSteps || !msg.plan || !msg.taskSteps),
        )
        .map((msg) => msg.agentRunId as string),
    ),
  )
  if (!ids.length) return

  // V3.13：steps（step 级）与 plan（run 级）分开拉，互不阻塞——
  // 计划恢复失败不能让 steps 被视为不存在，steps 恢复失败也不能覆盖已恢复的 plan
  const stepResults = await Promise.allSettled(ids.map((id) => aiApi.getAgentRunSteps(id)))
  const detailResults = await Promise.allSettled(ids.map((id) => aiApi.getAgentRunDetail(id)))

  /*
   * 第四步：子 run 的内部明细**不在外层 run 的 steps 里**——它属于子 run 自己。
   * 所以先按任务步骤透出的 childRunId 去重收集，再一次性并发补拉，
   * 最后在下面的同步映射里查表挂载（不在 forEach 里 await，避免打乱与 ids 的对应关系）。
   * 补拉失败只影响那一行的展开明细，不影响任务步骤本身的恢复。
   */
  const childRunIds = Array.from(new Set(
    stepResults.flatMap((r) =>
      r.status === 'fulfilled'
        ? ((r.value?.data ?? []) as AgentStepHistoryItem[]).map((s) => s.childRunId)
        : [],
    ).filter((id): id is string => !!id),
  ))
  const childStepResults = await Promise.allSettled(
    childRunIds.map((id) => aiApi.getAgentRunSteps(id)),
  )
  const innerByChildRun = new Map<string, AgentStepView[]>()
  childRunIds.forEach((childId, i) => {
    const res = childStepResults[i]
    if (res.status !== 'fulfilled') return
    const items = (res.value?.data ?? []) as AgentStepHistoryItem[]
    innerByChildRun.set(childId, items
      .map((s) => ({
        stepNo: s.stepNo,
        actionType: s.actionType,
        status: s.status as AgentStepView['status'],
        message: s.message ?? s.summary ?? s.actionType,
        thoughtSummary: s.thoughtSummary ?? null,
      }))
      .sort((a, b) => a.stepNo - b.stepNo))
  })

  ids.forEach((id, i) => {
    const steps = stepResults[i].status === 'fulfilled' ? stepResults[i].value?.data : null
    const plan = detailResults[i].status === 'fulfilled'
      ? detailResults[i].value?.data?.plan
      : null
    if (!steps?.length && !plan?.length) return

    // 第三刀：按 kind 分流——任务步骤不是思考步骤。
    // 两层同表，若不区分，任务步骤会被当成思考步骤渲染，而它没有思考文案
    // （AgentStepLabelSupport 不认识 TASK_STEP，会返回默认占位），
    // 表现就是"刷新后思考过程变了样"。
    const allSteps = (steps ?? []) as AgentStepHistoryItem[]
    const taskSteps: TaskStepEvent[] = allSteps
      .filter((s) => s.kind === 'TASK')
      .map((s) => ({
        stepId: `step-${s.stepNo}`,
        type: s.type ?? '',
        // 历史接口把 DISPATCHED 归一成了 SUCCESS，这里只能还原到这一层
        state: s.status === 'SUCCESS' ? 'SUCCEEDED' : s.status,
        code: '',
        summary: s.summary ?? s.message ?? '',
        goalEvidence: s.goalEvidence ?? [],
        // 第四步：任务步骤下的内部明细（子 run），补拉失败时为 undefined
        innerSteps: s.childRunId ? innerByChildRun.get(s.childRunId) : undefined,
      }))
    const agentSteps = allSteps.filter((s) => s.kind !== 'TASK')

    const idx = messages.value.findIndex(
      (msg) =>
        msg.agentRunId === id && (!msg.thinkingSteps || !msg.plan || !msg.taskSteps),
    )
    if (idx < 0) return
    const prev = messages.value[idx]
    messages.value[idx] = {
      ...prev,
      thinkingSteps: prev.thinkingSteps ?? (agentSteps.length
        ? agentSteps
            .map((s: AgentStepHistoryItem) => ({
              stepNo: s.stepNo,
              actionType: s.actionType,
              status: s.status as AgentStepView['status'],
              message: s.message ?? s.summary ?? s.actionType,
              thoughtSummary: s.thoughtSummary ?? null,
            }))
            .sort((a, b) => a.stepNo - b.stepNo)
        : undefined),
      taskSteps: prev.taskSteps ?? (taskSteps.length ? taskSteps : undefined),
      plan: prev.plan ?? (plan?.length ? plan : undefined),
    }
  })
}

/** 流式 step 事件：展开日志区并插入临时 RUNNING 日志 */
function applyWorkflowStepEvent(event: WorkflowStepEvent) {
  const workflowId = event.workflowRunId
  if (!workflowId) return

  workflowStepLogsExpanded.value[workflowId] = true
  workflowStepLogsLoading.value[workflowId] = false

  const runningLog: AiWorkflowStepLog = {
    id: `stream-${workflowId}-${event.step ?? event.action ?? 'workflow'}`,
    workflowRunId: workflowId,
    logType: 'STEP', // 实时流都来自 runStep（步骤级）
    stepOrder: 0,
    step: event.step ?? event.action ?? 'WORKFLOW',
    status: event.status || 'RUNNING',
    inputSummary: event.message ?? 'Workflow 正在执行...',
    // 后端在 SUCCESS / FAILED 事件里带了结构化元数据，实时即可显示耗时与 token
    // （RUNNING 事件不带，保持 undefined，前端显示 `—`）
    durationMs: event.durationMs,
    inputTokens: event.inputTokens,
    outputTokens: event.outputTokens,
    startedAt: new Date().toISOString(),
    createdAt: new Date().toISOString(),
  }

  // upsert：同 id 的实时日志更新状态（RUNNING → SUCCESS），不同 id 插到最前
  const oldList = workflowStepLogs.value[workflowId] ?? []
  const index = oldList.findIndex((log) => log.id === runningLog.id)

  if (index >= 0) {
    oldList[index] = runningLog
    workflowStepLogs.value[workflowId] = [...oldList]
  } else {
    workflowStepLogs.value[workflowId] = [runningLog, ...oldList]
  }
}

/** 初始 Workflow 尚未收到最终 STOP 时，先用步骤事件创建临时卡片。 */
function applyInitialWorkflowStepCard(
  event: WorkflowStepEvent,
  messageIndex: number,
) {
  const workflowId = event.workflowRunId
  if (!workflowId || !event.workflowType) return

  const message = messages.value[messageIndex]
  const existing = message?.workflow?.id === workflowId
    ? message.workflow
    : activeWorkflow.value?.id === workflowId
      ? activeWorkflow.value
      : null

  const workflow: AiWorkflowRun = existing
    ? {
        ...existing,
        currentStep: event.step as AiWorkflowRun['currentStep'],
        status: existing.status === 'FAILED' ? existing.status : 'RUNNING',
      }
    : {
        id: workflowId,
        workflowType: event.workflowType,
        workflowVersion: '1.0',
        status: 'RUNNING',
        currentStep: event.step as AiWorkflowRun['currentStep'],
        context: {
          workflowVersion: '1.0',
          variables: {},
        },
      }

  activeWorkflow.value = workflow

  if (message?.role === 'ai' && message.id === '') {
    messages.value[messageIndex] = {
      ...message,
      workflow,
    }
  }
}

/**
 * 5b：Workflow 结束后同步它绑定的消息正文和外层任务步骤。
 *
 * 后端在 Workflow 终态时会**自动续跑**外层计划的剩余步骤（`TASK_STEP` 行的状态被改写），
 * 前端不重新拉就会一直停在"等确认"。这里只做展示同步——恢复的正确性完全由后端保证，
 * 刷新失败不影响续跑（用户下次刷新页面照样能看到正确状态）。
 */
async function refreshBoundTaskResult(workflowId: number | string) {
  // 优先找挂了这张 Workflow 卡的消息；找不到就退到"最后一条带 agentRunId 的 AI 消息"
  // ——handoff 是当前这一轮的请求，它就在末尾（消息落库后 workflow 字段可能已不在内存里）
  let idx = messages.value.findIndex(
    (msg) => msg.role === 'ai' && msg.workflow?.id === workflowId && msg.agentRunId,
  )
  if (idx < 0) {
    for (let i = messages.value.length - 1; i >= 0; i -= 1) {
      if (messages.value[i].role === 'ai' && messages.value[i].agentRunId) {
        idx = i
        break
      }
    }
  }
  if (idx < 0) return
  const agentRunId = messages.value[idx].agentRunId as string
  const sessionId = currentSessionId.value
  try {
    const [stepRes, messageRes] = await Promise.all([
      aiApi.getAgentRunSteps(agentRunId),
      sessionId ? aiApi.getMessages(sessionId) : Promise.resolve(null),
    ])
    const previousTaskSteps = messages.value[idx].taskSteps ?? []
    const taskSteps: TaskStepEvent[] = ((stepRes.data ?? []) as AgentStepHistoryItem[])
      .filter((s) => s.kind === 'TASK')
      .map((s) => {
        // stepId 沿用历史接口的合成规则，与 hydrateThinkingSteps 保持一致
        const stepId = `step-${s.stepNo}`
        return {
          stepId,
          type: s.type ?? '',
          state: s.status === 'SUCCESS' ? 'SUCCEEDED' : s.status,
          code: '',
          summary: s.summary ?? s.message ?? '',
          goalEvidence: s.goalEvidence ?? [],
          // 终态同步只更新外层事实，不应清掉已经补拉出的子 run 展开明细。
          innerSteps: previousTaskSteps.find((task) => task.stepId === stepId)?.innerSteps,
        }
      })

    // 同步期间用户可能切换会话，不能再用旧索引覆盖新会话里的消息。
    const latestIdx = messages.value.findIndex(
      (msg) => msg.role === 'ai' && msg.agentRunId === agentRunId,
    )
    if (latestIdx < 0 || (sessionId && currentSessionId.value !== sessionId)) return

    const persisted = messageRes?.data?.find(
      (msg) => msg.role === 'ai' && msg.agentRunId === agentRunId,
    )
    messages.value[latestIdx] = {
      ...messages.value[latestIdx],
      content: persisted?.content ?? messages.value[latestIdx].content,
      taskSteps: taskSteps.length ? taskSteps : undefined,
    }
  } catch {
    // 展示同步失败不抛：后端已经恢复完了
  }
}

/** 流式结束事件：更新 activeWorkflow / 步骤日志 / editorAction */
async function applyWorkflowStreamResult(data: WorkflowStreamResult) {
  if (!data.workflow) return

  // 结束态（否定反馈取消 / 填充完成）：清空面板和本地 session 缓存，让后续消息走普通聊天
  if (data.workflow.status === 'CANCELLED' || data.workflow.status === 'COMPLETED') {
    // 填充动作随结束态一起返回（approve 填充后直接 COMPLETED）：先跳转填充，再清面板
    if (data.editorAction) {
      await handleEditorAction(data.editorAction)
    }
    // 5b：Workflow 终态后外层任务会被后端续跑，重新拉一次任务步骤让界面跟上
    await refreshBoundTaskResult(data.workflow.id)
    activeWorkflow.value = null
    clearSessionActiveWorkflow(data.workflow.id)
    return
  }

  activeWorkflow.value = data.workflow
  syncWorkflowSnapshotMessage(data.workflow)
  // 5b：这里也要补齐外层任务步骤。handoff 那一步的 TASK_STEP 要等 Workflow 初始链
  // 跑完（几十秒）才发，中途还可能因为流状态变化丢掉；不补的话，用户看到 Workflow 卡
  // 却看不到"优化"那一行任务步骤，只能手动刷新页面
  void refreshBoundTaskResult(data.workflow.id)
  // 流式内容已落库到 snapshot，清理累积的 delta
  delete workflowStreamingContent.value[data.workflow.id]
  delete workflowStreamingOutline.value[data.workflow.id]

  if (data.stepLogs) {
    const workflowId = data.workflow.id
    // 数据库里已有记录的步骤（step#status）——实时版本字段不全，丢弃以免同一步骤显示两条
    const persistedKeys = new Set(
      data.stepLogs.map((log) => `${log.step}#${log.status}`),
    )
    // 只保留数据库里没有对应记录的实时日志（如 create 阶段 run 未入库、不落库的步骤）
    const liveLogs = (workflowStepLogs.value[workflowId] ?? []).filter(
      (log) =>
        log.id.startsWith(`stream-${workflowId}-`) &&
        !persistedKeys.has(`${log.step}#${log.status}`),
    )

    workflowStepLogs.value[workflowId] = [
      ...liveLogs,
      ...data.stepLogs,
    ]
  }

  if (data.editorAction) {
    await handleEditorAction(data.editorAction)
  }
}

/** 展开时刷新步骤日志（仅当该 workflow 的日志区已展开） */
async function refreshWorkflowStepLogs(workflowId: string) {
  if (!workflowStepLogsExpanded.value[workflowId]) return

  const res = await aiApi.getWorkflowStepLogs(workflowId)
  workflowStepLogs.value[workflowId] = res.data ?? []
}

/** 展开/收起步骤日志，展开时懒加载；展开状态持久化，刷新后恢复 */
async function toggleWorkflowStepLogs(workflowId: string) {
  workflowStepLogsExpanded.value[workflowId] = !workflowStepLogsExpanded.value[workflowId]

  if (!workflowStepLogsExpanded.value[workflowId]) {
    localStorage.removeItem(`ai_wf_logs_expanded_${workflowId}`)
    return
  }
  localStorage.setItem(`ai_wf_logs_expanded_${workflowId}`, '1')
  if (workflowStepLogs.value[workflowId]?.length) return
  if (workflowStepLogsLoading.value[workflowId]) return

  workflowStepLogsLoading.value[workflowId] = true
  try {
    const res = await aiApi.getWorkflowStepLogs(workflowId)
    workflowStepLogs.value[workflowId] = res.data ?? []
  } catch (error: any) {
    message.error(error?.message ?? '加载 Workflow 执行详情失败')
  } finally {
    workflowStepLogsLoading.value[workflowId] = false
  }
}

// ============================================================
// 拖拽移动
// ============================================================

const isDragging = ref(false)
const dragStart = ref({ mouseX: 0, mouseY: 0, panelX: 0, panelY: 0 })
const panelOffset = ref({ x: 0, y: 0 })

function onHeaderMouseDown(e: MouseEvent) {
  // 不拦截按钮点击
  const target = e.target as HTMLElement
  if (target.closest('button')) return

  isDragging.value = true
  dragStart.value = {
    mouseX: e.clientX,
    mouseY: e.clientY,
    panelX: panelOffset.value.x,
    panelY: panelOffset.value.y,
  }
  document.addEventListener('mousemove', onMouseMove)
  document.addEventListener('mouseup', onMouseUp)
}

// 面板顶部不得越过 header（header sticky 64px + 安全间距 8px = 72px）
function clampPanelTop(yOffset: number): number {
  // 面板顶部 = 视口高度 - 120(bottom) - 面板高度 + yOffset
  // 约束：面板顶部 >= 72px
  const top = window.innerHeight - 120 - panelH.value + yOffset
  if (top < 72) return yOffset + (72 - top)
  return yOffset
}

function onMouseMove(e: MouseEvent) {
  if (!isDragging.value) return
  const rawY = dragStart.value.panelY + (e.clientY - dragStart.value.mouseY)
  panelOffset.value = {
    x: dragStart.value.panelX + (e.clientX - dragStart.value.mouseX),
    y: clampPanelTop(rawY),
  }
}

function onMouseUp() {
  isDragging.value = false
  document.removeEventListener('mousemove', onMouseMove)
  document.removeEventListener('mouseup', onMouseUp)
}

const panelStyle = computed(() => {
  const tx = panelOffset.value.x + resizeOffset.value.x
  const ty = panelOffset.value.y + resizeOffset.value.y
  const style: Record<string, string> = {}
  if (tx !== 0 || ty !== 0) style.transform = `translate(${tx}px, ${ty}px)`
  if (panelW.value !== 500) style.width = `${panelW.value}px`
  if (panelH.value !== 560) style.height = `${panelH.value}px`
  return Object.keys(style).length > 0 ? style : undefined
})

// ============================================================
// 边缘缩放
// ============================================================

type ResizeDir = 'n' | 's' | 'e' | 'w' | 'ne' | 'nw' | 'se' | 'sw'

const resizing = ref<ResizeDir | null>(null)
const panelW = ref(500)
const panelH = ref(560)
const resizeOffset = ref({ x: 0, y: 0 })
let resizeStart = { mx: 0, my: 0, w: 0, h: 0, ox: 0, oy: 0 }

const MIN_W = 360
const MIN_H = 400

function maxW() { return window.innerWidth - 48 }
function maxH() { return window.innerHeight - 140 }

function onResizeStart(e: MouseEvent, dir: ResizeDir) {
  e.preventDefault()
  e.stopPropagation()
  resizing.value = dir
  resizeStart = {
    mx: e.clientX, my: e.clientY,
    w: panelW.value, h: panelH.value,
    ox: resizeOffset.value.x, oy: resizeOffset.value.y,
  }
  document.addEventListener('mousemove', onResizeMove)
  document.addEventListener('mouseup', onResizeEnd)
}

function onResizeMove(e: MouseEvent) {
  if (!resizing.value) return
  const d = resizing.value
  const dx = e.clientX - resizeStart.mx
  const dy = e.clientY - resizeStart.my

  let newW = resizeStart.w, newH = resizeStart.h
  let ox = resizeStart.ox, oy = resizeStart.oy

  // 东/西：宽度变化以固定对边
  if (d.includes('e')) {
    // 拖右边 → 宽度 = 原始 + dx，左边缘不动 → 面板跟随右移
    const w = resizeStart.w + dx
    if (w >= MIN_W && w <= maxW()) { newW = w; ox = resizeStart.ox + dx }
  }
  if (d.includes('w')) {
    // 拖左边 → 宽度 = 原始 - dx，右边缘不动 → 面板跟随左移
    const w = resizeStart.w - dx
    if (w >= MIN_W && w <= maxW()) { newW = w; ox = resizeStart.ox + dx }
  }

  // 南/北：高度变化以固定对边
  if (d.includes('s')) {
    // 拖下边 → 高度 = 原始 + dy，上边缘不动 → 面板跟随下移
    const h = resizeStart.h + dy
    if (h >= MIN_H && h <= maxH()) { newH = h; oy = resizeStart.oy + dy }
  }
  if (d.includes('n')) {
    // 拖上边 → 高度 = 原始 - dy，下边缘不动 → 面板跟随上移
    // 约束：面板顶部不得越过 header（72px）
    const h = resizeStart.h - dy
    let candidateOy = resizeStart.oy + dy
    // 计算面板顶部位置
    const top = window.innerHeight - 120 - h + candidateOy
    if (top < 72) candidateOy += (72 - top)
    if (h >= MIN_H && h <= maxH()) { newH = h; oy = candidateOy }
  }

  panelW.value = newW
  panelH.value = newH
  resizeOffset.value = { x: ox, y: oy }
}

function onResizeEnd() {
  resizing.value = null
  document.removeEventListener('mousemove', onResizeMove)
  document.removeEventListener('mouseup', onResizeEnd)
}

// ============================================================
// Derived
// ============================================================

const sortedSessions = computed(() =>
  [...sessions.value].sort(
    (a, b) => new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime(),
  ),
)

// 记忆候选
const memoryCandidates = ref<AiMemoryCandidate[]>([])
const loadingMemoryCandidates = ref(false)
const handlingMemoryCandidateId = ref<string | null>(null)

const pendingMemoryCount = computed(() => memoryCandidates.value.length)

const memoryManagerVisible = ref(false)
const memoryTab = ref<'candidates' | 'memories'>('candidates')
const formalMemories = ref<AiMemory[]>([])
const loadingFormalMemories = ref(false)
const handlingFormalMemoryId = ref<string | null>(null)

// 行内编辑状态
const editingCandidateId = ref<string | null>(null)
const editingCandidateContent = ref('')
const editingMemoryId = ref<string | null>(null)
const editingMemoryContent = ref('')

const showWelcome = computed(() => messages.value.length === 0)

// ============================================================
// Scroll
// ============================================================

function isNearBottom() {
  const el = messageListRef.value
  if (!el) return true
  return el.scrollHeight - el.scrollTop - el.clientHeight <= AUTO_SCROLL_THRESHOLD
}

function handleMessageListScroll() {
  shouldAutoScroll.value = isNearBottom()
}

async function scrollToBottom(force = false) {
  await nextTick()
  const el = messageListRef.value
  if (!el) return

  if (force || shouldAutoScroll.value || isNearBottom()) {
    el.scrollTop = el.scrollHeight
    shouldAutoScroll.value = true
  }
}

function isMessageStreaming(index: number, role: string): boolean {
  return sending.value && role === 'ai' && index === messages.value.length - 1
}

function formatMessageTime(iso: string): string {
  if (!iso) return ''
  return new Date(iso).toLocaleString('zh-CN')
}

// ============================================================
// Actions
// ============================================================

async function loadSessions() {
  try {
    const res = await aiApi.getSessions(1, 50)
    if (res.data) {
      sessions.value = res.data.list
    }
  } catch {
    // 静默失败，session 列表为空
  }
}

/** 历史消息只带 workflowRunId，补拉 workflow 数据挂回消息，恢复工作流卡片（含执行详情） */
async function hydrateWorkflowMessages() {
  const ids = Array.from(
    new Set(
      messages.value
        .filter((msg) => msg.role === 'ai' && msg.workflowRunId && !msg.workflow)
        .map((msg) => msg.workflowRunId as string),
    ),
  )
  if (!ids.length) return

  const runs = await Promise.allSettled(ids.map((id) => aiApi.getWorkflowRun(id)))
  ids.forEach((id, i) => {
    const run = runs[i].status === 'fulfilled' ? runs[i].value?.data : null
    if (!run) return

    const idx = messages.value.findIndex((msg) => msg.workflowRunId === id && !msg.workflow)
    if (idx >= 0) {
      messages.value[idx] = {
        ...messages.value[idx],
        workflow: run,
        content: messages.value[idx].content || buildWorkflowSnapshotContent(run),
      }
    }

    // 恢复步骤日志展开状态（刷新前展开过的自动重新展开并拉取）
    if (localStorage.getItem(`ai_wf_logs_expanded_${id}`) === '1') {
      workflowStepLogsExpanded.value[id] = true
      void refreshWorkflowStepLogs(id)
    }
  })
}

/** V2.1：历史消息只带 agentRunId，补拉建议快照恢复建议卡（仅 WAITING_WORKFLOW_CONFIRM 渲染） */
async function hydrateSuggestionMessages() {
  const ids = Array.from(
    new Set(
      messages.value
        .filter(
          (msg) =>
            msg.role === 'ai' &&
            msg.agentRunId &&
            !msg.workflowSuggestion &&
            !msg.workflow,
        )
        .map((msg) => msg.agentRunId as string),
    ),
  )
  if (!ids.length) return

  const views = await Promise.allSettled(ids.map((id) => aiApi.getWorkflowSuggestion(id)))
  ids.forEach((id, i) => {
    const view = views[i].status === 'fulfilled' ? views[i].value?.data : null
    if (!view?.suggestion || view.status !== 'WAITING_WORKFLOW_CONFIRM') return

    const idx = messages.value.findIndex(
      (msg) => msg.agentRunId === id && !msg.workflowSuggestion && !msg.workflow,
    )
    if (idx >= 0) {
      messages.value[idx] = {
        ...messages.value[idx],
        workflowSuggestion: view.suggestion,
        suggestionState: 'pending',
      }
    }
  })
}

/** V2.4：确认写动作提案 → 后端标题匹配 + 执行 → 卡片消失（执行结果由后端返回） */
// V3.1：写动作卡按内层 actionType 渲染——不能按 done 判断（ADD 的 done 恒 false，会错显成「取消任务完成状态」）
// V3.3：重命名任务（UPDATE_LEARNING_TASK）——done 也恒 false，同样不能靠 done 判断
// V3.4：改文章标题（UPDATE_ARTICLE_TITLE）——文章域动作，done 恒 false、taskTitle 为 null
// V3.7：隐藏/公开文章（HIDE_ARTICLE / PUBLISH_ARTICLE）——方向即动作，无 newTitle
// 2026-09-21：充值（RECHARGE_WALLET）——钱包域，只有 payYuan
function writeActionTypeLabel(w: AgentWriteProposal): string {
  if (w.actionType === 'ADD_LEARNING_TASK') return '追加学习任务'
  if (w.actionType === 'UPDATE_LEARNING_TASK') return '重命名学习任务'
  if (w.actionType === 'UPDATE_ARTICLE_TITLE') return '修改文章标题'
  if (w.actionType === 'HIDE_ARTICLE') return '隐藏文章'
  if (w.actionType === 'PUBLISH_ARTICLE') return '公开文章'
  if (w.actionType === 'RECHARGE_WALLET') return '充值确认'
  return w.done ? '勾选任务为完成' : '取消任务完成状态'
}

/**
 * 充值卡的按钮文案。
 *
 * ⚠️ 它必须是**专属**的。默认的「继续」和所有其他写动作一模一样——
 * 而这张卡点下去动的是钱。金额要显式念出来，用户扫一眼就知道对不对
 * （这也是分类器万一误判时唯一的兜底：弹出「充值 1000 元」比弹出「继续」显眼得多）。
 */
function writeActionConfirmText(w: AgentWriteProposal): string {
  if (w.actionType === 'RECHARGE_WALLET') {
    return w.payYuan ? `确认充值 ¥${w.payYuan}` : '确认充值'
  }
  return '继续'
}
function writeActionReason(w: AgentWriteProposal): string {
  if (w.actionType === 'ADD_LEARNING_TASK') {
    const plan = w.planRef ? `「${w.planRef}」` : ''
    const stage = w.stageTitle ? ` · 阶段「${w.stageTitle}」` : ''
    return `向 计划${plan}${stage} 追加任务「${w.taskTitle}」`
  }
  if (w.actionType === 'UPDATE_LEARNING_TASK') {
    // 显式展示 旧名 → 新名，确认动作可审计（newTitle 兜底旧数据 undefined 不崩）
    const stage = w.stageTitle ? `（阶段：${w.stageTitle}）` : ''
    return `将任务「${w.taskTitle}」重命名为「${w.newTitle ?? '—'}」${stage}`
  }
  if (w.actionType === 'UPDATE_ARTICLE_TITLE') {
    // V3.4：显式展示 旧标题 → 新标题（articleTitle = 提案时刻 DB 权威旧标题，兜底旧数据不崩）
    return `将文章《${w.articleTitle ?? '—'}》标题改为《${w.newTitle ?? '—'}》`
  }
  if (w.actionType === 'HIDE_ARTICLE') {
    // V3.7：文章《X》将被隐藏（articleTitle 兜底旧数据不崩）
    return `将文章《${w.articleTitle ?? '—'}》设为隐藏，其他访客将无法看到`
  }
  if (w.actionType === 'PUBLISH_ARTICLE') {
    // V3.7：文章《X》将被公开
    return `将文章《${w.articleTitle ?? '—'}》公开，恢复为所有人可见`
  }
  if (w.actionType === 'RECHARGE_WALLET') {
    // 2026-09-21 钱包域：显式念出金额。
    // 到账额度**不在这里算**——它是兑换比例派生的，权威数字由后端在执行结果里给（前端会 toast）。
    // 卡片上写死一个数，而配置改过的话就变成了谎话
    return `给钱包充值 ${w.payYuan ?? '—'} 元，确认后立即到账`
  }
  return `任务「${w.taskTitle}」` + (w.stageTitle ? `（阶段：${w.stageTitle}）` : '')
}

async function confirmWriteAction(msg: AiMessage) {
  if (!msg.agentRunId || !msg.writeAction) return
  setWriteActionState(msg, 'processing')
  try {
    const res = await aiApi.confirmWriteAction(msg.agentRunId)
    message.success(res.data ?? '已执行')
    const idx = messages.value.findIndex((m) => m.id === msg.id)
    if (idx >= 0) {
      messages.value[idx] = {
        ...messages.value[idx],
        writeAction: undefined,
        writeActionState: undefined,
      }
    }
  } catch (e: any) {
    // 「已被处理或已过期」是终态：提案已经没得点了，再置回 pending 只会让用户
    // 反复点一张永远不会成功的卡。'expired' 这个状态一直定义了却从没人设置过，
    // 而它正好描述的就是这种情况（同会话起了新 run 时旧卡会被后端批量作废）
    const alreadyGone = typeof e?.message === 'string'
      && (e.message.includes('已被处理') || e.message.includes('已过期'))
    setWriteActionState(msg, alreadyGone ? 'expired' : 'pending')
    message.error(e?.message ?? '执行失败，请重试')
  }
}

/** V2.4：取消写动作提案（不执行任何修改） */
async function cancelWriteAction(msg: AiMessage) {
  if (!msg.agentRunId) return
  setWriteActionState(msg, 'processing')
  try {
    await aiApi.cancelWriteAction(msg.agentRunId)
    setWriteActionState(msg, 'cancelled')
  } catch (e: any) {
    setWriteActionState(msg, 'pending')
    message.error(e?.message ?? '取消失败，请重试')
  }
}

function setWriteActionState(msg: AiMessage, state: AiMessage['writeActionState']) {
  const idx = messages.value.findIndex((m) => m.id === msg.id)
  if (idx >= 0) {
    messages.value[idx] = { ...messages.value[idx], writeActionState: state }
  }
}

/** V2.4：历史消息按 agentRunId 补拉写动作提案（刷新恢复写动作卡） */
async function hydrateWriteActions() {
  const ids = Array.from(
    new Set(
      messages.value
        .filter(
          (msg) =>
            msg.role === 'ai' &&
            msg.agentRunId &&
            !msg.writeAction &&
            !msg.workflow,
        )
        .map((msg) => msg.agentRunId as string),
    ),
  )
  if (!ids.length) return

  const views = await Promise.allSettled(ids.map((id) => aiApi.getWriteAction(id)))
  ids.forEach((id, i) => {
    const view = views[i].status === 'fulfilled' ? views[i].value?.data : null
    if (!view?.proposal || view.status !== 'WAITING_WRITE_CONFIRM') return
    const idx = messages.value.findIndex(
      (msg) => msg.agentRunId === id && !msg.writeAction && !msg.workflow,
    )
    if (idx >= 0) {
      messages.value[idx] = {
        ...messages.value[idx],
        writeAction: view.proposal,
        writeActionState: 'pending',
      }
    }
  })
}

/** V2.1：确认 Agent 建议 → 后端启动学习类 Workflow → 返回的 Workflow 快照直接挂回消息渲染 Workflow 卡 */
async function confirmSuggestion(msg: AiMessage) {
  if (!msg.agentRunId || !msg.workflowSuggestion) return
  setMessageState(msg, 'processing')

  const key = getWorkflowActionKey(msg.agentRunId, 'CONFIRM_SUGGEST')
  try {
    const res = await aiApi.confirmWorkflowSuggestion(msg.agentRunId, key)
    clearWorkflowActionKey(msg.agentRunId, 'CONFIRM_SUGGEST')
    const workflow = res.data
    const idx = messages.value.findIndex((m) => m.id === msg.id)
    if (idx >= 0) {
      messages.value[idx] = {
        ...messages.value[idx],
        workflow,
        workflowRunId: workflow.id,
        workflowSuggestion: undefined,
        suggestionState: undefined,
      }
    }
    // 激活底部工作流面板（与正常 Workflow 创建一致）
    activeWorkflow.value = workflow
    message.success('已启动 ' + workflowLabel(workflow.workflowType))
  } catch (e: any) {
    setMessageState(msg, 'pending')
    message.error(e?.message ?? '启动失败，请重试')
  }
}

/** V2.1：取消 Agent 建议（不启动 Workflow） */
async function cancelSuggestion(msg: AiMessage) {
  if (!msg.agentRunId) return
  setMessageState(msg, 'processing')
  try {
    await aiApi.cancelWorkflowSuggestion(msg.agentRunId)
    setMessageState(msg, 'cancelled')
  } catch (e: any) {
    setMessageState(msg, 'pending')
    message.error(e?.message ?? '取消失败，请重试')
  }
}

function setMessageState(msg: AiMessage, state: AiMessage['suggestionState']) {
  const idx = messages.value.findIndex((m) => m.id === msg.id)
  if (idx >= 0) {
    messages.value[idx] = { ...messages.value[idx], suggestionState: state }
  }
}

function workflowLabel(type: WorkflowType): string {
  return type === 'OPTIMIZE_ARTICLE' ? '文章优化 Workflow' : type === 'LEARNING_PLAN' ? '学习规划 Workflow' : type === 'LEARNING_PROGRESS' ? '学习进度 Workflow' : type === 'LEARNING_ASSIST' ? '难点攻坚 Workflow' : '文章创作 Workflow'
}

// V3.0：思考中刷新补拉。Agent/流式回复尚未落库时（最后一条是用户消息），
// 立即渲染「思考中」占位 + 每 3 秒静默重拉（最多 6 次），落库后自然替换，
// 避免「刷新后 AI 回复和思考面板丢失」以及「突然整条跳出」的突兀感。
let pendingReplyReloadTimer: number | null = null
let pendingReplyReloadAttempts = 0

async function loadMessages(sid: string, silent = false) {
  if (!silent) loadingMessages.value = true
  try {
    const res = await aiApi.getMessages(sid)
    // 从 localStorage 恢复 references（后端未持久化，刷新会丢失）
    messages.value = (res.data ?? []).map(msg => {
      if (msg.role === 'ai' && (!msg.references || msg.references.length === 0) && msg.id) {
        try {
          const cached = localStorage.getItem(`ai_ref_${msg.id}`)
          if (cached) return { ...msg, references: JSON.parse(cached) }
        } catch { /* ignore parse errors */ }
      }
      return msg
    })
    // 历史 workflow 卡片：后端消息不带 workflow 对象，按 workflowRunId 补拉
    await hydrateWorkflowMessages()
    // V2.1：历史 Agent 建议卡：按 agentRunId 补拉建议快照
    await hydrateSuggestionMessages()
    // V2.3：历史 Agent 思考步骤：按 agentRunId 补拉（刷新恢复思考面板）
    await hydrateThinkingSteps()
    // V2.4：历史 Agent 写动作提案：按 agentRunId 补拉（刷新恢复写动作卡）
    await hydrateWriteActions()
    await scrollToBottom(true)
  } catch {
    message.error('加载消息失败')
  } finally {
    loadingMessages.value = false
    schedulePendingReplyReload(sid)
  }
}

/** V3.0：最后一条是用户消息且无 AI 回复（可能是流式/Agent 落库前刷新）→ 占位 + 轮询补拉 */
function schedulePendingReplyReload(sid: string) {
  const last = messages.value[messages.value.length - 1]
  if (!last || last.role !== 'user') return
  if (pendingReplyReloadAttempts >= 6) return
  pendingReplyReloadAttempts++
  // 确保有「思考中」占位（每次整体替换后重新补）
  if (messages.value[messages.value.length - 1]?.role === 'user') {
    messages.value.push({
      id: '',
      sessionId: sid,
      role: 'ai',
      content: '🤔 正在思考中…',
      createdAt: new Date().toISOString(),
    })
  }
  if (pendingReplyReloadTimer) clearTimeout(pendingReplyReloadTimer)
  pendingReplyReloadTimer = window.setTimeout(async () => {
    // 用户已发起新消息 → 放弃补拉（避免整体替换冲掉正在流式的临时消息）
    if (sending.value) return
    await loadMessages(sid, true)
  }, 3000)
}

/** 刷新/切会话后恢复 active workflow：从 session 的 activeWorkflowRunId 拉取并挂回消息 */
async function restoreActiveWorkflow(session?: AiSession | null) {
  const workflowId = session?.activeWorkflowRunId

  if (!workflowId) {
    activeWorkflow.value = null
    return
  }

  try {
    const res = await aiApi.getWorkflowRun(workflowId)
    if (!res.data) {
      activeWorkflow.value = null
      return
    }

    // 结束态的 workflow 不该恢复为 active（面板无对应操作区），只挂回消息卡片。
    //
    // FAILED 必须算终止态：漏了它，失败的 Workflow 会被一直挂在活动面板上
    // （存量 bug；后端 AI 长任务准入与启动恢复都会批量产生 FAILED，会放大它）。
    //
    // PAUSED 暂不并入：它语义上是「可恢复」而不是终止，且后端当前没有任何写入点。
    // 将来实现暂停/恢复时要连同它的面板操作区一起设计，不能在这里想当然地排除。
    if (
      res.data.status === 'COMPLETED' ||
      res.data.status === 'CANCELLED' ||
      res.data.status === 'FAILED'
    ) {
      activeWorkflow.value = null
      attachWorkflowToLatestAiMessage(res.data)
      return
    }

    activeWorkflow.value = res.data
    attachWorkflowToLatestAiMessage(res.data)

    if (workflowStepLogsExpanded.value[res.data.id]) {
      await refreshWorkflowStepLogs(res.data.id)
    }
  } catch {
    activeWorkflow.value = null
  }
}

/**
 * 按 runId 把**权威** Workflow 快照应用到面板与消息卡片（5b）。
 *
 * 为什么需要它：`restoreActiveWorkflow` 那条路只在「刷新 / 切会话」时走，id 来自会话绑定；
 * 而 5b 的 handoff **走的是聊天流**——它没有独立 Workflow SSE 那条
 * 「STOP 带完整快照 + 步骤日志」的路径，前端手上只有 `WORKFLOW_STEP` 事件里的几个字段：
 * 状态恒为 RUNNING（于是卡片标题显示成"Workflow"而不是"等待确认优化方案"）、
 * 步骤缺「第 N 步」序号、耗时 / token 也是空的。
 * 表现就是「卡片在，但内容和刷新后不是一回事」。
 *
 * 只在**流结束时**调一次，不是每个步骤事件都拉：一次 handoff 有六到八个步骤，
 * 逐个补拉会打出一串无用请求。
 */
async function applyWorkflowSnapshotById(workflowId?: string | number | null) {
  if (!workflowId) return
  try {
    const res = await aiApi.getWorkflowRun(String(workflowId))
    if (!res.data) return

    // 已结束的 Workflow 不该留在活动面板（那里没有对应操作区），只挂回消息卡片
    if (
      res.data.status === 'COMPLETED' ||
      res.data.status === 'CANCELLED' ||
      res.data.status === 'FAILED'
    ) {
      activeWorkflow.value = null
      attachWorkflowToLatestAiMessage(res.data)
      return
    }

    activeWorkflow.value = res.data
    attachWorkflowToLatestAiMessage(res.data)
    await refreshWorkflowStepLogs(String(res.data.id))
  } catch {
    // 补拉失败不抛：实时那份还在，且正确性本来就由后端负责，用户刷新照样看得到权威版本
  }
}

/**
 * 钱包余额（登录用户才拉）。
 *
 * 余额允许为负，但**负余额有两种含义**，只看数字会把「预扣进行中」误报成欠费：
 * 用户刚发出一条消息就看到负余额，会以为已经欠款，而实际上结算后会退回来。
 */
const wallet = ref<WalletInfo | null>(null)

const walletTone = computed(() => {
  const info = wallet.value
  if (!info || info.balance > 0) {
    return 'normal'
  }
  return info.pendingReserveCount > 0 ? 'pending' : 'empty'
})

/**
 * 余额入口的悬浮说明。
 *
 * 有调用在途时要把「预占」讲清楚：卡片上显示的是**加回在途预占**后的额度
 * （见 format.ts 的 displayCredit），而账单里那一刻可能显示得更少——
 * 不说清，用户会以为两个数字对不上。
 */
const walletTitle = computed(() => {
  const info = wallet.value
  if (!info) {
    return '查看我的钱包'
  }
  if (info.pendingReserveCount > 0) {
    return `有 ${info.pendingReserveCount} 次 AI 调用进行中，额度已临时占用，结束后按实际用量结算`
  }
  return info.balance > 0 ? '查看我的钱包' : '额度不足，点击充值'
})

async function loadWallet() {
  try {
    const res = await walletApi.getWallet()
    wallet.value = res.data
  } catch {
    // 余额是锦上添花：拿不到就不显示，不影响聊天
    wallet.value = null
  }
}

function goWallet() {
  visible.value = false
  router.push('/me/wallet')
}

async function toggle() {
  if (!visible.value) {
    visible.value = true
    if (isGuest.value) {
      // 游客：从 sessionStorage 恢复消息
      messages.value = loadGuestMessages()
      guestUsedCount.value = loadGuestUsedCount()
    } else {
      await loadSessions()
      // 不 await：余额只是展示，不该拖慢面板打开
      loadWallet()
      // 刚点过"创建会话"（pendingNewSession）→ 保持欢迎页；否则自动接回最近会话
      if (!pendingNewSession && sortedSessions.value.length > 0) {
        switchSession(sortedSessions.value[0].id)
      }
    }
  } else {
    visible.value = false
  }
}

function toggleFullscreen() {
  isFullscreen.value = !isFullscreen.value
  // 切换全屏时重置拖拽和缩放
  panelOffset.value = { x: 0, y: 0 }
  resizeOffset.value = { x: 0, y: 0 }
  panelW.value = 500
  panelH.value = 560
}

function openHistory() {
  viewingHistory.value = true
}

function closeHistory() {
  viewingHistory.value = false
}

async function switchSession(sid: string) {
  detachAllStreams()
  pendingNewSession = false
  currentSessionId.value = sid
  viewingHistory.value = false
  resetCompressionHint()
  await loadMessages(sid)
  await syncCompressionHint(sid)

  const session = sessions.value.find((item) => item.id === sid)
  await restoreActiveWorkflow(session)
}

/**
 * 用户显式点了"创建会话"后到下次发送/切换会话前，面板收起再打开保持欢迎页，
 * 不被 toggle 的"自动恢复最近会话"拉回旧会话（V3.8 实测 2026-09-07：
 * 创建会话 → 收面板 → 再打开 → 被拉回最近会话，用户以为的新会话实际是旧的）。
 */
let pendingNewSession = false

/** 创建新会话：游客清空临时消息，登录用户重置为欢迎页 */
function createSession() {
  detachAllStreams()
  // 换一个新的临时 key：让这个新会话与上一个新会话的流互不干扰
  pendingSessionKey.value = `${PENDING_SESSION_KEY}${++pendingKeySeq}`
  if (isGuest.value) {
    clearGuestData()
    messages.value = []
    guestUsedCount.value = 0
    activeWorkflow.value = null
    return
  }
  pendingNewSession = true
  currentSessionId.value = null
  messages.value = []
  activeWorkflow.value = null
  workflowRejectEditing.value = false
  workflowFeedback.value = ''
  viewingHistory.value = false
  input.value = ''
}

async function deleteSession(sid: string) {
  try {
    await aiApi.deleteSession(sid)
    sessions.value = sessions.value.filter((s) => s.id !== sid)
    message.success('已删除会话')
    if (currentSessionId.value === sid) {
      currentSessionId.value = null
      messages.value = []
      resetCompressionHint()
    }
  } catch {
    message.error('删除失败')
  }
}

// 导航路由映射（AI 导航 tool 调用后触发）
const NAV_ROUTES: Record<string, string> = {
  home: '/',
  profile: '/me',
  editor: '/editor',
  drafts: '/drafts',
  hotRank: '/rank/hot',
}

async function handleNavigate(navigate?: { target: string; param?: string }) {
  if (!navigate) return
  const { target, param } = navigate

  let path = ''
  if (target === 'article' && param) {
    path = `/articles/${param}`
  } else if (target === 'userProfile' && param) {
    path = `/users/${param}`
  } else if (NAV_ROUTES[target]) {
    path = NAV_ROUTES[target]
  }

  if (!path) {
    message.warning('AI 已识别跳转意图，但缺少目标参数')
    return
  }

  if (router.currentRoute.value.path === path) {
    message.info('已经在目标页面了')
    return
  }

  try {
    const failure = await router.push(path)
    if (failure) {
      message.warning('跳转失败，请检查是否需要登录')
    }
  } catch {
    message.warning('跳转失败，请检查是否需要登录')
  }
}

async function openReferenceArticle(reference: ArticleRagReference) {
  if (!reference.articleId) {
    message.warning('缺少文章 ID，无法打开来源')
    return
  }

  const path = `/articles/${reference.articleId}`

  if (router.currentRoute.value.path === path) {
    message.info('已经在这篇文章了')
    return
  }

  try {
    const failure = await router.push(path)
    if (failure) {
      message.warning('打开来源文章失败')
    }
  } catch {
    message.warning('打开来源文章失败')
  }
}

async function handleEditorAction(action: EditorAction) {
  const routeName = String(router.currentRoute.value.name ?? '')
  const inEditor = routeName === 'editor-new' || routeName === 'editor-edit'

  if (action.type === 'fillArticle') {
    const targetArticleId = action.articleId ? String(action.articleId) : ''

    if (targetArticleId) {
      const alreadyEditingTarget =
        routeName === 'editor-edit' &&
        String(router.currentRoute.value.params.id ?? '') === targetArticleId

      if (!alreadyEditingTarget) {
        await router.push({ name: 'editor-edit', params: { id: targetArticleId } })
        await nextTick()
      }
    } else if (!inEditor) {
      await router.push('/editor')
      await nextTick()
    }

    emitAiEditorAction(action)
    message.success(targetArticleId ? '已填入原文章编辑器' : '已填入编辑器')
    return
  }

  if (action.type === 'saveDraft' || action.type === 'publish') {
    if (!inEditor) {
      message.warning('请先进入写文章页面，再让 AI 保存或发布')
      return
    }

    emitAiEditorAction(action)
  }
}

async function handleArticleAction(action: ArticleAction) {
  if (router.currentRoute.value.name !== 'article-detail') {
    message.warning('请先打开具体文章，再让 AI 点赞')
    return
  }

  emitAiArticleAction(action)
}

function extractLegacyNavigate(content: string): { cleanContent: string; navigate?: { target: string; param?: string } } {
  const match = content.match(/\[BLOGNAV:([a-zA-Z]+)(?::(\d+))?\]/)
  if (!match) {
    return { cleanContent: content }
  }

  const legacyTargetMap: Record<string, string> = {
    goToHome: 'home',
    goToProfile: 'profile',
    goToEditor: 'editor',
    goToDrafts: 'drafts',
    goToHotRank: 'hotRank',
    goToArticle: 'article',
    goToUserProfile: 'userProfile',
  }

  const rawTarget = match[1]
  const target = legacyTargetMap[rawTarget] ?? rawTarget
  return {
    cleanContent: content.replace(match[0], '').trim(),
    navigate: {
      target,
      param: match[2],
    },
  }
}

async function send(text?: string, skipUserMessage = false) {
  const content = (text ?? input.value).trim()
  if (!content || sending.value) return

  // 游客次数检查
  if (isGuest.value && loadGuestUsedCount() >= GUEST_MAX) {
    message.warning('游客最多体验 3 次，登录后可继续使用')
    return
  }

  if (!skipUserMessage) {
    input.value = ''

    // 先在前端展示用户消息
    const tempUserMsg: AiMessage = {
      id: '',
      sessionId: currentSessionId.value ?? '',
      role: 'user',
      content,
      createdAt: new Date().toISOString(),
    }
    messages.value.push(tempUserMsg)
  }

  await scrollToBottom(true)

  // 会话级流状态：记下本次流属于哪个会话，并开启它的 controller
  const streamKey = currentStreamKey()
  const controller = startStream(streamKey)

  /** 流所属的会话是否仍在显示（判据见 detachedStreamKeys 的注释） */
  const uiAttached = () => streamAttached(streamKey)

  // 空 AI 占位气泡（后续 onData 逐 chunk 填充）
  const aiPlaceholder: AiMessage = {
    id: '',
    sessionId: '',
    role: 'ai',
    content: '',
    createdAt: new Date().toISOString(),
  }
  const aiPlaceholderIndex = messages.value.length
  messages.value.push(aiPlaceholder)

  // 分类器返回首个 SSE 事件前，先由前端给出本地等待反馈，避免数秒空白。
  clearChatStatus()
  liveChatSteps.value = []
  onChatStatusEvent({
    statusType: 'LOCAL_PENDING',
    retrievalMode: 'LOCAL',
    message: '正在理解需求',
  })

  // 后端结果按请求发生时的文章上下文归属，不能等 STOP 时再读取当前路由。
  const requestPageContext = buildPageContext()

  // V3.10：新一轮流式开启自动模式——若出现 Agent 思考步骤则自动展开，
  // 正文开始输出后自动收起（见 onAgentStep / onData）
  thinkingAutoMode.value = true
  // 普通聊天是真流式，不额外延迟；收到 TASK_PLAN 后才开启编排回复的显示节奏。
  let taskReplyPacing = false

  aiApi.streamChat(
    isGuest.value ? null : currentSessionId.value,
    content,
    requestPageContext,
    {
      onParam(_session, userMessage) {
        if (!uiAttached()) return
        // 用后端返回的正式数据替换临时用户消息
        let idx = -1
        for (let i = messages.value.length - 1; i >= 0; i--) {
          if (messages.value[i].role === 'user' && messages.value[i].id === '') {
            idx = i
            break
          }
        }
        if (idx >= 0) messages.value[idx] = userMessage
      },
      async onData(chunk) {
        if (!uiAttached()) return
        // V4.5：正文到达 → 清掉过程状态（含最小显示时长保护）
        clearChatStatus()
        const streamingMessage = messages.value[aiPlaceholderIndex]
        if (streamingMessage && liveChatSteps.value.length) {
          messages.value[aiPlaceholderIndex] = completeLiveProcessSteps(
            streamingMessage,
            liveChatSteps.value,
          )
          liveChatSteps.value = []
        }
        // V3.10：正文开始输出 → 自动收起思考面板（本次流式后用户可手动再展开）；
        // 仅首个 chunk 生效（关闭自动模式后不再重复操作）
        if (thinkingAutoMode.value) {
          thinkingStepsExpanded.value = false
          thinkingAutoMode.value = false
        }
        // 逐 chunk 追加到 AI 气泡
        const currentAiMessage = messages.value[aiPlaceholderIndex]
        if (currentAiMessage) {
          messages.value[aiPlaceholderIndex] = {
            ...currentAiMessage,
            content: currentAiMessage.content + chunk,
          }
        }
        await scrollToBottom()
        if (taskReplyPacing) await sleep(TASK_REPLY_CHUNK_DELAY_MS)
      },
      async onWorkflowStep(event) {
        if (!uiAttached()) return
        applyWorkflowStepEvent(event)
        applyInitialWorkflowStepCard(event, aiPlaceholderIndex)
        pushWorkflowStepToProcess(event)
        await scrollToBottom()
      },
      async onWorkflowContentDelta(event) {
        if (!uiAttached()) return
        applyWorkflowContentDelta(event)
        await scrollToBottom()
      },
      async onAgentPlan(event) {
        if (!uiAttached()) return
        // V3.13：计划恒在首个 AGENT_STEP 之前到达，同样按占位消息索引归并
        applyAgentPlanEvent(event, aiPlaceholderIndex)
      },
      async onChatStatus(event) {
        if (!uiAttached()) return
        // V4.5：聊天 / QA 过程状态（可能早于 PARAM 到达——不依赖消息 ID）
        onChatStatusEvent(event)
      },
      async onTaskPlan(event) {
        if (!uiAttached()) return
        taskReplyPacing = true
        // 第三刀：外层任务计划——先把"要做哪几件事"渲染出来，而不是等结果回来才知道
        applyTaskPlanEvent(event, aiPlaceholderIndex)
      },
      async onTaskStep(event) {
        if (!uiAttached()) return
        // 第三刀：外层任务步骤——每步结束推一次，状态如实（DISPATCHED 不说"已完成"）
        applyTaskStepEvent(event, aiPlaceholderIndex)

        // 任务链路的真实副作用结果不再等 STOP 的旧 actionResults 字段：
        // 写动作已由后端执行，这里只刷新权威状态，不重复发起写请求。
        const output = event.output ?? {}
        const interactionResult = output.interactionResult
        if (event.type === 'ARTICLE_WRITE'
          && interactionResult
          && requestPageContext.articleId) {
          emitAiArticleResults({
            articleId: requestPageContext.articleId,
            results: [interactionResult as any],
          })
        }

        // 导航步骤的执行端是前端。后端只返回 DISPATCHED + 结构化命令，
        // 这里才真正调用路由，不从自然语言 summary 里猜目标。
        if (event.type === 'NAVIGATE'
          && event.state === 'DISPATCHED'
          && typeof output.target === 'string') {
          const param = typeof output.param === 'string'
            ? output.param
            : typeof output.userId === 'string' ? output.userId : undefined
          await handleNavigate({ target: output.target, param })
        }
      },
      async onAgentStep(event) {
        if (!uiAttached()) return
        // 第四步：带 taskStepId 的是子 run 的内部步骤——挂到对应任务步骤下展开，
        // 不进整条消息的思考面板（TASK_STEP 与 AGENT_STEP 是两个层级，不能混）
        if (event.taskStepId) {
          applyInnerAgentStepEvent(event, aiPlaceholderIndex)
          await scrollToBottom()
          return
        }
        applyAgentStepEvent(event, aiPlaceholderIndex)
        // V3.10：思考期间自动展开面板（用户未手动干预时）
        if (thinkingAutoMode.value) thinkingStepsExpanded.value = true
        await scrollToBottom()
      },
      async onStop(session, assistantMessage, navigate, editorAction, articleAction, references, workflow, workflowSuggestion, writeAction, actionResults) {
        endStream(streamKey)

        if (requestPageContext.articleId && actionResults?.length) {
          emitAiArticleResults({
            articleId: requestPageContext.articleId,
            results: actionResults,
          })
        }

        // 用户已切走：这份 messages 已经属于别的会话，UI 一律不动
        // （尤其不能把 currentSessionId 设成这个流所属的会话，那会把用户强行拉回去）；
        // 但会话列表、记忆刷新、压缩检查这些副作用必须照常完成。
        if (!uiAttached()) {
          if (!isGuest.value) {
            const existing = sessions.value.findIndex((s) => s.id === session.id)
            if (existing >= 0) {
              sessions.value[existing] = session
            } else {
              sessions.value.unshift(session)
            }
          }
          scheduleMemoryCandidateRefresh()
          checkCompressionAfterReply(session.id)
          return
        }

        const legacyNavigate = extractLegacyNavigate(assistantMessage.content)
        assistantMessage = {
          ...assistantMessage,
          content: legacyNavigate.cleanContent,
          references: references ?? assistantMessage.references ?? [],
          workflow: workflow ?? (assistantMessage as any).workflow,
          workflowSuggestion: workflowSuggestion ?? (assistantMessage as any).workflowSuggestion,
          suggestionState: workflowSuggestion ? 'pending' : undefined,
          writeAction: writeAction ?? (assistantMessage as any).writeAction,
          writeActionState: writeAction ? 'pending' : undefined,
        }
        navigate = navigate ?? legacyNavigate.navigate

        // 后端判定为 Workflow：激活底部面板
        if (workflow) {
          activeWorkflow.value = workflow
        }

        // 5b：这一轮跑的是 handoff 时，面板里的卡是先由 WORKFLOW_STEP 实时建起来的，
        // 字段残缺（状态恒为 RUNNING → 标题只显示"Workflow"；步骤缺序号 / 耗时 / token）。
        // 流结束时补一次权威快照，否则"卡片在，但和刷新后完全是两回事"
        void applyWorkflowSnapshotById(activeWorkflow.value?.id)

        // 用后端返回的完整数据替换 AI 占位（保留实时累计的思考步骤 V2.3）
        let idx = -1
        for (let i = messages.value.length - 1; i >= 0; i--) {
          if (messages.value[i].role === 'ai' && messages.value[i].id === '') {
            idx = i
            break
          }
        }
        if (idx >= 0) {
          messages.value[idx] = {
            ...assistantMessage,
            // 保留实时累计的思考步骤（V2.3）与计划（V3.13）——
            // 否则实时计划只显示到正文落库前，刷新前后会不一致
            thinkingSteps: messages.value[idx].thinkingSteps,
            plan: messages.value[idx].plan,
            taskSteps: messages.value[idx].taskSteps,
            // 5b：占位消息上可能已经挂了 Workflow 卡（handoff 的 WORKFLOW_STEP 实时建的那张）。
            // 后端返回的消息**不带这个字段**（消息表不存 workflow），上面 2652 行的兜底
            // 又只看了 assistantMessage 自己，所以不在这里显式保留的话，
            // 卡片会在流结束的瞬间整张消失——用户得刷新才回来
            //（刷新走 restoreActiveWorkflow，靠会话绑定的 workflow 重新挂上）。
            workflow: messages.value[idx].workflow ?? (assistantMessage as any).workflow,
            // V4.x：过程步骤同理——流式期间累积在 liveChatSteps（事件可能早于消息创建，挂不上消息），
            // 这里落到消息上，完成后仍可展开回看
            processSteps: [
              ...(messages.value[idx].processSteps ?? []),
              ...liveChatSteps.value,
            ],
          }
          liveChatSteps.value = []
        }

        // 持久化 references 到 localStorage，防止刷新丢失（后端未存 references）
        const refs = assistantMessage.references
        if (refs && refs.length > 0 && assistantMessage.id) {
          try { localStorage.setItem(`ai_ref_${assistantMessage.id}`, JSON.stringify(refs)) } catch { /* quota exceeded, ignore */ }
        }

        if (isGuest.value) {
          saveGuestMessages(messages.value)
          const newCount = loadGuestUsedCount() + 1
          saveGuestUsedCount(newCount)
          guestUsedCount.value = newCount
        } else {
          pendingNewSession = false
          currentSessionId.value = session.id
          const existing = sessions.value.findIndex((s) => s.id === session.id)
          if (existing >= 0) {
            sessions.value[existing] = session
          } else {
            sessions.value.unshift(session)
          }
        }

        // V4.5：流结束 → 清掉过程状态（兜底；正常路径在首个 DATA 已清）
        clearChatStatus()
        scrollToBottom()
        handleNavigate(navigate)
        if (editorAction) {
          await handleEditorAction(editorAction)
        }
        if (articleAction) {
          await handleArticleAction(articleAction)
        }

        scheduleMemoryCandidateRefresh()
        // 回复结束后检查后端异步压缩状态，显示"正在自动压缩上下文 / 已自动压缩上下文"
        checkCompressionAfterReply(session.id)
      },
      onError(error) {
        endStream(streamKey)
        // 失败提示与可见性无关：用户切走了也该知道有个任务失败了
        message.error(error?.message ?? '发送失败')
        if (!uiAttached()) return
        // V4.5：出错 → 清掉过程状态（占位气泡也要移除，不能残留「正在检索」）
        clearChatStatus()
        const failedMessage = finalizeFailedStreamMessage(messages.value[aiPlaceholderIndex])
        if (failedMessage) {
          messages.value[aiPlaceholderIndex] = completeLiveProcessSteps(
            failedMessage,
            liveChatSteps.value,
          )
        } else if (messages.value[aiPlaceholderIndex]?.role === 'ai') {
          // 尚未输出任何正文时才删除空占位，不能抹掉已经展示给用户的部分结果。
          messages.value.splice(aiPlaceholderIndex, 1)
        }
        liveChatSteps.value = []
      },
      onAbort() {
        endStream(streamKey)
        if (!uiAttached()) return
        // V4.5：中断 → 清掉过程状态
        clearChatStatus()
        // 保留已流式输出的内容，末尾停止标记
        const aiMsg = messages.value[aiPlaceholderIndex]
        if (aiMsg && aiMsg.role === 'ai') {
          messages.value[aiPlaceholderIndex] = {
            ...aiMsg,
            content: (aiMsg.content || '') + '\n\n*（已停止生成）*',
          }
        }
        if (isGuest.value) {
          saveGuestMessages(messages.value)
          const newCount = loadGuestUsedCount() + 1
          saveGuestUsedCount(newCount)
          guestUsedCount.value = newCount
        }
        scrollToBottom()
      },
    },
    controller.signal,
  )
}

async function copyMessage(msg: AiMessage) {
  try {
    await navigator.clipboard.writeText(msg.content)
    msg.copied = true
    setTimeout(() => {
      msg.copied = false
    }, 1500)
    message.success('已复制')
  } catch {
    message.error('复制失败')
  }
}

async function deleteMessage(index: number) {
  const msg = messages.value[index]
  if (!msg?.id) return

  const removed = messages.value.splice(index, 1)[0]

  if (isGuest.value) {
    saveGuestMessages(messages.value)
    return
  }

  try {
    await aiApi.deleteMessage(msg.sessionId, msg.id)
  } catch (error: any) {
    messages.value.splice(index, 0, removed)
    message.error(error?.response?.data?.message ?? '删除失败')
  }
}

// ============================================================
// Memory candidates
// ============================================================

async function loadMemoryCandidates() {
  if (isGuest.value) {
    memoryCandidates.value = []
    return
  }

  loadingMemoryCandidates.value = true
  try {
    const res = await aiApi.getMemoryCandidates()
    memoryCandidates.value = res.data ?? []
  } catch {
    // 候选记忆不是聊天主流程，失败不打断用户
  } finally {
    loadingMemoryCandidates.value = false
  }
}

function scheduleMemoryCandidateRefresh() {
  if (isGuest.value) return

  window.setTimeout(() => {
    loadMemoryCandidates()
  }, 1500)
}

async function confirmMemoryCandidate(id: string) {
  handlingMemoryCandidateId.value = id
  try {
    // 如果正在编辑该候选，把编辑内容传过去
    const editedContent =
      editingCandidateId.value === id ? editingCandidateContent.value.trim() || undefined : undefined
    await aiApi.confirmMemoryCandidate(id, editedContent)
    editingCandidateId.value = null
    editingCandidateContent.value = ''
    memoryCandidates.value = memoryCandidates.value.filter((item) => item.id !== id)
    // 如果记忆管理弹窗开着，同步刷新长期记忆列表
    if (memoryManagerVisible.value) {
      loadFormalMemories()
    }
    message.success('已保存到长期记忆')
  } catch {
    message.error('保存记忆失败')
  } finally {
    handlingMemoryCandidateId.value = null
  }
}

async function rejectMemoryCandidate(id: string) {
  handlingMemoryCandidateId.value = id
  try {
    await aiApi.rejectMemoryCandidate(id)
    memoryCandidates.value = memoryCandidates.value.filter((item) => item.id !== id)
    message.success('已忽略')
  } catch {
    message.error('忽略失败')
  } finally {
    handlingMemoryCandidateId.value = null
  }
}

function memoryTypeLabel(type: AiMemoryCandidate['memoryType']) {
  if (type === 'PROFILE') return '画像'
  if (type === 'PREFERENCE') return '偏好'
  if (type === 'PROJECT_STATE') return '项目'
  return type
}

function memoryActionLabel(action: AiMemoryCandidate['candidateAction']) {
  if (action === 'CREATE') return '新增'
  if (action === 'UPDATE') return '更新'
  if (action === 'MERGE') return '合并'
  if (action === 'IGNORE') return '忽略'
  return action
}

function confirmMemoryCandidateLabel(action: AiMemoryCandidate['candidateAction']) {
  return action === 'IGNORE' ? '确认忽略' : '保存'
}

async function openMemoryManager() {
  memoryManagerVisible.value = true
  memoryTab.value = pendingMemoryCount.value > 0 ? 'candidates' : 'memories'
  await Promise.all([loadMemoryCandidates(), loadFormalMemories()])
}

async function loadFormalMemories() {
  if (isGuest.value) {
    formalMemories.value = []
    return
  }

  loadingFormalMemories.value = true
  try {
    const res = await aiApi.getMemories()
    formalMemories.value = res.data ?? []
  } catch {
    message.error('加载长期记忆失败')
  } finally {
    loadingFormalMemories.value = false
  }
}

function promptDeleteMemory(id: string) {
  dialog.warning({
    title: '确认删除',
    content: '删除后 AI 不会再使用这条记忆',
    positiveText: '确认删除',
    negativeText: '取消',
    onPositiveClick: () => {
      deleteFormalMemory(id)
    },
  })
}

async function deleteFormalMemory(id: string) {
  handlingFormalMemoryId.value = id
  try {
    await aiApi.deleteMemory(id)
    formalMemories.value = formalMemories.value.filter((item) => item.id !== id)
    message.success('已删除长期记忆')
  } catch {
    message.error('删除长期记忆失败')
  } finally {
    handlingFormalMemoryId.value = null
  }
}

// ============================================================
// 行内编辑
// ============================================================

function startEditCandidate(candidate: AiMemoryCandidate) {
  editingCandidateId.value = candidate.id
  editingCandidateContent.value = candidate.content
}

function cancelEditCandidate() {
  editingCandidateId.value = null
  editingCandidateContent.value = ''
}

function startEditMemory(mem: AiMemory) {
  editingMemoryId.value = mem.id
  editingMemoryContent.value = mem.content
}

function cancelEditMemory() {
  editingMemoryId.value = null
  editingMemoryContent.value = ''
}

async function saveEditMemory(mem: AiMemory) {
  const newContent = editingMemoryContent.value.trim()
  if (!newContent) {
    message.warning('内容不能为空')
    return
  }
  handlingFormalMemoryId.value = mem.id
  try {
    await aiApi.updateMemory(mem.id, {
      memoryType: mem.memoryType,
      memoryKey: mem.memoryKey,
      content: newContent,
    })
    // 更新本地列表
    const found = formalMemories.value.find((item) => item.id === mem.id)
    if (found) {
      found.content = newContent
      found.updatedAt = new Date().toISOString()
    }
    editingMemoryId.value = null
    editingMemoryContent.value = ''
    message.success('已更新')
  } catch {
    message.error('更新失败')
  } finally {
    handlingFormalMemoryId.value = null
  }
}

function stopGeneration() {
  // 只停**当前会话**的流——别的会话的流不该被这个按钮波及
  const controller = streamControllers.get(currentStreamKey())
  if (!controller) return
  // V4.5：主动停止 → 立刻清掉过程状态（不等 abort 回调）
  clearChatStatus()
  controller.abort()
}

async function regenerate(aiMsgIndex: number) {
  // 确保前一条是用户消息
  if (aiMsgIndex < 1) return
  const prevMsg = messages.value[aiMsgIndex - 1]
  if (prevMsg.role !== 'user') return

  const content = prevMsg.content
  if (!content) return

  // 删掉旧的 AI 消息，用 send() 重新生成（跳过用户消息推送）
  messages.value.splice(aiMsgIndex, 1)
  await send(content, true)
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    send()
  }
}

function clickSuggestion(text: string) {
  send(text)
}

const suggestions = [
  { icon: '🔍', text: '有没有关于Redis的文章？' },
  { icon: '📚', text: '这篇文章主要讲什么？' },
  { icon: '💡', text: '如何设计一个AI Agent？' },
]

// ============================================================
// Watch
// ============================================================

watch(visible, async (v) => {
  if (v && !isGuest.value) {
    loadMemoryCandidates()
  }
})
</script>

<template>
  <div
    class="ai-assistant"
    :class="{ open: visible, fullscreen: isFullscreen }"
  >
    <!-- ============================================================ -->
    <!-- 悬浮按钮 -->
    <!-- ============================================================ -->
    <button
      v-if="!visible"
      class="ai-fab"
      type="button"
      title="海林BlogAI助手"
      @click="toggle"
    >
      <span class="ai-fab-emoji">🤖</span>
    </button>

    <!-- ============================================================ -->
    <!-- 聊天面板 -->
    <!-- ============================================================ -->
    <div v-if="visible" class="ai-panel" :class="{ 'is-dragging': isDragging }" :style="panelStyle">
      <!-- 顶部栏（可拖拽） -->
      <header
        class="ai-panel-header"
        :class="{ dragging: isDragging }"
        @mousedown="onHeaderMouseDown"
      >
        <div class="ai-panel-title">
          <span class="ai-panel-emoji">🤖</span>
          <span>海林BlogAI助手</span>
          <!-- 余额入口：点击跳钱包页。三态配色与个人中心的余额卡一致 -->
          <button
            v-if="!isGuest && wallet"
            type="button"
            class="ai-balance-chip"
            :class="`is-${walletTone}`"
            :title="walletTitle"
            @mousedown.stop
            @click="goWallet"
          >
            {{ formatCredit(displayCredit(wallet)) }}
          </button>
        </div>
        <div class="ai-panel-actions">
          <n-button size="tiny" quaternary @click="createSession">
            <template #icon>
              <n-icon :component="Add" size="16" />
            </template>
            创建会话
          </n-button>
          <n-badge :value="pendingMemoryCount" :show="pendingMemoryCount > 0" :max="99">
            <n-button v-if="!isGuest" size="tiny" quaternary @click="openMemoryManager">
              <template #icon>
                <n-icon :component="BookmarkOutline" size="16" />
              </template>
              记忆
            </n-button>
          </n-badge>
          <n-button v-if="!isGuest" size="tiny" quaternary @click="openHistory">
            <template #icon>
              <n-icon :component="Time" size="16" />
            </template>
            历史记录
          </n-button>
          <n-button size="tiny" quaternary @click="toggleFullscreen">
            <template #icon>
              <n-icon :component="isFullscreen ? Contract : Expand" size="16" />
            </template>
          </n-button>
          <n-button size="tiny" quaternary @click="toggle">
            <template #icon>
              <n-icon :component="Close" size="18" />
            </template>
          </n-button>
        </div>
      </header>

      <!-- ============================================================ -->
      <!-- 历史记录视图 -->
      <!-- ============================================================ -->
      <div v-if="viewingHistory" class="ai-history-view">
        <div class="ai-history-header">
          <n-button size="tiny" quaternary @click="closeHistory">
            <template #icon>
              <n-icon :component="ArrowBack" size="16" />
            </template>
            返回
          </n-button>
          <span class="ai-history-title">历史会话</span>
          <span class="ai-history-spacer" />
        </div>
        <div class="ai-history-list">
          <div
            v-for="s in sortedSessions"
            :key="s.id"
            class="ai-history-item"
            @click="switchSession(s.id)"
          >
            <div class="ai-history-item-left">
              <span class="ai-history-item-icon">💬</span>
              <div class="ai-history-item-info">
                <span class="ai-history-item-title">{{ s.title }}</span>
                <span class="ai-history-item-date">{{
                  new Date(s.createdAt).toLocaleDateString('zh-CN')
                }}</span>
              </div>
            </div>
            <n-button
              size="tiny"
              quaternary
              type="error"
              @click.stop="deleteSession(s.id)"
            >
              <template #icon>
                <n-icon :component="Close" size="14" />
              </template>
            </n-button>
          </div>
          <div v-if="sortedSessions.length === 0" class="ai-history-empty">
            暂无历史会话
          </div>
        </div>
      </div>

      <!-- ============================================================ -->
      <!-- 聊天 / 欢迎视图 -->
      <!-- ============================================================ -->
      <template v-else>
        <div ref="messageListRef" class="ai-body" @scroll="handleMessageListScroll">
          <!-- 消息加载中 -->
          <div v-if="loadingMessages" class="ai-loading">
            <n-spin size="small" />
          </div>

          <!-- 欢迎页 -->
          <div v-else-if="showWelcome" class="ai-welcome">
            <!-- 游客次数用完引导 -->
            <div v-if="isGuest && guestUsedCount >= 3" class="ai-guest-limit-card">
              <span class="ai-guest-limit-icon">🎉</span>
              <p class="ai-guest-limit-text">你已用完 3 次免费体验</p>
              <p class="ai-guest-limit-sub">登录后即可无限使用 AI 助手</p>
              <router-link to="/login" class="ai-guest-limit-login">去登录 →</router-link>
            </div>
            <div class="ai-welcome-card">
              <span class="ai-welcome-icon">🤖</span>
              <h3 class="ai-welcome-title">你好，我是海林BlogAI助手</h3>
              <p class="ai-welcome-subtitle">我可以帮助你：</p>
              <ul class="ai-welcome-list">
                <li>查找博客文章</li>
                <li>解答技术问题</li>
                <li>分析当前页面内容</li>
                <li>辅助学习编程知识</li>
              </ul>
            </div>

            <div class="ai-suggestions">
              <p class="ai-suggestions-label">推荐提问：</p>
              <button
                v-for="(item, i) in suggestions"
                :key="i"
                class="ai-suggestion-chip"
                type="button"
                @click="clickSuggestion(item.text)"
              >
                <span>{{ item.icon }}</span>
                <span>{{ item.text }}</span>
              </button>
            </div>
          </div>

          <!-- 消息列表 -->
          <div v-else class="ai-messages">
            <template v-for="(msg, i) in messages" :key="msg.id || i">
                <div
                  class="ai-msg"
                  :class="[msg.role, { 'has-workflow': msg.workflow }]"
                >
                <span class="ai-msg-avatar">
                  <template v-if="msg.role === 'ai'">🤖</template>
                  <img
                    v-else-if="userAvatar"
                    :src="userAvatar"
                    class="ai-user-avatar-img"
                  />
                  <template v-else>👤</template>
                </span>
                <div class="ai-msg-bubble-wrapper">
                <div v-if="msg.role === 'ai'" class="ai-msg-bubble markdown-body">
                  <span v-html="renderMarkdown(msg.content)" />
                  <span v-if="isMessageStreaming(i, msg.role)" class="ai-typing-dots"><i class="dot" /><i class="dot" /><i class="dot" /></span>
                </div>

                <!-- V4.x：过程面板——过程状态 + 工作流步骤合并成一条链，完成后保留可展开回看
                     （历史部分来自 msg.processSteps，流式部分来自 liveChatSteps，同一列表续着显示） -->
                <div
                  v-if="msg.role === 'ai' && (msg.processSteps?.length || (isMessageStreaming(i, msg.role) && liveChatSteps.length))"
                  class="ai-process"
                >
                  <button
                    class="ai-process__toggle"
                    type="button"
                    @click="toggleProcessPanel(i)"
                  >
                    <span class="ai-process__icon">🧭</span>
                    <span class="ai-process__title">
                      过程（{{ (msg.processSteps?.length ?? 0) + (isMessageStreaming(i, msg.role) ? liveChatSteps.length : 0) }} 步）
                    </span>
                    <span class="ai-process__arrow">{{ isProcessPanelOpen(i) ? '▾' : '▸' }}</span>
                  </button>
                  <div v-show="isProcessPanelOpen(i)" class="ai-process__list">
                    <div
                      v-for="(step, doneIdx) in msg.processSteps ?? []"
                      :key="`done-${doneIdx}`"
                      class="ai-process__item"
                    >
                      <span class="ai-process__status">✓</span>
                      <span class="ai-process__text">{{ step }}</span>
                    </div>
                    <div
                      v-for="(step, liveIdx) in (isMessageStreaming(i, msg.role) ? liveChatSteps : [])"
                      :key="`live-${liveIdx}`"
                      class="ai-process__item"
                    >
                      <span class="ai-process__status">
                        {{ liveIdx === liveChatSteps.length - 1 ? '⌛' : '✓' }}
                      </span>
                      <span class="ai-process__text">{{ step }}</span>
                    </div>
                  </div>
                </div>

                <!--
                  第三刀：任务步骤（外层任务层）。
                  用户要的这几件事，每件的结果如实显示——DISPATCHED 说"已发起"而不是"已完成"。
                  与外层的"思考过程"是两个层级，所以放在它外面、消息正文之下。
                -->
                <div
                  v-if="msg.role === 'ai' && msg.taskSteps?.length"
                  class="ai-task"
                >
                  <div class="ai-task__title">这次要做的事</div>
                  <div
                    v-for="task in msg.taskSteps"
                    :key="task.stepId"
                    class="ai-task__item"
                  >
                    <div class="ai-task__row">
                      <span class="ai-task__icon">{{ taskStateIcon(task.state) }}</span>
                      <span class="ai-task__text">
                        {{ taskResultText(task) }}
                      </span>
                    </div>

                    <!--
                      第四步：该任务步骤的**内部明细**（子 run 的 ReAct 步骤）。
                      它是这一行的展开，不是整条消息的过程，所以不并进下面的"思考过程"面板。
                    -->
                    <div v-if="task.innerSteps?.length" class="ai-task__inner">
                      <div
                        v-for="inner in task.innerSteps"
                        :key="inner.stepNo"
                        class="ai-task__inner-item"
                      >
                        <span class="ai-task__inner-no">{{ inner.stepNo }}</span>
                        <span class="ai-task__inner-text">
                          {{ inner.thoughtSummary?.trim() || inner.message }}
                        </span>
                      </div>
                    </div>
                  </div>
                </div>

                <!-- Agent 思考过程（V2.3 + V3.13 计划）：折叠条 + 计划块 + 步骤列表，实时推流 -->
                <div
                  v-if="msg.role === 'ai' && (msg.thinkingSteps?.length || msg.plan?.length)"
                  class="ai-thinking"
                >
                  <button
                    class="ai-thinking__toggle"
                    type="button"
                    @click="onToggleThinkingSteps()"
                  >
                    <span class="ai-thinking__icon">🤔</span>
                    <span class="ai-thinking__title">
                      思考过程<template v-if="msg.thinkingSteps?.length">（{{ msg.thinkingSteps.length }} 步）</template>
                    </span>
                    <span class="ai-thinking__arrow">{{ thinkingStepsExpanded ? '▾' : '▸' }}</span>
                  </button>
                  <div v-if="thinkingStepsExpanded" class="ai-thinking__list">
                    <!-- V3.13：计划块（run 级）——排在步骤列表之前，用户先看到"我打算做什么" -->
                    <div v-if="msg.plan?.length" class="ai-thinking__plan">
                      <div class="ai-thinking__plan-title">我的计划</div>
                      <div
                        v-for="(item, planIndex) in msg.plan"
                        :key="`plan-${planIndex}`"
                        class="ai-thinking__plan-item"
                      >
                        <span class="ai-thinking__plan-no">{{ planIndex + 1 }}.</span>{{ item }}
                      </div>
                    </div>
                    <div
                      v-for="step in msg.thinkingSteps"
                      :key="step.stepNo"
                      class="ai-thinking__step"
                    >
                      <span class="ai-thinking__step-no">{{ step.stepNo }}</span>
                      <span
                        class="ai-thinking__step-status"
                        :class="`ai-thinking__step-status--${step.status.toLowerCase()}`"
                      >
                        {{ step.status === 'RUNNING' ? '⏳' : step.status === 'SUCCESS' ? '✓' : step.status === 'SKIPPED' ? '↷' : '✗' }}
                      </span>
                      <!-- V3.10：行文本优先思考摘要（trim 防空串），空则回退模板文案 -->
                      <span class="ai-thinking__step-message">{{ step.thoughtSummary?.trim() || step.message }}</span>
                    </div>
                  </div>
                </div>

                <div
                  v-if="msg.role === 'ai' && msg.references?.length"
                  class="ai-rag-references"
                >
                  <div class="ai-rag-title">参考来源</div>
                  <button
                    v-for="(reference, referenceIndex) in msg.references"
                    :key="`${reference.articleId}-${reference.chunkIndex}`"
                    class="ai-rag-item"
                    type="button"
                    @click="openReferenceArticle(reference)"
                  >
                    <span class="ai-rag-item-title">
                      <span class="ai-rag-item-index">[{{ referenceIndex + 1 }}]</span>
                      {{ reference.title || `文章 ${reference.articleId}` }}
                    </span>
                    <span class="ai-rag-item-snippet">{{ reference.snippet }}</span>
                    <span class="ai-rag-item-meta">片段 {{ reference.chunkIndex + 1 }}</span>
                  </button>
                </div>

                <!-- Agent Workflow 建议卡（V2.1）：Agent 基于观察建议启动学习类 Workflow，需用户确认 -->
                <div
                  v-if="msg.role === 'ai' && msg.workflowSuggestion && msg.suggestionState !== 'cancelled' && msg.suggestionState !== 'expired'"
                  class="ai-suggestion-card"
                >
                  <div class="ai-suggestion-card__head">
                    <span class="ai-suggestion-card__badge">Agent 建议</span>
                    <span class="ai-suggestion-card__type">
                      {{ msg.workflowSuggestion.workflowType === 'LEARNING_PLAN' ? '制定学习计划' : msg.workflowSuggestion.workflowType === 'LEARNING_PROGRESS' ? '调整学习进度' : msg.workflowSuggestion.workflowType === 'OPTIMIZE_ARTICLE' ? '文章优化' : '难点攻坚' }}
                    </span>
                  </div>
                  <div class="ai-suggestion-card__reason">{{ msg.workflowSuggestion.reason }}</div>
                  <div class="ai-suggestion-card__actions">
                    <n-button
                      size="small"
                      type="primary"
                      :loading="msg.suggestionState === 'processing'"
                      :disabled="msg.suggestionState === 'processing'"
                      @click="confirmSuggestion(msg)"
                    >
                      继续
                    </n-button>
                    <n-button
                      size="small"
                      :disabled="msg.suggestionState === 'processing'"
                      @click="cancelSuggestion(msg)"
                    >
                      取消
                    </n-button>
                  </div>
                </div>

                <!-- Agent 写动作确认卡（V2.4 / V3.1 / V3.3）：勾选 / 追加 / 重命名任务，需用户确认 -->
                <div
                  v-if="msg.role === 'ai' && msg.writeAction && msg.writeActionState !== 'cancelled' && msg.writeActionState !== 'expired'"
                  class="ai-write-card"
                >
                  <div class="ai-write-card__head">
                    <span class="ai-write-card__badge">写动作提案</span>
                    <span class="ai-write-card__type">
                      {{ writeActionTypeLabel(msg.writeAction) }}
                    </span>
                  </div>
                  <div class="ai-write-card__reason">
                    {{ writeActionReason(msg.writeAction) }}
                  </div>
                  <div class="ai-write-card__actions">
                    <n-button
                      size="small"
                      type="primary"
                      :loading="msg.writeActionState === 'processing'"
                      :disabled="msg.writeActionState === 'processing'"
                      @click="confirmWriteAction(msg)"
                    >
                      {{ writeActionConfirmText(msg.writeAction) }}
                    </n-button>
                    <n-button
                      size="small"
                      :disabled="msg.writeActionState === 'processing'"
                      @click="cancelWriteAction(msg)"
                    >
                      取消
                    </n-button>
                  </div>
                </div>

                <!-- Workflow 卡片 -->
                <div v-if="msg.role === 'ai' && msg.workflow" class="ai-workflow-card">
                  <div class="ai-workflow-card__head">
                    <span class="ai-workflow-card__badge">
                      {{ msg.workflow.workflowType === 'OPTIMIZE_ARTICLE' ? '文章优化 Workflow' : msg.workflow.workflowType === 'LEARNING_PLAN' ? '学习规划 Workflow' : msg.workflow.workflowType === 'LEARNING_PROGRESS' ? '学习进度 Workflow' : msg.workflow.workflowType === 'LEARNING_ASSIST' ? '难点攻坚 Workflow' : '文章创作 Workflow' }}
                    </span>
                    <span
                      v-if="msg.workflow.id === activeWorkflow?.id && isTransitioning"
                      class="ai-workflow-card__status ai-workflow-card__status--loading"
                    >
                      <n-spin :size="14" />
                      <span>{{ transitionLabel }}</span>
                    </span>
                    <span v-else class="ai-workflow-card__status">
                      {{ workflowStatusLabel(msg.workflow.status) }}
                    </span>
                  </div>
                  <div class="ai-workflow-card__body">
                    <div class="ai-workflow-card__step">
                      <span class="ai-workflow-card__label">当前步骤</span>
                      <span>{{ workflowStepLabel(msg.workflow.currentStep) }}</span>
                    </div>
                    <!-- ===== 学习规划 / 学习进度 Workflow：结构化计划渲染（阶段/任务列表） ===== -->
                    <template v-if="msg.workflow.workflowType === 'LEARNING_PLAN' || msg.workflow.workflowType === 'LEARNING_PROGRESS' || msg.workflow.workflowType === 'LEARNING_ASSIST'">
                      <div
                        v-if="msg.workflow.context?.stepResults?.plan"
                        class="ai-learning-plan"
                      >
                        <h4 class="ai-learning-plan-title">
                          {{ msg.workflow.context.stepResults.plan.title || '学习计划' }}
                        </h4>
                        <div
                          v-for="(stage, si) in msg.workflow.context.stepResults.plan.stages"
                          :key="si"
                          class="ai-learning-plan-stage"
                        >
                          <p class="ai-learning-plan-stage-title">阶段 {{ si + 1 }}：{{ stage.title }}</p>
                          <ul class="ai-learning-plan-tasks">
                            <li v-for="(task, ti) in stage.tasks" :key="ti">
                              {{ typeof task === 'string' ? task : task.title }}
                            </li>
                          </ul>
                        </div>
                      </div>
                      <div v-if="createCardData(msg.workflow.context).qualityCheck?.passed !== undefined" class="ai-workflow-card__quality">
                        <span class="ai-workflow-card__label">质量检查</span>
                        <span :class="createCardData(msg.workflow.context).qualityCheck?.passed === false ? 'ai-workflow-card__quality--bad' : 'ai-workflow-card__quality--good'">
                          {{ createCardData(msg.workflow.context).qualityCheck?.passed === false ? '未通过' : '通过' }}
                        </span>

                        <div v-if="createCardData(msg.workflow.context).qualityCheck?.issues?.length" class="ai-workflow-card__quality-block">
                          <div class="ai-workflow-card__quality-title">问题</div>
                          <ul class="ai-workflow-card__quality-list">
                            <li v-for="(item, index) in createCardData(msg.workflow.context).qualityCheck?.issues" :key="index">
                              {{ item }}
                            </li>
                          </ul>
                        </div>

                        <div v-if="createCardData(msg.workflow.context).qualityCheck?.suggestions?.length" class="ai-workflow-card__quality-block">
                          <div class="ai-workflow-card__quality-title">建议</div>
                          <ul class="ai-workflow-card__quality-list">
                            <li v-for="(item, index) in createCardData(msg.workflow.context).qualityCheck?.suggestions" :key="index">
                              {{ item }}
                            </li>
                          </ul>
                        </div>
                      </div>
                    </template>
                    <!-- ===== 创作 Workflow ===== -->
                    <template v-else-if="msg.workflow.workflowType !== 'OPTIMIZE_ARTICLE'">
                    <div v-if="msg.workflow.context?.requirement?.topic" class="ai-workflow-card__topic">
                      <span class="ai-workflow-card__label">主题</span>
                      <span>{{ msg.workflow.context.requirement.topic }}</span>
                    </div>
                    <div
                      v-if="workflowStreamingOutline[msg.workflow.id] || createCardData(msg.workflow.context).outline"
                      class="ai-workflow-card__section-title"
                    >
                      大纲：
                    </div>
                    <pre
                      v-if="workflowStreamingOutline[msg.workflow.id]"
                      class="ai-workflow-card__outline"
                    >{{ workflowStreamingOutline[msg.workflow.id] }}</pre>
                    <pre
                      v-else-if="createCardData(msg.workflow.context).outline"
                      class="ai-workflow-card__outline"
                    >{{ createCardData(msg.workflow.context).outline }}</pre>
                    <div v-else-if="createCardData(msg.workflow.context).draft?.summary" class="ai-workflow-card__summary">
                      <span class="ai-workflow-card__label">草稿摘要</span>
                      <p>{{ createCardData(msg.workflow.context).draft?.summary }}</p>
                    </div>
                    <div
                      v-if="workflowStreamingContent[msg.workflow.id] || createCardData(msg.workflow.context).draft?.content"
                      class="ai-workflow-card__section-title"
                    >
                      草稿：
                    </div>
                    <pre
                      v-if="workflowStreamingContent[msg.workflow.id]"
                      class="ai-workflow-card__stream-content"
                    >{{ workflowStreamingContent[msg.workflow.id] }}</pre>
                    <pre
                      v-else-if="createCardData(msg.workflow.context).draft?.content"
                      class="ai-workflow-card__stream-content"
                    >{{ createCardData(msg.workflow.context).draft?.content }}</pre>
                    <div v-if="createCardData(msg.workflow.context).qualityCheck?.passed !== undefined" class="ai-workflow-card__quality">
                      <span class="ai-workflow-card__label">质量检查</span>
                      <span :class="createCardData(msg.workflow.context).qualityCheck?.passed === false ? 'ai-workflow-card__quality--bad' : 'ai-workflow-card__quality--good'">
                        {{ createCardData(msg.workflow.context).qualityCheck?.passed === false ? '未通过' : '通过' }}
                      </span>

                      <div v-if="createCardData(msg.workflow.context).qualityCheck?.issues?.length" class="ai-workflow-card__quality-block">
                        <div class="ai-workflow-card__quality-title">问题</div>
                        <ul class="ai-workflow-card__quality-list">
                          <li v-for="(item, index) in createCardData(msg.workflow.context).qualityCheck?.issues" :key="index">
                            {{ item }}
                          </li>
                        </ul>
                      </div>

                      <div v-if="createCardData(msg.workflow.context).qualityCheck?.suggestions?.length" class="ai-workflow-card__quality-block">
                        <div class="ai-workflow-card__quality-title">建议</div>
                        <ul class="ai-workflow-card__quality-list">
                          <li v-for="(item, index) in createCardData(msg.workflow.context).qualityCheck?.suggestions" :key="index">
                            {{ item }}
                          </li>
                        </ul>
                      </div>
                    </div>
                    </template>

                    <!-- ===== 优化 Workflow ===== -->
                    <template v-else>
                    <div v-if="msg.workflow.context?.stepResults?.article?.title" class="ai-workflow-card__topic">
                      <span class="ai-workflow-card__label">优化文章</span>
                      <span>{{ msg.workflow.context.stepResults.article.title }}</span>
                    </div>
                    <div
                      v-if="workflowStreamingOutline[msg.workflow.id] || msg.workflow.context?.stepResults?.optimizationPlan"
                      class="ai-workflow-card__section-title"
                    >
                      优化方案：
                    </div>
                    <pre
                      v-if="workflowStreamingOutline[msg.workflow.id]"
                      class="ai-workflow-card__outline"
                    >{{ workflowStreamingOutline[msg.workflow.id] }}</pre>
                    <pre
                      v-else-if="msg.workflow.context?.stepResults?.optimizationPlan"
                      class="ai-workflow-card__outline"
                    >{{ msg.workflow.context.stepResults.optimizationPlan }}</pre>
                    <div
                      v-if="workflowStreamingContent[msg.workflow.id] || msg.workflow.context?.stepResults?.optimizedContent"
                      class="ai-workflow-card__section-title"
                    >
                      优化草稿：
                    </div>
                    <pre
                      v-if="workflowStreamingContent[msg.workflow.id]"
                      class="ai-workflow-card__stream-content"
                    >{{ workflowStreamingContent[msg.workflow.id] }}</pre>
                    <pre
                      v-else-if="msg.workflow.context?.stepResults?.optimizedContent"
                      class="ai-workflow-card__stream-content"
                    >{{ msg.workflow.context.stepResults.optimizedContent }}</pre>
                    <div v-if="msg.workflow.context?.stepResults?.contentCheck && msg.workflow.context.stepResults.contentCheck.passed !== undefined" class="ai-workflow-card__quality">
                      <span class="ai-workflow-card__label">内容检查</span>
                      <span :class="msg.workflow.context.stepResults.contentCheck.passed === false ? 'ai-workflow-card__quality--bad' : 'ai-workflow-card__quality--good'">
                        {{ msg.workflow.context.stepResults.contentCheck.passed === false ? '未通过' : '通过' }}
                      </span>

                      <div v-if="msg.workflow.context.stepResults.contentCheck.issues?.length" class="ai-workflow-card__quality-block">
                        <div class="ai-workflow-card__quality-title">问题</div>
                        <ul class="ai-workflow-card__quality-list">
                          <li v-for="(item, index) in msg.workflow.context.stepResults.contentCheck.issues" :key="index">
                            {{ item }}
                          </li>
                        </ul>
                      </div>

                      <div v-if="msg.workflow.context.stepResults.contentCheck.suggestions?.length" class="ai-workflow-card__quality-block">
                        <div class="ai-workflow-card__quality-title">建议</div>
                        <ul class="ai-workflow-card__quality-list">
                          <li v-for="(item, index) in msg.workflow.context.stepResults.contentCheck.suggestions" :key="index">
                            {{ item }}
                          </li>
                        </ul>
                      </div>
                    </div>
                    </template>

                    <!-- 执行详情（步骤日志，点开才加载） -->
                    <div class="ai-workflow-step-log">
                      <button
                        class="ai-workflow-step-log__toggle"
                        type="button"
                        @click="toggleWorkflowStepLogs(msg.workflow.id)"
                      >
                        <span>执行详情</span>
                        <span>{{ workflowStepLogsExpanded[msg.workflow.id] ? '收起' : '展开' }}</span>
                      </button>

                      <div v-if="workflowStepLogsExpanded[msg.workflow.id]" class="ai-workflow-step-log__body">
                        <div
                          v-if="workflowStepLogsLoading[msg.workflow.id]"
                          class="ai-workflow-step-log__empty"
                        >
                          加载中...
                        </div>

                        <div
                          v-else-if="!workflowStepLogs[msg.workflow.id]?.length"
                          class="ai-workflow-step-log__empty"
                        >
                          暂无执行日志
                        </div>

                        <div
                          v-else
                          v-for="log in workflowStepLogs[msg.workflow.id]"
                          :key="log.id"
                          class="ai-workflow-step-log__item"
                        >
                          <div class="ai-workflow-step-log__head">
                            <span class="ai-workflow-step-log__step">
                              {{ log.stepOrder ? `第 ${log.stepOrder} 步 · ` : '' }}{{ workflowStepLabel(log.step) }}
                            </span>
                            <span
                              v-if="log.logType"
                              class="ai-workflow-step-log__type"
                              :class="log.logType === 'STEP' ? 'is-step' : 'is-operation'"
                            >
                              {{ log.logType === 'STEP' ? '步骤' : '操作' }}
                            </span>
                            <span
                              class="ai-workflow-step-log__status"
                              :class="workflowStepStatusClass(log.status)"
                            >
                              {{ workflowStepStatusLabel(log.status) }}
                            </span>
                          </div>

                          <div class="ai-workflow-step-log__meta">
                            <span>耗时 {{ formatDuration(log.durationMs) }}</span>
                            <span v-if="log.retryCount">重试 {{ log.retryCount }}</span>
                            <span v-if="log.inputTokens || log.outputTokens">
                              token {{ log.inputTokens ?? 0 }} / {{ log.outputTokens ?? 0 }}
                            </span>
                          </div>

                          <div v-if="log.inputSummary" class="ai-workflow-step-log__summary">
                            <strong>输入：</strong>{{ log.inputSummary }}
                          </div>

                          <div v-if="log.outputSummary" class="ai-workflow-step-log__summary">
                            <strong>输出：</strong>{{ log.outputSummary }}
                          </div>

                          <div v-if="log.errorMessage" class="ai-workflow-step-log__error">
                            {{ log.errorMessage }}
                          </div>
                        </div>
                      </div>
                    </div>
                  </div>
                </div>

                <div v-else-if="msg.role !== 'ai'" class="ai-msg-bubble">{{ msg.content }}</div>
                <div class="ai-msg-meta">
                  <div v-if="msg.role === 'ai'" class="ai-msg-actions">
                    <button
                      class="ai-msg-action-btn"
                      :class="{ 'is-copied': msg.copied }"
                      title="复制"
                      @click="copyMessage(msg)"
                    >
                      <n-icon :component="msg.copied ? CheckmarkOutline : CopyOutline" size="15" />
                    </button>
                    <button class="ai-msg-action-btn" title="重新生成" @click="regenerate(i)">
                      <n-icon :component="RefreshOutline" size="15" />
                    </button>
                    <button class="ai-msg-action-btn" title="删除" @click="deleteMessage(i)">
                      <n-icon :component="TrashOutline" size="15" />
                    </button>
                  </div>
                  <div v-else class="ai-msg-actions">
                    <button
                      class="ai-msg-action-btn"
                      :class="{ 'is-copied': msg.copied }"
                      title="复制"
                      @click="copyMessage(msg)"
                    >
                      <n-icon :component="msg.copied ? CheckmarkOutline : CopyOutline" size="15" />
                    </button>
                    <button class="ai-msg-action-btn" title="删除" @click="deleteMessage(i)">
                      <n-icon :component="TrashOutline" size="15" />
                    </button>
                  </div>
                  <span class="ai-msg-time">{{ formatMessageTime(msg.createdAt) }}</span>
                </div>
              </div>
              </div>

              <!-- 会话压缩状态：固定插在已压缩消息之后，后续新消息显示在提示下面 -->
              <div
                v-if="compressionPhase !== 'idle' && i + 1 === compressionCoveredMessageCount"
                class="ai-compression-note"
              >
                <div
                  v-if="compressionPhase === 'compressing' || compressionPhase === 'done'"
                  class="ai-compression-note-line is-muted"
                >
                  正在自动压缩上下文
                </div>
                <div v-if="compressionPhase === 'done'" class="ai-compression-note-line">
                  已完成上下文压缩
                </div>
              </div>
            </template>
          </div>
        </div>

        <!-- Workflow 过渡面板（点击同意/拒绝/完成/取消后，后端处理中） -->
        <footer v-if="isTransitioning" class="ai-input-area">
          <div class="ai-workflow-panel ai-workflow-panel--transition">
            <n-spin size="small" />
            <span class="ai-workflow-transition-label">{{ transitionLabel }}</span>
          </div>
        </footer>

        <!-- Workflow 需求确认面板（无同意/不同意，直接输入） -->
        <footer v-else-if="workflowNeedRequirement" class="ai-input-area">
          <div class="ai-workflow-panel">
            <div class="ai-workflow-head">
              <span class="ai-workflow-badge">
                {{ activeWorkflow?.workflowType === 'OPTIMIZE_ARTICLE' ? '文章优化 Workflow' : activeWorkflow?.workflowType === 'LEARNING_PLAN' ? '学习规划 Workflow' : activeWorkflow?.workflowType === 'LEARNING_PROGRESS' ? '学习进度 Workflow' : activeWorkflow?.workflowType === 'LEARNING_ASSIST' ? '难点攻坚 Workflow' : '文章创作 Workflow' }}
              </span>
              <span class="ai-workflow-status">{{ workflowStatusLabel(activeWorkflow?.status) }}</span>
            </div>

            <div class="ai-workflow-reject">
              <textarea
                v-model="workflowFeedback"
                class="ai-workflow-feedback"
                rows="2"
                :placeholder="activeWorkflow?.context?.confirmation?.question ?? '请补充写作主题'"
                :disabled="workflowBusy"
              />
              <div class="ai-workflow-reject-actions">
                <button
                  class="ai-workflow-btn ai-workflow-btn--ghost"
                  type="button"
                  :disabled="workflowBusy"
                  @click="cancelWorkflow"
                >
                  取消工作流
                </button>
                <button
                  class="ai-workflow-btn ai-workflow-btn--primary"
                  type="button"
                  :disabled="workflowBusy || !workflowFeedback.trim()"
                  @click="rejectWorkflow"
                >
                  发送
                </button>
              </div>
            </div>
          </div>
        </footer>

        <!-- Workflow 确认面板（同意/不同意） -->
        <footer v-else-if="workflowWaitingConfirm" class="ai-input-area">
          <div class="ai-workflow-panel">
            <div class="ai-workflow-head">
              <span class="ai-workflow-badge">
                {{ activeWorkflow?.workflowType === 'OPTIMIZE_ARTICLE' ? '文章优化 Workflow' : activeWorkflow?.workflowType === 'LEARNING_PLAN' ? '学习规划 Workflow' : activeWorkflow?.workflowType === 'LEARNING_PROGRESS' ? '学习进度 Workflow' : activeWorkflow?.workflowType === 'LEARNING_ASSIST' ? '难点攻坚 Workflow' : '文章创作 Workflow' }}
              </span>
              <span
                v-if="workflowDraftNeedsFix"
                class="ai-workflow-status ai-workflow-status--bad"
              >{{ activeWorkflow?.workflowType === 'OPTIMIZE_ARTICLE' ? '优化结果未通过检查，请提修改意见' : '草稿未通过检查，请提修改意见' }}</span>
              <span v-else class="ai-workflow-status">{{ workflowStatusLabel(activeWorkflow?.status) }}</span>
            </div>

            <!-- 学习规划：确认卡片 type 为 LEARNING_PLAN 时按结构化计划渲染 -->
            <div
              v-if="workflowConfirmation?.type === 'LEARNING_PLAN' && workflowLearningPlan"
              class="ai-learning-plan"
            >
              <h4 class="ai-learning-plan-title">{{ workflowLearningPlan.title || '学习计划' }}</h4>
              <div
                v-for="(stage, si) in workflowLearningPlan.stages"
                :key="si"
                class="ai-learning-plan-stage"
              >
                <p class="ai-learning-plan-stage-title">阶段 {{ si + 1 }}：{{ stage.title }}</p>
                <ul class="ai-learning-plan-tasks">
                  <li v-for="(task, ti) in stage.tasks" :key="ti">
                    {{ typeof task === 'string' ? task : task.title }}
                  </li>
                </ul>
              </div>
              <div v-if="workflowQualityCheck?.passed !== undefined" class="ai-workflow-card__quality">
                <span class="ai-workflow-card__label">质量检查</span>
                <span :class="workflowQualityCheck?.passed === false ? 'ai-workflow-card__quality--bad' : 'ai-workflow-card__quality--good'">
                  {{ workflowQualityCheck?.passed === false ? '未通过' : '通过' }}
                </span>

                <div v-if="workflowQualityCheck?.issues?.length" class="ai-workflow-card__quality-block">
                  <div class="ai-workflow-card__quality-title">问题</div>
                  <ul class="ai-workflow-card__quality-list">
                    <li v-for="(item, index) in workflowQualityCheck?.issues" :key="index">
                      {{ item }}
                    </li>
                  </ul>
                </div>

                <div v-if="workflowQualityCheck?.suggestions?.length" class="ai-workflow-card__quality-block">
                  <div class="ai-workflow-card__quality-title">建议</div>
                  <ul class="ai-workflow-card__quality-list">
                    <li v-for="(item, index) in workflowQualityCheck?.suggestions" :key="index">
                      {{ item }}
                    </li>
                  </ul>
                </div>
              </div>
            </div>

            <div v-if="workflowDraftNeedsFix || workflowRejectEditing" class="ai-workflow-reject">
              <textarea
                v-model="workflowFeedback"
                class="ai-workflow-feedback"
                rows="2"
                :placeholder="workflowDraftNeedsFix
                  ? (activeWorkflow?.workflowType === 'OPTIMIZE_ARTICLE'
                    ? '优化结果未通过检查，请输入修改意见后重写'
                    : '草稿未通过质量检查，请输入修改意见后重写')
                  : '请输入修改意见...'"
                :disabled="workflowBusy"
              />
              <div class="ai-workflow-reject-actions">
                <button
                  v-if="!workflowDraftNeedsFix"
                  class="ai-workflow-btn ai-workflow-btn--ghost"
                  type="button"
                  :disabled="workflowBusy"
                  @click="resetWorkflowRejectUI"
                >
                  取消
                </button>
                <button
                  class="ai-workflow-btn ai-workflow-btn--no"
                  type="button"
                  :disabled="workflowBusy"
                  @click="cancelWorkflow"
                >
                  取消工作流
                </button>
                <button
                  class="ai-workflow-btn ai-workflow-btn--primary"
                  type="button"
                  :disabled="workflowBusy || !workflowFeedback.trim()"
                  @click="rejectWorkflow"
                >
                  {{ workflowDraftNeedsFix ? '发送修改意见' : '发送' }}
                </button>
              </div>
            </div>

            <div v-else class="ai-workflow-actions">
              <button
                class="ai-workflow-btn ai-workflow-btn--ok"
                type="button"
                :disabled="workflowBusy || workflowHasBlockingIssues"
                @click="approveWorkflow"
              >
                同意
              </button>
              <button
                class="ai-workflow-btn ai-workflow-btn--no"
                type="button"
                :disabled="workflowBusy"
                @click="workflowRejectEditing = true"
              >
                不同意
              </button>
              <button
                class="ai-workflow-btn ai-workflow-btn--ghost"
                type="button"
                :disabled="workflowBusy"
                @click="cancelWorkflow"
              >
                取消
              </button>
            </div>
          </div>
        </footer>

        <!-- Workflow 失败面板（可重试或取消） -->
        <footer v-else-if="workflowFailed" class="ai-input-area">
          <div class="ai-workflow-panel">
            <div class="ai-workflow-head">
              <span class="ai-workflow-badge">
                {{ activeWorkflow?.workflowType === 'OPTIMIZE_ARTICLE' ? '文章优化 Workflow' : activeWorkflow?.workflowType === 'LEARNING_PLAN' ? '学习规划 Workflow' : activeWorkflow?.workflowType === 'LEARNING_PROGRESS' ? '学习进度 Workflow' : activeWorkflow?.workflowType === 'LEARNING_ASSIST' ? '难点攻坚 Workflow' : '文章创作 Workflow' }}
              </span>
              <span class="ai-workflow-status ai-workflow-status--bad">
                {{ workflowStatusLabel(activeWorkflow?.status) }}
              </span>
            </div>

            <div v-if="activeWorkflow?.errorMessage" class="ai-workflow-error">
              {{ activeWorkflow.errorMessage }}
            </div>

            <div class="ai-workflow-actions">
              <button
                class="ai-workflow-btn ai-workflow-btn--ok"
                type="button"
                :disabled="workflowBusy"
                @click="retryWorkflow"
              >
                重试
              </button>
              <button
                class="ai-workflow-btn ai-workflow-btn--no"
                type="button"
                :disabled="workflowBusy"
                @click="cancelWorkflow"
              >
                取消
              </button>
            </div>
          </div>
        </footer>

        <!-- 普通输入区 -->
        <footer v-else class="ai-input-area">
          <n-button
            size="small"
            quaternary
            title="附加页面上下文（即将上线）"
            disabled
          >
            <template #icon>
              <n-icon :component="Attach" size="18" />
            </template>
          </n-button>
          <textarea
            v-model="input"
            class="ai-input"
            placeholder="输入你的问题…"
            :disabled="sending"
            rows="1"
            @keydown="onKeydown"
          />
          <button
            v-if="sending"
            class="ai-stop-btn"
            type="button"
            @click="stopGeneration()"
          >
            <n-icon :component="StopCircleOutline" size="18" />
          </button>
          <button
            v-else
            class="ai-send-btn"
            type="button"
            :disabled="!input.trim()"
            @click="send()"
          >
            <n-icon :component="Send" size="18" />
          </button>
        </footer>
      </template>

      <!-- 边缘缩放手柄 -->
      <div class="ai-resize-h resize-n" @mousedown="onResizeStart($event, 'n')" />
      <div class="ai-resize-h resize-s" @mousedown="onResizeStart($event, 's')" />
      <div class="ai-resize-h resize-e" @mousedown="onResizeStart($event, 'e')" />
      <div class="ai-resize-h resize-w" @mousedown="onResizeStart($event, 'w')" />
      <div class="ai-resize-h resize-ne" @mousedown="onResizeStart($event, 'ne')" />
      <div class="ai-resize-h resize-nw" @mousedown="onResizeStart($event, 'nw')" />
      <div class="ai-resize-h resize-se" @mousedown="onResizeStart($event, 'se')" />
      <div class="ai-resize-h resize-sw" @mousedown="onResizeStart($event, 'sw')" />
    </div>

    <!-- 记忆管理模态框 -->
    <n-modal
      v-model:show="memoryManagerVisible"
      preset="card"
      title="记忆管理"
      size="small"
      closable
      :bordered="false"
      :mask-closable="false"
      class="ai-memory-modal"
      style="width: min(640px, calc(100vw - 32px));"
    >
      <template #header-extra>
        <n-tabs
          :value="memoryTab"
          size="small"
          class="ai-memory-tabs"
          @update:value="(v: string) => memoryTab = v as 'candidates' | 'memories'"
        >
          <n-tab-pane name="candidates" tab="待确认" />
          <n-tab-pane name="memories" tab="长期记忆" />
        </n-tabs>
      </template>

      <!-- 待确认记忆 -->
      <div v-if="memoryTab === 'candidates'" class="ai-memory-tab-body">
        <div v-if="loadingMemoryCandidates" class="ai-memory-empty">
          加载中...
        </div>

        <div v-else-if="memoryCandidates.length === 0" class="ai-memory-empty">
          暂无待确认记忆
        </div>

        <div v-else class="ai-memory-list">
          <div
            v-for="candidate in memoryCandidates"
            :key="candidate.id"
            class="ai-memory-item"
          >
            <div class="ai-memory-item-head">
              <span class="ai-memory-type">
                {{ memoryTypeLabel(candidate.memoryType) }}
                <span class="ai-memory-key">{{ candidate.memoryKey }}</span>
              </span>
              <span class="ai-memory-importance">重要性 {{ candidate.importance ?? 5 }}</span>
            </div>

            <div class="ai-memory-meta-row">
              <span class="ai-memory-action" :class="`ai-memory-action--${candidate.candidateAction.toLowerCase()}`">
                {{ memoryActionLabel(candidate.candidateAction) }}
              </span>
              <span v-if="candidate.targetMemoryId" class="ai-memory-target">
                目标 #{{ candidate.targetMemoryId }}
              </span>
            </div>

            <!-- 编辑模式 -->
            <textarea
              v-if="editingCandidateId === candidate.id"
              v-model="editingCandidateContent"
              class="ai-memory-edit-input"
              rows="3"
            />
            <div v-else class="ai-memory-content">{{ candidate.content }}</div>

            <div v-if="candidate.mergedContent" class="ai-memory-merged">
              合并后：{{ candidate.mergedContent }}
            </div>

            <div v-if="candidate.reason" class="ai-memory-reason">
              {{ candidate.reason }}
            </div>

            <div v-if="candidate.decisionReason" class="ai-memory-reason">
              决策：{{ candidate.decisionReason }}
            </div>

            <div class="ai-memory-actions">
              <!-- 编辑模式操作 -->
              <template v-if="editingCandidateId === candidate.id">
                <n-button size="tiny" quaternary @click="cancelEditCandidate">
                  取消
                </n-button>
                <n-button
                  size="tiny"
                  type="primary"
                  :loading="handlingMemoryCandidateId === candidate.id"
                  @click="confirmMemoryCandidate(candidate.id)"
                >
                  保存
                </n-button>
                <n-button
                  size="tiny"
                  quaternary
                  :loading="handlingMemoryCandidateId === candidate.id"
                  @click="rejectMemoryCandidate(candidate.id)"
                >
                  忽略
                </n-button>
              </template>
              <!-- 普通模式操作 -->
              <template v-else>
                <n-button size="tiny" quaternary @click="startEditCandidate(candidate)">
                  编辑
                </n-button>
                <n-button
                  size="tiny"
                  type="primary"
                  :loading="handlingMemoryCandidateId === candidate.id"
                  @click="confirmMemoryCandidate(candidate.id)"
                >
                  {{ confirmMemoryCandidateLabel(candidate.candidateAction) }}
                </n-button>
                <n-button
                  size="tiny"
                  quaternary
                  :loading="handlingMemoryCandidateId === candidate.id"
                  @click="rejectMemoryCandidate(candidate.id)"
                >
                  忽略
                </n-button>
              </template>
            </div>
          </div>
        </div>
      </div>

      <!-- 长期记忆 -->
      <div v-else class="ai-memory-tab-body">
        <div v-if="loadingFormalMemories" class="ai-memory-empty">
          加载中...
        </div>

        <div v-else-if="formalMemories.length === 0" class="ai-memory-empty">
          暂无长期记忆
        </div>

        <div v-else class="ai-memory-list">
          <div
            v-for="mem in formalMemories"
            :key="mem.id"
            class="ai-memory-item"
          >
            <div class="ai-memory-item-head">
              <span class="ai-memory-type">
                {{ memoryTypeLabel(mem.memoryType) }}
                <span class="ai-memory-key">{{ mem.memoryKey }}</span>
              </span>
              <span class="ai-memory-importance">重要性 {{ mem.importance ?? 5 }}</span>
            </div>

            <!-- 编辑模式 -->
            <textarea
              v-if="editingMemoryId === mem.id"
              v-model="editingMemoryContent"
              class="ai-memory-edit-input"
              rows="3"
            />
            <div v-else class="ai-memory-content">{{ mem.content }}</div>

            <div v-if="mem.source" class="ai-memory-source">
              来源：{{ mem.source }}
            </div>

            <div class="ai-memory-actions">
              <span class="ai-memory-date">{{ new Date(mem.updatedAt).toLocaleDateString('zh-CN') }}</span>
              <!-- 编辑模式操作 -->
              <template v-if="editingMemoryId === mem.id">
                <n-button size="tiny" quaternary @click="cancelEditMemory">
                  取消
                </n-button>
                <n-button
                  size="tiny"
                  type="primary"
                  :loading="handlingFormalMemoryId === mem.id"
                  @click="saveEditMemory(mem)"
                >
                  保存
                </n-button>
              </template>
              <!-- 普通模式操作 -->
              <template v-else>
                <n-button size="tiny" quaternary @click="startEditMemory(mem)">
                  编辑
                </n-button>
                <n-button
                  size="tiny"
                  quaternary
                  type="error"
                  :loading="handlingFormalMemoryId === mem.id"
                  @click="promptDeleteMemory(mem.id)"
                >
                  删除
                </n-button>
              </template>
            </div>
          </div>
        </div>
      </div>
    </n-modal>
  </div>
</template>

<style scoped>
/* ============================================================
   Container
   ============================================================ */

.ai-assistant {
  position: fixed;
  right: 24px;
  bottom: 120px;
  z-index: 1000;
  pointer-events: none;
}

/* ============================================================
   FAB
   ============================================================ */

.ai-fab {
  width: 56px;
  height: 56px;
  border: 0;
  border-radius: 50%;
  background: #2f6f73;
  box-shadow: 0 4px 16px rgba(47, 111, 115, 0.36);
  cursor: pointer;
  pointer-events: auto;
  display: grid;
  place-items: center;
  transition: transform 0.2s, box-shadow 0.2s;
}

.ai-fab:hover {
  transform: scale(1.08);
  box-shadow: 0 6px 24px rgba(47, 111, 115, 0.48);
}

.ai-fab-emoji {
  font-size: 26px;
  line-height: 1;
}

/* ============================================================
   Panel
   ============================================================ */

.ai-panel {
  width: 500px;
  height: 560px;
  min-width: 360px;
  min-height: 400px;
  max-width: calc(100vw - 48px);
  max-height: calc(100vh - 140px); /* 120px(bottom) + 20px 保险，避免盖住顶部 header */
  border-radius: 12px;
  background: #fff;
  box-shadow: 0 8px 40px rgba(0, 0, 0, 0.12);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  pointer-events: auto;
  animation: ai-panel-in 0.24s ease-out;
  transition: width 0.3s, height 0.3s;
}

/* 拖拽中禁用动画 */
.ai-panel.is-dragging {
  transition: none;
}

/* 全屏：容器铺满视口，面板 100% 填充 */
.ai-assistant.fullscreen {
  top: 24px;
  left: 24px;
  right: 24px;
  bottom: 24px;
}

.ai-assistant.fullscreen .ai-panel {
  width: 100%;
  height: 100%;
  min-width: 0;
  min-height: 0;
  max-width: none;
  max-height: none;
}

/* 全屏隐藏缩放手柄 */
.ai-assistant.fullscreen .ai-resize-h {
  display: none;
}

@keyframes ai-panel-in {
  from {
    opacity: 0;
    transform: translateY(16px) scale(0.96);
  }
  to {
    opacity: 1;
    transform: translateY(0) scale(1);
  }
}

/* ============================================================
   Header
   ============================================================ */

.ai-panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 16px;
  border-bottom: 1px solid #eef0f2;
  background: #fbfdfc;
  flex-shrink: 0;
  gap: 8px;
  cursor: grab;
  user-select: none;
}

.ai-panel-header.dragging {
  cursor: grabbing;
}

.ai-panel-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 16px;
  font-weight: 700;
  color: #1f2937;
  white-space: nowrap;
}

.ai-panel-emoji {
  font-size: 22px;
}

/* 余额 chip：三态配色与 WalletBalanceCard / WalletView 保持一致 */
.ai-balance-chip {
  padding: 0 8px;
  border: 1px solid #e5e7eb;
  border-radius: 999px;
  background: #f8fafc;
  color: #64748b;
  font-size: 11px;
  font-weight: 600;
  line-height: 18px;
  cursor: pointer;
}

.ai-balance-chip:hover {
  background: #f1f5f9;
}

/* 预占中：余额是负的但不必充值，中性偏暖色，不做成告警 */
.ai-balance-chip.is-pending {
  border-color: #fde68a;
  background: #fffbeb;
  color: #b45309;
}

/* 结算后仍为负：这才需要充值 */
.ai-balance-chip.is-empty {
  border-color: #fecaca;
  background: #fef2f2;
  color: #dc2626;
}

.ai-panel-actions {
  display: flex;
  align-items: center;
  gap: 2px;
  flex-shrink: 0;
}

/* ============================================================
   Body
   ============================================================ */

.ai-body {
  position: relative;
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
}

/* 加载态 */
.ai-loading {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
}

/* ---- 欢迎页 ---- */

.ai-welcome {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 24px 20px;
  gap: 20px;
  overflow-y: auto;
}

.ai-welcome-card {
  width: 100%;
  background: #f8fafa;
  border: 1px solid #e6eded;
  border-radius: 12px;
  padding: 24px 20px;
  text-align: center;
}

.ai-welcome-icon {
  font-size: 48px;
  display: block;
  margin-bottom: 12px;
}

.ai-welcome-title {
  margin: 0 0 12px;
  font-size: 18px;
  font-weight: 700;
  color: #1f2937;
}

.ai-welcome-subtitle {
  margin: 0 0 10px;
  font-size: 14px;
  color: #6b7280;
}

.ai-welcome-list {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.ai-welcome-list li {
  font-size: 14px;
  color: #374151;
}

.ai-welcome-list li::before {
  content: '• ';
  color: #2f6f73;
  font-weight: 700;
}

/* ---- 推荐提问 ---- */

.ai-suggestions {
  width: 100%;
}

.ai-suggestions-label {
  margin: 0 0 10px;
  font-size: 13px;
  color: #9ca3af;
  font-weight: 500;
}

.ai-suggestion-chip {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  padding: 10px 14px;
  margin-bottom: 8px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #f9fafb;
  font-size: 13px;
  color: #374151;
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s;
  text-align: left;
}

.ai-suggestion-chip:hover {
  border-color: #2f6f73;
  background: #f0f7f7;
}

/* ---- 消息列表 ---- */

.ai-messages {
  flex: 1;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.ai-msg {
  display: flex;
  gap: 8px;
  max-width: 88%;
}

.ai-msg.user {
  align-self: flex-end;
  flex-direction: row-reverse;
}

.ai-msg.ai {
  align-self: flex-start;
}

.ai-msg.ai.has-workflow {
  width: 88%;
}

.ai-msg.ai.has-workflow .ai-msg-bubble-wrapper {
  flex: 1 1 0;
}

.ai-msg.ai.has-workflow .ai-msg-bubble {
  display: table;
  max-width: 100%;
}

.ai-msg-avatar {
  font-size: 20px;
  line-height: 1;
  flex-shrink: 0;
  margin-top: 2px;
}

.ai-user-avatar-img {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  object-fit: cover;
}

.ai-msg-bubble {
  padding: 10px 14px;
  border-radius: 12px;
  font-size: 14px;
  line-height: 1.6;
  word-break: break-word;
}

.ai-msg.user .ai-msg-bubble {
  background: #2f6f73;
  color: #fff;
  border-bottom-right-radius: 4px;
}

.ai-msg.ai .ai-msg-bubble {
  background: #f3f4f6;
  color: #1f2937;
  border-bottom-left-radius: 4px;
}

.ai-compression-note {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  padding: 4px 0 0;
  margin-top: 2px;
  pointer-events: none;
}

.ai-compression-note-line {
  font-size: 12px;
  line-height: 1.4;
  color: rgba(120, 130, 145, 0.72);
}

.ai-compression-note-line.is-muted {
  color: rgba(120, 130, 145, 0.5);
}

/* ---- RAG 引用列表 ---- */

.ai-rag-references {
  margin-top: 8px;
  padding: 9px;
  border: 1px solid #d8e7e5;
  border-radius: 8px;
  background: #f7fbfa;
}

.ai-rag-title {
  margin-bottom: 6px;
  font-size: 12px;
  font-weight: 700;
  color: #2f6f73;
}

.ai-rag-item {
  display: block;
  width: 100%;
  padding: 8px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: inherit;
  text-align: left;
  cursor: pointer;
}

.ai-rag-item:hover {
  background: #eef7f6;
}

.ai-rag-item + .ai-rag-item {
  margin-top: 4px;
}

.ai-rag-item-title {
  display: block;
  font-size: 13px;
  font-weight: 700;
  color: #1f2937;
  line-height: 1.4;
}

.ai-rag-item-index {
  margin-right: 6px;
  color: #2f6f73;
}

.ai-rag-item-snippet {
  display: block;
  margin-top: 3px;
  font-size: 12px;
  line-height: 1.45;
  color: #4b5563;
}

.ai-rag-item-meta {
  display: block;
  margin-top: 4px;
  font-size: 11px;
  color: #7c8f8d;
}

/* ---- 消息操作按钮与时间戳 ---- */

.ai-msg-bubble-wrapper {
  position: relative;
  min-width: 0;
}

.ai-msg-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 4px;
}

.ai-msg-actions {
  display: flex;
  gap: 4px;
  opacity: 0;
  transition: opacity 0.15s;
}

.ai-msg-bubble-wrapper:hover .ai-msg-actions {
  opacity: 1;
}

.ai-msg-action-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border: 1px solid #e5e7eb;
  border-radius: 5px;
  background: #fff;
  color: #888;
  cursor: pointer;
  transition: color 0.15s, border-color 0.15s, background 0.15s;
}

.ai-msg-action-btn:hover {
  color: #2f6f73;
  border-color: #2f6f73;
  background: #f0f9f9;
}

.ai-msg-action-btn.is-copied,
.ai-msg-action-btn.is-copied:hover {
  color: #18a058;
  border-color: #18a058;
  background: #f0faf4;
}

.ai-msg-time {
  font-size: 11px;
  color: #bbb;
  flex-shrink: 0;
}

/* 打字动画 */
.typing {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 14px 18px;
}

.typing .dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #9ca3af;
  animation: typing-bounce 1.4s infinite ease-in-out both;
}

.typing .dot:nth-child(1) {
  animation-delay: 0s;
}
.typing .dot:nth-child(2) {
  animation-delay: 0.16s;
}
.typing .dot:nth-child(3) {
  animation-delay: 0.32s;
}

@keyframes typing-bounce {
  0%,
  80%,
  100% {
    transform: scale(0.6);
    opacity: 0.4;
  }
  40% {
    transform: scale(1);
    opacity: 1;
  }
}

/* ============================================================
   Input
   ============================================================ */

.ai-input-area {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  padding: 10px 14px;
  border-top: 1px solid #eef0f2;
  flex-shrink: 0;
}

.ai-input {
  flex: 1;
  padding: 9px 12px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  font-size: 14px;
  outline: none;
  transition: border-color 0.15s;
  background: #f9fafb;
  font-family: inherit;
  line-height: 1.5;
  resize: none;
  max-height: 120px;
}

.ai-input:focus {
  border-color: #2f6f73;
  background: #fff;
}

.ai-input::placeholder {
  color: #9ca3af;
}

.ai-send-btn {
  width: 38px;
  height: 38px;
  border: 0;
  border-radius: 8px;
  background: #2f6f73;
  color: #fff;
  cursor: pointer;
  display: grid;
  place-items: center;
  transition: background 0.15s, opacity 0.15s;
  flex-shrink: 0;
}

.ai-send-btn:hover:not(:disabled) {
  background: #25595d;
}

.ai-send-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.ai-stop-btn {
  width: 38px;
  height: 38px;
  border: 0;
  border-radius: 8px;
  background: #e74c3c;
  color: #fff;
  cursor: pointer;
  display: grid;
  place-items: center;
  transition: background 0.15s;
  flex-shrink: 0;
}

.ai-stop-btn:hover {
  background: #c0392b;
}

/* ============================================================
   History View
   ============================================================ */

.ai-history-view {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.ai-history-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 16px;
  border-bottom: 1px solid #eef0f2;
  flex-shrink: 0;
}

.ai-history-title {
  font-size: 15px;
  font-weight: 600;
  color: #1f2937;
}

.ai-history-spacer {
  flex: 1;
}

.ai-history-list {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}

.ai-history-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 12px;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.15s;
}

.ai-history-item:hover {
  background: #f3f4f6;
}

.ai-history-item-left {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.ai-history-item-icon {
  font-size: 20px;
  flex-shrink: 0;
}

.ai-history-item-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.ai-history-item-title {
  font-size: 14px;
  font-weight: 500;
  color: #1f2937;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ai-history-item-date {
  font-size: 12px;
  color: #9ca3af;
}

.ai-history-empty {
  text-align: center;
  color: #9ca3af;
  font-size: 14px;
  padding: 40px 0;
}

/* ============================================================
   Markdown 渲染样式
   ============================================================ */

.markdown-body :deep(p) {
  margin: 0 0 8px;
}

.markdown-body :deep(p:last-child) {
  margin-bottom: 0;
}

.markdown-body :deep(h1),
.markdown-body :deep(h2),
.markdown-body :deep(h3),
.markdown-body :deep(h4) {
  margin: 12px 0 6px;
  font-weight: 600;
  color: #1f2937;
}

.markdown-body :deep(h1) { font-size: 1.25em; }
.markdown-body :deep(h2) { font-size: 1.15em; }
.markdown-body :deep(h3) { font-size: 1.05em; }

.markdown-body :deep(ul),
.markdown-body :deep(ol) {
  margin: 4px 0 8px;
  padding-left: 18px;
}

.markdown-body :deep(li) {
  margin-bottom: 2px;
}

.markdown-body :deep(a) {
  color: #2f6f73;
}

.markdown-body :deep(blockquote) {
  margin: 8px 0;
  padding: 4px 12px;
  border-left: 3px solid #2f6f73;
  background: #f0f7f7;
  border-radius: 0 4px 4px 0;
  color: #555;
}

.markdown-body :deep(table) {
  width: 100%;
  margin: 8px 0;
  border-collapse: collapse;
  font-size: 13px;
}

.markdown-body :deep(th),
.markdown-body :deep(td) {
  padding: 6px 10px;
  border: 1px solid #e5e7eb;
  text-align: left;
}

.markdown-body :deep(th) {
  background: #f3f4f6;
  font-weight: 600;
}

/* 行内代码 */
.markdown-body :deep(code:not(pre code)) {
  padding: 1px 5px;
  border-radius: 3px;
  background: #f0f0f0;
  color: #d63384;
  font-family: 'SF Mono', 'Cascadia Code', 'Fira Code', Consolas, monospace;
  font-size: 0.88em;
}

/* 代码块 */
.markdown-body :deep(pre) {
  margin: 8px 0;
  padding: 12px;
  border-radius: 6px;
  background: #1e1e1e;
  overflow-x: auto;
  line-height: 1.5;
}

.markdown-body :deep(pre code) {
  font-family: 'SF Mono', 'Cascadia Code', 'Fira Code', Consolas, monospace;
  font-size: 13px;
  color: #d4d4d4;
  background: none;
  padding: 0;
}

.markdown-body :deep(hr) {
  margin: 12px 0;
  border: 0;
  border-top: 1px solid #e5e7eb;
}

/* 图片 */
.markdown-body :deep(img) {
  max-width: 100%;
  border-radius: 4px;
}

/* ============================================================
   打字省略号动画
   ============================================================ */

.ai-typing-dots {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  margin-left: 2px;
  vertical-align: middle;
}

.ai-typing-dots .dot {
  display: inline-block;
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: #9ca3af;
  animation: typing-dot-bounce 1.4s infinite ease-in-out both;
}

.ai-typing-dots .dot:nth-child(1) { animation-delay: 0s; }
.ai-typing-dots .dot:nth-child(2) { animation-delay: 0.2s; }
.ai-typing-dots .dot:nth-child(3) { animation-delay: 0.4s; }

@keyframes typing-dot-bounce {
  0%, 80%, 100% { transform: scale(0.4); opacity: 0.3; }
  40% { transform: scale(1); opacity: 1; }
}

/* V4.5：过程状态文字——跟在打字点之后，与「在忙」动效共存（补充「在忙什么」） */
/*
 * V4.x：过程面板——过程状态 + 工作流步骤合并成一条链，可折叠回看。
 * 视觉对齐思考面板（.ai-thinking）：同族的边框 + 底色 + 圆角 + 折叠条。
 * 替代原来"跟在打字点后面的一行浅灰小字"（实测反馈："前端根本看不到他在干嘛"）。
 */
.ai-process {
  width: 100%;
  box-sizing: border-box;
  margin-top: 6px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #fafafa;
  overflow: hidden;
}

.ai-process__toggle {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  padding: 6px 10px;
  border: none;
  background: transparent;
  cursor: pointer;
  font-size: 12px;
  color: #6b7280;
}

.ai-process__toggle:hover {
  background: #f3f4f6;
}

.ai-process__icon {
  font-size: 13px;
}

.ai-process__title {
  flex: 1;
  text-align: left;
}

.ai-process__arrow {
  font-size: 10px;
  color: #9ca3af;
}

.ai-process__list {
  padding: 4px 10px 8px;
}

.ai-process__item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 2px 0;
  color: #6b7280;
  font-size: 13px;
}

.ai-process__status {
  flex: none;
  width: 14px;
  text-align: center;
  font-size: 12px;
}

.ai-process__text {
  flex: 1;
}

/* ============================================================
   游客次数用完引导卡片
   ============================================================ */

.ai-guest-limit-card {
  width: 100%;
  background: #fffbeb;
  border: 1px solid #fcd34d;
  border-radius: 10px;
  padding: 18px 16px;
  text-align: center;
  margin-bottom: 16px;
}

.ai-guest-limit-icon {
  font-size: 32px;
  display: block;
  margin-bottom: 6px;
}

.ai-guest-limit-text {
  margin: 0 0 4px;
  font-size: 14px;
  font-weight: 600;
  color: #92400e;
}

.ai-guest-limit-sub {
  margin: 0 0 12px;
  font-size: 13px;
  color: #a16207;
}

.ai-guest-limit-login {
  display: inline-block;
  padding: 5px 16px;
  border-radius: 6px;
  background: #2f6f73;
  color: #fff;
  font-size: 13px;
  font-weight: 500;
  text-decoration: none;
  transition: background 0.15s;
}

.ai-guest-limit-login:hover {
  background: #25595d;
}

/* ============================================================
   Resize handles（边缘缩放）
   ============================================================ */

.ai-resize-h {
  position: absolute;
  z-index: 10;
}

/* 四边 */
.resize-n { top: 0; left: 8px; right: 8px; height: 6px; cursor: ns-resize; }
.resize-s { bottom: 0; left: 8px; right: 8px; height: 6px; cursor: ns-resize; }
.resize-e { right: 0; top: 8px; bottom: 8px; width: 6px; cursor: ew-resize; }
.resize-w { left: 0; top: 8px; bottom: 8px; width: 6px; cursor: ew-resize; }

/* 四角 */
.resize-ne { top: 0; right: 0; width: 14px; height: 14px; cursor: nesw-resize; }
.resize-nw { top: 0; left: 0; width: 14px; height: 14px; cursor: nwse-resize; }
.resize-se { bottom: 0; right: 0; width: 14px; height: 14px; cursor: nwse-resize; }
.resize-sw { bottom: 0; left: 0; width: 14px; height: 14px; cursor: nesw-resize; }

/* ============================================================
   Memory popover
   ============================================================ */

:global(.ai-memory-modal) {
  border-radius: 12px;
  overflow: hidden;
  box-shadow: 0 18px 56px rgba(15, 23, 42, 0.18);
}

:global(.ai-memory-modal .n-card-header) {
  align-items: center;
  padding: 14px 18px 10px;
  border-bottom: 1px solid #eef0f2;
}

:global(.ai-memory-modal .n-card-header__main) {
  font-size: 15px;
  font-weight: 700;
  color: #1f2937;
}

:global(.ai-memory-modal .n-card-header__extra) {
  min-width: 0;
}

:global(.ai-memory-modal .n-card__content) {
  padding: 0 18px 18px;
}

.ai-memory-tabs {
  width: 168px;
}

:global(.ai-memory-tabs .n-tabs-nav) {
  line-height: 1;
}

.ai-memory-popover {
  max-height: 360px;
  overflow-y: auto;
  overscroll-behavior: contain;
}

.ai-memory-title {
  margin-bottom: 10px;
  font-size: 14px;
  font-weight: 700;
  color: #1f2937;
}

.ai-memory-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 160px;
  padding: 16px 4px;
  font-size: 13px;
  color: #6b7280;
  text-align: center;
}

.ai-memory-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.ai-memory-item {
  padding: 11px 12px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #f9fafb;
}

.ai-memory-item-head {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 6px;
}

.ai-memory-type {
  font-size: 12px;
  font-weight: 700;
  color: #2f6f73;
}

.ai-memory-key {
  margin-left: 6px;
  font-weight: 500;
  color: #7c8a95;
}

.ai-memory-importance {
  font-size: 12px;
  color: #6b7280;
}

.ai-memory-meta-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 7px;
  font-size: 12px;
}

.ai-memory-action {
  padding: 1px 6px;
  border-radius: 4px;
  border: 1px solid #d1d5db;
  color: #4b5563;
  background: #fff;
}

.ai-memory-action--create {
  border-color: #bfdbfe;
  color: #1d4ed8;
}

.ai-memory-action--update {
  border-color: #fde68a;
  color: #92400e;
}

.ai-memory-action--merge {
  border-color: #bbf7d0;
  color: #166534;
}

.ai-memory-action--ignore {
  border-color: #e5e7eb;
  color: #6b7280;
}

.ai-memory-target {
  color: #7c8a95;
}

.ai-memory-content {
  font-size: 13px;
  line-height: 1.5;
  color: #1f2937;
  word-break: break-word;
}

.ai-memory-edit-input {
  width: 100%;
  padding: 7px 8px;
  font-size: 13px;
  line-height: 1.5;
  color: #1f2937;
  border: 1.5px solid #3b82f6;
  border-radius: 6px;
  outline: none;
  resize: vertical;
  font-family: inherit;
  background: #fff;
}

.ai-memory-edit-input:focus {
  border-color: #2563eb;
  box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.12);
}

.ai-memory-merged {
  margin-top: 6px;
  padding: 7px 8px;
  border-left: 3px solid #86efac;
  background: #f0fdf4;
  font-size: 12px;
  line-height: 1.45;
  color: #166534;
  word-break: break-word;
}

.ai-memory-reason {
  margin-top: 6px;
  font-size: 12px;
  line-height: 1.4;
  color: #6b7280;
}

.ai-memory-actions {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
}

.ai-memory-tab-body {
  max-height: min(430px, calc(100vh - 220px));
  min-height: 180px;
  padding-top: 12px;
  overflow-y: auto;
  overscroll-behavior: contain;
}

.ai-memory-source {
  margin-top: 4px;
  font-size: 12px;
  color: #6b7280;
}

.ai-memory-date {
  font-size: 12px;
  color: #bbb;
  margin-right: auto;
}

/* ============================================================
   Workflow 面板
   ============================================================ */

.ai-workflow-panel {
  display: flex;
  flex-direction: column;
  gap: 10px;
  width: 100%;
}

.ai-workflow-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

/* 学习规划 Workflow：结构化计划渲染 */
.ai-learning-plan {
  max-height: 220px;
  overflow-y: auto;
  padding: 10px 12px;
  border: 1px solid var(--ai-border, #e5e7eb);
  border-radius: 8px;
  background: var(--ai-bg-soft, #f8fafc);
}

.ai-learning-plan-title {
  margin: 0 0 8px;
  font-size: 14px;
  font-weight: 600;
}

.ai-learning-plan-stage-title {
  margin: 8px 0 4px;
  font-size: 13px;
  font-weight: 500;
  color: var(--ai-text, #374151);
}

.ai-learning-plan-tasks {
  margin: 0;
  padding-left: 18px;
  font-size: 12.5px;
  color: var(--ai-text-secondary, #6b7280);
}

.ai-learning-plan-tasks li {
  margin: 2px 0;
}

.ai-workflow-badge {
  font-size: 12px;
  font-weight: 700;
  color: #2563eb;
}

.ai-workflow-status {
  font-size: 12px;
  color: #6b7280;
}

.ai-workflow-status--bad {
  color: #dc2626;
  font-weight: 600;
}

.ai-workflow-error {
  padding: 8px 10px;
  border: 1px solid #fecaca;
  border-radius: 6px;
  background: #fef2f2;
  color: #991b1b;
  font-size: 13px;
  line-height: 1.5;
  word-break: break-word;
}

.ai-workflow-actions,
.ai-workflow-reject-actions {
  display: flex;
  gap: 8px;
}

.ai-workflow-btn {
  min-width: 72px;
  height: 34px;
  padding: 0 12px;
  border-radius: 6px;
  border: 1px solid transparent;
  font-size: 13px;
  cursor: pointer;
}

.ai-workflow-btn--ok {
  background: #2563eb;
  color: #fff;
}

.ai-workflow-btn--no {
  background: #f3f4f6;
  color: #111827;
  border-color: #d1d5db;
}

.ai-workflow-btn--ghost {
  background: #fff;
  border-color: #d1d5db;
  color: #374151;
}

.ai-workflow-btn--primary {
  background: #dc2626;
  color: #fff;
}

.ai-workflow-feedback {
  width: 100%;
  min-height: 64px;
  padding: 8px 10px;
  border: 1px solid #d1d5db;
  border-radius: 6px;
  resize: vertical;
  font: inherit;
}

/* ---- 过渡面板 ---- */

.ai-workflow-panel--transition {
  flex-direction: row;
  align-items: center;
  justify-content: center;
  gap: 10px;
  min-height: 44px;
  padding: 12px 0;
}

.ai-workflow-panel--transition :deep(.n-spin-container) {
  display: inline-flex;
  align-items: center;
  flex: 0 0 auto;
}

.ai-workflow-transition-label {
  font-size: 14px;
  line-height: 20px;
  color: #4b5563;
}

/* ============================================================
   Workflow 消息卡片
   ============================================================ */

/* Agent 思考过程（V2.3）：折叠条 + 步骤列表，与建议卡同风格 */
/* 第三刀：任务步骤（外层任务层）——与"思考过程"是两个层级，视觉上也要分开 */
.ai-task {
  margin: 8px 0;
  padding: 8px 10px;
  border-radius: 8px;
  background: rgba(128, 128, 128, 0.08);
  font-size: 13px;
}

.ai-task__title {
  font-weight: 500;
  margin-bottom: 4px;
  opacity: 0.75;
}

.ai-task__item {
  padding: 2px 0;
  line-height: 1.6;
}

/* 第四步：一行任务 + 它自己的内部明细（子 run 的 ReAct 步骤） */
.ai-task__row {
  display: flex;
  gap: 6px;
}

.ai-task__inner {
  margin: 2px 0 4px 22px;
  padding-left: 8px;
  border-left: 2px solid #e5e7eb;
  font-size: 12px;
  opacity: 0.75;
}

.ai-task__inner-item {
  display: flex;
  gap: 6px;
  line-height: 1.5;
}

.ai-task__inner-no {
  flex: none;
  min-width: 14px;
  opacity: 0.6;
}

.ai-task__inner-text {
  word-break: break-word;
}

.ai-task__icon {
  flex: none;
}

.ai-task__text {
  word-break: break-word;
}

.ai-thinking {
  width: 100%;
  box-sizing: border-box;
  margin-top: 6px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #fafafa;
  overflow: hidden;
}

.ai-thinking__toggle {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  padding: 6px 10px;
  border: none;
  background: transparent;
  cursor: pointer;
  font-size: 12px;
  color: #6b7280;
}

.ai-thinking__toggle:hover {
  background: #f3f4f6;
}

.ai-thinking__icon {
  font-size: 13px;
}

.ai-thinking__title {
  flex: 1;
  text-align: left;
}

.ai-thinking__arrow {
  font-size: 10px;
  color: #9ca3af;
}

.ai-thinking__list {
  display: flex;
  flex-direction: column;
  border-top: 1px solid #f0f0f0;
  padding: 4px 0;
}

/* V3.13：计划块（run 级）——排在步骤列表之前，与步骤列表用虚线分隔 */
.ai-thinking__plan {
  padding: 6px 12px 8px;
  border-bottom: 1px dashed #e5e7eb;
}

.ai-thinking__plan-title {
  font-size: 12px;
  color: #9ca3af;
  margin-bottom: 4px;
}

.ai-thinking__plan-item {
  font-size: 12px;
  line-height: 1.6;
  color: #374151;
  word-break: break-all;
}

.ai-thinking__plan-no {
  color: #9ca3af;
  margin-right: 4px;
  font-variant-numeric: tabular-nums;
}

.ai-thinking__step {
  display: flex;
  align-items: baseline;
  gap: 6px;
  padding: 4px 12px;
  font-size: 12px;
  line-height: 1.6;
}

.ai-thinking__step-no {
  color: #9ca3af;
  font-variant-numeric: tabular-nums;
  flex: 0 0 auto;
}

.ai-thinking__step-status {
  flex: 0 0 auto;
  font-size: 11px;
}

.ai-thinking__step-status--running {
  color: #2563eb;
}

.ai-thinking__step-status--success {
  color: #16a34a;
}

.ai-thinking__step-status--failed {
  color: #dc2626;
}

.ai-thinking__step-status--skipped {
  color: #6b7280;
}

.ai-thinking__step-message {
  color: #4b5563;
  word-break: break-all;
}

/* Agent 写动作确认卡（V2.4）：与建议卡同风格 */
.ai-write-card {
  width: 100%;
  box-sizing: border-box;
  margin-top: 8px;
  border: 1px dashed #fcd34d;
  border-radius: 10px;
  background: #fffbeb;
  padding: 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.ai-write-card__head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.ai-write-card__badge {
  font-size: 13px;
  font-weight: 700;
  color: #b45309;
}

.ai-write-card__type {
  font-size: 12px;
  color: #b45309;
  background: #fef3c7;
  border-radius: 4px;
  padding: 1px 8px;
}

.ai-write-card__reason {
  font-size: 13px;
  color: #374151;
  line-height: 1.6;
}

.ai-write-card__actions {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
}

/* Agent Workflow 建议卡（V2.1）：Agent 建议 + 用户确认，与 Workflow 卡同风格 */
.ai-suggestion-card {
  width: 100%;
  box-sizing: border-box;
  margin-top: 8px;
  border: 1px dashed #93c5fd;
  border-radius: 10px;
  background: #f5f9ff;
  padding: 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.ai-suggestion-card__head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.ai-suggestion-card__badge {
  font-size: 13px;
  font-weight: 700;
  color: #1d4ed8;
}

.ai-suggestion-card__type {
  font-size: 12px;
  color: #2563eb;
  background: #dbeafe;
  border-radius: 4px;
  padding: 1px 8px;
}

.ai-suggestion-card__reason {
  font-size: 13px;
  color: #374151;
  line-height: 1.6;
}

.ai-suggestion-card__actions {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
}

.ai-workflow-card {
  width: 100%;
  box-sizing: border-box;
  margin-top: 8px;
  border: 1px solid #bfdbfe;
  border-radius: 10px;
  background: #f0f7ff;
  overflow: hidden;
}

.ai-workflow-card__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 10px 12px;
  border-bottom: 1px solid #bfdbfe;
  background: #e8f2fe;
}

.ai-workflow-card__badge {
  font-size: 13px;
  font-weight: 700;
  color: #1d4ed8;
}

.ai-workflow-card__status {
  font-size: 12px;
  color: #4b5563;
}

.ai-workflow-card__status--loading {
  display: inline-flex;
  align-items: center;
  justify-content: flex-end;
  gap: 6px;
  min-height: 18px;
  white-space: nowrap;
  line-height: 1.4;
}

.ai-workflow-card__status--loading :deep(.n-spin-container) {
  display: inline-flex;
  align-items: center;
  flex: 0 0 auto;
}

.ai-workflow-card__body {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px 12px;
}

.ai-workflow-card__label {
  display: inline-block;
  min-width: 56px;
  margin-right: 8px;
  font-size: 12px;
  font-weight: 600;
  color: #6b7280;
}

.ai-workflow-card__step,
.ai-workflow-card__topic {
  font-size: 13px;
  color: #1f2937;
}

.ai-workflow-card__section-title {
  margin: 6px 0 -2px;
  padding-left: 8px;
  border-left: 3px solid #2563eb;
  font-size: 13px;
  font-weight: 700;
  line-height: 1.4;
  color: #2563eb;
}

.ai-workflow-card__outline {
  margin: 0;
  padding: 10px 12px;
  border-radius: 6px;
  background: #fff;
  font-family: inherit;
  font-size: 13px;
  line-height: 1.6;
  color: #374151;
  white-space: pre-wrap;
  word-break: break-word;
  /* 流式增长时在内部滚动，避免撑高外层消息列表把滚动条推到底部 */
  max-height: 260px;
  overflow: auto;
}

.ai-workflow-card__stream-content {
  margin: 0;
  padding: 10px 12px;
  border-radius: 6px;
  background: #fff;
  font-family: inherit;
  font-size: 13px;
  line-height: 1.6;
  color: #374151;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 260px;
  overflow: auto;
}

.ai-workflow-card__summary p {
  margin: 4px 0 0;
  font-size: 13px;
  line-height: 1.5;
  color: #374151;
}

.ai-workflow-card__quality {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px 12px;
  border-top: 1px solid #bfdbfe;
  background: #fff;
}

.ai-workflow-card__quality--good {
  color: #059669;
  font-size: 13px;
  font-weight: 600;
}

.ai-workflow-card__quality--bad {
  color: #dc2626;
  font-size: 13px;
  font-weight: 600;
}

.ai-workflow-card__quality-block {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.ai-workflow-card__quality-title {
  font-size: 12px;
  font-weight: 600;
  color: #6b7280;
}

.ai-workflow-card__quality-list {
  margin: 0;
  padding-left: 18px;
  font-size: 13px;
  line-height: 1.5;
  color: #374151;
}

/* ---- Workflow 步骤日志折叠区 ---- */

.ai-workflow-step-log {
  border-top: 1px solid #bfdbfe;
  background: #fff;
}

.ai-workflow-step-log__toggle {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  padding: 9px 12px;
  border: 0;
  background: transparent;
  color: #1d4ed8;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
}

.ai-workflow-step-log__body {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 0 12px 12px;
}

.ai-workflow-step-log__empty {
  padding: 8px 0;
  color: #6b7280;
  font-size: 12px;
}

.ai-workflow-step-log__item {
  padding: 8px;
  border: 1px solid #e5e7eb;
  border-radius: 6px;
  background: #f9fafb;
}

.ai-workflow-step-log__head,
.ai-workflow-step-log__meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.ai-workflow-step-log__step {
  font-size: 12px;
  font-weight: 700;
  color: #111827;
}

.ai-workflow-step-log__type {
  padding: 0 6px;
  font-size: 11px;
  line-height: 16px;
  border-radius: 3px;
}

.ai-workflow-step-log__type.is-step {
  color: #2563eb;
  background: #eff6ff;
}

.ai-workflow-step-log__type.is-operation {
  color: #7c3aed;
  background: #f5f3ff;
}

.ai-workflow-step-log__status {
  font-size: 12px;
  font-weight: 600;
}

.ai-workflow-step-log__status--success {
  color: #059669;
}

.ai-workflow-step-log__status--failed {
  color: #dc2626;
}

.ai-workflow-step-log__status--running {
  color: #2563eb;
}

.ai-workflow-step-log__status--skipped {
  color: #6b7280;
}

.ai-workflow-step-log__meta {
  margin-top: 4px;
  justify-content: flex-start;
  flex-wrap: wrap;
  color: #6b7280;
  font-size: 11px;
}

.ai-workflow-step-log__summary {
  margin-top: 6px;
  color: #374151;
  font-size: 12px;
  line-height: 1.5;
  word-break: break-word;
}

.ai-workflow-step-log__error {
  margin-top: 6px;
  padding: 6px 8px;
  border-radius: 5px;
  background: #fef2f2;
  color: #991b1b;
  font-size: 12px;
  line-height: 1.5;
  word-break: break-word;
}

/* ============================================================
   Responsive
   ============================================================ */

@media (max-width: 480px) {
  .ai-panel {
    width: calc(100vw - 24px);
    height: 480px;
    right: 0;
  }

  .ai-assistant {
    right: 12px;
    bottom: 80px;
  }

  .ai-assistant.fullscreen .ai-panel {
    width: 100vw;
    height: 100vh;
    border-radius: 0;
  }
}
</style>
