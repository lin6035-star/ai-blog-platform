# Agent V3.10 设计：思考展示升级——受控 ReAct 思考摘要（thoughtSummary）+ 术语定位

- 版本：v1.3（v1.1 Codex 评审 3 点、v1.2 Codex 二轮 3 点，见稿尾「评审记录」）
- 日期：2026-09-08
- 状态：**定稿并实施完成（2026-09-08）**，见稿尾「实施记录」
- 上一刀：V3.9 QA 跨页指代接入

## 一、背景与动机

老大观察到多数 AI 项目以「ReAct」为招牌，问本项目有没有、要不要上（面向实习面试）。同时希望思考展示更有「思考感」——现在面板只是把动作步骤说出来。但老大有明确顾虑：**展示太细会把「调了几次模型、工具叫什么」全说出去，控制不好**。

本文回答两件事：

1. **术语定位（A）**：本项目 Agent 的决策循环本质就是**受限 ReAct**（decision → execute → observation 循环 + 白名单 + 受控写 + 确认门），缺的只是「Thought 落字」与准确命名。面试讲法见 §九。
2. **思考展示升级（B）**：给每步决策加一段「面向用户的思考摘要」，用三道闸控制粒度，回应「怕说太细」。

**更准确的名字**：受控 ReAct 思考摘要展示——展示的是「这一步为什么这么做」的用户可读摘要（sanitized reasoning summary），**不是**模型完整推理过程（raw CoT）。全文按此定位。

## 二、现状事实（代码锚点）

**展示现状 = 动作流水账**：思考面板每行文本由模板生成（"正在查询学习计划…"→"已完成查询学习计划…"），决策动机不可见、行文本随状态跳变。

链路：

```text
LlmAgentStepDecider.decide()
  → 决策 JSON {actionType, input}（无 thought）
  → AbstractAgentRuntime.run() 循环
  → emitter.emit() AGENT_STEP 事件 {stepNo, actionType, status, message}
      （AiMessageServiceImpl:639 组装 SSE data）
  → 前端 applyAgentStepEvent 按 stepNo upsert（AiAssistant.vue:898）
  → 刷新恢复：AgentRunInspectionService.listSteps → AgentStepVO
      （message 由 AgentStepLabelSupport 模板生成，AiAssistant.vue hydrateThinkingSteps 挂回）
```

关键事实：

- `AgentStepDecision` 是 `record(actionType, input)`，无摘要字段。构造点收敛在 3 处：`of()` / `withInput()` / `LlmAgentStepDecider.parseDecision`。
- **三域决策器全部继承 `LlmAgentStepDecider`**（学习域直接用基类；通用域/文章域覆写 system/summarize prompt）。JSON 解析（`parseDecision`）与 user prompt（`buildUserPrompt`）都在基类——**摘要的解析与 prompt 规范可以单点收口，零领域 prompt 改动**。
- 决策上下文 = goal + observations（已裁剪），摘要生成有真实依据可写。
- 落库 `ai_agent_steps` 两处 schema：`db/init.sql` + `blog-system/src/test/resources/schema.sql`（H2 测试库），生产本地库需 ALTER。
- 事件与 VO 结构同源但字段分离：实时 AGENT_STEP data {stepNo, actionType, status, message}；AgentStepVO 另有 summary（observation 摘要，存 DB 但前端未渲染）。
- 测试 mock 决策全走 `AgentStepDecision.of()`（摘要默认 null）→ 加第三分量零回归。

## 三、核心设计决策

### D0：命名约定——协议层与实现层分离

| 层 | 命名 | 理由 |
|---|---|---|
| LLM JSON 顶层 | `"thought"` | 贴近 ReAct 范式术语（Thought/Action），模型侧不区分语义 |
| Java record / 事件 / VO / 前端 / DB 列 | `thoughtSummary` / `thought_summary` | 内部语义 = **清洗后的摘要**，非原始链；面试讲「sanitized reasoning summary」不产生歧义 |

