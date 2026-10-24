# 文章 RAG 项目总结与简历讲法

本文用于总结 `01myBlog` 中文章 RAG 能力的实现思路、核心链路、关键代码和面试表达。它不是开发接力笔记，而是用于复盘项目、整理简历亮点和准备面试讲解。

## 项目定位

`01myBlog` 的 RAG 能力不是单独做一个问答 demo，而是接在真实博客业务上的文章知识检索系统。

目标是让 AI 助手能够基于站内文章回答问题，并且返回可点击的参考来源。它覆盖了文章生命周期、检索召回、prompt 注入、AI 回答、来源展示、索引一致性检查和手动修复等完整链路。

一句话概括：

```text
基于 Spring Boot + Spring AI + Elasticsearch，为博客文章构建 RAG 检索增强能力，让 AI 助手可以结合站内文章回答问题，并返回可追溯的参考来源。
```

## 整体链路

### 索引写入链路

文章进入 RAG 之前，先要被切分、向量化并写入 Elasticsearch。

```text
文章发布 / 更新
  ↓
触发 ArticleRagSyncService 异步任务
  ↓
ArticleRagIndexService 查询 MySQL 文章
  ↓
ArticleRagChunker 切分标题、摘要、正文
  ↓
Spring AI VectorStore 调用 Embedding 模型生成向量
  ↓
写入 Elasticsearch
```

文章隐藏或删除时，会删除对应 ES 索引：

```text
文章隐藏 / 删除
  ↓
触发 ArticleRagSyncService 异步任务
  ↓
ArticleRagIndexService.deleteArticleIndex(articleId)
  ↓
删除 ES 中该 articleId 对应的文章 chunk
```

这条链路解决的问题是：

- 文章数据源仍然以 MySQL 为准。
- ES 只作为 RAG 检索索引。
- 文章状态变化时，RAG 索引也随之更新，避免隐藏文章或删除文章继续被 AI 引用。

### 用户提问链路

用户通过 AI 助手提问时，后端会根据意图选择不同的 RAG 策略。

```text
用户提问
  ↓
AiMessageServiceImpl 识别意图
  ↓
ArticleRagSearchService 编排检索策略
  ↓
召回 ArticleRagContext
  ↓
ArticleRagPromptBuilder 拼接上下文到 prompt
  ↓
调用大模型生成回答
  ↓
SSE STOP 事件返回 references
  ↓
前端展示可点击参考来源
```

核心 DTO 是 `ArticleRagContext`，它把不同来源的检索结果统一成一种结构：

```java
public record ArticleRagContext(
        Long articleId,
        String title,
        Integer chunkIndex,
        String content
) {
}
```

这样无论结果来自 ES 向量检索、ES keyword 检索，还是 MySQL fallback，后续 prompt 和 references 都可以复用同一套逻辑。

## 核心业务能力

### 当前文章问答

在文章详情页，用户经常会问：

```text
这篇文章讲了什么？
帮我总结这篇文章
这篇文章适合什么人看？
```

这类问题不适合优先走全站 RAG，因为“这篇文章”明确指当前页面。如果仍然全站检索，可能召回其他文章，导致回答跑偏。

当前实现思路：

```text
如果意图是 ARTICLE_DETAIL_QA
  ↓
从 PageContext 中拿当前 articleId
  ↓
直接查询 MySQL 当前文章
  ↓
把当前文章内容放进 prompt
  ↓
references 至少包含当前文章
```

这体现的是业务优先级：

```text
明确上下文 > 全站语义检索
```

### 站内文章搜索

用户也可以问：

```text
有没有 Redis 相关的文章？
找一下秒杀系统相关内容
推荐几篇 Spring Boot 的文章
```

这类属于 `ARTICLE_SEARCH`，更像站内搜索。当前策略不是单纯依赖向量检索，而是使用轻量混合检索：

```text
ES 向量检索
  +
ES keyword 检索
  ↓
合并去重
  ↓
规则 rerank
  ↓
如果仍然为空，再 MySQL LIKE 兜底
```

这解决了两个问题：

- 向量检索适合语义相关，但可能漏掉非常明确的关键词。
- keyword 检索适合精确词命中，比如 Redis、MySQL、Spring Boot。

核心代码在 `ArticleRagSearchService`：

