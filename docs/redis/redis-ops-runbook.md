# Redis 运维排查手册

配套：`deploy/redis.conf.example`（生产配置）、`redis-capability-design.md`（设计取舍）。

**三个触发场景**：

1. 接口变慢，怀疑 Redis 卡住 → 先看慢查询（§一），再看大 key（§二）
2. 内存告警或写入报 `OOM command not allowed` → §四
3. 应用日志出现 `NOPERM` / 连接异常 → §五、§六

---

## 一、慢查询

Redis 是单线程处理命令，一条慢命令会把它后面排队的**所有**命令一起拖住——
包括限流、分布式锁、幂等校验。所以慢查询不是"慢一点"，而是全站一起慢。

```bash
redis-cli -a <admin-pass> SLOWLOG LEN           # 积了多少条
redis-cli -a <admin-pass> SLOWLOG GET 10        # 看最近 10 条（字段：耗时μs、命令、参数、客户端）
redis-cli -a <admin-pass> SLOWLOG RESET         # 排查完清掉，避免旧数据干扰下次判断
```

阈值由 `slowlog-log-slower-than 10000`（10ms）决定，见配置文件。

**按本项目预期，应该出现的命令**（出现即正常）：

| 命令 | 来源 | 正常耗时 |
|---|---|---|
| `SCAN` | `RedisKeyScanner`：浏览量同步、列表失效、RAG 失败标记清理 | 毫秒级（`COUNT 200`） |
| `ZREVRANGE` | 热度榜取前 N | 毫秒级（10 个成员） |
| `EVAL` | 浏览量原子取删、用户集合变更、热度榜替换 | 毫秒级 |

**不应该出现的命令**：任何 `KEYS`。运行期代码已全部改用 `SCAN`，出现 `KEYS` 说明
要么有脚本/运维手工执行，要么有旧版本实例还在跑。

---

## 二、大 key

大 key 的三重危害：**删除时阻塞**（`DEL` 是同步的，几百万成员的集合会卡住主线程）、
**网络传输放大**（一次读几十 MB）、**集群下分片倾斜**（单分片内存被打满）。

```bash
redis-cli -a <admin-pass> --bigkeys                    # 全库扫描，给每类结构最大的 key
redis-cli -a <admin-pass> MEMORY USAGE <key>           # 单个 key 的实际内存占用
redis-cli -a <admin-pass> SCARD article:liked:user:123 # 集合类看元素个数
redis-cli -a <admin-pass> ZCARD article:hot            # 有序集合看成员数
```

**本项目的高风险 key**：

| key | 为什么会大 | 处理 |
|---|---|---|
| `article:liked:user:{userId}` | 一个重度用户点赞过的文章数，随时间只增不减 | 有 40 分钟 TTL，不活跃自然淘汰；单用户超过几万条时需要改成只存最近 N 条 |
| `article:favorited:user:{userId}` | 同上 | 同上 |
| `article:hot` | 成员数 = 已发布文章数 | 有 2 小时 TTL，且每 30 分钟整体重建（`RENAME` 换 key，不是原地改）；文章数上万时重建耗时才是瓶颈，不是内存 |
| `comment:liked:user:{userId}` | 同上 | 有 TTL |
| 限流 key `ai:rate:*` | 窗口内每用户/每 IP 一个，量大但都是小 String | 自带短 TTL，无需处理 |
| 登录失败计数 `login:fail:account:*` | 每个归一化用户名一个（trim + 小写），15 分钟 TTL，阈值 5 | 量 = 窗口内有失败登录的账号数，正常是个位数。**突然出现成千上万条要当爆破告警看**，不是清理问题 |
| 登录失败计数 `login:fail:ip:*` | 每个来源 IP 一个，15 分钟 TTL，阈值 30（宽得多） | 量 = 窗口内的可疑来源数。**这个键涨得快就是撞库**：多账号各试一两次、同源 IP，账号维度看不见，只有它看得出 |
| 登出黑名单 `token:blacklist:*` | 每个被作废的 token 一条，TTL = 该 token 剩余有效期（最长 7 天） | 值只有 `"1"`，单条极小。量 = 近 7 天登出次数，有 TTL 自然淘汰 |

