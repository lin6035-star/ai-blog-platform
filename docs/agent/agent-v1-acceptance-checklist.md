# Agent V1.5 验收清单

> 创建：2026-08-25（Agent V1）
> 更新：2026-08-27（并入 Agent Runtime V1，整体收尾为 V1.5）
> 范围：全站 AI Agent Shell 能力（V3 全部）+ Agent Router V1 + Workflow 稳定化 + Agent Runtime V1，作为进入 Agent V2 讨论的前置确认。

## 验收结论（速览）

| 模块 | 状态 | 测试证据 |
|---|---|---|
| AI 助手基础（SSE / PageContext / 会话历史） | ✅ 完成 | AiSessionServiceTests、AiMessageIntentRoutingTests |
| Tool Calling（查询工具 + ToolContext 传用户态） | ✅ 完成 | AiLearningPlanToolsTests、StreamChatToolCallingReproTests、AiModelServiceToolExposureTests |
| RAG 体系（向量+keyword 混合检索 + rerank + 一致性修复） | ✅ 完成 | ArticleRag* 系列 8 个测试类 |
| Memory 长期记忆（正式/候选/ES 召回/LLM 决策） | ✅ 完成 | AiEpisodicMemory*、AiMemoryDecisionSanitizerTests |
| Agent Router V1（意图分类 + 规则裁判 + topicEvidence） | ✅ 完成 | AiIntentClassifierPromptTests、AiMessageIntentRoutingTests、CreateArticleWorkflowHandlerTests |
| Workflow Runtime（5 个 Workflow + 抽象分层） | ✅ 完成 | 5 个 Workflow 测试类 + 核心链路测试 |
| 并发/幂等/恢复三防线（锁 + version + Idempotency-Key） | ✅ 完成 | WorkflowActionLockTests、AiWorkflowRunVersionTests、AiWorkflowRunServiceTests |
| Redis 限流（chat/workflow/rag） | ✅ 完成 | AiRateLimiterTests（含 7 条确认路径：approve/stream 等流式端点已排除） |
| 前端 XSS 防护（统一清洗出口） | ✅ 完成 | markdown.spec、articleOutline.spec |
| **Agent Runtime V1**（有界只读决策循环） | ✅ 完成（2026-08-26） | LearningAgentRuntimeTests、LearningAgentActionExecutorTests、LlmAgentStepDeciderTests |
| **token 前端展示** | ⏳ 再推迟（V2 候选） | 后端 usage 已落库（ai_messages.token_count / run 表），前端未展示 |
| 全量回归测试 | ⏳ 待执行（按需） | 各轮仅跑相关测试，全量未跑 |
| 429 端到端复测（SSE 前置拦截） | ✅ 修复闭环（单测覆盖） | 实测暴露 → endsWith 改 contains 覆盖 /approve/stream → 单测 7 条确认路径；端到端复测未做 |

## 一、能力验收明细

### 1. AI 助手基础（✅）

- [x] 全站右侧 AI 助手入口 / 小窗 / 抽屉
- [x] 普通聊天 SSE 流式（fetch + ReadableStream，含 Abort）
- [x] PageContext 页面上下文感知（pageType / articleId / userId）
- [x] 登录用户会话历史（会话 CRUD / 消息 CRUD / 压缩状态）
- [x] 文章详情页基于当前文章内容问答（CURRENT_ARTICLE 检索模式）

### 2. Tool Calling（✅）

- [x] 学习计划查询 Tool（queryLearningPlans / queryLearningPlanDetail），无参工具手写 ToolCallback 绕过流式聚合器缺陷
- [x] ToolContext 显式传 userId（修复 Spring AI 内部线程池 ThreadLocal 拿不到用户态的坑）
- [x] 幻觉 planId / 越权访问统一友好文案，不打断聊天流

### 3. RAG 体系（✅）

- [x] DashScope Embedding → ES 向量索引（开发/测试索引隔离）
- [x] keyword 混合检索（ES match 兜底）
- [x] 规则 rerank（关键词命中打分 + articleId 去重）
- [x] 一致性手动修复（check + repair）+ Rebuild 接口权限保护（配置开关 + 白名单）
- [x] 前端 references localStorage 持久化

### 4. Memory 长期记忆（✅）