```java
List<ArticleRagContext> vectorContexts = articleRagRetrieveService.retrieve(question);

List<ArticleRagContext> contexts;
if (isArticleSearch(intent)) {
    List<ArticleRagContext> keywordContexts = articleRagKeywordRetriever.retrieve(question);
    contexts = mergeContexts(vectorContexts, keywordContexts);
} else {
    contexts = vectorContexts;
}

List<ArticleRagContext> rankedContexts = articleRagRanker.rank(question, contexts);
```

如果 ES 召回为空，才走 MySQL 兜底：

```java
if (isArticleSearch(intent)) {
    List<ArticleRagContext> fallbackContexts = buildMysqlFallbackContexts(intent);
    if (!fallbackContexts.isEmpty()) {
        return new ArticleRagSearchResult(
                ArticleRagSearchStrategy.MYSQL_FALLBACK,
                fallbackContexts
        );
    }
}
```

这个设计的重点是：MySQL fallback 不是替代 RAG，而是兜底来源统一。即使 ES 因为索引缺失、阈值过高或 embedding 效果问题没召回，AI 仍然可以基于 MySQL 搜索结果回答，并且前端仍然显示 references。

## 工程优化

### source 过滤

ES 中可能不止有文章向量，也可能有测试数据或其他来源的数据。检索文章 RAG 时，需要限制：

```text
source = article
```

向量检索和 keyword 检索都做了来源过滤，避免非文章数据占用 topK。

keyword 检索示例：

```java
.bool(b -> b
        .must(m -> m
                .match(mm -> mm
                        .field("content")
                        .query(question.trim())
                )
        )
        .filter(f -> f
                .term(t -> t
                        .field("metadata.source.keyword")
                        .value(FieldValue.of("article"))
                )
        )
)
```

### 轻量混合检索

当前项目没有直接上复杂搜索引擎排序框架，而是先做轻量混合检索：

```text
vector retrieve
keyword retrieve
merge by articleId + chunkIndex
rerank
```

合并去重逻辑：

```java
private String contextKey(ArticleRagContext context) {
    return context.articleId() + ":" + context.chunkIndex();
}
```

去重原因：

- 向量检索和 keyword 检索可能命中同一个 chunk。
- 重复 chunk 会浪费 prompt token。
- 重复来源会影响 AI 回答和前端展示。

### 规则 rerank

当前没有接真实 rerank 模型，而是通过 `ArticleRagRanker` 接口预留扩展点，默认实现使用规则排序。

规则包括：

```text
标题命中关键词：加高分
正文命中关键词：加中等分
chunkIndex = 0：加分
原始召回顺序靠前：保留一定权重
```

核心代码：

```java
for (String keyword : keywords) {
    if (title.contains(keyword)) {
        score += 10;
    }
    if (content.contains(keyword)) {
        score += 3;
    }
}

if (context.chunkIndex() != null && context.chunkIndex() == 0) {
    score += 2;
}
```

为什么这样做：

- 标题命中通常比正文命中更能说明文章主题相关。
- 第 0 个 chunk 通常包含标题、摘要和文章开头，信息密度更高。
- ES 原始排序不能完全丢掉，所以保留原始召回顺序的权重。

这个实现的价值不在于规则有多复杂，而在于把系统拆成了两层：

```text
retrieve 负责召回候选
rank 负责候选排序
```

后续如果要接模型 rerank，只需要替换 `ArticleRagRanker` 实现，不需要改主检索流程。

### 引用来源和编号

RAG 回答不是只输出文本，还会返回 references：

```text
AI 回答正文
  ↓
SSE STOP 事件
  ↓
references
  ↓
前端展示参考来源
```

references 按文章去重，前端可点击跳转文章详情。prompt 中也要求模型使用 `[1]`、`[2]` 这种引用编号，让回答和来源列表能对应起来。

这解决的是 RAG 系统的可信度问题：

```text
不是只让 AI 回答，而是告诉用户答案参考了哪些文章。
```

### 索引一致性巡检和手动修复

文章 RAG 的真实风险不是只在检索效果，还在数据一致性：

- MySQL 已发布文章，ES 没有索引，导致文章搜不到。
- MySQL 文章已隐藏或删除，ES 仍有旧索引，导致 AI 引用不该出现的内容。

