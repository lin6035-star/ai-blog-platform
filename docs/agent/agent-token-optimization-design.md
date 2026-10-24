# Agent Token 优化设计稿

> 版本：v0.4（2026-10-01）  
> 状态：**P0-P2、编排 C0 与 `WORKFLOW_HANDOFF` 5a 均已完成**；当前进入 P3 分类器收缩对照 spike，实施顺序以 §15 为准  
> 范围：意图分类器 + Agent 决策循环 + Article Verifier + Agent Token 观测  
> 原则：不改变“LLM 给建议、后端做裁决”的现有安全边界

---

## 0. 一句话结论

当前 Agent Token 高消耗的主因不是单次工具结果过长，而是：

```text
全域分类器一次性携带所有规则
  + Agent 每一步重复发送完整领域规则
  + 每一步重放累计 observation
  + FINAL_ANSWER 前 Verifier 再复制一遍证据
```

本设计不靠“换便宜模型”解决，而是缩小每次模型调用真正需要看到的信息：

```text
全域大分类器
  -> 小型路由判断 + 按需领域字段提取

文本 observation 流水
  -> 有预算、可去重、最新证据优先的结构化 AgentState

固定全量动作说明
  -> 只描述当前仍可执行的动作
```

目标是在不降低现有评测门通过率、不放松后端裁决的前提下，将典型文章 Agent 的端到端输入 Token **降低 50% 以上**作为 stretch target；首刀上线硬门槛见 §14.2。

当前已根据 §5.5 实测确定 P2 第一刀：**先处理每一步重复发送的静态 Decider 规则，不先建设长期 `AgentState`**。
第一刀只做运行模式级裁剪：完整文章 Agent 保留现有能力，analysis-only 子 run 只加载它允许使用的四类动作规则。
它不依赖解析 observation，也不提前实现状态驱动的逐步动作收缩。

---

## 1. 背景与现状证据

### 1.1 当前调用链

一次普通文章 Agent 请求大致经过：

```text
用户消息
  -> AiIntentClassifierImpl：全域意图分类
  -> AgentPlannerSupport：后端确定性裁决
  -> ArticleAgentStepDecider：第 1 步决策
  -> Tool 执行并产生 observation
  -> ArticleAgentStepDecider：第 2～N 步决策，重发累计 observation
  -> ArticleEvidenceVerifier：FINAL_ANSWER 草案与证据检查
  -> 必要时继续查询
  -> maxSteps 到顶时可能再调用 summarize
```

已有安全原则保持不变：

- 分类器和 Decider 只提供候选建议。
- `AgentPlannerSupport`、动作白名单、归属校验、确认门、CAS 和写执行器仍是权威裁决方。
- Verifier 只判断证据是否足够，不选择下一步动作。

### 1.2 静态 Prompt 体积

按当前源码文本块字符数统计：

| 调用点 | 静态规则体积 | 调用频率 |
|---|---:|---|
| `AiIntentClassifierImpl.buildSystemPrompt(false)` | 约 29,771 字符 | 每条非 active Workflow 消息至少一次 |
| `ArticleAgentStepDecider.buildSystemPrompt(false)` | 约 5,144 字符 | Article Agent 每一步一次 |
| `ArticleAnalysisStepDecider` 附加规则 | 约 270 字符 | 动作 + 分析路径每一步一次 |
| `LlmAgentStepDecider` 学习域规则 | 约 2,896 字符 | Learning Agent 每一步一次 |
| `GeneralAgentStepDecider` 通用域规则 | 约 880 字符 | General Agent 每一步一次 |
| `ArticleEvidenceVerifier` 规则 | 约 1,305 字符 | 满足语义闸条件时一次 |

分类器目前同时承担：

- 顶层意图和路由判断
- Workflow 类型判断
- Agent 域判断
- 页面动作和编辑器动作
- 学习计划引用摘录
- 文章主题、参数、目标对象摘录
- 复合文章动作拆解
- 动作 + 分析证据摘录
- Task Orchestration 候选目标拆解
- `needsThinking`
- 风险、置信度和原因

这些职责全部集中在一个 Prompt 中，导致任何领域新增规则都会让所有消息共同承担成本。

### 1.3 observation 重放

`AbstractAgentRuntime` 当前限制：

```text
单条 observation：最多 1500 字符
累计上下文：最多 6000 字符
```

每一步调用 Decider 时都会重新发送全部累计内容：

```text
第 1 步：0
第 2 步：O1
第 3 步：O1 + O2
第 4 步：O1 + O2 + O3
```

如果平均一条 observation 为 1000～1500 字符，4 步 Agent 仅重复上下文就会额外发送约 9000～13500 字符。

当前 `clipContext()` 从最早 observation 开始保留，达到 6000 字符后停止。**这是本稿发现的最严重问题，而且它不是 Token 问题，是正确性缺陷**：

上下文满载时丢掉的是**最新**证据，而 V3.11 证据收敛门的核心机制恰恰是「Verifier 判 `NEED_MORE` → 补查 → 用新证据回答」
（`verifierRejectHint` 原文：「请先补充查询再回答」）。补到的新证据最可能被截掉 → Verifier 继续拦 → 直到 `maxSteps` 用尽走保守收尾。

该实现有**两处重复**，逻辑完全相同：`AbstractAgentRuntime.clipContext`（1139-1151）与
`ArticleEvidenceVerifier.clipContext`（271-285）。修复时不能简单倒序拼接，否则会把模型看到的时间顺序也反过来；
正确语义是：**从尾部选出能装入预算的最新窗口，再按原时间顺序输出**。

这不需要先建立 Token 基线，但会改变 Decider / Verifier 实际看到的上下文，仍需补确定性长上下文测试：最新证据必须保留、
最旧证据被淘汰、选中窗口内顺序不变、单条超长 observation 仍受总预算限制。它不要求跑分类器全量在线门，
但不能以“只是改遍历方向”为由跳过 Agent / Verifier 的相关回归。该修复应排在本设计所有 Token 优化之前。

### 1.4 典型请求估算

以下为端到端输入估算，包含分类器、Agent 决策和 Verifier，不包含模型输出 Token：

| Article Agent 步数 | 输入字符量 | Token 粗估 |
|---|---:|---:|
| 2 步 | 约 4.4～4.7 万 | 约 2.2～3.1 万 |
| 3 步 | 约 5.2～5.6 万 | 约 2.6～3.7 万 |
| 4 步 | 约 6.2～6.7 万 | 约 3.1～4.5 万 |

Token 粗估只用于确定优化优先级。不同千问模型的 tokenizer 不同，实施验收必须使用供应商返回的真实 usage。

### 1.5 现有观测缺口

当前数据库已经保存：

- `ai_agent_runs.input_tokens/output_tokens/total_tokens`
- `ai_agent_steps.input_tokens/output_tokens`
- `ai_messages.token_count`：分类器 + Agent 循环的消息级总成本

但现有**开发者检查接口**没有完整透出：

- `AgentRunDevDetailVO` 没有 run 级 Token 字段。
- `AgentStepRawVO` 没有 step 级 Token 字段。
- `AgentRunInspectionService` 的开发者映射没有设置这些字段。
- 开发者 Agent 面板无法展示每步 Token。
- 前端当前能展示的是 Workflow step Token，不是 Agent step Token。

用户侧 `AgentRunDetailVO` / `AgentStepVO` 缺少 Token 字段是有意的安全 Interface，不属于观测缺口。

因此项目已经“记账”，但还不能方便地回答“哪一步最贵、优化后少了多少”。

---

## 2. 目标与非目标

### 2.1 目标

1. 开发者面板能看到消息、run、step 三个口径的真实 Token。
2. 普通聊天不再携带文章、学习、钱包、任务编排等全部领域规则。
3. Agent 每一步只接收当前决策需要的状态，不重放完整历史文本。
4. 最新证据和缺失证据优先进入上下文，旧证据可去重、淘汰。
5. Verifier 不再复制无关 observation。
6. 不降低现有分类器、Planner、写动作和 Agent 评测门通过率。
7. 不新增不必要的数据库表，不重写现有 Agent Runtime。

### 2.2 非目标

- 不通过降低回答质量、删除 Memory/RAG、缩短文章正文到不可用来省 Token。
- 不把后端安全校验迁移给 Prompt。
- 不在本设计中更换千问模型或调整免费额度策略。
- 不把 Prompt Cache 统计成“Token 数减少”；缓存主要影响计费和延迟，不能消除逻辑输入。
- 不同时重构 Workflow Prompt；本轮先处理动态 Agent 链路。
- 不引入 Multi-Agent 或新的外部 Agent 框架。

---

## 3. 方案比较

### 3.1 方案 A：只机械压缩 Prompt 文案

做法：删除重复示例、合并禁止项、缩短动作说明，保留现有单体分类器和 observation 流水。

优点：

- 改动小。
- 不改变调用链和数据模型。
- 容易快速验证。

缺点：

- 分类器职责仍然持续膨胀。
- 每增加一个领域，所有请求成本继续上涨。
- observation 重放问题没有解决。
- 很容易把关键意图分界规则一起删掉，引发分类漂移。

判断：机械删字可以作为清理动作，但不能作为主方案。P2 第一刀采用的不是机械删字，而是**按运行模式组合规则片段**：
analysis-only 不再加载自己永远不能执行的动作说明；完整版仍保留原有分界规则。两者不能混为一谈。

### 3.2 方案 B：分类职责收缩 + 结构化 AgentState（推荐方向）

做法：

1. 分类阶段把“路由判断”和“领域字段提取”拆成独立职责；物理上是一调用动态组 Prompt，还是两次调用，留给实测决定。
2. 对下游仍组装现有 `AiIntent`，先不强迫所有消费者一起迁移。
3. Agent Runtime 内增加结构化状态和上下文预算模块。
4. Decider 只接收当前可用动作与压缩状态。
5. Verifier 消费同一份证据状态，不再自己重新裁剪原始流水。

优点：

- 最大收益点和长期膨胀问题一起解决。
- 可以分刀迁移，不需要一次重写所有调用方。
- 分类器、Decider、Verifier 的输入职责更清晰。
- 后续增加新 Agent 域时，不会让其他域共同承担 Prompt 成本。

缺点：

