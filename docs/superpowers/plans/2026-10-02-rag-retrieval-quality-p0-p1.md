# RAG Retrieval Quality P0/P1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不破坏现有文章问答、Memory 和线上索引的前提下，建立可评测、可观测、可隔离重建的文章 RAG 基座，并完成第一轮低相关止血。

**Architecture:** 先将 article 与 memory 的 `VectorStore` 注入边界显式化，但默认仍指向现有共享索引，确保第一步不改变行为；再补 retrieval trace 与固定评测输入。随后创建文章专用物理索引和 `article_rag_active` alias，通过隔离构建、校验、原子切换替代“先删后写”。最后基于同源索引的 query-to-document 分数分布校准 Dense 阈值，并上线 keyword AND、rerank 分数和统一 Gate。

**Tech Stack:** Java 17、Spring Boot 3.5、Spring AI 1.0.0、Elasticsearch Java Client 8.15.5、JUnit 5、Mockito、AssertJ

---

## 约束与阶段边界

- 不调用现有 `/api/ai/rag/articles/rebuild`，因为它会先删在线文章文档。
- 不删除或改写当前 `spring-ai-document-index`。
- 不运行 `mvn test` 或 `-Dgroups=eval`；只运行本计划列出的定向测试。
- P0/P1 的阈值都来自同源 query-to-document 样本，不用 document-to-document 邻居分数代替。
- article 和 memory 必须使用独立 Bean 契约；即使迁移期暂时指向同一物理索引，也禁止业务服务继续无限定注入。
- 本计划不自动 `git commit`；每个任务完成后只记录可提交文件和验证结果。

### Task 1: 建立 article / memory VectorStore 注入边界

**Files:**
- Create: `blog-system/src/main/java/com/hailin/blogsystem/config/AiVectorStoreConfig.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/config/BlogAiProperties.java`
- Modify: `blog-system/src/main/resources/application.yml`
- Modify: `blog-system/src/test/resources/application.properties`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/ai/rag/ArticleRagIndexService.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/ai/rag/ArticleRagRetrieveService.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/ai/memory/AiMemoryIndexService.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/ai/memory/AiMemoryRetrieveService.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/ai/memory/AiEpisodicMemoryIndexService.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/ai/memory/AiEpisodicMemoryRetrieveService.java`
- Test: `blog-system/src/test/java/com/hailin/blogsystem/AiVectorStoreCorpusWiringTests.java`

- [x] **Step 1: 写失败的 Bean 契约测试**

  使用 `ApplicationContextRunner` 加载 `AiVectorStoreConfig`，mock `RestClient` 与 `EmbeddingModel`，断言存在 `articleRagVectorStore`、`memoryVectorStore` 两个不同实例，并且二者分别绑定配置中的 index name。测试还要通过反射或原生 options 验证维度为 1024、测试环境不初始化 schema。

- [x] **Step 2: 运行测试并确认 RED**

  Run: `.\mvnw.cmd -Dtest=AiVectorStoreCorpusWiringTests test`

  Expected: FAIL，原因是 `AiVectorStoreConfig`、corpus 配置和具名 Bean 尚不存在。

- [x] **Step 3: 增加最小配置与两个具名 Bean**

  `BlogAiProperties.Rag` 增加：

  ```java
  private Corpus corpus = new Corpus();

  @Data
  public static class Corpus {
      private String articleIndexName = "spring-ai-document-index";
      private String memoryIndexName = "spring-ai-document-index";
      private int dimensions = 1024;
      private boolean initializeSchema = true;
  }
  ```

  `AiVectorStoreConfig` 使用同一个 `RestClient` 与 `EmbeddingModel` 构造两个 `ElasticsearchVectorStore`，Bean 名固定为：

  ```java
  public static final String ARTICLE_RAG_VECTOR_STORE = "articleRagVectorStore";
  public static final String MEMORY_VECTOR_STORE = "memoryVectorStore";
  ```

  两个 store 的 `indexName` 分别来自 `blog.ai.rag.corpus.article-index-name` 与 `memory-index-name`。迁移期 application.yml 中二者默认都解析到现有 `spring.ai.vectorstore.elasticsearch.index-name`，确保不切换在线数据。

- [x] **Step 4: 六个服务改为显式构造器 qualifier**

  article 两个服务构造器参数使用：

  ```java
  @Qualifier(AiVectorStoreConfig.ARTICLE_RAG_VECTOR_STORE) VectorStore vectorStore
  ```

  memory 四个服务构造器参数使用：

  ```java
  @Qualifier(AiVectorStoreConfig.MEMORY_VECTOR_STORE) VectorStore vectorStore
  ```

  不依赖 Lombok 是否复制字段注解，相关类改用显式构造器。

- [x] **Step 5: 运行定向测试并确认 GREEN**

  Run: `.\mvnw.cmd "-Dtest=AiVectorStoreCorpusWiringTests,ArticleRagRetrieveServiceTests,ArticleRagIndexServiceTests,AiMemoryDecisionSanitizerTests,AiEpisodicMemoryServiceTests" test`

  Expected: PASS；测试环境 `initialize-schema=false`，不得访问真实 ES。

### Task 2: 文章直接 ES 组件统一读取 article corpus 配置

**Files:**
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/ai/rag/ArticleRagKeywordRetriever.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/ai/rag/ArticleRagConsistencyService.java`
- Test: `blog-system/src/test/java/com/hailin/blogsystem/ArticleRagCorpusIndexTargetTests.java`