当前实现了两个接口：

```text
GET  /api/ai/rag/articles/consistency
POST /api/ai/rag/articles/consistency/repair
```

只读巡检逻辑：

```text
查询 MySQL 已发布文章 ID
查询 ES 中 source=article 的 articleId
对比 missingInEs 和 extraInEs
```

手动修复逻辑：

```java
for (Long articleId : report.getMissingInEs()) {
    int indexedChunks = articleRagIndexService.indexArticle(articleId);
    if (indexedChunks > 0) {
        missingFixed++;
        chunkReindexed += indexedChunks;
    }
}

for (Long articleId : report.getExtraInEs()) {
    articleRagIndexService.deleteArticleIndex(articleId);
    extraDeleted++;
}
```

这里没有做自动修复，是因为自动修复属于运维动作，应该由开发者或管理员确认后执行。当前项目还没有管理员角色体系，所以它暂时作为本地开发和上线前检查接口。

## 核心类职责

### ArticleRagIndexService

负责真正的索引写入和删除。

```text
查询 MySQL 文章
切分文章
构造 Document
调用 VectorStore 写入 ES
删除指定文章索引
删除全部文章索引
```

### ArticleRagSyncService

负责异步调度索引同步。

```text
业务层触发
  ↓
@Async 后台执行
  ↓
调用 ArticleRagIndexService
```

当前用 `@Async` 是轻量实现。后续如果要对标高并发生产场景，可以替换为 MQ，但业务层不需要大改。

### ArticleRagRetrieveService

负责 ES 向量检索。

```text
用户问题
  ↓
embedding
  ↓
vector similarity search
  ↓
ArticleRagContext
```

### ArticleRagKeywordRetriever

负责 ES keyword 文本检索。

```text
用户问题
  ↓
ES match 查询 content
  ↓
过滤 source=article
  ↓
ArticleRagContext
```

### ArticleRagSearchService

负责检索策略编排。

```text
普通 RAG：vector
文章搜索：vector + keyword
为空兜底：MySQL LIKE
统一返回：ArticleRagSearchResult
```

### ArticleRagRanker

负责排序扩展。

```text
当前：规则 rerank
后续：可替换为模型 rerank
```

### ArticleRagConsistencyService

负责 ES 和 MySQL 的一致性检查与手动修复。

```text
check：只读巡检
repair：手动补索引和删除脏索引
```

## 简历写法

可以写成一条偏完整的项目经历：

```text
为个人博客系统实现基于 Spring AI 和 Elasticsearch 的文章 RAG 能力，支持文章发布、更新、隐藏、删除时异步同步向量索引；在 AI 助手中接入站内文章检索增强，支持当前文章问答、站内文章搜索、可点击参考来源和引用编号；针对搜索类问题实现 ES 向量检索 + keyword 检索的轻量混合召回，并通过规则 rerank 优化结果排序；补充 MySQL LIKE 兜底、source 过滤、索引一致性巡检和手动修复接口，提升检索可用性、可观测性和数据一致性。
```

如果简历空间较少，可以拆成 3 个短点：

```text
- 基于 Spring AI + Elasticsearch 实现博客文章 RAG，完成文章切片、向量化、索引写入、检索召回、prompt 注入和 references 展示闭环。
- 针对站内文章搜索实现轻量混合检索，结合 ES 向量召回、ES keyword 召回、规则 rerank 和 MySQL LIKE 兜底，提高关键词类问题的召回稳定性。
- 设计 RAG 索引同步与一致性维护机制，在文章发布、更新、隐藏、删除时异步维护 ES 索引，并提供一致性巡检和手动修复接口。
```

## 面试讲法

### 你为什么要做 RAG？

可以这样讲：

```text
博客系统里的 AI 助手如果只依赖大模型自身知识，就无法准确回答站内文章相关问题，也无法知道用户当前看的是哪篇文章。所以我引入 RAG，把站内文章作为知识库，让 AI 回答时先检索相关文章，再把召回内容拼进 prompt，同时把参考来源返回给前端。
```

重点：

- AI 助手要结合站内私有内容。
- 回答需要可追溯来源。
- RAG 是接在真实博客业务上的，不是孤立 demo。

### 你的 RAG 检索流程是什么？

可以这样讲：

