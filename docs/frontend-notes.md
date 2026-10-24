# Frontend Notes

## 头像上传

- 支持用户自由上传头像。
- 前端在个人信息或头像编辑入口提供图片选择功能。
- 选择图片后，前端可以先做本地预览，再调用后端头像上传接口。
- 头像上传接口：`POST /api/users/me/avatar`。
- 请求方式：`multipart/form-data`。
- 文件字段名：`file`。
- 该接口需要登录，请求头需要携带 `Authorization: Bearer <token>`。
- 上传成功后，后端返回更新后的 `usersVO`，其中包含新的 `avatarUrl`。
- 前端收到成功响应后，需要更新本地保存的当前用户信息，并刷新页面上的头像显示。
- 如果 `avatarUrl` 为空，前端显示默认头像。

本文件作为前端规则和跨会话交接笔记使用。后端开发过程中遇到需要前端配合的地方，先记在这里；新开前端会话时优先查看本文档和 `docs/api-convention.md`。

## 项目访问状态

- 这是公开博客，不强制登录。
- 游客可以进入首页、查看公开文章列表、文章详情、分类列表、标签列表。
- 登录用户在游客能力基础上，可以查看个人信息、发布文章、管理自己的文章。

## 当前功能进度

- V1 基础浏览、登录注册、文章发布和文章管理已完成。
- V2 互动功能基本完成：评论、评论点赞、文章点赞 / 收藏、关注作者、公开用户主页、关注列表 / 粉丝列表。
- V3 AI 助手已完成：右侧悬浮入口、抽屉聊天、SSE 流式、PageContext、登录用户会话历史、Tool Calling、RAG 参考来源展示。
- 搜索：列表页仍是标题模糊搜索（V0），AI 搜索类问题走 RAG 召回。
- 游客临时会话次数限制待做。
- 站内消息通知、私信、WebSocket、RocketMQ 暂缓。

## 登录注册流程

- 注册成功后视为自动登录。
- 登录成功和注册成功都返回认证结果：`token + usersVO`。
- 前端拿到 token 后保存登录状态，然后跳转到首页。
- 首页根据是否有 token / 用户信息决定显示：
  - 未登录：登录、注册入口。
  - 已登录：昵称、头像、我的文章、发布文章、退出登录入口。

## 退出登录（2026-09-15 后端加了 token 黑名单后更新）

- 入口在个人中心（`ProfileView`），确认弹窗里的 `onPositiveClick`。
- **必须先调 `POST /api/auth/logout` 再清本地状态**。顺序反了就带不上 token，后端不知道要作废哪一个
  （JWT 无状态，不通知 = 这个 token 继续有效到自然过期）。
- **调用失败必须忽略**：后端不可用时不能把用户卡在登录态，本地状态照清。为此这个请求单独设了
  3 秒超时——默认 60 秒会让"退出"按钮转一分钟。
- 登出后同一个 token：需要登录的接口返回 401，公开接口按游客处理（不报错，但不再以该身份写操作）。
- 后端登出接口是幂等的：没带 token / token 已失效，同样返回成功。

> 已知的既有行为（本次未改）：`utils/request.ts` 里 401 的 `onUnauthorized` 回调在主实例上
> **没有传**，所以 token 因其他原因失效（比如 7 天过期）时前端不会自动清登录态——页面仍显示已登录，
> 但要登录的操作会持续失败。要补的话是在 `createRequest()` 的调用处接上 `clearAuth()` + 跳登录。

## Token 保存与请求

- 登录或注册成功后，前端保存：
  - `token`
  - 当前用户基础信息 `usersVO`
- 后续访问需要登录的接口时，在请求头里带：

```http
Authorization: Bearer <token>
```

- 公开接口不需要带 token。

## 退出登录

- 退出登录接口：`POST /api/auth/logout`。
- 后端返回成功后，前端清理本地登录状态。
- 前端收到成功后：
  - 删除本地保存的 `token`。
  - 删除本地保存的用户信息。
  - 跳转回首页。
  - 首页以游客身份重新渲染。

## 前端 API 文件约定

