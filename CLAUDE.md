# Blog System

公开博客 / 多用户创作平台雏形，使用 Spring Boot + Vue3 构建。

目标：

- 游客浏览公开文章；登录用户发布、管理自己的文章
- 基础用户系统（注册 / 登录 / 忘记密码 / GitHub 登录）
- 分类全站可见且固定；标签全站可见，登录用户发布时可自由创建 / 复用
- 全站 AI Agent Shell（全局助手 / 页面上下文 / Tool Calling / RAG）
- 最终部署到公网服务器

## 项目定位

`D:\AIAgent` 工作区的第一个正式网站项目。不是单纯个人博客，也不是完整社区产品，而是从博客出发的多用户内容平台练习项目。

已跑通：本地开发 → 前后端联调 → 构建打包 → Linux 部署 → Nginx 反向代理 → 公网访问。

当前主线：在博客基础上补强互动体验，把 AI / Agent 能力作为后半段的学习主线。

## 工程化原则

核心目的只有一个：用一个可运行的博客系统，尽可能多地练习真实项目中的关键技术点，为面试和后续工作打基础。

### 判断标准

决定一个技术点做不做，按此顺序判断：

1. 面试官会不会问？（高频 = 必做，不管当前流量多大）
2. 能不能体现技术深度？（能讲清「为什么做」和「怎么做」 = 值得做）
3. 是不是安全底线？（安全 = 必做）
4. 当前流量需不需要？（最不重要——等有流量再补，面试也讲不出来）

反例：分布式锁、缓存击穿 / 雪崩、限流——QPS < 10 也必须做（面试必问）。
正例：K8s、分库分表、Prometheus 全套——只需讲清「什么场景需要」。

### 技术取舍

架构决策按最终形态，实现深度按面试 + 必要性分层。上技术前先过三问：解决什么问题 / 面试官会不会问 / 实现到什么程度算够讲（能画链路 + 说出边界）。

### 技术分层

- 第一层（面试必问 + 安全底线，做扎实）：缓存三问题 / 分布式锁 / 限流 / 幂等 / ES 混合检索 + rerank + 兜底 / 参数校验 / 乐观锁 / 敏感配置外置 / XSS / 健康检查 / traceId / 耗时统计 / token 黑名单 / 登录失败限制
- 第二层（轻量实现 + 写清升级路径）：MQ（已上 RocketMQ 第一批）/ Docker / CI / RAG 评测集与模型 rerank
- 第三层（不做，但要能讲清边界）：分库分表 / 微服务拆分 / K8s 集群 / 监控告警平台

### 能力审计

报告见 `docs/capability-audit-2026-08-06.md`。每完成一个里程碑更新一次；引入新技术栈前先列能力地图。

## 技术栈

- 后端：Spring Boot 3.x（Java 17）、MyBatis-Plus、MySQL 8.0、Spring AI（ChatClient / Stream / VectorStore）、Elasticsearch（全文检索 + RAG 向量索引）、Redis、RocketMQ
- 前端：Vue 3（Composition API + script setup）、Vite、Axios、Naive UI
- 部署：Linux、Nginx
- 规划中：Spring Security（JWT 已落地）、ELK、GitHub Actions

## 当前有效约束（改代码前必读）

### 意图分类与 Jev 快速路径

