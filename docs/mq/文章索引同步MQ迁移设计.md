# 文章索引同步迁移 RocketMQ：设计稿（第二批）

> 状态：**设计稿 · 已评审（2026-10-22）**：核心架构（outbox → relay → ORDERLY → 回查 DB → 双索引）通过，无硬伤；
> 按 §10 的 S1→S2→S3 切片实施；三个取舍先观察、不在本刀定死（§12.1）
> 前置：第一批（AI 记忆事件）已实施，见 `docs/mq/rocketmq-integration-design.md` §11
> 上游约束：同文件 §12 的六条硬约束
> 涉及代码：`ArticlesServiceImpl` / `ArticleRagSyncService` / `RagRetryQueue` / `RagRetryTask` /
> `ArticleRagIndexService` / `ArticleSearchService` / `ArticleRagConsistencyService` / `ArticleRagIndexHealthService`

## 0. 一句话

把"文章写库 → RAG 向量索引 + 站内搜索索引"从 **@Async + Redis ZSet 重试** 换成
**DB outbox（事务内落事件）→ RocketMQ（按 articleId 顺序）→ 消费者回查 DB 后双索引同步**，
用"至少一次投递 + 消费端幂等 + 定时对账"替代现在的"at-most-once + 失败转人工"。

## 1. 现状与问题

### 1.1 现在的链路

```
ArticlesServiceImpl（7 个文章变更入口）
  writeArticle / updateArticle / updateArticleTitle / updateArticleVisibility
  / deleteArticle / hideArticle / publishArticle
        │
        ├─ safelyIndexArticleRag(id) ──→ @Async ArticleRagSyncService.indexArticle
        │        └─ 3 次同步重试 → 失败写 rag:index:fail:* + 排 ZSet(最多 5 次) → 转人工
        ├─ safelyDeleteArticleRagIndex(id) ──→ @Async deleteArticleIndex（同上的重试链）
        └─ safelyIndexArticleSearch(id) ──→ **同步** 写站内搜索 ES（吞异常只打日志）

另：ArticleRagController 手动 rebuild / consistency / repair；
    ArticleRagIndexHealthService 每 5 分钟 canary，REBUILDING 期间拒绝局部写入；
    ArticleViewCountSyncTask / 热度榜等共用 `spring.task.scheduling.pool.size: 1`。
```

### 1.2 六个真缺口（按严重度）

| # | 问题 | 证据 |
|---|---|---|
| ① | **投递依赖 @Async，进程崩溃即丢**：DB 已提交、异步任务没跑 → 索引永久脏，只能靠人工 rebuild | `ArticleRagSyncService` 方法级 `@Async`；DB 写入与任务提交之间无原子性 |
| ② | **ZSet 队列 at-most-once**：`takeDue` 用 Lua `ZRANGEBYSCORE + ZREM` 取走即删；进程在"取走未执行完"时崩溃 → 任务永久消失 | `RagRetryQueue.TAKE_DUE_SCRIPT` |
| ③ | **顺序性空白**：更新 = `deleteArticleIndex` + `indexArticle` 两条独立 `@Async`，并发操作同一 ES 文档；删除与索引也可能乱序 | `ArticleRagIndexService.indexArticle` 内部先 delete 再 add；两个 async 调用无依赖 |
| ④ | **事务边界不一致**：`updateArticleTitle` / `updateArticleVisibility` 是 `@Transactional`，在事务内就触发异步索引；其余写方法没有事务 | `ArticlesServiceImpl` 只有两个方法带 `@Transactional` |
| ⑤ | **站内搜索索引同步写在请求线程里**：发布/改可见性要等 ES 写完才返回；而且方法内部吞异常，失败静默 | `ArticleSearchService.indexArticle/deleteArticle` catch 后只 log |
| ⑥ | **对账只有手动接口，且有上限**：`check()` 用 `.size(1000)` 拉 ES 文档 → 超过 1000 个 chunk 后 `missingInEs/extraInEs` 会误报；没有定时巡检 | `ArticleRagConsistencyService.loadIndexedArticleIds` |

> 结论：这不是"把 @Async 换成 send"能解决的。换 MQ 的收益全部来自
> **投递不丢（outbox）+ 顺序一致（顺序消息）+ 失败可控（重试/死信/对账）**，三者缺一就会比现在更糟。

### 1.3 现有保障盘点（迁移时不能丢）

- `ArticleRagIndexHealthService`：`incrementalWritesAllowed()` 只在 `HEALTHY` 放行；`REBUILDING` 期间必须延期局部写入，否则和全量重建互相覆盖。
- `IndexRebuildLock`：全局重建互斥（Redis，fail-closed）。
- `ArticleRagRebuildService`：隔离物理索引 → canary → alias 切换；`ArticleRagController` 手动入口。
- `ArticleRagConsistencyService`：canary 之外的第二道事实校验（DB published vs ES 已索引），支持手动 repair。
- `RagRetryTask` 的教训：调度线程池只有 1 个线程，**定时任务只能"捞+投递"，不能在里面同步做 embedding/ES**。

## 2. 目标 / 非目标

**目标（本刀）**
1. 文章写入成功后，索引事件**不丢**（DB 事务内落 outbox）。
2. 同一篇文章的索引/删除**按序收敛**，重复投递幂等。
3. 站内搜索索引与 RAG 索引**同一事件收口**，失败可重试；发布接口不再同步等 ES。
4. 失败有死信与对账兜底，不再"转人工只能靠人翻日志"。
5. 可灰度、可回退；面试能讲清"不丢 / 不重 / 不乱 / 不合格怎么办"。

