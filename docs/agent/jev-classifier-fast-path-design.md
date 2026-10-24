# Jev 分类器最小生产接入设计

> 日期：2026-10-04  
> 状态：第一版已实施并完成定向验证

## 1. 目标

使用 Jev 加速最常见、无需业务参数的只读分类请求，缩短 AI 助手首字等待时间；复杂请求继续使用现有 plus 分类器，保持当前能力和稳定性。

本阶段不追求让 Jev 覆盖大部分意图，只验证一条可回退的生产链路。

## 2. 第一版范围

Jev 只允许直通以下三类：

- `GENERAL_CHAT`：普通聊天、概念问答；`needsThinking` 仍只是建议，最终由现有 Planner 裁决。
- `ARTICLE_DETAIL_QA`：仅限当前文章详情页存在 `articleId`，且 Jev 只命中 `article_qa` 或 `article_analysis` 单目标。
- `ARTICLE_SEARCH`：仅命中 `article_search` 单目标；检索查询继续使用用户原始消息。

以下情况必须回退 plus：

- 命中两个及以上原子目标。
- 命中 Workflow、文章写动作、钱包、编辑器、导航、学习计划等非白名单目标。
- Jev 主意图与原子目标冲突。
- 必需概率低于 `0.70`。
- 文章问答缺少当前文章页面或 `articleId`。
- Jev 超时、HTTP 异常、响应缺字段、概率越界或 JSON 解析失败。

## 3. 调用链

```text
AiIntentClassifier.classify
  -> classifier-engine == PLUS_ONLY
       -> 现有 plus 分类器
  -> classifier-engine == JEV_FAST_PATH
       -> JevDecisionClient.decide
       -> JevFastPathMapper.tryMap
            -> 可安全映射：返回最小 AiIntent
            -> 不可映射：调用现有 plus 分类器
  -> 现有 TaskPlanningFacade / AgentPlannerSupport
```

Jev 只提供分类建议，不直接启动 Workflow、不执行写操作、不授权权限。生产路由仍由现有后端 Planner、白名单、页面上下文和归属校验决定。

## 4. 最小 AiIntent 映射

### GENERAL_CHAT

- `intent=GENERAL_CHAT`
- `requestKind=CHAT`
- `suggestedAction=CHAT`
- `risk=LOW`
- `confidence=primary.confidence`
- `needsThinking=Jev needs_thinking >= 0.70`

要求：无任何原子目标，且主意图为 `general_chat`、主置信度不低于 `0.70`。

### ARTICLE_DETAIL_QA

- `intent=ARTICLE_DETAIL_QA`
- `requestKind=CHAT`
- `suggestedAction=CHAT`
- `risk=LOW`
- `confidence=对应原子目标概率`

要求：只有 `article_qa` 或 `article_analysis` 一个目标；主意图与该目标一致；当前是文章详情页且 `articleId` 非空。

### ARTICLE_SEARCH

- `intent=ARTICLE_SEARCH`
- `requestKind=CHAT`
- `suggestedAction=CHAT`
- `risk=LOW`
- `confidence=article_search 概率`

要求：只有 `article_search` 一个目标，且主意图也是 `article_search`。

## 5. 配置与回退

新增独立配置：

```yaml
blog:
  ai:
    classifier-engine: JEV_FAST_PATH
```

枚举值：

- `PLUS_ONLY`：完全使用当前 plus 分类器。
- `JEV_FAST_PATH`：先尝试 Jev 三类快速路径，不满足条件时回退 plus。

现有 `blog.ai.classifier-mode=FULL|ON_DEMAND` 保持不变，只控制 plus 分类器内部 Prompt，不能与分类引擎开关合并。

生产 Jev Key 继续从 `JEV_API_KEY` 读取，不写入仓库配置。

## 6. 超时与失败语义

- Jev 单次调用，不在应用层额外重试。
- 连接超时 `500ms`，总请求超时 `1200ms`。
- 所有 Jev 失败均 fail-open 到 plus，不降级为普通聊天。
- plus 仍沿用现有 repair 和最终 `GENERAL_CHAT` 降级语义。

因此 Jev 故障只增加至多约 `1.2s` 的前置等待，不改变最终业务路线。

## 7. 可观测性

