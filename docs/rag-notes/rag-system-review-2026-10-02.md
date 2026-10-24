# RAG 系统审查笔记（2026-10-02）

触发：页面手测发现「不相关文章也被检索回来」。本文只记录**已实测证实的事实**与**待决问题**，
不含方案定稿——方案在与姐夫探讨后再落。

审查方式：读代码 + 直连本地 ES(19200) / DashScope 实测 + 启动后端调真实接口 + 查阅历史运行日志。
**本次审查未修改任何代码，未写入 ES 生产索引。**

---

## 一、已证实的事实（带数字）

### 1. 向量检索当前完全失效（后端运行时确认）

| 查询 | 10-02 13:50 日志 | 10-02 20:47 实测 |
|---|---|---|
| `Java中的@Builder注解详解：优雅创建对象的建造者模式` | `vector=3`，召回该文章 chunk 1/0/5 | **未召回片段（0 条）** |
| `秒杀系统怎么防止超卖` | — | 0 条 |
| `RAG 检索怎么做` | — | 0 条 |
| `zzzz-test-no-result-20261001` | — | 0 条 |

- 验证路径：`GET /api/ai/rag/articles/search?question=...`（需 JWT；`jwt.secret` 本地默认值自签即可）
- 两条独立路径结论一致（手写 REST 调 DashScope + 后端 Spring AI 路径），排除调用方式差异
- 根因：**ES 索引里的向量与当前 embedding 服务产出的向量不在同一空间**
  - 同一段文本：ES 存量向量 vs 现场 embed → **cos ≈ −0.01 ~ 0.02**（采样 4 篇文章 + 1 条 memory，全部不同源）
  - 用 chunk 原文做自检索：**它自己都进不了 top3**（最高 cos 0.0706）
  - 但存量向量**彼此自洽**（跨文章 0.39~0.68，同文章相邻 chunk 0.757）→ 是另一个语义空间里的有效向量，不是噪声
- 叠加效应：`similarity-threshold: 0.65` 的真实含义是**原始 cosine ≥ 0.65**（原稿误写为 0.3，勘误见 §8）
  → 不同源 + 该阈值 = **恒返回 0 条，且不报错**（日志只写"未召回片段"）

### 2. keyword 检索假阳性爆炸（"不相关被检索回来"的直接来源）

RAG 索引 `content` 由 Spring AI 自动建 mapping：**text 字段、无 analyzer → standard 分词 → 中文单字切**；
`ArticleRagKeywordRetriever` 用 `match` 查询（**默认 OR 语义**）查单字段 content。

| 查询 | RAG keyword（standard + OR） | 站内搜索（IK + 显式 AND） |
|---|---|---|
| 秒杀系统 | **84 / 126 块命中** | 5 篇 |
| 氛围编程 | **95 / 126 块命中** | 3 篇 |
| RAG 检索 | **42 / 126 块命中** | 3 篇 |

- 同一项目、同一 ES，站内搜索 `ArticleSearchService` 早已踩过同一个坑并修好（其注释写明"搜『建造者』返回 17 条"），
  **RAG 侧未同步**
- 每次检索 `keywordHit=5` 顶格（日志实测），merged 5~7 条进入 rerank

### 3. 检索链路没有任何相关性门

- `ArticleRagContext` 只有 4 个字段（articleId / title / chunkIndex / content）——**分数在转换时被丢弃**
- `GteRerankV2ArticleRagRanker`：请求 `return_documents: false`，响应解析**只读 `index`，不读 `relevance_score`**
  → 模型 rerank 只剩排序，无最低分门槛
- `DefaultArticleRagRanker`：`shouldFilterUnmatchedContexts = !keywords.isEmpty() && hasKeywordMatchedContext`
  → **全部不命中时反而全保留**，且 `score += max(0, 20-i)` 给原始顺序兜底分
- 融合只有 `mergeContexts` 去重（vector 优先 + keyword 补位），**无 RRF、无加权**

### 4. prompt 反向加压

