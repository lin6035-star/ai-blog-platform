# Agent 目标完成度闭环设计（讨论稿）

> 日期：2026-09-22  
> 状态：第一刀已实施（2026-09-23）；后续阶段仍未实施  
> 定位：在现有 Router、受限 ReAct、Workflow、受控写之上，补齐“用户要求了几件事、实际完成了几件事、最终能否如实回答”的任务完成契约。  
> 本文不是完整 Planner、Multi-Agent 或框架迁移方案。
>
> 第二轮修正：长期方向保留“目标完成度契约”；第一刀删除通用 Goal Spec、六态 Goal Ledger 与 `context_json` 持久化，收敛为“文章交互动作后端执行闭环”。
>
> 第三轮修正：澄清“复用 run/step”实为**新建 run**并补齐生命周期边界（§5.4）；并发幂等按表型拆分——软删除的关注靠条件更新，唯一索引兜不住（§6.4.3）；写明该路径不占准入是有意为之（§6.5）；补三处实施清单——停用 6 个文章动作工具（§7.5）、`favoriteArticle` 补登录检查（§6.4）、部分成功打可检索日志（§6.6）。
>
> 第四轮修正：选定“正常落消息后可刷新恢复”的窄承诺，不为消息落库前的进程崩溃引入占位消息状态机；明确启动恢复负责收敛 batch step 与计步字段；补登记 `db/init.sql` 缺少 `ai_messages.agent_run_id` 的 schema 基线漂移。
>
> 第五轮修正：卡死启动恢复只处理 `ARTICLE_INTERACTION_BATCH`；明确 `db/init.sql` 用本地 MySQL 8 临时库人工 smoke，不顺带修已登记暂缓的 `log_type`；补“文章动作 + 分析诉求”混合句边界；实施拆为领域底座与 Agent 迁移两刀。

> 实施记录（2026-09-23）：第一刀已落地。文章点赞 / 收藏 / 关注六种低风险动作改由后端批次执行，逐项持久化结构化结果，SSE `STOP` 返回 `actionResults`，前端只刷新权威状态；补齐 `ai_messages.agent_run_id` 初始化基线。后续阶段（确认后恢复、真正暂停 / 恢复、动态 ReAct 完成度评测）仍按下文路线推进。

## 〇、结论先行

当前项目已经具备有限的 `感知 -> 决策 -> 执行 -> 再感知`：学习、文章、通用 Runtime 能执行只读动作，把 observation 回填后继续决策；文章域还有 Evidence Verifier。

真正缺失的不是“再加一个更会规划的 LLM”，而是下面这条闭环：

```text
用户请求
  -> 识别 1..N 个明确目标
  -> 后端校验目标、对象、权限和风险
  -> 逐项执行并记录真实结果
  -> 判断是否仍有未完成目标
  -> 只能依据结果汇报全部成功 / 部分成功 / 失败 / 待确认
```

长期路线仍命名为 **Agent 目标完成度闭环**；但下一刀只命名为 **文章交互动作后端执行闭环**，避免把尚未存在的通用账本包装成已经落地的能力。

第一刀不做通用任务规划，只拿文章详情页的低风险交互动作做纵向试点：

- 点赞 / 取消点赞
- 收藏 / 取消收藏
- 关注作者 / 取消关注

典型验收句：

> 帮我给这篇文章点赞并收藏。

系统必须分别记录点赞和收藏的结果；只成功一项时，禁止回答“两项都已完成”。

## 一、当前 Agent 地图

项目当前不是一个统一 Agent，而是四种执行形态并存：

| 形态 | 当前职责 | 当前强项 | 当前缺口 |
|---|---|---|---|
| 普通聊天 / Tool Calling | 回答、导航、编辑器和文章页面命令 | 接入简单、响应快 | 页面命令的真实执行发生在前端，模型拿不到结果 |
| Agent Runtime | 学习 / 文章 / 通用 / 钱包四域 | 有界循环、动作白名单、步骤留痕、部分验证 | 只读动作能再感知；写动作确认后直接结束，不能继续剩余任务 |
| Workflow | 五类固定长流程 | 持久化、确认、重试、恢复、质量检查成熟 | 只适合预定义流程，不适合所有动态任务 |
| 受控写 | 学习任务、文章标题/可见性、钱包充值 | 后端裁判、确认门、CAS、审计完善 | 一个 run 一次只能挂一个提案，确认后直接结束 |

对应源码事实：

- `AbstractAgentRuntime` 已有 `decide -> execute -> observation -> decide` 循环。
- 只读侧 `AgentActionExecutor` 目前只返回 `String` observation；写侧已有 `WriteActionResult(message, auditPayload)` 的结构化先例，但它仍服务于单个确认提案，且 `auditPayload` 是弱类型 `Map`。
- `successfulActions` 只记录 `QUERY_ARTICLE` 一类动作枚举，不能表达“点赞成功、收藏失败”。
- `AgentWriteProposal` 有 9 个字段，但一份 proposal 仍只能表达一个 `actionType`；`WAITING_WRITE_CONFIRM` 确认完成后 run 直接 `COMPLETED`。
- `WAITING_USER` 仍以“下一句话新建 run”为主，不是真正从旧执行点恢复。
- `AiIntent.actionType`、`AgentDecision.clientActionType`、SSE `articleAction` 都是单值。
- `AiToolActionRegistry.articleActionMap` 每个 `requestId` 只保存一个命令，多个工具调用会覆盖前一个。
- 正常路径优先用 Planner 的单个 `clientActionType` 直接构造命令；Registry 只在直接命令为空时兜底。覆盖后若与 Planner 单值不一致，兜底命令还会被一致性校验拒绝，因此复合请求不只是“只做一个”，也可能一个都不执行。
- 文章页面动作在 SSE `STOP` 后才由前端执行，AI 正文生成时动作尚未发生；该路径被判为 `AgentAction.CHAT`，**不创建 run、也不占 AI 任务准入名额**——因此本刀的“复用现有 run/step”实为新建（详见 §5.4）。
- `ArticleFavoritesServiceImpl` 的收藏与取消收藏没有事务，而点赞、关注有；收藏关系行、计数字段之间可能出现单动作内部的部分提交。
- 三个领域服务都用 `IllegalArgumentException + 中文文案` 表达业务语义，当前没有可供完成判断直接消费的稳定 `resultCode`。
- `context_json` 目前同时存在“JSON 字符串”和“JSON 对象”两种根形状；`AgentWriteActionService.parseContext()` 解析失败时静默返回空 Map，`markExecuted()` 可能覆盖原有 observations。

