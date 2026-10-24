# Agent 框架评估：框架 vs 手搓的边界（V4⑥ 产出）

> **状态**：**逐项对照已完成（2026-09-12）**，结论见 §五。
> **来源**：2026-09-10 老大与 Codex 的讨论 + Claude 补充；2026-09-12 完成 LangGraph / LangChain4j / MCP 逐项对照。
> **用途**：V4⑥「与 LangGraph / AgentExecutor 能力地图对照」的正式产出；面试讲法见 §七。

## 〇、结论先行

1. **核心逻辑手写，边缘功能用框架。**
2. **不引入 LangGraph / LangChain4j 接管或替换现有 Runtime**——两条硬理由：**双重状态机**（§二）+ **HITL 语义冲突**（§5.1）。
3. **手写不是天然更好**——它成立的前提是补齐一整套工程化措施（§四），否则只是「我自己写所以我懂」。
4. 框架评估的产出是**对比结论**，不是引入决策——**对照已于 2026-09-12 完成**（§五）。
5. **可视化不需要引入 Agent 框架接管 Runtime**：LangGraph Studio 只能读它自己的图，读不了我们的 run / step（§5.2）。
6. **MCP 可以作为周边工具协议评估，但只接只读工具，写动作不走**（§5.3）。

## 一、边界划分

### 手写：业务语义、状态、裁决、权限

| 手写的东西 | 为什么必须自己拿 |
|---|---|
| Agent Runtime 循环 | 有界循环的每一步语义、失败留痕、终止条件——框架的抽象会把它们藏起来 |
| `AgentDecision` / action 白名单 | 动作白名单是安全边界，不能让通用设计替你决定 |
| Planner 最终裁决 | 路由决策的后端裁决权，LLM 只能建议 |
| 文章目标解析 + 权限判断 | **权限判断不能外包**（读权限 `isReadable` / 写路径归属校验单点） |
| Workflow 状态机与确认门 | 确认门是产品语义（用户点头才能写），不是通用 interrupt |
| 结论锚、CAS、幂等 | 并发正确性依赖具体存储语义（雪花 ID 单调性、条件更新） |
| Verifier 与保守收尾 | 「证据够不够、能不能出口」是本项目的核心裁判逻辑 |
| plan / observation / thought 的数据边界 | V3.13 划死的三级隔离——框架会把它们混成一个 state |
| 失败语义与终态语义 | COMPLETED / FAILED / WAITING_* 的具体含义 |

### 用框架 / 库：基础设施与适配

| 用的东西 | 用途 |
|---|---|
| Spring Boot | 启动、依赖注入、MVC |
| MyBatis-Plus | 数据访问基础能力 |
| Spring 事务管理 | 事务边界 |
| Jackson | JSON 解析 |
| Redis / MySQL / ES 客户端 | 存储与检索 |
| SSE | 传输 |
| **Spring AI / ChatClient** | **模型调用适配**（streaming / tool calling 协议层） |
| OpenTelemetry / Micrometer（候选） | 链路追踪、耗时观测、span 上报 |
| MCP Client（候选） | 外部只读工具 / 数据源接入协议 |

### 一条判据

> 可以使用 Spring AI 调模型，但**不能让 Spring AI 决定 Agent 状态机**；
> 可以使用 LangChain4j 的模型适配，但**不能让它成为写入权限的最终裁判**。

**状态、裁决、权限、失败语义——这四样永远手写。**

**注**：这不是设想，是**已验证的现状**。现在 ChatClient 只做模型调用适配，状态机在 `AbstractAgentRuntime`，白名单、CAS、确认门全在自己手里。

因此本文的「不引入」不是指周边框架一律不用，而是指**不让 Agent 框架接管 Runtime 状态机、确认门、权限裁决和失败语义**。周边能力仍然可以用框架：模型适配、协议适配、观测链路、只读外部工具接入，都属于可评估范围。

### 分层可用性（框架能用在哪一层）

| 层 | 是否可用框架 | 判断 |
|---|---|---|
| Agent / Workflow 状态机 | ❌ 不建议 | `WAITING_*`、确认门、CAS、失败语义、step 留痕是项目核心，不交给 LangGraph / LangChain4j |
| HITL / interrupt | ❌ 不建议 | LangGraph resume 会**重跑 node**，与「确认后执行真实写入」的模型冲突 |
| 模型调用适配 | ✅ 可以 | 现在已在用 Spring AI——**这就是「周边用框架」** |
| Tool Calling 协议层 | ✅ 可以 | 但后端仍要做裁决，**工具不能自己成为权限入口** |
| **可观测性** | ✅ **可以，而且适合** | OpenTelemetry + `traceId` + span，比接 LangGraph Studio 更贴合 |
| MCP | ✅ 可以，**但只读** | 外部文档 / GitHub / 搜索 / 知识库类工具可以；写动作不走 MCP |
| 多 Agent 编排 | ⏸ 以后再评估 | 真到 V5 Multi-Agent 时重新看 LangGraph / LangChain4j |

