# Agent 分层任务编排 5b 设计：WORKFLOW_HANDOFF 暂停、确认与恢复

> 日期：2026-10-01  
> 状态：**阶段 0-4 全部完成**——真实页面手测已通过（2026-10-01）；2026-10-02 完成规划边界、恢复状态机、巡检饥饿、原子收敛与 Token 口径补强。  
> 手测暴露的三处「实时那份 ≠ 权威那份」差异已修（卡片不出现 / 卡片消失 / 卡片内容残缺），
> 之后由 Codex 做了一轮展示层修补（前端 `AiAssistant.vue` / `api/ai.ts` 及若干展示与 VO 文件）。  
> 上游：`agent-task-orchestration-design.md` 第五步、5a `WORKFLOW_HANDOFF` 分类契约、Token P3 单调用按需 Prompt 方案  
> 第一版范围：**文章详情页 + `OPTIMIZE_ARTICLE` + 外层 TaskPlan 跨请求暂停/恢复**  
> 评审：Claude 逐条代码核实 + Codex 逐项回应，结论「无需要改变主架构的内容」；本文行内以 **（评审补充）** 标注并入的增补

## 一、结论先行

5b 不重写现有 Workflow，也不把 Workflow 内部步骤拆成 TaskStep。它只增加一个黑盒执行器：

```text
TaskPlan
  -> 前置低风险步骤
  -> WORKFLOW_HANDOFF(OPTIMIZE_ARTICLE)
       -> 创建并关联现有 Workflow Run
       -> 外层 TaskPlan 进入 WAITING_WORKFLOW
       -> 用户继续使用现有 approve / reject / retry / cancel
       -> Workflow 收敛后，后端幂等回填该 TaskStep
  -> 从第一个 PENDING 步骤继续，不重跑已完成步骤
  -> 按真实 StepResult 汇总
```

第一版采用以下方案：

- **后端负责恢复**，前端只展示和刷新，不能承担业务正确性；
- 在现有三张表上增加**最小显式关联字段**，不把关联、游标全塞进 `context_json`；
- 暂不新建完整 `task_runs / task_steps` 表，避免为文章域第一条链提前做平台化；
- 用唯一键、状态 CAS 和恢复巡检保证重复通知不会重复创建 Workflow、不会重复执行已完成写动作；
- `Workflow FAILED` 是**可重试故障态**，不是外层可立即消费的永久终态；只有 `COMPLETED / CANCELLED` 推动外层计划越过该步骤；
- **handoff 之后的后置步骤第一版只允许 `ARTICLE_SEARCH`**（评审补充）：它不调 LLM，Continuation 直接执行、不抢长任务名额。

一句话：**Workflow 继续负责固定流程，Task Orchestrator 只负责在它前后暂停和续跑。**

## 二、为什么现在要做 5b

5a 已经让分类器能稳定表达：

```json
{
  "type": "WORKFLOW_HANDOFF",
  "params": {
    "workflowType": "OPTIMIZE_ARTICLE"
  },
  "goalEvidence": "帮我优化一下"
}
```

（`goalEvidence` 在分类器协议里是**字符串**——`ClassifierPromptBlocks` 的字段契约原文即
`"goalEvidence":"原话连续片段"`，后端 `TaskPlanningFacade` 才兼容转成 `List<String>`。）

实施前的执行链断点如下（现均已在阶段 0-4 与 2026-10-02 补强中收口）：

- `StepType` 已有 `WORKFLOW_HANDOFF`，没有对应 Executor；且规划期就被判「未覆盖」，该类型目前永远进不了 `TaskPlan.steps`；
- 单 Workflow 请求只能显式回退旧链；
- Workflow 与其他目标组合时必须 fail-closed，否则会只执行一半；
- `TaskPlan` 没有正式持久化（`ai_agent_runs.plan_json` 归内层 Agent 的 Plan Preview，与 `TaskPlan` 无关），恢复时拿不到原计划和可信页面锚点；
- 等待态外层 run 仍被保存为 `RUNNING`（`TaskOrchestrationService` 原注释「暂停不是失败也不是完成：停在 RUNNING，等后续能力处理」），
  与“正在占用 worker”语义混淆，**且会被启动恢复当残留 `RUNNING` 改判 `FAILED`**；
- 当前只落“已经回调的步骤”，后续 `PENDING` 步骤没有独立持久化事实；
- Workflow 终态与外层 TaskStep 没有关联，也没有幂等恢复入口。

**两个现存缺陷（评审补充）——它们才是「现在就得改」的具体动因，不是未来预防**：

- **等待态被落成失败**：`TaskOrchestrator` 先回调 listener、再判断 `isWaiting()`（`TaskOrchestrator:62-68`），
  而 `TaskOrchestrationService.toStepStatus` 的 `default -> FAILED` 兜住了 `WAITING_CONFIRM / WAITING_USER`
  （`AiAgentStepStatus` 只有 `RUNNING / SUCCESS / FAILED / SKIPPED`）。
  **也就是：现在的「等待确认」在库里记的是 `FAILED`**，inspection 与前端看到的语义是错的；
- **`used_steps` 收尾口径不一致**：暂停后剩余步骤仍以 `PENDING` 进入 `results()`，
  而 `finishRun` 直接写 `results().size()`（`TaskOrchestrationService:141`）。
  3 步计划停在第 2 步 → `max_steps=3 / used_steps=3 / current_step=2`，两个计数对不上。

所以 5b 的核心不是“再注册一个执行器”，而是第一次让一份 TaskPlan **跨 HTTP 请求、跨用户确认、跨进程重启继续执行**。

## 三、范围

### 3.1 第一版做什么

- 页面范围：`article-detail`；
- Workflow：只开放 `OPTIMIZE_ARTICLE`；
- 支持一个 TaskPlan 中存在一个 `WORKFLOW_HANDOFF`；
- 5b 先接“Workflow + 其他目标”的复合请求；单独“优化这篇文章”继续走已验证的旧 Workflow 链，等复合链稳定后再决定是否统一入口；
- 支持 handoff 前已经接入编排层的文章步骤；
- **支持 Workflow 完成后恢复的后置步骤，第一版只允许 `ARTICLE_SEARCH`**（评审补充）：
  它是确定性短任务、不发起 LLM 调用，因此 **Continuation 直接执行，不重新抢 AI 长任务名额**
  ——依据是 `StepType.isLongRunningLlm()` 只把 `ARTICLE_ANALYSIS / WORKFLOW_HANDOFF` 算长任务
  （枚举注释原话：确定性 / 只读步骤都是秒级完成，让它们去占「8 在跑 + 32 排队」的长任务池，
  只会挤掉真正需要资源的 Agent 任务）；
  规划期必须**拒绝**后置 `ARTICLE_ANALYSIS` / `WORKFLOW_HANDOFF` / `NAVIGATE`：前两者要占长任务池，
  而**在已经结束的 HTTP 请求里无法返回 429**；后者与 §10 的客户端命令冲突；
