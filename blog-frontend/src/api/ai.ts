import request, { readAuthToken } from '@/utils/request'
import type { PageData } from '@/types/result'

// ============================================================
// 前端统一类型（role 用 'ai' 而不是后端的 'assistant'）
// ============================================================

export interface ArticleRagReference {
  articleId: number
  title: string
  chunkIndex: number
  snippet: string
}

export interface AiMemoryCandidate {
  id: string
  memoryType: 'PROFILE' | 'PREFERENCE' | 'PROJECT_STATE'
  memoryKey: string
  content: string
  candidateAction: 'CREATE' | 'UPDATE' | 'MERGE' | 'IGNORE'
  reason?: string
  decisionReason?: string
  mergedContent?: string
  targetMemoryId?: string
  confidence?: number
  importance?: number
  status: 'PENDING' | 'CONFIRMED' | 'REJECTED'
  createdAt: string
}

export interface AiMemory {
  id: string
  memoryType: 'PROFILE' | 'PREFERENCE' | 'PROJECT_STATE'
  memoryKey: string
  content: string
  source: string
  confidence?: number
  importance?: number
  enabled: number
  createdAt: string
  updatedAt: string
}

export interface AiEpisodicMemory {
  id: string
  projectKey: string
  memoryType: 'DECISION' | 'EVENT' | 'MILESTONE' | 'PLAN'
  title: string
  content: string
  importance?: number
  confidence?: number
  sessionId?: string
  occurredAt?: string
  createdAt: string
  updatedAt: string
}

export interface AiConversationSummaryStatus {
  compressing: boolean
  lastCompressedAt: string | null
  coveredMessageCount: number
  eligible: boolean
}

export interface AiSession {
  id: string
  title: string
  createdAt: string
  updatedAt: string
  activeWorkflowRunId?: string
}

export interface AiMessage {
  id: string
  sessionId: string
  role: 'user' | 'ai'
  content: string
  pageContext?: string
  createdAt: string
  references?: ArticleRagReference[]
  workflowRunId?: string
  workflow?: AiWorkflowRun
  /** V2.1：关联的 Agent Run（建议卡快照恢复用） */
  agentRunId?: string
  /** V2.3：Agent 思考步骤（实时 AGENT_STEP 事件累计 / 历史补拉挂回） */
  thinkingSteps?: AgentStepView[]
  /** V3.13：Plan Preview 计划（实时 AGENT_PLAN 事件归并 / run 详情补拉挂回，run 级） */
  plan?: string[]
  /**
   * 第三刀：任务步骤（外层任务层）。
   *
   * 与 thinkingSteps 分属两个层级——这里是"用户要的这件事完成得怎么样"，
   * thinkingSteps 是"为做成它 AI 内部查了什么"。混在一起用户会分不清哪些是他要的、
   * 哪些是过程。
   */
  taskSteps?: TaskStepEvent[]
  /**
   * V4.x：过程步骤（过程状态 + 工作流步骤合并到一条链）。
   * 流式结束后由前端从 liveChatSteps 落到消息上，供展开回看——展示"AI 这一轮都做了什么"。
   */
  processSteps?: string[]
  /** V2.1：Agent 待确认的 Workflow 建议（STOP event 透出 / 历史补拉挂回） */
  workflowSuggestion?: WorkflowSuggestion
  /** V2.4：Agent 待确认的写动作提案（STOP event 透出 / 历史补拉挂回） */
  writeAction?: AgentWriteProposal
  /** V2.4 前端临时状态：写动作卡交互态（pending/processing/confirmed/cancelled/expired） */
  writeActionState?: 'pending' | 'processing' | 'confirmed' | 'cancelled' | 'expired'
  /** V2.1 前端临时状态：suggestion 卡片交互态（pending/processing/confirmed/cancelled/expired） */
  suggestionState?: 'pending' | 'processing' | 'confirmed' | 'cancelled' | 'expired'
  /** 前端临时状态：该消息刚被复制过（按钮短暂显示 ✔） */
  copied?: boolean
}

/** Agent 写动作提案（V2.4 / V3.1 / V3.3 / V3.4）：LLM 只给标题类信息，索引/定位由后端匹配 */
export interface AgentWriteProposal {
  /**
   * 写动作类型：
   * UPDATE_TASK_DONE=勾选/取消勾选学习任务（done 生效）；
   * ADD_LEARNING_TASK=追加学习任务（done 忽略）；
   * UPDATE_LEARNING_TASK=重命名学习任务（taskTitle=旧名 + newTitle=新名，done 忽略）；
   * UPDATE_ARTICLE_TITLE=改自己文章标题（V3.4，articleId/articleTitle 为文章域锚，taskTitle/planRef/stageTitle 为 null，done 恒 false）；
   * HIDE_ARTICLE / PUBLISH_ARTICLE=隐藏/公开自己文章（V3.7，前置状态由动作推导，articleId + articleTitle 展示用）；
   * RECHARGE_WALLET=给钱包充值（2026-09-21，payYuan 为金额，其余字段为 null）
   */
  actionType: 'UPDATE_TASK_DONE' | 'ADD_LEARNING_TASK' | 'UPDATE_LEARNING_TASK' | 'UPDATE_ARTICLE_TITLE' | 'HIDE_ARTICLE' | 'PUBLISH_ARTICLE' | 'RECHARGE_WALLET'
  planRef?: string
  stageTitle?: string
  taskTitle?: string
  newTitle?: string
  done: boolean
  /** V3.4 文章域：目标文章 ID（页面上下文锚）与提案时刻 DB 权威旧标题（并发防护锚） */
  articleId?: string
  articleTitle?: string
  /**
   * 钱包域：充值金额（**元**，整数串）。
   *
   * ⚠️ 单位是「元」不是「分」——项目里 `payAmount` 一律是分，别混。
   * 到账额度**不在这里**：它是兑换比例派生的，由后端执行时算，卡片上只做估算。
   */
  payYuan?: string
}