`parseDecision` 读 LLM 的 `"thought"` 键，清洗后存入 `AgentStepDecision.thoughtSummary()`——命名边界在解析单点一次性收口，下游不再见 `thought` 原始名。

### D1：thoughtSummary 的定位 = 展示副产品，不是决策依据

决策依据永远是 `actionType + input`（白名单校验、执行器、落库审计只信它）。thoughtSummary 只是「这一步为什么这么做」的叙述，因此：

- 解析失败 / 为空 → **永远可容忍**，不阻断循环，不 FAILED；
- 不上决策上下文（不回填给下一轮决策——它是写给用户看的，不是给模型看的）；
- 无原文审计价值（排查决策问题看 actionType/input/observation 已够）→ **不存储、不保留模型原始输出**，只存清洗后版本。

### D2：三道闸控制粒度（回应「怕说太细」）

| 闸 | 位置 | 机制 |
|---|---|---|
| ① Prompt 约束（主闸） | `LlmAgentStepDecider.buildUserPrompt`（正常轮，单点） | 见下「thought 书写规范」；thought 为 JSON 顶层**可选**字段 |
| ② 后端闸门（兜底） | `parseDecision` 单点调 `AgentThoughtSanitizer` | 二元闸门：命中机制泄露信号 → 整句 null；干净 → 截断原样通过（规则见 D2.1） |
| ③ 展示与存储同源 | 全程只流转清洗后文本 | 事件 / DB / VO / 前端同一份文本，无第二出口 |

**thought 书写规范**（写进 user prompt，领域无关）：

```text
JSON 顶层可选字段 "thought"——给用户看的思考摘要：
- 一句中文，解释这一步的动机（为什么查这个、看到什么、为什么下一步这么做），第一人称、口语化
- 禁止出现任何动作英文名（QUERY_* / SEARCH_RAG / SUGGEST_* 等）与机制词：
  调用 / 接口 / API / 模型 / 工具 / 参数 / JSON / 白名单 / 决策 / 第 N 步 / 步骤
- 不要把最终答案写进 thought
- 想不出自然的说法就省略该字段（缺失不影响任何流程）
```

repair 轮（修复重试）不强求 thought——三个域 repair prompt 零改动，宽容缺失。

### D2.1 AgentThoughtSanitizer：分级闸门，零修补

模型输出**不修复、不替换、不拼接**——按泄露强度分级一票否决：

1. 输入 null / 空白 → 返回 null；
2. **强信号（单命中即整句 null）**——业务 thought 不会出现的绝对机制痕迹：
   - 动作枚举：`QUERY_[A-Z_]+` / `SEARCH_RAG` / `SUGGEST_[A-Z_]+` / `FINAL_ANSWER` / `ASK_USER`
   - 绝对机制词：`JSON` `白名单` `决策器` `第 ?\d+ ?步`
3. **组合信号（弱词与机制动词同现才 null）**——防误伤技术业务词汇：
   - 弱词（技术业务常见词，**单独出现放行**）：`API` `工具` `模型` `参数` `接口`
   - 机制动词（与任一弱词同现 → null）：`调用` `返回` `输出`
   - 例：「我先找找讲 XX 工具怎么用的文章」→ 放行；「我调用了 XX 的 API 看返回」→ null；「我让模型看看」→ 放行（无机制动词，可接受漏网——prompt 约束是主闸）
4. 通过 → 去首尾空白、截断 100 字，原样返回。

**为什么零修补**（如「我调用 QUERY_MEMORY 工具查询你的记忆」→「我查询记忆查询你的记忆」）：

- thought 是可选字段，丢得起——回退模板文案（"已完成查询记忆"）至少通顺；
- 修补制造病句的风险 > 强行保留的价值；命中即弃是对「模型没按 thought 用途输出」的合理惩罚，展示质量优先。

