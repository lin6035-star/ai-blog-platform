# V3.8 会话文章锚：跨页 / 跨轮文章指代（含首页受控写入口）

> 状态：**定稿并实施完成（2026-09-07）**，老大前后端测试通过。四轮对齐（Codex ×3 + 讨论修正）后实施；实施期实测又修正 4 处（见"实施中修正"），均已回归验证。
> 相关：[[agent-module-acceptance-checklist.md]] / [[agent-v3-4-article-write-action-design.md]]（本刀扩宽其"范围锁详情页"）/ [[agent-v3-7-article-visibility-write-design.md]]

## Context

实测两个体验断裂，根因同源：

1. **场景 A（首页指代僵死）**：详情页对《Java 后端面试突围…》改标题成功后，回首页说"帮我把刚刚那篇文章隐藏了" → Planner `hasArticleId` 不过 → 降级 CTA「文章 Agent 缺少当前文章上下文」。文章明明刚在本会话被讨论过，全链路却没有一环能提供它。
2. **场景 B（错位指代）**：站在另一篇详情页 B 说"刚刚那篇"（指 A）→ pageContext 的 articleId=B 被直接当目标 → 提案针对 B。比降级更危险：不看确认卡确认 = 隐藏错文章。

**根因**：文章 ID 唯一来源 = **当轮 pageContext**。分类器输入只有当前消息 + pageContext（无会话历史，prompt 还写死"缺 articleId → GENERAL_CHAT"）；决策器只吃 goal + 本 run 观察（跨轮不注入）；Runtime 提案端 articleId 只认 pageContext。V3.4 checklist 明记"不做任意页点名我的文章"——本刀不开"任意页点名"，只开**"会话内最后讨论过的那篇"**这一个窄口。

**观感根源（老大 2026-09-07）**：用户感知"Agent 笨 + 思考过程是代码写好的假思考"。拆开：思考面板 = 固定 step 脚手架（代码保证每轮都决策、格式固定），**步内决策内容**才由模型实时生成。笨感主因是**上下文断供**（每轮只给 goal + 当轮观察）+ **零观察拒绝规则**把一句话诉求拉成"先 QUERY → 再提案"多步循环。本刀修上下文断供；脚手架 vs 原生推理（V4+ 议题）不在本刀。

## 对齐结论（三轮演进，2026-09-07）

**Codex v1（分类器四值 + 从事件痕迹派生锚）** → 讨论修正：
- 分类器每轮必跑且无锚状态输入，语义判断放它是"低信息处做高风险判断"；文章域决策器有 QUERY_ARTICLE 观察反馈可自纠 → 消解位置下沉 runtime
- 事件痕迹（消息/run）≠ 状态，锚要单点真相

**Codex v2（ai_sessions 单点锚 + 模型输出 anchorMode + 会话级解析器）** → 修正：
- 模型只输出 anchorMode 不输出 articleId → **吸收**（文章域模型从此永不输出 ID，幻觉面归零）
- 落点 ai_sessions → **吸收**（状态语义 > 独立表迁移便利；域多了再抽表，YAGNI）
- 不另起 LLM 解析器（两个 LLM 各消解一遍 + 冲突裁决 = 叠床架屋）→ anchorMode 并入**决策器首步 JSON**，后端只做 mode→唯一锚的确定性映射 + 缺失/非法时安全回落

**Codex v3（收敛终稿）** → 全盘吸收，无异议：
- Planner 只负责"能不能进文章域 + 给哪些候选"，不最终定死目标；mode 消解放 runtime 首步
- 锚**更新条件收紧**：不是任何提到文章的消息都更新——仅 (a) 真进入文章域并完成一次定位 (b) 详情页且用户明确围绕该文章操作；防普通聊天污染（修正 v2 设计稿"详情页一切发言都写"的过宽口径）
- 锚字段分层：**entity_id 是本体，title/source/updated_at 只是快照**，定位永远走 id + 查库，标题不当锚本体（改名会漂）

## 关键设计

### 1. 会话级文章锚（ai_sessions 单点状态）

