# Agent Workflow 下一阶段开发笔记

本文用于新开 Codex / Claude 会话时快速接上 `01myBlog` 的 Agent Workflow 设计进度。

当前结论：

```text
RAG 解决 AI 如何知道博客知识。
Memory 解决 AI 如何知道当前用户。
Tool Calling 解决 AI 如何操作系统。
Workflow 解决 AI 如何按步骤完成复杂任务。
会话级 active workflow 解决 AI 如何跨轮持续推进同一个任务。
```

第一版 Workflow 不做重型多 Agent，也不做动态 ReAct 循环。当前已经先落地固定流程、可暂停、可恢复、可确认、可修改的 Workflow Runtime。

Agent Runtime V1 已单独收口到设计与验收：

```text
docs/agent/agent-runtime-v1-design.md
docs/agent/agent-v1-acceptance-checklist.md
```

结论：先做 Learning Agent 只读 Loop，验证 Run / Step / Action / Observation / maxSteps / 白名单，不做 Multi-Agent，不上外部 Agent 框架，不让 Agent 自动写入或自动启动 Workflow。

当前下一阶段进入 Agent Runtime V2.1：

```text
docs/agent/agent-runtime-v2-1-workflow-suggestion-design.md
```

结论：Agent 可以基于只读 observation 建议启动学习类 Workflow，但不能直接启动；必须经过后端 allowlist 校验、用户确认和幂等保护后，复用现有 Workflow Runtime 执行业务流程。

边界补充（2026-08-26 评审后写死）：

```text
入口：新意图 LEARNING_AGENT；路由优先级 activeWorkflow > LEARNING_ASSIST > PLAN/PROGRESS > LEARNING_AGENT > GENERAL_CHAT；
      "帮我安排今天学习/继续学什么/有点乱理一下" 进 Agent；后端"帮我+学习"宽兜底不再直接开制定计划
记忆：Agent 决策 prompt 不走 AiPromptServiceImpl 的自动注入，记忆只通过显式 QUERY_MEMORY 获取
复用：不整包复用 AiLearningDashboardTool（混装计划+语义/情景记忆），Executor 直接调
      LearningPlansService / AiMemoryRetrieveService / ArticleRagSearchService，每 action 独立 observation
终态：ASK_USER/WAITING_USER 为终态不恢复，用户下句话开新请求（V1.2 再做 continue）
降级：maxSteps 到顶后再总结一次，失败则用最后一条有效 observation 直接回答
```

## 当前阶段定位

当前 AI 助手已经具备：

- Spring AI ChatClient 接入。
- SSE 流式输出。
- PageContext 页面上下文。
- Tool Calling。
- RAG：ES 向量检索 + ES keyword 混合检索 + 规则 rerank + MySQL 兜底 + references。
- Memory：正式记忆 + 候选记忆 + 用户确认/忽略 + ES 相似旧记忆召回 + LLM 决策 CREATE / UPDATE / MERGE / IGNORE + prompt 注入 + 过期清理。
- Workflow V1：文章创作固定流程，支持持久化 run、跨轮补主题、用户确认、反馈重写、质量检查、编辑器填充和完成/取消收口。
- Workflow 工程化 V2/V2.5：已补 Step Log、FAILED/retry、active workflow 冲突处理、前端失败恢复和 Workflow step/content SSE。
- Agent V1 稳定化：已补 Redis 锁、DB version、Idempotency-Key、Redis 限流、前端 XSS 防护。

现在的重点不再是“有没有 Workflow”，而是把 Workflow 做厚：

```text
Goal -> Plan -> Step -> Tool Action -> Observation -> State Update -> Trace / Retry / Recovery
```

注意：

- `AiToolActionRegistry` 只是 request 级 Action 暂存，用于把导航、编辑器、文章动作通过 SSE STOP 返回给前端。
- 它不是持久化 Workflow。
- `fillArticle` 这类高影响 Tool Action 不应该绕过 Workflow 直接执行。
- 真正的 Workflow 需要 task/run 身份、状态、当前步骤、上下文、用户反馈、失败处理、恢复能力和执行轨迹。

## 已完成：CreateArticleWorkflow V1

当前文章创作 Workflow 链路：

```text
用户提出文章创作需求
  ↓
会话级 active workflow 路由
  ↓
需求解析 / 需求不明确则跨轮补主题
  ↓
读取 Memory 写作偏好
  ↓
RAG 检索站内相关知识
  ↓
LLM 生成大纲
  ↓
等待用户确认 / 修改大纲
  ↓
LLM 生成正文草稿
  ↓
规则型质量检查
  ↓
等待用户确认 / 修改草稿
  ↓
等待用户确认是否填充编辑器
  ↓
返回 editorAction 填充编辑器
  ↓
用户自己保存草稿 / 发布
  ↓
COMPLETED / CANCELLED 收口
```

第一版核心体验不是“AI 一句话直接生成并填充”，而是：