- 将来若开放后置 `ARTICLE_ANALYSIS`，才需要引入
  `WAITING_RESUME_CAPACITY → admission.submit() → 被拒后保持待续跑 → 巡检延后重试` 这套状态；
  **第一版不提前实现**；
- 复用现有 Workflow 的所有权校验、Action Lock、version/CAS、`Idempotency-Key`、SSE 和确认卡；
- 页面刷新、重复确认、服务重启后仍能从 DB 重建状态。

### 3.2 第一版明确不做

- `CREATE_ARTICLE`；
- 学习域三个 Workflow；
- 钱包域、跨域编排；
- 多个 `WORKFLOW_HANDOFF`；
- 后置 `ARTICLE_ANALYSIS` / `NAVIGATE`（见 §3.1：前者要占长任务池、后者与客户端命令冲突）；
- replan、并行步骤、步骤级自动重试、Reflection；
- 通用 `WAITING_USER` 人机协商恢复协议；
- MQ / Outbox / 分布式调度平台；
- Workflow 内部步骤改造成 TaskStep。

数据结构和关联字段按通用名称设计，但第一版注册表只允许 `OPTIMIZE_ARTICLE`，不能因为字段可扩展就顺手开放其他 Workflow。

### 3.3 CTA 文案：按计划生成，不写死（2026-10-23）

原本文案是固定的「这个请求里有一部分我暂时做不到，先不动手——能拆开一句一句说吗？」。
线上实测（2026-10-23，请求＝点赞 + 收藏 + 优化文章 + 搜 RAG + 比较各篇）用户直接反馈
“太抽象了，我都看不懂”。**问题不在文案长短，而在它没有说出是哪一段做不了。**

现在由 `TaskPlanningFacade#uncoveredCtaReason(plan, intent)` 按计划真实内容生成：

- 已承接的步骤 → 用户语言（点赞 / 收藏 / 优化文章 / 搜索文章 / 分析文章 / 页面跳转…）；
- 未覆盖的类型 → 同一套用户语言；`WORKFLOW_HANDOFF` 优先用分类器的
  `suggestedWorkflowType` 映射中文名（当前 5b 白名单只有 `OPTIMIZE_ARTICLE`，
  换 `CREATE_ARTICLE` 时会按未覆盖处理并显示“创建文章”）；
- 只有一条固定边界需要说明：**需要确认的流程完成之后，目前只能继续做站内搜索**（§3.1）；
- 未知类型、越界 action（如 `changeTitle`）一律用“文章操作 / 其他操作”泛化，
  **不把枚举名或模型原始值暴露给用户**；
- 仍然声明“这条我先不动手”，**不执行半份**，并给出可照做的拆分建议。

换 Workflow 或换任务时句子会跟着变，例如：

- `OPTIMIZE_ARTICLE` + 后置「分析文章」：
  「…「分析文章」排在「优化文章」（需要你确认）之后，流程确认完成后目前只能继续做站内搜索…」
- 未在白名单的 `CREATE_ARTICLE` + 后置「分析文章」：
  「…「创建文章、分析文章」目前没有可靠的能力承接…」

回归由 `TaskPlanningFacadeTests#uncoveredAfterHandoffCtaExplainsInUserLanguage`
与 `#uncoveredCtaAdaptsToAnotherWorkflow` 锁住——文案必须包含用户语言，
且不得出现 `ARTICLE_ANALYSIS` / `WORKFLOW_HANDOFF` 这类枚举名。

## 四、方案选择

### 4.1 方案 A：前端在 Workflow 完成后调用 `/resume`

优点是后端改动少。问题是刷新、断网、页面关闭或客户端漏调都会让计划永久卡住；重复点击还可能触发重复恢复。

**不采用。** 前端可以触发刷新，但不能成为恢复正确性的唯一来源。

### 4.2 方案 B：关联与游标全部写进 `context_json`

优点是不加列。问题是无法建立唯一约束，查询关联 Workflow、CAS 抢恢复权和启动巡检都要解析 JSON；字段拼错也只能运行时发现。

**不采用。** JSON 适合保存快照和展示元数据，不适合承担跨聚合关系和幂等键。

### 4.3 方案 C：现有表增加最小显式关系

这是第一版采用的方案：

- `ai_agent_runs` 保存正式 TaskPlan 快照，并增加明确等待状态；
- `ai_agent_steps` 为每个外层步骤保存稳定 `task_step_id`，预创建 `PENDING` 行；
- `ai_workflow_runs` 保存父 task run / step 关联，并用唯一键阻止重复创建；
- 恢复入口只认 DB 关系，不解析自然语言和前端状态。

它比新建完整任务表轻，又比 `context_json` 方案可靠。等第二个业务域或跨域编排真的接入，再评估独立 `task_runs / task_steps` 表。

## 五、数据模型

### 5.1 `ai_agent_runs`

新增：

```sql
task_plan_json TEXT NULL
```

`task_plan_json` 不复用现有 `plan_json`：

- `plan_json` 仍是 V3.13 的 `List<String>` 展示计划；
- `task_plan_json` 是可恢复的正式执行快照。

建议保存带版本的 envelope：

```json
{
  "schemaVersion": 1,
  "plan": {
    "goal": "点赞这篇文章，再帮我优化一下，完成后搜索 Redis 相关文章",
    "steps": [
      {"stepId": "s1", "type": "ARTICLE_WRITE"},
      {"stepId": "s2", "type": "WORKFLOW_HANDOFF"},
      {"stepId": "s3", "type": "ARTICLE_SEARCH"}
    ],
    "uncoveredGoalEvidence": []
  },
  "anchor": {
    "pageType": "article-detail",
    "articleId": "123"
  }
}
```

`anchor.articleId` 必须来自后端校验过的 `PageContext`，不是分类器参数。快照只保存恢复所需定位值，不保存文章正文等会变化的大对象。

`AiAgentRunStatus` 新增：

```text
WAITING_WORKFLOW
```

它不能复用 `WAITING_WORKFLOW_CONFIRM`：

- `WAITING_WORKFLOW_CONFIRM`：Agent 只提出 Workflow 建议，Workflow 还没创建；
- `WAITING_WORKFLOW`：Workflow 已创建，外层 TaskPlan 正在等待其结果。

### 5.2 `ai_agent_steps`

