# Blog System

这是一个公开博客 / 多用户创作平台雏形，使用 Spring Boot + Vue3 构建。

目标：

- 游客可以浏览公开文章
- 登录用户可以发布和管理自己的文章
- 支持基础用户系统
- 分类全站可见，V1 先固定，不开放普通用户创建
- 标签全站可见，登录用户发布文章时可以自由创建 / 复用
- 全站 AI Agent Shell（全局助手 / PageContext / Tool Calling / RAG）
- 最终部署到公网服务器

## 项目定位

这是 `D:\AIAgent` 工作区的第一个正式网站项目。它不是单纯的个人博客，也不是完整社区产品，而是一个从博客出发的多用户内容平台练习项目。

阶段一已经跑通：

- 本地开发
- 前后端联调
- 构建打包
- Linux 服务器部署
- Nginx 反向代理
- 公网访问

当前核心目标转为：在现有博客基础上继续补强互动体验，并把 AI / Agent 能力作为项目后半段的学习主线。

## 项目工程化原则

本项目不是以真实商业化产品为最终目标，核心目的只有一个：

```text
用一个可运行的博客系统，尽可能多地练习真实项目中会遇到的关键技术点，为面试和后续工作打基础。
```

### 核心判断标准

决定一个技术点做不做时，按这个顺序判断：

```text
1. 面试官会不会问？（面试高频 = 必做，不管当前流量多大）
2. 能不能体现技术深度？（能讲清楚”为什么做”和”怎么做” = 值得做）
3. 是不是安全底线？（安全 = 必做，不管项目大小）
4. 当前流量需不需要？（最不重要——等项目真有流量了再补就晚了面试也讲不出来）
```

**反例**：分布式锁、缓存击穿/雪崩防护、限流——即使当前 QPS < 10，面试官听到”我用了 Redis”就一定会追问这些，所以必须做。

**正例**：K8s 集群、分库分表、Prometheus + Grafana 全套——面试官不会期望一个练习项目上这些，只需要能讲清”什么场景下需要”就行。

### 技术取舍原则

**架构决策按最终形态，实现深度按面试 + 必要性分层**。不把”当前没有海量用户”当作不做某件事的理由——因为面试官不会因为你的项目流量小就不问。

上技术前先过 3 问（结论写进文档，面试就能讲）：

```text
1. 它解决什么问题？（说不清 = 不上）
2. 面试官会不会问？（面试高频 = 优先上）
3. 实现到什么程度算”够讲”？（能画出链路 + 能说出边界 = 够了）
```

### 技术分层

```text
第一层 · 面试必问 + 安全底线（不管流量大小都做扎实）
  缓存穿透/击穿/雪崩防护 / 分布式锁 / 限流 / 幂等控制
  ES 混合检索 + 规则 rerank + 兜底 / 一致性巡检 + 修复
  @Valid 参数校验 / 乐观锁 / 敏感配置外置 / XSS 防护 / 敏感信息过滤
  异步任务重试 + 失败标记 / 健康检查 / 请求追踪 traceId / 接口耗时统计
  Token 刷新 + 黑名单 / 登录失败限制 / OAuth state 校验

第二层 · 轻量实现 + 写清升级路径（先跑通完整链路，面试能讲）
  MQ：当前用 @Async + Redis 失败标记，后续可换 RocketMQ
  Docker：先写 Dockerfile + docker-compose，不追求 K8s
  CI/CD：GitHub Actions 自动构建，不追求复杂流水线
  RAG 评测集 / 模型 rerank：接口预留，后续可替换实现

第三层 · 明确不做，但能讲清边界（面试能说”什么规模才需要”即可）
  分库分表 / 微服务拆分 / K8s 集群 / Prometheus + Grafana 全套 / 自动告警平台
  连真实产品初期也没有；要能说清触发条件和替代方案
```

### 能力审计

项目能力审计报告见 `docs/capability-audit-2026-08-06.md`。每次完成一个里程碑后更新该报告，确保技术栈不会停留在表层。每引入新技术栈前，先列能力地图再动手。

推荐节奏：

```text
先做第一层（扎实、可讲、面试必问）
再按学习重点加深第二层
第三层只记触发条件，不写代码
```

## Tech Stack

### Backend

- Spring Boot 3.x（Java 17+）
- MyBatis-Plus
- MySQL 8.0
- Spring AI（ChatClient / Stream Chat / VectorStore）
- Elasticsearch（RAG 向量索引）

### Frontend

- Vue 3（Composition API + `<script setup>`）
- Vite（构建工具）
- Axios
- Naive UI

### Deployment

- Linux
- Nginx

## Planned / Optional Tech (Future Roadmap)

### Search

- Elasticsearch 全文搜索（已先行用于 RAG 向量索引）

### Authentication

- Spring Security（权限控制，可选；JWT 已落地）

### DevOps

- Docker（容器化部署）
- CI/CD（GitHub Actions / Jenkins）

### Observability

- Logback / ELK（日志系统）

### AI / Agent

- **Decision Model（Jev 类，2026-09-28 登记）**：判断交专用决策模型、生成留 LLM —— 目标是更快更省，且拿到**可用的置信度**。
  - 落点：意图分类 / Agent 步骤决策（Choice）、证据收敛门 / needsThinking（Noul）
  - 关键澄清：分类器**已是独立调用**，替换的是那一次而非新增调用；首 token 8s 里它只占一环
  - 额外价值：校准概率可驱动分支（当前 `AiIntent.confidence` 不可信）
  - 边界：中文实测准确率存疑（第三方客服测试 64~65%）、黑盒无解释、生态早期
  - 验证手段：**评测集已就绪**（离线 18 + 在线 38），可直接 A/B，不必靠信仰判断
  - 状态：候选，未评估

## Features

### V1（基础版）

- [x] 用户注册 / 登录
- [x] 游客查看公开文章列表
- [x] 游客查看公开文章详情
- [x] 登录用户发布文章
- [x] 登录用户编辑 / 删除 / 隐藏 / 发布自己的文章
- [x] 固定分类列表
- [x] 文章标签创建 / 复用

### V2（增强版）

- [x] Markdown 编辑器
- [x] 个人中心
- [x] 评论
- [x] 评论点赞
- [x] 文章点赞 / 收藏
- [x] 关注作者
- [x] 公开用户主页
- [x] 关注列表 / 粉丝列表
- [x] 搜索功能（2026-09-19 从 `title LIKE '%kw%'` 升级为 **ES 全文检索**：IK 中文分词 + 多字段权重 + 高亮 + 检索期过滤 + 降级）
- [ ] 站内消息通知 / 私信（暂缓）

### V3（AI / Agent 增强）

- [x] 全站右侧 AI 助手入口
- [x] AI 小窗 / 抽屉
- [x] 普通聊天（SSE 流式）
- [x] PageContext 页面上下文感知
- [x] 文章详情页基于当前文章内容问答
- [ ] 游客临时会话次数限制（暂缓）
- [x] 登录用户会话历史
- [x] SSE 流式输出
- [x] Tool Calling
- [x] RAG / Embedding / Elasticsearch（向量检索 + keyword 混合检索 + 规则 rerank + 一致性修复）
- [x] AI 长期记忆（Memory）：正式记忆 + 候选确认 + ES 相似记忆召回 + LLM 更新决策 + prompt 注入
- [x] 文章优化 Workflow（OPTIMIZE_ARTICLE）：文章详情页发起"优化这篇文章"→ 加载/分析文章 + 记忆 + RAG → 生成优化方案（确认）→ 重写 + 内容检查（确认）→ 填充编辑器；approve/reject/retry 通过 WorkflowHandlerRegistry 按 workflowType 复用；已验证第二个 Workflow 完整复用 V1 抽象，Runtime / 业务能力边界清单见 `docs/workflow/agent-workflow-next-stage-notes.md`
- [x] 学习难点攻坚 Workflow（LEARNING_ASSIST）：某计划某阶段卡住 → AI 拆解 3-5 个新任务点 + 讲解 → 确认 → 追加到对应阶段（其他阶段不动）
- [x] 钱包域 Agent（WALLET_AGENT，2026-09-21）：查余额（只读工具）+ 充值（确认卡）。**第 4 个 Agent 域，也是唯一一个决策器不调 LLM 的域**
- [x] **Agent 多目标编排（第二刀：动作 + 分析，2026-09-29）**：一句「点赞这篇文章，再分析一下它写得怎么样」= 确定性写动作 + 动态 Agent 分析，在同一个请求里跑完。规划层加原文证据字段 + 三层校验（包含性 / 防动作重叠 / 防反序）；执行层 `AgentRunPolicy` + `runWithPolicy` + 双 decider（analysis-only，不进 `WAITING_*`）；批次在权威 `submit` 成功后于 worker 内执行。**在线评测门 47 例通过**
- [x] **Agent 分层任务编排（第三刀·第一版，2026-09-29）**：把「一个请求 = 一个动作」改成「一个请求 = 一份可执行的粗计划」。文章域纵向切片：**点赞 / 搜索 / 导航**三类步骤由一个 `TaskOrchestrator` 串行执行、逐项落 `StepResult`、按真实结果汇总；**路由边界已收口**（旧路径只能显式退回，不会悄悄接管多目标请求）。父子 run（`ARTICLE_ANALYSIS`）待实施

### V4（额度与计费）—— 批次 A / B / C 已完成

- [x] 钱包本体：余额 / 流水 / 模拟充值（**纯 DB，零 Redis**）
- [x] AI 计费接入普通聊天：硬上限预扣 → 按实际用量结算 → 差额退回
- [x] 可负余额三态（区分「预占中」与「真的欠费」）
- [x] 注册赠送初始额度（`blog.wallet.initial-credit`）
- [x] 秒杀发放额度：Redis 预占 + DB 最终裁决 + Stream 异步落库 + 对账收敛 + **活动生命周期自动流转（含每日定时开场）**
- [x] 自定义金额充值（2026-09-20）：整数元 1 ~ 1000，**另开入口**而不是给套餐 DTO 加可选金额字段
- [x] ~~推广到 Agent / Workflow~~（2026-09-21 决定**不做**：见下方钱包域 Agent 那条的理由）

## 当前状态

**截至 2026-09-20**：V1 基础 + V2 互动 + V3 AI/Agent 主体 + V1.5 工程化第一层全部落地；**Redis 工程化五刀全部完成**；**token 统计全链路补全**（流式修复 + 非流式四刀）；Agent 侧 V4 主干评估点已清完；**额度系统批次 A/B/C 完成**（钱包纯 DB + 聊天计费闭环 + 注册赠送 + 秒杀全链路）；**AI 长任务准入与资源隔离完成**（专用有界池 + 每用户硬上限 + 429/503 明确拒绝 + `[AI-TASK]` 观测，**前后端两侧都做了**）；**`RUNNING` 启动恢复完成**（`AiRunStartupRecoveryRunner`，只动 RUNNING、不碰 `WAITING_*`）；**准入压测完成**（容量算法实测精确；**真瓶颈是供应商速率限制，不是应用层**）；**LLM 调用可靠性完成**（失败分类 + 429 重试；复测暴露真问题是**分类质量**，重试解决不了）；**工程化第一层收尾完成**（@Valid 第一批 + 健康检查 + 耗时统计）；**站内搜索升级为 ES 全文检索**（IK 分词 + 高亮 + 降级）；**缓存延迟双删完成**（第六刀：**先实测读路径耗时再定延迟值** + 进程内延迟重放，残留窗口收窄）。