```text
锚 = 本 session 最后一次"真实进入文章上下文"的文章（覆盖式，不设 TTL）
定位以 last_article_id 为本体；title 快照只作候选注入/轻量展示参考，权威标题读锚时查库现取
```

**更新条件（严格，二选一命中才写）**：
- 文章域 run 完成一次定位（QUERY_ARTICLE 归属校验通过 → run 创建/定位时写）
- 非 run 路径：分发判定为**明确围绕当前文章**的意图（intent ∈ {ARTICLE_DETAIL_QA, ARTICLE_ACTION} 且 pageContext.articleId 非空）→ 校验该文章存在后写。
  **QA 保留在写点集**：QA（"这篇文章讲了什么"）是用户在认真读/问这篇的最强围绕信号，问完切首页说"刚那篇"应当能指到——砍掉 QA 会让最常出现的"读后想操作"场景断链。ACTION（点赞/收藏/关注）同理。
  写点口径 = "围绕这篇文章"（操作 + 深读询问），非"提到文章"

**不更新**：GENERAL_CHAT 闲聊（即使在详情页发）、ARTICLE_SEARCH（结果不绑定单文）、NAVIGATE（纯浏览）、run 定位失败/归属失败（没"进入"成功）。

**读点**（Planner 放行判断 / Runtime mode 归约时需要才查）：ai_sessions 行内字段，O(1)。

**字段语义**：`last_article_id`（本体，BigInt）/ `last_article_title`（快照，展示与注入用，允许过期）/ `last_article_source`（写点：AGENT_RUN / PAGE_QA / PAGE_ACTION，审计）/ `last_article_updated_at`（写入时刻，排查用）。

### 2. Planner 归约：放行 + 注入候选，不定目标

`decideArticleAgent(intent, pageContext, session)`（补 session 入参）改为：

```text
intent = ARTICLE_AGENT 且分类器建议 AGENT：
  pageContext.articleId 非空 → 放行（候选 1 = 当前页文章）
  pageContext 无 → 查 ai_sessions.last_article_id：
     锚存在且查库存在 + 归属本人 → 放行（候选 = 锚文章）
     无锚 → CTA「本次会话还没聊过具体文章：打开目标文章详情页后再对我说"把这篇…"」
  （放行 ≠ 选目标：站 B 页说"刚刚那篇"时选 A 还是 B 由 runtime 首步 anchorMode 消解）
```

- 分类器零新字段；只把两处「缺 articleId → GENERAL_CHAT」硬规则改写（§6）
- ARTICLE_DETAIL_QA / OPTIMIZE_WORKFLOW 等非 Agent 路径不动

### 3. Runtime：首步 anchorMode 消解（本刀核心）

- **goal 候选注入**（effectiveGoal）：「【页面上下文】当前文章《B》(id=B)」+「【会话最近讨论文章】《A》(id=A)」去重注入（读锚时查库刷新权威标题，杜绝改名后快照漂）；无 pageContext 或无锚时只注存在的候选并显式说明哪个缺失
- **决策器首步 JSON 带 anchorMode 字段**（可选，缺省不猜）：
  ```text
  CURRENT_PAGE → 用户指当前页这篇（"这篇/它/当前文章"）；pageContext 无 → 非法
  SESSION_LAST → 用户指会话最近聊过的那篇（"刚刚/刚才/那篇"）；会话无锚 → 非法
  ASK_USER    → 指代不清/两候选冲突 → 终态追问（文案带候选标题："你是指《A》还是《B》？"）
  ```
  prompt 写死：两候选都存在时二选一；唯一候选时可省略 mode（runtime 用唯一候选）；锚/页缺失时对应 mode 非法；**不输出候选外的任何文章**；mode 非法 → FAILED step + observation 循环继续（现有拒绝模式复用）
- **runtime mode → 唯一锚确定性映射**：mode 合法且候选存在 → 目标文章 ID（查库存在 + 归属校验，不过 FAILED/终局失败语义与现状一致）；目标写入 run（contextJson 或 run 内存态），后续 QUERY_ARTICLE 直接用目标
- 首步后观察已锁定唯一文章，后续步（SUGGEST_WRITE 等）沿用 run 目标，不重复输出 mode