**非目标**
- 不做 MQ 集群/高可用、不做跨服务拆分（单机练习项目，讲清生产形态即可）。
- 不重做 embedding / chunk / rerank 算法；不动重建流程本身。
- 不动秒杀 Redis Stream、不动 AI 记忆第一批。
- 不做通用 outbox 框架（只服务这一个事件；第二个消费方出现再抽）。

## 3. 关键决策（评审重点）

| # | 决策 | 推荐 | 备选与不采用理由 |
|---|---|---|---|
| D1 | 投递原子性 | **DB outbox 表**：业务事务内 INSERT，relay 扫描投递 | after-commit + Redis pending：崩溃窗口仍在，且引入第二套存储；RocketMQ 事务消息：原生但 Spring 集成/回查成本高，本项目 outbox 足够且可对账 |
| D2 | 事件模型 | **单事件 `ARTICLE_CHANGED(articleId)`**，消费者回查 DB 决定 upsert/delete | 双事件（INDEX/DELETE）：两条消息乱序就错；事件带快照：消费时仍可能旧，等于把版本问题带进消费端 |
| D3 | 顺序 | **ORDERLY 消费，按 articleId 选队列**（`syncSendOrderly` + `ConsumeMode.ORDERLY`） | 并发 + 分布式锁：多一套锁与租约故障面；并发 + ES 版本比较：要改文档结构、每次多一次 ES get。顺序消息是 RocketMQ 原生能力，面试直接可讲 |
| D4 | 站点搜索 | **一起收口**到同一事件消费者 | 只迁 RAG：发布接口仍同步写 ES，且两套索引会长期不一致 |
| D5 | 旧机制退场 | **两阶段**：本刀保留旧路径（flag=false 可回退）；验证通过后第二刀删 `RagRetryQueue`/`RagRetryTask`/失败标记 | 同刀删除：回退只能回滚代码，演练期风险高 |
| D6 | 对账 | 定时巡检 + 手动 repair；**先修 1000 上限**（聚合或分页），只告警不自动修复 v1 | 自动修复：删除方向可能误删（ES 抖动时），先人工确认；缺失方向可后续放开 |
| D7 | 死信 | 先靠 **DLQ topic + 应用日志 + 对账兜底**；v1 不做自动重放 | 自动重放：与项目"哪个数是对的只有人知道"口径一致，不做 |
| D8 | 重建期门禁 | **relay 侧延期 + consumer 侧二次检查并重投 outbox** | 只在 consumer 里抛异常：重建几十秒内重试会打满重试次数进 DLQ，且顺序队列被阻塞 |

## 4. 详细设计

### 4.1 表结构：`article_index_outbox`

```sql
CREATE TABLE IF NOT EXISTS article_index_outbox (
    id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    article_id    BIGINT       NOT NULL COMMENT '文章 ID（同一文章的事件按 id 顺序投递）',
    status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SENDING/SENT/FAILED',
    attempts      INT          NOT NULL DEFAULT 0 COMMENT '已尝试投递次数',
    next_retry_at DATETIME     NOT NULL COMMENT 'PENDING 状态下最早可投递时间',
    locked_until  DATETIME     NULL COMMENT 'SENDING 租约到期时间（崩溃恢复用）',
    sent_at       DATETIME     NULL COMMENT '成功投递到 RocketMQ 的时间',
    trace_id      VARCHAR(64)  NULL COMMENT '写入请求的 traceId（relay 线程无请求上下文，必须落库）',
    last_error    VARCHAR(500) NULL,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_outbox_due (status, next_retry_at),
    KEY idx_outbox_article (article_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文章索引同步 outbox（事务内落事件，relay 投递）';
```

- 只存 `article_id`：**事件不带内容快照**，消费者回查 DB 保证拿到最新状态（D2）。
- `status` 四态见 §6 状态机；`attempts` 只统计 **relay 投递失败**（不是消费失败，消费失败由 MQ 重试）。
- 清理：每天删 `SENT` 且 `created_at < now - 7d`；`FAILED` 保留到人工处理。
- 同步进 `db/init.sql` + `src/test/resources/schema.sql`（H2 用 MySQL 兼容写法，与本项目现有表一致）。

### 4.2 写路径改造（7 个入口，统一成一个动作）

在 `ArticlesServiceImpl` 内新增唯一调用：

```java
articleIndexMutationPublisher.articleChanged(articleId);   // 只做这一件事
```

| 入口 | 现在 | 改后 |
|---|---|---|
| `writeArticle`（PUBLISHED 才建索引） | `safelyIndexArticleRag` | `articleChanged(id)`（草稿也会落事件，消费端回查发现未发布 → 删索引，天然正确） |
| `updateArticle` | 按 status 二选一 | `articleChanged(id)` |
| `updateArticleTitle` | `syncArticleRagIndex` | `articleChanged(id)`（已在事务内） |
| `updateArticleVisibility` | 按 target 二选一 | `articleChanged(id)` |
| `deleteArticle` | `safelyDeleteArticleRagIndex` | `articleChanged(id)` |
| `hideArticle` | `safelyDeleteArticleRagIndex` | `articleChanged(id)` |
| `publishArticle` | `safelyIndexArticleRag` | `articleChanged(id)` |
| 手动 rebuild / 初始化灌数据 | 不动（管理动作，不经过事件） | 不动 |

