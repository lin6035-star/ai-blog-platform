# 会话学习计划锚：多轮对话不再断片

> 创建：2026-09-13
> 定位：给学习域补上 V3.8 文章锚的对称能力——让"上一轮聊的计划"在多轮对话里被记住。
> 前序：`agent-v3-8-session-article-anchor-design.md`（文章锚）、`agent-live-status-and-query-regex-design.md`（同一次实测暴露的另一半）

## 一、问题（实测）

```
用户：你帮我分析一下我的c++学习计划，看看这份计划按安排的有没有什么问题
AI：  （详细分析：8 个计划并行过载、RAII 起步精准、CMake 摩擦成本高……它确实读到了内容）

用户：我是要应对学校课程，先说说你的计划，你会怎么改
AI：  你是想调整学习计划吗？告诉我是哪个计划、想怎么改，我来帮你处理。
用户：我不是说了吗
```

**落差最伤**：上一轮显得很懂，下一轮像失忆。

**根因（代码事实）**：

```java
AiIntent classify(String message, PageContextDTO pageContext, Long userId)
```

分类器**只接收当前这一条消息，没有对话历史**。第二句里没有"c++"，`learningPlanRef` 为空 → 后端拿不到 planId → 只能反问。

这是当初的设计决定（稳定 + 省 token），但在多轮对话里是硬缺陷——**人说话本来就省略主语**。

## 二、方案：会话学习计划锚

与 V3.8 文章锚**同构**（分类器不读锚，锚由后端消费）：

| 点 | 做法 |
|---|---|
| **存哪** | `ai_sessions` 加 4 列：`last_learning_plan_id` / `_title`（快照）/ `_source` / `_updated_at` |
| **写点** | 任一轮**成功定位到计划**时写（分类器选中 / 后端匹配唯一命中）。**纯查询不写**——它没有指向某一个 |
| **读点** | 判出"调整计划"但原话没点名 → 取锚兜底 |
| **兜底后** | **必须报出来**（"按你刚才提到的《X》来处理…"） |

**接入位置**（`routeLearningProgressWorkflow` / `routeLearningAssistWorkflow` 的兜底链）：

```
1. 分类器选中（最可靠，模型从真实列表里选）
2. 原话匹配唯一命中
3. 会话锚                    ← 新增
4. 只剩一个 ACTIVE 计划（隐含推断）
5. 多计划 → 弹卡让用户选
```

原则：**「用户这次说了什么」永远优先于「上次聊的是哪个」**，所以锚排在 2 之后、4 之前（锚比"只剩一个计划"的隐含推断更可靠）。

**为什么必须报出来**：锚是"上一轮聊过"的推断，不是用户这一句说的——沉默地改错计划是最坏结果，报出来用户才能当场纠正。

## 三、配套：Planner 风险放行

实测暴露的第二道坎：第二句的 `risk=MEDIUM` 会被 Planner 直接降级 CTA（`isLowRisk` 要求 LOW 才放行）。

**分类器判 MEDIUM 的典型理由正是「计划对象不清楚」**（它的 risk 定义里就有这条）——而锚恰好解决了这件事：

```java
if (!isLowRisk(intent) && !hasPlanAnchorFor(session, workflowType)) { ...CTA... }
```

**只对"操作已有计划"的意图生效**：

| 意图 | 锚有意义吗 |
|---|---|
| `LEARNING_PROGRESS` / `LEARNING_ASSIST` | ✓ 需要定位目标 |
| `LEARNING_PLAN`（新建） | ✗ 不涉及"改哪个"，仍按 MEDIUM 降级 |

**为什么安全**：放行 ≠ 改数据。工作流本身有确认门，最高代价是多弹一张可确认的卡；锚失效（计划已删/非本人）时下游 `resolve` 返回 null，仍走原有追问。

## 四、验证

| 项 | 结果 |
|---|---|
| `LearningPlanAnchorServiceTests`（新增 8 例：写入/容忍计划已删/会话缺失不写/归属校验/失效返回 null） | 8/8 绿 |
| `AgentPlannerSupportTests`（+3 例：有锚放行 / 无锚仍降级 / 新建计划不因锚放行） | 50/50 绿 |
| `LearningProgressWorkflowServiceTests` + `LearningAssistWorkflowServiceTests` | 25/25 绿 |
| 离线评测门（改 Planner 规则，按门槛必跑） | 26 例绿 |
| Spring 上下文装配（新增依赖注入） | 4/4 绿 |

