# Agent V4.5：聊天 / QA 轻量过程提示与来源展示收口 · 设计稿

> 日期：2026-09-11
> 状态：讨论稿 v4（Claude 复核后修订，补齐耗时量测与测试边界）
> 依据：`agent-v4-autonomous-loop-design.md` §5.5，§10.1 第二优先
> **定位收窄说明**：原拟名「全局思考展示」。经评审收窄为「**聊天 / QA 轻量过程提示 + 来源展示收口**」——
> 因为**来源展示已经存在**，本刀实际只补「首字前的语义状态」这一块，不是新建一套展示体系。

## 〇、结论先行

1. **QA / 聊天不复用 Agent 的思考面板**——它们没有决策循环，套上「思考过程」会夸大系统行为。
2. **真问题不是「界面全空白」，是「有动效、无语义」**——前端已有空占位气泡（`AiAssistant.vue:1958`）与打字动效（`:2578` `ai-typing-dots`），但首字之前用户不知道系统在**确认文章范围**、**检索站内**，还是单纯等模型。
3. **来源展示已经存在**——参考来源卡片（`AiAssistant.vue:2627`）+ STOP 事件里的 references（`api/ai.ts:694`）。本刀只做**语义统一**，不新建。
4. **本刀范围**：新增一个**瞬态**过程事件（`CHAT_STATUS = 4001`），且必须在慢操作**之前**发出；其余不动。

## 一、现状（已核实）

### 三条路径的事件能力

`AiChatEventType`（`entity/AiChatEventType.java`）：

| 码 | 事件 | 谁在用 |
|---|---|---|
| 1001 / 1002 / 1003 | DATA / STOP / PARAM | 全部路径 |
| 2001–2004 | WORKFLOW_STEP / STOP / ERROR / CONTENT_DELTA | Workflow |
| 3001 / 3002 | AGENT_STEP / AGENT_PLAN | 三域 Agent Runtime |
| — | **（空）** | **聊天 + QA 没有任何专属事件** |

分派在 `AiMessageServiceImpl.streamUserChat`（`:195`）：

```text
CTA        → streamCtaMessage
WORKFLOW   → 各 Workflow 流（有 WORKFLOW_STEP）
AGENT      → dispatchAgentRuntime（有 AGENT_STEP / AGENT_PLAN）
其余（默认）→ 普通聊天 + QA —— 只有 DATA / PARAM / STOP
```

**关键认知：QA 不是一条独立分支**，而是「默认聊天 + 检索增强」——靠 `routeDecision.usesRetrieval(...)` 决定是否检索、`qaTarget` 决定文章目标。

### 聊天 / QA 路径的真实体验（逐帧）

| 时刻 | 用户看到 |
|---|---|
| 点击发送 | 用户消息立即上屏 + **空 AI 占位气泡** + **打字动效**（`isMessageStreaming` → `ai-typing-dots`） |
| **首字之前** | **同上——动效在转，但没有语义** ← 缺口在这 |
| 首字之后 | 逐 chunk 填充 |
| 回答完成 | 正文 + **参考来源卡片**（如有检索） |

**所以缺口是**：动效表达了「在忙」，没表达「**在忙什么**」。

### 首字之前的三处同步阻塞

`AiMessageServiceImpl:385-425`：

| 顺序 | 调用 | 干什么 |
|---|---|---|
| 1 | `articleQaTargetResolver.resolve(...)` | QA 目标决议 + 可读加载（页面文章 / 会话锚 / 追问态） |
| 2 | `aiPromptService.buildPrompt(...)` | 拼 prompt（历史 + 记忆 + 页面上下文） |
| 3 | `articleRagSearchService.search(...)` | **站内检索**（`ARTICLE_SEARCH` 分支） |

全程零事件。这跟 V3.1.1 那个坑同族（「后端状态在推进，前端一个信号都没有」）——当时是 Workflow emitter 空实现，这次是 QA 没有对应的事件类型。

## 二、核心判断（不变）

> **Agent 展示「我在做什么」是如实描述；QA 套上「思考过程」是夸大。**

| | Agent | QA / 聊天 |
|---|---|---|
| 有没有决策循环 | 有（选动作 → 观察 → 再决策） | **没有**（一次检索 + 一次生成） |
| 展示「思考」是否如实 | 是 | **否**——实际没在"思考"，只是检索了一次 |
| 面板会不会误导 | 不会 | **会**：让用户以为系统做了复杂推理 |

这正是 §5.5 自己列的边界：**避免让用户误以为系统已经完成了更多工作**。