```text
AI 先生成方案。
用户确认后继续。
不满意就填写修改意见。
Workflow 基于同一个 workflowRun 继续调整，而不是重新开一轮普通聊天。
```

已经额外补上的关键能力：

- `ai_sessions.active_workflow_run_id`：会话级 active workflow。
- 有 active workflow 时，下一轮用户输入优先交给 workflow，而不是重新按普通聊天分类。
- `WAITING_REQUIREMENT_CONFIRM` 能跨轮接住用户补充主题。
- 普通聊天路径禁止直接输出 `fillArticle`，避免绕过 Workflow。
- `WAITING_OUTLINE_CONFIRM` / `WAITING_DRAFT_CONFIRM` 的 reject 会重新调用 LLM，而不是把反馈简单追加到旧内容末尾。

阶段小结：

```text
这一阶段先跑通文章创作 Workflow 的最小闭环：意图识别、创建任务、跨轮补主题、大纲确认、草稿确认、反馈重写、最终填充编辑器。
当前重点已经从“能不能跑通”转为“能不能追踪、恢复、排障、扩展”。
```

遇到的主要困难：

- 自然语言意图不能靠硬编码规则判断，必须先走 LLM 意图分类，再决定是否进入 Workflow。
- 没有 active workflow 时，用户跨轮补主题容易回到普通聊天或直接触发 Tool Calling。
- `reject` 不能把用户反馈拼到旧内容后面，而要进入同一个 workflow context 重新生成当前阶段结果。
- 前端非流式 workflow 调用会让用户感觉“点击后卡住”，所以先补底部处理中状态。
- `extractTopic` / `extractSimpleKeywords` 仍是 V1 规则型需求解析，后续可升级成 LLM 结构化 RequirementAnalyzeStep。

## 用户交互现状

Workflow 暂停时，AI 抽屉底部确认面板覆盖原输入框。

聊天记录区域仍然可以正常滚动查看，用户可以回看 AI 生成的大纲或草稿。

确认态：

```text
[同意] [不同意]
```

点击逻辑：

```text
点击 同意
  ↓
继续执行当前 workflow 的下一步
```

```text
点击 不同意
  ↓
按钮区切换为修改意见输入框
  ↓
用户填写反馈并提交
  ↓
后端把反馈追加到 feedbackHistory
  ↓
基于当前 context + 用户反馈重新生成当前阶段内容
  ↓
再次暂停等待确认
```

已补充底部处理中状态：

```text
正在根据大纲生成草稿...
正在根据意见重新生成大纲...
正在根据修改意见重写草稿...
```

已补充体验：

- Workflow Step Log 执行详情折叠区。
- Workflow Step SSE。
- 大纲/草稿内容 delta 流式展示。

后续体验增强暂缓：

- Workflow 消息卡片处理中状态。
- 更细的失败类型展示。
- 结构化 JSON 草稿的逐 token 展示。

## Workflow 状态

当前状态枚举：

```text
RUNNING
WAITING_REQUIREMENT_CONFIRM
WAITING_OUTLINE_CONFIRM
WAITING_DRAFT_CONFIRM
WAITING_FILL_CONFIRM
WAITING_USER_SAVE
PAUSED
COMPLETED
FAILED
CANCELLED
```

状态含义：

```text
RUNNING
  后端正在执行某个步骤。

WAITING_REQUIREMENT_CONFIRM
  写作主题或需求不明确，等待用户补充。

WAITING_OUTLINE_CONFIRM
  大纲已生成，等待用户确认或提出修改意见。

WAITING_DRAFT_CONFIRM
  正文草稿已生成，等待用户确认或提出修改意见。

WAITING_FILL_CONFIRM
  草稿已确认，等待用户确认是否填充到编辑器。

WAITING_USER_SAVE
  已返回 editorAction 并填充编辑器，等待用户自己保存草稿或发布。

PAUSED
  流程被用户或系统暂停，不是失败，也不是某个固定确认点。

COMPLETED
  Workflow 结束。

FAILED
  Workflow 执行失败。

CANCELLED
  用户取消 Workflow。
```

`PAUSED` 暂时预留：

```text
AI 正在执行较长步骤时，用户要求停一下。
系统等待外部资源。
后续加入更多非确认类暂停场景。
```

## Workflow Step

Java 里用枚举控制 step，避免字符串散落：

```java
public enum WorkflowStep {
    REQUIREMENT_ANALYZE,
    MEMORY_RETRIEVE,
    RAG_SEARCH,
    GENERATE_OUTLINE,
    GENERATE_DRAFT,
    QUALITY_CHECK,
    FILL_ARTICLE
}
```

数据库字段仍然用：

```text
current_step varchar(64)
```

这样既可读，也方便以后扩展新 step。

## 数据表现状

当前主表：

```text
ai_workflow_runs
```

核心字段：

