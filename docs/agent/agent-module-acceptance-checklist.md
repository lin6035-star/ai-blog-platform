# Agent 模块阶段收口验收清单（V1 → V4.x）

> 创建：2026-08-29（V2.5 更新：2026-09-01；V3.0 更新：2026-09-01；V3.1 更新：2026-09-02；V3.2 更新：2026-09-03；V3.3 更新：2026-09-05；V3.4 更新：2026-09-06；V3.5 更新：2026-09-06；V3.6 更新：2026-09-06；V3.7 更新：2026-09-07；V3.8 更新：2026-09-07；V3.9 更新：2026-09-08；V3.10 更新：2026-09-08；V3.11 更新：2026-09-09；V3.12 更新：2026-09-10；V3.13 更新：2026-09-10；V4 第一刀更新：2026-09-11；V4.5 更新：2026-09-11；**V4.x 验收矩阵：2026-09-14**）
> 范围：全站 AI Agent 模块从 V1（Agent Router + Runtime V1）到 V4.5（聊天 / QA 轻量过程提示 + 来源展示收口）的完整能力、安全边界、测试覆盖与遗留项。明细：V3.1 受控写扩展（ADD_LEARNING_TASK）/ V3.2 评测门 / V3.3 重命名任务 / V3.4 受控写跨域（文章域改标题）/ V3.5 路由元数据收口 / V3.6 写动作执行器注册表 / V3.7 文章域可见性受控写（HIDE/PUBLISH）/ V3.8 会话文章锚（anchorMode 消解）/ V3.9 QA 跨页指代接入（readable 语义 + resolver 单点决议）/ V3.10 思考展示升级（thoughtSummary + 三道闸 + 自动展开收起）/ V3.11 证据收敛门（规则闸 + 语义闸 + focus + 保守收尾 + P0 定位 bug）/ V3.12 会话结论继承（结论锚 + 两个写点 + 单点读取 + 注入）/ V3.13 Plan Preview 阶段一（AgentPlanValidator 六规则零修补 + plan_json 单列更新 + AGENT_PLAN 事件 + 域开关）/ V4 第一刀开发者只读面板（dev 端点三条 + 白名单门禁 + 观察来源标注 + 同参硬拦）/ V4.5 聊天 / QA 轻量过程提示（CHAT_STATUS 事件 + 慢操作前发 + 三出口共用 helper + 记忆召回并行）。
> 配套设计：`docs/agent/agent-runtime-v1-design.md`（V1）、`docs/agent/agent-runtime-v2-1-workflow-suggestion-design.md`（V2.1）、`docs/agent/agent-v1-acceptance-checklist.md`（V1.5）、`docs/agent/agent-v3-1-controlled-write-add-task-design.md`（V3.1）、`docs/agent/agent-eval-gate.md`（V3.2）、`docs/agent/agent-v3-4-article-write-action-design.md`（V3.4）、`docs/agent/agent-v3-5-route-registry-design.md`（V3.5）、`docs/agent/agent-v3-6-write-executor-registry-design.md`（V3.6）、`docs/agent/agent-v3-7-article-visibility-write-design.md`（V3.7）、`docs/agent/agent-v3-8-session-article-anchor-design.md`（V3.8）、`docs/agent/agent-v3-9-qa-cross-page-anchor-design.md`（V3.9）、`docs/agent/agent-v3-10-thought-display-design.md`（V3.10）、`docs/agent/agent-v3-11-evidence-verifier-design.md`（V3.11）、`docs/agent/agent-v3-12-conclusion-inheritance-design.md`（V3.12）、`docs/agent/agent-v3-13-plan-preview-design.md`（V3.13）

## 全景速览

| 阶段 | 能力 | 状态 | 核心文件 |
|---|---|---|---|
| V1 Agent Router（08-25） | 意图分类 + 规则裁判 + topicEvidence 幻觉防护 | ✅ 验收 | AgentPlannerSupport / AiIntentClassifierImpl |
| V1.5 Workflow 稳定化（08-25） | Redis 锁 + version CAS + Idempotency-Key 三防线 | ✅ 验收 | WorkflowActionLock / WorkflowActionIdempotency |
| V1 Agent Runtime（08-26） | 有界只读决策循环（maxSteps=5） | ✅ 验收 | LearningAgentRuntime / LlmAgentStepDecider |
| V2.1 建议桥（08-27~28） | SUGGEST_WORKFLOW → 确认卡 → 复用 Workflow Runtime | ✅ 验收 | AgentRunSuggestionService |
| V2.2 inspection（08-28） | run 详情 / steps / 会话历史只读 API | ✅ 验收 | AgentRunInspectionService |
| V2.3 思考面板（08-28） | AGENT_STEP 实时事件 + 折叠面板 + 刷新恢复 | ✅ 验收 | AgentStepEmitter / AgentStepLabelSupport |
| V2.4 受控写动作（08-29） | SUGGEST_WRITE 提案 → 确认 → updateTaskDone + 双表乐观锁 | ✅ 验收 | AgentWriteActionService / LearningStages.version |
| V2.5 文章侧建议桥（08-31~09-01） | 第二个单 Agent + 骨架抽取 + 公共 SSE 管道 + 终局失败 | ✅ 验收 | AbstractAgentRuntime / ArticleAgentRuntime / ArticleAgentActionExecutor / streamAgentReply |
| V3.0 通用思考模式（09-01） | 普通聊天接入 Agent Runtime：needsThinking 正交判定 + 通用域三件套 + Planner 单点归一化 | ✅ 验收 | GeneralAgentRuntime / GeneralAgentStepDecider / decideGeneralAgent / AiIntent.needsThinking |
| V3.1 受控写扩展（09-02） | 第 2 个写动作 ADD_LEARNING_TASK：追加任务（用户点名任务名 → 受控写，AI 拆解 → Workflow 分界写死） | ✅ 验收 | AgentWriteActionService（resolvePlanAndStage 拆分）/ AgentWriteProposal.TYPE_* |
| V3.2 评测门（09-03） | 固定样例集（3 JSON）+ 一键 runner（-Dgroups=eval）+ 回归门槛（离线 34 / 在线 42）；分类器分界 8 例 + 阶段定位 14 例 JSON 化 | ✅ 验收 | classifier-evaluation-cases.json / learning-plan-match-evaluation-cases.json / LearningPlanMatchEvalTests |
| V3.3 受控写扩展 2（09-05） | 第 3 个写动作 UPDATE_LEARNING_TASK：重命名已有任务（taskTitle=旧名 + newTitle=新名，用户原话）；归一化四分 + 同名改名双层拒绝 + 分类器改名分界（在线门 10/10） | ✅ 验收 | AgentWriteProposal.TYPE_UPDATE_LEARNING_TASK / LearningAgentRuntime 归一 / AgentWriteActionService.executeRenameLearningTask / LearningPlansService.renameTask |
| V3.4 受控写跨域（09-06） | **第 4 个写动作 UPDATE_ARTICLE_TITLE（文章域第 1 刀）**：改自己文章标题；范围锁详情页（articleId 只认页面上下文）；并发防护三明治锚定（proposal.articleTitle=锚 → confirm stale 校验 → service 旧值条件更新，Articles 无 @Version）；分类器「完整新标题 → ARTICLE_AGENT」分界（在线门 12/12）；评测样例带 pageContext 首例 | ✅ 验收 | AgentWriteProposal.TYPE_UPDATE_ARTICLE_TITLE / ArticleAgentRuntime.suggestWriteArticleTitle / AgentWriteActionService.executeUpdateArticleTitle / ArticlesServiceImpl.updateArticleTitle |
| V3.5 路由元数据收口（09-06） | **intent → runtime + fallbackMessage 单点登记**：消 AiMessageServiceImpl 双份分发 if（主 + fallback）+ 双份兜底文案 6 处 + Planner 判定名单双写；注册表只登记不决策（未登记 intent → warn + 显式兜底，静默雷变日志雷）；触发条件「域 ≥3」兑现（3 个 AGENT runtime） | ✅ 验收 | AgentRuntimeRouteRegistry / AiMessageServiceImpl.dispatchAgentRuntime / Planner predicate 查表 |
| V3.6 写动作执行器注册表（09-06，A 刀） | **AgentWriteActionService 壳化 + List&lt;AgentWriteActionExecutor&gt; 分发**：域执行逻辑迁出（Learning 3 动作 / Article 1 动作各自内聚，定位器私有化）；markExecuted 两法合一（actionType/executedAt 壳统一补）；纯重构零行为变化（20 断言平移全绿、proposal/前端/夹具零迁移）；新写动作即插即用 | ✅ 验收 | AgentWriteActionExecutor / LearningAgentWriteActionExecutor / ArticleAgentWriteActionExecutor / AgentWriteActionService |
| V3.7 文章域可见性受控写（09-07） | **HIDE_ARTICLE / PUBLISH_ARTICLE 批次**：前置状态从动作推导（HIDE 前置 PUBLISHED / PUBLISH 前置 HIDDEN）proposal 零新字段 + 执行原子条件更新收口（updateArticleVisibility，Codex TOCTOU 修正：校验通过 ≠ 原子执行）；DRAFT 拒绝（编辑器人工闸保留）；分类器「状态操作 vs 内容修改」分界（在线门 14/14）；V3.6 注册表即插即用首个验证 | ✅ 验收 | AgentWriteProposal.TYPE_HIDE/PUBLISH_ARTICLE / ArticleAgentWriteActionExecutor.executeVisibilityChange / ArticlesService.updateArticleVisibility / ArticleAgentRuntime.suggestWriteArticle |
| V3.8 会话文章锚（09-07） | **跨页/跨轮文章指代**：ai_sessions 单点锚（AGENT_RUN/QA/ACTION 三写点 + 删光消息清锚）+ anchorMode 指代消解（CURRENT_PAGE/SESSION_LAST，模型不输出 ID）+ goal 双候选注入 + Planner 只放行不选目标 + 指代不清 ASK_USER；分类器 LLM articleId 退出定位（防幻觉绕过） | ✅ 验收 | ArticleSessionAnchorService / ArticleAgentRuntime（effectiveGoal/resolveStepTarget/prepareStepDecision）/ LlmAgentStepDecider.anchorMode / AgentPlannerSupport.decideArticleAgent |
| V3.9 QA 跨页指代（09-08） | **会话锚铺到 QA 管道**：锚读语义按域拆（owned 给写动作 / readable 给 QA——他人公开文章也能续问）+ 读权限单点 loadReadable（公开 + 本人全状态）+ ArticleQaTargetResolver 单点决议（候选先加载后成候选 + 措辞信号矩阵 → 目标/追问态/说明态）+ QA 恒放行文章检索（Planner 零预判）+ intent.articleId 全链路退出 | ✅ 验收 | ArticleSessionAnchorService.resolveReadable/loadReadable / ArticleQaTargetResolver（ai/qa）/ AgentPlannerSupport QA 段恒放行 / AiMessageServiceImpl 三路径单次决议 |
| V3.10 思考展示升级（09-08） | **受控 ReAct 思考摘要展示**：决策器每步输出 thought（LLM JSON 顶层，贴近 ReAct 术语）→ 后端 AgentThoughtSanitizer 分级闸门（强信号单命中即弃 / 业务弱词与机制动词组合才弃，零修补）→ thoughtSummary（仅展示，非决策依据，不进决策上下文、不存原文）；AgentStepEmitter 5 参 + ai_agent_steps.thought_summary 可空列（V3.8/3.9 后首次 ALTER）+ 事件/VO/前端全链路；行文本 thoughtSummary?.trim() || message（RUNNING/SUCCESS 同句稳定，FAILED 不覆盖失败文案）；思考期间自动展开、正文输出自动收起、手动可再展开；术语定位（A 章节）：三域 Runtime = 受限 ReAct | Workflow = Plan-and-Execute | 聊天 @Tool = function calling | 三范式面试讲法 | 三域真实 LLM smoke（runRealLlm 门 3 例） | AgentThoughtSanitizerTests 21 例 | 全量回归暂缓（老大：边推进边修） | 待老大前后端手测 | 本地 MySQL 已 ALTER | ✅ 验收（实施完成，待手测） | AgentThoughtSanitizer（分级闸门）/ LlmAgentStepDecider（parseDecision + buildUserPrompt 单点）/ AgentStepDecision.thoughtSummary / AbstractAgentRuntime + 学习/文章域 emit 全扫 / AiAssistant.vue（渲染 + 自动展开收起） / LlmAgentStepDeciderOnlineSmokeTests |
| V3.11 证据收敛门（09-09） | **FINAL_ANSWER 前独立判定草案 vs 证据（文章域试点）**：规则闸（R1 目标已决议未读 → 零 LLM 拦截 / R2 零观察放行）+ 语义闸（独立角色独立调用：越证据/矛盾 → NEED_MORE、缺用户信息 → ASK_USER、够 → SUFFICIENT，只评不选）；NEED_MORE 落 FAILED step + missingEvidence 指引 → 补查走 QUERY_ARTICLE focus（净化词 + 分节定位，真增量防空转）；ASK_USER 转问单 step；到顶保守收尾（S1/S1b 不 summarize）；fail-open；P0 修 V3.8 遗留 QUERY_ARTICLE 定位 bug（决议目标 > pageContext）；决策器观察防注入 | ✅ 验收（自动化绿 + **手测 4/4 通过 2026-09-10**；暴露并修复三处 bug） | ArticleEvidenceVerifier（规则闸+语义闸）/ Verdict / AbstractAgentRuntime（收敛门钩子 + successfulActions + hasUnresolvedVerifierRejection + extractFinalAnswerText）/ ArticleAgentRuntime（钩子接线 + 保守收尾）/ ArticleAgentActionExecutorImpl（P0 定位 + focus）/ ArticleAgentStepDecider（focus 规则 + 防注入）/ ArticleEvidenceVerifierTests + ArticleAgentRuntimeVerifierTests + ArticleEvidenceVerifierOnlineTests（13，eval 门） |
| V3.12 会话结论继承（09-10） | **同 session「上轮 Agent 结论 → OPTIMIZE Workflow 优化方向」交接**（V4③ 试点窄刀）：会话结论锚（ai_sessions `last_conclusion_*` 五列，雪花 ID 条件更新 CAS 防旧 run 晚完成覆盖新结论）+ **两个写点**（A：FINAL_ANSWER 终态钩子，晚于 run 落库防孤儿锚，含 QUERY_ARTICLE 证据边界；B：建议卡 confirm 成功时刻——未确认的建议结构性进不了锚，顺带修 confirm 路径 suggestion.reason 丢失）+ **读取单点** ArticleOptimizeWorkflowHandler.create()（分类器直达 / confirm / API 直建三入口全覆盖）快照固化 context.handoff + **注入** GENERATE_OPTIMIZATION_PLAN（防注入三段式模板 + 500 字截断——二阶注入链：文章正文→观察→finalAnswer→下一条 prompt）；不判定用户是否引用（机会性弱参考，词表假阴性会静默打穿主路径；误注入可审计可拒绝）；不设时间窗口（updated_at 仅审计）；读权限对齐可读语义（与 09-10 读权限修复同源）；删光消息同步清五列 | ✅ 验收（实施完成 + 手测通过） | ArticleSessionAnchorService（markConclusion/resolveConclusion/isReadable）/ AbstractAgentRuntime.onAnswerConcluded / ArticleAgentRuntime / AgentRunSuggestionService.markConclusionIfOptimize / ArticleOptimizeWorkflowHandler（resolveHandoff + buildPlanUserPrompt）/ AiSessions 五列 / ArticleSessionAnchorServiceTests +10 等新增 22 例 |
| V4.5 聊天 / QA 轻量过程提示（09-11） | **定位收窄**（原拟「全局思考展示」；来源卡片本已存在，本刀只补「首字前的语义状态」）：新增瞬态 `CHAT_STATUS(4001)` 事件，**必须在慢操作之前发**（否则前端收到时检索早已结束——靠 `Flux.defer` 把 QA 决议 / prompt 拼装 / 站内检索惰性化）；**三条出口共用同一 helper**（登录主路径 / Workflow 回退 / 游客）；文案按**意图级**给（`CURRENT_ARTICLE` 只能说「正在确认文章范围」——事件发出时目标尚未决议，不能承诺已读到）；前端防抖 250ms + 最小显示 300ms + **五处清理**；普通闲聊不发（不打搅打字动效）；状态不放进 `AiMessage`（消息会写 localStorage，会残留假的「正在检索」）。**耗时测量（4.0）暴露并顺手修了性能问题**：`buildPrompt` 3.6s = 两次记忆向量召回**串行** → 并行 + 3s 超时 fail-open（切线程前提已核实：召回链路不碰 `UserContext`），**实测 3655→685ms（−81%）**；首 token ≈11s 记为待办（prompt 长度治理）。**教训**：`mvnw -q compile` 增量编译 + IDE 脏 class 会给**假阳性**，验证编译必须 `clean` | ✅ 验收（实施完成 + 手测 1/2/3/5 通过，2026-09-11；4/6 构造性跳过，7 待测） | AiChatEventType.CHAT_STATUS / AiMessageServiceImpl.buildChatStatusFlux + 三个 streamXxxFlow（惰性化）/ MemoryExtractionAsyncConfig.memoryRecallExecutor / AiPromptServiceImpl（召回并行）/ 前端 api/ai.ts + AiAssistant.vue / 设计稿 `agent-v4-5-global-thinking-display-design.md` §十二 实施记录 |
| V4 第一刀（09-11） | **开发者只读 run/step 可视化 + 首次观测产出**（V4 阶段 2 入口）：新开 `/api/ai/dev/agent-runs` 三条只读端点 + `blog.ai.inspection` 白名单门禁（沿用 `Rag.Rebuild` 范式，**空白名单 fail-closed**）/ **不放宽用户侧接口**（`AgentStepVO` 不返回 `inputJson` 是刻意的安全设计，两条通道分开建模）/ 前端 `/dev/agent-runs` 列表 + 详情（run 信息 / plan / steps / 原始 contextJson 默认折叠 + 「观察重复」前端按 outputJson 比对标记）/ **上线首次观测即产出**：4 次同参同果 `QUERY_MEMORY` → 排除 clipContext 裁剪假设（3446 < 6000）→ 硬拦后仍重复两次 → 真根因 **observations 缺来源标注**（模型不知道自己做过什么，不是"记忆不够"）→ 修法 = 观察加来源标注（治本，只带 focus/question/keyword，内部 ID 不进）+ 同参硬拦（兜底，只拦原样重复、换词放行） | ✅ 验收（实施完成 + 手测通过，2026-09-11） | AgentRunDevController（新）/ AgentRunInspectionService.listRawSteps+getDevDetail / AgentStepRawVO + AgentRunDevDetailVO（新）/ BlogAiProperties.Inspection / AbstractAgentRuntime.tagObservation + rejectDuplicatedQuery / AgentStepLabelSupport（跳过文案与刷新恢复同源）/ 前端 api/ai.ts + router/index.ts + DevAgentRunsView.vue + DevAgentRunDetailView.vue / AgentRunDevControllerTests(5) + ArticleAgentRuntimeTests(+3) |
| V3.13 Plan Preview 阶段一（09-10） | **文章域首轮拆 2-4 子目标 + 展示 + 观测**（V4① 观察性试点）：**能力地图补齐非痛点修复**（无实测证据表明缺计划导致跑偏）/ 不是 Plan-and-Execute 也不是 Plan+ReAct 只是基线观测 / 两形态分辨（预定义代码型已有 = Workflow，LLM 动态规划型未做）/ 四档选型（**目标不清 → ASK_USER 澄清**，不是纯 ReAct 兜底）/ 决策器自决触发（简单请求零计划是软目标）/ `AgentPlanValidator` 六规则零修补整条丢弃 / **单列更新**落 `plan_json`（不复用 updateById）/ `AGENT_PLAN(3002)` 事件**在首个 STEP 前**发 + 前端**占位消息索引**归并（事件早于 STOP 无 agentRunId）/ 刷新经 `getAgentRunDetail`（不扩展 `/steps`）/ 隔离分层（永不改四项 + 阶段一专属一项）/ `supportsPlanPreview()` 域开关仅文章域 true / 观测指标 FOLLOWED-SKIPPED-PIVOTED 人工标注 / 登记阶段二 + Reflection | ✅ 验收（实施完成 + 手测七项全过，2026-09-10；手测修重复查询 / 记无指代歧义边界 / 记 verifier 样例波动） | AgentPlanValidator（新）/ AbstractAgentRuntime.collectPlanIfApplicable / ArticleAgentRuntime.supportsPlanPreview / LlmAgentStepDecider.parsePlan + planPromptSection / ArticleAgentStepDecider / AgentStepEmitter.emitPlan / AiMessageServiceImpl（lambda → 匿名类 emitter）/ AiChatEventType.AGENT_PLAN / AiAgentRun.planJson + AgentRunInspectionService / AgentRunDetailVO / 前端 ai.ts + AiAssistant.vue（计划块 + 归并 + 双拉恢复）|