## 五、边界

- **不做锚泛化**：之前讨论过的 `(scope, target_id)` 方案会牵动文章域稳定链路（实体 / 测试 schema / 读写服务），本次**新增 4 列**风险小得多
- **锚不校验计划状态**：COMPLETED 的计划也能被继续调整——"完成"不等于"不能再改"
- **前端渲染已统一**：所有过程状态（文章确认范围 / 文章检索 / 学习查询 / 分类降级 / 锚命中）共用同一个 `.ai-chat-status` 渲染，改一处全都生效，不需要逐个适配
- **已补最小版**：真正"上一轮说了什么"的继承（"就按刚刚那个想法改"——连要求都在上一轮）已在学习进度 Workflow 先行落地：只在承接上轮建议的语句里，从同会话最近一次已完成学习 Agent 回答构造弱参考 handoff；其他域仍按 V4③ 后续铺开。

## 六、实测修正（同日）

首轮实现上线后老大实测，第二句仍然弹候选卡。日志还原出**两个叠加原因**：

```
15:09:17  「帮我分析我的c++学习计划…」 → sessionId=2099032630059171842
          intent=LEARNING_AGENT → action=AGENT（走 Agent Runtime）
15:14:19  「那就按照你说的建议…」      → sessionId=2099033668895997954   ← 不是同一个会话
          intent=LEARNING_PROGRESS, risk=LOW → 放行 → 定位失败 → 弹候选卡
```

### 修正 1：写点位置错了（真 bug）

锚原本写在两个 route 方法里（`routeLearningProgressWorkflow` / `routeLearningAssistWorkflow`），
而**第一句走的是 Agent Runtime**，根本不经过那里 → 锚压根没写。

**处置**：写点**收口到 `streamUserChat` 的总入口**（分发之前），一处覆盖工作流 / Agent / 聊天所有出口。
判据：意图是「操作已有计划」（`LEARNING_PROGRESS` / `LEARNING_ASSIST` / `LEARNING_AGENT`）
**且**用户原话点了计划名（`learningPlanRef` 非空）——新建计划语境里的计划名不算（那多半是"要创建的那个"）。
定位优先用分类器的权威 ID，否则按原话匹配，**不唯一就不写**。

同时删掉 route 方法里的 4 处重复写点。

### 修正 2：用户手选计划后没写锚

多候选卡里用户点选了计划（「刚刚选择B计划」）——这是**比任何推断都权威**的信号，但没有写锚，
于是下一句省略主语时会**再弹一次候选卡**。

**处置**：两个 Handler 的 `matchSelectedPlan` 成功后写锚，新增来源 `USER_SELECTION`。

### 修正 3：会话边界（不是 bug，是定义）

锚是**会话级**的（与 V3.8 文章锚一致）——跨会话天然不命中。这是设计，不是缺陷。

## 七、第二刀主体：工作流启动前的「理解展示」

实测追问："不分析嘛，不是说开启工作流之前会思考，还是像之前一样直接开启工作流？"

工作流以前是**判完直接开跑**：用户只看到确认卡突然弹出来，中间没有"它理解成了什么"这一步。

**处置**：`buildWorkflowUnderstandingStatusFlux` —— 在 5 个工作流出口前先发一条意图级状态：

```
我理解你要调整学习计划《c++学习计划》，正在准备...
```

**只给意图级信息**（意图 + 用户原话里的计划名）：真正的目标定位发生在工作流内部，这里还拿不到。
用锚兜底时 route 内部会再发一条更具体的（"按你刚才提到的《X》"）——前端状态是**累积列表**，后发的追加。

**降级 CTA / 转新建计划的分支不发**：那些分支的实际动作与"我理解你要调整…"对不上，说了反而误导。

## 八、前端：过程状态升级为「步骤累积」

实测要求："步骤累计，而不是新的覆盖旧的，像是思考过程一样，有步骤，还有打勾，沙漏表示状态嘛"。

从 V4.5 的**单条状态**（新的覆盖旧的）升级为**步骤列表**：

```
✓  我理解你要调整学习计划《c++学习计划》，正在准备...
✓  按你刚才提到的《C++ 系统学习与工程化实践计划》来处理...
⌛ 正在加载学习计划...
```

