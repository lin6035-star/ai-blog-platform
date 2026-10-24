# RAG 下一阶段开发笔记

本文用于新开 Codex 会话时快速接上当前 `01myBlog` 的 RAG 进度。

> **最近更新：2026-10-03** —— P0/P1「检索质量治理」已落地（版本化索引 + active alias + 隔离重建 +
> canary 健康检查 + keyword AND + relevance Gate + 阈值按实测重标定 + Memory 存量向量重刷），
> P1.1 正确性收口与 P1.5 评测收口亦已完成。
>
> **真实基线已跑通**（`docs/rag-notes/rag-baseline-2026-10-03.md`）：
> dense 阈值 0.55 可保证 Recall@5 = 52/52，但**挡不住 6/18 负例**（正负例分布重叠）；
> 加 rerank + Gate 0.75 后**负例误放 0/18、正例仍全保留** —— 这组阈值现在有数据依据。
>
> 设计与逐轮修订见 `docs/rag-notes/rag-retrieval-quality-optimization-design.md`（§25 有完整修订记录）；
> 事故复盘与实测证据见 `docs/rag-notes/rag-system-review-2026-10-02.md`。

## 当前状态

`blog-system` 的文章 RAG 已完成一轮检索质量治理；Memory 仍是旧索引结构、但存量向量已重刷。

P1.5 评测闭环已收口：评测集中的 `TITLE_EXACT` 已按 MySQL 发布标题校正，语义开放样例只在确有内容覆盖时允许多篇；
首轮与 bounded OR fallback 的 Gate trace 现会累计记录两轮裁决。原 Rerank 模型配额耗尽后已切换为
`qwen3-vl-rerank`；当前只做定向缩样复验，不把不同模型的分数与旧全量基线直接混比。

P2 结构化切块实验已按 **No-Go** 收口：实验完成了 Markdown 语义切块、旁路索引与 A/B，
但没有证明相对 `chunk-v1` 有稳定收益，不值得为当前学习项目继续扩大评测成本。
在线继续使用 `chunk-v1`，active alias 不切换；未上线的 P2 旁路代码已清理，实验过程保留在基线报告中作为技术取舍证据。

### 文章 corpus（P1 之后）

- 切块：段落合并 → 句子 → 逗号 → token → 字符，5 层递降（`ArticleRagChunker`，上限 800 字符 / 800 token）。
  **切块风格尚未结构化**——标题不单独成块、代码块可能被腰斩（实测 4/126），属于 P2 的工作；
  但**切块与当前代码是一致的**（实测块长 96 / 650 / 800，未超上限）。
- **版本化物理索引 + active alias**：物理索引 `article-rag-<model>-<dims>-<chunkver>-<ts>`，
  读写在别名 `article_rag_active`；`initialize-schema=false`，mapping 由应用自建
  （`src/main/resources/elasticsearch/article-rag-mapping.json`）。
- **隔离重建 + 全局互斥**：`ArticleRagRebuildService` 建新物理索引 → 全量写入 → canary 校验 → 原子切 alias，
  失败不触碰 active；`IndexRebuildLock`（Redis SETNX）保证不并发重建，**Redis 不可用时 fail-closed**
  （与 `WorkflowActionLock` 的降级放行相反，因为这里没有 DB CAS 兜底）。
- **canary 健康检查**：固定哨兵文本写入专用文档，`@Scheduled` 每 5 分钟探测，
  状态机 `HEALTHY / SUSPECT / UNHEALTHY / REBUILDING`：
  - `incrementalWritesAllowed()`（**写入**）——仅 `HEALTHY` 放行；
  - `denseSearchAllowed()`（**读取**）——仅已验证 `HEALTHY` 放行；`SUSPECT` 不放行，
    `REBUILDING` 继承重建前的读取许可，从 `UNHEALTHY` 起步时保持熔断。
    首次巡检延迟已从 10s 压到 1s，避免长启动窗口，同时不依赖“漂移幅度恰好会被 dense 阈值挡住”的偶然性。