新增：

```sql
task_step_id VARCHAR(64) NULL
UNIQUE KEY uk_agent_task_step(agent_run_id, task_step_id)
```

创建根 run 时，一次性为正式 TaskPlan 的所有步骤插入 `TASK_STEP` 行：

```text
s1 PENDING
s2 PENDING
s3 PENDING
```

执行时更新同一行，不再每次回调都插入新行。这样“还没执行”是明确事实，不需要通过“查不到记录”猜测。

（评审补充）**落行规则要写准**：`SUCCESS / FAILED / SKIPPED` 都更新同一行——`SKIPPED` 虽然没执行，
但它是一条**执行事实**（现状也是落行的）；只有因暂停“没轮到”的步骤才保持 `PENDING`。

5b 之后，外层 TaskStep 持久化不再是“失败只记日志、执行继续”的旁路审计。它是恢复依据：状态更新失败必须中止后续步骤并让根 run 进入可诊断失败，不能在没有恢复事实的情况下继续产生副作用。内部 Agent 的展示日志仍可保持原来的 best-effort 语义，两者不要混用。

`AiAgentStepStatus` 需要补齐：

```text
PENDING
RUNNING
WAITING_CONFIRM
WAITING_USER
SUCCESS
FAILED
SKIPPED
```

外层 `StepState.DISPATCHED` 仍可在 `output_json.state` 保留精确语义，表级状态继续归入 `SUCCESS`，避免扩大现有 inspection 改动面。

### 5.3 `ai_workflow_runs`

新增：

```sql
task_run_id BIGINT NULL
task_step_id VARCHAR(64) NULL
UNIQUE KEY uk_workflow_task_step(task_run_id, task_step_id)
```

（评审补充）列名用 `task_run_id / task_step_id`：对齐既有 `TaskExecutionContext.taskRunId()`；
**不要用 `parent_*` 前缀**——`ai_agent_runs` 已经有 `parent_run_id`（父子 run 用），
两个 `parent_` 指不同东西，排查时极易混。

普通独立 Workflow 两列都为空；由 `WORKFLOW_HANDOFF` 创建时两列必须同时非空。

唯一键同时解决两个问题：

- 同一 TaskStep 重试不能创建第二个 Workflow；
- 恢复巡检能直接从 Workflow 找回外层 run / step，不解析 `context_json`。

第一版不强加数据库外键，保持现有初始化脚本风格；关联完整性由创建事务、唯一键和巡检共同保证。

### 5.4 为什么暂不新建任务表

长期更干净的形态仍是独立 `task_runs / task_steps`。但当前只有文章域一条纵向链，现有根 `ai_agent_run`、消息关联、步骤 inspection、Token 子树和前端恢复都已可复用。

以下任一条件出现时再拆表：

- 接入第二个业务域；
- 支持多个 Workflow、并行或 replan；
- 任务查询开始大量依赖 `action_type=TASK_STEP` 过滤；
- `ai_agent_runs` 需要继续增加多组只服务任务编排的字段。

## 六、状态机

### 6.1 外层 Task Run

```text
RUNNING
  -> WAITING_WORKFLOW
  -> RUNNING
  -> COMPLETED

基础设施不可恢复异常 -> FAILED
用户取消整个根任务   -> CANCELLED（5b 不新增入口）
```

`WAITING_WORKFLOW` 不占用长任务 worker。进入等待态后，原请求正常结束，后续由 Workflow action 的新请求推动。

根 run 的 `COMPLETED` 表示“计划已经收敛，没有待执行或待确认步骤”，不表示每个 TaskStep 都成功；部分失败仍以各步骤结果和最终汇总为准。

### 6.2 `WORKFLOW_HANDOFF` TaskStep

```text
PENDING
  -> RUNNING
  -> WAITING_CONFIRM     Workflow 正在等待 approve / reject
  -> WAITING_USER        Workflow FAILED，可 retry 或 cancel
  -> SUCCESS             Workflow COMPLETED
  -> FAILED              Workflow CANCELLED 或创建前事实校验失败
```

建议新增确定性结果码：

```text
WORKFLOW_COMPLETED
WORKFLOW_CANCELLED
WORKFLOW_FAILED
```

程序只按 code 和状态判断，不解析 summary 文案。

### 6.3 Workflow 状态映射

| Workflow 状态 | 外层 TaskStep | 外层 TaskPlan | 说明 |
|---|---|---|---|
| `RUNNING` | `RUNNING` | `RUNNING` 或即将等待 | 短暂执行态 |
| 任意 `WAITING_*` | `WAITING_CONFIRM` | `WAITING_WORKFLOW` | 后续步骤保持 `PENDING` |
| `FAILED` | `WAITING_USER + WORKFLOW_FAILED` | `WAITING_WORKFLOW` | 保留现有 retry 能力，不越过该步骤 |
| `COMPLETED` | `SUCCESS + WORKFLOW_COMPLETED` | CAS 回 `RUNNING` 并续跑 | 可消费终态 |
| `CANCELLED` | `FAILED + WORKFLOW_CANCELLED` | CAS 回 `RUNNING`，按依赖规则收尾 | 用户明确放弃该步骤 |

这里刻意不直接复用当前 `WorkflowStatusSupport.isFinished()` 的“FAILED 也算 finished”口径。对会话活跃绑定而言 FAILED 可以算结束；对外层 TaskPlan 而言它仍有合法 `retry`，不能提前把失败永久消费掉。

因此要动的**不是「阻止自动清理」，而是 active Workflow 冲突入口的 `finished` 判据**（评审补充）：
现状 FAILED 时**没有任何代码**清 `activeWorkflowRunId`——清理发生在「下一次创建 Workflow」时，
由 `WorkflowRunManager.checkActiveWorkflowConflict` 看到 `isFinished(status)` 后惰性自愈
（主动清理只出现在 approve→`COMPLETED` 与 cancel）。所以要在那个进口加窄例外：
关联外层任务、且父 run 仍为 `WAITING_WORKFLOW` 的 FAILED Workflow 继续视为当前活跃任务，
不能放行第二个 Workflow；用户 retry 成功或明确 cancel 后再释放绑定。

（评审补充）另有一处不对称要记住：`retry` 成功到 `COMPLETED` 的路径**没有**主动清 session 绑定
（同样靠惰性自愈兜）。因此 **5b 的恢复判定不要依赖 `session.activeWorkflowRunId`**——
按 §7.6 只认 DB 里的父关联。

## 七、执行链

### 7.1 规划与前置校验

