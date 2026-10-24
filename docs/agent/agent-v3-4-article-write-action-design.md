# V3.4 受控写跨域：文章域第 1 个写动作（UPDATE_ARTICLE_TITLE 改自己文章标题）

> 状态：**定稿并实施完成（2026-09-06）**。与 Codex 两轮对齐（方向 + 并发防护语义收紧）后实施；全部单测/离线门/在线门 12/12 绿。
> 相关：[[agent-module-acceptance-checklist.md]] / [[agent-eval-gate.md]] / [[agent-v3-1-controlled-write-add-task-design.md]]

## Context

受控写已 3 动作（V2.4 勾选 / V3.1 追加 / V3.3 重命名），全部在学习域。第 3 个动作证明「actionType 抽象扛得住动作变多」，V3.4 证明「抽象不绑学习域」——文章域第 1 个受控写动作，跨域复用同一套链路：SUGGEST_WRITE 提案 → 确认卡 → confirm → Redis 锁 + Agent Run 状态 CAS + before/after 快照。

### 调研结论（决定可行性的三个事实）

1. **通用骨架真的通用**：AbstractAgentRuntime 的 SUGGEST_WRITE 终态通道（isTerminalExtension/handleExtraTerminalAction 钩子）、WAITING_WRITE_CONFIRM/CONFIRMING 状态机、WorkflowActionLock + 状态 CAS、context_json 留痕、HTTP 端点、前端确认卡壳——全部不绑学习域。学习域专有物集中在：LearningAgentRuntime 提案段、AgentWriteProposal 字段/常量、AgentWriteActionService 的 execute*/定位器（类名通用、内容当时 100% 学习域）。
2. **ArticleAgentRuntime 是「待接线」状态**：9 个 abstract 钩子 + 3 领域钩子全实现；受控写两个终态钩子留白；resolveSuggestionArticleId 已埋。原护栏测试断言「文章域禁 SUGGEST_WRITE」需改向。
3. **文章定位唯一路径 = PageContext.articleId**（前端 article-detail 上报，后端仅当线索、执行器查库验归属）；文章域无「我的文章列表」Agent 工具。

## 定稿语义（老大 + Codex 对齐记录）

1. **动作 = 改自己文章标题**（UPDATE_ARTICLE_TITLE）：与 V3.3 RENAME 同构（旧标题摘录 + newTitle 用户原话 + 同名拒绝 + 缺 newTitle 拒绝）；跨域验证力度最强（把 V3.3 安全语义原样跨域复跑）
2. **范围 = 仅文章详情页，写死**：无 PageContext.articleId 不进文章写动作；不做「任意页点名我的文章」（需新增列表工具，属另一大件）
3. **意图 = 复用 ARTICLE_AGENT 只调分界**（不新增 ARTICLE_WRITE_AGENT）：分界判据 = 「用户原话给出完整新标题」→ ARTICLE_AGENT（受控写）；泛泛优化（把标题改短）→ OPTIMIZE_ARTICLE_WORKFLOW 不动
4. **proposal 最小侵入 +2 字段**（articleId/articleTitle，newTitle 复用 V3.3）：字段膨胀记录为 V3.5 触发证据，本版不重构
5. **并发防护 = 三明治锚定（Codex 两轮修正的核心）**：
   - Articles 表**无 @Version**——Agent Run 状态 CAS 只防双击确认
   - **proposal.articleTitle = 提案时刻 DB 权威旧标题（锚）**；confirm 时查的当前标题**只做 stale 校验与 before 快照，绝不充当 expectedOldTitle**（自证式 CAS 防不了主窗口：提案后编辑器改标题）
   - confirm stale 校验：currentTitle != proposal.articleTitle → 拒绝「文章标题已变化，请重新发起修改」
   - service 层旧值条件更新 `WHERE title = expectedOldTitle(=proposal.articleTitle)` 收口微秒窗口，0 行同文案
   - 文档措辞严格区分三层：Agent Run 状态 CAS / stale 提案校验 / 标题旧值条件更新

## 关键设计

### 1. 提案字段（最小侵入）

AgentWriteProposal 8 字段：actionType / planRef / stageTitle / taskTitle / done / newTitle / **articleId** / **articleTitle** + `TYPE_UPDATE_ARTICLE_TITLE` 常量。学习域动作不填新字段（null）→ 零行为变化（record 构造点 2 处补 null，旧测试零改动）。

旧标题不拿 QUERY_ARTICLE 观察（截 40 字不可靠）：提案端先查库拿权威 title 作锚。

### 2. ArticleAgentRuntime 提案端（对齐 LearningAgentRuntime 模式）

