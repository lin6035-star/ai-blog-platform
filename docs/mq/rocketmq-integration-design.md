# 消息队列：RocketMQ 接入

> 状态：**第一批已实施（2026-10-22）**；第二批（RAG 索引同步）仍待独立设计评审。
> 原设计稿：2026-09-19（下方 §1-§10 保留设计原文，§11 是实施记录与对原设计的修正）
> 上游：`docs/capability-audit-2026-08-06.md`（第二层「MQ 替换 @Async」）——但**本刀的真实依据是下面三个缺口，不是"面试高频"**

## 0. 一句话

把三条**失败即丢**的异步任务迁到 RocketMQ（可靠投递 + 重试 + 死信）；
第二批替换 RAG 那套手搓重试队列（顺带修掉 at-most-once）；
**不动秒杀那套 Redis Stream**——它是全项目质量最高的实现。

## 1. 为什么做：盘出来的三个真缺口

CLAUDE.md 原先登记的是「MQ 第二层预留，**当前并发量不需要**」。但把现有异步链路盘了一遍之后，
发现**不是"不需要"，是有三个缺口一直没人提**：

### 缺口 ①：三条异步任务失败即丢

| 任务 | 位置 | 失败后 |
|---|---|---|
| 候选记忆提取 | `AiMemoryCandidateExtractorServiceImpl:51` | `log.warn` → **丢弃** |
| 情景记忆提取 | `AiEpisodicMemoryExtractorServiceImpl:39` | `log.warn` → **丢弃** |
| 会话摘要压缩 | `AiConversationSummaryServiceImpl:45` | `log.warn` → **丢弃** |

**无失败标记、无补偿、无对账**。对照一下同项目里其他链路：
RAG 有重试队列 + `rag:index:fail:*` 标记，秒杀有死信 + 对账任务，钱包/计费有对账——
**只有记忆这条链是裸奔的**。

用户感受：AI「忘了一件事」，而且**没有任何痕迹**，事后连查都没法查。

### 缺口 ②：`RagRetryQueue` 是 at-most-once

`takeDue` 用 Lua 做 `ZRANGEBYSCORE + ZREM` —— **取走即删**。进程在"取走但没执行完"时崩溃，
这个任务就**永久消失**了（`SeckillSettleTask` 的注释里明确写了这是秒杀**不复用**它的原因）。

### 缺口 ③：顺序性完全空白

RAG 的更新路径是 `deleteArticleIndex` + `indexArticle` **两个独立的 @Async**，并发操作同一个
ES 文档，**没有任何保序机制**。现在数据量小、竞争窗口窄，看起来没事；
但**换 MQ 时如果不显式设计保序键，重复投递 + 并发消费会让它从"偶发"变成"稳定复现"**。

## 2. 选型：RocketMQ

**对着缺口选，不是对着热度选**：

| 缺口 | RocketMQ 的对应能力 |
|---|---|
| 手搓的指数退避重试（`RagRetryQueue`） | **延迟消息**（原生分级延迟，不用自己写 ZSet 调度） |
| 保序空白（缺口③） | **顺序消息**（同一 MessageQueue 内严格 FIFO） |
| 失败即丢（缺口①） | **死信队列**（消费失败自动重试 N 次后进 DLQ，不丢） |

Kafka 吞吐更大，但**延迟消息和顺序消息都要自己实现**——而这两个恰好是本项目要解的问题，
选它等于把 RocketMQ 白送的能力再手搓一遍。

## 3. 核心设计

### 3.1 事件信封（统一消息结构）

现在各处消息**没有统一格式**（秒杀那条是裸的 `{activityId, userId}` 字段）。引入 MQ 时必须定一个：

```
topic          业务域（如 memory.extract）
eventType      事件类型（同类 topic 下区分动作）
bizKey         业务键（**同时用作顺序分区的 key**，如 userId / articleId）
payload        业务载荷
traceId        贯穿链路（复用现有 MdcContext）
attempt        投递次数（消费侧幂等与死信判定用）
```

**为什么要有 `bizKey`**：顺序消息必须按 key 选队列，否则同一实体的两条消息会落到不同队列、
并发消费，保序无从谈起。

### 3.2 消费者幂等（**必须与可靠投递同时做**）

MQ 是 **at-least-once**——**重复投递是常态，不是异常**。