**排查爆破**：两个维度分开数，看到的形态不一样——

```bash
SCAN login:fail:* COUNT 200   # 或直接按前缀分两次扫
```

- **`login:fail:account:*` 大量计数停在 5** → 有人在持续撞**同一批账号**（定向爆破 / 分布式爆破）。
  键名里的用户名就是被撞的账号，值到 5 说明已经触发拒绝。
- **`login:fail:ip:*` 值蹿得比账号维度快** → **撞库**：同一来源拿泄露库批量试不同账号，
  每个账号只失败一两次，账号维度看不到，全靠 IP 维度兜。

两个维度**只清账号、不清 IP**（登录成功时）——所以看到某个 IP 键还挂着高计数、
但对应账号已经正常登录成功，那是**预期行为**不是 bug：它表达的是"这个来源可疑"。

**删除大 key 的正确姿势**：不要用 `DEL`（同步阻塞），用 **`UNLINK`**（后台线程回收）。
项目代码里清理都是 `UNLINK`，手工运维时也照做。

---

## 三、热 key

单个 key 的 QPS 高到打满一个分片（单机下表现为把主线程占满）。

**`redis-cli --hotkeys` 在本项目用不了**——它依赖 LFU 淘汰策略，而本项目用的是
`volatile-lru`。这是工具的硬前提，不是配置问题，别在这上面浪费时间。替代方案：

```bash
# 短时采样，统计出现频次最高的 key。MONITOR 本身有性能开销，排查完立刻 Ctrl-C
redis-cli -a <admin-pass> MONITOR | head -n 20000 | awk '{print $4}' | sort | uniq -c | sort -rn | head -20
```

**按业务预期，热 key 候选**：

- `article:detail:{articleId}` —— 爆款文章详情。已做互斥回源，缓存命中时是单次 `GET`
- `article:hot` —— 首页热榜，每次访问都读
- `ai:rate:*` —— 限流计数，高频写

**真要到大 key / 热 key 需要平台化监控的程度**，触发条件见设计稿 §七——本项目当前的
数据量下，上面两条命令足够定位，不上自建监控。

---

## 四、内存与淘汰

```bash
redis-cli -a <admin-pass> INFO memory            # used_memory_human / maxmemory_human / mem_fragmentation_ratio
redis-cli -a <admin-pass> MEMORY DOCTOR          # 一句话诊断
redis-cli -a <admin-pass> CONFIG GET maxmemory-policy
redis-cli -a <admin-pass> INFO stats | grep evicted_keys   # 淘汰了多少 key
```

**读结果的三个要点**：

1. `used_memory` 长期贴近 `maxmemory` 且 `evicted_keys` 持续增长 → 要么扩容，
   要么缓存的数据本来就不该都留（考虑缩短 TTL）。
2. `evicted_keys` 为 0 但已经报 `OOM command not allowed` → **所有 key 都没有 TTL**，
   `volatile-lru` 找不到可淘汰对象。本项目已核实每个允许丢失的 key 都有 TTL
   （含热度榜的 2 小时），正常不会出现；真出现了说明有新增的 key 漏设 TTL。
3. `mem_fragmentation_ratio` 长期 > 1.5 → 碎片率偏高，可考虑重启实例或开
   `activedefrag`。

**排查"谁的 key 没有 TTL"**（谨慎使用，用 `SCAN` 不用 `KEYS`）：

```bash
redis-cli -a <admin-pass> --scan --pattern 'article:*' | while read k; do
  ttl=$(redis-cli -a <admin-pass> TTL "$k")
  [ "$ttl" = "-1" ] && echo "无 TTL: $k"
done
```

---

## 五、ACL / 权限问题

症状：应用日志出现 `NOPERM this user has no permissions to run the 'xxx' command`。