- 最后一条画 ⌛（进行中），其余画 ✓（已完成）——**完成状态由前端推断**（新步骤入列即前一步完成），后端不用发 DONE 事件
- 仍是**瞬态**的（`liveChatSteps`，不进 `AiMessage`）：消息会写 localStorage，混进去刷新后会残留假步骤
- 同一条消息里重复文案不重复入列（服务端重发/重试不刷屏）
- 渲染对齐思考面板（`.ai-thinking`）的视觉语言：同族边框 + 底色 + 圆角

**后续可扩展**：工作流自己的步骤（`WORKFLOW_STEP`）目前不在这条链里——
接进来才能形成"理解 → 定位 → 加载计划 → 生成"的完整链条。

## 九、实测修正 2：字符串匹配用通用词误命中（2026-09-13）

同会话第二次实测：「帮我分析我的c++学习计划」→「行，那就按照你说的优化建议，帮我优化一下这个计划」，
**工作流加载了 Redis 计划**。日志：

```
15:30:45「帮我分析我的c++学习计划…」→ intent=LEARNING_AGENT, planRef=c++学习计划, planId=null
15:32:03「行，那就按照你说的优化建议…」→ intent=LEARNING_PROGRESS, planRef=null, planId=null
         → RAG 检索 question=我想学习Redis，但是不知道怎么开始…，行，那就按照你说的优化建议…
                                              ↑ 这是 Redis 计划的 goal，说明加载错了对象
```

### 根因：`resolveMentionedActivePlans` 在 planRef 为空时退回用整句 message 匹配

`matchActivePlansByMessage` 是"分词 + 命中词数打分"，于是：

| 计划标题 | 命中词 | 得分 |
|---|---|---|
| 《Redis 核心原理与工程化实战**计划**（结构**优化**版）》 | 优化 + 计划 | **2** |
| 《C++ 系统学习与工程化实践计划》 | 计划 | 1 |

**Redis 成为唯一最高分 → 被当成"用户点名成功"** → 而且这个错误结果**抢先于会话锚**
（原话匹配返回了唯一结果，代码就走那条路了，锚根本没机会被读）。

### 处置

```java
// 判据：分类器摘出 planRef = 用户点名了计划（它判"提到哪个"比字符串匹配可靠得多）
// planRef 为空就是没点名 → 交给会话锚推断，不再用整句话碰运气
private List<LearningPlans> resolveMentionedActivePlans(Long userId, String learningPlanRef) {
    if (learningPlanRef == null || learningPlanRef.isBlank()) return List.of();
    return learningPlansService.matchActivePlansByMessage(userId, learningPlanRef);
}
```

**顺带**：`LearningPlanAnchorService.mark` 的日志从 debug 提到 info——
排查"锚为什么没生效"时，这是唯一能看到的写入痕迹（而且**只写入不作数，还要看写的是哪个计划**）。

### 与上一轮的关系

两次实测暴露的是**两个不同的失败原因**，叠在同一段体验里：

| | 会话 | 原因 |
|---|---|---|
| 上一轮（15:09/15:14） | **跨会话** | 锚是会话级的，天然不命中 |
| 本轮（15:30/15:32） | **同会话** | 字符串匹配误命中，抢先于锚 |

## 十、待办：过程展示统一

实测要求："我希望文章域的也是这样，就是想像思考过程那样" + "工作流这一块的可以合并成一个过程面板"。

**当前前端有三套并行的过程展示**：

| 套 | 数据 | 形态 |
|---|---|---|
| Agent 思考面板 | `msg.thinkingSteps` + `msg.plan` | 折叠面板（可展开，持久化） |
| 过程步骤条 | `liveChatSteps` | 瞬态列表（出正文即清） |
| 工作流进度 | `msg.workflow` + `workflowStepLogs` | 卡片 + 步骤日志 |

**目标形态**（实测期望）：

```
▼ 过程 (5)
  ✓  我理解你要调整学习计划《c++学习计划》，正在准备...
  ✓  按你刚才提到的《C++ 系统学习与工程化实践计划》来处理...
  ✓  正在启动学习计划调整工作流...
  ✓  已加载计划
  ⌛ 正在分析调整诉求...
```

**要点**：合并成一个可折叠面板、完成后保留可展开、工作流步骤与过程状态进同一条链。
待前端结构调研完成后实施。

## 十一、Agent 路径接入锚（2026-09-14，V4③ 前置补口）

