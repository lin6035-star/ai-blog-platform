# 学习域 Agent 结构化上下文设计

## 目标

学习域 Agent 当前每轮决策都会把历史 observation 文本重新放入 prompt。学习计划详情、记忆摘要和 RAG 结果会重复出现，导致输入 token 随步骤增长。

本阶段把文章域已经验证的 `AgentRunState` 模式迁移到学习域：执行器继续保留原有展示文本，同时返回结构化事实；状态对象负责去重、预算裁剪和反馈生命周期；决策器只消费压缩后的状态上下文。

## 边界

- 只改学习域 Agent 的决策上下文，不改动作白名单、权限校验、确认门、写执行器或 Workflow 路由。
- `observations` 继续完整落库并用于审计、开发者面板和最终收尾；结构化状态只作为模型上下文。
- 不把记忆提取、情景记忆和会话摘要的后台 token 写入 `ai_messages.token_count`，这些调用仍保持日志观测口径。
- 不新增数据库列、usage 明细表、钱包结算或历史数据回填。
- 通用域、钱包域本阶段继续使用 observation 回放。

## 结构化事实

`LearningAgentActionExecutorImpl` 为三个只读动作返回 `AgentActionResult(summary, payload)`：

| 动作 | payload 事实 |
| --- | --- |
| `QUERY_LEARNING_DASHBOARD` | `status`、`planRef`、`planCount`、`activePlanCount`、`matchedCount`、`selectedPlanId` |
| `QUERY_MEMORY` | `status`、`query`、`hitCount` |
| `SEARCH_RAG` | `status`、`keyword`、`hitCount` |

`summary` 保持现有中文输出，不作为结构化事实的来源。失败、无命中和部分命中必须通过 `status` / 计数区分，不能让状态对象猜测中文文案。

## 状态归约

新增 `LearningAgentState implements AgentRunState`，按以下槽位保存事实：

- 学习计划：按查询目标和选中计划 ID 去重，最新结果替换同键旧结果。
- 记忆：按查询词去重；成功命中保存事实，空结果记录已查询，部分失败保留已有命中并提示仍可重试。
- RAG：按关键词去重；成功命中保存事实，空结果记录已查询。
- 操作记录、缺失证据、最近失败和系统反馈：复用文章域的生命周期语义。

决策上下文使用固定槽位预算，并优先保留每个槽位的最新事实。第一步没有事实时返回空字符串，避免引入额外 token。Verifier 和 summary 的默认接口继续有效；学习域当前没有文章域证据 Verifier，因此只需保证 `verificationEvidence` / `summaryContext` 与决策状态一致。

## 错误与兼容

- 执行器仍抛出原有不可恢复异常，由 `AbstractAgentRuntime` 统一记录 FAILED step。
- 记忆和 RAG 的已有局部失败仍返回可展示摘要；payload 标为 `PARTIAL_FAILURE` 或 `FAILED`，状态对象据此保守处理。
- payload 缺失时状态对象不解析 `summary`，只把动作记录为已执行，保证旧测试替身和其他域不会被新逻辑反向影响。
- 状态对象是每个 run 新建的局部对象，不能放到 Runtime 单例字段。

## 验收

1. 学习域 Runtime 为每个 run 创建 `LearningAgentState`。
2. 同一查询重复执行时，第二轮决策上下文不重复追加完整结果。
3. 新查询结果能替换同键旧结果，且槽位总长度受预算限制。
4. 失败、空结果、部分失败不会被状态误判成成功证据。
5. 学习域原有展示文本、步骤落库和最终回答路径保持不变。
6. 定向测试覆盖状态归约、执行器 payload 和 Runtime 第二轮上下文；不运行全量 Maven 测试或在线评测。

## 修订：payload 必须带内容（2026-10-18）

手测「帮我分析我的Agent学习计划，看看有没有问题」暴露本设计的缺口：上面的 payload 表只列了**元数据**，
而决策上下文又不回放 `summary` → 模型看不到计划的阶段/任务，只能回答"当前观察仅包含计划元数据"，
随后反复查询同一计划被去重跳过。

修订后的 payload 契约（**槽位预算不变**，内容有界 + `…（后略）`）：

| 动作 | 新增内容字段 | 上限 |
| --- | --- | --- |
| `QUERY_LEARNING_DASHBOARD` | `planDetail`（标题/状态/总进度/阶段任务 [x]/[ ]）+ `planCandidates`（未命中或歧义时的候选） | 1400 / 500 字符 |
| `QUERY_MEMORY` | `memoryContent`（命中的常驻/语义/情景记忆行） | 500 字符 |
| `SEARCH_RAG` | `ragContent`（标题+文章ID+片段） | 700 字符 |

内容仍由**结构化对象直接构造**（不解析 summary），符合本设计"summary 不是事实来源"的约束。
事故、根因与验证见 `docs/agent/学习域只读路由修复设计.md` §9；
回归由 `LearningAgentActionExecutorTests` / `LearningAgentStateTests` 锁住。
