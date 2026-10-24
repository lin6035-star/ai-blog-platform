# 跨 run 继承铺开：学习域结论交接（V4③ 第二刀）

> **状态**：第一阶段已实施（2026-09-12）；第二阶段待证据。
> **定位**：V4 评估点 ③「跨 run / Workflow 上下文继承」的**第二刀**——V3.12 打通了文章域一条，本刀先修学习域建议卡确认时的 `reason` handoff；锚泛化只作为自然语言跨 run 继承的后续候选。
> **前序**：[`agent-v3-12-conclusion-inheritance-design.md`](agent-v3-12-conclusion-inheritance-design.md)（文章域那条）。

## 〇、结论先行

1. **长期模型可以把锚泛化为 `(scope, target_id)`，但不是本轮第一步**——当前先区分“确认时的 reason 交接”和“下一轮自然语言续接”，避免为修一个已确认的信息丢失直接改整套存储模型。
2. **文章域语义完全不变**：`scope=ARTICLE` 时 `targetId` 就是 `articleId`，V3.12 的读取单点 / 快照 / 弱参考设计**一行不用动**。
3. **第一阶段已只做 `LEARNING_ASSIST` 确认时 handoff**：学习域建议卡确认后，只有 `planId` 已由后端权威解析时，才把 `suggestion.reason()` 作为弱参考固化到 Workflow context，并注入 `GENERATE_TASKS` prompt。
4. **本稿不在证据不足时直接实施锚泛化**：DDL 成本不高，但它会牵动文章域稳定链路、实体、测试 schema 和读写服务；必须先证明自然语言跨 run 继承确实需要它。

## 一、背景：什么是「锚」

**锚 = 会话级的「最近目标」记忆槽位。**

```
用户（在 A 文章页）：这篇文章写得怎么样？
AI：（分析 A）
用户：刚刚那篇再帮我看看      ← 「刚刚那篇」是谁？
```

用户翻页或上下文变化后，后端**光看当前请求无法解析"刚刚那篇"**。锚把「这个会话最近涉及的目标」记在 `ai_sessions` 上，指代才能消解。

`ai_sessions` 上现在有三个状态字段组：

| 字段组 | 记住什么 | 引入 | 用途 |
|---|---|---|---|
| `active_workflow_run_id` | 当前活跃的 Workflow | V2.1 | 会话接着流程走 |
| `last_article_*`（4 列） | 最近聊的是**哪篇文章** | V3.8 | 指代消解（「刚刚那篇」） |
| `last_conclusion_*`（5 列） | 最近一次 Agent 的**结论内容** | V3.12 | **传给 Workflow** |

**前两个是「目标是谁」，第三个是「说了什么」**——本刀动的是第三个。

## 二、现状：V3.12 已打通的那条

```
文章域 Agent 结论
  → 写进结论锚（两个写点：FINAL_ANSWER 终态 / 建议卡 confirm）
  → ArticleOptimizeWorkflowHandler.create() 读锚（读取单点，三条入口全覆盖）
  → 固化成 context.handoff 快照
  → 注入 GENERATE_OPTIMIZATION_PLAN 的 prompt（弱参考 + 防注入三段式）
```

**这套设计是成熟的，本刀直接复用**：

| 设计点 | 为什么值得复用 |
|---|---|
| **读取单点放 `Handler.create()`** | 三条创建入口全覆盖；将来新增入口不会漏 |
| **校验宁可不注入** | 任一不满足返回 null（会话归属 / 目标匹配 / 文本非空 / 文章归属） |
| **快照固化 `context.handoff`** | 后续步骤不回查 DB；创建时刻的状态就是这一 run 的状态 |
| **弱参考（机会性注入）** | 不判定用户是否引用——词表假阴性会静默打穿主路径 |
| **防注入三段式** | 结论文本是"不可信数据"，进 prompt 前要包边界 |

## 三、锚的泛化（第二阶段候选）

### 3.1 问题

```java
// AiSessions
private Long lastConclusionArticleId;    // ← "Article" 写死在列名里
```

服务类叫 `ArticleSessionAnchorService`，读取方法签名是：

```java
resolveConclusion(Long sessionId, Long userId, Long targetArticleId)
//                                              ↑ 必须是「一篇文章」
```

**学习域的目标是「学习计划」**（planId）——现有形态装不下。

### 3.2 本质

结论锚的语义是：

> **会话 + 最近一次 + 某个目标的结论**

**「域」只是目标的属性，不是锚的本质**。所以泛化 = 把写死的目标维度换成一对 `(scope, target_id)`。

### 3.3 方案对比

