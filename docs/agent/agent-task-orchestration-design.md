# Agent 分层任务编排设计（第三刀：TaskPlan + Orchestrator）

> 日期：2026-09-29
> 状态：**文章域纵向切片、C0、`ARTICLE_ANALYSIS` 父子 run、`WORKFLOW_HANDOFF` 5a/5b 均已完成**；2026-10-02 补齐规划边界、恢复状态机、巡检与 Token 收敛
> 上游：`agent-goal-completion-loop-design.md`（第一刀）、`agent-stage2-action-plus-analysis-design.md`（第二刀）
> 定位：把「一个请求 = 一个动作」改成「一个请求 = 一份可调整的粗计划」，步骤按类型分派给确定性执行器 / ReAct / Workflow / 客户端命令。

## 〇、结论先行

前两刀走的都是「**一种组合 = 一条分支**」的路子：第一刀硬编码「同域低风险动作」，第二刀硬编码「动作 + 分析」。**这条路补不完**，而且没覆盖到的组合会静默丢一半或整个 CTA——下面三个反例就是实证。

**改成分层**：

```text
统一任务编排内核（第一版只在文章详情页启用）
  -> TaskPlan：粗粒度目标列表（用户想完成哪几件事）
  -> TaskOrchestrator：顺序 / 数据依赖 / 失败策略
  -> StepExecutor Registry：按 step type 分派
  -> StepResult[]：每步的真实结果
  -> 汇总：只能依据真实结果说话
```

**一句话**：Plan-and-Execute 负责全局，ReAct 负责局部动态判断，Workflow 负责固定流程，确定性执行器负责真实写操作。

这里必须区分两件事：**协议目标是通用编排**，但**第一版 rollout 不是全局能力切换**。
第一版只验证文章详情页的三个执行器；学习域、钱包域、Workflow 和文章分析仍走原有路径。
因此第一版不能宣称“所有多目标请求都已支持”，只能宣称“建立了可扩展的统一任务协议，并完成文章域第一条纵向切片”。

**这不是"预埋通用协议"，是前两刀的路线已经被反例证伪**——每加一种组合就多一条分支，且做不到的组合没有任何兜底。

## 一、问题：三个反例（真实手测）

| # | 用户说 | 实际发生 | 类型 |
|---|---|---|---|
| 1 | 点赞这篇文章，再跳转到该作者的个人主页 | **整个 CTA**，还答非所问（"比如想调整学习计划、写新文章"） | 组合未覆盖 → 全丢 |
| 2 | 点赞这篇文章，再帮我找找还有没有关于 Redis 的文章 | **只点赞** | 第二个目标**静默消失** |
| 3 | 点赞这篇文章，再分析一下它写得怎么样 | 已支持（第二刀） | 唯一被硬编码覆盖的组合 |

**反例 2 最严重**：它正是第一刀设计稿里写要消灭的「A 类错误——目标丢失」，在自己的地盘上又发生了一次。

### 根因：结构上只能装一件事

- `AiIntent` 是单个 `intent` + 单个 `requestedActions`
- `AgentDecision` 是单个 `action` + 单个 `articleActionBatch` + 单个 `followUpGoal`
- Planner 是「判断这是什么组合 → 走哪条分支」

**所以任何"两件事"的请求，要么被硬编码特例覆盖，要么掉一半。**

## 二、为什么是分层，而不是继续加分支

成熟产品的 Agent 一般不是「一句话 → 一个工具 → 回复」，也不是「一次性生成完整计划 → 机械执行到底」，而是：

```text
理解目标 -> 生成粗粒度计划 -> 执行当前可执行步骤 -> 拿到真实结果
  -> 继续执行或调整计划 -> 风险步骤进确认 -> 汇总
```

**计划是"方向和目标"，不提前写死每次工具调用**，因为执行中会冒出：目标不存在 / 无权限 / 结果为空 / 上一步输出改变下一步输入 / 工具失败 / 用户改口 / 需要确认。

**ReAct 适合"需要临场判断"的步骤，不适合承担全局编排。** 让 `ArticleAgentRuntime` 直接扛整个请求，它就得同时理解点赞、作者关系、搜索、导航、分析、汇总——**这正是当前"大 Runtime + 特殊字段"问题的来源**。

## 三、核心模型

### 3.1 TaskPlan / TaskStep

```java
record TaskPlan(
        String goal,                 // 用户原始请求，不是模型改写后的摘要
        List<TaskStep> steps,
        List<String> uncoveredGoalEvidence // 已识别但当前能力不支持的目标证据
) {}

record TaskStep(
        String stepId,
        StepType type,                 // 封闭集合，见 §五
        Map<String, Object> input,     // 后端校验过的参数，不接受 LLM 自报 ID
        StepDependency dependency,
        List<String> goalEvidence      // 模型摘录的用户原话片段，后端验证后才进入正式计划
) {}
```

**步骤粒度规则（重要）**：一个 `TaskStep` = **用户能一眼看懂的一件事**。

- ✅ 点赞这篇文章 / 跳转到作者主页 / 找 Redis 文章 / 分析这篇文章
- ❌ 查作者 ID / 读取文章 / 拼装 prompt

**中间的准备动作留在执行器内部**，不单独成步。理由：计划长度要跟用户的心智模型对齐——用户说了两件事却看到三步计划，会问"查作者 ID 是啥"；而且一旦允许拆细节，读文章、拼上下文这类都会冒出来，计划会碎掉。

不单独成步不等于不留痕：执行器必须把内部解析出的 `authorId`、解析失败原因和最终导航命令写入该步骤的结构化 `output` / `error`，
否则后端仍无法解释“为什么导航没有发起”。

> 这条规则直接修正了「点赞 + 跳转」的建模：`NAVIGATE_USER_PROFILE` **自己负责**查 authorId，而不是前面多一个 `ARTICLE_QUERY_OWNER` 步骤。

### 3.2 StepDependency：三分类，不是一个 dependsOn

| 字段 | 含义 | 例子 |
|---|---|---|
| `after` | **顺序要求**——用户明确说了先后 | "先点赞，再搜索"（搜索其实不依赖点赞结果） |
| `requires` | **数据依赖**——没有上一步的输出就做不了 | 跳转作者主页需要 authorId |
| `onFailure` | **失败策略**——前序失败后本步怎么办 | `CONTINUE` / `SKIP` / `CONFIRM` |

**三者不能合并成一个 `dependsOn`**：

- `after` 是用户的表达顺序，去掉它结果可能一样，但用户会认为"你没按我说的做"
- `requires` 是硬约束，缺了必须跳过并把原因写进结果
- `onFailure` 决定后续步骤的走向，"点赞失败还要不要搜索"必须显式定义

### 3.3 StepResult

```java
record StepResult(
        String stepId,
        StepState state,        // PENDING / RUNNING / SUCCEEDED / FAILED / SKIPPED / DISPATCHED / WAITING_CONFIRM / WAITING_USER
        StepCode code,          // APPLIED / ALREADY_SATISFIED / TARGET_NOT_FOUND / FORBIDDEN / CONFLICT / INTERNAL_ERROR
        String summary,         // 只用于展示
        Map<String, Object> output  // 供后续步骤的 requires 消费
) {}
```