- 登录注册退出：`frontend/src/api/auth.ts`
- 公开文章：`frontend/src/api/article.ts`
- 公开分类：`frontend/src/api/category.ts`
- 公开标签：`frontend/src/api/tag.ts`
- 我的文章管理：`frontend/src/api/myArticle.ts`
- 评论接口：`frontend/src/api/comment.ts`
- 公开用户主页 / 关注关系：`frontend/src/api/publicUser.ts`
- AI 助手：`frontend/src/api/ai.ts`

## 响应处理

- 后端统一返回 `Result<T>`：

```json
{
  "code": 0,
  "message": "success",
  "data": {}
}
```

- 前端业务数据从 `result.data` 读取。
- `code = 0` 表示成功。
- 非 0 表示失败，例如 `40001`、`40100`、`40400` 等错误码。

## V2 评论区

评论区已接入文章详情页，保留以下约定作为后续维护参考。

### 文件结构

- `frontend/src/types/comment.ts`：评论类型。
- `frontend/src/api/comment.ts`：评论接口请求。
- `frontend/src/components/comment/CommentSection.vue`：评论区整体，负责请求、分页、排序和加载更多。
- `frontend/src/components/comment/CommentEditor.vue`：评论输入框。
- `frontend/src/components/comment/CommentItem.vue`：单条评论和回复展示。

### 接入位置

文章详情页：`frontend/src/views/ArticleDetailView.vue`。

评论区放在正文之后：

```vue
<div class="article-content" v-html="renderedContent" />
<CommentSection v-if="article" :article-id="article.id" />
```

### 交互规则

- 标题显示“评论”，数量使用评论分页接口返回的 `total`。
- 登录用户显示评论输入框；游客显示“登录后发表评论”。
- 默认请求第 1 页评论：

```text
GET /api/articles/{articleId}/comments?page=1&pageSize=20&sort=time
```

- 主评论列表使用滚动触底加载下一页，并把新评论追加到当前评论列表。
- 每条主评论下的“更多回复”仍保留按钮触发，避免滚动时误加载多个楼层的回复。
- 排序入口保留“最新 / 最热”。
- `sort=time`：按发布时间倒序。
- `sort=hot`：当前先按点赞数倒序，点赞数相同再按时间倒序。

### 评论展示

- 主评论展示：头像、昵称、内容、创建时间、点赞数、回复按钮、IP 属地。
- 回复评论展示在对应主评论下面，平铺显示，不做无限缩进。
- `replyToNickname` 用于回复评论前缀，例如“回复 @某某：”。
- `ipLocation` 放在“回复”按钮后面，样式弱化，例如：

```text
1小时前  点赞  回复  广东
```

- 前端只展示 `ipLocation`，不要展示原始 IP。
- `liked = true` 时点赞按钮高亮；游客或未点赞时为普通状态。

### 评论类型

```ts
export interface Comment {
  id: number
  articleId: number
  userId: number
  nickname: string
  avatarUrl?: string
  content: string
  rootId: number | null
  parentId: number | null
  replyToNickname?: string | null
  ipLocation?: string | null
  likeCount: number
  liked: boolean
  createdAt: string
  replies: Comment[]
  replyCount: number
}
```

## 文章状态与生命周期

### 状态值

| 值 | 状态 | 说明 |
|----|------|------|
| `0` | 草稿 (DRAFT) | 仅作者自己可见，不出现在公开列表 |
| `1` | 已发布 (PUBLISHED) | 全站公开可见 |
| `2` | 隐藏 (HIDDEN) | 之前发布过但被作者隐藏，公开列表不可见 |

### 状态流转

```
                保存草稿
                   ↓
              ┌── 草稿(0) ──┐
              │              │ 删除 → 逻辑删除（不可恢复）
              │   发布       │
              ↓              │
           已发布(1) ←──发布──┘
              │
              │ 隐藏（在个人中心操作）
              ↓
           隐藏(2)
              │
              └── 发布 → 已发布(1)
```

### 写文章页（独立页面 `/editor`）行为规则

