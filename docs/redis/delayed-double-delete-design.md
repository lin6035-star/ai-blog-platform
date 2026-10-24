# 缓存延迟双删（第六刀）

> 状态：✅ 已实施（2026-09-20）
> 前置：第五刀「缓存一致性补偿」（`AfterCommitExecutor`）
> 代码：`component/DelayedCacheEvictor.java`、`config/BlogCacheProperties.java`

## 一、要解决的问题

第五刀把缓存删除推到了**事务提交之后**，消除了"删早了"那个必然出现的窗口。
但仍剩一个窗口：

```
读请求 R：TR1 查库（读到旧值）──────────┐
写请求 W：        T3 提交 → T4 删缓存   │
                        R 在 T4 之后才写回 ─┘   → 缓存里留下旧值，直到 TTL 过期
```

**触发条件**（精确）：`R 的「查库 → 写缓存」区间跨越了 T3`。

删除紧跟在提交之后（`afterCommit` 回调），所以近似等价于：**读请求的处理区间跨越了提交时刻**。

⚠️ **这不是"恰好并发"**——读请求的该区间有几十毫秒宽，对热数据只要提交前那段时间有并发读进库就会中。
后果是旧值一直留到 TTL（详情 10 分钟）。

## 二、这是「触发条件到期」，不是新增需求

本窗口在 `redis-capability-design.md` §5.9 就登记过，当时不做的两条理由**现在都被推翻**：

| 原判据 | 为什么不成立 |
|---|---|
| "窗口**微秒级**" | 窗口宽度 = 读请求「查库 → 写缓存」的耗时（实测 P99 十几到几十毫秒），<br>**不是**"删除动作到提交之间那几微秒"。原判断把"需要重叠的时长"和"读请求自身的耗时"搞混了 |
| "延迟时长**无法准确设定**" | **可实测**（见 §四），取读路径实测量级的若干倍；而且**偏大是安全方向**（多一次回源，无正确性代价） |

## 三、方案

### 3.1 组件

`component/DelayedCacheEvictor.java`：

```java
public void evict(String key);             // 提交后删一次 + D 毫秒后再删一次
public void evictPattern(String pattern);  // 同上，走 SCAN + UNLINK
```

内部**组合** `AfterCommitExecutor`（管"时机"），自己只管"延迟重放"。
调度用私有的 `ThreadPoolTaskScheduler`（`poolSize=1`）+ `AtomicInteger pending` 上限保护。

### 3.2 三条契约（改动组件前必须逐条确认）

| # | 契约 | 为什么 |
|---|---|---|
| 1 | **动作必须幂等** | "重复执行 == 执行一次"是敢重放的**唯一**前提。删除天然满足；将来要塞 `INCR` / `LPUSH` 必须先重新设计 |
| 2 | **任务可丢** | 丢了只是退化成"没有第二次删除"（= 加这一刀之前的行为） |
| 3 | **不抛异常** | 无事务调用方走 `AfterCommitExecutor` 的**立即执行**分支，而那条分支**不兜异常** |

### 3.3 ⚠️ 两个必须遵守的约束

**① 调度器必须私有 `new`，绝不能声明成 Spring Bean**

上下文里一旦出现任何类型为 `TaskScheduler` 的 bean：
- Spring Boot 自动配置的 `taskScheduler` 因 `@ConditionalOnMissingBean` **退避**
  → `spring.task.scheduling.pool.size: 1` **静默失效**
- 全部 8 个 `@Scheduled` 任务被搬到本池
  → 项目刻意维持的"**串行调度**"不变量被无声破坏（浏览量同步与热度榜重建读同一批数据，并发会重复计分）

这条写进了类注释。运维侧排查线索：现象是"定时任务开始并发、浏览量重复计分"，
根因查上下文里是否有第二个 `TaskScheduler` bean。

**② 不改 `AfterCommitExecutor.execute` 的行为，也不给它加重载**

- **改 `execute`** ❌ —— `UserSetCache.maintain` 用的是同一个 `execute`，而它的动作是
  Lua 维护 Set（`SADD`/`SREM` + 刷 TTL），**不是删除**；重复执行会把两个 TTL 各刷一次，语义已变。
  更根本的是：把"动作会跑两次"变成 `execute` 的隐含契约，将来有人塞非幂等动作就是**静默数据错误**
- **加重载** ⚠️ —— `AfterCommitExecutor` 现在零字段，6 个测试锁的就是它；加调度器后要持资源、
  会丢任务、需要 shutdown，既有 6 例语义会变、必须重写
- **新组件组合它** ✅ —— 时机语义仍只有一处实现；`AfterCommitCacheEvictTests` **6 例原样继续有效**

