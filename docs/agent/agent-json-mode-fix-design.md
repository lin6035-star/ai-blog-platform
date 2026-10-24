# 学习计划生成失败：模型漏掉 JSON 收尾括号

> 创建：2026-09-13
> 定位：修复日志里的「学习计划生成失败：模型返回格式错误」——**不是偶发抖动，是约 57% 概率的模型输出缺陷**
> 前序：`agent-live-status-and-query-regex-design.md`（同一次实测暴露的另一半）

## 一、现象

后端日志只留下一行：

```text
[PERF-WORKFLOW] step_end runId=2099123063921606658 step=GENERATE_PLAN
  status=FAILED durationMs=79941 error=学习计划生成失败：模型返回格式错误
```

紧接着同一步骤自动重试，92 秒后成功。用户侧的感受是「等了三分钟，中间还不知道发生了什么」。

**最麻烦的是排查条件**：原始输出没有任何地方留存——`parsePlanJson` 抛异常时不记录正文，
`ai_workflow_step_logs` 只存摘要（"步骤完成，耗时 108757ms"），token 列全是 0。
**日志里只有「格式错误」四个字，没有任何线索**，只能靠复现。

## 二、排查过程

### 1. 日志里的规律

| 模式 | prompt 字符 | 输出字符 | 结果 |
|---|---|---|---|
| **新建计划** | 204 | 626 / 589 / 730 / 895 | 4/4 成功 |
| **调整计划** | 1584–2749 | 1764–2592 | 7 次中 **2 次失败** |

失败集中在**调整模式**——prompt 要塞进完整旧计划（6 阶段 31 任务）+ 上轮 Agent 建议方向 +
用户记忆 + RAG 参考，输出天然长。新建模式输出短，从不进入这个区间。

### 2. 重建当时那条 prompt

从 `ai_workflow_runs.context_json` 取真实数据（`goal` / `oldPlan` / `handoff` /
`memoryContext` / `ragContext`），按 `buildPlanUserPrompt` 的逻辑重建：

- `systemPrompt` **324 字符，与日志逐字符一致** ✓
- `userPrompt` 2744 vs 日志 2749（差 5 字符，结构一致）

### 3. 对照组实验（直连 DashScope）

同一份 prompt，唯一变量是 `response_format`：

| 组 | 样本 | 失败 | 失败率 |
|---|---|---|---|
| 现状（不传 `response_format`） | 7 | **4** | **57%** |
| JSON Mode（`response_format: json_object`） | 7 | **0** | **0%** |

### 4. 排除网络截断

失败那一条流的最后三个事件：

```text
"false}]}"   →   finish_reason: stop   →   data: [DONE]
completion_tokens=4274（远未触顶）
```

**流是完整结束的**（收到 `[DONE]`）、**token 没到上限**、**模型自己宣告说完了**（`stop`）。
所以不是连接中断、不是长度截断——**是模型漏写了收尾**。

## 三、根因

`qwen3.6-plus-2026-04-02` 在「长 prompt + 长 JSON 输出」场景下，生成到最后一个任务后
**漏掉最外层收尾 `]}`**，而 `finish_reason` 仍是 `stop`。

四个失败样本的错误**完全一致**：

```text
Expected ',' or ']' after array element in JSON at position <末尾>
输出长度：2253 / 2325 / 2430 / 2649
```

**顺带查清的另一个现象**：这个模型是**思考型**——先产出 3000–16000 字符的 reasoning，
再输出正文。这就是日志里 `llm_first_chunk delayMs=61522`（首字等 61 秒）的来源。

## 四、修复

| # | 改动 | 位置 |
|---|---|---|
| 1 | `LlmStreamCaller` 加 `jsonMode` 开关（**重载**，现有调用点零改动） | `ai/workflow/LlmStreamCaller.java` |
| 2 | 学习域两处纯 JSON 出口开启：`GENERATE_PLAN` + `GENERATE_TASKS` | `ai/workflow/LearningPlanFlowSupport.java` |
| 3 | `parsePlanJson` 失败时记录**原文头尾各 200 字符** | 同上 |

**为什么只开这两处、不全局开**：

- 文章域的大纲 / 草稿 / 重写是 **Markdown 输出**，开 JSON Mode 会直接坏掉
- `GENERATE_TASKS`（难点拆解）与 `GENERATE_PLAN` **同源**——都是「纯 JSON 输出 → `parsePlanJson`」，
  输出变长时同样会中招，所以一并开

**为什么保留自动重试**：JSON Mode 是降低概率，不是数学保证（7 次样本推不出 100%）。
重试仍是最后防线——只是现在不该再有 57% 的第一次失败。

## 五、验证

| 项 | 结果 |
|---|---|
| 编译 | ✓ |
| 相关测试 **55/55 绿** | `LlmStreamCallerTests` 3/3（含**新增 2 例**：锁 JSON Mode 参数进 options / 反向锁 Markdown 路径不带参数）· 学习域 4 类 43/43 · 文章域 2 类 9/9 |
| 对照组实验（关闭 / 开启 JSON Mode） | **57% 失败 → 0%**（各 7 样本，同一 prompt 唯一变量） |
| 手测 | 待姐夫验证（重启后端 → 同一会话走「调整计划」流程） |

**测试没跑全量**（按约定只跑相关类）：`LearningAssistWorkflowServiceTests` 单类 174.8 秒、
`LearningProgressWorkflowServiceTests` 213.5 秒、`LearningPlanWorkflowServiceTests` 81.8 秒——
学习域测试慢是因为它们跑真实 Spring 上下文。

## 六、边界与遗留

- **样本量**：7 vs 7 足以看出趋势，但**不能据此宣称 JSON Mode 是 100% 保证**
- **质量影响未知**：JSON Mode 下 reasoning 明显缩短（有的从 16000 → 1267 字符），
  **首字会快很多，但计划质量是否下降需要手测确认**
- **同源风险未清（本次无证据，未动）**——这些也是「LLM 输出 → Jackson 解析」的同一模式：

  | 位置 | 说明 | 本次不动的理由 |
  |---|---|---|
  | `LlmAgentStepDecider` | Agent 决策器 | 输出短（<500 字符），从不进入长输出区间；且是 Runtime 核心，改动影响面大 |
  | `ArticleEvidenceVerifier` | 证据收敛门 | 同上 |
  | `CreateArticleWorkflowHandler:813` | `cleanMarkdown` 抽 JSON | 模式不同（从 Markdown 里捞结构） |
  | `ArticleOptimizeWorkflowHandler` | `GENERATE_OPTIMIZATION_PLAN` | **确认是纯 Markdown 输出**（"只输出 Markdown"），**不该开** |

- **诊断日志只在学习域**：`parsePlanJson` 是学习域工具，上面这些出口另有路径

## 七、这次排查值得记的两点

1. **「靠证据不靠猜」的完整闭环**：从日志规律 → 重建真实 prompt（精确到字符）→ 对照组实验 →
   排除法（`[DONE]` + `finish_reason` + token 数）→ 才下结论。
   全程没有一步是「我觉得应该是……」。
2. **可观测性缺口本身就是 bug**：这次的痛苦全在「日志只有四个字」。
   **失败时不记录原始输出**，等于每次排查都要重新复现一遍——修复 #3 比修复 #1 更长效。
