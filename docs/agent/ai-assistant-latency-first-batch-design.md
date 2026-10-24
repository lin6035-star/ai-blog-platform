# AI 助手延迟治理：第一批设计

> 日期：2026-10-03  
> 状态：第一批、Decider / Generator 拆分、Memory 背压与前端等待态已实施；第二批第一刀（单目标文章搜索确定性直出）因质量退化已撤回  
> 范围：登录态隔离、关键分段耗时、Article Agent 无新证据空转收敛

## 1. 结论

第一批已处理三件有直接证据、能够独立验收的问题：

1. 修复 `UserContext` 在线程复用和 SSE 异步转交下的残留风险。
2. 补齐分类、记忆召回、rerank、生成首字、Agent Decider 和 Verifier 的真实耗时。
3. Article Agent 被 Verifier 判 `NEED_MORE` 后，如果证据版本没有变化，不允许再次进入
   `FINAL_ANSWER` 验证循环。

本批没有改分类器输出协议、模型、Workflow Prompt 或前端协议，也没有拆分 Decider 与最终正文生成。
这样先补上可信基线，并把“Verifier 拒绝后持续生成终稿直到 maxSteps”的空转收敛，而不叠加 67 条
分类契约和四域 Agent 协议的回归风险。

### 1.1 实施结果

- 两个 JWT 拦截器都在请求入口清理残留 `UserContext`，并在 SSE 异步转交时清理原 servlet 线程。
- 新增单调时钟 `AiStageTimer`；分类、记忆、rerank、生成、Agent Decider、动作执行和 Verifier 均有
  不含业务正文的 `[PERF-AI]` 分段日志。
- `ai_agent_steps.duration_ms` 的公共 Runtime 路径从 Decider 开始计时，不再把普通终态固定记为 0。
- Article Agent 按缺失证据类型维护 revision。原先最坏的 6 步重复终稿场景收敛为 2 步：
  第一次 `FINAL_ANSWER` 被 Verifier 拒绝后记 `FAILED`，第二次在无证据增量时记 `SKIPPED` 并保守收尾；
  Verifier 只调用一次。
- 本批没有删除或改写工作区中原有的 RAG 评测、候选选择和 trace 改动。

## 2. 已核实问题

### 2.1 登录态残留风险

实施前，`OptionalJwtInterceptor.preHandle()` 在请求没有 `Authorization` 时直接放行，没有先清理
`UserContext`。SSE 使用异步请求处理，两个拦截器当时也没有在异步转交时清理原 servlet 线程。

这证明代码存在串身份条件，但不证明 2026-10-03 的诊断会话已经由该问题产生。Claude 创建的
5 个会话使用的是合法签名的本地测试 JWT，应与代码风险分开处理。

### 2.2 耗时数据口径不完整

实施前只能看到总耗时和部分动作耗时，以下阶段缺少统一、可关联的耗时：

- 意图分类器一次调用及 repair / plan locator 子调用；
- 长期记忆、情景记忆各自耗时、结果状态和整体等待时间；
- RAG 初检已有 `[RAG-TRACE]` 分段，但模型 rerank 自身缺少独立耗时和 fallback outcome；
- 正文生成从调用开始到首 chunk 的 TTFT；
- Agent 每轮 Decider；
- Article Evidence Verifier；
- Agent step 的总耗时。

`ai_agent_steps.duration_ms` 原先只表示动作执行器耗时。终态和被 Verifier 拒绝的步骤固定写 0，
不能继续把它解释为“一整步耗时”。

### 2.3 Article Agent 无效循环

原循环先让 Decider 生成完整 `FINAL_ANSWER`，再交给 Verifier。Verifier 返回 `NEED_MORE` 后，
骨架追加反馈并继续下一轮，但没有记录“拒绝时证据版本”，也没有要求下一次终稿前必须取得新证据。