```text
id bigint primary key
user_id bigint not null
conversation_id bigint null
workflow_type varchar(64) not null
workflow_version varchar(32) not null
status varchar(64) not null
current_step varchar(64) null
context_json json/text not null
retry_count int default 0
input_tokens int default 0
output_tokens int default 0
total_tokens int default 0
pause_reason varchar(255) null
error_message varchar(1000) null
created_at datetime not null
updated_at datetime not null
```

会话表补充：

```text
ai_sessions.active_workflow_run_id
```

用途：

- 表示当前会话正在进行的 Workflow。
- 有值时，用户下一轮输入优先交给 Workflow。
- Workflow `COMPLETED` / `CANCELLED` 后清空。

已经拆出：

```text
ai_workflow_step_logs
```

它用于记录每个 step 的输入摘要、输出摘要、状态、错误、耗时、重试次数和 token 预留字段，是后续多个 Workflow 复用的基础能力。

## Context JSON 设计

当前 `context_json` 建议结构：

```json
{
  "workflowVersion": "1.0",
  "variables": {
    "articleType": "TECH",
    "language": "zh-CN"
  },
  "requirement": {
    "rawRequirement": "帮我写一篇 Redis 缓存穿透博客",
    "topic": "Redis 缓存穿透",
    "type": "技术博客",
    "keywords": ["Redis", "缓存穿透", "高并发"]
  },
  "clarification": {
    "required": false,
    "question": ""
  },
  "memoryContext": "",
  "ragReferences": [],
  "outline": "",
  "draft": {
    "title": "",
    "summary": "",
    "content": "",
    "tags": [],
    "categoryName": "随笔"
  },
  "qualityCheck": {
    "passed": true,
    "issues": [],
    "suggestions": []
  },
  "feedbackHistory": []
}
```

`workflowVersion` 同时放表字段和 JSON：

- 表字段方便查询、迁移和筛选。
- JSON 内自带版本，方便上下文恢复时自描述。

`variables` 用于放通用参数，例如：

```json
{
  "articleType": "TECH",
  "language": "zh-CN"
}
```

以后如果增加 SEO、图片生成、目标读者等参数，优先放 `variables`，不急着改表。

## Feedback History

用户每次点“不同意”并提交修改意见，都追加一条结构化反馈：

```json
[
  {
    "time": "2026-08-05 22:00:00",
    "step": "GENERATE_DRAFT",
    "status": "WAITING_DRAFT_CONFIRM",
    "userFeedback": "文章太浅，需要增加源码分析"
  }
]
```

用途：

- 当前 Workflow 重新生成大纲或草稿。
- 后续 Memory 可从多次反馈里提取长期写作偏好。
- 面试时可以讲清“用户反馈如何进入 Agent Workflow 的状态更新”。

## 当前接口

### 创建文章 Workflow

```text
POST /api/ai/workflows/article/create
```

行为：

```text
创建 workflow run
执行 REQUIREMENT_ANALYZE
执行 MEMORY_RETRIEVE
执行 RAG_SEARCH
执行 GENERATE_OUTLINE
状态变为 WAITING_OUTLINE_CONFIRM
返回当前 run
```

### 查询 Workflow

```text
GET /api/ai/workflows/{runId}
```

用于：

- 刷新页面后恢复状态。
- AI 抽屉重新打开后恢复底部确认面板。

### 同意当前阶段

```text
POST /api/ai/workflows/{runId}/approve
```

根据当前状态推进：

```text
WAITING_OUTLINE_CONFIRM
  -> GENERATE_DRAFT
  -> QUALITY_CHECK
  -> WAITING_DRAFT_CONFIRM

WAITING_DRAFT_CONFIRM
  -> WAITING_FILL_CONFIRM

WAITING_FILL_CONFIRM
  -> FILL_ARTICLE
  -> 返回 editorAction
  -> WAITING_USER_SAVE
```

### 不同意当前阶段

```text
POST /api/ai/workflows/{runId}/reject
```

请求：

```json
{
  "feedback": "第三部分加上布隆过滤器，最后加项目实战"
}
```

规则：

- `feedback` 必填。
- 后端追加 `feedbackHistory`。
- 如果当前是 `WAITING_REQUIREMENT_CONFIRM`，把反馈当作补充需求。
- 如果当前是 `WAITING_OUTLINE_CONFIRM`，重新生成大纲。
- 如果当前是 `WAITING_DRAFT_CONFIRM`，重新生成正文草稿并重新做质量检查。
- 如果当前是 `WAITING_FILL_CONFIRM`，回退到 `WAITING_DRAFT_CONFIRM`，让用户重新确认草稿。

### 完成 Workflow

```text
POST /api/ai/workflows/{runId}/complete
```

行为：

```text
仅 WAITING_USER_SAVE 可完成
状态变为 COMPLETED
清空 ai_sessions.active_workflow_run_id
```

### 取消 Workflow

```text
POST /api/ai/workflows/{runId}/cancel
```

行为：

```text
状态变为 CANCELLED
清空 ai_sessions.active_workflow_run_id
不再允许 approve/reject
```

## 第一版不继续做的内容