```bash
redis-cli -a <admin-pass> ACL LIST                       # 所有账号的规则
redis-cli -a <admin-pass> ACL WHOAMI                     # 当前连接是谁
redis-cli -a <admin-pass> ACL CAT dangerous              # @dangerous 里有哪些命令
redis-cli -a <admin-pass> ACL DRYRUN blog-app KEYS '*'   # 以 blog-app 的身份试跑，不实际执行
```

`ACL DRYRUN` 是最省事的一条——不用登出重连就能确认某条命令会不会被拒。

**应用账号的规则**（见 `deploy/redis.conf.example`）是 `+@all -@dangerous`。
如果出现 `NOPERM`，通常是三个原因：

1. 新代码用了某个落在 `@dangerous` 里的命令 → 用 `ACL CAT dangerous` 确认，
   再决定是加白名单还是换实现
2. `REDIS_USERNAME` 没配，应用仍以 default 用户连接（那不该报 NOPERM，见下条）
3. 有人手工改过 ACL 规则 → `ACL LIST` 核对

**已知的一条**：Redis 6.x 上 `CLIENT` 命令归属 `@dangerous`，会被拒（7.2+ 已移出）。
Lettuce 建连时会发 `CLIENT SETINFO`（7.2+ 才有），被拒**不影响连接**——
`RedisHandshake.applyConnectionMetadataSafely()` 把元数据命令的失败整个吞掉，
只在 DEBUG 级留一行日志。回归测试：`RedisAclRuleTests`。

---

## 六、连接问题

```bash
redis-cli -a <admin-pass> INFO clients                   # connected_clients / blocked_clients
redis-cli -a <admin-pass> CLIENT LIST                    # 每条连接：addr / age / cmd / lib-name
redis-cli -a <admin-pass> CONFIG GET maxclients
```

**本项目不配连接池**（`pom.xml` 无 `commons-pool2`），Lettuce 用一条多路复用的共享连接
承载所有非阻塞命令——`connected_clients` 应该约等于「应用实例数 × 1」，看到几十条
连接说明有别的东西在连（监控、其他服务、或误配了池）。

`CLIENT LIST` 里 `cmd=` 长期停在某条命令上 → 那条命令卡住了，回到 §一查慢查询。

---

## 附：排查顺序

```text
接口慢
  → SLOWLOG GET 看有没有慢命令
      → 有：看是大 key 还是热 key（§二 / §三）
      → 没有：不是 Redis 的问题，去看 DB 慢查询和外部调用（LLM / ES）
  → INFO memory 看有没有在淘汰
      → 在淘汰且 evicted_keys 持续涨：扩容或缩短 TTL
      → 没淘汰但报 OOM：有 key 漏设 TTL（§四）
```

---

## 七、实测记录：2026-09-19

上面六节是「怎么查」，这一节是「**查到了什么**」。跑一遍就有素材，不是纸上谈兵。

**环境**：Redis 6.2.14（Windows 版，服务名 `redis6`，端口 6379）

- **db1 = 本项目开发库**（`spring.data.redis.database: 1`）
- **db0 = 本项目测试库**（`src/test/resources/application.properties` 里 `database=0`，**故意隔离**，测试不污染开发数据）
- ⚠️ 这台 Redis **不止本项目在用**——db0 里有 `auth:res:roles` 这种别的项目的 key。排查时先确认 key 归属，别把别人的当自己的

### 7.1 `--bigkeys`：大 key 扫描

⚠️ **一次只扫一个 db，且默认 db0**。项目在 db1，两个都得扫，否则会得出「我的 Redis 很干净」这种假结论。

```text
db0 → Sampled 30 keys
  26 streams with 0 entries   (86.67% of keys)   ← 孤儿事件队列，见 7.4
   2 strings                  (1549 bytes)
   1 hash                     (auth:res:roles, 5 fields)
   1 zset                     (article:hot, 3 members)

db1 → Sampled 7 keys
   Biggest set:    seckill:{2}:users  50 members   ← 压测残留，本库最大的 key
   Biggest string: test:redis         5 bytes
```