> 口径：`AfterCommitExecutor` 是**时机**抽象（两者共用），`DelayedCacheEvictor` 是
> **幂等删除**抽象（只有删除型用）。

### 3.4 接入：改写 6 个封装方法，业务调用点一个没动

```java
// 改造前（这份结构重复了 4 遍）
afterCommitExecutor.execute(() -> {
    try { stringRedisTemplate.delete(key); }
    catch (Exception e) { log.warn("[CACHE-EVICT-FAIL] ...", key, e); }
});

// 改造后
delayedCacheEvictor.evict(RedisConstants.ARTICLE_DETAIL_KEY_PREFIX + articleId);
```

顺带收口了 `ArticlesServiceImpl` 里重复 6 次的「删详情 + 删列表」两行组合
（新增 `evictArticleCaches(Long)`）——那两行必须成对出现，分开写漏一个就是**静默的脏缓存**。

## 四、延迟值 D 的实测

**要测的量**：读请求从「缓存 miss、开始查库」到「缓存写完」的耗时。

**方法（零代码改动）**：启动参数把 `blog.observability.slow-request-ms` 压到 1ms，
让 `RequestTimingInterceptor` 把**每个**请求都打成 WARN；然后强制 cache miss（`DEL` 缓存 key）
+ **16 并发**压测（并发是必须的——单线程测出来的是空载数据，覆盖不到 Hikari 等待与 Redis RTT 抖动）。

**实测结果**（2026-09-20，真 MySQL + 真 Redis，本地）：

| 读路径 | 样本 | P50 | P90 | P99 | **max** |
|---|---|---|---|---|---|
| 文章详情 `GET /api/articles/{id}` | 320 | 4ms | 6ms | 16ms | **28ms** |
| 文章列表 `GET /api/articles` | 320 | 7ms | 8ms | 22ms | **26ms** |

> 首次冷启动有一条 162ms（JIT 未预热 + 连接池未建立），不计入——它是一次性的，
> 而这一刀要覆盖的是**稳态**下的慢读。

**未单独实测的两类**：评论列表、学习计划列表——都需要登录态，且读路径同构、数据量更小
（评论 15 条 vs 列表 20 篇），按已测最大值估算。

**取值：`delay-ms: 200`** ≈ 实测 max 的 **7 倍**。

理由：本地环境 MySQL/Redis 都在本机、无网络 RTT；生产还要叠加网络往返、GC、更大数据量。
**偏大是安全方向**——代价只是多一次 DB 回源；偏小则窗口还在。

> ⚠️ 服务**刚启动那几分钟**（JIT 预热期）读路径会明显慢于上表，是这一刀覆盖不到的尾巴。

## 五、范围：什么纳入、什么不纳入

### 纳入（6 个封装方法 / 4 类 key）

| key | 读路径回填点 |
|---|---|
| `article:detail:{id}` ×4（文章 / 点赞 / 收藏 / 评论） | `saveArticleDetailToCache` |
| `article:list:*` | `saveArticleListToCache` |
| `comment:list:article:{id}:*` | `saveCommentListToCache` |
| `learning:plan:list:{userId}` | `listActiveByUserCached` |

两类 pattern 删除走 `RedisKeyScanner`，SCAN 次数翻倍（当前 key 基数是几十到几百，可接受）。

### 不纳入 `UserSetCache`（**本节最重要的判断**）

`UserSetCache.maintain` 走的是同一个 `execute`，但两个维度都不匹配：

1. **语义不同**：动作是 Lua **增量维护**（查标记 → `SADD`/`SREM` → 刷两个 TTL），不是删除。
   重放它是"重复应用一次增量"
2. **窗口形态不同**：它的读路径回填是 `markLoaded`——**全量快照 SET**。
   窗口是"写路径的增量维护被读路径的旧快照整个覆盖"，而不是"删完之后旧值被写回"。
   **这个窗口用"删两次"根本关不上**——删掉集合后读路径回源重建的是**快照时刻**的数据，
   未必包含那次点赞

> **登记不修，触发条件**：出现"用户自己的点赞/收藏在刷新后短暂消失"的反馈，
> 且能排除 Redis 重启。届时按"延迟重放同一次增量"单独设计，**不复用本组件**。

### 明确不纳入

读路径自愈的裸删（那边没有"事务提交"这个时刻）、`category:list` / `tag:list`（无写路径失效）、
`article:hot`（定时全量重建）。

## 六、测试与反向验证

`DelayedCacheEvictTests`（`@SpringBootTest` + 真 Redis + H2，7 例）：