**`DISPATCHED` 是新增的、也是必须的**：客户端步骤（导航 / 滚动 / 复制链接）由前端执行，**后端不知道它有没有成功**。它只能算「已发起」，不能算 `SUCCEEDED`。如需判断真正完成，必须另行设计客户端回执，不能在本协议里偷换语义。

步骤状态还必须保留暂停语义：`WAITING_CONFIRM` / `WAITING_USER`。它们不是失败，也不是完成。
任一步骤进入等待态，**整个 TaskPlan 暂停，后续步骤保持 `PENDING`，不继续执行**。
5b 已把 Workflow 终态后的外层恢复接通；第一版恢复出口只允许后置 `ARTICLE_SEARCH`，其余后置类型在规划期 fail-closed。

这条沿用项目已有的口径（`AgentStepActionType` 注释里的「工具返回"已准备点赞"不等于业务成功」）。**丢掉这个区分，汇总就会说谎**。

### 3.4 StepExecutor Registry

步骤执行不由 Orchestrator 写一条巨大的 `switch`，而由封闭类型到执行器的注册表分派：

```java
interface TaskStepExecutor {
    StepType supports();
    StepResult execute(TaskExecutionContext context, TaskStep step);
}
```

`TaskStepExecutorRegistry` 只负责 `StepType -> executor` 的单点登记和查找，不负责意图判断、计划生成或失败策略。
Orchestrator 负责传入已校验的页面上下文、前序输出和用户身份；执行器负责本步骤的事实校验与真实结果返回。

## 四、执行流程

```text
用户请求
  -> 全局任务规划（第一版可与分类器共用一次模型调用）产出 TaskPlanCandidate
  -> 后端校验：白名单 / 对象存在 / 权限 / 冲突 / 每个步骤证据在原文中
  -> 后端生成正式 TaskPlan（只包含通过校验的步骤和依赖）
  -> Orchestrator 按 steps 顺序：
       取下一个可执行步骤（after 已满足、requires 已就绪、按 onFailure 决定继续 / 跳过 / 等待）
       分派给该 type 的执行器
       拿到 StepResult，落 step 行（复用 ai_agent_run / ai_agent_step）
       根据 onFailure 决定继续 / 中止
  -> 全部结束 → 汇总（只读 StepResult[]）
```

**汇总规则**（沿用第一刀，扩展到 N 步）：

| 情况 | 文案 |
|---|---|
| 全部 SUCCEEDED（或 DISPATCHED） | 逐项陈述 |
| 部分成功 | 成功的说成功，失败的**说明原因** |
| 全部失败 | 逐项说明，不伪装 |
| 有 SKIPPED（requires 未满足） | **必须说明为什么跳过**——"跳转主页未执行，因为没有拿到作者信息" |

**禁止**：只讲第一步成功就收尾（这正是反例 2 的行为）。

### 4.1 审计归属必须先定

第一版由 `TaskOrchestrator` 创建并拥有**一个外层 Agent Run**，所有 `TaskStep` 都落在这个 run 下。现有
`ArticleInteractionBatchService` 和 `ArticleAgentRuntime` 当前都会自行创建 run；它们不能未经改造直接作为
Orchestrator 的执行器，否则一次用户请求会产生多个互不相连的用户可见 run，`stepNo`、恢复和最终结果都会分叉。

执行器需要提供“复用外层 run 的执行入口”，或明确把内部 run 标记为不可见的子执行记录；不能把嵌套 run
问题留到实施期再决定。

这里还要区分两类步号：外层 TaskPlan 的 `taskStepId`，以及 `ARTICLE_ANALYSIS` 内部 ReAct 的 `stepNo`。
两者不能共用一个连续计数，否则一次分析内部的 `QUERY_ARTICLE / QUERY_MEMORY / FINAL_ANSWER` 会污染全局任务进度，
也会错误触发外层的最大步骤限制。

**结论（2026-09-29 spike，静态分析）：接入现有 Runtime 时采用带 `parentRunId` 的不可见子 run，放弃"内部明细作为步骤结构化输出"。**

**但这是「当前代码下的落地拓扑」，不是最终任务模型。** 长期更干净的形态是 `TaskRun` / `TaskStep` 独立表，
`AiAgentRun` 退回到它本来的语义——「某个步骤内部的一次领域 ReAct 运行」，而 `TaskRun` 才是「跨执行形态的用户任务」。
那需要新增表和更多前后端关联，第一刀不做；但不能因为现在用父子 run，就往 `ai_agent_runs` 上不断加
`run_kind` / `visible` / `root_run_id` / `task_step_id` 这类字段——那是把两种概念揉进一张表。

依据是 `AbstractAgentRuntime` 对 run 的写操作共 **12 处**（8 次 `updateById` + 3 次 wrapper update + 1 次 `insert`），
且改的都是进度状态本身——`setUsedSteps` / `setCurrentStep` / `setContextJson` / 状态收尾 / token 累加。
这些状态**必须持久化**（崩溃恢复依赖 `usedSteps`），所以"不落 run"只有两种结局：**要么污染外层 run
（外层 plan 步数被 ReAct 内部步数污染、上下文被覆盖、甚至没跑完就被标 `COMPLETED`），要么失去恢复能力**。
而"内嵌模式"若强行实现，12 处写操作都要区分外层/内层，骨架里会并存两条互斥执行路径——
**正是项目一贯反对的双重状态机**。

父子 run **不能简化成「加一列 + 一处过滤、骨架零改动」**——循环内部确实不用动，但它的**边界**全要改。至少还要处理：

- 创建子 run 时传入 `parent_run_id`，并区分外层任务创建与内部执行，避免子 run 的 `cancelStalePendingSuggestions` 误取消用户待确认卡；
- `AiMessages.agentRunId`、确认 / 取消接口和学习 handoff 统一指向外层 run，子 run 不能被当成可确认的普通 run；
- 启动恢复同时收敛父子 RUNNING 状态，不能只把子 run 标 FAILED 后留下外层 RUNNING；
- 用户侧 / 开发者侧 run 列表默认只列外层，详情需要能查看或定位子 run；
- 日志同时携带外层 taskRunId 与内部 agentRunId，避免排查时只看到子 run；
- `ArticleInteractionBatchService` 与 `ArticleAgentRuntime` 返回的子结果要映射回对应 `TaskStep`，子 run 的终态不能直接决定外层 TaskPlan 终态。

因此父子 run 方向可以定，但实施范围明显大于一列和一处查询过滤，必须按这些边界做定向测试。

实施时必须保证：子 run 不出现在用户可见的 run 列表里；`taskStepId` 与内部 ReAct 的 `stepNo` 各自独立计数。
无论哪种拓扑，都不能让现有 Runtime 创建一个与外层任务无关联、却对用户可见的普通 run。

### 4.2 与现有“计划 / 思考步骤”的层级关系

新的 `TaskStep` 与现有前端“思考过程”有关联，但不能当成同一种步骤：