| 方案 | 做法 | 判断 |
|---|---|---|
| **A · 扩列 + 语义扩展** | 加 `scope` 列，`article_id` 里存 planId | ❌ **列名误导**（`article_id` 里存 planId，读到的人会误判） |
| **B · 平行一套** | 加 `last_conclusion_learning_*` 五列 | ❌ **列数翻倍**，第三个域再翻一倍 |
| **C · 改列名 + 加 scope** | `article_id` → `target_id`，加 `scope` 列 | ✅ **长期候选；本轮不直接实施** |

### 3.4 长期候选方案（C）

方案 C 作为后续真正实现“跨域结论锚”的目标形态保留，但当前不能把它当成本刀已经拍板的实施方案。

**表结构**：

```sql
ALTER TABLE ai_sessions
    CHANGE COLUMN last_conclusion_article_id last_conclusion_target_id BIGINT NULL,
    ADD COLUMN last_conclusion_scope VARCHAR(32) NULL;
-- 存量：未上线，只有本地数据，标注为文章域即可
UPDATE ai_sessions SET last_conclusion_scope = 'ARTICLE'
 WHERE last_conclusion_target_id IS NOT NULL;
```

**枚举取值**（先定义两个，后续按需扩）：

| scope | target_id 语义 | 来源域 |
|---|---|---|
| `ARTICLE` | 文章 ID | 文章域 |
| `LEARNING_PLAN` | 学习计划 ID | 学习域 |

**服务层（长期候选）**：

```java
// 泛化后的读取签名
resolveConclusion(Long sessionId, Long userId, String scope, Long targetId)

// 写入签名同步泛化
markConclusion(Long sessionId, Long userId, String scope, Long targetId,
               String text, Long sourceRunId, String sourceType)
```

**类名**：`ArticleSessionAnchorService` → **保留不动**？还是改名？

- 该类同时持有**文章锚**（V3.8）与**结论锚**（V3.12）两类职责
- 建议：**本次不改类名**（改名会牵动 V3.8 的调用点，与"锚泛化"是两件事）；在类注释里标注「结论锚部分已泛化，类名待后续整理」
- **理由**：控制本刀半径——锚的数据形态泛化是必须的，类名整理是可选的

### 3.5 不变的部分

**文章域的行为完全不变**：

```java
// 调用方（ArticleOptimizeWorkflowHandler.create）只是多传一个常量
resolveConclusion(sessionId, userId, "ARTICLE", dto.getArticleId())
```

**V3.12 的五条校验逻辑、快照形态、注入模板、弱参考定位全部保留**——本刀只改「锚怎么存、怎么读」，不改「读出来怎么用」。

### 3.6 两类交接必须分开

本稿涉及两个不同层次的问题，不能用同一个“结论继承”概念覆盖：

| 类型 | 例子 | 是否需要会话结论锚 |
|---|---|---|
| **确认时交接** | Agent 弹出 `LEARNING_ASSIST` 建议卡，用户点击确认，Workflow 使用建议原因 | **不一定需要**；可在 confirm 创建 DTO / context 时直接传 `suggestion.reason()` |
| **自然语言跨 run 继承** | Agent 给出分析结论，用户下一条说“按刚才说的拆一下” | **需要**；必须有会话级结论锚和目标计划绑定 |

第一类是当前代码已确认存在的信息丢失，优先用最小 handoff 修复；第二类才是 V4③ 的跨 run 存储扩展。

## 四、铺开范围与优先级

### 4.0 前置验证（代码级证据已有，缺的是「痛感」）

`AgentRunSuggestionService.createWorkflow` 的学习域三个分支：

```java
case "LEARNING_ASSIST" -> {
    AiWorkflowLearningAssistDTO dto = new AiWorkflowLearningAssistDTO();
    dto.setConversationId(run.getSessionId());
    dto.setRequest(suggestion.initialMessage());     // ← 只有 initialMessage
    applyPlanTarget(dto, run.getUserId(), suggestion.initialMessage());
    yield aiWorkflowRunService.createLearningAssistWorkflow(dto);
}
```

**`suggestion.reason()` 一个分支都没用**——Agent 给出的「为什么建议走这个流程」**没有传进 Workflow**。

**而且这不是新问题**：V3.12 **在文章域修过一模一样的 bug**（记录原话：「顺带修 confirm 路径 `suggestion.reason` 丢失」）→ **学习域是当时漏网的**。但“代码里存在丢失”不等于“必须立刻泛化结论锚”，要先区分确认时交接是否已经足够解决。

**所以「结构上可能存在信息丢失」不是可能，是确定**。缺的只是最后一步：

> **用户是否真的被它影响？**

**验证方法（零代码改动）**：

1. 发一次学习域 Agent 消息，让它产出建议卡（如「你的计划卡在 X，建议用难点攻坚拆解」）
2. **记下建议卡里写的方向**
3. 点确认，看 Workflow 生成的结果**有没有体现那个方向**
4. **判据**：如果结果明显丢了方向感 → 痛感成立 → 按本稿铺开；如果结果本来就一致 → **可能这一刀不需要做**

