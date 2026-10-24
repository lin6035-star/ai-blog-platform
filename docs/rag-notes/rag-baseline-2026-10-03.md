# RAG 检索基线报告（2026-10-03）

P1.5 第 2 步：用真实 embedding / rerank 跑固定评测集，**用数据证明 `similarity-threshold=0.55`
与 `gate.rerank-threshold=0.75` 这组配置**，而不是靠少量手工样本。

关联文档：设计稿 `rag-retrieval-quality-optimization-design.md`、事故复盘 `rag-system-review-2026-10-02.md`。

---

## 1. 环境快照（固化的口径）

| 项 | 值 |
|---|---|
| Embedding 模型 | `qwen3.7-text-embedding`，**1024 维** |
| Embedding 模式 | **默认（实测等价于 `text_type=query`）**；文档侧与查询侧使用同一模式 |
| Rerank 模型 | `qwen3.7-text-rerank`（`gte-rerank-v2` 接口，workspace 级） |
| 文章索引 | `article_rag_active` → `article-rag-qwen3-7-text-embedding-1024-chunk-v1-20261003-031844` |
| 索引内容 | 23 篇文章 / **136 chunks** + 1 canary 文档 |
| chunker 版本 | `chunk-v1`（段落 → 句子 → 逗号 → token → 字符，上限 800 字符） |
| 检索参数 | `top-k=10`、`similarity-threshold=0.55`、`gate.rerank-threshold=0.75` |
| 评测集 | `article-rag-eval-v1.jsonl`，**70 条**（52 正例 + 18 负例；TITLE_EXACT 已与 MySQL 标题快照对齐） |
| 评测方式 | 无阈值（`similarityThreshold=0`）召回 top10，按 articleId 去重后评估 |

---

## 2. Dense 基线（无 Gate）

复现：

```powershell
.\mvnw.cmd "-Dtest=ArticleRagRealDenseBaselineTests" test -DrunRealRagBaseline=true
```

**结果**：

```text
positives=52  recall@5=52/52 (1.0000)  mrr@5=1.0000
positiveBest: n=52, min=0.5573, p25=0.7174, p50=0.7989, p75=0.8458, max=0.9234
negativeTop:  n=18, min=0.3174, p25=0.3899, p50=0.5246, p75=0.5715, max=0.6696
```

**关键结论：dense 分数分不开正负例。**

| | 正例 | 负例 |
|---|---:|---:|
| 最低分 | **0.5573** | — |
| 最高分 | — | **0.6696** |

正例最低 **0.5573** < 负例最高 **0.6696** → **分布重叠**，不存在能同时"召回全部正例 + 拒绝全部负例"的单一阈值。

在 `similarity-threshold=0.55` 下，**18 条负例里有 6 条会被 dense 放行**：

| 负例 | dense cosine |
|---|---:|
| hard-003 Redis/MySQL 哪个适合剪辑视频 | 0.6696 |
| code-003 Spring AI 里 VectorStore 和 RAG 怎么配合 | 0.6414 |
| hard-001 Redis/Kafka 哪个适合图片压缩 | 0.6209 |
| hard-004 Kafka 能否替代图片识别模型 | 0.6079 |
| hard-006 Java/Redis 哪个适合规划旅行 | 0.5715 |
| para-017 VectorStore 在检索链路里负责什么 | 0.5693 |

---

## 3. Rerank 基线

> ⚠️ **2026-10-03 晚起 `qwen3.7-text-rerank` 额度用尽**，本节数据对应**旧模型**；
> 新模型 `qwen3-rerank` 的分数分布与 Gate 阈值重标定见 **§8**。两节不可混用。

复现：

```powershell
.\mvnw.cmd "-Dtest=ArticleRagRealRerankBaselineTests" test -DrunRealRagRerankBaseline=true
```

**结果**：

```text
modelApplied=70
positiveBest: n=52, min=0.8196, p25=0.9442, p50=0.9866, p75=0.9964, max=1.0000
negativeTop:  n=18, min=0.1407, p25=0.2826, p50=0.3919, p75=0.5386, max=0.7202
```

**关键结论：rerank 把正负例完全分开了。**

| | 正例 | 负例 |
|---|---:|---:|
| 最低分 | **0.8196** | — |
| 最高分 | — | **0.7202** |
| 分离间隔 | **+0.0994（不重叠）** | |