- **保存草稿**：调用创建/更新接口，status 设为 0。保存成功后停留在当前页面，不跳转。
- **发布**：调用创建/更新接口，status 设为 1。发布成功后退出编辑页，跳转回上一页（首页或个人中心）。
- **返回**：点击返回不自动保存。如果用户已输入内容，弹窗提示"是否保存为草稿？"。
- 编辑已有文章时，页面标题/按钮根据当前状态变化：
  - 草稿文章 → 显示"保存草稿"+"发布"
  - 已发布文章 → 显示"保存"（更新已发布内容）+"隐藏"（？）
  - 隐藏文章 → 显示"保存"+"重新发布"

> **一句话总结：**
> - 保存草稿 → 调 API 保存（status=0），成功但不离开编辑页面，继续编辑
> - 发布 → 调 API 保存并发布（status=1），成功后退出编辑页面，返回上一页
> - 隐藏 → 在个人中心的文章列表里操作，不在编辑页面

### 个人中心文章管理

- 在个人中心 `/me` 展示用户自己的文章列表（含草稿、已发布、隐藏）。
- 每条文章显示当前状态标签（草稿/已发布/隐藏）。
- 操作按钮根据状态变化：
  - 草稿 → [编辑] [发布] [删除]
  - 已发布 → [编辑] [隐藏] [删除]
  - 隐藏 → [编辑] [重新发布] [删除]
- 隐藏操作：在个人中心对已发布文章点击隐藏，确认后调用 hide 接口。