侦察里有一条很关键：**「RAG 索引覆盖写天然幂等，但记忆写入不幂等」**。
如果不做幂等就上 MQ，结果是**「偶尔丢一条记忆」变成「偶尔重复写几条记忆」**——更糟。

**方案**：按业务键设计幂等
- 记忆提取：以 `(userId, 消息ID)` 或内容指纹做去重键，落 Redis（短 TTL）/ DB 唯一索引
- RAG 索引：覆盖写**本来就是幂等的**，无需额外处理（这是它的运气，不是设计）

### 3.3 重试与死信

**统一到 MQ 的重试机制**，不再各写各的：
- 消费失败 → **不 ACK**，让 MQ 按延迟等级重投（RocketMQ 默认 16 级延迟）
- 重投 N 次仍失败 → **进死信队列**，落库/落 Redis 留痕 + 可检索日志
- **不做自动重放**（与项目现有对账任务同一个口径：哪个数是对的只有人知道）

> RAG 现有的 `rag:index:fail:*` 标记 + `RagRetryQueue` 在第二批里被替换，
> **但"失败要留痕"这条保留**——只是从手搓改成 MQ 自带。

### 3.4 与现有 `@Async` 的关系：**不是全替换**

| 场景 | 处理 |
|---|---|
| 记忆提取 / 情景记忆 / 摘要压缩 | **迁到 MQ**（缺口①，收益最大） |
| RAG 索引同步 | **第二批迁**（缺口②） |
| 秒杀结算 | **不动**（Redis Stream，已是最优解） |
| 记忆召回（`memoryRecallExecutor`） | **不动**——它在**请求线程路径**上同步等结果，不是异步任务 |
| 缓存失效（`AfterCommitExecutor`） | **不动**——刻意不重试（见 CLAUDE.md） |

**判断标准**：**只迁「可以延迟完成、且丢了有代价」的任务**。
召回是同步路径、缓存失效是刻意的 best-effort —— 它们**不该**进 MQ。

## 4. 范围

**第一批**（本刀主体）：
1. 部署 RocketMQ（Docker）+ 一个可复用的生产者/消费者封装（**不做通用框架**，够用即可）
2. 三条「失败即丢」的任务迁移：可靠投递 + 重试 + 死信 + 幂等
3. 死信的可观测（留痕 + 日志）

**第二批**（后续单独一刀）：
4. RAG 索引同步迁移，替换 `RagRetryQueue`，顺带修 at-most-once
5. 引入按 `articleId` 的保序（解缺口③）

**不做**：
- **不动秒杀 Stream**（质量最高，改它是拿风险换不到收益）
- **不做通用 MQ 框架**（信封 + 一个消费基类就够，抽象留给第二个消费方出现时）
- **不做事务消息**（当前没有跨服务/跨库的场景，为不存在的需求写代码）
- **不做 MQ 集群/高可用**（单机 Broker，讲清生产该怎么做即可）

## 5. 部署

RocketMQ 需要 **NameServer + Broker** 两个进程。当前项目**没有 Dockerfile 也没有 docker-compose**
（CLAUDE.md 登记的「先写 Dockerfile + docker-compose」一直没做），借这次补一个 `docker-compose.yml`。

## 6. 验证

| 项 | 怎么验 |
|---|---|
| 可靠投递 | 消费者抛异常 → 消息**不丢**，按延迟等级重投 |
| 死信 | 重投超限 → 进 DLQ，**留痕可查**，且**不自动重放** |
| 幂等 | **手动重复投递同一条消息** → 只产生一条记忆（这条最该验，因为它是"可靠投递"不变成"重复写"的前提） |
| 保序（第二批） | 同一 articleId 连续变更 → 最终状态是最后一次变更 |
| 不回归 | 秒杀、RAG（第二批前）、聊天主链路全部不受影响 |

## 7. 与面试的关系（写清楚，避免自欺）

这一刀的价值有**两层**，要分清楚：

- **实的那层**：补掉「记忆失败即丢」这个真缺口 —— 这是**工程质量**，不是为了面试
- **虚的那层**：MQ 是面试高频 —— 但**"我用了 MQ"本身不加分**，
  加分的是能答出「**为什么用**（三个缺口）」「**怎么保证不丢**（ACK + 重试 + 死信）」
  「**怎么保证不重**（幂等键）」这三问

**所以本刀的验收标准不是"MQ 跑起来了"，是上面那三问都能用本项目的代码回答。**

---

## 8. 关键决策点（**供评审逐条挑刺**）