**读出来的两件事**：

1. **业务缓存 key 基本都有 TTL**——服务停一会儿，db1 就只剩压测残留了。这本身就是 TTL 生效的证据（对照 §四：没淘汰却报 OOM，就是有 key 漏设 TTL）
2. **86% 的 key 数量是空的孤儿 stream**——见 7.4

`active:user:20260917` 占 **1544 bytes**，是 Bitmap 的真实体积：1544 × 8 = 12352 位，offset 到 12351，在 `UserActivityTracker.MAX_USER_OFFSET` 约束内。1 亿用户才会到 12.5 MB——**这个数字是可以当场算出来的**。

### 7.2 `SLOWLOG`：慢查询

```text
slowlog-log-slower-than = 10000   (10ms)
SLOWLOG LEN             = 1

1) 1) (integer) 0
   2) (integer) 1788759137        (2026-09-07)
   3) (integer) 82693             (82.7ms)
   4) 1) "info"
   5) "127.0.0.1:61445"
   6) "myBlog-Redis"
```

整库只有 1 条，而且是个 `INFO`（首次执行要分配内存，Windows 上偶发偏慢）。

**诚实结论**：本地流量太小，**这个样本撑不起「我优化过慢查询」**。能讲的是判读路径（§一 → §二 / §三）：

```text
接口慢 → SLOWLOG GET 看有没有慢命令
          有   → 是大 key 还是热 key
          没有 → 不是 Redis 的问题，去查 DB 慢查询和 LLM / ES 调用
```

### 7.3 AOF 崩溃恢复实测

⚠️ **没能真 `kill -9`**：Redis 以 Windows 服务（SYSTEM 权限）运行，`taskkill /F` 和 `net stop` 都返回「系统错误 5，拒绝访问」，需要管理员权限。

**改用等价验证**：`cp` 一份**未经优雅关闭的 AOF**（就是崩溃现场），用它在**另一个端口 6380** 冷启动独立实例。这比正常关闭**更严格**——正常关闭会先把 AOF 刷干净，验不出崩溃时刻的状态。

```text
redis-check-aof → size=19581217, ok_up_to=19581217,
                  ok_up_to_line=244246, diff=0
                  AOF is valid

冷启动          → DB loaded from append only file: 0.106 seconds
```

恢复后逐项核对：

| 项 | 崩溃前 | 恢复后 |
|---|---|---|
| db0 `DBSIZE` | 31 | **31** ✅ |
| db1 `DBSIZE` | 8 | **8** ✅ |
| `GET dtest:persist` | `before-crash` | **`before-crash`** ✅ |
| `TTL dtest:persist` | 3600 | **3384** ✅（扣掉流逝的 216 秒） |
| `SCARD seckill:{2}:users` | 50 | **50** ✅ |

**可讲的数字**：**19.5 MB / 24.4 万条命令，106 毫秒加载完**。
`appendfsync everysec` 的含义也实测到了：`SET` 之后隔一秒再 `cp`，那条命令**已经在文件里**——所以「最多丢 1 秒」不是背书，是量过的边界。

### 7.4 ⚠️ 实测挖出来的两个问题

#### （1）秒杀 key 全都没有 TTL

```text
TTL seckill:{2}:stock   → -1     (-1 = 存在但永不过期)
TTL seckill:{2}:users   → -1
TTL seckill:{2}:meta    → -1
TTL seckill:{2}:events  → -1
```

**为什么是问题**：`maxmemory-policy: volatile-lru` 的**前提是「允许丢失的数据都有 TTL」**——`volatile-lru` **只淘汰设了 TTL 的 key**，没 TTL 的它碰都不碰。这些 key 会一直占着内存，内存满了走 OOM 而不是淘汰。

> 这个坑本项目踩过一次：热度榜 `article:hot` 曾经是唯一无 TTL 的 key，当时补上了（现在 TTL 4730 秒 ✅）。**秒杀这套现在顶替了它的位置。**