**事务边界**：`article_index_outbox` 的 INSERT 必须与文章写操作**同一事务**。
- 两个已有 `@Transactional` 方法：直接把 `articleChanged` 放进现有事务。
- 另外 5 个方法：统一用 **`TransactionTemplate` 只包住「写库 + `articleChanged`」**，
  不扩大到缓存/Redis/活跃度统计（避免把 Redis 动作卷进 DB 事务）。
  flag=true 时 publisher 在同一事务内插 outbox；flag=false 时 publisher 只注册 after-commit 动作
  （有事务就推迟到提交后，无事务时 `AfterCommitExecutor` 立即执行，语义一致）。
  - 不采用"整个方法加 `@Transactional`"：会改变现有副作用时序（`userActivityTracker`、缓存失效），且失败回滚语义变大。
- 发布者内部断言 `TransactionSynchronizationManager.isActualTransactionActive()`；不在事务里调用直接抛异常——**这种误用必须尽早炸**，否则又会退化成"先写库后投递"。

**站点搜索**：`safelyIndexArticleSearch` 从写路径移除；旧路径（flag=false）在 after-commit 里同时做 RAG + 搜索（见 §4.6），MQ 路径由消费者做。

### 4.3 Relay（outbox → MQ）

- **调度**：`@Scheduled(fixedDelayString = "${blog.ai.article-index.relay.interval-ms:2000}")`。
  只做"claim + 交给专用 executor"，**不在调度线程里同步发送**（调度池 `pool.size:1` 的硬约束）。
- **发送线程**：新建 `articleIndexOutboxExecutor`（**单线程**，core=max=1，有界队列）。
  单线程是为了保证**同一个 relay 内的发送顺序**；不同实例/不同轮次由 MQ 队列再做一次顺序兜底（D3）。
- **executor 有界队列的拒绝处理**：claim 前先看 executor 剩余容量，容量不足本轮不 claim；
  提交被拒绝时立即把已抢到的行 CAS 回 `PENDING`（否则要等 60s 租约才恢复，白等一轮）。
- **claim（多实例安全，不用 `SKIP LOCKED`，H2 也能测）**：
  ```
  ① SELECT id, article_id FROM article_index_outbox
     WHERE status='PENDING' AND next_retry_at <= NOW()
     ORDER BY id LIMIT :batch
  ② 对每行 CAS 抢占：
     UPDATE article_index_outbox SET status='SENDING', locked_until=NOW()+60s, updated_at=NOW()
     WHERE id=? AND status='PENDING'
     （affected=1 才算抢到；0 = 别的实例抢先，跳过）
  ③ 把抢到的行交给 executor 逐条发送
  ```
- **发送**：`rocketMQTemplate.syncSendOrderly(destination, message, /*hashKey*/ String.valueOf(articleId))`，
  其中 **destination = `topic + ":ARTICLE_CHANGED"`**。⚠️ tag 只能从 destination 的 `topic:tag` 解析：
  放 Spring Message header（`MessageConst.PROPERTY_TAGS`）不会进 RocketMQ tags 字段，消息会变成 `Tags=[null]`，
  消费端 selector 直接按"不匹配"过滤（offset 照涨、listener 不调用、无死信日志）。2026-10-23 联调踩过。
  消息体：`{eventId, eventType:"ARTICLE_CHANGED", articleId, traceId, occurredAt}`（≤ 200 字节）；
  header 只带 `KEYS=articleId`。
- **成功**：`UPDATE ... SET status='SENT', sent_at=NOW() WHERE id=? AND status='SENDING'`。
- **失败**：`status='PENDING'`, `attempts+1`, `next_retry_at = now + min(5s * 2^(attempts-1), 5min)`,
  `last_error=...`；`attempts >= 20` → `status='FAILED'` + ERROR 日志（对账/人工可发现）。
- **崩溃恢复**：每轮 relay 前先执行 `UPDATE ... SET status='PENDING' WHERE status='SENDING' AND locked_until < NOW()`（租约 60s）。发送成功后进程崩溃 → 行被恢复 → 重发 → **消费端幂等**（回查 DB + 覆盖写）。
- **清理**：每天 04:00 `DELETE FROM article_index_outbox WHERE status='SENT' AND created_at < NOW()-INTERVAL 7 DAY LIMIT 10000`（分批）。
- **重建期门禁**：claim 前检查 `healthService.incrementalWritesAllowed()`；不满足则**不 claim**（行留在 PENDING，`next_retry_at` 推到 30s 后），并打一条限频日志。这样重建期间不会堆积到 MQ 里，也不会撞消费端。

### 4.4 消费者

```java
@RocketMQMessageListener(
    topic = "${blog.ai.article-index.topic:article-index-sync}",
    consumerGroup = "${ROCKETMQ_ARTICLE_INDEX_CONSUMER_GROUP:blog-article-index-consumer}",
    selectorExpression = "ARTICLE_CHANGED",
    consumeMode = ConsumeMode.ORDERLY,
    messageModel = MessageModel.CLUSTERING,
    consumeThreadNumber = 1, consumeThreadMax = 2,   // min 线程=1；ORDERLY 并行度≈min(队列数, max)，不是全局串行
    maxReconsumeTimes = 3)                           // 避免一个坏文章阻塞队列 16 次
```