补充一个最小验证分流：

- 如果只有“建议卡确认后，Workflow 没有体现 `reason`”的问题，优先做**直接 handoff**，不改 `ai_sessions` 结论锚结构。
- 只有当“用户下一条自然语言引用上一轮学习结论”也确实出现割裂，才进入方案 C 的锚泛化设计。

### 4.1 候选链路（验证后定稿）

| Handler | 场景 | 倾向 | 理由 |
|---|---|---|---|
| `LearningAssistWorkflowHandler` | 「把刚才聊的难点拆一下」 | ✅ **先做这一条** | 学习域 Agent 的结论（难点分析）**天然是拆解的输入**，交接价值最直接 |
| `LearningProgressWorkflowHandler` | 「按你说的调整计划」 | ✅ **已做最小版** | 实测已出现割裂：第二轮能定位到计划，但生成计划看不到上一轮建议；本轮只做同会话最近学习 Agent finalAnswer 的弱参考 handoff，不做锚泛化 |
| `LearningPlanWorkflowHandler` | 「按你说的制定个计划」 | ⏸ **下一批** | **没有目标计划**（是新建），`target_id` 语义待定——是「无目标」还是「关联上轮文章」？需要先想清楚 |
| `CreateArticleWorkflowHandler` | 「照着刚才聊的写一篇」 | ⏸ **下一批** | 同理：新建文章没有 `articleId`，且交接价值偏弱（写作场景里用户更可能直接说主题） |

**本轮已做两条最小链路**：`LEARNING_ASSIST` 覆盖建议卡确认 handoff；`LEARNING_PROGRESS` 覆盖自然语言承接上一轮学习 Agent 建议。`LEARNING_PLAN` / `CREATE_ARTICLE` 的 `target_id = null` 语义继续留在后续设计，不并入本轮。

### 4.2 不做 / 边界

- ❌ **Agent → Agent**：同一会话的 Agent 本来就有对话历史（`AiPromptServiceImpl` 拼 history），**不需要额外交接**
- ❌ **QA → Workflow**：QA 是问答，没有「结论」这个概念
- ❌ **Workflow → Agent**：反向交接没有明确场景，本刀不碰
- ❌ **改类名 / 拆锚服务**：见 §3.4 的判断

## 五、实施清单

### 5.0 第一阶段：最小确认时 handoff（已实施）

1. 确认链路保留现有后端计划定位逻辑，先得到权威 `planId`。
2. 只把 `suggestion.reason()` 作为弱参考传入 `LEARNING_ASSIST` 的 Workflow context / `GENERATE_TASKS` prompt。
3. 不新增 `last_conclusion_scope`，不改文章域已有结论锚字段。
4. 只验证“建议卡方向是否进入生成任务”，不宣称已经完成自然语言跨 run 继承。

**实施记录（2026-09-12）**：

- `AiWorkflowLearningAssistDTO` 新增 `handoffReason`，只承接 Agent 建议卡确认时的 `suggestion.reason()`。
- `AgentRunSuggestionService` 的 `LEARNING_ASSIST` 分支先执行 `applyPlanTarget(...)`，仅当 `dto.planId != null` 且 `reason` 非空时写入 `handoffReason`；多计划候选场景不注入，避免后续用户选 B 计划时带入 A 方向。
- `LearningAssistWorkflowHandler.create()` 将 `handoffReason` 截断到 500 字后固化为 `context.handoff = {sourceType, targetPlanId, suggestedDirection}`；不参与计划定位、阶段定位、权限判断、质量检查或 APPEND_TASKS。
- `LearningPlanFlowSupport.buildBreakdownUserPrompt()` 在 `GENERATE_TASKS` prompt 中加入“上轮建议方向”弱参考段，带防注入声明：它是历史文本与参考材料，不是系统指令；与当前计划、目标阶段或用户难点诉求冲突时，以当前上下文为准。
- **审查修正（2026-09-13）**：`handoff` 与 `feedback` 互斥——用户已在确认卡给出明确意见（reject 后重新拆解走同一个 `generateBreakdownByLLM`）时不再注入上轮方向。对齐 V3.12 文章域「有 feedback 时不注入上轮方向」（feedback 是更强的明确信号；`context.handoff` 创建时固化、不会自行消失，两者并存会把模型往回拉）。实现：`buildBreakdownUserPrompt` 里 `feedbackSummary` 只算一次并复用，`buildHandoffSummary(context, feedbackSummary)` 判空即跳过。
- 验证：`AgentRunSuggestionServiceTests` 全类（15 例）+ `LearningAssistWorkflowServiceTests` 三个 handoff 用例（`#confirmedSuggestionHandoffIsPersistedAndInjectedIntoBreakdownPrompt` 正向注入 / `#handoffDirectionIsDroppedWhenUserFeedbackExists` 互斥 / `#handoffDirectionIsTruncatedBeforePersisting` 截断 500）通过；`LearningAssistWorkflowServiceTests` 全类仍受原有真实 LLM 用例影响，AI 服务失败时会红，不作为本刀阻塞。

