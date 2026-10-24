# Agent 多目标编排设计（第二刀：动作 + 分析）

> 日期：2026-09-28（实施 2026-09-29）
> 状态：**已实施**——检测层 + 执行层 + analysis-only 三层约束全部落地；在线评测门 47 例通过
> 上游：`agent-goal-completion-loop-design.md`（第一刀「文章交互动作后端执行闭环」已实施 2026-09-23）
>
> **实施结果（2026-09-29）**
>
> - **规划层**：`AiIntent.analysisGoalEvidence` 承载分析诉求（**原文片段，非 LLM 改写**——`requirements` 因已被代码判定为"会幻觉"而弃用）。Planner 校验三层：包含性 → **不能是动作指令**（防伪证据：分类器可能把动作原话当分析诉求塞进来）→ **证据之后不能再出现动作词**（防反序与条件句）。分类器 prompt 给 `ARTICLE_ACTION` 开复合目标豁免
> - **执行层**：`AgentRunPolicy(decider, allowedActions, allowWaitingUser)` + `runWithPolicy`（现有 `run()` 走 `defaultPolicy()` 委托，**四个既有域零改动**，84 例回归验证）；文章域 `runAnalysisOnly` 入口 + `ArticleAnalysisStepDecider`（prompt 末尾追加模式覆盖声明）
> - **编排**：批次在 **worker 内**执行——权威 `submit` 成功之后才允许写副作用（放 `precheck` 与 `submit` 之间会造出「点赞已提交、Agent 却被拒且无回复」）；动作结果注入 `goalForAgent` 并作为正文首段发出；STOP 带 `actionResults`；异常兜底保留已经发生的动作事实
> - **三层约束全部落地**：决策器 prompt / 后端白名单 / Verifier 转问分流（`allowWaitingUser=false` 时保守收尾，不进 `WAITING_USER`）
> - **未完成**：① §4.2③ 的独立分支 `decideArticleActionWithFollowUp`——当前复用 `decideArticleAction` 后在出口改判 `AGENT` + 带 `followUpGoal`，**行为等价但未独立成型**；② §4.6 的**批次致命异常结构化快照**（`batchRunId + partialResults`）——批次抛致命异常时 `preExecution` 仍为 null，拿不到已经落库的部分 `results[]`
>
> Codex 第一轮复核修正：不再复用会被 LLM 改写的 `requirements` 作为分析目标；复合任务必须在权威 `submit` 成功后才允许产生写副作用；补齐 Article Runtime 可能进入 `WAITING_USER / WAITING_*_CONFIRM`、批次致命异常无法返回 `Execution`、条件句与用户顺序不能被忽略三项边界。
>
> 第二轮修正（§4.7 定案）：复合路径采用 **analysis-only** 终态策略，保证不进入 `WAITING_*`；并明确它必须**三层一起约束**（决策器 prompt / 后端白名单 / Verifier 转问路径）——前两层挡不住 Verifier 的转问，那是绕开白名单的第三条路径。
>
> 第三轮修正（§4.7 落点定案）：实施落点选 **`AgentRunPolicy` + `runWithPolicy` + 双 decider**——不改 `AgentStepDecider` 接口、不建薄 Runtime、不用瞬态实体标记；并补两个必须一起改的内部调用点（主循环 `decide` 与到顶 `summarize`）。

## 〇、结论先行

第一刀解决了**同域低风险动作**的多目标问题（"点赞并收藏"→ 后端批次执行 → 逐项结果 → 确定性文案）。
第二刀往前进一格：**异构目标**——一个请求里既有**确定性写动作**，又有**动态 Agent 分析**。

目标句：

> 点赞这篇文章，再分析一下它写得怎么样

预期行为：

```text
识别两个目标
  -> 后端执行点赞，取得真实结果
  -> 进入文章域 Agent 分析
  -> 汇总「动作结果 + 分析结论」
  -> 任一目标失败都如实说明
```

**为什么是这一刀，而不是"低风险动作 + 高风险确认"**：分水岭是**要不要跨请求**。本刀在单个请求内跑完；一旦引入确认卡，就必须处理 `WAITING_*` 恢复、剩余目标持久化、取消后收尾——那是第三刀，复杂度不是一个量级。分析段采用 analysis-only 终态策略（§4.7）保证不进入 `WAITING_*`，这条边界因此是**已落实的**，不只是目标。