**分级原因**（v1.1→v1.2，Codex 评审 2）：v1.1 把「工具/API/模型」当单命中信号，技术博客场景误伤率高（用户问工具用法 / API 设计 / 模型对比时，模型 thought 自然带这些词），思考摘要产出率会被打低。分级后：绝对机制痕迹单命中即弃，业务常见词只有与机制动词组合才弃。

### D3：空 thoughtSummary 全链路兼容

事件 / DB / VO / 前端 thoughtSummary 均可空；空时渲染回退现有 message 模板 → **老行为零回归**。LLM 不给、给得不好被闸门拦成 null、mock 决策器（of() 默认 null）全部走回退。

### D4：行文本稳定，不随状态跳变

现状行文本 RUNNING「正在查询学习计划…」→ SUCCESS「已完成查询学习计划…」，一个动作两句话来回跳，是流水账观感的主要来源。thoughtSummary 是「这一步的动机叙述」，**跨 RUNNING/SUCCESS 保持同一句**，状态只由图标表达（⏳→✓）。

### D5：UI 形态

面板行主文本 = `thoughtSummary ?? message`（为空整行回退现状）。折叠条「思考过程（N 步）」不变。行结构不变，仅文本来源变化。

## 四、数据与链路改动

### 4.1 AgentStepDecision 加第三分量

```java
public record AgentStepDecision(AgentStepActionType actionType, Map<String, Object> input, String thoughtSummary) {
    public static AgentStepDecision of(AgentStepActionType actionType) { /* thoughtSummary = null */ }
    public AgentStepDecision withInput(Map<String, Object> newInput) { /* 保留 thoughtSummary（V3.8 文章域 prepareStepDecision 场景：决议目标是后端并入，不改动机） */ }
}
```

`parseDecision` 读顶层 `"thought"` 键 → `AgentThoughtSanitizer.sanitize` → 存入 record 的 thoughtSummary。

### 4.2 AgentStepEmitter 加参数

`emit(int stepNo, String actionType, String status, String message, String thoughtSummary)`——5 参，nullable。`noop()` 同步。骨架全部 emit 调用点传 `decision.thoughtSummary()`：

| emit 调用点 | thoughtSummary |
|---|---|
| run() 决策无效 FAILED（233 行） | null（决策无效，无摘要） |
| FINAL_ANSWER / ASK_USER SUCCESS（254/260 行） | decision.thoughtSummary() |
| SUGGEST_WORKFLOW 零观察拒绝 FAILED（270 行） | null（拒绝原因文案已说明） |
| 动作执行 RUNNING / SUCCESS（284/405 行） | decision.thoughtSummary()（同一句，跨状态稳定，D4） |
| suggestWorkflow SUCCESS（531 行） | decision.thoughtSummary() |
| 学习域扩展终态（LearningAgentRuntime 内自 emit） | decision.thoughtSummary() |

### 4.3 落库

- `AiAgentStep` 实体加 `thoughtSummary` 字段；
- `executeActionAndRecordStep` 落 step 时写入 `decision.thoughtSummary()`；
- `recordTerminalStep` 落终态步时写入 `decision.thoughtSummary()`（骨架方法有 decision 参数，内部直接取）。

### 4.4 事件与 VO

- `AiMessageServiceImpl` agent emitter lambda：事件 data 加 `"thoughtSummary", thoughtSummary`（5 参接口同步）；
- `AgentStepVO` 加 `thoughtSummary`；`AgentRunInspectionService.toStep` 从 DB 读出。

### 4.5 前端

- `AgentStepEvent` / `AgentStepView` / `AgentStepHistoryItem` 加 `thoughtSummary?: string`；
- `applyAgentStepEvent` 透传 thoughtSummary（按 stepNo upsert 时整行替换，RUNNING/SUCCESS 同句 → 文本稳定）；
- 渲染行：`step.thoughtSummary?.trim() || step.message`——防空字符串 / 纯空白渲染（AiAssistant.vue 2535 行）；
- 补拉挂回路径同步透传（同样 trim 兜底）。

