# 公开读接口性能基线报告（2026-10-19）

## 1. 结论

本轮在本地单实例环境完成 6 个公开读接口、4 档并发的闭环压测，共完成
**460,634 次请求**，HTTP 与业务错误均为 **0**。

50 并发下：

| 场景 | 吞吐（req/s） | P50 | P95 | P99 | 错误率 |
|---|---:|---:|---:|---:|---:|
| 文章列表 | 1,647.27 | 30ms | 40ms | 48ms | 0% |
| 文章详情 | 2,410.53 | 20ms | 31ms | 38ms | 0% |
| 热门文章 | 976.47 | 50ms | 65ms | 75ms | 0% |
| 评论列表 | 4,030.53 | 12ms | 17ms | 24ms | 0% |
| 用户搜索 | 1,865.93 | 26ms | 32ms | 43ms | 0% |
| 文章全文搜索 | 219.67 | 222ms | 294ms | 338ms | 0% |

文章全文搜索是当前最明确的性能优化对象：10 并发到 50 并发时，吞吐仅从
186.87 req/s 增至 219.67 req/s（+17.6%），P95 却从 78ms 增至 294ms（+276.9%）。

## 2. 测试口径

- 日期：2026-10-19
- 被测服务：本地 Spring Boot，`http://127.0.0.1:8080`
- 压测端与服务端：同一台 Windows 11 主机
- CPU：Intel Core i7-13650HX，14 核 20 线程
- 内存：23.8 GB
- Java：17
- Node.js：22.22.2
- MySQL：8.0.45，Hikari 最大连接数 20
- 数据规模：文章 30 篇，其中公开文章 23 篇；评论 21 条；用户 208 个
- ES 搜索索引：启动日志显示 33 个文档
- 流量模型：固定并发、闭环模型。每个 worker 等前一个请求完成后再发下一个请求
- 并发档位：1、10、30、50
- 每档预热：5 秒
- 每档正式采样：15 秒
- 单请求超时：10 秒
- 正确性判据：HTTP 200 且响应 JSON 的业务 `code=0`

闭环吞吐是“本机当前环境中每秒完成的请求数”，不是设定的恒定到达率，也不是生产流量结果。

## 3. 被测接口

| 场景 | 接口 |
|---|---|
| 文章列表 | `GET /api/articles?pageSize=10` |
| 文章详情 | `GET /api/articles/2085208555163787287` |
| 热门文章 | `GET /api/articles/hot` |
| 评论列表 | `GET /api/articles/2085208555163787287/comments?page=1&pageSize=10` |
| 用户搜索 | `GET /api/users/search?keyword=012&page=1&pageSize=10` |
| 文章全文搜索 | `GET /api/articles?keyword=Redis&pageSize=10` |

## 4. 完整结果

| 场景 | 并发 | 吞吐（req/s） | P50 | P95 | P99 | 最大耗时 | 错误 |
|---|---:|---:|---:|---:|---:|---:|---:|
| 文章列表 | 1 | 156.67 | 6ms | 10ms | 15ms | 23ms | 0 |
| 文章列表 | 10 | 1,304.67 | 8ms | 10ms | 13ms | 27ms | 0 |
| 文章列表 | 30 | 1,954.80 | 15ms | 20ms | 24ms | 33ms | 0 |
| 文章列表 | 50 | 1,647.27 | 30ms | 40ms | 48ms | 71ms | 0 |
| 文章详情 | 1 | 214.20 | 4ms | 7ms | 10ms | 14ms | 0 |
| 文章详情 | 10 | 1,745.73 | 5ms | 8ms | 11ms | 21ms | 0 |
| 文章详情 | 30 | 2,298.60 | 12ms | 21ms | 26ms | 43ms | 0 |
| 文章详情 | 50 | 2,410.53 | 20ms | 31ms | 38ms | 58ms | 0 |
| 热门文章 | 1 | 78.40 | 12ms | 19ms | 27ms | 37ms | 0 |
| 热门文章 | 10 | 660.27 | 15ms | 19ms | 26ms | 56ms | 0 |
| 热门文章 | 30 | 980.27 | 30ms | 39ms | 50ms | 204ms | 0 |
| 热门文章 | 50 | 976.47 | 50ms | 65ms | 75ms | 145ms | 0 |
| 评论列表 | 1 | 632.93 | 1ms | 3ms | 4ms | 8ms | 0 |
| 评论列表 | 10 | 2,782.33 | 3ms | 6ms | 9ms | 23ms | 0 |
| 评论列表 | 30 | 2,921.27 | 10ms | 15ms | 20ms | 56ms | 0 |
| 评论列表 | 50 | 4,030.53 | 12ms | 17ms | 24ms | 49ms | 0 |
| 用户搜索 | 1 | 239.20 | 4ms | 6ms | 9ms | 17ms | 0 |
| 用户搜索 | 10 | 1,597.20 | 6ms | 9ms | 12ms | 22ms | 0 |
| 用户搜索 | 30 | 1,569.87 | 18ms | 26ms | 35ms | 168ms | 0 |
| 用户搜索 | 50 | 1,865.93 | 26ms | 32ms | 43ms | 67ms | 0 |
| 文章全文搜索 | 1 | 35.93 | 26ms | 37ms | 45ms | 235ms | 0 |
| 文章全文搜索 | 10 | 186.87 | 50ms | 78ms | 96ms | 133ms | 0 |
| 文章全文搜索 | 30 | 199.33 | 149ms | 199ms | 229ms | 274ms | 0 |
| 文章全文搜索 | 50 | 219.67 | 222ms | 294ms | 338ms | 368ms | 0 |