**本刀也是"通用目标协议是否需要"的试金石**：两个目标的执行形态完全不同（一个不进 LLM、一个是 LLM ReAct 循环）。如果连它们都能用**很小的机制**拼起来，说明通用协议不必急着造；如果拼得很难看，也正好证伪。

## 一、多目标覆盖矩阵

按「用户一句话里有几个目标、分别属于什么形态」分类，记录现状与规划。

| # | 组合类型 | 典型句 | 当前行为 | 目标行为 | 所需机制 | 失败语义 | 跨请求 | 优先级 |
|---|---|---|---|---|---|---|---|---|
| 1 | 同域低风险动作 | 点赞并收藏 | ✅ 已支持 | — | 第一刀 | 部分成功如实说 | 否 | 已完成 |
| 2 | **动作 + 分析** | 点赞，再分析一下它写得怎么样 | ❌ CTA 拒绝 | 两段执行 + 汇总 | **异构编排** | 一段失败另一段仍要出 | **否**（§4.7 analysis-only 保证） | **第二刀（本稿）** |
| 3 | 服务端 + 客户端动作 | 点赞并复制链接 | CTA 拒绝（`AgentPlannerSupport:1036`） | 明确拆分或 CTA，不静默丢项 | 跨端结果回传协议 | 一端失败不能伪装全成功 | 否 | 待评估 |
| 4 | 纯客户端多动作 | 复制链接并滚动到评论区 | 单值 `clientActionType`，暂不编排 | 明确拆分或 CTA，不静默丢项 | 客户端命令列表 + 逐项回执 | 前端逐项回传真实结果 | 否 | 待评估 |
| 5 | 低风险 + 高风险确认 | 点赞，然后把标题改一下 | ❌ 未支持 | 执行 → 确认卡 → 继续剩余目标 | `WAITING_*` 恢复、剩余目标持久化 | 取消后如何收尾 | **是** | 第三刀候选 |
| 6 | Workflow + 动作 | 点赞，再优化这篇文章 | ❌ 未支持 | 动作 + Workflow handoff | 跨执行形态编排 | Workflow 拒绝/取消后保留动作事实 | 是 | 后续 |
| 7 | 跨域多目标 | 点赞，再改一下学习计划 | ❌ 未支持 | 跨域编排 | 通用目标协议 | 各域独立事实汇总 | 可能 | 最后评估 |

**两列新加的判据**：

- **失败语义** —— 这一类组合里"一个成功一个失败"怎么表达。第一刀已经建立了"部分成功如实说"的语义（`ArticleInteractionResultRenderer`），后续每一类都要能接上它，而不是各造一套。
- **跨请求** —— 这一列直接决定复杂度档位。只有包含确认门或 Workflow handoff 的组合才必然涉及暂停/恢复；跨域并不天然等于跨请求，必须看具体目标类型。

**排序依据**：先做**不跨请求**且已有真实样例的第 2 类（§4.7 的 analysis-only 策略保证它守得住这条边界）。服务端 + 客户端同样属于跨执行端编排，并不比「动作 + 分析」更简单；它后置的原因是当前缺少客户端逐项回执协议，而「点赞 + 分析」刚刚已有真实验收句和后端权威结果底座。

## 二、与第一刀的关系

| 维度 | 第一刀 | 第二刀 |
|---|---|---|
| 目标形态 | 同构（都是确定性写动作） | **异构**（确定性动作 + LLM 分析） |
| 是否进 LLM | 否，纯确定性 | 分析段进 LLM |
| 执行器 | `ArticleInteractionBatchService` | 再加 `ArticleAgentRuntime` |
| 结果来源 | `ArticleActionResult[]` | 动作结果 + Agent 正文 |
| 文案生成 | 渲染器（确定性） | 动作段渲染器 + 分析段 LLM，**需要汇总** |
| 边界 | 单请求内闭环 | 单请求内闭环（§4.7 analysis-only 收窄终态后成立） |

**要改第一刀的什么**：见 §四（Planner 的混合句拦截是首要改动点）。

## 三、现状核实

逐处确认的代码事实（对应评审清单：分类器 / Planner / 文章批次 / Article Runtime / SSE 收尾 / run 关联）。

### 3.1 规划层根本表达不出「动作 + 分析」