- 若最终采用双调用，部分复杂业务请求会由一次大调用变成两次小调用。
- 需要重新锁定路由与字段提取的评测集。
- 结构化状态迁移需要兼容现有 `contextJson` 和历史检查面板。

判断：**职责收缩 + 结构化状态**作为主方向；分类器采用单调用还是双调用不是本节结论，见 §16.4。

### 3.3 方案 C：只使用 Prompt Cache / 更小模型

做法：保持现有 Prompt，通过供应商缓存静态前缀，或切换更小判断模型。

优点：

- 业务代码改动少。
- 可能降低延迟或实际费用。

缺点：

- 逻辑输入 Token 仍然存在。
- 免费额度如何计算缓存 Token 取决于供应商规则。
- Prompt 和上下文继续膨胀。
- 小模型面对 3 万字符规则时更容易漏规则，不一定更稳。

判断：只作为主方案完成后的额外优化，不作为根治方案。

---

## 4. 总体设计

### 4.1 目标链路

以下是本设计的长期目标链路，**不是 P2 第一刀必须一次落完的实现清单**。P2 第一刀只改 Step Decider 的 Prompt 组装，
不引入 `AgentStateReducer` / `DecisionContextBuilder`。

```text
用户消息
  -> Classifier V2
       -> 候选 A：单调用，动态组装公共规则 + 必要领域规则
       -> 候选 B：Route Classifier + 可选 Domain Extractor 两次调用
  -> AiIntentAssembler（兼容组装现有 AiIntent）
  -> AgentPlannerSupport（继续做后端权威裁决）
  -> Agent Runtime
       -> AgentStateReducer（合并工具结果、去重、预算裁剪）
       -> DecisionContextBuilder（当前动作 + 当前状态）
       -> Step Decider
       -> Tool / Action Executor
       -> Evidence Verifier（复用结构化证据）
  -> 最终回答
```

### 4.2 保持不变的权威顺序

```text
用户当前要求
  > 后端权限、安全校验、动作白名单和确认门
  > 工具返回的权威事实
  > Verifier 结论
  > LLM 路由与下一步建议
  > plan / thoughtSummary
```

Token 优化不能改变这条顺序。

---

## 5. 模块 A：Agent Token 可观测性

### 5.1 目标

在任何语义改造前，先建立可对比基线。

开发者详情页展示：

```text
消息总 Token
  = 分类/路由 Token + 根 run 的 subtree Token

Run Local Token
  = 每步 decide/repair + Verifier + 到顶 summarize

Run Subtree Token
  = 当前 run local Token + 全部后代 run local Token

Agent Step Token
  = 产生该 step 决策的 decide/repair Token
```

P0 尚无子 run 时，`localTokens == subtreeTokens`；P1 接入父子 run 后，消息总量和路由差值统一使用根 run 的
`subtreeTokens`，不能继续只减父 run 本层值。

### 5.2 最小改动

后端：

- `AgentRunDevDetailVO` 增加 run 级 `inputTokens/outputTokens/totalTokens`，以及查询派生的
  `localTokens/subtreeTokens`（第一版父子树只有一层，不新增冗余列）。
  **注意**：该类内嵌 `AgentRunDetailVO run`（`AgentRunDevDetailVO.java:14`），run 级 Token 请放在 DevDetailVO 的**外层字段**上。
- `AgentStepRawVO` 增加 `inputTokens/outputTokens`；`totalTokens` 由展示层相加，**不加冗余列**。
- `AgentRunInspectionService` 映射已有实体字段。
- 开发者详情页展示 run 总量和每步输入/输出。

> **口径边界（2026-09-30 补，原稿此处列错了 VO）**：`AgentRunDetailVO` / `AgentStepVO` 是**用户侧接口**
> （`AgentStepVO` 的 Javadoc 即「用户侧安全摘要」、`AgentRunDevController:24` 同样声明「那条是用户侧通道」），
> 给它们加 Token 字段会与 §5.3「不向普通用户侧暴露内部 Token」直接冲突，故**不加**。
> 项目已有一致口径：`AiMessageVO` 也不含 token 字段——message 级 token 落库但不给用户看。
>
> 另：`AiAgentStep` 实体与 `ai_agent_steps` 表**只有 `input_tokens` / `output_tokens` 两列**
> （`db/init.sql:499-501`），没有 `total_tokens`——原稿让 step VO 加 `totalTokens` 与 schema 不符。

消息级总量不新建列，继续使用 `ai_messages.token_count`。

若开发者详情页能通过 `agentRunId` 找到对应 assistant message，则额外展示：

```text
routingTokens = max(message.token_count - rootRun.subtreeTokens, 0)
```

它是兼容期估算值，页面上必须标注“路由/分类差值”，不能伪装成供应商独立返回的精确分类器字段。
P0 没有子 run 时，`rootRun.subtreeTokens` 就等于现有 `run.total_tokens`。

### 5.3 不做

- 不向普通用户侧暴露内部 Token。
- 不新增 Token 明细表。
- 不记录完整 Prompt 正文，避免泄漏内部规则和用户材料。

### 5.4 父子 run 的 Token 口径

P1 接入 `ARTICLE_ANALYSIS` 后，一次请求会同时存在**外层 Task run** 与**内部 Agent 子 run**。
口径定为**本层存储、查询聚合，不把子 run Token 回写父 run**：

1. 每条 run 的 `input_tokens / output_tokens / total_tokens` 只记录该 run 直接发起的 LLM 调用；父 run 不复制子 run Token。
2. 开发者面板同时展示 `localTokens`（本层）和 `subtreeTokens`（本 run + 全部后代求和）；这是查询派生值，不新增冗余列。
3. assistant message 继续记录整条消息的完整成本：`routeUsage + rootRun.subtreeTokens`。因此兼容期估算为
   `routingTokens = max(message.token_count - rootRun.subtreeTokens, 0)`，不能只减父 run 本层值。
4. 内部 ReAct step Token 仍挂在子 run 的 `AgentStep`；外层 `ARTICLE_ANALYSIS` TaskStep 不复制这份 Token。
   前端可在外层步骤旁展示派生的子树小计，但不能把同一消耗同时算进父 step 和子 step。

该口径的核心不变量是：**把所有 run 的 local Token 求和，等于整棵执行树的 run Token，总量不重复。**

> **已落地前提（衔接 `agent-task-orchestration-design.md` §4.1）**：`subtreeTokens` 的聚合依赖
> `ai_agent_runs.parent_run_id`；P1 已在 `db/init.sql` 与测试 schema 中补列和索引，`AiAgentRun` 实体也已映射。
>
> 另（2026-09-30 核实）：`ai_messages.token_count` 全项目**只有 6 处写入、没有任何读取点**
> （无 `getTokenCount`），因此本模块补编排链路 token 落库**不影响计费**，是纯增量观测数据。

原先已核实的缺口是：编排链路 `streamTaskOrchestration` 未把 `routeUsage` 落到 `ai_messages.token_count`。
P0-b / P1 已补齐成功与异常收尾出口；成功路径按 `routeUsage + rootRun.subtreeTokens` 落库，异常路径至少保留
`routeUsage`。因此当前复合基线可以使用 §5.4 的统一口径，不再把该项列为待办。

5b handoff 终态多一份关联 Workflow 用量，消息改按绝对公式收敛，不能继续用上面的普通编排差值公式反推分类器消耗：

```text
message.token_count
  = messageRoutingTokens（暂停时固化）
  + currentRootRun.subtreeTokens
  + linkedWorkflow.totalTokens
```

历史等待记录没有基线字段时只反推一次并持久化。Task CTA / handoff preflight 拒绝路径也已落 `routeUsage`，
开发者面板已展示 `localTokens / subtreeTokens`。

---

### 5.5 基线数据（2026-09-30 首采，✅ 已完成）

采集方式：`AgentTokenBaselineTests`（`@Tag("eval")`，需 `-DrunRealLlm=true`），固定 3 个问题跑真实
`ArticleAgentRuntime`，**全部数据从 DB 读回**（同时验证 P0-b 落库口径）。结果写 `target/agent-token-baseline.txt`。

> **两个采集前提，都是踩过坑才定下的**：
> ① 测试跑在 **H2 内存库**上（`src/test/resources/application.properties`），种子文章正文只有 17 字符——
> 首轮实测每步输入恒定 ~2400 token，**observation 重放被静态 prompt 完全淹没**。故改为**自造 13122 字符长文夹具**。
> ② 步数由模型当场决定、不可预设，所以**按实际 `usedSteps` 分档**，不按"2/3/4 步"造问题。

**实测数据**（n=3，输入 token）：

| 夹具 | 样本 | 实际步数 | 路由输入 | run 输入 | 端到端输入 | 路由占比 | step 输入序列 |
|---|---|---:|---:|---:|---:|---:|---|
| 短种子文（17 字符） | 1 | 3 | 8151 | 8054 | 16205 | 50% | 2446 → 2372 → 2413（**恒定**） |
| 短种子文 | 2 | 3 | 8122 | 8142 | 16264 | 50% | 2464 → 2390 → 2431 |
| 短种子文 | 3 | 2 | 8001 | 5581 | 13582 | 59% | 2437 → 2363 |
| 长文夹具（13122 字符） | 1 | 5 | 8141 | 16251 | 24392 | 33% | 2458 → 2991 → 3032 → 3069 → 3096 |
| 长文夹具 | 2 | 5 | 8184 | 18667 | 26851 | 30% | 2476 → 3009 → 3046 → 3796 → 3819 |
| 长文夹具 | 3 | 2 | 8001 | 6919 | 14920 | 54% | 2449 → 2982 |

**三条读数**（n=3 小样本，只作方向性判断）：

1. **路由成本恒定在 ~8000 token，与请求复杂度无关**（全部落在 8001~8184）。它对**短请求**的占比过半（54%~59%），
   对多步请求降到 30%~33%。→ §12 给分类器分层估的「端到端降低 35%~55%」，**上界对短请求成立，下界对多步请求偏高**。
