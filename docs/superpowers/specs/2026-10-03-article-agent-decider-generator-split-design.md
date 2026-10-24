# Article Agent Decider / Generator 拆分设计

> 日期：2026-10-03  
> 状态：已实施，定向离线回归通过  
> 范围：仅 Article Agent；学习域、通用域、钱包域、Workflow 与分类器不改

## 1. 目标

当前 Article Agent 的 `LlmAgentStepDecider` 同时承担两件事：选择下一步动作，以及在
`FINAL_ANSWER` 时生成完整正文。完整正文随后才交给 `ArticleEvidenceVerifier`；被拒绝的草案会被丢弃，
用户也要等整个 Agent run 完成后才能看到由后端分块模拟的正文。

本批把判断和生成拆开：

1. Decider 只选择动作；选择 `FINAL_ANSWER` 时只给一份精简 `answerPlan`。
2. Verifier 检查 `answerPlan` 中准备表达的结论是否被现有证据支撑。
3. 只有 Verifier 放行后，普通生成模型才根据目标、证据和已验证要点生成最终正文。
4. 直接 Article Agent 请求把生成 chunk 实时推给 SSE；Task Orchestration 子 run 第一版保持缓冲。

预期直接消除“判断模型先写一篇长文，Verifier 再丢弃”的主要浪费，并让直接 Agent 的正文首字不再等待
整篇生成结束。

## 2. 非目标

- 不推广到 Learning Agent、General Agent 或 Wallet Agent。
- 不改意图分类器、路由协议、`AgentStepActionType` 枚举或数据库结构。
- 不修改前端 SSE 事件类型；仍使用现有 `DATA`、`AGENT_STEP` 和 `STOP`。
- 不让 Generator 调用工具或决定下一步动作。
- 不在本批解决 memory 底层取消、rerank HTTP timeout 或分类器阻塞 SSE 的问题。
- 不让 Task Orchestration 子 run 把正文 chunk 直接混入外层 `DATA`；该链路仍由
  `TaskResultRenderer` 在任务完成后统一汇总，避免重复正文和步骤顺序错乱。

## 3. 方案选择

### 3.1 采用：要点预审后流式生成

```text
Article Decider
  -> FINAL_ANSWER + answerPlan
  -> Article Evidence Verifier
  -> SUFFICIENT
  -> Article Answer Generator
  -> chunk -> direct Agent SSE DATA
  -> 完整正文 -> step / run / message / conclusion anchor
```

Verifier 审核的是生成前的结论要点，而不是已经展示给用户的正文。Generator 只能展开已验证要点，
不得新增事实性结论。

### 3.2 不采用：完整草案审核后伪流式

继续先生成完整草案、审核通过后再分块发送，可以保留逐字审核，但无法改善正文 TTFT，仍然是缓冲输出。

### 3.3 不采用：正文先流出、结束后再审核

Verifier 无法撤回已经展示的内容。该方案会让证据门从执行闸退化成事后日志，不符合现有质量边界。

## 4. 输出协议

Article Decider 的 `FINAL_ANSWER` 输出从：

```json
{
  "actionType": "FINAL_ANSWER",
  "input": {
    "answer": "完整回答正文"
  }
}
```

改为：

```json
{
  "actionType": "FINAL_ANSWER",
  "input": {
    "answerPlan": "结论：结构清晰；依据：标题层级和正文预览；建议：补充缓存失效边界"
  }
}
```

`answerPlan` 是 1-5 条简短结论要点，不是给用户看的完整正文。它必须：

- 只包含准备在最终回答中表达的事实性结论和建议方向；
- 不复述文章 ID、用户 ID、动作枚举或系统机制；
- 不写寒暄、展开解释或 Markdown 长文；
- 后端最多保留 1200 个字符，防止模型再次把完整正文塞回判断协议。

Article 模式的正常 prompt 和 repair prompt 都声明该字段。其他领域继续使用 `input.answer`，不改变共享
`AgentStepDecision` record，也不改变它们的既有测试夹具。

