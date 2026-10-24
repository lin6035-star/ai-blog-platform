# V3.9 设计：QA 跨页指代接入（会话锚 → 文章问答）

> 状态：定稿并实施完成（2026-09-08，吸收 Codex 三轮评审）。实施结果见文末「实施记录」。
> V3.8 收口后登记的后续刀之一：把会话文章锚从「文章域 Agent 单域」铺到第二条管道——普通 QA 问答。机制同构，无新数据模型。

## 零、本刀核心不变量（防自相矛盾）

```text
1. 只要分类器判出 ARTICLE_DETAIL_QA，本请求就一定走 ArticleQaTargetResolver：
   有目标 → 注入文章正文 + 来源 [1]；无目标 → 注入 promptNote 说明态（模型明确告知
   没有可指文章并引导），绝不退化成无任何提示的普通聊天。
   说明态 ≠ 普通聊：普通聊时模型面对「刚才那篇讲了什么」无任何上下文，会即兴泛答甚至
   编内容（QA 无操作性质 ≠ 无害）；说明态给模型明确边界，杜绝幻觉编内容。

2. QA 目标定位信源只有 pageContext 与 readable 锚；intent.articleId（LLM 输出）全程
   不参与（放行不参与、定位不参与）——V3.8「模型不输出 ID」安全资产延续。

3. 「候选」必须是已验证可读的文章实体，不是 ID 线索：PC/AC 都以读权限加载成功为成立
   条件（v4）。resolver 决议与文章加载合一——单次决议产出的就是本请求要读的文章，
   消费点零查库、零权限判断，不存在「决议说选了 A、加载却失败/加载到 B」的割裂。
```

## 一、动机（现状断点）

V3.8 修好了「跨页/跨轮指代」在文章域 Agent（受控写 / 分析诉求）的问题。但 **QA 问答管道没有接入**——会话里聊完一篇文章后离开详情页，再问「刚才那篇文章里缓存击穿那段是怎么讲的？」：

1. 分类器：QA 判定绑定 `pageType=article-detail` +「这篇文章」语义；非详情页判不出 QA，且「如果缺少 articleId，输出 GENERAL_CHAT」硬规则还在。
2. 即便判出 QA：Planner（170-186）无会话锚概念——缺 ID → `RETRIEVAL_NONE` → 普通聊，无任何文章上下文。
3. 详情页内同款错位：A 页聊完跳 B 页，问「刚刚那篇的观点」→ pageContext 直取 B（V3.8 在 agent 域修过，QA 域仍裸奔）。

## 二、对齐与定位（延续 V3.8 分工）

- **锚 = ai_sessions 单点状态**，QA 只读不写新列；无新数据模型。
- **分类器只判意图，不判指代、不落词表、不感知锚状态**（无 DST）。指代落哪 = 后端消解。
- **不另起 LLM 解析层**：QA 没有决策器可放 anchorMode，消解用确定性后端规则（§七），与 agent 域「决策器 anchorMode」路径分开；软澄清 promptNote 约束回答模型（可接受差异，§十）。
- **锚读语义按域拆分**：锚存储通用（谁的文章都记），读时按用途过滤——写动作/文章域 Agent 用 **owned**（只能动自己的文章），QA 用 **readable**（公开可读 + 本人全状态可读）。用户读别人的公开文章也该能续问（锚写点 PAGE_QA/PAGE_ACTION 不验归属，锚里确实会有他人公开文章）。

## 三、现状链路盘点（侦察结论）

```
classifier(消息 + pageContext)
  → Planner.decide(...)    ← QA 现状：有 articleId → CHAT + CURRENT_ARTICLE
                            ← QA 现状：无 articleId → CHAT + RETRIEVAL_NONE（无聚焦普通聊）
  → 普通 chat 分支消费 CURRENT_ARTICLE 两处：
      ① buildArticleDetailContextFromIntent(intent, pageContext, sessionId)
            resolveArticleIdFromIntent（intent.articleId 优先 → pageContext 回退）
            → 查库强制 PUBLISHED → 拼正文注入 prompt + 成功时 mark 锚(PAGE_QA)
      ② buildCurrentArticleReference(routeDecision, intent, pageContext)
            同款 resolve + 强制 PUBLISHED → ArticleRagContext（来源 [1]）
```

