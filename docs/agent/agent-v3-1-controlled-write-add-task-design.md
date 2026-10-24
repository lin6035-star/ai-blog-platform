# V3.1 受控写扩展：ADD_LEARNING_TASK 设计

> 日期：2026-09-02
> 前置：V2.4 受控写（UPDATE_TASK_DONE，双表乐观锁 + 快照留痕）、V3.0 通用思考模式收口
> 一句话：放第 2 个 Agent 写动作——向学习计划指定阶段追加单个任务，仍走「提案 → 确认卡 → confirm → CAS + 快照」安全链路。

## 目标与边界

- **目标**：Agent 从「改任务状态」扩到「追加结构化任务」，验证 V2.4 受控写机制可复用于第 2 个写动作。
- **明确不做**：批量追加 / 重命名 / 删除 / 重排任务；文章域任何写动作；不新增 intent（复用 LEARNING_AGENT）。
- **与 LEARNING_ASSIST 的分界**：用户**自己给出任务名**（「加一个'缓存雪崩防护'任务」）→ 受控写直达；用户**要 AI 拆解**（「把阶段二拆细」）→ Workflow 不变。

## 完整链路

```text
用户：「给 Redis 计划第二阶段加一个'缓存雪崩防护'任务」
→ 分类器：LEARNING_AGENT（非 LEARNING_ASSIST / GENERAL_CHAT）
→ LearningAgentRuntime（先 QUERY_LEARNING_DASHBOARD 观察计划结构，零观察禁止提案）
→ 决策 SUGGEST_WRITE，input { actionType: "ADD_LEARNING_TASK", planRef, stageTitle, taskTitle }
→ 提案存 context_json（pendingWriteAction），run 转 WAITING_WRITE_CONFIRM
→ 前端确认卡按 actionType 渲染：「向 计划「Redis」· 阶段「第二阶段」追加任务「缓存雪崩防护」」
→ 用户 confirm → AgentWriteActionService 按 actionType 分支
→ 后端按标题定位 plan/stage（不猜，匹配不到/歧义 → 拒绝）
→ 重复预检（同名任务已存在 → 明确拒绝，不静默）
→ appendTasks(planId, stageId, List.of(taskTitle), userId)
   （现有安全入口：归属校验 + 去重 + @Version CAS，LearningAssistWorkflow 亦复用）
→ context_json 记录 actionType + before/after 快照
```

## 双层 actionType（必须写死，避免看懵）

同一提案 JSON 有两个 actionType，字段同名但语义分层：

```json
{
  "actionType": "SUGGEST_WRITE",
  "input": {
    "actionType": "ADD_LEARNING_TASK",
    "planRef": "Redis",
    "stageTitle": "第二阶段",
    "taskTitle": "缓存雪崩防护"
  }
}
```

- **外层** `actionType: SUGGEST_WRITE`：Agent Step 动作（白名单终态动作），LearningAgentRuntime.handleExtraTerminalAction 入口。
- **内层** `input.actionType`：写动作类型（UPDATE_TASK_DONE / ADD_LEARNING_TASK），AgentWriteProposal 的 actionType 字段，confirm 分支依据。
- 内层缺省回落到 `UPDATE_TASK_DONE`（保旧模型行为）；`done` 字段仅 UPDATE_TASK_DONE 有意义（ADD 忽略）。

## 后端改动

### 1. AgentWriteProposal（结构零改动）
record 已有 `actionType / planRef / stageTitle / taskTitle / done`，LLM 只给标题类信息的设计正好适配 ADD。仅补类注释：actionType 取值列表与 done 语义。

### 2. AgentWriteActionService：拆目标解析（关键）
现有 `resolveTarget(...)` 三段定位（plan → stage → taskTitle 已存在且唯一）**不能整体复用**——ADD 的 taskTitle 是新任务，本来就不存在。拆两个方法：

- `resolvePlanAndStage(proposal, userId) -> plan/stage`：仅按标题定位计划与阶段（ADD 与 UPDATE_TASK_DONE 共用；多计划/多阶段歧义 → 拒绝，沿用现有规则）。
- `resolveExistingTask(proposal, stage) -> taskIndex`：仅 UPDATE_TASK_DONE 用（taskTitle 必须已存在且唯一）。

ADD 分支流程：`resolvePlanAndStage` → **重复预检**（读 stage.tasks 比对同名，存在 → 明确错误「这些任务已在计划中」，不依赖 appendTasks 的静默 return）→ 调 `appendTasks(...)`（归属校验 + 去重 + @Version CAS 失败 → 「计划正在被修改，请稍后重试」透出）→ 写 context_json（actionType + before/after）。