若 Article Decider 返回 `FINAL_ANSWER` 但 `answerPlan` 为空，按无效决策处理，不能绕过 Verifier 直接生成。
第一版复用现有一次 repair 机会；repair 后仍缺字段则走现有“决策无效”失败出口。

为让这条约束真正进入现有 repair 流程，`LlmAgentStepDecider` 增加两个受保护钩子：

```java
protected AgentStepDecision normalizeDecision(AgentStepDecision decision) {
    return decision;
}

protected boolean isDecisionShapeValid(AgentStepDecision decision) {
    return true;
}
```

首轮和 repair 轮解析后都先 normalize、再 validate。`ArticleAgentStepDecider` 覆写它们：

- 非 `FINAL_ANSWER` 原样通过；
- `FINAL_ANSWER.answerPlan` trim 后为空则判无效，触发 repair；
- 超过 1200 字符时后端裁剪到 1200 字符并记录 outcome，不把长文本继续送入 Verifier / Generator。

默认实现保持原样，保证 Learning / General 的输出协议与 repair 行为不变。

## 5. Verifier 语义

结构规则保持不变：

- 目标文章未成功读取时仍返回 `NEED_MORE(ARTICLE_CONTENT)`；
- 零观察的非证据型收尾仍可规则放行；
- 没有新增证据时仍由 evidence revision 门提前收敛；
- `NEED_MORE`、`ASK_USER`、`SUFFICIENT` 和 fail-open 语义不变。

语义闸的第三段输入由“回答草案”改为“拟回答要点”：

```text
【目标】...
【已收集证据】...
【拟回答要点】...
```

Verifier 只判断要点是否有证据，不评价文笔，也不生成正文或选择动作。`NEED_MORE` 仍只给缺失类型和原因，
后续补查仍由 Decider 决定。

为兼容现有持久化测试和回滚路径，Verifier 提取顺序为 `answerPlan -> answer -> message`；生产 Article prompt
只产生 `answerPlan`。兼容字段不是新协议，不写回 prompt。

## 6. Generator 边界

新增 Article 专用 `ArticleAnswerGenerator`，职责只有“把已验证要点展开为正文”：

- 使用普通生成模型，不应用 `blog.ai.judge-model`；
- 不注册任何 Tool Callback；
- system prompt 通过 `PromptTrustBoundary`，把目标、文章、Memory 和 RAG 内容视为不可信数据；
- 输入只包含目标、结构化证据和 `answerPlan`；
- 明确要求不得引入 `answerPlan` 与证据之外的新事实，证据缺失处直接说明无法判断；
- 输出纯中文正文，不输出 JSON、代码块或系统字段；
- 使用显式输出上限，第一版固定 `maxTokens=1600`，不新增配置项。

Generator 返回：

```java
record GeneratedAnswer(String content, TokenUsageAccumulator usage, boolean deliveredAnyChunk) {}
```

调用方始终以 `content` 作为唯一权威正文。SSE chunk 只是实时展示副作用，不能反向拼成数据库权威值。

### 6.1 流式可靠性

- 单次生成超时 60 秒。
- 首 chunk 前遇到 429、网络或 timeout，最多重试两次，沿用现有 LLM 错误分类和退避规则。
- 是否允许重试按“是否已经向外部 SSE 交付 chunk”判断，不按模型是否产生过内部 chunk 判断：
  Task Orchestration 的默认 no-op 没有向用户交付正文，中途失败仍可从头重试；直接 Agent 已交付后禁止重试。
- 空响应按“尚未向外部交付正文的生成失败”处理：step/run 标记失败；直接 Agent 可由外层发送并保存现有
  友好兜底，Task Orchestration 则按失败步骤汇总；两者都不写结论锚。
- 已输出部分正文后失败时，先把终态 step 和 run 标记为失败，再抛出专用流异常让外层终止 SSE；
  不把不完整正文、兜底文案或 `STOP` 保存成一次成功回答，也不写结论锚。