原 `unresolvedRejectionAtMaxStepsShutsDownConservatively` 测试只锁住“最终不会无依据硬答”，却允许
Decider 连续生成终稿直到耗尽步数。它保证答案安全，不保证时间和 Token 收敛。

## 3. 方案比较

### 方案 A：小批次、先收敛再拆模型（采用）

先修上下文隔离和观测，再给 Agent 增加证据版本门。后续单独拆 Decider / Generator，并独立跑四域
回归与真实模型评测。

优点：改动边界清晰，失败容易归因，不需要本批触发完整分类器在线门。缺点：第一批只能消除循环，
不能彻底消除 Decider 单次生成长文的成本。

### 方案 B：一次拆完分类器、Decider、Generator

同时改分类器紧凑协议、Agent 动作协议和生成模型。

优点：理论收益最大。缺点：同时改路由契约和状态机，任何回归都难定位；还必须跑完整在线评测，
不适合作为第一批。

### 方案 C：只加前端“正在理解需求”

开发量最小，但只改善等待体感，不减少任何后端耗时、Token 或空转，不能作为性能修复。

## 4. 设计

### 4.1 `UserContext` 生命周期

#### 行为

- `OptionalJwtInterceptor.preHandle()` 和 `JwtInterceptor.preHandle()` 一进入就执行 `UserContext.clear()`。
- 可选鉴权无 Header、非法 token、已撤销 token 都保持 `UserContext` 为空。
- 可选鉴权和强制鉴权拦截器实现 `AsyncHandlerInterceptor`；进入异步处理后，在
  `afterConcurrentHandlingStarted()` 清理原 servlet 线程。
- `afterCompletion()` 继续保留，覆盖同步请求和异步二次分发结束路径。
- 业务异步任务必须显式捕获 `userId`，不允许依赖跨线程的 `UserContext`。

#### 验收

- 同一测试线程先发带 token 请求，再发无 Header 的可选鉴权请求，第二次读取必须为 `null`。
- 无 Header / 非法 token / 已撤销 token 三条路径都不得保留旧用户。
- 模拟异步转交后，原线程 `UserContext` 必须为 `null`。
- 合法 token 仍能在 controller 调用阶段读取用户 ID。

### 4.2 分段耗时

#### 日志约定

统一使用已有 `traceId`，不新增数据库列。新增或补齐以下结构化日志：

```text
[PERF-AI] stage=classifier ... durationMs=... outcome=... calls=...
[PERF-AI] stage=memory_recall source=long_term|episodic ... durationMs=... outcome=hit|empty|timeout|error
[PERF-AI] stage=memory_recall_total ... durationMs=... outcome=...
[RAG-TRACE] ... elapsedMs={vector:..., keyword:..., rank:..., total:...}
[PERF-AI] stage=rerank ... durationMs=... outcome=... candidates=...
[PERF-AI] stage=generation ... ttftMs=... durationMs=... signal=...
[PERF-AI] stage=agent_decider ... runId=... stepNo=... durationMs=... outcome=...
[PERF-AI] stage=agent_action ... runId=... stepNo=... executorDurationMs=... totalDurationMs=... outcome=...
[PERF-AI] stage=verifier ... runId=... durationMs=... outcome=... calls=...
```

日志不记录 Prompt、回答正文、JWT、记忆内容或文章正文。用户 ID 沿用现有日志脱敏约定。

#### 数据语义

- `ai_agent_steps.duration_ms` 改为该步从 Decider 开始到动作或终态判定结束的总耗时。
- 如果仍需要动作执行器耗时，只放日志，不在本批新增数据库字段。
- repair 仍属于同一次 classifier / decider / verifier 阶段，总耗时包含 repair；classifier 和 Verifier
  额外记录实际 `calls`。
- 生成 `durationMs` 在流结束时记录；`ttftMs` 在第一个有效 chunk 到达时只记录一次。

#### 验收