## 三、本刀范围

| 做 | 不做 |
|---|---|
| 新增**瞬态** `CHAT_STATUS` 事件（首字前的语义状态） | ❌ 复用 Agent 思考面板 |
| 在慢操作**之前**发出（见 §四） | ❌ 展示 Tool Calling（是「动作结果」不是「来源」） |
| 前端防抖 + 最小显示时长（见 §五） | ❌ 给普通聊天强行加状态 |
| 来源展示语义统一（如确有需要） | ❌ 持久化过程状态 |

**覆盖范围要一次说清**：本刀覆盖所有“普通聊天 + QA”出口，而不是只修登录用户主路径。

| 路径 | 位置 | 要求 |
|---|---|---|
| 登录用户主路径 | `AiMessageServiceImpl.streamUserChat` 默认聊天 / QA 分支 | 接入 `CHAT_STATUS` |
| active Workflow 消失后的回退聊天 | `fallbackToNormalChatAfterWorkflowGone` 内普通聊天 / QA 分支 | 接入同一套 helper，避免回退路径无提示 |
| 游客聊天 | `streamGuestChat` 默认聊天 / QA 分支 | 接入同一套 helper，游客不落库、不持久化 |

实施时必须抽一个共同 helper 生成 `CHAT_STATUS` 事件和状态文案，三处出口只调用 helper，不能各自复制一份 if/else。否则这刀会把展示语义散成三套。

## 四、★ 关键实现边界：事件必须在慢操作之前发

**这是本刀成立与否的前提。**

### 4.0 实施前先量耗时

本刀覆盖的是“决策完成后、首个 DATA 之前”这段空档。它的实际长度主要由三段组成：

```text
articleQaTargetResolver.resolve
  + aiPromptService.buildPrompt
  + articleRagSearchService.search
```

实施前先加临时计时日志，至少按三类样本量一次：

| 样本 | 需要记录 |
|---|---|
| `CURRENT_ARTICLE` 当前文章问答 | resolve / buildPrompt / totalBeforeData |
| `ARTICLE_SEARCH` 站内检索 | buildPrompt / ragSearch / totalBeforeData |
| 普通聊天 | buildPrompt / totalBeforeData（用于确认不发状态时的基线） |

判断标准：

- 如果大多数 QA 请求 `totalBeforeData < 250ms`，前端防抖后基本不会展示状态，本刀的用户可见收益有限，需要下调防抖参数或降级为“边界补齐”。
- 如果 `ARTICLE_SEARCH` 或复杂 `CURRENT_ARTICLE` 经常超过 250ms，`CHAT_STATUS` 才有稳定可见价值。
- 计时日志只用于实施前评估，不落库、不做长期指标，本刀不顺带引入完整 traceId / APM。

当前代码没有这三段的现成统一耗时日志；已有 Workflow / Agent step 耗时不能直接代表普通聊天 / QA 首字前耗时。

### 4.1 提前发事件

若后端仍在 `articleQaTargetResolver.resolve` / `buildPrompt` / `articleRagSearchService.search` **之后**才 concat 事件，那前端收到「正在检索」时检索**早已结束**——过程提示彻底失去意义。

**技术依据：能不能提前发？能。**

`routeDecision.usesRetrieval("CURRENT_ARTICLE")` / `("ARTICLE_SEARCH")` 在 `streamUserChat:385` **之前就已确定**（来自 `agentPlannerSupport.decide`），所以**在决议与检索之前就能知道「要不要检索」**，足以发出正确的状态文案。

**一个需要处理的细节**：`QaTarget` 可能是**追问态 / 说明态**（此时不检索）。事件在决议前发，那时还不知道会走向哪种 —— 所以文案按**意图级**给（依据 `usesRetrieval`），不承诺具体结果。

**实现形态**：`streamUserChat` 现在是 `return dispatchXxx(...)` 的纯返回式。要提前发事件，需要把慢操作包进惰性链（`Flux.concat(statusEvent, Flux.defer(() -> ...))` 之类）。**这是本刀真正的代码改动量所在**——不是「加个事件类型」那么简单。

**不解决的等待**：`CHAT_STATUS` 依赖 `routeDecision.usesRetrieval(...)`，而 `routeDecision` 来自分类器 + `AgentPlannerSupport.decide`。所以本刀**不解决分类器 / Planner 决策前的等待**，只补“决策完成后、首个 DATA 前”的语义状态。

### 4.2 事件顺序与归并方式