### 4.6 DB 变更（老大本地库执行）

```sql
ALTER TABLE ai_agent_steps
    ADD COLUMN thought_summary VARCHAR(500) NULL COMMENT '思考摘要（清洗后，展示用，非决策依据）' AFTER action_type;
```

同步：`db/init.sql` 建表语句、`test/resources/schema.sql`（H2 测试表）。

## 五、UI 对照

现状（动作流水账）：

```text
思考过程（3 步）
[1] ✓ 已完成查询学习计划
[2] ✓ 已完成检索站内知识
[3] ✓ 已生成最终回答
```

升级后（思考流；thoughtSummary 为空时该行回退成现状文案）：

```text
思考过程（3 步）
[1] ✓ 你的计划里 Redis 只到缓存基础，还没到雪崩这块
[2] ✓ 站内有两篇讲雪崩的，我挑跟「穿透」挨着的给你讲
[3] ✓ 已生成最终回答
```

动作标签不再逐行显示（thoughtSummary 缺失时模板文案自然兜底，机制透明不丢失）。

## 六、测试计划

| 类型 | 内容 |
|---|---|
| AgentThoughtSanitizerTests（新） | 见下「闸门用例表」 |
| Runtime 测试（改/增） | mock decider 返回带 thoughtSummary 的 decision → 断言 step 落库、emitter 事件携带；终态步也落 |
| 既有 runtime 测试 | `of()` 空摘要零改动零回归（空 → 原 message 文案） |
| 评测门 | 离线不变；在线 smoke 见下（决策器不经过既有评测样例） |
| 前端手测（老大） | 三域各触发一次 agent run：实时思考流 + 刷新恢复一致 + 空摘要场景回退 + 无空字符串渲染 |

**AgentThoughtSanitizerTests 闸门用例表**：

| 输入 | 期望 | 依据 |
|---|---|---|
| `我调用 QUERY_MEMORY 工具查询你的记忆` | null | 枚举强信号 |
| `这一步需要调用 API 才能继续` | null | 组合：调用 + API |
| `我调用了接口看返回结果` | null | 组合：调用 + 接口 + 返回 |
| `FINAL_ANSWER`（无上下文裸枚举） | null | 枚举强信号 |
| `我先找找讲 XX 工具怎么用的文章` | 通过 | 弱词单独，业务句 |
| `看看有没有 API 设计相关的文章` | 通过 | 弱词单独，业务句 |
| `我先查查你的计划，看 Redis 学到哪了` | 通过 | 干净 |
| `看完观察，我觉得信息够了，直接回答` | 通过 | 干净 |
| 空串 / null | null | 空输入 |
| 100 字以上干净句 | 截断至 100 字 | 长度 |

**三域真实 LLM 决策 smoke（收口前必跑，不进固定 eval gate）**：

本刀改的是 `LlmAgentStepDecider.buildUserPrompt` 的输出约束——多一个 thought 字段要求，真实风险是**决策 JSON 更容易变形**（解析失败修复一次仍失败 → run FAILED）。评测门不经过 AgentStepDecider，不代表这块不用验证。

- 形态：Learning / Article / General 各 1 条真实 LLM 决策调用，放 `-DrunRealLlm=true` 组（决策器只吃 goal + observations 字符串，不依赖 ES）；
- 断言：actionType ∈ 对应域白名单 + input 解析成功；thought 可有可无，缺失不 FAILED；
- 目的：锁住「多了 thought 要求不破坏决策 JSON」。

## 七、改动文件清单

后端：