/**
 * 写动作确认结果（2026-10-19）。
 *
 * 钱包域充值只做到「创建订单 + 发起支付」——AI 不替用户确认付款，
 * 前端必须靠 `result.orderNo / payToken` 打开支付窗口，由用户点确认。
 * 其他域的写动作没有前端后续动作，忽略 `result` 即可。
 */
export interface AgentWriteActionConfirmView {
  message: string
  actionType: AgentWriteProposal['actionType']
  /** 执行器载荷：钱包域含 orderNo / paymentNo / payToken / payAmount / creditAmount / expireAt / orderStatus */
  result?: Record<string, unknown> | null
}

/** Agent 思考步骤（V2.3 / V3.10）：status = RUNNING / SUCCESS / FAILED / SKIPPED */export interface AgentStepView {
  stepNo: number
  actionType: string
  status: 'RUNNING' | 'SUCCESS' | 'FAILED' | 'SKIPPED'
  message: string
  /** V3.10：思考摘要（可空，行文本优先于 message） */
  thoughtSummary?: string | null
}

/** V2.2 后端 AgentStepVO 结构（历史恢复时映射为 AgentStepView） */
export interface AgentStepHistoryItem {
  stepNo: number
  actionType: string
  status: string
  /** 展示文案（与实时 AGENT_STEP 事件一致，刷新前后不串味） */
  message?: string | null
  /** V3.10：思考摘要（与实时 AGENT_STEP 事件一致，可空） */
  thoughtSummary?: string | null
  summary?: string | null
  errorMessage?: string | null
  durationMs?: number | null
  createdAt?: string
  /**
   * 第三刀：步骤层级（AGENT=思考步骤 / TASK=外层任务步骤）。
   *
   * 两层都落在同一张 step 表，历史恢复时靠它分流——否则任务步骤会被当成思考步骤渲染，
   * 而它没有思考文案，会显示成默认占位（刷新后看起来像"变成了别的东西"）。
   */
  kind?: string | null
  /**
   * 第四步：该任务步骤由**子 run** 承接时给出子 run id（否则 null）。
   *
   * 刷新恢复靠它二次补拉内部明细——子 run 的步骤属于子 run 自己，
   * 不在外层 run 的 steps 列表里。
   */
  childRunId?: string | null
  /** TASK_STEP 的具体类型与用户原话目标；历史恢复时用于还原任务行语义。 */
  type?: string | null
  goalEvidence?: string[] | null
}

/** V3.13：后端 AgentRunDetailVO（run 级；plan 仅文章域可能非空） */
export interface AgentRunDetail {
  id: string
  sessionId?: string | null
  goal?: string | null
  status?: string | null
  currentStep?: number | null
  usedSteps?: number | null
  maxSteps?: number | null
  finalAnswer?: string | null
  errorMessage?: string | null
  createdAt?: string
  updatedAt?: string
  /** V3.13：Plan Preview 计划（可空） */
  plan?: string[] | null
}

/** V2.2：后端 AgentRunSummaryVO（run 列表项） */
export interface AgentRunSummaryItem {
  id: string
  status?: string | null
  goal?: string | null
  usedSteps?: number | null
  maxSteps?: number | null
  createdAt?: string
  updatedAt?: string
}

/** V4 第一刀：后端 AgentStepRawVO（开发者通道，含原始 inputJson / outputJson） */
export interface AgentStepRawItem {
  stepNo: number
  actionType: string
  status: string
  errorMessage?: string | null
  durationMs?: number | null
  /** 决策输入原始 JSON（用户侧接口刻意不返回） */
  inputJson?: string | null
  /** 动作结果原始 JSON */
  outputJson?: string | null
  thoughtSummary?: string | null
  createdAt?: string
  /** P0-b：本步决策（decide / repair）的 LLM token 用量；只读动作执行本身不调 LLM */
  inputTokens?: number | null
  outputTokens?: number | null
}

/** V4 第一刀：后端 AgentRunDevDetailVO（安全摘要 + 未清洗 contextJson） */
export interface AgentRunDevDetail {
  run: AgentRunDetail
  /** 原始 observations 序列化（含文章正文片段等模型可见材料，未清洗） */
  contextJson?: string | null
  /**
   * P0-b：run 级 Token（本 run 直接发起的 LLM 调用）。
   * 放在外层而非 `run` 里——`run` 与用户侧接口同源，不承载内部成本。
   * P0 没有子 run，totalTokens 即子树总量；P1 接入父子 run 后补 local / subtree 派生值。
   */
  inputTokens?: number | null
  outputTokens?: number | null
  totalTokens?: number | null
  /** 当前 run 自己直接消耗的 Token。 */
  localTokens?: number | null
  /** 当前 run 与直接子 run 的 Token 合计。 */
  subtreeTokens?: number | null
}

/** Agent 建议的 Workflow（V2.1）：确认后才由后端启动，Agent 无法直接启动 */
export interface WorkflowSuggestion {
  workflowType: WorkflowType
  reason: string
  initialMessage?: string
  risk?: 'LOW' | 'MEDIUM' | 'HIGH'
  /** V2.5 文章侧建议：目标文章 ID（仅 OPTIMIZE_ARTICLE 建议携带，前端只透传不渲染） */
  articleId?: string
}