而 `gate.rerank-threshold = 0.75` **正好落在这个间隔内**：

- `0.75 > 0.7202`（负例最高）→ **全部负例被拒绝**
- `0.75 < 0.8196`（正例最低）→ **全部正例被接受**

### Gate 的实际拦截效果

前文那 6 条被 dense 放行的负例，rerank 分数与 Gate 判定：

| 负例 | dense cosine | rerank score | Gate 0.75 |
|---|---:|---:|---|
| hard-003 | 0.6696 | **0.7202** | 拒绝 ✅ |
| hard-006 | 0.5715 | 0.6056 | 拒绝 ✅ |
| hard-004 | 0.6079 | 0.5386 | 拒绝 ✅ |
| code-003 | 0.6414 | 0.5236 | 拒绝 ✅ |
| hard-001 | 0.6209 | 0.4088 | 拒绝 ✅ |
| para-017 | 0.5693 | 0.3597 | 拒绝 ✅ |

**6/6 全部拦下。**

---

## 4. 端到端结论（本次基线的主要产出）

| 配置 | 正例 Recall@5 | 负例误放 |
|---|---:|---:|
| Dense 单独（阈值 0.55） | **52/52 = 100%** | **6/18 = 33%** ❌ |
| Dense + Rerank + Gate 0.75 | **52/52 = 100%** | **0/18 = 0%** ✅ |

也就是说：

1. **`similarity-threshold=0.55` 只能保证召回，不能保证精度**——它挡不住"含技术词但语义无关"的查询；
2. **Gate 是必需的，不是锦上添花**：它单独拦下了 dense 放行的 6 条负例，且没有损失任何正例；
3. **`0.75` 这个值有数据依据**：它落在 0.7202 ~ 0.8196 的干净间隔内，且**离负例最高分只差 0.03**
   —— 若下调到 0.70，hard-003 就会被放行，所以 0.75 是有必要的下限（略偏保守，与项目"精度优先"定位一致）。

---

## 5. 基线暴露的 3 个问题

**① `0.55` 不能再往上调**：正例最低分 **0.5573**（`para-018` 初学者怎样建立 C 语言和编程思维基础）
只比阈值高 0.007；另一条 C 语言标题精确查询（`title-017`）也只有 **0.5817**，是全库标题匹配里最低的。
阈值上调到 0.56+ 就会开始漏召回这两条。**要降低负例误放，只能靠 Gate，不能靠抬 dense 阈值。**

**② 若干"expected 排第 2"不是缺陷，是标注粒度问题**：

| 样例 | expected 排名 | 排第 1 的是 |
|---|---|---|
| title-015 Linux 系统诊断中的系统思维 | 2 | 「Linux 系统诊断思维：从故障现象到内核原理的逆向求索」 |
| title-019 Vibe Coding 进阶教程 | 2 | 「Vibe Coding 入门指南」 |
| title-023 我的第一篇博客 | 2 | 「我的第一篇文章」 |
| para-002 / para-009 秒杀相关 | 2 | 「RocketMQ：高可用消息引擎……电商核心链路实践」 |

这些是**同主题多篇文章**互相竞争的结果，召回哪一篇都算合理。
→ 评测标注应允许"接受多篇"（把 expected 补成合集），否则 MRR 会被低估。

**③ `top-k=10` 对 136 个 chunk 的语料偏大**：部分查询的候选里，第 6~10 名的 dense cosine 已低于 0.45，
对最终结果没有影响，但会**放大 rerank 的候选数与调用成本**。当前规模无碍，语料变大后需要重估。

---

## 6. 端到端评测（Dense → keyword → bounded OR fallback → rerank → Gate）

前两节把 Dense 与 Rerank **分开**测；这一节跑**真实主链路**（`ArticleRagSearchService.search()`），
并补上只有端到端才拿得到的两个指标：**Gate 拒绝原因**与 **fallback 恢复率**。

复现：

```powershell
.\mvnw.cmd "-Dtest=ArticleRagEndToEndEvalTests" test "-DrunRagEndToEndEval=true"
```

**结果**（65 条 = 52 正例 + 13 难负例；5 条 `OUT_OF_DOMAIN` 属路由层，不在本 runner 范围）：