以下内容暂时不做，避免无限打磨文章 Workflow：

- 多 Agent。
- 自主规划 Agent。
- ReAct 循环。
- 动态选择 Step。
- 自动发布文章。
- 每个 Step 都暂停确认。
- 独立 Workflow 可视化大屏。
- 复杂质量评分。
- claim extraction + RAG verify + revise 的高级事实核查循环。
- SEO 优化、配图生成、标题 AB 方案。

这些可以进入后续阶段，而不是继续塞进文章 Workflow V1。

## 已完成：Workflow 工程化 V2

文章 Workflow V1 已经可以作为闭环样板。V2 的目标不是继续加写作功能，而是补企业级 Workflow 能力。

当前已补上：

### 1. Step Log / 执行轨迹

新增：

```text
ai_workflow_step_logs
```

建议字段：

```text
id bigint primary key
workflow_run_id bigint not null
step varchar(64) not null
status varchar(32) not null
input_summary text null
output_summary text null
error_message varchar(1000) null
started_at datetime not null
ended_at datetime null
duration_ms bigint null
input_tokens int default 0
output_tokens int default 0
created_at datetime not null
```

用途：

- 查看每个 step 做了什么。
- 定位失败发生在哪一步。
- 统计耗时和成本。
- 面试可讲可观测性、审计、排障。

### 2. 失败状态和重试

当前部分异常只是 fallback 或直接抛错。V2 要明确失败语义：

```text
LLM 失败 -> FAILED / 可重试
RAG 失败 -> 降级但记录 warning
Tool 失败 -> FAILED / 可重试
```

已新增：

```text
POST /api/ai/workflows/{runId}/retry
```

第一版 retry 可以只重试当前 step。

### 3. active workflow 冲突处理

一个会话已有 active workflow 时，如果用户想开启新任务，要明确提示：

```text
当前还有一个文章创作任务未完成，请先完成或取消当前任务。
```

不要让多个任务抢同一个会话输入。

### 4. Tool Calling 边界固化

当前原则：

```text
fillArticle 只能由 Workflow 输出。
普通聊天不允许直接填充文章。
saveDraft / publish 可以保留。
```

后续如果有更多高影响工具，也要按同样原则处理：

```text
高影响动作进入 Workflow。
低风险动作可以保留 Tool Calling。
```

### 5. 前端体验增强

已经有底部处理中状态。当前已补错误态和重试按钮。后续可补：

- Workflow 消息卡片处理中状态。
- Workflow 历史恢复。
- Step 执行轨迹展示。

## 已完成：Workflow V2.5 Step SSE

Workflow 的 approve / reject / retry 已经补上流式接口；complete / cancel 保持普通 HTTP 请求即可。

市场上常见做法是：

```text
Run 状态持久化负责可恢复。
Step Log 负责可审计、可排障。
Step SSE 负责前端实时进度。
```

当前先做 step 级 SSE 和内容 delta 展示，不急着把结构化 JSON 草稿改成真正逐 token 持久化。原因是文章草稿目前依赖结构化输出，直接按 token 写入状态会出现半截 JSON、校验困难、失败回滚复杂。

已新增：

```text
POST /api/ai/workflows/{runId}/approve/stream
POST /api/ai/workflows/{runId}/reject/stream
POST /api/ai/workflows/{runId}/retry/stream
```

事件只暴露步骤摘要，不暴露模型 chain-of-thought：

```text
WORKFLOW_STEP  -> 某一步开始 / 成功 / 跳过
WORKFLOW_STOP  -> workflow 最新状态 + editorAction
WORKFLOW_ERROR -> 失败状态 + 可重试提示
```

后端通过轻量 `AiWorkflowStreamService` 包一层现有 `AiWorkflowRunService`，不重写已经稳定的状态机、StepLog、FAILED、retry 逻辑。

## 下一阶段：ArticleOptimizeWorkflow

第二个 Workflow 先做文章优化，不是为了堆一个新功能，而是验证第一个 Workflow 的抽象是否合理，为后续 Workflow Runtime 做准备。

核心原则：

```text
分析和执行分离。
高影响操作必须经过用户确认。
第一版不直接改数据库，只返回 editorAction 填充编辑器，由用户自己保存/发布。
```

推荐流程：

```text
用户请求优化当前文章
  ↓
创建 workflow run（workflow_type = OPTIMIZE_ARTICLE）
  ↓
LOAD_ARTICLE
  ↓
ANALYZE_ARTICLE
  ↓
MEMORY_RETRIEVE
  ↓
RAG_SEARCH
  ↓
GENERATE_OPTIMIZATION_PLAN
  ↓
WAITING_PLAN_CONFIRM
  ↓
REWRITE_ARTICLE
  ↓
CONTENT_CHECK
  ↓
WAITING_DRAFT_CONFIRM
  ↓
FILL_ARTICLE
  ↓
WAITING_USER_SAVE
  ↓
COMPLETED / CANCELLED
```

Step 语义：