> 线程数说明（2026-10-22 用 starter 字节码确认）：注解的 `consumeThreadNumber` 映射到
> `DefaultMQPushConsumer#setConsumeThreadMin`，`consumeThreadMax` 才是消费并发上限；
> ORDERLY 下**同一队列串行、不同队列并行**，默认 4 个队列 → 初始最多 2 篇同时索引。
> 初始取 max=2 是给 embedding 供应商限流兜底（与第一批记忆消费者同因），压测后再决定是否调到 4；
> 注解参数是编译期 int，改它需要发版，不适合做成运行期配置。

消费流程：

```
1. 从消息体取 articleId
2. 二次检查 healthService.incrementalWritesAllowed()
   否 → 在独立短事务中重新插入一条 PENDING outbox（next_retry_at=+30s）；
        只有 outbox 插入成功并提交后才 ACK，插入/提交失败必须抛异常交给 RocketMQ 重试，
        不能先 ACK 再补写，避免重建期间数据库故障造成事件丢失
3. 回查文章：articlesMapper.selectById(articleId)（@TableLogic 已处理软删除）
4. 分支：
   PUBLISHED →  ragIndexService.indexArticle(articleId)         // 内部先删旧 chunk 再加新 chunk
                articleSearchService.indexArticleStrict(article) // 新增 strict 版本，失败抛异常
   不存在/HIDDEN/DRAFT/DELETED →
                ragIndexService.deleteArticleIndex(articleId)
                articleSearchService.deleteArticleStrict(articleId)
5. 任何一步失败 → 抛出 → RocketMQ 按顺序模式重试（同队列阻塞）→ 3 次后进 DLQ
6. 成功 → 记录 [INDEX-MQ] 日志（articleId/action/durationMs）
```

**为什么必须回查 DB**：事件只带 articleId；发布 → 隐藏 → 再发布在消费端可能合并成一次执行，
最终收敛到 DB 的当前状态。回查能压缩过时事件的影响，但**不能单独解决并发消费的旧覆盖新**；
同一 `articleId` 仍必须用 ORDERLY（论证见 §6.3）。

**幂等**：不需要额外幂等表。`indexArticle` 是"先删后加"的覆盖写，`deleteArticleIndex` 删不存在也成功；
同一事件消费两次结果相同。

**strict 方法**：`ArticleSearchService.indexArticle/deleteArticle` 现在吞异常只 log，
消费者无法感知失败。新增 `indexArticleStrict(Articles)` / `deleteArticleStrict(Long)`（失败抛
`IllegalStateException`）；原有宽松方法保留给启动灌数据/重建，不动它们的行为。
其中 `deleteArticleStrict` 必须把 ES 的“文档不存在”响应（HTTP 404）视为成功，
因为删除操作本身要求幂等；只有网络错误、鉴权错误或 ES 5xx 等实际失败才抛异常触发 MQ 重试。

### 4.5 双索引执行顺序与失败语义

同一篇：先 RAG 后站内搜索。任一失败 → 整条消息失败重试（两个操作都幂等，重试成本可接受）。
不拆成两个 topic：拆分后"RAG 成功、搜索失败"需要两套对账，复杂度大于收益；本项目量小，整条重试更简单可靠。

### 4.6 旧路径（flag=false）保留与回退

开关：`blog.ai.article-index.use-mq`（env `AI_ARTICLE_INDEX_USE_MQ`），**默认 false**。

- `false`：`ArticleIndexMutationPublisher` 走原 `ArticleRagSyncService` + 同步站内搜索，
  但动作统一推到 **after-commit** 执行（`AfterCommitExecutor`，无事务立即执行）。
  这顺带修掉现有"事务内触发异步索引"的 ④ 号缺口。
- `true`：只落 outbox；relay/consumer 生效（两者都是 `@ConditionalOnProperty(use-mq=true)`）。
- **回退步骤**：等 outbox `PENDING=0` 且 consumer lag ≈0 → 把 flag 改 false → 重启。
  已在 topic 里的消息不会再被消费，但下次启用时会消费并回查 DB，结果仍然正确；不影响旧路径。
- **第二刀**（验证通过后）：删除 `RagRetryQueue` / `RagRetryTask` / `RAG_RETRY_QUEUE_KEY` /
  `rag:index:fail:*` 写路径 / `ArticleRagSyncService` 的同步重试与延迟队列逻辑；
  保留 `ArticleRagSyncService.rebuildPublishedArticles`（管理动作）或把 rebuild 收进新的管理服务。

### 4.7 对账与修复（兜底，不是主路径）

- **先修 `ArticleRagConsistencyService.check()` 的 1000 上限**（✅ 已实施 2026-10-23）：当前
  `loadIndexedArticleIds` 查询的是 chunk 文档，`.size(1000)` 限制的是**返回的 chunk 数**，
  不是文章数。已在本地 ES（alias `article_rag_active`）确认 mapping：
  `metadata.articleId` 是 `long`、`metadata.source` 带 keyword 子字段 →
  用 `composite` 聚合（`after_key` 翻页）收集文章 ID，不受 1000 限制；
  **不要用 `terms` 固定大 size**（超过桶上限会静默截断）。
  另注意 `metadata.dynamic: strict`：以后往 metadata 加字段必须先改 mapping——
  这也是不采用「ES 文档版本比较」路线的原因之一。
