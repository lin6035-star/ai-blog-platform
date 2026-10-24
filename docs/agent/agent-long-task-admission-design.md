# AI 长任务准入与资源隔离：第一刀

> 状态：**✅ 已实施（2026-09-19）** —— 实施结果、实测结论与已知残留见 `CLAUDE.md`「并发 / 资源隔离」节。
> 以下保留四轮评审 + 五轮修订的记录（其中的「修正：」引用块是**有意留档**，不是残留）：
> 一轮修正并发语义 · 二轮删全局 Semaphore / 重写 workers 口径 / 补 §5.3 `RUNNING` 恢复 ·
> 三轮补终态语义与排除清单 / 修正 `sink` 论证 / 前端 `FAILED` 存量 bug 纳为本刀 ·
> 四轮把约束从「首个步骤事件之前」修正为「**任何 SSE 事件之前**」，
> 解法定为「准入检查前置 + 名额增减留在订阅时 + 极小窗口降级」；
> 五轮把接口改成**两段式**（`precheck` / `submit`）、明确用户上限是**硬上限**
> （竞态结果是「这个任务被拒」，不是「多跑一个」）
> 日期：2026-09-19
> 前置：并发实测 `BoundedElasticSaturationTests` + 对原始判断的复核

---

## 0. 一句话

现在 7 处 Agent / Workflow 长任务**直接提交到 Reactor 全局共享池**，没有准入、没有上限、观测也没有统一收口。
这一刀建立一个**统一的提交入口**，把「每用户并发上限 + 专用有界池 + 明确拒绝 + 观测」作为**一个模块**一次做完。

> 范围只覆盖这 7 处同步阻塞的 Agent / Workflow 编排任务。普通聊天正文走响应式模型流，仍由现有
> `chat` 固定窗口限流与钱包计费保护，不纳入本刀的线程池准入。模块命名和文案不能暗示它覆盖了全部 AI 请求。

**不做**任务化 / 轮询（见 §5.1）。

---

## 1. 现状与实测

### 1.1 现状：7 处直接提交

> ⚠️ **本节是当时的结论，不完整**：这 7 处是**按「SSE 流式入口」这一种形态**搜出来的，
> 漏掉了同样跑 LLM 的**同步 HTTP 入口**。补漏过程、修法与另外两处见 **§8**。

全部是 `Schedulers.boundedElastic().schedule(...)`，提交到 **Reactor 全局共享池**：

| 位置 | 条数 |
|---|---|
| `AiMessageServiceImpl` 925 / 1125 / 1655 / 1774 / 1903 / 1983 | 6 |
| `AiWorkflowStreamServiceImpl` 131 | 1 |

（`ObservabilityConfig:13` 提到它，但那是注释，不是调用点。）

**四个缺口**：

1. **没有准入**——任何请求都能提交，没有"这个用户已经跑了几个"的概念
2. **没有上限**——一个用户开多个会话就能占多个线程
3. **观测未收口**——排了多久、跑了多久，只有 1 处有（见 1.3）
4. **拒绝不明确**——全局池默认每个 backing worker 的排队上限是 10 万，实际总缓冲更大；过载主要表现为**延迟持续增长**，不是及时失败

### 1.2 实测数据

`BoundedElasticSaturationTests`（3 例全绿，0.57s）：

| 项 | 值 |
|---|---|
| 全局池大小 | **`10 × CPU`**——20 核开发机 = 200 线程（**已实测**，非照公式推算） |
| 排队上限 | 默认每个 backing worker **100000**，不是整个池合计只有 100000 |
| **池满之后** | **排队**，不拒绝、不抢占 |
| 何时拒绝 | 线程**和**队列都满——生产上几乎走不到 |

**已有历史数据**（149 条 run）：COMPLETED 125 条，平均 **17.5s**，分桶集中在 `10-30s`。

### 1.3 已有的可复用件

**`queueWaitMs` 观测已经存在**，在 `AiWorkflowStreamServiceImpl`：

```java
long subscribeAt = System.currentTimeMillis();          // Flux.create 里
long workerStart = System.currentTimeMillis();          // worker 起来后
log.info("[PERF-WORKFLOW] worker_start ... queueWaitMs={}", workerStart - subscribeAt);
```

**这一刀不是从零加观测，是把这一处推广到 7 处。**

MDC 恢复也已经收口在 `MdcContext.wrap(tracer, logContext, runnable)`——`AiMessageServiceImpl:845` 的 `wrapAgentLogContext` 只是它的私有别名。改造时统一走底层那个。

### 1.4 ⚠️ 被推翻的三条判断（记录，避免重犯）

这一节的判断**经复核后不成立**，留在这里是因为它们很容易被再次想出来：

**① 「用户开 5 个会话，第 5 个会排队」——过度外推**

实测用的是 `newBoundedElastic(4, ...)`，**4 是人为缩小的**，只为了让测试跑得快。
它证明的是「**池满之后**的行为是排队而不是拒绝」，**不能推出「5 个用户就排队」**。
20 核下池是 200，5 个任务完全能同时跑。

**② 「CANCELLED 的 322 秒 / 1009 秒 = 线程被占用」——把等人工当成了占线程**

代码注释写得很清楚：

```
CANCELLED：取消（V1 预留，同步 Loop 暂不出现；V2.1 用于过期建议清理）
```

数据也对得上：那 4 条**全部 `used_steps = 2`、全部有 `final_answer`**，
是走完 2 步出了建议/提案后停在 `WAITING_*_CONFIRM`，**用户离开页面后才被取消**。
那 1009 秒里 LLM 线程早就释放了。