`CHAT_STATUS` 可能早于 `PARAM` 到达。因为如果必须等 `PARAM`，就已经等到 userMessage 保存和部分准备逻辑之后，削弱“提前提示”的意义。

因此前端归并规则必须是：

- 按**当前流的 AI 占位消息索引**归并，和 V3.13 `AGENT_PLAN` 的实时归并同类
- 不依赖 `assistantMessageId`（此时还没有）
- 不依赖 `userMessageId`（`PARAM` 可能还没到）
- 首个 `DATA` 到达、`STOP`、error、abort 时都必须清掉状态

### 4.3 事件载荷

事件码：

```text
4001 CHAT_STATUS
```

最小 `eventData`：

```json
{
  "statusType": "CURRENT_ARTICLE_RESOLVING",
  "retrievalMode": "CURRENT_ARTICLE",
  "message": "正在确认文章范围..."
}
```

允许的 `statusType` 起步只放两个：

| statusType | retrievalMode | 推荐文案 |
|---|---|---|
| `CURRENT_ARTICLE_RESOLVING` | `CURRENT_ARTICLE` | 正在确认文章范围... |
| `ARTICLE_SEARCHING` | `ARTICLE_SEARCH` | 正在检索站内文章... |

不放内部字段：articleId、chunk 分数、ES 查询参数、prompt 内容、trace 细节都不进事件。

**文案边界**：`CURRENT_ARTICLE` 不能写成“正在读取当前文章”。事件发出时 `QaTarget` 还没决议，最终可能是追问态 / 说明态，并没有真正读到文章。只能说“正在确认文章范围”。

## 五、前端：防抖、最小显示时长与清理

参数先按固定值实现，不做配置：

- **延迟展示**：收到 `CHAT_STATUS` 后延迟 **250ms** 再渲染；若首个 DATA 在此之前到达，**不渲染**
- **最小显示时长**：一旦渲染，至少保留 **300ms**，避免视觉抖动
- **清理时机**：首个 `DATA` 到达、`STOP`、error、abort 都清掉当前占位消息上的 `chatStatus`
- 依据：检索常为百毫秒级，不加防抖会闪一下——比不显示更糟

如果 4.0 的实测耗时显示大多数 QA 请求低于 250ms，允许在实施前调整防抖参数；调整必须写回本文档，不能代码和文档各走一套。

## 六、已定事项（原 7 问的结论）

| # | 问题 | 结论 |
|---|---|---|
| 1 | 过程提示值不值得做 | **做，但要防抖**（§五） |
| 2 | 来源展示与现有 `[1]` 的关系 | **已存在**（参考来源卡片 `:2627` + STOP references `ai.ts:694`）。本刀只做**语义统一**，不是新建 |
| 3 | Tool Calling 是否展示 | **不做**——它在 `AiModelServiceImpl.streamChat` 内部，结果经 registry 在 STOP 阶段取出，属「动作结果」而非「来源」。不与本刀混 |
| 4 | 普通聊天 | **不展示**，保留打字动效 |
| 5 | 事件设计 | **新开**，命名 `CHAT_STATUS = 4001`（过程状态）；最终来源仍走 STOP references，两者不混 |
| 6 | 持久化 | **不持久化**——瞬态 UI 状态，不落 `ai_agent_steps`、不新建聊天步骤表；刷新后恢复来源即可，不恢复「曾经在检索」 |
| 7 | Verifier 展示 | **本刀不处理**——Verifier 当前只在 Agent Runtime 内工作，QA 无对象。文档口径改为「未来 QA 接入 Verifier 再评估展示」 |

## 七、核实出的已有边界

1. **references 后端未持久化**——前端用 localStorage 补恢复。这是既有边界，本刀**不扩大也不顺带修**。
2. **追问态 / 说明态下不检索**——过程提示按 §四 的意图级文案处理，不能承诺已经读到文章。

## 八、实施参数

| 项 | 取值 |
|---|---|
| 事件码 | `4001` |
| 事件名 | `CHAT_STATUS` |
| 前端延迟展示 | `250ms` |
| 最小显示时长 | `300ms` |
| `CURRENT_ARTICLE` 文案 | 正在确认文章范围... |
| `ARTICLE_SEARCH` 文案 | 正在检索站内文章... |
| 普通聊天 | 不发 `CHAT_STATUS`，保留打字动效 |

## 九、验收点