- 锚写点不验归属：PAGE_QA 加载当前文章成功即写（公开文章也可）；PAGE_ACTION 取 pageContext.articleId 即写（收藏/点赞别人文章也写）→ 锚里可以有他人公开文章。
- 现有 `resolve()`（owned）未校验 `session.userId == userId`（v3.8 靠调用链 getOwnedSession 保证）——本刀补上。
- 文章域 Agent 执行器读文章：getById 无 status 过滤，归属是唯一门槛（本人草稿/隐藏都能读能聊）。
- QA 加载两处强制 PUBLISHED → 本人隐藏文章（V3.7 后常态）加载失败。
- 游客流：sessionId/userId 为 null → 无锚可指。放宽分类器后游客跨页 QA → 说明态（比现状瞎聊有引导性，可接受）。

## 四、意图层：QA 判定放宽为「文章内容问」（分类器）

现状 prompt 绑定 `pageType=article-detail` +「缺少 articleId → GENERAL_CHAT」硬规则。改为：

```text
ARTICLE_DETAIL_QA = 对某篇文章的内容问答（讲什么 / 总结 / 文中某概念解释 / 追问文中观点）。

判定不绑定页面：
- pageType=article-detail 且问文章内容 → QA（详情页高频场景，维持）
- 任意页/无页面语境，消息里明确近指一篇文章（这篇文章/那篇/刚刚那篇/
  你刚才说的那篇 + 内容问）→ QA，articleId 允许为空且禁止填（后端 pageContext/锚兜底）
- 不再因缺少 articleId 降级 GENERAL_CHAT
防漂移（模型无锚信息，只能靠语义）：
- 首页概念问（「Redis 缓存是什么」「Java 怎么学」）无文章指代 → 不判 QA
- 「有没有/推荐 XX 文章」→ ARTICLE_SEARCH（找文章 ≠ 问已知文章内容）
- 带具体主题名但不是指会话中已出现文章的特征指（「Redis 缓存那篇…」）→ 不判 QA（V4 边界）
```

## 五、Planner：QA 意图恒放行文章检索模式

Planner QA 段（170-186）改为只做一件事——QA 意图 → `CHAT + RETRIEVAL_CURRENT_ARTICLE`（不查页面、不查锚、不看 intent.articleId）：

```text
if (ARTICLE_DETAIL_QA):
    return chat(intent, RETRIEVAL_CURRENT_ARTICLE,
                List.of("article_detail_qa_intent"),
                "用户询问某篇文章内容，检索模式放行，目标由 QA resolver 单点决议")
```

理由：QA 的决策形态与目标无关（都是 CHAT），Planner 预判「有没有目标」只会造成 Planner 与 resolver 的割裂（v2 曾出现「Planner 说无锚回落普通聊、执行层又注入说明态」的矛盾）。目标有无全部下放 resolver；`RETRIEVAL_CURRENT_ARTICLE` 只是触发 QA 消费点的开关，不代表真有目标。agent 域 Planner 要查锚是因为要区分 AGENT vs CTA 两种 action 形态，QA 域不需要。观测性：锚命中由 resolver 单一决议点出 debug 日志，decision trace 的 ruleHit 保留意图级信息。

## 六、读权限单点：ArticleSessionAnchorService 扩展

读权限口径（本刀唯一的 SQL 权限条件，定义在此处单点，全链路复用）：

```text
loadReadable(articleId, userId) → Articles | null（v4：新增，读权限加载单点）
    SQL: WHERE id = ? AND (status = PUBLISHED OR author_id = ?)
    - 本人全状态可读（对齐文章域 Agent「归属是唯一门槛」）；他人只读 PUBLISHED（防隐私洞）
    - userId 为 null（游客）→ 退回 status = PUBLISHED
```

锚读方法（都校验 `session.userId == userId`，v3 补——service 不依赖调用方纪律，防他人会话锚参与本用户决议）：

```text
resolve(sessionId, userId)            —— owned，现语义 + 补 session 归属校验（文章域 Agent 用）
    条件：session 存在且归属 user + 文章存在 + 作者 = user

resolveReadable(sessionId, userId)    —— readable，新增（QA 用）
    条件：session 存在且归属 user → loadReadable(session.lastArticleId, userId)
    返回：Articles 实体（含正文，QA 决议要全文；v4 起不再返回 ArticleAnchor 摘要形态，
         因为唯一使用方 resolver 要全文，查询一次到位）| null
```