- 检索：dense `topK=10`、`similarityThreshold=0.55`（按实测 cosine 分布重标定；
  **注意 ES 的 `knn.similarity` 按原始 cosine 判定，返回的 `_score` 才是 `(1+cos)/2`**，见设计稿 §2.2）；
  keyword 走去壳词 + **AND**，零命中时的 bounded OR fallback 按设计稿 §10.1 做 A/B；
  检索期过滤 `source=article` + `articleStatus=1`，Gate 后仍按 `articleId` 批量复核 MySQL 可见性。
- 精排与准入：gte-rerank-v2 **读取真实 `relevance_score`** → `ArticleRagRelevanceGate`（阈值 0.75）；
  单候选不再跳过；rerank 不可用时只有强词法证据可放行。
- 运维入口（均走 `IndexManagementAccessGuard` 白名单，JWT 只代表已登录）：
  - `GET  /api/ai/rag/articles/health` —— 索引健康快照（状态 / canary 分数 / 是否熔断）；
  - `GET  /api/ai/rag/articles/consistency` —— MySQL 与 ES 的文章 ID 一致性；
  - `POST /api/ai/rag/articles/consistency/repair` —— 补索引 / 删脏索引；
  - `POST /api/ai/rag/articles/rebuild` —— 隔离重建。
- 引用协议、SSE `references`、前端参考来源、AI 计费：**保持不变**。

### Memory corpus（存量已修，结构未迁移）

- 正式记忆与情景记忆仍写在早期共享索引 `spring-ai-document-index`（配置项
  `blog.ai.rag.corpus.memory-index-name`），尚未纳入文章那套版本化 alias 体系。
- 2026-10-03 已按 MySQL 权威数据**重刷存量向量**（实测 cosine 由 ≈0 恢复为 **1.0**），
  入口 `POST /api/ai/rag/memories/reindex`（`MemoryRagReindexService`，内部先删后写、幂等可重跑）。
- 同一次重刷会清理**幽灵文档**（ES 里有、MySQL 已无的记忆，实测清掉 6 条）；
  读索引失败时整体跳过清理，宁可留幽灵也不误删。

#### 情景记忆"看着像没召回"（2026-10-16 归因 + 修复）

**症状**：问「还记得我们之前决定的，RAG 使用什么方法吗，为什么使用这个方法」，
AI 答"记忆中没有记录关于 RAG 具体使用方法和原因的决策细节"——**而那条记忆就在库里**。
日志现场：`Memory 向量召回 count=1` / `Episodic Memory count=0（阈值 0.58 下无命中）`。

**实测**（线上同一 embedding 模型 + ES knn 复现，检索词取 Agent 实际用的那个）：

| 检索词 | 该条情景记忆 cosine | 0.58 阈值 |
| --- | --- | --- |
| 用户原话 | 0.6241 | 过 |
| Agent decider 改写的 `之前决定的RAG使用方法及原因` | **0.5607** | **差 0.0193 被挡** |
| 两者拼接 | 0.6023 | 过 |
| 无关问题（Redis 缓存穿透 / 心情 / 下一步做什么） | 0.2688 ~ 0.3409 | 不过 |

**根因是两条通道不对称**：长期记忆有常驻通道（PROFILE 2 条 + PREFERENCE 3 条纯 SQL 无条件注入），
情景记忆**只有向量单通道**，阈值反而更严（0.58 vs 长期记忆 0.50）。向量一失手 = 整体静默。

##### 0.58 的来历：没有标定，且实测是"死阈值"

**别把它当成"有意保守"的设计改回去**。三条证据：

1. `docs/superpowers/specs/2026-08-18-episodic-memory-design.md` 全篇**没有讨论过检索阈值**
   （唯一的 `0.91` 是 confidence 示例）。
2. 0.58 是在 `docs/superpowers/plans/2026-08-18-episodic-memory-implementation.md:355`
   **直接写死的常量**，无标定过程、无注释依据。
3. 同期文章 RAG 的 0.58 是实测标定出来的（`rag-baseline-2026-10-03.md`：「挡住负例最高 0.5703，
   余量 0.01」）——但那是 **rerank Gate**，候选多、要精排；记忆召回只有几条候选，
   漏召回的代价远大于误召回。**两个场景的数值被混用了。**

用 `ai_agent_steps` 里 **18 条真实线上检索词**（去重样本）做阈值敏感性实测：