### 8.1 幂等键怎么设计 —— 最关键，也最没底

MQ 是 at-least-once，**不做幂等就等于把「偶尔丢一条记忆」换成「偶尔重复写几条记忆」**，
比现在的毛病更糟。三条任务的幂等难度并不相同：

| 任务 | 天然幂等？ | 要做什么 |
|---|---|---|
| RAG 索引 | ✅ **是**（ES 同 id 覆盖写） | 什么都不用 |
| 会话摘要压缩 | ⚠️ 半幂等（覆盖同一行 summary） | 看实现，可能要 |
| 候选记忆提取 | ❌ **不幂等**（INSERT 新记忆） | **必须去重** |
| 情景记忆提取 | ❌ **不幂等**（INSERT） | **必须去重** |

**候选方案**：

| 方案 | 做法 | 优点 | 缺点 |
|---|---|---|---|
| **A. 消息级去重** | 消费前 `SETNX mq:consumed:{msgId}`，TTL 7 天 | 简单、通用 | **只防"同一条消息重投"**，防不住"同一件事发了两条消息" |
| **B. 业务指纹去重** | 以 `(userId, sessionId, 内容指纹)` 为键 | 更彻底 | **内容指纹怎么算**？记忆提取的输入是一段对话窗口，窗口滑动会变 |
| **C. DB 唯一索引** | 加唯一键，冲突即忽略 | 最可靠、不怕进程崩溃 | 要改表 + 处理冲突语义 |

**倾向 A + C 组合**（消息级挡重投，业务级挡重复事件），**但这一块我没底，想听意见**。

### 8.2 payload 放什么：全量 vs 只放 ID 回查

记忆提取现在的入参是 `(userId, sessionId, userMessageId, assistantMessageId, userMessage, assistantReply)`
—— **两条是完整消息文本**。

| 方案 | 优点 | 缺点 |
|---|---|---|
| **放全量 payload** | 消费时不回查 DB | 消息体大（几 KB）；**消息内容可能与库不一致** |
| **只放 ID 回查** | 消息小；**数据以库为准**（单一真相） | 多一次 DB 查询；**消息发出后原文被改/删怎么办** |

**倾向"只放 ID"**（与秒杀那条 Stream 一致：消息只带 ID，消费时回查 DB）。
但记忆提取依赖的是**"当时那轮对话"**，而消息可能被删改 —— 这里有个真实的一致性问题。

### 8.3 顺序性：RocketMQ 顺序消息的代价

顺序消息是**按队列串行**的：同一 `bizKey` 进同一队列，**该队列单线程消费**。保序的代价是吞吐。

- **RAG 索引**（按 `articleId` 保序）：代价可接受，同一作者不会同时改 100 篇
- **记忆提取**（按 `userId` 保序）：一个活跃用户的多轮对话会被串行处理 —— **值得吗？**

**倾向"记忆提取不需要保序"**（每轮对话独立抽取），但**没有明确答案**，需要确认。

### 8.4 死信怎么处置

- **谁消费死信**？秒杀的做法是落 `seckill_order.status = FAILED_RETRY`，靠人看
- **要不要一个死信巡检任务**（像 `WalletReconcileTask` 那样定时扫）？
- **要不要自动重放**？项目现有对账任务的口径是**刻意不自动修复**（"哪个数是对的只有人知道"）——
  死信是否沿用这个口径？

### 8.5 现有机制怎么退场

- `RagRetryQueue` / `RagRetryTask`：**直接删**，还是**双跑一段时间**？
- 三个 `@Async` 池（`memoryCandidateTaskExecutor` / `conversationSummaryExecutor`）：废弃还是留着？
- ⚠️ **`memoryRecallExecutor` 不能动** —— 它在**请求线程路径上同步等结果**，不是异步任务

### 8.6 ⚠️ `brokerIP1` 是硬编码的（部署的现实问题）

`deploy/rocketmq/broker.conf` 里写的是 `brokerIP1 = 192.168.65.1`（本机 Docker Desktop 的网关地址）。
**这台机器上能用，换机器或 Docker 重启后 IP 变了就失效**，而且报错特别误导
（`connect to 172.17.0.2:10911 failed` —— 看着像网络问题，其实是注册的地址不对）。

要不要做成启动时自动探测？`deploy/` 下**目前没有 docker-compose**（CLAUDE.md 登记的
「先写 Dockerfile + docker-compose」一直没做），借这次补是自然的，但**这就扩大了本刀的范围**。