**路由判据要显式加一条（实施前核实，2026-10-01）**：注册 `WorkflowHandoffExecutor` 之后，
`TaskPlanner` 的 `registry.supports(type)` 自然转真（`TaskPlanner:85`，**不需要改 Planner**），
于是「帮我优化这篇文章」这类**单一 handoff 请求**会变成「有计划步骤」，
`TaskPlanningFacade.routeTask` 判 `!hasUncoveredGoals()` 后**会进新链**——这与 §3.1
「单独优化继续走已验证的旧 Workflow 链」相冲突。

因此必须在 `routeTask` 里加一条前置判据：

```text
计划只有一步，且该步是 WORKFLOW_HANDOFF，且没有未覆盖目标
  -> LEGACY（继续走旧链，5b 第一版不动已验证的那条路）
```

「handoff + 其他目标」的复合请求才进新链。等复合链稳定后再决定是否统一入口（§3.1）。

进入执行前增加 5b 形状校验：

- 当前页必须是文章详情页；
- 最多一个 `WORKFLOW_HANDOFF`；
- `workflowType` 必须精确等于 `OPTIMIZE_ARTICLE`；
- 不能含 5b 未开放的跨域步骤；
- 被优化文章必须存在且属于当前用户；
- 当前会话没有另一个未完成 Workflow；
- 分类器仍不能提供 `articleId / userId / workflowRunId`。

这些可提前知道的事实必须在任何低风险写步骤执行前校验。否则“点赞 + 优化”可能先点赞，再发现文章不属于自己或会话已有 Workflow，产生本可避免的部分执行。

创建 Workflow 时仍重复执行现有所有权与 active Workflow 冲突检查，防止前置校验和真实创建之间的竞态。

### 7.2 创建根 run 和步骤快照

在一个事务中：

1. 插入根 `ai_agent_runs`，状态 `RUNNING`；
2. 写入 `task_plan_json` 快照；
3. 按计划顺序预创建全部 `TASK_STEP/PENDING` 行；
4. 提交后开始执行。

从这一步开始，DB 已经能回答“计划是什么、执行到哪里、还有哪些没做”。

计数口径同时收紧：`maxSteps = plan.steps.size()`；`currentStep` 是最后一个已开始步骤的计划序号；`usedSteps` 只统计非 `PENDING` 步骤。暂停时不能因为结果列表里带着 PENDING 占位就把 `usedSteps` 写成整个计划长度。

### 7.3 `WorkflowHandoffExecutor`

**复用入口（实施前核实，2026-10-01）**：`AiMessageServiceImpl.streamArticleOptimizeWorkflowFromIntent` 是**私有方法**，
Executor 复用不了它；但它内部只是「构造 `AiWorkflowOptimizeArticleDTO` → 调
`AiWorkflowRunService.createArticleOptimizeWorkflow(dto, emitter)`」，而**后者是公开接口**（`AiWorkflowRunService:24`）。
所以 Executor 直接调 Service 即可，**不需要抽新组件**。

⚠️ **不要再走 `admission.submit`**：外层 TaskPlan 在提交时已经占过 AI 长任务名额，
Executor 在自己的 worker 里再 submit 会**重复占名额**（嵌套准入）。Workflow 自身的 approve / retry
由用户请求驱动、各自走现有准入（§12），与这里无关。

职责保持窄：

```text
校验已登记 workflowType
  -> 从可信 anchor 取得 articleId
  -> 再次校验文章所有权
  -> 按 parent run / step 查已有 Workflow
  -> 没有则复用现有 ArticleOptimizeWorkflowHandler 创建
  -> 返回与 Workflow 当前状态一致的 StepResult
```

它不负责：

- approve / reject / retry / cancel；
- Workflow 内部步骤；
- 外层剩余计划恢复；
- 自己拼 prompt 或额外调用 LLM。

创建时必须在 Workflow 首次 `save` 前写入父关联。若唯一键冲突，重新查询并返回已存在 Workflow，不再创建第二条。

### 7.4 暂停

Workflow 初始链通常会到 `WAITING_PLAN_CONFIRM`。此时应用层在一个事务中：

1. 更新 handoff 对应 TaskStep 为 `WAITING_CONFIRM`；
2. `output_json` 保存 `workflowRunId / workflowType / latestWorkflowStatus / latestWorkflowRunVersion`；
3. 根 run 从 `RUNNING` 更新为 `WAITING_WORKFLOW`；
4. 后续步骤保持 `PENDING`；
5. 原 SSE 正常返回等待摘要和现有 Workflow 卡。

Workflow 创建与外层等待落库目前属于两个事务，无法做到跨聚合绝对原子。第一版接受这一点，但必须依靠父关联唯一键和巡检修复“Workflow 已创建、外层仍 RUNNING”的断点，不能靠人工清库。

### 7.5 Workflow 推进

用户继续使用现有接口：

```text
approve / reject / retry / cancel
```

Workflow 自己的 Action Lock、version/CAS、`Idempotency-Key` 和状态机保持不变。每次状态提交后只产生一个轻量信号：

```text
workflowRunId 已更新，请检查是否需要推进关联 TaskPlan
```

不得把外层恢复逻辑散进五个 Handler 的每个 switch 分支。

### 7.6 后端恢复

新增统一协调组件，例如：

```text
TaskWorkflowContinuationService
```

处理流程：

1. 按 `workflowRunId` 读取 Workflow 和父关联；
2. 没有关联则说明是普通 Workflow，直接返回；
3. 校验父 run、TaskStep、用户、会话关系一致；
4. `WAITING_* / RUNNING` 只刷新快照，不恢复；
5. `FAILED` 把 TaskStep 更新为 `WAITING_USER`，根 run 保持 `WAITING_WORKFLOW`；
6. `COMPLETED / CANCELLED` 用 CAS 抢恢复权：

```sql
UPDATE ai_agent_runs
SET status = 'RUNNING'
WHERE id = ? AND status = 'WAITING_WORKFLOW';
```

7. CAS 成功者从 `task_plan_json` 和步骤行重建 `TaskExecutionContext`；CAS 失败者按幂等成功返回；
8. 在事务外从第一个 `PENDING` 步骤继续，避免把长步骤包进数据库事务；
9. 已 `SUCCESS / FAILED / SKIPPED` 的步骤只作为历史结果和依赖输入，不重新执行；
10. 剩余步骤执行完后，用一个短事务同时提交 handoff 终态、Token 快照、原 assistant message 与根 run 终态；
11. 短事务任一写失败则整体回滚，并以 `RUNNING -> WAITING_WORKFLOW` 条件更新释放恢复权，留给巡检重试。

当前 `TaskOrchestrator.execute(plan)` 会从第一步开始，5b 不能直接复用这个入口。
**恢复能力必须做在协议层**（评审补充），而不只是在应用服务里找起点：