export interface NavigateCommand {
  target: string
  param?: string
}

export interface EditorAction {
  type: 'fillArticle' | 'saveDraft' | 'publish'
  title?: string
  categoryName?: string
  summary?: string
  content?: string
  articleId?: number
}

export interface ArticleAction {
  type:
    | 'likeArticle'
    | 'unlikeArticle'
    | 'favoriteArticle'
    | 'unfavoriteArticle'
    | 'commentArticle'
    | 'scrollToComments'
    | 'copyArticleLink'
    | 'followAuthor'
    | 'unfollowAuthor'
    | 'scrollToTop'
  articleId?: string
  content?: string
}

/** 后端文章交互批次的单项权威结果。前端只消费并刷新，不据此再次发起写请求。 */
export interface ArticleInteractionResult {
  actionType: 'LIKE_ARTICLE' | 'UNLIKE_ARTICLE' | 'FAVORITE_ARTICLE' | 'UNFAVORITE_ARTICLE' | 'FOLLOW_AUTHOR' | 'UNFOLLOW_AUTHOR'
  outcome: 'SUCCESS' | 'FAILED'
  resultCode: 'APPLIED' | 'ALREADY_SATISFIED' | 'TARGET_NOT_FOUND' | 'FORBIDDEN' | 'CONFLICT' | 'INTERNAL_ERROR'
  summary: string
  before?: boolean | null
  after?: boolean | null
}

// ============================================================
// Workflow 类型
// ============================================================

export type WorkflowStatus =
  | 'RUNNING'
  | 'WAITING_REQUIREMENT_CONFIRM'
  | 'WAITING_OUTLINE_CONFIRM'
  | 'WAITING_DRAFT_CONFIRM'
  | 'WAITING_PLAN_CONFIRM'
  | 'WAITING_LEARNING_PLAN_CONFIRM'
  | 'WAITING_FILL_CONFIRM'
  | 'WAITING_USER_SAVE'
  | 'PAUSED'
  | 'COMPLETED'
  | 'FAILED'
  | 'CANCELLED'

export type WorkflowType = 'CREATE_ARTICLE' | 'OPTIMIZE_ARTICLE' | 'LEARNING_PLAN' | 'LEARNING_PROGRESS' | 'LEARNING_ASSIST'

export type WorkflowStep =
  | 'REQUIREMENT_ANALYZE'
  | 'MEMORY_RETRIEVE'
  | 'RAG_SEARCH'
  | 'GENERATE_OUTLINE'
  | 'GENERATE_DRAFT'
  | 'QUALITY_CHECK'
  | 'FILL_ARTICLE'
  | 'LOAD_ARTICLE'
  | 'ANALYZE_ARTICLE'
  | 'GENERATE_OPTIMIZATION_PLAN'
  | 'REWRITE_ARTICLE'
  | 'CONTENT_CHECK'
  | 'ANALYZE_GOAL'
  | 'GENERATE_PLAN'
  | 'SAVE_PLAN'
  | 'LOAD_PLAN'
  | 'ANALYZE_CHANGE'
  | 'LOCATE_STAGE'
  | 'GENERATE_TASKS'
  | 'APPEND_TASKS'

export interface WorkflowFeedbackItem {
  time: string
  step: WorkflowStep
  status: WorkflowStatus
  userFeedback: string
}

/**
 * 确认卡片：Workflow 停在等待确认态时由后端写入。
 * type 决定渲染哪种确认面板（与 status 解耦，前端只按 type 渲染）。
 */
export interface WorkflowConfirmation {
  type: 'REQUIREMENT' | 'OUTLINE' | 'DRAFT' | 'PLAN' | 'LEARNING_PLAN' | 'FILL'
  step?: WorkflowStep
  question?: string
}

export interface CreateArticleWorkflowContext {
  workflowVersion: string
  variables: {
    articleType?: string
    language?: string
  }
  requirement?: {
    topic?: string
    type?: string
    keywords?: string[]
  }
  confirmation?: WorkflowConfirmation
  memoryContext?: string
  ragReferences?: ArticleRagReference[]
  // 以下三个字段为历史数据位置（早期版本放 context 顶层），新数据统一在 stepResults
  outline?: string
  draft?: {
    title?: string
    summary?: string
    content?: string
    tags?: string[]
  }
  qualityCheck?: {
    passed?: boolean
    issues?: string[]
    suggestions?: string[]
  }
  stepResults?: {
    outline?: string
    draft?: {
      title?: string
      summary?: string
      content?: string
      tags?: string[]
    }
    qualityCheck?: {
      passed?: boolean
      issues?: string[]
      suggestions?: string[]
    }
  }
  feedbackHistory?: WorkflowFeedbackItem[]
}

export interface OptimizeArticleWorkflowContext {
  workflowVersion: string
  confirmation?: WorkflowConfirmation
  input?: {
    articleId?: number
    instruction?: string
  }
  memoryContext?: string
  ragContext?: {
    references?: ArticleRagReference[]
  }
  stepResults?: {
    article?: {
      id?: number
      title?: string
      summary?: string
      content?: string
      categoryId?: number
      status?: number
    }
    analysis?: {
      contentLength?: number
      paragraphCount?: number
      codeBlockCount?: number
      imageCount?: number
      issues?: string[]
    }
    optimizationPlan?: string
    optimizedContent?: string
    contentCheck?: {
      passed?: boolean
      issues?: string[]
      suggestions?: string[]
    }
  }
  feedbackHistory?: WorkflowFeedbackItem[]
}