### 4. 提案端：proposal.articleId = run 定位目标（不再直取 pageContext）

- `suggestWriteArticle`：目标来源从"pageContext.articleId"改为"run 定位目标"（首步 anchorMode 解析产物）；查库存在 + 归属 + V3.7 前置推导校验全部不变
- 顺带简化：SUGGEST_WRITE 决策不再需要 QUERY_ARTICLE 先验归属（首步定位已验）——**但零观察规则保留**（观察列表含 QUERY_ARTICLE 结果，语义不变：模型看到目标文章结构后才提案，符合现有决策链）
- confirm → 执行器链路（V3.6 壳 + V3.7 可见性）零改动

### 5. 执行器 clue 简化（mode 化的红利）

QUERY_ARTICLE 不再需要"pageContext 优先 or LLM 摘录"双线索判断——目标由 runtime 权威解析后直接传入执行器，clue 逻辑删除，白名单校验不必要（模型不输出 ID）。

### 6. 分类器改动（最小：纯意图判断，不落词表、不判指代）

两处硬规则（OPTIMIZE 段 / ARTICLE_AGENT 段「如果缺少 articleId，输出 GENERAL_CHAT」）改写为（v4，Codex 去词表修正——v3 仍残留"这篇/那篇/刚刚"词触发，违反"分类器只判意图"分工）：

```text
分类器只判"是不是文章操作诉求"，不判指代、不落词表、不感知候选与锚状态：
- 用户话里有明确文章操作动词（改标题/改名/隐藏/公开/取消隐藏/优化/重写等）→ 输出对应意图
  （ARTICLE_AGENT / OPTIMIZE_ARTICLE_WORKFLOW），articleId 允许为空——无页面上下文取不到就不填，不许编
- 用户话里没有文章操作动词 → 维持原判（GENERAL_CHAT 等），不因"看起来像在说文章"升级
- 指代（这篇/那篇/刚刚/它）如何落 = runtime 首步 anchorMode 的职责，分类器不参与
```

**行为变化说明（接受）**：去掉"无 articleId → GENERAL_CHAT"打回后，远指（"把我 Redis 那篇隐藏了"，无 pageContext）也会判 ARTICLE_AGENT → Planner 查锚：无锚 → CTA（引导打开详情页）；有锚但候选对不上用户话 → runtime 首步 ASK_USER。即远指从"静默普通聊（答非所问）"变成"显式引导/追问"——一致性更好，"不猜"原则不变；列表/搜索式定位仍不做（V3.4 登记项）。

**QA 段保留**（ARTICLE_DETAIL_QA 缺 articleId → GENERAL_CHAT 不动）：QA 无操作性质且不走 agent 链路，无 pageContext 的 QA 降级普通聊无害；QA 跨页指代 = 非目标（与 OPTIMIZE 同机制，见"非目标"）。

### 7. 数据模型

```sql
ALTER TABLE ai_sessions
  ADD COLUMN last_article_id        BIGINT       NULL COMMENT '会话文章锚：最后一次真实进入文章上下文的文章（本体）',
  ADD COLUMN last_article_title     VARCHAR(255) NULL COMMENT '标题快照（候选注入/展示参考；权威查库现取，允许过期）',
  ADD COLUMN last_article_source    VARCHAR(32)  NULL COMMENT '写点：AGENT_RUN / PAGE_QA / PAGE_ACTION',
  ADD COLUMN last_article_updated_at DATETIME     NULL COMMENT '锚写入时刻';
```

存量部署机需手工 ALTER（提醒老大）；同步改 `db/init.sql`。不加 ai_messages / ai_agent_runs 列（事件痕迹 pageContext JSON 已在消息表，审计可随时查，不建冗余）。

### 8. 锚写入接线（写点实现落位）