## 七、决议层：ArticleQaTargetResolver（本刀核心）

新增无状态组件（注入锚 service）。**职责 = 候选决议 + 可读加载合一**（v4）：候选成立 = 读权限加载成功，手里的就是已验证可读的文章实体。单次请求只 resolve 一次，结果分发两个消费点——正文与来源 [1] 永远同一篇文章，且消费点零查库、零权限判断（不存在「决议 A 加载 B」或「决议选 PC 却加载失败」的窗口）。

**候选（v4：先加载后成候选，不是 ID 非空就算）**：

```text
PC = pageContext.articleId 可解析
      ? anchorService.loadReadable(articleId, userId)   ← 一次查询即校验即加载
      : null
    不可读（脏 ID / 他人隐藏 / 已删除）→ 加载 null → 无 PC，按矩阵走 AC 或说明态
AC = anchorService.resolveReadable(sessionId, userId)   ← 会话归属 + 可读加载，一次到位
（intent.articleId 不参与，见不变量 2）
```

**措辞信号（闭合词集，只判方向不判文章）**：

```text
timeRecent    = 含 刚刚|刚才
S1 强当前页   = 非 timeRecent ∧ 含 这篇|这篇文章|当前文章|它
S2 强会话近指 = timeRecent ∧ 含 文章|那篇|这篇|它    （近指须与文章指代同现，
              防「刚才说的观点再展开」对话内容追问误伤）
S3 弱指代     = 非 timeRecent ∧ 含 那篇|那篇文章（可能是当前页口语也可能是会话）
S0 无指代词   = 以上皆否（详情页大多数问句「缓存击穿是什么意思」——语境隐含当前页）
```

**决议矩阵**：

```text
                        PC 有                           PC 无
AC 有：
  S1 强当前页      → PC                              → AC（唯一候选）
  S2 强会话近指    → AC                              → AC
  S3 弱指代        → 追问态（双候选都真，不猜）       → AC（唯一候选）
  S0 无指代词      → PC                              → AC（唯一候选）
AC 无：
  S1 强当前页      → PC                              → 说明态（分类器不该判出，防御）
  S2 强会话近指    → 说明态「不假设是当前页这篇」       → 说明态
                   （V3.8 老大实测场景：新会话详情页
                    说"刚刚那篇"→ 澄清，不许硬套当前页）
  S3 弱指代        → PC（唯一候选——"那篇"无会话对象   → 说明态
                    时大概率是当前页口语，不打断）
  S0 无指代词      → PC                              → 说明态
```

**输出形态（v4：QaTarget 直接携带已验证文章，不是 articleId）**：

```text
QaTarget.target(Articles article) —— 已验证可读（含全文），消费点直接拼装，不再查库。
                                     加载成功路径由消费点 mark 锚(PAGE_QA)（幂等同 id）
QaTarget.promptNote(String note)  —— 追问态或说明态文案（标题在 resolver 内查好填入）
  追问态：「用户用"那篇"指代一篇文章，但当前可能指：当前页《B》或本会话最近聊过的《A》。
           先用一句话向用户确认指哪一篇，确认前不要回答文章内容。」
  说明态(S2)：「用户用"刚刚/刚才"指代本会话早前聊过的文章，但本会话没有可读文章记录；
               即使当前页是《B》也不要把 B 当"刚刚那篇"。如实说明并请用户打开目标文章
               详情页后再问。」
  说明态(其余)：「用户询问某篇文章内容，但既没有当前页面文章、本会话也没有最近聊过的
                 文章记录；说明情况并请用户打开文章详情页后再问。」
```

规则边界：

- S3 追问只发生在**双候选都存在**时；唯一候选直接使用不打断（多数「那篇」口语指当前页，此时无锚 → PC）。
- 「它」归 S1（对齐 V3.8 agent 决策器「这篇/它/当前文章」→ CURRENT_PAGE）。
- 词集只回答「消息指哪个方向的候选」这一个三值问题，不做文章定位、不比标题——QA 无决策器下唯一不引入 LLM 层的确定性消解。
- 决议与加载合一后，跨请求窗口（锚被并发改写）仍在——每个请求独立决议本就该拿当时最新状态，非本刀目标。