- 每条主链请求能用同一 `traceId` 拼出分类、记忆、检索、生成阶段。
- Agent 每一行 step 的 `duration_ms` 不再把几十秒的终态调用记成 0。
- 超时、异常和 fail-open 都有明确 outcome，不能与“正常空结果”混在一起。

### 4.3 Verifier 收敛门

#### 状态

Article Agent 的 per-run 状态按缺失类型维护单调递增的 evidence revision：

- `ARTICLE_CONTENT`、`MEMORY`、`RAG` 各有独立 revision，不能拿无关类型的查询解锁终稿；
- 结构化槽位新增或变化时，对应 revision 递增；
- 某个查询条件第一次得到权威空结果时也递增，表示“已经查过且确认没有”，允许如实回答；
- 相同查询条件的重复命中、重复空结果、超时或异常不递增；
- Verifier 返回 `NEED_MORE` 时记录缺失类型和该类型当时的 revision；
- 只有对应类型 revision 变大，才允许再次尝试 `FINAL_ANSWER`；`OTHER` 接受任一类型 revision 变大。

这些状态只存在于单次 Runtime，不放进 Spring 单例字段。第一批不改表结构；运行中状态由
`AgentRunState` 持有，已有 `contextJson` 继续只保存可恢复上下文。

#### 无新增证据时的行为

当 Decider 再次返回 `FINAL_ANSWER`，但所缺类型的 evidence revision 未变化：

1. 不再调用 Verifier；
2. 该步落 `SKIPPED`，原因固定为“上次拒绝后没有新增证据”；
3. 立即走现有保守收尾，不继续消耗剩余步数。

第一批仍然无法避免这一次 Decider 调用，因为动作选择和正文生成尚未拆开；它至少把最坏情况从
“重复到 maxSteps”收敛为“一次重复后结束”。下一批拆分动作选择与正文生成后，才可以在调用生成模型
之前完全阻断无效终稿。

#### 缺失类型

- `ARTICLE_CONTENT`：只接受文章证据槽位变化；
- `MEMORY`：只接受记忆槽位变化或明确的“正常查询但无命中”；
- `RAG`：只接受 RAG 槽位变化或明确的“正常查询但无命中”；
- `OTHER`：任一证据版本变化均可再次尝试，否则保守收尾。

“超时 / 异常导致空结果”不能当作已经补过证据，也不能伪装成正常无命中。

#### 验收

- `FINAL_ANSWER -> NEED_MORE -> FINAL_ANSWER`：第二次不进 Verifier，立即保守收尾。
- `FINAL_ANSWER -> NEED_MORE -> 对应查询有新增 -> FINAL_ANSWER`：允许再次验证并正常放行。
- 查询结果重复：不递增 evidence revision，不能重新进入终稿验证。
- 查询正常无命中：按缺失类型记为已完成查询，允许模型如实回答“没有找到”，但不得继续重复查询。
- 查询超时或异常：不视为新增证据，最终文案必须说明暂时无法取得证据。
- analysis-only 与完整 Article Agent 都使用同一条收敛规则。

## 5. 实施记录

1. 已为两个拦截器补回归测试，再修 `UserContext` 生命周期。
2. 已为单调计时和 step 总耗时补测试，并完成 classifier、memory、rerank、generation、Agent 主链埋点。
3. 已把“允许反复终稿到顶”的用例改为 2 步收敛，并覆盖对应类型新增证据后可再次验证的路径。
4. 已运行受影响的定向离线测试；按项目约定没有运行完整 `mvn test`、`-Dgroups=eval` 或真实 LLM 门。
5. 已执行 `git diff --check` 并检查新增日志字段；没有记录 Prompt、回答草案、上下文、JWT 或原始模型 JSON。

## 6. 定向验证结果

2026-10-03 执行：

```powershell
.\mvnw.cmd "-Dtest=AuthInterceptorUserContextTests,AiStageTimerTests,AiIntentClassifierUsageTests,ArticleAgentRuntimeVerifierTests,ArticleAgentRuntimeDecisionContextTests,ArticleAgentStateTests,ArticleEvidenceVerifierTests,GteRerankV2ArticleRagRankerTests" test
```