### 版本演进

- **2026-09-20 缓存延迟双删（第六刀）**：第五刀的 `AfterCommitExecutor` 消除了"删早了"，
  但仍剩一个窗口——读请求在写事务**提交前**查库（拿到旧值）、却在缓存**删除之后**才写回，
  旧值一直留到 TTL（详情 10 分钟）。**这不是"恰好并发"**：窗口宽度 = 读请求「查库 → 写缓存」的耗时。
  - **先实测再定值**：`[PERF-HTTP]` 慢阈值压到 1ms + **16 并发**强制 cache miss × 320 样本 →
    详情冷读 P99=16ms / **max=28ms**，列表 max=26ms → **延迟取 200ms**（≈7 倍余量；偏大是安全方向，代价只是多一次回源）
  - 新增 `component/DelayedCacheEvictor`：**组合** `AfterCommitExecutor`（时机）+ 私有 `ThreadPoolTaskScheduler`
    （延迟重放）。6 个缓存失效封装方法改委托，**业务调用点一个没动**；顺带收口了重复 6 次的「删详情 + 删列表」两行组合
  - **两个刻意**：① 调度器**私有 new、不声明成 Bean**——否则自动配置的 `taskScheduler` 因 `@ConditionalOnMissingBean`
    退避，`pool.size: 1` **静默失效**且 8 个 `@Scheduled` 全被搬走，串行调度不变量被无声破坏；
    ② **不改 `execute` 的行为**——`UserSetCache.maintain` 用的是同一个入口，但它维护 Set 而非删除，
    把"动作跑两次"变成隐含契约，将来塞非幂等动作就是静默数据错误
  - **`UserSetCache` 不纳入**：它的读路径回填是**全量快照覆盖**，窗口形态与"删除后旧值写回"不同，
    **"删两次"根本关不上**（删掉集合后回源重建的是快照时刻的数据）
  - 顺带修掉一个第五刀遗留：`likeArticle` 在事务内**裸删**缓存（同类的 `cancelLikeArticle` 早就走封装方法），
    同文件内的不对称遗漏
  - 测试 7 例 + **反向验证**（砍掉重放 → 精确红 5 条，核心用例停在"延迟重删应覆盖…"）+ 回归 41 例全绿
  - 实施踩到两个坑：**收口时 `replace_all` 把新方法体自己也替换了**（→ 无限递归，被 B 组 `StackOverflowError` 抓出）；
    **挂起类测试的 latch 必须每例重建**（写成 `final` 复用会让挂起失效，表现为断言看到空缓存——**像实现错、实为测试错**）
  - 详见 `docs/redis/delayed-double-delete-design.md`

- **2026-09-19 站内搜索：LIKE 全表扫描 → ES 全文检索**：`title LIKE '%kw%'`（前置通配用不上索引、
  只搜标题、无相关性排序）→ 独立索引 `article_search_index`（IK 分词 + title^3/summary^2/content）
  + 高亮 + 检索期 `status` 过滤 + **ES 挂掉降级回 LIKE**。
  装 IK 走的是**官方下载站 `get.infini.cloud`**（原方案里的 GitHub 地址因仓库改名已失效）。
  实施中实测发现的坑：**`multi_match` 默认是 OR 语义** —— 「建造者」被切成「建造|者」，
  命中任一个词就算匹配，把「开发**者**」全捞进来（17 条）；改 `.operator(And)` 后降到 3 条。
  **这个坑靠看条数发现不了，是 highlight 暴露的。**
  详见 `docs/search/site-search-es-design.md`

- **2026-09-19 工程化第一层收尾**：@Valid 参数校验（框架 + 第一批「用户/认证」）+ `/actuator/health` 暴露
  + `[PERF-HTTP]` 耗时拦截器（SSE 流单独标记，不参与慢请求判定）。
  关键取舍：**校验失败保持 HTTP 200 + 40001** —— 项目约定业务错误走 200，
  与原先散在 controller 里的手写校验**格式完全一致**，前端零改动（这是敢动 47 个写接口的前提）。
  **校验边界只对齐数据库列宽、不引入新下限**（加下限是产品决策，不该夹在工程化改造里）。
  顺带修掉两个：**路径不存在返回 50000**（URL 写错却被告知"服务端坏了"）→ 40400；
  **坏 JSON 返回 500** → 40001。

- **2026-09-19 LLM 调用可靠性**：压测发现 429 **零重试** —— 根因不在项目代码，而是 Spring AI
  把 4xx 判成 `NonTransientAiException`（`onClientErrors` 默认 false），且那个 RetryTemplate
  **完全不覆盖流式路径**（而 Workflow 5 个生成步骤全是流式）。
  修法：失败分类（7 类 + 退避策略）+ `spring.ai.retry.on-http-codes: 429`（覆盖所有 `.call()`）+
  `LlmStreamCaller` 首字节前重试（单点覆盖 5 个 Workflow 步骤）。
  **复测有意外**：分类失败 30→0、进准入池请求 10→15 ✅，**但暴露出更根本的问题——分类判不准**
  （同一句「我想系统学 Redis」只有 15/40 判对；第一轮压测时这层被 429 掩盖了，因为模型压根没机会判）。
  **再挖一层找到真根因**：`AiJudgeModelSupport.applyTo()` **只设 model、不设 temperature**，
  走服务端默认 1.0 → 同一模型同一句话也会飘。该类的注释里**其实描述过症状**
  （「换模型后判出完全不同的结果」），但归因给了模型差异，漏了温度这一半。
  修法 `temperature(0.0)`（仅判断链，生成链不受影响）。**评测 A/B：失败 8/37 → 4/37**，
  但**有得有失**——7 个修好的同时 4 个"飘对"的变成稳定错；剩下 4 个全是「分界」case，
  **温度解决"飘"、解决不了"分辨不出"**。
  详见 `docs/agent/llm-call-reliability-design.md`

- **2026-09-19 准入压测（L1）**：自建 Node 压测脚本（**SSE 长连接，JMeter 不适用**——要量首字节、
  要保持连接、要按用户分组构造场景）+ 复用 `jwt.secret` 自签 JWT（200 个测试账号密码拿不到）。
  小池（2+3）验出**容量算法精确正确**；真实配置（8+32）× 40 并发全部 200。
  **核心发现：40 个请求里 30 个在意图分类阶段被供应商 rate limit 打挂、静默降级成闲聊
  —— 真正的容量上限在 LLM 供应商配额，不在应用层 worker**。
  踩到两个操作坑：`TaskStop` 不杀 Java 子进程（压到了旧实例、数据全假）、
  JS 数字精度吞掉 19 位雪花 ID（压测打在一个不存在的用户上）。
  报告 `docs/agent/agent-admission-stress-report.md`

- **V1** 基础（文章 CRUD / 分类 / 标签）+ **V1.5** 工程化第一层（XSS / 限流 / 分布式锁 + 幂等 / 乐观锁）
- **V2.x** 互动（评论 / 点赞 / 收藏 / 关注 / 主页）+ Agent 骨架（建议桥 / inspection 只读 API / 思考过程实时展示 / 受控写动作 + 双表乐观锁 / 第二个单 Agent + 骨架抽取 + 公共 SSE 管道）
- **V3.0–V3.13** AI/Agent 主体：通用思考模式（needsThinking 正交判定 + maxSteps=3 双闸门）→ 受控写动作扩张（勾选 / 追加 / 重命名 / 文章域改标题 / 隐藏公开）→ 路由注册表 → 写动作执行器注册表 → 会话文章锚 + `anchorMode` 指代消解 → QA 跨页指代接入 → 思考展示 `thoughtSummary`（sanitizer 分级闸门）→ **V3.11 证据收敛门**（规则闸 + 语义闸，第一个 evaluator-optimizer 闭环）→ **V3.12 会话结论继承**（`ai_sessions.last_conclusion_*` + 读取单点 + 防二阶注入）→ **V3.13 Plan Preview 阶段一**（文章域拆 2-4 子目标展示 + 观测，不锚定不驱动）
- **2026-09-19 AI 长任务准入与资源隔离**：专用有界池 + 每用户硬上限 2 + 429/503 明确拒绝 + `[AI-TASK]` 观测；
  前端配套把流状态从**组件级单例**改成**按会话隔离**（原先一个流在跑就锁死整个面板）。
  **第二轮补漏**：准入原先只覆盖 SSE 流式入口，漏了同步 HTTP 入口（Agent 建议确认 + 六个非流式接口）——
  新增 `runAdmitted`（占名额 + 当前线程执行），并顺带发现 **HikariCP 默认 10 < 准入 8 个长事务**。
  详见下方「并发 / 资源隔离」节
- **2026-09-13/14 学习域七连修**：会话学习计划锚（`last_learning_plan_*`，分类器不读锚、后端单点消费）→ **JSON Mode 修复**（模型漏收尾 `]}` 57% → `response_format: json_object` 归零）→ **判断链/生成链分离**（`blog.ai.judge-model`，分类器/决策器/验证器走独立模型）→ **意图边界修复**（"分析我的计划"被判成查列表 → 不写锚 → 下一句定位不到）→ Agent 路径读锚补口（`effectiveGoal`）→ 计划匹配放开状态过滤（`matchPlansByMessage` 不再只认 ACTIVE）→ **追问续答**（`AgentFollowUpResolver`，分类器之前先拿本句定位计划）。设计稿见 `docs/agent/` 对应文档。
- **2026-09-17 token 统计全链路**：先修流式链路的**结果翻倍 + 落库读到 0**，再拆四刀补全**非流式调用点**
  ——此前 11 个非流式点里 10 个把 usage 整个丢弃（`.call().content()` 只给文本），钱包计费的支出侧是空的。
  - **流式侧两个坑**：① 框架把"累计 usage"塞进**多个 chunk**，逐 chunk 累加 → 结果 ×2（改记峰值）；
    ② `doFinally` 的回调在 `onComplete` **传播给下游之后**才跑，而落库读的是那一刻的累加器 → **永远落 0**
    （改 `doOnComplete` + `doFinally` 兜 cancel + `AtomicBoolean` 幂等）。⚠️ **同步流的单测会假绿**，必须用 `delayElements` 异步流
  - **四刀**：`ai_agent_runs` / `ai_agent_steps` 新增 token 列；`AgentStepDecider` / `AiIntentClassifier` 加 **usage 出参**；
    `ai_workflow_step_logs` 从硬编码 0 换成真值（前端那行 `token X / Y` 终于会显示）；记忆/摘要链只记日志
  - **关键设计**：Agent 的 token **挂在 run 内存对象上**——run 有 8 个出口，逐个改签名容易漏，
    而它们都会 `updateById(run)`，挂对象上就全覆盖了（**出口零改动**）；step 级靠 `WorkflowTokenRecorder` 的
    ThreadLocal 暂存 + runner 每步 drain（**handler 零改动**）
  - **三口径不重复计数**：`ai_messages.token_count`（这条消息的完整成本，**含分类器**）/
    `ai_agent_runs.total_tokens`（Agent 循环内部）/ `ai_workflow_runs.total_tokens`（Workflow 内部）
  - **实时步骤日志补元数据（同日）**：数据库里 token 一直是对的，但**前端实时日志**显示不了——
    它从 SSE 步骤事件构造对象时只有 `message`，于是把「步骤完成，耗时 61515ms」塞进 `inputSummary`、
    耗时栏显示 `—`、token 行因 `v-if` 判 false 直接不渲染，**要等 STOP 拼上数据库日志才正常**。
    修法：`AiWorkflowStepEmitter` 加带元数据的 `emit` 重载（默认降级旧签名，noop/测试桩零改动）→
    `WorkflowStepRunner` 把 `drainStepBuffer()` **提到 emit 之前**（emit 与落库共用同一份，drain 只能取一次）→
    事件带 `durationMs` / `inputTokens` / `outputTokens`；顺带修掉 STOP 拼接的**重复显示**
    （原先同一步骤会同时出现实时条 + 数据库条，现按 `step#status` 去重、数据库优先）