2. **每步静态 Decider 规则约 2400~2500 token，是 run 成本的主体**：5 步 ≈ 12000 token，
   占 run 输入（16251）的 **74%**；而 observation 累积重放每步只贡献 ~500~600 token（合计约 15%）。
   → **这修正了 §1.3 的权重判断**：重放真实存在（长文下 step 输入确实递增），但它不是主要开销，
   **静态 prompt 才是**。故 §12 给「AgentState + 去重预算」的 20%~40% 缺乏依据，
   而 §8（Decider Prompt 收缩）的优先级**应高于** §7（AgentState）——本稿 §15 已据此改为 Prompt-first。
3. 短文夹具下 step 输入**完全恒定**（2446 → 2372 → 2413，无递增）：observation 太短时重放不可观测。
   这也是"基线必须用代表性长文"的实证。

**重跑方式**（后续每一刀完成后都应重跑本基线对比）：

```bash
mvnw test -Dtest=AgentTokenBaselineTests -DrunRealLlm=true
```

**复合基线（2026-09-30 已采，第四步·阶段 6）**：`TaskOrchestrationBaselineTests` 跑**真实复合请求**
（「点赞 + 分析」，13122 字符长文夹具），端到端验证 §5.4 的父子 Token 口径：

| 环节 | 实测 |
|---|---:|
| 路由（分类器） | 8254 |
| 外层 run local | **0**（写动作 / 检索 / 导航都不调 LLM） |
| 子 run local（内部 ReAct） | **7345** |
| 子树聚合 `subtreeTokens` | 7345 = 0 + 7345 ✅ **不重复不漏** |
| `message.token_count` 口径 | 8254 + 7345 = **15599** ✅ 可复算 |

口径不变量（**所有 run 的 local 求和 == `subtreeTokens`**）已写成测试断言，后续每一刀都要它继续成立。
重跑：`mvnw test -Dtest=TaskOrchestrationBaselineTests -DrunRealLlm=true`。

---

## 6. 模块 B：分类器职责收缩

### 6.1 问题

`AiIntent` 当前字段很多，下游消费者也多。如果直接把它拆成多个新 DTO 并一次迁移全部调用方，改动面过大，容易与工作区内正在进行的 Task Orchestration 冲突。

因此本模块先拆模型调用职责，不立即删除 `AiIntent`。

### 6.2 Route Classifier（双调用候选中的第一段）

只输出路由所需的最小包络：

```json
{
  "intent": "ARTICLE_AGENT",
  "suggestedAction": "AGENT",
  "suggestedWorkflowType": null,
  "risk": "LOW",
  "needsThinking": false,
  "requestKind": "TASK",
  "confidence": 0.91,
  "reason": "用户要求分析当前文章"
}
```

若采用双调用，Route Classifier 不负责：

- 学习计划标题和阶段摘录
- 文章主题和写作要求摘录
- 页面动作参数
- `requestedActions`
- `analysisGoalEvidence`
- `taskGoals`

上面的单 `intent` 只能作为**单目标示意**，不能直接拿来做 Extractor 的唯一分流键。当前编排已经支持跨类型多目标；
若第一段只选一个 intent，「点赞 + 优化」「搜索 + Workflow」会在进入 Extractor 前再次静默丢目标。双调用 spike
必须验证一个多标签候选包络（例如 `candidateDomains[]` / `requestKind`），或在歧义时 fail-open 到旧分类器；字段名
可以在 spike 中决定，但“第一段不得把多目标压成单值”是硬约束。

目标静态 Prompt：**不超过 6000 字符**（2026-09-30 补：**目前只是目标，尚无 spike 证明该体积可达且不损分界准确率**——本项目的 `ARTICLE_DETAIL_QA` vs `ARTICLE_AGENT` 分界曾反复调多轮才稳定，这类分界规则正是路由的核心，不能为字数砍掉；分区上线前必须重新锁定评测集）。

### 6.3 Domain Extractor（双调用候选中的第二段）

只有命中需要结构化字段的路由才调用：

| 路由 | 是否需要 Extractor | 提取内容 |
|---|---|---|
| 普通闲聊 | 否 | 无 |
| 简单 General Agent | 否 | goal 直接使用用户原话 |
| 单纯 Article Agent 分析 | 通常否 | 文章定位仍由 PageContext/会话锚 + 后端决议 |
| 文章复合动作/任务编排 | 是 | `requestedActions`、`analysisGoalEvidence`、`taskGoals` |
| 编辑器/导航/页面动作 | 是 | 动作类型和有限参数 |
| 创建文章 Workflow | 是 | topic、topicEvidence、categoryName、requirements |
| 学习计划调整/攻坚 | 是 | learningPlanRef、learningStageRef；计划列表按现有条件二次注入 |
| 钱包充值 | 否或确定性解析 | 金额继续由 `WalletAmountParser` 解析 |

每个 Domain Extractor 只加载本领域规则，目标静态 Prompt：**2000～5000 字符**。

### 6.4 兼容组装

新增 `AiIntentAssembler`，把 Route Classifier 和可选 Domain Extractor 的结果合并为现有 `AiIntent`。

迁移期下游仍接收 `AiIntent`：

- `AgentPlannerSupport` 不需要第一时间重写。
- `AiMessageServiceImpl` 的主路由结构保持不变。
- `AgentDecisionTrace` 继续记录最终候选和后端裁决。
- 工作区内现有 `taskGoals` 逻辑不被覆盖。

待稳定后，再评估是否把宽 `AiIntent` 拆成真正的路由对象和领域对象；不在第一阶段强做。

### 6.5 调用次数与 Token 取舍

分层后不是所有请求都变成两次调用：

```text
普通聊天 / 普通 Agent：1 次小路由调用
需要字段提取的业务请求：1 次小路由 + 1 次小领域提取
```

即使复杂请求是两次调用，只要总输入从约 3 万字符降到约 0.8～1.1 万字符，仍然是净节省。

还要单独计入现有“点名学习计划”的条件二次分类：今天最坏是 2 次（首轮 + 带计划列表重判）；若双调用简单叠加，
可能变成 Route + Extractor + 带计划列表 Extractor 共 3 次。P3 不能只测普通聊天和文章域，必须把两条真实计划定位
用例纳入调用次数与 P95 延迟统计。

验收时必须同时看：

- 总输入 Token
- 调用次数
- 首字延迟
- 路由准确率
- repair 率

不能只看调用次数下结论。

### 6.6 JSON 输出约束

现有 Workflow 已验证 `response_format=json_object` 能显著降低长 JSON 漏收尾问题。

分类器和 Extractor 都是纯 JSON 出口，实施时应做模型兼容验证后启用 JSON Mode，目的主要是：

- 降低 repair 二次调用概率。
- 避免额外解释文字污染解析。

它不能替代后端字段校验，也不能据小样本宣称 100% 不会输出坏 JSON。

现有 repair 还有一个必须在 P3 一并处理的基线缺口：`callClassifier(..., repair=true)` 会发起**全新请求**，但
repair system prompt 只写“字段与第一次要求完全一致”，既没有首轮完整 schema，也没有携带首次坏输出；user prompt
只有页面上下文、计划列表和用户原话。新模型当前 repair 率低不代表这条路径正确。候选方案的 repair 必须自包含本候选的
输出 schema，并继续遵守最小字段契约；是否同时带入坏输出由 spike 比较，不能让 repair 偷偷退回全量旧 Prompt。

---

## 7. 模块 C：结构化 AgentState

### 7.1 问题

当前 `List<String> observations` 同时承担：

- 模型决策上下文
- Verifier 证据
- 调试面板原始材料
- maxSteps 总结材料

一个文本列表承担四种职责，导致所有消费者只能重复裁剪同一份字符串。

### 7.2 建议状态模型

```text
ArticleAgentState（第一版仅文章域；学习域 / 钱包域推广见 §14.2 决策 3）
  goal
  target
    articleId
    anchorMode
  evidence
    article
    memories[]
    ragFacts[]
  completedActions[]
  rejectedActions[]
  missingEvidence
  latestError
  plan
  stepNo / maxSteps
```

关键规则：

- `goal` 是用户原话，优先级最高。
- `evidence` 只能来自真实 Tool/Executor 结果。
- `plan` 不进入 Verifier 证据。
- `thoughtSummary` 不进入决策状态。
- `completedActions` 使用结构化枚举，不依赖 observation 前缀判断。
- 每个 run 的状态放在运行期局部对象中，不能放进单例 Runtime 字段。

### 7.3 兼容迁移

**前提（2026-09-30 补）**：`AgentActionExecutor.execute(...)` 目前**只返回 `String`**（接口第 18 行，
Javadoc：「每个动作返回一段 observation 文本」）。因此 Reducer 只有两条路：

- **路 A**：解析 observation 自然语言——脆弱，且与项目「不让 LLM 改写的摘要成为权威文本」的既有原则相冲突；
- **路 B**：先把执行结果改成结构化返回契约（暂称 `AgentActionResult`），Reducer 只消费结构化结果，原 observation 退为审计展示——
  **推荐**，但会影响三个域（学习 / 文章 / 通用）的全部执行器，工作量不可低估。

**所以本模块的第一步是定义结构化结果契约，而不是直接写 Reducer。**具体使用 `record`、普通类还是接口层次，
以及三域一次迁移还是文章域先行，必须结合共享 Runtime 的消费方式做 spike 后再定。

第一阶段不删除现有 observation：

```text
工具执行结果
  -> 保留现有展示 summary
  -> 同时交给 AgentStateReducer 更新结构化状态
```

这样可以保持：

- 历史 `outputJson.summary` 展示不变。
- 开发者面板仍能看原始步骤结果。
- 新 Decider 使用结构化状态。
- 旧逻辑可在开发/评测环境回退。

### 7.4 上下文预算