| 阈值 | 能召回情景记忆的检索词 | 说明 |
| --- | --- | --- |
| **0.58**（改动前） | **0/18** | 全库最高分仅 0.5717，**一条都命中不了**——上线至今情景记忆几乎从未召回成功 |
| 0.55 | 2/18 | 目标那条 0.5605，**只比阈值高 0.0105**，模型换个措辞就掉 |
| **0.50**（当前） | **5/18** | 新增 3 条全是"用户背景信息 / 个人情况 / 技术背景"命中「后端开发者」身份记忆，**属合理召回** |

0.50 新增召回明细（都不是误召回，且 prompt 已写"只在相关时参考"）：

| 检索词 | 命中 | cosine |
| --- | --- | --- |
| 用户背景信息 | B: 后端开发者 | 0.5324 |
| 用户个人情况、偏好及历史交互记录 | B: 后端开发者 | 0.5485 |
| 用户的学习偏好、技术背景或学习目标 | B: 后端开发者 | 0.5462 |

区分度验证：「RAG 决定用 ES」那条除真正问 RAG 决策时是 0.5605，其余 17 条检索词对它最高只有 0.44
——降阈值不会把它误召回，只会让该召回时召回。

> ⚠️ 样本限制：2 条情景记忆 / 18 条检索词 / 1 个账号。方向明确但样本小，
> 后续记忆量上来后应重新标定，而不是把 0.50 当永久值。

**已修**：

1. `AiEpisodicMemoryRetrieveService.PROMPT_SIMILARITY_THRESHOLD` 0.58 → **0.50**
   ——与长期记忆对齐；实测无关问题最高 0.34 仍不命中，无误召回。
2. `AbstractAgentRuntime.prepareStepDecision` 给 `QUERY_MEMORY` 并入 run 原始目标 `userGoal`，
   检索词优先用原话而非 LLM 改写（原话 0.6241 > 拼接 0.6023 > 改写 0.5607，改写丢掉的信息比口语噪声更伤召回）。
   goal 在 run 内恒定，所以 `rejectDuplicatedQuery` 的 input_json 精确比对不受影响；
   `queryParamLabel` 只读 focus/question/keyword，`userGoal` 不进观察文本。
   契约由 `GeneralAgentRuntimeTests#queryMemoryInputCarriesRunGoalButRagInputDoesNot`
   + `GeneralAgentActionExecutorTests#memoryQueryPrefersRunGoalOverLlmRewrite` 锁住。

**未做（有意登记）**：情景记忆仍无"近期兜底通道"，纯向量失手时依旧整体静默。

写入侧触发仍窄——实测主力账号 20 条用户消息只命中 1 次关键词，`% 6` 兜底要求消息数
恰好为 6 的倍数，真实会话大多是 2~8 条（8 个会话里只有 3 次触发机会）。

> ⚠️ **不要因此就放宽触发频率**。收紧是有意的历史决策：2026-08-18 设计稿「风险 1 / 风险 2」
> 明确要避免"模型的常规规划话术把每轮聊天都送入情景记忆抽取"——最初的触发方式检查 AI 回复，
> 而 AI 回复里满是"阶段 / 下一步 / 准备"这类词，等于每轮都写。
>
> 2026-10-13 已把窗口兜底从 `% 10` 放宽到 `% 6`（代码注释写明理由是"情景记忆自 8-19 起再无新增"），
> 而且**立刻见效**：10-16 20:41 那条「记住我是后端开发者」正是靠 `% 6` 在第 6 条消息时触发的
> —— 3 轮对话（6 条消息）里用户消息一个关键词都没有，如果还是 `% 10`，这条至今不会存在。
>
> 所以当前缺的不是"触发更频繁"，而是**"触发更准"**（按会话结束 / 主题切换触发，而不是数消息条数）。
> 那是一次独立设计，不要用调小 `% N` 来凑。

关键包和文件：

