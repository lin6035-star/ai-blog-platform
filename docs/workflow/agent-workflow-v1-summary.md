# Agent Workflow V1 Summary

> Updated: 2026-08-25

本文记录 `01myBlog` 当前文章创作 Workflow V1 的完成状态，方便后续复盘、继续开发和面试讲解。

## 已完成能力

- 文章创作意图入口：用户说“帮我写一篇关于 XX 的文章/博客”时，后端稳定进入 Workflow，不再走普通聊天直接生成正文。
- 持久化运行状态：`ai_workflow_runs` 记录 workflow 类型、版本、状态、当前步骤、上下文、错误和 token 预留字段。
- 会话级 active workflow：`ai_sessions.active_workflow_run_id` 标记当前会话正在进行的 Workflow，刷新页面后可恢复。
- 用户确认闭环：支持大纲确认、草稿确认、填充确认、完成、取消、反馈重写。
- Step Log 执行轨迹：`ai_workflow_step_logs` 记录每个 step 的状态、耗时、输入摘要、输出摘要、错误信息和重试次数。
- SSE 流式体验：大纲和草稿支持流式输出，step 状态也会通过 Workflow SSE 推送。
- 失败与重试：Workflow 可进入 `FAILED`，支持按当前失败 step 重试。
- 历史数据生命周期：Workflow 完成/取消/失败时保留历史；删除单条消息或整个会话时，联动清理对应 workflow run 和 step logs。

## 核心表

- `ai_workflow_runs`：Workflow 主表，一条记录代表一次文章创作任务。
- `ai_workflow_step_logs`：步骤执行日志表，记录 `REQUIREMENT_ANALYZE`、`MEMORY_RETRIEVE`、`RAG_SEARCH`、`GENERATE_OUTLINE`、`GENERATE_DRAFT`、`QUALITY_CHECK`、`FILL_ARTICLE` 等步骤。
- `ai_sessions.active_workflow_run_id`：当前会话正在进行的 Workflow。
- `ai_messages.workflow_run_id`：AI 消息与 Workflow 的历史关联，用于删除消息/会话时清理 Workflow 数据。

## 当前主链路

```text
用户提出写作需求
  -> 意图识别 / 后端兜底判断
  -> 创建 ai_workflow_runs
  -> REQUIREMENT_ANALYZE
  -> MEMORY_RETRIEVE
  -> RAG_SEARCH
  -> GENERATE_OUTLINE
  -> 用户确认或反馈修改大纲
  -> GENERATE_DRAFT
  -> QUALITY_CHECK
  -> 用户确认或反馈修改草稿
  -> FILL_ARTICLE
  -> 用户保存/发布
  -> COMPLETED
```

取消链路：

```text
用户取消 Workflow
  -> status = CANCELLED
  -> 清空 active_workflow_run_id
  -> 历史消息仍可查看
```

删除链路：

```text
删除单条 AI 消息
  -> 删除 ai_messages
  -> 如果没有其他消息引用该 workflow_run_id
  -> 删除 ai_workflow_step_logs
  -> 删除 ai_workflow_runs

删除整个会话
  -> 收集会话下所有 workflow_run_id
  -> 删除 ai_messages
  -> 删除 ai_workflow_step_logs
  -> 删除 ai_workflow_runs
  -> 删除 ai_sessions
```

## 后续可优化

- 补强回归测试：覆盖创建、确认、反馈、重试、取消、删除消息、删除会话等关键链路。
- 第二个 Workflow：已确定优先做 `ArticleOptimizeWorkflow`（文章优化工作流），用来验证哪些逻辑需要抽成通用 Workflow Runtime。

---

## V1 稳定化完成（2026-08-25）

Workflow V1 正式关闭。验证计划：`docs/superpowers/plans/2026-08-25-workflow-v1-stability-validation.md`。

### 三层防护（并发 / 幂等 / 恢复）

```text
Redis 动作锁（防并发执行）
  + DB version CAS（防状态过期覆盖）
  + Idempotency-Key 结果重放（防网络重试重复执行）
  + 持久化 run 状态（刷新可恢复）
```

- **WorkflowActionLock**：SETNX + 120s TTL + Lua 校验释放；Redis 故障 fail-open（DB version 兜底）。approve / reject / retry / cancel 统一走 `claimWorkflowAction`：锁前归属校验 → 锁内重读 → `claimAction`（version+1 WHERE version=?）→ CAS 失败抛 BusyException → 内存对象同步 version。
- **WorkflowActionIdempotency**：SHA-256 key + fingerprint(action+payload)，24h TTL；同 key 不同 payload 抛 409；读写故障降级放行。approve / reject / retry 包装方法先查缓存，命中直接返回，不重复执行。
- **前端 Key 生命周期**：sessionStorage 按 workflowId+action+payload 持久化；网络失败复用 Key，成功 / 业务 FAILED 清除。
- **Agent Router V1（topic 幻觉防护最终版）**：LLM 逐字摘录 topicEvidence → 后端 `validateTopicEvidence`（normalize 后原话 substring 校验 + 泛词表过滤）→ 证据不真实一律 WAITING_REQUIREMENT_CONFIRM。修掉旧 extractTopic 启发式可被"一篇文章"绕过的洞。