- 分类器是**独立的一次调用**，Jev 替换的是那一次，不新增调用
- **走快速路径**：GENERAL_CHAT / 当前文章 ARTICLE_DETAIL_QA / ARTICLE_SEARCH / LEARNING_PLAN_QUERY（只读）；文章域两个 Workflow（CREATE_ARTICLE / OPTIMIZE_ARTICLE），要求单目标 + 置信度 ≥ 0.70
- **学习类三个 Workflow 已移出快速路径**（2026-10-18）：Jev 分不清「讲讲已有计划」和「制定新计划」，实测把「讲讲我的 Redis 学习计划」以 0.83 判成制定。由 `JevFastPathMapperTests.learningWorkflowGoalsAlwaysFallBackToPlus` 锁住，别为省时间加回白名单
- **不走快速路径**：article_write / navigate / editor_action / wallet——它们直接执行、没有确认环节，判错就是真实副作用。由 `JevFastPathMapperTests.stillRejectsDirectWriteActions` 锁住
- 复合目标、字段冲突、低置信度、Jev 异常一律回退；Mapper 输出 requestKind=CHAT，**不进 TaskPlan 编排**
- 回退开关：`BLOG_AI_CLASSIFIER_ENGINE=PLUS_ONLY` 关闭 Jev
- ⚠️ 把快速路径接到非只读意图时，逐个核对「plus 会输出、Jev 不输出」的字段——**测试不会红，只会让用户看到多余追问**。已踩两次：topicEvidence、learningPlanRef
- ⚠️ `AiIntent.confidence` 不可信

### 思考模式

默认全关：`AiThinkingPolicy` 单点判定，`RestClient` 拦截器 + `WebClientCustomizer` **双管线**（只挂 RestClient 会漏 `.stream()`）。默认 `disable=true` / `exempt-models=[]`；豁免必须实名登记、优先写具体版本号。回退 `BLOG_AI_DISABLE_THINKING=false`，启动日志 `[THINKING]` 可确认。三个测试分工：`AiThinkingPolicyTests` / `AiConfigThinkingTests`（抓真实请求体）/ `AiThinkingWebClientWiringTests`（断言容器里那个 WebClient.Builder 带 filter）。

### 任务编排 fail-closed 判据

`TaskPlanningFacade` 五道判据（搜索比较 / Workflow / 分析 / 写动作 / 域仲裁）：只在「计划已成立且无未覆盖目标」时生效；**判据只拦不补，不自行造步骤**。第四条读 `requestedActions` 而非 `taskGoals`，只比对白名单六项。⚠️ 仍覆盖不到 NAVIGATE——宁可少拦一种，也不引入会误杀的判据。

**域仲裁**：主意图不在文章域 8 个 intent 时，即使 requestKind=TASK 且每步都有执行器，也不编排、交回 Planner（LEGACY）。起因：LEARNING_PLAN_QUERY 被同一份输出里多出来的 taskGoals 抢跑成文章检索。同批 `AiIntentClassifierImpl.reconcileReadOnlyContract` 把只读意图携带的 taskGoals 清空——**只读查询不是任务**。详见 `docs/agent/学习域只读路由修复设计.md`。

### 学习域语义

- 「讲讲 / 看看我的 X 学习计划」= LEARNING_PLAN_QUERY（dashboard 读 + LLM 讲解），不是制定、不是分析；分析走 LEARNING_AGENT，动手改走 LEARNING_PROGRESS
- `AiLearningDashboardTool` 按问句选中被点名的 ACTIVE 计划（唯一命中才用）——事故用户 9 个计划时旧实现会拿错
- 分类器日志 / AgentDecisionTrace 记录 requestKind 与 taskGoalTypes（排障入口，不要靠 steps=1 反推）
- 学习 Agent 结构化状态必须带「内容」：payload 只放计数会让模型反复重查同一计划；planDetail / memoryContent / ragContent 都带正文。新增只读动作必须带回答依据——`LearningAgentActionExecutorTests` / `LearningAgentStateTests` 锁住

### RocketMQ

- 第一批（AI 记忆事件，2026-10-22）：三条 @Async 链路统一到 MQ，topic `ai-memory-extraction`，tag CANDIDATE / EPISODIC / SESSION_SUMMARY，三个 consumer group；开关 `blog.ai.memory.extraction.use-mq` 默认 false；at-least-once + 幂等表 + 死信（不自动重放）；消费线程限 2；投递失败 fail-open 只记 WARN
- ⚠️ **tag 必须放进 destination（topic:tag）**：RocketMQTemplate 不认 header 里的 PROPERTY_TAGS，否则 Tags=[null]，消费端按「不匹配」静默过滤（offset 照涨、listener 不调用、无死信）。回归锁在 `MemoryExtractionEventTests` / `RocketMqArticleIndexEventPublisherTests`
- 第二批（文章 RAG 索引同步）：S1/S2/S3 已实施，flag 默认 false；硬约束见 `docs/mq/rocketmq-integration-design.md` §12。剩本地 broker 彩排 + 旧机制退场
- 秒杀继续 Redis Stream——按场景选型，不是漏迁