- 分类器输出**单个** `intent`，`ARTICLE_ACTION` 与 `ARTICLE_AGENT` 是**两个互斥意图**；`AgentDecision` 是单个对象，`planArticleAction`（`AgentPlannerSupport:182`）与文章 Agent 分支（`:459`）是两条互斥早返回。
- 所以「同时有批次又有 Agent」**在当前模型里根本无法表达**——这是改动要碰的第一层。
- 现有的混合检测 `hasMixedServerActionAndAnalysisRequest`（`:1184`）是**关键词匹配 + CTA 拒绝**（9 个动作短语 × 4 个分析短语）。它的用途是拒绝，**宁可误拒**；而执行场景要求准确分解——**这个函数不能被复用**，只能当作"已知漏检"的参照。

### 3.2 分发顺序：批次在 Agent 之前，两者互斥

- `AiMessageServiceImpl:318` 的批次分支排在 `:446` 的 AGENT 分支**之前**，条件是 `routeDecision.getArticleActionBatch() != null`。
- 批次路径**不进 LLM**：`save(userMessage)` → `execute` → 落 assistant message → 组 PARAM/DATA/STOP（`:539-606`）。

### 3.3 一条消息只能挂一个 run

- `ai_messages.agent_run_id` 是**单列**（`db/init.sql:350`），无关联表。
- 批次路径**插入时**就带 runId（`:568` → `saveStreamAssistantMessage` 六参重载）；Agent 路径是**插入后回写**（`:1034` 插入 → `:1042` set → `:1054` updateById）。
- 同一 turn 里若两个 run 都要挂，后写的会抹掉先写的——而前端「思考过程恢复」正是靠 `message.agentRunId` 反查。

### 3.4 两套 run 的生命周期语义不同

| | 批次 run | Agent run |
|---|---|---|
| 创建 | `ArticleInteractionBatchService:79-118`，每次无条件新建 | `AbstractAgentRuntime.createRun:595` |
| `maxSteps` | 1 | 域给定（文章域 6） |
| 动作白名单 | 无循环 | `ALLOWED_ACTIONS` **不含** `ARTICLE_INTERACTION_BATCH` |
| 副作用 | 无 | **`cancelStalePendingSuggestions(sessionId)`**：同 session 的 `WAITING_*` 一律 CANCELLED |

批次 run 自身不会清理确认卡；后续创建 Agent run 时会执行一次 `cancelStalePendingSuggestions(sessionId)`，这与普通 Article Agent 的既有行为相同，不是两个 run 重复清扫。需要明确接受的是：复合请求一旦进入 Agent 段，同 session 的旧确认卡仍会失效；同时两个 run 没有父子关联，不能从一个 run 直接导航到另一个。

### 3.5 执行线程模型与 STOP 收尾不一致

- **批次是组装期同步执行**（`:556` 直接调用，**未包 `Flux.defer`**）——在方法返回 Flux 之前就在请求线程跑完了，异常直接抛、不经过任何 Flux 的 `onError` 兜底。
- **Agent 走准入 + 线程池**（`:950 precheck` / `:1015 submit` → `aiTaskExecutor` → `Flux.create`）。
- **三套 STOP 载荷互不相同**：批次带 `actionResults`、Agent 带 `workflowSuggestion`/`writeAction`、普通聊天带 `articleAction`。**Agent 的 STOP 不带 `actionResults`。**

### 3.6 准入不对称

批次路径**完全没有准入**（`:539-606` 内无任何 admission 调用），Agent 走 `AiTaskType.AGENT`。串行后整体耗时会叠加，而配额口径仍只有 AGENT 一种。

### 3.7 小结：真正的结构性障碍

按严重度：

1. **run 归属 + 消息↔run 的单列关联**（3.3 + 3.4）——这是主障碍，SSE 收尾（3.5）是它的附带症状
2. **规划层表达不出复合目标**（3.1）——改动要碰的第一层
3. **线程模型与准入不对称**（3.5 + 3.6）——`precheck` 只读快照，权威占位发生在 `submit`；写动作不能放在两者之间
4. **Article Runtime 不是纯分析函数**——它允许 `ASK_USER / SUGGEST_WORKFLOW / SUGGEST_WRITE`，可能进入 `WAITING_USER / WAITING_*_CONFIRM`，与「本刀以单请求闭环为目标」冲突
5. **批次致命异常没有结构化返回**——`ArticleInteractionBatchService.execute()` 会保留已落库的部分 `results[]` 后重新抛异常，调用方拿不到 `Execution`，当前失败矩阵无法直接实现