export interface AiWorkflowRun {
  id: string
  workflowType: WorkflowType
  workflowVersion: string
  status: WorkflowStatus
  currentStep?: WorkflowStep
  context: CreateArticleWorkflowContext & OptimizeArticleWorkflowContext & LearningPlanWorkflowContext
  editorAction?: EditorAction
  pauseReason?: string
  errorMessage?: string
}

export interface LearningPlanWorkflowContext {
  confirmation?: WorkflowConfirmation
  planTitle?: string  //难点攻坚：目标计划标题
  targetStageTitle?: string  //难点攻坚：定位到的阶段标题
  input?: {
    goal?: string
  }
  stepResults?: {
    plan?: {
      title?: string
      explanation?: string  //难点攻坚：难点讲解文本
      stages?: Array<{
        title?: string
        tasks?: Array<string | { title?: string }>
      }>
    }
    qualityCheck?: {
      passed?: boolean
      issues?: string[]
      suggestions?: string[]
    }
  }
}

export interface AiWorkflowStepLog {
  id: string
  workflowRunId: string
  /** 日志类型：OPERATION=操作级（确认/反馈/重试）/ STEP=步骤级（runStep 每步） */
  logType?: 'OPERATION' | 'STEP' | string
  stepOrder: number
  step: WorkflowStep | string
  status: 'RUNNING' | 'SUCCESS' | 'FAILED' | 'SKIPPED' | string
  retryCount?: number
  inputSummary?: string
  outputSummary?: string
  errorMessage?: string
  metadataJson?: string
  startedAt: string
  endedAt?: string
  durationMs?: number
  inputTokens?: number
  outputTokens?: number
  createdAt: string
}

export interface AiChatResult {
  session: AiSession
  userMessage: AiMessage
  assistantMessage: AiMessage
  navigate?: NavigateCommand
  editorAction?: EditorAction
  articleAction?: ArticleAction
}

export interface PageContext {
  pageType?: string
  path?: string
  articleId?: string
  articleTitle?: string
  userId?: string
}

export interface StreamCallbacks {
  onParam: (session: AiSession, userMessage: AiMessage) => void
  onData: (chunk: string) => Promise<void> | void
  onWorkflowStep?: (event: WorkflowStepEvent) => Promise<void> | void
  onWorkflowContentDelta?: (event: WorkflowContentDeltaEvent) => Promise<void> | void
  /** V2.3：Agent 思考步骤事件（实时） */
  onAgentStep?: (event: AgentStepEvent) => Promise<void> | void
  /** V3.13：Agent 计划事件（run 级，恒在首个 AGENT_STEP 之前到达） */
  onAgentPlan?: (event: AgentPlanEvent) => Promise<void> | void
  /** 第三刀：任务计划（外层，先于任何 onTaskStep 到达） */
  onTaskPlan?: (event: TaskPlanEvent) => Promise<void> | void
  /** 第三刀：任务步骤（外层，每步结束推一次） */
  onTaskStep?: (event: TaskStepEvent) => Promise<void> | void
  /** V4.5：聊天 / QA 过程状态（可能早于 PARAM 到达——按占位消息索引归并，不依赖消息 ID） */
  onChatStatus?: (event: ChatStatusEvent) => Promise<void> | void
  onStop: (
    session: AiSession,
    assistantMessage: AiMessage,
    navigate?: NavigateCommand,
    editorAction?: EditorAction,
    articleAction?: ArticleAction,
    references?: ArticleRagReference[],
    workflow?: AiWorkflowRun,
    workflowSuggestion?: WorkflowSuggestion,
    writeAction?: AgentWriteProposal,
    actionResults?: ArticleInteractionResult[],
  ) => void
  onError: (error: Error) => void
  /** 用户主动停止生成，前端自行处理（保留已输出内容） */
  onAbort?: () => void
}

// ============================================================
// Workflow 流式事件
// ============================================================

/**
 * Agent 计划事件（V3.13 Plan Preview，SSE 实时推送，run 级）。
 *
 * **本事件早于 STOP**——那时最终消息尚未落库，占位消息还没有 agentRunId，
 * 所以前端只能按**当前流的占位消息索引**归并；按 agentRunId 匹配会把它丢掉
 * （症状：刷新后才看得到计划，实时看不到）。
 */
export interface AgentPlanEvent {
  agentRunId?: string | null
  plan: string[]
}

/** Agent 思考步骤事件（V2.3，SSE 实时推送；V3.10 加 thoughtSummary） */
/**
 * 第三刀：任务计划事件（外层任务层）。
 *
 * 与 AGENT_PLAN（文章 Agent 的自然语言计划预览）不是一回事——这里列的是
 * 「用户要完成哪几件事」，每项对应一个可执行步骤。
 */
export interface TaskPlanEvent {
  goal: string
  steps: Array<{ stepId: string; type: string; goalEvidence?: string[] }>
}

/**
 * 第三刀：任务步骤事件（外层任务层）。
 *
 * 与 AGENT_STEP 分属两个层级：TASK_STEP 是「用户要的这件事」，
 * AGENT_STEP 是「为做成它，Agent 内部做了哪些查询和判断」。
 * 前端把任务步骤当外层、思考过程当子层展示。
 */
