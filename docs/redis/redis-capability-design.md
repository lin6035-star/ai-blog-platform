# Redis 能力补全设计（工程化 · 分阶段路线）

> 创建：2026-09-14
> 定位：把 Redis 从"用了"补到"讲得清边界、扛得住追问"
> 上游：`engineering-next-cut-review.md`（选型：缓存优先于登录）
> 专题：`cache-breakdown-avalanche-design.md`（击穿 / 雪崩的详细方案，本文引用不重复）
> 状态：设计稿 v6（第一 ~ 四刀已实施，见 §十）

## 一、结论先行

**本刀不做"一次性补全"，拆成五刀推进**——每一刀一个业务面，改完可独立验证：

| 刀 | 主题 | 内容 | 触碰面 | 状态 |
|---|---|---|---|---|
| **第一刀** | **缓存可靠性** | `KEYS`→`SCAN`、浏览量 Lua 原子取删、浏览量 key 原子 TTL、文章详情读路径收口、互斥回源、7 处 TTL 抖动 | `ArticlesServiceImpl` + 4 个缓存服务 | ✅ 已实施 |
| **第二刀** | **用户态集合与热榜** | `:loaded` 语义修复、Set TTL、重复写入清理、热榜原子重建、调度串行化 | 3 条业务线的 6 个写点 + 2 个定时任务 | ✅ 已实施 |
| **第三刀** | **生产配置与面试口径** | 超时（+ 连接池依赖决策）、`maxmemory-policy`、持久化取舍、危险命令、慢查询与大 key 手册 | 配置 + 文档，不动业务逻辑 | ✅ 已实施 |
| **第四刀** | **安全类 Redis** | 登录失败限制（`engineering-next-cut-review.md` §3.2）、token 黑名单 | 独立模块 | ✅ 已实施 |
| **第五刀** | **缓存一致性补偿** | 事务提交后失效（`AfterCommitExecutor` 统一收口）+ 删除失败可检索日志；延迟双删 / outbox / 消息化失效按需 | `AfterCommitExecutor` + 5 个 Service | ✅ 已实施 |

> **第二刀的"定时任务并发边界"降级为配置**：核实后 `syncViewCountToDb` 与 `rebuildArticleHotRank`
> 本就跑在默认单线程调度器上、不会并发（详见 §5.6），加锁是为不存在的问题写代码。
> 改为显式声明 `spring.task.scheduling.pool.size: 1`，把隐式默认变成显式约定。

**v1 的错误**：原稿把 7 项并列成"第一层必做"——其中 5/6/7 分属三条业务线、两个定时任务和构建依赖，一刀做完测试面失控。**范围按业务面切，不按"重要程度"切。**

## 二、能力地图：成熟产品怎么用 Redis

### 2.1 四类职责与面试追问

**一、缓存类**

| 能力 | 成熟做法 | 面试追问 |
|---|---|---|
| 穿透 | 空值缓存（短 TTL）/ 布隆过滤器 / 参数校验前置 | 布隆误判率、为什么不支持删除 |
| 击穿 | 互斥回源（SETNX + 双检）/ 逻辑过期（不阻塞读） | 锁超时了业务没完怎么办、逻辑过期怎么实现 |
| 雪崩 | TTL 抖动 / 多级缓存 / 熔断降级 / 集群高可用 | 抖动比例怎么定、多级缓存的一致性成本 |
| 双写一致性 | Cache Aside（写库后**删**缓存）/ 延迟双删 / binlog 订阅 | 为什么删不是改、删失败了怎么办 |
| 命中率 | key 粒度 / 大 key 拆分 / 热点探测 / 预热 | 命中率怎么量化、怎么发现热 key |

**二、并发控制类**

| 能力 | 成熟做法 | 面试追问 |
|---|---|---|
| 分布式锁 | SET NX PX + Lua 释放 / Redisson 看门狗续期 | 锁过期业务没完、Redlock 的争议 |
| 原子计数 | INCR / Lua 脚本 | 并发扣减超卖 |
| 幂等 | 幂等 token / 唯一索引 + Redis 去重 | key 过期后幂等窗口还在吗 |
| 限流 | 固定窗口 / 滑动窗口（ZSet）/ 令牌桶 / 漏桶 | 固定窗口的临界突刺问题 |

**三、数据结构应用**

| 结构 | 典型场景 | 本项目 |
|---|---|---|
| String | 缓存、计数、锁 | ✅ 全在用 |
| Set | 标签、共同关注、去重 | ✅ 点赞 / 收藏集合（**有正确性问题，见隐患 2**） |
| ZSet | 排行榜、延迟队列、滑动窗口限流 | ✅ `article:hot` 热度榜 |
| Hash / Bitmap / HyperLogLog / Stream | 对象存储 / 签到 / UV / 消息队列 | ⛔ 无场景 |

**四、运维治理（应用侧管不了、但必须讲得清）**

| 能力 | 生产要点 | 本项目 |
|---|---|---|
| 内存淘汰 | `maxmemory` + `maxmemory-policy` | ⚠️ 未文档化 → 第三刀 |
| 持久化 | RDB / AOF / 混合 | ⚠️ 未文档化 → 第三刀 |
| 客户端连接 | 连接池 + connect/socket 超时 | ⚠️ **缺 `commons-pool2` 依赖**，配了也不生效 → 第三刀 |
| 危险命令 | 应用账号 ACL 限制 `KEYS` / `FLUSHALL` 等命令 | ⛔ → 第三刀 |
| 高可用 | 主从 + 哨兵 / Cluster | ⛔ 不做（§七） |
| 大 key / 热 key | `--bigkeys` / 热点探测 | ⛔ → 第三刀（手册） |

## 三、现状盘点

### 3.1 Key 全景（主要 key 家族）

| Key | 结构 | TTL | 用途 |
|---|---|---|---|
| `article:view:{id}` | String | **无** | 浏览量增量（30s 同步到 DB） |
| `article:hot` | ZSet | **无** | 热度榜（实时 ZINCRBY + 30min 全量重建） |
| `article:detail:{id}` | String(JSON / Null Sentinel) | 10 min | 文章详情缓存（含空值缓存 3 min；区分不存在与不可见） |
| `article:list:*` | String(JSON) | 5 min | 文章列表 |
| `category:list` / `tag:list` | String(JSON) | 30 min | 分类 / 标签 |
| `comment:list:article:{id}` | String(JSON) | 3 min | 评论列表 |
| `learning:plan:list:{userId}` | String(JSON) | 5 min | 学习计划列表 |
| `article:liked:user:{uid}` | Set | **无** | 用户点赞的文章集合 |
| `article:favorited:user:{uid}` | Set | **无** | 用户收藏的文章集合 |
| `comment:liked:user:{uid}` | Set | **无** | 用户点赞的评论集合 |
| `{上述 Set}:loaded` | String | 30 min | "已从 DB 全量加载"状态（建议区分 `FULL` / `EMPTY`） |
| `ai:rate:*` | String | 窗口 | AI 限流计数 |
| `ai:workflow:action:*` | String | 120 s | 工作流分布式锁 |
| `ai:workflow:idempotency:*` | String | 24 h | 幂等记录 |
| `ai:agent:action:*` | String | TTL | Agent 动作锁 |
| `rag:index:fail:*` | String | 7 d | RAG 索引失败标记 |

### 3.2 隐患清单（代码核实）

#### 🔴 隐患 1：`KEYS` 命令全库扫描（第一刀）

**位置**：`ArticlesServiceImpl.java:922`，触发 `ArticleViewCountSyncTask.java:13` — `@Scheduled(fixedRate = 30000)`，一天 2880 次。

```java
Set<String> keys = stringRedisTemplate.keys(RedisConstants.ARTICLE_VIEW_KEY_PREFIX + "*");
```

**后果**：`KEYS` 是 O(N) 且**阻塞 Redis 服务端单线程**。Redis 里同时住着 AI 限流、分布式锁、缓存——一次 KEYS 卡住，**这些全部一起卡**。

> **这不是客户端连接数问题**：加连接池不改变服务端阻塞行为。唯一解是换 `SCAN`。

#### 🔴 隐患 2：`:loaded` 语义被破坏（第二刀）

**位置**：`ArticleLikesServiceImpl.java:63-68`（点赞）、`:106-111`（取消点赞），同模式另有 `ArticleFavoritesServiceImpl`、`LikeCommentsServiceImpl` 共 6 处。

```java
//确认点赞时维护 loaded 后的 Set
stringRedisTemplate.opsForSet()
        .add(... USER_KEY_PREFIX + userId, String.valueOf(articleId));

stringRedisTemplate.opsForValue()
        .set(... + ":loaded", "1", 30, TimeUnit.MINUTES);     // ← 无条件写标记
```

**`loaded=1` 的语义是"这个 Set 是全量、可信任"。但点赞路径无条件写它**——Set 此时可能只有刚加进去的这一个元素。
第二刀可以保持旧值兼容：旧值 `1` 暂按 `FULL` 处理，但发现 Set 缺失时必须回源；
新写入再改成能表达 `FULL` / `EMPTY` 的状态值。

**触发链**（Redis 重启 / 故障切换 / 手动清理后）：

```text
Set 与 loaded 标记同时丢失
  → 用户点赞文章 5 → Set={5}、标记=1（无条件）
  → 读取：hasKey(loaded)=true → members()={5}
  → 该用户 DB 里的历史点赞 1,2,3,4 全部消失
  → 每次点赞/取消都刷新标记 TTL → 错误状态无限延长，无自愈
```

**这是数据展示错误，不是内存问题**——用户看到的是"我从没点过赞"。

**同处还有重复写入**（同一操作写两遍）：
- `:56` 与 `:64-65` —— 同一个 `add` 写两次
- `:99` 与 `:107-108` —— 同一个 `remove` 写两次

**修法**：只有"从 DB 全量加载过"才能写 `loaded=1`；普通点赞/取消**只在 `loaded` 已存在时**同步维护 Set，否则不碰（让缓存保持"未加载"状态，下次读时从 DB 全量重建）。

```java
String loadedKey = key + ":loaded";
if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(loadedKey))) {
    stringRedisTemplate.opsForSet().add(key, String.valueOf(articleId));
}
// 不写 loaded —— 没有全量加载就不标全量
```

#### 🔴 隐患 3：浏览量同步的双写不一致（第一刀）

**位置**：`ArticlesServiceImpl.java:942-947`

```java
lambdaUpdate().eq(Articles::getId, articleId)
        .setSql("view_count = view_count + " + increment).update();   // ① 先更新 DB
stringRedisTemplate.delete(key);                                     // ② 再删 key
```

**两个问题**：

1. **① 成功、② 失败（或进程崩在中间）→ key 未删 → 下次同步重复累加 → 浏览量永久膨胀**
2. **循环体内无 try-catch** → 任意 key 出错，整轮同步中断

**修法**：改为**先原子取走、再更新 DB**（Lua 的 `GET` + `DEL`，见 §5.4），逐 key 容错。故障方向从"可能重复计数"改为"可能丢一次增量"——**浏览量是可接受丢失的近似统计**（与 §5.8 的淘汰语义统一），丢比重复好。