- `TaskExecutionContext` 的结果 Map 现在是私有字段、只有包级 `record()` 供 Orchestrator 调用，
  构造器**无法注入既有结果** → 新增 `TaskExecutionContext.resume(..., Map<String, StepResult> persistedResults)`；
- 新增 `TaskOrchestrator.resume(plan, context, listener)`，从第一个 `PENDING` 步骤继续；
- **不要把「已完成状态」放进 `TaskPlan`**：计划描述目标与依赖，运行结果属于 `ExecutionContext` 与数据库。

不能通过重新执行整份计划碰运气。

## 八、触发、补偿与事务边界

### 8.1 正常触发

推荐使用专门的 Workflow 状态变更事件：

```text
Workflow 状态事务提交
  -> AFTER_COMMIT listener
  -> 新事务调用 TaskWorkflowContinuationService
```

监听器必须捕获异常并记录，不能让“Workflow 已提交成功”表现成 HTTP 失败。

不能直接复用当前 `AfterCommitExecutor` 承载关键恢复写入。它的边界已经明确为 Redis / 内存动作，而且会吞掉 after-commit 异常；5b 的 DB 恢复需要独立事务、独立日志和可补偿入口。

### 8.2 巡检补偿

只靠进程内事件不够：服务可能在 Workflow 提交后、Continuation 执行前崩溃。

第一版增加轻量巡检，不上 MQ：

- 启动时执行一次；
- 运行期低频扫描小批量关联记录；
- 查询从终态 Workflow 出发，联表筛选“父 run 仍 `WAITING_WORKFLOW`”的记录；不能先取最早 20 条等待 run 再过滤，
  否则长期未确认的卡片会把后续可恢复记录永久挤出窗口；
- `FAILED` 只有在 TaskStep 尚未记录同一 `latestWorkflowStatus / latestWorkflowRunVersion` 时才处理，避免巡检反复消费同一个可重试失败快照；
- 调用同一个幂等 ContinuationService；
- 每批限制数量并记录恢复指标。

### 8.3 启动恢复改造

当前启动恢复会把所有 `ai_agent_runs.status=RUNNING` 标为 FAILED。5b 后必须先区分：

- 普通 Agent run / 子 run：继续按现有逻辑标记中断；
- `task_plan_json IS NOT NULL` 的根 Task run：从持久化步骤恢复，不直接判失败；
- `WAITING_WORKFLOW`：合法等待态，不清理；
- 关联 Workflow 若在重启时由 `RUNNING` 被改成 `FAILED`，外层 handoff 进入 `WAITING_USER`，允许用户 retry / cancel。

恢复过程中崩溃时，根 run 会留在 `RUNNING`。巡检只接管 `updated_at` 超过
`blog.ai.task.handoff-recovery-stale-minutes` 的记录（默认 15 分钟），并在条件更新中重复校验旧状态与时间，避免误抢仍在执行的新鲜任务：

- 有终态 Workflow：`RUNNING -> WAITING_WORKFLOW` 后进入统一 Continuation；
- 有未终态 Workflow：复位等待态，继续等用户或 Workflow；
- 没有关联 Workflow：缺少可恢复事实，如实标 `FAILED`。

`WAITING_WORKFLOW` 本身不设置统一过期时间：它可能在合法等待用户确认。生命周期由关联 Workflow 状态驱动；只有异常滞留的 `RUNNING` 使用 15 分钟陈旧阈值。

## 九、失败与依赖语义

5b 不新增第四种依赖策略，继续使用现有：

```text
after + requires + onFailure(CONTINUE / SKIP / CONFIRM)
```

Workflow 取消后：

- `requires` 依赖其输出的步骤：`SKIPPED`；
- `after` + `CONTINUE`：可继续；
- `after` + `SKIP`：跳过；
- `CONFIRM`：第一版不开放通用二次确认恢复，因此规划阶段 fail-closed，不生成这种组合。

第一版不把“Workflow 内部失败后 retry”当成 TaskStep 级重试。Workflow 仍在自己的状态机内 retry；外层 TaskStep 始终是同一条记录。

## 十、客户端命令冲突边界

`OPTIMIZE_ARTICLE` 的完成态是：

```text
FILL_ARTICLE -> COMPLETED -> editorAction(fillArticle)
```

前端收到后会跳到 `/editor/:articleId` 并填充优化稿。因此第一版不能用下面这句作为恢复验收：

```text
优化完成后打开作者主页
```

它会产生两个连续客户端导航命令：先进入编辑器，再跳作者主页，可能让用户离开尚未保存的优化稿。

第一版规则：

- `WORKFLOW_HANDOFF` 后允许用 `ARTICLE_SEARCH` 这类后端只读步骤验证恢复；
- handoff 后的 `NAVIGATE` 组合暂时 fail-closed；
- 不为了演示续跑去改变现有 `fillArticle` 行为。

这不是通用协议限制，只是 `OPTIMIZE_ARTICLE` 当前完成副作用的产品事实。

## 十一、消息、SSE 与刷新恢复

### 11.1 归属

- 原始用户请求只创建一个根 Task run；
- 原 assistant message 继续绑定根 `agentRunId`；
- Workflow 使用同一 `conversationId`，并通过父字段关联具体 TaskStep；
- Workflow 内部日志仍是 `WORKFLOW_STEP`；外层状态仍是 `TASK_STEP`，两者不能混成同一级。

### 11.2 初次暂停

原请求 SSE 依次发送：

```text
TASK_PLAN
TASK_STEP（前置步骤结果）
WORKFLOW_STEP（现有初始流程事件）
TASK_STEP（handoff=WAITING_CONFIRM）
STOP（等待摘要 + workflow 快照）
```

### 11.3 Workflow 后续操作

approve / reject / retry 继续走现有 Workflow SSE。Workflow STOP 中增加可选关联信息：

```text
taskRunId
taskStepId
```

前端收到后刷新根 run 详情并重建 `taskSteps`。恢复正确性已经由后端完成，前端刷新失败不会阻止续跑。

外层计划最终收敛时，更新原 assistant message 的汇总内容，不额外插入一条“幽灵回复”。这样仍保持“一次用户请求 = 一条 TaskPlan 消息”。

页面刷新时：

- `AiMessages.agentRunId` 找到根 run；
- `task_plan_json + TASK_STEP` 恢复外层计划；
- `activeWorkflowRunId` 和父关联恢复 Workflow 卡；
- Workflow 已结束但外层尚未恢复时，详情查询可以触发一次幂等补偿，但不能成为唯一恢复机制。