### 能力清单

- **部署链路**：本地开发 → 构建 → Linux + Nginx → 公网访问 ✅
- **功能**：文章 CRUD（Markdown）/ 评论点赞 / 收藏关注 / 用户主页 / 搜索（V0 标题模糊，AI 问题走 RAG）/ 学习计划 + 任务勾选
- **AI 能力**：全站助手（SSE 流式 / PageContext / 会话历史 / 文章问答）、Tool Calling、Agent Router V1、5 个 Workflow、**三域 Agent Runtime**（学习 / 文章 / 通用，共用 `AbstractAgentRuntime` 骨架）、**Agent → Workflow 建议桥**（确认卡 + confirm/cancel）、**受控写动作**（归一化四分 actionType，V3.4 跨域三明治锚定 / V3.6 执行器注册表 / V3.7 前置推导 + 原子条件更新）、**思考过程展示**、**证据收敛门**、RAG 混合检索 + rerank、Memory 长期记忆
- **评测门**：固定样例集 + 一键 runner `-Dgroups=eval`（离线 18 / 加 `-DrunRealLlm=true` 在线 38，共 56 例）+ 回归门槛规则（改分类器 prompt / 意图分界 / 匹配算法 / 新写动作 / Agent 决策 prompt / Verifier prompt / focus 定位算法 → 上线前必跑）；跑法与门槛见 `docs/agent/agent-eval-gate.md`
- **工程化**：Redis 限流 / 分布式锁 + Idempotency-Key 幂等 / 状态 CAS / XSS 防护 / MySQL 乐观锁（双表 version）/ **token 统计全链路（2026-09-17：流式修复 + 四刀补全非流式调用点，三口径不重复计数）** / **敏感配置外置（2026-09-14）** / **Redis 缓存可靠性（2026-09-15：读路径三态收口 + 互斥回源 + `KEYS`→`SCAN` + Lua 原子取删与替换 + 逐 key TTL）** / **缓存一致性（2026-09-15：`AfterCommitExecutor`——写路径缓存失效统一延后到事务提交后，含回滚不白删 + `[CACHE-EVICT-FAIL]` 可检索日志）** / **缓存延迟双删（2026-09-20：`DelayedCacheEvictor`——提交后删 + D 毫秒后重放，堵住"读请求跨提交写回旧值"；**D 由读路径耗时实测决定**）** / **登录防护（2026-09-15：失败次数限制双维度非等权——账号 5 次/15min + IP 30 次/15min + token 黑名单 `jti` 精确 TTL）**
- **暂缓**：站内消息通知 / 私信、游客临时会话次数限制、GitHub OAuth 安全细节
- **token 统计已知缺口（登记不修）**：Agent 子类**扩展终态路径**的 step 级 token 记 0（run 级完整，不影响计费）/
  workflow 路径的意图分类器消耗不落库（只覆盖聊天 · Agent · CTA 三落点）/ 记忆与摘要链只记日志不落库（`[PERF-AI] llm_usage`）/
  历史数据不回填；另外 `ai_workflow_step_logs` 在 `db/init.sql` 里**缺 `log_type` 列**（实体与测试 schema 有）——存量 bug，等真报错再修
- **面试材料**：`docs/interview/agent-interview-notes.md`（六个真实踩坑 + 范式定位 + 架构 Q&A + 框架边界）

### 设计文档索引

**路线与选型**

- `docs/capability-audit-2026-08-06.md` — 能力审计（第一层 / 第二层 / 第三层分层依据）
- `docs/engineering-next-cut-review.md` — 工程化下一刀选型评估（含 Codex 复核结论）
- `docs/search/site-search-es-design.md` — **站内搜索：LIKE 全表扫描 → ES 全文检索**（设计稿待评审；含与 RAG 检索的分工、IK 分词决策）
- `docs/agent/agent-v4-autonomous-loop-design.md` — V4 自主闭环整体设计（两层模型 / 五概念职责 / 四档任务选型 / Q1-Q5）
- `docs/agent/agent-module-acceptance-checklist.md` — V1→V4.5 阶段收口验收矩阵（A-F 六组 15 行）

**Redis / 工程化**

- `docs/redis/redis-capability-design.md` — Redis 能力补全（能力地图 + 五刀路线 + 6 处隐患）
- `docs/redis/delayed-double-delete-design.md` — **缓存延迟双删（第六刀）**（✅ 已实施；含**延迟值的实测方法与数据**、推翻原两条判据、`UserSetCache` 为何不纳入、两个实施坑）
- `docs/redis/cache-breakdown-avalanche-design.md` — 击穿 / 雪崩专题（读路径收口 + 互斥 + TTL 抖动）
- `docs/redis/redis-ops-runbook.md` — 运维排查手册（慢查询 / 大 key / 热 key / ACL / 连接）
- `docs/redis/wallet-verification-guide.md` — **钱包 + AI 计费验证指南**（DDL / 启动自检 / 手动测试案例 / 已知缺口）
- `docs/security/sensitive-config-externalization-design.md` — 敏感配置外置（已实施）

**Agent 能力（按版本倒序）**

- `docs/agent/agent-task-orchestration-design.md` — **Agent 分层任务编排（第三刀：TaskPlan + Orchestrator）**（✅ 第一版已实施；含 §5.0 实施结果与已知残留、§5.2 路由判据收口、§4.1 父子 run 的 10 条边界清单）
- `docs/agent/agent-stage2-action-plus-analysis-design.md` — **动作 + 分析（第二刀）**（✅ 已实施）
- `docs/agent/agent-long-task-admission-design.md` — **AI 长任务准入与资源隔离**（✅ 已实施；含 §1.4 被推翻的三条判断 + §5.3 `RUNNING` 启动恢复 + §8 实施后补漏）
- `docs/agent/agent-admission-stress-design.md` — **准入压测设计**（三层目标 / 为什么不用 JMeter / 两个操作坑）
- `docs/agent/agent-admission-stress-report.md` — **准入压测报告**（✅ 已完成；容量算法实测精确 + 真瓶颈在供应商速率限制）
- `docs/agent/llm-call-reliability-design.md` — **LLM 调用可靠性**（✅ 已实施；失败分类 + 429 可重试 + 流式首字节前重试）
- `docs/agent/agent-json-mode-fix-design.md` / `agent-judge-model-separation-design.md` / `agent-learning-intent-boundary-fix-design.md` / `agent-session-plan-anchor-design.md` — 学习域七连修
- `docs/agent/agent-v3-13-plan-preview-design.md` — Plan Preview 阶段一（含 Plan-guided ReAct 五条硬约束登记）
- `docs/agent/agent-v3-12-conclusion-inheritance-design.md` — 会话结论继承
- `docs/agent/agent-v3-11-evidence-verifier-design.md` — 证据收敛门
- `docs/agent/agent-v3-10-thought-display-design.md` — thoughtSummary 分级闸门 + 术语定位
- `docs/agent/agent-v3-9-qa-cross-page-anchor-design.md` / `agent-v3-8-session-article-anchor-design.md` — 锚与指代消解
- `docs/agent/agent-v3-7-article-visibility-write-design.md` / `agent-v3-6-write-executor-registry-design.md` / `agent-v3-5-route-registry-design.md` / `agent-v3-4-article-write-action-design.md` / `agent-v3-1-controlled-write-add-task-design.md` — 受控写动作线
- `docs/agent/agent-v4-intent-boundary-closeout-design.md` — Agent / QA 路由边界收口（含基线实测方法论 §8.1）
- `docs/agent/agent-v4-conclusion-inheritance-rollout-design.md` — V4③ 第二刀·讨论稿（待评审）
- `docs/agent/agent-observability-traceid-design.md` — traceId 贯穿（已实施）
- `docs/agent/agent-v4-5-global-thinking-display-design.md` — 聊天 / QA 轻量过程提示
- `docs/agent/agent-framework-evaluation.md` — 框架能力对照（LangGraph / LangChain4j / MCP，9 维度）
- `docs/agent/agent-v4-observation-log.md` — 五轮观测记录
- `docs/agent/agent-eval-gate.md` — 评测门
- 早期：`agent-runtime-v1-design.md` / `agent-runtime-v2-1-workflow-suggestion-design.md` / `agent-v1-acceptance-checklist.md` / `agent-v3-0-general-thinking-closeout.md`

> AI 注意：完成关键里程碑后，请主动更新此节。

## 下一步

### 主线：Redis 工程化五刀（2026-09-15 全部完成 ✅）

> **五刀已全部完成**（2026-09-15）。原先登记的两个候选——**额度系统**与**并发 / 资源隔离**——
> **也都已完成**（见下方各自章节）。原先登记的「下一刀」（`RUNNING` 启动恢复）也已完成。
> 下一步候选见「Agent 主线（V4）」的剩余待办与「工程化清单」的未勾选项。

设计稿 `docs/redis/redis-capability-design.md`。范围从「击穿 + 雪崩」扩到 Redis 全景后，**代码核实挖出 6 处真实隐患**：`KEYS` 全库扫描（每 30 秒一次）、浏览量同步双写不一致、`:loaded` 语义被破坏（数据展示错误）、`Set` 无 TTL、热度榜重建窗口期、连接池 / 淘汰 / 持久化全未配。拆成五刀推进：