**第一刀还必须补上浏览量 key 的兜底 TTL**：详情页目前只执行 `INCR`，`article:view:{id}`
没有 TTL。如果第三刀采用 `volatile-*`，这些 key 会变成无法淘汰的 key。不能用
“`INCR` 后再单独 `EXPIRE`”解决，因为两次调用之间仍可能发生故障或并发竞态；
应使用 Lua 一次完成“递增并在 key 当前没有 TTL 时补 TTL”：

```lua
local current = redis.call('INCR', KEYS[1])
if redis.call('TTL', KEYS[1]) < 0 then
    redis.call('EXPIRE', KEYS[1], ARGV[1])
end
return current
```

这样既覆盖新建 key，也能修复上线前已经存在但没有 TTL 的旧 key。
同步任务原子取走旧 key 后，下一次浏览会重新创建并重新获得兜底 TTL。

**可靠性边界必须写死**：`原子取删 -> 更新 DB` 不是可靠消息投递。Redis 取走成功后，
如果 DB 更新失败，这一批增量会丢失；因此它只适用于本项目明确允许近似的浏览量统计，
不能直接套到库存、余额、账务、订单等不可丢数据。若以后要求浏览量也不丢，应改为
可重试的 pending/outbox/Stream 方案，并为批次建立幂等标识，而不是简单恢复旧 key。

**可靠性边界必须写死**：`原子取删 -> 更新 DB` 不是可靠消息投递。Redis 取走成功后，
如果 DB 更新失败，这一批增量会丢失；因此它只适用于本项目明确允许近似的浏览量统计，
不能直接套到库存、余额、账务、订单等不可丢数据。若以后要求浏览量也不丢，应改为
可重试的 pending/outbox/Stream 方案，并为批次建立幂等标识，而不是简单恢复旧 key。

#### 🟠 隐患 4：热度榜全量重建的两个问题（第二刀）

**位置**：`ArticlesServiceImpl.java:952-976`，每 30 分钟触发。

```java
stringRedisTemplate.delete(RedisConstants.ARTICLE_HOT_KEY);   // ① 先清空
for (...) { zAdd(...) }                                       // ② 再逐篇重建
```

- **问题 A（窗口期空榜）**：① 与 ② 之间，`getHotArticles` 读到空榜 → 用临时 key 重建 + `RENAME` 原子替换
- **问题 B（并发快照不一致）**：与浏览量同步任务无互斥。改成原子取删后，原先
  “DB 已更新、key 未删”导致的重复计分会消失，但重建仍可能在同步任务取走增量前后
  读取到不同快照，导致热榜暂时偏旧或偏新。任务互斥或合并任务可以让这两个操作使用
  同一轮快照；它解决的是一致性窗口，不是 Redis 计数原子性。

**空榜边界**：如果当前没有已发布文章，临时 ZSET 不会被创建，不能直接 `RENAME`
一个不存在的 key。空榜需要用 Lua 原子删除旧榜，非空榜再执行临时 key + `RENAME`。

#### 🟠 隐患 5：Set 无 TTL（第二刀）

三个 Set（文章赞 / 文章藏 / 评论赞）**只有标记有 TTL，Set 本身永不过期** → 内存单调增长，用户长期不活跃后是死数据。

**改动必须与隐患 2 一起做**（都在同一批写点），且顺序有讲究：

| 顺序 | 后果 |
|---|---|
| ✅ Set TTL(40min) 写完 → 标记 TTL(30min) 写 | 标记在 → Set 必在 → 无空读 |
| ❌ 标记先写 → Set 后写 | 中间窗口标记在、Set 空 → 读到空集合 → **显示"没点过赞"**（静默错误） |

#### 🟠 隐患 6：配置缺失（第三刀）

`application.yml:30-37` 只有 host / port / password / database。

- **没有 `commons-pool2` 依赖**（已核实 `pom.xml` 零匹配）→ 配 `lettuce.pool.*` **不会生效**，Lettuce 保持共享单连接
- 无超时配置
- 服务端策略（淘汰 / 持久化 / 危险命令）未文档化

**第三刀处置**：超时已补、连接池决策为"不引"（§5.7）；服务端策略写入
`deploy/redis.conf.example`（§5.8）；顺带补上热度榜的 TTL——它是补之前唯一无 TTL 的 key，
而 `volatile-lru` 的前提正是"允许丢失的数据都有 TTL"。

## 四、分阶段路线

### 第一刀：缓存可靠性（窄刀，先做）

| 项 | 对应 | 位置 |
|---|---|---|
| 1. 读路径收口（三条合一） | 前置 | `ArticlesServiceImpl.getPublicArticleById` + `getArticleDetailFromCache` |
| 2. 文章详情互斥回源 | 击穿 | 同上 |
| 3. `KEYS` → `SCAN` | 隐患 1 | `ArticlesServiceImpl.syncViewCountToDb` |
| 4. 浏览量 Lua 原子取删 + 逐 key 容错 | 隐患 3 | 同上 |
| 5. 浏览量 `INCR` 首次写入时原子设置兜底 TTL | 内存淘汰前置条件 | `ArticlesServiceImpl` 详情浏览统计 |
| 6. TTL 抖动（7 处数据缓存） | 雪崩 | 见 §5.2 |

**为什么这一刀放在一起**：1、2 是同一段读路径的两面（不收口则互斥会破坏空值缓存语义）；
3、4 是同一个方法；5 是浏览量 key 采用 `volatile-*` 前必须满足的原子 TTL 条件；
6 是同一批缓存 `set` 调用。

**为什么窄**：主要集中在文章域的读路径与一个定时方法，TTL 接线另涉及现有的
文章列表、评论、分类、标签和学习计划缓存写点；不碰点赞/收藏业务线、不碰构建依赖。

### 第二刀：用户态集合与热榜

| 项 | 对应 |
|---|---|
| 1. `:loaded` 语义修复（6 处） | 隐患 2 |
| 2. 重复 Redis 写入清理（4 处） | 隐患 2 |
| 3. Set TTL + 写入顺序修正 | 隐患 5 |
| 4. 热榜临时 key + `RENAME` | 隐患 4A |
| 5. 定时任务互斥 | 隐患 4B |

### 第三刀：生产配置与面试口径

| 项 | 做法 |
|---|---|
| 1. 超时配置 | `timeout` / `connect-timeout`（**与 KEYS 无关，独立价值**） |
| 2. 连接池决策 | **先决定要不要引 `commons-pool2`**——不引就别配 `lettuce.pool.*`（配了无效反而误导） |
| 3. `maxmemory-policy` | 按部署拓扑确定（见 §5.8），不预先写死 |
| 4. 持久化取舍 | 写清"讲得清"而非"必做"（见 §5.8） |
| 5. 危险命令 | 应用账号 ACL 限制 `KEYS` / `FLUSHALL`，不采用全局 `rename-command` |
| 6. 慢查询 / 大 key / 热 key | 排查手册（`slowlog` / `--bigkeys`） |

### 第四刀：安全类 Redis

| 项 | 做法 |
|---|---|
| 1. 登录失败限制 | 计数维度取**两个独立维度、非等权**：账号维度（归一化用户名）5 次 / 15 分钟 + IP 维度 30 次 / 15 分钟，任一超限即拒。**不合成「用户名 + IP」组合键**——理由见 §5.11 的攻击形态覆盖矩阵 |
| 2. 账号存在性 | 三条一起做才算数：不存在的账号**同样计数**、提示语统一、**补一次 bcrypt 抹平响应时间** |
| 3. token 黑名单 | JWT 加 `jti` 字段，黑名单键 `token:blacklist:{jti}`，TTL = token **剩余有效期**（不是固定时长） |
| 4. 登出链路 | 后端 `/api/auth/logout` 落实现 + **前端真正调用它**（改之前 `authApi.logout` 是定义好却从没被调过的死代码） |

### 第五刀：缓存一致性补偿（已实施）

| 项 | 做法 |
|---|---|
| 1. 失效时机 | 新增 `component/AfterCommitExecutor`：有事务则 `registerSynchronization` 到提交后执行，**无事务则就地执行**。所有写路径的缓存失效统一走它 |
| 2. 收口范围 | 文章详情 / 文章列表 / 评论列表 / 学习计划列表 / 用户集合（点赞·收藏）——改的是**封装方法内部**，调用点一个没动 |
| 3. 删除失败 | 静默 `catch` → `log.warn("[CACHE-EVICT-FAIL] ... key={}", ...)`。**不做重试**：Redis 真挂时重试也是白搭，TTL 兜底 |
| 4. 明确不做 | 延迟双删（窗口微秒级且延迟时长无法准确设定）、outbox / binlog / Canal（触发条件见 §八）、失败重试队列 |

**顺带修掉一个副作用**：事务回滚时不再白删缓存——旧实现已经删了，而数据其实没变。

**登记（本刀不修）**：`ArticleRagSyncService.indexArticle` 是 `@Async`，却在事务方法内**发起**。
异步线程可能在提交前跑，读到旧文章（索引里留旧标题）。性质同源（副作用早于提交）、后果更轻
（不影响用户直接可见的数据）。触发条件：出现"搜索/问答结果与文章实际内容对不上"的反馈时。

## 五、逐项设计

### 5.1 击穿：读路径收口 + 互斥回源（第一刀）

方案与三个坑见 `cache-breakdown-avalanche-design.md` §二。

**本刀的前置修正**：读路径**现有已经是两条**（`ArticlesServiceImpl:164` 判空值 + `:183` 再读一次），互斥轮询是第三条。**先收口到单一读函数**，否则坑 3（空值缓存被误删）防不住。

**权限语义不能在收口时丢失**：空值缓存不能只写一个裸 `__NULL__`。不存在文章对所有身份都可以直接返回；
隐藏 / 草稿等不可见文章要带上作者与状态，只有“隐藏文章作者本人预览”才绕过/失效空值后回源。
否则会在两个方向出错：一律返回会挡住作者预览；一律让登录用户回源又会让普通登录用户绕开空值缓存，穿透防护失效。

**锁的实现（按 Codex 评审修正）**：

> **复用模式，不复用类**。`WorkflowActionLock` 的 `LOCK_TTL_SECONDS = 120`（`WorkflowActionLock.java:19`）是 Workflow 语义，失败时抛 `WorkflowActionBusyException`；缓存击穿锁需要的是**短 TTL（5 s）+ try-acquire + 失败后轮询/降级**。只沿用其 `SET NX PX + token + Lua 释放` 的模式，独立实现，独立 key 前缀。

### 5.2 雪崩：TTL 抖动（第一刀）

±20%，见专题文档 §三。

**范围修正**：**7 处数据缓存**该抖（`ArticlesServiceImpl:145/1021/1070`、`CommentsServiceImpl:381`、`CategoryServiceImpl:59`、`TagsServiceImpl:57`、`LearningPlansServiceImpl:225`，其中最后一处用 `Duration` 重载，需统一）；**9 处 `:loaded` 标记位不抖**（每用户一个的加载标记，失效只让该用户重查一次 DB，不构成 DB 压力峰值）。

### 5.3 `KEYS` → `SCAN`（第一刀）

```java
ScanOptions options = ScanOptions.scanOptions()
        .match(RedisConstants.ARTICLE_VIEW_KEY_PREFIX + "*")
        .count(200)
        .build();
try (Cursor<String> cursor = stringRedisTemplate.scan(options)) {
    while (cursor.hasNext()) { ... }
}
```