- **不要把现有 `ArticleRagConsistencyService.check()` 当成双索引对账**：它目前只比较
   MySQL 已发布文章与 RAG 向量索引，完全不检查 `article_search_index`。需要新增站内搜索索引
  的 ID/状态核对，或抽出一个同时检查 RAG 与搜索的 `ArticleIndexConsistencyService`，再由
  `ArticleIndexConsistencyTask` 每天 04:00 输出
  `[INDEX-RECONCILE] ragMissing=... ragExtra=... searchMissing=... searchExtra=...`；有差异打 ERROR（可接告警）。
  站内搜索侧已确认：`article_search_index` 的 `_id` 就是 articleId（字符串），文档里也有
  `articleId`(long) 与 `status`(keyword)；这一侧可直接按 `articleId` 聚合或扫描 `_id`，与 RAG 分开统计。
- 修复仍走手动接口 `POST /api/ai/rag/articles/consistency/repair`（`IndexManagementAccessGuard` 保护）；
  v1 不自动修（D6）。搜索索引的 repair 也要明确单独入口或纳入同一运维服务，不能默认由现有 RAG repair 覆盖。
- DLQ：不自动重放；靠对账发现差异 + 手动 repair / rebuild 恢复。DLQ 消息可在 RocketMQ 控制台查看。

### 4.8 观测

| 日志 | 内容 |
|---|---|
| `[INDEX-OUTBOX]` | claim 数 / 发送成功失败 / backlog（`SELECT COUNT(*) WHERE status='PENDING'`，每 N 轮打一次） |
| `[INDEX-MQ]` | articleId / action=upsert|delete / durationMs / retryCount |
| `[INDEX-RECONCILE]` | 每日对账摘要 |
| `[INDEX-GATE]` | 因 REBUILDING/UNHEALTHY 延期的事件数（限频，避免刷屏） |

建议后续加一个只读接口 `GET /api/ai/rag/index-outbox/stats`（PENDING/SENDING/FAILED 计数），
本轮可选（不阻塞本刀）。

### 4.9 配置项

```yaml
blog:
  ai:
    article-index:
      use-mq: ${AI_ARTICLE_INDEX_USE_MQ:false}
      topic: ${ARTICLE_INDEX_TOPIC:article-index-sync}
      relay:
        interval-ms: 2000
        batch-size: 20
        lease-seconds: 60
        max-attempts: 20
      consumer-group: ${ROCKETMQ_ARTICLE_INDEX_CONSUMER_GROUP:blog-article-index-consumer}

rocketmq:
  producer:
    send-message-timeout: 2000   # 单条发送超时，relay 单线程不被 broker 拖死
```

生产 broker 若关闭 `autoCreateTopicEnable`（README 推荐），发版前需手工建 topic / 消费组：

```bash
docker exec blog-rocketmq-broker sh mqadmin updateTopic   -n rocketmq-namesrv:9876 -c DefaultCluster -t article-index-sync
docker exec blog-rocketmq-broker sh mqadmin updateSubGroup -n rocketmq-namesrv:9876 -c DefaultCluster -g blog-article-index-consumer
```

### 4.10 发版顺序

1. 执行 `article_index_outbox` DDL（开发库 + 生产库）；
2. 部署代码（flag 默认 false，**业务结果与现在一致**；旧路径索引动作从请求线程/事务内统一挪到 after-commit，见 §4.6）；
3. 本地/预发打开 flag=true，验证：发布、编辑、隐藏、删除、重建期、broker 挂、消费 kill；
4. 生产打开 flag=true；观察 outbox backlog、消费耗时、对账 0 差异；
5. 稳定后第二刀删旧机制 + 更新 AGENTS/文档。

## 5. 关键流程图

```
发布/编辑/删除（DB 事务）
   ├─ UPDATE/INSERT articles
   └─ INSERT article_index_outbox(status=PENDING)
        commit
            │
   relay(2s, 单线程 executor)
        ├─ 健康门禁：非 HEALTHY → 留在 PENDING（+30s）
        ├─ CAS 抢行：PENDING → SENDING(lease 60s)
        ├─ syncSendOrderly(topic, key=articleId)
        │     成功 → SENT
        │     失败 → PENDING + 指数退避（20 次后 FAILED）
        └─ 租约过期恢复：SENDING → PENDING（崩溃重投）
            │
   consumer(ORDERLY, 同 articleId 串行)
        ├─ 非 HEALTHY → 短事务回插 PENDING outbox，提交成功后 ACK
        ├─ 回查 DB → PUBLISHED ? rag.index + search.indexStrict
        │                 : rag.delete + search.deleteStrict
        └─ 失败抛异常 → MQ 重试（3 次）→ DLQ → 日对账兜底
```

## 6. 一致性论证（评审可逐条追问）

### 6.1 不丢
- DB 提交成功 ⟺ outbox 行存在（同一事务）。
- relay 失败/崩溃：行仍在 PENDING/SENDING，租约恢复后重投。
- broker 不可用：relay 退避重试，最多 20 次后 FAILED（有日志 + 对账可见），不会静默丢。

### 6.2 不重（业务上）
- 重复投递不会产生重复文档：`ragIndexService.indexArticle` 覆盖写、`delete` 幂等、搜索索引同 id 覆盖；
  但重复消费仍会重复执行查询、切片和 embedding，不能把“业务结果幂等”理解成“零成本”。
- 同一篇的重复事件最多让消费端多跑一次“回查 DB + 覆盖写”，最终结果相同。