**③ 「线程数 ÷ 平均耗时 = 安全容量」——那是稳态吞吐，不是安全线**

它忽略了：瞬时突发、P95/P99 长尾、模型供应商侧的并发限制与超时、单个 Agent 内多次串行调用。
**只能当量级参考**，不能当容量规划依据。

> 这三条都不影响「要做准入」的结论——**缺口 1/2/3/4 是代码看出来的，不依赖任何性能推算**。

---

## 2. 第一刀的范围

| 做 | 不做 |
|---|---|
| 统一任务提交入口（收口 7 处） | 任务化 + 前端轮询（§5.1） |
| 每用户并发上限 | 协作式取消（§5.2，第二刀） |
| 专用有界线程池 | 公平调度（先到先得，见 §7） |
| 小型有界队列 + 明确拒绝 | 跨实例准入（见 §4.1） |
| 观测：queueWaitMs / executionMs / inFlight / running / 拒绝原因 | |
| 前端 Workflow 流解析非 2xx JSON 错误文案 | |
| 前端 `restoreActiveWorkflow` 终态判断补 `FAILED`（存量 bug，见 §5.3） | |

⚠️ **独立池、队列容量、拒绝策略必须一次做完**——建池的时候就必须定队列和拒绝策略，它们本来就是同一个决策，拆成几步做等于把池建两遍。

---

## 3. 设计

### 3.1 模块与接口

新建 `ai/task/AiOrchestrationTaskAdmission.java`。名称明确表达：本刀接管的是 Agent / Workflow
编排任务，不是假装覆盖普通聊天、记忆提取、RAG 异步同步等全部 AI 工作。

```java
/**
 * AI 长任务的统一提交入口。
 *
 * <p>提交者只提供「这是谁的任务、是什么任务、要跑什么」，
 * 准入判断、线程分配、MDC 恢复、UserContext 设置、观测埋点都在模块内完成。
 *
 * <p><b>两段式</b>：{@link #precheck} 在构造任何 SSE 事件之前调用（尽量把拒绝提前到 HTTP 状态码），
 * {@link #submit} 在订阅时调用（权威判定）。两段都可能抛 AiTaskRejectedException，但语义不同。
 */
public interface AiOrchestrationTaskAdmission {

    /**
     * 前置检查（<b>best effort，不做任何状态变更</b>）。必须在构造任何 SSE 事件之前调用。
     *
     * <p>超限抛 AiTaskRejectedException（USER_LIMIT → 429 / POOL_FULL → 503），
     * 让 GlobalExceptionHandler 能在响应提交前改状态码。
     *
     * <p>⚠️ 这是<b>优化</b>不是保证：它放宽的是「拒绝的时机」，不是「上限本身」。
     * 检查通过不代表提交一定成功（池状态随时在变）——所以名额的增减不在这里。
     */
    void precheck(AiTaskRequest request);

    /**
     * 准入并提交（订阅时调用）。<b>这是权威判定</b>：
     * 原子占用用户名额 → 提交线程池；任一步失败即回滚并抛 AiTaskRejectedException，
     * 由调用方转成<b>流内错误事件</b>（此时响应已提交，状态码改不动了）。
     *
     * <p>用户上限在这里<b>严格执行</b>——竞态的结果是「这个任务被拒」，不是「多跑一个」。
     *
     * <p>调用方不拿 Disposable：连接断开不是业务取消，不能把 Reactor dispose 冒充成 LLM 取消。
     */
    void submit(AiTaskRequest request, Runnable task);
}
```

**任务上下文**（`AiTaskRequest`）：

| 字段 | 用途 |
|---|---|
| `userId` | in-flight 准入计数 + `UserContext` 设置 |
| `taskType` | `AGENT` / `WORKFLOW_CONFIRM` / `WORKFLOW_CREATE` / …（观测与拒绝文案） |
| `businessRef` | 日志关联。创建前拿不到 runId 的路径使用 `requestId/sessionId`，已有 Workflow 才使用 `runId:action` |
| `logContext` | MDC 恢复（沿用 `MdcContext.wrap`） |

**模块内部统一做的事**（调用点不再各写各的）：

```
precheck（构造任何 SSE 事件之前；纯读，不改状态）
  1. 读用户 in-flight 计数 → 超限抛 USER_LIMIT
  2. 读线程池状态 → 已满抛 POOL_FULL（判法见 §3.4）

submit（订阅时；权威判定）
  3. 原子 compute 占用用户名额；递增后超限 → 回滚递增 + 抛 USER_LIMIT
  4. 提交线程池；被拒 → 立即回滚名额 + 抛 POOL_FULL
     （此时 task 根本没进 worker，finally 不会执行）
  5. worker 启动后：running +1、UserContext.set(userId)、MdcContext.wrap
  6. 计时：queueWaitMs / executionMs
  7. worker finally：running -1、释放名额、UserContext.clear()

⚠️ 3 与 4 的回滚都不能漏——漏了该用户就永久少一个名额（直到重启）。
```

### 3.2 准入：每用户并发上限

- 计数：`ConcurrentHashMap<Long, Integer>`，用 `compute/computeIfPresent` 对单个 userId 原子增减
- 上限：**初始 2**，配置项 `blog.ai.task.max-concurrent-per-user`
- 超限：抛 `AiTaskRejectedException`，**不是排队**——`precheck` 抛在 HTTP 层（429），
  `submit` 抛在流内（错误事件），两个时机见 §3.1；**上限本身是硬的**，不存在"多跑一个"