**代价要讲清**：SCAN 不保证快照一致性、可能返回重复元素。本项目**可接受**——配合 §5.4 的原子取删后天然幂等。

**不能只迁移浏览量同步这一处就屏蔽 `KEYS`**：当前代码里还有文章列表失效、
评论列表失效、RAG 失败标记清理和迁移后缓存清理等调用点。第三刀的生产安全措施应是
“应用账号通过 ACL 禁止高风险命令，迁移/运维账号单独授权”；前提是所有运行期调用点
先完成迁移。不要在现状下直接执行 `rename-command KEYS ""`。

### 5.4 浏览量同步：Lua 原子取删 + 逐 key 容错（第一刀）

```java
String incrementStr = stringRedisTemplate.execute(VIEW_TAKE_SCRIPT, singletonList(key));  // GET + DEL 一次 Lua
if (incrementStr == null) return null;
try {
    int increment = Integer.parseInt(incrementStr);
    if (increment <= 0) return;
    lambdaUpdate().eq(Articles::getId, articleId)
            .setSql("view_count = view_count + " + increment).update();
} catch (Exception e) {
    log.warn("浏览量同步失败，本次增量丢弃: articleId={} increment={}", articleId, incrementStr, e);
    failureCount++;      // 汇总日志，不中断整轮
}
```

> **原设计用 `GETDEL`（Redis ≥ 6.2），实施时改成了 Lua 的 `GET` + `DEL`。** 原因不是版本不够
> （本地实测 6.2.14 支持 `GETDEL`），而是 Lua 没有版本下限——`GETDEL` 是 6.2 才有的命令，
> 而 Lua 从 Redis 2.6 就有。既然脚本已经写好，就没必要再留一个"部署环境必须 ≥ 6.2"的隐含前提。
>
> **不能退化为普通 `GET` 后 `DELETE`**：并发浏览可能在两次调用之间写入增量，造成新增量被
> 一起删掉。原子性要么靠 `GETDEL`、要么靠 Lua，不能靠两次独立调用。第一刀的版本前提问题
> 就此关闭（§九 #3）。

### 5.5 `:loaded` 语义修复 + Set TTL（第二刀）

**规则一（语义）**：只有从 DB **全量加载**才能写 `loaded=FULL` 或 `loaded=EMPTY`；
点赞/取消点赞**只在 `loaded` 已存在时**维护 Set。旧值 `1` 在迁移期按 `FULL` 兼容，
但不能再无条件续写一个不代表全量的标记。

**规则二（顺序）**：Set TTL(40 min) **>** 标记 TTL(30 min)，且**先写 Set、后写标记**。
每次在已加载集合上执行点赞/取消点赞，都要同步刷新 Set TTL；不能只刷新 `:loaded`
的 TTL，否则 Set 临近过期时，操作会把标记续到 30 分钟而 Set 仍可能很快消失。

**规则三（清理）**：删除 4 处重复写入（`ArticleLikesServiceImpl:56/64`、`:99/107` 等）。

**这两个 TTL 不是绝对正确性保证**：Redis 内存淘汰、重启恢复或单次写入失败，
可能只留下标记或只留下 Set。读取时不能仅凭 `loaded=1` 把缺失的 Set 当成“空集合”：

- 非空集合：`loaded` 存在且 Set 存在，才视为命中；
- 空集合：需要显式空集状态（推荐让 `:loaded` 的值区分 `FULL` / `EMPTY`，
  或保留内部 sentinel），不能把“Set key 不存在”直接解释成缓存未命中；
- 标记存在但 Set 缺失：按未加载处理，回源 DB 重建，不能直接返回空集合。

如果继续保留“Set + loaded 两个 key”的结构，第二刀至少要覆盖这个判定；
否则 Set TTL 只解决正常过期，不解决部分淘汰造成的静默错误。

**状态转换也要明确**：

```text
未加载               -> 回源 DB -> FULL / EMPTY
FULL + 点赞          -> Set SADD + 刷新 Set TTL，保持 FULL
FULL + 取消最后一项   -> 切换为 EMPTY，不能留下“FULL + 缺 Set”
EMPTY + 新增一项      -> 创建 Set，状态改为 FULL
loaded=FULL 但 Set 丢失 -> 回源 DB 修复，不能直接返回空集合
```

Set 变更、Set TTL 和 `loaded` 状态如果拆成多次 Redis 调用，会有中间不一致窗口；
至少要做读时修复，若要把这条链路做成原子更新，应使用 Lua 将集合变更与状态更新放在
同一脚本中。

**改动点**：3 条业务线 × 2 个方向 = 6 处 + 3 个重建点（`ArticlesServiceImpl:318/389`、`CommentsServiceImpl:500`）。

#### 实施记录（B 方案：Lua 打包）

新增 `component/UserSetCache`，三条业务线（文章点赞 / 文章收藏 / 评论点赞）全部收口到它，
`opsForSet` 与 `:loaded` 字面量不再出现在业务代码里。选择 Lua 而非"检查 + SADD + 刷两个 TTL"
的四次往返，理由不是竞态严重（竞态最坏只是白写一次、下次读自愈），而是**调用次数从 4 降到 1，
且写入顺序不再需要调用方保证**。

**实施中发现并修复的一个存量 bug**（旧代码同样存在，非本次引入）：

`markLoaded` 原来直接用 `SADD` 写入——**SADD 是追加不是覆盖**。触发链：

```text
标记过期(30min)但集合还在(40min，Set TTL 更长)
  -> 这期间用户取消了点赞：维护逻辑发现未加载、正确地什么都没做，DB 已删
  -> 回源 DB 得到正确集合 -> SADD 到残留成员上
  -> 已取消的点赞"复活"
```

修法是写入前先 `DELETE`。`delete` 与 `add` 之间的窗口是安全的：读路径看到集合为空
会判为"状态不可信"去回源，不会读到错误的中间态。

**旧值兼容不需要运维动作**：旧值 `1` + 集合存在 → 按 `FULL` 用；旧值 `1` + 集合缺失 →
拒绝维护、读路径回源。30 分钟后旧值自然过期，无需手动清理。

### 5.6 热度榜：临时 key + `RENAME` + 调度串行化（第二刀，第三刀补 TTL）

```java
String tmpKey = RedisConstants.ARTICLE_HOT_KEY + ":rebuilding";
stringRedisTemplate.delete(tmpKey);
for (Articles article : articles) { zAdd(tmpKey, ...); }
// 检查 + RENAME + 补 TTL，一次原子操作（第三刀，见下方实施记录）
stringRedisTemplate.execute(RENAME_HOT_RANK_SCRIPT, List.of(tmpKey, ARTICLE_HOT_KEY), ttlSec);
```

**空榜边界**：没有已发布文章时临时 key 不会被创建，不能 `RENAME` 一个不存在的 key。
原设计写"空榜需要用 Lua 原子删除旧榜"——**实施时简化为直接 `DEL`**：`DEL` 本身就是单命令原子，
`RENAME` 也是，两者都不需要 Lua 包装。这个"不需要 Lua"的结论在第三刀被部分推翻：单看空榜确实
不需要，但加上 TTL 与并发之后需要了，见下。

**并发计分：不加锁，改为显式声明调度器池大小**（原设计主张加一把共享锁，核实后推翻）：

- 3 个 `@Scheduled` 全在 Spring Boot 默认 `ThreadPoolTaskScheduler` 上，项目没有自定义
  `TaskScheduler` bean，也没配 `spring.task.scheduling.pool.size` → **默认 poolSize=1，任务本就串行**
- 加锁是为一个不存在的问题写代码
- 但"不并发"原来是**隐式依赖默认值**，没有任何一行代码或配置声明它——谁把池子调大就悄悄坏了

改为在 `application.yml` 显式写出 `spring.task.scheduling.pool.size: 1`，并在
`ArticleViewCountSyncTask` 加类注释说明"改这个值前先想清楚"。这同时解决了隐患 4B 的另一半：
重建与同步读同一批数据（`article:view:*` 增量 + 文章计数），串行调度让两者天然使用同一轮快照。

**fixedRate 的副作用要记住**：串行意味着重建（遍历全部已发布文章 + 逐篇 `zAdd`）会推迟
同一时刻该跑的浏览量同步。当前文章量级下可忽略；文章数上千时需要重新评估
（把重建挪到独立调度器 + 用锁保护共享数据），不能只是把池子调大。

#### 实施记录（第三刀补的两个问题）

**问题 1：热度榜是当时唯一没有 TTL 的 key。** §3.1 核对 key 全景时发现，除它之外每个
允许丢失的 key 都有 TTL。缺 TTL 的后果不是"占点内存"，而是**破坏 `volatile-lru` 的前提**——
内存打满时若剩余 key 全无 TTL，淘汰策略找不到可淘汰对象，Redis 对写入直接报错。
补 `ARTICLE_HOT_TTL_HOURS = 2`（> 30 分钟重建周期，正常永远不过期；只有后端长时间停机
才会消失，届时重建补回）。

**坑：TTL 必须补在 `RENAME` 之后。** `RENAME` 会把**目标 key 的 TTL 覆盖成源 key 的**，
而临时 key 每轮都是新建、无 TTL——写在 `RENAME` 前面等于白写。这个顺序错了的症状是
**静默的**：榜单照常工作，只是永远不过期，`volatile-lru` 淘汰不到它。手测覆盖不了这种回归。

**问题 2：并发重建会撞 `RENAME` 报错**（实测复现，不是推演）。`RENAME` 的源 key 不存在时
抛 `ERR no such key`：两个执行者共享同一个临时 key，先完成者把它 `RENAME` 走，后到者必炸。

```text
生产：只有定时任务一个调用方，调度池 size=1，撞不上
测试 / 手工触发：成为第二个调用方 -> 必现
```

这个错就是本刀新增的并发测试第一次跑时炸出来的（测试进行中，上下文启动触发了定时任务首跑）。

修法：`EXISTS` + `RENAME` + `EXPIRE` 打包成一个 Lua 脚本（`RENAME_HOT_RANK_SCRIPT`）。
后到者走 `EXISTS` 分支安静跳过——两个执行者的数据同源，跳过等于结果一样。
副产品是 TTL 的顺序约束也不再由调用方保证。

**回归测试**：`ArticleHotRankTests` 3 例（TTL 存在、无临时 key 残留、4 线程并发重建不抛异常）。
反向验证过：临时改回旧的 `rename` 调用，并发用例立刻失败（`ExecutionException → ERR no such key`），
证明测试确实抓得住，不是假通过。

### 5.7 连接与超时（第三刀）

**决策结果（2026-09-15）：不引 `commons-pool2`。** 需要连接池的三个理由逐一核实，全不成立：

| 需要连接池的理由 | 本项目 |
|---|---|
| 阻塞命令（`BLPOP` 等）独占连接 | ❌ 全部是非阻塞命令 |
| 事务（`MULTI` / `WATCH`）需独占连接 | ❌ 没有；原子性靠 Lua，而 Lua 不独占连接 |
| 需要限制连接数 | ❌ 单实例单连接，离 `maxclients` 很远 |

根因是 I/O 模型不同：**Jedis 同步阻塞**，一条连接同时只能跑一条命令，所以必须池化；
**Lettuce 基于 Netty 异步**，一条连接可承载大量未完成命令（多路复用），非阻塞场景下池化
反而增加连接数与线程开销。这也是面试口径——"MySQL 需要连接池、Redis 不一定"的答案不是
"Redis 更轻"，而是阻塞 / 异步的区别。