### 指代与锚

- 会话锚是单槽：「再前面 / 上上篇 / 倒数第二篇」结构上取不到 → **一律追问，不猜**；词集刻意不收「前一篇 / 上一篇」
- 序位指代（第 N 篇 / 最后一篇）用 `ai_sessions.last_rag_articles_json`（最近一次 RAG 有序候选，最多 5 条），只对无当前页文章的聊天窗生效
- 通用 Agent 注入【本会话最近对话】，走决策上下文、**不拼进 effectiveGoal**（会污染记忆召回）

### 计费与钱包

- 一次用户可见请求 = 预扣（硬上限）→ 按实际用量结算 → 差额退回；**零消耗一律全额退**（不能 settle(0)）
- 余额不足提示两套：B1/B2 走 SSE 错误事件，B3 + 聊天走 HTTP 402（待统一）
- 预扣占款会让执行中余额大幅缩水（实测 107~247 倍），前端用展示归一：`balance + pendingReserveCredit`
- 钱包 Agent 确认卡只负责「创建订单 + 发起支付」，**AI 不代付**

## 功能清单

### V1 基础版

- [x] 注册 / 登录（两处都必须过行为验证码）、忘记密码、GitHub 登录
- [x] 游客浏览公开文章列表 / 详情；登录用户发布、编辑、删除、隐藏、发布
- [x] 固定分类；标签创建 / 复用

### V2 增强版

- [x] Markdown 编辑器、个人中心、评论、评论点赞
- [x] 文章点赞 / 收藏、关注作者、公开用户主页、关注 / 粉丝列表
- [x] 搜索：ES 全文检索（IK 分词 + 多字段权重 + 高亮 + 检索期过滤 + 降级）
- [x] 搜索独立成页 + 支持搜用户（`/search?keyword=`）
- [x] 站内消息通知 / 私信

### V3 AI / Agent

- [x] 全站 AI 助手（SSE 流式 / 页面上下文 / 会话历史 / 文章问答 / 思考展示）
- [x] Tool Calling、RAG 混合检索 + rerank + 一致性巡检、长期记忆（候选确认 + 相似召回 + LLM 更新决策）
- [x] 5 个 Workflow：文章创作 / 文章优化 / 学习计划 / 学习进度 / 学习难点
- [x] Agent 四域 Runtime（学习 / 文章 / 通用 / 钱包），共用 AbstractAgentRuntime
- [x] 受控写动作：白名单 + 确认门 + 归属校验 + 前置状态校验 + 幂等 + 乐观锁 CAS
- [x] 多目标编排：TaskPlan + Orchestrator + 父子 run + Workflow handoff
- [x] 游客临时会话次数限制

### V4 额度与计费

- [x] 钱包：余额 / 流水 / 充值（纯 DB）
- [x] 秒杀发放：Redis Lua 预占 + DB 最终裁决 + Stream 异步落库 + 对账 + 生命周期自动流转
- [x] 充值订单真实化：订单 + 支付单分离、验签 + 防重放、超时关单、查单补单、对账
- [x] AI 计费全链路：聊天 / Agent / Workflow / 编排 / 游客

## 当前状态

截至 2026-10-24：V1 + V2 + V3 主体 + V4 全部落地，工程化第一层完成，RocketMQ 两批已实施（flag 默认 false，待彩排）。

