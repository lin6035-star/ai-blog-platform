# V3.5 路由元数据收口：intent → runtime + fallbackMessage 单点登记

> 状态：**定稿并实施完成（2026-09-06）**。Codex 两轮对齐（异议 1：不做「域一等对象化」只收路由元数据；异议 2：A 写动作执行器注册表本轮不做，收益/风险比 C >> A）。
> 相关：[[agent-module-acceptance-checklist.md]] / [[agent-v3-4-article-write-action-design.md]]（V3.5 触发证据来源）

## Context

V3.4 收口登记的触发条件「域 ≥3」已满足（学习/文章/通用 3 个 AGENT runtime）。调研确认路由现状到痛点：

1. **双份 intent 分发 if 链**：AiMessageServiceImpl 主分发与 fallback 分发（workflow 回退路径）逐字复制；**AGENT 兜底 = else 一律 learningAgentRuntime**——新 AGENT intent 漏配会静默掉进学习域（主雷）
2. **兜底文案双份 6 处字符串**（主 :373/:388/:400 与 fallback :1780/:1794/:1806 逐字复制）——改一处忘一处
3. **判定 intent 名单字符串双写**：AgentPlannerSupport isLearningAgentIntent/isArticleAgentIntent 与执行侧 intent 字符串无单一来源
4. 无任何「intent → runtime → 兜底文案」集中登记处

## 对齐结论（Codex 两轮，2026-09-06 定稿）

1. 不做「域一等对象化」——只收路由元数据（intent → runtime + fallbackMessage），不造域枚举、不把 bean 引用/判定逻辑/域语义塞进一个概念
2. A（写动作执行器注册表）本轮不做，记下一刀：第 5 个写动作前做（届时 switch 5 case 到量化触发，新动作直接落表）
3. 注册表 = 单个 @Component 类，不拆「常量类 + 装配类」（路由目录单类直接、测试集中）
4. Planner 收敛：isLearningAgentIntent / isArticleAgentIntent 查注册表子集名单；**isGeneralAgentCandidate 留在 Planner**（「是否进通用思考」是语义判定，不塞注册表）
5. fallbackMessage 放注册表（路由与兜底文案同源，防 intent 改了文案漏改）
6. **注册表只登记不决策**（演化边界写死）：只提供 resolve / fallbackMessage / intents 查询；判定逻辑（needsThinking 语义）、业务分流（游客门）、默认兜底策略一律留在调用方——否则它从「收口」变「新抽象」

## 实施内容

### 1. AgentRuntimeRouteRegistry（新，ai/agent/）

- record AgentRoute(intent, runtime, fallbackMessage)；构造注入三 runtime（Learning/Article/General）登记 3 行（LEARNING_AGENT / ARTICLE_AGENT / GENERAL_CHAT，兜底文案逐字迁移）
- 只读查询：resolve(intent) / fallbackMessage(intent) / agentIntents() / learningAgentIntents() / articleAgentIntents()
- 判定名单按 runtime 身份派生（intentsFor(Class)：filter instanceof），不重复写 intent 名
- 未登记 → null（调用方显式处理，注册表绝不静默给默认）

### 2. AiMessageServiceImpl 分发收敛

- 主分发 + fallback 分发 → 共用私有 dispatchAgentRuntime(intent, message, ..., requestId)：resolve 查表 → null 时 warn「未登记路由，落入学习域显式兜底」（把静默雷变显式日志雷；现在不可能发生——分类器 AGENT 白名单已全登记）+ learning 兜底；命中 → streamAgentReply(runtime, ..., fallbackMessage(intent))
- 原 6 处兜底文案字符串删除 → 注册表单份取用；fallback 分发保留 removeById(userMessage.getId()) 差异

### 3. AgentPlannerSupport 判定名单收敛

- isLearningAgentIntent / isArticleAgentIntent 改查 registry.learningAgentIntents()/articleAgentIntents()（字符串单点在注册表）
- isGeneralAgentCandidate / decideLearningAgent / decideArticleAgent / decideGeneralAgent 裁决语义不动

### 4. 测试

- 新 AgentRuntimeRouteRegistryTests（4）：resolve 三 intent 命中对应 runtime / fallbackMessage 同源 / 未登记 null / 名单派生
- AgentPlannerSupportTests + AgentPlannerEvaluationTests：setUp 改用真实注册表（mock 三 runtime）——判定名单与生产一致
- 行为零变化：兜底文案逐字保留、分类器 prompt 不动（无需在线门）、匹配算法不动（无需离线门）

## 改动清单（实施结果）

| 文件 | 变更 |
|---|---|
| ai/agent/AgentRuntimeRouteRegistry.java（新） | 注册表（3 行登记 + 4 只读查询 + 名单派生） |
| service/impl/AiMessageServiceImpl.java | 主分发 + fallback 分发 → dispatchAgentRuntime 共用；6 处文案删 → 注册表单份；未登记 warn 显式兜底 |
| ai/planner/AgentPlannerSupport.java | 注入注册表；两 predicate 查表（isGeneralAgentCandidate/decide* 不动） |
| AgentRuntimeRouteRegistryTests（新） | 4 测试 |
| AgentPlannerSupportTests / AgentPlannerEvaluationTests | setUp 真注册表 + mock 三 runtime |

不动：三个 runtime 提案端；proposal/contextJson；前端；Controller；AgentWriteActionService（A 暂缓）；AgentRunSuggestionService（B 暂缓）；评测门 JSON/测试。

## 验证结果

1. 注册表单测 4/4 + AgentPlannerSupportTests + AgentPlannerEvaluationTests（17 例）全绿
2. AiMessageIntentRoutingTests 1/1 绿（意图路由行为不变）
3. Spring context 批量（ArticlesServiceUpdateTitleTests / LearningPlanMatchEvalTests）起 context 成功 = 注册表/Planner 新依赖无循环依赖
4. Agent 模块单测批量回归全绿（LearningAgentRuntimeTests 21 / ArticleAgentRuntimeTests 16 / AgentWriteActionServiceTests 20 / GeneralAgentRuntimeTests）
5. 手工 E2E（待老大）：学习页问计划 + 文章页模糊优化 + 普通聊天思考三条 AGENT 线各走通一次

## 下一刀登记（触发证据状态更新）

- **A 写动作执行器注册表**：第 5 个写动作前做（switch 4 → 5 case 达标；届时 AgentWriteActionService 壳化 + List<AgentWriteActionExecutor> 分发，新动作直接落表）。调研已备：最小改造面 = AgentWriteActionService + 学习/文章两执行器 + 测试 setUp，proposal/前端/夹具零迁移
- **B 确认壳基类**（与 AgentRunSuggestionService 共享锁+CAS 骨架，消 ~130 行复制）：可选刀，动 workflow 建议确认线
- proposal 域结构拆分 / AiAgentRun 加 domain 列：不做（零收益高风险）
- 加第 4 个 AGENT 域改动清单（收敛前 8-9 文件 15+ 处 → 收敛后：分类器 prompt + 注册表登记 1 行 + 新 runtime 三件套 + 调用方新判定分支）