“点赞并收藏”是姐夫报告的早期历史现象；当前版本尚未重新复测，不能直接声称当前必现。但单值链路、前端后执行和结果不可回传三个结构性缺口仍由源码确认，因此把这句话作为本刀的回归验收句是成立的。

## 二、先区分四种能力

“多步骤 Agent”至少包含四件不同的事，不能混称为 Planner：

| 能力 | 含义 | 当前状态 |
|---|---|---|
| 请求拆解 | 识别用户有几个独立子目标 | 文章分析能部分识别；业务动作仍是单值 |
| 动作编排 | 决定顺序、依赖、并行和确认点 | Workflow 有；动态复合动作没有 |
| 逐步执行 | 每步拿到真实成功/失败结果 | 只读 Runtime 有；前端页面动作没有 |
| 完成度验证 | 最终回答前核对所有子目标 | 尚无通用实现 |

V3.13 Plan Preview 解决的是“把复杂文章分析拆成用户可读子目标并观察”，它刻意不驱动执行。五轮观测后不做 Plan-guided ReAct，是基于文章分析样本作出的保守决定。

本方案解决的是**执行正确性和结果真实性**，与“不做 Plan-guided ReAct”不冲突。

## 三、问题定义

### 3.1 当前存在三类错误

#### A. 目标丢失

请求包含 A+B，分类器或命令结构只能保留一个动作，B 在进入执行层之前已经丢失。

#### B. 执行与回答时序倒置

模型先生成“已经完成”的正文，前端随后才真正发起接口。接口失败时，历史消息仍然声称成功。

#### C. 没有结果约束

即使系统执行了动作，最终文案也没有检查：

- 用户明确要求的受支持动作是否全部进入批次；
- 每项动作是否实际成功、已经满足或失败；
- 回答中的完成声明是否和真实结果一致。

### 3.2 目标

1. 一个文章交互请求可以表示 1..N 个受支持动作。
2. LLM 只能提出动作候选，后端负责白名单、对象、权限和冲突校验。
3. 每个动作返回结构化结果，不能只返回自然语言。
4. 最终回答必须以本次动作结果列表为事实来源。
5. 部分成功是合法终态，必须如实显示，不能假装原子成功。
6. 第一刀沿用现有 run / step / SSE / inspection，不另造一套聊天基础设施。

### 3.3 非目标

- 不做任意自然语言任务的通用 Planner。
- 不恢复已否决的 Plan-guided ReAct。
- 不做 Multi-Agent。
- 不引入 LangGraph 替换现有 Runtime。
- 不让 LLM 直接决定 ID、权限、事务或是否需要确认。
- 第一刀不支持跨域混合任务，例如“点赞这篇文章，再修改学习计划”。
- 第一刀不支持批量高风险写动作。
- 第一刀不自动重试写动作。
- 第一刀不做跨请求暂停/恢复、并行执行或目标级 CAS。
- 第一刀不修改现有受控写确认状态机。

## 四、三种候选方案

### 方案 A：只把单动作字段改成数组

```text
actionType -> actionTypes[]
articleAction -> articleActions[]
```

优点：改动最小，能让前端依次执行点赞和收藏。

问题：

- 模型正文仍早于前端执行；
- 后端不知道真实结果；
- 刷新后难以恢复每项状态；
- 无法阻止“部分成功却声称全部成功”。

结论：只能修“第二个动作被覆盖”，不能修完成度闭环，**不推荐**。

### 方案 B：直接做完整 Planner / Plan-guided ReAct

让模型生成任务计划、选择当前计划项、执行、重规划并维护完成状态。

优点：能力上限高，理论上能覆盖更复杂任务。

问题：

- 与现有 27 条文章分析观测结论冲突；
- 对“点赞并收藏”这种确定性任务明显过重；
- LLM 调用次数、延迟和失败面都会放大；
- 计划仍不能代替真实执行结果。

结论：不是当前缺口的最小解，**不推荐作为下一刀**。

### 方案 C：后端动作批次 + 结构化结果（推荐）

第一刀只引入本场景真正会工作的三个概念：

- `ArticleActionBatch`：后端校验后的有序动作列表；
- `ArticleActionResult`：每项动作的权威执行事实；
- `ArticleActionResultRenderer`：只依据结果生成最终文案。

优点：

- 直接解决漏执行和“先说成功、后执行失败”；
- 不预埋暂停、恢复、并行和确认状态；
- 第一刀不用新表，也不触碰脆弱的 `context_json`；
- 后续若真实出现跨请求任务，再从已验证的结果模型升级为目标账本。

代价：需要先补齐领域服务的结构化结果、单动作事务和幂等语义。

结论：**推荐，作为下一刀。** 通用 Goal Ledger 留在长期路线，不进入本次实现。

## 五、第一刀核心模型

### 5.1 ArticleActionBatch：一次请求的后端权威输入

示意结构：

```java
record ArticleActionBatch(
        Long articleId,
        Long authorId,
        List<ArticleActionType> actions
) {}
```

约束：

- `articleId / authorId` 来自后端可信页面上下文、会话锚与 DB 查询，不接受 LLM 自报 ID。
- `actions` 只允许第一刀的六个白名单动作。
- 后端去重并拒绝同对象上的相反动作，例如同时点赞和取消点赞。
- 保留用户表达顺序并串行执行；顺序只影响展示，不建立动作依赖图。
- 分类器输出只是候选，Planner 校验后才形成 batch。