### 测试

- `WorkflowActionLockTests` 2/2、`AiWorkflowRunVersionTests` 2/2、`AiWorkflowRunServiceTests` 19/19（version claim / cancel 清绑定 / 幂等缓存命中）
- `CreateArticleWorkflowHandlerTests` 6/6（泛化请求 / 具体主题 / 幻觉证据被拒 / 泛词过滤）
- 前端 `vue-tsc` 零错误

### 已知边界（保留，暂不处理）

```text
Redis 锁 TTL 120s：超过 TTL 的长 LLM 执行需续期或加长 TTL（当前 LLM 步骤均 < 60s 超时，未触发）
幂等结果保留 24h：过期后同 Key 重放会重新执行（罕见，可接受）
cancel 无结果重放：响应为空，同 Key 重试幂等由"终态快速返回"兜底
```
- 通用抽象：第二个 Workflow 先只抽最小 `WorkflowHandler` / workflowType 分发；等至少两个 Workflow 跑通后，再决定是否继续抽 `WorkflowContext`、`WorkflowStepLogger`、独立 Step 接口等能力。
- 可观测性深化：后续可补真实 token 统计、模型参数 metadata、失败类型分类和耗时统计。

## 面试讲法

我这个项目里一开始已经有普通 AI 聊天和 Tool Calling，比如跳转页面、查询文章、填充编辑器等。但我后来发现，像“帮我写一篇文章并填到编辑器里”这种任务不适合只靠一次 Tool Calling 完成，因为它不是一个瞬时动作，而是一个多步骤、高影响、需要用户确认的过程。如果普通聊天直接生成正文或者直接调用 `fillArticle`，用户没有机会确认大纲、修改草稿，也很难在刷新页面、失败重试、取消任务后恢复现场。

所以我单独做了一套文章创作 Workflow。它的核心不是“让 AI 多调用几个工具”，而是把一次文章创作变成一个有持久化 run 身份的任务。后端用 `ai_workflow_runs` 保存当前状态、当前 step、上下文 JSON 和错误信息；用 `ai_sessions.active_workflow_run_id` 记录某个会话当前正在进行的任务；用 `ai_workflow_step_logs` 记录每一步的输入摘要、输出摘要、耗时、状态和错误。这样前端刷新后还能恢复当前 Workflow，后端也能知道用户下一句话应该继续交给 Workflow，而不是重新走普通聊天。

当前文章创作链路是固定流程：先识别写作意图，再创建 Workflow run，然后依次执行需求分析、长期记忆召回、RAG 检索、生成大纲。大纲出来后不会直接写正文，而是停在确认点，让用户同意或提出修改意见；用户确认后再生成草稿并做质量检查；草稿确认后才生成编辑器填充动作。也就是说，`fillArticle` 这种高影响动作只能从 Workflow 的确认链路里出来，普通聊天路径会拦掉它，避免 AI 绕过用户确认直接改编辑器。

可靠性这块我也做了几个闭环：Workflow 支持 `FAILED` 状态和 retry，可以从失败 step 原地恢复；支持 cancel，取消后清空 active workflow，不影响下一次任务；StepLog 支持前端执行详情展示，方便看清楚卡在 Memory、RAG、生成大纲还是质量检查；大纲和正文支持 SSE 流式输出，用户能看到生成过程。历史数据方面，Workflow 完成、取消、失败时不会立刻删库，因为历史消息还要展示；但用户删除单条 AI 消息或删除整个会话时，会联动删除对应的 workflow run 和 step logs，生命周期跟用户历史保持一致。

如果面试官问为什么不直接上通用工作流框架，我会说这是项目里的第一个 Workflow，所以我先做固定流程，把状态持久化、确认点、失败重试、刷新恢复、执行轨迹这些工程化能力做扎实。第二个 Workflow 会先做文章优化：读取已有文章、分析问题、生成优化方案、用户确认、生成优化草稿、质量检查，再填充编辑器。这样能验证 `ai_workflow_runs`、Step Log、retry、cancel、confirm、context 是否能跨流程复用，再根据真实重复点抽象 `WorkflowHandler`、`WorkflowContext`、`WorkflowStepLogger` 之类的通用组件。这样不是为了抽象而抽象，而是先用真实业务流程验证边界。