1. ✅ **缓存可靠性**（已完成）：读路径三态收口 → 互斥回源（击穿）→ **`KEYS`→`SCAN`（实为 5 处，不只浏览量）** → 浏览量 Lua 原子取走 + 补 TTL + 逐 key 容错 → 7 处数据缓存 TTL 抖动（9 处 `:loaded` 标记不抖）
   - 测试（2026-09-15 补）：`ArticleDetailMutexTests` 4 例——8 线程并发验"只回源一次"、轮询读到空值不误删、轮询超时 fail-open、浏览量原子取删只取一次；**4 处反向验证**都精确停在预期断言上。数 DB 查询次数用自定义 MyBatis `Interceptor` 拦 `Executor.query`，**不能 spy `ArticlesMapper`**（JDK 动态代理，`MybatisMapperProxy` 内部状态取不到）
2. ✅ **用户态集合与热榜**（已完成）：9 处 `:loaded` 写点收口到 `component/UserSetCache`（**FULL / EMPTY 状态**，只有全量加载才写标记）+ 4 处重复写入清理 + Set TTL(40min) > 标记 TTL(30min) + 热榜临时 key + `RENAME`
   - **顺手修掉一个存量 bug**：`markLoaded` 用 `SADD` 是追加不是覆盖——标记过期但集合还在时回源重建，会把残留成员留下，**已取消的点赞"复活"**
   - **原设计的"定时任务互斥"被推翻**：核实为默认单线程调度器、本就不并发，加锁是为不存在的问题写代码。改为显式 `spring.task.scheduling.pool.size: 1`
3. ✅ **生产配置**（已完成）：超时 `3s` / `connect-timeout 2s` + **连接池决策为「不引 `commons-pool2`」**（三条需要池的理由逐条核实全不成立）+ `maxmemory-policy: volatile-lru` + 持久化取舍（可讲非必做）+ **应用账号 ACL `+@all -@dangerous`**（不需要 `+client`——查 Lettuce 源码确认握手命令失败被吞掉）
   - 产物：`deploy/redis.conf.example`（带取舍注释）、`docs/redis/redis-ops-runbook.md`（慢查询 / 大 key / 热 key / ACL / 连接排查）
   - **顺带补上热度榜 TTL**：补之前它是唯一无 TTL 的 key，而 `volatile-lru` 的前提是「允许丢失的数据都有 TTL」
   - **实施中实测复现一个并发 bug**：两个执行者共享热榜临时 key，后到者 `RENAME` 报 `ERR no such key` → 打包成 Lua 原子替换（`ArticleHotRankTests` 3 例锁住，含反向验证）
4. ✅ **安全类 Redis**（已完成）：登录失败限制 + token 黑名单
   - **计数维度取双维度、非等权**：账号维度 5 次 / 15 分钟（严）+ IP 维度 30 次 / 15 分钟（宽）。判据是**攻击形态覆盖矩阵**——账号维度拦定向爆破与**分布式爆破**（一账号、代理池多 IP），IP 维度拦**撞库**（多账号、同源 IP）；「用户名 + IP」组合键只拦得住第一种，**方案第一版就错在这里，实施时退回去了，已纠正**。残留代价：账号维度仍能被拿来锁死真实用户，代价限制在 15 分钟且本人登录成功即清零
   - **成功登录只清账号键、不清 IP 键**：清了等于留后门——攻击者只要有一个自己的账号，就能靠反复登录成功把 IP 计数刷掉
   - **归一化是被 DB collation 逼出来的**：实测 `utf8mb4_unicode_ci`，`Admin` 与 `admin` 在库里本来就是同一个用户，不归一化则大小写变体把 5 次阈值拆成 15 次
   - **防账号枚举三条一起做**：不存在的账号同样计数 + 提示语统一 + **补跑一次 bcrypt**（第三条原来没想到——查不到用户直接返回会快 ~100ms，响应时间本身就是探针）
   - **token 黑名单加 `jti`**（RFC 7519 设计意图），TTL 取 token **剩余有效期**；两个 JWT 拦截器都要查，只改一个等于没登出
   - **前端登出是死代码**：`authApi.logout()` 定义了却从未被调用，登出只清 localStorage——后端做完黑名单也没人触发，这次一并接上（失败忽略 + 单独 3s 超时）
   - 测试：单元 22（`LoginAttemptLimiterTests` 15 + `TokenBlacklistTests` 7）+ 集成 8（真 Redis + MockMvc），**3 处反向验证**（去掉归一化 / 去掉 IP 维度检查 / 跳过黑名单检查，都精确停在预期断言上）
5. ✅ **缓存一致性补偿**（已完成）：新增 `component/AfterCommitExecutor`——有事务注册 `TransactionSynchronization` 到提交后执行，**无事务就地执行**；所有写路径的缓存失效统一收口（改造做在封装方法内部，调用点一个没动）
   - **不用 `@TransactionalEventListener(AFTER_COMMIT)`**：事务事件在无事务时**默认不执行**，而本项目大量缓存失效在非事务方法里——哪天去掉 `@Transactional`，缓存就静默地永不失效，比"删早了"更难发现
   - **收口范围是清点出来的，不是拍脑袋定的**：全量清点发现受影响的不是 4 处而是 **10 个方法**（文章 2 / 点赞 2 / 评论点赞 1 / 学习计划 5），跨 3 类缓存 5 个文件
   - **顺带修掉两个**：事务回滚不再白删缓存；`ArticleFavoritesServiceImpl` 原本裸调 `delete` 无 try-catch（Redis 一抖收藏直接失败）
   - 删除失败：静默 `catch` → `[CACHE-EVICT-FAIL]` 可检索日志 + TTL 兜底，不做重试（Redis 挂了重试也白搭）
   - 测试：`AfterCommitCacheEvictTests` 6 例（真 Redis + `TransactionTemplate` 手动控事务边界，事务内断言一次、提交后再断言一次）+ 回归 9 个类 64 例；**2 处反向验证**（取消延迟执行 → 5 红 1 绿；去掉异常兜底 → 精确红 1 条）
   - **仍不是强一致**：残留一个需要恰好并发的窗口（读请求在提交前查库拿旧值，写缓存晚于我们的删除），上延迟双删/binlog 才解决，触发条件见设计稿 §八
   - **登记不修**：RAG 索引同步是 `@Async` 却在事务内发起，异步线程可能读到提交前的旧文章

**关键取舍（面试口径）**：浏览量是**可接受丢失的近似统计**——`Lua 原子取删 → 更新 DB` 不是可靠投递，只用于允许近似的统计，**不能套到库存 / 余额 / 订单**。
**另一条口径**：用户集合的"全量标记"不能无条件写——`loaded` 声称全量、集合却只有一条，是数据展示错误（不是内存问题）。

### 额度系统：钱包 + 秒杀（**批次 A/B/C 全部完成 2026-09-18**）

**定位**：不是独立电商场景，而是**接到已有的 token 统计上**——用户充值额度，AI 调用按实际 token 消耗扣减。面试价值：秒杀是面试超高频（防超卖 / 限流 / 削峰 / 幂等一条链全占），钱包提供「不可丢数据」的对照组。

**分工**：

- **钱包余额 / 扣减 = 纯 DB**（`UPDATE ... WHERE balance >= N` 条件更新 + 流水表）——额度是钱，引入 Redis 只增加一致性风险；面试加分点正是"知道什么场景不该用 Redis"
- **秒杀库存 = Redis Lua 原子扣**（预扣）→ 异步入账（DB 事务 + 流水）
- **一人一单**：Redis Set 去重 + DB 唯一索引双保险
- **必须有对账**：Redis 扣减数与 DB 入账数定期核对，差额告警（"Redis 扣了但 DB 没落"是必被追问的点）

**实施顺序**：钱包 DB 版（零 Redis，纯正确性）→ 接进 AI 链路（额度不足拒绝调用）→ 秒杀发放 + 补偿。
**进度（2026-09-18）**：