```text
[RAG-E2E-SUMMARY] cases=65 positives=52 recall@5=51/52(0.9808) negatives=13 falseAccepts=0(0.0000)
[RAG-E2E-FALLBACK] triggered=13 recovered=1(0.0769)
[RAG-E2E-STRATEGIES] {EMPTY=14, HYBRID=51}
[RAG-E2E-GATE-REASONS] {ACCEPT_RERANK=92, ACCEPT_STRONG_LEXICAL=3, REJECT_LOW_RELEVANCE=50}
```

上面的结果是 2026-10-03 早先 rerank 配额仍可用时的可比基线。评测集收口后重跑时，供应商返回
HTTP 403 `AllocationQuota.FreeTierOnly`，系统按 fail-closed 处理并完成测试（`BUILD SUCCESS`），但该次
统计 `recall@5=11/52`、`REJECT_RERANK_UNAVAILABLE=222` 不可与正常 rerank 基线比较，也不用于调整阈值。

**结论**：

1. **负例误放 0/13** —— Gate 在完整链路里依然有效（与 §4 的分段结论一致）；
2. **Recall@5 = 98.08%**，比 dense-only 的 100% 少 1 条（见下）；
3. **bounded OR fallback 触发 13 次、恢复 1 次（7.7%）** —— 数据说明它是"少数派救援"而非主力路径，
   支持"保留但默认不启用"的设计判断；
4. **Gate 拒了 50 条候选**，且**没有出现 `REJECT_RERANK_UNAVAILABLE`** —— rerank 服务全程可用。

### 唯一漏召回：`title-022`

```text
[RAG-E2E] id=title-022 type=TITLE_EXACT strategy=EMPTY fallback=false returned=[] expected=[2085208555163787265]
```

- dense cosine `0.6526`（过 0.55，dense-only 基线里排第 1）；
- 但 **rerank 分数未达 0.75** → 被 Gate 以 `REJECT_LOW_RELEVANCE` 拒绝；
- 实际标题是「我的第一篇博客文章」，与 query「我的第一篇文章」**并不相同** →
  这是**标注与实际标题不一致**的边缘案例，不是链路缺陷。
- 建议二选一：把该条 query 改成实际标题，或把「我的第一篇博客文章」之外的同主题文章也列入可接受集合。

### ⚠️ 评测口径说明（重要）

本 runner 跑在 **H2 测试库**上，而 RAG 索引在**真实 ES** 里 —— 两个数据源不对齐。
因此测试桩掉了 `ArticlesService` 的**数据源边界**：

- 可见性复核（`retainCurrentlyPublished`）一律放行；
- MySQL LIKE 兜底返回空（它是 ES 不可用时的降级路径，会掩盖真实检索结果）。

**不桩会怎样**：可见性复核拿 ES 的 articleId 去查 H2，查不到就按 fail-closed 全部过滤 →
**65 条样例全部变成 EMPTY**（实测踩过一次）。这两条路径各有独立测试覆盖，
端到端 runner 关注的是检索链路本身。

---

## 7. `text_type=document` 单变量 A/B（离线代理实验 → 结论：不切换）

**为什么先做代理实验**：完整 A/B 要新建物理索引 + 重新向量化 136 个 chunk + 跑三套评测；
而"值不值得"可以先便宜地回答——保持查询侧不变，只把**文档侧**换成 `text_type=document`，
看同一批候选的语义分数怎么变。这里没收益，就不必重建。

**对照的干净程度**：ES 里的 `content` 就是写入时被 embed 的同一段文本（已实测逐字相同），因此

- 基线 = 索引里的向量（默认模式）与查询的 cosine，取自 ES 的 knn 打分；
- 实验 = 现场用 `document` 模式重新 embed **同一段文本**，再与同一个查询向量算 cosine。

唯一变量就是文档侧的 text_type。

复现：

```powershell
.\mvnw.cmd "-Dtest=ArticleRagTextTypeAbTests" test -DrunTextTypeAb=true
```

**结果**（65 条，每条取 top3 候选）：

| 口径 | 基线（默认 ≈ query） | document | 变化 |
|---|---:|---:|---:|
| 正例（52）平均分 | 0.7569 | 0.7420 | **−0.0149** |
| 正例改善条数 | — | — | 15/52 |
| 负例（13）平均分 | 0.5460 | 0.5325 | −0.0134 |
| 正例最低分 | 0.5573 | 0.5713 | +0.0140 |
| 负例最高分 | 0.6696 | 0.6341 | **−0.0355** |
| **分离间隔**（正例 min − 负例 max） | **−0.1123** | **−0.0628** | +0.0495（**仍为负，仍重叠**） |