- Redis 工程化六刀：缓存可靠性 / 用户态集合与热榜 / 生产配置 / 安全类 / 一致性 / 延迟双删
- AI 长任务准入：有界池（8 在跑 + 32 排队）+ 每用户上限 2 + 429/503 明确拒绝；压测结论：真瓶颈在供应商速率限制
- token 统计全链路，三口径不重复计数
- 站内搜索 ES 化；RAG 评测集 70 条（Recall@5 94%、硬负例误接收 0，2026-10-21 复测）
- 读接口压测基线：6 接口 × 4 档、46 万请求零错误；`/actuator/health` 的 mail 探活 16.6s 问题已修
- 待办：`[PERF-AI]` 观测污染——归档 374/375 条 agent_decider 来自单测，裸统计会得 P50=0ms 假象，分析前按线程名过滤

**维护约定**：本文件是常驻指令，只写「当前有效」的内容；里程碑叙事、踩坑细节、复盘过程写进 `docs/project-history.md` 或设计稿。文件超过 64 KB 会被静默截断。

## 下一步

- **MQ 第二批收尾**：本地 broker 彩排（发布 / 编辑 / 隐藏 / 删除、broker 停、kill consumer、重建期、flag 回退），验证后删 `RagRetryQueue` / `RagRetryTask` / 失败标记
- **跨 run 继承铺开**（V4③ 第二刀）：讨论稿待评审；学习域前置手测
- **Token 工程 P3 两笔欠账**：①「写一篇新文章」偶发漏 taskGoals ② actionType 完整枚举只在 NAVIGATE 块（「取消点赞」看不到 unlikeArticle）
- **可选**：回答质量 / 长度埋点；记忆里的无条件偏好会压制讲解类任务展开（实测约 -35% 长度）
- 工程化待办：Memory 敏感信息过滤（放最后，误杀代价 > 漏放）；@Valid 其余模块分批
- 已完成项（首 token / Jev / 思考模式 / Task Orchestration / Token 工程 P2-P4.1）的实测与归因见 `docs/project-history.md`

## 本地启动

后端：

```bash
cd blog-system
./mvnw spring-boot:run
# JDK 17+；本地 MySQL blog_system 库，用户名 myRoot，密码 123456
```

> **SQL 日志策略（2026-10-23）**：log-impl 用 `Slf4jImpl`（可按 logger 过滤），`com.hailin.blogsystem.mapper: debug` 保留日常 SQL；只把 `SeckillActivityMapper` 调到 info（秒杀生命周期每 5 秒 3 条查询）。**不要改回 StdOutImpl**（绕过日志级别）。prod profile 用 `NoLoggingImpl` 覆盖。

> **敏感配置**：本地默认值保留在 application.yml，克隆即可启动。唯一例外是 GitHub OAuth client-secret——要用 GitHub 登录时复制 `blog-system/config/application.example.yml` 为 `config/application.yml` 填值（已被 .gitignore 忽略）。启动日志 `[敏感配置]` 行确认来源。生产变量清单见 `deploy/blog-system.env.example`。

前端：

```bash
cd blog-frontend
npm install
npm run dev
```

构建：`cd blog-frontend && npm run build`

## AI 开发约定

本项目由 AI 辅助开发（Claude、Codex）。改代码时：

- 遵守现有 API 设计；改前后端 API 前先读 `docs/api-convention.md`
- 不破坏现有端点；优先扩展而非重写；改动尽量小、可增量
- 后端改动暴露前端行为需求时，记到 `docs/frontend-notes.md`

### 数据库结构变更（无迁移工具，手工执行）

改了 `db/init.sql` / `src/test/resources/schema.sql` 后，**必须同步在本地 MySQL 开发库执行同样的 DDL**；测试库是 H2 内存库，测试全绿掩盖不了开发库缺列。两个方向都要防：2026-10-09 是**反向漂移**（开发库治理了索引没回写 init.sql，新库少复合索引、排序退化）；2026-10-18 是**缺列**（version 只改实体和本地库，新库上学习计划读就报 Unknown column）。教训：**比对口径要覆盖表 + 列 + 索引**，少一个维度就等于没查。验证方法：新建临时库执行 init.sql，再用 information_schema 与开发库逐条比对（当前 29 表 / 317 列 / 164 索引，零差异）。报告见 `docs/数据库索引审计.md`。