- `blog-system/src/main/java/com/hailin/blogsystem/ai/rag/` —— 检索主链路（策略 / dense / keyword / Gate / trace）
- `blog-system/src/main/java/com/hailin/blogsystem/ai/rag/index/` —— 索引版本化、alias、canary、健康状态、隔离重建、重建锁
- `blog-system/src/main/java/com/hailin/blogsystem/ai/memory/` —— 记忆索引与存量重刷
- `blog-system/src/main/resources/elasticsearch/article-rag-mapping.json` —— 文章 RAG 自建 mapping
- `blog-system/src/main/java/com/hailin/blogsystem/service/impl/AiMessageServiceImpl.java`
- `blog-system/src/main/java/com/hailin/blogsystem/service/impl/AiModelServiceImpl.java`
- `blog-system/src/main/java/com/hailin/blogsystem/ai/tool/AiArticleTools.java`
- `blog-frontend/src/api/ai.ts` / `blog-frontend/src/components/ai/AiAssistant.vue`
- 项目总结与面试讲法：`docs/rag-project-summary-and-resume.md`（**内容仍是治理前的版本，待更新**）

## 当前原则

项目整体工程化原则见根目录 `AGENTS.md` / `CLAUDE.md` 的“项目工程化原则”。RAG 这一块也遵循同样原则：不是因为当前文章量少就跳过真实系统会遇到的检索、排序、一致性和可观测性问题；但重型生产设施可以先用轻量版本落地，并保留清晰升级路径。

RAG 业务功能开发节奏：

```text
业务功能优先
遇到明显影响正确性、体验、调试的问题，再小步优化
```

暂时不要做 RAG 管理页，因为当前账号体系只有：

```text
游客
普通登录用户
```

没有管理员身份。`rebuild` 索引属于开发/运维动作，不应该暴露给普通用户。

上线前需要处理：

- `POST /api/ai/rag/articles/rebuild` 不能裸露给普通登录用户。
- 可以先只在开发环境开放，或者后续有管理员体系后再做后台入口。

## 已完成的 RAG 业务目标

### 当前文章问答增强

已完成。

业务效果：

用户在文章详情页打开 AI，可以自然提问：

```text
这篇文章讲了什么？
帮我总结成 3 点
这篇文章适合什么人看？
这篇文章里提到的 Redis 有什么作用？
根据这篇文章给我列一个学习路线
```

当前行为：

```text
如果用户在文章详情页提问
  ↓
优先使用当前文章 articleId 查询文章全文
  ↓
基于当前文章回答
  ↓
不优先走全站 RAG，避免“这篇文章”问题被其他文章片段干扰
  ↓
参考来源至少展示当前文章
```

已有基础 / 关键点：

- 前端 `PageContext` 已经会传 `pageType`、`path`、`articleId`、`articleTitle`。
- 后端已有 `buildArticleDetailContextFromIntent(...)`。
- 意图分类需要稳定识别 `ARTICLE_DETAIL_QA`。

注意点：

- 当前文章问答不应该优先走全站 RAG。
- 如果当前页面是文章详情页，并且用户问“这篇文章”，应该直接读当前文章。
- 回答下面的来源列表应该包含当前文章，点击能回到该文章详情。

### 站内文章搜索与兜底来源统一

已完成。

业务效果：

用户可以问：

```text
有没有 Redis 相关的文章？
有没有关于实战的文章？
找一下秒杀系统相关内容
```

当前行为：

```text
先走 ES RAG 语义检索
  ↓
如果 ES 有召回，使用 RAG 片段回答，并返回 references
  ↓
如果 ES 召回为空，但意图是 ARTICLE_SEARCH
  ↓
使用 MySQL title/summary LIKE 搜索兜底
  ↓
把 MySQL 搜索结果转换成 ArticleRagContext
  ↓
AI 回答和前端 references 使用同一套来源展示
```

注意点：

- 这是兜底，不是替代 ES RAG。
- 如果 ES 召回为空但 MySQL 找到了，前端也应该显示参考来源。
- `AiArticleTools.searchPublishedArticles(...)` 仍然是主模型可调用工具，但搜索兜底来源现在由后端主动统一生成，避免“回答找到了但没有 references”。

### 引用编号

已完成。

业务效果：

AI 回答正文可以使用：

```text
Redis 可以用于缓存、削峰和库存预扣 [1]。
```

前端参考来源对应展示：

```text
[1] Redis 在电商秒杀系统中的实战落地
```