```text
LOAD_ARTICLE
  读取用户指定文章，第一版优先从 PageContext.articleId 或编辑器 articleId 获取。

ANALYZE_ARTICLE
  只分析原文问题，不修改文章。

MEMORY_RETRIEVE
  读取用户长期写作偏好，例如喜欢源码、原理、Java 后端实践。

RAG_SEARCH
  检索站内相关知识，帮助优化内容完整性和避免重复。

GENERATE_OPTIMIZATION_PLAN
  生成可确认的修改方案，不直接改正文。

REWRITE_ARTICLE
  用户确认方案后，生成优化后的草稿。

CONTENT_CHECK
  检查最终优化草稿，而不是检查优化方案。检查标题、摘要、Markdown、长度、技术关键词、是否覆盖优化方案。

FILL_ARTICLE
  返回 editorAction，不直接 update 数据库。
```

Context 建议从第二个 Workflow 开始使用更通用的形态：

```json
{
  "workflowVersion": "1.0",
  "input": {
    "articleId": 100,
    "instruction": "优化这篇 Redis 文章"
  },
  "memoryContext": "",
  "ragContext": {
    "references": []
  },
  "stepResults": {
    "article": {},
    "analysis": {},
    "optimizationPlan": {},
    "optimizedDraft": {},
    "contentCheck": {}
  },
  "feedbackHistory": []
}
```

第一个 `CREATE_ARTICLE` 的旧 context 先保留，避免为了通用化回头大改稳定链路。等两个 Workflow 都跑通后，再决定是否迁移成统一结构。

这次只抽最小 Runtime 边界：

```text
WorkflowHandler
WorkflowHandlerRegistry / workflowType 分发
```

不要现在做 DAG、数据库动态配置步骤、LangGraph 替换、通用 Workflow 配置平台。业务 Step 可以依赖 `ArticlesService`、RAG、Memory、`ChatClient`，但 Runtime 层不要依赖具体文章业务。Step 之间通过 context 传递数据。

第二个 Workflow 完成后重点验证：

```text
WorkflowRun 是否能继续复用。
StepLog / retry / cancel / complete 是否能跨 Workflow 复用。
前端确认面板是否只需要按 workflowType 改少量文案。
哪些代码应该进入 Workflow Runtime。
哪些逻辑应该继续留在具体业务 Handler。
```

第三个 Workflow 再考虑非文章业务，例如学习计划、项目复盘、技术栈能力地图，用来验证 Runtime 是否真的脱离文章场景。

## 已完成：ArticleOptimizeWorkflow（第二个 Workflow）

第二个 Workflow（OPTIMIZE_ARTICLE）已跑通并接入 `WorkflowHandlerRegistry` 按 workflowType 路由。

### 验证结论

```text
WorkflowRun / StepLog / approve / reject / retry / cancel / context / editorAction
这套 V1 抽象被第二个真实业务完整复用，没有为文章优化场景改动 Runtime 骨架。
```

复用的具体表现：

- 同一个 `ai_workflow_runs` 表 + 会话级 `active_workflow_run_id`，OPTIMIZE_ARTICLE 直接落同表。
- `WorkflowHandlerRegistry` 按 `workflowType` 分发 approve / reject / retry，新增 Workflow 只需注册 handler + 枚举 + DTO。
- Step Log、状态机、前端确认面板、流式事件（WORKFLOW_STEP / STOP / ERROR）全部复用，前端只需要按 workflowType 换少量文案。
- 唯一新增的枚举/状态：`WAITING_PLAN_CONFIRM`、`LOAD_ARTICLE`、`ANALYZE_ARTICLE`、`GENERATE_OPTIMIZATION_PLAN`、`REWRITE_ARTICLE`、`CONTENT_CHECK`。

### 本轮逼出的边界问题

这些不是"新功能"，是第二个业务把 V1 抽象照出的盲点，逐条记录，第三条 Workflow 前想清楚归属：

1. **权限校验**：只能优化自己的文章。`validateArticleOwner` 在 handler 内做（业务规则），但"权限拒绝如何响应"是 Runtime 边界——可预期业务拒绝要转成友好 AI 回复 + STOP 事件，不能 `sink.error` 暴露堆栈（`buildOptimizeWorkflowRejectedMessage`）。
2. **原文所有权与原文编辑**：优化流程不直接改库，只返回 editorAction 填充编辑器，由用户自己保存；上下文必须保留原文快照，重写时以原文为基座，避免覆盖丢失。
3. **流式快照 vs 实时进度**：`runStep` 的 emitter 是实时进度（delta），`stepResults` 存的是完整结果快照（最终态）。两条通道职责分离——context 永远持有可恢复的完整状态，emit 只负责前端进度，不能混用。
4. **模型输出约束（大纲 vs 正文）**：LLM 在"生成方案"后容易继续输出大纲而不是完整正文。三道防线：prompt 硬约束 + `looksLikeOutline` 规则检查 + 检查失败后把问题喂回模型 auto-retry 一次（再失败则停留在确认点，不无限重试）。
5. **事实边界 prompt**：不得新增原文、用户要求、站内参考中没有出现的具体数字、业务规模、性能指标、成功率、生产经验；示例必须标注"例如 / 假设场景"；站内参考只能补充技术背景（降权），不能描述成"本站生产实践"。
6. **workflow_run_id 字符串 "null"**：`String.valueOf(null)` 会产生字符串 "null"，写入 BIGINT 列报错。AI 消息落库边界，null 必须显式判空。
7. **测试与真实 LLM 不确定性**：auto-retry 是否触发取决于真实模型输出，测试断言必须与自身状态自洽（以 retryCount==1 的 step log 是否出现为驱动），不能硬编码 step log 条数。