### 5.2 ArticleActionResult：单项执行事实

示意结构：

```java
record ArticleActionResult(
        ArticleActionType actionType,
        Outcome outcome,
        ResultCode resultCode,
        String summary,
        Boolean before,
        Boolean after
) {}
```

第一刀只需要两级语义：

```text
outcome: SUCCESS / FAILED

resultCode:
  APPLIED
  ALREADY_SATISFIED
  TARGET_NOT_FOUND
  FORBIDDEN
  CONFLICT
  INTERNAL_ERROR
```

- `APPLIED` 与 `ALREADY_SATISFIED` 都属于 `SUCCESS`。
- `summary` 只负责展示，程序判断只能读取 `outcome / resultCode`。
- 不从 `IllegalArgumentException` 的中文文案反推 `resultCode`。
- `before / after` 能可靠取得时记录；无法确认时宁可为 `null`，不能编造。

### 5.3 批次结果就是第一刀的完成契约

第一刀不维护 `PENDING / RUNNING / WAITING_CONFIRM` 等目标状态。执行结束后只检查：

```text
validatedActions 数量 == actionResults 数量
并且每个 validatedAction 恰好有一个 result
```

然后确定性收尾：

```text
全部 SUCCESS          -> 全部完成
SUCCESS + FAILED      -> 部分完成，逐项说明
全部 FAILED           -> 未完成，逐项说明
动作与结果无法一一对应 -> 系统异常，禁止输出“已完成”
```

这已经足以解决第一刀的问题，无需六态账本。

### 5.4 持久化位置

第一刀不新增 `ai_agent_goal` 表，也不写 `ai_agent_runs.context_json`：

- batch 写入现有 `ai_agent_steps.input_json`；
- 每完成一项就把结果追加到同一步的 `output_json.results[]`，不能等整个批次结束才一次性写；
- step 的 `RUNNING / SUCCESS / FAILED` 表示“批次 runner 是否正常跑完”，单项业务成败只看 `results[]`；批次正常跑完但含业务失败项时，step 仍可为 `SUCCESS`；
- run 的 `final_answer` 保存确定性结果文案；assistant message 已完成关联后，刷新可据此恢复展示。

run 与唯一的 `ARTICLE_INTERACTION_BATCH` step 必须在同一个短事务中创建：batch 在执行前已经完整可知，没有理由先留下一个无法识别类型的空 run。这样要么二者同时存在，要么都不存在；禁止用 `max_steps = 1` 猜 run 类型，也不新增 run 类型列。

**前提修正：`ARTICLE_ACTION` 当前不创建 run。** 它被判成 `AgentAction.CHAT`（`AgentPlannerSupport.decideArticleAction`），走 `streamNormalChatFlow`，全程不碰 run 表；全仓唯一创建 run 的位置是 `AbstractAgentRuntime.createRun`。而 `ai_agent_steps.agent_run_id` 是 `NOT NULL`（无外键但必填）——所以上面四项不是“复用现有 run/step”，而是**新造一个 run**。造出来必须一并定义清楚：

- **状态流转**：批次正常跑完即 `COMPLETED`，无论 `results[]` 里是否含失败项。与 step 的语义保持一致——run 状态表示“流程是否走完”，业务成败只看结果列表；
- **启动恢复不解释业务结果，但要收敛运行态**：进程崩溃时停在 `RUNNING` 的 run，会被 `AiRunStartupRecoveryRunner` 标成 `FAILED` 并写入 `error_message`，但它不能读取或重算 `results[]`。现有 runner 只更新 run、不更新 step；本刀新增 batch step 后，启动恢复必须把该 run 下仍为 `RUNNING` 的 `ARTICLE_INTERACTION_BATCH` step 同步标成 `FAILED`，同时原样保留 `output_json.results[]`；已经是 `SUCCESS / FAILED` 的 step 不得覆盖。inspection / 结果展示也不能用 run 或 step 状态推断单项动作成败，因为失败的批次流程仍可能包含已成功提交的动作；
- **计步字段由当次收尾与启动恢复共同维护**：单批次固定 `max_steps = 1`；run 与 batch step 创建时 `current_step = 0 / used_steps = 0`，正常批次结束（含业务部分失败）由 batch runner 更新为 `1 / 1`；可捕获的 runner 异常由当次失败收尾写为 `1 / 1`。只有进程崩溃、来不及进入失败收尾而遗留 `RUNNING` 时，才由启动恢复根据必然存在的 `ARTICLE_INTERACTION_BATCH` step 把计步补为 `1 / 1`；
- **run 的 `goal`** 直接取聊天入口收到的原始 `message`（按现有列宽截断），与现有 Agent run 同口径；分类器只产出意图和动作候选，不负责保存“用户原话”；
- **不继承旧确认卡取消副作用**：不能直接调用 `AbstractAgentRuntime.createRun()` 或照搬其 `cancelStalePendingSuggestions()`。当前 `ARTICLE_ACTION` 不会取消同 session 的 `WAITING_WORKFLOW_CONFIRM / WAITING_WRITE_CONFIRM`，一次点赞不应让旧确认卡失效；
- **消息关联沿用现成链路，并明确窄恢复承诺**：`AiMessages.agentRunId`、`AiMessageVO.agentRunId` 和前端按 run 补拉 inspection 的链路都已存在。正常收尾时 assistant message 必须写入 `agentRunId`，`STOP` 返回的 `assistantMessage` 也必须带该关联；实现可复用现有 Agent 路径“先保存消息、再回写 `agentRunId`”的做法，也可增加窄重载，不在设计阶段写死方法签名。第一刀不提前创建占位 assistant message，也不新增消息状态：若进程在 assistant message 落库前崩溃，动作的 DB 结果仍然有效，但用户不能从消息历史恢复本次 run；文档和前端只承诺**正常落消息后**可刷新恢复。
- **补齐 schema 基线**：实体、VO 与测试 `schema.sql` 已有 `ai_messages.agent_run_id`，现有 Agent 路径也会写入，但 `db/init.sql` 的建表与后续 ALTER 都缺少该列和索引。第一刀依赖此关联，实施时必须补齐初始化基线，并在部署前核对存量库实际列 / 索引，不能用“当前某个环境能跑”代替 schema 证据。