分类别看：

| 类型 | 基线 min ~ max | document min ~ max |
|---|---|---|
| TITLE_EXACT (22) | 0.5817 ~ 0.9054 | 0.5870 ~ **0.8852**（高分被削弱） |
| PARAPHRASE (23) | 0.5573 ~ 0.8300 | 0.5713 ~ 0.8392 |
| CODE_API (7) | 0.6925 ~ 0.8331 | 0.6736 ~ 0.8435 |
| HARD_NEGATIVE (13) | 0.3797 ~ 0.6696 | 0.4063 ~ **0.6341**（最高分被压低） |

**结论：不切换。** 理由：

1. **有得有失**：document 模式把负例最高分压低 `0.0355`（好），但同时让正例平均分掉 `0.0149`、
   52 条正例里只有 **15 条**改善（差）；
2. **它不解决根本问题**：分离间隔虽从 −0.1123 改善到 −0.0628，**仍然是负的**——
   dense 依旧分不开正负例，Gate 依旧必需。切换换不来架构简化；
3. **代价与收益不成比例**：要新建物理索引、重新向量化 136 chunk、重跑三套评测才能确认，
   而换来的只是一个"不改变结论"的分布平移；
4. **值得留存的信号**：它**压缩了分布**（高分降、低分升），其中 TITLE_EXACT 的强项被削弱最多。
   若将来 dense 分离度真的成为瓶颈，可以拿这份数据回头再看。

**局限（必须说明）**：本实验比的是 cosine **分数分布**，不是真实检索**排序**。
因此它属于"淘汰性证据"——足以支持"不值得投入完整重建"，但不能反过来证明"document 一定更差"；
若要下定论，仍需在旁路物理索引上跑真实评测。

---

## 8. rerank 模型变更 → Gate 阈值重标定（2026-10-03 晚）

**背景**：`blog.ai.rag.rerank.model` 从 `qwen3.7-text-rerank` 换成 `qwen3-rerank`（原模型额度用尽）。
**要点**：**换 rerank 模型必须重新标定 Gate 阈值**，否则 Gate 会静默失准——要么过度保守（误拒正例），
要么形同虚设（放行负例）。两者都不报错，只体现在指标上。

| 口径 | `qwen3.7-text-rerank`（§3） | `qwen3-rerank`（现行） |
|---|---:|---:|
| 正例 min | **0.8196** | **0.3571** |
| 正例 p25 | 0.9442 | 0.8704 |
| 负例 max | 0.7202 | **0.5703** |
| **分离间隔** | **+0.0994** | **−0.2132** |

新模型对同一批候选**整体给分更低**，`0.75` 于是变成"过度保守"：
全量 65 条下 Recall@5 掉到 **46/52**（漏 6 条），`REJECT_LOW_RELEVANCE` 从 50 涨到 93。

**重标定值：`0.58`**（挡住负例最高 0.5703，余量 0.01）。

**验证**（同一批 36 条代表样例 = 全部 `PARAPHRASE` + 全部 `HARD_NEGATIVE`，避免跑全量）：

| 阈值 | 正例 Recall@5 | 负例误放 | ACCEPT_RERANK | REJECT_LOW_RELEVANCE |
|---|---:|---:|---:|---:|
| 0.75 | 17/23 (0.7391) | 0/13 | 23 | 83 |
| **0.58** | **20/23 (0.8696)** | **0/13** | 35 | 71 |

救回 `para-101` / `para-103` / `partial-001` 三条，**负例零误放不变**。

**全量推算 = 49/52**：0.75 下"子集漏 6 条"与"全量漏 6 条"完全吻合，说明漏召回全部集中在 `PARAPHRASE`，
故 0.58 的全量应为 46+3 = **49/52**。此推算**未再跑全量**验证。

**仍漏的 2 条（阈值无解，属 P2/P3 目标）**：

- `para-018`「初学者怎样建立 C 语言和编程思维基础」：`fallback=true` 且结果为空 → **dense 层就没召回**（<0.55）；
- `partial-002`「MySQL 分库分表怎么做」：候选被 Gate 拒（<0.58）→ **rerank 判为低相关**，
  而该文章确实有「6.2 分库分表实战」章节 → 属 rerank 判断能力问题。