**一条统一判据**：框架只要**不持有业务状态、不做业务裁决**，就可以用；一旦开始持有中间态（graph state / checkpoint / `AgenticScope`），就会和 `ai_agent_runs` 打架。

## 二、为什么不引入 LangGraph：双重状态机

现在引入 LangGraph（或同类框架）会同时存在**两套状态**：

```text
我们已有的：
  ai_agent_runs / ai_agent_steps / ai_workflow_runs
  + 有界循环 + 失败步骤留痕 + Verifier 拦截 + ASK_USER 状态转换
  + 结论锚 CAS + Workflow 确认与重试

框架带来的：
  graph state / checkpoint / retry / interrupt
```

**两套状态一旦不一致，调试成本比现在更高**——而且不一致是必然会发生的：我们有业务语义（用户确认、证据收敛、会话结论继承），框架有它自己的通用语义，两者的中间态定义不同。

**关键判断**：我们手写的那些东西，恰恰是**框架最可能重新定义的地方**——有界循环、失败留痕、Verifier 拦截、ASK_USER 转换、结论锚、确认与重试。冲突是结构性的，不是配置问题。

## 三、评估前必读：我们已经有什么

**不先列这张清单，评估会变成重复劳动**——甚至可能引入框架去「实现」我们已有的东西，然后出现两套。

| 能力 | 现状 | 说明 |
|---|---|---|
| 状态持久化 | ✅ **已有** | `ai_agent_runs` / `ai_agent_steps` 落库 + inspection 只读 API |
| interrupt / human-in-the-loop | ✅ **已有** | `WAITING_USER` / `WAITING_WORKFLOW_CONFIRM` / `WAITING_WRITE_CONFIRM` 三种待确认态 |
| 可视化 | ⚠ **部分**（2026-09-12 更新） | V4 第一刀已落地**开发者只读面板**（run/step/plan/Verifier 拦截/观察重复）；无状态图与编排视图 |
| 重试与失败恢复 | ⚠ **部分** | Workflow 有 retry；Agent 循环没有系统性重试 |

**所以 Agent 框架「能补的」一度被判断为可视化与重试恢复**——但 §5.2 的调研推翻了这一条：Studio 只读它自己的图，**读不了我们的 run / step**，「用 LangGraph Studio 只读展示我们的数据」对我们并不成立。对照范围因此收窄为：**不引入 Agent 框架接管 Runtime**。周边观测仍走 OTel / traceId 这类基础设施路线。

## 四、手写的前提：工程化清单与现状

「完全掌控」不是自动成立的。手写的风险是：漏掉重试和超时 / 并发状态处理不完整 / 事务边界不清楚 / 事件与持久化和内存状态不一致 / **后续维护者不知道为什么这样写**。

补齐清单与我们的现状：

| 项 | 现状 | 备注 |
|---|---|---|
| 清晰边界 | ✅ 域钩子化（`AbstractAgentRuntime` 骨架 + 三域薄壳） | |
| 结构化日志 | ⚠ 有日志，**无 traceId** | 登记在工程化补课清单 |
| 状态快照 | ✅ run / steps 落库 + inspection | |
| 单元测试 | ✅ 55 个测试类 | |
| 评测集 | ✅ 评测门 56 例（离线 18 + 在线 38） | |
| 失败注入测试 | ⚠ 部分 | 有 fail-open 测试，无系统性失败注入 |
| 升级回归测试 | ⚠ 靠手测 | **全量回归一直没跑**（按约定只跑相关测试） |
| 设计文档 / 实施记录 | ✅ 每刀一份（`docs/agent/agent-v3-*.md`） | 应对「维护者不知道为什么这样写」 |

**这张表本身就是「手写站得住脚」的实证**——不是「我自己写所以我懂」，而是有测试、有评测、有审计、有文档。

三项 ⚠ 里，**traceId** 和**全量回归**是最该补的两块。

## 五、逐项对照（2026-09-12 完成）

> 依据 LangGraph / LangChain4j / MCP 的官方文档与 2026 年公开资料（来源见 §九）。