> **⚠️ 2026-10-11 修正（重要，原表已作废）**：文章证据**必须分槽**，见下表。
> 原表把「文章结构与正文预览」定成 1200 是错的——实测单条结构摘要 1889～2313 字符、
> 单条聚焦片段上限 1200（`ArticleAgentActionExecutorImpl.FOCUS_SNIPPET_MAX`），
> 于是**一条片段就能占满整个槽位**，而 `appendSlot` 的策略是"单条超预算则截断最新那条 +
> 其余整体丢弃"，导致每轮 focus 补查都把结构摘要挤掉。
>
> 后果不是"少看一点"：Verifier 判「文章里到底有没有这段内容」**只能靠小标题结构**，
> 结构一丢它就只能连续判 NEED_MORE（实测三次拒绝原话的最后一条：「当前证据**仅包含总结片段**，
> 无法支撑对全文结构…的完整评价」）→ 补查 → 证据更窄 → 6 步耗尽 → 用户拿到
> 「信息不足以给出可靠结论」。一次真实请求浪费 18300 input token、3 份草案（每份 ~7s）全被丢弃。
>
> 触发条件是**答案粒度**而非文章长度：同一篇文章，「分析**总结**一下」2 步就过
> （整体概括用结构摘要足够），「分析一下」进细节 → 被拦 → 补查 → 挤掉结构 → 死亡螺旋。
>
> 修正后的预算如下；`verifierRejectionExhausted()`（同一缺失类型连续被拒 ≥2 次 **且已无下一次机会**）
> 作为兜底，保证最坏情况也是"带说明的回答"而不是"没有回答"。

| 槽位 | 字符预算 | 淘汰策略 |
|---|---:|---|
| **文章结构摘要**（无 focus） | **2400** | **常驻**——它是判断全文结构的唯一依据，不被聚焦片段淘汰 |
| **聚焦片段**（带 focus） | **1500** | 最新优先（同 focus 新结果替换旧的） |
| Memory | 600 | 去重后按相关度保留 |
| RAG | 800 | 去重后按相关度保留 |
| completed/rejected actions | 300 | 结构化短字段 |
| missingEvidence/latestError | 300 | 最新优先 |
| 总预算 | 约 6300（结构 2400 常驻 + 片段 1500） | 超限时按槽位优先级裁剪 |

这不是把现在的 `6000` 简单改成 `3200`。核心变化是先按语义槽位整理，再按每个槽位预算裁剪。

**分槽后的总量校验**：`verificationEvidence()`（Verifier 实际收到的）不含"已完成动作 / 系统反馈"，
上限 ≈ 2400 + 1500 + 600 + 800 + 300 + 槽位标题 ≈ 5800 < `ArticleEvidenceVerifier.MAX_CONTEXT_CHARS`（6000），
仍在原来那份 observation 重放的上限之内；`decisionContext()`（Decider 用）会再多 700 左右，不受该常量约束。

### 7.5 去重与最新优先

证据键建议由以下信息组成：

```text
actionType + targetId + focus + normalizedSummaryHash
```

规则：

- 同键同内容：不重复进入状态。
- 同键新内容：替换旧内容。
- 不同 focus：允许并存，但受槽位预算限制。
- Verifier 返回 `missingEvidence` 后，相关新证据优先级最高。
- 若仍保留文本 fallback，裁剪时必须最新 evidence 优先，不能继续最早优先。

### 7.6 DecisionContextBuilder

每一步给 Decider 的输入只包含：

```text
目标
当前步数
已确认目标对象
当前证据槽位
已完成动作
上次缺失证据/拒绝原因
当前仍可执行动作
```

不再发送：

- 已完成动作的完整说明文档。
- 已被新证据替换的旧 observation。
- 前端展示用 thought 规则长说明。
- 与当前 Agent 域无关的动作规则。

### 7.7 实施结果（2026-09-30，✅ 已完成）

分两步落地，**契约先行**——§7.3 明确要求"第一步是定义结构化结果契约，而不是直接写 Reducer"。

**第一步：结构化结果契约**（零行为变更）

- 新增 `AgentActionResult(summary, payload)`：`summary` 仍是原 observation 文本
  （历史 `outputJson` / 开发者面板 / 观察流全不变），`payload` 是新增的结构化事实。
- 根接口 `AgentActionExecutor.execute` 改为返回它；文章域填 payload，学习 / 通用 / 钱包域返回空 payload。
- Runtime 唯一消费点取 `summary()`；`withSummary()` 保证「加来源标注 / 裁剪」不会丢掉 payload。
- 验证：**四个域 466 例全绿**（含 140 例直接受影响类），无一处行为变化。

文章域 payload（第一版只有它填）：

| 动作 | 字段 |
|---|---|
| QUERY_ARTICLE | `articleId` / `articleTitle` / `owned` / `focus` / `focusHit` |
| QUERY_MEMORY | `query` / `hitCount` |
| SEARCH_RAG | `keyword` / `hitCount` / `strategy` |
| 失败 | `failed=true` |

`focusHit` 是关键：聚焦命中与未命中**都会返回一段文本**，只有结构化字段能区分——
这正是 §7.3 否掉「解析 observation」那条路的原因。

**第二步：结构化状态 + 决策上下文切换**

- 新增 `AgentRunState` 接口（**per-run 硬约束**：由 `newRunState` 工厂每次新建）；
  基类钩子默认返回 `null` → 学习 / 通用 / 钱包域继续走 observation 重放，零影响。
- 新增 `ArticleAgentState`（§7.2 状态模型）：`reduce()` 只吃 payload，维护 target / 证据槽位 /
  已完成动作 / 缺失证据 / 最近失败；`decisionContext()` 按 §7.6 组装，槽位预算见 §7.4，
  超预算时**最新优先**（与 P0-a 修过的裁剪取向一致）。
- 文章域 `newRunState` 覆写启用；`run.setContextJson(...)` 仍是全量 observation（审计完整，不受影响）。

**实测（真实 LLM，复合样本）**：

| 场景 | 结果 |
|---|---|
| 2 步（点赞 + 分析） | 输入 4246 → **4285**（+0.9%，原因见下） |
| 4 步（分析 + 记忆 + 检索 + 作答） | 第 1 步 1857，**后续 2433 / 2460 / 2477 基本不再增长**（旧路径会随 observation 累积线性上升） |
| 6 步同类查询（离线构造，确定性） | 决策上下文 5085 → **1091 字符**（-78%） |

**踩坑（被真实数据抓出来的）**：第一版 `decisionContext()` 把「目标」与「步数」也写进了槽位——
而 `LlmAgentStepDecider#buildUserPrompt` **已经单独输出**「目标：」「当前步数：」，等于重复计费；
空状态还凭空加了格式开销，导致 2 步 run 输入**反增 6.4%**（4246 → 4518）。
修法：去掉重复头部 + 空状态返回空串（与旧路径 `clipContext(空)` 行为一致），第 1 步随即回落到 **1857**（与 P2.1 完全相同）。

**结论**：P2.2 的收益**随步数增长**——短 run 持平（+0.9% 是必要的槽位标题开销），
长 run 因为「键去重 + 槽位预算」不再累积。这与 §7.4 的定位一致：
不是把总预算从 6000 改成 3200，而是**先按语义槽位整理，再按槽位裁剪**。

> 遗留：Verifier 与 summarize 仍吃完整 observation（§9 模块 E 未实施）；
> `ArticleAgentState` 目前只用于决策上下文，`missingEvidence` 还未反向喂给 Verifier 的转问路径。

---

## 8. 模块 D：P2 第一刀——按运行模式裁剪 Decider Prompt

### 8.1 为什么从这里开始

§5.5 长文 5 步样本显示：每步 Article Decider 静态规则约 `2400～2500 Token`，静态 Prompt 约占 run 输入 **74%**；
observation 累积重放约占 **15%**。当前 `ArticleAnalysisStepDecider` 又是：

```text
完整 Article Prompt
  + 末尾追加“只允许四个分析动作”的覆盖声明
```

这意味着 analysis-only 每一步仍会发送 `ASK_USER / SUGGEST_WORKFLOW / SUGGEST_WRITE` 的动作说明和分界规则，
随后再告诉模型禁止使用。该浪费由当前源码直接证明，不需要先建设 `ArticleAgentState` 才能修。

### 8.2 第一刀的 Prompt 组成

第一刀把 Article Prompt 按职责拆成可组合片段，具体方法名由实施时遵循现有类结构确定，不在设计稿写死：

```text
公共核心
  = 角色与目标 + JSON 输出约束 + 防注入 + 后端裁决声明

文章定位规则
  = PageContext / 会话锚 / anchorMode / focus

动作规则片段
  = QUERY_ARTICLE / QUERY_MEMORY / SEARCH_RAG / ASK_USER /
    FINAL_ANSWER / SUGGEST_WORKFLOW / SUGGEST_WRITE

运行模式
  FULL          -> 装载现有全部动作及对应分界规则
  ANALYSIS_ONLY -> 只装载 QUERY_ARTICLE / QUERY_MEMORY / SEARCH_RAG / FINAL_ANSWER
```

正常 Prompt 与 repair Prompt 必须使用同一个运行模式来源，不能正常轮裁剪、repair 轮又把禁用动作带回来。
现有 `AgentRunPolicy.allowedActions` 继续做后端权威校验；Prompt 裁剪只减少模型输入和误选概率，不承担安全职责。

### 8.2.1 手工分段与可达性估算（2026-09-30，静态 spike）

按同一手工口径分段估算 `ArticleAgentStepDecider` 的静态文本（已剥掉 Java 文本块缩进）：

| 段 | 字符 | analysis-only 下 |
|---|---:|---|
| 核心（角色 + 目标） | 40 | 保留 |
| 定位规则（anchorMode / 候选） | 444 | 保留 |
| 动作声明：QUERY_ARTICLE / QUERY_MEMORY / SEARCH_RAG / FINAL_ANSWER | 539 | 保留 |
| 动作声明：ASK_USER / SUGGEST_WORKFLOW / SUGGEST_WRITE | **640** | **可裁** |
| 决策规则（保留部分） | 924 | 保留 |
| 决策规则（写动作 / Workflow 专项） | **605** | **可裁** |
| 决策规则（**交织段**：指代判定、anchorMode 说明里夹着 ASK_USER） | **386** | **需按模式改写** |
| 输出格式行 | 108 | 保留 |
| repair prompt | 419 | 同源裁剪 |
| plan 段（仅首步，V3.13 已优化） | 254 | 保留 |
| summarize prompt | 152 | 保留 |

可达性估算（最终基线仍以 §8.4 的统一测量函数为准）：

```text
当前 analysis-only 正常 prompt：手工分段约 3700～4000 字符
只删「干净片段」（3 类动作声明 + 写 / Workflow 规则）→ 约降 33%
再按模式改写交织段（386 里的 ASK_USER 部分）          → 预计进一步下降
```