## 9. chunk-v2 候选索引 A/B（2026-10-03）

用**同一套**端到端评测对比（`-Drag.eval.index=<物理索引名>`，口径完全一致）：

| 指标（65 条） | v1（active） | v2（candidate） |
|---|---:|---:|
| Recall@5 | 46/52 | 45/52 |
| 负例误放 | 0/13 | 0/13 |
| 合并后候选数（均值） | 7.3 | 9.8 |
| 平均不同文章数 | 2.8 | 2.4 |
| **平均「单篇最多 chunk」** | **4.2** | **7.3** |
| **观测「单篇最多 chunk」** | **8** | **14** |

**结论：不切 alias。** 但要说准两件事：

1. **45 vs 46 差 1 条，在 52 条样本上不构成差异**——只能说"v2 在**文章级**召回上没有优势"，
   不能说"v2 不行"；
2. **当前指标测不出 v2 的设计价值**：`Recall@5` 是文章级指标，只要该文章**任意一个** chunk 命中即算召回，
   **对 chunk 质量不敏感**。v2 的价值（片段更完整、更聚焦）体现在 chunk 级，需要新的指标才能观测。

v2 的真实问题是**碎片副作用**，两处可修（均在 `ArticleRagChunker`）：

1. `toChunk` 给**每个** chunk 前置「标题：完整标题」→ 同一篇文章的 chunk 分数系统性趋同，
   必然一起冲进候选（这正是"一篇占满前 10"的机制）；
2. `flushStructuredNormal` **没有最小长度保护** → 79/87 字符的碎片（标题壳 chunk、单条引用块）
   成为独立候选：信息量极低，却因为带着完整标题而分数虚高。
   v2 平均 **16.1 chunk/篇（最大 45）**，v1 约 6。

**后续路径**：修碎片 → 补 **chunk 级指标** → 再对比。缺了 chunk 级指标这一步，怎么比都是盲的。

**评测缩样能力**（本轮新增，避免动辄跑全量）：

```powershell
# 按类型缩样（TITLE_EXACT 天然容易且占比最大，阈值类改动看这两类更有信息量）
.\mvnw.cmd "-Dtest=ArticleRagEndToEndEvalTests" test -DrunRagEndToEndEval=true `
  "-Drag.eval.type=PARAPHRASE,HARD_NEGATIVE"
# 按 id 缩样（调试单条）；指定物理索引做 A/B；用系统属性临时覆盖阈值
.\mvnw.cmd "-Dtest=ArticleRagEndToEndEvalTests" test -DrunRagEndToEndEval=true `
  "-Drag.eval.case=para-103,hard-003" "-Dblog.ai.rag.gate.rerank-threshold=0.58"
```

### 碎片修复后的重测（2026-10-03 晚）→ **新 v2 仍不能切**

给 `ArticleRagChunker` 加了 `MIN_CHUNK_CHARS = 100` 的碎片合并（低于它的片段并入相邻 chunk，
**只在不超过上限时合并、绝不丢内容**；100 与 v1 的 `withMinChunkSizeChars(100)` 对齐，保证 A/B 口径一致）。
重建候选索引 `chunk-v2-20261003-080946`：**370 → 265 chunks（−28%）**，碎片率（<100 字符）降到 **1.5%**。

同样 36 条代表样例（PARAPHRASE + HARD_NEGATIVE）：

| 指标 | v1（active） | 新 v2 |
|---|---:|---:|
| 正例 Recall@5 | 20/23 | **23/23** |
| **负例误放** | **0/13** | **11/13** ❌ |
| Gate ACCEPT / REJECT | 35 / 71 | 65 / **12** |
| 平均「单篇最多 chunk」 | 3.5 | 5.3 |
| 接受的片段数 / 样例 | — | 1.8 |
| 片段长度 P50 | — | 301 |

**碎片修复本身有效**（chunk −28%、碎片率 1.5%），**但新 v2 依然不能切**：

- **负例误放 11/13（84.6%）**，v1 是 0/13；
- 直接原因：**v2 的候选 rerank 分数整体上移**，在阈值附近大量过线——Gate 从"拒 71 条"变成"只拒 12 条"，
  几乎失去筛选作用；
- 上移来源：`embeddingContent` 里多了 `headingPath`（语义更完整）+ 碎片合并后片段更长。