### Runtime 能力 vs ArticleOptimize 业务能力

```text
已明确属于 Runtime（已抽到基础设施类，2026-08-13）：
  WorkflowHandler 接口 + WorkflowHandlerRegistry        ← 已抽
  AiWorkflowRun / AiWorkflowStep / AiWorkflowStatus / 表结构 ← 已抽
  runStep 步骤执行模板 → WorkflowStepRunner
  recordStepLog → WorkflowStepLogRecorder
  parseContext / toJson / getMap / stepResults 结构 → WorkflowContextSupport
  appendFeedback（feedbackHistory 追加）→ WorkflowContextSupport
  parseStatus / parseStep → WorkflowStatusSupport
  touch（updatedAt 刷新）→ WorkflowContextSupport
  normalizeRequired / isBlank（参数校验）→ WorkflowContextSupport
  statusLabel / stepLabel 文案映射 → WorkflowStatusSupport
  isFinished 终态谓词 → WorkflowStatusSupport

已明确属于 Runtime（待抽，留到第三轮 / 第三个 Workflow）：
  create 时 token / createdAt / updatedAt 字段补齐
  run 生命周期管理（requireLogin / getOwnedRun / bindSession / markFailed）
    → WorkflowRunManager，牵涉事务与 session 绑定，单独一轮
  可预期业务拒绝 → 友好 AI 回复 + STOP（catch 块模式）

明确属于业务 Handler（留在各自 handler）：
  LOAD_ARTICLE 加载 + 作者校验（validateArticleOwner 规则本身）
  ANALYZE_ARTICLE 分析规则
  Memory / RAG 检索的参数构造与引用转换
  所有 LLM prompt 构建（含事实边界、大纲约束）
  buildContentCheck 质量检查规则（looksLikeOutline、关键词覆盖、段落数）
  approve / reject / retry 的每个状态分支（流转结构相同，分支内容全业务）
  auto-retry 的业务判定（哪些检查失败要重试、重试几次）
  buildEditorAction（AiEditorCommand 结构可复用，字段契约是文章场景）

灰色地带（本轮先留在业务，第三个 Workflow 再决定）：
  buildQualityFeedbackForModel + looksLikeOutline + getStringList
    —— "检查失败 → 转成模型反馈 → 重写" 闭环模式在两个 handler 重复，
       模板可抽，但"什么样的输出算不合格"是业务规则。
  各 status 分支的 switch 骨架
    —— approve / reject / retry 骨架结构相同，可抽成模板方法，
       但每个分支的步骤编排都是业务。
  runInitialSteps 链式调用
    —— 步骤顺序本身是业务，只有"逐步骤执行 + 记日志"是 Runtime。
```

### 抽 Runtime 的触发条件（给第三个 Workflow）

~~现在不抽。~~ 已调整：基础设施与流程引擎分开看。两个样本 100% 重复、零歧义的基础设施现在就可以抽，不违反"三样本"原则——三样本针对的是执行编排和灰色地带。

已抽出基础设施层（2026-08-13）：

```text
WorkflowContextSupport     纯工具：parseContext / toJson / getMap / getStepResults /
                          appendFeedback / normalizeRequired / isBlank / touch
WorkflowStepLogRecorder   recordStep：成功/失败/耗时落 StepLog，日志失败不影响主流程
WorkflowStepRunner        run：emit RUNNING → 计时执行 → emit SUCCESS/FAILED → 记 StepLog → 异常透传
WorkflowStatusSupport     无歧义状态语义：isFinished / isWaiting / parseStatus / parseStep /
                          statusLabel / stepLabel
```

命名约定：Support = 纯工具，Recorder = 记录，Runner = 执行，Manager = 生命周期。

第三轮抽取进度（2026-08-16 更新）：