`ArticleRagPromptBuilder`：*"只要下面存在片段，就表示站内已经找到了相关文章，不能回答'没有找到相关文章'"*
→ 检索层的噪声被 prompt 强制认领。上次手测模型"诚实说明未直接命中"是模型自己扛住了指令。

### 5. 切块现状：不是语义切块（但切块本身与当前代码一致）

当前 `ArticleRagChunker` 的实际行为（读码确认，**所有路径产出 ≤ 800 字符**）：
段落 → 句子 → 逗号 → token → 字符，五层递降后**打包**到 ≤ 800 字符。

索引实测（126 个 article chunk；2026-10-03 用**正确 UTF-8 解码**复测）：

| 指标 | 实测值 | 说明 |
|---|---|---|
| 块长度 min/avg/max | 96 / **650** / **800** 字符 | **未超 800 上限，切块与当前代码一致** |
| 含 markdown 标题行（`^#{2,4}\s`） | 109 / 126 | 标题未单独成块，与正文混在一起 |
| 含代码围栏 ``` | 28 / 126 | — |
| **反引号数为奇数（代码块被腰斩）** | **4 / 126** | articleId ...281 chunk 5/6、...269 chunk 2/4 |
| 边界正落在围栏上 | 8 / 126 | 以 ``` 开头或结尾 |
| 带「标题：/摘要：」前缀的块 | **21 条**（= 已索引的 21 篇各 1 条） | **其余块失去标题上下文**（context dilution） |

> ⚠️ **勘误（2026-10-03，Claude 自查）**：原稿写“块平均 1461 / 最大 2239 字符 → 索引是旧版切块产物”，**该结论错误，已撤回**。
> 根因是当时的实测跑在 PowerShell 5.1 上：`Invoke-RestMethod` 对未声明 charset 的 JSON 按 ISO-8859-1 解码，
> **UTF-8 字节被当成字符**，长度虚高约 2.5 倍。改用 `WebClient` 取原始字节 + 显式 UTF-8 解码后为 **650 / 800**。
> 原稿“以「标题：」开头的块 = 0 条”同样是脚本中文字面量被破坏所致，实际为 21 条。
> **结论：切块与代码一致；索引真正脱节的只有 embedding 向量空间与漏索引 2 篇。**

**结论（已修正）**：切块风格仍不是语义切块（标题不单独成块、代码块会被腰斩），这是 P2 的工作；
但**不存在“索引切块落后于代码”**——脱节只发生在**向量空间**（旧索引全部漂移）与**覆盖度**（漏 2 篇），
而系统里没有任何机制能发现这两件事。

### 6. 一致性巡检抓不到这类问题

`ArticleRagConsistencyService` 只比对 MySQL 已发布文章 ID 与 ES 中 `source=article` 的 articleId 集合
（`missingInEs` / `extraInEs`）→ **ID 齐了就算“一致”**，不检查块内容、不检查向量是否需要重建。

### 7. 漂移定位（20:50 补充实测）

- 随机采样 20 条 chunk：同源 cos 分布 **-0.055 ~ 0.035，无一条 > 0.1** → 索引是**整体漂移**，不是新旧混源
- 10-02 当天更新过的文章（`...269` 11:43、`...271` 11:11、`...287` 13:50）**同样不同源**
- 而 13:50 的运行日志仍能正常召回 → **变更发生在 10-02 13:50 ~ 20:45 之间**（进程未重启）
  ⇒ 属于「运行期 embedding 服务不兼容变化」（供应侧版本变化或账号灰度），不是代码问题
- **ES 只索引了 21 篇，MySQL 已发布 23 篇**：缺 `2085208555163787277`（从 Copilot 到 Codex）、
  `2085208555163787293`（Java + AI）。`GET /api/ai/rag/articles/consistency` 本可报出（missingInEs），
  但从未跑过——**"索引漂移不可见"不只是向量层面，还有文档缺失**
- ⚠️ 连带风险：此时调用 `repair()`（或索引重试补写单篇）会用**当前模型**为个别文章写入向量，
  与其余 20 篇旧向量**混源**，排序将不可预测。索引整体不健康时应禁用局部 repair。