export interface TaskStepEvent {
  stepId: string
  type: string
  state: string
  code: string
  summary: string
  /** 用户原话中的目标片段；失败时与结果一起显示，避免只剩通用错误文案。 */
  goalEvidence?: string[]
  /** 执行器的结构化结果：写动作结果或客户端命令等，不由文案反推 */
  output?: Record<string, any>
  /**
   * 第四步：该任务步骤的**内部明细**（子 run 的思考步骤）。
   *
   * 两条来源：实时 AGENT_STEP 事件（带 taskStepId）、刷新后按 childRunId 补拉。
   * 与 message.thinkingSteps 是两个层级——它是某一行的展开，不是整条消息的过程。
   */
  innerSteps?: AgentStepView[]
}

export interface AgentStepEvent {
  stepNo: number
  actionType: string
  status: 'RUNNING' | 'SUCCESS' | 'FAILED' | 'SKIPPED'
  message: string
  /** V3.10：思考摘要（清洗后，可空——行文本优先于 message，防空串用 trim 兜底） */
  thoughtSummary?: string | null
  /**
   * 第四步：该步骤属于哪个**外层任务步骤**（只有子 run 的内部事件才带）。
   *
   * 带了 = 它是某个任务步骤的展开明细，应挂到对应 taskSteps[].innerSteps；
   * 没带 = 普通 Agent 循环步骤，照旧进 message.thinkingSteps。
   */
  taskStepId?: string | null
}

/**
 * V4.5：聊天 / QA 轻量过程状态事件（4001）。
 *
 * 瞬态 UI 状态，**不持久化**；可能**早于 PARAM** 到达（PARAM 要等 userMessage 落库），
 * 所以前端按「当前流的 AI 占位消息索引」归并，不依赖任何消息 ID。
 */
export interface ChatStatusEvent {
  /** 过程状态类型；起步只放 CURRENT_ARTICLE_RESOLVING / ARTICLE_SEARCHING 两个 */
  statusType: string
  retrievalMode: string
  /** 可直接展示的文案（服务端给，前端只做防抖，不自己拼文案） */
  message: string
}

export interface WorkflowStepEvent {
  workflowRunId: string
  workflowType?: WorkflowType
  action?: string
  step?: string
  status: string
  message?: string
  /** 仅 SUCCESS / FAILED 事件带：本步耗时（RUNNING 时还没有） */
  durationMs?: number
  /** 仅 SUCCESS / FAILED 事件带：本步 LLM 输入 token */
  inputTokens?: number
  /** 仅 SUCCESS / FAILED 事件带：本步 LLM 输出 token */
  outputTokens?: number
}

export interface WorkflowContentDeltaEvent {
  workflowRunId: string
  step: string
  field: string
  delta: string
}

export interface WorkflowStreamResult {
  workflow?: AiWorkflowRun
  stepLogs?: AiWorkflowStepLog[]
  editorAction?: EditorAction
  message?: string
}

export interface WorkflowStreamCallbacks {
  onStep?: (event: WorkflowStepEvent) => Promise<void> | void
  onContentDelta?: (event: WorkflowContentDeltaEvent) => Promise<void> | void
  onStop?: (data: WorkflowStreamResult) => Promise<void> | void
  onWorkflowError?: (data: WorkflowStreamResult) => Promise<void> | void
  onError: (error: Error) => void
  onAbort?: () => void
}

// ============================================================
// 后端原始类型（role: 'assistant'）
// ============================================================

interface AiSessionRaw {
  id: string
  title: string
  createdAt: string
  updatedAt: string
  activeWorkflowRunId?: string
}

interface AiMessageRaw {
  id: string
  sessionId: string
  role: 'user' | 'assistant'
  content: string
  pageContext?: string
  createdAt: string
  references?: ArticleRagReference[]
  workflowRunId?: string
  agentRunId?: string
}

// ============================================================
// role 映射
// ============================================================

function mapMessage(m: AiMessageRaw): AiMessage {
  return {
    ...m,
    role: m.role === 'assistant' ? 'ai' : m.role,
    workflowRunId: m.workflowRunId,
    agentRunId: m.agentRunId,
  }
}

// ============================================================
// SSE 流式 chat（fetch + ReadableStream，不走 axios）
// ============================================================

interface AiChatEventRaw {
  eventType: number
  eventData: unknown
}

const EVENT_PARAM = 1003
const EVENT_DATA = 1001
const EVENT_STOP = 1002
const EVENT_WORKFLOW_STEP = 2001
const EVENT_WORKFLOW_STOP = 2002
const EVENT_WORKFLOW_ERROR = 2003
const EVENT_WORKFLOW_CONTENT_DELTA = 2004
const EVENT_AGENT_STEP = 3001
const EVENT_AGENT_PLAN = 3002
const EVENT_CHAT_STATUS = 4001
const EVENT_TASK_PLAN = 5001
const EVENT_TASK_STEP = 5002