## 5. 性能拐点

### 文章列表

吞吐在 30 并发达到 1,954.80 req/s，提升到 50 并发后反而下降至 1,647.27 req/s，
下降 15.7%；同时 P95 从 20ms 增至 40ms。当前环境下继续增加并发已经不能换来更高吞吐。

### 热门文章

30 并发和 50 并发吞吐分别为 980.27 与 976.47 req/s，基本持平；P95 则从 39ms
增至 65ms。热门文章链路在约 30 并发后进入吞吐平台期。

### 文章全文搜索

文章搜索需要经过 ES 检索、高亮、MySQL 权威状态回查、作者与分类组装、Redis 浏览量合并。
它在 10 并发后吞吐增长很小，长尾延迟却快速增加，是下一轮优化的首选。

本轮还观察到 `ArticleSearchService` 对每次成功搜索都输出一条 INFO 日志，搜索阶段产生约
1.27 万条 `[SEARCH-ES]` 日志。第一轮优化将高频成功明细降为 DEBUG，失败继续保留 WARN。

## 6. A/B 优化复测

> **⚠️ 2026-10-20 更正：本节 +25.8% 的结论不成立，已被同 JVM 交替 A/B 复测推翻（实测 +0.1%，在噪声内）。**
>
> 原因有三：① 两组跑在**不同 JVM** 上（A×3 → 重启 → B×3），JIT 与缓存状态不同；② A 组组内波动 34%
> （84.95 / 91.00 / 114.25），大于组间差异 25.8%，且 A 组第 2 次的 114.25 已等于 B 组中位数 114.45；
> ③ A 组 91 rps 与同期基线 219.67 rps 相差 2.4 倍，说明测量时环境本身不稳定。
>
> 完整的复测方法、数据与结论见 **§9**。以下原始记录保留作为对照，**不要再引用其中的百分比**。

### 优化内容

`ArticleSearchService` 的成功检索明细从 INFO 降为 DEBUG，失败路径仍为 WARN。
检索、排序、高亮、数据库回查和返回数据均未改变；相关日志级别由
`ArticleSearchLoggingTests` 锁定。

### 复测条件

- 接口：`GET /api/articles?keyword=Redis&pageSize=10`
- 并发：50
- 预热：5 秒
- 正式采样：20 秒
- 每组重复：3 次
- 比较指标：3 次结果的中位数
- A 组：成功日志 INFO
- B 组：成功日志 DEBUG
- 两组均移除误触发 Spring Boot debug 的临时 `DEBUG` 环境变量

### 复测结果

| 指标 | 优化前中位数 | 优化后中位数 | 变化 |
|---|---:|---:|---:|
| 吞吐 | 91.00 req/s | 114.45 req/s | **+25.8%** |
| P50 | 538ms | 435ms | **-19.1%** |
| P95 | 716ms | 507ms | **-29.2%** |
| P99 | 767ms | 546ms | **-28.8%** |
| 错误率 | 0% | 0% | 无变化 |