**为什么 rerank 分布采样看不出来**（同口径 36 条，max 分位）：

| max 口径 | v1 | 新 v2 |
|---|---:|---:|
| 正例 min | 0.7570 | 0.7881 |
| 负例 max | 0.7736 | 0.7818 |
| **分离间隔** | **−0.0166** | **+0.0063** |

两者间隔都**接近 0**，v2 只好了 0.006（不可用），而 **max 口径看不出"候选整体上移"这个效应**——
真正的证据是 Gate 的接受/拒绝数量。
**方法学教训：判断阈值是否还合适，要看"过阈值的候选数量"，不能只看 max 分位。**

**结论**：

1. **v1 继续用，v2 保留为实验索引**——与 Codex 的判断一致，但现在有了数据与机制解释；
2. **v1 也不是"结构性安全"**：它的 rerank 间隔同样是负的（−0.0166），当前 0/13 有相当一部分功劳
   来自 **dense `similarity-threshold=0.55` 的粗筛**——Gate 是第二层补充，不是唯一防线；
3. 想真正提升要靠**提高 rerank 的区分度**（正负例 max 只差 0.006，没有可用缝隙），
   方向是让候选"少而准"（文章级聚合），而不是把切块继续切细。

## 10. 阶段决策

1. 端到端 runner 与 `text_type=document` A/B 已完成；
2. P2 Markdown 结构化切块已完成旁路实验，但没有证明相对 `chunk-v1` 的稳定收益；
3. 按学习项目的投入产出比，P2 以 **No-Go** 收口：不切 alias，不扩大全量 A/B，清理未上线旁路代码；
4. RAG 主线转为维护状态：以后只对真实失败样例做定点修复，不再主动追加 IK、RRF、升维或 Parent-Child Retrieval。

### 2026-10-03 · 更换 Rerank 模型后的定向复验

当前运行模型已变为 `qwen3-vl-rerank`，原配置却误用了 `qwen3-rerank` 的分数分布，
将 Gate 阈值设为 `0.58`。同一批 12 条代表性样例中，当前模型的正例最低分是 `0.8377`，
负例最高分是 `0.7043`；因此阈值重标定为 `0.75`。

为控制额度，本轮没有重跑全量，只跑了 5 条定向 E2E：

| 候选索引 | 代表性正例 | 硬负例误放 | 结论 |
|---|---:|---:|---|
| `chunk-v2-20261003-065124` | 3/3 | 0/2 | 旧 v2 候选在当前模型 / Gate 下通过定向复验 |
| `chunk-v2-20261003-080946` | 3/3 | 0/2 | 碎片合并后的最新 v2 候选同样通过 |

最新 v2 的接受片段长度为 min / P50 / max = `109 / 306 / 775`，本次缩样中 `<100` 字符碎片为 `0`。
但 `para-002` 仍返回了未在当前标注中的 MySQL / Redis 文章，单看 Recall 会掩盖“返回偏多”；
该结果可能是真的多篇可接受，也可能是精度问题，需先人工核对内容再标注，不凭单例调阈值。

本轮只得出：**新模型下 `0.75` 恢复了 Gate 的基本安全边界，最新 v2 值得继续评估；样本不足以支持切 alias。**

---

## 11. 2026-10-21 全量复测：`qwen3-vl-rerank` 引入 3 条硬负例误接收

### 11.1 为什么要复测

10-03 之后检索链路有两处变化，但都**没有全量回归**：

1. **10-13 候选策略改动**：keyword 检索改为「按文章摊开」、`rerank.top-n=8`、`max-chunks-per-article=1`、`max-documents=30`
   —— 修的是「候选 8~13 篇、最终只给 LLM 一篇」；
2. **rerank 模型换成了 `qwen3-vl-rerank`**（10-03 晚原模型额度用尽），当时只做了 **12 例代表性定向复验**
   （正例 3/3、负例 0/2），**没有跑全量 70 条**。

### 11.2 复测环境