## 四、核心设计

### 4.1 目标怎么表达

一个请求要同时携带「写侧」和「分析侧」两个目标。落点：

| 目标 | 载体 | 现状 |
|---|---|---|
| 写侧（动作） | `AiIntent.requestedActions` | ✅ 已存在（第一刀建的） |
| 分析侧候选 | **`AiIntent.analysisGoalEvidence`（新增）** | 必须是用户原话中的连续片段，不能是 LLM 改写摘要 |
| 决策侧 | `AgentDecision.followUpGoal`（新增） | Planner 校验后把分析诉求放进决策 |

**为什么不能复用 `requirements`**：现有代码已明确把 `intent.topic / requirements` 定性为「LLM 结构化输出，会幻觉」，创作 Workflow 因此坚持用原始 `message` 作为 requirement（`AiMessageServiceImpl:1721-1723`）。若把它反过来当作第二目标的权威文本，会重新引入目标被改写、补充或丢失的问题。

`analysisGoalEvidence` 沿用 `topicEvidence` 的证据语义：分类器只摘取用户原话中的连续片段；Planner 必须验证该片段确实存在于原始 `message`，验证失败继续 CTA。`followUpGoal` 只能由这段已验证原文构造，不接受分类器自由改写。

代价是新增一个 DTO 字段并同步 prompt / 示例；`AiIntent` 实际是 Lombok `class`，不是 record，也不存在数据库 schema 迁移。相比复用一个已被代码明确判为不可信的通用字段，这个改动更小且语义更稳。

### 4.2 规划层：三个改动点

**① 分类器 prompt 开一个口子**（`AiIntentClassifierImpl:251-253` 与 `:284-288`）

当前 prompt 有一条多子目标规则：

> 一句话里包含两个或以上不同的处理动作 / 分析角度 / 待办项（"先…再…"）-> ARTICLE_AGENT

**它正是把「点赞 + 分析」推向 `ARTICLE_AGENT` 的原因**。要加豁免：当句子 =「已支持的服务端动作」+「分析诉求」时，仍判 `ARTICLE_ACTION` —— 动作进 `requestedActions`、分析诉求的原文证据进 `analysisGoalEvidence`。

**② Planner 第一分支收窄**（`AgentPlannerSupport:108-115`）

`hasMixedServerActionAndAnalysisRequest` 从「命中即拒」改为「**仅当分析诉求没有被可信表达时才拒**」：

- `analysisGoalEvidence` 非空、确实来自原文且属于文章分析诉求 → 放行给新分支
- 分类器没表达、证据不在原文或只是普通讨论 → 保持原 CTA，行为不变

**③ 新增分支，放在混合句拒绝守卫之后、`isArticleAgentIntent(intent)` 之前**

这个位置是硬约束：放到 `isArticleAgentIntent(intent)` 之后会被文章 Agent 的早返回截走——分类器发生漂移、仍把多子目标句判成 `ARTICLE_AGENT` 时尤其如此。新分支复用 `decideArticleAction` 的批次校验（白名单 / 冲突 / 完整性兜底全部沿用），只在出口多带一个 `followUpGoal`；证据不成立时仍由前面的混合句守卫 CTA，不放任它退化成普通文章 Agent。

**本刀只支持无条件、动作在前的组合。** Planner 还要按原文位置与条件词做确定性边界判断：

- 「点赞，再分析」→ 可进入本刀；
- 「先分析，再点赞」→ CTA，本刀不擅自改执行顺序；
- 「分析后如果写得好就点赞」→ CTA，条件是否成立不能在分析前预判；
- 「点赞并分析」可视为独立并列目标，但必须由评测集证明不会把“讨论点赞机制”误判成动作。

### 4.3 run 组织：两个 run，消息挂 Agent run

| 方案 | 改动面 | 代价 |
|---|---|---|
| 合并成一个 run（批次降级为 step 1） | 要动 Agent 骨架：`maxSteps` 语义、`ALLOWED_ACTIONS` 白名单、`createRun` 的 `cancelStalePendingSuggestions` 副作用 | **深且危险**，还会把 `ARTICLE_INTERACTION_BATCH` 变成"循环内动作" |
| **两个 run**（选定） | 保留两套生命周期主体，编排层负责串联 | 消息只能挂一个（挂 Agent run）；STOP 载荷要合并；批次失败快照与 analysis-only 策略仍需窄改 |