已配置（`application.yml`）：

- `timeout: 3s` —— 命令超时。**它不给 Redis 减负**：超时后命令仍在服务端跑完，
  它保护的是应用线程（缓存读卡住时及时 fail-open 去查 DB，而不是把 Tomcat 线程一起拖死）
- `connect-timeout: 2s` —— Redis 不可达时快速失败走 DB 兜底，不在 TCP 握手上耗着

项目里最重的 Redis 操作是 `ZREVRANGE`（10 个成员）和 `SCAN`（`COUNT 200`），都是毫秒级，
3 秒很宽松。配置里还留了**反向注释**：不要加 `lettuce.pool.*`——没有依赖时它会被静默忽略，
反而让人以为池已生效。只有将来引入 `BLPOP` / `MULTI` / `WATCH` 时才需要引依赖再配池。

### 5.8 服务端策略（第三刀，已写入 `deploy/redis.conf.example`）

产物是可直接合并进 `/etc/redis/redis.conf` 的配置模板；本节只记**取舍理由**，
排查手法另见 `redis-ops-runbook.md`。

| 配置 | 值 | 理由 | 状态 |
|---|---|---|---|
| `maxmemory` | 按实例内存实测确定，60~70% 仅作起点 | 给 RDB/AOF rewrite 的 fork、复制缓冲、客户端输出缓冲留余量——设成 90% 的典型后果是 fork 时 OOM | ✅ 模板 |
| `maxmemory-policy` | `volatile-lru` | 见下 | ✅ 模板 |
| 浏览量 key 兜底 TTL | 30 min | 正常 30 s 就被同步任务取走，TTL 只在异常时兜底；同时让 `volatile-lru` 有对象可淘汰 | ✅ 第一刀 |
| 热度榜 TTL | 2 h | 同上——补上这个缺口前它是唯一无 TTL 的 key | ✅ 第三刀 |
| `appendonly yes` + `appendfsync everysec` | 建议，非必做 | 见下 | ✅ 模板 |
| 应用账号 ACL | `on #<sha256> ~* +@all -@dangerous` | 见下 | ✅ 第三刀 |

**语义统一（v1 的自相矛盾在此修正）**：浏览量是**可接受丢失的近似统计**，不是"不可丢"数据。它在两处体现同一语义——

- 同步失败时丢一次增量（§5.4）优于重复计数
- 内存压力下允许被 `volatile-lru` 淘汰

**为什么是 `volatile-lru` 而不是 `allkeys-lru`**（原设计留的"待按部署拓扑定"，现在定了）：
Redis 里除了可丢的缓存，还住着分布式锁、幂等键、限流计数这类**丢了会出错**的状态。
`allkeys-*` 在内存压力下会一视同仁地淘汰它们——缓存少一条只是慢一点，锁被淘汰是并发正确性问题。
`volatile-*` 的代价是"所有允许丢失的数据都必须有 TTL"，这就是上面两行 TTL 存在的意义。

**持久化口径降级**：本项目的 Redis **大部分是缓存或软状态**——浏览量允许近似，锁 / 限流 / 幂等都有 TTL 与 DB 状态兜底。所以：

- **可以讲**："如果 Redis 与其他软状态共用实例，可以开 AOF everysec，降低重启后的状态丢失；
  但它不解决 Redis 与 MySQL 的跨系统一致性，也不替代浏览量失败补偿或缓存失效补偿"
- **不该写成必做**：它不是代码第一刀的组成部分，属于部署建议

#### ACL：为什么不用 `rename-command`，以及为什么不需要 `+client`

**不用全局 `rename-command KEYS ""`**：它全局生效且不可逆，管理员和迁移脚本也需要 `KEYS`；
一旦改名，所有调用点（含运维手册上的命令）跟着失效。ACL 按账号授权，管理员不受影响。

> 第一刀曾顾虑"还有 4 处在调 `KEYS`（文章列表失效 / 评论列表失效 / RAG 失败标记清理 /
> 迁移后清理），全局禁用会把它们变成运行时失败"。**该顾虑在第三刀已消除**：运行期代码的
> `KEYS` 调用点**全部清零**（统一走 `RedisKeyScanner` 的 `SCAN` + `UNLINK`）。
> 全局禁用技术上不再有障碍，但仍选 ACL——理由是上面那条。

**规则**：`user blog-app on #<sha256> ~* +@all -@dangerous`

- 密码写 SHA256（`#<hash>`）而非明文，配置文件里不出现密码
- 不禁 `SCAN`——它不在 `@dangerous` 里，这正是第一刀改成 `SCAN` 之后仍然可用的前提
- 可以进一步按 key 前缀收敛（`~article:* ~ai:rate:*`…），代价是每新增一个 key 家族都要
  同步改配置、漏改的症状是运行期 `NOPERM`。当前用 `~*` 换可维护性，真正的护栏是禁掉危险命令

**为什么不需要 `+client`（这条靠查源码，不是推演）**：

`-@dangerous` 在 Redis 6.x 上会把整个 `CLIENT` 命令禁掉，而 Lettuce 建连时会发
`CLIENT SETINFO`（Redis 7.2+ 才有）——看起来会导致应用连不上。核实结论是不影响：

```java
// io.lettuce.core.RedisHandshake
private CompletionStage<Void> applyConnectionMetadataSafely(Channel channel) {
    return applyConnectionMetadata(channel)   // 发 CLIENT SETNAME + 两条 CLIENT SETINFO
            .handle((v, t) -> {               // ← 无论成败都转成成功
                if (t != null) LOG.debug("Error applying connection metadata", t);
                return null;
            });
}
```

元数据命令的失败被整个吞掉，只在 DEBUG 级留一行日志。而且 Redis 7.2+ 已把 `CLIENT`
移出 `@dangerous`，那之后连这条 `NOPERM` 都不会产生。两条路都通向"不需要 `+client`"。

**这一条本地测不了，所以刻意没写成测试**：本地 Redis 是 6.2，压根不发 `CLIENT SETINFO`，
写这样的用例是空跑——留个"看起来验证过"的测试比没有更糟。能测的那部分（ACL 规则会不会
误伤应用实际用到的命令）由 `RedisAclRuleTests` 锁住，并做过反向验证。

**应用侧配套**：`spring.data.redis.username`（第三刀新增，默认空 = default 用户）。
只配密码不配账号的话，应用拿到的仍是 default 的全权限，ACL 等于没上。

### 5.9 Cache-Aside 的失效时机（第五刀，已实施）

改造前部分写路径带有 `@Transactional`，但缓存删除发生在事务提交之前。例如文章状态、
点赞和学习计划等路径：

```text
事务内更新 DB
  → 删除缓存
  → 另一个请求 miss，读到"尚未提交"的旧值并回填缓存
  → 原事务提交新数据
  → 缓存继续保留旧值直到 TTL
```

**这不是"概率很低"的竞态**：删缓存之后到事务提交之前的这段时间里，任何一次缓存 miss 都会走这条链，
窗口宽度由事务本身决定（含后续的 RAG 同步等操作）。所以"写库后删缓存"还不够，
严格的 Cache-Aside 应是**事务提交成功后再失效缓存**。

**实施（2026-09-15）**：新增 `component/AfterCommitExecutor`，所有**写路径**的缓存失效统一走它。

```java
public void execute(Runnable action) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
        action.run();          // 无事务：就地执行，行为与改造前逐字节一致
        return;
    }
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override public void afterCommit() { action.run(); }
    });
}
```

**为什么不用 `@TransactionalEventListener(AFTER_COMMIT)`**：事务事件在**没有事务时默认不执行**。
本项目大量缓存失效发生在非事务方法里（删文章 / 隐藏 / 发布 / 评论 / 收藏），
一旦有人给某个方法去掉 `@Transactional`，缓存就**永远不再失效**——这种失效是静默的，
比"删早了"更难发现。显式 fallback 让"无事务"这条路径的行为一眼可见。

**边界**：
- 读路径的缓存**回填**（`UserSetCache.markLoaded`）与**脏数据自愈**（分类 / 标签反序列化失败删 key）
  **不走它**——读路径本就没有事务，且回填必须立即生效给后续请求用，延后没有意义
- 动作必须只碰 Redis / 内存：事务已提交，此时再访问 DB 是另开事务
- 组件内部兜住异常：`afterCommit` 抛出去会传播到 `commit()` 的调用方，让一次**已经提交成功**的操作返回失败

**删除失败**：记录 `[CACHE-EVICT-FAIL]` 前缀的日志并依靠 TTL，不做重试（Redis 真挂时重试也是白搭）。
不能把 AOF 当作跨 MySQL/Redis 一致性方案。

**仍然不是强一致**：提交后删消除了"必然发生"的那个窗口，但 Cache-Aside 还剩一个**需要恰好并发**的窗口——
某次读请求在提交**之前**查库（拿到旧值），而它把结果写进缓存的时刻**晚于**我们的删除动作，旧值就留下了。
（注意不是"提交后删除前读到旧值"：那段时间缓存里还是旧值，读到的是命中，不会触发回填。）
解决它要靠延迟双删或订阅 binlog，本刀不做（触发条件见 §八）。

### 5.10 命中率与 key 治理（第三刀 / 后续）

**当前 key 粒度已经有基础约束，但仍存在低命中率风险**：

- 文章列表只缓存无关键词请求，但 `page`、`pageSize`、分类和排序都进入 key；
  如果允许任意分页参数，会产生大量低复用 key。应在入口统一分页边界，并优先缓存
  首页和常用 `pageSize`；
- 评论列表同样把分页和排序全部放入 key，深页通常访问频率低，没必要与首页使用
  相同的缓存策略。可以只缓存前几页或热点排序，深页直接查库；
- 文章详情是最适合高命中的缓存对象，应优先保证详情读路径、TTL 和失效语义；
  不要为了“命中率”把用户点赞、收藏等个性化状态塞回公共文章缓存。

命中率不能靠感觉判断。成熟做法是按 key family 记录低成本结果计数：

```text
HIT / MISS / BYPASS / NULL_HIT / DESERIALIZE_ERROR / REDIS_ERROR
```

第一阶段只做计数或采样日志，不记录完整 key 和用户隐私；至少能回答“哪一类缓存
命中率低、miss 后查库耗时多少、Redis 故障是否导致整体降级”。没有这组数据前，
不建议直接引入 Caffeine、多级缓存或大规模预热。

**key 冲突与版本治理**：

- 当前 key 按领域前缀区分，能避免大部分业务类型冲突；
- 但 key 没有应用 / 环境 / schema 版本前缀，不能把 Redis `database` 当作安全隔离；
  如果开发、测试、生产共用实例，应使用独立实例或账号，并规划
  `app:env:domain:resource:vN:...` 形式的前缀；
- 缓存 JSON 结构变更时优先使用新版本 key，避免旧值反序列化失败后集中回源；
  版本切换完成后再清理旧前缀，不在运行期用全库 `KEYS` 批量删除。

**原子扣减边界**：当前项目没有库存、余额或配额扣减场景，不为了展示 Redis 能力
强行新增 `DECR` 业务。以后出现这类不可超卖的资源，应使用 Lua 的“检查余额并扣减”
或数据库条件更新；普通的 `GET` + `DECR` 不具备并发正确性。