这里限制的是 **in-flight = 正在运行 + 已进入队列**，不是只数 running。否则同一用户仍可先把队列灌满，
等 worker 慢慢执行，用户级上限就失去保护队列的意义。

不要使用「`AtomicInteger.decrementAndGet() == 0` 后 `map.remove`」的朴素写法：释放线程准备 remove 时，
另一个提交线程可能刚把同一个 `AtomicInteger` 从 0 加回 1，随后旧线程仍把它 remove，导致活跃任务从计数表消失。
`ConcurrentHashMap.compute` 按 key 串行化更新，计数归零时直接返回 null，才能同时保证原子性和 map 清理。

**为什么是内存计数**：见 §4.1。

⚠️ **正常执行的计数释放必须放在 worker 的 `finally`；线程池提交被拒时必须在 catch 中立即回滚**。
只写前一半会导致「池满拒绝一次，该用户却永久少一个名额」。这两条都要专门测试（§6）。

### 3.3 线程池

新建 `config/AiTaskAsyncConfig.java`，但不能原样照抄 `ArticleRagAsyncConfig`：长任务不能用
`CallerRunsPolicy`，而且当前调用点已经显式捕获 `MdcContext.LogContext`，这里统一由
`MdcContext.wrap(tracer, logContext, runnable)` 恢复，**不再叠加 `ContextPropagatingTaskDecorator`**，避免两套上下文传播互相覆盖。

```java
ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
executor.setCorePoolSize(workers);     // 固定 worker 数；初始值 8——是成本闸门不是容量结论，口径见 §7
executor.setMaxPoolSize(workers);      // 必须与 core 相同，保证“worker + queue”容量可预测
executor.setQueueCapacity(queue);      // 默认 32，**必须是有界的**
executor.setThreadNamePrefix("ai-task-");
executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());   // ⚠️ 不是 CallerRunsPolicy
```

不需要为了保留旧调用形状再桥接成 Reactor `Scheduler`。统一模块直接调用 `executor.execute(...)`，
调用方只依赖 `AiOrchestrationTaskAdmission`，不再知道底层是 Reactor 还是 Spring Executor。

为什么固定 `core == max`：`ThreadPoolExecutor` 的规则是先开到 core，随后**先入队**，只有队列满后才扩到 max。
原稿的 `core=8/max=16/queue=32` 会让第 9～40 个长任务先排队，明明允许 16 个 worker 却迟迟不用，
`queueWaitMs` 会被配置本身人为放大。固定 worker 数的容量模型最简单：同时执行 `workers` 个，额外排队 `queue` 个。

**全局容量不另设计数**——`workers + queue` 本身就是容量真相。`ThreadPoolTaskExecutor.execute()` 在队列满且
worker 全忙时由 `AbortPolicy` **同步抛出** `TaskRejectedException`（`RejectedExecutionException` 的子类），
提交线程 catch 住即为 `POOL_FULL`，不需要任何额外信号源。

> 初稿在这里加了一层 `Semaphore.tryAcquire()` 维护全局 permit，**已删除**：那会同时存在两套容量真相
> （还要处理 permit 的领取、回滚与两边状态的竞态），而它换来的"提交时立即识别"线程池本来就给。

⚠️ executor 拒绝分支**不是理论上的死代码**（应用关闭、配置错位都能走到）。无论何种原因被线程池拒绝，
都必须在 catch 中立即回滚**用户计数**——此时 task 根本不会进入 worker 的 `finally`，等它释放就永远等不到。

配置项放 `BlogAiProperties`（沿用现有 `Async` 内部类的风格）：

- `blog.ai.task.worker-count`：默认 8
- `blog.ai.task.queue-capacity`：默认 32
- `blog.ai.task.max-concurrent-per-user`：默认 2

### 3.4 拒绝：按原因区分 HTTP 429 / 503，且必须发生在**任何** SSE 事件之前

新建 `AiTaskRejectedException extends BusinessException`，携带 reason：

| reason | HTTP | 语义 |
|---|---:|---|
| `USER_LIMIT` | **429** | 当前用户已有过多 Agent / Workflow 长任务 |
| `POOL_FULL` | **503** | 全局 worker + queue 容量已耗尽，服务暂时过载 |

`GlobalExceptionHandler` 根据 reason 返回对应状态。不能把用户自己的并发超限也伪装成服务整体故障。

**为什么不能走项目默认的 `200 + Result` JSON**：前端聊天走 SSE，**只在 `!response.ok` 时才解析 JSON 错误体**；200 时它把整个 body 当 SSE 流读，只认 `data:` 前缀的行，JSON 错误体被**静默忽略**——用户看到的是「连接意外中断」，完全指不到根因。

> 这与额度不足的处理是同一个坑，当时也是单独走 HTTP 402 解决的。同一类问题第二次出现，说明这是**本项目错误响应的既有约束**，不是特例。

⚠️ 仅仅在原来的 `Flux.create` 里把 `schedule(...)` 换成 `submit(...)`，**不保证能返回非 2xx**——
多条创建路径是 `Flux.concat(前置事件, workflowEvents)`，前置事件一发响应就按 200 SSE 开始了。
详见下方阻塞点分析。

**⚠️ 这是本刀实施前唯一的阻塞点**——三条约束同时成立：