---

## 9. 三个我不敢确定的地方（**最想让评审挑战的**）

### 9.1 这个缺口真的值得引入一整个中间件吗？⚠️

**自我质疑**：项目**已经有两套 Redis 消息机制**了 ——
`RagRetryQueue`（ZSet 延迟队列）+ 秒杀的 Redis Stream（at-least-once + PEL 回收 + 死信）。

**那"记忆失败即丢"这个缺口，能不能用同一套 Redis 机制补掉，而不引入 RocketMQ？**

| 支持引入 MQ | 支持继续用 Redis |
|---|---|
| 延迟 / 顺序 / 死信都是原生能力，自己搓要写不少 | **已经有现成的两套**，复用零新增运维 |
| MQ 是面试高频 | 秒杀那套 Stream 已证明**手搓也能搓好** |

**如果结论是"用 Redis 补就够了"，那这一刀的价值就只剩"简历上多一个中间件"** ——
而 §7 已经写明「我用了 MQ」本身不加分。**这一条请认真挑。**

### 9.2 记忆提取的失败率到底有多高？⚠️

三个缺口的严重性**取决于失败频率**。如果记忆提取一年才失败一次，"失败即丢"的代价就很低，
MQ 的可靠性收益也就有限。

**我手上没有这个数据** —— 合理的做法是**先加失败统计跑一段时间**，拿到数字再决定迁不迁。
**先迁移再找理由，是"为了用 MQ 而用 MQ"的典型症状。**

### 9.3 单机 RocketMQ 的运维成本

引入后新增：两个容器、一个要监控的组件、一套新的排障知识（消息堆积 / 消费延迟 / 位点管理）。

- 对**练习项目**：这是"能讲"的素材
- 对**真实成本**：这是负担

**这个交换划不划算，需要评审判断。**

---

## 10. 实测：部署已完成（2026-09-19）

RocketMQ 5.3.1 已在本地跑起来，验证通过：

```
The broker[broker-a, 192.168.65.1:10911] boot success. serializeType=JSON
```

- 容器：`rmqnamesrv`（NameServer）+ `rmqbroker`（Broker），同一 docker 网络 `rmq-net`
- 配置：`deploy/rocketmq/broker.conf`（含 `brokerIP1` 的坑与说明）
- **代码一行未动** —— 本刀停在设计阶段

---

## 11. 实施记录：第一批（2026-10-22）

**实际范围**：把 AI 记忆三条 `@Async` 链路（候选记忆 / 情景记忆 / 会话摘要）统一到 RocketMQ。
秒杀 Redis Stream、RAG 索引同步**都没动**（后者才是第二批）。

### 11.1 已落地

- **topic / tag**：`ai-memory-extraction`；`CANDIDATE` / `EPISODIC` / `SESSION_SUMMARY` 三个 tag；
  三条链路**各自独立 consumer group**（`blog-memory-consumer` / `blog-episodic-consumer` /
  `blog-summary-consumer`），失败率、重试耗尽与死信互不污染。
- **统一 seam**：`MemoryExtractionDispatcher` 是业务唯一入口；`use-mq=false` 走原 `@Async`，`true` 走 MQ。
- **生产端 fail-open**：投递失败只 WARN、不向外抛。旧 `@Async` 本身就是失败即丢；
  接 MQ 后如果 `syncSend` 异常冒到 `AiMessageServiceImpl` 的 STOP `Mono`，会把"记忆丢了"升级成"聊天收尾报错"。
- **事件幂等键**：由业务消息 ID 派生（candidate=`userMessageId`，episodic/summary=`assistantMessageId`），
  生产端重试不产生新事件；摘要特意用 `assistantMessageId` 而不是 `sessionId`——否则同一会话只有第一轮能过幂等表。
- **消费端幂等**：`tryStart` 抢占 + 业务处理 + `markSucceeded` 同一事务；失败回滚后重投可再次进入。
  重试耗尽由 RocketMQ 进 `%DLQ%<consumerGroup>`，**不自动重放**（与项目对账口径一致）。
- **消费限流**：三组 consumer 都配 `consumeThreadMin=1 / consumeThreadMax=2`，
  对齐被替换的线程池（core1/max2）。RocketMQ 默认 20 线程会把 LLM 供应商打爆——项目压测已证明真瓶颈在供应商限流。