| 项 | 值 |
|---|---|
| 评测集 | `article-rag-eval-v1.jsonl`，70 条（52 正 + 18 负），与 10-03 相同 |
| 文章数据 | 23 篇已发布文章（`status=1 AND deleted_at IS NULL`），22 条 `TITLE_EXACT` 标题零漂移 |
| ES 索引 | `article_rag_active` → `article-rag-...-chunk-v1-20261003-031844`，141 文档 |
| 检索参数 | `top-k=10`、`similarity-threshold=0.55`、`gate.rerank-threshold=0.75` |
| rerank 模型 | 默认跑当前配置 `qwen3-vl-rerank`；对照组用系统属性覆盖为 `qwen3.7-text-rerank` |
| 前提检查 | 离线门 13 例通过（数据集守卫 / mock 链路 / keyword 摊开单测）；真实 dense 基线复现 10-03 |

### 11.3 结果

**dense 基线（无 Gate，与 10-03 对比）**：

| 指标 | 10-03 | 2026-10-21 | 差异 |
|---|---:|---:|---|
| recall@5 | 52/52 | 52/52 | 无 |
| mrr@5 | 1.0000 | 1.0000 | 无 |
| 正例 best 分 min~max | 0.5573 ~ 0.9234 | 0.5573 ~ 0.9233 | 一致 |
| 负例 top 分 min~max | 0.3174 ~ 0.6696 | 0.3173 ~ 0.6696 | 一致 |

→ **检索召回层本身没有退化**，变化全部发生在 rerank + Gate 层。

**端到端（含 Gate）**：

| 运行 | rerank 模型 | recall@5 | falseAccepts | 备注 |
|---|---|---:|---:|---|
| 10-03 全量 | `qwen3.7-text-rerank` | 51/52 (98.1%) | 0/13 (0%) | 旧候选策略 |
| **2026-10-21 全量** | **`qwen3-vl-rerank`（当前配置）** | **50/52 (96.2%)** | **3/13 (23.1%)** | 10-13 候选策略 |
| 2026-10-21 全量 | `qwen3.7-text-rerank` | 49/52 (94.2%) | 0/13 (0%) | 10-13 候选策略 |
| 2026-10-21 缩样 13 负例 | `qwen3-vl-rerank` | — | 3/13 | 复现 |
| 2026-10-21 缩样 13 负例 | `qwen3.7-text-rerank` | — | 0/13 | 最高负例分 0.7202 |

**3 条误接收（全部卡在阈值上方 0.01~0.02）**：

| case | query 摘要 | rerank 分 | 决策 |
|---|---|---:|---|
| `hard-003` | Redis/MySQL 哪个适合剪辑视频 | 0.7585 | ACCEPT |
| `code-003` | Spring AI 里 VectorStore 和 RAG 怎么配合 | 0.7597 | ACCEPT |
| `hard-004` | Kafka 能否替代图片识别模型 | 0.7736 | ACCEPT |

**2 条漏召回（正例被 Gate 拒）**：

| case | rerank 分 | 说明 |
|---|---:|---|
| `para-018` | 0.7016 | 正例最低分，被 0.75 拒掉 |
| `partial-002` | 0.7388 | 部分命中用例被拒 |

**结论**：`qwen3-vl-rerank` 下**正例最低分 0.7016 < 负例最高分 0.7736**，正负分布重叠，
**不存在能同时保住 52 条正例、拒绝 13 条负例的单一阈值**。抬阈值到 0.78 会同时干掉
`para-018` / `partial-002` 等 5 条左右的正例候选，漏召回恶化远大于误接收改善。

### 11.4 变量分离：误接收来自模型，不是 10-13 候选改动

同一阈值、同一 13 条负例，只把模型换回 `qwen3.7-text-rerank`：

| 模型 | falseAccepts | 负例最高分 | 候选分布 |
|---|---:|---:|---|
| `qwen3-vl-rerank` | 3/13 | 0.7736（hard-004） | EMPTY=12 / HYBRID=53 |
| `qwen3.7-text-rerank` | **0/13** | **0.7202（hard-003）** | EMPTY=16 / HYBRID=49 |

且对照组在**更多候选**（`mergedAvgDistinctArticles=7.5`，新模型那轮是 3.5）的情况下仍然 0 误接收
——说明区分度差异主要来自模型，候选策略只是次要因素。

**但候选策略确实有代价**：同样用 `qwen3.7-text-rerank`，recall 从 10-03 的 51/52 降到 49/52，
即 10-13 的「候选摊开」在挽回"只给一篇"的同时，也带走了 2 条正例。

### 11.5 建议（待决策）