0. 实施前耗时量测：至少覆盖 `CURRENT_ARTICLE`、`ARTICLE_SEARCH`、普通聊天三类样本，并记录是否足以支撑 250ms 防抖阈值。
1. 当前文章问答：首字前可看到“正在确认文章范围...”，首个 DATA 后消失。
2. 站内文章搜索：首字前可看到“正在检索站内文章...”，回答完成后仍使用现有参考来源卡片。
3. 普通闲聊：不出现 `CHAT_STATUS`，只保留原打字动效。
4. active Workflow 回退普通聊天：同样有轻量状态提示。
5. 游客 QA：同样有轻量状态提示，但不持久化任何过程状态。
6. 快速响应：通过 mock / 人为构造首个 DATA 在 250ms 内到达的场景，验证状态不显示；这条不依赖真实 ES 速度手测。
7. abort / error：状态被清理，不残留在空占位消息上。

## 十、信息来源（均已核实）

| 位置 | 内容 |
|---|---|
| `entity/AiChatEventType.java` | 现有事件全集 |
| `AiMessageServiceImpl:195` | 三条路径分派 |
| `AiMessageServiceImpl:385-425` | QA 路径的三处同步阻塞 |
| `AiMessageServiceImpl:1950` | active Workflow 消失后的回退普通聊天 / QA 路径 |
| `AiMessageServiceImpl:2520` | 游客普通聊天 / QA 路径 |
| `AiAssistant.vue:1958` / `:2578` | 空占位气泡 / `ai-typing-dots` 打字动效 |
| `AiAssistant.vue:2627` | 参考来源卡片渲染 |
| `api/ai.ts:694` | STOP 事件（references 来源） |
| `AiModelServiceImpl` | Tool Calling 的位置（本刀不涉及） |
| 实测 2026-09-11 | 第五轮观测中 3 条请求走 QA，用户端「没有思考过程」 |

## 十一、修订记录

### 2026-09-11 Codex 二次复核

| # | 问题 | 处理 |
|---|---|---|
| 1 | `CHAT_STATUS` 可能早于 `PARAM`，不能依赖消息 ID 归并 | 补 4.1：按当前流 AI 占位消息索引归并 |
| 2 | 只盯登录主路径会漏掉 Workflow 回退聊天和游客聊天 | 补 §三 覆盖范围，要求三条普通聊天 / QA 出口共用同一套 helper |
| 3 | `CURRENT_ARTICLE` 文案“正在读取当前文章”会承诺过度 | 改为“正在确认文章范围...”，避免追问态 / 说明态下误导 |
| 4 | 防抖参数和事件码仍待确认 | 固定起步值：`CHAT_STATUS = 4001`、延迟 250ms、最小显示 300ms |

## 十二、实施记录（2026-09-11）

### 12.1 实施清单

| # | 位置 | 改动 |
|---|---|---|
| 1 | `AiChatEventType` | 加 `CHAT_STATUS(4001)` |
| 2 | `AiMessageServiceImpl.buildChatStatusFlux` | 只依赖 `routeDecision`，按 `usesRetrieval` 返回事件或**空流**（普通闲聊不打搅打字动效） |
| 3 | **三条路径接入** | 登录主路径 / Workflow 回退 / 游客，**全部调同一 helper**（无各自复制） |
| 4 | **惰性化重构** | 慢操作 + 流构建抽成 `streamNormalChatFlow` / `streamFallbackChatFlow` / `streamGuestChatFlow`，用 `Flux.defer` 包住——状态事件才真正**早于**慢操作 |
| 5 | 耗时测量 | `V4.5 首字前耗时: resolve=?ms buildPrompt=?ms ragSearch=?ms totalBeforeData=?ms` |
| 6 | 前端 `api/ai.ts` | `ChatStatusEvent` + `onChatStatus` + `EVENT_CHAT_STATUS = 4001` |
| 7 | 前端 `AiAssistant.vue` | 防抖 250ms / 最小显示 300ms / **五处清理**（首个 DATA、STOP、error、abort、主动停止）/ 渲染在打字点之后 |

**一处实现取舍**：状态**不放进 `AiMessage`**——消息会写 localStorage，混进去会在刷新后残留一个假的「正在检索」。改成独立的瞬态 ref（同一时刻只有一个流，够用）。

### 12.2 实测数据（2026-09-11 手测）

| 场景 | resolve | buildPrompt | ragSearch | totalBeforeData |
|---|---|---|---|---|
| 文章问答 | 3ms | **3655ms** | 0ms | **11693ms** |
| 站内检索 | 0ms | 1179ms | 2513ms | 5282ms |

**三条结论**：