注意点：

- prompt 已要求模型使用 `[1]`、`[2]`。
- 前端 references 已显示编号。
- 当前编号以去重后的 references 展示顺序为准。
- 以后如果要更严谨，需要让 prompt 里的片段编号也按文章去重，而不是按 chunk 编号。

## 后续可选 RAG 业务

### 相关文章推荐

业务效果：

用户可以问：

```text
有没有和这篇类似的文章？
推荐几篇 Redis 相关的文章
我想看秒杀系统相关内容
```

期望行为：

```text
根据当前文章标题/摘要/正文，或者用户问题
  ↓
检索 ES
  ↓
排除当前文章本身
  ↓
返回 3 篇左右相关文章
  ↓
AI 回答中解释推荐理由
  ↓
前端参考来源列表可点击
```

可以先做轻量版：

- 不新建独立接口。
- 仍然复用 AI 聊天。
- 在 prompt 中要求模型按召回片段推荐文章。

后续再考虑：

- 独立推荐接口。
- 文章详情页固定展示“相关推荐”。

## 已完成的大优化轻量版

这些能力已经按“轻量但可讲清楚”的方式完成，不再属于暂缓项：

### 混合检索

已完成轻量版。

当前策略：

```text
普通 RAG 问答
  ↓
ES 向量检索

ARTICLE_SEARCH 站内搜索
  ↓
ES 向量检索 + ES keyword 检索
  ↓
按 articleId + chunkIndex 合并去重
  ↓
规则 rerank
  ↓
如果 ES 仍为空，再 MySQL LIKE 兜底
```

后续如果要升级，可以把 keyword 检索扩展成 title / summary / content 多字段 BM25，并给不同字段设置权重。

### rerank

已完成规则版。

当前排序依据：

- 标题命中关键词。
- 正文命中关键词。
- `chunkIndex = 0`。
- ES 原始召回顺序。
- 按 `articleId` 去重，保留每篇文章最高分 chunk。

后续如果检索结果明显不准，可以替换 `ArticleRagRanker` 的实现，接入真实模型 rerank。

### Elasticsearch / MySQL 一致性巡检和手动修复

已完成开发运维版。

当前接口：

```text
GET  /api/ai/rag/articles/consistency
POST /api/ai/rag/articles/consistency/repair
```

当前能力：

```text
只读巡检
  ↓
对比 MySQL 已发布文章 ID 和 ES source=article 的 articleId
  ↓
输出 missingInEs / extraInEs

手动修复
  ↓
missingInEs 调用 indexArticle(articleId)
  ↓
extraInEs 调用 deleteArticleIndex(articleId)
```

上线前仍需要补权限或环境限制，避免普通用户调用 rebuild / repair 这类运维接口。

## 原“暂缓项”中已落地的部分

- ✅ **低相关 / 无关召回拒绝**（2026-10-01 页面手测登记，2026-10-03 治理完成）。
  登记时定位的根因（`DefaultArticleRagRanker` 在“全不命中”时反而全保留）只是表层，
  真实链路还叠了三个缺陷：dense 向量空间漂移（召回恒空）、keyword 单字 OR（84/126 假阳性）、
  rerank 不读 `relevance_score` 且 prompt 强制认领。
  现由「阈值按实测重标定 + keyword AND + `ArticleRagRelevanceGate`（0.75）+ prompt 松绑」共同承担。
  实测：正例 rerank 0.82~0.999 全部通过；域外（cos 0.37）与弱相关（0.37~0.51）负例在 dense 阈值层即被挡。
- ✅ **真实模型 rerank**：`GteRerankV2ArticleRagRanker` 已解析 `relevance_score` 并交给 Gate，单候选不再跳过。
- ✅ **定时健康巡检**：canary `@Scheduled` 每 5 分钟探测 + `HEALTHY/SUSPECT/UNHEALTHY/REBUILDING` 四态；
  重启后的 `SUSPECT` 窗口保持 fail-closed，首次巡检延迟已压到 1s。

## 仍待办

**P2 / P3（已停止主动投入）**：