| 层级 | 现有 / 新模型 | 回答的问题 | 示例 |
|---|---|---|---|
| 外层任务 | `TaskPlan` / `TaskStep` | 用户这次明确要完成哪几件事？每件事的真实结果是什么？ | 点赞、搜索 Redis 文章、导航作者主页 |
| 步骤内部推理 | `AgentStep` / `AGENT_STEP` | 为完成某个步骤，Agent 做了哪些查询和判断？ | `QUERY_ARTICLE`、`QUERY_MEMORY`、`FINAL_ANSWER` |
| 固定流程明细 | `WorkflowStep` / `WORKFLOW_STEP` | 一个 Workflow 的固定阶段执行到哪里？ | 生成方案、等待确认、写入文章 |
| 旧计划预览 | `plan_json` / `AGENT_PLAN` | 文章 Agent 预计会关注哪些方向？ | V3.13 的 2-4 条自然语言计划 |

展示上应形成父子关系，而不是把四类记录拼成同一级列表：

```text
TaskPlan
├─ TaskStep: 点赞        -> SUCCEEDED
├─ TaskStep: 分析文章    -> RUNNING
│  └─ AgentStep: 查询文章 / 查询记忆 / 汇总回答
└─ TaskStep: 导航作者主页 -> DISPATCHED
```

第一版可以复用现有思考面板的折叠容器、刷新补拉和 SSE 生命周期，但必须使用独立的
`taskPlan / taskSteps` 数据模型；不能把 `TaskStep` 塞进 `thinkingSteps`，也不能把旧的
`plan: string[]` 当成可执行计划。确定性步骤只有外层 `TaskStep`，只有接入 `ARTICLE_ANALYSIS`
或 Workflow 黑盒时，才在该任务步骤下展示对应的内部明细。

因此 2c 的 SSE / 前端接入至少要保留两个语义边界：

- **任务层事件**：计划、任务步骤状态、步骤结果，驱动用户看到“要做什么 / 做到哪 / 结果如何”；
- **内部过程事件**：`AGENT_STEP` / `WORKFLOW_STEP`，作为某个任务步骤的展开明细，不能决定外层任务是否成功。

这不是重复建设：复用的是展示和传输基础设施，分开的是状态、审计归属和成功判定。

## 五、第一刀范围

**纵向一条链：文章详情页**。StepType 是封闭集合，但**第一刀只接确定性执行器**：

| StepType | 执行方式 | 现有资产 | 第一刀 |
|---|---|---|---|
| `ARTICLE_WRITE` | 确定性后端执行器 | `ArticleInteractionBatchService` | ✅ |
| `ARTICLE_SEARCH` | 检索执行器 | `ArticleSearchService` / RAG | ✅ |
| `NAVIGATE` | 客户端命令分发 → `DISPATCHED` | 现有导航命令；执行器内部解析作者 ID，用页面上下文或前序结果 | ✅ |
| `ARTICLE_QUERY` | 查询执行器 | `ArticlesService` | 第二批 |
| `ARTICLE_ANALYSIS` | **内部 ReAct** | `ArticleAgentRuntime` | **第二批——它是父子 run 的触发点** |

**为什么第一刀不接 `ARTICLE_ANALYSIS`**：它是唯一需要引入父子 run 的步骤。先接它，等于一上来就要同时处理
外层计划 / 外层 run / 子 run / 文章 ReAct / SSE / 前端恢复 / 启动恢复 / 确认接口——**风险叠加**。
而前三个执行器已经能解决 §一 那两个真实反例。**父子 run 的复杂度应该为真实需求付费，不是为提前接上所有能力付费。**

### 5.0 第一版实施结果（✅ 已完成 2026-09-29）

| 环节 | 落点 |
|---|---|
| 协议层 | `TaskPlan` / `TaskStep` / `StepDependency`（三分类）/ `StepResult`（含 `DISPATCHED`、`SKIPPED`）/ `TaskStepExecutor` + `TaskStepExecutorRegistry` / `TaskOrchestrator` / `TaskResultRenderer` |
| 执行器 | `ArticleWriteExecutor`（**直调**第一刀的结构化领域方法，不复用批次服务）/ `ArticleSearchExecutor`（2026-10-03 已统一复用 RAG 混合召回 + Rerank + Gate，不调用回答模型）/ `NavigateExecutor`（产出命令即 `DISPATCHED`，自己解析作者 ID） |
| 持久化 | `TaskOrchestrationService`：一个外层 run + 每步一条 `TASK_STEP`；`kind` 字段区分层级；不碰 `context_json` |
| 接入 | `AiMessageServiceImpl` 在**决策审计之后**做任务路由；新增 `TASK_PLAN` / `TASK_STEP` 事件；`AiIntent.taskGoals` / `requestKind` |
| 路由 | `TaskPlanningFacade` 四态判据（见 §5.2） |
| 前端 | 任务步骤独立成栏（与"思考过程"分层）；写动作结果触发权威状态刷新；`DISPATCHED` 真正发起导航 |
| 验证 | 后端 **226 例** + 前端 **26 文件 / 115 例**；手测「点赞 + 跳转」「点赞 + 搜索」通过（点赞落库、导航跳转、搜索执行） |

**已知残留**：

1. `ARTICLE_ANALYSIS` 未接——父子 run 的触发点，见 §4.1 的边界清单；
2. 历史恢复时 `DISPATCHED` 被 `displayStatus` 归一成 `SUCCESS`，**导航步骤刷新后图标语义弱一档**（文案仍为「已发起…」）——修它要动第一刀批次链路共用的归一逻辑，暂登记；
3. **在线评测门仍未完整跑通**（2026-09-30：分类器组 45/49 通过，4 例是 LLM 额度中断导致的「分类器调用失败」，非断言不符）；
   第四步开工前补跑一次分类器组即可销账；
4. **`taskGoals` 漏目标导致「点赞 + 分析」回归**——2026-09-30 实测发现并修复（fail-closed 判据 + 评测断言维度补强），详见 §5.3。

### 5.1 第一版不是全局覆盖

第一版之后，只有同时满足以下条件的请求才进入新 Orchestrator：

- 当前页面和目标属于文章详情页语境；
- 每个目标都能映射到 `ARTICLE_WRITE` / `ARTICLE_SEARCH` / `NAVIGATE`；
- 参数、目标证据和依赖经过后端校验；
- 没有 Workflow、文章分析、学习域、钱包域或跨域目标。

其他请求不会因为有了 `TaskPlan` 就自动获得多步骤能力，而是按明确语义分流：非任务走 `CHAT`；已经证明旧能力能完整承接的请求走
`LEGACY`；其余含未覆盖目标的多目标请求走 `CTA`。如果新规划器识别出未覆盖目标，必须把它放入
`uncoveredGoalEvidence` 并明确 CTA / 未执行说明，**不能执行其中一部分后说“已完成”**。

这意味着：

| 请求类型 | 第一版行为 |
|---|---|
| 文章写动作 + 站内搜索 | 进入新 Orchestrator |
| 文章写动作 + 客户端导航 | 进入新 Orchestrator |
| 文章写动作 + 文章分析 | 已进入新 Orchestrator；分析由父子 run 承接 |
| 文章动作 + 学习计划 / 钱包 / Workflow | 不支持跨域编排，显式 CTA 或未执行说明 |
| 学习域内部多步骤 | 仍由现有 Workflow / Learning Agent 处理 |
| 单一普通聊天 | 仍走原有 CHAT / Agent 路径 |