## 八、消费点接线（AiMessageServiceImpl）

```text
QaTarget qaTarget = articleQaTargetResolver.resolve(message, pageContext, sessionId, userId)  ← 每请求一次
  ① buildArticleDetailContextFromTarget(qaTarget)：
       target(article) → 拼「文章内容」注入 prompt（标题/摘要/正文 limitText，含来源编号指引）+ mark(PAGE_QA)
       promptNote(note) → 直接作为注入文案返回（不加载不 mark）
  ② buildCurrentArticleReferenceFromTarget(qaTarget)：
       target(article) → ArticleRagContext（来源 [1] 卡片数据）
       promptNote(note) → null（ragContexts 空）
extraPromptContext 恒有值（正文或 promptNote 二选一）——QA 永不无提示普通聊
```

## 九、改动清单

| 位置 | 改动 |
|---|---|
| AiIntentClassifierImpl | QA 段放宽「文章内容问」：去 pageType 绑定 + 删「缺 articleId → GENERAL_CHAT」+ 分界示例 + 跨页禁填 ID |
| AgentPlannerSupport | QA 段（170-186）→ 恒放行 CHAT + CURRENT_ARTICLE（不查页面/锚/intent.articleId） |
| ArticleSessionAnchorService | +loadReadable（读权限 SQL 单点，游客退 PUBLISHED）+ resolveReadable（会话归属 + loadReadable，返回 Articles）；resolve 补 session.userId 校验 |
| ArticleQaTargetResolver（新） | §七：候选（先加载后成候选）+ 措辞信号 → QaTarget.target(article) / promptNote(note) |
| AiMessageServiceImpl | 每请求 resolve 一次 → 两消费点改 FromTarget（零查库）+ mark 收口 + 文案场景化 |
| 锚 / DB / 前端 | 零改动（无新列、无 ALTER、无前端） |

## 十、非目标 / 边界声明

- 标题/主题特征指（「Redis 缓存那篇」「第 3 篇」）不做（V3.8 已声明 V4）；分类器不判 QA，样例防升级。
- 远指 / 跨 session / 人称指代 → V4。
- 带主题词与锚不符的校验（「刚才那篇 Redis 文章」但锚是 Java 文章）→ 不做标题比对（V4）。
- OPTIMIZE_ARTICLE_WORKFLOW 跨页接入 → 下一刀（本刀只收 QA 一条管道）。
- QA 澄清是「软」的（promptNote 约束回答模型先确认/先说明），不同于 agent 域 ASK_USER 硬终态——QA 无决策器，为此引入 LLM 层不值，promptNote 强措辞兜底。
- 本刀不改变非 QA 意图行为：GENERAL_CHAT 等普通聊照旧。

## 十一、测试与评测

- **ArticleSessionAnchorServiceTests（+4~5）**：resolveReadable——本人公开/本人隐藏 → 可读；他人公开 → 可读；他人隐藏 / 文章不存在 → null；session.userId ≠ userId → resolve 与 resolveReadable 均 null（补验）；resolve（owned）回归不变。
- **ArticleQaTargetResolverTests（新 ~14）**，覆盖决议矩阵关键行 + v4 候选语义：
  详情页「刚刚那篇」+ AC → AC（不取 PC）；详情页「刚刚那篇」+ 无 AC → 说明态且不含 PC（V3.8 实测场景回归）；
  首页 + AC → AC；首页 + 无 AC → 说明态；详情页「这篇」+ AC → PC；详情页无指代词 + AC → PC；
  详情页「那篇」+ AC → 追问态；详情页「那篇」+ 无 AC → PC；
  详情页「刚才说的观点再展开」+ AC → PC（时间近指须与文章指代同现）；
  他人公开文章锚 + QA → 可读；游客 → 无锚路径；intent.articleId 幻觉填值 → 不参与；
  **PC 不可读三连（v4）：pageContext 脏 ID / 他人隐藏文章 ID / 已删除 ID → 无 PC**：有 AC 走 AC、无 AC 走说明态；
  TARGET 返回实体含正文（消费点拼装断言）。