- **2026-10-03 用正确 UTF-8 解码复测（排除编码假象）**：旧索引 article cos = `0.0125 / 0.0164`、
  memory cos = `-0.0099 / 0.0013`、episodic cos = `0.0329 / -0.0140` → **漂移结论成立**；
  同期新索引 article cos = **1.0**，canary cos = **0.999999** → **文章侧确已同源修复**。
- ⚠️ **Memory corpus 至今未重建**：`blog.ai.rag.corpus.memory-index-name` 仍指向旧共享索引
  （配置注释也写明“Memory 迁移仍保留旧共享索引”）。后果有两个，都是真实的：
  ① 历史 memory / episodic 向量仍漂移（阈值 0.50 下恒空召回）；
  ② 新产生的记忆会用当前模型写入同一索引 → **该索引变成混源 corpus**，表现为“老记忆召不回、新记忆正常”。

### 8. 勘误：kNN `similarity` 的量纲（2026-10-02 二次修订）

**原稿写错**：`similarity-threshold: 0.65` 等价于原始 cosine ≥ 0.30。
**正确结论：它要求原始 cosine ≥ 0.65。**

- ES 返回的 `_score` 对 cosine 归一化为 `(1 + cosine) / 2`，但**请求侧 `knn.similarity` 按原始 cosine 判定**，
  两者不是同一量纲（Spring AI 只是把配置值原样转发给 `knn.similarity`）
- 判别性实验（构造可控 cos 的查询向量，本地 ES 8.13.4）：

| 查询向量 | 实际 cos | `similarity = 0.65` 结果 |
|---|---:|---|
| `t = 0.4` 混合向量 | **0.5547** | **被过滤** |
| `t = 0.7` 混合向量 | 0.9191 | 保留 |

若阈值作用在归一化 `_score` 上（即 cos ≥ 0.30），0.5547 本应保留 —— 实测被过滤，故阈值作用在**原始 cosine** 上。
（构造方法：`q = t·v + (1-t)·r`，`r` 为与 `v` 正交的随机单位向量，理论 cos = `t / √(t² + (1-t)²)`，实测与理论一致。）

**这个修正反转了风险方向**：

`0.65` 不是“形同虚设”的松阈值；但“很严”目前只能作为候选判断，不能冻结为全局结论。取一条文档向量查同索引，8 个近邻中 7 个 cos 落在 **0.6506 ~ 0.6988**，第 8 个是自命中 1.0；这些近邻包含同文档 chunk，也包含 Redis / 秒杀等技术上相邻但未必回答同一问题的文章，不能直接称为“同主题正例”。这个实验测的是 document → document 近邻分布，且存量文档疑似使用 query 模式；它不能替代真正 query → document 的正负分布。`text_type=document` 已确认会改变向量，切换模式后更不能沿用该样本作为阈值代理。
而已检查的历史日志里，能越过 0.65 的检索 query 都是文章标题形式（Workflow 拿标题当检索 query）；这只能说明标题型 query 在当前日志样本下能过阈值，不能推出所有口语提问都低于 0.65。

⇒ 重建索引恢复同源后**不能假设“向量就恢复了”**：先用 similarity=0 / 不传 similarity 采样同一 embedding 模式下真实 query → 正例 document、hard negative 和随机负例的 cos 分布，再定阈值。在 0.65 下直接验收，很可能得到一个“同源但仍 0 召回”的假修复。

---

## 二、与「标准 RAG 链路」的逐环节对照