`ai_messages.agent_run_id` 是**单列**，一条消息只能指向一个 run。**挂 Agent run**——用户要看的是分析过程的思考步骤；动作结果本来就在正文里，不需要进 inspection 才知道。

批次 run 保持独立存在（inspection 面板里是一条完整记录），**只是从消息点不进去**。这是本刀明确接受的取舍。

### 4.4 执行串行与线程模型

顺序固定在 `AiMessageServiceImpl`：

```text
precheck（AGENT，必须在 save(userMessage) 之前；仅 best effort）
  -> save(userMessage) + 构造 PARAM
  -> 订阅复合 Flux
  -> admission.submit（权威占用用户名额 + 提交专用池）
  -> worker 内执行动作批次
  -> 动作结果注入 Agent 的 goal
  -> 同一个 worker 内直接调用 ArticleAgentRuntime.run
```

**禁止在 `precheck` 与 `submit` 之间执行批次。** `precheck` 不占名额，检查通过后 `submit` 仍可能因用户并发或线程池竞态被拒；若提前点赞，就会出现「写动作已经提交，但 Agent 被拒且没有完整回复」。权威 `submit` 成功进入 worker 后，才允许产生任何文章写副作用。

本刀只接收「动作在前、分析在后」且无条件依赖的句式，因此 worker 内顺序固定为先动作、后分析；不是无视用户顺序，而是 Planner 已把其他顺序挡在入口。

**动作失败仍继续分析**：两个目标独立，一件失败不该吞掉另一件（延续第一刀的 best-effort 语义）。

**注入 goal 用明确措辞**（"用户要求的动作已由系统执行完毕，结果如下…"），避免 Agent 在回答里重复许诺。文章域白名单里没有写动作，不会重复执行。

**准入口径**：不新增任务类型，也不为批次单独占第二个名额；整个复合 worker 只占一个 `AiTaskType.AGENT` 名额，名额生命周期覆盖「批次 + Agent」全段。这样仍保持第一刀“独立批次不是长任务”的判断，同时避免复合路径绕过权威准入。

### 4.5 汇总与 SSE

后端拼接最终消息：

```java
reply = exec.message() + "\n\n" + agentResult.finalAnswer();
```

SSE 顺序（仍是单条流）：

```text
PARAM -> [动作结果正文] -> AGENT_STEP... -> DATA(分析正文流) -> STOP(带 actionResults)
```

- 动作文案作为**流的第一段正文**发出，前端不需要新事件类型
- `STOP` 事件**新增 `actionResults`**（Agent 的 STOP 当前不带）——供前端刷新点赞/收藏/关注的权威状态，复用第一刀已有的前端处理
- 落库的 assistant message = 拼接后的完整文案，刷新后看到的是完整回复
- Agent worker 抛异常时，不能直接复用当前只落「AI 助手暂时不可用」的 catch；只要批次已经返回或产生部分事实，失败 STOP 与落库消息都必须保留动作结果文案和 `actionResults`

### 4.6 失败语义

| 动作 | 分析 | 汇报 |
|---|---|---|
| ✅ | ✅ | 动作文案 + 分析结论 |
| ✅ | ❌ | 动作文案 + 「分析没能完成（原因）」 |
| ❌ | ✅ | 动作失败说明 + 分析结论 |
| ❌ | ❌ | 两段都如实说明 |

**共同约束**：任何一段失败，都不能让另一段看起来「全完成了」。这与第一刀的「部分成功不伪装原子成功」是同一条原则。

这里必须区分两类“动作失败”：

- 单项业务失败已经被 `ArticleInteractionResult` 结构化，批次仍正常返回 `Execution`；
- 批次级致命异常目前会 `fail(state)` 后重新抛出，调用方拿不到 runId 与已持久化的部分 `results[]`。

第二类若仍要“分析照常继续”，必须先补一个结构化失败快照契约，例如专用异常携带 `batchRunId + partialResults`，并把尚未执行的目标补成明确失败/跳过结果；禁止靠异常中文文案拼最终回复。创建 run/step 之前就失败时，可以按请求动作生成全量 `INTERNAL_ERROR` 结果，但不得伪造任何已执行状态。