```text
1. 可预期拒绝 → 友好回复的 catch 块模式                ✅ 已抽（2026-08-16）
   —— 实际只有优化入口一份（文档旧写"两份"），非去重而是泛化：
      WorkflowHandler.buildRejectedMessage 默认方法（null = 真异常），
      优化 Handler 实现拒绝文案；AiMessageServiceImpl 三个入口统一
      emitRejectedOrError 收口（友好回复落消息 + STOP，否则 sink.error）
2. 质量反馈闭环（buildQualityFeedbackForModel + looksLikeOutline） ✅ 已抽
   —— 第三个 Workflow（LEARNING_PLAN）时抽到 WorkflowQualitySupport
3. status 分支的 switch 骨架（approve / reject / retry 模板方法）   ✅ 已抽（2026-08-16）
   —— AbstractWorkflowHandler：模板骨架（解析状态/上下文/stepResults
      + safeEmitter → doApprove/doReject/doRetry）+ waitForConfirm /
      finish 收尾工具；确认卡片生命周期（发卡→消费清卡）收进骨架
4. WorkflowRunManager（run 生命周期管理：requireLogin / getOwnedRun /
   bindSession / markFailed 等；涉及事务与 session 绑定）            ✅ 已抽（2026-08-16）
   —— 九个生命周期方法：requireLogin / getOwnedRun /
      checkActiveWorkflowConflict / bindSessionActiveWorkflow /
      clearSessionActiveWorkflow(+BySession) / markFailed /
      clearErrorMessage / touch
   —— Manager 纯工具不做事务，事务边界在调用方 ServiceImpl 的
      @Transactional 上，mapper 操作与调用方同事务
   —— ServiceImpl 只留：create 模板、approve/reject/retry/cancel
      状态机收口、操作级日志、VO 转换
```

待抽清单 1-4 全部完成，Runtime 抽象最终分工：

```text
AbstractWorkflowHandler  = 模板骨架（approve/reject/retry final + waitForConfirm/finish）
WorkflowHandler          = 业务分支（doApprove/doReject/doRetry + 拒绝文案钩子）
WorkflowContextSupport   = 纯工具（context 解析/确认卡片/反馈历史）
WorkflowStatusSupport    = 纯工具（状态/步骤解析与文案）
WorkflowStepRunner       = 执行（单步运行 + step 日志 + emitter）
WorkflowStepLogRecorder  = 记录（步骤级日志）
WorkflowRunManager       = 生命周期（登录/归属/冲突/会话绑定/失败标记）
WorkflowHandlerRegistry  = 路由（workflowType → Handler）
LearningPlanFlowSupport  = 业务层共享（学习规划类生成/检查/保存）
```

第四个 Workflow（LEARNING_PROGRESS）的验证点（2026-08-16）：

```text
不碰 Runtime 代码，只注册 handler + 枚举 + DTO，能否完整接入？  → 能（Runtime 零改动，第二次验证）
业务层共享逻辑第一次被两个 Handler 需要时，抽 Support 是否够用？ → 够
   LearningPlanFlowSupport（三段生成流 / 生成+质量检查+auto-retry / 幂等保存 / prompt 工具），
   LearningPlanWorkflowHandler 621 → 300 行；调整模式由 context 驱动（oldPlan 有无），
   两个 Handler 调用零差异
```

第四个 Workflow 的边界记录（供后续 Workflow 参考）：

```text
- 入口路由判"调整 vs 新建"用确定性规则（调整动词 + 最新 ACTIVE 计划存在），
  不扩 LLM 分类器类别——分类器没学过的类别改 prompt 要重新调，规则漏了最多
  退回 LEARNING_PLAN 多问一轮
- "LLM 重生成带进度 + 人确认兜底"取代规则匹配迁移：旧计划（含 done）转 prompt
  文本，要求 LLM 保留已完成任务；后端零合并逻辑，确认卡片人审兜底
- saveOrUpdatePlan 三条覆盖路径：plan.id 非空 → 按 id 覆盖（校验归属 + 保留
  createdAt）；否则按 source_workflow_run_id 幂等；否则 insert。调整场景
  plan 实体带旧计划 id 即可，重试天然幂等
- 勾选接口 PATCH /api/learning-plans/{planId}/stages/{stageId}/tasks/{index}：
  改 tasks JSON 单条 done，进度仍实时聚合不存库
```

第三轮抽取的两个行为修复（测试回归抓出 / 代码审查发现）：

```text
- 创作 approve"进入填充"分支 putConfirmation 后漏 setContextJson（FILL 卡片
  不落库）——waitForConfirm 统一收尾后自然修复
- retry 成功恢复到确认态后 VO 仍带旧 errorMessage——waitForConfirm 统一
  setErrorMessage(null)（原来只有创作/优化 retry 分支手动清，属于骨架语义）
```

注意：canApprove / canReject / canRetry 之类"哪个状态能做什么"的谓词**不进** WorkflowStatusSupport——那是各 Workflow 的状态机规则（例如创作 Workflow 的 WAITING_REQUIREMENT_CONFIRM 不能 approve，只能 reject 补主题），属于流程引擎范畴，留在各 Handler 的 switch 里。

业务 Handler 保持现在的形态：prompt、质量规则、状态分支、业务校验留在 handler 内。

第三个 Workflow 的验证点（已验证，2026-08-14）：