1. **防抖 250ms 参数合理**——实际等待 5~12 秒，远大于阈值（原担心的「< 250ms 就白做」不成立）
2. **本刀价值反而更大**：用户等 5~12 秒，「知道在等什么」和「干等」差别很大
3. **但暴露了两个性能问题**（本刀范围之外）：
   - `buildPrompt` 3.6s = **两次记忆向量召回串行**（长期记忆 + 情景记忆，每次都要算 query embedding + 查 ES）
   - **模型首 token 8s**（11693−3655−3）= prompt 太长（40 条对话历史 + 记忆 + 文章上下文）

### 12.3 顺手修的：记忆召回并行

第一个性能问题**在收口时顺手修了**（改动小、收益直接）：

- **`MemoryExtractionAsyncConfig`** 加 `memoryRecallExecutor` 专用池——**不复用写侧池**：召回在请求线程路径上，被写侧任务挤占会直接拖慢首字
- **`AiPromptServiceImpl.buildPrompt`**：两次召回改 `CompletableFuture` 并行 + `orTimeout(3s)` + `exceptionally` fail-open
- **切线程的前提已核实**：两条召回链路只依赖传入的 `userId`，**不碰线程绑定的 `UserContext`**（`requireLogin()` 只在 CRUD 方法里调用）

**实测效果（2026-09-11 手测）**：

| 场景 | `buildPrompt` 改前 | 改后 | 降幅 |
|---|---|---|---|
| 文章问答 | 3655ms | **685ms** | **−81%** |
| 站内检索 | 1179ms | **646ms** | −45% |

**比"省一半"的预期还好**（省了约 3 秒）。

**但首 token 才是剩下的大头**：

| 场景 | `totalBeforeData` | 减去 buildPrompt 后的首 token |
|---|---|---|
| 文章问答 | 11975ms | **≈ 11.3 秒** |
| 站内检索 | 3867ms | ≈ 3.2 秒 |
| 闲聊 | 2469ms | ≈ 1.9 秒 |

**规律：prompt 越长，首 token 越慢**——文章问答那条塞了文章正文，11 秒。**留作下一刀（prompt 长度治理：40 条对话历史 + 记忆 + 文章正文）**。

**日志降级**：量测目的已达成，`log.info` → `log.debug`（避免每次对话刷屏；后续查首 token 时临时开 debug）。

### 12.4 踩到的坑：增量编译的假阳性

实施中途 `mvnw -q compile` 报 `EXIT=0`，但评测门挂了 17 个 Error：

```
java.lang.Error: Unresolved compilation problem
Caused by: 错误: 找不到符号 assistantSaved
```

两个原因叠加：**Maven 增量编译**没重编改动过的类 + **IDE 后台编译的脏 class** 混进 `target/classes`（Eclipse JDT 在有编译错误时也会生成 class，加载即抛 Error）。

> **教训：这个项目验证编译必须 `clean`**——IDE 与 Maven 会互相污染 `target/`。

### 12.5 验收结果

| # | 验收点 | 结果 |
|---|---|---|
| 1 | 文章问答：首字前可见「正在确认文章范围...」 | ✅ 手测通过 |
| 2 | 站内检索：首字前可见「正在检索站内文章...」 | ✅ 手测通过 |
| 3 | 普通闲聊：不出现 `CHAT_STATUS` | ✅ 手测通过（"你好啊"：**耗时日志有、界面无状态**——日志打在方法入口，状态只在有检索意图时发，两者本就不是一回事） |
| 4 | Workflow 回退聊天：同样有提示 | 构造性太强，跳过 |
| 5 | 游客 QA：同样有提示 | ✅ 手测通过（**必须在文章详情页**——游客无会话锚，目标只能来自 pageContext） |
| 6 | 快速响应不闪烁 | 待补测（构造性验证） |
| 7 | abort / error 清理 | 待补测 |

**自动化验证**：后端 `clean` 后 273 例 + 离线评测门 24 例（含 `@SpringBootTest` 上下文加载，验证新 Bean 注入）✅ ｜ 前端 `vue-tsc` ✅

### 2026-09-11 Claude 复核

| # | 问题 | 处理 |
|---|---|---|
| 1 | 未量测首字前慢操作耗时，可能做出看不见的状态提示 | 补 4.0：实施前先量 `resolve / buildPrompt / ragSearch / totalBeforeData`，再确认防抖阈值 |
| 2 | “250ms 内不显示”靠真实 ES 速度手测不可稳定复现 | 验收点 6 改为 mock / 人为快速响应验证，真实手测不强行碰运气 |
| 3 | 三条聊天路径覆盖可能导致逻辑复制 | §三 补强：三处出口必须共用 helper 生成事件与文案 |