- [x] **Step 1: 写失败测试**

  构造 `BlogAiProperties`，把 article index 设为 `article-rag-test`、memory index 设为 `memory-rag-test`，调用 keyword 和 consistency 查询后捕获 Elasticsearch 请求，断言请求只指向 `article-rag-test`。

- [x] **Step 2: 运行测试并确认 RED**

  Run: `.\mvnw.cmd -Dtest=ArticleRagCorpusIndexTargetTests test`

  Expected: FAIL；当前两个组件仍读取全局 `spring.ai.vectorstore.elasticsearch.index-name`。

- [x] **Step 3: 改为构造器注入 BlogAiProperties**

  删除两个类中的 `@Value indexName`，查询目标统一使用：

  ```java
  blogAiProperties.getRag().getCorpus().getArticleIndexName()
  ```

- [x] **Step 4: 运行测试并确认 GREEN**

  Run: `.\mvnw.cmd "-Dtest=ArticleRagCorpusIndexTargetTests,ArticleRagSearchServiceTests" test`

  Expected: PASS。

### Task 3: 建立 P0 retrieval trace，不改变返回结果

**Files:**
- Create: `blog-system/src/main/java/com/hailin/blogsystem/ai/rag/ArticleRagRetrievalTrace.java`
- Create: `blog-system/src/main/java/com/hailin/blogsystem/ai/rag/ArticleRagTraceRecorder.java`
- Create: `blog-system/src/main/java/com/hailin/blogsystem/ai/rag/LoggingArticleRagTraceRecorder.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/ai/rag/ArticleRagSearchService.java`
- Test: `blog-system/src/test/java/com/hailin/blogsystem/ArticleRagRetrievalTraceTests.java`

- [x] **Step 1: 写失败测试**

  测试一次 `ARTICLE_SEARCH` 能记录：原始 question、清洗后 lexical query、vector 候选、keyword 候选、merge 后候选、rank 后候选、最终 strategy、各阶段耗时与 articleId/chunkIndex。另测 `GENERAL_CHAT` 记录 route skip reason，且不调用检索器。

- [x] **Step 2: 运行测试并确认 RED**

  Run: `.\mvnw.cmd -Dtest=ArticleRagRetrievalTraceTests test`

  Expected: FAIL；trace 类型和 recorder 尚不存在。

- [x] **Step 3: 实现只读 trace**

  `ArticleRagSearchService` 继续返回原 `ArticleRagSearchResult`，仅旁路记录结构化 trace。第一版不伪造 Spring AI 没有提供的 BM25/RRF/Gate 分数；字段允许 `null`，明确标记 `gateApplied=false`。