## V4.x 验收矩阵（2026-09-14）

> **用途**：收口 V4 主干 + 2026-09-13/14 实测驱动的四刀。
> **用法**：逐行手测打勾。「依据」列写明**怎么验证**（日志关键字 / 界面表现 / 自动化）。
> **状态图例**：✅ 已验（附日期/证据）｜⬜ 待测｜⚠️ 有已知边界

### A. 学习域多轮对话（会话锚链路）

| # | 场景（你输入） | 期望 | 依据 | 状态 |
|---|---|---|---|---|
| A1 | 「帮我分析我的 X 学习计划，看看有没有问题」 | 判 `LEARNING_AGENT`；**写锚** | 日志：`intent=LEARNING_AGENT` + `会话学习计划锚更新` | ✅ 09-14 12:33 实测 |
| A2 | 接着「按你说的建议修改一下计划」 | 判 `LEARNING_PROGRESS`；`risk=MEDIUM` 被**锚放行**；**直接起 workflow**（不反问"哪个计划"） | 日志：`intent=LEARNING_PROGRESS` + 紧随 `step_enter` | ✅ 09-14 12:34 实测 |
| A3 | **同一句话换个页面**（首页 / 文章页 / 计划页） | 都判 `LEARNING_AGENT`——**不依赖页面上下文** | 日志 intent 一致 | ⬜ 待测（原先在计划页才判对） |
| A4 | **没点名计划**：「帮我看看我的计划怎么样」 | Agent **用锚定位**，不列候选反问 | 界面：直接给分析而非候选列表 | ⬜ **重点待测**——当前是**软约束**（靠决策器填 `planRef`），模型不配合就要改硬约束 |
| A5 | 无锚且多计划（新会话直接说「改一下我的计划」） | 列候选 / 追问，**不猜** | 界面：候选列表 | ⬜ 待测 |
| A6 | **追问后回答**（Agent 问"是哪一份" → 你答「Java后端学习路线」） | **接着原任务继续**（继续分析），**不重新判意图、不起新建计划** | 日志 `追问续答命中` + 无 `LEARNING_PLAN` 工作流 | ✅ 单测 6/6（`AgentFollowUpResolverTests`）｜⬜ 手测待做 |

### B. 意图边界

| # | 场景 | 期望 | 依据 | 状态 |
|---|---|---|---|---|
| B1 | 「分析 / 评价计划」 vs 「查有哪些计划 / 进度到哪了」 | 前者 `LEARNING_AGENT`；后者 `LEARNING_PLAN_QUERY` | 评测门 35 例 | ✅ 全过 |
| B2 | 「可以，那就按照你的建议优化一下我的计划」 | `LEARNING_PROGRESS`——**不被"建议"二字吸去 AGENT** | 日志 intent + reason | ✅ 09-14 12:34 实测（reason：「执行调整动作」） |
| B3 | **已完成**的计划点名（如「分析我的 java 学习计划」，而《Java后端学习路线规划》是 COMPLETED） | **参与匹配**；与另一含 java 的计划**并列 → 追问**，不误写锚 | 日志 `会话学习计划锚更新` 不再出现（或指向正确计划） | ✅ 匹配评测 19/19（含新增 2 例；**有意变更 1 例既有样例**，见 §五） |

### C. 生成可靠性（JSON Mode）

| # | 场景 | 期望 | 依据 | 状态 |
|---|---|---|---|---|
| C1 | 走一次「调整计划」（长 prompt + 长输出） | **不再出现**「模型返回格式错误」；首字明显变快 | 日志无 `格式错误`；`llm_start` 带 `jsonMode=true` | ⬜ 待测 |
| C2 | （可选，构造）让 JSON 解析失败 | 日志有 `[DIAG-PLAN] 计划 JSON 解析失败` + 原文头尾各 200 字符 | 日志 | ⬜ 待测 |

### D. 模型配置（判断链 / 生成链分离）

| # | 场景 | 期望 | 依据 | 状态 |
|---|---|---|---|---|
| D1 | 启动时配 `AI_JUDGE_MODEL` | 启动日志出现 `判断链模型已配置: judgeModel=...` | 启动日志 | ⬜ 待测 |
| D2 | **生成链换成弱模型**（如 flash） | **判断链不受影响**——「分析计划」仍判 `LEARNING_AGENT` | 日志 intent | ⬜ 待测（本配置的核心价值） |

### E. Agent Runtime

| # | 场景 | 期望 | 依据 | 状态 |
|---|---|---|---|---|
| E1 | 会话内有锚时走 Agent | goal 里带 `【会话最近讨论的学习计划】《X》(ID n)` | 日志 `Agent Run 创建: ... goal=` / `ai_agent_runs.goal` | ⬜ 待测 |
| E2 | 会话内无锚 | goal 里是 `【会话最近讨论的学习计划】无` | 同上 | ⬜ 待测 |

### F. 过程展示（前端）

| # | 场景 | 期望 | 依据 | 状态 |
|---|---|---|---|---|
| F1 | 学习域多轮：理解 → 定位 → 工作流步骤 | 一条 `ai-process` 折叠链；**完成后保留可展开** | 界面 | ⬜ 待测 |
| F2 | 文章域问文章问题 | 过程状态进**同一条链**（不再是三套并行渲染） | 界面 | ⬜ 待测 |
| F3 | 多条消息各自开合 | 展开态 **per-message**（不联动） | 界面 | ⬜ 待测（顺带修的既有缺陷） |