async function streamChat(
  sessionId: string | null,
  message: string,
  pageContext: PageContext | undefined,
  callbacks: StreamCallbacks,
  signal?: AbortSignal,
): Promise<void> {
  // 与 axios 走同一个 token 读取出口：不勾「记住我」时 token 只在 sessionStorage，
  // 只读 localStorage 会让这类用户被当成游客（不落库、不计费、Workflow 401）。
  const token = readAuthToken()

  const body: Record<string, unknown> = { message }
  if (sessionId) body.sessionId = sessionId
  if (pageContext) body.pageContext = pageContext

  let response: Response
  try {
    response = await fetch('/api/ai/chat/stream', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      body: JSON.stringify(body),
      signal,
    })
  } catch (e) {
    if (e instanceof DOMException && e.name === 'AbortError') {
      callbacks.onAbort?.()
      return
    }
    callbacks.onError(e instanceof Error ? e : new Error('网络请求失败'))
    return
  }

  if (!response.ok) {
    callbacks.onError(new Error(await resolveRejectionMessage(response)))
    return
  }

  const reader = response.body!.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let stopped = false

  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) break

      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split('\n')
      buffer = lines.pop() ?? ''

      for (const line of lines) {
        // 编排回复可能整批到达浏览器后再按节奏展示；停止时必须丢弃尚未消费的缓冲事件。
        if (signal?.aborted) {
          callbacks.onAbort?.()
          return
        }
        const trimmed = line.trim()
        if (!trimmed.startsWith('data:')) continue

        const jsonStr = trimmed.slice(5).trim()
        if (!jsonStr) continue

        try {
          const event: AiChatEventRaw = JSON.parse(jsonStr)

          if (event.eventType === EVENT_PARAM) {
            const data = event.eventData as {
              session: AiSessionRaw
              userMessage: AiMessageRaw
            }
            callbacks.onParam(data.session, mapMessage(data.userMessage))
          } else if (event.eventType === EVENT_DATA) {
            await callbacks.onData(String(event.eventData ?? ''))
            // 让出事件循环，给 Vue 一次渲染机会，避免所有 chunk 积压到同一帧
            await new Promise((resolve) => setTimeout(resolve, 0))
          } else if (event.eventType === EVENT_WORKFLOW_STEP) {
            await callbacks.onWorkflowStep?.(event.eventData as WorkflowStepEvent)
          } else if (event.eventType === EVENT_WORKFLOW_CONTENT_DELTA) {
            await callbacks.onWorkflowContentDelta?.(event.eventData as WorkflowContentDeltaEvent)
          } else if (event.eventType === EVENT_AGENT_STEP) {
            await callbacks.onAgentStep?.(event.eventData as AgentStepEvent)
          } else if (event.eventType === EVENT_AGENT_PLAN) {
            await callbacks.onAgentPlan?.(event.eventData as AgentPlanEvent)
          } else if (event.eventType === EVENT_TASK_PLAN) {
            await callbacks.onTaskPlan?.(event.eventData as TaskPlanEvent)
          } else if (event.eventType === EVENT_TASK_STEP) {
            await callbacks.onTaskStep?.(event.eventData as TaskStepEvent)
          } else if (event.eventType === EVENT_CHAT_STATUS) {
            await callbacks.onChatStatus?.(event.eventData as ChatStatusEvent)
          } else if (event.eventType === EVENT_STOP) {
            stopped = true
            const data = event.eventData as {
              session: AiSessionRaw
              assistantMessage: AiMessageRaw
              navigate?: NavigateCommand
              editorAction?: EditorAction
              articleAction?: ArticleAction
              references?: ArticleRagReference[]
              workflow?: AiWorkflowRun
              workflowSuggestion?: WorkflowSuggestion
              writeAction?: AgentWriteProposal
              actionResults?: ArticleInteractionResult[]
            }

            const assistantMessage = {
              ...mapMessage(data.assistantMessage),
              references: data.references ?? [],
            }

            callbacks.onStop(
              data.session,
              assistantMessage,
              data.navigate,
              data.editorAction,
              data.articleAction,
              data.references,
              data.workflow,
              data.workflowSuggestion,
              data.writeAction,
              data.actionResults,
            )
          }
        } catch {
          // 跳过解析失败的 JSON 行
        }
      }
    }

    if (!stopped) {
      callbacks.onError(new Error('连接意外中断'))
    }
  } catch (e) {
    if (e instanceof DOMException && e.name === 'AbortError') {
      callbacks.onAbort?.()
      return
    }
    callbacks.onError(e instanceof Error ? e : new Error(String(e)))
  } finally {
    reader.cancel()
  }
}

// ============================================================
// SSE 流式 Workflow 操作（fetch + ReadableStream）
// ============================================================

async function streamWorkflowAction(
  url: string,
  body: Record<string, unknown> | undefined,
  idempotencyKey: string,
  callbacks: WorkflowStreamCallbacks,
  signal?: AbortSignal,
): Promise<void> {
  // 与 axios 走同一个 token 读取出口：不勾「记住我」时 token 只在 sessionStorage，
  // 只读 localStorage 会让这类用户被当成游客（不落库、不计费、Workflow 401）。
  const token = readAuthToken()

  let response: Response
  try {
    response = await fetch(url, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Idempotency-Key': idempotencyKey,
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      body: body ? JSON.stringify(body) : undefined,
      signal,
    })
  } catch (e) {
    if (e instanceof DOMException && e.name === 'AbortError') {
      callbacks.onAbort?.()
      return
    }
    callbacks.onError(e instanceof Error ? e : new Error('网络请求失败'))
    return
  }

  if (!response.ok) {
    callbacks.onError(new Error(await resolveRejectionMessage(response)))
    return
  }

  const reader = response.body!.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let stopped = false

  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) break

      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split('\n')
      buffer = lines.pop() ?? ''

      for (const line of lines) {
        const trimmed = line.trim()
        if (!trimmed.startsWith('data:')) continue

        const jsonStr = trimmed.slice(5).trim()
        if (!jsonStr) continue

        try {
          const event: AiChatEventRaw = JSON.parse(jsonStr)

          if (event.eventType === EVENT_WORKFLOW_STEP) {
            await callbacks.onStep?.(event.eventData as WorkflowStepEvent)
          } else if (event.eventType === EVENT_WORKFLOW_CONTENT_DELTA) {
            await callbacks.onContentDelta?.(event.eventData as WorkflowContentDeltaEvent)
          } else if (event.eventType === EVENT_WORKFLOW_STOP) {
            stopped = true
            await callbacks.onStop?.(event.eventData as WorkflowStreamResult)
          } else if (event.eventType === EVENT_WORKFLOW_ERROR) {
            stopped = true
            await callbacks.onWorkflowError?.(event.eventData as WorkflowStreamResult)
          }
        } catch {
          // 跳过解析失败的事件
        }
      }
    }

    if (!stopped) {
      callbacks.onError(new Error('Workflow 连接意外中断'))
    }
  } catch (e) {
    if (e instanceof DOMException && e.name === 'AbortError') {
      callbacks.onAbort?.()
      return
    }
    callbacks.onError(e instanceof Error ? e : new Error(String(e)))
  } finally {
    reader.cancel()
  }
}