### 4.7 复合路径的终态策略：analysis-only（已定）

现有 `ArticleAgentRuntime.ALLOWED_ACTIONS` 包含 `ASK_USER / SUGGEST_WORKFLOW / SUGGEST_WRITE`。因此直接复用 `run()` 时，分析段可能返回：

- `WAITING_USER`；
- `WAITING_WORKFLOW_CONFIRM`；
- `WAITING_WRITE_CONFIRM`。

这会推翻“本刀全程单请求闭环、不碰第三刀”的前提。这里有两个真实方案：

1. **窄的 analysis-only 运行策略**：复用同一骨架和查询动作，但本路径只允许 `QUERY_* / FINAL_ANSWER`。第一版不允许 `ASK_USER`，否则仍会生成 `WAITING_USER`，与本刀的单请求边界冲突；信息确实不足时，按“分析未完成”保守收尾，不伪装成功。禁止只靠 prompt 约束。
2. **保留完整 Article Runtime**：允许返回追问、建议卡或写动作卡，改动较小。现有确认服务本来就能消费这些卡，因此这**不等于已经实现第三刀的“确认后恢复剩余目标”**——前置动作已经终结，没有剩余动作账本要恢复；但它会让本路径出现跨请求终态，并破坏“分析必须在本轮给完”的范围承诺，验收矩阵也要改成“可能跨请求”。

方案 1 也不能只改 `allowedActions()`：当前证据校验器可以把一个合法的 `FINAL_ANSWER` 判成 `ASK_USER`，骨架会在白名单校验之后直接转成 `WAITING_USER`。所以 analysis-only 至少要同时约束三层：

1. 决策器 prompt 不再声明 `ASK_USER / SUGGEST_*`；
2. 后端动作白名单拒绝这些终态；
3. Verifier 的 `ASK_USER` 在本模式下改为保守收尾，而不是调用 `markWaitingUser`。

**决定：采用方案 1（analysis-only）。**

理由不在改动量，在**语义**。用户从没要求改标题——复合请求里的分析段是「附带要求」，让它在自由裁量下弹出一张无关的写动作卡，等于把用户没要的东西塞给他。方案 2 省下的是实现成本，付出的是产品语义，而且会把验收矩阵从「单请求闭环」改成「可能跨请求」——本刀的全部价值就在那条边界上。

第 3 层（Verifier 转问）的处理方式：Verifier 判 `ASK_USER` 说明「缺的是用户才知道的信息」，本模式下改为**保守收尾**——不转问、不伪装成功，在最终回答里如实说明信息不足，**同时保留已经发生的动作事实**（本刀的一贯约束）。

**实施落点：`AgentRunPolicy` + `runWithPolicy` + 双 decider。**

```java
record AgentRunPolicy(
        AgentStepDecider decider,
        Set<AgentStepActionType> allowedActions,
        boolean allowWaitingUser
) {}
```

- `AbstractAgentRuntime` 增加 `protected final runWithPolicy(..., policy)`；现有 `run()` 改走 `runWithPolicy(..., defaultPolicy())`。**`defaultPolicy()` 用现有抽象方法（`decider()` + `allowedActions()`）拼出，四个域及其测试桩零改动**——若改成"每个域各提供一份"，就凭空多出四个方法，偏离"影响面收敛"的初衷
- 文章域增加 `runAnalysisOnly(...)` → `runWithPolicy(..., analysisOnlyPolicy)`
- **两个 decider bean**：`ArticleAgentStepDecider`（完整模式）/ `ArticleAnalysisStepDecider`（prompt 只声明 `QUERY_* / FINAL_ANSWER`）。**不会产生两个 Runtime bean**——`AgentRuntimeRouteRegistry` 仍只注入一个 `ArticleAgentRuntime`

**为什么不是另外两条**：

- ❌ **改 `AgentStepDecider` 接口**（给它加运行模式参数）：四个域、四个实现、所有测试桩都要动
- ❌ **建薄分析 Runtime**：会复制文章定位、锚点、Verifier、保守收尾等大量钩子

**policy 里带 decider 是关键**：`decide()` 的签名里既没有 run 也没有模式，所以运行模式**到不了 prompt 那一层**——三层约束的第一层，只有换 decider 实例才覆盖得到。（同理，把模式挂在 `AiAgentRun` 的瞬态字段上也够不着 decider。）