### 5.11 登录失败限制与 token 黑名单（第四刀）

#### （1）计数维度：两个独立维度，非等权

**按攻击形态选维度**，而不是按"哪个副作用小"选：

| 攻击形态 | 账号维度 | IP 维度 | 「用户名 + IP」组合 |
|---|---|---|---|
| 单 IP 定向爆破（一个账号硬撞） | ✅ | ✅ | ✅ |
| 分布式爆破（一个账号、代理池多 IP） | ✅ | ❌ | ❌ |
| 撞库（泄露库，多账号各试一两次、同源 IP） | ❌ | ✅ | ❌ |

- **账号维度（严：5 次 / 15 分钟）**：分布式爆破时每个 IP 都只试几次，按 IP 或按组合计数都攒不到阈值
- **IP 维度（宽：30 次 / 15 分钟）**：撞库时每个账号只失败一两次，只有 IP 维度看得出"这个来源在批量试"

**组合维度只覆盖第一种**——它把"账号能被锁死"这个副作用换掉了，代价是漏掉两种主流攻击，
那不是漏拦一点点，是限流装置形同虚设。

**实施记录（一次走错的弯路）**：本刀最初按组合维度实施（等于退回最早的提法），根因是调研阶段
只从"消掉锁死真实用户"这一个点出发做推演，又用一个自己标的 ⚠️ 把单方面改动写成了
"对 §3.2 的修正"，**没有回到讨论环节确认**——于是覆盖矩阵的漏洞没有第二双眼睛看到。
复核覆盖矩阵后改回双维度。

**残留代价（已知，不假装解决）**：账号维度仍可被用来锁死真实用户——攻击者拿别人的用户名
连试 5 次即可。代价被限制在"15 分钟不能登录"，且该用户登录成功即清零。彻底消除要换成
渐进式延迟 / 验证码（OWASP 对这一条的建议），不在本项目范围内。

**成功登录只清账号键，不清 IP 键**：IP 计数表达的是"这个来源可疑"，张三登录成功证明不了
同一 NAT 出口下的李四没在撞库；更关键的是——清 IP 键等于留后门，攻击者只要有一个自己的账号，
就能靠反复登录成功把 IP 计数刷掉。

#### （2）用户名必须归一化（这一条由 DB collation 决定）

实测本地库：

```text
@@collation_database   utf8mb4_unicode_ci
users.username         varchar(50)  utf8mb4_unicode_ci
```

`WHERE username = 'Admin'` 能命中 `admin` —— 在库里它们**本来就是同一个用户**。
计数键若不归一化，`Admin` / `ADMIN` / `admin` 是三个键，5 次阈值被拆成 15 次。
归一化（`trim` + 小写）后与 DB 的比对语义一致，不存在误伤。

**边界**：只处理大小写与首尾空格，不做完整 Unicode 折叠。残留的同形字变体只会让计数分散，
不构成直接入侵——这条写进类注释，不假装完全解决。超长用户名截断到 50（对齐列宽），防止输入撑大 key。

#### （3）不泄露账号存在性：三条一起做才算数

任何一条漏掉，另外两条都白做：

1. **不存在的账号同样计数**。否则"会不会被限流"就是账号是否存在的探针——试到第 6 次，
   被拒的是存在的账号，不被拒的是不存在的（§3.2 已识别）
2. **提示语统一**：锁定时的提示对两类账号完全一致，且与"用户名或密码错误"共用同一个失败分支
3. **补一次 bcrypt 抹平响应时间**。`users == null` 时不做密码校验会立即返回，
   而 `users != null` 要算 ~100 ms —— **响应时间本身就是探针**，与第 2 条自相矛盾

第 3 条最容易漏：它不在提示语层面，而在时间维度上。实现是对一个类加载时现算的 dummy hash
跑一次 `matches`（不硬编码 hash 字符串，也不需要它对应任何真实密码）。

#### （4）Lua 脚本刻意不与 `AiRateLimiter` 共享

两份脚本文本相同（`INCR` + 首次 `EXPIRE`），但**契约不同**：

| | `AiRateLimiter` | `LoginAttemptLimiter` |
|---|---|---|
| 调用时机 | 每次请求消费一个配额 | **只在失败时**计数 |
| 清零 | 无此概念 | 登录成功必须清零 |

共享一个常量会制造"改这里会不会影响那边"的隐性耦合，而 4 行 Lua 重复的成本远低于一次错误抽象。
**触发条件**：出现第三处，或两处开始同步演进时再抽公共组件。

> §3.2 倾向的选项 C 是"独立类 + 共享脚本常量"。实施时采纳了独立类，但**放弃了共享常量**，
> 理由如上。

原子性仍是必需的：`INCR` 与 `EXPIRE` 分开调用，一旦 `INCR` 成功而 `EXPIRE` 失败，
计数键**永不过期 → 用户被永久锁定**，重启也不恢复。

失败计数与锁定共用同一个键（`login:fail:account:{归一化用户名}` 与 `login:fail:ip:{IP}`，固定窗口 15 分钟）：
计到上限后窗口内一律拒绝，窗口自然过期即解锁，不需要第二个"锁定标记"键。

#### （5）token 黑名单：`jti` + 精确 TTL

JWT 是无状态的，签发后服务端没有任何办法提前作废——黑名单是唯一手段。

- **加 `jti`**（RFC 7519 就是为此字段设计的），而不是按 `userId` 拉黑：按 userId 等于
  "登出踢掉全部设备"，被追问多端登录时答不上来
- **TTL 取 token 的剩余有效期**（`exp - now`），不是固定 7 天：条目只需活到 token 自己过期为止
- 已过期的 token 不写黑名单（它本来就无效）

**存量兼容**：加 `jti` 之前签发的 token 没有这个字段，解析出来是 null、因而无法被拉黑——
这类 token 登出后仍有效到 `exp`。属于可接受的过渡（重新登录即拿到带 `jti` 的新 token），
不为此写兼容分支。

#### （6）两处都 fail-open，但理由不同

| | Redis 故障时 | 理由 |
|---|---|---|
| 登录失败限制 | **放行** | 拒绝会让 Redis 一抖等于**全站登不进去**（确定性伤害）；放行的风险有 bcrypt（每次 ~100 ms）天然限速兜底 |
| token 黑名单 | **按未拉黑放行** | 黑名单是加强项、不是唯一防线，JWT 自身的 7 天过期是兜底 |

两者都**不做配置开关**：这里没有"两种都合理"的情形，留一个开关只是给人配错的机会。
故障时一律打 ERROR 日志留痕（安全类降级必须可追溯）。

#### （7）落点

| 文件 | 改动 |
|---|---|
| `security/LoginAttemptLimiter` | 新建：`checkBlocked` / `recordFailure` / `clear` |
| `security/TokenBlacklist` | 新建：`revoke` / `isRevoked` |
| `utils/JwtUtil` | `generateToken` 加 `jti`；`parseToken` → `parsePayload`（返回 `userId` / `jti` / `exp`） |
| `LoginServiceImpl.login(dto, clientIp)` | 查锁定 → 查库 → 失败计数 / 成功清零 |
| `LoginController.logout` | 手动从 `Authorization` 头取 token（`/api/auth/**` 不在任何拦截器路径里），幂等：无 token / 无效 token 也返回成功 |
| `JwtInterceptor` / `OptionalJwtInterceptor` | 查黑名单：前者 401，后者按游客处理 |

**黑名单必须在两个拦截器都查**：只改 `JwtInterceptor` 的话，登出后的 token 在公开接口上
仍按已登录处理，照样能点赞、评论——等于没登出。

`RateLimitExceededException` 与 `RedisConstants` 只做加法（新增带 message 的构造函数、
两个 key 前缀），限流的 429 + `Retry-After` 基础设施整体复用，没有新建第二套。

## 六、验证

| 刀 | 验证方式 | 结果 |
|---|---|---|
| 第一刀 | 集成（真 Redis + 真 MySQL）：`ArticleDetailMutexTests` 4 例——互斥只回源一次（8 线程并发）/ 轮询读到空值缓存不误删 / 轮询超时 fail-open 回源 / 浏览量增量原子取走且只取一次。单测：`CacheTtlSupportTests` 5 例（抖动范围）+ `RedisKeyScannerTests` 5 例（SCAN 游标 / UNLINK 删除）。手测：并发访问同一篇文章、连续刷文章后核对 DB 浏览量无重复 | ✅ 14 例通过（含 4 处反向验证）；手测 3 项通过 |
| 第二刀 | 集成测试（真 Redis）：`UserSetCacheIntegrationTests` 18 例覆盖 §5.5 全部状态转换——未加载不写标记 / FULL 但集合丢失必须回源 / 取消最后一项转 EMPTY / EMPTY 新增转 FULL / 旧值 `1` 的两种分支 / Set TTL > 标记 TTL；回归：`ArticlesControllerTests` 等 28 例。手测：清空 Redis 后点赞 → 刷新确认历史点赞仍在 | ✅ 18 + 28 通过；手测待做 |
| 第三刀 | 集成测试（真 Redis）：`ArticleHotRankTests` 3 例（TTL 存在 / 无临时 key 残留 / 并发重建不抛异常）+ `RedisAclRuleTests`（ACL 规则不误伤应用命令、危险命令仍被拒）；配置生效性：`CONFIG GET maxmemory-policy`、`INFO memory` 核对；文档评审：设计稿与代码逐条核对，改掉 4 处过时表述 | ✅ 4 例通过（含 1 次反向验证）；配置项待部署时验证 |
| 第四刀 | 单元（Mockito）：`LoginAttemptLimiterTests` 15 例 + `TokenBlacklistTests` 7 例——归一化后变体共用一键 / 账号维度跨 IP 汇总（拦分布式爆破）/ IP 维度跨账号汇总（拦撞库）/ IP 宽阈值独立生效 / 空 clientIp 跳过 IP 维度 / 成功登录只清账号键 / fail-open / 脚本参数与 TTL / 老 token 与已过期 token 直接跳过。集成（真 Redis + MockMvc）：`AuthSecurityTests` 8 例——失败 5 次后 429 + `Retry-After` / 大小写与空格变体共享计数 / 预置 IP 计数后换个账号仍被拒（撞库形态）/ 不存在的账号与密码错误**响应体逐字节相同** / 成功登录清零 / 登出后 token 立即 401 / 被拉黑的 token 走公开接口仍正常 / 登出幂等。回归：`ArticlesControllerTests` 等 | ✅ 单元 22 + 集成 8 全绿；回归 52 例全绿（含 3 处反向验证）；手测待做 |
| 第五刀 | 集成（真 Redis + 真 MySQL）：`AfterCommitCacheEvictTests` 6 例——事务内调带 `@Transactional` 的 `updateArticleTitle` 时缓存**提交前仍在** / 提交后已删 / 回滚后缓存原封不动 / 无事务调用方仍立即执行 / 事务内只登记不执行 / 提交后动作抛异常不污染提交 / 用户集合维护同样延后。回归：受影响面 9 个测试类 64 例 | ✅ 6 + 64 全绿（含 2 处反向验证） |
| 通用 | 文章详情 / 列表 / 分类 / 标签 / 评论 / 学习计划的读接口全过一遍 | — |