优化前压测专用控制台日志捕获到 7,230 条 `[SEARCH-ES]` 成功明细；优化后为 0 条 INFO
成功明细。这个结果说明日志 I/O 确实是本机高并发搜索链路的可观测开销之一。

### 结果边界

这是同一台 Windows 主机上的单实例闭环压测，样本数据只有 23 篇公开文章，不能直接外推为
生产容量或公网 QPS。由于本轮基线本身存在明显抖动，简历中建议使用“约 26%”和“P95
约下降 29%”这类保守表达，并注明是本地离线压测；不要写成生产环境长期稳定指标。

如果继续优化，下一候选是把文章列表/搜索结果中的逐篇 Redis 浏览量读取改为批量 `MGET`，
但应单独设计并发前后正确性测试，不能把这轮日志优化结果与下一轮改动混写。

## 7. 可复现命令

语法检查：

```powershell
node --check deploy/api-stress/read-baseline.mjs
```

完整基线：

```powershell
node deploy/api-stress/read-baseline.mjs `
  --levels 1,10,30,50 `
  --warmup 5 `
  --duration 15 `
  --timeout-ms 10000
```

只复测文章全文搜索：

```powershell
node deploy/api-stress/read-baseline.mjs `
  --only article-search `
  --levels 1,10,30,50 `
  --warmup 5 `
  --duration 15 `
  --timeout-ms 10000
```

原始结果：

`deploy/api-stress/results/read-baseline-2026-10-19T15-18-48-355Z.json`

## 8. 简历口径边界（2026-10-20 修订）

本轮数据可以写成“本地单实例离线压测”，不能写成生产 QPS。

**日志降噪不能写成性能收益**：2026-10-20 复测显示它对吞吐没有可测量影响（+0.1%，在噪声内，见 §9）。
日志级别改动本身仍有价值（同口径日志量 44.2MB → 1.17MB），但那是可观测性收益，不是吞吐收益。

可以使用的表述：

> 基于 Node.js 构建固定并发的接口压测脚本，覆盖文章列表、详情、评论与 ES 全文搜索共 6 个接口、
> 4 档并发，在本地单实例 50 并发下完成 46 万次请求且业务错误率为 0；据基线定位 ES 全文搜索为
> 并发上升时延迟恶化最快的链路（50 并发 P95 294ms），并用同 JVM 交替 A/B 验证日志降噪不是该链路的瓶颈。

**已证伪、禁止使用的表述**：吞吐提升约 26% / P95 下降约 29% / “三次中位数对比”。

---

## 9. 2026-10-20 复测：A/B 结论更正与 health 修复

### 9.1 原 A/B 设计的三个问题

| 问题 | 证据 | 后果 |
|---|---|---|
| 两组跑在不同 JVM | A×3 → 重启 → B×3 | JIT / 连接池 / 缓存状态不同，单变量不成立 |
| 组内波动 > 组间差异 | A 组 84.95 / 91.00 / 114.25（极差 34%），B 组 109.95 / 114.45 / 124.00 | 3 次取中位数无法支撑 25.8% 的结论 |
| 与基线相差 2.4 倍 | A 组 91.00 vs 同期基线 219.67 | 测量时环境本身不稳定 |

另外，A 组期间日志 44.2MB 中 MyBatis SQL 占 43,604 行，SEARCH-ES 只占 7,230 行（约 2~3%）——
即使日志 I/O 真有影响，也不该归因到一条 120 字节的检索日志上。

### 9.2 复测方法

- **同一 JVM 内切换**：通过 `/actuator/loggers` 动态设置 `ArticleSearchService` 的级别，不重启进程
  （启动时用命令行参数临时暴露端点：`--management.endpoints.web.exposure.include=health,loggers`）
- **交替顺序**：奇数轮 A→B，偶数轮 B→A，消除顺序效应
- **一次性预热 60 秒**，之后每组采样 60 秒，共 5 轮
- **关闭第二变量**：启动参数 `--mybatis-plus.configuration.log-impl=...NoLoggingImpl`，压测期间 SQL 日志 0 行
- **环境快照**：压测脚本自动记录 CPU / 核数 / 内存 / java 进程数 / 被测源码指纹
  （`ArticleSearchService.java` sha256 前 12 位 `28ba32d601db`）/ git commit（当前无 git 仓库，记 null）

### 9.3 复测结果

| 轮次 | A（`ArticleSearchService`=DEBUG，日志开） | B（INFO，日志关） |
|---|---:|---:|
| 1 | 217.62 | 217.73 |
| 2 | 199.12 | 217.82 |
| 3 | 196.97 | 216.93 |
| 4 | 236.92 | 231.90 |
| 5 | 233.68 | 220.70 |
| **中位数** | **217.62 req/s** | **217.82 req/s** |
| 组内极差 | 18.4% | 6.9% |

- 组间差异 **+0.1%**，小于组内极差 18.4%，脚本判定 `conclusive=false`。
- 结论：**日志级别对该接口吞吐没有可测量的影响**。原 +25.8% 来自环境噪声。

### 9.4 基线本身可复现

今天 B 组（INFO，即线上实际形态）中位数 **217.82 req/s**，昨天基线 **219.67 req/s**，相差 0.8%——
**§1~§5 的基线数据可信**，“全文搜索是并发上升时延迟恶化最快的链路”这一结论保留。
昨晚 A 组的 91 rps 属于环境异常状态（JIT 冷 + SQL 日志 + 后台多进程），不是新代码的真实水平。

### 9.5 副产品一：客户端上限

新增 `probe-not-found` 场景（`/api/__probe__`，只验证 HTTP 响应、不解析业务码）：

| 场景 | 并发 | 吞吐 | P50 | P95 | 错误 |
|---|---:|---:|---:|---:|---:|
| 404 探针 | 50 | 5,439.8 req/s | 9ms | 12ms | 0% |

客户端（单进程 Node）可跑到 5,400+ req/s，**说明原基线里 4,030 req/s 的评论列表不是客户端瓶颈**。

### 9.6 副产品二：`/actuator/health` 定时炸弹（已修复）

**现象**：单次 health 请求 **16.6 秒**；50 并发探针只有 **7.5 rps**，100 个请求超时。

**根因**：Spring Boot 自动注册的 `MailHealthIndicator` 每次探活都调 `JavaMailSender.testConnection()`
连 SMTP，本地网络下单次 16~17 秒（connectiontimeout 5s 叠加重试）。health 是同步聚合，探活会把
Tomcat 线程池占住——同一时段正常 `GET /api/articles` 被拖到 1.1~1.5 秒（`[PERF-HTTP] slow` 日志可查）。

**修复**：`application.yml` 增加

```yaml
management:
  health:
    mail:
      enabled: false