| 环节 | 项目现状 |
|---|---|
| Query 预处理 | ❌ 几乎没有：只对 keyword 分支做停用词替换；无查询改写 / 扩展 / 多轮指代消解 / 路由 |
| 问题向量化 | ✅ 有 |
| 语义召回 | ⚠️ 有实现，但**当前 0 召回** |
| 关键词召回 | ⚠️ 有实现，但**单字 OR**（84/126 假阳性） |
| 多路融合 | ⚠️ 只有 merge 去重，无 RRF / 加权 / 分数 |
| Rerank 精排 | ⚠️ 有模型 rerank，但**只排序不过滤**，分数丢弃 |
| 相关性门 / 拒答 | ❌ 完全没有 |
| Prompt 拼装 | ⚠️ 有，但话术强制认领 |
| 生成 + 溯源引用 | ✅ references + `[1][2]` 编号齐全 |
| 上下文压缩 / 预算 | ❌ 无（rerank 直接 top5 拼进 prompt） |
| 评测集 | ❌ 无（RAG 侧只有 mock 单测：`ArticleRagSearchServiceTests` 等） |
| 检索观测 | ⚠️ 日志打印 articleId/title/chunkIndex，**没有分数** |

---

## 三、待决问题（姐夫拍板，本次不执行）

1. **怎样安全重建 RAG 索引**
   - 已决定可以重建，但不能直接把当前 `POST /api/ai/rag/articles/rebuild` 当成最终方案：它先删除 active 文章文档再写回，中途失败会留下空窗；
   - 当前物理索引还混存 article / memory / episodic_memory，共用一个未限定 VectorStore；应先拆 corpus，再创建新物理文章索引、校验后原子切 alias；
   - 重建后先做无阈值 canary / 自检索和真实 query → document 正负分布采样，再选择 `similarity-threshold`，不能预设“cos 应回到 0.9+”或沿用 0.65 验收；
   - 新索引会顺带按当前 chunker / mapping 重建，但是否达到质量门槛仍由评测决定。
2. **keyword 检索改造方向**
   - A：给 RAG 索引 `content` 加 IK + 显式 AND（保持 chunk 粒度，需接管索引 mapping）
   - B：改查 `article_search_index`（IK + AND + 权重，已验证），但那是文章级索引，需转 chunk 片段
   - C：BM25 粗排 + 向量精排两段式（依赖向量先恢复）
3. **embedding 维度是否提升**（1024 → 更高）
   - 当前端点已实测支持 `1024 / 1536 / 2048 / 2560`；未知项不再是“能不能传”，而是收益是否值得；
   - 倾向先保留 1024 基线：维度切换会换向量空间，必须整体重建；小语料下当前瓶颈不在表征容量；
   - 若要做，作为**可评测超参** A/B（同一评测集比 Recall@5 / MRR、负例误接收率、延迟和索引资源），不靠直觉。
4. **是否需要"向量同源自检"**（把换模型/模型静默升级 → 必须 rebuild 变成系统约束，而不是靠人记得）

---

## 四、存疑 / 未验证

- 索引与当前 embedding 不同源的具体成因未定论：可能是供应商侧模型版本变化，也可能是索引陈旧
  （两种情况的重建动作相同，故不阻塞）
- 「10-02 13:50 好用、20:47 失效」的切换点未定位（期间无代码变更记录）
- `dimensions=1024 / 1536 / 2048 / 2560` 和 `text_type=document` 可透传性已经实测；未决的是质量收益与是否值得自定义 Embedding 调用

---

## 五、复现命令

```powershell
# 1) 启动后端（需注入 ALIYUN_API_KEY）
cd blog-system; $env:ALIYUN_API_KEY = [Environment]::GetEnvironmentVariable('ALIYUN_API_KEY','User'); .\mvnw.cmd spring-boot:run

# 2) 自签 JWT 调检索调试接口（jwt.secret 本地默认值）
#    secret = blog-system-dev-secret-change-later, payload = {"userId":<id>,"jti":"<uuid>","exp":<epoch>}
GET http://localhost:8080/api/ai/rag/articles/search?question=<urlencoded>

# 3) 直连 ES 看块与向量
POST http://localhost:19200/spring-ai-document-index/_search
{ "size":200, "query":{"term":{"metadata.source.keyword":"article"}}, "_source":["content","metadata.articleId","metadata.chunkIndex"] }

# 4) 对照站内搜索（IK + AND）
POST http://localhost:19200/article_search_index/_search
{ "query":{"bool":{"must":[{"multi_match":{"fields":["title^3","summary^2","content"],"query":"秒杀系统","operator":"and"}}]}} }
```