| 组 | 用例 | 证什么 |
|---|---|---|
| A | `evictsAgainAfterDelay` | 重放真的发生（用"单线程重排事件顺序"建模迟到写回，不需要真并发） |
| A | `patternEvictReplays` | pattern 版本同形，且**不越界删不匹配的 key** |
| A | `disabledSwitchDegradesToSingleDelete` | 开关关掉 = 退化成单次删除 |
| A | `rollbackNeitherDeletesNorSchedulesReplay` | 回滚既不删也不排重放 |
| A | `dropsReplayWhenPendingExceedsLimit` | 积压超限只丢超出的那个 |
| **B** | **`readerCrossingCommitIsOverwrittenByReplay`** | **残留窗口真的被关上**（核心用例） |
| C | `likeArticleEvictsAfterCommitAndReplays` | 端到端接线（一例覆盖 bug 修复 + 提交时机 + 延迟重放） |

**B 组怎么做到确定性**：用自定义 MyBatis `Interceptor`（沿用 `ArticleDetailMutexTests` 的先例）
拦 `Executor.query`，在 `proceed()` **之后**——即"已从库里拿到旧值、尚未写回缓存"——
用 latch 把读线程挂住；主线程在此期间完成改名并提交，再放行。**全程不靠赛跑。**

**反向验证**：把 `scheduleReplay` 直接 `return`（砍掉重放），跑测试 →

| 用例 | 结果 |
|---|---|
| `evictsAgainAfterDelay` / `patternEvictReplays` / `dropsReplayWhenPendingExceedsLimit` | ❌ 红 |
| **`readerCrossingCommitIsOverwrittenByReplay`** | ❌ 红，且停在断言②「延迟重删应覆盖…」 |
| `likeArticleEvictsAfterCommitAndReplays` | ❌ 红 |
| `disabledSwitchDegradesToSingleDelete` / `rollbackNeitherDeletesNorSchedulesReplay` | ✅ 仍绿（它们断言的就是"没有重放"） |

**回归保护**：`AfterCommitCacheEvictTests` 6 例、`ArticleDetailMutexTests` 4 例、
`ArticleHotRankTests` 4 例、`ArticlesListCacheTests` 2 例、`UserSetCacheIntegrationTests` 18 例
—— 连同新用例共 **41 例全绿**。

### 实施中踩到的两个坑（都很典型）

1. **收口时用 `replace_all` 把新方法体自己也替换了** → `evictArticleCaches` 无限递归调自己，
   B 组一跑就 `StackOverflowError`。**新加的方法体恰好就是那两行、缩进又和调用点一致**。
   —— 是 B 组抓出来的；A/C 组都没走那条路径，放过了它
2. **挂起类测试的 latch 必须每例重建**：初版把 latch 写成 `final` 并在 `reset()` 里
   `countDown()` 复用，结果 `releaseReader` 在 `@BeforeEach` 就被归零，
   读线程"挂起"时 `await` **立即返回、根本没停住**，主线程反而跑到了它前面。
   表现是断言① 看到空缓存——**看起来像实现错了，其实是测试写错了**

## 七、边界与代价

**解决不了的**：

1. **跨实例不覆盖**——重放任务只活在发起删除的那个 JVM。实例在 D 毫秒内退出/崩溃 → 丢失 → 退化成现状
2. **读路径耗时 > D 的尾部不覆盖**——STW GC、Hikari 等待、Redis 慢查询都可能超；兜底仍是 TTL
3. **RAG / ES 索引那条路仍开着**——`ArticleRagSyncService.indexArticle` 是 `@Async` 却在事务内发起
   （第五刀已登记不修），延迟双删只关了**缓存**这条路。**两句要并列看，别以为一致性已经收口**
4. **不是强一致**——要真正消除窗口得上 binlog 订阅；触发条件不变，只是窗口被收窄了

**代价（要认账的）**：

- `article:list:*` 与 `comment:list:*` 的 SCAN 次数翻倍
- 详情缓存平均多一次 DB 回源——**这正是它起作用的表现**
- 连续 N 次写产生 N 个重放任务（不做按 key 合并：合并要么把最早的删除推后、反而扩大窗口）；
  上限由 `max-pending` 兜住，超限打 `[CACHE-EVICT-DEFER-DROP]` 并丢弃

**降级路径**（任何一条触发都只是退回加这一刀之前的行为，无正确性问题）：

| 场景 | 退化成 |
|---|---|
| `enabled=false` / 配置漏了 | 单次删除 |
| 重放任务丢失（实例退出 / 调度器异常） | 同上 |
| `pending > maxPending`（极端写风暴） | 同上，且有 WARN 可检索 |
| Redis 删除两次都失败 | 靠 TTL，`[CACHE-EVICT-FAIL]` 留痕 |