1. **必须是「任何 SSE 事件之前」**，不只是 `PARAM/AGENT_STEP/WORKFLOW_STEP`。
   **聊天里创建 Workflow 这条路径已经确定做不到**——`AiMessageServiceImpl:325-337` 是

   ```java
   Flux<AiChatEventVO> understanding = buildWorkflowUnderstandingStatusFlux(intent, routeDecision);
   return Flux.concat(understanding, streamCreateArticleWorkflowFromIntent(...));
   ```

   `understanding` 是 `Flux.just(...)`，订阅瞬间就发出去。同类前置事件还有
   `SESSION_PLAN_ANCHOR_USED`（:1472）、`CHAT_STATUS`（:511，事件类型 4001）。
   **这条路径不用测**：在订阅 lambda 里抛拒绝，响应早已是 200 SSE。
2. 位置前移到返回 Flux 之前 → **任务拿不到 `sink`**（emitter 依赖它，`AiMessageServiceImpl:888`），
   事件发不出去。
3. 全局池满若只能在订阅时得知 → `POOL_FULL` 同样退化为流内错误。

> 修正：本条初稿写的是"倾向于返回 Flux 之前，惰性损失接近零"——**论证有盲点**（`sink` 拿不到）；
> 第二轮改写的"两阶段准入"也**不完整**（只前移了用户名额，全局池满仍要等到订阅时才判定）。

### 解法：准入**检查**前置，提交仍留在订阅时

| 拒绝类型 | 判定时机 | 判定方式 | 结果 |
|---|---|---|---|
| `USER_LIMIT` | 返回 Flux 之前 | 读用户计数（**纯检查，不改状态**） | **HTTP 429**，覆盖绝大多数 |
| `POOL_FULL` | 返回 Flux 之前 | 读线程池状态（`getActiveCount()` + `getQueue().size()`） | **HTTP 503**，覆盖绝大多数 |
| 极小窗口漏网 | 订阅时提交被拒 | — | 降级为**流内错误事件** |

**名额增减放在订阅时**（原子 `compute` 递增 / worker `finally` 递减，天然配对）——
避免"返回了 Flux 但没被订阅 → 名额永久泄漏"。

⚠️ **前置检查通过 ≠ 可以多跑一个**：订阅时的 `compute` 是**权威判定**——递增后若超限，
**回滚递增并抛 USER_LIMIT**（调用方转成流内错误事件），**不会真的多启动一个任务**。
前置检查放宽的是「拒绝的时机」，**不是「上限本身」**。用户上限**始终是硬上限**——
这是本刀作为成本闸门的前提，降级成"尽量限制"就失去了意义。

> 修正：本条初稿写的是"极端并发时可能多跑一个，后果只是多花点钱"——**这是错的**，
> 与「每用户并发上限 2」直接冲突，等于把硬上限降级成软上限。

**前置检查的实现要点（best effort，不承担正确性）**：

- 线程池**关闭**要单独判断（`isShutdown()`）——关闭与队列满是两种不同原因
- 队列满优先用 `getQueue().remainingCapacity() == 0`
- 读快照本身非原子，**允许偏差**：它只负责"尽量早拒绝"

⚠️ **正确性完全由订阅时那段保证**，不能因为前置检查通过就省掉任何一步：
名额递增 → `execute()` → catch `TaskRejectedException` → 回滚名额。
**订阅时提交仍可能被拒，而那时 task 没进 worker、`finally` 不会执行**——回滚漏了该用户就永久少一个名额。

⚠️ **「检查」不等于「预占」，这正是不需要重新引入 Semaphore 的原因**：

- `Semaphore` 是**准入凭证**——拿到才提交、必须严格释放，所以有 permit 回滚与状态竞态（§3.3 已论证删除）
- 状态读数是**观测量**——允许微小偏差，偏差后果只是"极罕见情况下降级成流内错误"

池满在全项目**几乎走不到**（§1.2 实测：200 线程 + 10 万队列）。为最后那个极小窗口
引入一套凭证生命周期管理不划算——**这是取舍，不是"保证 100% 正确"**：本方案确实做不到 100%。

**必须锁定的集成测试**（Controller / MockMvc 级，按此顺序）：

1. 先在**原地（订阅 lambda 内）**抛拒绝异常，看状态码到底能不能改（这决定后续改动量）
2. 测**没有前置事件**的两条流：Agent 流 · Workflow 确认流
3. 测**聊天创建 Workflow 流**——目标不是"订阅时能否返回 503"（`Flux.concat` 已证明不行），
   而是**确认前置检查确实排在 `understanding` / `CHAT_STATUS` 之前**
4. 断言响应状态码 · 首个 SSE 事件 · 任务是否真的没启动 · **用户名额是否回滚**
5. 断言**没有残留**：Workflow run / session 绑定 / 孤立消息（拒绝发生在任务启动前，
   但代码可能已经先建了消息记录）
6. 路径 1 对**所有流形态**都失败后，再讨论两阶段或 `Sinks.Many`

前端还要补一处现有不对称：聊天流在 `!response.ok` 时会解析 JSON 的 `message`，但
`streamWorkflowAction` 目前只显示 `请求失败 (status)`。本刀把两处收口到同一个错误体解析 helper，
否则后端虽然返回了「当前并发任务过多」，Workflow 用户仍只能看到裸 `429/503`。

### 3.5 观测

新增日志前缀 `[AI-TASK]`，字段：

| 字段 | 说明 |
|---|---|
| `taskType` | 区分 Agent / Workflow |
| `queueWaitMs` | 从提交到 worker 起来（**推广现有实现**，见 1.3） |
| `executionMs` | worker 实际执行时长 |
| `inFlight` | 已准入总数：running + queued |
| `inFlightOfUser` | 该用户已准入数：running + queued |
| `running` | worker 已实际开始执行的数量 |
| `rejected` | 拒绝原因（`USER_LIMIT` / `POOL_FULL`）。**对外只有这两个**；内部再分 `QUEUE_FULL` / `EXECUTOR_SHUTDOWN` 便于排查，不暴露给前端 |