只有后续把更多 `StepExecutor` 和对应路由迁入，才逐步扩大覆盖面。新增一个例句不应再新增一条组合分支，
但新增一个**执行能力类型**仍然需要注册执行器、定义结果契约和补验收矩阵。

**Workflow 不进入第一条验收链。** 它将来会以**不可拆的黑盒步骤**（`WORKFLOW_HANDOFF`）接入——不试图拆那 5 条固定流程；
但第一版验收只覆盖上表中标记为 ✅ 的 3 种 step；`ARTICLE_QUERY` / `ARTICLE_ANALYSIS` 虽已进入协议模型，
仍属于后续接入能力。"点赞 + 优化这篇文章"这类组合不在范围内，遇到时按未覆盖目标处理（§6.1 的 `uncoveredGoalEvidence`）。

### 5.2 并存期的路由判据（✅ 已实施）

第一版**不切换、只并存**：新编排器上线时，旧的 `ARTICLE_ACTION` / `ARTICLE_AGENT` 分支继续保留。
但“保留旧路径”不等于“所有未承接请求都可以随意退回旧路径”。路由必须先区分请求是不是任务，以及任务是否包含多个目标。

目标判据如下：

| 候选结果 | 路由 | 约束 |
|---|---|---|
| 不是任务（`NOT_TASK`） | 普通 CHAT / 旧 Agent | 这是聊天兼容，不涉及任务目标丢失 |
| 单目标任务，且该能力尚未迁移 | 显式 `LEGACY` | 整个请求交给一个旧能力处理，不得拆成新旧两半 |
| 多目标任务，全部步骤有执行器 | 新 `TaskOrchestrator` | 单任务和多任务都走 TaskPlan |
| 多目标任务，至少一个目标未迁移 / 未覆盖 | CTA 或明确未执行说明 | **禁止回退旧单意图路径后只执行其中一部分** |

**这条已于 2026-09-29 收口**：`TaskRoute.Route` 现在是四态 `ORCHESTRATE` / `CHAT` / `LEGACY` / `CTA`，
**`FALLBACK` 已删除**。`LEGACY` 是**显式**语义——只有确认旧路径能**完整**处理时才用它
（纯单任务未迁移，或未覆盖的只有 `ARTICLE_ANALYSIS`：第二刀已验证那个组合能整体收口）；
其余含未迁移目标的多目标请求一律 `CTA`。

判据实现在 `TaskPlanningFacade.legacyCanFullyHandle`，`TaskPlanningFacadeTests` 覆盖三条边界
（纯未迁移 → `LEGACY`、动作 + 分析 → `LEGACY`、动作 + Workflow → `CTA`）。

> **计划中的 step 全部被第一版执行器支持 → 走新编排器；只要有一个不支持 → 不能默认退回旧单意图路径。**

**不做“执行一半、另一半标未覆盖”的降级。** 例如「点赞这篇文章，再分析一下它写得怎么样」，
在 `ARTICLE_ANALYSIS` 接入前有两种合法行为：

1. 如果现有第二刀能完整承接这整个复合请求，走显式 `LEGACY` / 旧复合兼容入口；
2. 如果旧路径也不能保证两个目标完整执行，则直接 CTA，明确说明分析没有执行。

无论哪种情况，都不能只执行点赞后用“已全部完成”收尾。

判据按「计划整体是否可执行」而不是按「识别到几个目标」，就是为了堵这个口子。
新路径定向测试稳定后再逐步迁移旧分支，而不是一次切换。

#### 5.2.1 这条约束的生效边界

四态路由已经完成的是**规划候选进入后端之后的完整性约束**：只要分类器产出了 `TASK + taskGoals`，后端就不能再把一个
含未覆盖目标的请求静默交给旧单意图链路。

它还不是「端到端绝不漏多目标」的数学保证。当前仍有三个检测入口会绕过这条约束：

- 分类器误把任务判成 `CHAT`；
- 分类器判成 `TASK`，但没有产出 `taskGoals`；
- 分类调用失败，入口拿不到有效 `AiIntent`。

这三种情况目前都会继续走旧兼容链路。考虑到判断模型能力偏弱，**路由状态机完成不等于检测召回率已经达标**；因此在线评测门仍是
进入 `ARTICLE_ANALYSIS` 前必须补的验证项。后续若实测仍存在多目标漏检，应在候选入口增加 fail-closed 保护或二次任务检测，不能靠继续
添加例句分支解决。

#### 5.2.2 域边界：非文章域主意图不得进入编排（2026-10-18）

上述 §5.1 的范围条件（"当前页面和目标属于文章详情页语境""没有学习域 / 钱包域 / 跨域目标"）此前**没有落成判据**：`routeTask` 只校验
"每个步骤有没有执行器"，不校验"主意图属于哪个域"。于是出现了 §5.2.1 清单之外的**第 4 个 bypass 入口**：

- 分类器主判是**只读意图**（`intent=LEARNING_PLAN_QUERY / suggestedAction=TOOL`），同一份输出又带了
  `requestKind=TASK + taskGoals=[ARTICLE_SEARCH]`；单步检索有执行器 → `steps=1, uncovered=0` → `ORCHESTRATE`，
  在 `AiMessageServiceImpl` 处直接 return，Planner 已经裁决好的 `TOOL getLearningDashboard` **根本不会执行**。
- 生产实测：用户「帮我看看我的Redis学习计划」得到「已全部完成：没有找到与「Redis学习计划」相关的文章」；
  完整事故、证据与修复见 `docs/agent/学习域只读路由修复设计.md`。

**修复（已实施）**：`TaskPlanningFacade` 在 `planner.plan()` **之前**做域仲裁——

```text
主意图 ∉ ORCHESTRATABLE_INTENTS（文章域 8 个 intent）
  且 candidate.requestKind == TASK  →  TaskRoute.legacy("主意图不属于可编排域…")
```

- 白名单而不是黑名单：新增域默认无权编排；缺主意图（分类失败）也 fail-closed。
- **只拦不补**：被忽略的是模型候选，不是用户目标；主意图仍由 Planner 按既有链执行。
- 选 `LEGACY` 而不是 `CTA`：主意图是分类器综合判断的最高级结论，只读查询 / 学习工作流在旧链里都有完整承接。
- 回归锁：`TaskPlanningFacadeTests` 新增 6 例（只读学习查询 + 单/多文章目标、学习 Workflow 意图、钱包域、缺意图；
  另有一例锁"文章域不误伤"）。

`LEGACY` 的真实语义因此需要写清：它不是一条独立链路，而是**让 Planner 决策继续执行**（与 `CHAT` 在入口消费上等价，
差别只在语义与日志）。这也是本次能"把执行权交回 Planner"的原因。

### 5.3 实测补漏：`taskGoals` 漏目标时的 fail-closed（2026-09-30）

**§5.2 的四态路由有一个前提从未被验证：模型真的会把每个目标都拆进 `taskGoals` 吗？**
用在线探针（`TaskGoalsProbeTests`，真实 LLM、`pageType=article-detail`）实测五句话，答案是**不会**：