- 文章域 run：定位成功（QUERY_ARTICLE 校验通过）时更新锚——AbstractAgentRuntime 领域钩子 or ArticleAgentRuntime 定位后调用 AnchorService；run 终态若实际处理文章不同（场景 B 站 B 指 A：首步 mode=SESSION_LAST → 锚 A，本就一致；CURRENT_PAGE 场景 = 当前页文章）锚随定位即定，无需终态回写
- 非 run 路径（QA / ACTION）：AiMessageServiceImpl 分发确定 intent ∈ {ARTICLE_DETAIL_QA, ARTICLE_ACTION} 且 pageContext.articleId 非空 → 校验存在后更新锚（小事务，失败不影响主链路——锚是派生状态）
- AnchorService（新，收口读写）：upsert(sessionId, article) / read(sessionId) / 读时查库刷新权威标题

## 改动清单（实施结果）

| 文件 | 变更 |
|---|---|
| ArticleSessionAnchorService（新） | 锚 mark（AGENT_RUN/PAGE_QA/PAGE_ACTION 写点，权威标题查库现取）/ resolve（存在 + 归属 + 权威标题，失效 null） |
| AiSessions 实体 + db/init.sql + 本地库 | +last_article_id/title/source/updated_at 4 列（本地 ALTER 已执行） |
| AiAgentRun 实体 | +@TableField(exist=false) targetArticleId（run 决议目标瞬态，不落库） |
| AbstractAgentRuntime | +3 领域钩子（resolveStepTarget / prepareStepDecision / onStepSucceeded）；effectiveGoal/resolveSuggestionArticleId 签名带 userId/sessionId/run；FINAL_ANSWER answer↔message 键兼容 |
| ArticleAgentRuntime | goal 双候选注入（查库权威标题 + 显式"无"标注）；anchorMode 决议（SESSION_LAST / CURRENT_PAGE / 页面无文章自动回退锚）；prepare 注入决议目标；suggestWriteArticle 改用 run 决议目标；QUERY 成功写锚 |
| AgentPlannerSupport | decideArticleAgent：文章域裁决改用 hasPageArticle（只信 pageContext，不信分类器 LLM articleId）+ 会话锚放行 + 无锚场景化 CTA |
| LlmAgentStepDecider | 决策 JSON 顶层 anchorMode 并入 input（record 零改动，学习/通用域零感知） |
| ArticleAgentStepDecider | anchorMode 字段规则 + 指代判定语义（"这篇"→当前页 / "刚刚那篇"→会话 / 会话无候选时 ASK_USER 不默认当前页）+ 禁填 ID |
| AiIntentClassifierImpl | ARTICLE_AGENT 段：缺 articleId 不再一律 GENERAL_CHAT（明确操作动词 → 意图照出，后端锚兜底）；OPTIMIZE 段保持原样（非目标） |
| AiMessageServiceImpl | QA 加载成功写锚（PAGE_QA）；ACTION 分发写锚（PAGE_ACTION）；deleteMessage 会话消息删光联动清锚 |
| classifier-evaluation-cases.json | 14 → 16（首页近指隐藏 → ARTICLE_AGENT / 远指泛指不升级） |
| 测试 | +ArticleSessionAnchorServiceTests(8)；ArticleAgentRuntimeTests 20→23（首页锚提案 / 站 B 指 A / 缺省当前页回归）；AgentPlannerSupportTests 42→43（会话锚放行）；AgentPlannerEvaluationTests 构造适配 |
| 前端 AiAssistant.vue | pendingNewSession：点"创建会话"后收/开面板不被自动恢复拉回旧会话 |

注：执行器零改动——决议目标经 prepareStepDecision 并入 QUERY_ARTICLE input（后端注入），执行器原 clue 链（pageContext → LLM 摘录 → 报缺）作为决议失败时的回退保留。

## 实施中修正（实测驱动，2026-09-07）

1. **FINAL_ANSWER 键兼容**：实测模型偶发把正文写进 input.message（而非 answer）→ 内容被吞成"已为你分析这篇文章"兜底。answer/message 双向兼容（ASK_USER question 同款防呆）。
2. **删光消息清锚**：deleteMessage 只删消息行，会话锚仍在 → "删光消息 AI 还记着"（老大实测困惑）。消息数归零 → 锚 4 列清空，对齐"清空对话 = 上下文归零"心智。
3. **前端创建会话状态被吞**：toggle 打开面板自动恢复最近会话，覆盖 createSession 置 null → 老大以为的新会话实际还在旧会话（查库实锤 session_id 未变）。pendingNewSession 标记修复。
4. **决策器指代规则补强**：老大故意测试"详情页 + 新会话 + '刚刚那篇'"——系统把当前页硬套成"刚刚那篇"弹提案。补规则："刚刚那篇"时间近指指会话历史；会话标注无候选时 ASK_USER 澄清（带当前页标题），不许默认当前页。