- **No-Go** Markdown 结构化语义切块已完成实验并做出停止决策（2026-10-03）：
  `rawContent / embeddingContent / headingPath / contentHash` 已分离，标题层级、列表/表格、代码围栏和
  超长代码分片均有测试；mapping 已补 `contentHash / chunkerVersion`。
  最新真实候选索引为
  `article-rag-qwen3-7-text-embedding-1024-chunk-v2-20261003-080946`，共 **265** 个文章 chunk；
  它在早期 `065124` 候选索引基础上加了 `<100` 字符碎片合并，chunk 数从 370 降到 265（-28%），
  碎片率降到 **1.5%**；代码围栏中断仍为 **0**。
  `article_rag_active` 继续指向 `chunk-v1`，**不切 alias**。
  当前 Rerank 模型为 `qwen3-vl-rerank`，已加“每篇最多 2 个 chunk”候选限流；Gate 阈值按当前模型小样本重标定为 `0.75`。
  对最新碎片合并候选索引 `article-rag-qwen3-7-text-embedding-1024-chunk-v2-20261003-080946`
  做了 5 条定向端到端复验：3 条代表性正例 Recall@5 = 3/3，2 条硬负例误放 = 0/2；
  小样本只证明新方案可用，没有证明优于 `chunk-v1`；因此停止扩大 A/B，旁路构建代码已清理。
- ~~`text_type=document` A/B~~ **已于 2026-10-03 做完，结论：不切换**
  （离线代理实验：正例平均分 −0.0149、负例最高分 −0.0355、分离间隔 −0.1123 → −0.0628 **仍为负**；
  有得有失且不解决"dense 分不开正负例"，详见 `rag-baseline-2026-10-03.md` §7。
  兼容模式可透传且真的改变向量，不传时等价 `query`）；
- manifest / 文章 ID 集合 / content hash 持久化、IK / RRF / `matchedTerms` 、升维统一列为可选升级，
  当前不做；只在后续出现一组可复现的真实失败样例时再重开。
- 路由层评测 + 完整在线评测集：`ArticleRagOfflineEvalRunner` 与真实 evaluator 都已就绪；
  **2026-10-03 已跑通 Dense 与 Rerank 两段真实基线并固化报告**
  （`docs/rag-notes/rag-baseline-2026-10-03.md`：dense 阈值 0.55 下负例误放 6/18，
  加 rerank + Gate 0.75 后 0/18 且正例 Recall@5 保持 100%）；
  端到端 runner 已补齐，可输出 Gate reason 与 fallback 恢复率；继续保留为以后定点回归工具，
  不再为了“指标更完整”主动跑全量。

**零散遗留**：

- snippet 更精细清洗；
- MQ 替换 `@Async`；
- RAG 管理页；
- ID 一致性巡检仍是手动接口，未接定时 + 告警（canary 巡检已定时）；
- Memory corpus 与文章 corpus 的结构尚未统一（前者沿用早期共享索引，存量向量已重刷）；
- 幽灵记忆文档的**成因未溯源**（为何 `deleteMemoryIndex` 未被调用），只做了对齐；
- `/rebuild` 已加全局互斥，但**没有构建期增量补偿**（重建期间的文章更新可能落在旧物理索引，
  由切换后的一致性检查兜住）。

## 本地验证命令

后端编译：

```powershell
cd D:\AIAgent\01myBlog\blog-system
mvn clean test-compile
```

RAG 相关测试：

```powershell
mvn "-Dtest=ArticleRagRetrieveServiceTests,ArticleRagPromptBuilderTests,ArticleRagIndexServiceTests,ArticleRagControllerTests" test
```

前端构建：

```powershell
cd D:\AIAgent\01myBlog\blog-frontend
npm run build
```

本地 RAG 搜索调试：

```powershell
Invoke-RestMethod `
  "http://localhost:8080/api/ai/rag/articles/search?question=电商秒杀" `
  -Headers @{ Authorization = "Bearer $token" } |
  ConvertTo-Json -Depth 5
```

手动重建索引：

```powershell
Invoke-RestMethod `
  -Method POST `
  -Uri "http://localhost:8080/api/ai/rag/articles/rebuild" `
  -Headers @{ Authorization = "Bearer $token" }
```

注意：手动重建索引当前只适合本地开发使用，上线前必须加权限或环境限制。