### 6.3 不乱（为什么"回查 DB + 顺序消费"能收敛）
设同一篇文章有两个事件 E1→E2（发送顺序与 DB 提交顺序可能因并发略有交错，但 MQ 按发送顺序排队）。
ORDERLY 保证 E1 处理完才开始 E2。每次处理都在**开始时**读 DB 当前状态 Sᵢ，并写入基于 Sᵢ 的结果。
文章状态并不是单调变化（可以发布 → 隐藏 → 再发布），所以不能用“DB 只会前进”证明正确性。
真正的收敛条件是：同一 `articleId` 不允许两个索引动作并发执行；每条消息开始时回查 DB，
后续事件也会再次回查当时的最新状态。即使 E1 读到旧状态后 E2 才提交，E1 完成后 E2 仍会再读一次并覆盖到最新状态；
只要 outbox/MQ 事件最终不丢，最后一条已提交变更对应的事件会把索引收敛到当前 DB 状态。
- 若没有 ORDERLY（并发消费）：E1 读 S1（旧）→ 耗时长（embedding 数秒）→ E2 读 S2（新）→ E2 写 → E1 写 → **旧覆盖新**。
  这是必须排除的竞态，也是 D3 选顺序消息的根本原因。
- 重复投递：等价于同一个 S 写两次，无影响。

### 6.4 重建期
- relay 在 REBUILDING 时不 claim → 事件不进入 MQ；
- 已进入 MQ 的事件被消费时二次检查，仍 REBUILDING → 在短事务中回插 outbox 延后；
  **回插提交成功后才能 ACK，回插失败必须让 MQ 重试**，不能用 ACK 代替可靠落库；
- 全量重建本身从 DB 读取当前数据，重建完成后再补齐延迟事件，最终一致。

### 6.5 失败面
| 失败点 | 行为 |
|---|---|
| DB 提交前崩溃 | 文章与 outbox 一起回滚，无事件（正确） |
| DB 提交后、relay 前崩溃 | 行在 PENDING，relay 恢复后重投 |
| relay 发送后、标记 SENT 前崩溃 | 租约恢复重发，消费端幂等 |
| 消费前 broker/consumer 挂 | MQ 持久化，恢复后消费 |
| 消费中 embedding/ES 失败 | 抛异常 → 顺序重试 3 次 → DLQ；日对账修复 |
| 重建与消费并发 | 门禁延期（§6.4） |

## 7. 测试计划

**离线（零 LLM / 零真实 broker）**
1. `ArticleIndexOutboxServiceTests`：事务内插入；无事务调用抛异常；rollback 不落行。
2. `ArticleIndexOutboxRelayTests`：CAS 抢占（并发只有一个成功）；发送成功→SENT；发送失败→PENDING+退避；
   租约过期恢复；attempts 上限→FAILED；健康门禁不 claim；清理只删 SENT。
3. `ArticleIndexMutationPublisherTests`：flag true/false 分流；false 时 after-commit 调用旧链；
   事务内/外行为。
4. `ArticleIndexMqConsumerTests`：PUBLISHED→双索引；HIDDEN/DRAFT/null→双删除；重复消息幂等；
   search strict 失败→异常冒泡；健康门禁→回插 outbox。
5. `ArticleSearchStrictTests`：strict 版本失败抛出、成功写入；宽松版本行为不变。
6. 序列化往返（复用第一批 `RocketMQMessageConverter` 测试模式）。
7. `ArticlesService` 集成（H2）：7 个写方法在 flag=true 时各插入一行 outbox；flag=false 时走旧链；
   `updateArticleTitle/Visibility` 回滚不留 outbox 行。
8. 对账：`check()` 分页/聚合修复的单元测试（模拟 >1000 文档）。

**手工/彩排（本地 broker + ES）**
- broker 停 → 发布文章成功、outbox 积压；broker 起 → 30s 内清空且索引出现；
- kill consumer → 恢复后 backlog 消费完；
- 重建进行中发布/隐藏 → 重建完成后对账 0 差异；
- 重复投递（手动重发同一消息）→ 对账 0 差异；
- flag 回退：等待排空 → 切 false → 发布/隐藏仍正常。

**回归**
- 现有 `ArticleRagSyncHealthTests` / `ArticleRagIndexServiceTests` / `ArticleSearch*Tests` /
  `ArticlesService` 相关测试；第二刀删除旧机制时同步删 `RagRetryQueueTests` / `RagRetryTaskTests`。

## 8. 验收标准（DoD）

- [ ] flag=false 时业务结果与当前一致；索引动作移到 after-commit，属于修复 ④，不能再表述为“逐字节一致”。
- [ ] flag=true：发布/编辑后 5s 内 RAG + 站内搜索都能搜到；隐藏/删除后 5s 内消失。
- [ ] broker 停：文章写成功（HTTP 200），outbox 积压可观测；broker 恢复后自动补齐，对账 0 差异。
- [ ] kill 消费者：恢复后自动追平，无需人工。
- [ ] 重建期写入不会与重建互相覆盖；重建后对账 0 差异。
- [ ] 对账在 >1000 篇/片段的场景下准确（分页/聚合修复已验证）。
- [ ] 旧机制可回退；第二刀删除后无残留 Redis key 读写。
- [ ] 文档：AGENTS / 设计稿 / 部署 README / project-history 更新。

## 9. 风险与回滚