- ✅ **批次 A 钱包本体**：`user_wallet` 条件更新（门槛 `balance > 0`，**允许透支一轮**）+ 流水表（按 `balance_seq` 严格排序，不用时间戳也不用雪花 ID）+ 模拟充值（状态 CAS + 流水唯一索引双防线）。**纯 DB，零 Redis**——判据是「钱不能丢」。
- ✅ **批次 B 聊天计费**：硬上限预扣 → 实际用量结算 → 差额退回。预扣依据 = `finalPromptContext` **真实长度** + `maxTokens` × 工具轮数（输入在调用前已确定，不需要靠分布去猜）。额度不足走 **HTTP 402**——项目默认的 200 + JSON 会被前端 SSE 静默忽略，用户只会看到「连接意外中断」。
- ✅ **账单聚合（记账视角 ≠ 账单视角）**：`wallet_transaction` 是**记账视角**——一次 AI 调用写两条（预扣 + 结算退回），因为「改余额」和「记账」要逐笔对应，否则按 `balance_seq` 的逐笔回放会断档；而**用户账单**按 `biz_id` 聚合成一条净额。**预扣中**（净额没定下来）与**净额为 0**（取消的调用）的都不进账单。分页必须按聚合后的条目数算，前端过滤做不到。
- ✅ **注册赠送**：`blog.wallet.initial-credit` 默认 10000，注册即送。**这是「注册就能用」和「计费上线」之间那一环**——计费开关默认开着，不送的话每个新账号注册完立刻就是「额度不足」。幂等键 `INITIAL:{userId}` 保证只送一次。两个注册入口的事务语义**故意不一致**：本地注册走同一事务（送礼失败连用户一起回滚，否则用户名被自己占死、重试都重试不了），GitHub OAuth 入口是独立事务（那里前面有两次调 GitHub 的 HTTP，包进事务就成长事务 + 事务内远程调用）。**存量账号不覆盖**，余额仍是 0。
- ✅ **批次 C 秒杀**：Redis Lua 原子预占（类型预检 → 查重 → `XADD` → `DECR` → `SADD`）→ Stream 消费组异步落库 → **DB 条件更新 `sold_count < total_stock` 最终裁决** → 失败分类（业务失败 ACK / 技术失败不 ACK + 死信 `FAILED_RETRY`）→ 对账判收敛而非相等。测试 10 例 + 两处反向验证。
  - **「排队中」≠「抢到了」是这部分的语义核心**：`grab` 成功只代表名额占住、事件入队，钱要等消费者落库才到账。前端必须轮询 `/result`，UI 上「排队中」（暖黄）和「已到账」（绿色）必须是两个状态——合并成一个提示，用户就会问「我抢到了为什么额度没到」。
  - **Redis 挡流量、DB 保正确**：Redis 故障走 **fail-closed**（整条链路依赖预占 + 事件，放行到 DB 只会让用户永远等）。能走到 DB 的只有中签者，所以 **DB 压力是 O(库存) 而不是 O(QPS)**，削峰没有失效。
  - **回收用 `XPENDING` + `XCLAIM` 而不是 `XAUTOCLAIM`**：死信判据要的 delivery count，`XPENDING` 扩展形式直接给，一个命令拿全（设计稿 §6.3 原写 XAUTOCLAIM，行为等价）。
  - **预热刻意不清 `events`**：Stream 里是已预占未入账的名额，删了等于凭空抹掉——用户永远停在排队中，而库存已经少一份。只清 stock / users / meta。
  - **预热灌的是「总库存 − DB 已发放」**，不是总库存；取错口径每重建一次就多发一批。
  - **运维接口用配置白名单**（`blog.seckill.admin-user-ids`，默认空 = 谁都不能调）：项目没有角色体系，这是最小权限的临时方案。
  - **压测实战（2026-09-18，200 并发）**：结果 `sold_count = 45` 而非 50，日志 5 条「入账失败」+ `wallet_transaction.uk_user_idem` 唯一键冲突。**根因不是并发也不是代码**——是压测重来时 `DELETE FROM seckill_order` 只清了订单、没清流水（流水是账本，删不得），同一批用户二次抢到时撞上流水幂等键 `SECKILL:{activityId}:{userId}`。
    **链条上每一环都在正常工作**：幂等键挡住了一次重复发钱，事件留在 PEL 等重试，`sold_count` 没超过 `total_stock`。
    修法：① 重来一次**新建活动**，不要重置旧活动；② 加 `[SECKILL-DIRTY-RESET]` 日志把根因直接点出来——否则堆栈里只有一个唯一键冲突，**完全指不到「重置」这个动作**。
  - **两个运维坑**：消费者每 2 秒查一次 DB 会把 MyBatis 日志刷爆（改成活动列表缓存 60 秒 + 预热事件清缓存，否则预热完的活动最多 60 秒才被消费）；拿掉缓存后又发现「缓存了空列表」会让新活动一直不被消费——所以缓存和预热必须联动。
  - **两条对账线判据正好相反（2026-09-19 补）**：秒杀判**收敛**（异步落库有延迟，单轮不一致只 warn，连续 3 轮才 error）；钱包流水判**必须相等**（只增不改的账本，断档不会自愈，发现即 error）。钱包那条查的是**流水自身自洽**而不是「余额 = 流水累计」——后者会被任何一次手改余额的 SQL 打破（验证指南里就教过用来造场景），那是造场景不是坏账。两个任务都**刻意不自动修复**：哪个数是对的只有人知道
  - **钱包对账的盲区是自己补的**：`LAG` 对每个用户的第一笔返回 NULL，看不见「开头的流水被删」。删掉开头后剩下的序列仍然首尾自洽，只有「首笔必须是 1」这个不变量抓得住——所以是两个查询不是一个（设计稿 §7.3 只有前一条）。秒杀深度对账（§7.2）**登记不修**，理由见验证指南 §七
  - **秒杀 key 的 TTL 与孤儿 stream（2026-09-19 运维实测挖出）**：`--bigkeys` 扫出 db0 里 **86% 的 key 是空 stream**。根因**不在测试**（`@AfterEach` 早就在删 key 了），而在消费者的 `XGROUP CREATE ... MKSTREAM`——`MKSTREAM` 的语义是「不存在就**创建**」，而这任务每 2 秒跑一轮，于是刚被删的 key 会被它一遍遍建回来。修法：先 `hasKey` 确认再建组（events 由抢购脚本的 `XADD` 创建，key 不存在 = 没人抢过）+ 去掉 `MKSTREAM`。
    同时给秒杀 key 补 TTL：预热建的三个 = `endAt − now + 7 天`（**下限不能随剩余时间下沉**——活动已结束时剩余时间是负数，而 `EXPIRE` 收到负数会**立即删除 key**）；抢购才创建的 `users` / `events` 由 Lua 判 `TTL < 0` 补 30 天。**`users` 这个缺口是回头读 Lua 才发现的**——它由 `SADD` 创建，预热时给它设 TTL 是空操作（key 还不存在，`EXPIRE` 静默返回 false）。两处反向验证都精确命中各自那条用例
- ✅ **秒杀每日自动开场（2026-09-20 补）**：每天 10:00–10:30 自动一场、50 份、每场 5000 credit。
  **只建一场 DRAFT 活动**——激活/预热/结束全交给下面那套生命周期流转，一行都不重复实现
  （顺带验证了那层抽象拆得对：加一个「每天定时开场」没碰激活/预热/结束的任何一行）。
  - **活动 id = `yyyyMMdd`，幂等完全靠主键**：重复创建直接撞主键，不需要任何额外机制，
    也不用给表加唯一列改两张 schema。段位约定：**8 位** = 自动开场、**12 位** `yyyyMMddHHmm` = 手工开一天两场、
    **19 位**雪花 = 手工临时活动。⚠️ **不要推广到别的表**——`articles.id` 是 `AUTO_INCREMENT`，
    在那张表手写 `20260921` 会把自增计数器顶到两千万
  - **「下一场」而不是「今天那场」**：`LocalTime.now() < endTime ? today : today+1`。10:30 那一刻
    今天的卡被列表过滤掉、明天的卡同时建出来，**列表永不空**。这道判据还顺带堵掉一个坑：
    不判的话，10:30 之后启动的服务会建出一场窗口全在过去的活动——它**永远停在 DRAFT、
    永不激活、无异常无日志**，还会以「未开始」的置灰卡片永久挂着
  - **每分钟轮询而不是 `cron = "0 0 0 * * ?"`**：零点那一刻服务若在重启/宕机就漏一天；
    每分钟检查能**自愈**（10:15 启动的进程几秒后建出当天那场，生命周期 5 秒内激活，用户 10:15:08 就能抢）。
    成本是每分钟一次主键点查，量级不到同池 60 秒一轮的消费者的百分之一
  - **列表过滤用 `end_at > now` 而不是 `status != 'ENDED'`**——理由是**它和抢购谓词同构**
    （Lua 里判的就是 `now < endAt`）：过滤条件 = 「现在还能不能抢」。`status != 'ENDED'`
    会漏出排空门期间仍是 ACTIVE 的活动，以及上面那种永不激活的死 DRAFT。
    **反向验证命中**：换成 `status != 'ENDED'` 后列表用例精确变红
  - **排序也必须一起改**：`startAt DESC` → `endAt ASC`。列表语义从「历史流水」变成
    「接下来能抢什么」，DESC 会把最晚开始的排最前，而配合 `LIMIT 20` 截掉的恰好是
    今天正在跑、此刻唯一能抢的那场
  - **窗口配反是唯一「静默全废」的方向**：`end-time` 早于 `start-time` 时激活判据在同一天内恒为假，
    活动永远停在 DRAFT、不报错不打日志。由 `SeckillDailyValidator` 启动时 fail-fast 堵住
    （对照：yml 里 `10:00` 忘加引号是**响亮**的——YAML 1.1 把它当 60 进制整数解析成 600，启动直接挂）
  - **前端修掉一个真 bug**：`scheduleRefresh` 原先在「排不出边界」时直接 return、不排任何重拉
    → 空列表时**页面冻死**，用户挂着等开场什么都不会发生。加了兜底重拉
  - **删掉了 `db/init.sql` 的秒杀种子活动**（原 id=1、100 份、30 天窗口的 ACTIVE）：有了自动开场
    它就多余了，而且会一直挂在列表上且能抢，让「每天 50 份」看起来是坏的。
    验证指南 C0/C1 已改用当天自动建的那场
  - 测试 5 例 + 一处反向验证
- ✅ **秒杀活动生命周期（2026-09-20 补）**：`status`（DRAFT/ACTIVE/ENDED）在此之前**全项目零写入**
  ——两个常量定义了但从未被赋值。补上 `SeckillLifecycleTask`（5 秒一轮）+ CAS 流转：
  `DRAFT --到点--> ACTIVE(+自动预热)`、`ACTIVE --到期/售罄且已排空--> ENDED`。
  - **先划清边界：抢购的正确性一直没有 bug**——时间窗和售罄是 Lua 判的，这次补的是运营自动化。
    三层时间边界各管各的：Lua 管正确性、status 管运营视图、前端只管展示
  - **最要紧的一条：ENDED 的前置条件是「已排空」（XLEN=0 且 PEL 空），不是「时间到了」**。
    消费者只查 `status = ACTIVE`，一置 ENDED 就再也不看它——而 PEL 里可能还有在途事件
    （用户已抢到、正等入账），那条事件会永远烂在 PEL 里。原先打算放宽消费者的查询条件，
    改成排空门后**消费者和对账一行都不用改**，不变量还更强（「ENDED ⟺ 无在途工作」）
  - **`pendingCount` 的失败语义必须保守**（查不到就抛 = 当作没排空）：对账任务那份失败返回 0
    是无所谓的，这里返回 0 会让还有在途事件的活动被结束——**这是唯一会丢数据的方向**
  - **预热修复的探针是 `HGET meta status` 不是 `hasKey`**：后者发现不了「建起来了但状态位是错的」
    （拿 CAS 之前的旧实体去预热，写进 meta 的就是 DRAFT），而那正是这条流程最容易产出的坏状态。
    能只 HSET 修状态位就**绝不重建**——重建会清掉在途预占、重置对账基准
  - **`status_changed_at` 刻意不叫 `updated_at`**：`increaseSoldCount` 每发一份额度就 UPDATE 一行，
    那个列会让时间戳每次抢购都跳。只在 CAS 语句里显式写，顺带保证 MySQL/H2 行为一致
  - **多实例不需要分布式锁**：CAS 输家静默返回 0（照 `refreshPlanStatus` 的论证：
    幂等定时任务失败说明别人推过了）
  - **前端本地时钟只允许把状态推向「更不可抢」**（过了 `endAt` → 显示已结束），
    **`startAt` 绝不参与判断**——那会造出「前端说能抢、后端说没开始」的窗口。
    另补了边界重拉（这页原先只有 `onMounted`，用户停着的话活动开始/结束了界面都不变），
    顺带修掉 `buttonLabel` 对 DRAFT 活动返回「立即抢」的既有 bug
  - **顺手修掉一个既有 bug**：`SeckillReconcileTask.pendingCount` 用
    `connection.execute("XPENDING", ...)` 原生命令读 PEL，而 Lettuce 的 `ByteArrayOutput`
    **解析不了 summary 里的整数**，会抛 `UnsupportedOperationException`（被包成
    「Unknown redis exception」），又被 `catch (RuntimeException) { return 0; }` 吞掉
    → **恒返回 0** → 对账那句「在途多于 PEL 待处理」从来没真正生效过。改用
    `opsForStream().pending()`。**这个坑是写生命周期时踩到并定位的**
  - 测试 13 例（含两条反向用例：塞一个「在途用户」验证修复没有误触发重建；
    `meta.status` 改错后只被 HSET 修好、`users` 成员仍在）