- `ai/agent/AgentStepDecision.java`（record 加分量 thoughtSummary）
- `ai/agent/AgentStepEmitter.java`（接口 5 参 + noop）
- `ai/agent/AgentThoughtSanitizer.java`（新增，二元闸门）
- `ai/agent/LlmAgentStepDecider.java`（parseDecision 读 "thought" + 闸门；buildUserPrompt 加书写规范）
- `ai/agent/AbstractAgentRuntime.java`（emit 调用点 + executeActionAndRecordStep / recordTerminalStep 落库）
- `ai/agent/LearningAgentRuntime.java`（扩展终态 emit 带 thoughtSummary，若有自 emit）
- `service/impl/AiMessageServiceImpl.java`（agent emitter lambda 5 参 + 事件 data 加 thoughtSummary）
- `entity/AiAgentStep.java` + `entity/vo/AgentStepVO.java`（加字段）
- `ai/agent/AgentRunInspectionService.java`（toStep 读 thoughtSummary）
- `db/init.sql` + `blog-system/src/test/resources/schema.sql`（建表加列 thought_summary）

测试：

- `AgentThoughtSanitizerTests`（新增，分级闸门用例表）
- `LlmAgentStepDeciderOnlineSmokeTests`（新增，`-DrunRealLlm=true` 组，三域真实 LLM 决策 smoke）

前端：

- `api/ai.ts`（AgentStepEvent / AgentStepView / AgentStepHistoryItem 加 thoughtSummary）
- `components/ai/AiAssistant.vue`（applyAgentStepEvent 透传 + 渲染行 thoughtSummary 优先 + 补拉挂回）

## 八、不做清单（后续候选）

- observation/summary 上屏做「动机→动作→看到什么」三段式——本刀只做动机层，观察已在最终回答体现；
- Reflexion 反思轮 / Plan 层展示 / workflow 步骤 thought——范式补全留 V4 框架评估；
- thought 回填决策上下文（给模型自己看）——D1 明确不做；
- 模型原始输出留存（raw CoT 审计）——D1 明确不存，审计依赖 actionType/input/observation/状态 + 清洗后摘要。

## 九、术语定位与面试讲法（A 部分）

**一句话定位**：本项目 AI 助手 = 自研受限 ReAct Agent Runtime——决策器每轮输出下一步动作，后端白名单校验后执行并回填观察，循环至终态；ReAct 的自由循环被替换为「白名单动作 + 受控写 + 人工确认门」。

三范式对照（面试讲「为什么这么设计」用）：

| 范式 | 本项目对应物 | 为什么不是照抄 |
|---|---|---|
| ReAct（Reason+Act 循环） | 三域 Agent Runtime（AbstractAgentRuntime.run） | 自由 ReAct 在消费级产品有越权/成本/死循环三风险 → 白名单动作、maxSteps 兜底、受控写提案 + 确认卡 |
| Plan-and-Execute | 5 个 Workflow handler（步骤预定义 + 每步确认） | 高价值流程要可预期、可审计、可打断 |
| Tool calling（function calling） | 聊天管道 @Tool（Spring AI） | 页面动作类（点赞/跳转/收藏）无需 agent 级状态，走轻量原生工具 |

面试 Q&A 模板：

- 「做过 Agent 吗？循环怎么实现？」→ 决策器（LLM 输出结构化下一步 + 每步可选思考摘要）→ 白名单校验 → 执行 → 观察回填 → maxSteps 收敛，三域共用骨架，领域差异走钩子。
- 「ReAct 知道吗？」→ 我的循环就是受限 ReAct；纯 ReAct 三风险 + 我的三个对应改造（白名单 / 人工确认 / maxSteps）；演示过不设防会乱跑。
- 「思考过程怎么展示的？是暴露模型思维链吗？」→ 不是。展示的是 sanitized reasoning summary（清洗后的推理摘要），不是原始 CoT——三道闸（prompt 禁令 / 后端一票否决 / 只存清洗版）保证不泄露调用次数、工具名、内部机制。**不展示也不存储模型原始思维链**；审计依赖 actionType、input、observation、run/step 状态和清洗后的思考摘要。
- 「怎么防 Agent 乱写数据？」→ 写动作零直接执行：SUGGEST_WRITE 提案 → 用户确认 → 双表乐观锁条件更新，全程留痕。