新增的 step 状态恢复与 run 计步补写必须通过 `action_type = 'ARTICLE_INTERACTION_BATCH'` 限定目标 run / step；禁止扩大成“本次恢复的 run 下所有 `RUNNING` step”。原有“所有 `RUNNING` run 标 `FAILED`”的恢复逻辑保持不变，四个现有 Agent 域的历史 step 恢复语义不在本刀修改范围；若要统一治理必须另开设计与回归测试。

建议第一刀用一个 `ARTICLE_INTERACTION_BATCH` step 记录整个批次，不进入 1~3 个固定动作的循环；`maxSteps = 1` 只是 run 数据约束，不代表引入 ReAct。若 inspection 需要逐项时间线，前端从 `results[]` 展开即可。

`context_json` 的根形状不统一和静默覆盖问题登记为阶段 2 前置治理；本刀既不消费也不扩大这笔债。

## 六、第一刀：文章低风险交互闭环

### 6.1 范围

纳入后端直接执行：

- `LIKE_ARTICLE / UNLIKE_ARTICLE`
- `FAVORITE_ARTICLE / UNFAVORITE_ARTICLE`
- `FOLLOW_AUTHOR / UNFOLLOW_AUTHOR`

仍留在客户端命令：

- `SCROLL_TO_COMMENTS / SCROLL_TO_TOP`
- `COPY_ARTICLE_LINK`
- 页面导航

继续走受控写或现有 Workflow：

- 评论内容提交
- 修改标题
- 隐藏 / 公开
- 发布文章
- 修改学习计划
- 钱包充值

理由：点赞、收藏、关注是用户明确发出的低风险、可逆命令，当前系统本来就会自动执行，只是执行放在前端。迁到后端没有扩大既有授权；高影响动作仍保留确认门。

### 6.2 入口识别

分类器为 `ARTICLE_ACTION` 增加多动作候选，而不是继续只返回单个 `actionType`。示意：

```json
{
  "intent": "ARTICLE_ACTION",
  "requestedActions": ["likeArticle", "favoriteArticle"]
}
```

但分类器输出不是最终 batch。Planner 必须逐项执行：

1. 校验动作白名单；
2. 校验当前页面 / 会话目标能否唯一定位文章；
3. 校验登录要求；
4. 拒绝互相冲突的同对象动作，例如同时点赞和取消点赞；
5. 去重同动作；
6. 生成后端权威 `ArticleActionBatch`。

分类器不稳定是项目已有观测结论，因此它不能独占正确性。第一刀要增加一个高精度完整性兜底：当用户原话明显含多个受支持动作，而分类器只返回一个时，不允许静默执行一个并声称完成；降级为 CTA 或重新分类一次。

该兜底只覆盖有限的明确动作词，不承担通用语义拆解。真正的泛化能力由固定在线评测集验证。

纯服务端动作进入本刀；纯客户端动作继续走原路径。第一刀不混编“点赞并复制链接”这种跨执行端批次，遇到时明确拆分或 CTA，不能悄悄丢一项。

第一刀同样不编排“文章动作 + 分析诉求”这种跨路由混合句，例如“帮我点赞，再看看这篇文章写得怎么样”。在分类之后、真正路由和执行之前增加高精度边界守卫：同时命中明确动作词与明确分析诉求时，返回 CTA 提示用户分开发送，**本次一个动作也不执行**。不能判成 `ARTICLE_ACTION` 后丢掉分析，也不能判成 `ARTICLE_AGENT` 后丢掉点赞；自动拆成“先执行、再分析”属于后续多意图编排，不进第一刀。

### 6.3 执行归属

服务端业务动作不再通过 `AiToolActionRegistry -> STOP -> 前端 emit -> HTTP` 执行，而是在后端调用现有领域服务。

```text
validated batch
  -> ArticleInteractionExecutor
  -> ArticleLikesService / ArticleFavoritesService / FollowService
  -> DB 权威提交
  -> ArticleActionResult[]
  -> 确定性结果文案
```

现有三个领域服务仍从 `UserContext` 读取当前用户。若确定性 Runtime 运行在 AI 专用线程池，必须沿用现有 Agent worker 的做法：任务开始前绑定 `userId`，`finally` 中清理；不能假设请求线程的 ThreadLocal 会自动传播。

每个动作独立提交，第一刀不做整批事务。`BatchRunner` 自身不能标整批 `@Transactional`；每轮通过独立事务调用领域命令：

- 点赞成功、收藏失败时保留点赞结果；
- 最终状态是“部分成功”，不是整批回滚；
- 这更符合用户可观察到的交互语义，也能真实暴露失败项。

同一项动作的 DB 变更与该项 `results[]` 留痕应加入同一个小事务：业务提交成功但结果尚未落 step 的进程崩溃窗口，不能靠最终模板补救。系统异常导致该项事务回滚后，再用独立事务记录 `FAILED` 结果。整个过程仍是“逐项原子、批次非原子”。

异常后可以在新事务中读取权威状态：如果目标状态实际上已经达成，可记 `ALREADY_SATISFIED`；否则记失败。但“异常后查询”只是未知提交结果的兜底，不能替代领域服务本身的结构化业务语义。

### 6.4 领域服务前置治理

这一项是第一刀的真实成本，不能靠 Agent 层包装掉：