```text
不碰 Runtime 代码，只注册 handler + 枚举 + DTO，能否完整接入？  → 能（Runtime 零改动）
审批 / 重试 / 取消 / StepLog / 前端面板是否零改动复用？        → 全复用
```

如果第三个 Workflow 需要给 Runtime 加能力，说明边界还没划对，先回本文档重新分类，再动手。

## LLM 建议、后端做裁判：高影响结构化字段校验清单

原则：

```text
LLM 可以判断"用户想做什么"（意图），但不能脑补"具体参数是什么"。
凡是会启动高影响流程的结构化字段，都必须有后端确定性校验：
LLM 给建议，后端做裁判。校验失败走追问/拒绝，不进入流程。
```

已落地：

```text
articleId
  OPTIMIZE_ARTICLE create 前 validateArticleOwner：存在性 + 作者权限。

topic
  创作入口 requirement 只用原始 message，intent.topic 完全弃用。
  主题明确性由 CreateArticleWorkflowHandler.isRequirementUnclear 确定性规则判断：
  循环剥请求框架词（我想让你/帮我/写一篇…）→ 剥空 / 太短（<4字）/ 纯虚词
  （随便/都行/东西/啥…）→ 模糊 → 停 WAITING_REQUIREMENT_CONFIRM 追问。
  次入口（fallback 重新起 Workflow、游客提示登录）同样用该规则，不信任 topic。

requirements
  随 topic 一起弃用（用户的额外要求天然在原文里，LLM 提取会丢信息/造信息）。

actionType
  fillArticle 只能由 Workflow 输出，普通聊天不允许（已有边界）。
```

待评估：

```text
categoryName：LLM 指定分类，当前兜底"随笔"，暂未发现幻觉事故。
```

背景（2026-08-13 氛围编程事故）：

```text
用户只说"我想让你帮我写一篇文章"，classifier 幻觉出 topic="氛围编程"，
入口信任 topic 当 requirement → 绕过追问直接开写。
修复不是枚举坏词（打地鼠），而是拆注入点：topic 字段不参与 requirement 拼装，
LLM 幻觉成什么词都进不来。剩下的"主题错"只可能来自原文本身，有确认环节兜底。
```

同类低风险点（已确认不修）：

```text
AiEditorDraftGeneratorImpl 的 prompt 使用 intent.getTopic()，但同时传入用户原文全文，
幻觉 topic 只是误导信号之一，且产物是供用户编辑的草稿，风险低。
```

## 后续演进：Workflow + Reasoning Action

后续可以逐步演进：

```text
固定 Workflow
  ↓
可配置 Workflow
  ↓
Workflow + Reasoning Action
  ↓
动态选择 Step / Tool
  ↓
Observation 驱动下一步
  ↓
完整 Agent
```

Reasoning Action 不是下一步立刻做。

等满足这些条件后再考虑：

- 至少两个固定 Workflow 已跑通。
- 已有 step log，可以观察每一步输入/输出。
- 已有 retry / failed / cancel / complete 的稳定生命周期。
- 高影响 tool 已经被 workflow 边界管住。

## 面试讲法

当前可以讲：

```text
我不是只做了 Tool Calling，而是做了一个会话级、可持久化的 Agent Workflow。

它通过 ai_workflow_runs 保存任务状态，通过 active_workflow_run_id 让任务跨轮继续。
文章创作流程中，AI 会先生成大纲，等待用户确认；用户可以反馈修改意见，系统会基于同一个 workflow context 重新生成。
正文生成后还会做质量检查，最后才返回 editorAction 填充编辑器。

这样可以避免普通 LLM 一步到位，也避免 Tool Calling 绕过用户确认直接操作系统。
```

现在已经可以继续讲：

```text
我还为 Workflow 增加了 step 级执行轨迹，记录每一步输入摘要、输出摘要、耗时、错误和 token 消耗。
这样可以排查 LLM / RAG / Tool 哪一步失败，也能做成本统计和审计。
```

## 技术栈能力地图

以后做任何技术栈，不只看“这个技术能不能用”，还要先想它在市场上通常解决什么问题。

例如 Redis 不只是缓存，还常见于：

```text
高并发读压力
分布式锁
限流
计数器
排行榜
热点数据
缓存穿透 / 击穿 / 雪崩
延迟队列
Session / Token 状态
幂等控制
```

RAG 也不只是向量检索，还常见于：

```text
文档切分
embedding
混合检索
rerank
query rewrite
metadata filter
references
一致性校验
评测集
权限隔离
知识更新
```

Workflow / Agent 也不只是“多步骤调用”：

```text
状态持久化
跨轮任务恢复
human-in-the-loop
step log / tracing
失败重试
任务取消
权限边界
tool action guardrail
成本统计
多 workflow 路由
```

以后做技术选型时，默认流程改成：

```text
先看这个技术的能力地图
再看当前项目已经用了哪些
再看还缺哪些关键能力
最后决定本阶段补哪些最值钱
```

这样可以避免项目只停留在“会用一点”，而是逐步把每个技术栈做厚、做完整。