本批不抽取 Workflow 的 `LlmStreamCaller`。它绑定 `AiWorkflowStep` 与 Workflow emitter，强行复用会让 Agent
依赖 Workflow 协议。两者可以共享错误分类和 Token 峰值算法，但保持各自的领域适配器。

## 7. Runtime 数据流

共享 `AbstractAgentRuntime` 增加受保护生成钩子，默认实现仍从 `decision.input.answer/message` 取正文；
只有 `ArticleAgentRuntime` 覆写并调用 `ArticleAnswerGenerator`。因此 Learning / General / Wallet 行为保持原样。

Article `FINAL_ANSWER` 的顺序固定为：

1. 检查上次 Verifier 拒绝后是否有对应 evidence revision 增量；没有则 `SKIPPED` 并保守收尾。
2. 用 `answerPlan` 调用 Verifier；`NEED_MORE` / `ASK_USER` 沿用现有分支，Generator 不调用。
3. Verifier 放行后调用 Generator。
4. Verifier 放行后先插入 `FINAL_ANSWER RUNNING` step 并发实时事件，再调用 Generator。
5. Generator 正常完成后把同一 step 更新为 `SUCCESS`，再统一 `finish()`。
6. run 成功落库后执行 `onAnswerConcluded`，结论锚保存完整生成正文。

不得在 Generator 正常结束前发送 `FINAL_ANSWER SUCCESS`，否则中途失败会留下“步骤成功、run 失败”的矛盾审计。
Generator 失败时更新已经存在的 `RUNNING` step 为 `FAILED`，不另插一行，保证 `(runId, stepNo)` 唯一且
`usedSteps` 与 step 表一致。

### 7.1 Token 和耗时

- Decider usage 继续按现有位置累计到 run。
- Generator 使用独立 `TokenUsageAccumulator`，生成结束后单独累计到 run，避免再次累加 Decider usage。
- 写 step 前把 Generator usage 合并进当前 `stepUsage`，因此终态 step 同时包含判断和生成成本。
- `ai_agent_steps.duration_ms` 从 Decider 开始，覆盖 Verifier 和 Generator 完成时间。
- `AgentRunResult.totalTokens`、父子 run subtree 聚合和 `ai_messages.token_count` 沿用现有口径，无第二份账。
- 新增 `[PERF-AI] stage=agent_generation`，只记录 runId、stepNo、TTFT、总耗时、chunk 数、字符数、signal；
  不记录目标、要点、证据或正文。

## 8. SSE 接线

`AgentStepEmitter` 保持函数式接口，新增默认方法：

```java
default boolean emitAnswerChunk(String chunk) {
    return false;
}
```

返回值表示该 chunk 是否已经交付到外部响应。直接 Agent 链路的匿名 emitter 覆写该方法，把 chunk 映射为
现有 `DATA` 事件、返回 `true`，并记录本次是否已经实时发送正文。
Runtime 返回后：

- 已实时发送正文：不再执行 `splitIntoChunks(agentReply, 32)`；
- 未实时发送正文：继续旧分块路径，覆盖其他领域、兜底文案和非流式失败出口。

消息落库仍使用 `AgentRunResult.finalAnswer()`，不从 SSE chunk 反向组装。

`ArticleAnalysisExecutor` 当前用 lambda 构造 `AgentStepEmitter`，默认 `emitAnswerChunk` 为 no-op：Generator 仍会
在内部收集完整正文，子 run 返回后由 `TaskResultRenderer` 统一汇总。这样第一版不会出现：

- 子步骤正文提前混入外层结果；
- 编排结束后同一正文再发一次；
- 后续步骤还没执行，用户先看到看似已经完成的总答复。

## 9. 失败与回滚

- Verifier `NEED_MORE`：不调用 Generator，继续补查。
- Verifier `ASK_USER`：不调用 Generator，沿用等待或 analysis-only 保守收尾。
- Verifier 异常：继续保持 fail-open，然后调用 Generator。
- Generator 在向外部交付首 chunk 前最终失败：当前步和 run 失败；直接 Agent 返回现有友好兜底，
  Task Orchestration 按失败步骤汇总。