1. 点赞、收藏、关注的命令入口返回结构化领域结果；已有 HTTP Controller 可用薄适配层维持原接口文案，但领域层不再让调用方解析中文异常。
2. 单个动作内部的关系记录与冗余计数必须处于同一 DB 事务。尤其先给收藏 / 取消收藏补事务。
3. 并发幂等要**分两种表型**，不能一律指望唯一索引：
   - **点赞 / 收藏（硬删除）**：`article_likes(article_id,user_id)`、`article_favorites(article_id,user_id)` 的唯一索引可作最终防线——并发重复插入撞唯一键，整个动作事务回滚，计数不会多加；
   - **关注（软删除）**：`user_follows` 有 `deleted_at`，恢复关注走的是 **UPDATE 同一行**，**不违反唯一索引**，所以它兜不住。而现在的实现恰好是“先查 `deleted_at != null`，再无条件 UPDATE 恢复”（`FollowServiceImpl.followUser`），两个并发的“取关后重新关注”会**各加一次计数**（首次关注那条路仍由唯一索引兜住）。恢复分支必须改成条件更新：`WHERE follower_id=? AND following_id=? AND deleted_at IS NOT NULL`，以受影响行数判定是否真的恢复了。
4. “确保达到某状态”按幂等命令设计：已点赞再点赞、已取消再取消返回 `ALREADY_SATISFIED`，不重复增减计数。
5. 禁止“先查状态，然后无条件写”的 TOCTOU 实现；使用事务内条件更新、唯一约束或受影响行数判定。
6. DB 是业务真相。用户集合缓存与详情缓存继续提交后维护；当前热榜 `incrementScore` 也应移到提交后并 fail-open，由定时重建收敛，避免 DB 回滚但 Redis 分数已变化。
7. 目标不存在、不能关注自己、未登录等分支返回稳定枚举；只有系统异常才走 `INTERNAL_ERROR`。注意 `favoriteArticle` 目前**没有 `userId == null` 检查**（`likeArticle` 有），它是靠前端守卫兜住的；迁移后这条路径由后端发起，“未登录”必须自己挡住，否则会带着 null userId 一路走到写入。
8. 事务必须经过 Spring 代理边界；不要在同一 Bean 自调用一个带 `@Transactional` 的内部方法，并误以为每项事务已经生效。

第一刀不要求把全项目所有 `IllegalArgumentException` 一次性重构，只收口这六个动作真正经过的领域入口。

### 6.5 执行编排归属

推荐新增一个窄的 `ArticleInteractionBatchRunner`，在 Planner 产出 batch 后、进入普通聊天 LLM 之前执行；复用现有 run / step / SSE / inspection 数据形态，但不进入 `AbstractAgentRuntime` 的 LLM ReAct 循环：

```text
ARTICLE_ACTION + validated batch
  -> ArticleInteractionBatchRunner
  -> 串行执行每项动作
  -> 一个 ARTICLE_INTERACTION_BATCH step 落 input/results
  -> 确定性结果文案
```

钱包域证明了“明确业务动作不需要 LLM 决策”的原则，但 `WalletAgentStepDecider` 是单步终态模型，不能直接证明它适合多动作批次。现有 `AgentRuntime.run(...)` 也拿不到分类器的动作数组；为了套骨架而让它重新解析用户原话，或改动四个 Runtime 的公共签名，都不划算。

因此第一刀复用基础设施，不强行复用 `AgentStepDecider`。实施时若能以很小改动把 batch 作为已校验输入传入现有公共 SSE 生命周期，可以抽一个窄请求对象；不能为这一个场景先造新的通用 Runtime 基类。

**准入：这条路径不占名额，且是有意的。** `AiTaskType` 没有 CHAT（其 Javadoc 写明普通聊天走响应式模型流，不在这里），`streamNormalChatFlow` 一次 admission 都不调。批次只有几次 DB 写，不是长任务，按现有准入的定位（同步阻塞的 Agent / Workflow 编排）本就不该纳入。

需要写明的只是**行为变化**：当前前端只限制**同一会话**发送中再次发送，跨会话本来就可以并发；迁移后执行权进入后端，同一用户仍可从不同会话并发触发多个批次。既有兜底是 chat 限流 + 动作幂等，当前范围够用；但这是**判断结果，不是遗漏**，应当显式记下来。

### 6.6 结果收尾

终态前必须确定性检查动作与结果是否一一对应：

```text
全部 SUCCESS
  -> 全部完成

SUCCESS + FAILED
  -> 部分完成，逐项说明

全部 FAILED
  -> 未完成，逐项说明原因

结果缺项 / 重复
  -> 系统异常，禁止输出完成文案
```

第一刀最终文案不再交给 LLM 自由生成，由结果渲染器按 `actionResults` 生成。例如：

```text
已点赞并收藏这篇文章。

已点赞；收藏失败，请稍后重试。

这篇文章已经点过赞；已为你收藏。
```

禁止出现结果列表之外的完成声明。这里的”守卫”只是一个列表完整性校验与模板分支，不包装成通用 Completion Guard 组件。

批次含失败项时打一条可检索日志（照 `[CACHE-EVICT-FAIL]` 的风格）。最小口径固定为 `[ARTICLE-ACTION-PARTIAL] runId={} userId={} articleId={} actions={} resultCodes={}`，不记录用户原文、文章正文或异常堆栈中的敏感内容；系统异常另保留异常日志。部分成功是**用户看得见、开发者看不见**的状态——用户看到”收藏失败”顶多重试一次，没人会去反馈；没有这条日志，领域层的失败率就永远是盲区，而它恰恰是本刀最该观测的指标。

### 6.7 前端职责与迁移

后端返回 `actionResults`，前端只负责：

- 展示部分成功 / 失败状态；
- 根据结果刷新点赞、收藏、关注状态和计数；
- assistant message 已绑定 `agentRunId` 时，从 run / step 恢复执行记录。

前端不再承担这些服务端动作的真正执行，也不能把“收到命令”当作“执行成功”。

迁移必须一次切换：后端开始执行的同一版本里，前端必须停止收到 `articleAction` 后再次调用点赞 / 收藏 / 关注接口，否则会出现双执行。保留一个回归断言，确保服务端动作结果事件只触发 UI 刷新。