## 验证结果

1. 单测：ArticleSessionAnchorServiceTests 8/8、ArticleAgentRuntimeTests 23/23、AgentPlannerSupportTests 43/43、ArticleAgentStepDeciderTests 6、LlmAgentStepDeciderTests 9、AgentWriteActionServiceTests 23、LearningAgentRuntimeTests 21 零回归、GeneralAgentRuntimeTests 10、AgentPlannerEvaluationTests 1/1——全绿
2. 分类器评测样例 16（在线门待 `-DrunRealLlm=true` 补跑）
3. 老大前后端手工测试通过（详情页指代 / 删光消息后指代 / 新会话指代 / QA·ACTION 后回首页指代 / 锚提案链路）
4. 本地库 ALTER 已执行；部署机需同步 ALTER（见风险 6）

## 风险与对策

1. **安全边界刻意扩宽**（首页可对锚文章发写提案）：三层防护——锚写点受控（两类事件 + 归属校验）+ mode 映射后查库验权 + 确认卡带《标题》人闸。误指 = 用户拒卡，不产生实际错误写。V3.4"模型永不输出任意文章 ID"资产升级为"文章域模型不输出任何 ID"。
2. 锚被普通聊天污染 → 更新条件严格化（§1 二选一 + 意图集白名单），AnchorServiceTests 覆盖闲聊不命中。
3. 标题快照漂（改名后）→ 快照仅注入参考，读锚/定位时查库现取权威标题。
4. mode 误判（该 CURRENT_PAGE 判成 SESSION_LAST 等）→ 观察反馈自纠（QUERY 结果标题与用户话对不上）+ 确认卡兜底；评测样例覆盖三态 + 非法 mode 回落。
5. 分类器硬规则改写漂移 → 离线 boundary/regression 样例 + 在线门必跑。
6. 存量库 ALTER → 部署清单同步（老大）。

## 通用性与形态上限

- 锚 = DST（对话状态跟踪）槽位的最小形态，**域单槽放会话表**。学习域同款断点（"刚刚那个任务勾上"）将来：ai_sessions +last_learning_* 列（或域多时抽 referent 表）+ 学习 run 定位写点 + 决策器同款 mode——表结构演进路径清晰，本刀不做
- 单槽只跟"最近一次真实进入的上下文"，三类指代是形态硬上限，属 V4 方向（全历史/记忆/工具）：
  - **跨级指代**：聊 A → 聊 B → 再指 A（"更早那篇"）
  - **人称指代**："刚刚那个作者"——人靠对话内容出现，无页面/操作写点可跟
  - **远指/特征指**："我 Redis 那篇"——需列表/搜索工具（V3.4 登记不做项）
- 产品形态关系：step 循环是教学脚手架；观感"笨"= 上下文断供（本刀修）+ 固定多步循环（自由推理 vs 脚手架 = V4+ 议题）

## 非目标（边界声明）

- 远指/特征指、跨 session 指代、跨级指代、人称指代 → 不做（V4 方向）
- **OPTIMIZE_ARTICLE_WORKFLOW / QA 的跨页指代接入**：机制已同构（Planner 锚兜底 + 候选注入），但工作流创建链路与 QA 检索链路各有自己的定位入口，场景接入按后续刀扩展——本刀只收 ARTICLE_AGENT（agent 受控写/分析诉求）链路，避免一刀牵三条管道的回归面
- 分类器 DST 化（锚状态进分类器 prompt）→ 每轮成本 + 评测面扩大，收益被 runtime mode 消解覆盖
- 最近几轮全文历史注入 → 候选标题注入已够；需要全文是 V4（"解析器输入含最近几轮消息"被否：不另起 LLM 层）
- ai_messages / ai_agent_runs 加列做审计 → 不做（JSON 已在，无冗余）