### 列表接口不返回文章正文

`ArticleDetailVO.from()` 是列表语义（不带 content），详情用 `fromDetail()`。正文占一页 JSON 的 92%。失败模式不对称：漏改详情 = 页面空白（显眼），漏改列表 = 静默多传 50 KB。契约由 `ArticleListContentTests` 锁住（含缓存）。已知降级：summary 为空时 RAG snippet 会用 content 前 50 字兜底，剥正文后退化成标题。

### 登录验证码（tianai 1.5.5）

二次验证必须开（`captcha.secondary.enabled=true`），否则绕过前端直接调登录接口就能跳过验证码。三个坑：① `init-default-resource=true` 只装模板不装背景图，报错指不到根因；② 前端 SDK 必须与后端版本配对，必须从源码自构建，升级后端要重建前端产物；③ 验证码端点返回 tianai 原生 `{code,msg,data}`，不是项目 Result。详见 `docs/登录验证码设计.md`。

### 忘记密码与账号模型

users 有 email（唯一索引）与 token_version；GitHub 登录按已验证主邮箱绑定已有账号；重置成功调 `TokenVersionStore.bump()` 踢掉所有设备。要点：① token_version 权威值在 DB，Redis 只当缓存，读失败回源 DB 不能 fail-open；② 老 token 缺 ver 按 0 处理；③ GitHub 查用户不能带 login_type，只认 primary && verified 邮箱；④ 用户级分支响应必须恒定，但**全局分支（邮件服务未配置）必须明确报 50301**——「响应恒定」不能无差别套用。详见 `docs/忘记密码设计.md`。

### 记住我

勾选 = 后端签 30 天 + localStorage；不勾 = 24 小时 + sessionStorage。`rememberMe` 为 null 按「不记住」处理（`Boolean.TRUE.equals`）。token 过期后存哪都失效，storage 切换必须与后端 ttl 分级配套。⚠️ 改造前登录页的「记住登录状态」复选框是摆设（写死 checked、没接后端）——「看起来做了、其实没接」比没做更危险。

### 其他边界

- token 统计：Agent 部分正文流中断不保存半截 assistant 消息，但已消耗 usage 仍落 run/step
- 测试跑法：默认只跑相关类 `-Dtest=XxxTests`；`-Dgroups=eval` 和全量 `mvn test`（约 30 分钟）都要先经过我同意
- 面试材料在 `docs/interview/`：Agent 面试笔记 / 数据库索引话术 / AOP 话术 / Redis 面试笔记

## 设计文档索引

**路线与选型**

- `docs/capability-audit-2026-08-06.md` — 能力审计（三层依据）
- `docs/engineering-next-cut-review.md` — 工程化下一刀选型评估
- `docs/数据库索引审计.md` — 全库索引审计（28 表 / 89 索引逐条对照代码）
- `docs/首页游标分页设计.md` — 深分页改 keyset（索引 / 缓存 / ES search_after）
- `docs/search/site-search-es-design.md` — 站内搜索 ES 化
- `docs/agent/agent-v4-autonomous-loop-design.md` — V4 自主闭环整体设计
- `docs/agent/agent-module-acceptance-checklist.md` — 阶段收口验收矩阵

**Redis / 工程化 / MQ**