行为变化必须接受并写入验收：请求已经到达后端后，即使用户关掉文章页面，动作仍可能完成；这正是执行权从浏览器迁到服务端的结果，不应再绑定组件是否存活。

### 6.8 实施拆分

这里的“两刀”是阶段 1 内部的两个实施批次，不改变后文四阶段能力路线。实现分两刀合入，第二刀以第一刀的领域测试通过为前置：

1. **领域底座刀**：点赞 / 收藏 / 关注六个命令入口补结构化领域结果、单动作事务、条件更新 / 唯一约束幂等和 Redis 提交后语义；Controller 用薄适配保持现有 HTTP 返回与中文文案不变。该刀不改分类器、Agent、SSE 或前端。
2. **Agent 迁移刀**：多动作候选、边界守卫、batch runner、run / step 持久化与恢复、`agent_run_id` schema 基线、停用六个旧副作用工具、确定性结果文案和前端只刷新。

这样先锁住博客核心交互的兼容性，再切换执行归属；不能把领域回归与 Agent 路由问题混在同一批故障里排查。

纯客户端动作仍由前端执行，但用户文案必须使用“已发起 / 已跳转 / 已复制”等与实际可观测结果匹配的表述；后续若需要确认其结果，应单独设计客户端回执，不混入本刀。

## 七、与现有模块的关系

### 7.1 Plan Preview

Plan Preview 继续是文章分析的自然语言弱参考。第一刀的 `ArticleActionBatch` 是后端校验后的即时命令，两者不共用 `plan_json`，也不互相驱动。

### 7.2 Evidence Verifier

Evidence Verifier 回答：**回答是否被证据支撑**。

结果完整性校验回答：**本次受支持动作是否都已有真实结果**。

两者职责不同：

- 未来分析型 Agent 若引入通用完成契约，可同时使用完成度检查与证据检查；
- 确定性动作结果不需要 LLM Evidence Verifier。

### 7.3 受控写

第一刀不修改现有 `AgentWriteActionService` 状态机。

后续若要实现“低风险动作 + 高风险确认动作”的同一轮编排，才需要把一个确认提案绑定到更高层任务项，并在 confirm 后继续剩余任务。那一步不能直接沿用当前模型，必须先处理两笔已确认的架构债：

- `AgentWriteProposal` 已有 9 个跨域字段、多个 switch 分支；继续加任务绑定前先评估拆成域提案/注册表，避免让大 record 再膨胀。它是重构触发点，不预设必须一次性重构全域。
- `context_json` 根形状不统一且解析失败 fail-silent；若阶段 2 继续依赖它，必须先统一根对象契约、收口写入口，并增加 Runtime -> reader 往返测试。

### 7.4 Workflow

固定、长流程继续走 Workflow。第一刀的文章动作批次不取代 Workflow 状态机，也不尝试编排 Workflow。

未来可能出现：

```text
任务 A：点赞文章 -> 直接完成
任务 B：优化文章 -> WAITING_WORKFLOW_CONFIRM / Workflow handoff
```

但这是后续跨执行形态编排，不进入第一刀。

### 7.5 Tool Calling

工具返回”已准备点赞”不再等价于业务成功。第一刀切换后，点赞 / 收藏 / 关注不再依赖 Registry 临时命令；仅生成前端命令的工具继续表示 `DISPATCHED`，不能标记为业务成功。

**实施清单必须包含停用那 6 个服务端副作用工具**（`AiArticleActionTools` 的 like / unlike / favorite / unfavorite / follow / unfollow）。这里不是删除整个 `AiArticleActionTools`：应拆出或取消注册这 6 个方法，继续保留 `commentArticle`、`scrollToComments`、`copyArticleLink` 等现有客户端命令能力。只改执行端却留着 6 个旧工具，一旦分类器漏判、请求落进普通聊天 LLM，模型仍会调用它们并拿到”已准备点赞当前文章”的返回值，于是正文承诺了一个后端根本不会执行的动作——恰好是本刀要消灭的那类假话。

## 八、错误与一致性语义

| 场景 | 结构化结果 | 最终回答 |
|---|---|---|
| 已点赞，再要求点赞 | `SUCCESS / ALREADY_SATISFIED` | 已经点过赞 |
| 点赞成功、收藏失败 | 一成功一失败 | 明确部分完成 |
| 文章不存在 | 对应动作 `FAILED / TARGET_NOT_FOUND` | 未完成，不猜目标 |
| 未登录 | 执行前 CTA，不创建 run | 提示登录 |
| 非法动作 | Planner 拒绝 | 不进入 batch runner |
| 同时点赞和取消点赞 | 冲突，CTA | 请用户明确目标状态 |
| SSE 中断但后端已执行，且 assistant message 随后正常落库 | 结果持久化在 run/step，消息绑定 `agentRunId` | 刷新后可恢复真实结果 |
| 执行异常但 DB 已达目标状态 | 查询权威状态后判成功 | 按真实 after 状态回答 |
| 结果条数与动作数不一致 | 批次完整性失败 | 禁止“全部完成” |

第一刀的批处理语义是 **best effort + 逐项事实记录**，不是跨动作原子事务。

## 九、测试设计

### 9.1 动作提取与 Planner

- “点赞这篇文章” -> 1 个动作。
- “点赞并收藏这篇文章” -> 2 个动作，顺序稳定。
- “收藏、收藏这篇文章” -> 去重为 1 个动作。
- “点赞并取消点赞” -> 冲突 CTA，不执行。
- 非文章页、无唯一文章目标 -> CTA。
- 分类器漏掉明显的第二动作 -> 完整性兜底拦截，不能只执行一个。
- 未知 action -> Planner 拒绝。
- “点赞并复制链接” -> 不静默丢项；按第一刀约定拆分或 CTA。
- “帮我点赞，再看看这篇文章写得怎么样” -> CTA，本次零执行；不能丢掉任一诉求后继续。
- “分析这篇文章里的点赞机制” -> 不因出现“点赞”二字误触发动作守卫。

### 9.2 领域动作与幂等