**这次分段定位否掉了一种省事做法**：把三个动作段落整段删掉就收工。
`ArticleAnalysisStepDecider` 当初选「末尾追加」而非拆分，正是因为终态动作的说明
**散落在决策规则十几处**，其中「指代判定」与「anchorMode」两段把 ASK_USER 和定位逻辑写在同一条里——
交织段也必须按模式改写，不能因为数字门已经通过就整段保留。

> **口径校正（Codex 复核）**：本节的 11 段是手工归类结果，适合判断“规则散落在哪里”，不适合作为最终验收分母。
> 当前文档曾同时出现 `3723 / 4519 / 5144` 三个基线，以及“交织段改写后约 37%”与“单次发送仅 34.1%”两个互斥结论；
> `3723 + 419 + 254 + 152` 本身也不等于 `4519`。因此这些数字不再承担达标判定，实施第一步必须由同一个运行时测量函数
> 固化 FULL / ANALYSIS_ONLY 的 normal 与 repair 长度，再记录唯一基线。

### 8.3 第一刀明确不做

- 不新增 `ArticleAgentState`、Reducer 或结构化执行结果契约。
- 不修改 `AgentActionExecutor` 的 `String` 返回类型。
- 不修改共享 `AbstractAgentRuntime` 的循环、持久化和恢复语义。
- 不根据 observation 文本猜测“当前还剩哪些动作可用”。
- 不在执行到某一步后动态隐藏 `QUERY_ARTICLE / QUERY_MEMORY / SEARCH_RAG`；这需要后续结构化状态支撑。
- 不顺带压缩分类器、Learning Agent、General Agent 或 Workflow Prompt。
- 不为了字符数删除完整版里已有的历史 bug 分界规则。

`ArticleAgentStepDecider` 的完整版语义保持不变；第一刀的直接收益主要来自 analysis-only 子 run。
若只完成“把长字符串拆成多个方法”但 analysis-only 最终仍拼回全部片段，则不算完成。

### 8.4 第一刀验收

**分母口径（必须先钉死，否则门槛无从计算）**：静态字符数指
**单次正常决策实际发送的 system prompt**——剥掉 Java 文本块缩进、不含注释、
**不含 repair prompt**（它只在解析失败时发送，基线里不是每步都有）、不含 plan 段与 summarize prompt。
基线与裁剪后必须用**同一个测量函数**量取。

> 为什么必须写死：repair、plan、summarize 并不会在每次普通决策中一起发送，把它们并入分母会稀释本刀真实收益。
> Token 成本按「每次实际发送」计，所以取单次 normal system prompt 口径；repair 另设结构契约，不混进 normal 降幅。

确定性契约测试：

- analysis-only 正常 Prompt 不得包含 `ASK_USER / SUGGEST_WORKFLOW / SUGGEST_WRITE` 的动作声明和执行规则。
- 指代判定、anchorMode 等交织段中的 `ASK_USER` 分支必须按模式拆开；不得为了通过数字门而整段保留。
- analysis-only repair Prompt 的合法动作列表必须精确收窄为四项。
- **FULL 模式拼接结果与改造前逐字一致**（改造前先存完整文本夹具，正常版与 repair 版分别断言逐字相等）。
  这是整个重构的安全网：片段拆分最容易出的事故是 **analysis-only 全绿、FULL 却悄悄少了一段历史规则**
  （V3.8 指代判定 / V3.11 focus / 受控写三明治），而那种丢失只有线上真实对话才暴露。
- FULL Prompt 仍包含现有七类动作、文章定位规则、受控写与 Workflow 分界。
- 两种模式继续由后端白名单兜底，非法动作不得被执行。
- analysis-only 静态 system Prompt 字符数相对当前基线降低至少 **30%**。
  该数字只是防“拆了方法却仍拼回全部内容”的护栏；是否真正完成由上面的结构门判断，不能用超过 30% 代替交织段处理。

真实 LLM 对比沿用 P0-b / P1 的固定夹具和 Token 口径，不新造一套统计：

- 固定“动作 + 分析”复合样本至少重复 3 次，比较子 run 输入 Token 中位数。
- analysis-only 子 run 输入 Token P50 相对当前复合基线降低至少 **20%**（实测 **-24.7%**，见 §8.9）。
- 平均 Agent 步数不得比基线增加 `0.3` 步以上。
- repair 率不得高于基线，终态语义和 TaskStep 回填不得回归。
- 相关离线测试与文章 Agent / 动作 + 分析在线评测门通过。

`30%` 是静态文本的最低护栏，三项禁用动作归零、交织段按模式处理、FULL 逐字等价是结构硬门；`20%` 是包含
goal、observation 等动态输入后的真实 usage 门。三类门同时满足，避免为了贴近静态上限删规则，也避免只拆代码不产生真实收益。

### 8.5 后续：稳定规则与状态驱动规则分离

完成第一刀并拿到数据后，才进入依赖 `ArticleAgentState` 的状态驱动裁剪。届时稳定 system prompt 只保留：

- 角色和目标。
- JSON 输出结构。
- Prompt Injection 防护。
- 动作选择总原则。
- 不能绕过后端裁决的声明。

状态驱动的 user/context 部分只保留：

- 当前可用动作及必要参数。
- 当前 AgentState。
- 当前缺失证据。
- 当前步数和终止条件。

### 8.6 后续：动态动作集合

例如文章已成功读取后：

- 不再重复解释如何通过 PageContext/会话锚定位文章。
- 如果目标只要求文章结构分析，可不继续暴露 Memory/RAG 动作。
- `SUGGEST_WRITE` 不满足前置条件时不暴露给模型。
- analysis-only 模式直接从动作集合删除等待态和写提案动作，而不是在长 Prompt 尾部追加覆盖声明。

后端白名单仍然保留，动态动作集合只是减少模型看到的无关选择，不是安全机制。

### 8.7 可顺手评估：thoughtSummary

当前 thought 是可选展示副产品，却在每个正常步骤重复附加较长写作规范。

建议：

- system prompt 中只保留一条短规则。
- 首步、Verifier 拒绝后的转折步可以生成 thought。
- 普通查询步骤允许不生成，由 `AgentStepLabelSupport` 给确定性展示文案。
- thought 缺失不影响决策、执行、状态持久化和验收。

### 8.8 长期 Prompt 体积目标

| Decider | 当前静态字符 | 目标静态字符 |
|---|---:|---:|
| Article | 约 5144 ※ | 不超过 2200 |
| Learning | 约 2896 | 不超过 1800 |
| General | 约 880 | 不超过 800 |

※ **口径待统一**：本表 Article 的 5144 与 §8.2.1 手工分段结果不一致，说明两处测量的**范围或缩进处理不同**。
§8.4 的验收必须以实施第一步固化的运行时测量函数为准；本表只保留长期量级目标，不作为 P2.1 分母。

目标不是机械压字数；若压缩后评测失败，应恢复必要分界规则，而不是为了数字牺牲正确性。

### 8.9 实施结果（2026-09-30，✅ 已完成）

新增 `ArticleDeciderPrompts`（片段 + 按模式组装），两个 decider 只声明自己的模式。
analysis-only 不再是「完整 prompt + 末尾覆盖声明」，而是**根本不装载**三个禁用动作的声明与规则，
连交织在「指代判定 / anchorMode」里的 ASK_USER 分支也按模式改写。

| 门 | 口径 | 基线 | 结果 |
|---|---|---:|---:|
| 结构硬门 | analysis-only 下禁用动作出现次数 | 十余处 | **0** |
| FULL 逐字等价 | normal / repair vs 改造前夹具 | — | **逐字相同** |
| 静态门（≥30%） | analysis-only 正常 prompt 字符数 | 3986 | **2522（-36.7%）** |
| 真实 usage 门（≥20%） | 子 run 输入 Token | 5642 | **4246（-24.7%）** |

真实 usage 分步：QUERY_ARTICLE 2555 → **1857**（-27.3%）；FINAL_ANSWER 3087 → **2389**（-22.6%）。
**3 次重复的输入完全一致**——输入 token 是确定性的，抖动只在输出（out 14/14/14 与 170/178/159）。

不回归：路由仍 `ORCHESTRATE`；步数 2 → 2（门槛 ≤+0.3）；3 次均无 repair；
外层 TaskStep 回填 `code=APPLIED` / `code=ANSWERED` 不变；离线 92 例全绿（含四个域 decider 测试）。

基线口径：`DeciderPromptBaselineDumpTests` 在改造前落盘四份夹具（FULL normal **3795** / FULL repair **388** /
analysis-only 正常 **3986** / repair **579**），取**运行时字符串长度**——
手工分段统计（3723 / 4519 / 5144 三个互相矛盾的数字）已废弃。

**两个实施坑（已固化成测试，不是口头约定）**：

1. `anchorSection` 首行原措辞是「如需定位文章（**QUERY_ARTICLE / SUGGEST_WRITE**）」——
   只删动作声明段落**不够**，这句话里的 `SUGGEST_WRITE` 也要按模式收窄。它正是 §8.2.1 说的"交织段"。
2. `rulePronoun` 的续行若用文本块拼接会**丢缩进**（文本块会剥离公共缩进），必须用带显式缩进的字符串字面量。
   这个 bug 是被 **FULL 逐字等价测试**抓出来的——少了 2 个空格语义没变，但"零变更"的承诺已经不成立。

---

## 9. 模块 E：Verifier 与收尾路径

### 9.1 保持 Verifier 职责不变

Verifier 仍然只回答：

```text
这个回答草案是否被现有证据支撑？
```

它不负责：

- 选择下一步动作。
- 修改回答。
- 生成新的事实。
- 放宽写动作安全边界。

### 9.2 输入改造

Verifier 不再自行接收完整 observation 流水，而是接收：

```text
用户目标
回答草案
与当前目标相关的 AgentState.evidence
结构化 successfulActions
```

证据预算建议不超过 2500～3200 字符。

现有零 LLM 规则闸继续优先执行：

- 目标文章未成功读取。
- 零 observation 的非证据型回答。
- 重复查询没有产生新证据。

只有规则闸无法判断时才调用语义 Verifier。

### 9.3 maxSteps 收尾