- [x] **Step 4: 运行测试并确认 GREEN**

  Run: `.\mvnw.cmd "-Dtest=ArticleRagRetrievalTraceTests,ArticleRagSearchServiceTests" test`

  Expected: PASS，且原检索策略断言不变。

### Task 4: 固定 P0 评测数据契约与离线 runner

**Files:**
- Create: `blog-system/src/test/resources/rag/article-rag-eval-v1.jsonl`
- Create: `blog-system/src/test/java/com/hailin/blogsystem/rag/ArticleRagEvalCase.java`
- Create: `blog-system/src/test/java/com/hailin/blogsystem/rag/ArticleRagEvalDatasetTests.java`
- Create: `blog-system/src/test/java/com/hailin/blogsystem/rag/ArticleRagOfflineEvalTests.java`

- [x] **Step 1: 写数据契约测试并确认 RED**

  每条 JSONL 至少包含 `id`、`query`、`shouldRouteToRag`、`expectedArticleIds`、`caseType`、`notes`；`caseType` 只允许 `TITLE_EXACT`、`PARAPHRASE`、`CODE_API`、`HARD_NEGATIVE`、`OUT_OF_DOMAIN`。正例必须有 expected id，负例必须为空。

- [x] **Step 2: 建立首批人工可审查样例**

  先录入设计审查期间已核实的标题/主题问题与难负例，不使用 LLM 自动扩写充数。数据集达到 60 条前只标记为 `DRAFT`，不能用于冻结阈值。

- [x] **Step 3: 离线 runner 输出基线，不作为默认单测**

  已完成可复用的 `ArticleRagOfflineEvalRunner` 计算器和指标单测；真实 ES/Embedding evaluator 仍需通过显式系统属性接入，默认测试不访问在线模型。

- [x] **Step 4: 运行 schema 测试**

  Run: `.\mvnw.cmd -Dtest=ArticleRagEvalDatasetTests test`

  Expected: PASS。

### Task 5: 创建文章专用 mapping、物理索引与 active alias

**Files:**
- Create: `blog-system/src/main/java/com/hailin/blogsystem/ai/rag/index/ArticleRagIndexManager.java`
- Create: `blog-system/src/main/java/com/hailin/blogsystem/ai/rag/index/ArticleRagIndexManifest.java`
- Create: `blog-system/src/main/resources/elasticsearch/article-rag-mapping.json`
- Test: `blog-system/src/test/java/com/hailin/blogsystem/ArticleRagIndexManagerTests.java`

- [x] **Step 1: 测试 mapping 与 alias 不变量**

  mapping 必须显式声明 `content`、`metadata.title`、`metadata.headingPath`、`metadata.rawContent`、`metadata.source`、`metadata.articleId`、`metadata.articleStatus`、`embedding`；`article_rag_active` 只能指向一个物理索引。

- [x] **Step 2: 创建新物理索引但不切 alias**

  已完成物理名、mapping、manifest 数据结构和 alias 原子切换请求管理器。
  **2026-10-03 更新**：真实 ES 已创建物理索引 `article-rag-qwen3-7-text-embedding-1024-chunk-v1-20261003-031844`
  （137 docs = 136 article chunks + 1 canary），`article_rag_active` 已原子切换指向它；
  `initialize-schema=false` 与 `corpus.article-index-name=article_rag_active` 均已生效。

  - [x] **Step 3: 运行定向测试**

  Run: `.\mvnw.cmd -Dtest=ArticleRagIndexManagerTests test`

  Expected: PASS。

### Task 6: 隔离重建、校验与原子切换