- 收藏关系写入后计数更新失败 -> 同一 DB 事务回滚。
- 两个并发点赞 -> 唯一索引兜底，关系与计数只增加一次。
- 测试库 schema 同步补齐点赞 / 收藏唯一约束，避免并发测试在 H2 中验证了错误的数据模型。
- 已点赞再点赞、未收藏再取消收藏 -> `ALREADY_SATISFIED`，计数不漂移。
- Redis 热榜 / 缓存故障 -> DB 结果仍可确定，辅助数据 fail-open 并可收敛。
- 目标不存在、自关注等分支 -> 稳定 resultCode，不解析异常文案。

### 9.3 批次执行与持久化

- 两项都成功 -> batch step 的 `results[]` 两项 SUCCESS。
- 第一项成功、第二项失败 -> 保留第一项，第二项 FAILED。
- 已点赞 + 收藏成功 -> `ALREADY_SATISFIED + APPLIED`。
- 服务异常后权威状态已达成 -> 成功，不重复写。
- 重复订阅 / 重复请求不能造成重复计数或反向切换。
- SSE 中断但后端正常收尾、消息成功落库后，inspection 能看到 batch 与真实结果。
- 启动恢复把遗留 `RUNNING` run 与其 `ARTICLE_INTERACTION_BATCH` step 标为 `FAILED` 后，已落库的 `results[]` 原样保留，inspection 不得把其中成功项显示成失败。
- run 与 batch step 创建事务失败 -> 两者都不存在；不允许出现只有 run、没有 batch step 的半成品。
- 启动恢复遇到 batch step 对应的中断 run -> `current_step / used_steps = 1 / 1`；已为 `SUCCESS / FAILED` 的 step 不被覆盖。
- 启动恢复遇到其他 action type 的 `RUNNING` step -> 不更新该 step，锁住四个现有 Agent 域的既有语义。
- 在本地 MySQL 8 新建专用临时库，完整执行 `db/init.sql` 后，通过 `information_schema.columns / statistics` 核对 `ai_messages.agent_run_id` 及其索引，再实写一条带 run 关联的 assistant message；记录结果后删除临时库。当前没有 Testcontainers / Docker 基础设施，本刀不为这一项引入它们。
- `context_json` 保持本刀之前的行为，不新增 batch 片段。

### 9.4 最终回答约束

- 全成功才允许“都已完成”。
- 任一失败时必须出现失败项，不得全成功措辞。
- 结果缺项或重复时拒绝成功收尾。
- 结果文案不得包含 batch 中不存在的动作。

### 9.5 前端

- 收到结果后点赞/收藏/关注状态正确刷新。
- 部分失败不吞提示。
- assistant message 正常落库后，刷新所见步骤与结果一致。
- 纯客户端动作仍正常工作。
- 不再二次执行已由后端完成的文章交互。
- 请求后关闭页面，重新进入时能看到 DB 权威状态。

### 9.6 真实 LLM 评测样例

新增一组复合目标边界样例，至少覆盖：

- 并列词：并、和、同时、顺便。
- 顺序词：先点赞，再收藏。
- 已满足状态表达：如果还没点赞就点赞，然后收藏。
- 单动作对照组，防所有文章动作都被误判成复合任务。
- 内容分析多目标对照组，防 `ARTICLE_AGENT` 分界回归。
- 动作 + 分析混合句进入 CTA 且零执行；包含动作词但实际讨论该概念的句子不能误拦截。

改分类器 prompt 后继续遵守现有在线评测门，不用单次模型输出宣称稳定。

## 十、分阶段路线

### 阶段 1：低风险文章交互闭环

- 多动作候选字段；
- Planner 白名单、定位、去重与冲突校验；
- 领域动作结构化返回、收藏事务补齐、幂等与 Redis 提交后语义；
- 服务端执行点赞 / 收藏 / 关注；
- `ArticleActionBatch + ArticleActionResult[]`；
- batch / results 写入现有 run / step，不写 `context_json`；
- assistant message 关联 `agentRunId`，补齐 `db/init.sql` 的 `agent_run_id` 列与索引基线；
- 确定性最终文案；
- SSE / 前端只消费结果并刷新，不二次写；
- 固定评测样例。

这是下一刀的推荐范围。

### 阶段 2：先治理状态载体，再讨论确认后继续执行

- 统一 `context_json` 根对象契约、写入口与 fail-loud 解析，补 Runtime -> reader 往返测试；
- 评估 `AgentWriteProposal` 是否已达到域提案 / 执行器注册表的重构触发条件；
- 只有真实用例要求“一轮内低风险执行 + 高风险确认”时，再设计任务项、确认后续跑和取消语义；
- 此时再决定是否需要独立目标表，不从第一刀预埋六态模型。

`context_json` 治理是该阶段若继续使用此列的硬前置；`AgentWriteProposal` 域拆分是实施前评估点，是否重构取决于新增字段和 switch 是否继续跨域膨胀。

### 阶段 3：真正的暂停 / 恢复

- `WAITING_USER` 恢复旧 run，而不是只合并原诉求后新建 run；
- 恢复任务项、observations、目标对象和剩余预算；
- 防旧回答串到新目标；
- 定义恢复过期和用户换话题语义。

### 阶段 4：扩到动态 ReAct

- 只读 executor 从 `String` 逐步升级为结构化 `Action Result`；
- `FINAL_ANSWER` 前同时运行真正的完成度守卫和领域 Verifier；
- `successfulActions` 从动作类型列表升级为带参数、目标和结果的事实列表；
- 仅在真实样本证明需要时再讨论重规划 / Reflection。

## 十一、暂不建议做的事