到顶路径区分三种情况：

1. 没有关键证据：后端保守模板，不调 summarize。
2. 有结构化证据但没有合格草案：允许一次 compact summarize。
3. 已有被 Verifier 放行的回答草案：直接使用，不重复总结。

compact summarize 只接收 AgentState，不接收原始 observation 流水。

### 9.4 实施结果（2026-09-30，✅ 已完成）
**改了什么**

- `AgentRunState` 新增结构化上下文出口（槽位预算合计约 3200）。
- `verifyBeforeFinalization` 增加 `AgentRunState` 参数：文章域有状态时只喂 `verificationEvidence()`，
  不再传完整 observation 流水；**空状态仍传空列表**——保住「零 observation 的非证据型回答」
  那条零 LLM 规则闸的语义（传 `List.of("")` 会让它失效）。
- 到顶收尾 `completeWithSummary` 切到 `summaryContext()`（§9.3 第 2 种情况）。
  第 1 种（无关键证据走保守模板）与第 3 种（草案已被放行不重复总结）**本来就没有额外 LLM 调用**，未动。

**实测（真实 LLM）**：

| 指标 | 2 步样本 | 4 步样本 |
|---|---:|---:|
| 子 run local | 6027 | 11316 |
| 步骤合计（in+out） | 4471 | 9570 |
| **Verifier + 收尾等非 step 消耗** | **1556** | **1746** |

**与 P2.1 对比**（同一样本、2 步）：非 step 消耗 1515 → 1556（**+41，可忽略**）——
2 步场景下 observation 本来只有一条，没触及任何裁剪上限，换了源头但长度接近。

**收益同样在长 run**：Verifier / summarize 的证据从「observation 重放（6000 字符上限）」
变成「槽位证据（约 3200 上限，且键去重）」——**上限本身降约 47%**，且不随步数累积。

**本轮未做（有意留作独立一刀）**

§8.6 的**状态驱动动作集合**（如"文章已读取后不再暴露定位规则"、"`SUGGEST_WRITE` 前置不满足时不暴露"）
需要给 `AgentStepDecider.decide(...)` 增加"当前可用动作"参数，会波及四个域的 decider 实现；
§8.5 明确要求"完成第一刀并拿到数据后再进入"——现在数据有了，可以独立评估。
注意它**只改模型看到的动作集合，不改后端白名单**（§8.6：白名单仍是权威安全机制，
动态集合不是安全机制；错误收窄白名单会让模型选到"看不见但允许"的动作，骨架遇非法动作直接终局）。

### 9.5 评审后修订（2026-09-30，Codex 复审）

复审指出四处**闭环语义**问题（Token 降幅本身成立）。四处都已核实并修复：

1. **反馈断链（高）**：Decider 已只读 `decisionContext()`，但 Verifier 的 NEED_MORE、重复查询拦截、
   Workflow 拒绝提示仍**只写 observations** → 模型下一轮不知道"刚才为什么被拒、该补什么"，
   容易重复同样的动作直到步数耗尽。
   **修**：`AgentRunState.noteFeedback(message)`；三处反馈两边都写（observations 保审计完整、状态保模型可见）。

2. **R3 放行规则静默失效（高）**：R3「本轮没有新证据 → 放行，避免空转到顶」的判据是
   `noNewEvidence(observations)`，它要求 **≥2 条** observation；而结构化路径只喂**一份**槽位证据
   → 恒返回 false，规则等于消失，本该放行的继续被拦。
   **修**：`AgentRunState.lastStepHadNewEvidence()`（默认 true，未知即当作有新证据）；
   文章域用**去重键**判断（同键同内容 = 无新证据）；Verifier 的 R3 改为「文本比对 **或** 结构化信号」。

3. **槽位预算不是硬上限（中）**：`appendSlot` 原先无条件保留第一条——Memory 预算 600，
   首条 1500 字符也会整条进入，所以"证据上限约 3200"名不副实。
   **修**：单条超预算时**截断**，并补「单条超预算」测试。

4. **记忆检索失败被当成无命中（中）**：检索异常被吞后仍返回 `hitCount=0`，
   状态据此告诉模型"记忆里没有命中，不必再查"——把**故障**说成了**事实结论**。
   **修**：payload 增加 `status`（SUCCESS / PARTIAL_FAILURE / FAILED，语义记忆与情景记忆两个来源分别计）；
   状态对失败给"可重试"，对部分失败给"命中可能不全"。

**测试盲区（这一节最该记住的）**：上述 1、2 两条能溜过去，是因为
**Runtime 测试只预设"下一步决策返回什么"，从不检查"模型看到了什么"**——
18 例状态测试 + 37 例 Runtime 测试全绿，模型却看不到拒绝原因。
新增 `ArticleAgentRuntimeDecisionContextTests` 直接断言 Decider **实际收到的上下文**；
并已验证它有效：临时禁用 `noteFeedback` 后两条测试都失败。

> 同批修正了 §7.7 的一处口径笔误：4 步样本的 2433 / 2460 / 2477 是**第 2~4 步**（第 1 步 1857），
> 不能写成"每步就是这三个数"。正确表述是"后续步骤未继续线性增长"。

### 9.6 二次复审修订（2026-09-30，Codex 第二轮）

第一轮修完后复审又指出四处（前两处为高），均已核实并修复：

1. **领域旁路的反馈仍断链（高）**：公共骨架修了 Verifier / Workflow / 重复查询三处，
   但**文章域自己的两处**（零观察提案写动作被拒、同名改标题 / 已隐藏等业务拒绝）还只写 observations——
   `handleExtraTerminalAction(...)` 拿不到 runState，第一轮的新测试只覆盖了公共三处。
   **修**：不追加参数，改用**索引差同步**（`syncFeedback`）——调用钩子前记下 observations 长度，
   返回后把新增项作为反馈同步进状态。一处覆盖**所有域**的钩子（学习 / 文章 / 钱包都覆写了它），
   不必改三处签名、也就不会"漏一个再犯一次"。

2. **反馈生命周期不正确（高）**：`noteFeedback` 把同一条反馈同时写进 `missingEvidence` 与 `latestError`
   → 同一句在 prompt 里出现**两遍**、`latestError` 逃出所有预算；更麻烦的是**补查成功后旧反馈不清除**：
   Verifier 说"缺正文细节"，随后带 focus 的补查成功了，但反馈以「系统提示」开头、缺失项以「文章内容」开头，
   前缀清不掉 → 下一轮 Verifier 又看到"证据不足"，形成**自我强化**。
   **修**：反馈独立成 `【系统反馈】` 槽位（400 字符预算、去重、只出现一次）、携带
   `FeedbackClearKey`（`ARTICLE_CONTENT / MEMORY / RAG / QUERY_REQUIRED`），对应条件满足时按枚举清除；
   `latestError` 只留给动作失败。不能使用「文章正文细节」等中文展示标签作为生命周期键，
   否则标签与状态槽位命名不一致时会静默清理失败。

3. **RAG 缺失不收敛（中）**：`SEARCH_RAG` 无命中会加"站内知识无命中"，但换关键词命中后**没有删除**
   （Memory / Article 都有 `removeIf`，RAG 漏了）→ 缺失项只增不减。
   **修**：补 `removeIf` + `clearFeedback`。

4. **测试仍少一层（中）**：补了 5 条——写提案拒绝进上下文（验证第 1 条）、补到证据后旧反馈消失、
   换关键词后 RAG 缺失消失、`lastStepHadNewEvidence=false` **确实传到 Verifier**、
   **`runAnalysisOnly()` 真实入口用的是 analysisDecider**（原先测的是 FULL 入口，而编排层走 analysis-only）。

**这一轮又抓出一个我自己没发现的真 bug**：`isEmpty()` 漏了 `feedbacks` ——
只有一条拒绝提示、没有其他事实时，`decisionContext()` 返回**空串**，模型两眼一抹黑。
它是被 `duplicateQueryHintReachesNextDecisionContext` 的失败（actual 是空字符串）暴露的。

> **两轮下来同一个教训**：降 token 的重构必须配「断言模型实际看到了什么」的测试。
> 状态测试 + Runtime 测试全绿，挡不住"反馈根本没进上下文"这类断链。

### 9.7 三次复审修订（2026-09-30，Codex 直接修复）

二次修订后继续沿真实数据流复核，补了三处状态生命周期：

1. **临时查询前置反馈会永久残留**：索引差同步把反馈统一记成无清理类型；零观察写提案被拒后，
   即使下一步已经完成查询，Decider 仍看到“必须先查询”。新增 `FeedbackClearKey.QUERY_REQUIRED`，
   `SUGGEST_WORKFLOW` 与文章域零观察 `SUGGEST_WRITE` 都显式标记，任一只读查询产出结果后清除。
2. **Verifier 与总结共用同一上下文**：`decisionContext()` 同时包含系统反馈和最近失败，导致下一轮
   Verifier 把自己上一轮的“证据不足”当成证据再次判断。现拆为 `verificationEvidence()` 与
   `summaryContext()`：前者只含目标、真实证据和客观缺失，后者保留动作、反馈与失败用于如实收尾。
3. **成功重试后旧错误残留**：`latestError` 原先只有字符串、没有所属动作，也没有清理入口。
   现绑定 `actionType`，同动作成功后清除；无关动作成功不会掩盖该错误。

新增 4 条直接断言：查询前置反馈退出、真实 Runtime 下一轮不再看到旧拒绝、Verifier 不读取自身旧反馈、
同动作成功重试后旧错误消失。所有测试都先在旧实现上按预期失败，再由对应最小修复转绿。

---

## 10. 上线与回退策略

### 10.1 分阶段实施

```text
阶段 0：最新证据窗口修复 + Token 可观测性
阶段 1：ARTICLE_ANALYSIS + 父子 run Token 口径
阶段 2：按运行模式裁剪 Article Decider Prompt（先拿可量化降幅）
阶段 3：结构化执行结果 + ArticleAgentState + DecisionContextBuilder
阶段 4：Verifier/summary 使用结构化状态 + 反馈生命周期收口（动态动作集合转独立可选项）
阶段 5：分类器单调用 / 双调用 spike，胜出方案灰度
阶段 6：评估其他域推广、Prompt Cache 和判断模型进一步优化
```