**要改的内部调用点恰好两个**（`AbstractAgentRuntime` 内 `decider()` 仅此两处，已核实）：

| 位置 | 现状 | 改为 |
|---|---|---|
| `AbstractAgentRuntime:311` | 主循环 `decider().decide(...)` | 本次 policy 选出的 decider |
| `AbstractAgentRuntime:1039` | 到顶总结 `decider().summarize(...)` | **同一个** decider（漏了它，收尾会用错 prompt） |

**Verifier 的 `ASK_USER` 按 `policy.allowWaitingUser()` 分流**：完整模式保持进 `WAITING_USER`；analysis-only 保守收尾。

**⚠️ 专用 prompt 是前置必需项，不是优化**：analysis-only 下若 LLM 仍输出 `ASK_USER`，白名单校验处会**直接 `markFailed` 返回**（`AbstractAgentRuntime:320-328`），**整轮终局失败**——不是"退回循环浪费一步"。没有 `ArticleAnalysisStepDecider`，这条路径一试就挂。

## 五、边界

**支持**：文章详情页 + 「1~N 个已支持的服务端动作」+「1 个分析诉求」（分析诉求限于文章域 Agent 能处理的范围），且第一版只接受无条件、动作在前的表达。

**仍 CTA / 拒绝**：

- 动作 + **客户端动作**混编（第一刀已拒，保持）
- 动作 + **Workflow**（"点赞，再优化这篇文章"）—— 后者带确认门，属于跨执行形态编排
- **跨域**（动作 + 学习计划）
- **多个分析诉求**（"分析一下，再总结一下"）—— 先一个，多了 CTA
- **分析在前或带条件依赖**（“先分析再点赞”“如果写得好就点赞”）—— 本刀不改序、不预执行

## 六、测试设计

### 6.1 规划层

- 「点赞这篇文章，再分析一下它写得怎么样」→ `ARTICLE_ACTION` + `requestedActions=[likeArticle]` + `analysisGoalEvidence` 为原文分析片段
- `analysisGoalEvidence` 必须逐字来自用户原话；分类器改写、补充或输出原文不存在的内容 → CTA
- **单动作对照组**：「点赞这篇文章」→ 不得带上 `followUpGoal`
- **单分析对照组**：「分析一下这篇文章」→ 仍走 `ARTICLE_AGENT`，不得误判成组合
- **多子目标但无动作**：「分析一下，再总结一下」→ 不走本刀
- 「讨论点赞机制」（"为什么点赞数这么高"）→ **不得**触发本分支（沿用既有测试的语义）
- 「先分析，再点赞」与「如果写得好就点赞」→ CTA，且零写入

**在线分类器门槛按弱模型能力分层，不要求所有口语化表达逐条 100% 命中：**

- 2 条核心支持句逐条严格通过：`ARTICLE_ACTION`、动作数组和原文分析证据都正确；
- 8 条口语化扩展表达按组统计，召回率 `>= 80%`（当前即至少 `7/8`）；
- 单动作、纯分析、反向顺序、讨论点赞机制等反例仍要求**零误放行**；
- `confidence=0` 且原因为“分类器调用失败”的降级结果属于 429 / 超时 / 供应商异常，评测应直接标记为**基础设施失败**，不得计作模型意图误判或召回漏判。

这里的取舍只放宽“能不能识别出来”的召回率，不放宽后端执行边界。弱模型漏判时应 CTA；证据不在原文、动作不完整或顺序不满足时，Planner 仍必须 fail-closed。

还要防一种比“整句判错”更危险的半结构化结果：模型输出了 `ARTICLE_ACTION + requestedActions`，却漏掉 `analysisGoalEvidence`。Planner 不能因此把请求当成纯动作执行；只要原文仍有明确的分析信号，就由确定性守卫退回 CTA。该守卫允许少量误拒，但不允许“只执行动作、静默丢掉分析”。

### 6.2 执行层