## 十二、Token、调用次数与准入

5b 本身不新增模型调用：

- 分类器仍是 P3 胜出的单调用按需 Prompt；
- `WorkflowHandoffExecutor` 不调用 LLM；
- `OPTIMIZE_ARTICLE` 内部调用次数与旧链一致；
- 续跑只执行剩余 TaskStep，不重跑分类器和 Workflow 初始链。

Token 保持单一事实源：

- 分类器用量属于原消息；
- Agent 子 run 用量留在 `ai_agent_runs`；
- Workflow 用量留在 `ai_workflow_runs`；
- 根 run 不复制 Workflow token；任务总量查询时聚合根 run 子树和关联 Workflow；
- handoff 的 `output_json` 在初次暂停时固化 `messageRoutingTokens`，终态时同步保存
  `accountedWorkflowTokens / accountedWorkflowVersion`；
- 最终消息不做 `current + delta`，而按绝对公式覆盖：

```text
message.token_count
  = messageRoutingTokens
  + currentRootRun.subtreeTokens
  + workflow.totalTokens
```

- 历史等待记录没有 `messageRoutingTokens` 时，首次续跑用
  `message.token_count - current subtree - accounted workflow` 反推一次，并在同一终态事务中持久化；
- `AgentRunDevDetailVO` 已提供查询派生的 `localTokens / subtreeTokens`；Task CTA 与 handoff preflight 拒绝路径也会落分类器 `routeUsage`。

（评审补充）**聚合口径变更要同步断言**：`TaskOrchestrationBaselineTests` 现有不变量
「所有 run 的 local token 求和 == 子树聚合值」——5b 之后「任务总量」是否把关联 Workflow 纳入聚合，
必须同步改这条断言，否则它会变成**假绿**。既有约定见 `AiAgentRun`「三个口径语义不同、不重复计数」
与 `TaskOrchestrationService`「父 run 不复制子 run token、不充当第二份账」。

进入 handoff 前仍执行长任务准入。暂停后释放 worker；用户 approve / retry 时继续走现有 Workflow 准入，不让一个等待确认的 TaskPlan 长期占用并发名额。

（评审补充）**自动续跑不重新抢名额**：第一版后置步骤只允许 `ARTICLE_SEARCH`（不调 LLM），
Continuation 直接执行即可。这同时避开了一个无解问题——内部自动续跑**无法向已经结束的 HTTP 请求返回 429**。
将来开放后置 `ARTICLE_ANALYSIS` 时再引入 `WAITING_RESUME_CAPACITY` + 被拒保持待续跑 + 巡检重试（见 §3.1）。

## 十三、可观测性

建议日志统一带：

```text
taskRunId
taskStepId
workflowRunId
workflowType
trigger=EVENT|STARTUP|RECONCILE|DETAIL_REFRESH
```

最小指标：

- `workflow_handoff_created_total`
- `workflow_handoff_duplicate_suppressed_total`
- `task_continuation_attempt_total`
- `task_continuation_success_total`
- `task_continuation_recovered_total`
- `task_waiting_workflow_seconds`
- `task_waiting_workflow_stuck_count`

日志必须能区分“Workflow retry 后仍失败”和“Continuation 自己失败”，否则排障会把两套状态机混在一起。

## 十四、安全边界

- `workflowType` 只认后端注册表；第一版只有 `OPTIMIZE_ARTICLE`；
- `articleId` 只认持久化的可信页面锚点；
- 创建和恢复都校验 `workflow.userId == taskRun.userId`；
- `ArticleOptimizeWorkflowHandler.validateArticleOwner` 保留，不能因为前置校验存在就删除；
- 非本人文章在任何前置写动作执行前 fail-closed；
- 前端传来的 `taskRunId / taskStepId` 只用于展示定位，不作为后端授权依据；
- 恢复只消费 DB 中已经关联的 Workflow，不接受任意 workflowRunId 拼接。

## 十五、验收用例

### 15.1 主链

**用例 A：前置动作 + 暂停**

```text
点赞这篇文章，再帮我优化一下
```

预期：

- 点赞只执行一次并落真实结果；
- 创建一个关联的 `OPTIMIZE_ARTICLE`；
- handoff 步骤进入 `WAITING_CONFIRM`；
- 根 run 为 `WAITING_WORKFLOW`；
- 页面刷新后仍能看到点赞结果、等待步骤和 Workflow 卡。

**用例 B：Workflow 后恢复后置步骤**

```text
帮我优化这篇文章，完成后再搜索 Redis 相关文章
```

预期：

- 搜索在 Workflow 完成前保持 `PENDING`；
- reject 重新生成方案时，外层不恢复；
- Workflow `FAILED` 时搜索不执行，用户可 retry；
- 最终 `COMPLETED` 后只恢复一次，搜索执行一次；
- 最终汇总同时包含优化完成和搜索结果。

### 15.2 正确性与恢复

- 重复 approve / 重复终态通知：只一个 Continuation 获得恢复权；
- handoff Executor 重试：只存在一个关联 Workflow；
- Workflow 取消：handoff 失败，后续按 `requires / onFailure` 如实跳过或继续；
- Workflow 失败后 retry 成功：外层先等待，成功后继续；
- Workflow 已完成、服务在 Continuation 前崩溃：启动或巡检恢复；
- 外层恢复过程中崩溃：不重跑已成功的点赞，不重复创建 Workflow；
- 非本人文章：整个组合在前置写动作前拒绝；
- 会话已有活跃 Workflow：整个组合在前置写动作前拒绝；
- 页面刷新：TaskPlan、Workflow 卡、步骤层级和最终汇总均可恢复；
- handoff 后含 `NAVIGATE`：第一版 fail-closed，不连续发送冲突客户端命令；
- **handoff 后含 `ARTICLE_ANALYSIS` 或第二个 `WORKFLOW_HANDOFF`**：**规划期**直接拒绝（评审补充），
  不允许先执行前置写动作、再发现后置步骤没法续跑。

### 15.3 定向验证

实施时优先跑：

- `TaskPlannerTests` / `TaskPlanningFacadeTests`；
- `TaskOrchestrator` 恢复入口测试；
- `WorkflowHandoffExecutorTests`；
- `TaskWorkflowContinuationServiceTests`；
- Workflow approve / reject / retry / cancel 的关联集成测试；
- 启动恢复与巡检测试；
- 前端 TaskStep + Workflow 刷新恢复测试。

本设计不要求写稿阶段跑测试。实施完成后先跑定向测试；由于会改变任务路由和 `WORKFLOW_HANDOFF` 执行边界，上线前仍需按项目规则跑相关评测门，是否跑完整在线集另行确认。