- ✅ **自定义金额充值（2026-09-20 补）**：套餐之外的自主充值，整数元 **1 ~ 1000**，
  按基准 **1 元 = 1000 credit**（无优惠——PACK_10 本来就是基准价，自定义 = 原价买灵活度，套餐才是折扣）。
  - **不是给现有 DTO 加个可选金额字段，而是另开一个入口**：套餐那条「请求体里根本没有金额字段」
    是靠**类型**保证的（不可能发生），合并成 `{packageCode?, payYuan?}` 就降级成「靠 if 拦」（可能发生）
    ——在钱的路径上那是降级。**洞的实质不是「请求体里有金额」，而是「客户端决定了到账多少 credit」**：
    自定义入口里客户端只决定「充多少钱」，credit 仍由后端按配置算（设计稿 §4.6）
  - **三个写的时候差点踩的坑**：① DTO 必须用 `BigDecimal`——Jackson 默认开 `ACCEPT_FLOAT_AS_INT`，
    `Long` 字段会把 `{"payYuan":1.5}` **静默截断成 1**，不报错、无日志；
    ② `1e30` 的 scale 是 -30，过得了整数判定却会在 `longValueExact` 抛 `ArithmeticException`
    → 落到 `Exception` 兜底变成 **50000「服务器坏了」**（该说的是 40001「你传错了」）；
    ③ 换算用 `Math.multiplyExact`，`*` 溢出会**静默绕成负数**写进一张负金额订单
  - **校验刻意不放在 DTO 的 `@Min/@Max`**：上下限来自配置，而注解参数必须是编译期常量，两者天生冲突
    （改了 yml 注解不会跟着变，是必然漂移）。放在 service 还让「守卫 + 元→分换算 + credit 公式」挨在一起
  - **复用同一个 `pay`**：自定义订单与套餐订单同构（只多 `package_code='CUSTOM'`）。
    单开一条「自定义支付」会绕掉订单状态 CAS——幂等只剩流水唯一索引一层，而那个 bug 只在并发双击时才现形
  - **顺带补了一个既有缺口**：`choosePackage` 的 `paying` 只在 `onPositiveClick` 里置位，
    连点两个套餐会叠开两个确认框 → **两张订单、两次到账**（CAS 防不了这个，它只管同一张订单）。
    现在两个入口都在**开框之前**拦
  - 测试 19 例（含 `dtoKeepsFractionalInputInsteadOfTruncating`——锁字段类型，改成 `Long` 的话
    service 层测试会照样全绿，只有它能抓到）；前端 6 条源码断言
- 验证指南与手动测试案例：`docs/redis/wallet-verification-guide.md`
- 运维实测记录（bigkeys / slowlog / AOF 崩溃恢复，含 D 类素材）：`docs/redis/redis-ops-runbook.md` §七

**排期**：Redis 五刀已于 2026-09-15 全部完成 ✅（秒杀要用的原子扣减 / 限流 / 幂等已在前几刀打磨过）。

### 并发 / 资源隔离（2026-09-14 登记 → 2026-09-19 实测 + 出设计稿 → **2026-09-19 实施完成** ✅）

**设计稿**：`docs/agent/agent-long-task-admission-design.md`（四轮评审 + 实施）
范围：统一任务提交入口 + 每用户并发上限 + 专用有界池 + 明确拒绝 + 观测，**作为一刀做完**。
**不做**任务化 / 轮询。

**2026-09-19 实测（`BoundedElasticSaturationTests`，3 例）**：

- 全局池 = **`10 × CPU`**（20 核 → 200 线程，**实测非推算**）；队列默认 **100000**
- **池满之后是排队，不是拒绝**——生产上几乎走不到"线程+队列都满"
- **三条原始判断被推翻**（详见设计稿 §1.4）：①「开 5 个会话就排队」是拿缩小池（4 线程）做的过度外推；
  ②「CANCELLED 322 秒 = 占线程」错——那是 `WAITING_*_CONFIRM` 等用户的时间，4 条记录全是 `used_steps=2` + 有答案；
  ③「线程数 ÷ 平均耗时 = 安全容量」只是稳态吞吐，忽略突发与长尾
- **结论**：要做准入的**依据是代码缺口（无准入 / 无上限 / 无观测 / 拒绝不明确），不依赖任何性能推算**

**现状核实**：

- **数据不会串** ✅ 隔离已做：
  `UserContext` ThreadLocal（Agent 跑在 `boundedElastic` 上**手动 set + `finally` clear**）/ MDC 靠 `onScheduleHook` + finally /
  Redis 锁按 `runId` / 会话与消息按 `sessionId` / 缓存 key 分级（限流按 user·ip、计划列表按 userId、
  文章详情全局共享**且只缓存 PUBLISHED**）/ 数据库并发写靠双表 version CAS + 条件更新 / `Idempotency-Key` 幂等
- **但会互相拖慢** ⚠️：Agent 循环与 Workflow 是**同步阻塞长任务**（LLM 调用几十秒到几分钟），
  全部挤在 Reactor 的**全局共享** `Schedulers.boundedElastic()`（上限约 CPU 核数 × 10）；
  且**没有用户级并发上限**——一个会话由 `activeWorkflowRunId` 保证单活跃，但**同一用户开多个会话可并发**

**实施结果（2026-09-19）**：

- **准入模块** `ai/task/`：两段式接口 `precheck`（best effort，在构造任何 SSE 事件之前调用）
  / `submit`（订阅时做权威判定）。每用户 `ConcurrentHashMap.compute` 原子计数，上限 2，
  **是硬上限——竞态结果是「这个任务被拒」，不是「多跑一个」**（降级成"尽量限制"就等于毁掉成本闸门）
- **专用池** `AiTaskAsyncConfig`：`core == max == 8` + 有界队列 32 + `AbortPolicy`。
  不用 `CallerRunsPolicy`——长任务退回调用者线程等于隔离失效；`core == max` 是因为
  `ThreadPoolExecutor` 会先入队、队列满才扩到 max，那会让 `queueWaitMs` 被配置本身人为放大
- **明确拒绝**：`USER_LIMIT` → **HTTP 429**、`POOL_FULL` → **HTTP 503**，且必须发生在**任何 SSE 事件之前**
  （响应一旦按 200 SSE 提交，状态码就改不动了）。⚠️ handler 必须与其余 handler **同处一个 advice**：
  跨 advice 按 advice 顺序匹配、**不按异常具体度**，另起一个会被 `@ExceptionHandler(Exception.class)` 抢先吞成 500
- **观测**：`[AI-TASK] start/done` 统一记 `queueWaitMs` / `executionMs` / `running` / `queued` / `inFlightOfUser` / 拒绝原因
- **收口 7 处**（`AiMessageServiceImpl` 6 + `AiWorkflowStreamServiceImpl` 1）。
  `precheck` 放在每个方法**开头**——既在任何 Flux 构造之前，也在 `save(userMessage)` 之前
  （否则拒绝后会留下孤立消息）。⚠️ 不必放到路由层：`Flux.concat(understanding, streamXxx(...))` 里的
  `streamXxx(...)` 是**同步求值**的，方法体在构造 concat 时就执行了，异常能直接传到 Controller

**实测结论（`SseAdmissionProbeTests`，5 条）**：只要**还没发过任何事件**，在 `Flux.create` 的订阅 lambda 内
抛拒绝异常**照样能返回 429**；先发过事件（如聊天创建 Workflow 的 `understanding`）就只能退化成流内错误。
⚠️ 探针本身踩过三个坑（必须用真实容器 RANDOM_PORT，**不能**用 MockMvc）：① `Accept` 头要跟前端一致——
前端 `fetch` **不带 Accept**（浏览器默认 `*/*`），想当然设 `text/event-stream` 会测出一片假 500；
② 不要自造异常 + 自造 advice（会被兜底 handler 抢先）；
③ `GlobalExceptionHandler` 里那个 `accept.contains("text/event-stream")` 分支**对聊天流是死代码**。

**前端配套（同日）**：原来 `sending` / `abortController` 是**组件级单例**——一个流在跑整个面板就锁死，
切到别的会话（含新建）后输入框仍禁用、发送键仍是暂停键。改成**按会话隔离**
（新会话 key 每次新建都换一个，否则两个"新会话"会共用同一个 key）+ **脱离守卫**
（切走不再更新视图，避免内容写到别的会话的消息数组上）。
四个流式入口 `streamChat` / `streamApproveWorkflow` / `streamRejectWorkflow` / `streamRetryWorkflow` 统一守卫；
`workflowBusy` / `localTransition` 从全局单值改为**按 workflow id 记录**（暴露成 computed，模板 12 处一行未改）。
⚠️ `onStep` / `onContentDelta` **刻意不加守卫**——它们按 `workflowRunId` 存，切走继续累积反而是对的。

**已知残留（登记不修）**：

- `worker=8 / queue=32` 是**起始配置不是容量结论**——它本质是**成本闸门**（= 同时向供应商发起的并发请求数
  = 花钱速率），定它要看**供应商并发上限 + 成本速率 + 连接池 + 可接受排队延迟**，**不能按 CPU 核数推导**
  （IO 密集任务的瓶颈在外部服务）
- **租户公平性不严格**：单用户最多占 2 个名额，但 20 个用户同时提交就能占满 40 个名额，后面全 503
- 前端 `confirmWriteAction` / `confirmWorkflowSuggestion` 等**普通短请求**仍会写全局消息列表，
  理论上有极短的串会话窗口（彻底解决需按会话隔离消息列表，是更大的改造）
- ~~**准入之外没有压测**~~ → **2026-09-19 已压测**（报告 `docs/agent/agent-admission-stress-report.md`）：
  小池（2+3）验出**容量算法精确正确**（`running`/`queued` 峰值与配置一致、池满 503、`queueWaitMs` 5.7s）；
  真实配置（8+32）× 40 并发全部 200、`rejected=0`。**真瓶颈不在应用层**——见下方面试口径

**暂缓**：任务化 + 轮询——**原始理由不成立**（SSE 返回 `Flux`，Spring MVC 走 Servlet 异步，**不占 Tomcat 线程**），
且轮询**解决不了取消**（任务本就脱离请求线程，SSE 断开 worker 照跑）。恢复条件见设计稿 §5.1。
**第二刀**：协作式取消（步骤间检查状态，当前 LLM 调用靠 HTTP 超时结束）。

**`RUNNING` 启动恢复（2026-09-19 完成 ✅）**：`AiRunStartupRecoveryRunner` 启动时把遗留的 `RUNNING`
标成 `FAILED` + `errorMessage`，两张表都处理（`ai_workflow_runs` / `ai_agent_runs`）。

- ⚠️ **只动 `RUNNING`、绝不碰 `WAITING_*`**：那是等用户确认的**合法状态**，流程是 DB 驱动 + handler
  按状态分发，重启后用户点确认照样能继续。按 `status NOT IN (终态)` 写会**误杀所有等确认的 Workflow**
  （用户没提交的方案凭空消失）。`AiRunStartupRecoveryTests` 专门锁这条，**反向验证**已证明它抓得住
- **不用 `CANCELLED`**：那是「用户主动取消」的语义，进程崩溃不是用户的选择，混用会让取消率统计失真
- **不做实例判据**：单实例下"启动时把 `RUNNING` 标中断"**就是精确解**，加 `instanceId` 是为不存在的问题写代码
- **session 锚点不用专门清**：`WorkflowRunManager.checkActiveWorkflowConflict` 早有脏绑定自愈
- **不删记录**：只改状态，run / step / trace 保留供排查
- 失败不阻断启动（与 `SeckillPreheatRunner` 同风格），下次重启再试