简历措辞建议（项目亮点一行）：「自研受限 ReAct Agent Runtime：决策-执行-观察循环 + 白名单动作 + 受控写确认 + 思考摘要展示（sanitized reasoning summary，非原始思维链），支撑学习/文章/通用三域 6 个受控写动作与 5 个 Workflow」。

## 十、风险与开放问题

1. **模型是否稳定产出 thought**（DeepSeek 在线）：给出就给、不给或命中闸门就回退模板文案，产品不劣化；上线前在线门 + 手测验证。若产出率过低，后续可考虑由后端从「上一步观察 + 本步动作」模板生成（备选，本刀不做）。
2. **thoughtSummary 真实性**：模型事后叙述动机可能与真实决策路径有偏差——CoT 摘要通病，业界一致接受。prompt 要求基于已有观察写、不编造；定位是「给人看的叙述」非事实承诺。
3. **DB 变更**：本刀是 V3.8/V3.9 后第一次 ALTER（ai_agent_steps 加可空列），本地库由老大执行；列可空，老数据零影响，回滚 = 不读该列。

## 十一、实施顺序

1. 命名先行：LLM JSON 允许 `"thought"`，Java / DB / VO / 前端统一 `thoughtSummary`（D0）；
2. 新增 `AgentThoughtSanitizer` + 闸门用例测试，先锁住分级闸门行为（强信号 → null / 弱词单独 → 通过 / 组合 → null / 干净句 → 通过）；
3. 扩展 `AgentStepDecision` → `AgentStepEmitter`（5 参）→ `AbstractAgentRuntime` 调用点 → `AiAgentStep` / `AgentStepVO` / inspection → `AiMessageServiceImpl` 事件；
4. 前端：类型加字段 → `applyAgentStepEvent` 透传 → 渲染行 `thoughtSummary?.trim() || message`；
5. 全量验证：编译 → 三域 runtime 测试 → 离线评测门 → 前端 build；DB ALTER 后跑后端全测；
6. 收口前：三域真实 LLM 决策 smoke（`-DrunRealLlm=true`，§六），确认 thought 要求不破坏决策 JSON。

## 评审记录

v1.1（2026-09-08，Codex 3 点 + Claude 1 点简化）：

1. 命名 `thought` → `thoughtSummary`（LLM JSON 保留 `"thought"`，后端起全链路 `thoughtSummary`，DB 列 `thought_summary`）——Codex，采纳；
2. §九 删除与 D1 矛盾的「完整链留着审计」，改为「不展示也不存储模型原始思维链，审计依赖 actionType/input/observation/状态 + 清洗后摘要」——Codex，采纳；
3. Sanitizer 从「枚举替换 + 机制词删除」改为二元闸门零修补（命中即 null 回退）——Codex 提方向，Claude 进一步简化：不做「轻微可清洗 + 阈值」分级，命中任一信号即整句丢弃；宁可误杀回退模板，不放行机器味句子；
4. 实施顺序 §十一 采纳 Codex 建议并补充「DB ALTER 后全测」。

v1.2（2026-09-08，Codex 第二轮 3 点，全采纳）：

1. 验收补「三域真实 LLM 决策 smoke」（§六）：本刀改 buildUserPrompt 输出约束，真实风险是决策 JSON 因多了 thought 要求而变形（解析失败 → FAILED）；评测门不经过决策器 ≠ 不用验证；不进固定 gate，`-DrunRealLlm=true` 组收口前必跑；
2. Sanitizer 从单命中即弃改为**分级闸门**（D2.1）：「工具/API/模型/参数/接口」是技术业务常见词，单独出现放行，与「调用/返回/输出」机制动词组合才弃；强机制痕迹（枚举/JSON/白名单/决策器/第 N 步）仍单命中即弃；
3. 前端渲染 `thoughtSummary?.trim() || message`，防空字符串 / 纯空白（4.5）。