| 用户说 | intent / requestKind | analysisGoalEvidence | taskGoals |
|---|---|---|---|
| 点赞这篇文章，再分析一下它写得怎么样 | ARTICLE_ACTION / TASK | `分析一下它写得怎么样` ✅ | `[ARTICLE_WRITE]` ❌ **分析没进计划** |
| 点赞这篇文章，再跳转到该作者的个人主页 | ARTICLE_ACTION / TASK | null | `[ARTICLE_WRITE, NAVIGATE]` ✅ |
| 点赞这篇文章，再帮我找找还有没有关于Redis的文章 | ARTICLE_ACTION / TASK | null | `[ARTICLE_WRITE, ARTICLE_SEARCH]` ✅ |
| 点赞这篇文章 | ARTICLE_ACTION / TASK | null | `[ARTICLE_WRITE]` ✅ |
| 分析一下这篇文章 | ARTICLE_DETAIL_QA / CHAT | null | null ✅ |

**执行后果**：`uncovered` 为空 → `routeTask` 判 `ORCHESTRATE` → `AiMessageServiceImpl` 直接 return，
`routeDecision.getFollowUpGoal()` 那条第二刀路径**根本没机会执行** → 只点赞、回「已全部完成」、**分析静默消失**。
这正是 §一 反例 2 那类 A 类错误，而且发生在第二刀已经做好的组合上——**§九 验收句 3 要求的「不能回归」被违反了**。

根因不是路由写错，而是**「模型会拆全目标」这个前提不成立**：分类器 prompt 的 `type` 只声明了
`ARTICLE_WRITE` / `ARTICLE_SEARCH` / `NAVIGATE` 三个取值，分析诉求只活在第二刀的老字段 `analysisGoalEvidence` 里。
模型按 prompt 字面输出，分析目标自然进不了 `taskGoals`，路由也就看不到「有未覆盖目标」。

**为什么两条防线都没拦住**：

| 防线 | 失效原因 |
|---|---|
| 在线分类器门 | 断言字段（intent / requestedActions / analysisGoalEvidence）**恰好全部正确**，缺 `taskGoals` 维度 |
| `TaskPlanningFacadeTests` | 全是手构 `AiIntent`——`actionPlusAnalysisGoesLegacy` 那条是人工把 `ARTICLE_ANALYSIS` 塞进 taskGoals 才绿的，验证的是「如果模型这么输出就对」，而模型不这么输出 |
| 离线门 | 根本不涉及分类器输出 |

**修复判据（已实施）**：`TaskPlanningFacade.analysisGoalNotInPlan`——
`AiIntent.analysisGoalEvidence` 非空（分类器声明「除动作外还有分析诉求」）却没有任何 `ARTICLE_ANALYSIS` 步骤承接时，
**不得判 `ORCHESTRATE`**：计划里全是 `ARTICLE_WRITE` → `LEGACY`（第二刀复合路径能整体收口）；含其他步骤 → `CTA`（没有路径能完整处理）。

两个刻意的选择：

1. **读老字段而不是 `taskGoals`**——`analysisGoalEvidence` 经过第二刀评测门验证，是可靠信号；
   而 `taskGoals` 的分类器 prompt 尚未声明 `ARTICLE_ANALYSIS` 取值（第四步才做），这正是漏目标的直接原因。
   **后端判事实，不赌模型**。
2. **判据会自动退场**——第四步接入 `ARTICLE_ANALYSIS` 执行器后，目标会变成计划里的步骤，判据随即返回 false，
   **不需要手工删除这段代码**（`analysisPlannedAsStepStillOrchestrates` 锁住了这个演进方向）。

这正落实了 §5.2.1 结尾那句预判：「后续若实测仍存在多目标漏检，应在候选入口增加 fail-closed 保护」。

**同步补的回归保护**：分类器样例新增 `expectedRequestKind` / `expectedTaskGoalTypesContains` /
`expectedTaskGoalCountAtLeast` 三个断言维度，并把第一刀那两个**从未入集**的验收句（「点赞+跳转」「点赞+搜索」）补进评测集。
另注：实测发现「点赞+搜索」的 `intent` 会在 `ARTICLE_ACTION` / `ARTICLE_AGENT` 之间漂移，故该类样例**不写死 `expectedIntent`**——
样例锁的是「目标没丢」，不是意图分界（分界另有专门样例）。

**对第四步的直接影响**：接入 `ARTICLE_ANALYSIS` 后本条 fail-closed 退场、「点赞+分析」从 `LEGACY` 迁到 `ORCHESTRATE`；
前提是分类器 prompt 同步声明该 type 取值——**那将是第三次改分类器 prompt，必须跑在线门**。

## 六、关键设计决策

**1. 第一版可以只做一次模型调用，但不能把 TaskPlan 永久塞进 `AiIntent`。**
当前分类器每次都会调用模型，第一版可以让同一次调用同时产出意图建议和粗计划，以控制延迟；但调用结果应在边界处转换为独立的
`TaskPlanCandidate`，再由后端生成 `TaskPlan`。`AiIntent` 仍表示“分类器对请求的意图建议”，不应承载步骤状态、依赖、执行结果或恢复信息。
**单任务 = 长度为 1 的计划**，走完全相同的执行协议，不额外维护第二套路径。

**迁移约束：TaskPlan-first，AiIntent 只做兼容输入。** 新的任务入口必须先尝试生成并校验
`TaskPlanCandidate`；单目标请求也不能绕过计划层直接执行。已经迁移到新 Orchestrator 的能力，执行只能来自
后端生成的 `TaskPlan`，不能再从 `AiIntent.actionType` / `suggestedAction` 直接落动作。尚未迁移的旧域可以暂时
通过兼容适配器消费 `AiIntent`，但它属于迁移边界，不得被新任务路径复用。若候选计划明确包含未迁移或不可执行目标，
必须进入 `uncoveredGoalEvidence` / CTA，不能退回旧单意图路径后静默执行其中一部分。

**2. 第一版不做 replan。**
计划只在执行前生成一次；某步失败按 `onFailure` 处理，不重新规划。理由：第一版只有 5 种确定步骤，用不上重规划；而它需要 LLM 再次介入，容易在两版方案间震荡。等真实样本证明需要时再加。

**3. 客户端步骤返回 `DISPATCHED`，不返回 `SUCCEEDED`。**
后端拿不到前端执行结果，这是事实，不能靠文案掩盖。后续若需要确认真实结果，单独设计客户端回执。

**4. 步骤参数只接受后端可验证的值。**
沿用「LLM 判语义、后端判事实」：authorId 由执行器查文章获得，articleId 只认页面上下文，**不接受 LLM 自报**。

**5. 计划必须有目标覆盖判据。**
“分类器只提取到一个目标”不能靠测试口号解决。每个 `TaskStep` 必须携带由模型摘录的原文证据，后端验证证据是用户原话的连续片段；
无法映射到步骤的明确目标进入 `uncoveredGoalEvidence`，计划不得按“全部完成”收尾。第一版可以对无法确定覆盖关系的复合句整体降级 CTA，
但不能静默执行已识别的一步并宣称请求完成。这个判据是弱模型下的安全底线，不要求后端证明自然语言目标已经被 100% 理解。

### 6.1 计划持久化格式不能直接沿用旧的字符串数组