> **订正 + 补测（2026-09-15）**：第一刀行原写"单测：TTL 抖动范围 / 互斥只回源一次 / 轮询读空值不误删 /
> 原子取删后增量不重复 ✅ 单测 10 例"——**后三项与实际不符**。实际那 10 例是
> `CacheTtlSupportTests` 5 + `RedisKeyScannerTests` 5；`src/test` 里 `cache:lock`、`DETAIL_LOCK`、
> `takeView`、`syncViewCountToDb`、`article:view`、`pollArticleDetailCache` **全部零匹配**。
> 也就是说：**互斥回源（第一刀的核心交付）当时没有自动化防线**，而它恰恰是"坑 3"所在——
> 多条读路径对空值的处理必须一致，错了会误删别人刚写好的空值缓存。
> 最接近的 `ArticlesControllerTests.visibilityNullCacheIsStableForOtherLoggedInUser` 走的是
> 非互斥路径，证明不了"轮询读到空值不误删"。
>
> **欠账已补**（同日）：`ArticleDetailMutexTests` 4 例。核心难点是**怎么数 DB 查询次数**——
> 最终用自定义 MyBatis `Interceptor` 拦 `Executor.query` 数 SQL 语句，而不是 spy `ArticlesMapper`：
> MyBatis 的 mapper 是 JDK 动态代理，Mockito spy 之后 `MybatisMapperProxy` 的内部状态取不到
> （实测报 `Unable to retrieve the mapperInterface and sqlSession properties`）。
> 另外拦截器必须带 `@Intercepts`（MyBatis 从注解读签名，否则 `Plugin.wrap` 直接抛异常）；
> 定时任务 `ArticleViewCountSyncTask` 用 `@MockBean` 关掉，否则它会中途把浏览量增量偷走。
>
> **4 处反向验证**，都精确停在预期断言上：撤销互斥 → 并发用例红（8 次查询而非 1 次）；
> 轮询绕过统一读入口 → 空值用例红；轮询超时不 fail-open → 超时用例红；
> `VIEW_TAKE_SCRIPT` 去掉 `DEL` → 浏览量用例红。
>
> **补测过程中被反向验证打回一条弱断言**：空值用例原本只断言"key 还在"，抓不住病因——
> 误删之后轮询必然超时，超时又 fail-open 回源，回源会把详情缓存写回去，key 又"存在"了。
> 改成断言 key 的**内容**仍是空值哨兵才锁得住；断言顺序也一并调整（先验 key 内容、再验返回值），
> 否则"误删"先表现为返回值错，红在返回值上掩盖病因。

**为什么第二刀用集成测试而不是 mock**：核心逻辑全在 Lua 脚本里（何时才算全量、取消最后一项
怎么转换状态），mock 掉 `StringRedisTemplate` 就测不到这些判断。项目已有 `@SpringBootTest` +
真 Redis 的先例（`ArticlesListCacheTests`），沿用即可。

**热榜重建的自动化测试是第三刀补的**：原判断是"只有两条分支（空榜 `DEL` / 非空 `RENAME`）、
需要特定 DB 状态，手测即可"——这个判断漏了第三类风险：**TTL 补在 `RENAME` 前后的顺序**和
**并发重建**都不依赖 DB 状态，但错了都是静默的（前者榜单照常工作只是永远不过期，
后者偶发定时任务报错）。

`ArticleHotRankTests` 用 3 例覆盖，其中并发用例**写出来的第一次跑就红了**——
它抓到的是真实存在的 `ERR no such key`（上下文启动触发定时任务首跑，与测试线程成为两个执行者），
不是为测试造的假问题。这条经验值得记：判断"要不要写测试"时，别只看分支数，
要看**错了以后症状是否静默**。

**第四刀为什么要加一层集成测试**：单元测试证明的是"组件自己没写错"，证明不了"它被接上了"。
漏注册拦截器、Service 忘了调 limiter、黑名单只改了 `JwtInterceptor` 漏了 `OptionalJwtInterceptor`
——这类**接线错误**恰恰是这一刀最容易出的问题，而它们只有走完整链路才看得见。

做过的两次反向验证（破坏实现 → 确认测试变红 → 改回）：

| 破坏点 | 结果 |
|---|---|
| `normalize()` 去掉 `trim().toLowerCase()` | `caseAndSpaceVariantsShareOneCounter` 单元 + 集成**两处同时红** |
| `JwtInterceptor` 跳过 `isRevoked` 检查 | `logoutRevokesTokenImmediately` 红：`expected 401 but was 200` |

## 七、边界：明确不做 + 触发条件

| 不做 | 理由 | 触发条件 |
|---|---|---|
| 布隆过滤器 | 空值缓存已覆盖穿透；布隆不支持删除、误判率需调参 | 恶意 ID 扫描量级上升 |
| 多级缓存（Caffeine + Redis） | 一致性维护成本高于收益 | 单 key QPS 高到 Redis 往返成瓶颈 |
| 缓存预热 | 无大规模冷启动场景 | 服务重启后"第一批请求集体慢" |
| 集群 / 主从 / 哨兵 | 单机容量与 QPS 远未触顶 | 单机内存或 QPS 触顶 |
| 热 key / 大 key 监控平台 | 无自建监控体系 | 出现明显单 key 热点或慢查询 |
| Canal / binlog 订阅 | 重量级组件，一致性要求未到秒级 | 一致性要求提到秒级且缓存量级上升 |
| Redis 做消息队列 | 项目用 `@Async` + 失败标记 | 需要削峰 / 跨服务解耦 |
| Redlock | 单机 Redis 用不上；且 Redlock 本身有争议 | 真上多主集群时重新评估 |

## 八、面试口径

**问"缓存怎么防穿透 / 击穿 / 雪崩"**：

> 穿透用空值缓存（3 分钟短 TTL；不存在与不可见分开编码，不可见缓存带作者与状态，避免挡住隐藏文章作者预览）；击穿对文章详情做互斥回源（SETNX + token + Lua 释放 + 5 秒短 TTL，轮询超时 fail-open 退化查库）；雪崩给 7 个数据缓存写点的 TTL 加 ±20% 抖动——`loaded` 这类每用户一个的标记位不加，它失效只让单个用户重查一次，不构成 DB 压力峰值。
>
> 被追问"**你怎么证明互斥真的生效了**"：8 个线程同时打同一篇文章，数**真实的 DB 查询次数必须是 1**——
> 不是"没报错"就算过。数查询用 MyBatis 拦截器拦 `Executor.query`，不能用 Mockito spy mapper
> （JDK 动态代理，`MybatisMapperProxy` 的状态取不到）。
> 轮询路径最容易出的 bug 是"读到空值缓存当脏数据删掉"，所以专门验证了它不删——
> 而且这条断言必须查**缓存内容**：只查"key 还在"抓不住，误删之后回源会把 key 又写回来。
> 这四类断言都用"破坏实现 → 看它是否精确变红"验过一遍。

**问"为什么不用 KEYS"**：

> 代码里已经清零了：原来 5 处在用（浏览量同步、文章列表失效、评论列表失效、RAG 失败标记清理、迁移后清理），全部改成 `SCAN` 游标迭代 + `UNLINK` 异步回收。`KEYS` 的问题是 O(N) 且阻塞服务端单线程——Redis 里同时住着限流、锁、缓存，一次 `KEYS` 会让它们全部一起卡；`SCAN` 每次只扫一批（`COUNT 200`），不阻塞。删大 key 用 `UNLINK` 而不是 `DEL`，后者同步回收、同样阻塞。
>
> 服务端那层我用 ACL 而不是 `rename-command`：`rename-command` 全局生效且不可逆，管理员和迁移脚本也需要这个命令，改名之后运维手册上的命令全失效；ACL 按账号授权，应用账号 `+@all -@dangerous`，管理员不受影响。

**问"缓存和数据库怎么保持一致"**：

> Cache Aside，写库后**删**缓存（不是更新——并发写时会互相覆盖）。删除失败记日志不重试，靠 TTL 兜底最终一致。浏览量链路单独处理：Redis 计数 + 定时回写 DB，用 Lua 把 `GET` + `DEL` 打包成一次执行、先取走再更新 DB，把故障方向从"可能重复计数"改成"可能丢一次增量"——浏览量允许近似，丢比重复好。
>
> 被追问"**写完就删，那删早了怎么办**"：删缓存必须**等事务提交之后**。事务内删的话，
> 从删完到提交之间的任何一次缓存 miss 都会读到"还没提交的旧值"并回填，缓存一直脏到 TTL——
> 这个窗口不是概率问题，它由事务本身的耗时决定。做法是注册 `TransactionSynchronization`，
> 有事务就等到 `afterCommit`，没有就就地执行。
> **这里有个容易踩的坑**：如果用 `@TransactionalEventListener(AFTER_COMMIT)`，
> 没有事务时事件**默认不发布**——哪天有人给某个方法去掉 `@Transactional`，缓存就永远不再失效，
> 而且悄无声息。所以用的是显式 fallback 的写法。
>
> 再追问"**那删失败了呢**"：记 `[CACHE-EVICT-FAIL]` 日志 + 靠 TTL，不重试——Redis 都挂了，
> 重试大概率还是失败，反而堆线程。也不上 MQ / binlog 订阅：那是把"一致性"提到秒级才需要的成本。
>
> 最后一定要说边界：**这么改仍然不是强一致**。剩下一个需要恰好并发的窗口——
> 读请求在提交前查了库（拿到旧值），写缓存的时刻却晚于我们的删除，旧值就留下了。
> 要彻底解决得上延迟双删或订阅 binlog，代价和收益在那个量级上不匹配。

**问"缓存里存的全量标记怎么保证可信"**（本题由本轮 Codex 评审提出）：

> 我们原来踩过坑：点赞路径无条件把 `loaded` 标成 1，Redis 重启后用户点一个赞，缓存里只有这一个元素却自称全量，展示出来就是"历史点赞全丢了"。修法是只有**从 DB 全量加载**的分支才能写这个标记，普通点赞只在标记已存在时同步维护集合。

**问"Redis 内存满了怎么办"**：

> `volatile-lru`，不是 `allkeys-lru`——Redis 里除了可丢的缓存，还住着分布式锁、幂等键、限流计数这类**丢了会出错**的状态，`allkeys-*` 在内存压力下会一视同仁地把它们淘汰掉。缓存少一条只是慢一点，锁被淘汰是并发正确性问题。
>
> `volatile-*` 的代价是"所有允许丢失的数据都必须有 TTL"，所以逐 key 核对过一遍：缓存类 3~30 分钟、浏览量 30 分钟、用户集合 40 分钟、热度榜 2 小时（这条是后补的，当时唯一漏网）、锁 / 限流 / 幂等自带 TTL。这个前提不是形式主义——如果内存满了而剩余 key 全无 TTL，淘汰策略找不到可淘汰对象，Redis 会直接对写入报 `OOM command not allowed`，那是"写不进去"，不是"淘汰掉一点缓存"。浏览量本身允许近似，被淘汰一部分可以接受。

**问"Redis 要不要配连接池"**：