### 后端 API 汇总

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/users/me/articles` | `POST` | 创建文章（body: ArticlesDTO，含 status 字段） |
| `/api/users/me/articles/{id}` | `PUT` | 更新文章 |
| `/api/users/me/articles/{id}` | `GET` | 获取自己某篇文章详情 |
| `/api/users/me/articles?page=&pageSize=` | `GET` | 获取自己的文章列表（含草稿/已发布/隐藏） |
| `/api/users/me/articles/{id}` | `DELETE` | 删除文章（逻辑删除） |
| `/api/users/me/articles/{id}/publish` | `PATCH` | 发布文章（空请求体，后端设 status=1） |
| `/api/users/me/articles/{id}/hide` | `PATCH` | 隐藏文章（空请求体，后端设 status=2） |

### 文章数据结构 (ArticlesDTO)

```json
{
  "categoryId": 1,
  "title": "文章标题",
  "summary": "文章摘要",
  "content": "Markdown 正文",
  "coverUrl": "封面图 URL",
  "status": 0
}
```

- `status` 由前端控制：保存草稿传 0，发布传 1。
- `publishedAt` 由后端在发布时自动设置。
- 标签通过额外接口关联，不在 ArticlesDTO 中直接传。

## 全站 AI Agent Shell 前端（现状）

- 全站统一 AI 助手已落地：右侧悬浮按钮 + 抽屉聊天（`src/components/ai/AiAssistant.vue`），首页、文章详情页、个人中心等页面可用。
- AI 助手挂载在 `App.vue` 根级 provider 内，不再放在各页面的 `MainLayout` 内，避免站内跳转时组件重建导致 SSE 流、AI 消息占位和 Workflow 卡片内存态丢失。
- 聊天走 SSE 流式（`POST /api/ai/chat/stream`），回答下方展示参考来源列表（STOP 事件的 references），可点击跳转文章详情。
- 前端每次发送消息携带 PageContext（`pageType / path / articleId / articleTitle`），后端据此识别页面并读取当前文章。
- 登录用户支持历史会话：新建、查看、删除。
- 待做：游客临时会话次数限制。
- 文章创作 Workflow 前端已接入：底部确认面板支持补充需求、同意、不同意、完成、取消。
- Workflow FAILED 状态已接入：底部显示错误信息，支持点击“重试”调用 `/api/ai/workflows/{id}/retry` 或流式 retry。
- Step Log 已接入前端执行详情折叠区，Workflow step/content SSE 可实时补充执行中状态和内容 delta。

### AI 流式等待与中断语义（2026-10-04）

- 用户发送消息后，不等后端首个 SSE 事件：前端复用现有 250ms 防抖状态机制，显示“正在理解需求”。
  若 250ms 内正文已经到达，则不显示该状态，避免短请求闪烁。
- 每轮发送前必须清理上一轮的瞬态过程步骤；首个正文 chunk 到达后，把本轮已经展示的过程步骤固化到
  当前 AI 消息，再清空实时状态。
- SSE 在**尚未收到正文**时失败，可以删除空 AI 占位消息；SSE 在**已经展示部分正文**后失败，必须保留
  已展示内容，并追加“生成中断，内容可能不完整”，不能再无条件 `messages.pop()`。
- 删除空占位时按本轮记录的 `aiPlaceholderIndex` 使用 `splice`，不要假定它始终是消息列表最后一项，
  避免并发 UI 更新时误删其他消息。
- 上述中断语义只影响当前前端展示；后端在部分输出后失败时不会保存不完整消息，也不会发送伪成功的
  `STOP` 事件，前端应以 SSE 异常结束处理。

## 评论热度算法规划

当前阶段的热门评论先保持简单：

```text
sort=hot = like_count DESC, created_at DESC
```

后续进入 Redis 阶段后，再考虑综合热度分数：

```text
hotScore = likeCount * 2 + replyCount * 1 - timeDecay
```

实现方向：

- `likeCount` 继续来自评论点赞数。
- `replyCount` 可以由评论回复数维护，后续不建议每次列表查询实时聚合。
- `timeDecay` 用于让很旧的评论逐渐降权，具体公式等 Redis 阶段再定。
- 综合热度结果适合放进 Redis ZSET，例如按文章维护热门评论集合。
- 数据库查询先负责准确数据，Redis 后续负责热点排序和缓存。

## 钱包（2026-09-18）

额度（credit）用于 AI 助手消费，按 token 用量扣减。
**当前只有"充值 + 看余额"**——消费链路是下一批（AI 计费）才接。

### 文件

- `src/api/wallet.ts`：钱包接口
- `src/types/wallet.ts`：类型
- `src/views/WalletView.vue`：钱包页（余额 / 充值套餐 / **账单**）
- `src/components/common/WalletBalanceCard.vue`：个人中心右侧卡的余额小卡（**整张卡就是进入钱包页的入口**，没有再另加导航链接）
- 路由 `/me/wallet`（`requiresAuth: true`）

### 两个容易混的格式化函数

`src/utils/format.ts` 新增了两个，**不能混用**：

- `formatPayAmount(fen)` → `¥10.00`，只用于 `payAmount`（后端单位是**分**）
- `formatCredit(credit)` → `10,000`，用于所有**额度**字段

混用会把 10000 credit 显示成 `¥100.00`。

### 负余额的三态（两处必须保持一致）

余额允许为负（透支一轮），但负余额有**两种完全不同的含义**，只看数字会把"预扣进行中"误报成欠费：

| 条件 | 展示 |
|------|------|
| `balance > 0` | 正常可用 |
| `balance <= 0` 且 `pendingReserveCount > 0` | "本次调用进行中"——**中性色，不要做成红色告警** |
| `balance <= 0` 且 `pendingReserveCount == 0` | "额度不足，请充值"（红色） |

`WalletBalanceCard.vue` 和 `WalletView.vue` 各有一份同样的判断——改一处要记得改另一处。

### 账单是聚合视角，不是原始流水

`GET /api/wallet/bill` 返回的每一条是**一笔业务**，不是一行流水——一次 AI 调用只有**一条净额**，
不会出现「AI 消费 -400」+「AI 结算退回 +365」两条。

前端只需按 `bizType` 映射文案：

| bizType | 文案 |
|---|---|
| `RECHARGE_ORDER` | 充值到账 |
| `SECKILL_ORDER` | 秒杀到账 |
| `INITIAL_GRANT` | 注册赠送 |
| `AI_BILLING` | AI 消费 |

两条不显示规则（后端已处理，前端不用管）：
**预扣中的调用不显示**（净额没定下来），**全额退回的也不显示**（净额为 0）。

> 别在前端做这个聚合：按原始流水分页会得到忽多忽少的一页（一页 20 行可能只对应 13 笔业务）。

### 支付

`POST /api/wallet/recharge/orders/{orderNo}/pay` 是**幂等**的（订单号 + 状态 CAS 保证重复支付只加一次额度），
前端不需要防重、不需要带 `Idempotency-Key`。

WalletView 里"下单"和"支付"分两步调用，是为了和将来接真实支付时的流程一致——那时两者中间会隔着一次第三方支付。

## 额度秒杀（2026-09-18）

- `src/api/seckill.ts`：秒杀接口
- `src/types/seckill.ts`：类型
- `src/views/SeckillView.vue`：抢购页
- 路由 `/seckill`（`requiresAuth: true`）
- 入口在**钱包页**：余额卡和充值区之间的那张 `wallet-seckill-entry`

### 最要紧的一条：「排队中」≠「抢到了」

`grab` 成功只代表**名额占住了、入账事件已入队**，真正的发放由后端异步完成。
所以 `SeckillView` 拿到 `QUEUED` 后会轮询 `/result`（1 秒一次、最多 10 次），
**只有出现 `GRANTED` 才提示到账**。

UI 上两者必须是不同的样子：`QUEUED` 是暖黄 +「排队中…」，`GRANTED` 是绿色 +「已到账」。
合并成一个「抢到了」的提示，用户就会来问「我抢到了为什么额度没到」。

### status 取值

| status | 按钮文案 | 说明 |
|---|---|---|
| `NONE` | 立即抢 | |
| `QUEUED` | 排队中… | 暖黄，**不能提示成功** |
| `GRANTED` | 已到账 | 绿色 |
| `FAILED` / `FAILED_RETRY` | 未抢到 / 发放异常 | 终态，轮询到此为止 |
| `SOLD_OUT` / `DUPLICATE` / `NOT_ACTIVE` | （弹提示，不轮询） | 只在 grab 返回 |

### 两个容易踩的点

- **轮询接口不限流**：后端只拦 `grab`（20 次/分钟）。所以轮询不需要退避，正常每秒问即可；
  但也别把轮询频率调高——它对后端是一次 DB 查询加一次 Redis 查询。
- **组件卸载要清 timer**：用户中途跳走时如果轮询还在跑，会往已卸载的组件上写状态。
  `SeckillView` 在 `onUnmounted` 里统一 `clearTimeout`。

## 5b：WORKFLOW_HANDOFF 暂停 / 恢复（2026-10-01，**前后端均已实施**）

> 来源：`docs/agent/agent-task-orchestration-workflow-handoff-design.md`（阶段 0-3 已实施，阶段 4 手测待做）。
> 5b 让「点赞这篇文章，再帮我优化一下」这类复合请求在外层计划里**跨请求暂停并续跑**。

**总原则：前端只负责展示与刷新，不承担恢复正确性。** 恢复由后端完成；前端刷新失败不会阻止续跑，
反过来前端漏调也不会让计划卡死。

**已实施的前端改动**：`AiAssistant.vue` 新增 `refreshBoundTaskResult(workflowId)`，
在 `applyWorkflowStreamResult` 的**终态分支**（Workflow `COMPLETED / CANCELLED`）以及取消接口成功后调用——
拿该消息的 `agentRunId` 重拉原 AI 消息与 `getAgentRunSteps`，同步续跑后的汇总正文，
并按 `kind === 'TASK'` 重建 `taskSteps`。
不做这步的话，后端其实已经续跑完了，界面却一直停在"等确认"，直到用户手动刷新页面。

`taskSteps` 是稳定的计划清单：每行始终显示 `goalEvidence`（缺失时显示固定步骤类型），
执行完成后只切换状态图标，不用 `summary` 覆盖步骤名称。搜索结果、分析正文等详细结果只放在 AI 消息正文，
避免“这次要做的事”与最终回答重复。

任务编排的最终正文是后端完成全部步骤后批量发送的 `DATA`，不是模型生成期的真流式。
前端仅在本轮收到 `TASK_PLAN` 后，以每块 `24ms` 的节奏展示这些缓冲块；普通聊天 / QA 的真流式不加延迟。
用户停止生成时，SSE 解析循环会丢弃尚未消费的缓冲事件。Workflow 自动续跑没有原聊天 SSE，仍在完成后整体刷新原消息。

> **与设计稿的一处差异（有意）**：设计稿原本设想「`WORKFLOW_STOP` 增加 `taskRunId` / `taskStepId`
> 两个可选字段」，实施时改成了前端**主动刷新**。原因：前端本来就有 `msg.agentRunId` 与
> `msg.workflow.id`，而一次用户请求只对应一个根 run，反查是确定的；为此去改 SSE 协议
> 要多动一处契约，收益只是省一次反查。**如果将来出现"一条消息对应多个根 run"，就得回到设计稿的做法。**

刷新恢复的入口不变：

- 仍由 `AiMessages.agentRunId` 找到根 run，再靠 `task_plan_json` + `TASK_STEP` 重建外层计划；
  `activeWorkflowRunId` 只用于恢复 Workflow 卡。⚠️ 不要拿 `session.activeWorkflowRunId`
  判断"任务是否还在跑"——它在 `retry` 成功路径上是滞后的（后端靠惰性自愈兜），
  后端恢复判定也只认 DB 父关联；
- **启动恢复不再清理** `task_plan_json IS NOT NULL` 的外层 Task run（它有可恢复的步骤快照，
  判死等于让用户重来），改由低频巡检接手；`WAITING_WORKFLOW` 是合法等待态，任何情况下都不清理；
- **最终汇总更新原消息**：外层计划收敛后更新**原来那条 assistant message** 的汇总内容，
  **不额外插入一条「幽灵回复」**——保持「一次用户请求 = 一条 TaskPlan 消息」。

### 实时渲染的两个坑（2026-10-01 手测踩到，都已修）

5b 让「外层任务步骤」和「Workflow 卡」出现在**同一条聊天流**里，这暴露了两个
**只在实时路径出现、一刷新就自愈**的问题。共同特征很好认：
**刷新能好 = 后端数据是对的，问题全在前端的内存状态**。

1. **长连接里，"给用户看的事件"同时是保活**。handoff 原先用 `AiWorkflowStepEmitter.noop()`，
   初始链要跑几十秒却一个事件都不发——用户看到的是"点了发送、什么都没发生，
   然后突然冒出一张卡"。更麻烦的是：长时间静默的流更容易被中间环节掐断，
   连 handoff 完成时那条 `TASK_STEP` 都可能到不了前端（表现：任务步骤少一行）。
   现在转发为 `WORKFLOW_STEP`，**流程一启动卡片就出现**。
   > 教训：别把"展示完整性"当成可以延后的东西——在 SSE 里它和链路健康是同一件事。
2. **"替换占位消息"必须逐项列出要保留的实时字段**。流结束时前端用后端返回的完整消息
   替换占位消息，那里显式保留了 `thinkingSteps` / `plan` / `taskSteps`，
   **唯独漏了 `workflow`**；而兜底 `assistantMessage.workflow` 永远是空
   （消息表不存这个字段），于是卡片在流结束的瞬间整张消失。
   > 新增这类"只在实时路径存在"的消息字段时，**记得同步那份保留清单**。
   > 好消息是它不会造成数据错误——刷新走 `restoreActiveWorkflow` 就能恢复。
3. **"卡片在"不等于"卡片对"**：handoff 创建的 Workflow 走聊天流，
   没有独立 Workflow SSE 那条「STOP 带完整快照 + 步骤日志」的路径，实时那张卡的字段本就是残缺的——
   状态恒为 `RUNNING`（标题显示"Workflow"而不是"等待确认优化方案"）、步骤缺「第 N 步」序号、
   耗时 / token 为空、输入输出还可能写反。修法是**流结束时按 runId 补拉一次**
   `aiApi.getWorkflowRun` + `refreshWorkflowStepLogs`（封装成 `applyWorkflowSnapshotById`）。
   ⚠️ 只在流结束补拉，**不要每个步骤事件都拉**：一次 handoff 有六到八个步骤，逐个拉会打出一串无用请求。

一个必须记住的产品事实（设计稿 §10 定的边界）：`OPTIMIZE_ARTICLE` 完成时会产生
`editorAction(fillArticle)` **跳编辑器并填优化稿**，所以第一版**不允许 handoff 之后接导航**——
否则会连发两条客户端命令，用户可能在优化稿尚未保存时被带走。

## 首页切换分类 / 排序的请求竞态（2026-10-09 修复）

- 位置：`views/HomeView.vue` 的 `loadArticles`。
- 现象：滑到底触发 loadMore（请求 A）后立刻点分类或切排序（请求 B），两条请求并发在飞。
  A 若**后**返回，会把旧分类的数据追加进新分类列表，并把 `currentPage` 覆盖回旧页号——
  之后 loadMore 从这个错误页号继续，**中间那一页被静默跳过**。
- 修法：加请求序号。每次 `loadArticles` 开头取 `const seq = ++loadRequestSeq`，
  响应 / 异常回来后先比 `seq !== loadRequestSeq`，不是最新就整个丢弃。
  **过期请求连 `loading` / `loadingMore` 都不重置**——那两个状态归最新那次请求管，
  否则会把"新请求还在飞"误标成已结束。
- 为什么不用 `AbortController`：`utils/request.ts` 没有 signal 通道，加它要动公共请求层；
  而真正的失效模式不是"旧请求发出去了"，是**"旧响应落地了"**——序号方案只改一个文件就能覆盖它。
- 注意 `reloadArticles()` / `resetHomeState()` / `refreshArticlesForAuthChange()` 都不 await 就重置
  `currentPage`，它们靠的正是序号把旧响应作废。

### 首页列表接口不再返回正文（2026-10-09 后端改动，前端无需适配）

- 后端 `ArticleDetailVO.from()` 改为**列表语义、不带 `content`**，详情路径走 `fromDetail()`。
- 前端**零改动**：列表卡片（`ArticleCard`）本来就只用 title / summary / coverUrl / 计数；
  两个真正需要正文的入口——`ArticleDetailView` 的渲染、`EditorView` 的回填（走
  `myArticleApi.getDetail` → `BeanUtil.copyProperties`）——都不经过 `from()`。
- 附带收益：首页第一页响应体实测从 53.6 KB 降到 4.1 KB（24 篇已发布文章）。

### 首页分页改成游标（2026-10-09 后端改动，前端已同步适配）

- `/api/articles` 的 `page` 参数**已移除**，换成 `cursor`（不透明 token）。
- 判断"还有没有下一页"看 `data.nextCursor`，**不要再算 `total`**——游标模式下后端
  不计算 total，返回的 `total` / `page` 都是 `null`。
- `PageData` 里 `total` / `page` 已改成可选（用 `?? 0` / `?? nextPage` 兜底），
  `nextCursor` 是 `string | null`。改这个类型时记得检查所有取值点。
- 切换分类 / 排序时要清空游标（`resetCursor()`），否则会用上一个筛选条件的游标去查。
- 「请求序号」（`loadRequestSeq`）和「游标」是**两件事**，别合并：
  序号管的是"旧响应不许落地"，游标管的是"下一页从哪里继续"。
- 热榜（`/articles/hot`）**没动**，它仍是 `page`/`pageSize`——数据源是 Redis ZSET 的排名区间，
  不是 SQL 分页。所以 `articleApi.getHotList` 的参数类型是单独写的，不要用 `ArticleListParams`。

## 搜索页独立成页 + 支持搜用户（2026-10-17）

**行为变化**：顶部搜索框提交后跳 `/search?keyword=`，不再回首页。

- 搜索页两栏：**文章 / 用户**。原来的「推荐 / 最新 / 关注」三档 tab 和左侧分类栏留在首页，
  搜索页不带——那是「浏览信息流」的语义，不是「检索」。
- 文章栏走 `articleApi.getList({ keyword, cursor, pageSize })`，**刻意不传 `sort`**：
  有关键词时后端固定走 ES，排序就是相关性；而 `ArticleSearchService` 目前**根本没把 `sort`
  接进排序数组**（见该类注释），传了也是假的。这也顺手暴露了一个既有缺口：
  以前在搜索状态下切「最新/推荐」tab 结果不会变。
- 用户栏走 `publicUserApi.searchUsers(keyword, page, pageSize)`，传统分页（`page` + `total`），
  与关注/粉丝列表一致。后端只搜 `nickname`，游客可用；登录用户额外拿到 `followed` / `self`。
- tab 状态写进 URL 的 `type` 参数（`article` / `user`），刷新和分享后还停在原栏。
- 两个栏位各自维护「请求序号」：换关键词 / 切 tab 时旧响应必须丢弃，
  否则会把上一个关键词的结果追加进当前列表（首页踩过同样的坑，见上文游标分页那节）。
- `AppHeader` 的搜索框 placeholder 改成「搜索文章或用户」。

**相邻注意**：搜索页文章栏与首页列表是两份独立的游标分页代码。没抽公共 composable 是
刻意的最小改动——首页那套还背着 authSignature / refresh / keepAlive 三个耦合点，
抽出来要动首页，风险大于收益。等搜索页稳定了再考虑抽。

## 钱包充值改成两段式 + 订单列表（2026-10-19）

**行为变化**：点套餐 / 「立即充值」不再直接到账，而是先下单、再打开「模拟支付页」。

- 流程：`createOrder` → `initiatePayment`（拿 `payToken`）→ 模拟支付页（金额 + **支付倒计时**）
  → `mockPay` → **轮询 `getRechargeOrder`** 直到 `PAID` / `CLOSED`。
- **不要凭 `mockPay` 返回就提示充值成功**：真实支付里入账由渠道回调驱动，前端只认轮询到的订单终态。
- 订单状态：`PENDING` / `PAID` / `CLOSED`（旧的 `CANCELLED` 已废弃）。
  关闭原因在 `closeReason`：`TIMEOUT`（超时关单）/ `USER_CANCELLED`（用户取消）。
- 钱包页新增「最近订单」区块：待支付订单可「继续支付」或「取消」；
  订单列表接口失败**不影响**余额/套餐展示（单独 catch，理由同自定义配置那节）。
- `/api/wallet/recharge/notify/**` 是**渠道回调入口**，免登录（靠 HMAC 验签），前端不要调用；
  前端能调的只有 `/mock-pay`（模拟支付页的确认按钮）。
- 倒计时到 0 前端会提示已超时并关闭支付框；服务端那边订单也会被关单任务/惰性关单关掉。

## AI 发起充值 → 支付窗口（2026-10-19）

**行为变化**：AI 助手里的「充值确认」卡不再代表「点完就到账」，而是「创建订单」。

- 用户说「充 10 块」→ Agent 确认卡（按钮文案已改成「创建订单 ¥10」）→ 用户点确认
  → `POST /ai/agent-runs/{id}/write-action/confirm` 返回结构变了：
  `{ message, actionType, result: { orderNo, paymentNo, payToken, payAmount, creditAmount, expireAt, orderStatus } }`
  （原来只返回一句 message 字符串——前端没有 orderNo/payToken 就没法继续支付）。
- 前端拿到 `result` 后**打开与钱包页同一个 `MockPayDialog`**，用户在窗口里点「确认支付」才 `mockPay`，
  然后轮询订单终态；点「取消订单」则订单置 CLOSED。
- **AI 不代付**：`WalletAgentWriteActionExecutor` 只做「创建订单 + 发起支付」，不再调 `mockPay`。
  真实支付下这个位置返回的是收银台参数，用户跳渠道支付。
- 支付成功后侧边余额卡刷新；订单详情在钱包页「最近订单」可查（AI 会话里不重复展示）。
- 支付窗口组件 `components/wallet/MockPayDialog.vue` 由钱包页与 AI 助手共用——
  不要为 AI 再复制一套倒计时 / 轮询逻辑。
- 刷新页面后 Agent run 已 COMPLETED，但待支付订单仍是 PENDING——用户可在钱包页继续支付。