**统一成一条完成日志**（成功与拒绝共用前缀，便于 grep 统计 P50/P95/P99）。

⚠️ **不要再用 `updated_at − created_at` 推算线程占用**——那里面混着等人工的时间（见 1.4 ②）。

### 3.6 改造的 7 个调用点

每处的改动形状：

```java
// 改前
Schedulers.boundedElastic().schedule(wrapAgentLogContext(logContext, () -> {
    UserContext.set(userId);
    try { ... } finally { UserContext.clear(); }
}));

// 改后
aiOrchestrationTaskAdmission.submit(
        AiTaskRequest.of(userId, AiTaskType.AGENT, requestId + ":" + sessionId, logContext),
        () -> { ... });
```

`taskType` 按用途区分（这样观测才有意义）：`AGENT` / `WORKFLOW_CREATE` / `WORKFLOW_CONFIRM` / `WORKFLOW_RETRY` / …

---

## 4. 关键取舍

### 4.1 为什么不用 Redis 计数（跨实例准入）

- 项目现在**单实例**，内存计数的生命周期跟着进程走——**进程挂了计数自然消失，没有残留**
- Redis 方案反而要额外处理「任务异常结束没 DECR」的脏计数，**复杂度换来的跨实例能力现在用不上**
- 升级路径写在 §7，触发条件明确

### 4.2 为什么不用 `CallerRunsPolicy`

现有多个异步池使用 `CallerRunsPolicy`（池满时调用者线程执行）——对**短任务**可以是有意的降级，
对**几十秒到几分钟的长任务**等于把调用者线程占死。而这里的调用者是**订阅线程**，
在 SSE 场景下就是**请求处理链路上的线程**，隔离直接失效。

### 4.3 为什么拒绝选择 HTTP，而不是新增 SSE error 事件

技术上可以新增一种结构化 SSE error 事件；不能拿“200 时不解析普通 JSON”证明 SSE error 做不到。
这里选择 HTTP 非 2xx 的理由是语义：准入失败表示任务**从未被系统接受**，没有 run、没有排队位置，
应该在流建立前明确拒绝，而不是先返回 200 再在业务流里宣告失败。前提仍是 §3.4 的 HTTP 集成测试通过。

### 4.4 为什么准入要排在计费预扣之前

普通聊天现在已经是「预扣 → 调用 → 结算」，但它**不属于本刀 7 个调用点**。本节是未来 Agent / Workflow
接入计费时的顺序约束：如果准入在预扣之后拒绝，就得把预扣释放掉，多一条容易漏的清理路径；应先准入再预扣。

> 现在 Agent / Workflow 还没接计费（免费），顺序天然是对的。**接的时候别搞反**。

---

## 5. 不做 / 暂缓

### 5.1 任务化 + 轮询（暂缓）

**原始理由不成立**：SSE 返回的是 `Flux`，Spring MVC 走 `ReactiveTypeHandler` → `DeferredResult` → **Servlet 异步**，Tomcat 请求线程在订阅后就释放了——它占的是 AsyncContext（内存），不是线程。

**而且轮询解决不了取消**：任务本来就通过 `schedule()` 脱离了请求线程，SSE 断开只是不再发事件、worker 照跑。改成轮询**只改变通信方式**，不会中断正在阻塞的 LLM 调用。

**恢复条件**：出现 Nginx/SSE 频繁断开、应用重启后需要恢复、跨实例调度等**真实需求**时再评估。

### 5.2 协作式取消（第二刀）

一期只做「**当前 LLM 调用结束后，不再执行下一步**」——在步骤之间检查取消状态。这是可实现、可验证的取消语义。

**要强制中断正在进行的 HTTP 调用**，必须先确认底层客户端支持可取消请求；**靠轮询实现不了**。

### 5.3 `RUNNING` 残留的启动恢复（**✅ 已实施 2026-09-19**，实现见 `AiRunStartupRecoveryRunner`）

**为什么不能拖**：本刀之后，准入是**内存计数**（重启自然清零），run 状态是 **DB 记录**（重启原样残留），
两者语义直接冲突——DB 里躺着几个 `RUNNING`、准入说 0 个在跑。不修的话，本刀引以为据的
"系统知道现在有几个任务在跑"就是假的。

**⚠️ 最大的坑：不能把「未完成」等同于 `RUNNING`**

`WorkflowStatusSupport.isFinished()` 只认 `COMPLETED / CANCELLED / FAILED`，
而 `WAITING_*_CONFIRM` **不在其中**——那是**等用户点确认**的合法状态，流程是 DB 驱动 + handler 按状态分发，
重启后用户回来点「确认」照样能继续推进。

所以清理条件必须是 `status = 'RUNNING'`，**不是** `status NOT IN (终态)`。
按后者写会**误杀所有等确认的 Workflow**——用户没提交的方案凭空消失。`ai_agent_runs` 的 `WAITING_USER` 同理。

**恢复到什么状态**：`RUNNING → FAILED`，`errorMessage = "应用重启导致任务中断，请重新发起"`。
**不用 CANCELLED**——那是用户主动取消的语义，进程崩溃不是用户的选择，混用会让"取消率"这类统计失真。

**两张表都要处理**：`ai_workflow_runs` 与 `ai_agent_runs` 的 `status = 'RUNNING'`。

**排除清单（逐条核实自枚举定义）**：