结果：`Tests run: 71, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`。
`git diff --check` 没有空白错误，仅提示 Windows 工作区后续可能把 LF 转为 CRLF。

本轮未运行以下范围，不能据此宣称完整回归或线上延迟已经改善：

- 无 `-Dtest` 的全量 Maven 测试；
- `-Dgroups=eval` 与 67 条完整在线分类门；
- 真实模型、真实 ES / memory timeout、真实 rerank 网络抖动压测；
- 前端构建与浏览器手测（本批没有前端改动）。

## 7. 后续批次

第一批埋点上线采到可信耗时基线后，再依次评审：

1. Agent Decider 只选动作，最终正文由生成模型流式生成；
2. 记忆 embedding / ES 客户端真实超时、取消能力和线程池拒绝策略；
3. 分类器紧凑输出契约与 judge model A/B；
4. rerank 显式超时与规则降级；
5. 前端从发送时刻启动的 250ms 本地等待提示。

每一项独立改动、独立回滚、独立验收，不把分类器路由契约和 Agent 状态机绑在一次发布里。

### 7.1 本批明确保留的限制

- 第二次无效 `FINAL_ANSWER` 的 Decider 仍会先用 judge model 非流式生成长文本；当前收敛门只能阻止
  第二次 Verifier 和后续循环。真正根治需要拆开“动作判断”和“正文生成”。
- memory 的 `orTimeout` 仍只让等待方 fail-open，没有取消底层 embedding / ES 请求；线程池拒绝策略和
  下游客户端真实超时也没有在本批调整。
- rerank 仍未配置本批专属的显式 HTTP timeout；这里只补了耗时与 fallback outcome。
- classifier 的模型、Prompt、输出协议均未调整。
- 前端 250ms 本地等待提示未实施。

## 8. 回滚

- `UserContext` 修复只改变清理时机，出现问题可按拦截器单独回滚。
- 性能日志不参与业务判断，可逐调用点回滚。
- 收敛门只影响 Article Agent；保留现有 `maxSteps` 保守收尾作为最后兜底。
- 本批不改数据库结构、配置文件、API 或前端协议。

## 9. 2026-10-04：Memory 召回背压补丁

### 9.1 新证据

- 本地 Elasticsearch 对 Memory 索引的只读查询约 `80-110ms`；主要测试账号的 MySQL / ES
  数据一致，均为 9 条长期记忆 + 2 条情景记忆，不能用“该账号没有记忆”解释固定超时。
- 对当前 `qwen3.7-text-embedding` 做了 4 次串行和 4 次并发的小规模只读探针：串行约
  `842-1700ms`，并发约 `1612-1865ms`，均低于现有 3 秒等待预算。因此没有把超时阈值
  武断下调到 1 秒。
- 真正的积压风险在应用层：`memoryRecallExecutor` 原为 `core=2 / max=4 / queue=50 /
  CallerRunsPolicy`。`CompletableFuture.orTimeout` 只结束等待，不会可靠取消底层 HTTP；超时任务会
  留在 50 长度的队列里继续执行，队列满后还会回退到请求线程，从而绕过 3 秒 fail-open。

### 9.2 实施

- 召回池保持最多 4 个并发，但改为零队列和 `AbortPolicy`：只允许正在执行的工作，不保存已经失去
  调用方的过期召回。
- `AiPromptServiceImpl` 捕获饱和拒绝，记录 `outcome=rejected`，本轮按无记忆继续构造 Prompt；
  拒绝不会让聊天失败，也不会占满 3 秒。
- 保留健康调用的 3 秒超时。该补丁解决的是故障后的持续积压和请求线程反压，不宣称能取消已经
  发出的底层 HTTP；后者仍需独立的 embedding 客户端超时或可取消调用支持。

### 9.3 定向验证