| 风险 | 缓解 |
|---|---|
| 事务边界改动影响文章写接口 | 只包「写库+outbox」，回归 7 个写接口测试；flag=false 路径行为保持 |
| 顺序消息队头阻塞（一篇慢拖同队列） | `maxReconsumeTimes=3` 快速进 DLQ；默认 4 个队列，不同文章分散；量小可接受 |
| outbox 表增长 | 每日清理 SENT；PENDING/FAILED 计数观测 |
| 消费者与重建竞态 | relay 门禁 + consumer 二次检查 + 回插 |
| 双索引任一失败导致整体重试 | 两个操作都幂等；重试成本可接受；量小 |
| 对账误报/漏报 | 先修 1000 上限；canary 与对账双保险 |
| 回退后 topic 里残留消息 | 下次启用时消费回查 DB，结果正确；无副作用 |

**回滚**：`AI_ARTICLE_INDEX_USE_MQ=false` + 重启（先等 PENDING 排空）。代码回滚到上一版即可恢复旧行为。

## 10. 实施切片

| 切片 | 内容 | 可独立验证 |
|---|---|---|
| S1 · outbox 基座 ✅（2026-10-22） | DDL + entity/mapper + `ArticleIndexOutboxService`（插入/CAS/清理）+ relay + 单线程 executor + 配置 + 事件/发布器；**不接业务调用**（flag=false 完全无变化） | 14 例单测 + 全上下文回归；开发库已建表 |
| S2 · 接入与消费 ✅（2026-10-23） | `ArticleIndexMutationPublisher` + `ArticlesServiceImpl` 7 个入口 + 旧路径 after-commit + consumer + strict 搜索方法 + 健康门禁 + 回插 | 11 例新测 + 58 例回归全绿；flag 默认 false |
| S3 · 收口（对账/观测已实施 2026-10-23） | 对账 composite 分页修复 + RAG/搜索双索引日巡检 + outbox stats 接口；**本地 broker 彩排 + 第二刀删旧机制待用户验证后做** | 对账/接口测试 + 回归；真实 ES 已验证 composite 聚合 |

## 11. 面试讲法（三问）

1. **不丢**：DB 事务内写 outbox，不依赖"发消息成功"；relay 至少一次投递 + 租约恢复。
2. **不重**：消费端以 articleId 回查 DB，操作天然幂等；不引入额外幂等表。
3. **不乱**：同一 articleId 顺序消息；并发消费会"旧覆盖新"，顺序 + 回查是必要组合。
4. **不合格怎么办**：MQ 重试 → 死信 → 每日对账 + 手动 repair/rebuild；不自动重放。
5. **为什么秒杀继续 Redis Stream**：秒杀是 Redis 原子扣减 + 结算流，Stream 的消费组/PEL 已是最优；按场景选型。

## 12. 评审结论与待确认项（2026-10-22）

**结论**：核心架构（outbox → relay → ORDERLY → 回查 DB → 双索引）评审通过，无硬伤。
以下 7 项按"推荐"执行；实施中如有新证据再回写文档，不边写边改：

1. **D1**：outbox 表 vs RocketMQ 事务消息 → 采用 outbox。
2. **D3**：ORDERLY vs 并发 + 分布式锁 → 采用 ORDERLY（§6.3 的旧覆盖新是硬理由）。
3. **D4**：站内搜索这次一起收口 → 一起。
4. **D5**：旧机制删除放本刀还是验证后第二刀 → 验证后第二刀。
5. **D6**：对账只告警 vs 自动修"缺失"方向 → v1 只告警 + 手动 repair。
6. **D8**：重建期门禁放 relay 还是只放 consumer → relay 主 + consumer 兜底。
7. 命名 → 包 `ai.index` / 表 `article_index_outbox` / topic `article-index-sync` / 消费组 `blog-article-index-consumer`。

### 12.1 先观察、本刀不定死的三个取舍（2026-10-22 评审补充）

1. **消费并发**：初始 `consumeThreadMax=2`。注意 `consumeThreadNumber=1` 映射的是 `setConsumeThreadMin`，
   **不会让所有文章全局串行**；ORDERLY 并行度 ≈ min(队列数, max)，默认 4 队列 → 最多 2 篇同时索引。
   先按 2 观测 outbox 积压与单篇耗时；压测后再决定是否调到 4，同时要盯 embedding 供应商限流
   （与第一批记忆消费者同因，盲目加线程会先打爆 429）。注解参数是编译期 int，调整需发版。
2. **同文章连续编辑产生多条事件**：最终结果正确，但会重复查询/切片/embedding（§6.2）。
   当前流量小先接受；若压测显示浪费明显，再在 relay claim 后按 articleId 合并（同批只发一条、其余标 SENT），
   不引入额外存储。
3. **重建期回插 outbox 可能重复**：正确性无碍，但会多出无效待处理行。
   先观测每日回插数量与 `PENDING` 峰值；超过阈值再加轻量去重（插入前查同 articleId 是否已有 PENDING；
   并发下不是严格唯一，只是减少噪音）。

> 实施顺序：S1 已实施（2026-10-22，见 §13）；S2 等第一批 MQ 代码提交、工作区干净后开工；
> S3 收口后第二刀删旧机制。S1 不碰业务、可先合入。

## 13. 实施记录：S1 outbox 基座（2026-10-22）

全部是新增代码，**未接业务调用**，`use-mq=false` 时行为零变化：