---

## 一、安全模型（贯穿全部版本）

**核心原则：LLM 给建议，后端做裁判。**

三级能力演进（每级都保留确认门控）：

```text
V1   只读 Loop     Agent 只能查询（计划/记忆/RAG），白名单 5 动作
V2.1 建议         Agent 可建议启动 Workflow，确认后才执行（SUGGEST_WORKFLOW）
V2.4 受控写        Agent 可提案写动作（勾选任务），确认后才写入（SUGGEST_WRITE）
V3.1 写动作扩展    Agent 可提案追加任务（用户点名的新任务），确认后才写入；分界写死——
                   用户给任务名 = 受控写，AI 生成任务 = LEARNING_ASSIST Workflow
     禁止          Agent 永远不能直接：写库 / 启动 Workflow / 发布 / 删除 / 重命名 / 重排
```

统一安全机制：

- **确认门控**：WAITING_WORKFLOW_CONFIRM / WAITING_WRITE_CONFIRM 终态，前端确认卡，确认后才执行
- **幂等**：Idempotency-Key（24h TTL + fingerprint 校验）防同请求重试；状态 CAS 防异 key 并发
- **并发**：Redis 锁（SETNX + 120s TTL + Lua 校验释放 + fail-open）+ DB 状态 CAS（WAITING → CONFIRMING → COMPLETED，失败回滚可重试）
- **裁判**：零观察拒绝（observations.size() >= 1）；workflowType allowlist；写动作标题匹配（不信任 LLM 索引，找不到/重名 → 拒绝或候选，不猜）
- **过期**：新 run 创建时同 session 旧 WAITING 提案/建议置 CANCELLED
- **留痕**：before/after 快照（tasks JSON）+ 解析结果 + 执行时间记入 context_json，可解释可回滚
- **乐观锁**：learning_stages / learning_plans 双表 version CAS（tasks JSON 读改写防丢失更新）
- **终局失败**（V2.5）：执行失败分两类——临时故障记 FAILED step 循环继续；终局失败（文章归属/存在校验）记 FAILED step 后**直接结束 run**（COMPLETED + 后端生成友好文案，不走 LLM 追加步骤），避免"失败后 LLM 复述失败"的无效循环
- **公共文案中性化**（V2.5）：AgentStepLabelSupport 终态文案去领域化（FINAL_ANSWER → "已生成最终回答"），实时事件与历史补拉双域共用，刷新前后不串味
- **正交判定**（V3.0）：needsThinking 与 intent 正交——intent 管"是什么问题/哪个域"，needsThinking 管"要不要进通用 Runtime"；归一化收在 Planner 单点（AiMessageServiceImpl 只消费 AgentDecision）；后端规则层强制（非 GENERAL_CHAT 不消费该字段、游客恒 false、LLM 缺失值按 false）
- **延迟双闸门**（V3.0）：maxSteps 上限（通用域 3）+ Decider prompt 下限（"能不查就不查、能直接答直接答"）；needsThinking 默认 false 防 LLM 漂移成"普遍思考"；needsThinkingReason 仅抽样排查，不进 UI、不影响路由
- **游客硬边界前置**（V3.0）：分类器已判明确 Workflow 诉求（含 Planner 双签未中降级 CTA 的情况）→ 直接登录提示，不再"信息不足"追问；学习计划正则补"创建/制定/给我…学习计划"变体（登录用户同样受益）

## 二、各阶段明细

### V1 Agent Router（2026-08-25）

- 意图分类（11 类）+ 置信度 + AgentDecision 决策链路
- **topic 幻觉防护最终版**：LLM 逐字摘录 topicEvidence → 后端 validateTopicEvidence（原话 substring 校验 + 泛词表过滤）→ 失败当无主题追问
- 学习规划入口路由（制定/调整/攻坚/查询四路分流，LLM 主判 + 规则兜底 + 查询排除）
- 测试：AiMessageIntentRoutingTests、AiIntentClassifierPromptTests、CreateArticleWorkflowHandlerTests 等

### V1.5 Workflow 稳定化（2026-08-25）

- Redis 分布式锁（SETNX + TTL + Lua 释放 + fail-open）；四操作统一 claimWorkflowAction + version CAS
- Idempotency-Key 幂等（fingerprint + 24h TTL + 409 冲突 + 故障降级）；前端 sessionStorage Key 生命周期
- 测试：WorkflowActionLockTests、AiWorkflowRunVersionTests、AiWorkflowRunServiceTests

### V1 Agent Runtime（2026-08-26）

- 有界只读 Loop：decide（LLM JSON）→ 白名单校验 → 执行 → observation 落 step；maxSteps=5 + summarize 兜底
- 数据模型：ai_agent_runs + ai_agent_steps；clipContext 单条 1500 / 累计 6000
- 只读动作：QUERY_LEARNING_DASHBOARD（多计划语义）/ QUERY_MEMORY / SEARCH_RAG；终态 ASK_USER / FINAL_ANSWER
- 入口：LEARNING_AGENT 意图 + Planner 双签 + 管道隔离（isLearningDomain 排除）；游客拦截 + fallback 路径接入
- 测试：LearningAgentRuntimeTests、LearningAgentActionExecutorTests、LlmAgentStepDeciderTests

### V2.1 建议桥（2026-08-27~28）

- SUGGEST_WORKFLOW（终态）：零观察裁判、workflowType allowlist（仅学习类）、WAITING_WORKFLOW_CONFIRM 确认态
- confirm：幂等 → 锁 → 归属/状态/冲突校验 → 状态 CAS → 转 DTO 复用 createLearningPlan/Progress/AssistWorkflow（同步执行初始链路）→ COMPLETED + workflowRunId 落 context + 同步 ai_messages.workflow_run_id
- cancel / getSuggestion（快照恢复）；新 run 过期清理；前端确认卡 + 刷新恢复
- 测试：AgentRunSuggestionServiceTests

### V2.2 inspection（2026-08-28）

- GET /{id} 详情（安全摘要，WAITING 时带 pendingWorkflowSuggestion）
- GET /{id}/steps（stepNo 升序，observation 摘要，不吐 inputJson）
- GET ?sessionId=&page=&pageSize=（分页，size 兼容别名）
- 边界：只读、只查本人（归属校验）、不影响 confirm/cancel
- 测试：AgentRunInspectionServiceTests

### V2.3 思考面板（2026-08-28）

- AgentStepEmitter：每步执行前后推事件（RUNNING → SUCCESS/FAILED）+ 终态
- AGENT_STEP(3001) 事件；streamLearningAgent 改 Flux.create + boundedElastic 实时推流（与 Workflow 创建同模式）
- 前端折叠面板「🤔 思考过程（N 步）」：状态图标 + 中文文案；刷新恢复（agentRunId 无条件绑定 + steps API 补拉）
- **文案一致性**：AgentStepLabelSupport 公共文案（实时事件与历史补拉同源，刷新前后不串味）
- 测试：LearningAgentRuntimeTests（emitter 事件序列）

### V2.4 受控写动作（2026-08-29）

- SUGGEST_WRITE 提案：{planId(后端定位), stageTitle, taskTitle, done}——LLM 只给标题，索引后端匹配
- 后端裁判：计划（点名唯一/单 ACTIVE；多 ACTIVE 拒绝）→ 阶段标题 → 任务标题（唯一执行/重名或找不到拒绝）
- 确认：WAITING_WRITE_CONFIRM → CONFIRMING → COMPLETED（锁 + CAS）；执行走现有 updateTaskDone
- 快照：before/after tasks JSON + 解析结果 + executedAt 记入 context_json
- **乐观锁底座**：learning_stages.version + learning_plans.version（@Version + 插件）；updateTaskDone/appendTasks CAS 失败报错，refreshPlanStatus 幂等忽略
- 测试：AgentWriteActionServiceTests

### V3.0 通用思考模式（2026-09-01）

- **入口契约**：AiIntent 加 needsThinking(boolean) + needsThinkingReason(string|null)；prompt 语义判定（非词表：是否指向前文/依赖记忆/存在信息缺口），默认 false，只对 GENERAL_CHAT 生效；示例更新（含"需思考"样例）
- **通用域三件套**：GeneralAgentRuntime（白名单 {QUERY_MEMORY, SEARCH_RAG, ASK_USER, FINAL_ANSWER}，无 SUGGEST_WORKFLOW / SUGGEST_WRITE，maxSteps=3——基类新增 maxSteps() 钩子，学习/文章域默认 5 不变）/ GeneralAgentActionExecutorImpl（QUERY_MEMORY 为主——V3 相对现状普通聊天的核心增量；SEARCH_RAG 只补证据）/ GeneralAgentStepDecider（prompt 写死"能不查就不查、能直接答直接答"、"SEARCH_RAG 不是默认第一步"）
- **Planner 单点归一化**：decideGeneralAgent（CTA 保持 → 建议不一致降 CHAT → 游客恒 false → needsThinking=true 进通用域）；isGeneralAgentCandidate 只认 GENERAL_CHAT + Boolean.TRUE；主分发 + fallback 双路径按 intent 分支（GENERAL_CHAT → generalAgentRuntime），直接 return 天然跳过普通聊天自动 RAG（无双份检索）
- **行为语义**（端到端验收确认）：状态依赖型聊天（"你觉得我现在这套方案还有什么问题""结合我的情况给个建议"）→ 通用 Runtime 思考（查记忆→补证据→回答）；纯概念问答/寒暄/明确指令 → 直达不变；学习/文章专线不受 needsThinking 影响
- **边界修复**：思考中刷新丢消息（assistant 落库在 loop 后，前端只拉一次历史错过窗口）→ 前端占位"思考中…" + 3s 轮询补拉（最多 6 次，用户发新消息自动放弃）；游客明确诉求被 CTA 追问 → isExplicitGuestBusinessIntent 前置登录提示 + 学习计划正则补漏
- 测试：GeneralAgentRuntimeTests(10) / GeneralAgentActionExecutorTests(8) / GeneralAgentStepDeciderTests(6) / AgentPlannerSupportTests(+7) / AiIntentClassifierPromptTests(+1)

### V3.1 受控写扩展（2026-09-02）

- **目标**：放第 2 个写动作 ADD_LEARNING_TASK——向学习计划指定阶段追加单个任务，复用 V2.4 安全链路（提案 → 确认卡 → confirm → CAS + 快照）。不批量 / 重命名 / 删除 / 重排；文章域写动作不动
- **双层 actionType 语义写死**：外层 SUGGEST_WRITE（Agent Step 动作）+ 内层 input.actionType（写动作类型 UPDATE_TASK_DONE / ADD_LEARNING_TASK，缺省回落 UPDATE_TASK_DONE 保旧模型）；AgentWriteProposal 公共常量 TYPE_*（双份字面量漂移防护）
- **目标解析拆分**（Codex 评审修正）：原三段 resolveTarget 拆 resolvePlanAndStage（计划 → 阶段，ADD/UPDATE 共用）+ resolveTaskIndex（仅 UPDATE：任务必须已存在且唯一）；ADD 的 taskTitle 是新任务不做存在校验
- **重复预检**：ADD 分支先读 stage 比对同名 → 明确拒绝「已在阶段中」，不依赖 appendTasks 静默去重 return（void 无感知，避免"成功但没变化"）
- **执行**：confirm 调 appendTasks（归属校验 + 去重 + @Version CAS 全内置，LearningAssistWorkflow 亦复用）；context_json 留 actionType + before/after 快照（taskIndex 仅 UPDATE 写入）
- **分类器分界**（真实 LLM 验证中发现并修复）：prompt 原把「增加辅助任务」归 LEARNING_ASSIST → 模型把「加一个'缓存雪崩防护'任务」误判拆解 workflow；修正为「用户点名任务名 = LEARNING_AGENT 受控写 / 未给任务名要 AI 生成 = LEARNING_ASSIST」，规则 + 示例双处写死
- **Decider prompt**：SUGGEST_WRITE 规则段扩 ADD 语义（taskTitle 来自用户原话不在观察中；与 UPDATE 的"taskTitle 必须来自观察"对照）；repair prompt 白名单补 SUGGEST_WRITE（原缺失：提案 JSON 解析失败重试无法再输出）
- **前端确认卡**：按 writeAction.actionType 渲染（原按 done 三目——ADD 的 done 恒 false 会错显「取消任务完成状态」）；ADD 显示 计划/阶段/新任务名
- 测试：AgentWriteActionServiceTests(+3，原 8 → 11) / LearningAgentRuntimeTests(+1，15 → 16) / AgentPlannerSupportTests(+1，41 → 42) / AiIntentClassifierIntegrationTests(+1 真实 LLM)

### V3.2 评测门（2026-09-03）