## 实施记录（2026-09-08）

**新增文件**：

- `ai/agent/AgentThoughtSanitizer.java`——分级闸门（D2.1 逐条落地）
- `AgentThoughtSanitizerTests.java`——21 用例（闸门用例表 + 小写枚举 + 业务词防误伤 + 空/截断）
- `LlmAgentStepDeciderOnlineSmokeTests.java`——三域真实 LLM smoke（`@Tag("eval")` + runRealLlm 门，见下）

**改动文件**：`AgentStepDecision`（record 第三分量 thoughtSummary）/ `AgentStepEmitter`（5 参 + noop）/ `LlmAgentStepDecider`（parseDecision 读 "thought" → 闸门；buildUserPrompt 正常轮追加书写规范，repair 轮不加）/ `AbstractAgentRuntime`（9 处 emit 调用点 + 执行步与终态步落库）/ `LearningAgentRuntime` + `ArticleAgentRuntime`（各自 SUGGEST_WRITE emit 全扫：FAILED 类传 null，SUCCESS 传 decision.thoughtSummary()）/ `AiAgentStep` + `AgentStepVO` + `AgentRunInspectionService` / `AiMessageServiceImpl`（agent emitter lambda 5 参，事件 data 加 thoughtSummary）/ `db/init.sql` + `test/resources/schema.sql`（thought_summary 列）/ 前端 `api/ai.ts` 三类型 + `AiAssistant.vue`（透传 + 渲染 + 补拉映射 + 交互）。

**实施中新增需求（老大，2026-09-08）**：思考面板交互升级——思考期间自动展开、正文开始输出自动收起（对齐 Codex 交互），用户可手动再展开。实现：`thinkingAutoMode` ref，send 置 true；onAgentStep 自动展开；首个 DATA chunk 自动收起并退出自动模式；toggle 点击退出自动模式（用户干预优先）。设计稿 §五 UI 形态的展示层之上叠加，未改后端。

**实施中修正**：

1. **Javadoc `*/` 事故**：注释正文写动作枚举 `QUERY_*/SEARCH_RAG` 时 `*` + `/` 提前闭合块注释，javac 报注释外全角字符非法字符——注释内禁止出现星号紧邻斜杠；枚举改写为 `QUERY_xxx / SEARCH_RAG` 写法；
2. **Learning emitter 测试断言**：`captureSteps()` 捕获 insert 而非事件，落库每步只 insert 一次（RUNNING → SUCCESS 是 updateById）——断言从 3 行改 2 行；同时该测试升级：mock decision 带 thoughtSummary → 断言事件 RUNNING/SUCCESS 同句携带、终态 null、落库同步（设计稿 §六 Runtime 测试点）；
3. **测试内联 emitter lambda 4 参 → 5 参**：Article/General/Learning 三个 runtime 测试各 1 处（接口签名变更的编译波及）。

**验证**：

- Sanitizer 21 / Learning 21 / Article 23 / General 10 / 三域 executor + inspection + 三个 decider 测试全绿（131 + 21）；
- 离线评测门（-Dgroups=eval）：planner 1 + learning 17 全绿（本刀不经过 planner/classifier，跑一遍确认无波及）；
- 前端 build 通过（thoughtSummary 类型 + 交互改动）；
- **三域真实 LLM smoke 3/3 全绿**（-DrunRealLlm=true）：Learning/General/Article 决策 JSON 在 thought 字段要求下正常解析，actionType ∈ 白名单，thoughtSummary 非 null 均通过闸门幂等断言；
- 本地 MySQL 已 ALTER（thought_summary 列）；DB schema 无其他变更。
- 全量回归按老大指示不做（边推进边修，手测发现 bug 再补）。