- **DDL**：`article_index_outbox` 已进 `db/init.sql` 第 30 节与 `src/test/resources/schema.sql`；
  本地开发库已建表，列 / 索引与脚本核对一致。
- **代码**（`com.hailin.blogsystem.ai.index`）：
  `ArticleIndexEvent`（只带 articleId + traceId）、`ArticleIndexOutbox`（实体）、
  `ArticleIndexOutboxService`（enqueue 强制事务 / CAS claim / 租约恢复 / 指数退避转 FAILED / 清理 / backlog 计数）、
  `ArticleIndexEventPublisher` + `RocketMqArticleIndexEventPublisher`（syncSendOrderly，key=articleId）、
  `ArticleIndexOutboxRelayTask`（健康门禁、executor 容量保护、拒绝即退回 PENDING）、
  `ArticleIndexAsyncConfig`（单线程有界队列）。mapper 跟随 `@MapperScan` 放在 `mapper` 包。
- **配置**：`application.yml` 新增 `blog.ai.article-index.*`（默认 false）；
  `blog-system/deploy/blog-system.env.example` 补可选变量。
- **验证**：S1 相关 14 例单测全绿（事件序列化往返 3 / outbox 状态机 6 / relay 门禁与投递 4 / 发布器 1），
  另跑全上下文与 RAG 回归（`ArticleAgentDirectStreamTests` / `ArticleRagControllerTests` / `ArticleRagSyncHealthTests`）。
- **S2 待接**：`enqueue` 还没有调用方；consumer、旧路径 after-commit、`ArticlesServiceImpl` 7 个入口、
  `ArticleSearchService` strict 方法、对账修复都未动。


## 14. 实施记录：S2 接入与消费（2026-10-23）

- **统一入口**：新增 `ArticleIndexMutationPublisher`。必须在文章写事务内调用：
  flag=true → `outboxService.enqueue(articleId)`；flag=false → 注册 after-commit 后回查 DB，
  按最新状态决定 RAG + 站内搜索的 index/delete（与消费端同一套判断，不再在调用点二选一）。
- **写路径**：`ArticlesServiceImpl` 7 个入口全部接入；5 个非事务方法用 `TransactionTemplate`
  只包「写库 + `articleChanged`」；`updateArticleTitle` / `updateArticleVisibility` 在原有事务内调用。
  删除 `safelyIndexArticleRag` / `safelyDeleteArticleRagIndex` / `safelyIndexArticleSearch` /
  `syncArticleRagIndex` 四个 helper。
- **消费端**：`RocketMqArticleIndexConsumer`（ORDERLY、maxReconsumeTimes=3、线程 1~2）：
  回查 DB → PUBLISHED 双索引 upsert / 否则双删；索引非 HEALTHY 时用
  `ArticleIndexOutboxService.enqueueDeferred`（REQUIRES_NEW）回插 outbox，**提交成功才 ACK**；
  失败抛异常交 MQ 重试；traceId 恢复 MDC。
- **搜索索引 strict 方法**：`ArticleSearchService.indexArticleStrict` / `deleteArticleStrict`
  （删除的 404 视为成功，网络/5xx 抛异常触发重试）。
- **验证**：S2 新增 11 例（publisher 5 / consumer 5 / outbox deferred 1），S1+S2 累计 25 例；
  回归 58 例全绿（标题/可见性写路径、`AfterCommitCacheEvictTests`、`DelayedCacheEvictTests`、
  `AgentWriteActionServiceTests` 26 例、全上下文 `ArticleAgentDirectStreamTests`、RAG controller/health）。
- **S3 对账/观测部分已实施（2026-10-23，见 §15）；仍待**：对账 1000 上限修复 + 定时巡检 + 观测接口；本地 broker 彩排（发布/编辑/隐藏/删除、
  broker 停、kill consumer、重建期、flag 回退）；验证通过后第二刀删 `RagRetryQueue` / `RagRetryTask` / 失败标记。

## 15. 实施记录：S3 对账与观测（2026-10-23）

- **对账 1000 上限修复**：`ArticleRagConsistencyService.loadIndexedArticleIds` 从 `.size(1000)` 拉 chunk 文档
  改成 **composite 聚合 + after_key 分页**（按 `metadata.articleId` 去重，不再有 1000 条限制）；
  已用本地真实 ES（alias `article_rag_active`）验证聚合返回 `buckets + after_key`、ID 为 long。
- **站内搜索对账**：新增 `checkSearchIndex()`（`article_search_index` 按 `articleId` composite 聚合），
  与 RAG 报告同口径（published/indexed/missing/extra）；v1 只读，不自动修。
- **日巡检**：`ArticleIndexConsistencyTask`（`@Scheduled` 默认每天 04:00，cron 可配）分别 try RAG / 搜索，
  输出 `[INDEX-RECONCILE] rag ...` / `[INDEX-RECONCILE] search ...`，有差异打 ERROR；只在 `use-mq=true` 时启用。
- **观测接口**：`GET /api/ai/rag/articles/index-outbox/stats`（复用 rebuild allowlist）返回
  pending/sending/sent/failed 计数；另加 `GET /api/ai/rag/articles/consistency/search` 手动查搜索对账。
- **验证**：S3 新增 3 例（task 2 + controller stats 1），S1~S3 累计 28 例；回归 58 例保持全绿；
  真实 ES composite 查询已手工验证。**broker 彩排与旧机制删除仍未做**，等用户统一测试后再执行。