**✅ 已修（2026-09-19）**：

- `stock` / `meta` → 预热时设 TTL = `endAt − now + 7 天`（下限就是这 7 天，**不能随剩余时间下沉**——活动已结束时剩余时间是负数，而 `EXPIRE` 收到负数会**立即删除 key**）
- `users` / `events` → 这两个是**抢购时才创建**的，预热时给它们设 TTL 是空操作（key 还不存在，`EXPIRE` 静默返回 false）。所以由抢购脚本补，判 `TTL < 0` 才设：**不能无条件 `EXPIRE`**，否则每次抢购都把过期时间往后推，等于永不过期
- `events` 给的固定 30 天而不是「到活动结束」：抢购脚本不打 DB、拿不到 `endAt`，而为算 TTL 去查一次库等于把「抢购不打 DB」这条设计破了——那条链路的性能优势全在这

> 那个「预热时设 TTL 是空操作」的缺口，是写修复时**回头看 Lua 才发现**的：`users` 由 `SADD` 创建，而 `SADD` 只在有人抢的时候发生。测试里专门留了一条断言锁它（先断言 key 此时是 `-2` 不存在），否则那条用例在修复前后都会通过。

#### （2）26 个孤儿事件队列永久残留

db0 里躺着 26 个 `seckill:{雪花ID}:events`，**全是测试留下的**（`SeckillLuaTests` / `SeckillHttpConcurrencyTests` 用生成的 ID 造活动）。

**为什么清不掉**：

- 消费者只做 `XACK → XDEL` 删**消息**，**不删 stream key 本身**
- stream key 没有 TTL（同上）
- 测试结束没有任何清理

**所以每跑一次秒杀测试就多留一个**——「86.67% 的 key」就是这么来的。

**✅ 已修（2026-09-19）**：根因不在测试的 `@AfterEach`（它其实**已经**在删 4 个 key 了），而在消费者：

```java
XGROUP CREATE {key} {group} 0 MKSTREAM    // ← MKSTREAM 的语义是「不存在就创建」
```

这个任务**每 2 秒跑一轮**，于是「测试删了 Redis key，但 DB 里的活动行还在、活动列表缓存也没过期」的那个窗口里，它会把空 stream 一遍遍建回来。

改成：**先 `hasKey` 确认 stream 存在，不存在就跳过**，并去掉 `MKSTREAM`。events 由抢购脚本的 `XADD` 创建，所以「key 不存在」就等于「没人抢过」= 没有东西要消费。

> 反向验证：把守卫和 `MKSTREAM` 都还原，8 条用例里**精确红在 `settleTaskDoesNotRecreateADeletedEventsKey` 这一条**上。

### 7.5 工具坑：Git Bash 下别把 key 当命令行参数传

`redis-cli.exe` 是原生 Windows 程序，**MSYS2 会改写命令行参数**。实测：

```bash
redis-cli -a *** -n 1 EXISTS 'seckill:{2}:stock'          # → 0  ❌ key 明明存在
printf 'EXISTS seckill:{2}:stock\n' | redis-cli -a ***    # → 1  ✅
```

**用 stdin 传命令**（或 `--eval`）。另外原生 exe 不认 `/d/...` 路径，要用 `cygpath -w` 转成 `D:\...`，否则 `redis-check-aof` 会报 "Cannot open file"。

### 7.6 无 TTL 的 key 清单（实测）

| key | 库 | 说明 |
|---|---|---|
| `seckill:{id}:stock` / `:users` / `:meta` / `:events` | db1 | 秒杀活动状态，活动结束也不回收 |
| `seckill:{雪花ID}:events` × 26 | db0 | 测试残留的孤儿队列 |
| `test:redis` | db0 + db1 | 手工测试残留，无关紧要 |

> `auth:res:roles`（db0）也无 TTL，但**不是本项目的**——排查时先确认归属。