### 3. LearningAgentRuntime.suggestWrite（L152-196）
现构造硬编码 `"UPDATE_TASK_DONE"`。改为从 `decision.input()` 读写动作类型：ADD 时 taskTitle = 新任务标题（必填，缺失 FAILED run 同现有），done 忽略；提案旁注消息区分「勾选」vs「追加」文案。

### 4. LlmAgentStepDecider 学习域 prompt
写动作规则段补 ADD_LEARNING_TASK：触发语义（用户明确给出任务名 + 指定计划/阶段时）、与 LEARNING_ASSIST 分界、taskTitle 不加不猜、一次一个、双层 actionType JSON 示例。不动现有 UPDATE_TASK_DONE 段。

### 5. inspection / 补拉侧
proposal 结构未变，grep 验证无解析处排斥新值（预期零改动）。

## 前端改动

### 1. api/ai.ts AgentWriteProposal（L99-105）
`actionType: 'UPDATE_TASK_DONE'` → `'UPDATE_TASK_DONE' | 'ADD_LEARNING_TASK'`（vue-tsc 编译期兜底）。

### 2. AiAssistant.vue 确认卡（L2543-2575）
现按 `done` 三目渲染（ADD 时 done=false 会错显「取消任务完成状态」，必须改）。改为按 `writeAction.actionType` 分支：
- UPDATE_TASK_DONE：现有勾选文案不变。
- ADD_LEARNING_TASK：「向 计划「planRef」· 阶段「stageTitle」追加任务「taskTitle」」（补显示 planRef）。
按钮（继续/取消）与状态机（pending/processing/cancelled/expired）零改动。confirm 成功后计划页同步沿用 V2.4 现有机制。

## 测试门（测试先行）

| 测试类 | 用例 |
|---|---|
| AgentWriteActionServiceTests | ADD confirm 成功（resolvePlanAndStage 定位 + appendTasks 被调 + context_json 断言双层 actionType + before/after）；同名重复 → 明确拒绝；计划/阶段定位不到或歧义 → 拒绝；cancel / expired / 非本人沿用；CAS 失败透出 |
| LearningAgentRuntimeTests | SUGGEST_WRITE(ADD) 提案生成 → WAITING_WRITE_CONFIRM；taskTitle 缺失 → FAILED；UPDATE_TASK_DONE 回归不变 |
| AgentPlannerSupportTests | 「给计划加个任务」→ LEARNING_AGENT，不进 LEARNING_ASSIST workflow |
| AiIntentClassifierIntegrationTests | 真实 LLM 2 条：「加一个缓存雪崩防护任务」→ LEARNING_AGENT（对照「拆成小任务」仍 LEARNING_ASSIST） |

## E2E 场景

1. 明确单计划单阶段 → 确认 → 计划页出现新任务（未勾选）
2. 计划/阶段不明 → Agent 追问（ASK_USER）或拒绝
3. 同名任务已存在 → 确认被明确拒绝
4. 游客 → 登录提示（受控写天然要求登录）
5. 刷新 → 确认卡恢复（pendingWriteAction 挂回）

## 风险与缓解

| 风险 | 缓解 |
|---|---|
| 分类器把「加任务」误判 LEARNING_ASSIST / GENERAL_CHAT | 真实 LLM 样例锁线 |
| 复用 resolveTarget 导致 ADD 死锁（taskTitle 不存在） | 拆分 resolvePlanAndStage / resolveExistingTask |
| appendTasks 静默去重 → 「成功但没变化」 | service 预检，同名明确拒绝 |
| 前端按 done 渲染错文案 | 按 actionType 分支 + 类型联合编译期兜底 |
| decider prompt 改动影响 UPDATE_TASK_DONE | 只追加规则不动旧段；内层缺省回落 UPDATE_TASK_DONE |
| 旧消息 / 旧提案兼容 | proposal record 结构零改动 |

## 执行顺序

1. P0 平账（已完成：前端修复提交 + 产物 .gitignore）✅
2. P1 本文档
3. P2 后端测试先行（红）
4. P3 拆 AgentWriteActionService 目标解析 + ADD 分支（绿）
5. P4 Runtime + Decider prompt
6. P5 前端类型 + 确认卡
7. P6 定向测试 + vue-tsc + 真实 LLM 小样例
8. P7 收口：checklist + AIAgent-notes V12 + AGENTS.md/CLAUDE.md 同步