当前 `ai_agent_runs.plan_json` 和 `AgentRunDetailVO.plan` 存的是 V3.13 的 `List<String>` 展示计划；新的 TaskPlan
需要保存步骤类型、依赖、原文证据和未覆盖目标，不能继续把结构化计划压成字符串数组。

本稿结论：**新增 `task_plan_json`，保留旧 `plan_json` 只服务 V3.13 Plan Preview**。两者语义不同，不能为了少一列
让 inspection 和前端同时兼容两种计划形状。`task_plan_json` 保存正式 TaskPlan；旧 `plan_json` 继续保持 JSON 字符串数组和原有单列更新契约。

## 七、与现有资产的关系

**复用**：

- `ai_agent_run` / `ai_agent_step` 审计（步骤落库、刷新恢复）
- 第一刀的结构化结果契约（`ArticleInteractionResult` / `ResultCode` / 渲染器的"部分成功"语义）
- 第二刀的 `AgentRunPolicy` + `runWithPolicy`（`ARTICLE_ANALYSIS` 内部仍用 analysis-only）
- 第二刀的编排经验：**写副作用必须在权威准入成功之后**、异常兜底保留已发生的事实
- SSE / 前端状态刷新

**废弃**：

- `AgentDecision.followUpGoal`（被 TaskPlan 取代）
- Planner 里「哪种组合走哪条分支」的判断（改成分派，不再组合爆炸）
- `AiIntent` 的单 `requestedActions`（扩成目标数组）

**保留不动**：Workflow 的 5 条固定流程、受控写确认门。

## 八、边界（第一版不做）

- **不做 replan / 重规划**
- **不做并行步骤**（串行；并行要处理并发写冲突，不值得在第一版做）
- **不做跨域编排**（文章 + 学习计划这类，后续再评估）
- **不做客户端回执**（`DISPATCHED` 就是终态）
- **不做步骤级重试**（失败即按 `onFailure` 处理）
- **不拆 Workflow**
- **不做 Workflow 确认后的外层计划续跑**（等待态暂停整个 TaskPlan，剩余步骤不自动继续）

因此第一版的“自主性”是**有限的计划执行**：计划生成后不自动重规划，只能按已校验步骤执行、暂停或明确失败。
不能把它宣传成具备完整的动态自主闭环；重规划要等真实失败样本证明需要后再加入。
`WORKFLOW_HANDOFF` 保留在扩展模型中但不属于第一条验收链；确认后的外层续跑同样是后续能力（见 §3.3）。

## 九、测试与验收

**验收句**（三个反例 + 一个既有）：

1. 「点赞这篇文章，再跳转到该作者的个人主页」→ **真的点赞 + 向前端发出作者主页导航命令**，两步分别如实汇报；没有客户端回执时不得声称“跳转已完成”
2. 「点赞这篇文章，再帮我找找还有没有关于 Redis 的文章」→ 点赞 + 搜索，**搜索不消失**
3. ~~「点赞这篇文章，再分析一下它写得怎么样」~~ → **不在第一刀范围**（需要 `ARTICLE_ANALYSIS`，见 §五）。
   但**第二刀的既有行为不能回归**——第一刀上线期间这条路径仍走原来的分支，直到第三步接入为止。
4. 单任务（"点赞这篇文章"）→ 走同一条路径，行为不变

**必测**：

- `requires` 未满足 → 该步 `SKIPPED` 且**汇总里说明原因**
- `ARTICLE_WRITE` 失败 + 后续步骤 → 按 `onFailure` 走，不得静默继续
- 客户端步骤 → `DISPATCHED`，汇总用「已发起」而不是「已完成」
- 分类器只提取到一个目标（漏项）→ 不得静默执行一个然后说完成
- 计划中每个步骤的证据均来自用户原话；存在 `uncoveredGoalEvidence` 时不得生成“全部完成”文案
- 多目标中含未迁移目标时，不得进入旧单意图路径只执行其中一项；只能走完整旧复合入口或 CTA
- 单目标未迁移能力仍可走显式兼容入口，但不能伪装成新 TaskPlan 已执行

## 十、逐项结论（原待讨论项）

1. **TaskPlan 放哪：结论 = 新建独立结构。** `AiIntent` 是分类器建议对象，当前还是单意图；TaskPlan 还要承载目标证据、依赖、未覆盖目标和后续执行状态，直接扩展会把“建议”和“运行态”混在一起。
2. **Orchestrator 是新的还是复用：结论 = 新组件。** `AbstractAgentRuntime` 是单个领域内的受限 ReAct 循环；全局 Orchestrator 需要处理步骤依赖、跨执行形态、客户端状态和外层 run 归属，继承它会把两种心智模型耦合在一起。
   但 Orchestrator 可以复用 Runtime 的审计、SSE 和准入基础设施，不应复制这些基础能力。
3. **内部 ReAct 审计拓扑：结论 = 带 `parentRunId` 的不可见子 run。** 结构化输出方案经 spike 否决——依据见 §4.1（12 处 run 写操作 + 进度状态必须持久化）。
4. **旧路径怎么迁移：结论 = 先并存，不切换，但不能用一个模糊 `FALLBACK` 吞掉所有情况。**
   不是任务可以走旧 CHAT；单目标未迁移能力可以走显式兼容入口；多目标只要有未迁移目标，必须 CTA 或完整旧复合入口，
   禁止退回旧单意图链路后静默执行半截。新路径仍要求计划中的 step **全部**可执行。
5. **汇总文案由谁生成：结论 = 确定性渲染器。** 第一版只能根据 `StepResult` 渲染；LLM 可以在后续作为格式化增强，但不能参与判断动作是否成功。
6. **步骤展示：结论 = 第一版按 `TaskStep` 一一展示。** 一个用户目标对应一行；步骤内部的业务子结果（如点赞 / 收藏批次）放在该行的结构化详情中，不另造用户级步骤。

## 十一、实施顺序（按能力分段推进，不要一次接完）

**第一步：Orchestrator 纯协议 + 单测，不接任何真实执行器。✅ 已完成（2a）**

用假执行器验证协议本身：多目标计划、`after` / `requires` / `onFailure`、部分成功、步骤输出传递、
`DISPATCHED`、漏目标不得全部成功、等待态停止后续步骤、N 步汇总。这里的“单任务 = 长度为 1 的计划”
只适用于已经接入新 Orchestrator 的能力域；尚未迁移的域仍按旧路由运行，不能据此宣称所有单任务都已统一。
**这一步完全不碰 run / SSE / 前端**——协议错了，这时候改最便宜。

**第二步：只接确定性执行器（`ARTICLE_WRITE` / `ARTICLE_SEARCH` / `NAVIGATE`）。✅ 已完成（2b / 2c）**

§一 那两个反例在这一步就被解决。这三个执行器**不需要父子 run**：
`ArticleInteractionBatchService` 不能原样作为执行器：它虽然只有一处建 run，但还负责批次 run/step 的创建、收尾和失败落库。
第一刀应把其中的领域动作执行与 run/step 审计拆开，执行器只复用点赞 / 收藏 / 关注的真实业务调用，外层 Orchestrator 统一创建和更新 TaskStep。
一并打通外层 run 归属、SSE 步骤展示、前端恢复。

**第三步：先收口并存期路由边界。✅ 已完成**

