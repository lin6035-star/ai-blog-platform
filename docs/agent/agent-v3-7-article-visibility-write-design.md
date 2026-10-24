# V3.7 文章域可见性受控写（HIDE_ARTICLE / PUBLISH_ARTICLE）

> 状态：**定稿并实施完成（2026-09-07）**。Codex 一轮异议已吸收：① 执行必须原子条件更新（不直接复用 hideArticle/publishArticle 的无条件 set status——TOCTOU）② 命名去计数口径（两个 actionType 的批次，非「第 5 个写动作」）。
> 相关：[[agent-module-acceptance-checklist.md]] / [[agent-v3-6-write-executor-registry-design.md]]（本刀即插即用载体）

## Context

V3.6 写动作执行器注册表就位后的第一个新动作批次：文章域可见性切换。方向已与 Codex 对齐。现状探明：hideArticle/publishArticle 现成但为「无条件 set status」（编辑器链路用，Agent 链路直接复用有 TOCTOU）；文章 status int（DRAFT=0/PUBLISHED=1/HIDDEN=2）；现实状态转换仅 DRAFT→PUBLISHED（编辑器）/ PUBLISHED↔HIDDEN，无路径需要 AI 对草稿做可见性操作。

## 对齐结论（Codex，2026-09-07）

1. **执行必须原子条件更新**：新增 `ArticlesService.updateArticleVisibility(id, expectedStatus, targetStatus, userId)`——归属 + `status = expectedStatus` 进 WHERE，0 行 → 「文章状态已变化，请重新发起修改」；成功按 targetStatus 对齐 hideArticle/publishArticle 副作用（缓存 + RAG + PUBLISHED 时 publishedAt=now）。**校验通过 ≠ 原子执行**（V3.4 同款教训：run 状态 CAS 只挡同 run 并发，挡不住文章被其他请求改状态）
2. HIDE_ARTICLE / PUBLISH_ARTICLE 作 actionType 值（不带 TYPE_ 前缀暴露给 LLM/前端）✓
3. DRAFT 拒绝：Agent 不从详情页把草稿发布/隐藏，编辑器人工闸保留 ✓
4. 分类器边界样例 + 在线门必跑 ✓；文章域执行器接两动作（V3.6 注册表派上用场）✓
5. proposal 不加字段（复用 articleId + articleTitle 展示确认卡）✓
6. 命名：V3.7 = 「文章域可见性受控写」批次

## 关键设计

### 1. 动作语义（目标状态动作，方向 = actionType）

- TYPE_HIDE_ARTICLE：把当前自己的文章设为隐藏，**前置 = 当前 PUBLISHED**
- TYPE_PUBLISH_ARTICLE：把当前自己的文章公开（取消隐藏），**前置 = 当前 HIDDEN**
- DRAFT：两种动作都拒绝（终局 FAILED「这篇文章还未发布，隐藏/公开操作请在编辑器中完成」——编辑器人工闸保留）
- 范围锁文章详情页（articleId 只认页面上下文）；只动自己文章

### 2. 并发/stale 语义——前置从动作推导 + 执行原子条件更新（设计亮点）

```text
V3.4 改名：前置 = 具体旧标题（DB 无法从动作推导）→ 存锚 proposal.articleTitle + stale 校验 + service 旧值条件更新
V3.7 可见性：前置 = 目标状态的反向（HIDE 要求 PUBLISHED / PUBLISH 要求 HIDDEN）——从动作方向可推导
  → proposal 不加 articleStatus 锚：confirm 校验「当前状态 ∈ 动作前置」，不满足 = 提案后状态被并发修改 → 拒绝
  → 执行原子条件更新收口（Codex TOCTOU）：updateArticleVisibility 的 WHERE status = expectedStatus
面试可讲：锚的形态取决于动作可推导性；条件更新的必要性取决于动作破坏性（改名覆盖 = 破坏数据；状态重复 set 也有 publishedAt 重置等副作用，统一原子收口）
```

- 提案端预检（查库）：归属（终局失败）→ DRAFT 终局拒 → 已是目标状态（已隐藏再提隐藏）→ FAILED step + observation 循环继续 LLM 告知（不弹卡）
- confirm 执行器（ArticleAgentWriteActionExecutor 可见性分支）：归属 → 前置 stale 校验（明确文案层）→ before 快照 → updateArticleVisibility（原子第二层）→ after 快照

### 3. 执行器与提案端接线（V3.6 即插即用验证）