- **动机**：受控写第 3 个动作前（CLAUDE.md 升格点）立边界锁定手段；V12.1 四连修全靠人肉 E2E 发现，评测门让「改分类器 prompt / 改匹配逻辑 / 新写动作」后一键回归、边界漂移当场可见。样例素材全部来自既有测试积累，零新增样例、零语义改动
- **评测网形态**：3 个 JSON 固定样例集 + `-Dgroups=eval` 一键 runner（surefire 3.5.6 原生 JUnit5 tag，pom 零改动）。离线门（默认，不花钱）= planner 17 + 阶段定位 14 + append 3 = 34；在线门（-DrunRealLlm=true）= + 分类器 8 = 42
- **分类器 JSON 化**：8 个硬编码 @Test → `classifier-evaluation-cases.json` + @ParameterizedTest（逐例红绿可定位 + `-DevalCase=子串` 单例复跑省钱）；字段全可空非 null 才断言，显式支持 false 断言（expectedNeedsThinking=false 不可靠 null 跳过）；category = boundary/regression/thinking/baseline
- **阶段定位 JSON 化**：LearningPlanMatchServiceTests（17 例）→ `learning-plan-match-evaluation-cases.json`（14 例 fixture 结构化：plans/stages/tasks 三级 + planRef 局部引用 + STAGE_MATCH 恰一 plan 校验）+ 新 LearningPlanMatchEvalTests（参数化 14 + appendTasks 3 保持普通 Java——写操作 + 多步动作序列不适合声明式 JSON）；TEST_USER 107 避开旧类 106；两段式共存验证等价后删旧类
- **tag 接线**：AiIntentClassifierIntegrationTests / LearningPlanMatchEvalTests / AgentPlannerEvaluationTests 加 @Tag("eval")（planner 仅加 tag 逻辑不动）；StreamChatToolCallingReproTests 顺带修雷（原无门控默认套件真发 LLM 请求 → 加 @Tag("integration") + @EnabledIfSystemProperty）；test application.properties 清合并冲突残留 `>>>>>>> master` + 乱码注释
- **回归门槛规则**：必须跑在线门 = 改分类器 prompt / 改意图枚举路由分界 / 新写动作上线前 / 升格评审前；必须跑离线门 = 改匹配算法 / 改样例 JSON / 改 Planner 规则；LLM 抖动连续两次失败才算回归，改样例必须记录原因防掩盖回归
- 文档：`docs/agent/agent-eval-gate.md`（定位 / 样例分类约定 / 跑法语义定死 / 门槛规则 / 新增样例流程 / 失败处理）
- 测试：AiIntentClassifierIntegrationTests 8 方法 → 1 参数化（在线 8 例） / LearningPlanMatchServiceTests 17 → LearningPlanMatchEvalTests 17（离线 14 + append 3） / AgentPlannerEvaluationTests（tag 单行）

### V3.3 受控写扩展 2（2026-09-05）

- **目标**：第 3 个写动作 UPDATE_LEARNING_TASK——重命名已有任务（taskTitle=旧名定位 + newTitle=新名），当第 3 个 case 压测双层 actionType 抽象。一次一个任务，复合动作（改名+勾选）/批量不做
- **提案语义**：record 加第 6 字段 newTitle（编译期强制发现 2 个构造点）；taskTitle 语义按 actionType 浮动延续 V3.1（UPDATE/ADD/RENAME 各自定义）；前端 reason 显式「旧名 → 新名」可审计
- **归一化四分**（Codex 评审收紧）：blank/null → UPDATE（保旧模型）；UPDATE_TASK_DONE → UPDATE；ADD → ADD；RENAME → RENAME；**其他非空 actionType → FAILED 不生成提案**（模型幻觉 DELETE/MOVE 时绝不吞成勾选——回落 + done 缺省 true = 误勾选，与 V3.1 同类坑一次修掉）；RENAME 缺 newTitle 显式 FAILED 绝不回落
- **不对称 precheck**（镜像 V3.1）：提案前预检「新名撞已有任务（排除原任务自身）+ 同名改名显式拒」→ FAILED step 循环继续不弹卡；「旧名不存在」confirm 时 resolveTaskIndex 拒绝（LLM 摘录旧名可能不准，留用户改）；confirm 层再查（防御纵深）
- **service renameTask**：照抄 updateTaskDone（归属 → taskIndex 边界 → put("title") done 保留 → @Version CAS → refreshPlanStatus）
- **分类器分界**（改名诉求原会漂进 LEARNING_PROGRESS「调整」语义族）：prompt 4 处加规则/示例/排除句（窄口径：仅改名 + 新名来自用户原话 → 受控写）；评测 JSON +2（boundary 改名 → LEARNING_AGENT + regression 对照组「帮我调整第二阶段」不漂移）——**在线门 10/10 全绿验证**
- **decider prompt**：SUGGEST_WRITE 内层「二选一」→「三选一」（UPDATE_LEARNING_TASK 字段文档 + 触发规则 + 一次只改一个）
- 前端：ai.ts union +'UPDATE_LEARNING_TASK' + newTitle；AiAssistant.vue 两 helper 加分支（done 恒 false 不能按 done 判断，V3.1 教训）+ 模板注释
- 测试：AgentWriteActionServiceTests(+4，11→15) / LearningAgentRuntimeTests(+4，17→21) / LearningProgressWorkflowServiceTests(+2，renameTask 集成) / 评测门在线 8→10

### V3.4 受控写跨域（2026-09-06）

- **目标**：第 4 个写动作 UPDATE_ARTICLE_TITLE（文章域第 1 个）——证明受控写抽象不绑学习域，跨域复用同一链路（SUGGEST_WRITE 提案 → 确认卡 → confirm → Redis 锁 + Run 状态 CAS + before/after 快照）。调研结论：通用骨架（AbstractAgentRuntime 终态通道 / WAITING_WRITE_CONFIRM 状态机 / 锁 / context_json / HTTP 端点 / 前端卡壳）全部中立；学习域专有物集中在提案端 + execute*/定位器
- **范围锁死详情页**：articleId 只认 PageContext（LLM input 摘录不作数）；无页面上下文 → FAILED 终局。不做「任意页点名我的文章」（需新增列表工具，另一大件）
- **并发防护三明治锚定**（Codex 两轮修正核心洞）：Articles 无 @Version → ① proposal.articleTitle = 提案时刻查库的权威旧标题（锚）② confirm stale 校验 currentTitle != 锚 → 拒绝「文章标题已变化，请重新发起修改」③ service 旧值条件更新 `WHERE title = 锚`（0 行同文案）。confirm 时查的当前标题只做 stale 校验与 before 快照，绝不充当 expectedOldTitle（自证式 CAS 防不了「提案后编辑器改标题」主窗口）
- **service updateArticleTitle**：字段级 SQL（不走全量 updateArticle——BeanUtil.copyProperties 会把未传字段置 null）；两步（归属校验 → 条件更新）；副作用落地：清详情/列表缓存 + 已发布刷新 RAG 索引（服务层测试断言）
- **提案端归一**（文章域无旧模型兼容负担）：只认 UPDATE_ARTICLE_TITLE，null/blank/未知（含学习域动作）→ FAILED 终局绝不回落；同名改名提案前拒（FAILED step 循环继续不弹卡）；归属失败 → ArticleNotOwnedException 语义终局（落 FAILED step + throw AgentRunTerminalException）
- **分类器分界**（「帮我把标题改短」原判 OPTIMIZE 明确指令族）：新分界 = 用户原话给完整新标题（改成 XX / 改名为 XX）→ ARTICLE_AGENT（受控写）；泛泛改标题 → OPTIMIZE_ARTICLE_WORKFLOW 不动。prompt 两段各加例外句——**在线门 12/12 全绿验证（原 10 例不漂）**
- **评测样例带 pageContext 首例**：ClassifierEvalCase +pageType/pageArticleId、驱动透传（article-detail 分界原本测不了）
- **测试基建修正**：ES 未开暴露 AiIntentClassifierIntegrationTests / LearningPlanMatchEvalTests 依赖本地 ES → @MockBean VectorStore（评测门语义上只依赖被测组件；兑现 eval-gate「离线门零前置」）
- 前端：ai.ts union +'UPDATE_ARTICLE_TITLE' + articleId/articleTitle（taskTitle 改可选——文章域提案为 null）；AiAssistant.vue 两 helper 加分支「修改文章标题 · 将文章《旧》改为《新》」（可审计）
- 测试：AgentWriteActionServiceTests(+5，15→20) / ArticleAgentRuntimeTests(护栏改向 +6，10→16) / ArticlesServiceUpdateTitleTests(新类 4) / ArticleAgentStepDeciderTests(+1，5→6) / LearningAgentRuntimeTests 21 零回归 / 在线门 10→12 / 离线门 34 全绿
- V3.5 触发证据登记：见文末「后续路线」V3.5 行与 `docs/agent/agent-v3-4-article-write-action-design.md`

### V3.5 路由元数据收口（2026-09-06）

- **触发兑现**：V3.4 登记的「域 ≥3 或 switch ≥5 case」——3 个 AGENT runtime（学习/文章/通用）已达域数量触发；调研确认加第 4 域要串改 8-9 文件 15+ 处（双份 if 链 + else 静默兜底学习域 + 文案双份 6 处 + 判定名单双写）
- **Codex 异议修正**：① 不做「域一等对象化」——只收路由元数据，不造域枚举（防把 bean/判定逻辑/域语义塞进一个概念）② **A（写动作执行器注册表）本轮不做**——收益/风险比 C >> A，switch 4 case 不是主雷，第 5 个动作前再做（届时 switch 5 case 达标、新动作直接落表）
- **AgentRuntimeRouteRegistry**（只登记不决策，Codex 补充约束写死）：构造注入三 runtime 登记 3 行（intent + runtime + fallbackMessage 同源单份）；只读查询 resolve/fallbackMessage/agentIntents/learningAgentIntents/articleAgentIntents（判定名单按 runtime 身份派生，不重复写 intent 名）；未登记 → null
- **AiMessageServiceImpl 收敛**：主分发 + fallback 分发共用 dispatchAgentRuntime（resolve 查表；未命中 warn「未登记路由，落入学习域显式兜底」——把静默雷变日志雷，现在不可能发生）；6 处兜底文案字符串删 → 注册表单份
- **Planner 名单收敛**：isLearningAgentIntent / isArticleAgentIntent 查注册表子集；**isGeneralAgentCandidate 留 Planner**（needsThinking 语义判定不塞注册表）；decide* 裁决语义不动
- 测试：AgentRuntimeRouteRegistryTests（新 4）/ Planner 两测试 setUp 改真注册表（mock 三 runtime，名单与生产一致）；**纯重构：分类器不动（无需在线门）+ 匹配算法不动（无需离线门）**——相关单测 + 意图路由测试全绿即等价
- 下一刀登记：A（写动作执行器注册表，第 5 动作前）+ B（确认壳基类与 AgentRunSuggestionService 共享，可选）；加第 4 域改动清单收敛前后对比见 agent-v3-5 设计稿

### V3.6 写动作执行器注册表（2026-09-06，A 刀兑现）

- **Context**：V3.5 登记 A 刀（Codex：A 是顺手清 switch 非主雷，收益/风险比低于 C，故 V3.5 只做路由收口、A 后置）。AgentWriteActionService 原为「类名通用、内容双域混合」——switch 4 case 注入两域 service + markExecuted/markArticleTitleExecuted 按域复制（触发证据 2/3 兑现）。第 5 个写动作将至（switch 4 → 5 case 到量化触发），先注册后落动作
- **AgentWriteActionExecutor 接口**：supports(actionType) 注册判定 + execute(run, proposal, userId) 返回 WriteActionResult(message, auditPayload)；执行器不依赖壳（防循环）；壳注入 List 自动收集
- **壳化 AgentWriteActionService**：构造删两域 service → List<executor>；confirmWithLock 改分发（未注册 → 原 SERVER_ERROR「提案数据异常」default 语义）；markExecuted 通用化（actionType/executedAt 壳统一补，域字段 + before/after 由执行器载荷给）；保留锁/CAS/getOwnedRun/parseWriteAction
- **域执行器**：LearningAgentWriteActionExecutor（3 动作内部 switch + resolvePlanAndStage/resolveTaskIndex/readStageTasks 私有化 + 载荷组装）/ ArticleAgentWriteActionExecutor（UPDATE_ARTICLE_TITLE + articleSnapshot）——逐行迁移零语义变化
- **测试**：AgentWriteActionServiceTests setUp 重装配（壳 4 参 + 真执行器注入原 mock service），20 断言全平移全绿；夹具零改动
- **纯重构**：分类器/匹配算法未动 → 无需在线/离线门；runtime 提案端/proposal/前端/Controller/评测门全不动
- 下一刀：第 5 个写动作（文章域可见性切换 / 学习域备注——落执行器即插即用）+ B 确认壳基类（可选）

### V3.7 文章域可见性受控写（2026-09-07）