```text
白名单 +SUGGEST_WRITE
覆写 isTerminalExtension（SUGGEST_WRITE → true）+ handleExtraTerminalAction（零观察拒绝 → suggestWriteArticleTitle）
suggestWriteArticleTitle：
  actionType 归一：只认 UPDATE_ARTICLE_TITLE；null/blank/未知（含学习域动作）→ FAILED 终局，绝不回落
    （文章域无旧模型兼容负担——学习域 blank→UPDATE 是历史兼容，这里不学）
  必填：articleId（只认 pageContext，LLM input 摘录不作数——范围锁详情页）+ newTitle
  查库：归属校验；不存在/非本人 → ArticleNotOwnedException 语义终局失败
    （落 FAILED step + throw AgentRunTerminalException，isTerminalFailure 兼容，COMPLETED + 友好文案）
  同名改名（newTitle equalsIgnoreCase 权威 title）→ FAILED step + observation，循环继续由 LLM 告知（不弹卡）
  构造提案 → WAITING_WRITE_CONFIRM + context_json.pendingWriteAction（键结构复用）
```

### 3. confirm 端（现有壳不动，AgentWriteActionService 内加分支）

executeUpdateArticleTitle：必填校验（articleId/articleTitle/newTitle）→ articleId 数字解析 → 查文章（存在 + 归属，双层校验）→ **stale 校验（currentTitle != proposal.articleTitle → CONFLICT 拒绝）** → 同名拒绝（防御纵深第二层）→ before 快照（articleSnapshot：id/title/status/updatedAt，content 大字段不入 context_json）→ `articlesService.updateArticleTitle(id, proposal.articleTitle(), newTitle.trim(), userId)` → after 快照 → markArticleTitleExecuted（载荷 actionType/articleId/articleTitle/newTitle/beforeArticle/afterArticle/executedAt）。

markArticleTitleExecuted 是 markExecuted 的学习域载荷按域复制版——复制债记录为 V3.5 触发证据。

### 4. ArticlesServiceImpl.updateArticleTitle（旧值条件更新）

```sql
-- 两步（对齐学习域 updateTaskDone：归属校验出明确文案 → 条件更新收口）
-- ① selectOne(id)：不存在 →「未找到该博文」；authorId != userId →「无权操作该文章」
-- ② UPDATE articles SET title = ?, updated_at = ? WHERE id = ? AND author_id = ? AND title = ?
--    影响 0 行 →「文章标题已变化，请重新发起修改」
```

- 不走全量 updateArticle（BeanUtil.copyProperties 会把未传字段置 null + 读改写信竞态）
- expectedOldTitle 由调用方（confirm 端）传提案锚，service 不自己查（自证式无意义）
- 副作用落地：deleteArticleDetailCache + deleteArticleListCache + syncArticleRagIndex（已发布 → 重刷 RAG doc（doc 含标题）；状态取更新前行，改名不动状态）
- @Transactional + 显式 userId 参数（对齐学习域 service 模式，Agent 链路可测）

### 5. 决策器 prompt（ArticleAgentStepDecider）

SUGGEST_WRITE 段：内层 actionType = UPDATE_ARTICLE_TITLE；newTitle 必填且必须来自用户原话；不填 articleId/articleTitle（后端以页面上下文定位、以 DB 权威为准）；分界规则行「用户明确给出新标题 → SUGGEST_WRITE；只说想改没说改成什么 → 走 OPTIMIZE_ARTICLE / FINAL_ANSWER」；一次只改一个；零观察禁止。

### 6. 分类器分界（AiIntentClassifierImpl）

两段各加例外句：
- OPTIMIZE_ARTICLE_WORKFLOW 段：用户原话给出完整新标题（把标题改成 XX / 改名为 XX）→ 不是 OPTIMIZE，判 ARTICLE_AGENT
- ARTICLE_AGENT 段：同例外正向表述（Agent 提案 → 后端确认卡 → 确认后执行）；「帮我把标题改短」类无具体新标题仍走 Workflow 不冲突

### 7. 前端

- ai.ts：AgentWriteProposal actionType union +'UPDATE_ARTICLE_TITLE'；+articleId?/articleTitle?；taskTitle 改可选（文章域提案为 null）；注释按动作分列
- AiAssistant.vue：writeActionTypeLabel +「修改文章标题」分支（default 前）；writeActionReason +「将文章《articleTitle ?? '—'》标题改为《newTitle ?? '—'》」（旧数据兜底同 V3.3）

## 改动清单（实施结果）