新增 `MemoryRecallExecutorTests` 和 `AiPromptServiceMemoryRecallTests`，分别锁住零队列快速拒绝、
以及拒绝后的 Prompt fail-open。未运行全量 Maven 测试、在线分类门或真实聊天链路。

## 10. 2026-10-04：前端等待反馈与部分流失败

### 10.1 实施

- 用户发送消息后立即触发现有状态管线，由 250ms 防抖后显示“正在理解需求”；首个正文 chunk 在此之前
  到达时不会渲染，避免快速请求闪烁。
- 每轮开始清理上一轮瞬态步骤；正文开始后，已展示的实时步骤转入当前消息的 `processSteps`。
- 流在零正文时失败仍删除空占位；已经展示正文后失败则保留正文，并追加
  `（生成中断，内容可能不完整）`。
- 占位删除从无条件 `messages.pop()` 改为按 `aiPlaceholderIndex` 精确 `splice`，避免误删后续插入的消息。
- 这与后端部分流失败语义一致：后端不保存不完整消息、不发送伪成功 `STOP`，前端保留用户已经看到的
  内容并明确标为不完整。

### 10.2 验证

- `streamMessageState.spec.ts`：4 条用例通过，覆盖空占位删除、部分正文保留、中断标记幂等和过程步骤固化。
- `npm run build` 通过。
- Playwright 模拟“5 秒无后端事件 -> 返回一个 `DATA` -> 连接异常结束”，确认等待提示、部分正文和
  中断提示均可见；截图保存在被忽略的 `.playwright-cli/latency-waiting.png`。

## 11. 当前验证边界

本轮后端组合定向回归共 167 条通过，覆盖 Article Agent 拆分与 Memory 背压；前端单测 4 条通过，
生产构建通过。仍未运行无 `-Dtest` 的全量 Maven 测试、`-Dgroups=eval`、67 条完整在线分类门，
也未用真实聊天请求重新测量端到端 TTFT。因此可以确认代码路径和回归门通过，不能把它表述为线上延迟
已经达到某个具体数值。

## 12. 2026-10-04：生成 TTFT 第二批设计

### 12.1 Jev 生效后的新基线

本轮只记录用户在新会话中观察到的首个正文 chunk 时间，并用同一请求的 `[PERF-AI]`、
`[RAG-TRACE]` 和 `ai_billing_order` 交叉核对：

| 场景 | Jev 分类 | Memory | RAG | 正文输入 | 模型 TTFT | 端到端首字 |
|---|---:|---:|---:|---:|---:|---:|
| 当前文章讲解 | 929ms | 934ms | 0 | 5647 tokens | 14307ms | 约 16s |
| 站内搜索文章 | 368ms | 582ms | 1930ms | 3180 tokens | 5623ms | 约 8s |
| 通用概念讲解 | 397ms | 596ms | 0 | 1666 tokens | 10953ms | 约 12s |

结论：Jev 已把这三类只读请求的分类阶段压到 1 秒内；剩余首字时间主要位于正文生成调用。
Prompt 大小有影响，但不是唯一变量：同一篇文章、相同 `5647` 输入 tokens 的两次调用，模型 TTFT
分别为 `9344ms` 和 `14307ms`；相同 `3180` 输入 tokens 的两次文章搜索，模型 TTFT 分别为
`9643ms` 和 `5623ms`。供应商排队或模型侧抖动可单次贡献约 4-5 秒，不能把全部延迟归因于应用 Prompt。

### 12.2 第二批顺序

第二批按可确定收益和回归风险分三刀，不把它们绑在一次提交中：

1. ~~单目标 `ARTICLE_SEARCH` 确定性直出，不再调用正文生成模型。~~（2026-10-04 因质量退化撤回）
2. 普通聊天按路由组装 System Prompt，并对通用技术问题评估按需 Memory。
3. 当前文章问答区分“具体问题”和“整篇讲解”，再决定相关 chunk、结构摘要或发布时摘要缓存。