- **批次语义**：HIDE_ARTICLE（前置 = 当前 PUBLISHED）/ PUBLISH_ARTICLE（前置 = 当前 HIDDEN）——目标状态动作，方向 = actionType（Codex：值不带 TYPE_ 前缀暴露给 LLM/前端；命名去「第 5 个写动作」计数口径）
- **设计亮点（前置推导 vs 锚存储）**：可见性前置可从动作方向推导 → proposal 不加 articleStatus 锚（对照 V3.4 改名必须存旧标题锚）；但**执行必须原子条件更新**（Codex TOCTOU 修正：hideArticle/publishArticle 是无条件 set status，confirm 校验到写之间有窗口——run 状态 CAS 只挡同 run 并发，挡不住文章被其他请求改状态；校验通过 ≠ 原子执行，V3.4 同款教训）
- **updateArticleVisibility**：归属 + `status = expectedStatus` 进 WHERE（0 行 → 「文章状态已变化，请重新发起修改」）；成功按 targetStatus 对齐 hide/publish 副作用（缓存 + RAG + PUBLISHED 时 publishedAt=now 与编辑器「重新发布」一致）；hideArticle/publishArticle 本体不动（编辑器链路继续用）
- **DRAFT 拒绝**：提案端终局 FAILED「这篇文章还未发布，隐藏/公开操作请在编辑器中完成」——Agent 不从详情页把草稿发布出去，编辑器人工闸保留；已是目标状态（已隐藏再提隐藏）→ FAILED step 不弹卡循环继续 LLM 告知
- **提案端三分支重构**：suggestWriteArticle（归一三分支）+ 抽 rejectStepContinue / finishSuggestWrite 公共私有方法（原改名流程行内重复收敛，V3.4 测试零改）
- **分类器分界**：「是否涉及内容」——状态操作指令（隐藏/公开）→ ARTICLE_AGENT 受控写；内容修改指令（删段落/润色）→ OPTIMIZE_ARTICLE_WORKFLOW 不漂移（在线门 14/14）
- 前端：ai.ts union +2；AiAssistant.vue label「隐藏文章/公开文章」+ reason 带可见性说明
- 测试：ArticlesServiceUpdateVisibilityTests（新 4）/ AgentWriteActionServiceTests(+3，20→23) / ArticleAgentRuntimeTests(+4，16→20) / 在线门 12→14（总量 46→48）
- 下一刀：学习域第 6 个写动作（备注/重排）或 B 确认壳基类；工程化补课仍后置（老大决定）

### V3.8 会话文章锚：跨页/跨轮文章指代（2026-09-07）

- **动机（老大实测两场景）**：首页说"把刚刚那篇隐藏了"→ Planner 降级 CTA 僵死（文章明明刚在会话里聊过）；站 B 详情页说"刚刚那篇"（指 A）→ pageContext 直取 B 错位提案。根因：文章 ID 唯一来源 = 当轮 pageContext，全链路无会话状态
- **对齐收敛（Codex ×3 轮）**：采纳"ai_sessions 单点状态 + 模型只输出 anchorMode 不输出 ID + Planner 只放行不选目标"；否掉"分类器四值枚举"（低信息处高风险判断）与"另起 LLM 解析器"（两次消解叠床架屋）；锚更新条件严格化（AGENT_RUN 定位成功 / PAGE_QA / PAGE_ACTION，闲聊/纯浏览不写；删光消息联动清锚）；title 只是快照（定位走 id + 查库现取权威标题）
- **会话文章锚**：ai_sessions +last_article_id/title/source/updated_at（本地 ALTER 已执行；部署机需同步）；ArticleSessionAnchorService mark/resolve（resolve 校验存在 + 归属，失效 null 宁不猜）
- **定位模型**：goal 双候选注入（当前页 ∪ 会话锚，查库权威标题 + 显式"无"标注）→ 决策器首步 JSON 顶层 anchorMode（CURRENT_PAGE / SESSION_LAST，并入 input 不动 record）→ runtime 决议 → run.targetArticleId（transient 不落库）→ QUERY_ARTICLE 经 prepareStepDecision 后端注入目标（执行器零改）→ SUGGEST_WRITE 提案用 run 目标（不再直取 pageContext）；决议容错：页面无文章时缺省自动回退会话锚
- **骨架只加 3 空钩子**（resolveStepTarget / prepareStepDecision / onStepSucceeded），学习/通用域零感知；Planner 文章域裁决改 hasPageArticle（只信 pageContext，分类器 LLM articleId 不参与定位防幻觉绕过）+ 会话锚放行 + 无锚场景化 CTA
- **实测修正 4 处**（实施期）：① FINAL_ANSWER answer/message 键兼容（模型偶发写 message 吞回答成兜底文案）② 删光消息清锚（"清空对话=上下文归零"心智）③ 前端 pendingNewSession（创建会话后被 toggle 自动恢复拉回旧会话，查库实锤 session_id 未变）④ 决策器指代规则补强（"刚刚那篇"在会话无候选时 ASK_USER 澄清，不许默认当前页——老大故意测试抓出）
- **非目标**：远指（Redis 那篇）/跨级指代/人称指代/跨 session → V4；OPTIMIZE/QA 跨页接入 → 机制同构后续刀；最近几轮全文历史注入 → 候选标题注入已够
- 测试：ArticleSessionAnchorServiceTests(新 8) / ArticleAgentRuntimeTests(+3，20→23) / AgentPlannerSupportTests(+1，42→43) / 评测样例 14→16（在线门待补跑）

### V3.9 QA 跨页指代接入：会话锚 → 文章问答（2026-09-08）

- **动机（V3.8 遗留断点）**：锚只覆盖文章域 Agent（操作/分析），QA 这条最高频的续聊管道仍断——首页问「刚才那篇里缓存击穿怎么讲的」无聚焦普通聊；详情页跳页问「刚刚那篇」错位答当前页；读别人公开文章/隐藏后的自己文章续问全失败
- **对齐收敛（Codex ×3 轮）**：锚读语义按域拆分（owned=写动作 / readable=QA，锚写点不验归属、锚里本就有他人公开文章）；intent.articleId 全链路退出 QA 定位（LLM 输出不可信，Planner 放行也不看）；resolve/loadReadable 都补 session.userId 归属校验（v3.8 遗留防御缺口）；QA 无目标注入 promptNote 说明态、绝不退化成无提示普通聊
- **Planner 零预判**：QA 意图恒放行 CHAT + CURRENT_ARTICLE（不查页面/不查锚/不看 ID）——QA 决策形态与目标无关，预判只制造 Planner/resolver 割裂（agent 域要查锚是因 AGENT vs CTA 两种 action 形态）；CURRENT_ARTICLE 只是触发消费点的开关
- **ArticleQaTargetResolver 单点决议（ai/qa 新包）**：候选先加载后成候选（PC=loadReadable 成功、AC=resolveReadable，脏 ID/他人隐藏/已删除 → 无 PC）+ 措辞信号矩阵（时间近指∧文章指代 → 锚优先；这篇系 → 页面优先；弱指代「那篇」双候选 → 追问态带双标题；唯一候选直接用；无 → 说明态）+ QaTarget(已验证文章|promptNote) ——决议与加载合一，正文注入与来源 [1] 卡共用一次 resolve，消费点零查库
- **实施中修正 1 处**：游客会话近指豁免（sessionId null → 信号降级 S0——游客无「会话最近文章」概念，详情页问「刚才那篇」不触发澄清误伤）
- 测试：ArticleSessionAnchorServiceTests(8→19) / ArticleQaTargetResolverTests(新 18) / AgentPlannerSupportTests(+2，43→45) / ArticleAgentRuntimeTests 23 零回归 / 离线门全绿；分类器样例 16→22（在线门待补跑）；planner 样例 QA 断言更新（reason「当前文章」→「QA resolver」）

### V3.10 思考展示升级：受控 ReAct 思考摘要 + 术语定位（2026-09-08）

- **动机（老大 + ReAct 话题）**：多数项目以 ReAct 为招牌，本项目决策循环本质就是受限 ReAct 但「Thought 不落字」——思考面板是动作流水账（模板文案「正在/已完成 X」来回跳）；老大顾虑「展示太细会把调了几次模型、工具名全说出去，控制不好」
- **术语定位（A）**：三域 Runtime = 受限 ReAct（decision → execute → observation 循环 + 白名单 + 受控写 + 确认门）；Workflow = Plan-and-Execute；聊天 @Tool = function calling——三范式对照 + 面试 Q&A + 简历措辞（稿 §九）
- **thoughtSummary 定位（D1）**：展示副产品非决策依据——不上决策上下文、不阻断循环、不存原文（审计看 actionType/input/observation/状态）
- **命名分离（D0）**：LLM JSON 顶层 "thought"（ReAct 术语）→ parseDecision 读入 → 全链路 thoughtSummary / DB thought_summary
- **三道闸（D2）**：① prompt 约束（buildUserPrompt 基类单点，repair 轮宽容）② AgentThoughtSanitizer 分级闸门（强信号单命中即弃：枚举/JSON/白名单/决策器/第 N 步；业务弱词「API/工具/模型/参数/接口」与机制动词「调用/返回/输出」组合才弃——防技术博客业务误伤；零修补）③ 展示与存储同源
- **链路**：AgentStepDecision 第三分量 → AgentStepEmitter 5 参（骨架 9 处 + 学习 7 + 文章 8 emit 全扫，FAILED 传 null 失败文案优先）→ ai_agent_steps.thought_summary 可空列（V3.8/3.9 后首次 ALTER，本地已执行）→ 事件/VO/补拉全链路；前端行文本 `thoughtSummary?.trim() || message`
- **交互（老大实施中新增）**：思考期间自动展开 → 正文首个 DATA chunk 自动收起 → 手动可再展开（thinkingAutoMode，用户 toggle 即退出自动模式）
- **实施中修正 3 处**：Javadoc 写 `QUERY_*` 星号紧邻斜杠提前闭合注释（javac 报注释外非法字符）——注释内禁用；Learning emitter 落库断言 3→2 行（captureSteps 捕获 insert 非事件）；测试内联 emitter lambda 4→5 参 ×3
- 测试：AgentThoughtSanitizerTests(新 21) / Learning 21（事件断言升级：RUNNING/SUCCESS 同句携带 + 落库同步） / 三域 runtime + executor + inspection + decider 全绿 / 离线门全绿 / 三域真实 LLM smoke（LlmAgentStepDeciderOnlineSmokeTests 新 3，runRealLlm 门）；全量回归暂缓（老大：边推进边修）

### V3.11 证据收敛门：Evidence Verifier（2026-09-09）

- **动机（Codex 建议 + V4②④ 试点）**：文章域「证据够不够」和「下一步做什么」挤在决策器一个 decision 里自问自答——查一次就急着答（跳步/编造论断直接进用户眼睛）无独立检查；两条收尾路径（FINAL_ANSWER / maxSteps 总结）都没收敛门
- **收敛门结构**：FINAL_ANSWER 前领域钩子（骨架默认 null 三域原路径）——规则闸（R1：目标文章已决议但 successfulActions 无 QUERY_ARTICLE → 零 LLM NEED_MORE；R2 零观察放行）+ 语义闸（独立角色独立调用一次 LLM：草案论断越证据/矛盾 → NEED_MORE、缺用户才知道信息 → ASK_USER、够 → SUFFICIENT；只评不选，输出 missingEvidence 指引补查方向）
- **QUERY_ARTICLE focus（Codex 评审抓出的核心洞）**：原 QUERY_ARTICLE 每次返回同一份结构摘要——NEED_MORE 后补查必空转；focus 净化词表 + 小标题/段落 contains 返回正文片段（材料真增量）；决策器 prompt 补 focus 与 SEARCH_RAG 分界 + 观察防注入（文章正文不可信数据）
- **拦截与转问**：NEED_MORE 落 FAILED step（errorMessage 记 missingEvidence+reason）+ 提示 observation → 循环补查（计一步，maxSteps 兜底）；ASK_USER 构造新 decision 转问（单条 ASK_USER step，不伪造 FINAL_ANSWER step）
- **到顶保守收尾门**：S1（目标文章从没读过）/ S1b（验证拒绝从未满足，hasUnresolvedVerifierRejection run 局部）→ 不 summarize 硬答，落保守文案；extractFinalAnswerText helper（answer→message 兜底）Verifier 与 completeWithAnswer 共用（验证对象 = 实际输出）
- **P0 前置修 V3.8 遗留 bug**：QUERY_ARTICLE 定位优先级 pageContext 优先于决议目标——B 页指会话锚 A 时读 B（跨页分析错答）；改为决议目标 input.articleId 优先、pageContext 降兜底（决策器 prompt 禁 LLM 自填 articleId，input 只来自后端注入）
- **fail-open**：验证器异常/JSON 两次解析失败 → SUFFICIENT 放行（拦次优答不拦危险操作，写安全归后端硬校验）
- **在线评测两档断言**（首轮 4 失败修正）：SUFFICIENT 例精确锁放行 / NEED_MORE·ASK_USER 例锁 not-SUFFICIENT（两拦态互判是闭环语义差异不硬锁）；missingEvidence 归因只锁稳例；样例 4 收紧为纯观察复述；样例 9 归因抖动不锁
- 测试：ArticleEvidenceVerifierTests(11，规则闸+三态+fail-open+修复) / ArticleAgentRuntimeVerifierTests(5，拦截补查/转问单 step/S1b/S1/默认 null 回归) / executor focus 4 例 / 在线 verifier 13 例（eval 门）；全量回归未跑（老大指示只跑相关测试——三域 runtime + eval 门已覆盖骨架主路径）

### V3.12 会话结论继承：跨 run 上下文交接（V4③ 试点窄刀）（2026-09-10）