| 表 | 排除 | 依据 |
|---|---|---|
| `ai_workflow_runs` | 6 个 `WAITING_*_CONFIRM`、`PAUSED`、`WAITING_USER_SAVE` | `AiWorkflowStatus` 注释：`PAUSED`「流程被暂停，**不属于失败**」；`WAITING_USER_SAVE`「已废弃：仅保留兼容存量 run 数据」 |
| `ai_agent_runs` | `WAITING_USER` / `WAITING_WORKFLOW_CONFIRM` / `WAITING_WRITE_CONFIRM` / `CONFIRMING` | `AiAgentRunStatus` 注释：前三个都是「等待用户确认」的终态；`CONFIRMING` 是「confirm 进行中的短转移态，仅后端内部」 |

> **`CONFIRMING` 属于 Agent run（不在 Workflow 枚举里）**——它由 `AgentRunSuggestionService.confirm()` /
> `AgentWriteActionService.confirmWrite()` 的 CAS 写入，两个方法**都是 `@Transactional`**
> （注释：「失败整体回滚，run 自然回到 WAITING 可重试」）。未提交事务在进程崩溃时 DB 会回滚，
> 所以它**不会持久残留**，不纳入启动恢复。

> **`PAUSED` 是「定义了但没有写入点」的状态**：全量检索只在枚举定义、`pauseReason` 字段注释、
> 显示文案（"已暂停"）各出现一次，**没有任何地方 set 它**。排除它语义正确、成本为零，
> 但不需要为它写专门逻辑——真出问题只可能来自存量数据。

**session 锚点：已有现成自愈，不需要专门清**

`WorkflowRunManager.checkActiveWorkflowConflict()`（:72-76）早已带脏绑定自愈：
run 不存在或 `isFinished` → 自动清 `activeWorkflowRunId` 并放行。
所以**只要 run 被正确标成终态，session 锚点自己就会走上这条路**，不需要另写启动清理。

> 修正：本条初稿写的是"两条必须一起清，否则会话卡死且用户无法自救"——**核实代码后不成立**。
> 自愈逻辑一直在，卡死只发生在 `RUNNING` 残留期间，而那时 run 状态本身就是错的，根因只有一个。

⚠️ **但"后端自愈"不等于"UI 恢复正常"**——本条初稿写成"窗口最长持续到下次发起 Workflow"，
**核实后不准确**。前端 `AiAssistant.vue:1786` 的判断是：

```js
// 注释写的是「结束态的 workflow 不该恢复为 active」，代码却只列了 2 个
if (res.data.status === 'COMPLETED' || res.data.status === 'CANCELLED') {
    activeWorkflow.value = null
}
activeWorkflow.value = res.data        // FAILED / PAUSED 落到这里 → 继续当活动面板
```

所以 `RUNNING → FAILED` 之后，前端仍会把失败的 run 挂成活动面板——**不是窗口期，是持续显示**。

**这是存量 bug，不是本刀引入的**（任何 Workflow 失败后用户刷新页面都会踩到），
但启动恢复会**批量产生 FAILED** 从而放大它。所以它列为**本刀必须顺带修的前端项**：
把 `FAILED` 并入终态判断。**不能笼统写成"后端修好后 UI 自然正常"。**

> 实施修正：初稿写的是「补 `FAILED` / `PAUSED`」，实际**只补了 `FAILED`**。
> `PAUSED` 语义上是「可恢复」而不是终止（枚举注释：「流程被暂停，**不属于失败**」），
> 且后端**没有任何写入点**——并入它属于「为不存在的问题写代码」。
> 将来实现暂停/恢复时，要连同它的面板操作区一起设计。

> session 锚点本身要不要在启动时清：**可选**。前端终态判断修好后，拉回来也只会挂成消息卡片，
> 不会变成活动面板；清锚点只是省掉一次无谓的拉取。

**保留 run / step / trace 供排查**：只改状态，不删记录。

**不做实例判据**：单实例部署下，"启动时把 `RUNNING` 标中断"**就是精确解**，不是简化版——
本实例刚启动，不可能有自己创建的 `RUNNING`，全量标记不会误伤任何东西。
加 `instanceId` 需要 run 表加列 + 启动写标识 + 只清理非本实例的，单实例下这些代码全部空转，
正确性增量是零（同类教训见五刀第 2 刀：定时任务互斥被推翻，为不存在的问题写代码）。
**触发条件**：多实例部署 / 滚动发布时判据才变成真问题，那时再加。

---

## 6. 验证方式

```
./mvnw test -Dtest='AiOrchestrationTaskAdmission*Tests'
```

必须覆盖：

| 用例 | 锁住什么 |
|---|---|
| 同一用户提交 3 个（上限 2）→ 第 3 个被拒 | 准入生效 |
| 同一用户并发提交大量任务，任何时刻成功数不超过 2 | `compute` 原子准入，不是只测串行提交 |
| 任务抛异常 → 该用户 in-flight 归零，仍可提交 | worker `finally` 释放用户计数 |
| executor 拒绝提交 → 用户计数立即回滚 | 拒绝发生时 task 根本不会进入 worker `finally` |
| worker + queue 已满 → `TaskRejectedException` → `POOL_FULL`，其他用户也不能继续提交 | 全局有界容量由线程池自身保证 |
| 用户超限返回 HTTP 429；全局满返回 HTTP 503 | 拒绝原因没有混成一个状态码 |
| 拒绝响应发生在首个 SSE 事件之前 | 锁住 §3.4 的响应提交时机；必须是 Controller/MockMvc 集成测试 |
| 聊天流与 Workflow 流都展示后端 JSON `message` | 前端不只显示裸状态码 |
| `restoreActiveWorkflow` 拿到 `FAILED` 的 run 时不设为 `activeWorkflow` | 存量 bug 修复，锁住终态判断（§5.3） |
| 7 个调用点都带 `taskType` 与 `queueWaitMs` | 收口完成，无遗漏 |
| worker 结束后 `UserContext` 与 MDC 不残留 | 专用线程复用时不串用户/trace |