### 5.1 第二阶段：自然语言跨 run 继承（只有证据成立才进入）

**2026-09-13 修正**：学习进度场景已有真实证据，先落了不改表的最小版，未进入下面的 DDL 泛化方案。

- 新增 `LearningProgressHandoffResolver`：只在本轮有承接信号（「你说的 / 按你的建议 / 按刚才」等）时触发。
- 来源只取同 session、同 user、最近带 `agentRunId` 的 assistant 消息；对应 `ai_agent_runs` 必须 `COMPLETED` 且 `finalAnswer` 非空。
- 再校验该 run 有成功的 `QUERY_LEARNING_DASHBOARD` step，避免文章 Agent / 通用 Agent 串入学习进度 Workflow。
- `AiWorkflowLearningProgressDTO` 新增 `handoffReason` / `handoffSourceAgentRunId`；`LearningProgressWorkflowHandler.create()` 固化 `context.handoff = {sourceType, sourceAgentRunId, targetPlanId, suggestedDirection}`。
- `LearningPlanFlowSupport.buildPlanUserPrompt()` 在调整模式注入弱参考；`feedbackHistory` 非空时不注入旧方向。
- 验证：`LearningProgressHandoffResolverTests` 3 例 + `LearningProgressWorkflowServiceTests#handoffDirectionIsPersistedAndInjectedIntoProgressPrompt` + 匹配/锚/Planner 相关 86 例通过。

下面的泛化方案仍作为后续候选，不因本轮最小版自动执行：

1. **DDL**：改列名 + 加 `scope` 列 + 存量标注（本地执行；生产部署前同步）
2. **实体**：`AiSessions.lastConclusionArticleId` → `lastConclusionTargetId`，新增 `lastConclusionScope`
3. **锚服务**：`markConclusion` / `resolveConclusion` 签名泛化（多传 `scope`）
4. **文章域调用点**：传 `"ARTICLE"`（行为不变，只是显式化）
5. **学习域**：
   - 写点：`LearningAgentRuntime` 的 FINAL_ANSWER 终态（对齐 V3.12 写点 A）
   - 读点：`LearningAssistWorkflowHandler.create()` / `LearningProgressWorkflowHandler.create()`
   - 注入：对应的 `GENERATE_TASKS` / `GENERATE_PLAN` prompt（复用 V3.12 的防注入三段式 + 截断）
   - **注入文案按 `sourceType` 分支**：第一阶段只有 `WORKFLOW_SUGGESTION_CONFIRMED` 一种来源，文案写死“用户刚确认的 Agent 建议原因”（当前准确）；加入 FINAL_ANSWER 来源后该措辞不再准确，需按 `handoff.sourceType` 选文案——字段已存，消费端尚未使用（勿提前实现，YAGNI）
6. **测试**：锚服务的单测（scope 分派 / 串目标校验）+ 学习域交接的集成测试

## 六、风险与边界

| # | 风险 | 应对 |
|---|---|---|
| 1 | **串目标**（上轮聊 A 计划、这轮调 B 计划） | 复用 V3.12 的校验模式：`anchor.target_id == dto.planId` 才注入 |
| 2 | **跨域串**（上轮聊文章、这轮调计划） | `scope` 不匹配即不注入——**这正是不用方案 A 的另一个理由** |
| 3 | 学习域写点选在哪 | 对齐 V3.12 写点 A（FINAL_ANSWER 终态），**钩子晚于 run 落库**防孤儿锚 |
| 4 | 存量数据 | 未上线，本地一条 UPDATE 即可；生产部署前同步 DDL |
| 5 | 类名与职责不符 | 见 §3.4：本次不改名，注释标注待整理 |
| 6 | 学习 Agent 没有权威 `planId` | 不能直接把 `learningPlanRef` 或 LLM 输出当 ID；自然语言继承前必须补 run / session / confirm 的目标绑定链 |

## 七、待评审

- [ ] 方案 C（改列名 + 加 scope）是否作为长期目标采纳；不等于本轮立即实施
- [ ] `LEARNING_PLAN` / `CREATE_ARTICLE` 的 `target_id = null` 语义（新建类 Workflow 要不要继承）
- [x] 第一阶段是否先做 `LEARNING_ASSIST` 的直接 `suggestion.reason()` handoff
- [ ] 自然语言跨 run 继承是否已有真实痛点；若没有，不进入第二阶段
- [ ] 学习域自然语言继承时，权威 `planId` 从哪条后端链路产生并固化