- **AgentPlannerSupportTests（+1~2）**：QA 意图（无论页面/锚）→ 恒 CURRENT_ARTICLE；旧 QA 用例回归。
- **分类器评测样例（在线门 16 → ~22）**：
  首页「你刚才介绍的那篇文章，缓存击穿那段再讲讲」→ ARTICLE_DETAIL_QA / 首页「刚才那篇讲了什么」→ ARTICLE_DETAIL_QA /
  首页「Redis 缓存是什么」→ GENERAL_CHAT（概念问不升级）/ 首页「Redis 缓存那篇讲了什么」→ 非 QA（特征指）/
  首页「有没有 Redis 缓存相关的文章」→ ARTICLE_SEARCH（找 vs 问分界）/ 详情页「总结一下刚刚那篇」→ ARTICLE_DETAIL_QA
- **回归门槛**：改分类器 QA 段 / 意图分界 → 必跑在线门；决议改动 → resolver + Planner 单测全绿即等价。

## 十二、实施顺序

1. ArticleSessionAnchorService：resolve 补归属校验 + loadReadable + resolveReadable + 锚单测
2. ArticleQaTargetResolver + 单测（纯新代码先行闭环）
3. AgentPlannerSupport QA 段恒放行 + Planner 单测
4. AiMessageServiceImpl：单次 resolve + 两消费点改 FromTarget（删旧 resolve/加载逻辑）+ 文案
5. 分类器 QA 段 prompt + 样例 JSON
6. 手工前后端测试点：他人公开文章详情页问几句 → 回首页问「那篇…」→ 带来源 [1] 续答；自己文章隐藏后继续问 → 可读；详情页新会话问「刚刚那篇」→ 澄清不硬套当前页；详情页 + 锚都在问「那篇」→ 澄清带双标题；删光消息再问 → 说明态；游客跨页问 → 说明态引导；详情页开着时文章被删/藏（模拟脏 pageContext）→ 不割裂

## 十三、实施记录（2026-09-08，全部按 v4 落地）

- 新增：`ArticleQaTargetResolver`（ai/qa 包）+ `QaTarget(target/promptNote)`；`ArticleSessionAnchorService.loadReadable / resolveReadable`；resolve 补 session.userId 校验
- 改动：AgentPlannerSupport QA 段恒放行（不查页面/锚/intent.articleId）；AiMessageServiceImpl 三个消费路径（登录主分发 / workflow 回退 / 游客）各自单次 resolve → `buildArticleDetailContextFromQaTarget` + `buildCurrentArticleReferenceFromQaTarget`（删旧两方法 + resolveArticleIdFromIntent）；分类器 QA 段放宽（跨页近指 + 删缺 ID 降级硬规则 + 三区分线）；正文注入文案「当前文章内容」→「用户询问的文章内容」（锚场景避免误导模型指认当前页）
- **实施中修正 1 处（游客会话近指豁免）**：resolver 对 sessionId == null 把信号降级 S0_NONE——游客没有「会话最近文章」概念，详情页问「刚才那篇」（大概率当前页口语）不应触发 S2 澄清规则误伤；登录用户行为不受影响。设计稿只声明了游客无锚，未覆盖此误伤面，实施期补上
- 测试：ArticleSessionAnchorServiceTests 8→19（readable 三态 + 会话归属补验 + owned 回归）；ArticleQaTargetResolverTests 新 18（词集边界 2 + 矩阵 15 + 游客豁免 1）；AgentPlannerSupportTests 43→45（QA 恒放行 ×2）；ArticleAgentRuntimeTests 23 / LearningAgentRuntimeTests 21 / GeneralAgentRuntimeTests 10 零回归；planner 样例断言 reason 更新（「当前文章」→「QA resolver」，QA 恒放行后决策语义变化）
- 评测样例：分类器 16 → 22（+6 条 QA 分界：首页近指 ×2 / 概念问不升级 / 特征指不判 / SEARCH 分界 / 详情页近指）；planner 样例断言 1 处更新；离线门（AgentPlannerEvaluation + LearningPlanMatch 共 34 断言）全绿；**在线门待补跑**（`-Dgroups=eval -DrunRealLlm=true`，样例 22）
- 手工验收待老大前后端测试