第一刀曾实施后撤回；第二、三刀继续以真实输入 token、P50/P95 TTFT 和回答质量 A/B 为前置门，
不能只按首字时间验收。

### 12.3 第一刀：单目标文章搜索确定性直出（已撤回）

#### 适用边界

- 只处理 Planner 已放行的单目标 `ARTICLE_SEARCH` 普通聊天路径。
- 复合 Task 中的 `ARTICLE_SEARCH` 继续由现有 `ArticleSearchExecutor` 执行；它本来就不调用回答模型。
- `ARTICLE_SEARCH + ARTICLE_ANALYSIS`、Workflow handoff 后搜索等编排路径不改。
- 当前文章问答、通用概念讲解和 Agent Runtime 不进入该快速路径。

#### 数据流

```text
Jev / plus 分类
  -> AgentPlannerSupport 确定 ARTICLE_SEARCH
  -> 立即发送现有 CHAT_STATUS（正在检索站内文章）
  -> ArticleRagSearchService（混合检索 + rerank + relevance gate）
  -> 按 articleId 保序去重
  -> 后端确定性渲染正文与引用编号
  -> PARAM -> DATA chunks -> STOP(references)
```

快速路径必须位于 `AiPromptService.buildPrompt()`、Memory 召回、聊天计费预扣和
`AiModelService.streamChat()` 之前。搜索展示不需要个性化记忆；如果先构建 Prompt 再分流，只能省下模型 TTFT，
仍会白等 Memory，也会产生一笔没有模型调用的预扣单。

#### 输出契约

- 结果先按 `articleId` 保序去重，最多展示 5 篇。
- 正文中的 `[1]`、`[2]` 必须按照去重后的 `references` 顺序编号，不能按原始 chunk 下标编号。
- 每篇展示标题和已有 snippet；snippet 继续复用引用卡片的空白归一化与长度上限，不把完整 chunk 回显给用户。
- 有结果时使用中性事实文案，例如“找到 3 篇与 Redis 相关的公开文章”；不生成模型式推荐理由。
- relevance gate 后为空时，明确说明未找到相关公开文章，并建议更换关键词。
- 检索异常时返回“文章搜索暂时不可用，请稍后重试”，记录错误但不回退 flash；否则故障路径仍会重新引入
  5-10 秒不可控等待，并可能编造搜索结果。

#### 持久化、计费与 SSE

- 登录用户仍保存 user / assistant 两条消息并更新会话时间；游客仍不落库。
- assistant `token_count` 只记录本轮分类消耗 `routeUsage`，与现有规则生成 CTA 的口径一致。
- 不创建 `ai_billing_order`：正文由后端规则生成，没有正文模型用量可结算。
- 保持现有 `CHAT_STATUS`、`PARAM`、`DATA`、`STOP` 事件类型；`STOP.references` 继续使用
  `ArticleRagReferenceVO`，前端无需新增协议或分支。
- 确定性正文仍按现有 `splitIntoChunks` 切成 `DATA`，避免直到 `STOP` 才突然替换整条消息。

### 12.4 第一刀验收（历史方案）

#### 正确性

- 多 chunk 命中同一篇文章时，正文和引用卡都只出现一次，编号一致。
- gate 空结果、MySQL fallback 和检索异常分别有稳定文案，不调用正文模型。
- 登录态消息、游客消息、会话更新时间和 `STOP.references` 保持现有契约。
- 分类消耗落 assistant `token_count`；没有 CHAT 计费单。

#### 性能

- 新增 `[PERF-AI] stage=article_search_response`，记录检索、渲染和首个 DATA 前总耗时，不记录正文。
- 本地代表样例“有没有关于 Redis 的文章”目标：首个 DATA 不再包含 generation TTFT，主要耗时应收敛为
  `classifier + RAG + 小量持久化/渲染`；按本轮基线约为 2-4 秒。