**面试口径**：被问「几百人同时用会怎样」——**先分类，再给数字，最后说边界**：

- **闲聊**（CHAT / TOOL）走**响应式流**，不占准入池——**实测这条路的瓶颈是 LLM 供应商速率限制，
  且 40 并发就撞上了**（40 个请求里 30 个分类调用被 rate limit 打挂、降级成闲聊）
- **起 Agent / 工作流**走准入：默认 **8 个在跑 + 32 个排队 = 40 个名额**，**第 41 个直接 503 明确拒绝**
  ——**实测名额算法精确**：小池（worker=2/queue=3）下 `running` 峰值 2、`queued` 峰值 3、
  第 6 个拿 `503 POOL_FULL`、`queueWaitMs` 爬到 5.7s
- 边界：**真正的容量上限在供应商配额，不在应用层 worker** —— 40 并发压测时应用层只用到 `8 在跑 + 2 排队`，
  40 个名额还空着 30 个

**这条口径是「玩具 demo vs 真系统」的分界线**：压测之前只能说「我设计了一个上限」；
压测之后能说「**我压过**：名额算法是对的，瓶颈在 X 不在我以为的 Y」。三个数字（8/32/40）从
「起始配置」变成了「实测过的边界」。

**准入覆盖补漏（2026-09-19 第二轮，✅ 已完成）**：实施后发现**准入只覆盖了 SSE 流式入口**，
漏掉同样跑 LLM 的**同步 HTTP 入口**。根因是当时搜的是**代码形态**（`Schedulers.boundedElastic().schedule`）
而不是**语义**（「所有跑 LLM 的入口」）——换个搜法（搜「所有创建 run 的调用点」）当时就能发现。

- **漏 1 · Agent 建议确认**（前端「继续」按钮在用，最实的一处）：`confirm()` 会同步跑完 workflow 的
  `runInitialSteps`（以 OPTIMIZE_ARTICLE 为例含两次 LLM，几十秒），却不占名额 → 开多个会话各跑出一张建议卡、
  逐个点「继续」就能同时跑 N 个。⚠️ **只加 `precheck` 是假保护**：precheck 只读计数、不加计数，
  连点确认每次都读同一个数、每次都通过，上限完全失效。
  修法：准入新增第三种执行形态 **`runAdmitted`**（占名额 + **当前线程**执行，不占池）
- **漏 2 · `AiWorkflowController` 六个非流式接口**（create×3 / approve / reject / retry）：
  单参数重载同步跑完整个 Workflow，登录用户一条 curl 就能绕过准入。**已删**（前端早已切流式，无调用方）。
  service 单参数重载保留（测试在用，也是同步路径的底层），controller 类 Javadoc 注明
  「将来要同步启动走 `runAdmitted`，不要直接调重载」
- **漏 3 · 测试漏了 `WAITING_USER_SAVE`**：行为本来就对（条件是 `eq(RUNNING)`），但覆盖与设计稿排除清单不一致，已补

**`runAdmitted` 两个刻意的不做**：**不检查池容量**——这条路不往池里投任务，池满时连确认都不让点反而更糟；
**不包装 MDC / UserContext**——执行在调用者线程，包装多余、还可能覆盖调用方已有的 trace scope。
被拒时抛 RuntimeException → `@Transactional` 回滚 → run 回到 `WAITING_WORKFLOW_CONFIRM` 可重试。

**顺带挖出：HikariCP 默认 10 vs 准入 8** —— 走准入的 Workflow 任务在 `@Transactional` 里跑 LLM，
**8 个并发 = 8 个 DB 连接被占住几十秒**，而两个 yml 都没配 HikariCP（默认 `maximumPoolSize = 10`），
只剩 2 个连接服务全局普通请求。已显式配 `maximum-pool-size: 20`。
**这是 `worker=8` 定下来时没算的账**；且它只是放大池、**不根治**（LLM 仍在事务里）。

**三处反向验证全部精确命中**：去掉占名额 → `runAdmittedOccupiesUserSlot` 精确红；加池检查 →
`runAdmittedIgnoresPoolCapacity` 精确红；建议桥去掉准入 → `confirmRejectedByAdmissionDoesNotStartWorkflow` 精确红。
（后端 35 例全绿 + 前端构建通过。）顺带踩到 Mockito 陷阱：**用 `when()` 覆盖已有 stub 时旧 answer 会先执行**
（参数是 `any()` 产生的 null），必须用 `doThrow(...).when(mock).method(...)`。

**本轮新登记（不修）**：① **长事务**——LLM 仍在 `@Transactional` 里，放大连接池只是缓解，根治需独立一刀；
② **三个 `@Async` 池用 `CallerRunsPolicy`**（记忆提取 / RAG 同步 / 会话压缩）：池满时长任务**退回调用者线程**，
触发条件苛刻（要 33 / 106 / 52 个任务同时排队），但一旦触发就是「用户在等 HTTP 响应时跑几十秒 LLM」。
**不能简单改 AbortPolicy**——这几类任务都不该丢（RAG 索引丢了文章搜不到、记忆丢了就缺一条），
要配「有界队列 + 拒绝时落延迟队列重试」（`articleRagTaskExecutor` 已有 `RagRetryQueue` 可复用）；
`memoryRecallExecutor` 的 CallerRuns 是**故意的**（注释写明「宁可慢也不丢」）。
详见设计稿 §8。

### Agent 主线（V4）

**已完成** ✅：第一刀「开发者只读 run/step 可视化」→ 五轮观测收口（**阶段二 `Plan-guided ReAct` 判定不做**——数据落在「计划与执行高度一致且路径稳定 → 优先 Workflow 化」）→ V4.5 聊天 / QA 轻量过程提示 → evaluator-optimizer 审计收口（设计稿 §5.4 原写「Workflow 节点间开环」**与代码不符**，三域早已同构：规则检查 + 单次 auto-retry + 失败停确认门）→ ⑥ 框架能力对照（**不引入 Agent 框架接管 Runtime**，硬理由除双重状态机外新增 **HITL 语义冲突**）→ Agent 可观测性 traceId 贯穿。

**观测副产物**：只读面板暴露并修复 3 个真问题——重复查询根因（observations 缺来源标注）、focus 无法定位时静默降级、一条静默失效的测试。

**✅ 第 4 个 Agent 域：钱包（WALLET_AGENT，2026-09-21）**：AI 助手能查余额（只读工具）+ 充值
（「帮我充 10 块钱」→ 确认卡 → 点确认 → 到账）。**不新造机制**——充值复用两轮前做好的
`WalletRechargeService.createCustomOrder` + `pay`，确认复用 V2.4 的受控写动作壳。
详见 `docs/agent/agent-wallet-agent-design.md`。

- **金额不经过 LLM**（本设计最重要的判断）。最初方案是「分类器提取金额 → 拼进 goal」，
  被否掉了——依据是项目一贯的判据「**LLM 判语义、后端判事实**」。金额是**事实**：
  让它过一遍 LLM，用户说「帮我充点钱」时模型可能回一个凭空编的数字，卡片就弹出用户从没说过的金额。
  改成 `WalletAmountParser` 直接解析用户原话（阿拉伯 + 中文数字），结果只有三态，
  **失败方向永远是「问用户」不是「猜」**。顺带删掉了 `AiIntent` 改动与主流程注入两处
- **⚠️ 死循环是本域最危险的失效模式**：基类循环只有 `usedSteps` 一个推进变量，
  而**确定性决策器每一轮输入完全相同**——「返回 null → 回循环顶 → 再决策」必然得到同一个提案。
  学习域没炸是因为它的 null 出口都自己推进了步数、且靠 LLM 下一轮换招，**这两条在本域都不成立**。
  三层防护：钩子对 SUGGEST_WRITE **永不返回 null**（校验失败一律 `markFailed`）+
  决策器 `stepNo > 1` 自终止（唯一能真正让循环有界的东西）+
  `isTerminalExtension` 返回 **false**（语义有悖直觉，但万一是 null 出口时能掉进 executor 抛异常而不是空转）
- **追问只能用 `FINAL_ANSWER`**：钱包域**不能有 `WAITING_USER`**——`AgentFollowUpResolver`
  只认学习计划，用户回答「10块」会走成普通分类，**永远回不到钱包域**，钱充不进去
- **幂等是 run 状态 CAS，不是订单 uk**：`confirmWrite` 的 `WAITING_WRITE_CONFIRM → CONFIRMING`
  是 DB 行锁一次性消费；而每次尝试都生成新 orderNo，撞 `uk_recharge_order_no` 那层根本不触发。
  写进设计稿了，否则评审会误以为订单唯一索引保护了 confirm 双击
- **执行器下单 + 支付两步都做**（只下单不到账，用户会看到「确认了但钱没到」）；
  **到账额度不快照进提案**——它是配置派生值，卡片刻的估算与执行时的实际可能不一致
- **`AgentWriteProposal` 加了第 9 个字段**：record 加组件 → 4 处 `new` **编译强制改**；
  但 `parseWriteAction` 那处**编译器只保证参数个数对、不保证传的是解析值**——
  专门写了一条测试 + 反向验证（改成 `null` 后精确红在「提案缺少充值金额」）
- **只读工具注册只要一行**（`buildToolCallbacks` 默认分支）——比预想的小一个数量级，
  不用改 `buildTools` 三个数组、不用加 `AiPrompt` 开关
- **前端最危险的地方其实是卡片文案**：未知 actionType 会显示成「取消任务完成状态」、
  按钮是通用的「继续」，而这张卡点下去**动的是钱**。改成「充值确认」+「确认充值 ¥10」，
  金额显式念出来（也是分类器误判时唯一的兜底）。顺带修掉 `'expired'` 状态定义了却从没人设置的问题
- **Agent / Workflow 明确不接计费（2026-09-21 决定，终态）**：`aiBillingService.reserve`
  只在普通聊天链路，`streamAgentReply` 没有计费——**这个状态不再改动**。原 V4 待办
  「推广到 Agent / Workflow」判为**重复劳动**（聊天侧已有完整的预扣 → 结算 → 差额退回，
  再接一遍收益不匹配）。连带消掉一条本来要防的隐患：「0 余额用户在 `streamAgentReply`
  之前被 402 拦住 → 因为没钱所以不能充钱」——不接计费，它就不存在
- 测试：新增 5 个类 29 例 + 既有 4 个测试类同步（注册表构造 ×4、Planner mock、工具暴露断言、prompt 断言）+ 一处反向验证