**反向验证**（项目惯例，各自精确停在预期断言上）：
- 去掉准入上限 → 第 3 个不再被拒
- 把计数递减从 `finally` 移出 → 异常用例红
- 删除 executor 拒绝分支的用户计数回滚 → 再提交用例红
- 把 `AbortPolicy` 换成 `CallerRunsPolicy` → 队列满用例红（长任务退回调用者线程）

---

## 7. 已知缺口与升级路径

| 缺口 | 触发条件 / 升级路径 |
|---|---|
| **跨实例准入失效** | 多实例部署时换成 Redis 计数。要处理脏计数：Lua 原子 INCR+EXPIRE，TTL 取任务最长耗时的若干倍，并接受"进程崩溃后计数要等 TTL 才释放" |
| **不保证严格租户公平** | 单个用户最多占 2 个名额，但若干较早提交的用户仍可占满 worker + queue；线程池"直接开线程"与"入队"两条路径并存，整体也不承诺严格 FIFO。要做到用户间轮转需要分区调度，现在不做 |
| **`AiMessageServiceImpl` 的 6 处 taskType 需要逐个确认** | 它们不是同一个任务，实施时要逐个看上下文，不能一刀切 |
| **`RUNNING` 残留**（独立 bug，本刀不修） | 与准入的内存计数语义冲突。**紧接着单独修**，方案与坑（`WAITING_*` 不能误杀、锚点有现成自愈、不做实例判据）见 §5.3 |
| **`workers` 是成本闸门，不是容量结论** | 它是**同时向供应商发起的并发请求数上限**，直接决定花钱速率。定它的依据是**供应商并发限制 + 成本速率 + HTTP 连接池 + 可接受的排队延迟**的综合，**不能按 CPU 核数或固定的等待/计算比推导**（IO 密集任务的瓶颈在外部服务，不在本机）。149 条历史 run 只能估**量级**，不能当容量证明；上线前按 `queueWaitMs` P95 调整 |
| **长事务：LLM 在 `@Transactional` 里跑** | `createWorkflowInternal` / `approve` / `reject` / `retry` 的事务范围都包含 `runInitialSteps` 的 LLM 调用，8 个准入 worker 同时跑就占 8 个 DB 连接几十秒。本轮只把连接池显式放大到 20（见 §8.6）**不是根治**。根治要把 LLM 调用移出事务边界（先落状态、再异步推进），是独立一刀 |
| **`runAdmitted` 占的是 Tomcat 线程** | Agent 建议确认必须同步返回 VO，所以只能跑在调用者线程上——它比专用池 worker 更稀缺（Tomcat 默认 200，且被所有 HTTP 请求共享）。在把它改成异步 / SSE 之前，这条路径仍会占住一个 HTTP 线程几十秒。改名/改协议是前端契约变更，暂不做 |
| **三个 `@Async` 池用 `CallerRunsPolicy`**（本轮新发现） | `memoryCandidateTaskExecutor`(1/2/30) / `articleRagTaskExecutor`(2/4/100) / `conversationSummaryExecutor`(1/1/50)——池满时长任务**退回调用者线程**。触发条件苛刻（要 33 / 106 / 52 个任务同时排队），但一旦触发就是「用户在等 HTTP 响应时跑几十秒 LLM」。**不能简单改 AbortPolicy**：这几类任务都不该丢（RAG 索引丢了文章搜不到、记忆丢了就缺一条），要改得配「有界队列 + 拒绝时落延迟队列重试」（`articleRagTaskExecutor` 已有 `RagRetryQueue` 可复用）。`memoryRecallExecutor` 的 CallerRuns 是**故意的**——注释写明「宁可慢也不丢」，且它在请求线程路径上 |
| **`memoryCandidateTaskExecutor` 的注释已过时**（本轮新发现） | 注释写「任务极轻（规则判断 + 一次 DB insert）」，但 `AiEpisodicMemoryExtractorServiceImpl:150` 实际在里面跑 LLM。这也是上一行那条风险被低估的原因 |

---

## 8. 实施后补漏：当时漏掉的入口（2026-09-19 第二轮）

### 8.1 漏因：只按一种**形态**去找

§1.1 那 7 处是搜 `Schedulers.boundedElastic().schedule(...)` 搜出来的，一处不落。
但「长任务入口」还有另一种形态：**HTTP 同步接口里直接跑 LLM**。
这类入口不走 Reactor、不提交任何池，那一搜**结构性地看不见它**。

教训：收口要找的是「**跑 LLM 的入口**」这个语义，不是某种代码写法。
换个搜法（比如搜「所有创建 run 的调用点」）当时就能发现。

### 8.2 漏 1：Agent 建议确认（前端在用，最实的一处）

```
前端「继续」按钮 → POST /ai/agent-runs/{id}/workflow-suggestion/confirm
  → AgentRunSuggestionService.confirm()           @Transactional
  → createWorkflow(run, suggestion)
  → aiWorkflowRunService.createXxxWorkflow(dto)    ← 单参数重载 = 同步版
  → createWorkflowInternal(...)                    @Transactional
  → handler.runInitialSteps(run, emitter)          ← 跑 LLM
```