1. **不先做完整 Planner。** 当前缺的是完成契约，不是计划展示。
2. **不把所有操作塞进一个大事务。** 部分成功是真实业务状态，应能表达。
3. **不让前端回传一句“成功”就当权威。** 服务端动作以服务端状态为准。
4. **不把自然语言 observation 当结果模型。** 展示文案不能承担程序判断。
5. **不在第一刀建 Goal Ledger / 目标表 / 通用 DAG。** 这里只有同请求内的独立串行动作。
6. **不顺手批量开放高风险写动作。** 确认、CAS、权限边界继续保留。
7. **不把姐夫报告的旧现象伪装成当前已复现。** 把它加入当前回归集重新实测，同时承认源码缺口客观存在。
8. **不解析中文异常文案得到业务 code。** 领域层必须给稳定的结构化语义。
9. **不顺手补 `ai_workflow_step_logs.log_type`。** 它是同类 schema 漂移，但项目已明确登记为暂缓且本刀不依赖；若要推翻“等真报错再修”的决定，单独评估，不能借修改 `db/init.sql` 偷带范围。

## 十二、第五轮评审结论与剩余验证

### 已确定

- 低风险文章交互迁到后端执行；前端只刷新 UI。
- 第一刀不建账本、不建表、不写 `context_json`。
- 分类器输出数组不是权威，必须经过后端校验和漏项兜底。
- 最终文案不调用 LLM，部分成功不回滚。
- 领域服务先补结构化结果、单动作事务和幂等。
- 迁移版本必须防止后端和前端双执行。

### 实施第一步验证

1. 用当前真实链路复测“点赞并收藏”，记录是只执行一项还是零项；该结果只影响缺陷描述，不影响结构修复方向。
2. 做最小数据流 spike：Planner 的已校验 batch 如何进入 batch runner，同时最大程度复用现有消息保存、run / step 与 SSE 收尾。注意这条路径现在**不创建 run**，§5.4 列出的 run 生命周期边界要在 spike 里一并落地，尤其要验证正常收尾时 assistant message 的 `agentRunId` 关联；消息落库前的进程崩溃已明确不在第一刀恢复承诺内。长任务准入已明确不复用，无需在 spike 中重新讨论。
3. 核对生产库三张关系表的唯一索引与 `db/init.sql` 一致，不能只相信初始化脚本。
4. 先写领域动作并发 / 回滚测试，再决定具体使用条件 SQL、唯一键异常回读还是二者组合。
5. 核对存量库 `ai_messages.agent_run_id` 及索引，并补齐 `db/init.sql`；按 §9.3 用本地 MySQL 8 专用临时库执行完整初始化 smoke，防止测试 schema 掩盖生产基线漂移。
6. 启动恢复实现与测试必须用 `ARTICLE_INTERACTION_BATCH` 类型限定；不得顺手修改四个现有 Agent 域的 step 状态。

### 仍待 spike 决定的实现点

不直接照搬 `WalletAgentStepDecider`。钱包证明的是“确定性动作无需 LLM”，但它只有一个终态提案；本刀是 1..N 个动作与逐项结果。当前 `AgentRuntime.run(...)` 又拿不到分类器的动作数组，因此设计稿先选窄 batch runner。

如果 spike 证明给现有 Runtime 增加一个窄的已校验请求对象，能避免复制生命周期代码且不污染其余四域，可以改走现有 Runtime 接口；在证据出现前，不为形式复用先改公共签名。

## 十三、验收标准

第一刀只有同时满足以下条件才算完成：

1. “点赞并收藏”在当前版本真实执行两项，而不是只让模型说两项。
2. 每项动作都有结构化、持久化的真实结果。
3. 部分失败时最终消息不声称全部完成。
4. 已处于目标状态被正确识别，不重复增加计数；并发重复请求由唯一索引（点赞 / 收藏）或条件更新（关注，软删除表）分别兜底。
5. 前端不再重复执行后端已经执行的动作。
6. 正常收尾后的 assistant message 已绑定 `agentRunId`，刷新后能从消息进入 run / step 查看 batch 及两项结果；消息落库前的进程崩溃不支持从历史消息恢复，文档与前端不得作出相反承诺。
7. 手动点赞 / 取消点赞、收藏 / 取消收藏、关注 / 取消关注的 HTTP 接口行为与既有中文文案不变；单动作 Agent、文章分析、Workflow、现有受控写路径无回归。
8. 在线分类器评测覆盖复合动作与相邻边界。
9. 收藏关系与计数在故障下保证一起提交或一起回滚；Redis 故障不改变 DB 权威结果。
10. `db/init.sql` 与测试 schema 都包含 `ai_messages.agent_run_id` 及索引，干净库初始化后 Agent / batch 消息关联可写可读。
11. “文章动作 + 分析诉求”混合句返回 CTA 且零执行，不静默丢掉任一目标。
12. 启动恢复只收敛 `ARTICLE_INTERACTION_BATCH` step；其他 Agent action type 的 step 状态不因本刀改变。

## 十四、面试口径

这一步的价值不在“支持一次点赞收藏”，而在于能讲清四层职责：

```text
LLM：识别用户可能想执行哪些动作
Planner：校验动作是否合法、可定位、可授权
Executor：执行并返回结构化事实
Result Renderer：根据完整结果列表决定能否宣称完成
```

核心取舍：

- 固定、明确、低风险的多动作不用完整 Planner，确定性调度更可靠；
- 高风险写动作继续确认；
- 部分成功不伪装原子成功；
- 第一刀的完成契约是已校验 batch 与逐项结果，不是提前设计的通用账本；
- 最终回答不能比执行事实更乐观。

**讲的时候先讲单动作。** 本刀真正高频的收益不是“点赞并收藏”，而是 B 类错误的主场：用户说“帮我点赞”，模型答“已经点赞了”，而前端此时才发起请求、失败了也没人纠正——这个场景几乎人人遇到过，比复合动作有说服力得多；复合动作作为顺带覆盖。

**另一条更值钱的讲法**：这次改造逼出了领域层躺了很久的三个正确性缺陷——收藏缺事务、关注恢复分支的 TOCTOU、热榜分数在事务内更新。所以落点不是“我做了个 Agent”，而是“**把执行权从前端收回后端之后，领域层原来靠前端守卫和低并发掩盖的问题暴露了出来**”。