- ArticleAgentWriteActionExecutor：supports +2；execute 改 switch 三分支（executeUpdateArticleTitle 独立 + executeVisibilityChange 方向参数化）
- ArticleAgentRuntime：suggestWriteArticleTitle → suggestWriteArticle（三分支归一）；抽 rejectStepContinue（业务拒绝循环继续）与 finishSuggestWrite（提案落库）两公共私有方法（原改名流程行内重复收敛）
- proposal 复用 V3.4 articleId/articleTitle（articleTitle 仅确认卡展示）；newTitle/done 等 null/false

### 4. decider / 分类器 / 前端

- ArticleAgentStepDecider：SUGGEST_WRITE 内层三选一 + 触发规则（改标题 / 隐藏 / 公开各自触发词；草稿会被后端拒的提示）
- 分类器分界：**状态操作指令（隐藏/公开）→ ARTICLE_AGENT 受控写 vs 内容修改指令（删段落/润色）→ 仍 OPTIMIZE_ARTICLE_WORKFLOW**——判据「是否涉及内容」；OPTIMIZE 段 + ARTICLE_AGENT 段各加例外句
- 前端：ai.ts union +'HIDE_ARTICLE'/'PUBLISH_ARTICLE'；AiAssistant.vue label「隐藏文章/公开文章」+ reason「将文章《X》设为隐藏，其他访客将无法看到/公开，恢复为所有人可见」

## 改动清单（实施结果）

| 文件 | 变更 |
|---|---|
| AgentWriteProposal.java | +TYPE_HIDE_ARTICLE = "HIDE_ARTICLE" / TYPE_PUBLISH_ARTICLE = "PUBLISH_ARTICLE" |
| ArticlesService + Impl | +updateArticleVisibility(id, expectedStatus, targetStatus, userId)：归属 + 前置状态进 WHERE 原子更新 + 按 targetStatus 副作用（缓存/RAG/PUBLISHED 时 publishedAt） |
| ArticleAgentWriteActionExecutor | supports +2；execute switch 三分支；executeVisibilityChange（前置 stale + 原子条件更新 + 快照/载荷） |
| ArticleAgentRuntime | suggestWriteArticle 三分支（DRAFT 终局拒 / 已目标态 step 拒循环继续 / 提案落库）+ rejectStepContinue/finishSuggestWrite 抽取 |
| ArticleAgentStepDecider | SUGGEST_WRITE 内层三选一 + 触发规则 |
| AiIntentClassifierImpl | OPTIMIZE / ARTICLE_AGENT 两段加「状态操作 → 受控写」例外句 |
| classifier-evaluation-cases.json | +2（boundary 隐藏 → ARTICLE_AGENT / regression 内容修改不漂移） |
| ArticleAgentRuntimeTests | +4（HIDE/PUBLISH 提案 / DRAFT 终局拒 / 已隐藏 step 拒）→ 20 |
| AgentWriteActionServiceTests | +3（HIDE 成功 verify / stale 拒 / PUBLISH 成功）→ 23 |
| ArticlesServiceUpdateVisibilityTests（新） | 4（HIDE/PUBLISH 成功 + RAG verify / expectedStatus 失配拒 / 归属拒） |
| ai.ts + AiAssistant.vue | union +2 + label/reason 分支 |

不动：hideArticle/publishArticle 本体（编辑器链路继续用）；壳 AgentWriteActionService；学习域；Controller；AiAgentRun 表。

## 验证结果

1. 单测：ArticlesServiceUpdateVisibilityTests 4/4、AgentWriteActionServiceTests 23/23、ArticleAgentRuntimeTests 20/20、ArticlesServiceUpdateTitleTests 4/4（V3.4 回归）、LearningAgentRuntimeTests 21（学习域零影响）、ArticleAgentStepDeciderTests 6、AgentRuntimeRouteRegistryTests 4——全绿
2. **在线门 14/14 全绿**（样例 12 → 14，总量 46 → 48；隐藏 → ARTICLE_AGENT ✓、内容修改不漂移 ✓、原 12 例无漂移）
3. 前端 vue-tsc 零错误
4. 手工 E2E（待老大）：详情页「把这篇隐藏了」→ 确认卡「隐藏文章 · 将文章《X》设为隐藏」→ 继续 → 文章隐藏（列表消失 + RAG 索引删）；「把这篇公开」→ 恢复；已隐藏再提隐藏 → 不弹卡告知；DRAFT 提隐藏 → 拒绝提示去编辑器

## 风险与对策

1. 分类器分界漂移（状态操作 vs 内容修改）→ boundary/regression 双样例 + 在线门 14/14
2. 前置推导假设（状态机现实路径三条）→ 未来若出现新路径，前置校验安全回落提示，无需改设计
3. publishedAt 重置语义：PUBLISH 对齐编辑器「重新发布」（publishedAt=now）——不发明新语义，行为与编辑器一致