`runInitialSteps` 以 `OPTIMIZE_ARTICLE` 为例：

```
LOAD_ARTICLE → ANALYZE_ARTICLE(LLM) → MEMORY_RETRIEVE → RAG_SEARCH → GENERATE_OPTIMIZATION_PLAN(LLM) → WAITING_PLAN_CONFIRM
```

**跑完全程才返回**——用户的 POST 要挂几十秒。后果三条：

1. **每用户上限形同虚设**：开多个会话各跑出一张建议卡，逐个点「继续」就能同时跑 N 个
2. **不占专用池，占 Tomcat 线程**：池容量 8 这个闸门在这条路径上根本不生效
3. **全程在 `@Transactional` 里**：LLM 调用期间一直握着 DB 连接

### 8.3 修法：`runAdmitted`（占名额 + 当前线程执行）

⚠️ **只加 `precheck` 是假保护**——precheck 只读计数、不加计数，
用户连点确认每次都读同一个数、每次都通过，上限完全失效。
**占名额必须发生在真正执行的那一刻。**

于是准入有了第三种执行形态：

| 形态 | 跑在 | 占名额 | 占池 | 用途 |
|---|---|---|---|---|
| `precheck` | 调用者线程 | ❌ | ❌ | 前置检查，best effort |
| `submit` | 专用池 worker | ✅ | ✅ | SSE 流式入口 |
| `runAdmitted` | **调用者线程** | ✅ | ❌ | HTTP 同步入口 |

两个**刻意的不做**：

- **不检查池容量**：这条路不往池里投任务，池满与它无关。
  拿池的状态去拒绝它，等于「系统忙的时候连确认都不让点」——池满时它反而更该放行
- **不包装 MDC / UserContext**：执行发生在调用者线程，上下文本来就在。
  包装不仅多余，还可能覆盖调用者已有的 trace scope

被拒时抛的 `AiTaskRejectedException`（RuntimeException）触发 `@Transactional` 回滚 →
run 自然回到 `WAITING_WORKFLOW_CONFIRM` 可重试（与 Handler 抛异常走同一条路径）。

### 8.4 漏 2：`AiWorkflowController` 的 6 个非流式接口

`article/create` · `article/optimize` · `learning-plan/create` · `{id}/approve` · `{id}/reject` · `{id}/retry`
——单参数重载（不传 emitter）会同步跑完整个 Workflow 初始步骤。

前端 `ai.ts` 早已标注「已废弃，已全面切到流式」，**但接口还暴露着**：
登录用户一条 curl 就能绕开准入起 Workflow。

**处理：删除**。同步版没有调用方，留着它就是留一个绕过后门。
service 的单参数重载**保留**（测试在用，也是同步执行路径的底层），
但 controller 类 Javadoc 里写明：**将来要同步启动 Workflow，走 `runAdmitted` 占名额，不要直接调重载**。

### 8.5 漏 3：测试漏了 `WAITING_USER_SAVE`（轻）

§5.3 的排除清单表格里点了这个名，测试的 7 个状态里却没有它。
行为本来就对（条件是 `eq(RUNNING)`，天然不碰），但**测试覆盖与设计稿清单不一致**——
哪天有人把实现改成「排除清单」写法，这条就没人拦。已补。

### 8.6 顺带：HikariCP 默认 10 vs 准入 8（本轮新发现）

`createWorkflowInternal` / `approve` / `reject` / `retry` 全是 `@Transactional`，
**事务范围包含 `runInitialSteps` 的 LLM 调用**。也就是说：

> 8 个准入 worker 同时跑 = 8 个长事务 = **8 个 DB 连接被占住几十秒**

而两个 yml 里都**没有** HikariCP 配置 → 用默认 `maximumPoolSize = 10`。
只剩 2 个连接要服务全局所有普通请求（页面 / 列表 / SSE 流里的查询 / 缓存回源），
高峰期直接卡到拿不到连接。

**修法**：显式配 `maximum-pool-size: 20`（两个 profile 都加，注释写明与 `workerCount` 的关系）。

> 这条是**这一刀的直接后果**——`worker=8` 是这次定的，定的时候没算 DB 连接这笔账。
> 注意它只是把池放大，不是根治：LLM 仍在事务里（见 §7）。

### 8.7 反向验证（三处，都精确命中）

| 改什么 | 期望 | 结果 |
|---|---|---|
| `runAdmitted` 去掉 `acquireUserSlot` | `runAdmittedOccupiesUserSlot` 红 | ✅ 精确红（其余 9 条绿）|
| `runAdmitted` 加 `isPoolFull()` 检查 | `runAdmittedIgnoresPoolCapacity` 红 | ✅ 精确红 |
| 建议桥换回直接调 `createWorkflow` | `confirmRejectedByAdmissionDoesNotStartWorkflow` 红 | ✅ 精确红 |

> **顺带踩到一个 Mockito 陷阱**：用 `when(...)` 覆盖**已有** stub 时，
> 旧 stub 的 answer 会**先被执行**（stub 注册本身就是一次 mock 调用，参数是 `any()` 产生的 null）
> → 立刻 NPE，新 stub 根本没注册上。覆盖已有 stub 必须用
> `doThrow(...).when(mock).method(...)` 形式。这个坑第一次跑测试就撞上了。

### 8.8 验证与产物

- 后端 35 例全绿：`AiOrchestrationTaskAdmissionTests`(10) + `AgentRunSuggestionServiceTests`(16)
  + `AiRunStartupRecoveryTests`(4) + `SseAdmissionProbeTests`(5)
- 前端 `npm run build` 通过（删死方法后无残留引用）