- **traceId 贯穿**：生产线程从 MDC 取 traceId 写入事件，消费线程恢复，日志能跨 MQ 串起来。
- **幂等表**：`ai_memory_extraction_events` 已补 `db/init.sql`；应用启动 DDL 只作兜底。
- **部署**：`deploy/rocketmq/docker-compose.yml`（本地，含 JVM 压堆与 `brokerIP1` 说明）+ `README.md`
  （生产 compose 片段、环境变量、排障速查、`autoCreateTopicEnable=false` 等生产差异）。
  后端环境变量示例已补到 `blog-system/deploy/blog-system.env.example`。
- **测试**：dispatcher（含 fail-open）/ event（含 summary 键）/ 三个 consumer / 幂等表 /
  **真实序列化往返**（`RocketMQMessageConverter`，锁住 record + `Instant` 字段，此前单测全 mock 了模板=没验过能发能收）。

### 11.2 对原设计的修正 / 确认

1. **payload 放全文，不放 ID 回查**（推翻 §8.2 原倾向）：记忆要还原"当时那轮对话"，
   消息被删改后回查会抽错上下文。代价是消息体偏大、对话文本落 broker；单机/低量可接受，登记为取舍。
2. **消费端仍用 `@Transactional` 包住 LLM 调用**：换来"失败回滚幂等占位 + 业务写入"的一致性；
   代价是长事务占 DB 连接。低量可接受，规模上来要换"短事务占位 + 租约"。
3. **episodic 的触发条件（会话消息数取模）只能在消费时判**，生产端不做预筛；
   候选记忆的规则预筛仍在生产端（省消息）。
4. **§9.1 的自我质疑维持"引入值得"**：三条链路的可靠性缺口是真的；但 §7 的口径不变——
   价值不在"用了 MQ"，在"不丢 / 不重 / 不乱 / 不过载"四问能用本项目代码回答。

### 11.3 已知缺口（登记，不在第一批扩张）

- **没有统一 envelope**：§3.1 的 `bizKey` 用 RocketMQ 消息 key/tag 承担，`attempt` 用 MQ 自带
  `reconsumeTimes`；若以后跨 topic 复用，再抽 envelope（不做通用框架）。
- **DLQ 没有专门消费者/巡检**：目前靠 MQ 自带 DLQ topic + 应用日志。第二批 RAG 迁移时统一做
  "死信落库 + 手动重放接口 + 不自动重放"。
- **转换失败（坏消息）观测不到**：发生在 listener 之前，幂等表/业务日志都看不到；
  本地已出现两条手工 `mq-probe` 坏 JSON 消息在重试。生产端统一走对象发送，不会产生这种消息。
- **episodic 的窗口触发在并发消费下可能重复提取**：`messageCount % 6 == 0` 按消费时 DB 现状判断，
  两条不同 assistantMessageId 的事件并发消费可能都看到同一个窗口末尾（原 @Async 池 max=2 同样存在）。
  若要做严格去重，需要按 `(sessionId, 窗口末尾 messageId)` 建唯一键——本轮有意不做，先观察。
- **生产 compose 未直接改** `deploy-package/docker-compose.yml`（部署配置红线，越权风险）——
  README 已给现成片段，落地时执行。

## 12. 第二批（RAG 索引同步）动工前的硬约束

> 详见 `docs/agent/学习域只读路由修复设计.md` 同期的评审讨论；这里只留检查清单。

1. **投递原子性**：先 outbox（或 after-commit + pending 标记 + 补偿），否则 DB 提交成功后进程崩溃会丢事件 = 长期脏索引。
2. **单事件、回查 DB**：投 `ARTICLE_CHANGED(articleId)`，消费者回查当前状态决定 upsert/delete，
   天然压掉「delete + index 两条消息乱序」；顺序消息按 articleId 只作第二道保险。
3. **消费者不能调 `@Async` 方法**：`ArticleRagSyncService` 的 `indexArticle` / `retryFromQueue` 是 `@Async`，
   直接调会先 ACK 再执行，回到 at-most-once；必须抽同步核心方法。
4. **双索引一起收口**：RAG 向量索引 + 站内搜索 ES 索引（后者现在是请求线程里同步写的）统一进同一事件消费。
5. **门禁与兜底保留**：`incrementalWritesAllowed` 的重建期门禁不能丢；`ArticleRagConsistencyService` 定时对账做最终兜底。
6. **`RagRetryQueue` / `RagRetryTask` 在同一刀退场**，不允许 MQ 与 ZSet 两套重试并存。