| 维度 | 我们现状 | LangGraph | LangChain4j | 结论 |
|---|---|---|---|---|
| 状态持久化 / checkpoint | ✅ `ai_agent_runs` / `ai_agent_steps` 落库 + inspection 只读 API | ✅ checkpointer：`thread_id` 主键 + super-step 边界快照 + 节点级 pending writes + time travel | ✅ `AgenticScope`（blackboard）+ `memoryId()` + `checkpoint()` + `serializableCopy()`；**模块仍是 `1.12.1-beta21`** | **重叠**——引入即两套中间态 |
| interrupt / HITL | ✅ `WAITING_USER` / `WAITING_WORKFLOW_CONFIRM` / `WAITING_WRITE_CONFIRM` 三种业务确认态 | ✅ `interrupt()` + `Command(resume=)`；**强制要求 checkpointer**；**resume 时整个节点从头重跑**，副作用必须幂等；多个 interrupt 按索引严格匹配 | ✅ `HumanInTheLoop` 是**一个普通 agent 步骤** + `AgenticSystemSuspendedException` + `completePendingResponse` + 同 `memoryId` 重新调用 | **语义不同**：框架是「暂停 + 重放」，我们是「持久化业务状态 + 从下一步继续」 |
| **可视化 / 调试** | ✅ **自建只读面板**（V4 第一刀；开发者工具 + 白名单门禁） | ✅ **Studio（它的强项）**：图渲染 / step-through / state 检查 / time travel / hot reload；**但只能读 LangGraph 自己的图**；动态子图与大扇出时画布会乱；依赖 LangSmith 后端 | ❌ **原生 UI 仍是 open feature request（#4442）**；现有的是 `AgentMonitor` 静态 HTML 报告（拓扑树 + 执行瀑布）、Quarkus Dev UI、OTel / Micrometer | **原文预设判断需要修正**（§5.2） |
| 重试与失败恢复 | ⚠ 部分：Workflow 有 retry；Agent 循环无系统性重试 | ✅ 节点级 pending writes——恢复时已成功的节点不重跑 | ⚠ 宣称已加入 failure recovery（Devoxx 2026） | 重叠但语义不同，暂不引入 |
| 流式输出 | ✅ SSE 管道 | ✅ | ✅ | 无增量 |
| tool calling | ✅ Spring AI `@Tool` | ✅ | ✅（含 MCP 工具） | 无增量 |
| 多 Agent 编排 | 未做（V5） | ✅ | ✅ agentic module（beta） | **真能补**，但 V5 才需要 |
| 状态模型语义 | 自研，业务语义强（证据收敛 / 用户确认 / 会话结论继承 / 写动作三明治锚定） | 通用图状态 | blackboard（`AgenticScope`） | **冲突是结构性的**，不是配置问题 |
| 版本稳定性 | — | 1.0 GA（2025 年末），月下载 34.5M+ | agentic 模块仍是 beta | LangGraph 更稳；LangChain4j 的 agentic 编排模块未 GA |

### 5.1 结论一：不引入的论据比原来更硬

原有论据是「双重状态机」（§二）。调研后**新增两条**：

1. **HITL 语义冲突是硬冲突**。LangGraph 的 `interrupt()` 在 resume 时**把整个节点从头重跑**——官方文档明确要求「interrupt 之前的副作用必须幂等」（文档举例：把 `charge_payment()` 放在 interrupt 之前可能重复扣款）。我们的确认门后面接的是**真实写入**（追加任务 / 改标题 / 切换可见性），且写入依赖「proposal 旧值锚 → confirm stale 校验 → 原子条件更新」这套手写语义——**它不承诺节点可重放**。两者不是「适配」关系，是两套正确性模型。
2. **LangChain4j 的 agentic 编排模块暂不适合接管主线**。它的 agentic 模块仍是 beta（`1.12.1-beta21`），且 `AgenticScope` 又是一个独立的第二状态容器。这个结论只针对 Agent Runtime / 编排层，不否定 LangChain4j 作为 Java 模型、RAG、工具适配库的价值。

### 5.2 结论二：可视化那句预设判断要修正

原文写：

> 可视化是唯一可能值得引入的部分；如果要引入，也应该是**只读取我们的 run/steps 做展示**，而不是接管状态。

**调研后这句话不成立**：LangGraph Studio **只读 LangGraph 自己的图状态**，读不了我们的 `ai_agent_runs` / `ai_agent_steps`——除非把 Runtime 改写成 LangGraph 图（那就是「接管」的另一种说法）。

