# Agent 可观测性：traceId 贯穿（V4⑥ 落地动作·设计稿）

> **状态**：**已实施（2026-09-12）**，实施记录见 §八。
> **定位**：V4⑥「框架能力对照」的**落地动作**——框架评估得出「不引入 Agent 框架接管 Runtime，但周边能力可以用框架」，本刀是那句话的第一个正例。
> **日期**：2026-09-12

## 〇、结论先行

1. **不接管 Runtime，只在外面加观测层**——判据：**不持有业务状态、不做业务裁决**。traceId 是旁路，运行时挂掉不影响主流程。
2. **只做 L1（traceId 贯穿），不做 L3（Prometheus + Grafana 全套）**；L2（span 导出）仅保留升级路径，本刀不接后端。
3. **查证后成本比预想低，但不是零风险**：Spring Boot 3.5.16 走 Micrometer Tracing 路线；HTTP 入口 traceId 与日志 MDC 可由框架生成，但 Reactor 调度与手写线程池仍要逐条验收。
4. **两处决策已落定**（§四）：本刀不加 `trace_id` 列 / 不接导出后端。

## 一、为什么做

### 1.1 痛点（都是真实排查经历）

| 场景 | 现在怎么排查 | 问题 |
|---|---|---|
| V3.13 重复查询根因 | 查库读 `context_json`（3446 字符） | 靠人肉读 JSON |
| V4.5 首字前耗时 | 看手写的 `[PERF-WORKFLOW]` 日志 | **只覆盖 Workflow step**，不覆盖 planner / LLM / RAG / Memory |
| "这个用户这次请求经历了什么" | 按 runId 逐个 grep 不同 logger | **前半段没有 runId**（run 还没创建） |

**核心缺口**：一次聊天请求会依次经过

```text
HTTP 入口 → 限流拦截器 → 分类器 → Planner → (Agent Run → N 个 step → LLM)
                                              ↓
                                     RAG 召回 / Memory 召回 / ES 查询
                                              ↓
                                        SSE 流式回写 → 落库
```

这些环节**散落在不同 logger、不同线程**，**没有共同的锚**。`[PERF-WORKFLOW]` 是手写埋点，只覆盖了其中一段。

### 1.2 为什么现在做

- V4 主干评估点已清（① 不做 / ② 待证据 / ③ 待铺开 / ④ 收口 / ⑤ 完成 / ⑥ 完成），**这是补支撑设施的窗口**
- 它是 ⑥ 已登记的落地动作（`agent-framework-evaluation.md` §5.2）
- **本地就能受益**，不依赖上线
- 面试高频，且能讲清「什么用框架、什么不用」

## 二、范围与非目标

### 2.1 能力地图与分层取舍

| 层 | 内容 | 本刀 | 理由 |
|---|---|---|---|
| **L1 · traceId 贯穿** | 请求生成 traceId → MDC → 日志格式 → 各环节耗时 | ✅ **全做** | 解决「串不起来」的核心痛点，成本最低 |
| **L2 · span 化** | agent step / LLM / RAG / Memory 各打 span，可导出 | ⏸ **不做，只留升级路径** | 没有导出后端时收益不稳定，先不加手写 span |
| **L3 · 指标看板** | Micrometer + Prometheus + Grafana | ❌ **不做** | 第三层登记；单机博客用不上 |

**「做到什么程度算够」**：一次聊天请求从入口到落库，**日志能按 traceId 串成一条线**。

### 2.2 非目标（明确不做）

- ❌ 不改 `AbstractAgentRuntime` 的循环逻辑、状态机、失败语义
- ❌ 不做自动告警
- ❌ 不替换现有 `[PERF-WORKFLOW]` 日志（它是 code-level 埋点，与本刀互补，见 §5.4）
- ❌ 不接 Prometheus / Grafana
- ❌ 不接 Jaeger / Zipkin / OTLP Collector
- ❌ 不把 `traceId` 写入 run 表
- ❌ 不做 baggage 自动传播 `userId/sessionId`

## 三、设计

### 3.1 traceId 生命周期

| 环节 | 做什么 | 谁做 |
|---|---|---|
| 入口 | 收到 HTTP 请求 → 生成 / 继承 traceId → 塞进 MDC | **Spring Boot tracing 自动配置** |
| 业务标识 | run 创建后把 `agentRunId` / `workflowRunId` 放进 MDC | 手写（§3.3） |
| 自建线程池传递 | 进 `ThreadPoolTaskExecutor` 时复制上下文 | `ContextPropagatingTaskDecorator`（§3.4） |
| Reactor 调度传递 | `Schedulers.boundedElastic().schedule(...)` 是否保留 traceId | **实施时验证；不先承诺**（§3.5） |
| 出口 | 请求结束清理 MDC | **Spring 自动** |