- 至少重复 3 次观察 P50 / 最大值；单次快不作为验收结论。

#### 定向测试

- 渲染器：去重、编号、5 篇上限、空 snippet、空结果。
- 登录流：跳过 `buildPrompt` / Memory / billing / `streamChat`，持久化两条消息并返回 references。
- 游客流：同样跳过模型，保持不落库。
- RAG 异常：稳定失败文案，无模型 fallback。
- 只运行相关测试类；不运行无 `-Dtest` 的全量测试或 67 条在线分类门。

### 12.5 后续两刀的前置门

路由专用 Prompt 需要先记录普通聊天最终发送的 system/user 字符数、prompt tokens、cached tokens 与 TTFT，
再比较完整版和精简版回答质量。当前 `GENERAL_CHAT` 只有约 1666 输入 tokens 仍出现 11 秒 TTFT，说明精简
Prompt 可以降低 prefill，但不能承诺消除供应商抖动。

当前文章问答不能直接把正文上限从 9000 字武断下调。具体问题可按 `articleId` 做文章内 chunk 检索；
“讲解整篇文章”需要覆盖整体结构，应比较“全文”“标题/摘要/标题层级/代表段落”和“发布时预生成摘要”三种方案。
任何方案都必须同时比较事实覆盖率、引用正确性、输入 token 和 P50/P95 TTFT。

### 12.6 第一刀实施记录（已撤回）

2026-10-04 已完成：

- 新增 `ArticleSearchResponseRenderer`，按 `articleId` 保序去重、最多展示 5 篇；正文编号和
  `STOP.references` 从同一份引用列表生成。
- 登录与游客的单目标 `ARTICLE_SEARCH` 都在 `buildPrompt()` 之前分流；不进入 Memory、正文模型或
  CHAT 计费。复合 Task 的 `ArticleSearchExecutor` 保持原路径。
- 登录流仍保存 user / assistant 两条消息、更新会话时间，并把分类消耗写入 assistant
  `token_count`；游客不落库。
- 空结果、MySQL fallback 和检索异常都使用稳定确定性文案；异常不回退正文模型。
- 新增 `[PERF-AI] stage=article_search_response`，其 `durationMs` 截止首个 `DATA`，覆盖检索、渲染和
  登录态消息持久化，不记录 query 或正文。

定向验证命令：

```powershell
.\mvnw.cmd "-Dtest=ArticleSearchResponseRendererTests,ArticleSearchFastPathTests,ArticleRagSearchServiceTests,TaskStepExecutorTests,TaskArticleReferenceSupportTests,ArticleInteractionStreamTests" test
```

结果：`Tests run: 37, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`。

本次仍未运行无 `-Dtest` 的全量 Maven 测试、67 条在线分类门，也没有重启后端做 3 次真实搜索请求，
所以目前只能确认代码路径已移除 generation TTFT，不能把“首结果 2-4 秒”写成已达成的实测结论。

### 12.7 回退记录

2026-10-04 已撤回单目标 `ARTICLE_SEARCH` 确定性直出：

- 实测虽然首个正文 chunk 明显提前，但正文退化为“标题 + 约 50 字 snippet”，长标题还会挤占 snippet
  预算，无法替代模型对多个 RAG chunks 的综合整理。
- 登录与游客搜索恢复原链路：`RAG 检索多个 chunks -> 拼入 Prompt -> flash 流式生成详细回答`；
  `STOP.references` 仍由同一批 RAG 上下文生成。
- Jev 分类、Memory 并行与背压、前端等待提示、生成 TTFT 埋点等其他延迟优化全部保留。
- 新增 `ArticleSearchQualityPathTests` 锁住质量边界：多个 RAG chunks 必须进入最终 Prompt，正文必须来自
  `AiModelService.streamChat()`，避免再次用引用摘要替代生成回答。
- 按撤回前实测，搜索端到端首字预计回到约 8 秒；本轮按约定不启动后端，因此该数值尚未做真实复测。