- `docs/redis/redis-capability-design.md` — Redis 能力补全（五刀路线）
- `docs/redis/delayed-double-delete-design.md` — 缓存延迟双删（含延迟值实测方法）
- `docs/redis/cache-breakdown-avalanche-design.md` — 击穿 / 雪崩专题
- `docs/redis/redis-ops-runbook.md` — 运维排查手册
- `docs/redis/wallet-verification-guide.md` — 钱包 + 计费验证指南
- `docs/redis/充值订单真实化设计.md` — 订单 + 支付单分离、验签、关单、对账
- `docs/mq/rocketmq-integration-design.md` — RocketMQ 接入（第一批已实施；§12 是第二批硬约束）
- `docs/mq/文章索引同步MQ迁移设计.md` — 第二批索引同步（outbox + ORDERLY + 对账，S1-S3 已实施）
- `docs/计费覆盖审计.md` — AI 计费覆盖面审计 + 补全设计
- `docs/security/sensitive-config-externalization-design.md` — 敏感配置外置
- `docs/登录验证码设计.md` — 行为验证码（含 5 个踩坑与验证方法）
- `docs/忘记密码设计.md` — 忘记密码 + 账号模型收敛

**Agent 能力（按版本倒序）**

- `docs/agent/学习域只读路由修复设计.md` — 学习域只读被编排抢跑：根因与修复
- `docs/agent/agent-task-orchestration-design.md` — 分层任务编排（TaskPlan + Orchestrator + 父子 run）
- `docs/agent/agent-task-orchestration-workflow-handoff-design.md` — handoff 暂停、确认与恢复
- `docs/agent/agent-stage2-action-plus-analysis-design.md` — 动作 + 分析（第二刀）
- `docs/agent/agent-long-task-admission-design.md` — AI 长任务准入与资源隔离
- `docs/agent/agent-admission-stress-design.md` / `agent-admission-stress-report.md` — 准入压测设计与报告
- `docs/agent/llm-call-reliability-design.md` — LLM 调用可靠性
- `docs/agent/agent-json-mode-fix-design.md` / `agent-judge-model-separation-design.md` / `agent-learning-intent-boundary-fix-design.md` / `agent-session-plan-anchor-design.md` — 学习域七连修
- `docs/agent/agent-v3-13-plan-preview-design.md` — Plan Preview 阶段一
- `docs/agent/agent-v3-12-conclusion-inheritance-design.md` — 会话结论继承
- `docs/agent/agent-v3-11-evidence-verifier-design.md` — 证据收敛门
- `docs/agent/agent-v3-10-thought-display-design.md` — thoughtSummary 分级闸门
- `docs/agent/agent-v3-9-qa-cross-page-anchor-design.md` / `agent-v3-8-session-article-anchor-design.md` — 锚与指代消解
- `docs/agent/agent-v3-7-article-visibility-write-design.md` / `agent-v3-6-write-executor-registry-design.md` / `agent-v3-5-route-registry-design.md` / `agent-v3-4-article-write-action-design.md` / `agent-v3-1-controlled-write-add-task-design.md` — 受控写动作线
- `docs/agent/agent-v4-intent-boundary-closeout-design.md` — Agent / QA 路由边界收口
- `docs/agent/agent-observability-traceid-design.md` — traceId 贯穿
- `docs/agent/agent-v4-5-global-thinking-display-design.md` — 聊天 / QA 轻量过程提示
- `docs/agent/agent-framework-evaluation.md` — 框架能力对照（LangGraph / LangChain4j / MCP）
- `docs/agent/agent-eval-gate.md` — 评测门
- 早期：`agent-runtime-v1-design.md` / `agent-runtime-v2-1-workflow-suggestion-design.md` / `agent-v1-acceptance-checklist.md` / `agent-v3-0-general-thinking-closeout.md`

## 工作区约定

- 本项目独立于 `D:\AIAgent` 下其他项目，不引用其他项目代码
- 项目相关上下文写在本目录的 `AGENTS.md` / `CLAUDE.md`；根目录这两个文件只保存全局背景和长期方向
- **新增文档用中文文件名**（如 `数据库索引审计.md`、`AOP面试话术.md`）——英文文件名容易被忽略。
  `docs/` 下的早期文档（`project-history.md`、`api-convention.md` 等）是历史命名，不用回头改。

---

> 修改本文件后，请同步更新同目录下的另一个入口文件（`AGENTS.md` / `CLAUDE.md`），两者内容应保持一致。