**覆盖范围**：所有 HTTP 请求，含**游客**（无 userId）与 **SSE 长连接**（待验证，见 §5.3）。

### 3.2 依赖与配置

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-tracing-bridge-otel</artifactId>
</dependency>
```

> 版本由 Spring Boot BOM 管理，**不要自己 pin**。

```yaml
management:
  tracing:
    sampling:
      probability: 1.0        # 本地开发全采样

logging:
  pattern:
    correlation: "[${spring.application.name:},%X{traceId:-},%X{spanId:-}] "
  include-application-name: false
```

> **注**：`micrometer-tracing-bridge-otel` 只提供 bridge；**不配 OTLP endpoint 也能用**（traceId 生成与 MDC 注入不依赖导出）。

**Actuator 端点策略：本刀零暴露。** tracing 自动配置与 web 端点暴露是**两件事**——本刀只要 traceId，**不需要任何 actuator 端点**：

```yaml
management:
  endpoints:
    web:
      exposure:
        exclude: "*"     # 不暴露任何端点；actuator 只作为 tracing 自动配置的依赖
```

不开放 `env` / `beans` / `prometheus` 等敏感端点。**特别注意**：`/actuator/health` 由 Spring Boot **默认暴露且对所有请求开放（含未登录）**，会泄露 DB / Redis / ES 的连通状态——本刀一并关掉；健康检查接口留到工程化补课那批再做。

### 3.3 业务 ID 进 MDC

`agentRunId` / `workflowRunId` 让「日志 → DB」双向可跳。

**做法**：run 创建成功后 `MDC.put("agentRunId", id)`，在 `finally` 里 `MDC.remove`。

**为什么不用 baggage**：baggage 的语义是**跨服务传播**（要放进 HTTP header 发给下游）。我们是单服务，`agentRunId` 是**业务流程内的局部标识**，用 baggage 属于语义错配，还会增加不必要的 header 注入。

**落点**（待实施时确认具体位置）：`AbstractAgentRuntime` 的 run 创建后 / `WorkflowRunManager` 的 run 创建后。业务 ID 只服务日志检索，不作为业务状态持久化字段。

### 3.4 自建线程池传递（4 个线程池）

**清点结果**——项目里所有需要加 `TaskDecorator` 的地方：

| 线程池 | 位置 | 用途 |
|---|---|---|
| `articleRagTaskExecutor` | `ArticleRagAsyncConfig` | RAG 索引同步 |
| `memoryCandidateTaskExecutor` | `MemoryExtractionAsyncConfig` | 记忆候选 / 语义与情景记忆提取入口 |
| `conversationSummaryExecutor` | 同上 | 会话摘要 |
| `memoryRecallExecutor` | 同上 | **V4.5 记忆召回并行** |

外加 `AiPromptServiceImpl` 里那两处 `CompletableFuture`（用的也是 `memoryRecallExecutor`）。

**做法**：每个池 `setTaskDecorator(new ContextPropagatingTaskDecorator())`。

**边界**：`ContextPropagatingTaskDecorator` 只覆盖这些 `ThreadPoolTaskExecutor`。它不覆盖项目里手写的 `Schedulers.boundedElastic().schedule(...)`，也不保证 Spring AI 内部线程一定继承 MDC。

**不需要担心的**：项目**没有自定义 Servlet Filter**（只有 3 个 `HandlerInterceptor`：JWT ×2 + 限流）。HandlerInterceptor 跑在 `DispatcherServlet` **之后**，不会出现「自定义 Filter 抢在 tracing 自动配置之前、traceId 还没进 MDC」这个常见坑。

### 3.5 非自建线程池的边界

**三类链路不走上面的自建线程池**，`ContextPropagatingTaskDecorator` 覆盖不到：

| 链路 | 位置 | 数量 |
|---|---|---|
| Reactor 调度 | `AiMessageServiceImpl` | **6 处** `Schedulers.boundedElastic().schedule(...)` |
| Workflow 流式恢复 | `AiWorkflowStreamServiceImpl` | **1 处** |
| **Spring AI 内部线程池** | 工具执行（`@Tool` 方法） | 框架内部；并非所有工具方法都有 `ToolContext` 参数 |

**Spring AI 这条有实证**——[`AiModelServiceImpl.java:69`](../../blog-system/src/main/java/com/hailin/blogsystem/service/impl/AiModelServiceImpl.java) 的注释写着：

> 工具执行在 Spring AI 内部线程池（boundedElastic），ThreadLocal 的 UserContext 拿不到。

当时的解法是**显式传参**（在请求线程读一次 `userId`，通过 `ToolContext` 传给工具）。

**但 traceId 不能照搬这个解法**：`userId` 只在一个地方用，而 traceId 要的是「**每行日志都带上**」——遍布全代码，**没法逐个传参**。

所以本刀**不能只加 `TaskDecorator` 就宣称「全链路不断」**。实施第一步逐条验证，分别处理：

- **Reactor 链路**：验证是否自动传播；断链则在 `schedule` 包裹点做最小手写传播（捕获当前上下文后 wrap `Runnable`）
- **Spring AI 工具链路**：先验证、先记录边界。项目只有 `toolContext` 的构建入口统一（`AiModelServiceImpl.buildToolContext`），**工具方法本身并不完全统一**：部分工具有 `ToolContext`，部分 `@Tool` 没有。若断链且确实需要看工具内部日志，再对具体工具小范围补 `ToolContext` / MDC 包裹，不把本刀扩大成全工具改造
- **共同底线**：不改 Runtime 状态机

### 3.6 与本刀无关但要注意的

- **采样率**：本地 1.0；将来上生产要调（高频场景全采样会放大数据量与延迟）
- **SSE**：长连接期间 traceId 是否保持，**列为实施时的第一个验证点**（§6.1）

## 四、两处决策（已收口）

### 决策 A：`ai_agent_runs` / `ai_workflow_runs` 要不要加 `trace_id` 列？

| 选项 | 优点 | 缺点 |
|---|---|---|
| **A1 · 加** | dev 面板拿到 run → **直接跳该 run 的全部日志**；DB 记录能自证链路 | **2 次 ALTER**（本地 + 生产）；实体 / VO / 插入逻辑改动 |
| **A2 · 不加** | 零 DDL；日志里有 traceId，靠 runId 反查也能找到 | 从 run 找日志要两步：拿 runId → grep 日志里的 runId |

**结论：选 A2，本刀不加列。**

理由：

- 当前核心痛点是「日志串不起来」，不是「从 dev 面板一键跳日志」。
- 加列会引入 DDL、实体、VO、前端展示和生产同步，超过 L1 目标。
- 如果前端没有日志查看 / 跳转能力，`trace_id` 很容易变成死字段。
- 先让关键日志显式带 `agentRunId / workflowRunId`，已经能做到「日志 ↔ DB」互查。

升级条件：后续真的做 dev 面板日志跳转，或者日志留存系统能按 traceId 查询，再补 run 表 `trace_id`。

### 决策 B：要不要现在接导出后端（Jaeger / Grafana）？

| 选项 | 优点 | 缺点 |
|---|---|---|
| **B1 · 先不接** | 零运维；本地日志立刻有用；starter 已在，将来加配置即开 | 看不到跨请求的聚合视图与甘特图 |
| **B2 · 接 Jaeger**（docker-compose 起一个） | 有可视化链路，演示 / 面试直观 | 多一个运行时组件与运维负担；与"第三层不做 Prometheus+Grafana"的取舍口径是否一致？ |

**结论：选 B1，本刀不接。**

理由：L1 已解决「串不起来」的核心痛点；Jaeger / Grafana / Collector 会把本刀从 Agent 周边能力拉成一套观测平台建设。面试讲法重点是边界：当前用 traceId + MDC + 异步传播解决单机排障；多实例、链路聚合、容量分析出现后再接导出后端。

升级条件：后续需要跨请求聚合视图、链路耗时瀑布图，或部署到多实例环境。

## 五、风险与坑

| # | 风险 | 应对 |
|---|---|---|
| 1 | **MDC 跨线程丢失**——最易踩 | 4 个池全部加 `ContextPropagatingTaskDecorator`；**验收点必须有异步链路**（§6.1-③） |
| 2 | 漏配某个池 → 链路断一段 | 验收点覆盖「记忆召回并行」这条真实异步路径 |
| 3 | Reactor `boundedElastic` 链路断 trace | **实施第一步就验证**；必要时只在 `schedule` 包裹点做上下文 wrap |
| 4 | SSE 长连接的 traceId 保持 | **实施第一步就验证**，不通过则记录边界 |
| 5 | 与 `[PERF-WORKFLOW]` 重复 | **保留手写日志**——它提供 code-level 的进入/退出时序，traceId 提供跨组件串联，两者互补 |
| 6 | 采样率 | 本地 1.0；生产调低，写进部署备注 |
| 7 | MDC 泄漏（线程复用残留） | 业务 ID 的 `MDC.put` 必须配 `finally` 清理 |
| 8 | Actuator 暴露面扩大 | **零暴露**（`exposure.exclude: "*"`）；连默认开放的 `/actuator/health` 也关掉 |
| 9 | **Spring AI 工具执行链路**（框架内部线程池）拿不到 ThreadLocal | 实施时验证；断链先记录边界，只有确实需要工具内部日志时才对具体工具补 `ToolContext` / MDC 包裹 |

## 六、实施与验收

### 6.1 验收点

1. **一条完整的线**：发一条 Agent 请求 → 用 traceId grep → 能看到 `分类器 / Planner → Agent Run → 各 step → LLM → RAG / Memory → 落库` 中实际发生的环节使用同一 traceId
2. **游客**请求同样有 traceId
3. **异步不断链**：记忆召回并行（`memoryRecallExecutor`）那条支线，traceId 与主线程一致
4. **SSE 链路结果明确**：验证流式输出期间 traceId 是否保持；若断链，按 §6.2-④ 最小补传播或记录已知边界
5. **业务 ID 可互查**：日志里能看到 `agentRunId` 或 `workflowRunId`，再到 dev 面板 / DB 反查对应 run

### 6.2 实施清单

1. pom 加 2 个依赖
2. yml 配日志 pattern + 采样
3. 4 个线程池加 `TaskDecorator`
4. 验证 Reactor / SSE 链路；若断链，只在 `Schedulers.boundedElastic().schedule(...)` 包裹点补上下文传播
5. 验证 Spring AI 工具链路；若断链，先记录为边界，不默认改造全部工具
6. Agent / Workflow run 创建后 `MDC.put` 业务 ID + `finally` 清理
7. 跑相关测试 + 手工验收 5 条

### 6.3 回滚

**低风险但不是零风险**：大部分是旁路改动，去掉依赖与配置即可回到现状；但依赖冲突、Actuator 暴露面、异步上下文传播都需要编译和手测确认。`MDC` 本身不参与业务裁决，业务 ID 写入失败不应影响主流程。

## 七、面试讲法（初稿）

**Q：为什么要做可观测性？**
因为 Agent 系统天然比普通接口难排查——一次请求可能 6 步、每步一次 LLM 调用、还夹着 RAG 和 Memory 召回。之前我排查一个重复查询问题，是靠查库读 3446 字符的 `context_json` 才定位到的。这种排查方式不可持续。

**Q：为什么用 Micrometer Tracing 而不是自己写？**
用它不代表把控制权交出去——**判据是「不持有业务状态、不做业务裁决」**。traceId 是纯旁路：它不接管 Runtime 状态机、不参与确认门、失败了也不影响主流程。这类能力用成熟框架是正解；反过来，`WAITING_*` 状态、写动作三明治锚定这些，框架给不了也不该给。

**Q：为什么不做全套（Jaeger / Prometheus / Grafana）？**
当前是单机博客，L1 已经解决了「串不起来」这个真实痛点。全套的触发条件是**多实例部署、需要跨请求聚合视图、需要容量评估或告警**——现在上属于把周边能力做重。（能说清"什么规模才需要"本身就是答案。）

## 八、实施记录（2026-09-12）

### 8.1 实际落了什么

| 层 | 落地内容 |
|---|---|
| 依赖 | `spring-boot-starter-actuator` + `micrometer-tracing-bridge-otel`（版本由 BOM 管，不自行 pin） |
| 配置 | `spring.application.name` / `management.tracing.sampling` / `logging.pattern.correlation` / actuator **零暴露**（`exclude: "*"`） |
| 自建线程池 | 4 个 `ThreadPoolTaskExecutor` 加 `ContextPropagatingTaskDecorator` |
| **统一工具** | `utils/MdcContext`：`LogContext`（MDC + `TraceContext`）/ `captureWithTrace(tracer, fallback)` / `wrap(tracer, logContext, runnable)`——**恢复时同时 scope Micrometer TraceContext** |
| **Agent 链路** | `AiMessageServiceImpl` 的 `captureAgentLogContext` / `wrapAgentLogContext` 委托给 `MdcContext`，包裹 Agent 调度点 |
| **Workflow 创建 / 兜底路径** | 5 处（文章优化 / 文章创建 / 学习计划 / 学习进度 / 难点攻坚）同模式包裹 |
| **Workflow 流式恢复** | `AiWorkflowStreamServiceImpl.streamAction` 包裹（approve / reject / retry 全走这里） |
| **LLM 流式回调** | `LlmStreamCaller` 的 `doOnNext` 逐 chunk 包裹——LLM streaming 的 onNext 同样跑在 Reactor 线程上 |
| 业务 ID | Agent run（`AbstractAgentRuntime.run`）+ Workflow 操作（`AbstractWorkflowHandler` 的 approve / reject / retry）进 MDC |

### 8.2 踩的坑（本轮最有价值的部分）

**坑 1 · 「Reactor 断链」的位置比设计稿预判的更深**

设计稿 §3.5 预判了 Reactor 链路可能断、也留了「必要时手写传播」的余地，但**低估了断在哪一层**：

- 初始判断：`Schedulers.boundedElastic()` 的调度不继承 ThreadLocal → 加个 `onScheduleHook` 就能兜住
- **实际**：`Flux.create` 的 sink lambda **在订阅时执行**，而**订阅线程（Spring MVC 的异步处理）本身就没有 MDC**
- 所以 hook 捕获到的是**空快照**——**它救不了「源头就没有」的情况**

**最终解法**：**在请求线程抓快照 → 显式传参 → 执行线程恢复**，不依赖任何钩子。

**坑 2 · `ContextPropagatingTaskDecorator` 只覆盖自建线程池**

对 `ThreadPoolTaskExecutor` 有效 ✅；对 `Schedulers.boundedElastic()` 无效 ❌——那是 Reactor 的调度器，不认 Spring 的 decorator。

**坑 3 · `orTimeout` 的 Delayer 线程**

`CompletableFuture.orTimeout` 超时后，`exceptionally` 回调跑在 **`ForkJoinPool` 的 Delayer 线程**上——`TaskDecorator` 和调度钩子都够不到 → **判定记边界不修**（见 8.4）。

**坑 4 · 方法论**

定位靠的是**逐层对比**：「分类器有 traceId、Agent 没有」→ 断点在两者之间。中间走过弯路（先怀疑 hook 没注册、再怀疑 MDC 被清理），根因是**跳过了「哪一层还有」的验证**。→ 记进面试材料：**MDC 类问题要逐层验证，不要跳步**。

**坑 5 · 只恢复 MDC 不够，还要 scope Micrometer 的 `TraceContext`**

首版实现里，`AiWorkflowStreamServiceImpl` 那处只做了恢复 MDC（当时的判断是「traceId 就在 MDC 里，够用」）。
**实际不够**：后续若有 Micrometer 的 tracing scope 切换，**MDC 会被覆盖/清理**，traceId 照样丢。

正解是 `wrap(tracer, logContext, runnable)`——**恢复 MDC 的同时把 `TraceContext` scope 回当前线程**。这也是最终把 API 统一到 `MdcContext` 的原因：**MDC 与 TraceContext 必须成对处理，只做一半会在部分路径上失效**。

### 8.3 验收结果

| 项 | 结果 |
|---|---|
| 分类器 / Planner | ✅ 正常 |
| Agent Run 全程（创建 / step / 结束 / 结论锚） | ✅ 有 |
| 工具路径内的召回 | ✅ 有 |
| `memory-recall-*` 线程池 | ✅ 有（`TaskDecorator` 生效） |
| **Workflow 流式恢复路径** | ⚠️ **无自动化测试覆盖**，待手工验（触发：会话存在 activeWorkflow 时发消息） |
| 自动化测试 | ✅ 本轮相关用例全绿 |

### 8.4 已知边界

1. **`orTimeout` 超时路径丢 traceId** —— `ForkJoinPool` Delayer 线程。修它要动 V4.5 刚稳定的并行召回代码，**判定记边界不修**（那条日志本身带 `userId`，排查够用）
2. **`ObservabilityConfig` 的 `onScheduleHook` 保留但非主力** —— 显式包裹才是可靠路径，钩子留作将来新增调度点的兜底
4. **记忆召回 > 3s** —— 与本刀无关的性能问题（ES 已启动仍超时），另行处理