```

**理由**：邮件是辅助功能（忘记密码），不应决定整站健康状态；SMTP 可用性看发信失败日志，
发信本身仍受 `spring.mail.properties` 的 5s 超时保护。

**验证**（2026-10-20）：

| 指标 | 修复前 | 修复后 |
|---|---:|---:|
| 单次 health 延迟 | 16,600ms | **14ms** |
| health 50 并发吞吐 | 7.5 req/s | **438.8 req/s** |
| health 探针错误数 | 100（超时） | **0** |
| `MailHealthIndicator` WARN 日志 | 每请求 1 条 | **0** |

### 9.7 复现命令

```powershell
# 启动（关闭 SQL 日志 + 临时暴露 loggers，用于同 JVM A/B）
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl --management.endpoints.web.exposure.include=health,loggers"

# 客户端上限
node deploy/api-stress/read-baseline.mjs --only probe-not-found --levels 50 --duration 20 --warmup 10

# 同 JVM 交替 A/B
node deploy/api-stress/read-baseline.mjs --only article-search --levels 50 --duration 60 --warmup 60 `
  --ab-logger com.hailin.blogsystem.search.ArticleSearchService --ab-levels DEBUG,INFO --ab-rounds 5
```

原始结果：`deploy/api-stress/results/ab-logger-2026-10-20T15-13-25-023Z.json`（A/B）、
`deploy/api-stress/results/baseline-2026-10-20T15-00-57-733Z.json`（404 探针）、
`deploy/api-stress/results/baseline-2026-10-20T15-01-36-564Z.json`（修复前 health）、
`deploy/api-stress/results/baseline-2026-10-20T15-17-58-726Z.json`（修复后 health）。