这一步不新增执行器，专门把当前 `FALLBACK` 拆成明确语义：普通聊天、单目标旧能力兼容、多目标未覆盖 CTA。
重点验收「点赞 + 分析」「点赞 + 优化」「点赞 + 学习计划」等请求不能只执行第一项目标后声称全部完成。
收口后，2c 才算完成全局约束，而不只是文章域第一条纵向切片。

**第四步：再接 `ARTICLE_ANALYSIS`（`ArticleAgentRuntime`）。✅ 已完成（2026-09-30）**

这时才引入父子 run，按 §4.1 的边界清单逐条做定向测试。此时 TaskPlan 结构、外层任务状态、
SSE 展示、失败与漏目标语义都已稳定——**父子 run 的复杂度是加在一个已验证的基座上**。

> **详细设计见 §13**（2026-09-30，评审已收口）：回填的本质是「如实映射目标结果」而非搬运回答，
> 第一件事是给 `AgentRunResult` 补完成类型；阶段 0～7 的改动清单、回填映射表、验收与复合基线均在该节。

**第五步：`WORKFLOW_HANDOFF`，按“契约先行 → P3 → 实施”分两段推进。✅ 5a/5b 已完成**

**5a（✅ 已完成，2026-10-01）只冻结分类契约与评测集，不写执行链**：定义 `taskGoals.type=WORKFLOW_HANDOFF`、允许的
`params`、原文证据、单目标/多目标路由和 fail-closed 语义。分类器收缩依赖的是这份稳定协议，不依赖
Workflow 执行代码已经完成。

5a 最终冻结的最小协议为：`params` 只能包含一个已登记的 `workflowType`，`goalEvidence` 必须非空且逐字
来自用户原话；禁止分类器在该目标中填充 `articleId / planId / stageId / userId / workflowRunId / topic /
planRef / stageRef` 等业务定位值。单 Workflow 目标必须与旧字段 `suggestedWorkflowType` 双写一致；多 Workflow
目标必须把旧单值字段置空。后端仅允许“单个已知 Workflow + 最小参数形态 + 新旧字段一致”显式回退旧链；
未知、缺失、冲突、额外参数或 Workflow 与其他目标组合均 fail-closed 到 CTA，不执行一半。

在线门使用当时的 judge model `qwen3.7-max-2026-05-20` 定向运行 5a 的 **15 条案例，15/15 通过**；Surefire
汇总为 `Tests run: 18, Failures: 0, Errors: 0, Skipped: 3`，其中 3 条是被 `evalCase=5a` 过滤的其他在线测试。

现在插入 Token P3：用包含 `WORKFLOW_HANDOFF` 正反例的同一评测集比较单调用模块化 Prompt 与
Route + Extractor 双调用，胜出方案才进入 ACTIVE（**P3 已完成**：单调用按需装载胜出、双调用停止，
结论见 `docs/agent/p3-classifier-shrink-handoff.md`）。这样可以尽早省 Token，又不会在 Workflow 接入时重写
分类器协议。

**5b（✅ 已完成，2026-10-01；2026-10-02 正确性补强）接黑盒执行步骤与外层恢复**：

> 详细讨论稿：`docs/agent/agent-task-orchestration-workflow-handoff-design.md`。第一版只做文章详情页
> `OPTIMIZE_ARTICLE`，采用后端恢复、显式父关联、状态 CAS 与巡检补偿；不让前端承担恢复正确性。

不拆已有五条 Workflow 的内部固定流程，只把一次 Workflow 作为一个外层 `TaskStep`：

```text
前置低风险步骤完成
  -> WORKFLOW_HANDOFF 创建 / 关联 workflow run
  -> 外层 TaskPlan 进入 WAITING_CONFIRM，后续步骤停止
  -> 用户确认 / 拒绝，Workflow 收敛到阶段结果
  -> 结果回填对应 TaskStep
  -> 按 requires / onFailure 恢复或终止剩余计划
```

第一条纵向验收仍限定文章详情页，优先使用 `OPTIMIZE_ARTICLE`，验证“低风险动作 + 高风险确认”而不是立即做
学习 / 钱包 / 全跨域。核心价值不是多注册一个执行器，而是第一次让外层计划**跨请求暂停并继续**。
已落地：workflow run 与 task run/step 显式关联、重复确认幂等、拒绝/取消映射、启动/低频恢复、
消息与 SSE 归属、AFTER_COMMIT 触发续跑，以及剩余步骤的依赖语义。后置步骤按计划位置校验，第一版只放行 `ARTICLE_SEARCH`。

## 十二、面试口径

**这一步的价值是「从补丁式路由到分层编排」**：

- 前两刀是「一种组合一条分支」，反例证明这条路补不完——**能讲清"为什么当时选简单方案、以及什么时候必须换"**
- 分层之后，Plan 负责方向、Orchestrator 负责状态与依赖、Executor 负责真实执行、Verifier 负责可信——**四层职责不混**
- **`after` / `requires` / `onFailure` 三分类**是个能讲深的点：多数人只会做 `dependsOn`
- **`DISPATCHED` vs `SUCCEEDED`**：跨执行端时"不知道就别装作知道"

**核心取舍**：Plan 是可调整的方向而不是不可变脚本；单任务与多任务走同一套模型，只在展示上分档。

## 十三、第四步详细设计：`ARTICLE_ANALYSIS` 结果回填（2026-09-30，**评审修正版**）

> 状态：**阶段 0-7 全部完成 + 端到端手测通过（2026-09-30）**——在线门 **49/49 全绿**、复合基线已采（`message = 路由 + 子树` 口径断言通过）、
> 多个多目标任务在真实页面验证可用（任务步骤 + 分层内部明细 + 刷新恢复）。实施按阶段逐项提交和定向验证，不一次铺开。
> 上游依据：§4.1（父子 run 的边界清单）、§11 第四步；Token 口径见 `agent-token-optimization-design.md` §5.4。

### 13.1 结论先行：回填的本质是「如实映射目标结果」，不是「搬运回答」

把子 run 的 `finalAnswer` 搬到 `StepResult.summary` 只是数据搬运。真正决定外层语义的是：
**用户要求的“分析文章”是否已经得到可依赖的结果**。内部循环结束、run 进入 `COMPLETED`，只能证明运行过程收尾，
不能证明用户目标达成。因此第一件事是补充显式完成类型，再由外层确定性映射；不能写成
`COMPLETED -> SUCCEEDED`，也不能解析 `finalAnswer` 的自然语言猜结果。

### 13.2 卡点：四种不同业务结果共享 `COMPLETED`

| 收尾出口 | run status | 对外层的真实含义 |
|---|---|---|
| `completeWithAnswer` | COMPLETED | Verifier 放行的回答；但提取不到正文时会退成空答案兜底，仍不能算成功 |
| `completeWithConservativeShutdown` | COMPLETED | 证据不足，主动不下结论 |
| `completeWithSummary` | COMPLETED | 步数到顶后的总结 / 拼接，未经过最终答案同等级的证据放行 |
| `completeWithTerminalFailure` | COMPLETED | 目标不存在或无权访问等不可继续的业务终局 |
| `markFailed` | FAILED | 非法决策或运行异常 |
| `markWaitingUser` | WAITING_USER | analysis-only 下不可达（`allowWaitingUser=false`） |