- [x] 正式记忆 / 候选记忆双表 + 手动 CRUD
- [x] AI 异步提取候选 + ES 相似记忆召回 + LLM 决策（CREATE/UPDATE/MERGE/IGNORE）
- [x] 前端待确认列表 + 保存/忽略
- [x] 注入策略（enabled / confidence / importance / 数量限制）+ PENDING/REJECTED 过期清理
- [x] 情景记忆（DECISION/EVENT/MILESTONE/PLAN）

### 5. Agent Router V1（✅）

- [x] 意图分类（11 类）+ 置信度 + risk/reason + AgentDecision 决策链路
- [x] 学习规划入口路由（制定/调整/攻坚/查询四路分流，LLM 主判 + 规则兜底）
- [x] **topic 幻觉防护最终版**：LLM 逐字摘录 topicEvidence → 后端 validateTopicEvidence（原话 substring 校验 + 泛词表过滤）→ 验证失败 WAITING_REQUIREMENT_CONFIRM
- [x] "LLM 给建议，后端做裁判"原则落地（高影响字段由确定性规则裁决）

### 6. Workflow Runtime + 5 个 Workflow（✅）

- [x] Runtime 分层：AbstractWorkflowHandler（模板骨架）/ Support（纯工具）/ Recorder（记录）/ Runner（执行）/ Manager（生命周期）/ Registry
- [x] 五个 Workflow：CREATE_ARTICLE / OPTIMIZE_ARTICLE / LEARNING_PLAN / LEARNING_PROGRESS / LEARNING_ASSIST
- [x] 确认面板按 workflowType 结构化渲染 + 卡片生命周期闭环（发卡→消费清卡）
- [x] 失败语义：LLM 失败即 FAILED + 可重试 + 失败绑定会话（刷新不丢重试入口）
- [x] token 成本统计：流式精确 usage 落 run 表 + ai_messages.token_count
- [x] 质量检查反馈进 prompt 重试（双层 auto-retry）
- [x] 幂等保存（source_workflow_run_id 唯一 + upsert）

### 7. 工程化稳定性（✅ 本轮完成）

- [x] Redis 分布式锁：SETNX + 120s TTL + Lua 校验释放 + fail-open；四操作（approve/reject/retry/cancel）统一 claimWorkflowAction + DB version CAS
- [x] Idempotency-Key 幂等：fingerprint(action+payload) + 24h TTL + 同 key 不同 payload 409 + 读写故障降级；前端 sessionStorage Key 生命周期
- [x] Redis 限流：Lua 原子 INCR+EXPIRE，chat 10/60s、workflow 3/600s、rag 30/60s；登录用户 userId / 游客 IP（限额减半）；HTTP 429 + Retry-After + RATE_LIMITED
- [x] 前端 XSS：DOMPurify 统一清洗出口（markdown / articleOutline 两处渲染）

### 8. Agent Runtime V1（✅ 2026-08-26）

- [x] LEARNING_AGENT 意图 + Planner 双签分发 + 管道隔离（isLearningDomain 排除）
- [x] ai_agent_runs / ai_agent_steps 双表持久化（runId/stepNo/actionType/observation/status）
- [x] 核心 Loop：LLM 决策器（JSON 输出 + 修复 1 次 + 白名单校验）→ 执行器 → observation 落 step → 终态 FINAL_ANSWER/ASK_USER（计步）→ maxSteps=5 有界 + summarize 汇总
- [x] 只读动作：QUERY_LEARNING_DASHBOARD（多计划语义：点名唯一→详情、点名多→候选不猜、点名未命中→不 fallback）/ QUERY_MEMORY（语义+情景，失败带标记）/ SEARCH_RAG（复用混合检索）
- [x] 接入链路：游客路径 / Workflow 结束 fallback 路径均接入 AGENT；Codex 审查 6 项全部修复（含 clipContext 6000、终态计步、planRef 不 fallback）
- [x] 运行期修复：流式工具调用轮 null text NPE（Reactor map 归一 ""）；429 排除条件 endsWith→contains（覆盖 /approve/stream）

### 9. 前端（✅）