- Generator 已交付输出后失败：更新 step/run 为失败并抛出 `AgentAnswerStreamException`；
  `runWithPolicy` 不把它吞成普通 `AgentRunResult`，`AiMessageServiceImpl` 捕获后只结束错误流，
  不进入 assistant message、Memory、Summary 和 `STOP` 的成功落库链。
- 旧领域默认生成钩子保持不变；回滚 Article 覆写和 prompt 字段即可恢复旧链路。

不增加数据库列、SSE 事件类型和前端数据结构。

## 10. 验收

### 10.1 离线行为

- Article prompt 的 `FINAL_ANSWER` 只声明 `answerPlan`，不再要求完整 `answer`。
- Learning / General prompt 仍声明 `answer`，逐字基线不变。
- `NEED_MORE` 时 Generator 调用次数为 0。
- 无 evidence revision 增量的第二次 `FINAL_ANSWER` 不进 Verifier，也不进 Generator。
- 补到对应新证据后，Verifier 可再次放行，Generator 只调用一次。
- Generator 正常返回时，step/run 保存完整正文，结论锚收到同一正文。
- Generator usage 同时进入终态 step、run 和最终 message 口径，且不重复累计 Decider usage。
- 直接 Agent 的 chunk 在 `runtime.run()` 返回前已经产生，返回后不重复发送。
- Task Orchestration 的分析子 run 不实时发送内层正文，最终汇总仍只出现一次。
- 流在首 chunk 前失败可重试；首 chunk 后失败不可重试且不保存成功答案。

### 10.2 验证范围

优先运行受影响的定向离线测试，不运行无 `-Dtest` 的全量测试：

- `ArticleDeciderPromptTests`
- `ArticleAgentStepDeciderTests`
- `ArticleEvidenceVerifierTests`
- `ArticleAgentRuntimeVerifierTests`
- `ArticleAgentRuntimeDecisionContextTests`
- `ArticleAgentRuntimeTests`
- `ArticleAnalysisExecutorTests`
- Agent SSE 对应的 `AiMessageServiceImpl` 测试
- 新增 `ArticleAnswerGeneratorTests`
- Learning / General Runtime 与 Decider 回归测试

真实模型验证只跑 3-5 条 Article 定向样例，并且必须另行取得确认；不运行 67 条完整分类门，因为本批不改分类器。

## 11. 成功指标

上线观测至少比较同类型 Article Agent 请求：

- `agent_decider` completion tokens 显著下降；
- 被 Verifier 拒绝的轮次 `agent_generation` 调用数为 0；
- 直接 Article Agent 的正文 TTFT 从“整个 run 完成后”变为 Generator 首 chunk 时间；
- `FINAL_ANSWER` 重复正文、Token 重复累计、结论锚缺失均为 0；
- 回答质量定向样例不得出现超出 `answerPlan` 或证据的新事实。

本批不预设具体百分比门槛。先由 `[PERF-AI]` 日志采集同模型、同类请求数据，再决定是否推广到其他域。

## 12. 实施结果（2026-10-03）

- Article Decider 的 `FINAL_ANSWER` 已改为有界 `answerPlan`，Verifier 优先审核计划，普通模型只在放行后生成正文。
- 直接 Article Agent 已使用现有 `DATA` 事件实时发送 Generator chunk，Runtime 返回后不再重复分块；Task Orchestration 子 run 保持缓冲。
- Generator 失败会原位更新终态 step；已部分交付时以错误流结束，不保存 assistant 消息、不运行 Memory / Summary，也不发送 `STOP`。
- Spring 上下文测试额外发现并修复 `ArticleAnswerGenerator` 双构造器未明确注入导致的启动失败。
- 最终定向验证覆盖 12 个测试类、159 条测试，失败 0、错误 0；未运行全量测试、在线模型评测或 67 条分类门。