- **动机（老大手测实证）**：agent 分析给建议 A/B/C → 用户「按刚刚说的优化」→ OPTIMIZE Workflow 重新分析生成 X/Y/Z≠A/B/C——建议桥只传 initialMessage 不传 agent 结论，跨 run 上下文断裂（与 V3.8/V3.9 指代传递同族：「改哪篇」已解决，「按什么改」没有）
- **会话结论锚**：`ai_sessions.last_conclusion_*` 五列（articleId / text / sourceRunId / sourceType / updatedAt）——同 session 单槽位，存 finalAnswer 原文（2000 字截断）
- **两个写点**：**A（FINAL_ANSWER 终态）**骨架钩子 `onAnswerConcluded` 晚于 `finish()` 落库（防孤儿锚），四项条件含 QUERY_ARTICLE 证据边界（没读过目标文章不写，与 V3.11 协同）；**B（建议卡 confirm 成功时刻）**在 CAS 后、`createWorkflow` 前——未确认的建议结构性进不了锚（不靠词表排除），顺带修复 confirm 路径 `suggestion.reason` 丢失
- **读取单点**：`ArticleOptimizeWorkflowHandler.create()`（分类器直达 / confirm / API 直建三入口全覆盖）→ 快照固化进 `context.handoff`（读时快照，事后锚变不影响进行中的 Workflow）
- **注入**：GENERATE_OPTIMIZATION_PLAN prompt 防注入三段式模板 + 500 字截断（二阶注入链：文章正文 → 观察 → finalAnswer → 下一条 prompt，V3.11 的观察层防护会被本链绕开）
- **不判定用户是否引用**（机会性弱参考）：词表假阴性会静默打穿主路径；误注入可审计可拒绝——「用户引用」判定留升级路径（见设计稿 §四）
- **并发**：雪花 ID 条件更新 CAS（`last_conclusion_source_run_id < 新 runId`），防旧 run 晚完成覆盖新结论；不新增列、不设时间窗口（`updated_at` 仅审计）
- **读权限对齐可读语义**（与同轮读权限修复同源）：他人公开文章的分析结论可继承；他人非公开一律失效；越权风险为零（写路径 `validateArticleOwner` 仍把关，别人的文章创建不了 Workflow）
- **清理**：删光消息联动清五列
- **编辑页页面上下文补强（同轮附带，2026-09-10）**：编辑已有文章的页面补 `articleId`（`buildPageContext` 只认 `article-detail` → 补 `editor-edit`）+ 后端按 `pageType` 明示「已保存版本」语义（agent 域 `effectiveGoal` + QA 域正文注入两处同源）；分类器 prompt 未动（编辑页意图判定是概率性的，登记观察）
- 测试：ArticleSessionAnchorServiceTests(+10，CAS/串文章/越权/他人公开可读/他人非公开拒绝/截断) / ArticleAgentRuntimeTests(+3，写锚/没读文章不写/fail-open) / AgentRunSuggestionServiceTests（构造器同步）；设计 + 实施记录见 `docs/agent/agent-v3-12-conclusion-inheritance-design.md`；Codex 两轮评审 15 条全吸收；相关回归 342 例全绿（全量回归未跑）

### V3.13 Plan Preview 阶段一：文章域计划展示与观测（V4① 试点）（2026-09-10）