| 方案 | recall@5 | falseAccepts | 评价 |
|---|---:|---:|---|
| 保持 `qwen3-vl-rerank` + 0.75（现状） | 96.2% | **23.1%** | 引用错误风险高，不推荐 |
| 保持 `qwen3-vl-rerank` + 抬阈值 0.78 | 约 88~90% | 0% | 用 5+ 条漏召回换 3 条误接收，不划算 |
| **切回 `qwen3.7-text-rerank` + 0.75** | 94.2% | **0%** | **推荐**：误接收清零，代价是 2 条召回（相对 10-03） |
| 换其他区分度更好的 rerank 模型 | 待测 | 待测 | 需要重新标定阈值，成本和风险更高 |

**推荐切回 `qwen3.7-text-rerank`**：本次实验证明该模型当前额度可用；改动只有
`blog.ai.rag.rerank.model` 一行，阈值 0.75 无需重标定（其标定本来就是按该模型做的）。
**已于 2026-10-21 执行切回并用默认配置全量复跑确认，见 §11.7。**

### 11.6 复现命令

```powershell
# 前提：注入 ALIYUN_API_KEY / ALIYUN_WORKSPACE_ID
# 当前配置（qwen3-vl-rerank）
.\mvnw.cmd "-Dtest=ArticleRagEndToEndEvalTests" test -DrunRagEndToEndEval=true

# 变量分离：换回旧模型
.\mvnw.cmd "-Dtest=ArticleRagEndToEndEvalTests" test -DrunRagEndToEndEval=true "-Dblog.ai.rag.rerank.model=qwen3.7-text-rerank"

# 只跑负例
.\mvnw.cmd "-Dtest=ArticleRagEndToEndEvalTests" test -DrunRagEndToEndEval=true "-Drag.eval.type=HARD_NEGATIVE"
```

报告落点：`blog-system/target/rag-end-to-end-eval-article_rag_active.txt`（每次运行覆盖，
如需对比留档，跑完先改名备份）。

### 11.7 执行记录：已切回并用默认配置确认（2026-10-21）

**配置改动**：`application.yml` → `blog.ai.rag.rerank.model: qwen3.7-text-rerank`（阈值 0.75 不变），
并在该段写明切换依据，留档于本节。

**确认方式**：不传 `-Dblog.ai.rag.rerank.model` 覆盖，直接用默认配置跑全量 E2E；
启动日志确认 `model=qwen3.7-text-rerank`。

**结果（与 §11.4 的覆盖实验逐项一致）**：

```text
[RAG-E2E-SUMMARY] cases=65 positives=52 recall@5=49/52(0.9423) negatives=13 falseAccepts=0(0.0000)
[RAG-E2E-STRATEGIES] {EMPTY=16, HYBRID=49}
[RAG-E2E-GATE-REASONS] {ACCEPT_RERANK=81, ACCEPT_STRONG_LEXICAL=22, REJECT_LOW_RELEVANCE=161}
```

| 指标 | 切回后 | 判断 |
|---|---:|---|
| falseAccepts | 0/13 | 达标（回到 10-03 的安全边界） |
| recall@5 | 49/52 (94.2%) | 比 10-03 的 51/52 少 2 条，差异来自 10-13 候选改动，登记为已知欠账 |
| EMPTY 策略占比 | 16/65 | 比 vl 模型（12/65）更保守，符合「宁缺勿错」 |

**归档**：

- `docs/rag-notes/rag-e2e-2026-10-21-text-rerank-run1.txt`（text 模型全量，切回前用 `-D` 覆盖跑的第一次）
- `docs/rag-notes/rag-e2e-2026-10-21-qwen3-7-text-rerank.txt`（text 模型全量，切回后用默认配置确认）

⚠️ **vl 模型全量报告未保留**：`target/` 下的报告在随后跑「13 条负例」和「text 全量」时被覆盖，
vl 那轮的逐条数据以 §11.3 / §11.4 摘录为准；如需重新留档，用 §11.6 命令重跑一次并立即改名备份。

**教训**（与部署手册 §463 同源，本次是实例）：换 rerank 模型必须同步重标 `gate.rerank-threshold`。
10-03 切换 `qwen3-vl-rerank` 时只做了 12 例缩样，阈值沿用 text 模型的 0.75；全量一跑就暴露
3 条硬负例误接收。缩样只能证明「没明显坏」，证明不了「分布没变」。