- [x] 助手 UI（消息流 / 确认面板 / 过渡态 / 快照消息恢复 / 返回按钮 back 化）
- [x] 幂等 Key 生命周期（成功清除 / 网络失败复用 / reject 绑定 feedback）
- [x] 学习计划列表/详情/进度条/任务勾选乐观更新 + 回滚

## 二、测试覆盖统计

### 后端（约 50 个测试类，新增 6 个 Agent Runtime 类）

Agent/Workflow 相关核心测试类：

| 测试类 | 用途 | 类型 |
|---|---|---|
| AiWorkflowRunServiceTests | 核心链路（创建/确认/取消/重试/幂等/version） | 规则 + 真实 LLM |
| WorkflowActionLockTests / AiWorkflowRunVersionTests / AiRateLimiterTests | 锁 / version / 限流（含 7 条确认路径） | 纯 Mockito |
| CreateArticleWorkflowHandlerTests | topicEvidence 裁判规则 | 纯规则 |
| AiMessageIntentRoutingTests | 意图路由规则 | 纯规则 |
| AiIntentClassifierPromptTests / AiIntentClassifierIntegrationTests | 分类器 prompt / 集成 | 规则 + 真实 LLM |
| AgentPlannerSupportTests / AgentPlannerEvaluationTests / AgentDecisionTraceTests | Planner 决策 | 规则为主 |
| LearningPlan / LearningProgress / LearningAssistWorkflowServiceTests | 三个学习 Workflow | 规则 + 真实 LLM |
| LearningPlanMatchServiceTests / AiLearningPlanToolsTests | 计划匹配 / 查询工具 | 纯规则 |
| StreamChatToolCallingReproTests | 流式工具调用降级 | 真实 LLM |
| ArticleRag*（8 个类）/ AiEpisodicMemory* / AiMemoryDecisionSanitizerTests | RAG / 记忆 | 规则 + 集成 |
| LearningAgentRuntimeTests（7） | 核心 Loop（终态/异常/超限/执行失败） | 纯 Mockito |
| LearningAgentActionExecutorTests（13） | 三个只读动作 + 多计划语义 | 纯 Mockito |
| LlmAgentStepDeciderTests（9） | 决策器（JSON 解析/白名单/summarize） | 纯 Mockito + 真实 LLM |

### 前端（22 个 spec 文件）

- AI 相关：markdown.spec（XSS 6）、articleOutline.spec（3）、home-layout.spec（含 XSS 出口断言）
- 通用：api 系列 / views 系列 / stores / router / utils

### 未执行

- [ ] 后端全量测试（`mvn test`）——本轮只跑了相关测试
- [ ] 前端全量 vitest（`npm test`）——本轮只跑了 markdown + articleOutline
- [ ] 429 端到端实测（启动后端连打接口验证限流 + SSE 前置拦截）

## 三、遗留项清单

### V1.5 范围内待做（均不阻塞 V2）

| 项 | 说明 | 状态 |
|---|---|---|
| token 前端展示 | 后端 usage 已落库（ai_messages.token_count / run 表），前端未展示 | ⏳ **再推迟**，并入 V2 候选 |
| 全量回归 | 确认无跨模块回归（约 30 分钟，含真实 LLM） | ⏳ 未跑，按需执行 |
| 429 端到端复测 | 修复已单测覆盖（7 条确认路径），未启动后端实测 | ⏳ 未做，问题已闭环 |

### V1.5 范围外（明确暂缓 / 不归 V1.5）

- 站内消息通知 / 私信（V2 已列暂缓）
- 游客临时会话次数限制
- GitHub OAuth 安全细节（独立原型，非 Agent 范围）
- 锁 TTL 续期 / 幂等保留时长调整（已知边界，见验证计划 Task 6）
- Agent Runtime 后续演进（V2.1 Workflow 建议确认 → Run/Step 历史 API → 前端时间线 → 受控写动作 → 多领域 Agent，见 `agent-runtime-v2-1-workflow-suggestion-design.md`）

## 四、验收结论

**Agent V1.5 主体能力全部完成并通过测试验证**（Agent Router V1 + Workflow 稳定化三防线 + Agent Runtime V1，含 Codex 审查修复与运行期 429/NPE 修复闭环）。遗留三项（token 展示再推迟、全量回归、429 端到端复测）均不阻塞，**正式进入 Agent V2 规划**。