- **定位（诚实优先）**：**能力地图补齐，不是痛点修复**——目前没有实测证据表明「agent 因为缺少计划而跑偏」（V3.11 那次 6 步空转是 focus 定位 bug）；产出以面试讲法 + 架构认知 + 观测数据为主。也**不是** Plan-and-Execute、不是 Plan + ReAct，只是后者的**基线观测**
- **两形态分辨**：预定义 / scripted Plan-and-Execute 已有（= Workflow，步骤代码写死）；LLM 动态规划型未做。真正的空缺是第三种：目标明确、但步骤无法预定义时的 **Plan-guided ReAct**
- **四档选型**：能预定义流程 → Workflow；目标明确 + 需多子目标 + 临场判断 → Plan-guided ReAct；目标明确、单步可解 → 纯 ReAct；**目标不清 → ASK_USER 澄清**（把它写成「纯 ReAct 兜底」会重新引入 V3.8/V3.11 修过的猜测与空转）
- **生成与校验**：决策器**同一次调用**带顶层 `plan`（零额外 LLM 调用，避免两次独立判断不一致）；`AgentPlanValidator` 六规则（数量 2-4 / 非空 / 去重 / 单项 ≤60 字 / 总长 ≤200 字 / 机制词）**零修补整条丢弃**；机制词只拦动作枚举与实现标识，业务词（API / 工具 / 模型 / 参数 / 接口）放行
- **触发**：决策器自决（不做后端词表闸门）；只在**首步**采集；首步即终态 / 需澄清（`FINAL_ANSWER` / `ASK_USER` / `SUGGEST_*`）时不采集（无执行基线，且可能在澄清前误导用户）
- **持久化**：`ai_agent_runs.plan_json`（**run 级**）+ **单列更新**（不复用 `updateById`——否则计划写失败会与 run 状态写失败混成同一个故障，fail-open 形同虚设）
- **展示**：`AGENT_PLAN(3002)` 独立事件（不复用 `AGENT_STEP`：计划 run 级 vs 步骤步级）；时序写死 `decide → 白名单 → plan 校验 → 落库 → emitPlan → 首个 STEP 事件`；前端按**占位消息索引**归并（事件早于 STOP，占位消息还没有 agentRunId——按 ID 匹配会丢掉实时计划）；思考面板**顶部计划块**；刷新经 `getAgentRunDetail` 恢复（**不扩展 `/steps` 形状**，step 级接口不塞 run 级数据）
- **隔离分层**：**永远不改**——不进 observations / 不影响 Verifier / 不绕过权限白名单确认门 / 与 thoughtSummary 分字段；**阶段一专属**——不进决策上下文（阶段二放开为独立 plan context）
- **域开关**：`supportsPlanPreview()` 默认 false、**仅文章域 true**；解析层共享（无域语义），prompt 规则与采集链路在开关内——学习域 / 通用域代码路径、prompt、事件零变化
- **观测指标（阶段一核心产出）**：计划生成率 / 项数分布 / **计划-执行对应率**（人工标注 FOLLOWED / SKIPPED / PIVOTED，PIVOTED 再四类归因——只有「初始计划错误」指向计划准确性）；**观测数据决定阶段二做不做**（高度跟随 → 该 Workflow 化；经常偏离且更好 → 阶段二不做；计划合理但跑偏 → 阶段二成立）
- **登记不实施**：阶段二 Plan-guided ReAct（五条硬约束 + 两个未决问题：偏离 vs 重新规划的边界 / 计划与 Verifier 的分工）+ **Reflection**（Verifier vs Reflection 分辨 / 触发条件 / 六条边界 / 五层职责关系表 / **先穷尽规则再上 LLM 反思**——V3.11 的 R3 是规则级空转保护，不是 Reflection）
- **手测（2026-09-10 七项全过）**：发现三件——① **重复查询已修**（连查 4 次记忆 / 两次一字不差的 QUERY_ARTICLE+focus；根因是决策器 prompt 缺防重复约束，QUERY_ARTICLE 原有那条只覆盖「无 focus」；修法是给**终止条件**不是禁重复：查询一次性语义 + 记忆一次就够、没命中即确实没有）；② **无指代跨页选会话锚不修**（老大判定产品上确是歧义场景，记已知边界）；③ verifier 在线样例 [3] 判定波动（固定样例直调、与本次改动无关，待观察）
- 测试：AgentPlanValidatorTests(12) / ArticleAgentRuntimeTests(+5，含**计划不进 observations 隔离锁**与**单列更新断言**）；相关回归 359 例 + 离线评测门 24 例全绿；前端 vue-tsc 通过；本地 MySQL 已 ALTER（生产部署前需同步）；Codex 三轮评审共 16 条全吸收；设计 + 实施记录见 `docs/agent/agent-v3-13-plan-preview-design.md`

### V2.5 文章侧建议桥（2026-08-31~09-01）

- **骨架抽取**：AbstractAgentRuntime 抽象基类（模板方法）——循环骨架/终态/落库/过期清理/建议桥上移，领域差异钩子化（decider/白名单/文案/SUGGEST_WRITE 扩展终态）；LearningAgentRuntime 改继承，15 个现有测试断言零修改
- **公共 SSE 管道**：streamAgentReply 泛化（runtime + pageContext + fallback 文案参数化），学习/文章共用 Flux.create 流式出口；PageContextDTO 从 Planner 直通 Runtime（articleId 只当线索，查库验归属）
- **文章域三件套**：ArticleAgentRuntime（白名单 6 动作，无 SUGGEST_WRITE）/ ArticleAgentActionExecutorImpl（QUERY_ARTICLE 结构摘要：标题/状态/字数/段落/小标题/正文预览）/ ArticleAgentStepDecider（继承 LlmAgentStepDecider 只换 prompt）
- **路由**：分类器 ARTICLE_AGENT（文章页 + 模糊优化诉求；明确指令仍 OPTIMIZE_ARTICLE_WORKFLOW 直达）；Planner decideArticleAgent（缺 articleId 降级 CTA）；主分发按 intent 分支
- **confirm 分支**：AgentWorkflowSuggestion 加 articleId 字段（编译期强制全链）；createWorkflow 加 OPTIMIZE_ARTICLE case（parseArticleId 防脏数据 + Handler 归属双保险）
- **行为语义**（端到端验收确认）：咨询型（"帮我看看还能怎么改"）→ Agent 直接给意见，不弹建议卡（用户无动手意愿，不打断）；行动型 → 建议卡 → 确认 → Workflow；明确指令 → 直达
- **终局失败**：ArticleNotOwnedException → 失败 step 后直接收尾（1 步结束，后端友好文案，不泄露文章 ID）
- 测试：ArticleAgentRuntimeTests(10) / ArticleAgentActionExecutorTests(8) / ArticleAgentStepDeciderTests(5) / AgentPlannerSupportTests(+5) / AgentRunSuggestionServiceTests(+2)

## 三、测试覆盖

后端 55 个测试类 / 346 个 @Test（含真实 LLM 集成测试）。Agent/Workflow 相关 42 个类。默认套件 2026-09-03 实测：351 通过 + 3 在线 skipped（Repro / 分类器需 -DrunRealLlm=true）；AiWorkflowRunServiceTests 4 失败 + 4 错误为既有问题（真实 LLM 依赖 + approve 校验迭代后测试未同步，与本轮改动无关，待修）。

| 模块 | 测试类（核心） |
|---|---|
| Router/分类器 | AgentPlannerSupportTests(45) / AiMessageIntentRoutingTests / AiIntentClassifierPromptTests(4) / AiIntentClassifierIntegrationTests / AgentPlannerEvaluationTests / AgentDecisionTraceTests / AgentRuntimeRouteRegistryTests(4) |
| V3.8/V3.9 会话锚/QA 决议 | ArticleSessionAnchorServiceTests(19，owned+readable+归属校验) / ArticleQaTargetResolverTests(18，决议矩阵+词集边界+游客豁免) |
| V3.10 思考摘要闸门 | AgentThoughtSanitizerTests(21，分级闸门+业务词防误伤+空/截断) / LlmAgentStepDeciderOnlineSmokeTests(3，runRealLlm 门——三域真实 LLM 决策 + thought 闸门幂等断言) |
| V3.11 证据收敛门 | ArticleEvidenceVerifierTests(11，规则闸 R1/R2 + 语义闸三态 + ASK_USER 缺 question 修复 + fail-open ×2 + 修复路径) / ArticleAgentRuntimeVerifierTests(5，NEED_MORE 拦截补查审计 / ASK_USER 转问单 step / S1b 保守收尾 / S1 保守收尾 / 验证器 null 原路径回归) / executor focus(4，分节命中/自然措辞净化/未命中明说/无 focus 原摘要) / ArticleEvidenceVerifierOnlineTests(13，runRealLlm 门——12 样例两档断言 + 防惰性探针) / +手测修复(5，纯标题节跳过/并列降级/正文消歧/他人公开可读/他人草稿拒绝) + R3 规则闸 2 例 |
| V3.12 结论继承 | ArticleSessionAnchorServiceTests(29，含 V3.12 +10：mark CAS/旧 run 拒绝/串文章 null/越权 null/他人公开可读/他人非公开 null/截断 500/空参) / ArticleAgentRuntimeTests(+3，读过→写锚 / 没读→不写锚 / 写锚异常不影响回答) / AgentRunSuggestionServiceTests(构造器同步) |
| V3.13 Plan Preview | AgentPlanValidatorTests(12，六规则 + 机制词六种 + 业务词放行 + 边界值) / ArticleAgentRuntimeTests(+5，首步计划单列更新落库 + 发事件 / **计划不进 observations 隔离锁** / 校验不过丢弃 / 首步终态不采集 / 非首步忽略) |
| V4 第一刀 | AgentRunDevControllerTests(5，门禁 fail-closed：面板关闭 / 非白名单 / 未登录 / **空白名单** → 403 且不触达数据层；白名单放行) / ArticleAgentRuntimeTests(+3，同参重复拦截不执行 + 检查位置（终态不查） + **观察来源标注且内部 ID 不进观察**) |
| Runtime | LearningAgentRuntimeTests(21) / LearningAgentActionExecutorTests(13) / LlmAgentStepDeciderTests(9) |
| V2.1 建议桥 | AgentRunSuggestionServiceTests(13) |
| V2.2 inspection | AgentRunInspectionServiceTests(6) |
| V2.4/V3.1/V3.3/V3.4/V3.6/V3.7 受控写 | AgentWriteActionServiceTests(23) / LearningAgentWriteActionExecutor（经壳端到端覆盖）/ ArticleAgentWriteActionExecutor（经壳端到端覆盖） |
| V2.5/V3.4/V3.7/V3.11 文章域 | ArticleAgentRuntimeTests(23) / ArticleAgentActionExecutorTests(13，含 P0 定位优先级 2 例 + focus 4 例) / ArticleAgentStepDeciderTests(6) / ArticlesServiceUpdateTitleTests(4) / ArticlesServiceUpdateVisibilityTests(4) |
| V3.0 通用域 | GeneralAgentRuntimeTests(10) / GeneralAgentActionExecutorTests(8) / GeneralAgentStepDeciderTests(6) |
| Workflow 稳定化 | WorkflowActionLockTests / AiWorkflowRunVersionTests / AiWorkflowRunServiceTests(19) |
| Workflow 业务 | LearningPlan/Progress/AssistWorkflowServiceTests / CreateArticleWorkflowHandlerTests |
| 学习计划 | LearningPlanMatchEvalTests(17) / AiLearningPlanToolsTests |
| 评测门（V3.2/V3.4/V3.9/V3.10/V3.11） | 离线门 `-Dgroups=eval`（planner 1 + learning 17 = 18）；在线门 `-DrunRealLlm=true`（+ 分类器 22 + 三域决策 smoke 3 + verifier 13 = 38）——共 56 例，2026-09-09 全绿；2026-09-09 伪分界修复（分类器 2 例「泛指不升级」改「升级+resolver 澄清兜底」）；门槛规则补：改 Verifier prompt / focus 定位算法必跑——见 `docs/agent/agent-eval-gate.md`；评测类已 mock ES |
| RAG/记忆 | ArticleRag*（8 类）/ AiEpisodicMemory* / AiMemoryDecisionSanitizerTests |
| 其他 | AiRateLimitInterceptorTests / AiRateLimiterTests / StreamChatToolCallingReproTests / AiModelServiceToolExposureTests |

前端：22+ spec 文件（markdown XSS / articleOutline / home-layout / api / stores / router / utils）+ vue-tsc 零错误。

## 四、遗留 / 暂缓 / 后续路线

### 暂缓（不阻塞，明确记录）

- **token 前端展示**：后端 usage 已落库（ai_messages.token_count / workflow run 表），前端未展示（多次推迟）
- 站内消息通知 / 私信
- 游客临时会话次数限制
- GitHub OAuth 安全细节（独立原型）
- 锁 TTL 续期 / 幂等保留时长调整（已知边界）

### 后续路线（设计文档 Later Evolution）

```text
V2.5   文章侧 Workflow 建议 ✅（2026-09-01 验收）
V3.0   通用思考模式 ✅（2026-09-01 验收）——普通聊天接进 Agent Runtime：
       needsThinking 正交判定（分类器语义粗筛，硬闸门先上，后续按漏拦率放宽到
       "只打标不拦截"）+ 通用域三件套（QUERY_MEMORY 为主 / SEARCH_RAG 补证据，
       maxSteps=3）+ Planner 单点归一化 + 游客硬边界前置
V3.1   受控写扩展 ✅（2026-09-02 验收）——第 2 个写动作 ADD_LEARNING_TASK（追加任务）：
       双层 actionType 语义写死 + 目标解析拆分（resolvePlanAndStage / resolveTaskIndex）
       + 重复预检明确拒绝 + 分类器"用户给任务名 vs AI 生成任务"分界 + 确认卡按 actionType 渲染
V3.2   评测门 ✅（2026-09-03 验收）——固定样例集（3 JSON）+ 一键 runner（-Dgroups=eval
       离线 34 / 在线 42）+ 回归门槛：改分类器 prompt / 意图分界 / 匹配算法 / 新写动作
       上线前必跑；分类器分界 8 例 + 阶段定位 14 例 JSON 化（加样例 = 加 JSON 行）
V3.3   受控写扩展 2 ✅（2026-09-05 验收）——第 3 个写动作 UPDATE_LEARNING_TASK（重命名）：
       newTitle 提案字段 + 归一化四分（未知非空 actionType FAILED 不吞勾选）+ 同名改名双层
       拒绝 + 不对称 precheck + 分类器改名分界（在线门 10/10）
V3.4   受控写跨域 ✅（2026-09-06 验收）——文章域第 1 个写动作 UPDATE_ARTICLE_TITLE（改自己文章标题）：
       范围锁详情页（articleId 只认页面上下文）+ 并发防护三明治锚定（proposal.articleTitle=提案时刻
       DB 权威旧标题 → confirm stale 校验 → service 旧值条件更新，Articles 无 @Version）+ 分类器
       「完整新标题 vs 泛泛优化」分界（在线门 12/12）+ 评测样例带 pageContext 首例 + 评测类 mock ES
V3.5   路由元数据收口 ✅（2026-09-06 验收）——intent → runtime + fallbackMessage 单点登记：
       AgentRuntimeRouteRegistry（只登记不决策）+ 双份分发/文案/判定名单收敛 + 未登记 warn 显式兜底
       （Codex：不做「域一等对象化」；写动作执行器注册表 A 本轮不做，记下一刀）
V3.6   写动作执行器注册表 ✅（2026-09-06 验收，A 刀兑现）——AgentWriteActionService 壳化 +
       List<AgentWriteActionExecutor> 分发（Learning 3 动作 / Article 1 动作域内聚合 + 定位器私有化）；
       markExecuted 两法合一；纯重构零行为变化；新写动作 = 域执行器加 supports/分支即插即用
V3.7   文章域可见性受控写 ✅（2026-09-07 验收）——HIDE_ARTICLE / PUBLISH_ARTICLE 批次：
       前置状态从动作推导（proposal 零新字段）+ 执行原子条件更新（updateArticleVisibility 收口
       TOCTOU）+ DRAFT 拒绝（编辑器人工闸）+ 分类器「状态操作 vs 内容修改」分界（在线门 14/14）
V3.8   会话文章锚 ✅（2026-09-07 验收）——ai_sessions 单点锚（AGENT_RUN/QA/ACTION 三写点 +
       删光消息清锚）+ anchorMode 指代消解（CURRENT_PAGE/SESSION_LAST，决策器只输出语义枚举
       不输出 ID）+ goal 双候选注入 + Planner 只放行不选目标 +「刚刚那篇」无候选 ASK_USER
       不默认当前页 + 分类器 LLM articleId 退出文章域定位（防幻觉绕过）；QA/OPTIMIZE 跨页接入
       = 机制同构后续刀（V3.9 兑现 QA 侧）
V3.9   QA 跨页指代接入 ✅（2026-09-08 验收）——会话锚铺到 QA 管道：锚读语义按域拆
       （owned=写动作 / readable=QA，resolveReadable 放行他人公开文章续问）+ 读权限单点
       loadReadable（公开 + 本人全状态）+ ArticleQaTargetResolver 单点决议（候选先加载后成
       候选 + 措辞信号矩阵 → 目标/追问态/说明态，QA 无目标注入 promptNote 不落普通聊）+
       Planner QA 恒放行零预判 + intent.articleId 全链路退出 + resolve 补 session 归属校验
V3.10  思考展示升级 ✅（2026-09-08 实施完成，待手测）——受控 ReAct 思考摘要展示：
       决策器每步输出 thought（LLM JSON 顶层）→ AgentThoughtSanitizer 分级闸门（强信号
       单命中即弃 / 业务弱词 + 机制动词组合才弃，零修补，防业务误伤）→ thoughtSummary
       （仅展示非决策依据，不存原文）；AgentStepEmitter 5 参 + ai_agent_steps 加可空列 +
       事件/VO/前端全链路；行文本 thoughtSummary?.trim() || message；思考自动展开、
       正文输出自动收起、手动可再展开；术语定位（受限 ReAct / Plan-and-Execute /
       function calling 三范式 + 面试讲法）；三域真实 LLM smoke 锁「thought 要求不破坏
       决策 JSON」；本地 MySQL 已 ALTER；全量回归暂缓（老大：边推进边修）
V3.11  证据收敛门 ✅（2026-09-09 实施完成，Codex 两轮评审全吸收；**手测 4/4 通过
       2026-09-10**——拦截→补查闭环、focus 命中、保守收尾文案、ASK_USER 转问；本轮手测
       暴露并修复三处——focus 定位 bug（纯标题节抢命中 + 取第一个 + 并列无消歧 → 6 步空转）、
       R3 规则闸（本轮观察与前序逐字重复 → 放行防空转）、读权限误用归属（QUERY_ARTICLE/
       锚解析/候选/Planner 入口四处的读路径改 isReadable，写路径归属校验不动））——文章域
       FINAL_ANSWER 前独立判定「草案 vs 证据支撑性」：规则闸（R1 目标已决议未读 → 零 LLM
       NEED_MORE / R2 零观察放行）+ 语义闸（独立角色独立调用：越证据/矛盾 → NEED_MORE、
       缺用户信息 → ASK_USER、够 → SUFFICIENT，missingEvidence 指引补查）；QUERY_ARTICLE
       input.focus（净化词 + 小标题/段落 contains 真增量，防空转——Codex 评审核心洞）；
       NEED_MORE 拦截落 FAILED step 计一步 / ASK_USER 构造新 decision 转问单 step；
       到顶保守收尾（S1 没读文章 / S1b 验证拒绝未满足 → 不 summarize）；fail-open；
       P0 修 V3.8 遗留 QUERY_ARTICLE 定位 bug（决议目标 > pageContext）；决策器观察防注入；
       extractFinalAnswerText 共用（验证对象 = 实际输出）；eval 门 56 例全绿（verifier 在线 13
       两档断言）；全量回归未跑（老大指示只跑相关测试）
V4     自主闭环整体设计（2026-09-08 老大定调：零敲碎打散点刀收拢到 V4，不逐个单做；
       2026-09-09 调整为试点先行节奏——V3.11 = ②④ 试点落地、V3.12 = ③ 试点落地；
       ① 的号顺延为 V3.13「Plan Preview」（观察性试点；阶段一基线观测 → 阶段二
       Plan-guided ReAct；设计稿已评审，见 docs/agent/agent-v3-13-plan-preview-design.md）。
V3.12  会话结论继承 ✅（2026-09-10 实施完成 + 手测通过）——同 session「上一轮文章域 Agent
       结论 → OPTIMIZE Workflow 优化方向」交接（V4③ 试点窄刀）：会话结论锚（ai_sessions
       last_conclusion_* 五列）；两个写点（A：FINAL_ANSWER 终态，钩子晚于 run 落库防孤儿锚，
       含 QUERY_ARTICLE 证据边界 / B：建议卡 confirm 成功时刻，未确认的建议结构性进不了锚，
       顺带修 confirm 路径 suggestion.reason 丢失）；读取单点 ArticleOptimizeWorkflowHandler
       .create()（分类器直达 / confirm / API 直建三入口全覆盖）→ context.handoff 快照固化 →
       注入 GENERATE_OPTIMIZATION_PLAN（防注入三段式 + 500 字截断，二阶注入链：文章正文→
       观察→finalAnswer→下一条 prompt）；不判定用户是否引用（机会性弱参考，词表假阴性会静默
       打穿主路径；误注入可审计可拒绝）；雪花 ID 条件更新 CAS 防旧 run 覆盖新结论；读权限对齐
       可读语义；删光消息同步清五列；Codex 两轮评审 15 条全吸收
        根因判断：AI 助手自主闭环不够（跨 run 上下文不继承 / 流程无闭环验证 / 判定入口窄）。
        评估与设计点：① 运行期任务拆解（Plan 层：首轮显式计划 vs 现有 Workflow 预定义步骤 +
        循环贪心单步——demo「自动拆解+验证」多为自由循环自然行为非显式设计）；② 显式验证/
        反思（Reflexion 自检 + 工具结果质量驱动重查 + 收尾基于观察核验防幻觉——现只有失败
        回填隐式修正）；③ 跨 run/工作流上下文继承（手测实证：agent 建议 A/B/C →「按刚刚说
        的优化」→ OPTIMIZE 重分析出 X/Y/Z≠A/B/C——建议桥只传 initialMessage 不传 agent
        结论，修复 = workflow 创建链注入上轮结论；与 V3.8/3.9 指代传递同族，「改哪篇」vs
        「按什么改」）；④ evaluator-optimizer 闭环（workflow 节点间无 LLM 判断：检查报告
        开环给人、retry 人手按；行业对照 Routing 有 / Evaluator-Optimizer 无闭环 /
        Orchestrator-Workers 无——闭环 = 生成↔评估自动循环 + 上限 + 人工兜底，自研可加）
        → ✅ 2026-09-12 审计收口：产物层早已闭环（写作/优化/学习三域同构的规则检查 + 单次
        auto-retry + 失败停确认门，原描述「开环」与代码不符），方案层有意不闭环（主观标准
        撞门槛二）；审计顺手修学习域两处不一致（检查无独立 step / 前端不展示检查结果），
        见 V4 设计稿 §5.4 与 §十四；
        ⑤ 思考展示全局化（QA/普通聊天过程展示，老大暂缓等 V4，粒度像 agent、简单问候除外）；
        ⑥ 与 LangGraph / AgentExecutor 能力地图对照（Anthropic《Building Effective
        Agents》：Workflow = 代码预定义路径 / Agent = LLM 动态路径，能 Workflow 就
        Workflow；框架补状态/持久化不决定模式），结论落「值得移植 vs 取舍不动」；
        框架评估（LangGraph / LangChain4j）并入此主线，作为实现选项之一
        → ✅ 2026-09-12 完成：9 维度逐项对照（LangGraph / LangChain4j / MCP，依据官方
        文档）落 agent-framework-evaluation.md；**不引入 Agent 框架接管 Runtime**（双重
        状态机 + 新增硬理由 HITL 语义冲突：interrupt() resume 时整个节点从头重跑、要求
        副作用幂等，我们的确认门不承诺可重放）；可视化预设判断被推翻（Studio 只读它自己
        的图 → 自建面板已落地 + OTel tracing 待做）；MCP 只接只读工具（写路径禁止：工具
        动态发现 + annotations 官方声明不可信；输出只进自己的 observation / Verifier
        链路）。**周边能力仍可用框架**（模型适配 / 观测链路 / 只读工具协议），分层可用性
        表见该文档 §一
        → ✅ 2026-09-12 实施 Agent 可观测性（V4⑥ 落地动作）：Spring Boot 原生 Micrometer
        Tracing + 4 线程池 ContextPropagatingTaskDecorator + 业务 ID 进 MDC；**核心坑：
        Reactor 断链比预判更深**——Flux.create 的 sink lambda 在**订阅时**执行，而订阅线程
        （Spring MVC 异步）本身就没有 MDC，onScheduleHook 救不了「源头没有」；正解是请求
        线程抓快照 + 显式传参（8 处调度点显式包裹：Agent 1 + Workflow 创建 5 + Workflow
        流式 1 + 其它）；已知边界 4 条见设计稿 §8.4
V5     Multi-Agent（有真实独立角色和可衡量收益时才做）
```

### 已知边界

- 学习类 Workflow 初始链路步骤事件（V3.1.1 修复：plan/progress/assist 三处创建流 emitter 原为空实现 → 前端转圈 30s~2min 无卡片；现与文章类对齐实时推 WORKFLOW_STEP，前端 applyInitialWorkflowStepCard 提前弹「执行中」卡）
- 学习 Workflow 计划/阶段定位（V3.1.1 修复：阶段标题为主题名时用户「阶段三」= order_num 3 序号直解，标题分词仅 fallback；归一目标用中文序号防数字误撞任务标题如「3 主 3 从」；建议桥 confirm 补消息点名，多计划用户不再被迫重选计划）
- 重复任务预检（V3.1.1：SUGGEST_WRITE ADD 提案前查重 → 已存在不弹确认卡，LLM 直接告知；基类新增 isTerminalExtension 豁免扩展终态被拒后的脏执行）
- 分类器 JSON repair（V3.1.1：解析失败 repair prompt 重试一次再降级——多行消息/长任务名曾致模型把规则文字混进 JSON，点名单任务诉求静默退化普通聊天）
- 写动作四动作（UPDATE_TASK_DONE + ADD_LEARNING_TASK + UPDATE_LEARNING_TASK + UPDATE_ARTICLE_TITLE，学习/文章两域）；批量追加 / 备注 / 删除 / 重排 / 文章域第 2 个写动作（可见性切换等）留后续（V3.1/V3.3/V3.4 明确不做）
- 分类器编辑页分界（2026-09-10，编辑页上下文补强后）：分类器 prompt 多处写死 `pageType 是 article-detail`（QA / 点赞收藏 / 跳转 / 优化诉求四条规则），编辑页能否判出文章意图是**概率性**的——后端已可读（articleId 已补），但分类器可能判成普通聊天。先观察，掉链子再改（改则必跑分类器评测门）
- 编辑页写动作敞开（2026-09-10）：编辑页填了 articleId 后，「把标题改成 XX」等写动作照常走 SUGGEST_WRITE 改库值；编辑器里未保存的修改在之后保存时**会覆盖** AI 改的库值（V3.4 三明治锚定防的是并发请求，防不了这条本地覆盖路径）。概率低，登记不处理
- 编辑器页正文版本语义（2026-09-10）：编辑页 AI 读到的是**已保存版本**，编辑器未保存的修改不存在于任何地方——agent 域 `effectiveGoal` 与 QA 域正文注入两处已在 prompt 中明示该边界，但模型是否每次都向用户说明仍是概率性的
- **无指代跨页歧义（2026-09-10 记录不修）**：会话里聊完 A 文章后切到 B 文章页，问一句**没有指代词**的话（如「先看整体结构，再检查 X 那一节」）→ 决策器可能受对话历史惯性填 `anchorMode: SESSION_LAST` 读 A（按 prompt 规则无指代应为 CURRENT_PAGE）。老大判定产品上确是歧义场景（上一秒聊 A、下一秒在 B 页问无指代的话），AI 选哪篇都讲得出道理，**不修**；新会话同问法表现正常，与 V3.8 锚机制无关
- **verifier 在线样例 [3] 判定波动（2026-09-10 观察）**：固定样例「记忆偏好问答有依据」期望 SUFFICIENT，实跑出现 NEED_MORE；该样例直调验证器、不经决策器，与当次改动无关；此前全量跑为全绿 → LLM 判定波动，待观察是否需调样例或 prompt
- Agent 写动作候选交互：重名/多计划直接拒绝提示（未实现候选选择流程）
- V2.5 行为语义：咨询型诉求不触发建议卡（产品语义，非缺陷）；"明确 vs 模糊"由分类器判别（概率性，临界说法可能走任一路径，两条路都安全）
- V3.0 行为语义：needsThinking 判定是 LLM 语义判断（概率性），漏拦（判 false 但实际依赖上下文）暂无法被用户察觉（回答不带记忆）——通过 needsThinkingReason 抽样观察，触发"放宽闸门"的条件已在路线中记录；思考中刷新补拉是 3s 轮询（最多 6 次），极端场景（loop 异常不落库）占位会停驻约 18 秒后消失

## 五、验收结论

**Agent 模块 V1 → V3.13 全部完成**：只读 Loop → 建议桥 → inspection → 思考展示 → 受控写 → 第二个单 Agent（骨架抽取 + 公共管道 + 文章域建议）→ 通用思考模式（普通聊天接入 Runtime）→ 受控写扩展（第 2 个写动作）→ 评测门（固定样例集 + 一键 runner + 回归门槛）→ 受控写扩展 2（第 3 个写动作：重命名）→ 受控写跨域（第 4 个写动作：文章域改标题）→ 路由元数据收口 → 写动作执行器注册表 → 文章域可见性受控写（HIDE/PUBLISH 批次）→ 会话文章锚（跨页/跨轮指代）→ QA 跨页指代接入 → 思考展示升级（thoughtSummary 受控摘要 + 自动展开收起）→ 证据收敛门（Evidence Verifier + focus）→ 会话结论继承（跨 run 上下文交接试点）→ Plan Preview（文章域计划展示与观测，V4① 试点），每级都守住"LLM 给建议、后端做裁判、用户做确认"的安全边界。V3.1 端到端场景：明确单计划单阶段追加 → 确认 → appendTasks + 快照；同名任务 → 明确拒绝；多计划/阶段不明 → 追问或拒绝；游客 → 登录提示；刷新 → 确认卡恢复。V3.2：离线门 34 样例 + 在线门样例数据驱动化。V3.3：重命名提案 → 确认 → renameTask（done 保留）+ 快照；新名撞已有 / 同名改名 / 未知 actionType → 提案前拒绝或 FAILED；分类器改名分界在线门全绿。V3.4：文章详情页「把标题改成 XX」→ Agent 提案（提案锚 = DB 权威旧标题）→ 确认卡「修改文章标题 · 《旧》→《新》」→ stale 校验 + 旧值条件更新 → 标题变 + 快照留痕；同名改名不弹卡；非本人文章终局失败；编辑器并发改标题 → stale 拒绝不覆盖。V3.5：路由收口纯重构（文案逐字不变），主分发 + fallback 分发查同一注册表、未登记 intent warn + 显式兜底（静默雷变日志雷）。V3.6：写动作确认壳化纯重构（域逻辑迁入两执行器、markExecuted 合一），confirm 语义经 20 断言平移全绿等价。V3.7：详情页「把这篇隐藏了」→ Agent 提案（前置 PUBLISHED 从动作推导）→ 确认卡「隐藏文章 · 将文章《X》设为隐藏」→ 原子条件更新（WHERE status=expected）→ 文章隐藏 + 快照留痕；「把这篇公开」→ 恢复；草稿提隐藏/公开 → 拒绝提示去编辑器。V3.8：会话文章锚（ai_sessions 单点状态 + anchorMode 消解）打通跨页/跨轮文章指代——首页「把刚刚那篇隐藏了」→ Planner 锚放行 → 决策器 anchorMode=SESSION_LAST → 提案锚文章；站 B 详情页指 A → 提案 A 不被页面 B 抢走；删光消息联动清锚（会话隔离）；分类器 LLM articleId 不再参与定位（防幻觉绕过）；「刚刚那篇」无会话候选时 ASK_USER 澄清不默认当前页。V3.9：QA 跨页指代接入——首页/他页「刚才那篇里 X 怎么讲的」→ 分类器判文章内容问 → Planner 恒放行 → resolver 单点决议（readable 锚：公开可读 + 本人全状态，别人公开文章也能续问）→ 正文 + 来源 [1] 同源注入；详情页「刚刚那篇」指会话文章不答错当前页、无候选澄清不硬套；读权限 SQL 单点收敛、消费点零查库；resolve 补 session 归属校验。V3.10：思考展示升级——「你计划里 Redis 只到缓存基础」替代「已完成查询学习计划」的动作流水账：决策器输出 thought → 分级闸门清洗（业务词防误伤、机制痕迹一票否决）→ thoughtSummary 全链路（事件实时 + DB 落库 + 刷新恢复同源）→ 行文本跨状态稳定、失败不被动机覆盖；思考期间自动展开、正文输出自动收起、手动可再展开；三域真实 LLM smoke 确认 thought 字段要求不破坏决策 JSON；术语定位成稿（受限 ReAct 三范式 + 面试讲法，面试可直接背）。V3.11：证据收敛门——「缓存击穿那段写得怎么样」不再被 5 字预览糊弄：决策器想答 → 规则闸（目标文章没读过零 LLM 拦）→ 语义闸独立判草案（论断越证据/引用错文 → 拦回循环补查，focus 取正文片段 → 验证过才出口）；「适合发出去吗」缺用户信息 → 转问；补查一路失败到顶 → 保守收尾不硬答；顺手修掉 V3.8 遗留的跨页读错文 bug（B 页指 A 读 B）。拦截留痕（FAILED step + missingEvidence）可统计拦截率喂 V4④。V3.12：会话结论继承——「按刚刚说的优化」不再从零重分析：上轮 Agent 结论写入会话结论锚（两个写点：FINAL_ANSWER 终态 / 建议卡确认时刻）→ OPTIMIZE Workflow 创建时单点读取 → 注入优化方案 prompt 作为弱参考；未确认的建议进不了锚、串文章/越权/他人非公开一律失效、雪花 ID CAS 防旧 run 覆盖新结论、500 字截断 + 防注入模板挡二阶注入链。**V3.11 验收状态**：实施完成（P0-P6）+ 自动化验证绿（离线 52 + eval 门 56），**手测 4/4 通过（2026-09-10）**，本轮手测暴露并修复三处（focus 定位 bug / R3 规则闸 / 读权限误用归属）。**V3.12 验收状态**：实施完成 + 手测通过（回归 342 例全绿）。**编辑页页面上下文补强**（2026-09-10）：编辑已有文章的页面补 `articleId` + 按 pageType 明示「已保存版本」语义（agent/QA 两域同源），手测通过。**V3.13 Plan Preview 阶段一**（2026-09-10）：文章域首轮拆 2-4 子目标 + 展示 + 观测（**不锚定、不执行驱动**）——决策器同调用带 `plan` → 六规则零修补校验 → **单列更新**落 `plan_json` → `AGENT_PLAN(3002)` 在首个 STEP 前发（前端占位消息索引归并）→ 思考面板顶部计划块 + detail 恢复；隔离分层（永不改四项 + 阶段一专属一项）；域开关仅文章域 true。相关回归 359 例 + 离线评测门 24 例全绿，前端 vue-tsc 通过，本地 MySQL 已 ALTER。**待手测 7 项**。全量回归未跑（老大指示只跑相关测试）。下一步：V3.13 手测 → 按观测数据判断阶段二（Plan-guided ReAct）做不做 → V4 整体设计收 ③⑤⑥ + 框架 + MCP。