// ============================================================
// API
// ============================================================

export const aiApi = {
  /** 创建会话 */
  createSession(title?: string) {
    return request.post<AiSession>('/ai/conversations', title ? { title } : {})
  },

  /** 历史会话分页 */
  getSessions(page = 1, pageSize = 20) {
    return request.get<PageData<AiSessionRaw>>('/ai/conversations', {
      params: { page, pageSize },
    })
  },

  /** 删除会话 */
  deleteSession(id: string) {
    return request.delete(`/ai/conversations/${id}`)
  },

  /** 查询会话的消息列表 */
  async getMessages(sessionId: string) {
    const res = await request.get<AiMessageRaw[]>(`/ai/conversations/${sessionId}/messages`)
    if (res.data) {
      return { ...res, data: res.data.map(mapMessage) }
    }
    return res as unknown as typeof res & { data: AiMessage[] }
  },

  /** 删除单条消息 */
  deleteMessage(sessionId: string, messageId: string) {
    return request.delete(`/ai/conversations/${sessionId}/messages/${messageId}`)
  },

  /** 会话压缩状态：压缩中标记 / 最近压缩时间 / 已压缩消息数 */
  getSummaryStatus(sessionId: string) {
    return request.get<AiConversationSummaryStatus>(`/ai/conversations/${sessionId}/summary-status`)
  },

  /** 查询待确认记忆 */
  getMemoryCandidates() {
    return request.get<AiMemoryCandidate[]>('/ai/memory-candidates')
  },

  /** 确认候选记忆，可传编辑后的 content 覆盖原内容 */
  confirmMemoryCandidate(id: string, content?: string) {
    return request.post<void>(`/ai/memory-candidates/${id}/confirm`, content ? { content } : undefined)
  },

  /** 忽略候选记忆 */
  rejectMemoryCandidate(id: string) {
    return request.post<void>(`/ai/memory-candidates/${id}/reject`)
  },

  /** 查询正式长期记忆 */
  getMemories() {
    return request.get<AiMemory[]>('/ai/memories')
  },

  /** 删除正式长期记忆：后端实际是 enabled = 0 */
  deleteMemory(id: string) {
    return request.delete<void>(`/ai/memories/${id}`)
  },

  /** 查询情景记忆 */
  getEpisodicMemories() {
    return request.get<AiEpisodicMemory[]>('/ai/episodic-memories')
  },

  /** 物理删除情景记忆 */
  deleteEpisodicMemory(id: string) {
    return request.delete<void>(`/ai/episodic-memories/${id}`)
  },

  /** 编辑长期记忆内容 */
  updateMemory(id: string, data: { memoryType: string; memoryKey: string; content: string }) {
    return request.put<void>(`/ai/memories/${id}`, data)
  },

  /** 发送消息（SSE 流式） */
  streamChat,

  /** 查询 Workflow 运行状态 */
  getWorkflowRun(id: string) {
    return request.get<AiWorkflowRun>(`/ai/workflows/${id}`)
  },

  /** 查询 Agent Run 建议快照（历史消息恢复建议卡，仅 WAITING_WORKFLOW_CONFIRM 返回 suggestion） */
  getWorkflowSuggestion(agentRunId: string) {
    return request.get<{ status: string; suggestion: WorkflowSuggestion | null }>(
      `/ai/agent-runs/${agentRunId}/workflow-suggestion`,
    )
  },

  /** V2.2：Agent Run 步骤列表（历史消息恢复思考面板；后端 VO 字段为 summary） */
  getAgentRunSteps(agentRunId: string) {
    return request.get<AgentStepHistoryItem[]>(`/ai/agent-runs/${agentRunId}/steps`)
  },

  /**
   * V3.13：Agent Run 详情（run 级入口，含 plan）。
   *
   * 刷新恢复计划必须走这里——`/steps` 是 step 级接口，不含 run 级 plan
   * （不扩展它的形状：往步级接口塞 run 级数据是语义错位）。
   */
  getAgentRunDetail(agentRunId: string) {
    return request.get<AgentRunDetail>(`/ai/agent-runs/${agentRunId}`)
  },

  /* ---- V4 第一刀：开发者只读面板（后端白名单门禁，403 即无权；只读不改状态、不触发 retry） ---- */

  /** 开发者通道：run 列表（仍是自己的 run，分页） */
  listDevAgentRuns(params?: { sessionId?: string; page?: number; pageSize?: number }) {
    return request.get<PageData<AgentRunSummaryItem>>('/ai/dev/agent-runs', { params })
  },

  /** 开发者通道：run 详情（安全摘要 + 原始 contextJson） */
  getDevAgentRunDetail(agentRunId: string) {
    return request.get<AgentRunDevDetail>(`/ai/dev/agent-runs/${agentRunId}`)
  },

  /** 开发者通道：完整 step 列表（含 inputJson / outputJson 原始列） */
  getDevAgentRunSteps(agentRunId: string) {
    return request.get<AgentStepRawItem[]>(`/ai/dev/agent-runs/${agentRunId}/steps`)
  },

  /** 确认 Agent 建议：启动学习类 Workflow，返回 Workflow 快照（Idempotency-Key 防双击重复创建） */
  confirmWorkflowSuggestion(agentRunId: string, idempotencyKey: string) {
    return request.post<AiWorkflowRun>(
      `/ai/agent-runs/${agentRunId}/workflow-suggestion/confirm`,
      undefined,
      { headers: { 'Idempotency-Key': idempotencyKey } },
    )
  },

  /** 取消 Agent 建议：不启动任何 Workflow */
  cancelWorkflowSuggestion(agentRunId: string) {
    return request.post<string>(`/ai/agent-runs/${agentRunId}/workflow-suggestion/cancel`)
  },

  /** V2.4：确认写动作提案（标题匹配 → 执行）；2026-10-19 起返回结构含执行器载荷（钱包域拿支付凭证开支付窗口） */
  confirmWriteAction(agentRunId: string) {
    return request.post<AgentWriteActionConfirmView>(`/ai/agent-runs/${agentRunId}/write-action/confirm`)
  },

  /** V2.4：取消写动作提案（不执行任何修改） */
  cancelWriteAction(agentRunId: string) {
    return request.post<string>(`/ai/agent-runs/${agentRunId}/write-action/cancel`)
  },

  /** V2.4：写动作提案快照（历史消息恢复写动作卡） */
  getWriteAction(agentRunId: string) {
    return request.get<{ status: string; proposal: AgentWriteProposal | null }>(
      `/ai/agent-runs/${agentRunId}/write-action`,
    )
  },

  /** 查询 Workflow 步骤执行日志 */
  getWorkflowStepLogs(id: string) {
    return request.get<AiWorkflowStepLog[]>(`/ai/workflows/${id}/steps`)
  },

  /** 同意 Workflow 当前步骤（SSE） */
  streamApproveWorkflow(
    id: string,
    idempotencyKey: string,
    callbacks: WorkflowStreamCallbacks,
    signal?: AbortSignal,
  ) {
    return streamWorkflowAction(
      `/api/ai/workflows/${id}/approve/stream`,
      undefined,
      idempotencyKey,
      callbacks,
      signal,
    )
  },

  /** 拒绝 Workflow 当前步骤并反馈（SSE） */
  streamRejectWorkflow(
    id: string,
    feedback: string,
    idempotencyKey: string,
    callbacks: WorkflowStreamCallbacks,
    signal?: AbortSignal,
  ) {
    return streamWorkflowAction(
      `/api/ai/workflows/${id}/reject/stream`,
      { feedback },
      idempotencyKey,
      callbacks,
      signal,
    )
  },

  /** 重试失败的 Workflow 当前步骤（SSE） */
  streamRetryWorkflow(
    id: string,
    idempotencyKey: string,
    callbacks: WorkflowStreamCallbacks,
    signal?: AbortSignal,
  ) {
    return streamWorkflowAction(
      `/api/ai/workflows/${id}/retry/stream`,
      undefined,
      idempotencyKey,
      callbacks,
      signal,
    )
  },

  /** 取消 Workflow */
  cancelWorkflow(id: string) {
    return request.post<void>(`/ai/workflows/${id}/cancel`)
  },

  // 【已废弃】非流式接口，前端已全面切到流式，暂时注释，后续删除
  // async chat(sessionId: string | null, message: string, pageContext?: PageContext) {
  //   const body: { sessionId?: string; message: string; pageContext?: PageContext } = {
  //     message,
  //   }
  //   if (sessionId) body.sessionId = sessionId
  //   if (pageContext) body.pageContext = pageContext
  //
  //   const res = await request.post<any>('/ai/chat', body)
  //   if (res.data) {
  //     return { ...res, data: mapChatResult(res.data) }
  //   }
  //   return res as unknown as typeof res & { data: AiChatResult }
  // },
}

/**
 * SSE 请求被拒绝（HTTP 非 2xx）时解析后端的错误体。
 *
 * 后端的准入拒绝 / 限流 / 额度不足都走「非 2xx + Result JSON」——
 * 不能走项目默认的 200，因为 SSE 解析器在 200 时只认 `data:` 前缀的行，
 * JSON 错误体会被静默忽略，用户看到的只是「连接意外中断」，完全指不到根因。
 *
 * 聊天流与 Workflow 流共用这一处，避免两边行为不一致
 * （原先 Workflow 流只显示裸的 `请求失败 (429)`，后端给的「你还有任务正在处理中」看不到）。
 */
async function resolveRejectionMessage(response: Response): Promise<string> {
  let errorMsg = `请求失败 (${response.status})`
  try {
    const text = await response.text()
    if (text) {
      const parsed = JSON.parse(text)
      errorMsg = parsed.message || errorMsg
    }
  } catch {
    // 不是 JSON（或空体），用状态码兜底
  }
  return errorMsg
}