> 这个项目没配，是有意不配。要不要池化取决于有没有"独占连接"的需求：阻塞命令（`BLPOP`）和事务（`MULTI` / `WATCH`）会独占连接，有这些才需要池。我们全是非阻塞命令，原子性靠 Lua（Lua 不独占连接），所以 Lettuce 一条 Netty 连接多路复用就够。
>
> 这也是它和 Jedis 的本质区别：Jedis 同步阻塞，一条连接同时只能跑一条命令，必须池化。顺带一个反直觉的点——**MySQL 必须要连接池、Redis 不一定**，根因是 I/O 模型不同（阻塞 vs 异步），不是"Redis 更轻"。

**问"ACL 收这么紧，客户端会不会连不上"**：

> 确实踩到一个点：`+@all -@dangerous` 在 Redis 6.x 上会把整个 `CLIENT` 命令禁掉，而 Lettuce 建连时会发 `CLIENT SETINFO`（7.2+ 才有）。我一开始也以为会连不上，查了 Lettuce 源码才确认不影响——`RedisHandshake.applyConnectionMetadataSafely()` 用 `handle((v,t) -> { log.debug(...); return null; })` 把元数据命令的失败整个吞掉，只在 DEBUG 留一行日志。而且 7.2+ 已经把 `CLIENT` 移出 `@dangerous` 了，那之后连这条 `NOPERM` 都不会有。
>
> 附带一个测试判断：本地 Redis 是 6.2，压根不会发这条命令，写这个用例是空跑，所以我没写——留个"看起来验证过"的测试比没有更糟。能测的那部分（ACL 规则会不会误伤应用实际用的命令）我写了测试锁住，并做了反向验证。

**问"JWT 是无状态的，用户登出 / 改密码后旧 token 怎么办"**：

> 加黑名单。JWT 签发之后服务端没有任何办法提前作废它，黑名单是唯一手段——代价是每个请求多一次 Redis `GET`（内存操作、亚毫秒级）。
>
> 键用 `jti`（RFC 7519 里就是为这个字段设计的），不是按 `userId` 拉黑：按 userId 等于"登出踢掉全部设备"，多端登录就说不通了。**TTL 取 token 的剩余有效期**而不是固定时长——条目只需要活到 token 自己过期，多存一秒都是浪费。
>
> 两个拦截器都要查：只改强制登录那个的话，登出后的 token 在公开接口上仍被当成登录态，照样能点赞评论，等于没登出。Redis 挂了按"未拉黑"放行——黑名单是加强项，JWT 自身的过期才是兜底防线。
>
> 顺带踩到一个真问题：**前端原来的登出只清了 localStorage，从没调用过后端接口**（`authApi.logout` 是定义好却没人调的死代码）。后端把黑名单做完了但没人触发，等于白做——这也是"链路必须端到端验证"的一个实例。

**问"登录失败限制怎么做的，会不会被绕开、或者被拿来锁死别人"**：

> 维度按**攻击形态**选，不是按"哪个副作用小"选。账号维度拦得住定向爆破和**分布式爆破**
> （一个账号、代理池多 IP——每个 IP 只试几次，只有账号维度看得见）；IP 维度拦得住**撞库**
> （泄露库、多账号各试一两次、同源 IP——每个账号都攒不到阈值，只有 IP 维度看得出这个来源在批量试）。
> **「用户名 + IP」组合键只拦得住第一种**，另外两种一个都拦不住。
>
> 所以取双维度**非等权**：账号 5 次 / 15 分钟（严），IP 30 次 / 15 分钟（宽——只为拦撞库，
> 不让 NAT 出口被邻居拖累）。组合键看着"副作用最小"，但那是拿覆盖面换的。
>
> 残留代价是账号维度仍能被用来锁死真实用户（拿别人用户名连试 5 次），代价限制在 15 分钟
> 不能登录、且本人登录成功即清零。彻底消除要换渐进式延迟 / 验证码，这里没做。
>
> 成功登录**只清账号键、不清 IP 键**：清了等于留后门——攻击者只要有一个自己的账号，
> 就能靠反复登录成功把 IP 计数刷掉。
>
> 用户名必须归一化：我们库的 collation 是 `utf8mb4_unicode_ci`，`Admin` 和 `admin` 在库里**本来就是同一个用户**，计数键不归一化的话大小写变体就能把 5 次阈值拆成 15 次。
>
> 防账号枚举做了三条：不存在的账号**同样计数**、提示语统一、**账号不存在时也补跑一次 bcrypt**。第三条最容易漏，因为泄露发生在时间维度上——查不到用户就直接返回，比密码错误快约 100 ms，响应时间本身就是探针。
>
> 这套方案我走过一次弯路：最初实现成了组合键——当时只盯着"消掉锁死真实用户"一个点，
> 漏了覆盖矩阵。后来按攻击形态列了张表才看出来，**副作用小不等于拦得住**。

## 九、待评审问题（全部已关闭）

原问题与结论留档——这些是设计期的真实分歧点，面试常从这里追问。

| # | 原问题 | 结论 |
|---|---|---|
| 1 | 第一刀范围是否够窄？ | ✅ 够——已实施并验证（§六）。范围按业务面切而非按"重要程度"切，事后看是对的 |
| 2 | 隐患 2（`:loaded` 语义）放第二刀还是提前到第一刀？ | ✅ 第二刀——它只影响展示、不丢数据，和热榜同属用户态数据，一刀改完测试面完整 |
| 3 | `GETDEL` 版本前提：部署环境 Redis 是否 ≥ 6.2？ | ✅ **问题消解**——实施时改用 Lua 的 `GET` + `DEL`，没有版本下限，"部署环境必须 ≥ 6.2"这个前提不再存在 |
| 4 | `commons-pool2` 取舍：引依赖配连接池还是不引？ | ✅ **不引**——三条需要池的理由（阻塞命令 / 事务 / 限制连接数）逐条核实全不成立，见 §5.7 |
| 5 | 第二刀：`:loaded` 语义与 Set TTL 同时改还是分开？ | ✅ 同时改——同一批写点，分两次动要改两遍测试，收益为零 |
| 6 | 第四刀：脚本复用选 A / B / C？ | ✅ **C 的变体**——独立类（不塞进 `AiRateLimiter`、也不动稳定链路），但**不共享脚本常量**：契约不同、演化方向不同，4 行 Lua 重复的成本低于错误抽象（§5.11-4） |
| 7 | 第四刀：计数键用输入原文还是 userId？ | ✅ **归一化后的原文**——用 userId 则"用户不存在"无法计数，会变成账号枚举探针；原文必须 `trim` + 小写，否则被大小写变体绕过（§5.11-2） |
| 8 | 第四刀：锁定期间的响应形态？ | ✅ 429 + 明确提示，且对**不存在的账号走完全相同的分支**——不可区分靠的是"两条路径同一段代码"，不是靠含糊其辞（§5.11-3） |
| 9 | 第四刀：token 黑名单用 `jti` 还是 `userId`？ | ✅ **`jti`**——按 userId 是"登出踢全端"，且每次登出都要写一个 7 天 TTL 的键。`jti` + 剩余有效期是标准答案 |
| 10 | 第四刀：账号维度与 IP 维度是合成一个键还是分开？ | ✅ **分开，且非等权**（v7 补记）。合成「用户名 + IP」只拦得住单 IP 定向爆破——分布式爆破（一账号多 IP）与撞库（多账号同源 IP）一个都拦不住。判据是**攻击形态覆盖矩阵**，不是"哪个副作用小" |

## 十、修订记录

### v2（2026-09-14，按 Codex 评审）

| Codex 意见 | 处置 |
|---|---|
| 第一刀过重 | ✅ 采纳——改为四刀路线，按业务面切 |
| `:loaded` 语义才是真问题 | ✅ 采纳——升级为隐患 2（🔴），补触发链、重复写入、修法 |
| 持久化口径要降级 | ✅ 采纳——§5.8 改为"讲得清而非必做"，并修正 v1 的自相矛盾 |
| `volatile-lru` + 浏览量 TTL 表述矛盾 | ✅ 采纳——统一为"可接受丢失的近似统计" |
| 连接池不是 KEYS 的解药 + 缺 `commons-pool2` | ✅ 采纳——已核实 `pom.xml` 零匹配；§5.7 改为"先决策依赖" |
| 不要直接复用 `WorkflowActionLock` 类 | ✅ 采纳——核实其 TTL 为 120 s；§5.1 改为"复用模式，不复用类" |
| 先改设计稿为分阶段路线 | ✅ 本版即为此修订 |
| `rename-command KEYS` 与现有调用点冲突 | ✅ 改为应用账号 ACL；所有运行期/迁移调用点迁移完成前不全局禁用 |
| `GETDEL` 非原子 fallback 不安全 | ✅ 删除普通 `GET` + `DELETE` fallback，改为 Redis 版本前提或 Lua 取删 |
| 浏览量 key 无 TTL 未纳入第一刀 | ✅ 补为首次 `INCR` 原子设置兜底 TTL |
| Set 与 `loaded` 可能部分淘汰 | ✅ 第二刀补一致性判定，不能把缺失 Set 当空集合 |
| Cache-Aside 失效可能早于事务提交 | ✅ 新增 §5.9 与第五刀登记 |

### v3（2026-09-14，按 Codex 复核）

| Codex 意见 | 处置 |
|---|---|
| 路线表已新增第五刀但顶部仍写四刀 | ✅ 顶部路线表补齐第五刀 |
| 浏览量 `GETDEL` 只适合近似统计 | ✅ 补可靠性边界，不将其类比为可靠消息投递 |
| 浏览量 TTL 脚本只判断 `INCR == 1` | ✅ 改为 key 当前无 TTL 时补 TTL，兼容历史无 TTL key |
| Set TTL 大于标记 TTL仍可能因续期失效 | ✅ 要求集合变更时同步刷新 Set TTL |
| `loaded` 不能表达全量空集合 | ✅ 补 `FULL / EMPTY` 状态转换与读时修复要求 |
| 缺少命中率、key 版本和原子扣减边界 | ✅ 新增 §5.10，按现有业务给出后续治理范围 |

### v4（2026-09-15，第二刀实施）

| 事项 | 处置 |
|---|---|
| 第二刀实施完成 | ✅ 9 处 `:loaded` 写点全部收口到 `component/UserSetCache`（3 处全量回填 + 6 处无条件写）；4 处重复写入清理；热榜临时 key + `RENAME` |
| 原设计："任务互斥（加共享锁）" | ⛔ **推翻**——核实为默认单线程调度器、本就不并发。改为显式 `spring.task.scheduling.pool.size: 1` + 类注释（把隐式默认变显式约定） |
| 原设计："空榜需要用 Lua 原子删除旧榜" | ✅ 简化为 `DEL`——单命令原子，`RENAME` 同样，都不需要 Lua 包装 |
| 原设计："若要原子应使用 Lua" | ✅ 采纳为 B 方案（Lua 打包"检查 + 集合变更 + 刷两个 TTL"）。理由不是竞态严重（最坏白写一次、下次读自愈），而是调用从 4 次降到 1 次、顺序不再由调用方保证 |
| **实施中发现存量 bug** | 🔴 `markLoaded` 直接 `SADD` 是**追加不是覆盖**：标记过期但集合还在时回源重建，会把残留成员留下 → 已取消的点赞"复活"。修法：写入前先 `DELETE`。旧代码同样存在此问题 |
| 旧值 `1` 的存量兼容 | ✅ 无需运维动作：读侧按 FULL 用、集合缺失时回源；写侧拒绝在集合缺失时维护。30 分钟后自然过期 |
| 读路径三态收口 | ✅ `readIfLoaded` 返回 null / 空 Set / 非空 Set，与第一刀的 `readArticleDetailCache` 同一思路 |