当前 `AgentRunResult` 实际包含
`(agentRunId, status, finalAnswer, usedSteps, totalTokens, pendingWorkflowSuggestion, pendingWriteAction)`，
但这些字段都不能区分上面四种 `COMPLETED`。若直接映射为 `SUCCEEDED`，当前 `TaskResultRenderer`
会把证据不足也放进 done 列表，甚至输出「已全部完成」。这与任务编排的“不谎报完成”硬约束直接冲突。

### 13.3 已落地结构摘要

第四步已按以下结构收口，逐阶段施工记录不再常驻本设计稿：

- 含 `ARTICLE_ANALYSIS` 的整份 TaskPlan 先过长任务准入；内部子 Agent 不重复占用户名额；
- `AgentCompletionKind` 区分“有可信答案、证据不足、步数到顶、业务终局”等同为 `COMPLETED` 的不同结果；
- `ai_agent_runs.parent_run_id` 建立父子 run；消息只绑定根 run，子 run 不取消旧确认卡、不能被确认接口直接操作；
- `ArticleAnalysisExecutor` 只按完成类型映射 `StepResult`，不解析 `finalAnswer` 文案猜成功；
- 内部 `AGENT_STEP` 显式携带 `taskStepId`，前端挂在外层 TaskStep 下；
- Token 只保存在各自 run / step，通过 `parent_run_id` 聚合，根 run 不复制子 run Token。

#### 13.3.1 搜索到分析的正文证据契约（2026-10-03）

复合请求中的 `ARTICLE_SEARCH` 与普通 RAG 问答共用 `ArticleRagSearchService`，但步骤输出按用途分成两份：

- `articles`：按 `articleId` 去重，只供确定性汇总、推荐卡片和引用转换使用；
- `ragContexts`：保留 Rerank / Gate 后最多 5 个正文 chunk，供紧随其后的 `ARTICLE_ANALYSIS` 使用。

`ArticleAnalysisExecutor` 把 `ragContexts` 恢复为 `ArticleRagContext`，复用 `ArticleRagPromptBuilder`
构造与单任务 RAG 一致的来源格式。同一篇文章的多个相关 chunk 不再因为卡片去重而丢失。
旧任务没有 `ragContexts` 时仍读取 `articles[].summary`，保证持久化任务恢复兼容。

正文证据只保存在当前 Task step 的 `outputJson`，并进入当前分析子 run 的 goal；会话消息只保存最终回答，
不会把原始 chunk 自动注入后续聊天轮次，也不写入 Memory。当前 chunk 上限为 5，按现有每块 800 字计，
本轮正文预算上界约 4000 字。

### 13.4 回填映射表（阶段 3 的核心）

| 子 run 结果 | `StepResult.state` | `StepCode` | 对外语义 | `output` |
|---|---|---|---|---|
| COMPLETED + ANSWER | SUCCEEDED | `ANSWERED` | 得到经证据门放行的分析答案 | agentRunId / usedSteps / completionKind |
| COMPLETED + EMPTY_ANSWER_FALLBACK | FAILED | `INTERNAL_ERROR` | 模型没有产出可用正文 | agentRunId / completionKind |
| COMPLETED + CONSERVATIVE_SHUTDOWN | FAILED | `INSUFFICIENT_EVIDENCE` | 证据不足，未完成分析目标 | agentRunId / usedSteps / completionKind |
| COMPLETED + MAX_STEPS_SUMMARY | FAILED | `INCOMPLETE_RESULT` | 有阶段性总结，但未证明完整达成目标 | agentRunId / usedSteps / completionKind |
| COMPLETED + TERMINAL_FAILURE | FAILED | `TARGET_NOT_FOUND` | 目标不存在或无权访问；对外保持隐私安全的合并语义 | agentRunId / completionKind |
| FAILED | FAILED | `INTERNAL_ERROR` | 决策或运行异常 | agentRunId / errorMessage |
| 任意 WAITING_* | FAILED | `INTERNAL_ERROR` | analysis-only 契约被破坏；第一版不暂停外层计划 | agentRunId / status |

这里的 `StepState` 表达的是**用户目标结果**，不是“内部代码有没有跑完”。因此证据不足不能标 `SUCCEEDED`。
`MAX_STEPS_SUMMARY` 可以把阶段性内容展示给用户，但必须留在“部分结果 / 未完成”区域，不能进入 done 列表；
将来若对总结再做证据验证并通过，才可升级为 `ANSWERED`。

### 13.5 必须长期保留的边界

1. `taskStepId` 通过显式 emitter 传递，不依赖 ThreadLocal。
2. 子 run 不执行 `cancelStalePendingSuggestions`，不能取消同会话已有确认卡。
3. 长任务准入包住整份 TaskPlan，不允许前置写动作先发生、分析步骤再被准入拒绝。
4. `StepResult.output` 只放业务结果与子 run 标识，不复制 Token。
5. `completionKind == null` 必须防御性映射为失败，不能猜成 `ANSWER`（实际枚举不存在 `UNKNOWN`）。

### 13.6 验收结果（已完成）

- 历史在线快照（2026-09-30）**49/49**；当前分类器固定数据集已扩到 67 条，本轮未跑全量在线门；
- 真实页面验证“点赞 + 分析”等复合请求，任务层步骤、内部明细与刷新恢复正常；
- `message.token_count = routeUsage + rootRun.subtreeTokens` 的复合基线通过；
- 证据不足、空答案和 max-steps 总结均不会被渲染为“全部完成”；
- 用户侧只展示根 run，子 run 可由开发者详情定位；
- 权威准入失败时，前置点赞 / 收藏不会执行。

### 13.7 C0 正确性收尾（已完成）

页面手测曾发现弱模型把 `changeTitle` 塞进 `ARTICLE_WRITE.params.action`。最终没有针对单句打补丁，而是收口整个候选参数边界：

| 项 | 最终做法 |
|---|---|
| 参数校验 | `TaskStepParamValidator` 只校验各执行器真正依赖的键；非法目标进入 uncovered，不执行 |
| 序号稳定 | 正式 `stepId = s{candidateIndex+1}`，过滤候选不引起后续步骤重排 |
| 依赖安全 | 三遍扫描处理逐项判定、依赖连带和建步骤；非法、越界、自引用依赖全部 fail-closed |
| 对外脱敏 | 原始模型值只进 `LogValues.safe()` 日志；SSE / errorMessage 使用固定友好文案 |
| 旧链回退 | `LEGACY` 只表示旧 Article Agent 可重新裁决，不翻译模型参数、不直接授权写入 |

验证结果：编排与 Agent 域 **508 例全绿**；复核补丁定向 **80 例全绿**，未改分类器 Prompt，未跑在线 LLM 测试。

### 13.8 当前下一步

5a/5b 已完成，详细实现与恢复边界见
`docs/agent/agent-task-orchestration-workflow-handoff-design.md`。P3 已于 2026-10-02 默认启用 `ON_DEMAND`，
3 条真实模型定向样例通过；`FULL` 保留为显式回退模式，当前 67 条完整在线门未运行。

后续新增能力应表现为新增或扩展 `StepExecutor`，而不是在聊天入口继续为具体句式增加分支。