每次分类记录一条结构化日志：

- `engine=JEV|PLUS`
- `jevOutcome=accepted|fallback|error`
- `fallbackReason`
- `jevDurationMs`
- `acceptedGoals`
- `totalClassifierDurationMs`

第一版不新增数据库表，不保存完整用户原文或 Jev 原始响应。
Jev 返回的 `input_tokens` / `output_tokens` 必须计入现有 `TokenUsageAccumulator`，保证调用次数和分类消耗不漏账；若随后回退 plus，两次调用都要累计。

## 8. 验收标准

- 三类白名单请求能由 Jev 返回最小 `AiIntent`，并继续通过现有 Planner。
- 复合请求、写操作、Workflow 和缺少上下文的文章问答全部调用 plus。
- Jev 超时、异常、坏响应全部调用 plus。
- `PLUS_ONLY` 下不发起任何 Jev 请求，行为与当前版本一致。
- 不运行 67 条完整在线门；只跑 Jev 映射、回退和分类入口的定向测试，再选 3 至 5 条真实请求做在线验证。

## 9. 后续扩展条件

只有第一版生产观测稳定后，才讨论扩大 Jev 覆盖。扩展顺序优先考虑后端已有确定性参数解析的能力；需要模型摘录原文证据、生成 `taskGoals` 或定位学习计划的请求，必须单独设计，不能仅凭概率阈值直接放行。

## 10. 实施结果（2026-10-04）

- 生产链路已接入：`JEV_FAST_PATH -> 确定性白名单映射 -> 其余回退 plus`。
- 一键回退：`BLOG_AI_CLASSIFIER_ENGINE=PLUS_ONLY`，现有 `BLOG_AI_CLASSIFIER_MODE` 继续只控制 plus Prompt。
- 离线定向回归：协议、HTTP Gateway、映射、入口回退、token 账本、既有 Prompt 开关和 Workflow 回填共 `36/36` 通过。
- 真实 API 定向样例：`5/5` 通过，耗时为 `364/695/728/763/860ms`，P50 `728ms`、P95 `860ms`。
- 启动验证：Spring 注入成功，日志确认 `classifierEngine=JEV_FAST_PATH`、`apiKeyConfigured=true`；`/actuator/health` 返回 `UP`。
- 后端已在验证后关闭，`8080` 已释放。

本次没有运行 67 条 plus 分类器完整在线门，也没有宣称复杂请求延迟已经下降；复合目标、Workflow 和写操作当前仍会在 Jev 之后回退 plus。

## 11. 修订：学习类 Workflow 移出快速路径（2026-10-18）

**触发事故**：用户「讲讲我的 Redis 学习计划」「讲一讲我的 Redis 学习计划」被 Jev 以 `workflow_learning_plan` 0.83 / 0.76 直通，Planner 直接拉起 `LEARNING_PLAN` 工作流（生成新计划确认卡 + 真实计费）。用户实际诉求是"看/讲已有计划"，不是制定。完整事故与根因见 `docs/agent/学习域只读路由修复设计.md`。

**新边界**：

- 快速路径只保留 **只读意图**（`general_chat` / `article_qa` / `article_analysis` / `article_search` / `learning_plan_query`）与**文章域两个 Workflow**（`workflow_create` / `workflow_optimize`，动词明确且与只读意图分开判定）。
- `workflow_learning_plan` / `workflow_learning_progress` / `workflow_learning_assist` **一律回退 plus**（Mapper 返回 `UNSUPPORTED_GOAL`），由 plus 的语义分界裁决。
- 回归锁：`JevFastPathMapperTests.learningWorkflowGoalsAlwaysFallBackToPlus`、`JevWorkflowHandoffTests.jevLearningPlanNeverReachesPlannerDirectly`。

**原判断被推翻**：本文件 §2 认为"确认型 Workflow 判错最多多问一次"——实测代价是**多跑一次 17 秒工作流 + 一张错误的计划确认卡 + 真实计费**，且用户会以为系统要重建计划。判错代价不对称原则仍然成立，但学习域写工作流的"判错代价"此前被低估。

**代价**：这三类请求多一次 plus 分类（本机实测约 6s；工作流本身约 17s）。文章域 Workflow 的快速路径不受影响。