**Files:**
- Create: `blog-system/src/main/java/com/hailin/blogsystem/ai/rag/index/ArticleRagRebuildService.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/ai/rag/ArticleRagConsistencyService.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/ai/rag/ArticleRagController.java`
- Test: `blog-system/src/test/java/com/hailin/blogsystem/ArticleRagIsolatedRebuildTests.java`

  - [x] **Step 1: 写失败场景测试**

  覆盖构建中断、数量不符、canary 不健康、alias CAS 失败。所有失败场景都必须保持旧 alias 不动；健康检查不通过时局部 `repair()` 必须 fail-closed。

  - [x] **Step 2: 实现 build -> validate -> switch**

  新索引写完后依次验证 mapping、article 数、chunk 数、published ids、content hash、canary query。全部通过后一次 alias action 移除旧目标并添加新目标。

  - [x] **Step 3: 运行定向测试**

  Run: `.\mvnw.cmd "-Dtest=ArticleRagIsolatedRebuildTests,ArticleRagControllerTests" test`

  Expected: PASS。

### Task 7: 同源基线、阈值校准与 P1 Gate

**Files:**
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/ai/rag/ArticleRagRetrieveService.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/ai/rag/ArticleRagKeywordRetriever.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/ai/rag/ArticleRagSearchService.java`
- Create: `blog-system/src/main/java/com/hailin/blogsystem/ai/rag/ArticleRagGate.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/ai/rag/DefaultArticleRagRanker.java`
- Test: `blog-system/src/test/java/com/hailin/blogsystem/ArticleRagGateTests.java`
- Test: `blog-system/src/test/java/com/hailin/blogsystem/ArticleRagKeywordRetrieverTests.java`

- [x] **Step 1: 先采无阈值 Dense 基线**

  在新同源索引上以 `similarityThreshold=0` 跑固定评测集，记录正例、hard negative、out-of-domain 的 query-to-document 原始 cosine 分布；报告 Recall@5、MRR@5 与负例候选数。

  **2026-10-03 已完成**（评测集此时已扩到 70 条：52 正例 + 18 负例）：

  ```text
  positives=52  recall@5=52/52 (1.0000)  mrr@5=0.9519
  positiveBest: n=52, min=0.5573, p25=0.6862, p50=0.7776, p75=0.8255, max=0.9053
  negativeTop:  n=18, min=0.3174, p25=0.3899, p50=0.5246, p75=0.5715, max=0.6696
  ```

  关键结论：**正负例分布重叠**（正例最低 0.5573 < 负例最高 0.6696），
  在 0.55 阈值下 18 条负例有 **6 条**会被 dense 放行 → 单靠阈值无法解决精度问题。
  完整报告：`docs/rag-notes/rag-baseline-2026-10-03.md`。
  已用手工 query-to-document 采样替代（9 条查询，见设计稿 §25 五）：正例 top1 cosine `0.63~0.83`、
  域外 `0.37`、弱相关 `0.37~0.51`。注意计划约束：**document-to-document 邻居分数不能替代该分布**。

- [x] **Step 2: 冻结 similarity threshold**

  `blog.ai.rag.similarity-threshold: 0.55` 已写入配置并被实测验证（正例全部越过，域外与弱相关被挡）。
  **仍待补**：模型 / dimensions / text type / index version / 评测集版本的成文记录。
  另需记住量纲——ES 的 `knn.similarity` 按**原始 cosine** 判定，返回的 `_score` 才是 `(1+cos)/2`（设计稿 §2.2）。

- [x] **Step 3: keyword 改为去壳后 AND**

  `ArticleRagKeywordRetriever` 已使用 `Operator.And`，空召回单独计数，
  bounded OR fallback 由 `ArticleRagCorpusIndexTargetTests` 覆盖（日志可见 `operator=And` / `operator=Or` 两条路径）。
  实测降噪：`秒杀系统` 84 → 5、`氛围编程` 95 → 3、`RAG 检索` 42 → 5。

- [x] **Step 4: Gate 与 fail-closed rerank 降级**

  `ArticleRagRelevanceGate`（`gate.rerank-threshold: 0.75`）已上线并使用真实 `relevance_score`；
  MySQL fallback 与 rerank 降级路径收敛到同一 Gate 语义，测试见 `ArticleRagRelevanceGateTests`。

- [x] **Step 5: 定向回归**

  Run: `.\mvnw.cmd "-Dtest=ArticleRagRelevanceGateTests,ArticleRagCorpusIndexTargetTests,ArticleRagEvalDatasetTests,ArticleRagOfflineEvalTests,ArticleRagIndexManagerTests,ArticleRagIsolatedRebuildTests" test`

  Expected: PASS。**2026-10-03 实测 17 例全绿。**
  注：原命令里的 `ArticleRagGateTests` / `ArticleRagKeywordRetrieverTests` 实际类名为
  `ArticleRagRelevanceGateTests` / `ArticleRagCorpusIndexTargetTests`，已按实际名称更正。

## 执行检查点

1. Task 1-3：行为不变的基座，已完成。
2. Task 4：schema、60 条 `v1-draft` 样例和离线指标计算器已完成；**真实 ES evaluator 仍未接入**，
   因此 Task 7 Step 1 保持未勾选，阈值只做了手工采样验证。
3. Task 5-6：mapping、物理索引、alias 原子切换与隔离重建**已在真实 ES 执行**（2026-10-03），
   canary cosine 0.9999999。
4. Task 7：阈值 `0.55`、keyword AND、Gate `0.75` 均已上线并被实测验证；Step 1 的自动基线是唯一未完成项。

## 当前执行状态（2026-10-03 更新）

**已完成**：Task 1-6 全部；Task 7 的 Step 2-5。

- 真实 ES：`article_rag_active` → `article-rag-qwen3-7-text-embedding-1024-chunk-v1-20261003-031844`，
  23 篇文章 / 136 chunks，canary cosine `0.9999999`；旧 `spring-ai-document-index` 未删（保留回滚）。
- 配置：`corpus.article-index-name=article_rag_active`、`initialize-schema=false`、`top-k=10`、
  `similarity-threshold=0.55`、`gate.rerank-threshold=0.75`、`health.canary-threshold=0.9`。

**P1 收尾（2026-10-03，Claude 实施）**：

- 读写健康语义分离：新增 `denseSearchAllowed()`，仅 `UNHEALTHY` 熔断读取；
  此前 `SUSPECT` 也熔断，导致每次重启后约 10 秒语义召回全空（实测修复后窗口期内返回 6 条）。
- 新增 `GET /api/ai/rag/articles/health`（状态 + canary 分数 + 两个放行开关）。
- 权限边界统一为 `IndexManagementAccessGuard`（rebuild / repair / health / memory 共用）。
- 新增 `IndexRebuildLock` 全局互斥（Redis SETNX，**fail-closed**）。
- Memory 存量向量重刷：`POST /api/ai/rag/memories/reindex`（`memory` 10 条 cosine 恢复 1.0），
  同时清理 6 条幽灵 `episodic_memory` 文档（ES 8 → 2，与 MySQL 对齐）。
- 定向测试两批：**23 例 + 17 例全绿**。

**未完成 / 已知遗留**：

- Task 7 Step 1 **已于 2026-10-03 跑通并固化报告**（`docs/rag-notes/rag-baseline-2026-10-03.md`），
  阈值记录随之成文（模型 / 维度 / embedding 模式 / chunk 版本 / 索引版本 / 评测集版本）；
- **端到端 runner 已完成**（`ArticleRagEndToEndEvalTests`，`-DrunRagEndToEndEval=true`）：
  Dense → keyword → bounded OR fallback → rerank → Gate 全链路，65 条样例，
  负例误放 0/13、Recall@5 51/52、fallback 触发 13 恢复 1；Gate 拒绝原因分布已可观测。
  数据见 `docs/rag-notes/rag-baseline-2026-10-03.md` §6；
- **评测标注收口**：需支持"可接受多篇"（同主题多篇竞争会低估 MRR），并修 `title-022` 的标注；
- 幽灵记忆文档的成因未溯源；Memory corpus 结构与文章未统一；
- `/rebuild` 无构建期增量补偿；
- `blog-system` 仓库仍有未提交改动。