**真正可行的「只读接入 / 观测」形态有两个，且都不需要引入 Agent 框架接管 Runtime**：

| 形态 | 说明 | 我们的状态 |
|---|---|---|
| **OpenTelemetry tracing** | 把每次 LLM 调用 / 工具调用 / 步骤上报成 span，用 Jaeger / Grafana 看 | 未做（已登记工程化补课清单） |
| **静态 HTML 报告** | 学 LangChain4j `AgentMonitor` 的形态：跑完生成自包含 HTML（拓扑树 + 执行瀑布） | **已有更轻的替代**——V4 第一刀的只读面板（在线、按 run 查） |

**所以可视化的结论是**：自建只读面板（已落地）+ 后续补 OTel tracing 即可，**不需要为此引入任何 Agent 框架接管 Runtime**。

### 5.3 MCP 评估（工具接入协议）

**定位**：MCP 回答的是「外部工具 / 数据源能否以标准协议接入」，**不是让 Agent 变聪明的主线能力**（与 V4 设计稿 §5.6 一致）。

**规格现状**（`2026-07-28` 修订，官方称最大的一次修订、「最后一个破坏兼容性的版本」）：

- **无状态协议核心**：移除 `initialize` 握手与 `Mcp-Session-Id`，每个请求自带协议版本与能力——**对我们是好事**，不需要引入 session 管理
- HTTP 原生路由（`Mcp-Method` / `Mcp-Name` 头）、缓存元数据（`ttlMs` / `cacheScope`）、W3C Trace Context（`traceparent`）
- Tools：完整 JSON Schema 2020-12、`outputSchema`、`structuredContent`、resource links
- **弃用 Roots / Sampling / Logging**（12 个月过渡期）
- **安全**：规格要求客户端「确认敏感操作、校验结果、实现超时、记录使用」；且 **tool annotations 默认不可信**——「clients MUST treat annotations as untrusted unless from trusted servers」

**对我们的适用性（分层结论）**：

| 层 | 判断 | 理由 |
|---|---|---|
| **只读工具接入** | ✅ 可行 | 检索 / 查询类外部工具，不碰写路径、不碰确认门 |
| **写动作** | ❌ **不走 MCP** | 白名单 / 确认门 / 三明治锚定是**针对具体动作手写**的；MCP 工具是**动态发现**的，且其 annotation 官方声明不可信 |
| **有状态工具** | ⚠ 谨慎 | MCP 无协议级 session，有状态工具靠显式 handle——写动作需要的「旧值锚 + stale 校验 + CAS」它不提供 |

**结论：不单开 MCP 主线**（与设计稿 §十一 一致）；将来若接入，**只接只读工具**。MCP 的输出最多进入我们自己的 observation / Verifier 链路，不能绕过动作白名单、确认门和写入执行器。

## 六、项目特殊性：为什么这条结论对本项目成立

本项目的定位（见项目 CLAUDE.md）：不是商业化产品，是「用可运行的博客系统尽可能多地练习真实项目关键技术点，为面试和后续工作打基础」。

所以选型的第一标准不是「哪个更好」，而是**「哪个能讲清楚更多东西」**：

- 手写 Agent Runtime → 能讲循环怎么实现、状态怎么管、边界怎么划、为什么这么划
- 用 LangGraph → 能讲怎么用；被追问「它内部怎么做 checkpoint 的」就容易卡住

**但反过来：「我们自己写的」不能作为面试答案。** 面试官问「你了解 LangGraph 吗」，要能答清楚两件事：

1. 它解决什么问题（checkpoint / 可视化 / 持久化 / HITL）
2. **我们为什么不用**（双重状态机 + 业务语义在手写层）

所以框架评估**要做**，产出是对比结论。

## 七、面试讲法（速记）

**Q：为什么不用 LangGraph / LangChain4j 接管 Agent Runtime？**
不是因为它们不好，是两条硬理由：

① **双重状态机**。我们已经有完整的状态模型——run/step 落库、三种待确认态、CAS、确认门、结论锚。框架的 graph state / checkpoint / interrupt 会和它重叠，而两者对中间态的定义不同（我们有业务语义：证据收敛、用户确认、会话结论继承）。两套状态不一致时，调试成本比现在更高。而框架最容易重新定义的地方，恰恰是我们手写的那部分。

② **HITL 语义冲突**。LangGraph 的 `interrupt()` 在 resume 时会把**整个节点从头重跑**，官方文档明确要求 interrupt 之前的副作用必须幂等（原话举例：把 `charge_payment()` 放在 interrupt 之前可能重复扣款）。而我们的确认门后面接的是**真实写入**（追加任务、改标题、切换可见性），靠「proposal 旧值锚 → confirm stale 校验 → 原子条件更新」保证安全——**不承诺节点可重放**。这不是适配问题，是两套正确性模型。