- **2026-09-29 Agent 分层任务编排（第三刀·第一版：TaskPlan + Orchestrator）**：把「一个请求 = 一个动作」
  改成「一个请求 = 一份可执行的粗计划」，步骤按类型分派给确定性执行器 / ReAct / Workflow / 客户端命令。
  **第一版只做文章域纵向切片**（点赞 / 搜索 / 导航），`ARTICLE_ANALYSIS` 待接。
  设计稿 `docs/agent/agent-task-orchestration-design.md`。
  - **为什么换路线**：前两刀是「一种组合 = 一条分支」，**三个真实反例证伪了它**——「点赞 + 跳转」整个 CTA
    且答非所问，「点赞 + 搜索」**只点赞、第二个目标静默消失**（正是第一刀要消灭的 A 类错误）。
    根因是 `AgentDecision` **结构上只能装一件事**
  - **三层模型**：`TaskPlan`（用户要哪几件事）/ `TaskStep`（每件事，带 `goalEvidence` 原文证据）/
    `StepDependency` —— **三分类**：`after` 顺序 / `requires` 数据 / `onFailure` 策略。
    合并成一个 `dependsOn` 会让实现各自解读
  - **`DISPATCHED` 是新增且必须的**：客户端步骤由前端执行，**后端不知道结果**，只能算「已发起」；
    把它当 `SUCCEEDED` 汇总就会说谎
  - **执行器直调领域服务**，不复用 `ArticleInteractionBatchService`——它的职责是"跑一整批 + 建 run/step +
    渲染文案"，而编排里每个 step 是一个动作、**不存在"一整批"**
  - **路由边界（第一版收口点）**：`FALLBACK` 拆成 `LEGACY`（**确认旧路径能完整处理**才退回）/ `CTA`
    （否则明确拒绝）。**核心约束是「不能让旧路径悄悄接管一个多目标请求」**——
    「点赞 + 优化并发布」退回后旧路径只会执行一半还声称完成，所以判据按**未覆盖目标的类型**：
    只有 `ARTICLE_ANALYSIS` 可退（第二刀已验证那个组合）
  - **Planner 不决定路由**：它只摆事实（哪些能承接、哪些不能），走哪条路由是 `TaskPlanningFacade` 的事
  - **实现踩到的坑**：加 `AgentStepActionType.TASK_STEP` 触发了三个既有 executor 的**穷尽 switch**
    （第一刀预言过）；`ArticleWriteExecutor` 的 `ALREADY_SATISFIED` 分支漏带 `output`，
    导致"已点过赞"时前端拿不到事实；**前端 `onStop` 只认第一刀的 `actionResults`、不认新的 `taskResults`**
    →「后端做了但界面没反应」；刷新后任务步骤被当成思考步骤渲染（两层同表、缺层级标记）
  - **未完成**：`ARTICLE_ANALYSIS`（父子 run，设计稿 §4.1 列了 10 条边界）；**在线评测门未跑**（改了分类器 prompt）

- **2026-09-29 Agent 多目标编排（第二刀：动作 + 分析）**：让「点赞这篇文章，再分析一下它写得怎么样」
  这类**异构目标**在同一个请求里跑完——前一个是确定性写动作（不进 LLM），后一个是 LLM ReAct 分析。
  设计稿 `docs/agent/agent-stage2-action-plus-analysis-design.md`。
  - **规划层**：新增 `AiIntent.analysisGoalEvidence` 承载分析诉求。**关键取舍：不用 `requirements`**——
    代码里已明确把它定性为「LLM 结构化输出，会幻觉」，拿它当第二目标的权威文本是**拿成本换正确性**。
    新字段走**证据**语义（同 `topicEvidence`）：只摘原文连续片段，Planner 验证它真在原文里
  - **Planner 三层校验**：包含性 → **不能是动作指令** → **证据之后不能再出现动作词**。
    后两层都是被真实失败逼出来的：分类器会把动作原话（「点赞这篇文章」）当分析诉求塞进证据字段
    （它是原文子串，包含性校验放它过去）；对反序句（「先分析再点赞」）它照样填证据，
    而 prompt 里明明写了「动作在后不适用」——**所以位置判断必须放在 Planner，不能指望 prompt**
  - **执行层**：`AgentRunPolicy(decider, allowedActions, allowWaitingUser)` + `runWithPolicy`。
    **policy 里必须带 decider**——`decide()` 的签名里既没有 run 也没有模式，只换白名单挡不住 LLM
    继续输出 `ASK_USER`；而白名单拒绝非法动作是**直接 `markFailed` 终局**、不是退回循环，整轮会废
  - **`defaultPolicy()` 委托**让四个既有域零改动（84 例回归验证）
  - **编排**：批次从「组装期同步执行」挪进 **worker 内**（权威 `submit` 成功之后）——
    放在 `precheck` 与 `submit` 之间会造出「点赞已提交、Agent 却被 429/503 拒掉且没有回复」。
    动作结果注入 goal 并作为正文首段，STOP 带 `actionResults`，异常兜底保留动作事实
  - **analysis-only 三层约束**：决策器 prompt（末尾覆盖声明）/ 白名单 / Verifier 转问分流。
    **第三层最容易漏**——Verifier 能把合法的 `FINAL_ANSWER` 判成 `ASK_USER`，
    骨架在白名单校验**之后**直接 `markWaitingUser`，绕开了白名单
  - **未完成**：独立的 `decideArticleActionWithFollowUp` 分支（当前行为等价但未成型）；
    §4.6 的批次致命异常结构化快照（`batchRunId + partialResults`）——抛致命异常时拿不到已落库的部分 `results[]`
  - **踩到的坑**：`AiConfig` 的 `enable_thinking=false` 注入对新模型失效（thinking-only 模型不接受关闭，直接 400）；
    **样例层次错误**——把 Planner 的职责（反序拒绝）写进了**分类器**评测集，分类器只管提取、位置校验在 Planner

**✅ 文章交互动作后端执行闭环（2026-09-23）**：文章详情页的点赞 / 取消点赞、收藏 / 取消收藏、关注 / 取消关注由后端批次执行，Planner 负责动作白名单与冲突校验，批次逐项保存 `results[]`，最终文案由结构化结果确定性渲染；SSE `STOP` 返回 `actionResults`，前端只刷新文章和作者权威状态，不再二次执行。新增 `ARTICLE_INTERACTION_BATCH` run/step、启动恢复限定该 step 类型，并补齐 `db/init.sql` 的 `ai_messages.agent_run_id` 列与索引。后续仍未做确认后继续执行和跨请求暂停 / 恢复。
- 验证：前端 26 个测试文件 / 113 例全绿；后端本轮聚焦 90 例全绿；前端生产构建通过。

**剩余待办**：

- **跨 run 继承铺开**（V4③ 第二刀）——讨论稿 `docs/agent/agent-v4-conclusion-inheritance-rollout-design.md` 待评审；**第二刀（学习域）仍待前置手测**；侦查补充：`generateBreakdownByLLM` 的 prompt **没有承接 `suggestion.reason()` 的位置**，最小方案不止传参、还要加 prompt 位
- `Reflection` 暂缓等证据（Workflow evaluator-optimizer 已审计收口：产物层早已闭环，方案层有意不闭环）
- 中间刀（学习域写动作 / OPTIMIZE 跨页 / B 确认壳）**挂起不单做**
- 已知待办：**首 token 8s**（V4.5 耗时测量发现，记忆召回已并行 + 3s 超时，首字仍慢）

### 工程化清单（按「面试优先」原则重排，参照 `docs/capability-audit-2026-08-06.md` 第一层）

- [x] ~~前端 XSS 防护~~（V1.5：DOMPurify 统一清洗出口）
- [x] ~~Redis 限流~~（V1.5：chat/workflow/rag 三桶，用户/游客分级）
- [x] ~~Redis 分布式锁 + workflow 幂等~~（V1.5：WorkflowActionLock + Idempotency-Key + version CAS）
- [x] ~~敏感配置外置~~（2026-09-14：占位符化 + 启动校验 + `config/application.yml` 私有覆盖）
- [x] ~~MySQL 乐观锁~~（双表 version CAS）
- [x] ~~请求追踪 traceId~~（2026-09-12：Micrometer Tracing + 4 线程池装饰器 + `MdcContext`）
- [x] ~~**Redis 缓存可靠性**~~（2026-09-15：五刀全部完成，见上方五刀路线）
- [x] ~~**登录失败次数限制 + token 黑名单**~~（2026-09-15：五刀第 4 刀。评估见 `docs/engineering-next-cut-review.md` §3.2，实施结果与对评估的三处修正见 `docs/redis/redis-capability-design.md` §5.11）
- [~] **@Valid 参数校验**（2026-09-19：**框架 + 第一批「用户/认证」已上**，其余模块待分批）
  - 关键结论：项目约定业务错误走 **HTTP 200 + code**，所以校验失败**保持 200 + 40001**，
    与原先散在 controller 里的手写校验**格式完全一致**，前端零改动（这是敢动几十个写接口的前提）
  - 顺带修掉：请求体不是合法 JSON 原先落到兜底返回 **500**（"我坏了"），现归 40001（"你传错了"）
  - **校验边界只对齐数据库列宽，不引入新的下限** —— 加下限是产品决策，不该夹在工程化改造里
- [ ] **Memory 敏感信息过滤**（放最后：技术博客的记忆天然充满"疑似敏感信息"，误杀代价 > 漏放）
- [x] ~~健康检查接口 + 接口耗时统计~~（2026-09-19：`/actuator/health` 暴露 + `[PERF-HTTP]` 耗时拦截器）

## 本地启动

后端：

```bash
cd blog-system
./mvnw spring-boot:run
# 需要 JDK 17+，本地 MySQL blog_system 库，用户名 myRoot，密码 123456
```

> **敏感配置（2026-09-14 工程化第一刀）**：本地开发默认值保留在 `application.yml` 里，克隆即可启动，不需要配任何环境变量。
> 唯一例外是 GitHub OAuth `client-secret`（真实密钥，不保留默认值）——本地要用 GitHub 登录时，把
> `blog-system/config/application.example.yml` 复制为同目录的 `config/application.yml` 并填值（该文件已被 .gitignore 忽略）。
> 启动日志会打印一行 `[敏感配置] profile=... | jwt.secret=... | ...`，据此确认本次用的是开发默认值还是注入值。
> 生产环境变量清单见 `deploy/blog-system.env.example`。设计稿：`docs/security/sensitive-config-externalization-design.md`。

前端：

```bash
cd blog-frontend
npm install
npm run dev
```

## 构建部署

```bash
cd blog-frontend
npm run build
```

## AI Development Notes

This project is developed with AI assistance（Claude, Codex）.

When modifying code:

- Always follow existing API design
- Before adding or changing frontend/backend APIs, read and follow `docs/api-convention.md`
- When backend work reveals a frontend-only behavior or requirement, such as page redirects, token storage, route guards, or UI state changes, record a clear short note in `docs/frontend-notes.md`.
- Do not break current endpoints
- Prefer extending instead of rewriting
- Keep changes minimal and incremental

## 工作区约定

- 本项目独立于 `D:\AIAgent` 下其他项目
- 不引用其他项目代码
- 项目相关上下文写在本目录的 `AGENTS.md` / `CLAUDE.md`
- 根目录 `AGENTS.md` / `CLAUDE.md` 只保存全局背景和长期方向

---

> 修改本文件后，请同步更新同目录下的另一个入口文件（`AGENTS.md` / `CLAUDE.md`），两者内容应保持一致。