```text
文章发布后会被切分成多个 chunk，通过 Spring AI VectorStore 调用 embedding 模型生成向量并写入 Elasticsearch。用户提问时，普通问答走 ES 向量检索；如果是站内搜索类问题，会同时走 ES 向量检索和 ES keyword 检索，合并去重后进入规则 rerank。最后把排序后的 ArticleRagContext 拼进 prompt，模型回答完成后通过 SSE 返回 references 给前端展示。
```

重点：

- 写入链路和查询链路都讲清楚。
- 普通问答和文章搜索策略不同。
- references 是完整体验的一部分。

### 为什么要做混合检索？

可以这样讲：

```text
单纯向量检索适合语义相近的问题，但对于 Redis、MySQL、Spring Boot 这种明确关键词，可能因为 embedding、阈值或 topK 原因漏召回。所以我在 ARTICLE_SEARCH 场景下增加 ES keyword 检索，和向量召回合并，再统一 rerank。这样既保留语义检索能力，也增强关键词搜索稳定性。
```

重点：

- vector 解决语义相关。
- keyword 解决明确词命中。
- 只在搜索意图启用 keyword，避免所有问题都增加额外查询。

### 为什么还要 MySQL fallback？

可以这样讲：

```text
ES 是检索索引，不是最终数据源。实际系统中可能出现索引延迟、同步失败、阈值设置过高等情况，导致 ES 没召回。对于 ARTICLE_SEARCH，如果 ES 没结果，我会用 MySQL title 和 summary 的 LIKE 搜索做兜底，并把结果转换成同一个 ArticleRagContext，这样 prompt 和 references 仍然走同一套逻辑。
```

重点：

- MySQL 是兜底，不是主检索。
- fallback 也统一 references，避免回答有结果但没有来源。

### rerank 是怎么做的？

可以这样讲：

```text
我先没有接模型 rerank，而是定义 ArticleRagRanker 接口，默认实现规则 rerank。规则主要考虑标题命中、正文命中、chunk 位置和原始召回顺序。这样能先把召回和排序解耦，后续如果文章量变多或排序效果不够，可以直接替换成模型 rerank 实现。
```

重点：

- 当前是轻量规则版。
- 接口已经隔离。
- 有明确升级路径。

### 文章更新后怎么保证 ES 一致？

可以这样讲：

```text
文章发布和更新会触发异步索引重建，隐藏和删除会触发 ES 索引删除。为了防止异步失败或异常导致 MySQL 和 ES 不一致，我还提供了一致性巡检接口，对比 MySQL 已发布文章 ID 和 ES 中 source=article 的 articleId，输出 missingInEs 和 extraInEs。同时提供手动修复接口，手动补索引或删除脏索引。
```

重点：

- 业务事件同步。
- 巡检发现问题。
- 手动修复解决问题。
- 自动修复留给管理员体系和上线阶段。

### 为什么没有直接上 MQ 或模型 rerank？

可以这样讲：

```text
我当前项目先实现成熟系统会遇到的问题边界，而不是一开始堆重型组件。异步索引用 @Async 实现，业务层通过 ArticleRagSyncService 触发，后续可以平滑替换为 MQ。rerank 通过 ArticleRagRanker 接口隔离，当前用规则实现，后续可以替换成真实模型 rerank。这样既能体现工程设计，也避免现阶段为了技术而技术。
```

重点：

- 不是不会做，而是当前阶段采用轻量实现。
- 边界已经留好。
- 后续升级不需要推翻主流程。

## 可以继续优化但不是当前必须

这些属于后续增强，不影响当前 RAG 主体完成：

- `rebuild`、`repair` 接口上线前加管理员权限或开发环境限制。
- 用 MQ 替换 `@Async`，增强高并发下的可靠性和重试能力。
- 将规则 rerank 替换为真实模型 rerank。
- keyword 检索扩展成更完整的 BM25 多字段检索，比如 title、summary、content 加不同权重。
- 增加 RAG 管理页，展示索引状态、巡检结果和手动修复入口。
- 增加定时一致性巡检和告警。

当前阶段已经完成的是：

```text
RAG 主链路
重要业务场景
轻量混合检索
规则 rerank
一致性巡检
手动修复
references 可追溯展示
```

所以这部分已经可以作为项目中的一个完整 AI/RAG 模块来讲。