| 文件 | 变更 |
|---|---|
| ai/agent/AgentWriteProposal.java | +TYPE_UPDATE_ARTICLE_TITLE 常量 + articleId/articleTitle 字段 |
| ai/agent/ArticleAgentRuntime.java | 白名单 +SUGGEST_WRITE；覆写 isTerminalExtension + handleExtraTerminalAction；suggestWriteArticleTitle（归一/校验/查库权威 title/同名拒绝/终局归属失败）；+ArticlesService 注入 |
| ai/agent/AgentWriteActionService.java | +ArticlesService 注入；switch +1 case；executeUpdateArticleTitle（stale 校验 + 锚定条件更新）；parseWriteAction 补 2 字段；markArticleTitleExecuted |
| service/ArticlesService.java + impl | +updateArticleTitle(id, expectedOldTitle, newTitle, userId)：归属校验 + 旧值条件更新 + 清详情/列表缓存 + 已发布 RAG 刷新 |
| ai/agent/ArticleAgentStepDecider.java | prompt（主 + repair）加 SUGGEST_WRITE 段（单类型 + 纪律行 + 分界行） |
| service/impl/AiIntentClassifierImpl.java | OPTIMIZE / ARTICLE_AGENT 两段加「完整新标题」分界例外句 |
| test/resources/agent/classifier-evaluation-cases.json | +2 样例（boundary 新标题 → ARTICLE_AGENT / regression 泛泛改标题不漂移），样例带 pageType/pageArticleId |
| AiIntentClassifierIntegrationTests | record +pageType/pageArticleId；驱动构建 PageContextDTO 透传；+@MockBean VectorStore（门不依赖本地 ES） |
| LearningPlanMatchEvalTests | +@MockBean VectorStore（离线门兑现「零前置」） |
| ArticleAgentRuntimeTests | 护栏改向（白名单 → 零观察拒绝）+ 提案类 6 测试 |
| AgentWriteActionServiceTests | +5（成功 verify expectedOldTitle=proposal.articleTitle / stale 拒 / 他人文章拒 / 同名拒 / 缺 newTitle 拒）→ 20 |
| ArticlesServiceUpdateTitleTests | 新类 4 测试（成功 + RAG/缓存副作用 / 草稿不刷 RAG / 他人拒 / expectedOldTitle 失配拒） |
| ArticleAgentStepDeciderTests | +1（SUGGEST_WRITE 双层解析）→ 6 |
| blog-frontend/src/api/ai.ts | union +'UPDATE_ARTICLE_TITLE' + 2 字段；taskTitle 改可选 |
| blog-frontend/src/components/ai/AiAssistant.vue | writeActionTypeLabel/writeActionReason 各 +1 分支 |

不动：LlmAgentStepDecider（学习域 prompt）；AbstractAgentRuntime 骨架（含 AgentRunTerminalException 复用）；AiAgentRun 表结构；Controller 端点；前端卡壳模板。

## 验证结果

1. 单测：AgentWriteActionServiceTests 20/20、ArticleAgentRuntimeTests 16/16、ArticlesServiceUpdateTitleTests 4/4、ArticleAgentStepDeciderTests 6/6、LearningAgentRuntimeTests 21/21（学习域零回归）
2. 在线门 12/12（样例 10→12；`-DevalCase=受控写` 先单跑 5 例省钱 → 全量 68s 全绿，原 10 例无漂移）
3. 离线门全绿（34 例；LearningPlanMatchEvalTests mock ES 后兑现「离线门零前置」）
4. 前端 vue-tsc 零错误
5. 手工 E2E（待老大）：文章详情页 AI「把标题改成《XX》」→ 思考面板提案步骤 → 确认卡「修改文章标题 · 《旧》→《新》」→ 继续 → 标题变（缓存/RAG 一致）+ 快照留痕；同名改名不弹卡；非本人文章终局失败

## V3.5 域注册触发证据（收口登记）

- AgentWriteProposal record 8 字段跨两域互不相干（命名带域前缀）
- AgentWriteActionService switch 双域混合（4 case）且依赖注入两域 service（LearningPlansService + ArticlesService）
- markArticleTitleExecuted 与 markExecuted 按域复制（快照载荷分域）
- confirm 壳与 AgentRunSuggestionService（workflow-suggestion 侧）各复制一份锁+CAS 骨架
- 分类器 prompt / 路由分发随域增长成串修改

→ 触发条件量化：**域 ≥3 或单文件 switch ≥5 case 时做 V3.5 域注册**（intent → runtime → fallbackMessage → domainPolicy + 写动作执行器分发 + proposal 域结构拆分）

## 风险与对策（实施回顾）

1. **并发写覆盖（Codex 修正核心洞）**：三层防护落地——提案锚（proposal.articleTitle = 提案时刻 DB 权威标题）/ confirm stale 校验 / service 旧值条件更新；测试覆盖 stale 拒绝 + expectedOldTitle 失配两路径
2. **分类器分界漂移**：boundary/regression 双样例（带 pageContext 首个先例）+ 在线门 12/12
3. **updateArticle 全量覆盖坑**：字段级 SQL 化解；副作用（缓存/RAG）服务层测试断言
4. **旧标题截断坑**：QUERY_ARTICLE 观察标题截 40 字不拿当权威 → 提案端查库
5. **测试基建修正**：ES 未开暴露 AiIntentClassifierIntegrationTests / LearningPlanMatchEvalTests 依赖本地 ES → @MockBean VectorStore（评测门语义上本就该只依赖被测组件）