- 动作成功 + 分析成功 → 两段都在最终消息里
- 动作失败 + 分析成功 → 动作的失败说明仍在，分析照出
- 动作成功 + 分析失败 → 分析失败如实说
- 批次 run 与 Agent run 都被创建，assistant message 挂的是 **Agent run**
- `precheck` 通过但权威 `submit` 拒绝 → 不得产生任何文章写入
- 批次部分提交后抛致命异常 → 已完成结果不丢，未执行项有明确状态，分析是否继续符合 §4.6
- Agent worker 异常 → STOP 和落库消息仍携带已经发生的动作事实
- 复合路径下 `ASK_USER / SUGGEST_*` **三层同时被拒**：决策器不输出、白名单不通过、**Verifier 判 `ASK_USER` 时保守收尾而不转问**（§4.7）
- 到顶总结使用与主循环**同一个** policy decider（漏改这一处，收尾会用错 prompt）
- `ArticleAnalysisStepDecider` 有对照测试，锁住「不再输出 `ASK_USER / SUGGEST_*`」——现有评测集测的是完整模式，覆盖不到它

### 6.3 回归（会被旧断言判成回归，需一起改）

- `AgentPlannerSupportTests:693 mixedServerActionAndAnalysisRequestAreRejected`
- `AgentPlannerSupportTests:708 discussionOfLikeMechanismDoesNotTriggerMixedRequestGuard`（这条要**保持**通过）
- 第一刀评测集里「混合句 → CTA 零执行」的断言
- **必跑在线评测门**（改了分类器 prompt + Planner 裁决规则，按 `agent-eval-gate.md` 规定）

## 七、验收标准

1. 「点赞这篇文章，再分析一下它写得怎么样」**真实执行点赞 + 真实分析**，两段都在回复里
2. 动作失败时，失败说明仍在回复里，且分析照常给出
3. 分析失败时，动作结果不丢
4. assistant message 关联 Agent run，刷新后能从消息进入分析过程
5. 单动作 / 单分析 / 第一刀的多动作路径无回归
6. 在线分类器评测满足：核心支持句全过、扩展表达召回率 `>= 80%`、反例误放行 `= 0`，并把供应商调用失败与模型误判分开报告
7. 权威准入失败前不产生任何文章写副作用
8. 分类器不能通过改写 `analysisGoalEvidence` 改变用户的第二目标
9. 批次级致命异常与 Agent 异常都不丢已经发生的动作事实
10. 复合路径**恒不进入 `WAITING_*`**：`ASK_USER / SUGGEST_*` 三层同时被拒（决策器 / 白名单 / Verifier 转问路径）且有测试覆盖，不靠 prompt 假定

## 八、面试口径

**这一步的价值是「异构目标编排」**：两个目标的执行形态完全不同——一个是确定性写动作（不进 LLM），一个是 LLM ReAct 循环。它们能不能在同一个请求里串起来、结果能不能合成一句如实的话。

**核心取舍**：

- **不预埋通用协议**。先用真实场景验证它到底需不需要：如果两套完全不同的执行形态能用很小的机制拼起来，说明通用目标协议是过度设计；如果拼得很难看，也正好证伪。**先穷尽简单方案，再上抽象。**
- **复杂度的分水岭是「跨不跨请求」**。本刀用 analysis-only 终态策略（§4.7）把这条边界钉死——**代价是三层一起约束**（决策器 prompt / 后端白名单 / Verifier 转问路径），比「删三个白名单项」贵，换来的是验收边界单一。**这里最容易做错的是只改前两层**：Verifier 的转问绕开白名单，只删白名单等于没做。
- **一个 run 还是两个 run，是按「改动面」选的**，不是按「看起来整不整齐」。合并更漂亮，但要动 Agent 骨架的白名单、步数语义和既有副作用；两个 run 保留各自生命周期主体，但仍要补编排、失败快照和终态策略，不能表述成零改动。

## 九、暂不建议做的事

1. **不建通用目标协议 / Goal Ledger**。本刀只有两个目标，一个 `followUpGoal` 够用。
2. **不为了编排把批次塞进 `AgentRuntime.run`**。批次仍在 Runtime 之外串；analysis-only 走 `runWithPolicy` + 独立入口（§4.7）——不动 `run()` 的公共签名，也不改 `AgentStepDecider` 接口。
3. **不把批次降级成 Agent run 的 step**。那要动白名单和步数语义，收益不匹配。
4. **不做「确认后继续剩余目标」**。那是第三刀，需要 `WAITING_*` 恢复。
5. **不支持多个分析诉求**。先一个，多了 CTA。