每个阶段单独验收，不把五刀绑成一次大提交。

### 10.2 双轨验证

分类器 V2 需要 `LEGACY / SHADOW / ACTIVE` 三态：

- `LEGACY`：只走旧分类器。
- `SHADOW`：仅在开发/评测环境同时运行新旧分类器，业务仍采用旧结果，记录差异。
- `ACTIVE`：采用新结果，保留快速回退能力。

`SHADOW` 会额外消耗 Token，因此禁止在正常生产流量长期打开。

AgentState 迁移不需要长期双跑两个 Agent；只需要在测试中对相同工具结果比较旧 context 与新 state 的决策结果。

### 10.3 失败处理

- Route Classifier 失败：沿用当前降级语义，不自动启动高影响 Workflow/写动作。
- Domain Extractor 失败：保留 Route 结果，但缺少必要字段时进入 CTA/ASK_USER，不猜参数。
- AgentStateReducer 解析某个工具结果失败：保留原 observation 供调试，本步状态只标记 reducer error，不编造槽位。
- Verifier 调用失败：保持现有 fail-open，但结构规则闸仍生效。
- JSON Mode 不被某个模型支持：按模型能力关闭，不能让整个判断链不可用。

---

## 11. 验收指标

### 11.1 正确性门槛

必须满足：

- 当前 `docs/agent/agent-eval-gate.md` 规定的离线门全绿。
- 涉及分类器 Prompt/意图分界时，在线门全绿。
- Task Orchestration、动作 + 分析、文章受控写的现有样例不得回归。
- PageContext 只作为定位线索，文章归属校验保持后端权威。
- repair 失败不得静默启动 Workflow 或写动作。

### 11.2 Token 指标

建立固定样本集，至少覆盖：

- 普通聊天。
- General Agent。
- Learning Agent 2～4 步。
- Article Agent 2～4 步。
- 动作 + 分析复合请求。
- Task Orchestration 多目标请求。
- Verifier `SUFFICIENT / NEED_MORE / ASK_USER`。
- JSON repair 路径。

首轮目标：

| 指标 | 目标 |
|---|---:|
| 普通消息分类输入 Token P50 | 降低至少 65% |
| 复杂业务分类输入 Token P50 | 降低至少 50% |
| Article Agent run 输入 Token P50 | 降低至少 30% |
| Article Agent 端到端输入 Token P50 | 降低至少 50% |
| 4 步 Agent 累计上下文输入 | 降低至少 50% |
| repair 率 | 不高于基线 |
| 平均 Agent 步数 | 不高于基线 0.3 步以上 |

这些是完整 Token 工程的长期验收目标，不是当前已验证结果；P2 第一刀只适用 §8.4 的 analysis-only 双门槛，
不能要求它单独完成分类器或 observation 治理的总体目标。

### 11.3 延迟指标

分类器拆分可能增加调用次数，因此同时检查：

- 路由耗时 P50/P95。
- 首个 SSE 事件时间。
- Agent 首个可见 thought/status 时间。
- 端到端完成时间。

复杂请求若 Token 明显下降但延迟显著上升，需要评估是否把 Route 和 Domain Extractor 合并为“按候选域动态组 Prompt”的单调用方案。

---

## 12. 预计收益

按当前静态体积估算：

| 改造 | 单项预期收益 | 说明 |
|---|---:|---|
| 分类器分层 | 端到端输入降低 35%～55% | 最大收益点 |
| AgentState + 去重预算 | Agent run 输入降低 20%～40% | 步数越多收益越大 |
| Decider 运行模式裁剪 | analysis-only 子 run 输入降低至少 20% | P2 第一刀；不依赖 AgentState |
| Decider 状态驱动动作集合 | Agent run 输入降低 15%～30% | 后续项；与 AgentState 有重叠，不能简单相加 |
| Verifier compact evidence | 端到端输入降低 5%～12% | 只影响触发 Verifier 的请求 |
| thought 规则收缩 | 端到端输入降低约 1%～3% | 小收益，顺手做 |
| maxSteps 确定性收尾 | 特定到顶请求少一次调用 | 非高频主收益 |

组合目标不是把百分比直接相加。典型文章 Agent 预期从约 **2.5～4.5 万输入 Token** 降到约 **0.8～1.8 万**，最终以真实 usage 为准。

---

## 13. 行业常见做法对照

主流生产 Agent 通常不会把所有能力说明和全部工具历史无限重放，而是采用：

1. 小路由器选择领域，领域 Agent 只加载自己的规则。
2. Tool 结果归一为结构化状态，而不是不断追加自由文本 scratchpad。
3. 每一步只暴露当前可用工具或动作。
4. 安全、权限、幂等和参数合法性由后端确定性代码负责。
5. 为每个请求设置 Token/步骤预算，并在预算耗尽时保守收尾。
6. 对稳定静态前缀使用 Prompt Cache，但不把缓存当成上下文治理。
7. 判断链和生成链使用不同模型或参数，但先保证输入职责清晰。

本项目已经具备第 4、5、7 点的大部分基础；本设计重点补齐第 1、2、3 点。

---

## 14. 风险与待评审决策

### 14.1 主要风险

1. 分类器拆分后，两次小调用可能带来延迟增加。
2. Prompt 压缩可能删除历史 bug 对应的关键分界规则。
3. AgentState reducer 如果丢字段，可能比原始 observation 更难察觉。
4. 动态动作集合如果计算错误，可能让本可执行动作对模型不可见。
5. Token 下降可能伴随平均步数上升，最终总成本未必下降。

对应防线：评测门、开发者 Token 面板、旧结果对照、后端白名单和分阶段回退。

### 14.2 需要确认的设计决策

当前结论分为已定与待测：

1. **待测**：分类器 V2 采用单调用模块化 Prompt，还是 Route + Extractor 双调用；不能预设复杂请求一定改成两次调用。
2. **已定**：迁移期保留现有 `AiIntent` 作为兼容组装结果，稳定后再评估拆 DTO。
3. **已定**：P2 第一刀先做运行模式级 Article Decider Prompt 裁剪，不先建设 `ArticleAgentState`。
4. **已定**：若后续建设结构化状态，第一阶段只覆盖文章域；推广到学习域 / 通用域需要文章域实测证据。
5. **已定**：50% 保留为整体 stretch target；P2 第一刀采用三类门：结构硬门、静态 Prompt 至少降低 30%、
   子 run 输入 Token P50 至少降低 20%。同时正确性评测不退化、repair 率不升高、平均步数不明显增加。
   数字门不能替代禁用动作归零、交织段分流和 FULL 逐字等价。

---

## 15. 推荐实施顺序

```text
P0-a：clipContext 选取最新窗口、保持原时间顺序  ✅ 已实施（2026-09-30）
  -> 抽 AgentContextClipper 单一实现（原先两处逐字重复 = 缺陷根因），8 例定向测试

P0-b：开发者侧 Token 可观测性  ✅ 已实施并完成基线首采（2026-09-30；2026-10-02 补齐）
  -> 只扩开发者 VO（AgentRunDevDetailVO / AgentStepRawVO），展示 local/subtree；补编排链路与 Task CTA/preflight routeUsage 落库
  -> 固定长文夹具 + 真实 LLM + DB 回读，基线见 §5.5

P1：接入 ARTICLE_ANALYSIS  ✅ 已实施并完成复合基线（2026-09-30）
  -> 同时确定父子 run Token 归属
  -> 增加「外层 TaskPlan + 内部 ReAct」复合基线

P2.1：按运行模式裁剪 Article Decider Prompt  ← ✅ 已完成（2026-09-30，实测见 §8.9）
  -> FULL 保持现有语义；ANALYSIS_ONLY 只加载四类允许动作
  -> 不改 Runtime / Executor 返回类型 / AgentState
  -> 静态 Prompt 降幅 + 真实子 run usage 双门验收（见 §8.4）

P2.2：文章域结构化执行结果 + ArticleAgentState
  -> ✅ 已完成（2026-09-30）：结构化结果契约、Reducer、槽位预算与去重均已落地

P2.3：Verifier / summarize 切到 compact state + 反馈生命周期收口
  -> ✅ compact state、反馈生命周期、Verifier / summarize 已完成并通过页面手测（2026-10-01）
  -> 状态驱动动态动作集合不再作为 P2 收口条件，移为独立可选优化：它需改四域 decide 契约，当前收益不足以阻塞主线

C0：编排正确性收尾（分类器收缩前的小刀，不属于 Token 指标）  ✅ 已完成
  -> TaskPlanner 校验 StepType 内部参数白名单；内部 action 不得泄露给用户
  -> 仅在旧 Article Agent 链能完整重新裁决时显式 LEGACY；否则 CTA，禁止把未知别名直接映射成高影响动作

WORKFLOW_HANDOFF 5a：只冻结分类契约与评测集  ✅ 已完成（2026-10-01）
  -> 定义 type / params / goalEvidence / fail-closed，不先写暂停恢复执行链
  -> 把下一阶段会新增的分类输出纳入 P3 基线，避免 P3 完成后立即改协议
  -> 最小参数只允许 workflowType；证据必须逐字来自原话；单目标双写旧字段，多目标清空旧单值字段
  -> 当前模型 qwen3.7-max-2026-05-20 定向在线门 15/15 通过（Surefire：18 run / 0 fail / 0 error / 3 filtered）

P3：分类器收缩 spike 与实施  ✅ spike 已完成（2026-10-01）
  -> 单调用模块化 Prompt、双调用 Route + Extractor 使用同一评测集对比
  -> 数据胜出的方案才进入 ACTIVE
  -> 结论：**单调用按需装载（A）胜出、双调用（B）停止**。A 在线配对 26 条：输入降幅 33.8%、
     调用次数不增加、P50 反而更快（3865→3108ms）；B 相对 A 只再省 10.8%，而调用 10→19、P50 +40%
     → 按 15% 门槛与供应商限流现实淘汰。**B 不是"双调用永远无效"**，它是不适合当前模型 /
     当前 Prompt 结构（19 块里 27% 是输出契约）/ 当前限流条件
  -> 激活前补两处保护：**复合目标信号回退 FULL**（线索表只认识它认识的目标，会把第二目标规则裁掉）、
     **`blog.ai.classifier-mode` 一键回退开关**（默认 `ON_DEMAND`，可显式切 `FULL`）；2026-10-02 按当前 Prompt 重测离线降幅 **23.1%**
  -> ⚠️ **模型口径**：本轮 A/B 实测用 `qwen-plus-latest`（`blog.ai.judge-model` 默认值），
     与上文 5a 那次 `qwen3.7-max-2026-05-20` **不是同一个模型**，两轮延迟不可直接比较
  -> 结论与证据：docs/agent/p3-classifier-shrink-handoff.md

回归 Agent 主线 5b：WORKFLOW_HANDOFF + WAITING_CONFIRM 后恢复剩余 TaskPlan  ✅ 已完成
  -> 文章域“低风险动作 + OPTIMIZE_ARTICLE 确认”的跨请求暂停 / 恢复闭环已落地
  -> 2026-10-02 补齐后置边界、状态 CAS、巡检、短事务收敛与绝对 Token 公式

P4：学习域 / 通用域推广、Prompt Cache、Decision Model A/B
  -> 以文章域实测结果决定是否值得
```