## 十六、分阶段实施建议

### 阶段 0：持久化基座 ✅ 已完成（2026-10-01）

- DDL / entity / enum；
- **两处 schema 必须同时改**：`db/init.sql` 与 `src/test/resources/schema.sql`；
- **手工在本地 MySQL 开发库执行同样的增量 DDL**，并查 `information_schema.columns / statistics`
  确认列与唯一索引真的建上（评审补充）——测试库是 H2、每次由 `schema.sql` 重建，
  **测试全绿完全掩盖不了开发库缺列**，运行时会直接报 `Unknown column`（项目已踩过）；
- `task_plan_json` 快照；
- 预创建 TaskStep；
- inspection 能恢复 `PENDING / WAITING_*`。

**实施记录**：枚举补 `AiAgentRunStatus.WAITING_WORKFLOW` + `AiAgentStepStatus` 的
`PENDING / WAITING_CONFIRM / WAITING_USER`（注明**只服务外层 TASK_STEP**，内部 Agent 步骤边跑边写、
不存在 PENDING）；三条 ALTER 已落两处 schema 并在本地 MySQL 执行 + `information_schema` 双验证；
`createRun` 按计划预创建全部步骤行（`step_no` = **计划序号**）并写 `task_plan_json` 快照；
`StepRecorder` 改为**更新同一行**，落库失败从"记日志继续"改成**抛出中止**；
`toStepStatus` 改成**穷尽 8 个 `StepState`、不留 `default`**——顺手修掉"等待确认被记成 FAILED"的现存缺陷；
`used_steps` 只数非 PENDING、`current_step` 用计划序号、`max_steps` 保留 `max(1, ...)`。
inspection **无需改动**（外层 `TASK_STEP` 在 `toStep()` 里提前返回，新状态原值透出）。

### 阶段 1：handoff 创建与暂停 ✅ 已完成（2026-10-01）

- `WorkflowHandoffExecutor`；
- Workflow 父关联和唯一键；
- 前置事实校验；
- `RUNNING -> WAITING_WORKFLOW`。

做到这里先验证用例 A，不急着续跑。

**实施记录**：
- **执行器**：直调公开接口 `AiWorkflowRunService.createArticleOptimizeWorkflow(dto)`（**不抽新组件**）；
  **不走 `admission.submit`**（外层已占名额，嵌套会重复占）；先按父关联查、再创建（幂等）；
  状态映射按 §6.3，其中 **`FAILED` → `WAITING_USER`**（可重试）而不是永久失败。
- **父关联**：走 `AiWorkflowOptimizeArticleDTO` 新增的 `taskRunId / taskStepId`，由
  `createArticleOptimizeWorkflow` 在**首次 save 之前**写进 run——其它四个 Workflow 路径零改动
  （它们的 dto 里这两个字段是 null，行为不变）。
- **规划期校验**：`TaskStepParamValidator` 给 `WORKFLOW_HANDOFF` 加 fail-closed
  （params 只能含 `workflowType`，且必须在 5b 白名单内）。这里刻意比"未知键一律放过"更严：
  对协议字段来说，多余键（`articleId / topic / planRef`）是**模型夹带业务定位值**的信号。
- **前置事实校验**：落在 `WorkflowHandoffPreflight`，由**聊天链路在 ORCHESTRATE 分支调用**
  （它需要 pageContext + userId + sessionId；拒绝即走现成的 CTA 分支）。
  校验内容是白名单 / 可信锚点 / 文章归属 / 会话无活跃 Workflow——放进执行器就晚了，
  那时排在前面的点赞已经执行过。
- **路由判据**：`TaskPlanningFacade` 新增"单一 handoff → LEGACY"。不写这条，注册执行器之后
  `registry.supports(WORKFLOW_HANDOFF)` 转真，"帮我优化这篇文章"会进新链，偏离 §3.1。
- **白名单共用**：`WorkflowHandoffTypes`（规划期校验、前置校验、执行器三处同一份，避免"一处放行一处拦"）。

**阶段 1 的取舍已在阶段 3 收口（2026-10-01，手测驱动）**：Workflow 内部步骤事件**已转发**到外层 SSE。
`TaskStepEventSink` 增加 `emitWorkflowStep(...)`（default 方法，不破坏既有实现），
聊天流的 sink 实现它并推 `WORKFLOW_STEP`；`WorkflowHandoffExecutor` 用适配器把
`AiWorkflowStepEmitter` 接到它上面，runId 靠 `bindWorkflowRunId` 入库后回填（与旧链同一套路）。

> 当初用 `noop` 的理由是"它属于展示完整性，可以延后"——**手测证明这个判断是错的**：
> 初始链要跑几十秒，这期间一个事件都不发，用户看到的是"点了发送、什么都没发生，
> 然后突然冒出一张卡"。更麻烦的是长时间静默的 SSE 更容易被中间环节掐断，
> 连 handoff 完成时那条 `TASK_STEP` 都可能到不了前端（表现：任务步骤少一行，刷新才有）。
> 教训：**"展示完整性"和"链路健康"在长连接里是同一件事**——事件不只是给用户看的，它同时是保活。

### 阶段 2：终态回填与续跑 ✅ 已完成（2026-10-01）

- `TaskWorkflowContinuationService`；
- CAS 抢恢复权；
- 从持久化结果恢复 `TaskExecutionContext`；
- 更新原 assistant message。

做到这里验证用例 B。

**实施记录**：
- **协议层**（§7.6 要求的"恢复能力做在协议层"）：`TaskExecutionContext.resume(...)` 预置历史结果 +
  `TaskOrchestrator.resume(...)` 从第一个没有结果的步骤继续。`execute` 与 `resume` 共用
  `runSteps(..., skipCompleted)`，**不做成"重跑整份计划靠执行器幂等"**——幂等是执行器的可选属性
  （客户端导航就没有幂等可言），放在协议层才对所有执行器一致成立。
- **历史结果必须"预置"而不是"跑到一半再补"**：`requires` 的判定发生在每一步**执行之前**，
  补晚了会让一个已成功的前置步骤把后置步骤拖成 SKIPPED。这条已写成测试。
- **快照编解码独立成 `TaskPlanSnapshotCodec`**（读写同一个类）：写端比读端早好几周，
  少存一个字段代码照样跑、只是恢复出来的计划少一条判据——最难发现的那种错。
  同时**补上了 `uncoveredTypes`**：它是路由判据与"不得谎报完成"的依据，原快照只存了证据文本。