另外 LangChain4j 的 agentic 模块 2026 年仍是 beta（`1.12.1-beta21`），时机也不成熟。这里说的是**不接管 Runtime / 编排层**，不是否定它作为 Java AI 基础库的价值。

**Q：那你们完全不用框架？**
用。Spring Boot、MyBatis-Plus、Jackson、Redis/MySQL/ES 客户端、SSE、Spring AI 的模型调用适配——**基础设施和协议适配全部用现成的**。后续 OTel tracing、MCP 只读工具接入也属于可评估的周边框架能力。判据是：**可以用 Spring AI 调模型，但不能让它决定 Agent 状态机；可以用框架做模型适配、观测和只读工具协议，但不能让它成为写入权限的最终裁判。** 状态、裁决、权限、失败语义这四样永远自己拿。

**Q：手写不会有问题吗？**
会，手写的风险是漏掉重试超时、并发状态不完整、事务边界不清、状态不一致、后人看不懂。所以我们补了：清晰边界（域钩子化）、状态快照、55 个测试类、56 例评测门、CAS 幂等、审计留痕、每刀一份设计文档。**「完全掌控」不是自动成立的，是靠这些撑起来的。** 还欠 traceId 和系统性失败注入测试。

**Q：那框架的价值在哪？**
可视化是它真正的强项——LangGraph Studio 能做图渲染、step-through、state 检查、time travel。但有个关键限制：**它只能读 LangGraph 自己的图**，读不了我们的 run / step 表。所以「引入框架来做可视化」和「只读我们的数据做展示」这两件事其实不能同时成立——要接 Studio，就得先把 Runtime 改写成 LangGraph 图。

**我们实际走的路**：自建只读面板（只读 / 开发者工具 / 白名单门禁），后续再补 OpenTelemetry tracing 做链路与性能观测。两者都不需要引入 Agent 框架接管 Runtime。

**Q：MCP 呢？**
MCP 解决的是「外部工具 / 数据源如何以标准协议接入」，是**工具接入协议**，不是让 Agent 变聪明的东西。2026-07-28 那版规格把协议核心改成了无状态的（移除了 `initialize` 握手和 session id），对我们是好事。但我们只打算接**只读工具**——写动作不走 MCP：我们的白名单、确认门、写动作三明治锚定都是针对具体动作手写的，而 MCP 工具是动态发现的，连规格本身都写明 tool annotations「默认不可信」。

## 八、待继续讨论

- ~~LangGraph / LangChain4j 逐项对照~~ ✅ 已完成（§五，2026-09-12）
- ~~「可视化只读接入」的具体形态未设计~~ → 形态已定：自建只读面板（已落地）+ OTel tracing（待做）
- **OTel tracing 的接入时机**——与工程化补课清单里的 `traceId` 是同一件事，应一起做
- 失败注入测试的补齐时机（工程化补课清单）
- MCP 只读工具接入的触发条件：等出现「确实要接外部数据源」的真实需求时再评估

## 九、来源（2026-09-12 调研）

- **LangGraph**：[Interrupts](https://docs.langchain.com/oss/python/langgraph/interrupts)、[Checkpointers](https://docs.langchain.com/oss/python/langgraph/checkpointers)、[LangSmith Studio](https://docs.langchain.com/langsmith/observability-studio)、[Debugging](https://mintlify.wiki/langchain-ai/langgraph/guides/debugging)
- **LangChain4j**：[DefaultAgenticScope](https://docs.langchain4j.dev/apidocs/dev/langchain4j/agentic/scope/DefaultAgenticScope.html)、[HumanInTheLoop](https://docs.langchain4j.dev/apidocs/dev/langchain4j/agentic/workflow/HumanInTheLoop.html)、[AgenticSystemSuspendedException](https://docs.langchain4j.dev/apidocs/dev/langchain4j/agentic/scope/AgenticSystemSuspendedException.html)、[AgentMonitor](https://docs.langchain4j.dev/apidocs/dev/langchain4j/agentic/observability/AgentMonitor.html)、[Native UI feature request #4442](https://github.com/langchain4j/langchain4j/issues/4442)
- **MCP**：[Specification](https://modelcontextprotocol.io/specification/draft)、[Tools (2026-07-28)](https://modelcontextprotocol.io/specification/2026-07-28/server/tools.md)