### v5（2026-09-15，第三刀实施）

| 事项 | 处置 |
|---|---|
| 超时配置 | ✅ `timeout: 3s` / `connect-timeout: 2s`，并写明「超时不是给 Redis 减负，是保护应用线程」 |
| `commons-pool2` 取舍 | ✅ **不引**——三条需要池的理由逐条核实全不成立（§5.7）。配置里留反向注释，避免后人加 `lettuce.pool.*`（无依赖时静默失效） |
| `maxmemory-policy` | ✅ 定为 `volatile-lru`（原设计留"待按部署拓扑定"）。理由：Redis 里住着锁 / 幂等键这类**丢了会出错**的状态，`allkeys-*` 会连它们一起淘汰 |
| 服务端产物 | ✅ 新增 `deploy/redis.conf.example`（内存 / 淘汰 / 持久化 / ACL / 慢查询，带取舍注释）与 `redis-ops-runbook.md`（慢查询 / 大 key / 热 key / ACL / 连接排查） |
| ACL 规则 | ✅ `on #<sha256> ~* +@all -@dangerous`；应用侧新增 `spring.data.redis.username`——只配密码不配账号的话，应用拿到的仍是 default 全权限，ACL 等于没上 |
| ACL 与 Lettuce 握手 | ✅ 查源码确认**不需要 `+client`**：`RedisHandshake.applyConnectionMetadataSafely()` 用 `handle((v,t)->{log;return null;})` 吞掉元数据命令的失败。本地 Redis 是 6.2、不发该命令，**刻意不写成测试**（空跑比没有更糟） |
| 热度榜 TTL | ✅ 补 `ARTICLE_HOT_TTL_HOURS = 2`。**TTL 必须在 `RENAME` 之后补**——`RENAME` 会把目标 key 的 TTL 覆盖成源 key（临时 key）的，写在前面等于白写 |
| 热度榜并发 | 🔴 **实施中实测复现**：`RENAME` 源 key 不存在时抛 `ERR no such key`，两个执行者共享同一临时 key 时后到者必炸。修法：`EXISTS` + `RENAME` + `EXPIRE` 打包成 Lua |
| 原判断"空榜不需要 Lua" | ⚠️ **部分推翻**——单看空榜确实不需要，加上 TTL 与并发之后需要了（§5.6） |
| `GETDEL` → Lua | ✅ 文档与代码对齐：实际实现是 Lua 的 `GET` + `DEL`（无版本下限），原文多处 `GETDEL` 已全改，§九 #3 关闭 |
| §5.8「还有 4 处在用 `KEYS`」 | ⛔ **过时**——第三刀核实运行期 `KEYS` 调用点**已全部清零**。仍选 ACL 而非全局 `rename-command`，理由改为"不可逆 + 运维也需要该命令" |
| §八 面试口径 | ✅ 补两条（连接池取舍 / ACL 与客户端握手），"内存满了怎么办"补热度榜 TTL 与 `allkeys-*` 的反例 |
| 测试 | ✅ `ArticleHotRankTests` 3 例（并发用例**写出来的第一次就跑红**，抓到真实的 `ERR no such key`）；`RedisAclRuleTests`（反向验证过：砍掉 `@scripting` 后测试立刻红） |

### v8（2026-09-15，第五刀实施）

| 事项 | 处置 |
|---|---|
| 失效时机 | ✅ 新增 `component/AfterCommitExecutor`：有事务则注册到 `afterCommit`，无事务则就地执行。改造做在**封装方法内部**，调用点一个没动 |
| 原设计"`TransactionSynchronization` 或 `@TransactionalEventListener(AFTER_COMMIT)`" | ⚠️ **二选一取了前者**：事务事件在**无事务时默认不执行**，而本项目大量缓存失效发生在非事务方法里——哪天有人去掉 `@Transactional`，缓存就静默地永不失效，比"删早了"更难发现 |
| 收口范围 | ✅ 实施前做了全量清点：受影响的**不是 4 处，是 10 个方法**（文章 2 / 点赞 2 / 评论点赞 1 / 学习计划 5），跨 3 类缓存、5 个文件。这是选择"统一收口"而非"只改事务内"的依据 |
| 顺带修掉 | ✅ 事务回滚不再白删缓存；`ArticleFavoritesServiceImpl` 原本**裸调** `delete` 没有 try-catch（Redis 一抖收藏直接失败），一并收口 |
| 删除失败 | ✅ 静默 `catch` → `[CACHE-EVICT-FAIL]` 可检索日志，靠 TTL 兜底，不做重试 |
| RAG 异步索引 | 📌 **登记不修**：`@Async` 在事务方法内发起，异步线程可能读到提交前的旧文章（索引留旧标题）。同源问题、后果更轻（不影响用户直接可见的数据），触发条件写进 §四 |
| 测试 | ✅ 6 例（真 Redis + `TransactionTemplate` 手动控制事务边界，能在事务内断言一次、提交后再断言一次）；回归 9 个测试类 64 例全绿；**2 处反向验证**——取消延迟执行 → 5 红 1 绿（恰好只有"无事务立即执行"那条保持绿），去掉提交后异常兜底 → 精确只红 1 条 |

### v7（2026-09-15，第四刀方案偏离纠正 + 前三刀复核）

| 事项 | 处置 |
|---|---|
| 计数维度 | 🔴 **v6 的「归一化用户名 + IP」组合维度是被否掉的第一版提法，实施时退回去了**。本文 §3.2 与 Codex 复核收敛的结论是「用户名为主 + IP 宽阈值辅助」，实施却写成了组合键——组合键只拦得住单 IP 定向爆破，分布式爆破与撞库一个都拦不住。已改回**双维度非等权**（§5.11-1 整节重写 + 覆盖矩阵） |
| **根因（流程，不是技术）** | 我实施时**只从"消掉锁死真实用户"一个点出发推演**，自己在文档里标了个 ⚠️ 就把单方面改动写成"对 §3.2 的修正"，**方案变更没有回到讨论环节**。覆盖矩阵的漏洞因此没有第二双眼睛看到。教训：**改结论要么回到讨论，要么把推演过程摊开**；自己标 ⚠️ 不等于被审过 |
| 前三刀复核 | ✅ **8 项逐一核对到文件:行号，没有第二处"实施到旧版本"**：读路径单一入口 / 独立短 TTL 锁（5s）/ 7 处 jitter / 运行期 `KEYS` 零匹配 / `UserSetCache` 的 FULL·EMPTY + 40·30 TTL / 热榜 Lua + 2h / 浏览量 Lua 取删 / `spring.task.scheduling.pool.size: 1` |
| §六 第一刀验证表 | 🔴 **虚报，已订正**：原表声称的单测包含"互斥只回源一次 / 轮询读空值不误删 / 原子取删后增量不重复"，但 `src/test` 里 `cache:lock`、`DETAIL_LOCK`、`takeView`、`syncViewCountToDb`、`article:view`、`pollArticleDetailCache` **全部零匹配**。实际 10 例是 `CacheTtlSupportTests` 5 + `RedisKeyScannerTests` 5。**互斥回源当时没有自动化测试**，同日补齐：`ArticleDetailMutexTests` 4 例 + 4 处反向验证（§六 订正段） |
| `cache-breakdown-avalanche-design.md` | ✅ 两处过期表述订正：锁的实现改为"复用 `WorkflowActionLock` 的模式、**不复用它的类**"（其 TTL 120s 与失败语义是 Workflow 专用）；抖动写点数 6 → 7；实施状态由"零调用点"改为"已接线（7 处 jitter、9 处 `:loaded` 有意不抖）" |
| 测试 | 单元 15（`LoginAttemptLimiterTests`，含分布式爆破 / 撞库两条形态用例）+ 集成 8（新增 `ipDimensionBlocksCredentialStuffingAcrossAccounts`） |
| 第一刀测试欠账 | ✅ **同日补齐**：`ArticleDetailMutexTests` 4 例 + 4 处反向验证。反向验证还打回一条弱断言（"key 还在"抓不住误删，必须断言内容）——详见 §六 订正段 |
| 测试脆弱性 | 🔴 **实施中发现**：原用例断言 `verify(valueOperations, times(N))` 的**总次数**，而 N 里含 IP 维度的查询——破坏 IP 检查时，与测试意图无关的用例会连带变红。改为 `atLeastOnce()` + 按键前缀过滤，**每条测试只对自己的意图负责** |

### v6（2026-09-15，第四刀实施）

| 事项 | 处置 |
|---|---|
| 计数维度 | ✅ 「归一化用户名 + IP」组合，5 次 / 15 分钟。**修正 §3.2 的"用户名维度为主"**——组合维度消掉了"锁死真实用户"这个副作用，阈值因此不必再为它放宽 |
| 用户名归一化 | 🔴 **实施中才挖出的前提**：实测 `@@collation_database = utf8mb4_unicode_ci`，`Admin` 与 `admin` 在库里**本来就是同一个用户**。计数键不做 `trim` + 小写，大小写变体就把 5 次阈值拆成 15 次 |
| 账号存在性 | ✅ 三条一起做：不存在的账号同样计数 / 提示语统一 / **账号不存在时补跑一次 bcrypt**。原设计只覆盖前两条，第三条是**时间维度**上的泄露（快 ~100 ms 本身就是探针） |
| 脚本复用 | ⚠️ **部分推翻 §3.2 的倾向 C**——独立类采纳，共享脚本常量放弃：两句契约不同（每次消费配额 vs 仅失败计数），共享会制造隐性耦合 |
| token 黑名单粒度 | ✅ `jti`（RFC 7519 就是为此字段设计的）；**TTL 取 token 剩余有效期**而非固定时长；已过期 token 不写黑名单 |
| `JwtUtil.parseToken` | ✅ 改为 `parsePayload`，一次解析返回 `(userId, jti, expireAt)`；改造完两个拦截器后 `parseToken` 已无调用方，删除 |
| 前端登出 | 🔴 **发现死代码**：`authApi.logout()` 定义了却从未被调用，登出只清 localStorage → 后端就算做完黑名单也没人触发。改为先 `await authApi.logout()` 再 `clearAuth()`，**失败必须忽略**（后端不可用不能拦着用户登出），请求单独设 3 s 超时（默认 60 s 太久） |
| `RateLimitExceededException` | ✅ 只加了带 message 的构造函数，HTTP 429 + `Retry-After` 那套基础设施整体复用，没有新建第二套 |
| fail 方向 | ✅ 两处都 fail-open，且**不做配置开关**：这里没有"两种都合理"的情形，留一个开关只是给人配错的机会 |
| 测试 | ✅ 单元 18 例 + 集成 7 例；做了 **2 处反向验证**（去掉归一化 / 跳过黑名单检查），都精确停在预期断言上（`expected 401 but was 200` / `expected 429 but was 200`） |
| 存量 token | ⚠️ 加 `jti` 之前签发的 token 无法被拉黑，登出后仍有效到 `exp`。接受这个过渡（重新登录即可），不写兼容分支 |