- **恢复协调者** `TaskWorkflowContinuationService`：幂等入口 `onWorkflowAdvanced(workflowRunId)`，
  事件 / 巡检 / 详情补偿都走它；CAS 抢恢复权只靠一条带条件的 UPDATE（**不上分布式锁**——
  恢复的唯一性它已经能提供，而锁会引入"锁住了但进程挂了"的新问题）。
  分支：`WAITING_* / RUNNING` → 只刷新快照；`FAILED` → 步骤转 `WAITING_USER`、根 run 保持等待；
  `COMPLETED / CANCELLED` → CAS + 续跑。
- **`WorkflowHandoffResults`**：Workflow 状态 → StepResult 的映射只留一份（执行器与恢复服务共用）。
  两处各写一遍的话，"FAILED 算什么"这种判断会漂移。
- Token 收敛（§12）：暂停时固化 `messageRoutingTokens`，终态按“路由基线 + 当前 run 子树 + Workflow 总量”绝对重算；
  不再依赖跨请求增量相加。

**实施中踩到并修掉的两个坑（都写进代码注释了）**：
1. **`output_json` 是同一列，不能分两次写**。终态回填与 token 入账各写一次时，
   后写的那次拿**内存里的陈旧基线**做合并，把先写的终态冲掉——结果 `status=SUCCESS`
   但 `summary` 还停在"等你确认"。现已合并为一次写入，且顺序放在续跑之后
   （续跑崩了就保持"等确认" + 根 run 复位等待态，巡检重来时两者一致；
   反过来先标终态会出现"步骤已完成、任务还在等"的错位）。
2. `StepResult` 的构造器用 `Map.copyOf` 收紧 output，而**它对 null 值直接抛 NPE**：
   `latestWorkflowRunVersion` 在 version 未设置时会让整个步骤炸掉。映射里已改为不放 null。

### 阶段 3：补偿与前端恢复 ✅ 已完成（2026-10-01）

- AFTER_COMMIT 触发；
- 启动恢复和低频巡检；
- ~~Workflow STOP 带父关联~~ → 改为前端主动刷新（差异见下）；
- 前端刷新根 run 详情。

**实施记录**：
- **触发收口**：`AiWorkflowRunServiceImpl` 的 5 个状态推进出口（创建 / approve / retry / …）
  统一改走 `persistAndNotify(run)`——先 `updateById`，再发 `WorkflowAdvancedEvent`。
  分散去发事件必然漏一处，而漏掉的那一处表现是"用户点了确认、外层计划永远不继续"。
- **AFTER_COMMIT**：`WorkflowHandoffResumeListener` 用
  `@TransactionalEventListener(AFTER_COMMIT, fallbackExecution = true)`。
  `fallbackExecution` **不能省**：Workflow 的保存不一定在事务里（某些路径是单条 UPDATE 自动提交），
  不加这个开关，那些路径的恢复会被**静默跳过**。监听端捕获异常——Workflow 已经提交成功，
  恢复失败不能反过来把一次成功的操作表现成失败，兜底交给巡检。
- **启动恢复**：`AiRunStartupRecoveryRunner` 那句"把 RUNNING 全判死"补了
  `.isNull(task_plan_json)`：外层 Task run 有步骤快照、恢复依据还在，判死等于让用户重来。
  `WAITING_WORKFLOW` 本来就不匹配 `RUNNING`，天然不会被清掉。
- **低频巡检** `TaskHandoffReconciliationTask`（60s，每批 20 条）扫两类：
  ① 从终态 Workflow 联表筛选父 run 仍在 `WAITING_WORKFLOW` 的记录 → 去续跑；
  ② 被启动恢复刻意跳过的 `RUNNING` + 有快照的根 run → 有终态 Workflow 则复位等待态后续跑 /
  未终态则复位等待态 / **无关联 Workflow 则如实失败**（第一版边界：不恢复"崩在中途写步骤"的任务）。
  第 ② 类只处理超过默认 15 分钟的陈旧记录，并用状态 + `updated_at` 条件更新防误抢。
- **前端**：`AiAssistant.vue` 新增 `refreshBoundTaskSteps`，在 Workflow 流式结束的终态分支调用，
  用消息上的 `agentRunId` 重拉 `getAgentRunSteps` 重建 `taskSteps`（原消息上的 `agentRunId` 一直有，
  所以前端不依赖新协议字段也能定位根 run）。
  **与设计稿的差异（有意）**：原方案是"`WORKFLOW_STOP` 带 `taskRunId` / `taskStepId`"，
  实施改成前端主动刷新——一次用户请求只对应一个根 run，反查是确定的，
  为省一次反查去改 SSE 契约不划算。**若将来一条消息可能对应多个根 run，必须回到原方案。**

### 阶段 4：评测与激活

- 定向单测 / 集成测试；
- 5a 正反例回归；
- 两条真实页面手测；
- 先保持可回退开关，再切 ACTIVE。

## 十七、回滚

建议保留配置开关：

```text
blog.ai.task-orchestration.workflow-handoff-enabled=false
```

关闭后：

- 单一 `OPTIMIZE_ARTICLE` 可继续走已有 Workflow 旧链；
- Workflow 与其他目标的组合继续 fail-closed 到 CTA；
- 已创建并处于 `WAITING_WORKFLOW` 的根任务不能被开关直接遗弃，Continuation 和巡检仍需继续处理存量；
- 新增字段保留，不做破坏性回滚。

## 十八、Go / No-Go

满足以下条件才进入 ACTIVE：

- 两条主链验收通过；
- 重复终态通知不会重复点赞、重复搜索或重复创建 Workflow；
- FAILED -> retry -> COMPLETED 能继续外层计划；
- 重启断点可恢复；
- 非本人文章和 active Workflow 冲突在前置动作前被拒绝；
- 前端刷新后不丢 TaskStep / Workflow 层级；
- Token 聚合无重复；
- 5a 分类正反例与相关在线评测门无回归。

任一项不满足则保持开关关闭，单 Workflow 继续旧链，复合请求继续 CTA，不允许“先执行一半再假装完成”。

## 十九、面试口径

这一步可以讲清三个真实工程问题：

1. **长事务不是长任务状态**：等待用户确认时必须释放线程，把状态持久化为可恢复状态机。
2. **跨聚合不强求一个大事务**：Task Run 和 Workflow Run 各自提交，用显式关联、唯一键、CAS 和巡检实现最终收敛。
3. **FAILED 不一定是业务终点**：同一个状态对“会话活跃绑定”和“外层计划能否越过该步骤”可能有不同语义，必须按消费者定义状态谓词。

最终结构仍是：**Plan-and-Execute 管全局目标，ReAct 管局部动态判断，Workflow 管固定多阶段流程，后端状态机负责暂停和恢复。**