§16 保留评审依据与尚未解决的口径问题；实施阶段只认本节这一份顺序，避免两套路线并存。

**阶段结论（更新于 2026-10-02）**：P2.1-P2.3、C0、`WORKFLOW_HANDOFF` 5a/5b 与 **P3 分类器收缩已全部收口**。
P3 已默认启用 `ON_DEMAND`，生产路径开始获得按需装载收益；3 条真实模型定向样例通过，当前 67 条完整在线门未运行。
相关定向测试与真实页面手测均通过；手测额外发现的
`changeTitle` 参数越界属于 Task Orchestration 的后端校验缺口，RAG 无关召回属于检索相关性缺口，二者均非
P2 Token 状态治理回归。后续为 **P4（学习域/通用域推广、Prompt Cache、Decision Model A/B，
以文章域实测结果决定是否值得）**；
紧邻能力的输出协议已经冻结。动态动作集合与 RAG 调优继续暂缓。

**P3 遗留欠账（登记）**：`actionType` 完整枚举只在 `NAVIGATE` 块 → "取消点赞"类请求模型看不到
`unlikeArticle`（评测集不覆盖、离线覆盖检查也抓不到）。「旧字段有 Workflow 信号但计划漏 handoff」已由后端
fail-closed 判据补齐，不再允许复合请求只执行半份。

---

## 16. 评审意见（2026-09-30，Codex + Claude 双评）

> 状态：评审依据。正文事实修正已经同步，权威实施顺序统一见 §15；本节只保留论证与开放问题。

### 16.1 诊断成立，顺序要改

三条根因（分类器全域规则 / observation 重放 / Verifier 复制证据）经代码核对**全部成立**。
**但 §15 把「分类器 V2」放 P0、理由是"先拿最大收益"——这不成立**：收益最大 ≠ 应该最先做。它踩两个已实测的约束：

1. **供应商限流是真瓶颈**（`agent-admission-stress-report.md:81-82`：40 并发 → 40 次分类调用 → 30 次失败）。
   路由 + 提取拆两次调用是**在瓶颈上加压**；
2. **父子 run（P1）会扩展 token 口径**——现有单 run 的 2/3/4 步基线仍然有效，但不能代表
   「外层 TaskPlan + 内部 ReAct」的端到端成本；接入后必须补一组复合基线，不能拿旧基线直接相减。

### 16.2 三处必须修正（**已按本节结论直接修正正文**）

| # | 位置 | 问题 | 改法 |
|---|---|---|---|
| 1 | §5.2 | 要给 `AgentRunDetailVO` / `AgentStepVO` 加 Token 字段，但这两个是**用户侧接口**（`AgentStepVO` Javadoc 即「用户侧安全摘要」，`AgentRunDevController:24` 同样声明），与 §5.3「不向普通用户侧暴露」自相矛盾；项目已有一致口径——`AiMessageVO` 不含 token 字段 | 只加 `AgentRunDevDetailVO` / `AgentStepRawVO`。**注意** `AgentRunDevDetailVO` 内嵌 `AgentRunDetailVO run`，run 级 token 要么放 DevDetailVO 外层，要么给 DetailVO 加字段但用户侧不填充 |
| 2 | §5 | **初稿完全没提父子 run 的 Token 口径**（子 run 是否汇总到父 run / 展示本层还是递归总量 / `message.token_count - run.total_tokens` 减的是哪一个）。当时编排链路 `streamTaskOrchestration` 也未把 `routeUsage` 落到 message，`routingTokens` 无法计算 | 补一节口径定义；并补编排链路 routeUsage 落库（P0-b / P1 已完成） |
| 3 | §7.2 | 状态模型列了 `learningPlan` / `wallet`，与 §14.2「AgentState 先以文章域试点」矛盾，会重演宽 `AiIntent` 的膨胀 | 删掉非文章域字段，第一版只做 `ArticleAgentState` |

另：§5.2 说 step VO 加 `totalTokens`，但 `AiAgentStep` 与 `ai_agent_steps` **只有 input/output 两列**（`init.sql:499-501`）——要么加列、要么在 VO 层算。

### 16.3 被埋没的正确性缺陷：`clipContext` 最早优先（正文 §1.3 已同步，P0-a 已实施）

代码核对：`AbstractAgentRuntime.clipContext`（1139-1151）与 `ArticleEvidenceVerifier.clipContext`（271-285）**两处实现相同**——
都从最早累加、满了 `break`，**满载时丢的是最新证据**。

这与 V3.11 证据收敛门的核心机制**直接对冲**：Verifier 判 `NEED_MORE` 后要求「先补充查询再回答」，
而补到的新证据最可能被截掉 → Verifier 继续拦 → 直到 maxSteps 用尽走保守收尾。

**它不是 token 问题，是决策质量缺陷**；不需要 token 基线，但不能直接把列表倒序发给模型。
应选取最新窗口后恢复原时间顺序，并补最新保留 / 最旧淘汰 / 顺序不变 / 总预算四类定向测试。
该修复已作为 P0-a 排在本设计所有 Token 优化之前完成。

### 16.4 分类器收缩：单调用与双调用都先做 spike

本稿把「分层」和「两次调用」绑在一起讲了。两者可以分开，而分开恰是当前限流下的最优解：

- **分层收益的大头**来自「普通聊天不再背全部领域字段规则」；
- 但「单调用只装一个域的规则」直接做不到——不知道域就无法组 prompt；
- `pageContext` 是后端确定性线索，但**不是域权限边界**：助手是全站入口，用户在 `article-detail` 仍然可以询问学习计划、钱包或创建 Workflow。
  因此它只能决定“当前页面专属规则是否注入、哪些规则提高优先级”，不能硬删除其他全局领域。
- 单调用若要继续收缩，只能采用“公共跨域路由规则 + 页面专属规则 + 由原文确定性命中的少量领域规则”的 fail-open 组装；
  原文线索不明确时必须保留候选规则，不能为了省 Token 静默降低召回。

**建议**：先用同一份样本集做两个最小 spike：A 为单调用模块化 Prompt，B 为 Route + Extractor 双调用。
对比 Token / RPM / TPM / 延迟 / 召回率 / repair 率后再决定主次，当前不能仅凭 `pageContext` 把单调用定为首选。
同时：`6000 字符`目标目前**只是目标、没有 spike 证明**，分区上线必须重新锁定评测集。

### 16.5 其它核实结论

- §6.5「不是所有请求都变两次调用」表述准确，故「把双调用定为主方案」的判断有偏差（见 16.4）；
- §7.3「工具结果同时交给 Reducer」低估工作量：`AgentActionExecutor.execute` 只返回 `String`（接口 18 行），
  Reducer 要么解析自然语言、要么先把执行结果改成结构化类型（影响三个域）——**应先定结构化结果契约**；
- §7.2 与 §14.2 都提到「文章域试点」，两者不冲突，冲突在 §7.2 的字段清单（见 16.2 #3）。

### 16.6 修订后的实施顺序（已同步 §15）

权威实施顺序已收口到 §15：P0 / P1 已完成；P2 先按运行模式裁剪 Article Decider Prompt，拿到真实降幅后再决定
结构化状态，最后用同一评测集比较单调用 / 双调用分类器。此处不再保留第二份步骤清单，避免后续状态漂移。

### 16.7 P3 留给讨论的开放项（**已收口，2026-10-01**）

> 本节三个开放项都已由 P3 spike 回答：**形态** → 单调用按需装载胜出（双调用在本项目的模型 /
> Prompt 结构 / 限流条件下不划算）；**测量口径** → 同模型、同链路、逐条交错的配对测量，硬门先于性能；
> **激活边界** → `blog.ai.classifier-mode` 一键回退开关（默认 `ON_DEMAND`，显式 `FULL` 回退）+ 复合目标信号保守回退。
> 结论与证据见 `docs/agent/p3-classifier-shrink-handoff.md`。以下正文保留为当时的讨论记录。

`ArticleAgentState` 与结构化结果契约已在 P2.2-P2.3 落地，不再是开放项。P3 只保留分类器收缩本身的取舍：

1. **分类器收缩形态**：单调用模块化 Prompt vs 双调用（Route + Domain Extractor）。
   `pageContext` 只能作为软线索，不能硬过滤全局领域；需要样本集数据（Token / RPM / TPM / 延迟 / 召回率 / repair 率）决定。
2. **测量口径**：两种候选必须使用同一模型、同一在线门（61 条数据驱动样例 + 2 条计划定位 + 1 条召回率聚合测试，
   JUnit 共 64 个 test execution）、同一采样参数和相同重试上限；分别记录首轮与 repair 的输入 / 输出 Token、
   P50/P95 延迟、请求数，以及多目标完整率，不能只比较静态 prompt 字符数。
3. **激活边界**：spike 只产数据，不直接替换 ACTIVE 路径；胜出方案仍需通过完整在线门，并保留旧分类器的一键回退开关。

首轮「降低 50%」已不再列为开放决策：它保留为整体 stretch target；P2 第一刀的验收线已经收口到 §8.4。
父子 run Token 口径已在 §5.4 收口，不再作为开放项。