**缺口**：锚的消费点原先只有两个 `routeXxxWorkflow` 的兜底链（`AiMessageServiceImpl:1226` / `1324`）——
**Agent Runtime 路径完全不读锚**。

**实测后果**（09-13 23:16）：第二句一度被判进 `LEARNING_AGENT`，Agent 的回答是
「我看到你有多个进行中的学习计划，请问你希望优化哪一个计划？」——**它查了列表，但没用上锚**，
哪怕上一轮刚分析过那个计划。

**处置**：`LearningAgentRuntime` 新增 `effectiveGoal` 重写，把锚作为**定位线索**注入 goal
（与 `ArticleAgentRuntime.effectiveGoal` 同构）：

```text
【会话最近讨论的学习计划】《RocketMQ 30天入门学习计划》(ID 2099356…)
（以上只是定位线索：用户省略主语、说"这个计划 / 它"时很可能就是指它——
  需要读计划内容时，在 QUERY_LEARNING_DASHBOARD 的 input.planRef 填该计划标题；
  用户原话明确点了别的计划时以原话为准，不要被这条线索带偏）
```

**边界（逐条核对 Codex 评审意见）**：

| 评审边界 | 实现 |
|---|---|
| 分类器仍不读锚 | ✅ 锚只在 `effectiveGoal` 注入，分类链路没碰 |
| 锚只做目标定位 | ✅ 注入线索文本，不替决策器做选择 |
| 有明确 planRef 时用户当前句优先 | ✅ goal 提示明写；执行器仍按 planRef 字符串匹配 |
| 锚冲突不猜 | ✅ 锚是**单列**，结构上不存在多锚；计划已删 → `resolve` 返 null → 注入"无" |
| 多计划不猜 → 追问 | ✅ 执行器 `matched.size() > 1` 时只列候选（原有逻辑） |

**已知软肋（如实记）**：本实现是**软约束**——把锚当线索注入，**靠决策器自己把标题填进 `input.planRef`**，
后端不强制。好处是与文章域 `anchorMode` 完全同构、改动最小（只加一个方法重写，不碰接口与执行器）；
代价是**依赖模型配合**。
若实测发现模型不填（验收矩阵 A4：没点名计划时仍反问"哪个计划"），则改为**硬约束**：
执行器直接读锚（需给 `AgentActionExecutor.execute` 加 `sessionId` 参数）。

**测试**：`LearningAgentRuntimeTests` +2 例（有锚注入 goal / 无锚显式标"无"），23/23 绿；
Spring 装配 `AiSessionServiceTests` 4/4 绿。

## 十一、实测修正 3：学习进度承接上一轮建议（2026-09-13）

同会话场景：「帮我分析我的 C++ 学习计划，看看有没有问题」→ Agent 给出优化建议 →「可以，就按照你说的优化建议优化一下我的计划」。

前两刀只能保证**改哪个计划**，不能保证**按什么方向改**：`LEARNING_PROGRESS` 创建时只有本轮原话 `request`，没有上一轮 Agent 的 `finalAnswer`，模型看到的是空泛的「按你说的优化」，自然容易重新发挥。

### 处置

- 新增 `LearningProgressHandoffResolver`：只在本轮有承接信号（如「你说的 / 按你的建议 / 按刚才」）时触发。
- 只取同 session、同 user、最近带 `agentRunId` 的 assistant 消息；对应 `ai_agent_runs` 必须 `COMPLETED` 且有 `finalAnswer`。
- 再校验该 run 有成功的 `QUERY_LEARNING_DASHBOARD` step，避免把文章 Agent / 通用 Agent 的回答当学习计划建议。
- `streamLearningProgressWorkflowFromIntent` 在 `targetPlan` 已确定时把 handoff 写入 DTO；候选计划未确认时不注入。
- `LearningProgressWorkflowHandler.create()` 固化 `context.handoff = {sourceType, sourceAgentRunId, targetPlanId, suggestedDirection}`。
- `LearningPlanFlowSupport.buildPlanUserPrompt()` 在调整模式下读取 handoff，作为弱参考注入 `GENERATE_PLAN` prompt；若有 feedbackHistory，则不再注入旧方向。

### 边界

- 这是机会性弱参考，取不到就不影响 Workflow 创建。
- 不新增表，不泛化 `ai_sessions` 锚结构。
- `handoff` 不参与目标定位、权限判断、质量检查和保存，只影响生成计划时的方向参考。
